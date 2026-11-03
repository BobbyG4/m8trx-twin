package com.m8trx.twin.behavior

import com.m8trx.twin.layer1.BrowseEpisode
import com.m8trx.twin.layer1.FixtureSet
import com.m8trx.twin.layer1.ImpressionOracle
import com.m8trx.twin.layer1.ZoneRoleResolver
import com.m8trx.twin.layer2.EasTagging
import com.m8trx.twin.layer3.OperatingModel
import com.m8trx.twin.layer3.Reconciliation
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.exp
import kotlin.random.Random

/**
 * The behavior model for one site: geometry, stock, parameters and the operating model, loaded once. Runs a day
 * of shopper agents against whatever [PlanTimeline] is on the floor (requirements 1, 5, 7, 8).
 *
 * Office sites are refused at construction: the twin has no office layout, and the only edge that sees the
 * office is production hardware (requirement 11). See `RESULTS-TWIN-REQ-007-ROUND-1-2026-11-04.md`.
 */
class BehaviorModel(
    val params: BehaviorParams,
    chainDir: Path = Path.of("reference/data/chain"),
    modelPath: Path = Path.of("reference/data/store-operating-model.json"),
) {
    val site: String = params.site
    val zone: ZoneId = ZoneId.of(params.timezone)
    val model: OperatingModel = OperatingModel.load(modelPath)
    private val storeDir = chainDir.resolve("stores/$site")

    init {
        require(storeDir.resolve("layout.json").toFile().exists()) {
            "no layout for '$site' under $chainDir/stores — the behavior model needs the site's geometry (office sites have none in the twin dataset)"
        }
    }

    val fixtures: FixtureSet = FixtureSet.load(storeDir.resolve("layout.json"))
    val world =
        StoreWorld(
            fixtures,
            ZoneRoleResolver.resolve(storeDir.resolve("layout.json")).zones,
            EasTagging.loadGates(storeDir.resolve("layout.json")),
            params.truths,
        )
    val stock = StoreStock(storeDir, params.truths.levelsPerFixture)
    val presentations = PresentationCache(world, stock, params)
    private val browse = BrowseEpisode(fixtures, emitHz = 5.0)

    /** Operating-model category unit mix, mapped onto this store's categories — a mission shopper's target. */
    private val categoryMix: Map<String, Double> = run {
        val present = stock.catalog.values.map { it.category }.toSet()
        val bucket = mapOf(
            "footwear" to "footwear",
            "apparel" to "apparel",
            "outdoor" to "outdoor",
            "fitness_equipment" to "outdoor",
            "accessories_other" to "accessories",
            "hardware_watches" to "accessories",
            "team_sport" to "accessories",
        )
        val out = HashMap<String, Double>()
        model.basket.cleanUnitMix.forEach { (k, w) -> bucket[k]?.takeIf { it in present }?.let { out[it] = (out[it] ?: 0.0) + w } }
        out
    }

    private val agent = ShopperAgent(world, params, browse, categoryMix, stopNormalizer())

    private fun stopNormalizer(): Double {
        val pub = presentations.of(stock.published)
        return world.visible
            .filter { world.placement[it.code] == Placement.FLOOR || world.placement[it.code] == Placement.BACK_CORNER }
            .sumOf { ShopperAgent.roleWeight(world, it) * pub.getValue(it.code).contentAppeal }
    }

    /** Expected visitors for a date: model base (weekday/weekend) × day-of-week × weekly seasonality × scale. */
    fun expectedVisitors(date: LocalDate, scale: Double): Double {
        val weekend = date.dayOfWeek.value >= 6
        val base = if (weekend) model.traffic.visitorsPerDay.weekend else model.traffic.visitorsPerDay.weekday
        return base * params.traffic.dayFactor(date) * params.traffic.seasonality(date) * scale
    }

    /**
     * One simulated day. Arrivals are an inhomogeneous Poisson process off the operating model's hourly curve;
     * each arrival is an independent [ShopperAgent] with its own RNG, reading the plan active when they enter.
     *
     * [hours] restricts which arrival hours are KEPT (store-local, e.g. 14..14 for one hour) without changing the
     * draw, so a one-hour slice is exactly that hour of the full day.
     */
    fun runDay(date: LocalDate, timeline: PlanTimeline, seed: Long, scale: Double = 1.0, sink: SampleSink? = null, hours: IntRange? = null): DayRun {
        val weekend = date.dayOfWeek.value >= 6
        val curve = if (weekend) model.traffic.hourlyCurve.weekend else model.traffic.hourlyCurve.weekday
        val daily = expectedVisitors(date, scale)
        val open = LocalTime.parse(model.store.operatingHours.open)
        val arrivals = Random(mix(seed, date.toEpochDay(), -1))
        val records = ArrayList<ShopperRecord>()
        var idx = 0
        curve.forEachIndexed { h, share ->
            val n = poisson(daily * share, arrivals)
            val hour = open.hour + h
            val times = (0 until n).map { arrivals.nextDouble() }.sorted()
            for (u in times) {
                val i = idx++
                if (hours != null && hour !in hours) continue
                val entry = ZonedDateTime.of(date, LocalTime.of(hour, 0), zone).toInstant().plusMillis((u * 3_600_000).toLong())
                val rng = Random(mix(seed, date.toEpochDay(), i.toLong()))
                val persona = ShopperAgent.weightedPick(params.personas, rng)
                val plan = timeline.at(entry)
                records += agent.run("s-$date-$i", persona, entry.toEpochMilli(), plan, presentations.of(plan), rng, sink)
            }
        }
        return DayRun(
            date,
            records,
            if (hours ==
                null
            ) {
                daily
            } else {
                curve.withIndex().filter { open.hour + it.index in hours }.sumOf { daily * it.value }
            },
        )
    }

    /** A day's records and its expected footfall. Reconciliation follows the twin's existing §1 identity. */
    inner class DayRun(val date: LocalDate, val records: List<ShopperRecord>, val expectedVisitors: Double) {
        val ledger: FixtureLedger by lazy { FixtureLedger.of(records) }
        val transactions: Int get() = records.count { it.bought }
        val units: Int get() = records.sumOf { it.lines.size }
        val revenueUsd: Double get() = records.sumOf { it.revenueUsd }

        fun reconcile(): Reconciliation.Report =
            Reconciliation.check(model, expectedVisitors, Reconciliation.Observed(records.size, transactions, units, revenueUsd))
    }

    companion object {
        fun forSite(site: String): BehaviorModel = BehaviorModel(BehaviorParams.forSite(site))

        /** SplitMix-style mix so (seed, day, index) streams never collide or correlate. */
        fun mix(seed: Long, day: Long, idx: Long): Long {
            var z = seed * -0x61c8864680b583ebL + day * 0x5851f42d4c957f2dL + idx * 0x14057b7ef767814fL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        fun poisson(lambda: Double, rng: Random): Int {
            if (lambda <= 0.0) return 0
            if (lambda > 60.0) {
                // Normal approximation keeps big hours fast; the draw is still deterministic from the rng.
                return kotlin.math.round(lambda + kotlin.math.sqrt(lambda) * ShopperAgent.gaussian(rng)).toInt().coerceAtLeast(0)
            }
            val l = exp(-lambda)
            var k = 0
            var p = 1.0
            do {
                k++
                p *= rng.nextDouble()
            } while (p > l)
            return k - 1
        }
    }
}

/**
 * The oracle's per-fixture totals for a set of shopper records — the numbers the planogram lane checks a run
 * against without trusting the writer (requirement 7), and the identity behind requirement 8's share.
 */
class FixtureLedger(val rows: Map<String, Row>) {
    data class Row(
        var stops: Int = 0,
        var impressions: Int = 0,
        /** Engaged (looking) time summed over impressions — the agent's `Visit.dwellMs`. */
        var dwellMs: Long = 0,
        /** What the platform should store as impression duration: `lastLook − firstDwell` = look + approach. */
        var platformDurationMs: Long = 0,
        var engagements: Int = 0,
        var tryOns: Int = 0,
        var units: Int = 0,
        var revenueUsd: Double = 0.0,
        val dwelled: MutableSet<String> = HashSet(),
        val dwelledAndBoughtHere: MutableSet<String> = HashSet(),
    ) {
        /** Of the shoppers who dwelled here, the share who bought something taken from here (requirement 8). */
        val dwellToBuyShare: Double get() = if (dwelled.isEmpty()) 0.0 else dwelledAndBoughtHere.size.toDouble() / dwelled.size
    }

    fun row(code: String): Row = rows[code] ?: Row()

    companion object {
        fun of(records: List<ShopperRecord>): FixtureLedger {
            val m = HashMap<String, Row>()
            records.forEach { r ->
                val boughtFrom = r.lines.map { it.cell.fixture }.toSet()
                r.visits.forEach { v ->
                    val row = m.getOrPut(v.fixture) { Row() }
                    if (v.kind == Visit.Kind.BROWSE || v.kind == Visit.Kind.GRAB) row.stops++
                    if (v.impression) {
                        row.impressions++
                        row.dwellMs += v.dwellMs
                        row.platformDurationMs += v.dwellMs + ShopperAgent.APPROACH_MS
                        row.dwelled += r.id
                        if (v.fixture in boughtFrom) row.dwelledAndBoughtHere += r.id
                    }
                    if (v.kind == Visit.Kind.TRY_ON) row.tryOns++ else row.engagements += v.pickups.size
                }
                r.lines.forEach { l ->
                    val row = m.getOrPut(l.cell.fixture) { Row() }
                    row.units++
                    row.revenueUsd += l.cell.priceUsd
                }
            }
            return FixtureLedger(m)
        }
    }
}

/** Predicted impressions per (shopper, fixture) from the recorded samples — the oracle's independent check. */
fun oracleImpressions(fixtures: FixtureSet, streams: Map<String, List<ImpressionOracle.Sample>>): Map<String, List<String>> {
    val oracle = ImpressionOracle()
    return streams.mapValues { (_, s) -> oracle.run(fixtures, s.sortedBy { it.tsMs }).map { it.fixtureCode } }
}

/** Instant helper for plan timelines expressed as a local date + hour at the site. */
fun BehaviorModel.at(date: LocalDate, hour: Int): Instant = ZonedDateTime.of(date, LocalTime.of(hour, 0), zone).toInstant()
