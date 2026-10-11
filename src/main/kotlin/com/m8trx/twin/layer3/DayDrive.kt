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
 * fit a run inside an observation window, or when the machine may sleep) · `M8TRX_DAY_LIVE` (false) ·
 * `M8TRX_DAY_EPOCH_MODE` (`wall` | `plan`, default `wall`) · `M8TRX_DAY_ALLOW_FUTURE` (false).
 *
 * ## Epoch mode — which clock the envelope `ts` carries
 *
 * - **`wall`** (default, the behavior every run before 2026-10-12 had): `ts = wireEpoch + (compressed offset)`,
 *   i.e. the day lands at the hour it is DRIVEN, squeezed to the compressed span.
 * - **`plan`**: `ts` is the sample's own planned `tsMs` — the store-local hour of `M8TRX_DAY_DATE` — so a day
 *   driven at night lands on the day's own hours once the edge honors the envelope time (core timestamp fix).
 *   Pacing is untouched (still wall-clock, still gap-only compression); only the stamp changes. Within an
 *   episode the stamps keep their real 200 ms spacing either way, so the oracle's prediction holds.
 *   A plan stamp in the future is refused unless `M8TRX_DAY_ALLOW_FUTURE=true`: the read windows are
 *   `[now-24h, now)`, so a future-dated session reads as missing until the clock catches up.
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
    val epochMode = DayEpochMode.parse(env("M8TRX_DAY_EPOCH_MODE", "wall"))
    val allowFuture = env("M8TRX_DAY_ALLOW_FUTURE", "false").toBoolean()

    require(compress >= 1.0) { "M8TRX_DAY_COMPRESS must be >= 1.0" }
    if (spaceId.startsWith(OFFICE_SPACE)) {
        log.error("REFUSING: M8TRX_SPACE_ID={} is the OFFICE space (real Xovis hardware).", spaceId)
        exitProcess(2)
    }

    // ── generate + oracle-check offline, BEFORE publishing anything ───────────
    log.info(
        "═══ DayDrive ═══  date={} seed={} scale={} compress={}x window={}..{} epoch={}",
        date,
        seed,
        scale,
        compress,
        fromHour,
        toHour,
        epochMode,
    )
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

    // ── PACING: compress each shopper's ARRIVAL, never their internal timing — see [buildDayTimeline] ──
    if (fromHour != null || toHour != null) {
        log.info("slicing to store hours {}..{} (sessions kept whole)", fromHour, toHour)
    }
    val timeline = buildDayTimeline(streams, compress, fromHour, toHour)
    if (timeline.isEmpty()) {
        log.error("no shoppers in the requested window — nothing to drive")
        exitProcess(1)
    }
    val sliced: Map<String, List<ImpressionOracle.Sample>> = timeline.groupBy({ it.objectId }, { it.s })

    val oracle = ImpressionOracle()
    val slicedByObj = sliced
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
    val firstPlanTs = timeline.first().s.tsMs
    val lastPlanTs = timeline.maxOf { it.s.tsMs }
    if (epochMode == DayEpochMode.PLAN) {
        log.info(
            "EPOCH plan: wire ts = the plan's own clock — first {} · last {} (now {})",
            java.time.Instant.ofEpochMilli(firstPlanTs),
            java.time.Instant.ofEpochMilli(lastPlanTs),
            java.time.Instant.now(),
        )
        // A stamp is "future" if it is ahead of the wall clock AT THE MOMENT IT IS PUBLISHED, not merely ahead of now:
        // a compress-1 slice that starts in the past stays behind the wall clock all the way through (the live
        // moving dots), while a compressed day of today outruns it within minutes.
        val planStart = timeline.first().ts
        val lead = timeline.maxOf { it.s.tsMs - (it.ts - planStart) } - System.currentTimeMillis()
        if (lead > FUTURE_SLACK_MS && !allowFuture) {
            log.error(
                "REFUSING: plan-mode stamps run up to {} and would be published up to {} ahead of the clock — in the " +
                    "future. Reads are windowed [now-24h, now), so these sessions would read as missing. Drive a past " +
                    "day/slice, or set M8TRX_DAY_ALLOW_FUTURE=true.",
                java.time.Instant.ofEpochMilli(lastPlanTs),
                fmt(lead),
            )
            exitProcess(4)
        }
        log.info(
            "  stamp lead over the publish clock: {} (must stay <= 5 min)",
            if (lead >
                0
            ) {
                fmt(lead)
            } else {
                "none — every stamp is in the past when sent"
            },
        )
    } else {
        log.info("EPOCH wall: wire ts = publish start + compressed offset (the pre-2026-10-12 behavior)")
    }

    if (!live) {
        log.info("DRY-RUN (M8TRX_DAY_LIVE!=true) — nothing published. Set M8TRX_DAY_LIVE=true to fire.")
        log.info(
            "SIM-RUN day date={} seed={} epoch={} shoppers={} samples={} expected_impressions={} published=0 plan_wall={}",
            date,
            seed,
            epochMode,
            slicedByObj.size,
            timeline.size,
            expectedInSlice,
            fmt(compressedMs),
        )
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
            ts = wireTs(epochMode, w, wireEpoch, planEpoch),
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

    // An eviction in plan mode is stamped one second after the shopper's last planned sample, so the session
    // closes on the day's own clock rather than at the moment the publisher happened to finish.
    val lastPlanByWire = timeline.groupBy { "$runTag-${it.objectId}" }.mapValues { (_, ws) -> ws.maxOf { it.s.tsMs } + 1_000 }
    seenObjects.forEach {
        val evictTs = if (epochMode == DayEpochMode.PLAN) lastPlanByWire.getValue(it) else System.currentTimeMillis()
        nats.objEviction(ObjEviction(objectId = it, layoutId = spaceId), ts = evictTs, id = "$it-evict")
    }
    log.info("published {} samples for {} shoppers in {}", published, seenObjects.size, fmt(System.currentTimeMillis() - t0))
    nats.close()
    log.info("★ expected {} impressions in this slice. Allow ~20s for the expireAfterWrite cache to flush.", expectedInSlice)
    log.info(
        "SIM-RUN day date={} seed={} epoch={} shoppers={} samples={} expected_impressions={} published={} wall={}",
        date,
        seed,
        epochMode,
        seenObjects.size,
        timeline.size,
        expectedInSlice,
        published,
        fmt(System.currentTimeMillis() - t0),
    )
    log.info("  objectId prefix for verification: {}", runTag)
}

/** Which clock the envelope `ts` carries — see the file note. */
enum class DayEpochMode {
    WALL,
    PLAN,
    ;

    companion object {
        fun parse(s: String): DayEpochMode = when (s.trim().lowercase()) {
            "wall" -> WALL
            "plan" -> PLAN
            else -> error("M8TRX_DAY_EPOCH_MODE must be wall|plan, got '$s'")
        }
    }
}

/** One sample on the compressed publish timeline: [ts] is when it is PUBLISHED, [s] carries its planned time. */
data class DayWire(val ts: Long, val objectId: String, val s: ImpressionOracle.Sample)

private const val FUTURE_SLACK_MS = 5 * 60_000L

/**
 * The envelope `ts` for [w]. `WALL`: the publish start plus the compressed offset. `PLAN`: the sample's own
 * planned time. [planEpoch] is the compressed timeline's first `ts`, which is also the first planned time.
 */
fun wireTs(mode: DayEpochMode, w: DayWire, wireEpoch: Long, planEpoch: Long): Long = when (mode) {
    DayEpochMode.WALL -> wireEpoch + (w.ts - planEpoch)
    DayEpochMode.PLAN -> w.s.tsMs
}

/**
 * The compressed publish timeline — compress each shopper's ARRIVAL, never their internal timing.
 *
 * The obvious implementation — walk the merged timeline and compress any gap that is not intra-episode — is
 * WRONG, and silently so. With ~790 overlapping shoppers, two consecutive samples on the merged timeline are
 * almost always DIFFERENT shoppers, so nearly every delta looks inter-episode and gets divided. That squeezes
 * a shopper's 200ms sample spacing to ~11ms and their 8.4s dwell to ~470ms — under `millisTillImpression`,
 * so the whole run yields zero impressions while looking completely healthy for 26 minutes.
 *
 * Instead: shift each shopper's START by dividing their offset from the slice start, and keep every sample
 * at its ORIGINAL offset within that shopper. Shoppers simply overlap more.
 *
 * ⚠ SLICE IN STORE TIME, BEFORE COMPRESSION. Filtering the re-based timeline instead is wrong: the compressed
 * day spans ~1h of wall time, so "hour 17" lands far past its end and the slice comes back EMPTY. A shopper is
 * kept whole if their session STARTS inside the window — clipping mid-session would cut episodes and
 * silently destroy the very dwell the run exists to produce.
 */
fun buildDayTimeline(
    streams: Map<String, List<ImpressionOracle.Sample>>,
    compress: Double,
    fromHour: Int? = null,
    toHour: Int? = null,
    openHour: Int = 10,
): List<DayWire> {
    if (streams.isEmpty()) return emptyList()
    val dayStart = streams.values.flatten().minOf { it.tsMs }
    val sliced = if (fromHour == null && toHour == null) {
        streams
    } else {
        val lo = dayStart + ((fromHour ?: openHour) - openHour) * 3_600_000L
        val hi = dayStart + ((toHour ?: 24) - openHour) * 3_600_000L
        streams.filterValues { it.minOf { s -> s.tsMs } in lo until hi }
    }
    if (sliced.isEmpty()) return emptyList()
    val sliceStart = sliced.values.flatten().minOf { it.tsMs }
    return sliced.flatMap { (id, samples) ->
        val ordered = samples.sortedBy { it.tsMs }
        val origStart = ordered.first().tsMs
        var t = sliceStart + ((origStart - sliceStart) / compress).toLong() // arrival: compressed
        val out = ArrayList<DayWire>(ordered.size)
        out.add(DayWire(t, id, ordered.first()))
        for (i in 1 until ordered.size) {
            val d = ordered[i].tsMs - ordered[i - 1].tsMs
            // ≤1000ms ⇒ INSIDE an episode ⇒ replay verbatim, or the clocks never accumulate. Anything larger is
            // idle — a walk to the next rack, or minutes standing in a zone that emits no samples — and is
            // exactly what the factor is for.
            t += if (d <= EPISODE_GAP_MS) d else (d / compress).toLong().coerceAtLeast(EPISODE_GAP_MS + 1)
            out.add(DayWire(t, id, ordered[i]))
        }
        out
    }.sortedBy { it.ts }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%dh%02dm%02ds".format(s / 3600, (s % 3600) / 60, s % 60)
}

private fun env(k: String, d: String) = System.getenv(k)?.takeIf { it.isNotBlank() } ?: d
private fun req(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() } ?: error("Required env var $k is not set")
