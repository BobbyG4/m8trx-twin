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

    // ── 2b. fixture coverage — the S15 regression ─────────────────────────────
    //
    // The 2026-07-28 full day covered 97 of Denver's 115 fixtures. The 18 it missed were not a platform
    // fault: journeys reached fixtures only through `department`, and 19 fixtures carry none — the GPS
    // cases, accessories bays, benches, checkout rack, and all three circles (`Z-01`). On a heatmap that
    // reads as the platform dropping them, which is the same wrong conclusion the old GeometryConverter
    // bug caused. Fixture pools are now keyed off `in_area_zone`, so this asserts it stays fixed.
    log.info("[2b] fixture coverage — every visible fixture must be reachable by a journey")
    log.info(
        "  · covered {}/{} ({}%)",
        weekday.fixturesCovered.size,
        weekday.fixturesVisible.size,
        "%.1f".format(weekday.coveragePct),
    )
    val missed = weekday.fixturesNeverBrowsed
    if (missed.isNotEmpty()) log.warn("  · never browsed ({}): {}", missed.size, missed.sorted())

    // Deliberately a THRESHOLD, not zero. Whether every fixture is *reachable* is a deterministic geometry
    // question and is asserted exactly, scale-free, by `peopleSelfTest` [10]. What remains here is sampling:
    // at the default 0.12 scale a day is ~98 visitors, and a fixture in a low-affinity zone can simply go
    // undrawn. Demanding zero here would make the harness flaky and teach us to ignore it — the failure mode
    // that lets a real silent regression through. Full scale (~790, the S15 day) covers 115/115.
    check(
        "fixture coverage is high (>=90% of visible fixtures browsed)",
        weekday.coveragePct >= 90.0,
        "only ${"%.1f".format(weekday.coveragePct)}% — never browsed: ${missed.sorted()}",
    )

    // Named explicitly, not just counted: these are the three that motivated the fix, and they are
    // CONTAINMENT-ONLY on core's side (`Geometry.Circle.edges()` is a stub), so they are the first thing
    // that breaks if the standoff logic regresses.
    val circles = setOf("PI-01", "PI-03", "RR-02")
    check(
        "all three circle fixtures are browsed (PI-01, PI-03, RR-02)",
        weekday.fixturesCovered.containsAll(circles),
        "missing ${circles - weekday.fixturesCovered}",
    )

    // The department-less set as a class — the actual root cause, not just its most visible symptom. Before
    // the `in_area_zone` re-key NONE of these could be browsed, so a majority being covered at a 12% day is
    // the signal; exact per-fixture reachability is `peopleSelfTest` [10]'s job.
    val deptLess = setOf(
        "GPS-01", "GPS-02", "GPS-03", "GPS-04", "GPS-05", "GPS-06",
        "ACC-01", "ACC-02", "ACC-03", "GA-01", "FB-01", "CO-IR",
    )
    val deptLessHit = deptLess.intersect(weekday.fixturesCovered)
    log.info("  · department-less fixtures covered: {}/{} — {}", deptLessHit.size, deptLess.size, deptLessHit.sorted())
    check(
        "department-less fixtures are reached at all (GPS cases, accessories bays, bench, treadmill, impulse rack)",
        deptLessHit.size >= deptLess.size * 2 / 3,
        "only ${deptLessHit.size}/${deptLess.size}: missing ${(deptLess - weekday.fixturesCovered).sorted()}",
    )

    // ── 2b. loss prevention — the Shoplift arc (S17) ──────────────────────────
    log.info("[2b] loss prevention — EAS tagging + gate")

    val layout = java.nio.file.Path.of(System.getenv("M8TRX_CHAIN_DIR") ?: "reference/data/chain", "stores/dec-us-denver/layout.json")
    val gates = com.m8trx.twin.layer2.EasTagging.loadGates(layout)
    check("Denver declares an eas_gate crossing slice", gates.isNotEmpty(), "found none — a zone-only scan misses crossing_slices")
    val gate = gates.first()
    check(
        "the gate is CS-01 with real SRF geometry",
        gate.code == "CS-01" && gate.xEnd > gate.xStart,
        "got ${gate.code} x=${gate.xStart}..${gate.xEnd} y=${gate.y}",
    )
    check("gate crossing points lie ON the line", gate.crossingPoint(0.5).y == gate.y && gate.crossingPoint(0.5).x in gate.xStart..gate.xEnd)

    // The tagging RULE, asserted as a rule — a literal list is what rotted the old watch anchor.
    val tag = com.m8trx.twin.layer2.EasTagging
    check("a premium concealable item is tagged", tag.isTagged(350.0, "footwear"))
    check("a cheap item is NOT tagged", !tag.isTagged(29.99, "footwear"))
    check("a road bike is NOT tagged however expensive — unconcealable", !tag.isTagged(2999.0, "outdoor"))
    check("the rule is case-insensitive on category", !tag.isTagged(2999.0, "OUTDOOR"))

    // ⚠ The anchor the docs used to name does not exist. Assert its ABSENCE so nobody restores it.
    val denverRows = java.nio.file.Files.readAllLines(
        java.nio.file.Path.of(System.getenv("M8TRX_CHAIN_DIR") ?: "reference/data/chain", "stores/dec-us-denver/assortment.csv"),
    )
    val watchRows = denverRows.drop(1).count { it.contains("watch", ignoreCase = true) }
    check(
        "the live catalog still has ZERO watch SKUs (the old 'W-series, EAS-tagged, 40 items' anchor is dead)",
        watchRows == 0,
        "found $watchRows — if watches were added, revisit EasTagging and the LP anchor docs",
    )

    log.info("  · LP arcs in the weekday day: {} concealment(s), {} gate alarm(s)", weekday.concealments, weekday.gateAlarms)
    check(
        "the Shoplift arc actually FIRES in a generated day (not merely compiled)",
        weekday.concealments > 0 && weekday.gateAlarms > 0,
        "concealments=${weekday.concealments} gateAlarms=${weekday.gateAlarms} — shoplift_baseline is ${"%.4f".format(
            0.003,
        )} of the mix; at this scale it may need a larger day",
    )
    check(
        "every concealment produced a gate alarm — the alarm is a consequence of the track",
        weekday.gateAlarms == weekday.concealments,
        "concealments=${weekday.concealments} but gateAlarms=${weekday.gateAlarms}",
    )

    // ── 3. determinism ────────────────────────────────────────────────────────
    log.info("[3] determinism — same seed must reproduce the day exactly")
    val repeat = runner.run(LocalDate.of(2026, 7, 28), seed = 4242, populationScale = scale)
    check("visitors reproduce", repeat.visitors == weekday.visitors, "${repeat.visitors} vs ${weekday.visitors}")
    check("transactions reproduce", repeat.transactions == weekday.transactions)
    check("revenue reproduces exactly", repeat.revenueUsd == weekday.revenueUsd)
    check("emitted sample count reproduces", repeat.emittedSamples == weekday.emittedSamples)
    check("predicted impressions reproduce", repeat.predictedImpressions == weekday.predictedImpressions)
    check("fixture coverage reproduces", repeat.fixturesCovered == weekday.fixturesCovered)

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
