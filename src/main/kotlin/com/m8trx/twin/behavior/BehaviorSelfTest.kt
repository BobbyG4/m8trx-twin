package com.m8trx.twin.behavior

import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.system.exitProcess

/**
 * `./gradlew behaviorSelfTest` — offline proof of TWIN-REQ-007's "done when" checks that can be decided without the
 * platform. Nothing is published.
 *
 * Every truth is measured the way the brief states its check: run the same seeded week with the truth OFF (1.0),
 * at its default, and DOUBLED (`1 + 2 × (default − 1)`), then compare the measure it multiplies. Same seed in all
 * three, so the comparison is between arrangements, not between random draws. A truth passes when OFF removes
 * the effect (ratio 1.0 by construction — it is the baseline), the default moves the measure in the stated
 * direction by roughly its stated size, and doubling roughly doubles `|ratio − 1|`.
 *
 * Tolerances are stated beside each check and are deliberately wide enough for weekly sampling noise and the
 * dampening a fixed-size weighted draw introduces; they are not tuned to make a failing effect pass.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.behavior.BehaviorSelfTest")

private var passed = 0
private var failed = 0

private fun check(name: String, ok: Boolean, detail: String) {
    if (ok) passed++ else failed++
    log.info("  {} {} — {}", if (ok) "✓" else "✗", name, detail)
}

private val WEEK_START: LocalDate = LocalDate.of(2026, 10, 5) // a Monday

fun main() {
    val site = System.getenv("M8TRX_BEHAVIOR_SITE") ?: "dec-us-denver"
    val base = BehaviorParams.forSite(site)
    val m = BehaviorModel(base)
    val (before, after) = m.stock.resetPair(base)
    log.info("behaviorSelfTest — site={} seed={} plans: published={} before={} after={}", site, base.seed, m.stock.published.id, before.id, after.id)

    // ── 1. reconciliation, traffic shape (requirement 1) ──────────────────────────────────────────────────
    log.info("[1] traffic and reconciliation — published plan, seeded week")
    val week = (0L until 7L).map { m.runDay(WEEK_START.plusDays(it), PlanTimeline.single(m.stock.published), base.seed) }
    week.forEach { d ->
        val r = d.reconcile()
        check("reconciles ${d.date.dayOfWeek}", r.ok, r.lines.joinToString { "${it.name} ${"%+.1f".format(it.deltaPct)}%" })
    }
    val tue = week.first { it.date.dayOfWeek.name == "TUESDAY" }.records.size
    val sat = week.first { it.date.dayOfWeek.name == "SATURDAY" }.records.size
    val fri = week.first { it.date.dayOfWeek.name == "FRIDAY" }.records.size
    check("weekend > weekday, Friday > Tuesday", sat > 1.5 * tue && fri > tue, "Tue=$tue Fri=$fri Sat=$sat")
    val peakWeekday = hourPeak(week[0])
    val peakSat = hourPeak(week[5])
    check(
        "hourly shape: weekday evening peak, weekend midday",
        peakWeekday in 17..19 && peakSat in 12..14,
        "weekday peak ${peakWeekday}h, Saturday peak ${peakSat}h",
    )
    val s1 = base.traffic.seasonality(WEEK_START)
    val s4 = base.traffic.seasonality(WEEK_START.plusWeeks(2))
    check("weekly seasonality varies the base", s1 != s4, "factor ${"%.3f".format(s1)} → ${"%.3f".format(s4)} two weeks later")

    // ── 2. determinism + oracle agreement, one hour with samples (requirements 7, 8) ─────────────────────
    log.info("[2] determinism and oracle = trace — one simulated hour with 5 Hz samples")
    val hour = 14..14
    val sinkA = MemorySampleSink()
    val sinkB = MemorySampleSink()
    val a = m.runDay(WEEK_START.plusDays(5), PlanTimeline.single(after), base.seed, sink = sinkA, hours = hour)
    val b = m.runDay(WEEK_START.plusDays(5), PlanTimeline.single(after), base.seed, sink = sinkB, hours = hour)
    check("same seed ⇒ same shoppers, visits, sales", digest(a.records) == digest(b.records), "digest ${digest(a.records)}")
    check("same seed ⇒ same samples", sinkA.streams == sinkB.streams, "${sinkA.streams.values.sumOf { it.size }} samples")
    val c = m.runDay(WEEK_START.plusDays(5), PlanTimeline.single(after), base.seed + 1, hours = hour)
    check("different seed ⇒ different day", digest(c.records) != digest(a.records), "seed+1 digest ${digest(c.records)}")

    val predicted = oracleImpressions(m.fixtures, sinkA.streams)
    val claimed = a.records.associate { r -> r.id to r.visits.filter { it.impression }.map { it.fixture } }
    val mismatches = a.records.filter { predicted[it.id].orEmpty().sorted() != claimed[it.id].orEmpty().sorted() }
    check(
        "oracle impressions = agent's claimed impressions, per shopper",
        mismatches.isEmpty(),
        "${a.records.size} shoppers, ${predicted.values.sumOf { it.size }} predicted, ${claimed.values.sumOf { it.size }} claimed; " +
            "mismatched=${mismatches.take(3).map { "${it.id}: oracle=${predicted[it.id]} agent=${claimed[it.id]}" }}",
    )

    val orphanLines = a.records.flatMap { r ->
        val looked = r.visits.filter { it.impression }.map { it.fixture }.toSet()
        r.lines.filter { !it.noLook && it.cell.fixture !in looked }.map { "${r.id}@${it.cell.fixture}" }
    }
    check("no sale line without a prior impression at that fixture (except gift/impulse)", orphanLines.isEmpty(), "orphans=${orphanLines.take(5)}")
    val noLookFrom = a.records.filter { r -> r.lines.any { it.noLook } }.map { it.persona }.toSet()
    val impulseOnly = a.records.all { r -> r.lines.filter { it.noLook }.all { r.persona == "gift" || it.cell.fixture == StoreWorld.IMPULSE_FIXTURE } }
    check("no-look lines come only from the gift persona or the impulse fixture", impulseOnly, "personas with no-look lines: $noLookFrom")

    // Requirement 8's identity, recomputed from the ORACLE's impressions instead of the agent's claims.
    val ledger = a.ledger
    val byOracle = HashMap<String, Pair<MutableSet<String>, MutableSet<String>>>()
    a.records.forEach { r ->
        val bought = r.lines.map { it.cell.fixture }.toSet()
        predicted[r.id].orEmpty().toSet().forEach { f ->
            val e = byOracle.getOrPut(f) { HashSet<String>() to HashSet() }
            e.first += r.id
            if (f in bought) e.second += r.id
        }
    }
    val shareMismatch = byOracle.filter { (f, e) ->
        ledger.row(f).dwelled.size != e.first.size ||
            ledger.row(f).dwelledAndBoughtHere.size != e.second.size
    }
    val sample = byOracle.entries.filter { it.value.second.isNotEmpty() }.maxByOrNull { it.value.first.size }
    check(
        "dwelled-then-bought-here share: oracle impressions reproduce the ledger, every fixture",
        shareMismatch.isEmpty(),
        sample?.let {
            "e.g. ${it.key}: ${it.value.second.size}/${it.value.first.size} = ${"%.2f".format(it.value.second.size.toDouble() / it.value.first.size)}"
        }
            ?: "no buyers",
    )

    // ── 3. the six truths (requirements 2, 3, 10) ─────────────────────────────────────────────────────────
    log.info("[3] truths — same seeded week, truth OFF / default / DOUBLED")
    val t = base.truths
    val entrance = base.reset.entranceTableFixture
    val corner = base.reset.heroBackCornerFixture

    truth("entrance table ⇒ impressions", t.entranceTableImpressions, after, base, { p, v ->
        p.copy(entranceTableImpressions = v)
    }) { it.row(entrance).impressions.toDouble() }
    truth("entrance table ⇒ pickup per impression", t.entranceTablePickup, after, base, { p, v ->
        p.copy(entranceTablePickup = v)
    }) { perImpression(it, entrance) }
    truth("back corner ⇒ impressions", t.backCornerImpressions, before, base, { p, v ->
        p.copy(backCornerImpressions = v)
    }) { it.row(corner).impressions.toDouble() }
    truth("eye level ⇒ pickup of eye-level cells", t.eyeLevelPickup, after, base, { p, v ->
        p.copy(eyeLevelPickup = v)
    }) { pickupsAtLevel(it, t.eyeLevel) }
    truth("floor level ⇒ pickup of floor cells", t.floorLevelPickup, after, base, { p, v ->
        p.copy(floorLevelPickup = v)
    }) { pickupsAtLevel(it, t.levelsPerFixture) }
    val coherent = m.presentations.of(after).values.filter { it.colorStory == FixturePresentation.ColorStory.COHERENT }.map { it.code }.toSet()
    truth("color story (coherent) ⇒ impressions of coherent faces", t.colorStoryCoherent, after, base, { p, v ->
        p.copy(colorStoryCoherent = v)
    }) { l ->
        coherent.sumOf { l.row(it).impressions }.toDouble()
    }
    val clash = m.presentations.of(after).values.filter { it.colorStory == FixturePresentation.ColorStory.CLASH }.map { it.code }.toSet()
    truth("color story (clash) ⇒ impressions of clashing faces", t.colorStoryClash, after, base, { p, v -> p.copy(colorStoryClash = v) }) { l ->
        clash.sumOf { l.row(it).impressions }.toDouble()
    }
    val compl = base.reset.complementHandles.toSet()
    truth("complement beside hero ⇒ conversion of the complement", t.adjacencyComplement, after, base, { p, v ->
        p.copy(adjacencyComplement = v)
    }) { w ->
        conversion(w.records, entrance) { it.handle in compl }
    }

    // Size run is a STYLING check (requirement 3's "two stylings that differ only in one input"): the same
    // fixture with a style's middle sizes removed vs the complete run.
    sizeRun(m, base, after)

    // ── 4. plan reset: before/after and a flat control (requirement 5) ────────────────────────────────────
    log.info("[4] scripted reset — 14 days, reset effective day 8 at open")
    val resetDay = WEEK_START.plusDays(7)
    val timeline = PlanTimeline(listOf(m.at(WEEK_START, 0) to before, m.at(resetDay, 0) to after))
    val days = (0L until 14L).map { m.runDay(WEEK_START.plusDays(it), timeline, base.seed) }
    val pre = FixtureLedger.of(days.take(7).flatMap { it.records })
    val post = FixtureLedger.of(days.drop(7).flatMap { it.records })
    val control = base.reset.controlFixture
    fun line(code: String) =
        "${pre.row(code).impressions} → ${post.row(code).impressions} impressions, ${pre.row(code).units} → ${post.row(code).units} units"
    check("entrance table rises after the reset", post.row(entrance).impressions > 3 * pre.row(entrance).impressions, "$entrance ${line(entrance)}")
    check(
        "units sold where the heroes stand rise with the move",
        unitsAt(post, entrance) > unitsAt(pre, corner),
        "units $corner(before)=${unitsAt(pre, corner)} $entrance(after)=${unitsAt(post, entrance)}",
    )
    check("back corner falls once the heroes leave", post.row(corner).impressions < pre.row(corner).impressions, "$corner ${line(corner)}")
    val drift =
        kotlin.math.abs(post.row(control).impressions - pre.row(control).impressions).toDouble() / pre.row(control).impressions.coerceAtLeast(1)
    check("untouched control fixture stays flat (±15%)", drift <= 0.15, "$control ${line(control)} (drift ${"%.1f".format(drift * 100)}%)")
    check(
        "every shopper read the plan active when they entered",
        days.all { d ->
            d.records.all {
                it.planId ==
                    timeline.at(java.time.Instant.ofEpochMilli(it.entryMs)).id
            }
        },
        "plans ${before.id} → ${after.id}",
    )

    log.info("[4b] same reset effective mid-day (15:00): shoppers entering after it see the new plan")
    val midday = PlanTimeline(listOf(m.at(WEEK_START, 0) to before, m.at(resetDay, 15) to after))
    val split = m.runDay(resetDay, midday, base.seed)
    val early = split.records.filter { it.entryMs < m.at(resetDay, 15).toEpochMilli() }
    val late = split.records - early.toSet()
    check(
        "hourly response within the simulated day",
        early.all { it.planId == before.id } && late.all { it.planId == after.id } &&
            perShopper(late, entrance) > 3 * perShopper(early, entrance),
        "$entrance impressions/shopper ${"%.3f".format(
            perShopper(early, entrance),
        )} before 15:00, ${"%.3f".format(perShopper(late, entrance))} after",
    )

    // ── 5. history window guard (requirement 9) ───────────────────────────────────────────────────────────
    log.info("[5] history window guard")
    val today = LocalDate.of(2026, 11, 4)
    check("84 days back is accepted", runCatching { HistoryPlan.of(today, 84, 42) }.isSuccess, "start ${today.minusDays(84)}")
    check(
        "85+ days back is refused (stays inside the 90-day compression window)",
        runCatching {
            HistoryPlan.of(today, 85, 42)
        }.isFailure,
        "start ${today.minusDays(85)}",
    )
    check("reset must fall inside the window", runCatching { HistoryPlan.of(today, 56, 60) }.isFailure, "reset 60 days back in a 56-day window")

    log.info("")
    log.info("behaviorSelfTest — {} passed, {} failed", passed, failed)
    if (failed > 0) exitProcess(1)
}

// ── measures ──────────────────────────────────────────────────────────────────────────────────────────────

/** A seeded week's records and their ledger. */
private class Week(val records: List<ShopperRecord>) {
    val ledger: FixtureLedger = FixtureLedger.of(records)
    fun row(code: String) = ledger.row(code)
}

private fun week(params: BehaviorParams, plan: ShelfPlan): Week {
    val m = BehaviorModel(params)
    return Week((0L until 7L).flatMap { m.runDay(WEEK_START.plusDays(it), PlanTimeline.single(plan), params.seed).records })
}

/**
 * Measure [measure] with the truth at 1.0, at [default], and at `1 + 2 × (default − 1)`. Pass bands:
 * default ratio within 35% of the stated size (in the stated direction, and never on the wrong side of 1.0),
 * and the doubled effect between 1.5× and 2.5× the default effect.
 */
private fun truth(
    name: String,
    default: Double,
    plan: ShelfPlan,
    base: BehaviorParams,
    set: (BehaviorParams.Truths, Double) -> BehaviorParams.Truths,
    measure: (Week) -> Double,
) {
    fun run(v: Double): Double = measure(week(base.withTruths { set(it, v) }, plan))
    val off = run(1.0)
    val on = run(default)
    val doubled = run((1 + 2 * (default - 1)).coerceAtLeast(0.0))
    val r1 = if (off == 0.0) Double.NaN else on / off
    val r2 = if (off == 0.0) Double.NaN else doubled / off
    val dirOk = if (default > 1.0) r1 > 1.0 else r1 < 1.0
    val sizeOk = kotlin.math.abs((r1 - 1) - (default - 1)) <= 0.35 * kotlin.math.abs(default - 1) + 0.02
    val doubling = (r2 - 1) / (r1 - 1)
    check(
        name,
        dirOk && sizeOk && doubling in 1.5..2.5,
        "off=${"%.1f".format(off)} default(×$default)=${"%.1f".format(on)} ratio ${"%.3f".format(r1)}; doubled ratio ${"%.3f".format(r2)} " +
            "→ effect ×${"%.2f".format(doubling)}",
    )
}

private fun perImpression(w: Week, code: String): Double = w.row(code).let {
    if (it.impressions ==
        0
    ) {
        0.0
    } else {
        it.engagements.toDouble() / it.impressions
    }
}

private fun pickupsAtLevel(w: Week, level: Int): Double =
    w.records.flatMap { it.visits }.filter { it.kind == Visit.Kind.BROWSE }.flatMap { it.pickups }.count { it.cell.level == level }.toDouble()

private fun conversion(rs: List<ShopperRecord>, fixture: String, pred: (Cell) -> Boolean): Double {
    val ps = rs.flatMap { it.visits }.filter { it.fixture == fixture && it.kind == Visit.Kind.BROWSE }.flatMap { it.pickups }.filter { pred(it.cell) }
    return if (ps.isEmpty()) 0.0 else ps.count { it.bought }.toDouble() / ps.size
}

private fun unitsAt(l: FixtureLedger, code: String): Int = l.row(code).units

private fun perShopper(rs: List<ShopperRecord>, code: String): Double =
    if (rs.isEmpty()) 0.0 else rs.sumOf { r -> r.visits.count { it.fixture == code && it.impression } }.toDouble() / rs.size

private fun hourPeak(d: BehaviorModel.DayRun): Int {
    val zone = java.time.ZoneId.of("America/Denver")
    return d.records.groupingBy { java.time.Instant.ofEpochMilli(it.entryMs).atZone(zone).hour }.eachCount().maxBy { it.value }.key
}

private fun digest(rs: List<ShopperRecord>): String {
    val md = MessageDigest.getInstance("SHA-256")
    rs.forEach { r ->
        md.update("${r.id}|${r.persona}|${r.entryMs}|${r.exitMs}|".toByteArray())
        r.visits.forEach { v ->
            md.update("${v.fixture}:${v.startMs}:${v.dwellMs}:${v.pickups.joinToString { "${it.cell.ean}${it.tried}${it.bought}" }};".toByteArray())
        }
        r.lines.forEach { md.update("${it.cell.ean}@${it.cell.fixture};".toByteArray()) }
    }
    return md.digest().take(6).joinToString("") { "%02x".format(it) }
}

/**
 * Size run as two stylings of the same fixture. The style is chosen from the data, not named: the complete,
 * multi-size (6+) style handled most often in a baseline week, so the comparison has enough pickups to read. Its
 * middle sizes are removed; nothing else changes.
 */
private fun sizeRun(m: BehaviorModel, base: BehaviorParams, after: ShelfPlan) {
    val baseline = week(base, after)
    val (fixture, style) = baseline.records.asSequence().flatMap { it.visits }.filter { it.kind == Visit.Kind.BROWSE }.flatMap { it.pickups }
        .map { it.cell.fixture to it.cell.handle }
        .filter { (f, h) ->
            val run = m.stock.catalogSizes[h].orEmpty()
            run.size >= 6 && after.cells(f).filter { it.handle == h }.map { it.size }.toSet() == run
        }
        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        ?: run {
            check(
                "broken size run sinks conversion of that style (two stylings)",
                false,
                "no complete multi-size style was handled in the baseline week",
            )
            return
        }
    val run = m.stock.catalogSizes.getValue(style).sorted()
    val mid = run.drop(run.size / 4).take(run.size / 2).toSet()
    val cells = after.cells(fixture)
    val broken = ShelfPlan("broken-run", after.cellsByFixture + (fixture to cells.filterNot { it.handle == style && it.size in mid }))
    fun conv(w: Week) = conversion(w.records, fixture) { it.handle == style }
    val complete = conv(baseline)
    val brokenConv = conv(week(base, broken))
    val brokenOff = conv(week(base.withTruths { it.copy(sizeRunConversion = 1.0) }, broken))
    val missing = mid.size.toDouble() / run.size
    val expected = maxOf(base.truths.sizeRunConversion, 1.0 - (1.0 - base.truths.sizeRunConversion) * missing / base.truths.sizeRunFullBreakShare)
    val r = brokenConv / complete
    check(
        "broken size run sinks conversion of that style (two stylings)",
        r < 1.0 && kotlin.math.abs(r - expected) <= 0.2 && kotlin.math.abs(brokenOff / complete - 1.0) <= 0.2,
        "$style on $fixture: complete ${"%.3f".format(complete)}, ${mid.size}/${run.size} middle sizes removed ${"%.3f".format(brokenConv)} " +
            "(ratio ${"%.2f".format(r)}, rule says ${"%.2f".format(expected)}); same broken run with the truth off ${"%.3f".format(brokenOff)}",
    )
}
