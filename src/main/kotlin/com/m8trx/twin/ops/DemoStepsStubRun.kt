package com.m8trx.twin.ops

import com.m8trx.twin.connect.DatedStream
import com.m8trx.twin.connect.http.ConnectHttp
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.history.ChainKeys
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

/**
 * `./gradlew demoStepsStubRun` — the three platform-facing steps' LIVE code paths (login, reads, every write, ledgers)
 * driven against a local stub HTTP server on 127.0.0.1 instead of the platform. Nothing leaves the machine; the state
 * dir is a temp dir. Prints every route hit with its count and the first body sent to it — the request shapes — and
 * fails if a step errors or a route the step must call was never called.
 *
 * What the stub plays that matters to the review's findings: the platform's `thing_location` keeps every 4th wall unit in
 * the Back Room (the twin's own record puts them on the wall) — the stock step must read none of them at a wall; the
 * space holds a neighbor fixture in the +x direction of every moved fixture — the fixmoves step must route around it;
 * and `/auth/login` hands out JWTs with an `exp` — a second driver on the same state dir must log nobody in again. A second
 * stub then plays the rate limit (429) and a rejected cached token against the login policy of [OpsClient].
 */
fun main() {
    val chain = Path.of("reference/data/chain")
    val state = Files.createTempDirectory("twin-stub-state")
    val mapper = ConnectMappers.camel
    val calls = LinkedHashMap<String, MutableList<String>>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val today = LocalDate.parse("2026-10-14")
    val now = Instant.parse("2026-10-14T23:00:00Z")
    val keys = ChainKeys.load(chain.resolve(ChainKeys.DEFAULT_PATH.fileName))
    val siteIds = DatedStream.readSiteIds(chain.resolve("site_ids.csv"))
    val backRoomZone = "bbbbbbbb-0000-0000-0000-000000000001"
    val backroom = HashSet<String>() // every unit the stub platform keeps in the Back Room (stockroom + the "drifted" wall units)
    val located = HashMap<String, List<Pair<String, String>>>() // site id -> (zone id, epc)
    for (slug in listOf("dec-us-denver", "dec-fr-lyon")) {
        val zoneOf = keys.store(slug).fixtures.associate { it.code to it.zoneId }
        var i = 0
        located[siteIds.getValue(slug)] = DatedStream.loadEpcRows(chain.resolve("stores/$slug/epcs.csv")).map { r ->
            val z = zoneOf[r.fixture]
            if (z == null || i++ % 4 == 0) {
                backroom += r.epc
                backRoomZone to r.epc
            } else {
                z to r.epc
            }
        }
    }
    val pulled = HashMap<String, MutableSet<String>>() // countId -> every EPC pulled
    val puts = ArrayList<com.fasterxml.jackson.databind.JsonNode>()
    val putIds = ArrayList<String>()
    val loginAs = ArrayList<String>()
    // the stub platform's fixture zones for the two proof stores, from their committed layouts (circles as GeoJSON Points with
    // the platform's properties incl. `code`), updated by every PUT so a later read sees the floor as the moves left it
    val zoneGeo = HashMap<String, MutableMap<String, Any?>>() // zone id -> {id, geometry_type, geometry, properties}
    val spaceZones = HashMap<String, MutableList<String>>() // space id -> zone ids
    for (slug in listOf("dec-us-denver", "dec-fr-lyon")) {
        val store = keys.store(slug)
        val floor = FixtureMoves.Floor.load(chain, slug)
        store.fixtures.forEach { z ->
            val f = floor.fixtures[z.code] ?: return@forEach
            val fp = f.footprint
            zoneGeo[z.zoneId] = if (fp is FixtureMoves.Footprint.Circle) {
                linkedMapOf(
                    "id" to z.zoneId,
                    "geometry_type" to "circle",
                    "geometry" to mapOf("type" to "Point", "coordinates" to listOf(fp.cx, fp.cy, 0.0)),
                    "properties" to
                        mapOf("code" to z.code, "centerX" to fp.cx, "centerY" to fp.cy, "radiusX" to fp.r, "radiusY" to fp.r, "rotation" to 0),
                )
            } else {
                linkedMapOf(
                    "id" to z.zoneId,
                    "geometry_type" to "polygon",
                    "geometry" to mapOf(
                        "type" to "Polygon",
                        "coordinates" to listOf(
                            (fp.ring() + fp.ring().first()).map {
                                listOf(it.x, it.y, 0.0)
                            },
                        ),
                    ),
                    "properties" to mapOf("code" to z.code),
                )
            }
            spaceZones.getOrPut(store.salesFloorSpaceId) { ArrayList() } += z.zoneId
        }
    }
    val exp = Instant.now().plusSeconds(10 * 86_400L).epochSecond

    fun route(ex: HttpExchange): Pair<Int, Any> {
        val path = ex.requestURI.path
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val m = ex.requestMethod
        val key = "$m " + path.replace(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "{id}")
        calls.getOrPut(key) { ArrayList() } += body.take(600) + if (ex.requestURI.query != null) " ?${ex.requestURI.query}" else ""
        val node = if (body.isNotBlank()) mapper.readTree(body) else null
        return when {
            path.endsWith("/auth/login") -> {
                val who = node!!.path("email").asText()
                loginAs += who
                200 to mapOf("accessToken" to mkJwt(who, exp), "capabilities" to emptyList<String>())
            }

            path.endsWith("/graphql") && body.contains("TwinStockLocations") -> {
                val v = node!!.path("variables")
                val rows = located.getValue(v.path("site").asText())
                val page = rows.drop(v.path("offset").asInt()).take(v.path("limit").asInt())
                200 to mapOf(
                    "data" to mapOf(
                        "thing_location" to page.map { (z, e) ->
                            mapOf("zone_id" to z, "item" to mapOf("item_identifiers" to listOf(mapOf("value" to e))))
                        },
                    ),
                )
            }

            path.endsWith("/graphql") && body.contains("TwinSpaceFixtures") -> {
                val space = node!!.path("variables").path("space").asText()
                200 to mapOf(
                    "data" to mapOf(
                        "space_by_pk" to mapOf(
                            "id" to space,
                            "boundary" to mapOf(
                                "type" to "Polygon",
                                "coordinates" to listOf(
                                    listOf(
                                        listOf(0.0, 0.0, 0.0),
                                        listOf(28500.0, 0.0, 0.0),
                                        listOf(28500.0, 23000.0, 0.0),
                                        listOf(0.0, 23000.0, 0.0),
                                        listOf(0.0, 0.0, 0.0),
                                    ),
                                ),
                            ),
                        ),
                        "zone" to spaceZones[space].orEmpty().map { zoneGeo.getValue(it) },
                    ),
                )
            }

            path.endsWith("/graphql") && body.contains("zone_by_pk") ->
                200 to mapOf("data" to mapOf("zone_by_pk" to zoneGeo[node!!.path("variables").path("id").asText()]))

            path.endsWith("/graphql") && body.contains("task(") -> 200 to mapOf(
                "data" to mapOf(
                    "task" to listOf(
                        mapOf(
                            "id" to UUID.randomUUID().toString(),
                            "type" to "replenishment_request",
                            "status" to "overdue",
                            "title" to "old drift",
                            "zone_id" to null,
                            "assigned_to" to null,
                            "created_at" to "2026-10-02T15:00:00.123+00:00",
                        ),
                        mapOf(
                            "id" to UUID.randomUUID().toString(),
                            "type" to "replenishment_request",
                            "status" to "open",
                            "title" to "today's drift",
                            "zone_id" to null,
                            "assigned_to" to null,
                            "created_at" to "2026-10-14T16:00:00+00:00",
                        ),
                    ),
                ),
            )

            m == "POST" && path.endsWith("/count") ->
                201 to
                    mapOf("countId" to node!!.path("countId").asText(), "owner" to "cloud", "natsUrl" to null, "status" to "open")

            path.endsWith("/count/pulls") -> {
                pulled.getOrPut(node!!.path("countId").asText()) { HashSet() } += node.path("tags").map { it.path("identifier").asText() }
                202 to mapOf("replay" to false)
            }

            path.endsWith("/observations") -> 201 to mapOf("id" to UUID.randomUUID().toString())

            path.endsWith("/close") && path.contains("/count/") -> 202 to mapOf("alreadyClosed" to false, "status" to "submitted")

            path.endsWith("/exceptions") ->
                200 to
                    mapOf("reconciled" to true, "totals" to mapOf("counted" to 0, "under" to 3, "over" to 1, "misplaced" to 1))

            m == "PUT" && path.contains("/zones/") -> {
                val id = path.substringAfterLast('/')
                puts.add(node!!)
                putIds.add(id)
                val z = zoneGeo.getValue(id)
                z["geometry"] = mapper.convertValue(node.path("geometry"), Map::class.java)
                if (node.has("properties")) z["properties"] = mapper.convertValue(node.path("properties"), Map::class.java)
                200 to mapOf("id" to id, "updated" to true)
            }

            path.endsWith("/tasks/assignees") -> 200 to mapOf(
                "people" to listOf(
                    mapOf("userId" to "11111111-1111-1111-1111-111111111111", "name" to "Jennifer Anderson", "roles" to listOf("site-manager")),
                    mapOf("userId" to "22222222-2222-2222-2222-222222222222", "name" to "Adam Garcia", "roles" to listOf("Floor Associate")),
                    mapOf("userId" to "33333333-3333-3333-3333-333333333333", "name" to "Lisa Lewis", "roles" to listOf("Floor Associate")),
                ),
            )

            m == "POST" && path.endsWith("/tasks") -> 200 to mapOf("task_id" to UUID.randomUUID().toString(), "status" to "assigned")

            path.contains("/tasks/") -> 200 to mapOf("status" to "ok")

            else -> 404 to mapOf("error" to "NOT_STUBBED", "message" to "$m $path")
        }
    }
    server.createContext("/") { ex ->
        val (code, payload) = runCatching { route(ex) }.getOrElse { 500 to mapOf("error" to "STUB", "message" to it.toString()) }
        val bytes = mapper.writeValueAsBytes(payload)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
    server.start()
    val base = "http://127.0.0.1:${server.address.port}"
    try {
        // a tiny sent sales plan so the task step has walls that sold down
        val wall = DatedStream.loadEpcRows(chain.resolve("stores/dec-us-denver/epcs.csv")).filter {
            !it.backOfHouse
        }.groupBy { it.fixture }.entries.sortedByDescending { it.value.size }.take(3)
        val plan = state.resolve("stream").resolve("twin-day-20261014-20261016.plan.csv")
        Files.createDirectories(plan.parent)
        Files.writeString(
            plan,
            "sale_id,store,at,epcs\n" +
                wall.mapIndexed { i, (_, rows) ->
                    "S$i,dec-us-denver,2026-10-14T18:00:00Z,${rows.take(4 + i * 3).joinToString(";") { it.epc }}"
                }.joinToString("\n") +
                "\n",
        )
        Files.writeString(plan.resolveSibling("twin-day-20261014-20261016.sent"), "S0\nS1\nS2\n")

        fun cfg(date: LocalDate, stores: List<String>) = OpsConfig(
            apiBase = "$base/api/v2", hasuraUrl = "$base/graphql", chainDir = chain, stateDir = state, date = date, seed = 20261016L,
            live = true, allowFuture = true, allowPast = true, stores = stores, password = "stub-password", now = now,
        )
        val stock = StockDrive(cfg(today, listOf("dec-us-denver", "dec-fr-lyon"))).run { false }
        val again = StockDrive(cfg(today, listOf("dec-us-denver", "dec-fr-lyon"))).run { false }
        val fix = (12..17).map { FixtureMoveDrive(cfg(LocalDate.of(2026, 10, it), listOf("dec-us-denver", "dec-fr-lyon"))).run() }
        val tasks = TaskDrive(cfg(today, listOf("dec-us-denver"))).run()
        val tasks2 = TaskDrive(cfg(today, listOf("dec-us-denver"))).run()

        println("\n── routes hit (count · first body) ──")
        calls.forEach { (k, v) -> println("%-42s %5d  %s".format(k, v.size, v.first().take(420))) }
        println(
            "\nstock: $stock\nstock re-run: closed=${again.closed} (ledger)\nfixmoves: ${fix.sumOf {
                it.sent
            }} sent of ${fix.sumOf { it.planned }} planned, errors ${fix.sumOf { it.err }}\ntasks: $tasks",
        )
        check(stock.err == 0 && stock.closed == stock.counts && stock.counts > 0) { "stock did not close every count" }
        check(again.closed == 0) { "a re-run re-sent a closed count" }
        check(
            fix.sumOf {
                it.err
            } == 0 && fix.sumOf { it.sent } == 12,
        ) { "fixmoves: expected 1 per store per day (2 stores x 6 days), got ${fix.map { it.sent }}" }
        println("tasks, second pass of the same day: $tasks2")
        check(tasks2.cancelled == 0 && tasks.assigned + tasks.created + tasks2.assigned + tasks2.created <= 5) {
            "a second pass exceeded the day's target"
        }
        check(tasks.err == 0 && tasks.cancelled == 1 && tasks.assigned == 1 && tasks.created >= 1 && tasks.completed >= 1) { "tasks: $tasks" }
        listOf(
            "POST /api/v2/auth/login", "POST /api/v2/count", "POST /api/v2/count/pulls", "POST /api/v2/count/{id}/close",
            "GET /api/v2/count/{id}/exceptions", "POST /api/v2/stocktake/sessions/{id}/observations",
            "PUT /api/v2/zones/{id}", "POST /graphql",
            "GET /api/v2/tasks/assignees", "POST /api/v2/tasks", "POST /api/v2/tasks/{id}/cancel",
            "POST /api/v2/tasks/{id}/reassign", "POST /api/v2/tasks/{id}/start", "POST /api/v2/tasks/{id}/complete",
        ).forEach {
            check(it in calls) { "never called: $it" }
        }
        // H1 — the platform's own locations decide the reads: a unit the platform keeps in the Back Room is read in a count
        // only as one of its 1-2 deliberate `over` units, never as a wall's stock (the twin's record would have read ~25 % of them)
        check("POST /api/v2/inventory/items/details" !in calls) { "the stock step still reads /inventory/items/details" }
        check(calls.getValue("POST /graphql").any { it.contains("TwinStockLocations") && it.contains("thing_location") }) { "no thing_location read" }
        check(pulled.isNotEmpty()) { "no pulls recorded" }
        pulled.forEach { (id, epcs) ->
            val fromBack = epcs.count { it in backroom }
            check(fromBack <= 2) { "count $id read $fromBack back-room units (only its 1-2 deliberate over units may come from there)" }
        }
        println(
            "stock reads: ${pulled.size} counts; back-room units read per count (<= 2 allowed): ${pulled.values.map { v ->
                v.count { it in backroom }
            }}",
        )
        // v2 — every PUT moves a movable fixture (a circle here), inside its own region, clear of the floor, with a reason, and keeps
        // the zone's properties (code, radius); the ledger holds the shift actually applied
        check(puts.size == fix.sumOf { it.sent }) { "PUT count ${puts.size} != sent" }
        val codeOf = listOf("dec-us-denver", "dec-fr-lyon").flatMap { s -> keys.store(s).fixtures.map { it.zoneId to (s to it.code) } }.toMap()
        puts.zip(putIds).forEach { (b, id) ->
            val (slug, code) = codeOf.getValue(id)
            val floor = FixtureMoves.Floor.load(chain, slug)
            check(FixtureMoves.isMovable(floor, code)) { "$code (${floor.fixtures[code]?.category}) is not movable" }
            check(b.path("reason").asText().startsWith("twin: ")) { "a PUT without a reason" }
            check(b.path("geometryType").asText() == "circle" && b.path("geometry").path("type").asText() == "Point") { "circle body shape: $b" }
            check(b.path("properties").path("code").asText() == code && b.path("properties").path("radiusX").asDouble() > 0) { "properties lost: $b" }
            val c = b.path("geometry").path("coordinates")
            check(b.path("properties").path("centerX").asDouble() == c[0].asDouble()) { "centerX not moved with the Point" }
            val moved = FixtureMoves.Footprint.Circle(c[0].asDouble(), c[1].asDouble(), b.path("properties").path("radiusX").asDouble())
            check(PolyGeom.within(moved.ring(), floor.regions.getValue(floor.fixtures.getValue(code).region!!).ring)) { "$code left its region" }
        }
        val ledgerLines = StoreStock.readLines(state.resolve("fixmoves").resolve("applied.csv")).filter { !it.startsWith("extId") }
        check(ledgerLines.size == puts.size) { "ledger lines ${ledgerLines.size} != PUTs ${puts.size}" }
        ledgerLines.forEach { l ->
            val c = l.split(",")
            check(Math.hypot(c[3].toDouble(), c[4].toDouble()) >= FixtureMoves.MIN_SHIFT_MM) { "a ledger line without a real shift: $l" }
        }
        // the floor after every move: no two fixtures of a store overlap
        spaceZones.values.forEach { ids ->
            val rings = ids.mapNotNull { FixtureMoveDrive.footprintOf(mapper.valueToTree(zoneGeo.getValue(it)))?.ring() }
            for (i in rings.indices) {
                for (j in i + 1 until rings.size) {
                    check(!PolyGeom.overlaps(rings[i], rings[j])) {
                        "two fixtures overlap after the moves"
                    }
                }
            }
        }
        println("fixmoves: ${puts.size} PUTs, all movable circles, each inside its region with a reason, properties kept, no overlap after")
        // the revert (dry, platform read on): the two Denver gondolas, restored from the snapshot the moves would have taken
        val gf = keys.store("dec-us-denver").byCode.getValue("GF-R2-U2").zoneId
        val gb = keys.store("dec-us-denver").byCode.getValue("GB-R3-U5").zoneId
        val snap = mapper.convertValue(zoneGeo.getValue(gf)["geometry"], Map::class.java)["coordinates"]
        zoneGeo.getValue(gf)["geometry"] = mapOf(
            "type" to "Polygon",
            "coordinates" to FixtureMoves.translate(
                mapper.convertValue(snap, object : com.fasterxml.jackson.core.type.TypeReference<List<List<List<Double>>>>() {}),
                312.0,
                0.0,
            ),
        )
        val origins = mapper.readValue(state.resolve("fixmoves").resolve("origin.json").toFile(), Map::class.java).toMutableMap()
        origins[gf] = snap
        mapper.writeValue(state.resolve("fixmoves").resolve("origin.json").toFile(), origins)
        val rv = FixtureRevert.run(cfg(today, listOf("dec-us-denver")).copy(live = true)) { k ->
            if (k ==
                "M8TRX_REVERT_TARGETS"
            ) {
                "dec-us-denver:GF-R2-U2"
            } else {
                null
            }
        }
        check(rv.reverted == 1 && rv.err == 0 && rv.refused == 0) { "revert: $rv" }
        val back = mapper.convertValue(zoneGeo.getValue(gf)["geometry"], Map::class.java)["coordinates"]
        check(back == snap) { "GF-R2-U2 is not back at its snapshot" }
        check(puts.last().path("reason").asText() == FixtureRevert.REASON) { "the revert PUT carries no reason" }
        val rv2 = FixtureRevert.run(cfg(today, listOf("dec-us-denver")).copy(live = true)) { k ->
            if (k ==
                "M8TRX_REVERT_TARGETS"
            ) {
                "dec-us-denver:GF-R2-U2,dec-us-denver:GB-R3-U5"
            } else {
                null
            }
        }
        check(rv2.inPlace == 1 && rv2.refused == 1 && rv2.reverted == 0) { "second revert: GF in place, GB has no source → refused, got $rv2" }
        check(gb.isNotEmpty())
        println(
            "fixrevert: GF-R2-U2 put back from the snapshot (reason '${FixtureRevert.REASON}'); a re-run leaves it; GB-R3-U5 without a source is refused",
        )
        // H3 — one login per person for the whole run (the JWTs are kept in state/ops/jwt, reused by every later driver)
        check(loginAs.size == loginAs.toSet().size) { "logins: ${loginAs.size} calls for ${loginAs.toSet().size} people — the cache did not hold" }
        val jwtDir = state.resolve("ops").resolve("jwt")
        val files = Files.list(jwtDir).use { it.toList() }
        check(files.size == loginAs.toSet().size) { "cached ${files.size} tokens for ${loginAs.toSet().size} logins" }
        files.forEach { f ->
            check(Files.getPosixFilePermissions(f) == PosixFilePermissions.fromString("rw-------")) { "$f is not mode 600" }
        }
        check(Files.getPosixFilePermissions(jwtDir) == PosixFilePermissions.fromString("rwx------")) { "the jwt dir is not mode 700" }
        println(
            "logins: ${loginAs.size} calls for ${loginAs.toSet().size} people over 5 driver runs (all five shared one state dir); cache files mode 600",
        )
        loginPolicy()
        val applied = StoreStock.readLines(state.resolve("fixmoves").resolve("applied.csv")).size - 1
        println("state: ${StoreStock.readLines(StoreStock.missingFile(state)).size} missing EPCs recorded · $applied fixture moves in the ledger")
        println("demoStepsStubRun: OK")
    } finally {
        server.stop(0)
    }
}

private fun mkJwt(sub: String, expEpoch: Long, nonce: String = ""): String {
    val claims = ConnectMappers.camel.writeValueAsString(
        mapOf(
            "sub" to sub,
            "n" to nonce,
            "tid" to "tenant-stub",
            "exp" to expEpoch,
            "https://hasura.io/jwt/claims" to mapOf("x-hasura-default-role" to "frontEnd"),
        ),
    )
    return "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray()) + ".s"
}

/**
 * The login policy of [OpsClient] against a stub that plays the platform's rate limit and a token it no longer accepts.
 * The clock is fake and a "sleep" only moves it, so the 60-second waits cost nothing and the sliding window is exact.
 */
private fun loginPolicy() {
    val mapper = ConnectMappers.camel
    val t0 = Instant.parse("2026-10-14T00:00:00Z")
    var nowMs = t0.toEpochMilli()
    val sleeps = ArrayList<Long>()
    val stamps = ArrayList<Long>() // fake-clock time of every login that reached the stub
    var limitFirst = 0 // answer the next N logins with 429
    var serial = 0
    val revoked = HashSet<String>() // tokens the stub no longer accepts
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { ex ->
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val (code, payload) = when {
            ex.requestURI.path.endsWith("/auth/login") -> {
                stamps += nowMs
                if (limitFirst > 0) {
                    limitFirst--
                    429 to mapOf("error" to "RATE_LIMITED", "message" to "too many logins")
                } else {
                    val who = mapper.readTree(body).path("email").asText()
                    200 to mapOf("accessToken" to mkJwt(who, t0.epochSecond + 10 * 86_400L, "n${++serial}"))
                }
            }

            else -> {
                val tok = ex.requestHeaders.getFirst("Authorization").removePrefix("Bearer ")
                if (tok in revoked) 401 to mapOf("error" to "UNAUTHORIZED") else 200 to mapOf("ok" to true, "token" to tok)
            }
        }
        val bytes = mapper.writeValueAsBytes(payload)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
        val base = "http://127.0.0.1:${server.address.port}/api/v2"
        val state = Files.createTempDirectory("twin-login-policy")
        fun client() = OpsClient(
            base,
            "$base/graphql",
            { "pw" },
            ConnectHttp(),
            state,
            clock = { Instant.ofEpochMilli(nowMs) },
            sleepMs = { ms ->
                sleeps += ms
                nowMs += ms
            },
        )

        // a 429 is waited out once (60 s) and the login retried; the answer is a token
        limitFirst = 1
        val c1 = client()
        val t = c1.jwt("a@x.test")
        check(t != null && sleeps.contains(OpsClient.RATE_LIMIT_WAIT_MS)) { "429 once: no 60 s wait and retry ($sleeps)" }
        // a second 429 gives up on THAT login (null) and is not remembered: the next ask logs in again
        limitFirst = 2
        val none = c1.jwt("b@x.test")
        check(none == null) { "429 twice must give null" }
        check(c1.jwt("b@x.test") != null) { "a 429 was cached — the next ask did not try again" }
        // pacing: the stub never sees more than 6 logins inside any 60 s of the fake clock
        stamps.clear()
        val c2 = client()
        (1..13).forEach { c2.jwt("p$it@x.test") }
        val worst = stamps.indices.maxOf { i -> stamps.count { it in stamps[i] until stamps[i] + 60_000L } }
        check(worst <= LoginPacer.MAX_PER_MINUTE) { "pacing: $worst logins inside one minute" }
        // reuse: a new client on the same state dir logs nobody in (the tokens are on disk, valid for days)
        stamps.clear()
        val c3 = client()
        check(c3.jwt("a@x.test") == t && c3.jwt("p4@x.test") != null && c3.logins == 0 && stamps.isEmpty()) {
            "cached login not reused (${c3.logins} logins)"
        }
        // a cached token the platform rejects (401) is dropped; the call is retried once on a fresh login
        revoked += t!!
        val c4 = client()
        val tok = c4.jwt("a@x.test")!!
        val r = c4.send(tok, "GET", "/ping")
        check(r is com.m8trx.twin.connect.http.ConnectResponse.Ok && c4.logins == 1) { "401 was not retried on a fresh login (${c4.logins})" }
        check(c4.jwt("a@x.test") != t) { "the rejected token is still cached" }
        println(
            "login policy: 429 -> wait 60 s, retry once · never cached · max $worst logins/min over 13 people · " +
                "reused with 0 logins · rejected cached token refreshed once",
        )
    } finally {
        server.stop(0)
    }
}
