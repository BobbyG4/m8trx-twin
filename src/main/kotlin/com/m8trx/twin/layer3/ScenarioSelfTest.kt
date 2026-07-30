package com.m8trx.twin.layer3

import org.slf4j.LoggerFactory
import java.time.LocalDate
import kotlin.system.exitProcess

/**
 * Offline scenario harness — `./gradlew scenarioSelfTest`.
 *
 * Generates whole days headlessly and asserts they satisfy the §1 reconciliation identity, that the run is
 * deterministic from seed, and — the part that matters — that the emitted dwell streams would actually
 * produce impressions on the real pipeline.
 *
 * No network, no credentials, no edge.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.layer3.ScenarioSelfTest")

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        passed++
        log.info("  ✓ {}", name)
    } else {
        failed++
        log.error("  ✗ {} {}", name, detail)
    }
}

fun main() {
    val scale = System.getenv("M8TRX_SCENARIO_SCALE")?.toDoubleOrNull() ?: 0.12
    val runner = ScenarioRun()

    // ── 1. a weekday ──────────────────────────────────────────────────────────
    log.info("[1] weekday — 2026-07-28 (Tuesday), scale={}", scale)
    val weekday = runner.run(LocalDate.of(2026, 7, 28), seed = 4242, populationScale = scale)
    log.info(
        "  visitors={} transactions={} units={} revenueUsd={} samples={} predictedImpressions={}",
        weekday.visitors,
        weekday.transactions,
        weekday.units,
        "%.2f".format(weekday.revenueUsd),
        weekday.emittedSamples,
        weekday.predictedImpressions,
    )
    log.info("\n{}", weekday.reconciliation.render())

    check("scheduler ran without errors", weekday.schedulerErrors == 0, "errors=${weekday.schedulerErrors}")
    check("visitors were generated", weekday.visitors > 0)
    check("transactions were generated", weekday.transactions > 0)
    check("revenue is positive", weekday.revenueUsd > 0.0)
    weekday.reconciliation.lines.forEach {
        check("§1 identity — ${it.name}", it.ok, "expected ${"%.1f".format(it.expected)} got ${"%.1f".format(it.observed)}")
    }

    // ── 2. the emit contract holds for generated journeys ─────────────────────
    log.info("[2] emit contract — generated dwell must actually fire impressions")
    check("dwell samples were emitted", weekday.emittedSamples > 0)
    check(
        "the day predicts impressions on the real pipeline",
        weekday.predictedImpressions > 0,
        "predicted 0 — journeys are emitting dwell that core would silently discard",
    )
    val silentPct = if (weekday.visitors == 0) 100 else weekday.shoppersWithNoImpression * 100 / weekday.visitors
    log.info("  · shoppers producing NO impression: {}/{} ({}%)", weekday.shoppersWithNoImpression, weekday.visitors, silentPct)
    check("most shoppers produce at least one impression", silentPct < 40, "silent=$silentPct%")
    val perVisitor = weekday.predictedImpressions.toDouble() / weekday.visitors
    log.info("  · impressions per visitor: {}", "%.2f".format(perVisitor))
    check("impressions per visitor is plausible (0.5..8)", perVisitor in 0.5..8.0, "got $perVisitor")

    // ── 3. determinism ────────────────────────────────────────────────────────
    log.info("[3] determinism — same seed must reproduce the day exactly")
    val repeat = runner.run(LocalDate.of(2026, 7, 28), seed = 4242, populationScale = scale)
    check("visitors reproduce", repeat.visitors == weekday.visitors, "${repeat.visitors} vs ${weekday.visitors}")
    check("transactions reproduce", repeat.transactions == weekday.transactions)
    check("revenue reproduces exactly", repeat.revenueUsd == weekday.revenueUsd)
    check("emitted sample count reproduces", repeat.emittedSamples == weekday.emittedSamples)
    check("predicted impressions reproduce", repeat.predictedImpressions == weekday.predictedImpressions)

    val different = runner.run(LocalDate.of(2026, 7, 28), seed = 9999, populationScale = scale)
    check("a different seed yields a different day", different.visitors != weekday.visitors || different.revenueUsd != weekday.revenueUsd)

    // ── 4. weekend shape ──────────────────────────────────────────────────────
    log.info("[4] weekend — 2026-08-01 (Saturday)")
    val weekend = runner.run(LocalDate.of(2026, 8, 1), seed = 4242, populationScale = scale)
    log.info("  visitors={} (weekday was {}) revenueUsd={}", weekend.visitors, weekday.visitors, "%.2f".format(weekend.revenueUsd))
    check(
        "weekend is busier than weekday (1.7x multiplier)",
        weekend.visitors > weekday.visitors,
        "weekend=${weekend.visitors} weekday=${weekday.visitors}",
    )
    weekend.reconciliation.lines.forEach {
        check("weekend §1 — ${it.name}", it.ok, "exp ${"%.1f".format(it.expected)} got ${"%.1f".format(it.observed)}")
    }

    log.info("")
    log.info("scenarioSelfTest — {} passed, {} failed", passed, failed)
    if (failed > 0) exitProcess(1)
}
