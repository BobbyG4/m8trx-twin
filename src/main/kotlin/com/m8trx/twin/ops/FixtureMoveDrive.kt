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
 * SAFE BY DEFAULT — dry run. `M8TRX_FIXMOVES_LIVE=true` sends. Env: [OpsConfig] (prefix `M8TRX_FIXMOVES`) plus
 * `M8TRX_FIXMOVES_PER_WEEK` (3). Ledger `.twin-state/fixmoves/applied.csv` (`extId,zoneId,date,dx,dy`): an applied
 * move is never re-sent; the week's plan is computed from the offsets applied BEFORE that week, so it is the same plan
 * whichever day of the week it is read on.
 */
class FixtureMoveDrive(private val cfg: OpsConfig, private val http: ConnectHttp = ConnectHttp()) {
    private val log = LoggerFactory.getLogger(FixtureMoveDrive::class.java)
    private val ops = OpsClient(cfg.apiBase, cfg.hasuraUrl, cfg::requirePassword, http)
    private val dir: Path = cfg.stateDir.resolve("fixmoves")
    private val appliedFile = dir.resolve("applied.csv")
    private val originFile = dir.resolve("origin.json")
    private val mapper = ConnectMappers.camel

    data class Applied(val extId: String, val zoneId: String, val date: LocalDate, val dx: Double, val dy: Double)

    data class Summary(val planned: Int, val sent: Int, val skipped: Int, val held: Int, val err: Int, val shapes: List<String>)

    fun readApplied(): List<Applied> = StoreStock.readLines(appliedFile).filter { !it.startsWith("extId") }.map {
        val c = it.split(",")
        Applied(c[0], c[1], LocalDate.parse(c[2]), c[3].toDouble(), c[4].toDouble())
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
            for (m in moves) {
                log.info("  {} {} {} {} dx={} dy={} mm", if (cfg.live) "[LIVE]" else "[DRY]", m.extId, m.code, m.zoneId, m.dxMm, m.dyMm)
                if (shapes.isEmpty()) {
                    shapes += "GraphQL zone_by_pk(id: \"${m.zoneId}\") { id geometry geometry_type properties }"
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
                    log.error("    login refused for {}", email)
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
                val dx = prior.sumOf { it.dx } + m.dxMm
                val dy = prior.sumOf { it.dy } + m.dyMm
                val body = putBody(FixtureMoves.translate(coords, dx, dy))
                val r = ops.send(jwt, "PUT", "/zones/${m.zoneId}", body)
                if (r is ConnectResponse.Ok && ops.json(r).path("updated").asBoolean(false)) {
                    sent++
                    Files.createDirectories(dir)
                    mapper.writerWithDefaultPrettyPrinter().writeValue(originFile.toFile(), origins)
                    if (!Files.exists(appliedFile)) StoreStock.appendLines(appliedFile, listOf("extId,zoneId,date,dx,dy"))
                    StoreStock.appendLines(appliedFile, listOf("${m.extId},${m.zoneId},${m.date},${m.dxMm},${m.dyMm}"))
                } else {
                    err++
                    log.error("    PUT /zones/{} refused: {}", m.zoneId, ops.describe(r))
                }
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
