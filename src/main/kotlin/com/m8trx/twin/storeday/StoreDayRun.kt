package com.m8trx.twin.storeday

import com.m8trx.twin.behavior.BehaviorModel
import com.m8trx.twin.behavior.BehaviorParams
import com.m8trx.twin.connect.model.ConnectMappers
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.TreeMap

/**
 * `./gradlew storeDayRun` — N store days offline (slice 2a). RECORD ONLY: it writes files under `out/day/<slug>/` and never
 * opens a socket; nothing in this package can send. Slice 2b: any of the ten sales-floor stores, or `all`.
 *
 * Env: `M8TRX_STORE_DAY_SITE` (dec-us-denver, or `all` for the ten stores) · `M8TRX_STORE_DAY_FROM` (2026-11-02, a Monday) · `M8TRX_STORE_DAY_DAYS` (28) ·
 * `M8TRX_STORE_DAY_SEED` (the behavior file's seed) · `M8TRX_STORE_DAY_OUT` (out/day) · `M8TRX_STORE_DAY_STATE` (a snapshot base
 * path to resume from, no extension; default: the offline initial snapshot) · `M8TRX_STORE_DAY_SAMPLES` (false) ·
 * knobs `M8TRX_STORE_DAY_CALENDAR` (true) · `M8TRX_STORE_DAY_MISPLACE` (0.005) · `_STAFF_UPH` (120) · `_PREOPEN_STOCKERS` (3) · `_TOPUP_STOCKERS` (1) ·
 * `_TOPUP_THRESHOLD` (0.60) · `_QUIET_HOURS` (3).
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.storeday.StoreDayRun")

fun env(k: String, d: String) = System.getenv(k)?.takeIf { it.isNotBlank() } ?: d

fun knobsFromEnv(): Knobs {
    val d = Knobs()
    return Knobs(
        misplaceProb = env("M8TRX_STORE_DAY_MISPLACE", d.misplaceProb.toString()).toDouble(),
        staffUnitsPerHour = env("M8TRX_STORE_DAY_STAFF_UPH", d.staffUnitsPerHour.toString()).toDouble(),
        preOpenStockers = env("M8TRX_STORE_DAY_PREOPEN_STOCKERS", d.preOpenStockers.toString()).toInt(),
        topUpStockers = env("M8TRX_STORE_DAY_TOPUP_STOCKERS", d.topUpStockers.toString()).toInt(),
        topUpThreshold = env("M8TRX_STORE_DAY_TOPUP_THRESHOLD", d.topUpThreshold.toString()).toDouble(),
        quietHours = env("M8TRX_STORE_DAY_QUIET_HOURS", d.quietHours.toString()).toInt(),
        samples = env("M8TRX_STORE_DAY_SAMPLES", "false").toBoolean(),
        calendar = env("M8TRX_STORE_DAY_CALENDAR", d.calendar.toString()).toBoolean(),
    )
}

/** Where the committed calibration lives (written by `storeDayCalibrate`). */
fun calibrationPath(slug: String): Path = Path.of("reference/data/chain/stores/$slug/model/calibration.json")

fun loadCalibration(slug: String): Calibration = calibrationPath(slug).takeIf { Files.exists(it) }?.let(Calibration::load) ?: Calibration.NEUTRAL

fun buildSim(site: String, cal: Calibration = loadCalibration(site), knobs: Knobs = Knobs()): StoreDaySim {
    require("office" !in site) {
        "office sites are refused: the twin has no office layout and the only edge that sees the office is production hardware ($site)"
    }
    require(site in StoreState.STORE_SLUGS) { "'$site' is not one of the ten sales-floor stores ${StoreState.STORE_SLUGS}" }
    val model = StoreDayModel(site)
    return StoreDaySim(model, BehaviorModel(BehaviorParams.forSite(site)), cal, knobs)
}

/** Running totals over a multi-day run: the numbers the PR body and the self-test quote. */
class RunTally(val sim: StoreDaySim) {
    class Day(val date: LocalDate, val summary: Map<String, Any>, val factor: Double, val closed: Boolean, val weekend: Boolean)

    val days = ArrayList<Day>()
    val unitsByDept = TreeMap<String, Int>()
    val unitsByEan = HashMap<String, Int>()
    val lostByDept = TreeMap<String, Int>()
    val soldEpcs = HashSet<String>()
    var duplicateSold = 0
    var visitors = 0
    var transactions = 0
    var units = 0
    var valueUsd = 0.0
    var stockOutEvents = 0
    var lost = 0
    var lostValue = 0.0
    var received = 0
    var receiveRequests = 0
    var moves = 0
    var moveBatches = 0
    var misplacements = 0
    val weekUnits = TreeMap<Int, Int>()
    val weekdayUnits = ArrayList<Int>()
    val weekendUnits = ArrayList<Int>()
    var closedDays = 0
    var weekdayVisits = 0
    var weekendVisits = 0
    val weekdayVisitors = ArrayList<Double>()
    val weekendVisitors = ArrayList<Double>()
    var floorCloseMin = Int.MAX_VALUE
    var floorCloseMax = 0
    var storeUnitsMin = Int.MAX_VALUE
    var storeUnitsMax = 0
    var conservationOk = true
    val firstDate: LocalDate? get() = days.firstOrNull()?.date

    fun add(r: DayResult) {
        val s = StoreDayOutput.summary(sim, r)
        val weekendShape = r.plan.shape?.weekend ?: (r.date.dayOfWeek.value >= 6)
        days +=
            Day(
                r.date,
                s,
                r.expectedVisitors / (sim.baseVisitors(weekendShape) * sim.model.visitScale) * r.plan.conversion,
                r.plan.closed,
                weekendShape,
            )
        visitors += r.records.size
        transactions += r.records.count { it.bought }
        units += r.saleUnits.size
        valueUsd += r.saleUnits.sumOf { it.priceUsd }
        r.saleUnits.forEach { u ->
            if (!soldEpcs.add(u.epc)) duplicateSold++
            unitsByEan.merge(u.ean, 1, Int::plus)
            unitsByDept.merge(sim.model.demand[u.ean]?.department ?: "?", 1, Int::plus)
        }
        r.records.forEach { rec ->
            stockOutEvents += rec.stockOuts.size
            rec.stockOuts.filter { it.lost }.forEach { o ->
                lost++
                lostValue += o.cell.priceUsd
                lostByDept.merge(sim.model.demand[o.cell.ean]?.department ?: "?", 1, Int::plus)
            }
        }
        received += r.receivedEpcs.size
        receiveRequests += r.receiveRequests
        moves += r.moves.sumOf { it.epcs.size }
        moveBatches += r.moves.size
        misplacements += r.misplacements
        val week = (days.size - 1) / 7 + 1
        weekUnits.merge(week, r.saleUnits.size, Int::plus)
        if (r.plan.closed) {
            closedDays++
        } else if (weekendShape) {
            weekendUnits += r.saleUnits.size
            weekendVisitors += r.expectedVisitors * r.plan.conversion
            weekendVisits += r.records.size
        } else {
            weekdayUnits += r.saleUnits.size
            weekdayVisitors += r.expectedVisitors * r.plan.conversion
            weekdayVisits += r.records.size
        }
        val floorClose = r.closeOnHand.entries.filter { it.key in sim.model.required }.sumOf { it.value }
        floorCloseMin = minOf(floorCloseMin, floorClose)
        floorCloseMax = maxOf(floorCloseMax, floorClose)
        storeUnitsMin = minOf(storeUnitsMin, r.endUnits)
        storeUnitsMax = maxOf(storeUnitsMax, r.endUnits)
        if (r.startUnits + r.receivedEpcs.size != r.endUnits + r.saleUnits.size) conservationOk = false
    }

    /** What a shopper can take: EANs some fixture holds. Off-floor EANs (a small store cannot shelve its whole range) cannot sell. */
    private val onFloor: Set<String> = sim.model.required.keys.map { it.ean }.toSet()

    fun deptTarget(): Map<String, Double> {
        val t = TreeMap<String, Double>()
        days.filter { !it.closed }.forEach { d ->
            sim.model.demand.values.filter { it.ean in onFloor }.forEach { r ->
                t.merge(
                    r.department,
                    (if (d.weekend) r.perDayWeekend else r.perDayWeekday) * d.factor,
                    Double::plus,
                )
            }
        }
        return t
    }
}

/** One store, [days] days from [from]: writes under [outRoot] and returns the tally. */
fun runStore(
    site: String,
    knobs: Knobs,
    from: LocalDate,
    days: Int,
    outRoot: Path,
    seedOverride: Long?,
    statePath: String?,
    write: Boolean = true,
): RunTally {
    val sim = buildSim(site, knobs = knobs)
    val seed = seedOverride ?: sim.behavior.params.seed
    val root = outRoot.resolve(site)
    val state = statePath?.takeIf { it.isNotBlank() }?.let { StoreState.load(Path.of(it)) } ?: StoreState.initial(sim.model)
    if (write && statePath.isNullOrBlank()) state.save(root.resolve("state/initial"))
    log.info(
        "storeDayRun (record only, sends nothing) {} {} + {} days seed={} units={} calendar={} calibration={}",
        site,
        from,
        days,
        seed,
        state.totalNotSold(),
        knobs.calendar,
        calibrationPath(site),
    )
    val tally = RunTally(sim)
    for (i in 0 until days) {
        val date = from.plusDays(i.toLong())
        val r = sim.simulate(state, date, seed)
        if (write) StoreDayOutput.write(root.resolve(date.toString()), sim, r)
        tally.add(r)
        val s = tally.days.last().summary
        log.info(
            "{} {} visitors={} tx={} units={} stockOuts={} lost={} received={} moves={} floorClose={}{}",
            date, date.dayOfWeek.name.take(3), s["visitors"], s["transactions"], s["units_sold"], s["stock_out_events"], s["lost_sales"],
            (s["deliveries"] as Map<*, *>)["units_received"], r.moves.sumOf { it.epcs.size }, s["floor_units_close"],
            if (r.plan.closed) " CLOSED (${r.plan.holiday ?: "no hours"})" else "",
        )
    }
    if (write) {
        state.save(root.resolve("state/latest"))
        Files.createDirectories(root)
        Files.writeString(root.resolve("run-summary.md"), RunReport.markdown(tally, from, days, seed, knobs))
    }
    return tally
}

fun main() {
    val site = env("M8TRX_STORE_DAY_SITE", "dec-us-denver")
    val knobs = knobsFromEnv()
    val from = LocalDate.parse(env("M8TRX_STORE_DAY_FROM", "2026-11-02"))
    val days = env("M8TRX_STORE_DAY_DAYS", "28").toInt()
    val seed = System.getenv("M8TRX_STORE_DAY_SEED")?.takeIf { it.isNotBlank() }?.toLong()
    val outRoot = Path.of(env("M8TRX_STORE_DAY_OUT", "out/day"))
    val state = System.getenv("M8TRX_STORE_DAY_STATE")
    if (site == "all") {
        val tallies = StoreState.STORE_SLUGS.map { runStore(it, knobs, from, days, outRoot, seed, null) }
        Files.createDirectories(outRoot)
        Files.writeString(outRoot.resolve("CHAIN-RUN.md"), ChainReport.markdown(tallies, from, days, knobs))
        log.info("done: {}", outRoot.resolve("CHAIN-RUN.md").toAbsolutePath())
    } else {
        runStore(site, knobs, from, days, outRoot, seed, state)
        log.info("done: {}", outRoot.resolve(site).toAbsolutePath())
    }
}

/** The per-store table the PR quotes: one row per store from a multi-store run. */
object ChainReport {
    private fun f(x: Double, d: Int = 1) = "%.${d}f".format(x)

    fun markdown(ts: List<RunTally>, from: LocalDate, days: Int, knobs: Knobs): String = buildString {
        appendLine("# Chain store-day run: ${ts.size} stores, $from + $days days")
        appendLine()
        appendLine("Offline, record only. Knobs: `$knobs`")
        appendLine()
        appendLine(
            "| store | visitors/day wkday | wkend | transactions | units | units by week (w4/w1) | floor at close (of plan) | store units band (of plan) | lost sales | deliveries (units) | moves (units) | misplaced % of units |",
        )
        appendLine("|---|---:|---:|---:|---:|---|---|---|---:|---:|---:|---:|")
        for (t in ts) {
            val m = t.sim.model
            val planned = m.stockPlan.sumOf { it.qty }
            val floorPlan = m.required.values.sum()
            val w = t.weekUnits
            appendLine(
                "| ${m.slug} | ${f(
                    t.weekdayVisits.toDouble() / maxOf(1, t.weekdayUnits.size),
                    0,
                )} | ${f(t.weekendVisits.toDouble() / maxOf(1, t.weekendUnits.size), 0)} " +
                    "| ${t.transactions} | ${t.units} | ${w.values.joinToString(
                        "/",
                    )} (${f(100.0 * w.getValue(w.keys.max()) / w.getValue(w.keys.min()), 0)} %) " +
                    "| ${t.floorCloseMin}..${t.floorCloseMax} ($floorPlan) | ${t.storeUnitsMin}..${t.storeUnitsMax} ($planned) " +
                    "| ${f(
                        100.0 * t.lost / maxOf(1, t.lost + t.units),
                    )} % | ${t.receiveRequests} req, ${t.received} | ${t.moves} | ${f(100.0 * t.misplacements / maxOf(1, t.units))} % |",
            )
        }
        appendLine()
        appendLine("Department share of units sold, simulated vs the demand target (percentage points in brackets):")
        appendLine()
        val depts = ts.flatMap { it.unitsByDept.keys }.toSortedSet()
        appendLine("| store | " + depts.joinToString(" | ") + " |")
        appendLine("|---|" + depts.joinToString("") { "---:|" })
        for (t in ts) {
            val target = t.deptTarget()
            val tt = target.values.sum()
            appendLine(
                "| ${t.sim.model.slug} | " + depts.joinToString(" | ") { d ->
                    val n = t.unitsByDept[d]
                    if (n == null) {
                        "-"
                    } else {
                        val share = 100.0 * n / t.units
                        val want = 100.0 * (target[d] ?: 0.0) / tt
                        "${f(share)} vs ${f(want)} (${"%+.1f".format(share - want)})"
                    }
                } + " |",
            )
        }
    }
}

object RunReport {
    fun markdown(t: RunTally, from: LocalDate, days: Int, seed: Long, knobs: Knobs): String = buildString {
        appendLine("# Store-day run: ${t.sim.model.slug}, $from + $days days, seed $seed")
        appendLine()
        appendLine("Offline, record only. Knobs: `$knobs`")
        appendLine()
        appendLine("| metric | value |")
        appendLine("|---|---:|")
        appendLine("| visitors | ${t.visitors} |")
        appendLine("| transactions | ${t.transactions} (${"%.1f".format(100.0 * t.transactions / t.visitors)} %) |")
        appendLine("| units sold | ${t.units} (${"%.2f".format(t.units.toDouble() / t.transactions)} per transaction) |")
        appendLine("| value sold | ${"%.0f".format(t.valueUsd)} USD |")
        appendLine("| stock-out events | ${t.stockOutEvents} |")
        appendLine(
            "| lost sales | ${t.lost} units, ${"%.0f".format(t.lostValue)} USD (${"%.1f".format(100.0 * t.lost / (t.lost + t.units))} % of demand) |",
        )
        appendLine("| deliveries | ${t.receiveRequests} receive requests, ${t.received} units |")
        appendLine("| moves | ${t.moves} units in ${t.moveBatches} batches (staff and misplacement) |")
        appendLine("| misplacements | ${t.misplacements} (${"%.1f".format(100.0 * t.misplacements / maxOf(1, t.units))} % of units sold) |")
        appendLine("| closed days | ${t.closedDays} |")
        appendLine("| floor units at close (plan slots) | ${t.floorCloseMin} .. ${t.floorCloseMax} |")
        appendLine("| store units (floor + back room) at close | ${t.storeUnitsMin} .. ${t.storeUnitsMax} |")
        appendLine("| units by week | ${t.weekUnits.entries.joinToString(", ") { "w${it.key}=${it.value}" }} |")
        appendLine()
        appendLine("| department | units | share | demand share |")
        appendLine("|---|---:|---:|---:|")
        val target = t.deptTarget()
        val tt = target.values.sum()
        t.unitsByDept.forEach { (d, n) ->
            appendLine("| $d | $n | ${"%.1f".format(100.0 * n / t.units)} % | ${"%.1f".format(100.0 * (target[d] ?: 0.0) / tt)} % |")
        }
    }
}

@Suppress("unused")
private val keepMapper = ConnectMappers.snake
