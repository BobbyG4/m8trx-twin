package com.m8trx.twin.ops

import com.m8trx.twin.connect.DatedStream
import com.m8trx.twin.connect.model.ConnectMappers
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

/**
 * `./gradlew demoStepsStubRun` — the three platform-facing steps' LIVE code paths (login, reads, every write, ledgers)
 * driven against a local stub HTTP server on 127.0.0.1 instead of the platform. Nothing leaves the machine; the state
 * dir is a temp dir. Prints every route hit with its count and the first body sent to it — the request shapes — and
 * fails if a step errors or a route the step must call was never called.
 */
fun main() {
    val chain = Path.of("reference/data/chain")
    val state = Files.createTempDirectory("twin-stub-state")
    val mapper = ConnectMappers.camel
    val calls = LinkedHashMap<String, MutableList<String>>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val claims = mapper.writeValueAsString(
        mapOf(
            "tid" to "tenant-stub",
            "https://hasura.io/jwt/claims" to mapOf("x-hasura-default-role" to "frontEnd"),
        ),
    )
    val jwt = "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray()) + ".s"
    val today = LocalDate.parse("2026-10-14")
    val now = Instant.parse("2026-10-14T23:00:00Z")

    fun route(ex: HttpExchange): Pair<Int, Any> {
        val path = ex.requestURI.path
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val m = ex.requestMethod
        val key = "$m " + path.replace(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "{id}")
        calls.getOrPut(key) { ArrayList() } += body.take(600) + if (ex.requestURI.query != null) " ?${ex.requestURI.query}" else ""
        val node = if (body.isNotBlank()) mapper.readTree(body) else null
        return when {
            path.endsWith("/auth/login") -> 200 to mapOf("accessToken" to jwt, "capabilities" to emptyList<String>())

            path.endsWith("/graphql") && body.contains("zone_by_pk") -> 200 to mapOf(
                "data" to mapOf(
                    "zone_by_pk" to mapOf(
                        "id" to node!!.path("variables").path("id").asText(),
                        "geometry_type" to "polygon",
                        "geometry" to
                            mapOf(
                                "type" to "Polygon",
                                "coordinates" to
                                    listOf(
                                        listOf(
                                            listOf(1000.0, 1000.0, 0.0),
                                            listOf(2200.0, 1000.0, 0.0),
                                            listOf(2200.0, 1600.0, 0.0),
                                            listOf(1000.0, 1600.0, 0.0),
                                            listOf(1000.0, 1000.0, 0.0),
                                        ),
                                    ),
                            ),
                    ),
                ),
            )

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

            path.endsWith("/inventory/items/details") -> 200 to node!!.path("epcs").map { mapOf("epc" to it.asText(), "state" to "in_stock") }

            m == "POST" && path.endsWith("/count") ->
                201 to
                    mapOf("countId" to node!!.path("countId").asText(), "owner" to "cloud", "natsUrl" to null, "status" to "open")

            path.endsWith("/count/pulls") -> 202 to mapOf("replay" to false)

            path.endsWith("/observations") -> 201 to mapOf("id" to UUID.randomUUID().toString())

            path.endsWith("/close") && path.contains("/count/") -> 202 to mapOf("alreadyClosed" to false, "status" to "submitted")

            path.endsWith("/exceptions") ->
                200 to
                    mapOf("reconciled" to true, "totals" to mapOf("counted" to 0, "under" to 3, "over" to 1, "misplaced" to 1))

            m == "PUT" && path.contains("/zones/") -> 200 to mapOf("id" to path.substringAfterLast('/'), "updated" to true)

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
            live = true, allowFuture = true, allowPast = true, stores = stores, password = "stub-password", dataBearer = "stub-bearer", now = now,
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
        check(fix.sumOf { it.err } == 0 && fix.sumOf { it.sent } == 6) { "fixmoves: expected 3 per store for the week" }
        println("tasks, second pass of the same day: $tasks2")
        check(tasks2.cancelled == 0 && tasks.assigned + tasks.created + tasks2.assigned + tasks2.created <= 5) {
            "a second pass exceeded the day's target"
        }
        check(tasks.err == 0 && tasks.cancelled == 1 && tasks.assigned == 1 && tasks.created >= 1 && tasks.completed >= 1) { "tasks: $tasks" }
        listOf(
            "POST /api/v2/auth/login", "POST /api/v2/count", "POST /api/v2/count/pulls", "POST /api/v2/count/{id}/close",
            "GET /api/v2/count/{id}/exceptions", "POST /api/v2/stocktake/sessions/{id}/observations",
            "POST /api/v2/inventory/items/details", "PUT /api/v2/zones/{id}",
            "GET /api/v2/tasks/assignees", "POST /api/v2/tasks", "POST /api/v2/tasks/{id}/cancel",
            "POST /api/v2/tasks/{id}/reassign", "POST /api/v2/tasks/{id}/start", "POST /api/v2/tasks/{id}/complete",
        ).forEach {
            check(it in calls) { "never called: $it" }
        }
        val applied = StoreStock.readLines(state.resolve("fixmoves").resolve("applied.csv")).size - 1
        println("state: ${StoreStock.readLines(StoreStock.missingFile(state)).size} missing EPCs recorded · $applied fixture moves in the ledger")
        println("demoStepsStubRun: OK")
    } finally {
        server.stop(0)
    }
}
