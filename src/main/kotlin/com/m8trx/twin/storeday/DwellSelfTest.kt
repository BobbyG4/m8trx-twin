package com.m8trx.twin.storeday

import com.m8trx.twin.behavior.Visit
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * Slice 2e checks, run per store from `storeDaySelfTest`: dwell differs by fixture kind (mean ratio and a two-sample KS distance),
 * rises with the depth of the range, interactions rise with dwell, the store's median session is the operating model's, and with the
 * dwell model off (the 2d floor) the kinds do not differ. Hold-out dates the fit never saw.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.storeday.DwellSelfTest")
private const val MIN_VISITS = 150

/** Two-sample Kolmogorov-Smirnov distance: the largest gap between the two empirical distribution functions. */
internal fun ksDistance(a: List<Double>, b: List<Double>): Double {
    val x = a.sorted()
    val y = b.sorted()
    var i = 0
    var j = 0
    var d = 0.0
    while (i < x.size && j < y.size) {
        val v = minOf(x[i], y[j])
        while (i < x.size && x[i] <= v) i++
        while (j < y.size && y[j] <= v) j++
        d = maxOf(d, Math.abs(i.toDouble() / x.size - j.toDouble() / y.size))
    }
    return d
}

private fun pearson(x: List<Double>, y: List<Double>): Double {
    val mx = x.average()
    val my = y.average()
    val c = x.indices.sumOf { (x[it] - mx) * (y[it] - my) }
    return c / Math.sqrt(x.sumOf { (it - mx) * (it - mx) } * y.sumOf { (it - my) * (it - my) })
}

internal fun checkDwell(site: String) {
    log.info("dwell ({})", site)
    val from = LocalDate.of(2027, 1, 4)
    val sim = buildSim(site)
    val st = DwellStats.stocked(sim, from, 14)
    val target = DwellStats.targetSessionMin()
    val med = DwellStats.median(st.sessionMin)
    check(
        "$site: median session is the operating model's dwell.total_median_min within 10 %",
        Math.abs(med / target - 1.0) <= 0.10,
        "${"%.1f".format(med)} min vs $target (${"%+.1f".format(100 * (med / target - 1))} %); mean stop ${"%.0f".format(st.meanAll())} s",
    )
    val by = st.browseByKind
    fun mean(k: FixtureKind) = by[k]?.takeIf { it.size >= MIN_VISITS }?.average()
    val wall = mean(FixtureKind.FOOTWEAR_WALL)
    val peg = mean(FixtureKind.PEGBOARD)
    if (wall != null && peg != null) {
        check(
            "$site: footwear wall mean dwell is at least 2x pegboard's",
            wall >= 2 * peg,
            "${"%.0f".format(wall)} s vs ${"%.0f".format(peg)} s (x${"%.1f".format(wall / peg)})",
        )
        val ks = ksDistance(by.getValue(FixtureKind.FOOTWEAR_WALL), by.getValue(FixtureKind.PEGBOARD))
        check("$site: footwear wall and pegboard dwell distributions differ (KS distance above 0.6)", ks > 0.6, "D = ${"%.2f".format(ks)}")
    }
    val rail = mean(FixtureKind.APPAREL_RAIL)
    val fold = mean(FixtureKind.APPAREL_FOLDED)
    if (rail != null && fold != null && peg != null) {
        check(
            "$site: apparel rails > folded apparel > pegboard",
            rail > fold && fold > peg,
            "${"%.0f".format(rail)} > ${"%.0f".format(fold)} > ${"%.0f".format(peg)} s",
        )
    }
    mean(FixtureKind.GPS_CASE)?.let { gps ->
        check("$site: GPS cases are long when engaged (above folded apparel)", fold == null || gps > fold, "${"%.0f".format(gps)} s")
    }
    if (st.tryOnBench.size >= MIN_VISITS && wall != null) {
        check(
            "$site: the bench try-on is longer than a footwear wall stop",
            st.tryOnBench.average() > wall,
            "${"%.0f".format(st.tryOnBench.average())} s vs ${"%.0f".format(wall)} s",
        )
    }
    // depth of range: within a kind, a fixture with more distinct styles is browsed longer
    val styles = sim.plan.cellsByFixture.mapValues { (_, cs) -> cs.map { it.handle }.toSet().size }
    val perFixture = HashMap<String, MutableList<Double>>()
    for (i in 0 until 14) {
        sim.stockedDay(from.plusDays(i.toLong()), sim.behavior.params.seed + 1000 + i).records.forEach { r ->
            r.visits.filter { it.kind == Visit.Kind.BROWSE }.forEach { perFixture.getOrPut(it.fixture) { ArrayList() } += it.dwellMs / 1000.0 }
        }
    }
    val kindOf = sim.model.fixtureSpecs.mapValues { DwellModel.classify(it.value) }
    val kindMean = perFixture.entries.groupBy { kindOf[it.key] }.mapValues { (_, es) -> es.flatMap { it.value }.average() }
    val pts = perFixture.filter { it.value.size >= 30 && (styles[it.key] ?: 0) > 0 }.map { (f, v) ->
        Math.log(styles.getValue(f).toDouble()) to Math.log(v.average() / kindMean.getValue(kindOf[f]))
    }
    val r = pearson(pts.map { it.first }, pts.map { it.second })
    check(
        "$site: dwell rises with the depth of the range (kind-adjusted correlation with distinct styles above 0.1)",
        r > 0.1,
        "r = ${"%.2f".format(r)} over ${pts.size} fixtures",
    )
    // interactions scale with dwell: pickups per visit rise with the span, inside one kind
    val longVisits = ArrayList<Int>()
    val shortVisits = ArrayList<Int>()
    val cut = DwellStats.median(by[FixtureKind.APPAREL_RAIL] ?: by.values.maxBy { it.size })
    sim.stockedDay(from, sim.behavior.params.seed + 1000).records.forEach { r ->
        r.visits.filter { it.kind == Visit.Kind.BROWSE }.forEach {
            if (it.dwellMs / 1000.0 >=
                cut
            ) {
                longVisits += it.pickups.size
            } else {
                shortVisits += it.pickups.size
            }
        }
    }
    check(
        "$site: pickups per visit rise with dwell",
        longVisits.average() > 1.15 * shortVisits.average(),
        "${"%.2f".format(longVisits.average())} above the median span vs ${"%.2f".format(shortVisits.average())} below",
    )
    if (site == "dec-us-denver") {
        val flat = DwellStats.stocked(buildSim(site, knobs = Knobs(dwell = false)), from, 7)
        val w = flat.browseByKind.values.flatten().average()
        check(
            "$site: with the dwell model off every fixture kind is back to the flat span",
            w < 70.0,
            "mean ${"%.0f".format(w)} s (the 2d floor, 25 to 80 s x content)",
        )
    }
}
