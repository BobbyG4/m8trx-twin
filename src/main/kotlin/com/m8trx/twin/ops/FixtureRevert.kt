package com.m8trx.twin.ops

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.http.ConnectHttp
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate

/**
 * `./gradlew connectFixtureRevert` — put back the fixtures the old `fixmoves` step should never have moved (Bob, 2026-10-14:
 * "those are big huge gondolas"). ONE-SHOT, run on the box on Bob's go; dry run by default.
 *
 * Targets: `M8TRX_REVERT_TARGETS` (default the two Bob saw in Denver: `dec-us-denver:GF-R2-U2,dec-us-denver:GB-R3-U5` —
 * "Gondola R2 Front U2" and "Gondola R3 Back U5"), plus, with `M8TRX_REVERT_ALL_LEDGER=true`, every zone in the box's
 * ledger (`.twin-state/fixmoves/applied.csv`) whose type is not movable and whose net shift is not zero — the old step moved
 * gondolas and wall bays at every store, three a week.
 *
 * The position to restore = the fixture's geometry before `M8TRX_REVERT_BEFORE` (default 2026-10-13T13:57:00Z = 22:57 KST,
 * the first twin move Bob saw). Three sources, in this order:
 *  1. `M8TRX_REVERT_GEOMETRY_<SLUG>_<CODE>` (e.g. `M8TRX_REVERT_GEOMETRY_DEC_US_DENVER_GF_R2_U2`) — a GeoJSON Polygon (or its
 *     `coordinates`), from a SELECT on mother the Coordinator runs: it wins over the others;
 *  2. the twin's own snapshot of the platform geometry taken just before its FIRST move of that zone
 *     (`.twin-state/fixmoves/origin.json`, written by the old step on the box);
 *  3. `zone_history` through Hasura as the store's manager (READ only): the latest row recorded before the cutoff. If the
 *     `frontEnd` role cannot read `zone_history`, that is logged and the other sources decide.
 * When two sources disagree by more than 1 mm the zone is NOT moved (refused, both printed) unless (1) was given.
 *
 * The write is the same zone update the moves use, `PUT /zones/{id}` with the restored ring and
 * `reason: "twin: revert implausible move"`; the ledger gets a compensating `RV-` line so the net shift of the zone is zero.
 * A zone already at its restored position is left alone. `M8TRX_REVERT_LIVE=true` sends; `M8TRX_REVERT_READ=true` lets a
 * dry run read the platform (zone_by_pk + zone_history) to show the current position and the third source.
 */
object FixtureRevert {
    private val log = LoggerFactory.getLogger("com.m8trx.twin.ops.FixtureRevert")
    const val REASON = "twin: revert implausible move"
    const val DEFAULT_TARGETS = "dec-us-denver:GF-R2-U2,dec-us-denver:GB-R3-U5"
    const val DEFAULT_BEFORE = "2026-10-13T13:57:00Z"

    const val HISTORY_GQL = "query TwinZoneBefore(\$zone: uuid!, \$before: timestamptz!) { zone_history(where: {zone_id: {_eq: \$zone}, " +
        "recorded_at: {_lt: \$before}}, order_by: {recorded_at: desc}, limit: 1) { geometry geometry_type recorded_at change_type } }"

    data class Target(val slug: String, val code: String, val zoneId: String)

    data class Summary(val targets: Int, val reverted: Int, val inPlace: Int, val refused: Int, val err: Int, val bodies: List<Map<String, Any?>>)

    fun envKey(slug: String, code: String) = "M8TRX_REVERT_GEOMETRY_${OpsConfig.envKey(slug)}_${code.uppercase().replace('-', '_')}"

    /** A GeoJSON Polygon object or a bare `coordinates` array → polygon coordinates. */
    fun parseCoords(text: String): List<List<List<Double>>> {
        val n = ConnectMappers.camel.readTree(text)
        val c = if (n.isObject) n.path("coordinates") else n
        return ConnectMappers.camel.convertValue(c, object : com.fasterxml.jackson.core.type.TypeReference<List<List<List<Double>>>>() {})
    }

    fun ringsAgree(a: List<List<List<Double>>>, b: List<List<List<Double>>>, tolMm: Double = 1.0): Boolean {
        val ra = PolyGeom.ring(a[0])
        val rb = PolyGeom.ring(b[0])
        return ra.size == rb.size && ra.zip(rb).all { (p, q) -> Math.abs(p.x - q.x) <= tolMm && Math.abs(p.y - q.y) <= tolMm }
    }

    fun run(
        cfg: OpsConfig,
        http: ConnectHttp = ConnectHttp(),
        env: (String) -> String? = {
            System.getenv(it)?.takeIf { v -> v.isNotBlank() }
        },
    ): Summary {
        val mapper = ConnectMappers.camel
        val ops = OpsClient(cfg.apiBase, cfg.hasuraUrl, cfg::requirePassword, http, cfg.stateDir)
        val dir = cfg.stateDir.resolve("fixmoves")
        val appliedFile = dir.resolve("applied.csv")
        val origins: Map<String, Any?> = dir.resolve("origin.json").let { if (Files.exists(it)) mapper.readValue(it.toFile()) else emptyMap() }
        val applied = StoreStock.readLines(appliedFile).filter { !it.startsWith("extId") }.map { it.split(",") }
        val before = Instant.parse(env("M8TRX_REVERT_BEFORE") ?: DEFAULT_BEFORE)
        val readPlatform = cfg.live || env("M8TRX_REVERT_READ")?.toBoolean() == true

        val targets = LinkedHashMap<String, Target>()
        (env("M8TRX_REVERT_TARGETS") ?: DEFAULT_TARGETS).split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { t ->
            val (slug, code) = t.split(":", limit = 2)
            val zone = cfg.keys.store(slug).byCode[code] ?: error("$code is not a zone of $slug in the key export")
            targets[zone.zoneId] = Target(slug, code, zone.zoneId)
        }
        if (env("M8TRX_REVERT_ALL_LEDGER")?.toBoolean() == true) {
            val net = applied.groupBy { it[1] }.mapValues { (_, ls) -> ls.sumOf { it[3].toDouble() } to ls.sumOf { it[4].toDouble() } }
            for (s in cfg.keys.stores) {
                val floor = FixtureMoves.Floor.load(cfg.chainDir, s.slug)
                s.fixtures.filter { (net[it.zoneId]?.let { (dx, dy) -> dx != 0.0 || dy != 0.0 } == true) && !FixtureMoves.isMovable(floor, it.code) }
                    .forEach { targets.putIfAbsent(it.zoneId, Target(s.slug, it.code, it.zoneId)) }
            }
        }
        log.info("═══ fixture revert ═══ targets={} before={} live={} platform-read={}", targets.size, before, cfg.live, readPlatform)

        var reverted = 0
        var inPlace = 0
        var refused = 0
        var err = 0
        val bodies = ArrayList<Map<String, Any?>>()
        for (t in targets.values) {
            val net = applied.filter { it[1] == t.zoneId }.let { ls -> ls.sumOf { it[3].toDouble() } to ls.sumOf { it[4].toDouble() } }
            log.info("  {} {} zone {} · twin ledger net shift ({}, {}) mm", t.slug, t.code, t.zoneId, net.first, net.second)
            try {
                val given = env(envKey(t.slug, t.code))?.let(::parseCoords)
                val snapshot = origins[t.zoneId]?.let {
                    runCatching {
                        mapper.convertValue(it, object : com.fasterxml.jackson.core.type.TypeReference<List<List<List<Double>>>>() {})
                    }.getOrNull()
                }
                var jwt: String? = null
                var current: JsonNode? = null
                var history: List<List<List<Double>>>? = null
                if (readPlatform) {
                    jwt = ops.jwt(cfg.actorEmail(t.slug)) ?: error("login refused for ${cfg.actorEmail(t.slug)}")
                    current = ops.query(jwt, FixtureMoveDrive.ZONE_GQL, mapOf("id" to t.zoneId)).path("zone_by_pk")
                    history = runCatching {
                        ops.query(
                            jwt,
                            HISTORY_GQL,
                            mapOf("zone" to t.zoneId, "before" to before.toString()),
                        ).path("zone_history").firstOrNull()?.let { h ->
                            log.info(
                                "    zone_history row before the cutoff: recorded_at={} change_type={}",
                                h.path("recorded_at").asText(),
                                h.path("change_type").asText(),
                            )
                            if (h.path("geometry").path("type").asText() == "Polygon") parseCoords(h.path("geometry").toString()) else null
                        }
                    }.onFailure {
                        log.warn("    zone_history not readable as {} ({}) — the other sources decide", cfg.actorEmail(t.slug), it.message)
                    }.getOrNull()
                }
                log.info(
                    "    sources: argument={} snapshot(origin.json)={} zone_history={}",
                    given?.get(0)?.take(2) ?: "-",
                    snapshot?.get(0)?.take(2) ?: "-",
                    history?.get(0)?.take(2) ?: (if (readPlatform) "none" else "not read (dry)"),
                )
                val target: List<List<List<Double>>> = given ?: run {
                    val found = listOfNotNull(snapshot, history)
                    if (found.isEmpty()) {
                        log.error("    REFUSED — no source for the prior position (pass {} from a SELECT on mother)", envKey(t.slug, t.code))
                        refused++
                        return@run null
                    }
                    if (found.size == 2 && !ringsAgree(found[0], found[1])) {
                        log.error("    REFUSED — the snapshot and zone_history disagree; pass {} to decide", envKey(t.slug, t.code))
                        refused++
                        return@run null
                    }
                    found[0]
                } ?: continue
                // cross-check only: the committed layout is the geometry the chain was seeded with (RE-RESEED v2 verified it
                // byte-for-byte), so a restored ring that is not the layout's means the fixture was moved before the twin was
                val seeded = FixtureMoves.Floor.load(cfg.chainDir, t.slug).fixtures[t.code]?.footprint
                if (seeded != null) {
                    val r = PolyGeom.ring(target[0])
                    val same = Math.abs(r.sumOf { it.x } / r.size - seeded.cx) <= 1.0 && Math.abs(r.sumOf { it.y } / r.size - seeded.cy) <= 1.0
                    log.info(
                        "    layout (seed) centroid ({}, {}) — the restored ring {} it",
                        seeded.cx.toLong(),
                        seeded.cy.toLong(),
                        if (same) "matches" else "DIFFERS from",
                    )
                }
                val body = FixtureMoveDrive.polygonBody(target, REASON)
                bodies += body
                if (current != null && current.path("geometry").path("type").asText() == "Polygon") {
                    val now = parseCoords(current.path("geometry").toString())
                    if (ringsAgree(now, target)) {
                        log.info("    already at the restored position — left alone")
                        inPlace++
                        continue
                    }
                    val c0 = PolyGeom.ring(now[0])
                    val c1 = PolyGeom.ring(target[0])
                    log.info(
                        "    now ({}, {}) → restored ({}, {}) mm (centroids)",
                        c0.sumOf { it.x }.div(c0.size).toLong(),
                        c0.sumOf { it.y }.div(c0.size).toLong(),
                        c1.sumOf { it.x }.div(c1.size).toLong(),
                        c1.sumOf { it.y }.div(c1.size).toLong(),
                    )
                }
                log.info("    {} PUT /zones/{} {}", if (cfg.live) "[LIVE]" else "[DRY]", t.zoneId, mapper.writeValueAsString(body).take(300))
                if (!cfg.live) continue
                val r = ops.send(jwt!!, "PUT", "/zones/${t.zoneId}", body)
                if (r is ConnectResponse.Ok && ops.json(r).path("updated").asBoolean(false)) {
                    reverted++
                    val ring0 = current?.let { PolyGeom.outerRing(it.path("geometry")) }
                    val ring1 = PolyGeom.ring(target[0])
                    val dx = if (ring0 != null) ring1.sumOf { it.x } / ring1.size - ring0.sumOf { it.x } / ring0.size else -net.first
                    val dy = if (ring0 != null) ring1.sumOf { it.y } / ring1.size - ring0.sumOf { it.y } / ring0.size else -net.second
                    Files.createDirectories(dir)
                    if (!Files.exists(appliedFile)) StoreStock.appendLines(appliedFile, listOf("extId,zoneId,date,dx,dy"))
                    StoreStock.appendLines(appliedFile, listOf("RV-${t.slug}-${t.code}-${LocalDate.now()},${t.zoneId},${LocalDate.now()},$dx,$dy"))
                    log.info("    reverted ✓ (ledger RV line: {}, {})", dx, dy)
                } else {
                    err++
                    log.error("    PUT refused: {}", ops.describe(r))
                }
            } catch (e: Exception) {
                err++
                log.error("    FAILED — {}: {}", e.javaClass.simpleName, e.message)
            }
        }
        log.info(
            "SIM-RUN fixrevert targets={} reverted={} in_place={} refused={} err={} live={}",
            targets.size,
            reverted,
            inPlace,
            refused,
            err,
            cfg.live,
        )
        return Summary(targets.size, reverted, inPlace, refused, err, bodies)
    }
}

fun main() {
    val cfg = OpsConfig.fromEnv("M8TRX_REVERT")
    val s = FixtureRevert.run(cfg)
    if (cfg.live && (s.err > 0 || s.refused > 0)) error("the revert left ${s.refused} refused / ${s.err} failed — see the log")
}
