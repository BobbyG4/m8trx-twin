package com.m8trx.twin.layer3

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The realism gate — `STORE-OPERATING-MODEL.md` §1. A generated day is *correct* only if the emitted stream
 * ties out to the model:
 *
 * ```
 * visitors      = footfall(day_type, date)
 * transactions  = visitors × conversion_rate
 * revenue       = transactions × ATV
 * units_sold    = transactions × units_per_txn
 * ```
 *
 * **The visitor denominator is `person_session`, not `crossing`** (ruled 2026-07-28). `person_session` is
 * the platform's visit record — entry/exit, site, space, one row per tracked person — and it is live and
 * proven; twin's own S15 episodes created six. `crossing_line` is a door-counter instrument: more precise
 * for entrance counts, post-MVP, and not a prerequisite for this gate. Twin's `CustomerEntered` is the
 * local counterpart, so the identity closes on data twin already produces.
 *
 * Tolerances: visitors within ±3√N of expected (Poisson noise); transactions and revenue within ±10%
 * (sampling + basket variance). A miss means a generator drifted from the model — exactly the signal this
 * exists to catch.
 */
object Reconciliation {

    data class Observed(val visitors: Int, val transactions: Int, val units: Int, val revenueUsd: Double)

    data class Line(val name: String, val expected: Double, val observed: Double, val tolerance: Double, val ok: Boolean) {
        val deltaPct: Double get() = if (expected == 0.0) 0.0 else (observed - expected) / expected * 100.0
    }

    data class Report(val lines: List<Line>, val expectedVisitors: Double) {
        val ok: Boolean get() = lines.all { it.ok }
        fun render(): String = buildString {
            appendLine("  ${"metric".padEnd(14)} ${"expected".padStart(12)} ${"observed".padStart(12)} ${"delta".padStart(9)}   verdict")
            lines.forEach {
                appendLine(
                    "  ${it.name.padEnd(14)} ${"%.1f".format(it.expected).padStart(12)} " +
                        "${"%.1f".format(it.observed).padStart(12)} ${"%+.1f%%".format(it.deltaPct).padStart(9)}   " +
                        if (it.ok) "PASS" else "FAIL",
                )
            }
        }
    }

    /**
     * [expectedVisitors] is the model's footfall for the day (already scaled). Downstream expectations are
     * derived from OBSERVED visitors, not expected — otherwise one unlucky Poisson draw cascades into three
     * spurious failures when the funnel ratios are actually correct.
     */
    fun check(model: OperatingModel, expectedVisitors: Double, obs: Observed): Report {
        val conversion = model.conversion.overallRate.value
        val atv = model.basket.atvUsd.value
        val upt = model.basket.unitsPerTxn.value

        val expTxn = obs.visitors * conversion
        val expUnits = obs.transactions * upt
        val expRevenue = obs.transactions * atv

        // Tolerances must respect SAMPLING NOISE, not just a flat percentage. The model's ±10% was written
        // for a full day (~850 visitors), where 10% of expected comfortably exceeds the sampling spread. At
        // a scaled-down 98 visitors the binomial sd on transactions is ~4.1 while 10% is only ~2.2 — so a
        // perfectly correct generator fails roughly half the time. Widening the percentage instead would be
        // tolerance-hacking; taking max(pct, 3 sd) is the honest fix and converges to the model's ±10% as n
        // grows, which is exactly the behaviour we want.
        val txnSd = sqrt(obs.visitors * conversion * (1 - conversion))
        val unitSd = sqrt(expUnits.coerceAtLeast(1.0))
        // Revenue carries basket-price dispersion on top of count noise, so its noise floor is wider.
        val revSd = expRevenue / sqrt(obs.transactions.coerceAtLeast(1).toDouble())

        return Report(
            listOf(
                line("visitors", expectedVisitors, obs.visitors.toDouble(), 3.0 * sqrt(expectedVisitors.coerceAtLeast(1.0))),
                line("transactions", expTxn, obs.transactions.toDouble(), maxOf(expTxn * 0.10, 3.0 * txnSd)),
                line("units", expUnits, obs.units.toDouble(), maxOf(expUnits * 0.10, 3.0 * unitSd)),
                line("revenue", expRevenue, obs.revenueUsd, maxOf(expRevenue * 0.10, 2.0 * revSd)),
            ),
            expectedVisitors,
        )
    }

    private fun line(name: String, expected: Double, observed: Double, tol: Double) =
        Line(name, expected, observed, tol, abs(observed - expected) <= tol)
}
