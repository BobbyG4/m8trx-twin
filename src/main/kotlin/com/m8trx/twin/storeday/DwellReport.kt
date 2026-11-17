package com.m8trx.twin.storeday

import java.time.LocalDate

/**
 * `./gradlew storeDayDwellReport` — offline. Per-kind mean dwell per engaged browse visit, the store mean against the operating model and the
 * session length, for the stores in `M8TRX_STORE_DAY_SITE` (comma list, default Denver and Paris) over `M8TRX_STORE_DAY_CAL_DAYS` fully stocked days.
 * `M8TRX_STORE_DAY_DWELL=off` shows the 2d floor (no dwell model) for the before column.
 */
fun main() {
    val sites = env("M8TRX_STORE_DAY_SITE", "dec-us-denver,dec-fr-paris").split(',')
    val days = env("M8TRX_STORE_DAY_CAL_DAYS", "14").toInt()
    val from = LocalDate.parse(env("M8TRX_STORE_DAY_CAL_FROM", "2026-09-07"))
    val on = env("M8TRX_STORE_DAY_DWELL", "on") != "off"
    val target = DwellStats.targetSessionMin()
    System.getenv("M8TRX_STORE_DAY_COMPARE")?.takeIf { it.isNotBlank() }?.let {
        compareCalibration(sites, java.nio.file.Path.of(it))
        return
    }
    for (site in sites) {
        val fitted = if (env("M8TRX_STORE_DAY_DWELL_FIT", "off") ==
            "on"
        ) {
            loadCalibration(site).let { it.copy(dwellScale = DwellStats.fit(site, it, from, days)) }
        } else {
            loadCalibration(site)
        }
        val sim = buildSim(site, fitted, Knobs(dwell = on))
        val st = DwellStats.stocked(sim, from, days)
        println(
            "== $site dwell model ${if (on) "on" else "OFF (2d floor)"}: $days stocked days, ${st.visitors} visitors, scale ${sim.cal.dwellScale}",
        )
        val med = DwellStats.median(st.sessionMin)
        println(
            "session median ${"%.1f".format(
                med,
            )} min vs operating model ${"%.1f".format(
                target,
            )} min (${"%+.1f".format(
                100 * (med / target - 1),
            )} %); mean ${"%.1f".format(
                st.sessionMin.average(),
            )} min; mean browse stop ${"%.0f".format(
                st.meanAll(),
            )} s; pickups/browse visit ${"%.2f".format(
                st.pickups.toDouble() / st.pickupsPerVisit.size,
            )}; units/visitor ${"%.3f".format(st.units.toDouble() / st.visitors)}",
        )
        println("| kind | visits | mean s | median s | p10 s | p90 s |")
        println("|---|---:|---:|---:|---:|---:|")
        st.browseByKind.forEach { (k, v) ->
            val s = v.sorted()
            println(
                "| ${k.label} | ${s.size} | ${"%.0f".format(
                    s.average(),
                )} | ${"%.0f".format(s[s.size / 2])} | ${"%.0f".format(s[s.size / 10])} | ${"%.0f".format(s[s.size * 9 / 10])} |",
            )
        }
        if (st.tryOnBench.isNotEmpty()) {
            println(
                "| footwear bench (try-on) | ${st.tryOnBench.size} | ${"%.0f".format(st.tryOnBench.average())} | | | |",
            )
        }
        println(
            "minutes in zone per shopper who visited: " +
                st.zoneMin.entries.joinToString { "${it.key} ${"%.1f".format(it.value.average())} (n=${it.value.size})" } +
                "  [operating model: footwear 9, apparel 6, gps 4]",
        )
    }
}

/**
 * `M8TRX_STORE_DAY_COMPARE=<dir of the 2d calibration files>` — the calibration before and after slice 2e, on the hold-out window the self-test gates
 * (28 stocked days from 2027-01-04): (A) the 2d calibration on the 2d floor (dwell off); (B) the 2d calibration with the dwell model on and its scale
 * fitted but nothing else re-fitted (what the dwell model does to a calibration it was not fitted with); (C) the committed 2e calibration.
 */
internal fun compareCalibration(sites: List<String>, oldDir: java.nio.file.Path) {
    println("| store | variant | total error | worst dept error (units, tol) | fixture MAPE (>=5 units) |")
    println("|---|---|---:|---|---:|")
    for (site in sites) {
        val old = Calibration.load(oldDir.resolve("$site.json"))
        val new = loadCalibration(site)
        val fitted = old.copy(dwellScale = new.dwellScale)
        listOf(
            "A 2d calibration, dwell off" to (old to false),
            "B 2d calibration, dwell on" to (fitted to true),
            "C 2e calibration, dwell on" to (new to true),
        ).forEach { (name, v) ->
            val m = measure(buildSim(site, v.first, Knobs(dwell = v.second)), LocalDate.of(2027, 1, 4), 28)
            val worst = m.actual.keys.maxBy { d ->
                Math.abs(m.actual.getValue(d) / m.target.getValue(d) - 1.0) /
                    maxOf(0.10, 3.0 / Math.sqrt(m.target.getValue(d)))
            }
            val e = m.actual.getValue(worst) / m.target.getValue(worst) - 1.0
            val tol = maxOf(0.10, 3.0 / Math.sqrt(m.target.getValue(worst)))
            println(
                "| $site | $name | ${"%+.1f".format(
                    100 * (m.actual.values.sum() / m.target.values.sum() - 1),
                )} % | $worst ${"%+.1f".format(100 * e)} % (tol ${"%.0f".format(100 * tol)} %) | ${"%.1f".format(100 * m.fixtureError())} % |",
            )
        }
    }
}
