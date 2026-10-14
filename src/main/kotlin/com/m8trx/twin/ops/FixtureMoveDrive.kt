package com.m8trx.twin.ops

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.http.ConnectHttp
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * `./gradlew connectFixtureMoves` — the `fixmoves` step: the moves a store would make ([FixtureMoves]), each through the
 * platform's zone update, `PUT /api/v2/zones/{id}` (gate `zone:update` SITE, the site-manager kit's spatial editor;
 * `@Audited zone_updated`), as the store's manager. The zone table's own trigger (`trg_zone_history`) writes the
 * `zone_history` row. Never psql; never the Hasura mutation.
 *
 * Every store, every store-local day: `M8TRX_FIXMOVES_PER_DAY` (1, at most 2) movable fixtures (a promotion table, a round
 * rack) moved to a free slot of their own region. A move is sent only on the store's own date, from 07:30 store-local; an
 * earlier pass of that day HOLDS it, a later date is HELD as not yet the store's day, a past date is refused (the platform
 * stamps `zone_history.recorded_at` now()). The geometry is read from the platform first (Hasura `zone_by_pk`, READ only)
 * and kept as the fixture's ORIGIN the first time the twin touches it (`.twin-state/fixmoves/origin.json`); the room is
 * read once per store (`space_by_pk` + every fixture zone) so the destination is checked against the floor as it stands.
 * The body carries `reason`. Circles go as `geometryType: circle` with a GeoJSON Point and the zone's own `properties` with
 * `centerX/centerY` updated (every other key kept, so the fixture code is untouched); polygons as before.
 *
 * SAFE BY DEFAULT — dry run: lists the day's moves per store (movable types only) with the destination computed against the
 * committed layout, offline. `M8TRX_FIXMOVES_LIVE=true` sends. Ledger `.twin-state/fixmoves/applied.csv`
 * (`extId,zoneId,date,dx,dy`): an applied move is never re-sent.
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

    data class Summary(
        val planned: Int,
        val sent: Int,
        val skipped: Int,
        val held: Int,
        val err: Int,
        val shapes: List<String>,
        val listing: List<String> = emptyList(),
    )

    fun readApplied(): List<Applied> = StoreStock.readLines(appliedFile).filter { !it.startsWith("extId") }.map {
        val c = it.split(",")
        Applied(c[0], c[1], LocalDate.parse(c[2]), c[3].toDouble(), c[4].toDouble())
    }

    private fun readSpace(jwt: String, spaceId: String): SpaceGeo {
        val d = ops.query(jwt, SPACE_GQL, mapOf("space" to spaceId, "zt" to "fixture"))
        val fx = LinkedHashMap<String, List<PolyGeom.P>>()
        d.path("zone").forEach { z -> footprintOf(z)?.let { fx[z.path("id").asText()] = it.ring() } }
        check(fx.isNotEmpty()) { "the platform returned no fixture polygons for space $spaceId — refusing to move blind" }
        val boundary = PolyGeom.outerRing(d.path("space_by_pk").path("boundary"))
        if (boundary == null) log.warn("    space {} has no polygon boundary — the room edge is the layout's", spaceId)
        return SpaceGeo(fx, boundary)
    }

    /**
     * Why a LIVE move of [slug] on the configured date waits (null = it goes now): only on the store's own local date, from
     * 07:30 local. `allowFuture` lifts both time holds; `allowPast` the refusal of an earlier date.
     */
    fun holdReason(slug: String): String? {
        cfg.pastRefusal(slug)?.let { return it }
        val local = cfg.now.atZone(cfg.keys.store(slug).zone)
        if (cfg.allowFuture) return null
        if (cfg.date.isAfter(local.toLocalDate())) return "not yet $slug's day (its local date is ${local.toLocalDate()})"
        if (cfg.date == local.toLocalDate() && local.toLocalTime().isBefore(FixtureMoves.MOVE_AT)) {
            return "before ${FixtureMoves.MOVE_AT} store-local ($slug is at ${local.toLocalTime().withNano(0)})"
        }
        return null
    }

    fun run(): Summary {
        val perDay = (System.getenv("M8TRX_FIXMOVES_PER_DAY")?.toIntOrNull() ?: 1).coerceIn(0, FixtureMoves.MAX_PER_DAY)
        val applied = readApplied()
        val appliedIds = applied.map { it.extId }.toSet()

        @Suppress("UNCHECKED_CAST")
        val origins: MutableMap<String, Any?> = if (Files.exists(originFile)) mapper.readValue(originFile.toFile()) else LinkedHashMap()
        var planned = 0
        var sent = 0
        var skipped = 0
        var held = 0
        var err = 0
        val shapes = ArrayList<String>()
        val listing = ArrayList<String>()
        for (slug in cfg.stores) {
            val floor = FixtureMoves.Floor.load(cfg.chainDir, slug)
            val store = cfg.keys.store(slug)
            val candidates = FixtureMoves.candidates(store, floor, cfg.date, cfg.seed)
            if (candidates.isEmpty()) {
                log.info("  {} has no movable fixture ({}) — nothing to move", slug, FixtureMoves.MOVABLE_CATEGORIES)
                continue
            }
            planned += minOf(perDay, candidates.size)
            // a store that fails (login, a refused read, a dropped connection) fails alone; the pass goes on to the next store
            try {
                if (!cfg.live) {
                    // offline: the day's moves against the committed layout (the live run uses the platform's floor)
                    val others = floor.fixtures.mapValues { it.value.footprint.ring() }.toMutableMap()
                    val day = FixtureMoves.planDay(candidates, perDay, {
                        floor.fixtures.getValue(it.code).footprint
                    }, others, { it.code }, floor, null, cfg.seed)
                    day.forEach { (m, dx, dy) -> listing += dryLine(m, floor.fixtures.getValue(m.code).footprint, dx, dy) }
                    if (day.size <
                        minOf(perDay, candidates.size)
                    ) {
                        listing +=
                            "$slug ${cfg.date}: only ${day.size} of ${minOf(perDay, candidates.size)} found a free slot"
                    }
                    continue
                }
                val ymd = cfg.date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                var done = appliedIds.count { it.startsWith("FM2-$slug-$ymd-") }
                if (done >= perDay) {
                    log.info("  {} {}: {} move(s) already made today (ledger) — done", slug, cfg.date, done)
                    continue
                }
                val hold = holdReason(slug)
                if (hold != null) {
                    log.info("  {} {}: HELD — {}", slug, cfg.date, hold)
                    held += perDay - done
                    continue
                }
                val email = cfg.actorEmail(slug)
                val jwt = ops.jwt(email)
                if (jwt == null) {
                    log.error("  {}: login refused for {} — this store is skipped for the pass", slug, email)
                    err++
                    continue
                }
                // the room as it is now: every fixture and the space boundary, read once, kept current as moves land
                val g = readSpace(jwt, store.salesFloorSpaceId)
                for (m in candidates) {
                    if (done >= perDay) break
                    if (m.extId in appliedIds) continue
                    log.info("  [LIVE] {} {} ({}) in {} — {}", m.extId, m.code, m.category, m.region, m.reason)
                    val zone = ops.query(jwt, ZONE_GQL, mapOf("id" to m.zoneId)).path("zone_by_pk")
                    if (zone.isMissingNode || zone.isNull) {
                        log.error("    zone {} not visible to {} — skipped", m.zoneId, email)
                        err++
                        continue
                    }
                    val current = footprintOf(zone)
                    if (current == null) {
                        log.info(
                            "    {} has a {} geometry the drive cannot move — next candidate",
                            m.code,
                            zone.path("geometry").path("type").asText(),
                        )
                        skipped++
                        continue
                    }
                    val d = FixtureMoves.destination(m, current, floor, g.fixtures.filterKeys { it != m.zoneId }.values, g.boundary, cfg.seed)
                    if (d == null) {
                        log.info(
                            "    {} has no free slot in {} ({} mm aisle, gate clear) — next candidate",
                            m.code,
                            m.region,
                            FixtureMoves.CLEARANCE_MM,
                        )
                        skipped++
                        continue
                    }
                    origins.putIfAbsent(m.zoneId, mapper.convertValue(zone.path("geometry").path("coordinates"), Any::class.java))
                    val (dx, dy) = d
                    val body = putBody(zone, dx, dy, m.reason)
                    if (shapes.isEmpty()) {
                        shapes += "GraphQL zone_by_pk(id: \"${m.zoneId}\") { id geometry geometry_type properties }"
                        shapes += "GraphQL space_by_pk(id: <space>) { boundary } · zone(space_id = <space>, zone_type = fixture) { id geometry }"
                        shapes += "PUT /zones/${m.zoneId} ${mapper.writeValueAsString(body).take(400)}"
                    }
                    val r = ops.send(jwt, "PUT", "/zones/${m.zoneId}", body)
                    if (r is ConnectResponse.Ok && ops.json(r).path("updated").asBoolean(false)) {
                        sent++
                        done++
                        log.info(
                            "    moved {} ({}, {}) → ({}, {}) mm",
                            m.code,
                            current.cx.toLong(),
                            current.cy.toLong(),
                            (current.cx + dx).toLong(),
                            (current.cy + dy).toLong(),
                        )
                        g.fixtures[m.zoneId] = current.moved(dx, dy).ring()
                        Files.createDirectories(dir)
                        mapper.writerWithDefaultPrettyPrinter().writeValue(originFile.toFile(), origins)
                        if (!Files.exists(appliedFile)) StoreStock.appendLines(appliedFile, listOf("extId,zoneId,date,dx,dy"))
                        StoreStock.appendLines(appliedFile, listOf("${m.extId},${m.zoneId},${m.date},$dx,$dy"))
                    } else {
                        err++
                        log.error("    PUT /zones/{} refused: {}", m.zoneId, ops.describe(r))
                    }
                }
                if (done < perDay) log.info("  {} {}: {} of {} move(s) made — no other candidate had a free slot", slug, cfg.date, done, perDay)
            } catch (e: Exception) {
                err++
                log.error("  {} FAILED — {}: {} (the pass goes on to the next store)", slug, e.javaClass.simpleName, e.message)
            }
        }
        if (!cfg.live) {
            log.info(
                "[DRY] the day's moves for {} — movable types only ({}), destinations against the committed layout:",
                cfg.date,
                FixtureMoves.MOVABLE_CATEGORIES,
            )
            listing.forEach { log.info("  {}", it) }
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
        return Summary(planned, sent, skipped, held, err, shapes, listing)
    }

    private fun dryLine(m: FixtureMoves.Move, cur: FixtureMoves.Footprint, dx: Double, dy: Double): String =
        "${m.slug} ${m.date} ${m.code} [${m.category}] in ${m.region}: (${cur.cx.toLong()}, ${cur.cy.toLong()}) → " +
            "(${(cur.cx + dx).toLong()}, ${(cur.cy + dy).toLong()}) shift ${Math.round(Math.hypot(dx, dy))} mm · \"${m.reason}\""

    companion object {
        const val ZONE_GQL = "query GetZoneById(\$id: uuid!) " +
            "{ zone_by_pk(id: \$id) { id name geometry geometry_type properties zone_type space_id } }"

        // zone_type is a custom scalar in the v2 schema: the value goes in as a variable, never as a bare literal
        const val SPACE_GQL = "query TwinSpaceFixtures(\$space: uuid!, \$zt: zone_type!) { space_by_pk(id: \$space) { id boundary } " +
            "zone(where: {space_id: {_eq: \$space}, zone_type: {_eq: \$zt}}, limit: 3000) { id geometry geometry_type properties } }"

        /** A platform zone row's footprint: a Polygon's outer ring, or a circle from its Point and `radiusX`; null otherwise. */
        fun footprintOf(z: JsonNode): FixtureMoves.Footprint? = when (z.path("geometry_type").asText()) {
            "polygon" -> PolyGeom.outerRing(z.path("geometry"))?.let { FixtureMoves.Footprint.Poly(it) }

            "circle" -> z.path("geometry").path("coordinates").let { c ->
                val rx = z.path("properties").path("radiusX").asDouble(0.0)
                if (c.isArray && c.size() >= 2 && rx > 0) FixtureMoves.Footprint.Circle(c[0].asDouble(), c[1].asDouble(), rx) else null
            }

            else -> null
        }

        /**
         * The zone update for a shift of (dx, dy), with [reason]. A polygon sends only its moved ring (`properties` untouched
         * by the platform). A circle sends its moved Point AND the zone's own properties with `centerX/centerY` moved — every
         * other key is copied back, so the fixture code and radius survive whatever the platform does with `properties`.
         */
        fun putBody(zone: JsonNode, dx: Double, dy: Double, reason: String): Map<String, Any?> {
            val m = ConnectMappers.camel
            return if (zone.path("geometry_type").asText() == "circle") {
                val c: List<Double> = m.convertValue(
                    zone.path("geometry").path("coordinates"),
                    object : com.fasterxml.jackson.core.type.TypeReference<List<Double>>() {},
                )
                val moved = listOf(c[0] + dx, c[1] + dy) + c.drop(2)
                val props: MutableMap<String, Any?> = if (zone.path(
                        "properties",
                    ).isObject
                ) {
                    m.convertValue(
                        zone.path("properties"),
                        object : com.fasterxml.jackson.core.type.TypeReference<MutableMap<String, Any?>>() {},
                    )
                } else {
                    LinkedHashMap()
                }
                props["centerX"] = moved[0]
                props["centerY"] = moved[1]
                mapOf(
                    "geometryType" to "circle",
                    "geometry" to mapOf("type" to "Point", "coordinates" to moved),
                    "properties" to props,
                    "reason" to reason,
                )
            } else {
                val coords: List<List<List<Double>>> = m.convertValue(
                    zone.path("geometry").path("coordinates"),
                    object : com.fasterxml.jackson.core.type.TypeReference<List<List<List<Double>>>>() {},
                )
                polygonBody(FixtureMoves.translate(coords, dx, dy), reason)
            }
        }

        fun polygonBody(coords: List<List<List<Double>>>, reason: String): Map<String, Any?> =
            mapOf("geometryType" to "polygon", "geometry" to mapOf("type" to "Polygon", "coordinates" to coords), "reason" to reason)
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
