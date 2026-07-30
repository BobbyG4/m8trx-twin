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
