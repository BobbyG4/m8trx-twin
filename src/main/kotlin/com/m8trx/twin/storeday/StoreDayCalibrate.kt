package com.m8trx.twin.storeday

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.TreeMap

/**
 * `./gradlew storeDayCalibrate` — fit [Calibration] so a FULLY STOCKED day sells what `demand.csv` says, per department.
 *
 * Why it is needed: the behavior model decides which fixtures a shopper stops at from zone role and content, and the store's
 * floor plan gives every department about the same number of fixtures, so left alone units follow SPACE, not demand
 * (cycling holds a sixth of the floor and sells under 1 % of the units). The department stop scale re-points the same
 * shoppers at the departments that sell; the buy scale sets the total. Neither touches Denver's tuned behavior file: both
 * only apply inside a store-day run, through [com.m8trx.twin.behavior.FloorHooks].
 *
 * Fixed point: scale_d <- scale_d x (target_d / actual_d)^0.7 over a calibration window of [DAYS] days, then buy_scale <-
 * buy_scale x total target / total actual. Seeds differ per day, so the fit is not to one lucky draw.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.storeday.StoreDayCalibrate")

/** Fully stocked days in the fit window. 14 for Denver (as committed in 2a); a longer window for the 2b stores averages out the small departments. */
private val DAYS = env("M8TRX_STORE_DAY_CAL_DAYS", "14").toInt()
private const val MIN_SCALE = 0.002
private const val MAX_SCALE = 6.0

fun main() {
    val site = env("M8TRX_STORE_DAY_SITE", "dec-us-denver")
    val power = env("M8TRX_STORE_DAY_CELL_POWER", "1.0").toDouble()
    val iterations = env("M8TRX_STORE_DAY_CAL_ITER", "14").toInt()
    val from = LocalDate.parse(env("M8TRX_STORE_DAY_CAL_FROM", "2026-09-07"))
    val model = StoreDayModel(site)
    val depts = model.demand.values.map { it.department }.toSortedSet()
    var cal = Calibration(model.lines.map { it.fixture }.toSortedSet().associateWith { 1.0 }, depts.associateWith { 1.0 }, power)
    var best: Pair<Double, Calibration>? = null
    for (it in 1..iterations) {
        val m = measure(buildSim(site, cal), from)
        val err = depts.maxOf { d -> Math.abs(m.actual.getValue(d) / m.target.getValue(d) - 1.0) }
        val fixErr = m.fixtureError()
        val score = err + 0.25 * fixErr
        log.info(
            "iter {}: max dept error {} %, total {}/{}, fixture MAPE {} %",
            it,
            "%.1f".format(err * 100),
            m.actual.values.sum(),
            "%.0f".format(m.target.values.sum()),
            "%.1f".format(fixErr * 100),
        )
        if (best == null || score < best.first) best = score to cal
        // Fixtures: stop scale follows the fixture's own miss, damped and bounded; a fixture with almost no sales moves less.
        val moved = cal.fixtureScale.mapValues { (f, sc) ->
            val t = m.targetByFixture[f] ?: 0.0
            val a = (m.byFixture[f] ?: 0).toDouble()
            val r = if (t <= 0.0) 0.5 else (t + 1.0) / (a + 1.0)
            (sc * Math.pow(r, 0.5)).coerceIn(MIN_SCALE, MAX_SCALE)
        }
        // Stop scale and buy scale trade off one for one, so pin the stop scale's level: the median fixture stays at 1.0 (the
        // shopper's stops per visit stay what the behavior file says) and the buy scale carries the level.
        val median = moved.filterKeys { (m.targetByFixture[it] ?: 0.0) >= 5.0 }.values.sorted().let { it[it.size / 2] }
        val fixScale = moved.mapValues { (it.value / median).coerceIn(MIN_SCALE, MAX_SCALE) }
        // Departments: the buy chance absorbs what the stop scale cannot (capped stops, cheap vs dear lines).
        val buy = cal.departmentBuy.mapValues { (d, b) ->
            (b * median * Math.pow(m.target.getValue(d) / m.actual.getValue(d).toDouble().coerceAtLeast(1.0), 0.8)).coerceIn(0.005, 50.0)
        }
        cal = Calibration(fixScale, buy, power)
    }
    cal = best!!.second
    // Stage 2: freeze the fixtures and trim only the department buy scales. A buy draw is compared with the same uniform
    // every run, so units rise monotonically with the scale and this converges where stage 1's coupled moves wander.
    var trimBest: Pair<Double, Calibration>? = null
    for (it in 1..25) {
        val m = measure(buildSim(site, cal), from)
        val err = depts.maxOf { d -> Math.abs(m.actual.getValue(d) / m.target.getValue(d) - 1.0) }
        log.info("trim {}: max dept error {} %", it, "%.2f".format(err * 100))
        if (trimBest == null || err < trimBest.first) trimBest = err to cal
        if (err < 0.02) break
        cal = Calibration(
            cal.fixtureScale,
            cal.departmentBuy.mapValues { (d, b) ->
                (b * Math.pow(m.target.getValue(d) / m.actual.getValue(d).toDouble().coerceAtLeast(1.0), 0.8)).coerceIn(0.005, 50.0)
            },
            power,
        )
    }
    cal = trimBest!!.second
    val sim = buildSim(site, cal)
    val finalM = measure(sim, from)
    Files.writeString(calibrationPath(site), cal.toJson())
    val out = Path.of(env("M8TRX_STORE_DAY_OUT", "out/day")).resolve(site)
    Files.createDirectories(out)
    Files.writeString(out.resolve("CALIBRATION.md"), report(site, cal, finalM, from, model, measure(sim, LocalDate.of(2027, 1, 4), 28)))
    log.info("wrote {} and {}", calibrationPath(site), out.resolve("CALIBRATION.md"))
}

class Measure(
    val actual: Map<String, Int>,
    /** Demand of the EANs the plan puts on the floor (what a shopper can take), per department. */
    val target: Map<String, Double>,
    /** Demand of every EAN in `demand.csv`, per department (the floor-only target plus the off-floor styles). */
    val targetAll: Map<String, Double>,
    val byEan: Map<String, Int>,
    val targetByEan: Map<String, Double>,
    val byFixture: Map<String, Int>,
    val targetByFixture: Map<String, Double>,
    val visitors: Int,
    val transactions: Int,
) {
    /** Mean absolute percentage error over fixtures whose target is at least 5 units in the window. */
    fun fixtureError(): Double {
        val fs = targetByFixture.filter { it.value >= 5.0 }
        return fs.entries.map { Math.abs((byFixture[it.key] ?: 0) / it.value - 1.0) }.average()
    }
}

/** [DAYS] fully stocked days from [from]: units sold per department and fixture against `demand.csv` scaled by each day's traffic factor. */
fun measure(sim: StoreDaySim, from: LocalDate, days: Int = DAYS): Measure {
    val actual = TreeMap<String, Int>()
    val target = TreeMap<String, Double>()
    val targetAll = TreeMap<String, Double>()
    val byEan = HashMap<String, Int>()
    val targetByEan = HashMap<String, Double>()
    val byFixture = HashMap<String, Int>()
    val targetByFixture = HashMap<String, Double>()
    var visitors = 0
    var tx = 0
    val facingsOf = sim.plan.allCells.groupBy { it.ean }
    for (i in 0 until days) {
        val date = from.plusDays(i.toLong())
        val run = sim.stockedDay(date, sim.behavior.params.seed + 1000 + i)
        visitors += run.records.size
        tx += run.transactions
        val weekend = date.dayOfWeek.value >= 6
        val factor = run.expectedVisitors / (sim.baseVisitors(weekend) * sim.model.visitScale)
        run.records.forEach { r ->
            r.lines.forEach { l ->
                actual.merge(sim.model.demand[l.cell.ean]?.department ?: "?", 1, Int::plus)
                byEan.merge(l.cell.ean, 1, Int::plus)
                byFixture.merge(l.cell.fixture, 1, Int::plus)
            }
        }
        sim.model.demand.values.forEach { d ->
            val t = (if (weekend) d.perDayWeekend else d.perDayWeekday) * factor
            targetAll.merge(d.department, t, Double::plus)
            val cells = facingsOf[d.ean]
            if (cells != null) {
                target.merge(d.department, t, Double::plus)
                targetByEan.merge(d.ean, t, Double::plus)
                val total = cells.sumOf { it.facings }.toDouble()
                cells.forEach { c -> targetByFixture.merge(c.fixture, t * c.facings / total, Double::plus) }
            }
        }
    }
    return Measure(actual, target, targetAll, byEan, targetByEan, byFixture, targetByFixture, visitors, tx)
}

/** Spearman rank correlation of per-EAN units against demand over EANs the plan puts on the floor. */
fun spearman(a: Map<String, Double>, b: Map<String, Double>, keys: Collection<String>): Double {
    fun ranks(m: Map<String, Double>): Map<String, Double> {
        val sorted = keys.sortedBy { m[it] ?: 0.0 }
        val r = HashMap<String, Double>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && (m[sorted[j + 1]] ?: 0.0) == (m[sorted[i]] ?: 0.0)) j++
            val avg = (i + j) / 2.0
            for (k in i..j) r[sorted[k]] = avg
            i = j + 1
        }
        return r
    }
    val ra = ranks(a)
    val rb = ranks(b)
    val n = keys.size
    val ma = ra.values.average()
    val mb = rb.values.average()
    val cov = keys.sumOf { (ra.getValue(it) - ma) * (rb.getValue(it) - mb) }
    val va = keys.sumOf { Math.pow(ra.getValue(it) - ma, 2.0) }
    val vb = keys.sumOf { Math.pow(rb.getValue(it) - mb, 2.0) }
    return if (n < 2 || va == 0.0 || vb == 0.0) 0.0 else cov / Math.sqrt(va * vb)
}

private fun report(site: String, cal: Calibration, m: Measure, from: LocalDate, model: StoreDayModel, hold: Measure): String = buildString {
    val total = m.actual.values.sum()
    val tt = m.target.values.sum()
    val ta = m.targetAll.values.sum()
    appendLine("# Calibration: $site (generated by storeDayCalibrate)")
    appendLine()
    appendLine(
        "Window: $DAYS fully stocked days from $from (no stock limit, no staff), seeded. Target = `demand.csv` units/day (weekday or weekend) x the day's traffic factor (expected visitors / the model's 850 or 1,450 x the store's footfall scale).",
    )
    appendLine()
    appendLine(
        "Total: ${m.visitors} visitors, ${m.transactions} transactions, $total units vs ${"%.0f".format(
            tt,
        )} demanded of on-floor EANs (${"%+.1f".format(
            100.0 * (total / tt - 1),
        )} %); `demand.csv` in full is ${"%.0f".format(
            ta,
        )} (the ${"%.1f".format(100.0 * (1 - tt / ta))} % difference is the 89 off-floor EANs, which a shopper cannot take from a fixture).",
    )
    appendLine()
    appendLine("| department | units | target (on floor) | error | demand.csv (all EANs) | error vs all | buy scale |")
    appendLine("|---|---:|---:|---:|---:|---:|---:|")
    m.actual.forEach { (d, n) ->
        val t = m.target.getValue(d)
        val all = m.targetAll.getValue(d)
        appendLine(
            "| $d | $n | ${"%.0f".format(
                t,
            )} | ${"%+.1f".format(
                100.0 * (n / t - 1),
            )} % | ${"%.0f".format(all)} | ${"%+.1f".format(100.0 * (n / all - 1))} % | ${"%.3f".format(cal.departmentBuy[d] ?: 1.0)} |",
        )
    }
    appendLine()
    appendLine(
        "cell_weight_power = ${cal.cellWeightPower}; fixture stop scales: ${cal.fixtureScale.size} fixtures, min ${"%.3f".format(
            cal.fixtureScale.values.min(),
        )}, median ${"%.3f".format(
            cal.fixtureScale.values.sorted()[cal.fixtureScale.size / 2],
        )}, max ${"%.3f".format(cal.fixtureScale.values.max())}.",
    )
    val fs = m.targetByFixture.filter { it.value >= 5.0 }
    appendLine("Fixtures with at least 5 target units in the window: ${fs.size}; mean absolute error ${"%.1f".format(100.0 * m.fixtureError())} %.")
    val keys = m.targetByEan.keys
    val eanA = keys.associateWith { (m.byEan[it] ?: 0).toDouble() }
    appendLine()
    appendLine(
        "Per EAN (informational, not a gate): Spearman rank correlation of units vs demand over ${keys.size} EANs = ${"%.3f".format(
            spearman(eanA, m.targetByEan, keys),
        )}.",
    )
    val top = keys.sortedByDescending { m.targetByEan.getValue(it) }.take(keys.size / 10)
    appendLine(
        "Top 10 % of EANs by demand carry ${"%.1f".format(
            100.0 * top.sumOf {
                m.targetByEan.getValue(it)
            } / tt,
        )} % of target units and ${"%.1f".format(100.0 * top.sumOf { eanA.getValue(it) } / total)} % of simulated units.",
    )
    appendLine()
    appendLine("## Hold-out: 28 fully stocked days from 2027-01-04 (dates and seeds the fit never saw; this is what `storeDaySelfTest` gates)")
    appendLine()
    appendLine("| department | units | target (on floor) | error | tolerance |")
    appendLine("|---|---:|---:|---:|---:|")
    hold.actual.forEach { (d, n) ->
        val t = hold.target.getValue(d)
        appendLine(
            "| $d | $n | ${"%.0f".format(
                t,
            )} | ${"%+.1f".format(100.0 * (n / t - 1))} % | +/-${"%.0f".format(100.0 * maxOf(0.10, 3.0 / Math.sqrt(t)))} % |",
        )
    }
    appendLine()
    appendLine(
        "Tolerance is 10 %, widened to three standard deviations of a Poisson count where a department sells too few units for 10 % to be meaningful (cycling: about 100 units in 28 days).",
    )
    appendLine(
        "The ${model.demand.size - model.required.keys.map {
            it.ean
        }.toSet().size} EANs off the floor (no fixture holds them) are not in the on-floor target.",
    )
}
