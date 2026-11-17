package com.m8trx.twin.storeday

import com.m8trx.twin.behavior.BehaviorParams
import com.m8trx.twin.behavior.FixturePresentation
import com.m8trx.twin.behavior.ShelfPlan
import com.m8trx.twin.layer1.Fixture
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Slice 2e: how long a shopper's engaged span at a fixture runs, by what the fixture IS and what is ON it.
 *
 * Before 2e the behavior file's flat span (25 to 80 s, times the square root of content appeal) was the only thing a fixture could
 * change, so dwell per visit was near-constant and total dwell merely mirrored visit counts. Now the span is
 *
 *     draw (behavior file, uniform 25..80 s)  x  [DwellKnobs] kind factor  x  depth factor  x  attraction factor  x  store dwell scale
 *
 * and the cells a shopper handles at the visit grow with the span. It only exists inside a store-day run ([com.m8trx.twin.behavior.FloorHooks.dwellScale] is
 * null for every other run), so `behaviorSelfTest` and Denver's tuned behavior file are untouched.
 */
enum class FixtureKind(val label: String) {
    FOOTWEAR_WALL("footwear wall"),
    APPAREL_RAIL("apparel rail / rounder"),
    APPAREL_FOLDED("folded apparel"),
    PEGBOARD("pegboard accessories"),
    PROMO_TABLE("promo table / bin"),
    GPS_CASE("GPS case"),
    BIKE("bike"),
    LARGE_ITEM("large items (bulk)"),
    OTHER("other"),
}

/** Every number of the dwell model, in one place. Kind factors are RELATIVE (the store's dwell scale sets the level, see [Calibration.dwellScale]). */
object DwellKnobs {
    /** Relative engaged span per fixture kind: a footwear wall is where people try shoes on, so long. */
    val KIND_FACTOR: Map<FixtureKind, Double> = mapOf(
        // Shoes are lifted, turned over and tried on at the bench: the longest ordinary stop.
        FixtureKind.FOOTWEAR_WALL to 2.0,
        // Rails and rounders: flipping through hangers, holding items up, a medium-long browse.
        FixtureKind.APPAREL_RAIL to 1.4,
        // Folded stacks are scanned and picked from: medium.
        FixtureKind.APPAREL_FOLDED to 0.9,
        // Pegboard accessories are grab-and-go: short.
        FixtureKind.PEGBOARD to 0.45,
        // Promo tables and bins are seen on the way past: short (their traffic is frequent, not their stops long).
        FixtureKind.PROMO_TABLE to 0.5,
        // A GPS case is a considered, expensive look through glass, with an associate: long when engaged (a shopper has to stop at all).
        FixtureKind.GPS_CASE to 2.4,
        // A bike is test-sat and compared: the longest stop, but few shoppers stop (the stop weight is the fixture's own, not changed here).
        FixtureKind.BIKE to 3.0,
        // Tents, packs and other large items: unfolded and handled, long-ish.
        FixtureKind.LARGE_ITEM to 1.3,
        FixtureKind.OTHER to 1.0,
    )

    /** Depth of range: span x (distinct styles on the fixture / the store's median fixture)^this, so a wider range is browsed longer. */
    const val DEPTH_EXPONENT = 0.3
    const val DEPTH_MIN = 0.6
    const val DEPTH_MAX = 1.7

    /** Attraction: span x (content appeal x the fixture's impression truths)^this. Replaces the behavior model's own sqrt(content appeal) in a store-day run. */
    const val ATTRACTION_EXPONENT = 0.5
    const val ATTRACTION_FLOOR = 0.25

    /** Interactions scale with the span: cells considered = base x (span / the behavior file's mean span)^this, at least 1. 0 = unchanged. */
    const val INTERACTION_EXPONENT = 0.35

    /** A try-on (bench or fitting room) lasts this much longer than the behavior file's 2 to 5 minutes, so the bench is the longest footwear stop. */
    const val TRY_ON_SCALE = 1.8

    /**
     * A put-back lands on a neighbour with `Knobs.misplaceProb` x this. Longer visits handle more cells (see [INTERACTION_EXPONENT]), so a
     * unit sold now comes with about twice the put-backs; halving the per-put-back chance keeps the share of units sold from a wrong
     * fixture where 2a set it (about 5 %). Fitted from the measured ratio (9.7 % at 1.0), not derived.
     */
    const val MISPLACE_SCALE = 0.52

    /** Put-backs of an item at or above this price (bike-class) are never misplaced: a shopper does not carry a bike to another aisle. Matches [StoreDayModel.caseSize]'s 500. */
    const val MISPLACE_MAX_PRICE_USD = 500.0
}

class DwellModel(private val model: StoreDayModel, plan: ShelfPlan, private val params: BehaviorParams, val storeScale: Double) {
    fun kindOf(code: String): FixtureKind = kinds[code] ?: FixtureKind.OTHER

    private val kinds: Map<String, FixtureKind> = model.fixtureSpecs.mapValues { (_, s) -> classify(s) }

    /** The store's median number of distinct styles on a shelved fixture, the depth factor's 1.0. */
    private val referenceStyles: Double = plan.cellsByFixture.values.map { cs -> cs.map { it.handle }.toSet().size }.sorted()
        .let { if (it.isEmpty()) 1.0 else maxOf(1.0, it[it.size / 2].toDouble()) }

    private val referenceSpanMs: Double = (params.browse.dwellSeconds[0] + params.browse.dwellSeconds[1]) / 2.0 * 1000.0

    /** Total multiplier on the behavior file's draw for a visit at [f] showing [p]. */
    fun scale(f: Fixture, p: FixturePresentation): Double {
        val styles = p.cells.map { it.handle }.toSet().size
        val depth = ((styles / referenceStyles).pow(DwellKnobs.DEPTH_EXPONENT)).coerceIn(DwellKnobs.DEPTH_MIN, DwellKnobs.DEPTH_MAX)
        val attraction = (p.contentAppeal * p.impressionFactor).coerceAtLeast(DwellKnobs.ATTRACTION_FLOOR).pow(DwellKnobs.ATTRACTION_EXPONENT)
        return DwellKnobs.KIND_FACTOR.getValue(kindOf(f.code)) * depth * attraction * storeScale
    }

    fun cellsConsidered(base: Int, dwellMs: Long): Int =
        maxOf(1, (base * (dwellMs / referenceSpanMs).pow(DwellKnobs.INTERACTION_EXPONENT)).roundToInt())

    companion object {
        /** Kind from the fixture's category, then its goods mix (the goods class holding most facings). */
        fun classify(s: StoreDayModel.FixtureSpec): FixtureKind = when (s.category) {
            "gps_case" -> FixtureKind.GPS_CASE

            "promo_island", "checkout" -> FixtureKind.PROMO_TABLE

            "impulse_rack", "accessories_wall" -> FixtureKind.PEGBOARD

            else -> when (s.goodsFacings.entries.sortedWith(compareBy({ -it.value }, { it.key })).firstOrNull()?.key) {
                "shoe" -> FixtureKind.FOOTWEAR_WALL
                "hang" -> FixtureKind.APPAREL_RAIL
                "fold" -> FixtureKind.APPAREL_FOLDED
                "peg" -> FixtureKind.PEGBOARD
                "bike" -> FixtureKind.BIKE
                "bulk" -> FixtureKind.LARGE_ITEM
                else -> FixtureKind.OTHER
            }
        }
    }
}

/** What a window of stocked days says about dwell: engaged spans per visit by fixture kind, session lengths, per-fixture totals. */
class DwellStats(val sim: StoreDaySim) {
    /** Engaged span in seconds of every BROWSE visit, by the fixture's kind. */
    val browseByKind = java.util.TreeMap<FixtureKind, MutableList<Double>>()
    val tryOnBench = ArrayList<Double>()
    val sessionMin = ArrayList<Double>()
    val pickupsPerVisit = ArrayList<Int>()
    var visitors = 0
    var units = 0
    var pickups = 0

    /** Per-class minutes in the zone per shopper who visited it (browse + bench try-on): footwear (walls and bench), apparel, GPS. */
    val zoneMin = java.util.TreeMap<String, MutableList<Double>>()
    private val kinds: Map<String, FixtureKind> = sim.model.fixtureSpecs.mapValues { DwellModel.classify(it.value) }

    fun observe(records: List<com.m8trx.twin.behavior.ShopperRecord>) {
        for (r in records) {
            visitors++
            sessionMin += (r.exitMs - r.entryMs) / 60_000.0
            units += r.lines.size
            val perClass = HashMap<String, Double>()
            for (v in r.visits) {
                val k = kinds[v.fixture] ?: FixtureKind.OTHER
                val sec = v.dwellMs / 1000.0
                when (v.kind) {
                    com.m8trx.twin.behavior.Visit.Kind.BROWSE -> {
                        browseByKind.getOrPut(k) { ArrayList() } += sec
                        pickups += v.pickups.size
                        pickupsPerVisit += v.pickups.size
                        classOf(k)?.let { perClass.merge(it, sec, Double::plus) }
                    }

                    com.m8trx.twin.behavior.Visit.Kind.TRY_ON -> {
                        if (v.fixture != com.m8trx.twin.behavior.ShopperAgent.FITTING_ROOM) {
                            tryOnBench += sec
                            perClass.merge("footwear", sec, Double::plus)
                        } else if (v.triedCell?.category == "apparel") {
                            perClass.merge("apparel", sec, Double::plus)
                        }
                    }

                    else -> {}
                }
            }
            perClass.forEach { (c, s) -> zoneMin.getOrPut(c) { ArrayList() } += s / 60.0 }
        }
    }

    private fun classOf(k: FixtureKind): String? = when (k) {
        FixtureKind.FOOTWEAR_WALL -> "footwear"
        FixtureKind.APPAREL_RAIL, FixtureKind.APPAREL_FOLDED -> "apparel"
        FixtureKind.GPS_CASE -> "gps"
        else -> null
    }

    val allBrowse: List<Double> get() = browseByKind.values.flatten()
    fun meanAll(): Double = allBrowse.average()

    companion object {
        /** [days] fully stocked days from [from], the same days the calibration fits on. */
        fun stocked(sim: StoreDaySim, from: java.time.LocalDate, days: Int): DwellStats {
            val st = DwellStats(sim)
            for (i in 0 until days) st.observe(sim.stockedDay(from.plusDays(i.toLong()), sim.behavior.params.seed + 1000 + i).records)
            return st
        }

        /** The operating model's `dwell.total_median_min`: the median time a visitor spends in the store, in minutes. */
        fun targetSessionMin(): Double = com.m8trx.twin.connect.model.ConnectMappers.snake.readTree(
            java.nio.file.Files.readAllBytes(java.nio.file.Path.of("reference/data/store-operating-model.json")),
        ).path("dwell").path("total_median_min").path("value").asDouble()

        fun median(v: List<Double>): Double = v.sorted().let { it[it.size / 2] }

        /**
         * The dwell scale that puts the store's MEDIAN session (door to door) on [targetSessionMin]. The span at a fixture is linear in the
         * scale but a session also holds the fixed parts (walks, checkout queue), so the median is close to affine in the scale: secant steps
         * on that line, exact to well inside a minute after three evaluations. The operating model states a median, so the median is the gate.
         */
        fun fit(site: String, cal: Calibration, from: java.time.LocalDate, days: Int): Double {
            val target = targetSessionMin()

            fun median(scale: Double) = median(stocked(buildSim(site, cal.copy(dwellScale = scale)), from, days).sessionMin)
            var g0 = 1.0
            var m0 = median(g0)
            var g1 = 4.0
            var m1 = median(g1)
            repeat(3) {
                val g = (g0 + (target - m0) * (g1 - g0) / (m1 - m0)).coerceIn(0.2, 20.0)
                g0 = g1
                m0 = m1
                g1 = g
                m1 = median(g)
            }
            return g1
        }
    }
}
