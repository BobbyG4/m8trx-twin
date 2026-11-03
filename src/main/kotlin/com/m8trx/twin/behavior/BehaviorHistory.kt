package com.m8trx.twin.behavior

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.exitProcess

/**
 * The backdated-history window (TWIN-REQ-007 requirement 9): `daysBack` days ending yesterday, with the scripted
 * reset `resetDaysBack` days ago.
 *
 * **Hard limit: 84 days back (12 weeks).** TimescaleDB compresses `item_custody_event` and `compliance_event`
 * chunks older than 90 days (7-day chunks). The 90-day line moves with the clock, so the guarantee is a race: a
 * backfill starting [daysBack] days back must FINISH within [wallMarginDays] (= 90 − daysBack) days of wall time,
 * or its oldest rows land in a chunk that is already compressed. At 84 days back that margin is 6 days; at the
 * edge path's measured ~40 min per Denver day, 84 days take ~56 h, which fits. A longer window is a brief to core,
 * never a decompression.
 */
data class HistoryPlan(val today: LocalDate, val start: LocalDate, val end: LocalDate, val reset: LocalDate) {
    val days: List<LocalDate> get() = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()

    /** Wall-clock days a live backfill has before its oldest day crosses the 90-day compression line. */
    val wallMarginDays: Long get() = 90 - java.time.temporal.ChronoUnit.DAYS.between(start, today)

    companion object {
        const val MAX_DAYS_BACK = 84
        const val MIN_DAYS_BACK = 56

        fun of(today: LocalDate, daysBack: Int, resetDaysBack: Int): HistoryPlan {
            require(daysBack in MIN_DAYS_BACK..MAX_DAYS_BACK) {
                "history must span $MIN_DAYS_BACK..$MAX_DAYS_BACK days back (8–12 weeks, inside the 90-day compression window); got $daysBack"
            }
            require(resetDaysBack in 7 until daysBack - 6) {
                "the reset must leave at least a week either side inside the window; got $resetDaysBack of $daysBack"
            }
            return HistoryPlan(today, today.minusDays(daysBack.toLong()), today.minusDays(1), today.minusDays(resetDaysBack.toLong()))
        }
    }
}

private val log = LoggerFactory.getLogger("com.m8trx.twin.behavior.BehaviorHistory")

/**
 * `./gradlew behaviorHistory` — generate the backdated history in RECORD mode: every day of the window through the
 * same agents a live run uses, with the reset on its date, written to files the planogram lane can check a later
 * live backfill against (requirement 7: the oracle can dump expected per-fixture totals).
 *
 * **It sends nothing.** A live backfill through the platform's own paths waits on two things this round cannot
 * supply: the re-measure of whether those paths honor a past timestamp (`behaviorBackdateProbe`, and TWIN-REQ-008
 * if they do not), and Bob's go. `M8TRX_HISTORY_LIVE=true` is refused with that reason rather than half-built.
 *
 * Env: `M8TRX_BEHAVIOR_SITE` (dec-us-denver) · `M8TRX_HISTORY_TODAY` (today at the site) · `M8TRX_HISTORY_DAYS`
 * (84) · `M8TRX_HISTORY_RESET_DAYS_BACK` (42) · `M8TRX_HISTORY_SCALE` (1.0) · `M8TRX_HISTORY_OUT`
 * (build/behavior/history-<site>-<start>).
 */
fun main() {
    val site = System.getenv("M8TRX_BEHAVIOR_SITE") ?: "dec-us-denver"
    if (System.getenv("M8TRX_HISTORY_LIVE")?.toBoolean() == true) {
        log.error(
            "REFUSING M8TRX_HISTORY_LIVE=true: a live backfill waits on (1) the past-timestamp re-measure " +
                "(behaviorBackdateProbe → TWIN-REQ-008 if a path ignores occurred_at/ts) and (2) Bob's go. Record mode only in round 1.",
        )
        exitProcess(2)
    }
    val m = BehaviorModel.forSite(site)
    val today = System.getenv("M8TRX_HISTORY_TODAY")?.let(LocalDate::parse) ?: LocalDate.now(ZoneId.of(m.params.timezone))
    val plan = HistoryPlan.of(today, env("M8TRX_HISTORY_DAYS", "84").toInt(), env("M8TRX_HISTORY_RESET_DAYS_BACK", "42").toInt())
    val scale = env("M8TRX_HISTORY_SCALE", "1.0").toDouble()
    val out = Path.of(System.getenv("M8TRX_HISTORY_OUT") ?: "build/behavior/history-$site-${plan.start}")
    Files.createDirectories(out)

    val (before, after) = m.stock.resetPair(m.params)
    val timeline = PlanTimeline(listOf(m.at(plan.start, 0) to before, m.at(plan.reset, 0) to after))
    log.info("history (RECORD) site={} {}..{} reset {} seed={} scale={}", site, plan.start, plan.end, plan.reset, m.params.seed, scale)
    log.info("  plans {} → {}", before.id, after.id)

    // Reconciliation is the calibration gate for the BASELINE plan. After the reset the plan is meant to move
    // conversion and basket value — that lift is the effect being simulated, so post-reset days are reported,
    // not gated. Per-day misses at the 3-sigma tolerance are expected occasionally (persona mix makes daily
    // conversion over-dispersed against the binomial tolerance), so the gate is on the share of baseline days.
    val writer = HistoryFiles(out)
    var baselineDays = 0
    var baselineMisses = 0
    plan.days.forEach { d ->
        val run = m.runDay(d, timeline, m.params.seed, scale)
        val ok = run.reconcile().ok
        if (d.isBefore(plan.reset)) {
            baselineDays++
            if (!ok) baselineMisses++
        }
        writer.day(run, timeline.at(m.at(d, 12)).id, ok)
    }
    writer.close(timeline)
    log.info("wrote {} days to {}", plan.days.size, out.toAbsolutePath())
    log.info("baseline (pre-reset) days outside reconciliation tolerance: {}/{} (gate: at most 10%)", baselineMisses, baselineDays)
    if (baselineMisses > baselineDays / 10) exitProcess(1)
}

/** The record-mode files. Every column is something the platform should later show for the same run. */
private class HistoryFiles(private val dir: Path) {
    private val days = Files.newBufferedWriter(dir.resolve("days.csv")).apply {
        write("date,dow,plan_id,visitors,expected_visitors,transactions,units,revenue_usd,reconciles\n")
    }
    private val fixtures = Files.newBufferedWriter(dir.resolve("fixture_daily.csv")).apply {
        write("date,fixture,stops,impressions,look_s,platform_duration_s,engagements,try_ons,units,revenue_usd,dwelled,dwelled_and_bought_here\n")
    }

    fun day(run: BehaviorModel.DayRun, planId: String, ok: Boolean) {
        days.write(
            "${run.date},${run.date.dayOfWeek},$planId,${run.records.size},${"%.1f".format(
                run.expectedVisitors,
            )},${run.transactions},${run.units},${"%.2f".format(run.revenueUsd)},$ok\n",
        )
        run.ledger.rows.toSortedMap().forEach { (f, r) ->
            fixtures.write(
                "${run.date},$f,${r.stops},${r.impressions},${r.dwellMs / 1000},${r.platformDurationMs / 1000}," +
                    "${r.engagements},${r.tryOns},${r.units},${"%.2f".format(r.revenueUsd)},${r.dwelled.size},${r.dwelledAndBoughtHere.size}\n",
            )
        }
    }

    fun close(timeline: PlanTimeline) {
        days.close()
        fixtures.close()
        Files.writeString(
            dir.resolve("plan-changes.csv"),
            "effective_at,plan_id,plan_name\n" + timeline.changes.joinToString("") { "${it.first},${it.second.id},${it.second.name}\n" },
        )
    }
}

private fun env(k: String, d: String) = System.getenv(k)?.takeIf { it.isNotBlank() } ?: d
