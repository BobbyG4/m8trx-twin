package com.m8trx.twin.history

import com.m8trx.twin.history.HistoryCsv.fixed
import com.m8trx.twin.history.HistoryCsv.money
import com.m8trx.twin.history.HistoryCsv.row
import com.m8trx.twin.layer1.ZoneAffinityModel
import com.m8trx.twin.layer1.ZoneRole
import com.m8trx.twin.layer3.OperatingModel
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The history generator — BRIEF-TWIN-HISTORY-GENERATOR-2026-10-11 § 2/§ 3. Writes the eleven-file contract
 * migration 259 loads. Offline: no network, no credentials, no mother.
 *
 * Determinism: every store-day runs from `derive(seed, slug, date, …)`, independent of every other, so the
 * thread pool's ordering cannot leak into the output; rows are assembled sorted by (store, date). Same seed
 * → byte-identical files (asserted by `historySelfTest`).
 */
class HistoryGenerator(
    val params: Params,
    private val chainDir: Path = Path.of("reference/data/chain"),
    private val modelPath: Path = Path.of("reference/data/store-operating-model.json"),
    keysPath: Path = ChainKeys.DEFAULT_PATH,
) {
    private val log = LoggerFactory.getLogger(HistoryGenerator::class.java)

    data class Params(
        val seed: Long,
        val from: LocalDate,
        /** inclusive LOCAL date; a store-day is generated only if it also closes before [cutoff] */
        val to: LocalDate,
        val out: Path,
        val stores: List<String>? = null,
        val threads: Int = Runtime.getRuntime().availableProcessors(),
        /** MANIFEST `generator_git_sha` */
        val gitSha: String = "unknown",
        /** MANIFEST `generated_at` — the generator commit's time by default, so the manifest is reproducible too */
        val generatedAt: String = "unknown",
    ) {
        /**
         * Migration 259 refuses a commerce window ending after 2026-10-05T00:00Z (`TO + 1 day`, UTC). A US
         * store's local Oct 4 closes at 03:00–04:00Z on Oct 5, so its LAST day is Oct 3 — whole days are
         * dropped, never clipped (a half day would read as a sales dip on the dashboard).
         */
        val cutoff: Instant get() = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
    }

    val keys: ChainKeys = ChainKeys.load(keysPath)
    val model: OperatingModel = OperatingModel.load(modelPath)
    private val referenceArea = keys.store(REFERENCE_STORE).areaSqm
    val story = StoryCalendar(model, referenceArea)
    private val openTime = LocalTime.parse(model.store.operatingHours.open)
    private val openHours = model.store.operatingHours.openHours
    private val baseBuyerShare = HistoryDayRunner.modelBuyerShare(model)

    val stores: List<ChainKeys.Store> = keys.stores.filter { params.stores == null || it.slug in params.stores }

    // ── calendar ──────────────────────────────────────────────────────────────

    fun closeUtc(store: ChainKeys.Store, date: LocalDate): Instant =
        ZonedDateTime.of(date, openTime, store.zone).plusHours(openHours.toLong()).toInstant()

    /** The store's generated days: open, inside [from, to], and closing at or before the cutoff. */
    fun days(store: ChainKeys.Store): List<LocalDate> = generateSequence(params.from) { it.plusDays(1) }
        .takeWhile { !it.isAfter(params.to) }
        .filter { story.isOpen(store, it) && !closeUtc(store, it).isAfter(params.cutoff) }
        .toList()

    fun lastDay(store: ChainKeys.Store): LocalDate? = days(store).lastOrNull()

    /** Expected gate alarms per store-week (brief § 2: 2–4). */
    private val alarmsPerWeek = 3.0

    fun plan(store: ChainKeys.Store, date: LocalDate): HistoryDayRunner.DayPlan {
        val weather = java.util.Random(HistoryCsv.derive(params.seed, store.slug, date, "weather"))
        val noise = exp(WEATHER_SIGMA * weather.nextGaussian() - WEATHER_SIGMA * WEATHER_SIGMA / 2)
        val expectedDayVisitors = story.expectedVisitors(store, date)
        // a share, not a count: ~3 alarms a week whatever the store's size (the model's 0.003 would give ~20)
        val shoplift = (alarmsPerWeek / 7.0 / expectedDayVisitors.coerceAtLeast(50.0)).coerceAtMost(0.01)
        return HistoryDayRunner.DayPlan(
            date = date,
            seed = HistoryCsv.derive(params.seed, store.slug, date, "day"),
            dayType = story.dayType(store, date),
            seasonality = story.seasonality(story.country(store), date),
            populationScale = story.visitMultiplier(store, date) * noise,
            buyerShare = buyerShare(store, date),
            shopliftShare = shoplift,
            departmentWeights = story.departmentWeights(date),
        )
    }

    /**
     * The day's expected footfall GIVEN its weather draw — the model's day-type volume × seasonality × every
     * multiplier the generator was handed. What remains between this and the generated count is sampling
     * noise only (Poisson arrivals), which is what the ±5 % self-test bounds.
     */
    fun expectedVisitors(plan: HistoryDayRunner.DayPlan): Double = when (plan.dayType) {
        com.m8trx.twin.layer3.TrafficGenerator.DayType.WEEKDAY -> model.traffic.visitorsPerDay.weekday
        com.m8trx.twin.layer3.TrafficGenerator.DayType.WEEKEND -> model.traffic.visitorsPerDay.weekend
    } * plan.seasonality * plan.populationScale

    fun buyerShare(store: ChainKeys.Store, date: LocalDate, withStory: Boolean = true): Double =
        baseBuyerShare * story.conversionMultiplier(store, date, withStory)

    // ── output rows ───────────────────────────────────────────────────────────

    /** A store-day reduced to its CSV rows and the few facts the derived files need. */
    class DayRows(
        val slug: String,
        val date: LocalDate,
        val commerceHourly: List<String>,
        val commerceDept: List<String>,
        val trafficHourly: List<String>,
        val trafficDept: List<String>,
        val behavioral: List<String>,
        val visits: Int,
        val transactions: Int,
        val units: Int,
        val cents: Long,
        val expectedVisits: Double,
        val expectedTransactions: Double,
        val alarms: List<HistoryDayRunner.Alarm>,
        /** fixture → (units sold, a representative EAN), most-sold first */
        val topSold: List<Triple<String, Int, String>>,
        val deptCents: Map<String, Long>,
        val schedulerErrors: Int,
    )

    private fun reduce(runner: HistoryDayRunner, plan: HistoryDayRunner.DayPlan, r: HistoryDayRunner.DayResult): DayRows {
        val store = runner.store
        val slug = store.slug
        val ws = r.openUtc
        val checkoutMin = (ZoneAffinityModel.dwellMedianMin(ZoneRole.CHECKOUT) ?: 3.0) * MEAN_DWELL_FACTOR
        val associatesPeak = (store.areaSqm / staffing.sqmPerAssociate).roundToInt().coerceAtLeast(2)
        val associatesOff = (associatesPeak * staffing.offpeak.toDouble() / staffing.peak).roundToInt().coerceAtLeast(1)
        val curve = model.traffic.hourlyCurve.let { if (plan.dayType.name == "WEEKEND") it.weekend else it.weekday }
        val peakHours = curve.withIndex().sortedByDescending { it.value }.take(4).map { it.index }.toSet()
        val staffRng = java.util.Random(HistoryCsv.derive(params.seed, slug, r.date, "staff"))

        val commerceHourly = ArrayList<String>(openHours)
        val trafficHourly = ArrayList<String>(openHours)
        r.hours.forEachIndexed { i, h ->
            val a = ws.plusSeconds(3600L * i)
            val b = a.plusSeconds(3600)
            commerceHourly += row(
                slug, a, b, h.units, money(h.cents), h.transactions,
                if (h.visits > 0) fixed(h.transactions.toDouble() / h.visits, 4) else null,
                if (h.transactions > 0) money(Math.round(h.cents.toDouble() / h.transactions)) else null,
                if (h.transactions > 0) fixed(h.units.toDouble() / h.transactions, 3) else null,
            )
            // Little's law at the till: mean occupancy = arrivals/hour × mean checkout dwell (h)
            val queue = h.transactions * checkoutMin / 60.0
            val associates = if (i in peakHours) associatesPeak else associatesOff
            val staffMoves = associates * STAFF_MOVES_PER_HOUR + staffRng.nextInt(associates + 1)
            trafficHourly += row(
                slug,
                a,
                b,
                h.visits,
                h.transits,
                if (h.sessions > 0) fixed(h.sessionMsSum / 1000.0 / h.sessions, 1) else null,
                fixed(queue, 2),
                staffMoves,
            )
        }

        val dayStart = ws
        val dayEnd = ws.plusSeconds(3600L * openHours)
        val commerceDept = store.departments.sortedBy { it.name }.map { z ->
            val d = r.departments[z.code]
            row(slug, z.name, dayStart, dayEnd, d?.units ?: 0, money(d?.cents ?: 0), d?.transactions ?: 0)
        }
        val trafficDept = trafficRegions(runner).map { z ->
            val d = r.departments[z.code]
            val n = d?.shoppers?.size ?: 0
            row(slug, z.name, dayStart, dayEnd, n, if (n > 0) fixed(d!!.dwellMs / 1000.0 / n, 1) else fixed(0.0, 1))
        }
        val behavioral = runner.behavioralFixtures.map { code ->
            val f = r.fixtures[code]
            row(slug, code, dayStart, dayEnd, f?.shoppers?.size ?: 0, fixed((f?.dwellMs ?: 0) / 1000.0, 1), f?.engagement ?: 0)
        }

        val soldBy = r.sold.groupBy { it.fixture }
        val topSold = soldBy.map { (fx, l) -> Triple(fx, l.size, l.first().ean) }
            .filter { store.byCode.containsKey(it.first) }
            .sortedWith(compareByDescending<Triple<String, Int, String>> { it.second }.thenBy { it.first })
            .take(8)

        val expectedVisits = expectedVisitors(plan)
        return DayRows(
            slug, r.date, commerceHourly, commerceDept, trafficHourly, trafficDept, behavioral,
            r.visits, r.transactions, r.units, r.cents,
            expectedVisits, expectedVisits * plan.buyerShare,
            r.alarms.sortedBy { it.at }, topSold,
            store.departments.associate { it.name to (r.departments[it.code]?.cents ?: 0L) },
            r.schedulerErrors,
        )
    }

    /** Regions shoppers can browse — those with at least one keyed, pipeline-visible fixture inside. */
    private fun trafficRegions(runner: HistoryDayRunner): List<ChainKeys.Zone> = trafficRegionCache.getOrPut(runner.store.slug) {
        val withFixtures = runner.fixtureRegionCodes()
        runner.store.regions.filter { it.code in withFixtures }.sortedBy { it.name }
    }
    private val trafficRegionCache = java.util.concurrent.ConcurrentHashMap<String, List<ChainKeys.Zone>>()

    private data class Staffing(val peak: Int, val offpeak: Int, val sqmPerAssociate: Double)

    /** `staffing` from the operating model — read here rather than widening [OperatingModel] for one consumer. */
    private val staffing: Staffing = run {
        val n = com.m8trx.twin.connect.model.ConnectMappers.camel.readTree(Files.readAllBytes(modelPath)).path("staffing")
        Staffing(
            n.path("associates_on_floor_peak").asInt(5),
            n.path("associates_on_floor_offpeak").asInt(3),
            n.path("sqm_per_associate").asDouble(120.0),
        )
    }

    // ── the run ───────────────────────────────────────────────────────────────

    class Output(val days: List<DayRows>, val files: Map<String, Int>, val wallMs: Long)

    fun generateDays(): List<DayRows> {
        val runners = stores.associate { it.slug to HistoryDayRunner(it, model, chainDir, story::departmentWeights) }
        runners.values.forEach {
            log.info(
                "  {} sampler targets: units {} · line price \${} (model {} · \${}; tagged SKUs {})",
                it.store.slug,
                "%.3f".format(java.util.Locale.ROOT, it.samplerUnitTarget),
                "%.2f".format(java.util.Locale.ROOT, it.samplerLinePriceTarget),
                model.basket.unitsPerTxn.value,
                model.basket.avgLinePriceUsd.value,
                it.taggedSkuCount,
            )
        }
        val jobs = stores.flatMap { s -> days(s).map { s to it } }
        log.info(
            "history: {} store-days across {} stores, {}..{} (cutoff {}), seed {}, {} threads",
            jobs.size,
            stores.size,
            params.from,
            params.to,
            params.cutoff,
            params.seed,
            params.threads,
        )
        val pool = Executors.newFixedThreadPool(params.threads)
        val done = java.util.concurrent.atomic.AtomicInteger()
        try {
            val futures = jobs.map { (s, d) ->
                pool.submit(
                    Callable {
                        val runner = runners.getValue(s.slug)
                        val p = plan(s, d)
                        reduce(runner, p, runner.run(p)).also {
                            val n = done.incrementAndGet()
                            if (n % 250 == 0 || n == jobs.size) log.info("  … {}/{} store-days", n, jobs.size)
                        }
                    },
                )
            }
            return futures.map { it.get() }.sortedWith(compareBy({ it.slug }, { it.date }))
        } finally {
            pool.shutdown()
        }
    }

    fun generate(): Output {
        val t0 = System.currentTimeMillis()
        val days = generateDays()
        val derived = DerivedHistory(this, chainDir, days)
        val out = params.out
        Files.createDirectories(out)
        val counts = LinkedHashMap<String, Int>()
        fun w(name: String, header: List<String>, rows: List<String>) {
            counts[name] = HistoryCsv.write(out.resolve(name), header, rows)
        }
        w("commerce_store_hourly.csv", COMMERCE_HOURLY_HEADER, days.flatMap { it.commerceHourly })
        w("commerce_department_daily.csv", COMMERCE_DEPT_HEADER, days.flatMap { it.commerceDept })
        w("traffic_store_hourly.csv", TRAFFIC_HOURLY_HEADER, days.flatMap { it.trafficHourly })
        w("traffic_department_daily.csv", TRAFFIC_DEPT_HEADER, days.flatMap { it.trafficDept })
        w("behavioral_fixture_daily.csv", BEHAVIORAL_HEADER, days.flatMap { it.behavioral })
        w("compliance_weekly.csv", COMPLIANCE_HEADER, derived.compliance())
        w("tasks.csv", TASKS_HEADER, derived.tasks())
        w("alarms.csv", ALARMS_HEADER, derived.alarms())
        w("stocktakes.csv", STOCKTAKES_HEADER, derived.stocktakes())
        w("kpi_targets.csv", KPI_HEADER, derived.kpiTargets())
        val manifest = counts.map { (file, n) ->
            row(file, n, HistoryCsv.sha256(out.resolve(file)), params.seed, params.generatedAt, params.gitSha)
        }
        HistoryCsv.write(out.resolve("MANIFEST.csv"), MANIFEST_HEADER, manifest)
        return Output(days, counts, System.currentTimeMillis() - t0)
    }

    /** Planned (no store story) monthly visits / sales — the KPI plan the dashboards compare against. */
    fun plannedDaily(store: ChainKeys.Store): Triple<Double, Double, Double> {
        val ds = days(store)
        val visits = ds.sumOf { story.expectedVisitors(store, it, withStory = false) }
        val txns = ds.sumOf { story.expectedVisitors(store, it, withStory = false) * buyerShare(store, it, withStory = false) }
        val n = ds.size.coerceAtLeast(1).toDouble()
        return Triple(visits / n, txns / n, txns / visits.coerceAtLeast(1.0))
    }

    companion object {
        const val REFERENCE_STORE = "dec-us-denver"
        const val WEATHER_SIGMA = 0.07
        const val STAFF_MOVES_PER_HOUR = 6

        /** mean of the journeys' 0.55..1.80 uniform dwell factor */
        const val MEAN_DWELL_FACTOR = 1.175

        val COMMERCE_HOURLY_HEADER = listOf(
            "site_slug", "window_start", "window_end", "units_sold", "sales_value", "transaction_count",
            "conversion_rate", "avg_basket_value", "units_per_transaction",
        )
        val COMMERCE_DEPT_HEADER = listOf("site_slug", "zone_name", "window_start", "window_end", "units_sold", "sales_value", "transaction_count")
        val TRAFFIC_HOURLY_HEADER = listOf(
            "site_slug",
            "window_start",
            "window_end",
            "visit_count",
            "transit_count",
            "dwell_time_seconds",
            "queue_length",
            "staff_movement_count",
        )
        val TRAFFIC_DEPT_HEADER = listOf("site_slug", "zone_name", "window_start", "window_end", "visit_count", "dwell_time_seconds")
        val BEHAVIORAL_HEADER = listOf("site_slug", "fixture_code", "window_start", "window_end", "impression", "dwell", "engagement")
        val COMPLIANCE_HEADER = listOf("site_slug", "week_start", "score_pct", "targets_checked", "non_compliant", "drift_detected", "remediated")
        val TASKS_HEADER = listOf(
            "site_slug", "zone_name_or_code", "type", "priority", "title", "status", "created_at", "due_at", "started_at", "completed_at",
            "assigned_to_role",
        )
        val ALARMS_HEADER = listOf("site_slug", "created_at", "epc", "sku", "gate_code", "severity", "resolved_at", "resolution")
        val STOCKTAKES_HEADER = listOf(
            "site_slug", "started_at", "completed_at", "mode", "scanned", "expected", "missing", "misplaced", "ghost", "financial_value_missing",
        )
        val KPI_HEADER = listOf("site_slug", "metric_type", "target_value", "reference_period", "currency")
        val MANIFEST_HEADER = listOf("file", "rows", "sha256", "seed", "generated_at", "generator_git_sha")

        /** Sampling helper — a log-normal with mean 1. */
        fun lognormal1(rng: java.util.Random, sigma: Double) = exp(sigma * rng.nextGaussian() - sigma * sigma / 2)
    }
}
