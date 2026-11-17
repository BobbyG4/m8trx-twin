package com.m8trx.twin.storeday

import com.m8trx.twin.history.ChainKeys
import java.time.LocalDate
import java.util.TreeMap

/**
 * Slice 2d: the twin's own truth for one planted run, accumulated from the same [DayResult]s the per-day truth files are written from
 * (`fixture_hour.csv`, `fixture_product_day.csv`, `onhand.csv`) plus the per-product pickups and the back room the day files do not
 * carry. Every planted pattern is checked against this and nothing else ([PlantVerify]).
 */
class PlantLedger(val sim: StoreDaySim) {
    class FixtureStat {
        var impressions = 0
        var dwellMs = 0L
        var pickups = 0
        var units = 0
        var valueUsd = 0.0
        var stockOuts = 0
        var lost = 0
        var onHandValueSum = 0.0
        var onHandQtySum = 0L
        var emptyDays = 0
    }

    class EanStat {
        var units = 0
        var pickups = 0
        var lost = 0
        var stockOuts = 0
        var floorQtySum = 0L
        var totalQtySum = 0L
    }

    val dates = ArrayList<LocalDate>()
    val fixtures = TreeMap<String, FixtureStat>()
    val eans = TreeMap<String, EanStat>()

    /** Units of an EAN sold per fixture it sat on: the grain of `fixture_product_day.csv`, summed over the run. */
    val fixtureEan = TreeMap<Pair<String, String>, Int>(compareBy({ it.first }, { it.second }))

    private val price = sim.model.demand.mapValues { it.value.priceUsd }
    private val keys = ChainKeys.load().store(sim.model.slug)
    private val fixtureRegion: Map<String, String> = sim.behavior.fixtures.fixtures.associate { it.code to (it.inAreaZone ?: "") }

    /** Fixtures the plan shelves, in the Phase 1 plan: the merchandise a question about the floor is about. */
    val planFixtures: Set<String> = sim.model.lines.map { it.fixture }.toSet()

    /** Merchandise floor fixtures: shelved, and not the checkout counters (Z-02) whose "impressions" are the queue. */
    val merch: List<String> = planFixtures.filter { fixtureRegion[it] != "Z-02" }.sorted()

    val days: Int get() = dates.size

    fun fixture(code: String) = fixtures.getOrPut(code) { FixtureStat() }

    fun ean(e: String) = eans.getOrPut(e) { EanStat() }

    fun observe(r: DayResult) {
        dates += r.date
        StoreDayOutput.fixtureHours(sim, r).forEach { (k, v) ->
            fixture(k.second).also {
                it.impressions += v.impressions
                it.dwellMs += v.dwellMs
                it.pickups += v.pickups
                it.units += v.units
                it.valueUsd += v.valueUsd
                it.stockOuts += v.stockOuts
                it.lost += v.lostSales
            }
        }
        r.saleUnits.forEach { u ->
            ean(u.ean).units++
            fixtureEan.merge(u.fixture to u.ean, 1, Int::plus)
        }
        r.records.forEach { rec ->
            rec.visits.forEach { v -> v.pickups.forEach { ean(it.cell.ean).pickups++ } }
            rec.stockOuts.forEach { o ->
                ean(o.cell.ean).also {
                    it.stockOuts++
                    if (o.lost) it.lost++
                }
            }
        }
        if (r.plan.closed) return
        val byFixture = HashMap<String, Int>()
        val valueByFixture = HashMap<String, Double>()
        val pl = sim.plant
        // A shopper's put-back can land one unit on a neighbouring slot during the day (the 2a misplacement). Staff return it the next
        // morning, so those units are the only ones allowed above a slot's planned quantity at a close.
        val misplaced = r.moves.filter { it.reason == "misplaced" }.groupingBy { Slot(it.fixture, it.ean) }.fold(0) { n, m -> n + m.epcs.size }
        r.closeOnHand.forEach { (slot, q) ->
            if (pl != null && (slot.fixture in pl.protectedFixtures || slot.ean in pl.protectedEans)) {
                val cap = r.required[slot]
                if (cap == null) {
                    strayUnits += q
                } else if (q > cap + (misplaced[slot] ?: 0)) {
                    overCapacity +=
                        "${r.date} ${slot.fixture} ${slot.ean} $q>$cap ${r.moves.filter { m ->
                            m.fixture == slot.fixture && m.ean == slot.ean
                        }.map { m -> m.reason + m.epcs.size }} open=${r.openOnHand[slot]}"
                }
            }
            byFixture.merge(slot.fixture, q, Int::plus)
            valueByFixture.merge(slot.fixture, q * (price[slot.ean] ?: 0.0), Double::plus)
            ean(slot.ean).floorQtySum += q
            ean(slot.ean).totalQtySum += q
        }
        r.closeBackroom.forEach { (e, q) -> ean(e).totalQtySum += q }
        planFixtures.forEach { f ->
            val st = fixture(f)
            st.onHandQtySum += byFixture[f] ?: 0
            st.onHandValueSum += valueByFixture[f] ?: 0.0
            if ((byFixture[f] ?: 0) <= EMPTY_QTY) st.emptyDays++
        }
        openDays++
    }

    var openDays = 0

    /** Planted slots that held more than their planned (full) quantity at a close, and units sitting off-plan on a planted fixture. */
    val overCapacity = ArrayList<String>()
    var strayUnits = 0L

    companion object {
        /** A fixture needs this many visits over the run before its dwell per visit is ranked (a 28-day run of a quiet fixture still has several a day). */
        const val MIN_VISITS_FOR_DWELL = 20

        /** A fixture holding this many units or fewer at close counts as empty (a stray put-back can land on a bare fixture). */
        const val EMPTY_QTY = 2
    }

    // ── readings ──────────────────────────────────────────────────────────────────────────────────────

    private val denominator: Double get() = maxOf(1, days).toDouble()

    /** Units sold per day over the run. */
    fun velocity(e: String): Double = (eans[e]?.units ?: 0) / denominator

    /** Mean units a day that wanted the product: sold plus lost to an empty facing. */
    fun demandPerDay(e: String): Double = ((eans[e]?.units ?: 0) + (eans[e]?.lost ?: 0)) / denominator

    /** Mean floor on-hand of an EAN at the days' close, in units. */
    fun floorUnits(e: String): Double = (eans[e]?.floorQtySum ?: 0L) / maxOf(1, openDays).toDouble()

    /** Mean store-wide on-hand (floor and back room) at the days' close, in units. */
    fun onHandUnits(e: String): Double = (eans[e]?.totalQtySum ?: 0L) / maxOf(1, openDays).toDouble()

    /** Days of cover: on-hand at close over the daily demand. Infinite when nothing was asked for. */
    fun coverDays(e: String): Double = demandPerDay(e).let { if (it <= 0.0) Double.POSITIVE_INFINITY else onHandUnits(e) / it }

    fun impressions(f: String): Int = fixtures[f]?.impressions ?: 0

    /** Mean engaged dwell per visit (impression) at a fixture, in seconds: 0 when nobody stopped. */
    fun dwellPerVisit(f: String): Double = fixtures[f]?.let { if (it.impressions == 0) 0.0 else it.dwellMs / 1000.0 / it.impressions } ?: 0.0

    /** Merchandise fixtures with enough visits in the run for a dwell per visit to mean something (the rest are noise). */
    val merchWithVisits: List<String> get() = merch.filter { impressions(it) >= MIN_VISITS_FOR_DWELL }

    fun onHandValue(f: String): Double = (fixtures[f]?.onHandValueSum ?: 0.0) / maxOf(1, openDays)

    fun regionOf(f: String): String = fixtureRegion[f] ?: ""

    /** Impressions summed over the fixtures of a region (a department area or a zone such as the entrance). */
    fun regionImpressions(code: String): Int = fixtureRegion.filter { it.value == code }.keys.sumOf { impressions(it) }

    /** The department region codes (`D-01` ...) and the department each holds, from the keys export. */
    val departmentRegion: Map<String, String> = keys.departments.associate { it.department!! to it.code }

    /** Where a product is shelved in the plan in force at the end of the run (its first line by fixture code). */
    fun homeFixture(e: String, plan: PeriodPlan): String = plan.lines.filter { it.ean == e }.map { it.fixture }.sorted().firstOrNull()
        ?: error("${sim.model.slug}: $e is not on the plan")

    val floorEans: List<String> get() = sim.model.required.keys.map { it.ean }.toSortedSet().toList()

    fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        return s[minOf(s.size - 1, Math.floor(p * s.size).toInt())]
    }

    fun median(values: List<Double>) = percentile(values, 0.5)
}
