package com.m8trx.twin.layer3

import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * `./gradlew lossAudit` — structural profile of a generated day, for interpreting a NATS-vs-DB
 * impression comparison. Offline, no network.
 *
 * Motivating case: `fullday-0728` emitted **3,664** `fixtureImpression` events over NATS (oracle predicted
 * 3,664, exact) but persisted **2,256** `impression_event` rows — a 1,408-row / 38.4% gap, with zero errors
 * on the twin edge. Core owns that investigation (filed with the BW lane); this exists so twin hands over
 * measured structure instead of speculation, including hypotheses it has already **falsified**:
 *
 *  1. **Cache-capacity / concurrency ceiling — REFUTED.** Peak concurrent `(shopper, fixture)` cache
 *     entries barely move with run length, because pacing compresses ARRIVALS while leaving episode
 *     durations untouched: peak concurrency is set by arrival rate × episode length, not by total duration.
 *     A 4.3× volume difference between runs therefore does **not** imply a 4.3× concurrency difference, so
 *     "it fell over under load" does not follow from the volume gap alone.
 *  2. **Row keyed on `(person_session, fixture)` so repeat visits UPDATE instead of INSERT — REFUTED.**
 *     Repeat visits exist (a journey draws fixtures with `pool.random()`, so a shopper can return to a rack
 *     and core fires a second impression), but they are a few percent of the total — an order of magnitude
 *     too small to account for the gap.
 *
 * **Read the caveat before quoting any number here.** This describes the generator at HEAD, which is not
 * the generator that produced `fullday-0728`: the S16 fixture-coverage fix deliberately made the day browse
 * more fixtures, so HEAD predicts ~3,967 where S15 predicted 3,664. To reconstruct a past run exactly,
 * check out that run's commit. The two *refutations* above survive this drift because they turn on
 * magnitudes, not on exact counts.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.layer3.LossAudit")

fun main() {
    val date = LocalDate.of(2026, 7, 28)
    val seed = System.getenv("M8TRX_DAY_SEED")?.toLongOrNull() ?: 4242L
    val scale = System.getenv("M8TRX_DAY_SCALE")?.toDoubleOrNull() ?: 1.0
    val compress = System.getenv("M8TRX_DAY_COMPRESS")?.toDoubleOrNull() ?: 18.0
    val fromHour = System.getenv("M8TRX_DAY_FROM_HOUR")?.toIntOrNull()
    val toHour = System.getenv("M8TRX_DAY_TO_HOUR")?.toIntOrNull()

    val runner = ScenarioRun()
    log.info("═══ impression structural profile ═══")
    log.info("date={} seed={} scale={} compress={}x window={}..{}", date, seed, scale, compress, fromHour, toHour)
    log.info("⚠ describes the generator at HEAD, NOT the S15 runs — see the KDoc caveat.")
    log.info("")

    val (peakCache, peakSessions, _) = runner.cacheWorkingSet(
        date,
        seed,
        scale,
        compress,
        fromHour = fromHour,
        toHour = toHour,
    )
    val (impressions, distinctPairs, shoppers) = runner.repeatVisitProfile(
        date,
        seed,
        scale,
        fromHour = fromHour,
        toHour = toHour,
    )
    val repeats = impressions - distinctPairs
    val repeatPct = if (impressions == 0) 0.0 else repeats * 100.0 / impressions

    log.info("  shoppers                                {}", shoppers)
    log.info("  oracle impressions (rule fires)         {}", impressions)
    log.info("  distinct (shopper, fixture) pairs       {}", distinctPairs)
    log.info("  repeat visits                           {} ({}%)", repeats, "%.1f".format(repeatPct))
    log.info("  peak concurrent cache entries           {}", peakCache)
    log.info("  peak concurrent sessions                {}", peakSessions)
    log.info("")
    log.info("── what this constrains ──")
    log.info("  Repeat visits are {}% of impressions. A dedup-on-(session,fixture) mechanism could", "%.1f".format(repeatPct))
    log.info("  therefore explain at most that share of any NATS-vs-DB gap — not a 38% one.")
    log.info("")
    log.info("  Peak concurrency is ~{} entries. Under arrival compression this is roughly independent", peakCache)
    log.info("  of run length, so a longer run is NOT a proportionally more concurrent one, and a")
    log.info("  volume-scaled loss cannot be inferred from a concurrency ceiling.")
    log.info("")
    log.info("── the one hard fact for core ──")
    log.info("  In the SAME fullday-0728 run: person_session persisted 790/790 (0% loss) while")
    log.info("  impression_event persisted 2,256/3,664 (38.4% loss). Same stream, same edge, same")
    log.info("  window, zero twin-side errors. That localises the gap to the impression path")
    log.info("  specifically — downstream of ingest, and not the rule, since the rule's own output")
    log.info("  (the NATS events) matched twin's oracle exactly.")
    log.info("")
    log.info("── oracle standing ──")
    log.info("  The oracle models the RULE and is validated against the EMITTED STREAM. It has never")
    log.info("  modelled persistence and should not. '3,664 = 3,664' was always evidence that the rule")
    log.info("  fired 3,664 times — never that 3,664 rows were stored. Those are two separate claims,")
    log.info("  and S15 only ever established the first.")
}
