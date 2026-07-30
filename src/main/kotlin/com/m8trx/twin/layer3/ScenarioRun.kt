package com.m8trx.twin.layer3

import com.m8trx.twin.domain.CustomerEntered
import com.m8trx.twin.domain.SaleCompleted
import com.m8trx.twin.layer1.FixtureSet
import com.m8trx.twin.layer1.ImpressionOracle
import com.m8trx.twin.layer1.ZoneRoleResolver
import com.m8trx.twin.layer2.Journeys
import com.m8trx.twin.layer2.StoreCatalog
import com.m8trx.twin.runtime.FailurePolicy
import com.m8trx.twin.runtime.GeneratorContext
import com.m8trx.twin.runtime.QueueScheduler
import com.m8trx.twin.runtime.RecordingSink
import com.m8trx.twin.runtime.SimpleClock
import com.m8trx.twin.runtime.SimpleEventBus
import com.m8trx.twin.runtime.forkRng
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Headless scenario run at `rate = +∞` — generate a whole day, reconcile it, and oracle-check the emitted
 * dwell streams, all offline. Nothing touches the edge.
 *
 * This is the loop that makes iteration cheap: the oracle was validated 7/7 against the live twin edge
 * (including both negative controls and a DB-confirmed 5/5), so its verdict on a generated day is
 * trustworthy without publishing a single event.
 */
class ScenarioRun(
    private val storeCode: String = "dec-us-denver",
    private val chainDir: Path = Path.of("reference/data/chain"),
    private val modelPath: Path = Path.of("reference/data/store-operating-model.json"),
) {
    private val log = LoggerFactory.getLogger(ScenarioRun::class.java)

    data class Result(
        val visitors: Int,
        val transactions: Int,
        val units: Int,
        val revenueUsd: Double,
        val expectedVisitors: Double,
        val reconciliation: Reconciliation.Report,
        val emittedSamples: Int,
        val predictedImpressions: Int,
        val shoppersWithNoImpression: Int,
        val schedulerErrors: Int,
        /**
         * Fixture codes that would carry at least one impression over this day — i.e. exactly what a
         * fixture heatmap of the run would light up.
         *
         * Tracked because the S15 full day covered 97 of 115 and the 18 it missed looked, on a heatmap,
         * like the platform silently dropping them. A coverage number turns that into an assertion.
         */
        val fixturesCovered: Set<String>,
        /** Every fixture the impression pipeline can see — the denominator for [fixturesCovered]. */
        val fixturesVisible: Set<String>,
    ) {
        val fixturesNeverBrowsed: Set<String> get() = fixturesVisible - fixturesCovered
        val coveragePct: Double get() = if (fixturesVisible.isEmpty()) 0.0 else fixturesCovered.size * 100.0 / fixturesVisible.size
    }

    /**
     * The generated dwell streams for a day, keyed by customer. Same seed → identical output, so this can
     * be called after [run] to get the emissions without threading them through the result.
     */
    fun streamsFor(
        date: LocalDate,
        seed: Long,
        populationScale: Double,
        zone: ZoneId = ZoneId.of("America/Denver"),
    ): Map<String, List<ImpressionOracle.Sample>> {
        val sink = RecordingSink()
        generate(date, seed, populationScale, zone, sink)
        return sink.streams
    }

    /**
     * Peak size of core's impression-cache working set on the PUBLISHED (compressed) timeline.
     *
     * Core creates a `FixtureImpression` the moment both clocks cross `millisTillImpression`, then re-`put`s
     * it on every subsequent sample, and publishes **on `expireAfterWrite` expiry** (~10s). So every
     * in-flight `(shopper, fixture)` pair is a live cache entry, and the peak count is the concurrency the
     * cache actually has to hold.
     *
     * This matters because pacing compresses ARRIVALS by [compress] while leaving episode durations
     * untouched — which is correct for the rule, but it means shoppers overlap `compress`-times more than
     * they do in store time. A day that reconciles perfectly can still present the edge with a working set
     * far larger than any single-episode test ever did.
     *
     * Returns `(peak concurrent impression entries, peak concurrent shoppers)`. Windowing mirrors
     * `DayDrive`: slice in store time, then re-base arrivals by `offset / compress`.
     */
    fun cacheWorkingSet(
        date: LocalDate,
        seed: Long,
        populationScale: Double,
        compress: Double,
        fromHour: Int? = null,
        toHour: Int? = null,
        cacheTtlMs: Long = 10_000L,
        zone: ZoneId = ZoneId.of("America/Denver"),
    ): Triple<Int, Int, Int> {
        val fixtures = FixtureSet.load(chainDir.resolve("stores/$storeCode/layout.json"))
        val all = streamsFor(date, seed, populationScale, zone)
        val openHour = LocalTime.parse(OperatingModel.load(modelPath).store.operatingHours.open).hour
        val dayStart = all.values.flatten().minOf { it.tsMs }

        val sliced = if (fromHour == null && toHour == null) {
            all
        } else {
            val lo = dayStart + ((fromHour ?: openHour) - openHour) * 3_600_000L
            val hi = dayStart + ((toHour ?: 24) - openHour) * 3_600_000L
            all.filterValues { it.minOf { s -> s.tsMs } in lo until hi }
        }
        if (sliced.isEmpty()) return Triple(0, 0, 0)
        val sliceStart = sliced.values.flatten().minOf { it.tsMs }

        val oracle = ImpressionOracle()
        // (start, end) of every live cache entry and every session, on the compressed timeline.
        val entries = mutableListOf<Pair<Long, Long>>()
        val sessions = mutableListOf<Pair<Long, Long>>()
        var impressions = 0
        sliced.forEach { (_, samples) ->
            val ordered = samples.sortedBy { it.tsMs }
            val origStart = ordered.first().tsMs
            // Same arrival shift DayDrive applies; intra-episode spacing is untouched, so an interval's
            // DURATION carries over unchanged and only its start moves.
            val shift = origStart - (sliceStart + ((origStart - sliceStart) / compress).toLong())
            sessions += (ordered.first().tsMs - shift) to (ordered.last().tsMs - shift + cacheTtlMs)
            oracle.run(fixtures, ordered).forEach { p ->
                impressions++
                entries += (p.firstDwellMs - shift) to (p.lastDwellMs - shift + cacheTtlMs)
            }
        }
        return Triple(peakOverlap(entries), peakOverlap(sessions), impressions)
    }

    /**
     * Impressions vs **distinct `(shopper, fixture)` pairs** for a run.
     *
     * The gap between these two numbers is the count of *repeat* visits: a journey draws fixtures with
     * `pool.random()`, so a shopper can return to a rack they already browsed. Core treats that as a new
     * impression (a reset nulls the id, so leave-and-return fires again) and publishes one event per visit —
     * but if the row is keyed on `(person_session, fixture)` rather than on the event, the second visit
     * UPDATES the first instead of inserting, and the table ends up holding distinct pairs, not events.
     *
     * That makes the NATS-vs-DB difference measurable offline and falsifiable: if distinct pairs match the
     * persisted row count, nothing was lost and the two numbers are counting different things.
     */
    fun repeatVisitProfile(
        date: LocalDate,
        seed: Long,
        populationScale: Double,
        fromHour: Int? = null,
        toHour: Int? = null,
        zone: ZoneId = ZoneId.of("America/Denver"),
    ): Triple<Int, Int, Int> {
        val fixtures = FixtureSet.load(chainDir.resolve("stores/$storeCode/layout.json"))
        val all = streamsFor(date, seed, populationScale, zone)
        val openHour = LocalTime.parse(OperatingModel.load(modelPath).store.operatingHours.open).hour
        val dayStart = all.values.flatten().minOf { it.tsMs }
        val sliced = if (fromHour == null && toHour == null) {
            all
        } else {
            val lo = dayStart + ((fromHour ?: openHour) - openHour) * 3_600_000L
            val hi = dayStart + ((toHour ?: 24) - openHour) * 3_600_000L
            all.filterValues { it.minOf { s -> s.tsMs } in lo until hi }
        }

        val oracle = ImpressionOracle()
        var impressions = 0
        val pairs = mutableSetOf<Pair<String, String>>()
        sliced.forEach { (customer, samples) ->
            oracle.run(fixtures, samples.sortedBy { it.tsMs }).forEach { p ->
                impressions++
                pairs += customer to p.fixtureCode
            }
        }
        return Triple(impressions, pairs.size, sliced.size)
    }

    /** Max number of intervals alive at once — sweep the endpoints. */
    private fun peakOverlap(intervals: List<Pair<Long, Long>>): Int {
        val events = intervals.flatMap { listOf(it.first to 1, it.second to -1) }.sortedWith(
            compareBy({ it.first }, { -it.second }),
        )
        var cur = 0
        var peak = 0
        events.forEach {
            cur += it.second
            if (cur > peak) peak = cur
        }
        return peak
    }

    private data class Tally(var visitors: Int = 0, var transactions: Int = 0, var units: Int = 0, var revenue: Double = 0.0, var errors: Int = 0)

    /** Shared generation path — deterministic from (date, seed, scale). */
    private fun generate(date: LocalDate, seed: Long, populationScale: Double, zone: ZoneId, sink: RecordingSink): Tally {
        val model = OperatingModel.load(modelPath)
        val layout = chainDir.resolve("stores/$storeCode/layout.json")
        val fixtures = FixtureSet.load(layout)
        val zones = ZoneRoleResolver.resolve(layout).zones
        val catalog = StoreCatalog.load(chainDir.resolve("stores/$storeCode/assortment.csv"))
        val journeys = Journeys(fixtures, zones, catalog, model)

        val openHour = LocalTime.parse(model.store.operatingHours.open)
        val clock = SimpleClock(ZonedDateTime.of(date, openHour, zone).toInstant())
        val scheduler = QueueScheduler(clock, FailurePolicy.SKIP_AND_LOG)
        val bus = SimpleEventBus()
        val tally = Tally()

        bus.subscribe(CustomerEntered::class) { tally.visitors++ }
        bus.subscribe(SaleCompleted::class) { s ->
            tally.transactions++
            tally.units += s.units
            tally.revenue += s.totalUsd
        }

        val generator = TrafficGenerator(model, journeys, TrafficGenerator.Params(date = date, zone = zone, populationScale = populationScale))
        val ctx = GeneratorContext(clock, scheduler, bus, forkRng(seed, generator.id), log, sink)
        generator.start(ctx)
        scheduler.drain()
        generator.stop(ctx)
        tally.errors = scheduler.errorCount
        return tally
    }

    fun run(date: LocalDate, seed: Long, populationScale: Double, zone: ZoneId = ZoneId.of("America/Denver")): Result {
        val model = OperatingModel.load(modelPath)
        val fixtures = FixtureSet.load(chainDir.resolve("stores/$storeCode/layout.json"))
        val sink = RecordingSink()
        val tally = generate(date, seed, populationScale, zone, sink)

        val dayType = if (date.dayOfWeek.value >= 6) model.traffic.visitorsPerDay.weekend else model.traffic.visitorsPerDay.weekday
        val expectedVisitors = dayType * populationScale

        // Oracle-check every shopper's emitted dwell stream. A shopper who browsed but would produce no
        // impression is the silent failure this whole harness exists to catch.
        val oracle = ImpressionOracle()
        var predicted = 0
        var silent = 0
        val covered = mutableSetOf<String>()
        sink.streams.forEach { (_, samples) ->
            val ps = oracle.run(fixtures, samples)
            predicted += ps.size
            ps.forEach { covered += it.fixtureCode }
            if (ps.isEmpty()) silent++
        }

        val report = Reconciliation.check(
            model,
            expectedVisitors,
            Reconciliation.Observed(tally.visitors, tally.transactions, tally.units, tally.revenue),
        )

        return Result(
            tally.visitors, tally.transactions, tally.units, tally.revenue, expectedVisitors, report,
            sink.totalSamples, predicted, silent, tally.errors,
            fixturesCovered = covered,
            fixturesVisible = fixtures.impressionVisible.map { it.code }.toSet(),
        )
    }
}
