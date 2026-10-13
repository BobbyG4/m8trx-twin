package com.m8trx.twin.ops

import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.http.ConnectHttp
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * `./gradlew connectFixtureMoves` — the `fixmoves` step: a handful of fixture moves per store per week ([FixtureMoves]),
 * each through the platform's zone update, `PUT /api/v2/zones/{id}` `{geometry, geometryType:"polygon"}` (gate
 * `zone:update` SITE, the site-manager kit's spatial-editor; `@Audited zone_updated`). The zone table's own trigger
 * (`trg_zone_history`) writes the `zone_history` row, so "what moved this week" and the layout diff read a real move.
 * Never psql; never the Hasura mutation.
 *
 * The geometry is read first from the platform (Hasura `zone_by_pk`, READ only, as the same user) and kept as the
 * fixture's ORIGIN the first time the twin touches it (`.twin-state/fixmoves/origin.json`). Every PUT is absolute:
 * origin + the sum of the twin's applied offsets — so a re-sent move lands on the same polygon, never one step further.
 * `properties` is not sent (the update leaves it untouched; sending it would replace the fixture's code). A fixture whose
 * platform geometry is not a polygon (21 circles chain-wide) is skipped and logged.
 *
 * A move is only sent when it fits: [FixtureMoves.choose] tries the planned shift, then the other directions and 250 mm, against
 * every other fixture of the space as the platform holds it now (read once per store, `space_by_pk` + `zone`) and the space
 * boundary, and takes the first that overlaps nothing and stays inside; none fits = skipped with a log line, retried next pass.
 * The ledger records the shift actually applied.
 *
 * SAFE BY DEFAULT — dry run. `M8TRX_FIXMOVES_LIVE=true` sends. Env: [OpsConfig] (prefix `M8TRX_FIXMOVES`) plus
 * `M8TRX_FIXMOVES_PER_WEEK` (3). Ledger `.twin-state/fixmoves/applied.csv` (`extId,zoneId,date,dx,dy`): an applied
 * move is never re-sent; the week's plan is computed from the offsets applied BEFORE that week, so it is the same plan
 * whichever day of the week it is read on.
 */
class FixtureMoveDrive(private val cfg: OpsConfig, private val http: ConnectHttp = ConnectHttp()) {
    private val log = LoggerFactory.getLogger(FixtureMoveDrive::class.java)
    private val ops = OpsClient(cfg.apiBase, cfg.hasuraUrl, cfg::requirePassword, http, cfg.stateDir)
    private val dir: Path = cfg.stateDir.resolve("fixmoves")
    private val appliedFile = dir.resolve("applied.csv")
    private val originFile = dir.resolve("origin.json")
    private val mapper = ConnectMappers.camel

    /** One space as the platform holds it now: every fixture's polygon (circles as 32-gons) by zone id, and the boundary ring if it has one. */
    class SpaceGeo(val fixtures: MutableMap<String, List<PolyGeom.P>>, val boundary: List<PolyGeom.P>?)

    data class Applied(val extId: String, val zoneId: String, val date: LocalDate, val dx: Double, val dy: Double)

    data class Summary(val planned: Int, val sent: Int, val skipped: Int, val held: Int, val err: Int, val shapes: List<String>)

    fun readApplied(): List<Applied> = StoreStock.readLines(appliedFile).filter { !it.startsWith("extId") }.map {
        val c = it.split(",")
        Applied(c[0], c[1], LocalDate.parse(c[2]), c[3].toDouble(), c[4].toDouble())
    }

    private fun readSpace(jwt: String, spaceId: String): SpaceGeo {
        val d = ops.query(jwt, SPACE_GQL, mapOf("space" to spaceId, "zt" to "fixture"))
        val fx = LinkedHashMap<String, List<PolyGeom.P>>()
        d.path("zone").forEach { z ->
            val ring = when (z.path("geometry_type").asText()) {
                "polygon" -> PolyGeom.outerRing(z.path("geometry"))

                "circle" -> z.path("geometry").path("coordinates").let { c ->
                    val pr = z.path("properties")
                    val rx = pr.path("radiusX").asDouble(0.0)
                    val ry = pr.path("radiusY").asDouble(rx)
                    if (c.isArray && c.size() >= 2 && rx > 0) PolyGeom.circle(c[0].asDouble(), c[1].asDouble(), rx, ry) else null
                }

                else -> null
            }
            if (ring != null) fx[z.path("id").asText()] = ring
        }
        check(fx.isNotEmpty()) { "the platform returned no fixture polygons for space $spaceId — refusing to move blind" }
        val boundary = PolyGeom.outerRing(d.path("space_by_pk").path("boundary"))
        if (boundary == null) log.warn("    space {} has no polygon boundary — the room edge is not checked", spaceId)
        return SpaceGeo(fx, boundary)
    }

    fun run(): Summary {
        val perWeek = System.getenv("M8TRX_FIXMOVES_PER_WEEK")?.toIntOrNull() ?: 3
        val applied = readApplied()
        val appliedIds = applied.map { it.extId }.toSet()
        val monday = FixtureMoves.weekStart(cfg.date)
        val before = applied.filter { it.date.isBefore(monday) }.groupBy { it.zoneId }
            .mapValues { (_, l) -> l.sumOf { it.dx } to l.sumOf { it.dy } }

        @Suppress("UNCHECKED_CAST")
        val origins: MutableMap<String, Any?> = if (Files.exists(originFile)) mapper.readValue(originFile.toFile()) else LinkedHashMap()
        var planned = 0
        var sent = 0
        var skipped = 0
        var held = 0
        var err = 0
        val shapes = ArrayList<String>()
        for (slug in cfg.stores) {
            val moves = FixtureMoves.forDate(cfg.keys.store(slug), cfg.date, cfg.seed, perWeek, before)
            planned += moves.size
            // a store that fails (login, a refused read, a dropped connection) fails alone; the pass goes on to the next store
            try {
                var geo: SpaceGeo? = null
                for (m in moves) {
                    log.info("  {} {} {} {} dx={} dy={} mm", if (cfg.live) "[LIVE]" else "[DRY]", m.extId, m.code, m.zoneId, m.dxMm, m.dyMm)
                    if (shapes.isEmpty()) {
                        shapes += "GraphQL zone_by_pk(id: \"${m.zoneId}\") { id geometry geometry_type properties }"
                        shapes += "GraphQL space_by_pk(id: <space>) { boundary } · zone(space_id = <space>, zone_type = fixture) { id geometry }"
                        shapes +=
                            "PUT /zones/${m.zoneId} {\"geometryType\":\"polygon\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[x+${m.dxMm},y+${m.dyMm},z],…]]}}"
                    }
                    if (!cfg.live) continue
                    if (m.extId in appliedIds) {
                        log.info("    already applied (ledger) — skipped")
                        continue
                    }
                    val refusal = cfg.pastRefusal(slug)
                    if (refusal != null) {
                        log.warn("    HELD — {}", refusal)
                        held++
                        continue
                    }
                    val email = cfg.actorEmail(slug)
                    val jwt = ops.jwt(email)
                    if (jwt == null) {
                        log.error("    login refused for {} — this store is skipped for the pass", email)
                        err++
                        break
                    }
                    val zone = ops.query(jwt, ZONE_GQL, mapOf("id" to m.zoneId)).path("zone_by_pk")
                    if (zone.isMissingNode || zone.isNull) {
                        log.error("    zone {} not visible to {} — skipped", m.zoneId, email)
                        err++
                        continue
                    }
                    if (zone.path("geometry_type").asText() != "polygon" || zone.path("geometry").path("type").asText() != "Polygon") {
                        log.info("    {} is a {} — only polygons are moved; skipped", m.code, zone.path("geometry_type").asText())
                        skipped++
                        continue
                    }
                    val origin = origins.getOrPut(m.zoneId) { mapper.convertValue(zone.path("geometry").path("coordinates"), List::class.java) }
                    val coords: List<List<List<Double>>> = mapper.convertValue(
                        origin,
                        object : com.fasterxml.jackson.core.type.TypeReference<List<List<List<Double>>>>() {},
                    )
                    val prior = applied.filter { it.zoneId == m.zoneId }
                    val priorOffset = prior.sumOf { it.dx } to prior.sumOf { it.dy }
                    // the room as it is now: every other fixture's polygon and the space boundary (one read per store, kept current)
                    val g = geo ?: readSpace(jwt, cfg.keys.store(slug).salesFloorSpaceId).also { geo = it }
                    val pick = FixtureMoves.choose(
                        m,
                        PolyGeom.ring(coords[0]),
                        priorOffset,
                        g.fixtures.filterKeys { it != m.zoneId }.values,
                        g.boundary,
                    )
                    if (pick == null) {
                        log.info(
                            "    {} SKIPPED — no shift of 250-500 mm in any direction keeps it inside the room and off its neighbors " +
                                "(planned dx={} dy={})",
                            m.code,
                            m.dxMm,
                            m.dyMm,
                        )
                        skipped++
                        continue
                    }
                    val (cdx, cdy) = pick
                    if (cdx != m.dxMm || cdy != m.dyMm) {
                        log.info("    {} planned shift would overlap a neighbor or leave the room — using dx={} dy={} instead", m.code, cdx, cdy)
                    }
                    val moved = FixtureMoves.translate(coords, priorOffset.first + cdx, priorOffset.second + cdy)
                    val r = ops.send(jwt, "PUT", "/zones/${m.zoneId}", putBody(moved))
                    if (r is ConnectResponse.Ok && ops.json(r).path("updated").asBoolean(false)) {
                        sent++
                        g.fixtures[m.zoneId] = PolyGeom.ring(moved[0])
                        Files.createDirectories(dir)
                        mapper.writerWithDefaultPrettyPrinter().writeValue(originFile.toFile(), origins)
                        if (!Files.exists(appliedFile)) StoreStock.appendLines(appliedFile, listOf("extId,zoneId,date,dx,dy"))
                        StoreStock.appendLines(appliedFile, listOf("${m.extId},${m.zoneId},${m.date},$cdx,$cdy"))
                    } else {
                        err++
                        log.error("    PUT /zones/{} refused: {}", m.zoneId, ops.describe(r))
                    }
                }
            } catch (e: Exception) {
                err++
                log.error("  {} FAILED — {}: {} (the pass goes on to the next store)", slug, e.javaClass.simpleName, e.message)
            }
        }
        log.info(
            "SIM-RUN fixmoves date={} seed={} planned={} sent_ok={} skipped={} held={} sent_err={}",
            cfg.date,
            cfg.seed,
            planned,
            sent,
            skipped,
            held,
            err,
        )
        return Summary(planned, sent, skipped, held, err, shapes)
    }

    companion object {
        const val ZONE_GQL = "query GetZoneById(\$id: uuid!) " +
            "{ zone_by_pk(id: \$id) { id name geometry geometry_type properties zone_type space_id } }"

        // zone_type is a custom scalar in the v2 schema: the value goes in as a variable, never as a bare literal
        const val SPACE_GQL = "query TwinSpaceFixtures(\$space: uuid!, \$zt: zone_type!) { space_by_pk(id: \$space) { id boundary } " +
            "zone(where: {space_id: {_eq: \$space}, zone_type: {_eq: \$zt}}, limit: 3000) { id geometry geometry_type properties } }"

        fun putBody(coords: List<List<List<Double>>>): Map<String, Any?> =
            mapOf("geometryType" to "polygon", "geometry" to mapOf("type" to "Polygon", "coordinates" to coords))
    }
}

fun main() {
    val cfg = OpsConfig.fromEnv("M8TRX_FIXMOVES")
    val log = LoggerFactory.getLogger("com.m8trx.twin.ops.FixtureMoveDrive")
    log.info("Fixture-move drive → date={} seed={} stores={} live={}", cfg.date, cfg.seed, cfg.stores.size, cfg.live)
    val s = FixtureMoveDrive(cfg).run()
    s.shapes.forEach { log.info("  shape: {}", it) }
    if (cfg.live && s.err > 0) error("a fixture move was refused — see the log")
}
