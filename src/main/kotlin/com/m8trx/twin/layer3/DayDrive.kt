package com.m8trx.twin.layer3

import com.m8trx.twin.TwinConfig
import com.m8trx.twin.domain.ObjEviction
import com.m8trx.twin.domain.ObjLocation
import com.m8trx.twin.layer0.NatsEmitter
import com.m8trx.twin.layer0.objEviction
import com.m8trx.twin.layer0.objLocation
import com.m8trx.twin.layer1.FixtureSet
import com.m8trx.twin.layer1.ImpressionOracle
import io.nats.client.Nats
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalTime
import kotlin.system.exitProcess

/**
 * Drive a whole generated store day into the twin edge — `./gradlew connectDayDrive`.
 *
 * The day is generated and oracle-checked OFFLINE first (see [ScenarioRun]), so the expected impression
 * count is known before a single event is published. Then the recorded streams are replayed live.
 *
 * ## Pacing — compress the GAPS, never the EPISODES
 *
 * The impression rule has hard ABSOLUTE timings: `millisTillImpression` 5000ms, both allowances 1000ms, a
 * >1 Hz emit floor, and a 10s cache. Compressing episode durations uniformly would push dwell under 5s and
 * yield **zero impressions from a run that looks perfectly healthy** — the single most expensive mistake
 * available here.
 *
 * So the replay splits every inter-sample delta in two:
 *  - **Within an episode** (same shopper, gap ≤ [EPISODE_GAP_MS]) → replayed at REAL time, untouched.
 *  - **Everything else** (idle spans, inter-arrival time, walks between fixtures) → divided by the factor.
 *
 * Episode maths is therefore identical to the offline run, so the oracle's predicted counts stay valid.
 * A 11-hour trading day compresses to roughly 35–45 minutes at 15–20×.
 *
 * Safety interlocks are the same two as `PeopleDrive`: the NATS `server_name` must be `edge-twin-denver`
 * (`:4222` is `edge-itx-office`, production, real Xovis hardware), and the office space is denylisted.
 *
 * Env: `M8TRX_NATS_URL` · `M8TRX_SPACE_ID` · `M8TRX_SITE_ID` · `M8TRX_TENANT_ID` ·
 * `M8TRX_DAY_DATE` (2026-07-28) · `M8TRX_DAY_SEED` (4242) · `M8TRX_DAY_SCALE` (1.0) ·
 * `M8TRX_DAY_COMPRESS` (18.0) · `M8TRX_DAY_FROM_HOUR` / `M8TRX_DAY_TO_HOUR` (slice a window — use this to
 * fit a run inside an observation window, or when the machine may sleep) · `M8TRX_DAY_LIVE` (false).
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.layer3.DayDrive")

private const val EXPECTED_SERVER = "edge-twin-denver"
private const val OFFICE_SPACE = "0efb9aaa"

/** Gaps at or below this are treated as intra-episode and are never compressed. */
private const val EPISODE_GAP_MS = 1_000L

fun main() {
    val natsUrl = req("M8TRX_NATS_URL")
    val spaceId = req("M8TRX_SPACE_ID")
    val siteId = req("M8TRX_SITE_ID")
    val tenantId = req("M8TRX_TENANT_ID")
    val date = LocalDate.parse(env("M8TRX_DAY_DATE", "2026-07-28"))
    val seed = env("M8TRX_DAY_SEED", "4242").toLong()
    val scale = env("M8TRX_DAY_SCALE", "1.0").toDouble()
    val compress = env("M8TRX_DAY_COMPRESS", "18.0").toDouble()
    val fromHour = System.getenv("M8TRX_DAY_FROM_HOUR")?.toIntOrNull()
    val toHour = System.getenv("M8TRX_DAY_TO_HOUR")?.toIntOrNull()
    val live = env("M8TRX_DAY_LIVE", "false").toBoolean()

    require(compress >= 1.0) { "M8TRX_DAY_COMPRESS must be >= 1.0" }
    if (spaceId.startsWith(OFFICE_SPACE)) {
        log.error("REFUSING: M8TRX_SPACE_ID={} is the OFFICE space (real Xovis hardware).", spaceId)
        exitProcess(2)
    }

    // ── generate + oracle-check offline, BEFORE publishing anything ───────────
    log.info("═══ DayDrive ═══  date={} seed={} scale={} compress={}x window={}..{}", date, seed, scale, compress, fromHour, toHour)
    val result = ScenarioRun().run(date, seed, scale)
    log.info(
        "generated: visitors={} transactions={} revenueUsd={} samples={} predictedImpressions={}",
        result.visitors,
        result.transactions,
        "%.2f".format(result.revenueUsd),
        result.emittedSamples,
        result.predictedImpressions,
    )
    log.info("\n{}", result.reconciliation.render())
    check(result.reconciliation.ok) { "the generated day does not reconcile — refusing to drive it" }

    // Re-generate the streams (ScenarioRun keeps them internally; re-run with the same seed is identical).
    val chainDir = Path.of(env("M8TRX_CHAIN_DIR", "reference/data/chain"))
    val fixtures = FixtureSet.load(chainDir.resolve("stores/dec-us-denver/layout.json"))
    val streams = ScenarioRun().streamsFor(date, seed, scale)

    // ── PACING: compress each shopper's ARRIVAL, never their internal timing ──
    //
    // The obvious implementation — walk the merged timeline and compress any gap that is not
    // intra-episode — is WRONG, and silently so. With ~790 overlapping shoppers, two consecutive samples
    // on the merged timeline are almost always DIFFERENT shoppers, so nearly every delta looks
    // inter-episode and gets divided. That squeezes a shopper's 200ms sample spacing to ~11ms and their
    // 8.4s dwell to ~470ms — under `millisTillImpression`, so the whole run yields zero impressions while
    // looking completely healthy for 26 minutes.
    //
    // Instead: shift each shopper's START by dividing their offset from store-open, and keep every sample
    // at its ORIGINAL offset within that shopper. Episode durations, sample spacing and walk gaps are all
    // untouched, so the oracle's predicted counts remain exactly valid. Shoppers simply overlap more.
    data class Wire(val ts: Long, val objectId: String, val s: ImpressionOracle.Sample)
    val open = LocalTime.parse("10:00")
    val dayStart = streams.values.flatten().minOf { it.tsMs }

    // ⚠ SLICE IN STORE TIME, BEFORE COMPRESSION. Filtering the re-based timeline instead is wrong: the
    // compressed day spans ~1h of wall time, so "hour 17" lands far past its end and the slice comes back
    // EMPTY. Window on the original timestamps, then re-base only the survivors.
    //
    // A shopper is kept whole if their session STARTS inside the window — clipping mid-session would cut
    // episodes and silently destroy the very dwell the run exists to produce.
    val sliced: Map<String, List<ImpressionOracle.Sample>> = if (fromHour == null && toHour == null) {
        streams
    } else {
        val lo = dayStart + ((fromHour ?: open.hour) - open.hour) * 3_600_000L
        val hi = dayStart + ((toHour ?: 24) - open.hour) * 3_600_000L
        streams.filterValues { it.minOf { s -> s.tsMs } in lo until hi }
            .also { log.info("sliced to store hours {}..{} → {} shoppers (sessions kept whole)", fromHour, toHour, it.size) }
    }
    if (sliced.isEmpty()) {
        log.error("no shoppers in the requested window — nothing to drive")
        exitProcess(1)
    }

    val sliceStart = sliced.values.flatten().minOf { it.tsMs }
    val timeline = sliced.flatMap { (id, samples) ->
        val ordered = samples.sortedBy { it.tsMs }
        val origStart = ordered.first().tsMs
        var t = sliceStart + ((origStart - sliceStart) / compress).toLong() // arrival: compressed
        val out = ArrayList<Wire>(ordered.size)
        out.add(Wire(t, id, ordered.first()))
        for (i in 1 until ordered.size) {
            val d = ordered[i].tsMs - ordered[i - 1].tsMs
            // ≤1000ms ⇒ INSIDE an episode ⇒ replay verbatim, or the clocks never accumulate.
            // Anything larger is idle — a walk to the next rack, or minutes standing in a zone that emits
            // no samples at all — and is exactly what the factor is for. Leaving intra-shopper idle at real
            // time was over-conservative: it kept a session at 16 minutes of mostly dead air.
            t += if (d <= EPISODE_GAP_MS) d else (d / compress).toLong().coerceAtLeast(EPISODE_GAP_MS + 1)
            out.add(Wire(t, id, ordered[i]))
        }
        out
    }.sortedBy { it.ts }

    val oracle = ImpressionOracle()
    val slicedByObj = timeline.groupBy({ it.objectId }, { it.s })
    val expectedInSlice = slicedByObj.values.sumOf { oracle.run(fixtures, it).size }

    // Wall time is now simply the span of the re-based timeline; deltas are replayed verbatim.
    val realMs = sliced.values.flatten().let { it.maxOf { s -> s.tsMs } - it.minOf { s -> s.tsMs } }
    val compressedMs = timeline.last().ts - timeline.first().ts

    // Guard the invariant rather than trusting the arithmetic: every shopper's intra-sample spacing must
    // survive the rebase, or the run is the silent-zero failure described above.
    val worstIntraGap = timeline.groupBy { it.objectId }
        .mapNotNull { (_, w) -> w.sortedBy { it.ts }.zipWithNext { a, b -> b.ts - a.ts }.maxOrNull() }
        .maxOrNull() ?: 0L
    val worstEpisodeSpacing = timeline.groupBy { it.objectId }
        .mapNotNull { (_, w) -> w.sortedBy { it.ts }.zipWithNext { a, b -> b.ts - a.ts }.filter { it <= EPISODE_GAP_MS }.maxOrNull() }
        .maxOrNull() ?: 0L
    check(worstEpisodeSpacing in 1..EPISODE_GAP_MS) {
        "intra-episode sample spacing was altered by the rebase (worst=${worstEpisodeSpacing}ms) — " +
            "episodes MUST replay at real time or nothing can ever fire"
    }
    log.info(
        "  pacing check: worst intra-episode spacing {}ms (must stay <= {}ms), worst intra-shopper gap {}ms",
        worstEpisodeSpacing,
        EPISODE_GAP_MS,
        worstIntraGap,
    )
    log.info(
        "PLAN: {} samples · {} shoppers · expected impressions in slice = {} · store time {} → wall time ~{} at {}x",
        timeline.size,
        slicedByObj.size,
        expectedInSlice,
        fmt(realMs),
        fmt(compressedMs),
        compress,
    )
    log.info("      episode deltas replay at REAL time; only inter-episode gaps are divided.")

    if (!live) {
        log.info("DRY-RUN (M8TRX_DAY_LIVE!=true) — nothing published. Set M8TRX_DAY_LIVE=true to fire.")
        return
    }

    // ── interlock: assert the edge identity before emitting ───────────────────
    val probe = Nats.connect(natsUrl)
    val serverName = probe.serverInfo?.serverName
    probe.close()
    if (serverName != EXPECTED_SERVER) {
        log.error("REFUSING TO PUBLISH: server_name='{}' expected '{}'. :4222 is the PRODUCTION office edge.", serverName, EXPECTED_SERVER)
        exitProcess(3)
    }
    log.info("✓ interlock: edge identity confirmed as '{}'", serverName)

    val nats = NatsEmitter(
        TwinConfig(natsUrl = natsUrl, restBaseUrl = "", serviceBearer = "", tenantId = tenantId, siteId = siteId, spaceId = spaceId),
    )
    val runTag = env("M8TRX_DAY_TAG", "day-$date")
    val t0 = System.currentTimeMillis()
    var published = 0
    val seenObjects = LinkedHashSet<String>()

    // Wire timestamps come from the PLANNED timeline, not the wall clock.
    //
    // Core computes both dwell clocks from the envelope `ts`, so stamping System.currentTimeMillis() at
    // publish time lets scheduling jitter leak into the rule. The first live slice published 245k samples
    // in 18m54s against a 15m57s plan; that ~18% lag stretched some intra-episode gaps past the 1000ms
    // allowance, SPLITTING episodes and producing 854 impressions where the oracle predicted 812. The
    // tell was 854 impressions against only 790 lookingAtFixture transitions — a look-transition fires
    // only when the fixture CHANGES, so extra impressions without extra transitions means the same
    // fixture's clocks reset mid-episode.
    //
    // Anchoring ts to the plan makes the run reproducible and keeps the oracle's prediction exact
    // regardless of how evenly the publisher actually keeps time.
    val wireEpoch = System.currentTimeMillis()
    val planEpoch = timeline.first().ts

    for (i in timeline.indices) {
        val w = timeline[i]
        val wireId = "$runTag-${w.objectId}"
        seenObjects.add(wireId)
        nats.objLocation(
            ObjLocation(
                objectId = wireId,
                x = w.s.p.x,
                y = w.s.p.y,
                isMale = true,
                hasTag = false,
                viewDirection = w.s.viewDir?.let { arrayOf(it.x, it.y) },
                layoutId = spaceId,
            ),
            ts = wireEpoch + (w.ts - planEpoch),
            id = "$wireId-$i",
        )
        published++
        if (published % 2000 == 0) {
            log.info(
                "  … {}/{} samples ({}%), elapsed {}",
                published,
                timeline.size,
                published * 100 / timeline.size,
                fmt(
                    System.currentTimeMillis() - t0,
                ),
            )
        }
        if (i == timeline.size - 1) break
        // Sleep toward the PLANNED wall time for the next sample rather than the raw delta, so publishing
        // cost is absorbed instead of accumulating into drift.
        val dueAt = wireEpoch + (timeline[i + 1].ts - planEpoch)
        val sleep = dueAt - System.currentTimeMillis()
        if (sleep > 0) Thread.sleep(sleep)
    }

    seenObjects.forEach { nats.objEviction(ObjEviction(objectId = it, layoutId = spaceId), ts = System.currentTimeMillis(), id = "$it-evict") }
    log.info("published {} samples for {} shoppers in {}", published, seenObjects.size, fmt(System.currentTimeMillis() - t0))
    nats.close()
    log.info("★ expected {} impressions in this slice. Allow ~20s for the expireAfterWrite cache to flush.", expectedInSlice)
    log.info("  objectId prefix for verification: {}", runTag)
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%dh%02dm%02ds".format(s / 3600, (s % 3600) / 60, s % 60)
}

private fun env(k: String, d: String) = System.getenv(k)?.takeIf { it.isNotBlank() } ?: d
private fun req(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() } ?: error("Required env var $k is not set")
