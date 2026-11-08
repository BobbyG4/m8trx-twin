package com.m8trx.twin.ops

import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.history.HistoryCsv
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * The `fixmoves` step's generator — the moves a store would actually make (Bob, 2026-10-14: "those are big huge gondolas,
 * usually one piece; it doesn't make sense what you're moving there").
 *
 *  - **Only movable fixture types** ([MOVABLE_CATEGORIES]). Gondolas, wall bays, cases, the checkout and its queue line,
 *    benches and the treadmill never move.
 *  - **Every store, every day, on its own local day:** `perDay` (default 1, at most [MAX_PER_DAY]) moves per store per
 *    store-local date, made from [MOVE_AT] store-local (before opening, when a merchandiser re-flows the floor) — no store
 *    is skipped by the seed. A store with fewer movable fixtures than `perDay` moves what it has.
 *  - **A destination that makes sense:** a free slot inside the fixture's OWN region zone (`in_area_zone` — the entrance,
 *    a department band), at least [MIN_SHIFT_MM] from where it stands, keeping [CLEARANCE_MM] of aisle from every other
 *    fixture and [GATE_CLEARANCE_MM] from an EAS gate line, inside the room. A region is a rectangle, so the straight path
 *    from the old spot to the new one stays inside it: no move crosses a wall.
 *  - **A reason** on every move ([reasonFor]), sent with the zone update and recorded in the run log.
 *
 * Pure and deterministic in (seed, store, date) and the geometry it is handed; the drive hands it the platform's geometry.
 */
object FixtureMoves {
    /**
     * The fixture types a store moves by hand — Bob's list (rack · rail · table · display · basket · endcap) as the layout
     * names them. Today's layout grammar (`scripts/build_layout.py`) has exactly two: `round_rack` (an apparel rack on
     * castors) and `promo_island` (a promotion table / display). It has no rail, basket or endcap type, so those words have
     * nothing to match yet; add the category here when the layout gains one. Everything else in the grammar is fixed:
     * `gondola_front/back` (one-piece runs), `perimeter_east/west` and `accessories_wall` (walls), `gps_case` (locked
     * cases), `checkout` and `impulse_rack` (the cash wrap and its queue line), `footwear_bench`, `gait_treadmill`, and the
     * Back Room's `backroom_rack` / `receiving_dock`.
     */
    val MOVABLE_CATEGORIES: Set<String> = setOf("round_rack", "promo_island")

    const val MAX_PER_DAY = 2

    /** A move a shopper would notice: 0.75 m or more. A smaller shuffle is not a re-flow. */
    const val MIN_SHIFT_MM = 750.0

    /** The aisle kept free around a moved fixture, to every other fixture's footprint. */
    const val CLEARANCE_MM = 600.0

    /** Kept between a moved fixture and an EAS gate line — the layout's own spacing (islands stand 500 mm above `CS-01`). */
    const val GATE_CLEARANCE_MM = 500.0

    /** The grid the free slots are sampled on. */
    const val GRID_MM = 125.0
    val MOVE_AT: LocalTime = LocalTime.of(7, 30)

    data class Gate(val y: Double, val x0: Double, val x1: Double)

    data class Region(val code: String, val name: String, val ring: List<PolyGeom.P>)

    /** One sales-floor fixture as the layout declares it: its type and the region zone it stands in. */
    data class FloorFixture(val code: String, val category: String, val region: String?, val footprint: Footprint)

    /** A fixture's footprint: a circle (centre + radius) or a polygon ring. */
    sealed interface Footprint {
        fun ring(): List<PolyGeom.P>

        fun moved(dx: Double, dy: Double): Footprint

        fun inflated(mm: Double): List<PolyGeom.P>

        val cx: Double
        val cy: Double
        val reach: Double

        data class Circle(override val cx: Double, override val cy: Double, val r: Double) : Footprint {
            override fun ring() = PolyGeom.circle(cx, cy, r, r)

            override fun moved(dx: Double, dy: Double) = copy(cx = cx + dx, cy = cy + dy)

            override fun inflated(mm: Double) = PolyGeom.circle(cx, cy, r + mm, r + mm)

            override val reach: Double get() = r
        }

        data class Poly(val pts: List<PolyGeom.P>) : Footprint {
            override fun ring() = pts

            override fun moved(dx: Double, dy: Double) = Poly(PolyGeom.translate(pts, dx, dy))

            override fun inflated(mm: Double): List<PolyGeom.P> {
                val x0 = pts.minOf { it.x } - mm
                val x1 = pts.maxOf { it.x } + mm
                val y0 = pts.minOf { it.y } - mm
                val y1 = pts.maxOf { it.y } + mm
                return listOf(PolyGeom.P(x0, y0), PolyGeom.P(x1, y0), PolyGeom.P(x1, y1), PolyGeom.P(x0, y1))
            }

            override val cx: Double get() = pts.sumOf { it.x } / pts.size
            override val cy: Double get() = pts.sumOf { it.y } / pts.size
            override val reach: Double get() = pts.maxOf { hypot(it.x - cx, it.y - cy) }
        }
    }

    /** A store's sales floor from its committed `layout.json`: fixture types, region rectangles, the room, the EAS gates. */
    data class Floor(
        val slug: String,
        val fixtures: Map<String, FloorFixture>,
        val regions: Map<String, Region>,
        val room: List<PolyGeom.P>,
        val gates: List<Gate>,
    ) {
        companion object {
            fun load(chain: Path, slug: String): Floor {
                val root = ConnectMappers.camel.readTree(Files.readAllBytes(chain.resolve("stores/$slug/layout.json")))
                val sf = root.path("spaces").first { it.path("space_type").asText() == "sales_floor" }
                fun rect(r: com.fasterxml.jackson.databind.JsonNode) = listOf(
                    PolyGeom.P(r.path("x1").asDouble(), r.path("y1").asDouble()),
                    PolyGeom.P(r.path("x2").asDouble(), r.path("y1").asDouble()),
                    PolyGeom.P(r.path("x2").asDouble(), r.path("y2").asDouble()),
                    PolyGeom.P(r.path("x1").asDouble(), r.path("y2").asDouble()),
                )
                val fixtures = LinkedHashMap<String, FloorFixture>()
                val regions = LinkedHashMap<String, Region>()
                sf.path("zones").forEach { z ->
                    val code = z.path("code").asText()
                    if (z.path("zone_type").asText() == "fixture") {
                        val fp: Footprint = if (z.path("geometry_type").asText() == "circle") {
                            val p = z.path("properties")
                            Footprint.Circle(p.path("centerX").asDouble(), p.path("centerY").asDouble(), p.path("radiusX").asDouble())
                        } else {
                            Footprint.Poly(rect(z.path("rect_mm")))
                        }
                        val region = z.path("in_area_zone").asText().ifBlank { null }
                        fixtures[code] = FloorFixture(code, z.path("fixture_category").asText(), region, fp)
                    } else if (z.has("rect_mm")) {
                        regions[code] = Region(code, z.path("name").asText(), rect(z.path("rect_mm")))
                    }
                }
                val fp = sf.path("footprint_mm")
                val w = fp.path("width").asDouble()
                val d = fp.path("depth").asDouble()
                val room = listOf(PolyGeom.P(0.0, 0.0), PolyGeom.P(w, 0.0), PolyGeom.P(w, d), PolyGeom.P(0.0, d))
                val gates = sf.path("crossing_slices").filter { it.path("eas_gate").asBoolean(false) }.map {
                    Gate(it.path("y").asDouble(), it.path("x_start").asDouble(), it.path("x_end").asDouble())
                }
                return Floor(slug, fixtures, regions, room, gates)
            }
        }
    }

    data class Move(
        val extId: String,
        val slug: String,
        val code: String,
        val zoneId: String,
        val date: LocalDate,
        val category: String,
        val region: String,
        val reason: String,
    )

    /** The movable fixtures of [store] (by the layout's type, standing in a region), in code order. */
    fun movable(store: ChainKeys.Store, floor: Floor): List<ChainKeys.Zone> = store.fixtures.filter {
        val f = floor.fixtures[it.code]
        f != null && f.category in MOVABLE_CATEGORIES && f.region != null
    }.sortedBy { it.code }

    /** True when [code] is a fixture type a store would move. */
    fun isMovable(floor: Floor, code: String): Boolean = floor.fixtures[code]?.category in MOVABLE_CATEGORIES

    fun reasonFor(category: String, regionName: String): String = when (category) {
        "promo_island" -> "twin: promo re-flow - promotion table moved to a free spot in $regionName"
        "round_rack" -> "twin: apparel re-flow - round rack moved to open the aisle in $regionName"
        else -> "twin: merchandising re-flow in $regionName"
    }

    /**
     * The day's candidates for one store: every movable fixture, in an order drawn from (seed, store, date). The drive moves the
     * first `perDay` of them that have a free slot, so a fixture boxed in by its neighbours does not cost the store its move.
     */
    fun candidates(store: ChainKeys.Store, floor: Floor, date: LocalDate, seed: Long): List<Move> {
        val rng = Random(HistoryCsv.derive(seed, store.slug, date, "fixmoves-v2"))
        val ymd = date.format(DateTimeFormatter.BASIC_ISO_DATE)
        return movable(store, floor).shuffled(rng).map { z ->
            val f = floor.fixtures.getValue(z.code)
            val region = floor.regions[f.region]?.name ?: f.region!!
            Move("FM2-${store.slug}-$ymd-${z.code}", store.slug, z.code, z.zoneId, date, f.category, f.region!!, reasonFor(f.category, region))
        }
    }

    /** The first `perDay` (capped at [MAX_PER_DAY]) candidates — the plan before geometry is consulted. */
    fun forDate(store: ChainKeys.Store, floor: Floor, date: LocalDate, seed: Long, perDay: Int): List<Move> =
        candidates(store, floor, date, seed).take(perDay.coerceIn(0, MAX_PER_DAY))

    /**
     * The day's moves against a floor: candidates in seed order, each placed in a free slot of its region against [others]
     * (zone/code → footprint ring, updated as moves land), until `perDay` have a destination. Returns (move, dx, dy).
     */
    fun planDay(
        candidates: List<Move>,
        perDay: Int,
        footprintOf: (Move) -> Footprint?,
        others: MutableMap<String, List<PolyGeom.P>>,
        keyOf: (Move) -> String,
        floor: Floor,
        boundary: List<PolyGeom.P>?,
        seed: Long,
    ): List<Triple<Move, Double, Double>> {
        val out = ArrayList<Triple<Move, Double, Double>>()
        for (m in candidates) {
            if (out.size >= perDay.coerceIn(0, MAX_PER_DAY)) break
            val cur = footprintOf(m) ?: continue
            val d = destination(m, cur, floor, others.filterKeys { it != keyOf(m) }.values, boundary, seed) ?: continue
            others[keyOf(m)] = cur.moved(d.first, d.second).ring()
            out += Triple(m, d.first, d.second)
        }
        return out
    }

    /**
     * Where [m] goes: the shift (dx, dy) from [current] to the first free slot of its region, in an order drawn from the
     * seed, or null when the region has no free slot. [others] = every OTHER fixture of the space as it stands now.
     */
    fun destination(
        m: Move,
        current: Footprint,
        floor: Floor,
        others: Collection<List<PolyGeom.P>>,
        boundary: List<PolyGeom.P>?,
        seed: Long,
    ): Pair<Double, Double>? {
        val region = floor.regions[m.region] ?: return null
        val x0 = region.ring.minOf { it.x }
        val x1 = region.ring.maxOf { it.x }
        val y0 = region.ring.minOf { it.y }
        val y1 = region.ring.maxOf { it.y }
        val slots = ArrayList<Pair<Double, Double>>()
        var x = Math.ceil(x0 / GRID_MM) * GRID_MM
        while (x <= x1) {
            var y = Math.ceil(y0 / GRID_MM) * GRID_MM
            while (y <= y1) {
                slots += x to y
                y += GRID_MM
            }
            x += GRID_MM
        }
        val rng = Random(HistoryCsv.derive(seed, m.slug, m.date, "fixmoves-v2-slot", m.code))
        return slots.shuffled(rng).asSequence().map { (sx, sy) -> (sx - current.cx) to (sy - current.cy) }.firstOrNull { (dx, dy) ->
            fits(current.moved(dx, dy), hypot(dx, dy), region, floor, others, boundary)
        }
    }

    /** The rules a destination must pass — see the object note. */
    fun fits(
        moved: Footprint,
        shift: Double,
        region: Region,
        floor: Floor,
        others: Collection<List<PolyGeom.P>>,
        boundary: List<PolyGeom.P>?,
    ): Boolean {
        if (shift < MIN_SHIFT_MM) return false
        val ring = moved.ring()
        if (!PolyGeom.within(ring, region.ring)) return false
        if (!PolyGeom.within(ring, boundary ?: floor.room)) return false
        val halo = moved.inflated(CLEARANCE_MM)
        if (others.any { PolyGeom.overlaps(halo, it) }) return false
        return floor.gates.none { g ->
            moved.cx + moved.reach > g.x0 && moved.cx - moved.reach < g.x1 && abs(moved.cy - g.y) < moved.reach + GATE_CLEARANCE_MM
        }
    }

    /** Translate every vertex of a GeoJSON polygon ring set by (dx, dy); z and extra members are kept. */
    fun translate(coordinates: List<List<List<Double>>>, dx: Double, dy: Double): List<List<List<Double>>> =
        coordinates.map { ring -> ring.map { v -> listOf(v[0] + dx, v[1] + dy) + v.drop(2) } }
}
