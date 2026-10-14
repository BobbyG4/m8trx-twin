package com.m8trx.twin.layer3

import com.m8trx.twin.TwinConfig
import com.m8trx.twin.connect.DatedStream
import com.m8trx.twin.domain.ObjEviction
import com.m8trx.twin.domain.ObjLocation
import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.layer0.NatsEmitter
import com.m8trx.twin.layer0.objEviction
import com.m8trx.twin.layer0.objLocation
import com.m8trx.twin.layer1.FixtureSet
import com.m8trx.twin.layer1.ImpressionOracle
import io.nats.client.Nats
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The people plane LIVE — `M8TRX_PEOPLE_MODE=live` on `connectPeopleDrive` (and the `m8trx-twin-live.service` on the box).
 *
 * Bob, 2026-10-14: "we can look at LIVE, and then we can speed it up." The batch day drive ([DayDrive]) replays a whole day
 * in minutes with the wire `ts` anchored to the PLAN, so the live floor never shows anyone walking. This mode is its
 * deliberate opposite (twin STATUS § pacing rule, the live exception):
 *
 *  - **Each store on its own local day.** A store's plan is the same per-date weekday plan the day drive uses — seed
 *    `M8TRX_LIVE_SEED + day-of-year`, the weekday table's shape — generated for the date the store's shifted local clock
 *    reads, in the store's own time zone. A day that ends rolls into the next one (generated in the background).
 *  - **Wall-clock pace × speed.** `M8TRX_LIVE_SPEED` ∈ {1, 2, 5, 10} plan seconds per wall second. Under the default
 *    `M8TRX_LIVE_DWELL=scaled` both a shopper's ARRIVAL and their internal timing run × speed (Bob's "at 5× they walk five
 *    times faster"). `real` keeps every in-episode delta (≤ 1 s) at real time and divides only arrivals and the gaps
 *    between episodes — the batch rule — so impressions still fire above 1× (see the dry run's impression line: under
 *    `scaled` a dwell of 8 s at 2× is 4 s on the wire, under the edge's 5 000 ms `millisTillImpression`).
 *  - **Camera cadence.** Positions go out at the plan's own 5 Hz per shopper on the wall clock: under `scaled` every
 *    speed-th sample is published, so the twin edge sees the same per-shopper rate at any speed.
 *  - **Wire `ts` = the shifted wall clock** — the scheduled publish time of the sample (never `currentTimeMillis()` at
 *    send, so publisher jitter cannot split an episode; the lesson in [DayDrive]). Sessions land at "now".
 *  - **Plan time on the wire.** The envelope (`type, ts, id, areaId, body`) has no metadata field and `objLocation`'s body
 *    is the camera's, so `metadata.plan_time` cannot ride the wire. The plan time rides the OBJECT ID instead:
 *    `live-<slug>-<HHmm plan-local arrival>-cust-<plan date>-<n>`.
 *  - **`M8TRX_LIVE_SHIFT`** — `open` (default: each store's 10:00 local today is played at now), `at:HH:mm` (that local
 *    time at now), or a number of hours added to the store's local clock (`0` = the store's real local time).
 *  - **Scale.** `M8TRX_LIVE_SCALE` (else `M8TRX_DAY_SCALE`) is HARD-CAPPED at [SCALE_CAP] = 0.6: a value above it is
 *    refused. Unset, the scale is the weekday table scaled so Saturday = the cap (weekday 0.48, Mon 0.41, Tue 0.43, Fri 0.53,
 *    Sat 0.60, Sun 0.55) — a weekend-scale replay starved the main-server for two hours on 2026-10-12.
 *  - **The aggregate cap.** `M8TRX_LIVE_MAX_EVENTS_PER_S` (default [DEFAULT_MAX_EVENTS_PER_S]) on objLocation messages
 *    to the twin edge and `M8TRX_LIVE_MAX_ARRIVALS_PER_MIN` (default [DEFAULT_MAX_ARRIVALS_PER_MIN]) on shoppers — each
 *    arrival is about 7 platform writes (a session begin + close + ~5 impressions). Both are measured on the merged
 *    timeline of every PUBLISHING store; when either peak is over its cap, every store's arrivals are thinned by the same
 *    keep fraction (a deterministic hash of the object id), so the load drops evenly across stores.
 *  - **Edges.** A store publishes only where a twin edge is bound to ITS sales-floor space ([KNOWN_EDGES], overridable
 *    with `M8TRX_LIVE_EDGES=slug=url|server_name|space_id;…`). Today that is Denver alone (`edge-twin-denver`, one space);
 *    every other store is planned and reported, never published — publishing Paris's shoppers into Denver's edge would
 *    put them on Denver's floor. The two interlocks of [DayDrive] hold per edge: `server_name` asserted before the first
 *    event, the office space refused.
 *  - **Stop / hold.** SIGTERM (systemd stop) or the HOLD file (`M8TRX_LIVE_HOLD_FILE`, polled every 5 s) ends the run:
 *    every shopper on the floor is evicted (sessions close at their last position) and the plan clock is saved to
 *    `M8TRX_LIVE_STATE`, so `M8TRX_LIVE_CONTINUE=true` (the `live speed N` restart) resumes the same plan time at the new speed.
 *
 * DRY-RUN unless `M8TRX_PEOPLE_LIVE=true`: plans every requested store, prints the per-store rates, the cap arithmetic and
 * the first `M8TRX_LIVE_DRY_SECONDS` (60) seconds of events for `M8TRX_LIVE_DRY_PRINT` (dec-us-denver,dec-fr-paris), and
 * writes the full window to `out/live-dry/`. Nothing is published, no connection is opened.
 */
object LiveDrive {
    private val log = LoggerFactory.getLogger("com.m8trx.twin.layer3.LiveDrive")

    /** Hard cap on the live day scale — the measured safe envelope for a store day through the twin edge (2026-10-12). */
    const val SCALE_CAP = 0.6

    val SPEEDS = listOf(1, 2, 5, 10)

    /**
     * objLocation messages per second, all publishing stores together. 500 is below the ~570/s a weekday batch replay
     * pushed through the twin edge cleanly on Oct 5-8 (1.36 M samples in ~40 min) and half the ~990/s of the Saturday
     * replay that starved the main-server (2.85 M in 48 min). At 1x Denver's peak is ~40/s, so the cap binds only when
     * several stores share edges at high speed.
     */
    const val DEFAULT_MAX_EVENTS_PER_S = 500

    /**
     * Shoppers per minute, all publishing stores together. Each arrival costs ~7.2 platform writes (session begin + close,
     * ~5.2 impressions: 8,757 predicted / 1,684 shoppers on the Oct 11 plan). 40/min = ~290 writes/min: under half the
     * twin-edge key's 600/min Connect bucket and under half the 660/min the Phase-0 people lane was measured clean at
     * (p95 65 ms), so a peak minute never draws a 429.
     */
    const val DEFAULT_MAX_ARRIVALS_PER_MIN = 40

    /** The plan's per-shopper emit period (5 Hz, BrowseEpisode's default) — the cadence kept on the wall at any speed. */
    const val CAMERA_PERIOD_MS = 200L

    /** Gaps at or below this are inside an episode (the batch rule's constant). */
    const val EPISODE_GAP_MS = 1_000L

    const val HISTORY_SEED = 20261016L

    private const val OFFICE_SPACE = "0efb9aaa"

    data class Edge(val url: String, val serverName: String, val spaceId: String)

    /** Twin edges and the ONE space each is bound to (its `impressionEvaluator.spaceId`). */
    val KNOWN_EDGES: Map<String, Edge> = mapOf(
        "dec-us-denver" to Edge("nats://192.168.55.29:4223", "edge-twin-denver", "e3c9a424-3ced-5288-9756-19935d39f88f"),
    )

    enum class Dwell { SCALED, REAL }

    sealed interface Shift {
        data object Open : Shift

        data class At(val time: LocalTime) : Shift

        data class Hours(val hours: Double) : Shift

        companion object {
            fun parse(s: String?): Shift {
                val v = s?.trim()?.lowercase().orEmpty()
                return when {
                    v.isEmpty() || v == "open" -> Open
                    v.startsWith("at:") -> At(LocalTime.parse(v.removePrefix("at:")))
                    else -> Hours(v.toDoubleOrNull() ?: error("M8TRX_LIVE_SHIFT must be open | at:HH:mm | <hours>, got '$s'"))
                }
            }
        }
    }

    /** The plan instant a store's clock reads at wall time [now]. */
    fun planAnchor(now: Instant, zone: ZoneId, shift: Shift, open: LocalTime = LocalTime.of(10, 0)): Instant = when (shift) {
        Shift.Open -> ZonedDateTime.of(now.atZone(zone).toLocalDate(), open, zone).toInstant()
        is Shift.At -> ZonedDateTime.of(now.atZone(zone).toLocalDate(), shift.time, zone).toInstant()
        is Shift.Hours -> now.plusMillis((shift.hours * 3_600_000).toLong())
    }

    /** day.sh's weekday table (Sat 1.25 · Sun 1.15 · Mon 0.85 · Tue 0.9 · Fri 1.1 · else 1.0). */
    fun weekdayTable(date: LocalDate): Double = when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> 1.25
        DayOfWeek.SUNDAY -> 1.15
        DayOfWeek.MONDAY -> 0.85
        DayOfWeek.TUESDAY -> 0.9
        DayOfWeek.FRIDAY -> 1.1
        else -> 1.0
    }

    /** The live scale: an explicit value (refused above the cap), else the weekday table scaled so Saturday = the cap. */
    fun liveScale(date: LocalDate, override: Double?): Double {
        if (override != null) {
            require(override > 0 && override <= SCALE_CAP) { "live scale $override is above the hard cap $SCALE_CAP (or not positive) — refused" }
            return override
        }
        return SCALE_CAP * weekdayTable(date) / 1.25
    }

    /** day.sh's per-date seed: SEED + day-of-year. */
    fun daySeed(base: Long, date: LocalDate): Long = base + date.dayOfYear

    /** A store's plan clock: plan time runs [speed]× the wall clock from ([wallAnchor], [planAnchor]). */
    data class Clock(val wallAnchor: Long, val planAnchor: Long, val speed: Int) {
        fun wallOf(planMs: Long): Long = wallAnchor + Math.floorDiv(planMs - planAnchor, speed.toLong())

        fun planOf(wallMs: Long): Long = planAnchor + (wallMs - wallAnchor) * speed
    }

    /** One message on the wall timeline: an objLocation ([sample] set) or the shopper's eviction ([sample] null). */
    data class Wire(val dueMs: Long, val objectId: String, val sample: ImpressionOracle.Sample?, val seq: Int)

    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")

    fun objectId(slug: String, zone: ZoneId, customer: String, firstPlanMs: Long): String =
        "live-$slug-${Instant.ofEpochMilli(firstPlanMs).atZone(zone).format(HHMM)}-$customer"

    /** Deterministic in the object id: the same shopper is kept or thinned on every run and every machine. */
    fun keepShopper(objectId: String, keep: Double): Boolean {
        if (keep >= 1.0) return true
        val h = MessageDigest.getInstance("SHA-256").digest(objectId.toByteArray())
        val u = ((h[0].toLong() and 0xff) shl 16 or ((h[1].toLong() and 0xff) shl 8) or (h[2].toLong() and 0xff)).toDouble() / 16_777_216.0
        return u < keep
    }

    /**
     * The wall timeline of one store-day. Samples planned before [notBeforePlanMs] are dropped: a shopper who left before it is
     * left out, one still inside walks on from there. [streams] = customer → samples in plan time, as [ScenarioRun.streamsFor].
     */
    fun storeDayWires(
        slug: String,
        zone: ZoneId,
        streams: Map<String, List<ImpressionOracle.Sample>>,
        clock: Clock,
        dwell: Dwell,
        notBeforePlanMs: Long = Long.MIN_VALUE,
    ): List<Wire> {
        val speed = clock.speed
        val out = ArrayList<Wire>()
        for ((customer, raw) in streams) {
            val all = raw.sortedBy { it.tsMs }
            if (all.isEmpty()) continue
            // a shopper already inside when the clock starts (a mid-day shift or a resumed clock) walks on from where the plan
            // has them at that moment, so the floor is populated from the first second; their id keeps the planned arrival
            val ordered = if (all.first().tsMs >= notBeforePlanMs) all else all.filter { it.tsMs >= notBeforePlanMs }
            if (ordered.isEmpty()) continue
            val id = objectId(slug, zone, customer, all.first().tsMs)
            var seq = 0
            when (dwell) {
                Dwell.SCALED -> {
                    // the camera's cadence on the WALL clock at any speed: keep a sample once speed x 200 ms of plan time has passed
                    // since the last kept one (an episode's first sample always qualifies — it follows a gap)
                    var last = Long.MIN_VALUE
                    for (s in ordered) {
                        if (last != Long.MIN_VALUE && s.tsMs - last < CAMERA_PERIOD_MS * speed) continue
                        last = s.tsMs
                        out += Wire(clock.wallOf(s.tsMs), id, s, seq++)
                    }
                    out += Wire(clock.wallOf(ordered.last().tsMs + EPISODE_GAP_MS), id, null, seq)
                }

                Dwell.REAL -> {
                    var t = clock.wallOf(ordered.first().tsMs)
                    out += Wire(t, id, ordered.first(), seq++)
                    for (i in 1 until ordered.size) {
                        val d = ordered[i].tsMs - ordered[i - 1].tsMs
                        t += if (d <= EPISODE_GAP_MS) d else (d / speed).coerceAtLeast(EPISODE_GAP_MS + 1)
                        out += Wire(t, id, ordered[i], seq++)
                    }
                    out += Wire(t + EPISODE_GAP_MS, id, null, seq)
                }
            }
        }
        out.sortWith(compareBy<Wire> { it.dueMs }.thenBy { it.objectId }.thenBy { it.seq })
        return out
    }

    /** Messages per 10-s bucket and arrivals per 60-s bucket, keyed by the bucket's start (wall ms). */
    data class Load(val events10s: Map<Long, Int>, val arrivals60s: Map<Long, Int>) {
        val peakEventsPerS: Double get() = (events10s.values.maxOrNull() ?: 0) / 10.0
        val peakArrivalsPerMin: Int get() = arrivals60s.values.maxOrNull() ?: 0

        operator fun plus(o: Load) = Load(merge(events10s, o.events10s), merge(arrivals60s, o.arrivals60s))

        companion object {
            val EMPTY = Load(emptyMap(), emptyMap())

            private fun merge(a: Map<Long, Int>, b: Map<Long, Int>): Map<Long, Int> = HashMap(a).also { m ->
                b.forEach { (k, v) -> m.merge(k, v, Int::plus) }
            }

            fun of(wires: List<Wire>): Load {
                val e = HashMap<Long, Int>()
                val a = HashMap<Long, Int>()
                wires.forEach { w ->
                    if (w.sample != null) e.merge(Math.floorDiv(w.dueMs, 10_000L) * 10_000L, 1, Int::plus)
                    if (w.seq == 0) a.merge(Math.floorDiv(w.dueMs, 60_000L) * 60_000L, 1, Int::plus)
                }
                return Load(e, a)
            }
        }
    }

    /** The one keep fraction every store's arrivals are thinned by, so the merged peak stays under both caps. */
    fun keepFraction(load: Load, maxEventsPerS: Double, maxArrivalsPerMin: Double): Double {
        val e = if (load.peakEventsPerS > maxEventsPerS) maxEventsPerS / load.peakEventsPerS else 1.0
        val a = if (load.peakArrivalsPerMin > maxArrivalsPerMin) maxArrivalsPerMin / load.peakArrivalsPerMin else 1.0
        return minOf(e, a, 1.0)
    }

    fun thin(wires: List<Wire>, keep: Double): List<Wire> = if (keep >= 1.0) wires else wires.filter { keepShopper(it.objectId, keep) }

    // ── settings ─────────────────────────────────────────────────────────────────────────────────────────────────────

    data class Settings(
        val sites: List<String>,
        val speed: Int,
        val shift: Shift,
        val scaleOverride: Double?,
        val dwell: Dwell,
        val maxEventsPerS: Double,
        val maxArrivalsPerMin: Double,
        val seedBase: Long,
        val live: Boolean,
        val holdFile: Path?,
        val stateFile: Path,
        val continueState: Boolean,
        val drySeconds: Int,
        val dryPrint: List<String>,
        val tenantId: String,
        val edges: Map<String, Edge>,
        val chainDir: Path,
    ) {
        fun describe() = "sites=${sites.size} speed=${speed}x dwell=${dwell.name.lowercase()} shift=${shiftText()} " +
            "scale=${scaleOverride ?: "weekday-table x ${SCALE_CAP}/1.25"} cap=${maxEventsPerS.toInt()}ev/s,${maxArrivalsPerMin.toInt()}arr/min"

        fun shiftText() = when (shift) {
            Shift.Open -> "open(10:00 local at now)"
            is Shift.At -> "at:${shift.time}"
            is Shift.Hours -> "${shift.hours}h"
        }

        companion object {
            fun fromEnv(keys: ChainKeys): Settings {
                fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
                val speed = env("M8TRX_LIVE_SPEED")?.toIntOrNull() ?: 1
                require(speed in SPEEDS) { "M8TRX_LIVE_SPEED must be one of $SPEEDS, got ${env("M8TRX_LIVE_SPEED")}" }
                val all = keys.stores.map { it.slug }
                val sites = env("M8TRX_LIVE_SITES")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.let { s ->
                    if (s == listOf("all")) all else s
                } ?: all
                sites.forEach { require(it in all) { "unknown site '$it' in M8TRX_LIVE_SITES (known: $all)" } }
                val scale = (env("M8TRX_LIVE_SCALE") ?: env("M8TRX_DAY_SCALE"))?.toDouble()
                if (scale != null) liveScale(LocalDate.now(), scale) // refuse above the cap before anything is generated
                val ops = env("M8TRX_OPS_DIR") ?: "/opt/m8trx/twin-ops"
                return Settings(
                    sites = sites,
                    speed = speed,
                    shift = Shift.parse(env("M8TRX_LIVE_SHIFT")),
                    scaleOverride = scale,
                    dwell = when (env("M8TRX_LIVE_DWELL")?.lowercase() ?: "scaled") {
                        "scaled" -> Dwell.SCALED
                        "real" -> Dwell.REAL
                        else -> error("M8TRX_LIVE_DWELL must be scaled | real")
                    },
                    maxEventsPerS = env("M8TRX_LIVE_MAX_EVENTS_PER_S")?.toDouble() ?: DEFAULT_MAX_EVENTS_PER_S.toDouble(),
                    maxArrivalsPerMin = env("M8TRX_LIVE_MAX_ARRIVALS_PER_MIN")?.toDouble() ?: DEFAULT_MAX_ARRIVALS_PER_MIN.toDouble(),
                    seedBase = env("M8TRX_LIVE_SEED")?.toLong() ?: HISTORY_SEED,
                    live = env("M8TRX_PEOPLE_LIVE")?.toBoolean() == true,
                    holdFile = (env("M8TRX_LIVE_HOLD_FILE") ?: "$ops/HOLD").let(Path::of),
                    stateFile = Path.of(env("M8TRX_LIVE_STATE") ?: ".twin-state/live/clock.properties"),
                    continueState = env("M8TRX_LIVE_CONTINUE")?.toBoolean() == true,
                    drySeconds = env("M8TRX_LIVE_DRY_SECONDS")?.toInt() ?: 60,
                    dryPrint = (env("M8TRX_LIVE_DRY_PRINT") ?: "dec-us-denver,dec-fr-paris").split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    tenantId = env("M8TRX_TENANT_ID") ?: "unset-dry-run",
                    edges = env("M8TRX_LIVE_EDGES")?.let(::parseEdges) ?: KNOWN_EDGES,
                    chainDir = Path.of(env("M8TRX_CHAIN_DIR") ?: "reference/data/chain"),
                )
            }

            fun parseEdges(s: String): Map<String, Edge> = s.split(";").map { it.trim() }.filter { it.isNotEmpty() }.associate { e ->
                val (slug, rest) = e.split("=", limit = 2)
                val p = rest.split("|")
                require(p.size == 3) { "M8TRX_LIVE_EDGES entry must be slug=url|server_name|space_id, got '$e'" }
                slug.trim() to Edge(p[0].trim(), p[1].trim(), p[2].trim())
            }
        }
    }

    // ── one store's plan ────────────────────────────────────────────────────────────────────────────────────────────

    data class StorePlan(
        val slug: String,
        val zone: ZoneId,
        val siteId: String,
        val spaceId: String,
        val edge: Edge?,
        val edgeProblem: String?,
        val clock: Clock,
        val date: LocalDate,
        val seed: Long,
        val scale: Double,
        val wires: List<Wire>,
    ) {
        val publishes: Boolean get() = edge != null && edgeProblem == null
        val shoppers: Int get() = wires.count { it.seq == 0 }
        val samples: Int get() = wires.count { it.sample != null }
    }

    /** Why [slug] may not publish through [edges] (null = it may). One edge = one space: the edge must be bound to THIS store's floor. */
    fun edgeProblem(edges: Map<String, Edge>, slug: String, spaceId: String): String? {
        val e = edges[slug] ?: return "no twin edge is bound to this store's space (one edge = one space; F6)"
        if (spaceId.startsWith(OFFICE_SPACE) || e.spaceId.startsWith(OFFICE_SPACE)) return "the OFFICE space — refused"
        if (e.spaceId != spaceId) return "edge '${e.serverName}' is bound to space ${e.spaceId}, not this store's $spaceId — refused"
        return null
    }

    private fun edgeFor(s: Settings, slug: String, spaceId: String): Pair<Edge?, String?> = s.edges[slug] to edgeProblem(s.edges, slug, spaceId)

    /** Generate one store-day on the store's clock — the same generator and per-date seed as the day drive. */
    fun planDay(s: Settings, keys: ChainKeys, siteIds: Map<String, String>, slug: String, clock: Clock, date: LocalDate, notBefore: Long): StorePlan {
        val store = keys.store(slug)
        val seed = daySeed(s.seedBase, date)
        val scale = liveScale(date, s.scaleOverride)
        val streams = ScenarioRun(storeCode = slug, chainDir = s.chainDir).streamsFor(date, seed, scale, store.zone)
        val (edge, problem) = edgeFor(s, slug, store.salesFloorSpaceId)
        return StorePlan(
            slug, store.zone, siteIds[slug] ?: error("no site id for $slug"), store.salesFloorSpaceId, edge, problem,
            clock, date, seed, scale, storeDayWires(slug, store.zone, streams, clock, s.dwell, notBefore),
        )
    }

    /** The store-local date the clock is in at [wallMs] — the plan date. */
    fun planDate(clock: Clock, zone: ZoneId, wallMs: Long): LocalDate = Instant.ofEpochMilli(clock.planOf(wallMs)).atZone(zone).toLocalDate()

    private fun loadState(s: Settings, now: Long): Map<String, Long> {
        if (!s.continueState || !Files.exists(s.stateFile)) return emptyMap()
        val p = java.util.Properties().also { pr -> Files.newBufferedReader(s.stateFile).use { pr.load(it) } }
        val wall = p.getProperty("wall")?.toLongOrNull() ?: return emptyMap()
        if (now - wall > 30 * 60_000L) {
            log.warn("live state {} is {} min old — starting the clocks fresh", s.stateFile, (now - wall) / 60_000)
            return emptyMap()
        }
        return p.stringPropertyNames().filter { it != "wall" }.associateWith { p.getProperty(it).toLong() }
    }

    private fun saveState(s: Settings, now: Long, plans: Collection<StorePlan>) {
        Files.createDirectories(s.stateFile.toAbsolutePath().parent)
        val p = java.util.Properties()
        p.setProperty("wall", now.toString())
        plans.forEach { p.setProperty(it.slug, it.clock.planOf(now).toString()) }
        val tmp = s.stateFile.resolveSibling(s.stateFile.fileName.toString() + ".tmp")
        Files.newBufferedWriter(tmp).use { p.store(it, "m8trx-twin live plan clocks (plan ms per store at wall ms)") }
        Files.move(tmp, s.stateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun impressionsPredicted(chainDir: Path, plan: StorePlan): Int {
        val fixtures = FixtureSet.load(chainDir.resolve("stores/${plan.slug}/layout.json"))
        val oracle = ImpressionOracle()
        return plan.wires.filter { it.sample != null }.groupBy { it.objectId }.values.sumOf { ws ->
            oracle.run(fixtures, ws.map { it.sample!!.copy(tsMs = it.dueMs) }).size
        }
    }

    private fun fmtMs(ms: Long): String {
        val s = ms / 1000
        return "%dh%02dm%02ds".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    // ── entry ───────────────────────────────────────────────────────────────────────────────────────────────────────

    fun main() {
        val keys = ChainKeys.load(Path.of(System.getenv("M8TRX_CHAIN_DIR") ?: "reference/data/chain").resolve(ChainKeys.DEFAULT_PATH.fileName))
        // a bad setting (speed, scale above the cap, shift, sites) is a refusal, not a crash: exit 3, which the unit never restarts
        val s = try {
            Settings.fromEnv(keys)
        } catch (e: IllegalArgumentException) {
            log.error("REFUSING: {}", e.message)
            exitProcess(3)
        } catch (e: IllegalStateException) {
            log.error("REFUSING: {}", e.message)
            exitProcess(3)
        }
        val siteIds = DatedStream.readSiteIds(s.chainDir.resolve("site_ids.csv"))
        val now = System.currentTimeMillis()
        val saved = loadState(s, now)
        log.info("═══ LiveDrive ═══ {} live={}", s.describe(), s.live)
        val plans = s.sites.map { slug ->
            val zone = keys.store(slug).zone
            val planAt = saved[slug] ?: planAnchor(Instant.ofEpochMilli(now), zone, s.shift).toEpochMilli()
            val clock = Clock(now, planAt, s.speed)
            val date = planDate(clock, zone, now)
            if (s.live && edgeFor(s, slug, keys.store(slug).salesFloorSpaceId).second != null) {
                // a live run does not generate a store it cannot publish (memory, start-up time); the dry run plans every one
                val problem = edgeFor(s, slug, keys.store(slug).salesFloorSpaceId).second
                log.info("  {} NOT PUBLISHED — {}", slug, problem)
                return@map StorePlan(
                    slug, zone, siteIds[slug] ?: "", keys.store(slug).salesFloorSpaceId, null, problem, clock, date,
                    daySeed(s.seedBase, date), liveScale(date, s.scaleOverride), emptyList(),
                )
            }
            planDay(s, keys, siteIds, slug, clock, date, notBefore = planAt).also {
                log.info(
                    "  {} plan {} {} seed={} scale={} shoppers={} samples={} peak={}/s {}/min · plan clock now {} · {}",
                    slug,
                    it.date,
                    it.date.dayOfWeek.name.take(3),
                    it.seed,
                    "%.2f".format(it.scale),
                    it.shoppers,
                    it.samples,
                    "%.1f".format(Load.of(it.wires).peakEventsPerS),
                    Load.of(it.wires).peakArrivalsPerMin,
                    Instant.ofEpochMilli(it.clock.planOf(now)).atZone(zone).toLocalDateTime(),
                    if (it.publishes) "PUBLISHES via ${it.edge!!.serverName}" else "NOT PUBLISHED — ${it.edgeProblem}",
                )
            }
        }
        val pub = plans.filter { it.publishes }
        val pubLoad = pub.fold(Load.EMPTY) { acc, p -> acc + Load.of(p.wires) }
        val allLoad = plans.fold(Load.EMPTY) { acc, p -> acc + Load.of(p.wires) }
        val keep = keepFraction(pubLoad, s.maxEventsPerS, s.maxArrivalsPerMin)
        log.info(
            "LOAD publishing stores ({}): peak {} ev/s, {} arrivals/min → keep {} (caps {} ev/s, {} arr/min)",
            pub.size,
            "%.1f".format(pubLoad.peakEventsPerS),
            pubLoad.peakArrivalsPerMin,
            "%.3f".format(keep),
            s.maxEventsPerS.toInt(),
            s.maxArrivalsPerMin.toInt(),
        )
        log.info(
            "LOAD if all {} requested stores published: peak {} ev/s, {} arrivals/min → keep would be {}",
            plans.size,
            "%.1f".format(allLoad.peakEventsPerS),
            allLoad.peakArrivalsPerMin,
            "%.3f".format(keepFraction(allLoad, s.maxEventsPerS, s.maxArrivalsPerMin)),
        )
        val noEdge = plans.filter { !it.publishes }.map { it.slug }
        log.info(
            "SIM-RUN live plan sites={} publishing={} not_published={} speed={}x dwell={} shift={} keep={}",
            s.sites.size,
            pub.joinToString(",") { it.slug }.ifEmpty { "none" },
            noEdge.joinToString(",").ifEmpty { "none" },
            s.speed,
            s.dwell.name.lowercase(),
            s.shiftText(),
            "%.3f".format(keep),
        )

        if (!s.live) {
            dryRun(s, plans, keep, now)
            return
        }
        if (pub.isEmpty()) {
            log.error("REFUSING: none of the requested stores has a twin edge bound to its space — nothing can be published")
            exitProcess(5)
        }
        if (s.holdFile != null && Files.exists(s.holdFile)) {
            log.error("REFUSING: HOLD is set ({}) — the box publishes nothing until resume", s.holdFile)
            exitProcess(6)
        }
        // NatsEmitter logs every publish at DEBUG (logback.xml has com.m8trx.twin at DEBUG): ~50 lines a second all day in
        // twin-ops/log/live.log. The live run keeps its own per-minute rate line instead.
        (LoggerFactory.getLogger("com.m8trx.twin.layer0") as? ch.qos.logback.classic.Logger)?.level = ch.qos.logback.classic.Level.INFO
        Publisher(s, keys, siteIds, pub.map { it.copy(wires = thin(it.wires, keep)) }, keep).run()
    }

    private fun dryRun(s: Settings, plans: List<StorePlan>, keep: Double, now: Long) {
        log.info("DRY-RUN (M8TRX_PEOPLE_LIVE!=true) — nothing published, no connection opened.")
        val outDir = Path.of("out/live-dry")
        Files.createDirectories(outDir)
        for (p in plans) {
            val oneHour = p.wires.filter { it.dueMs < now + 3_600_000L }
            val imp = impressionsPredicted(s.chainDir, p.copy(wires = oneHour))
            val shoppersHr = oneHour.count { it.seq == 0 }
            log.info(
                "  {} first wall hour at {}x: {} shoppers · {} samples · {} predicted impressions on the wire clock (dwell={}) · " +
                    "~{} platform writes/min",
                p.slug,
                s.speed,
                shoppersHr,
                oneHour.count { it.sample != null },
                imp,
                s.dwell.name.lowercase(),
                "%.1f".format((shoppersHr * 2 + imp) / 60.0),
            )
        }
        for (slug in s.dryPrint) {
            val p = plans.firstOrNull { it.slug == slug } ?: continue
            val window = thin(p.wires, if (p.publishes) keep else 1.0).filter { it.dueMs < now + s.drySeconds * 1000L }
            val file = outDir.resolve("$slug-first${s.drySeconds}s-${s.speed}x.tsv")
            Files.write(
                file,
                listOf("wall_offset_ms\twire_ts\tobject_id\ttype\tx_mm\ty_mm\tview_dir\tplan_local") +
                    window.map { w -> line(w, now, p.zone, p.clock) },
            )
            log.info(
                "── {} · first {} s at {}x · {} events ({} shoppers) · {} ──",
                slug,
                s.drySeconds,
                s.speed,
                window.size,
                window.count { it.seq == 0 },
                if (p.publishes) "would publish via ${p.edge!!.serverName}" else "would NOT publish (${p.edgeProblem})",
            )
            window.take(30).forEach { log.info("  {}", line(it, now, p.zone, p.clock)) }
            if (window.size > 30) log.info("  … {} more in {}", window.size - 30, file)
        }
    }

    private fun line(w: Wire, now: Long, zone: ZoneId, clock: Clock): String {
        val s = w.sample
        val plan = Instant.ofEpochMilli(clock.planOf(w.dueMs)).atZone(zone).toLocalTime()
        return if (s == null) {
            "+${w.dueMs - now}ms\t${Instant.ofEpochMilli(w.dueMs)}\t${w.objectId}\tobjEviction\t\t\t\t$plan"
        } else {
            "+${w.dueMs - now}ms\t${Instant.ofEpochMilli(w.dueMs)}\t${w.objectId}\tobjLocation\t${s.p.x.toLong()}\t${s.p.y.toLong()}\t" +
                "${s.viewDir?.let { "%.2f,%.2f".format(it.x, it.y) } ?: "-"}\t$plan"
        }
    }

    // ── the publisher ───────────────────────────────────────────────────────────────────────────────────────────────

    private class Publisher(
        private val s: Settings,
        private val keys: ChainKeys,
        private val siteIds: Map<String, String>,
        initial: List<StorePlan>,
        private val keep: Double,
    ) {
        private inner class Cursor(var plan: StorePlan, val emitter: NatsEmitter) {
            var idx = 0
            var next: Future<StorePlan>? = null
            val due: Long get() = plan.wires.getOrNull(idx)?.dueMs ?: Long.MAX_VALUE
        }

        private val gen = Executors.newSingleThreadExecutor { r -> Thread(r, "live-gen").also { it.isDaemon = true } }
        private val cursors: List<Cursor>
        private val active = LinkedHashMap<String, Cursor>()

        @Volatile private var stopping = false
        private val done = CountDownLatch(1)

        init {
            // interlock: every distinct edge proves its identity BEFORE a single event goes out
            initial.map { it.edge!! }.distinct().forEach { e ->
                val probe = Nats.connect(e.url)
                val name = probe.serverInfo?.serverName
                probe.close()
                if (name != e.serverName) {
                    log.error(
                        "REFUSING TO PUBLISH: {} answers as server_name='{}', expected '{}'. :4222 is the PRODUCTION office edge.",
                        e.url,
                        name,
                        e.serverName,
                    )
                    exitProcess(3)
                }
                log.info("✓ interlock: {} is '{}'", e.url, name)
            }
            cursors = initial.map { p ->
                val cfg =
                    TwinConfig(
                        natsUrl = p.edge!!.url,
                        restBaseUrl = "",
                        serviceBearer = "",
                        tenantId = s.tenantId,
                        siteId = p.siteId,
                        spaceId = p.spaceId,
                    )
                Cursor(p, NatsEmitter(cfg)).also { c -> c.next = prefetch(c.plan) }
            }
        }

        private fun prefetch(p: StorePlan): Future<StorePlan> = gen.submit<StorePlan> {
            planDay(s, keys, siteIds, p.slug, p.clock, p.date.plusDays(1), Long.MIN_VALUE).let { it.copy(wires = thin(it.wires, keep)) }
        }

        fun run() {
            Runtime.getRuntime().addShutdownHook(
                Thread {
                    stopping = true
                    done.await(20, TimeUnit.SECONDS)
                },
            )
            val t0 = System.currentTimeMillis()
            var published = 0L
            var minuteEvents = 0
            var minuteArrivals = 0
            var worstLag = 0L
            var lastMinute = t0
            var lastHoldCheck = 0L
            var lastSave = t0
            var reason = "stopped"
            try {
                while (!stopping) {
                    val c = cursors.minByOrNull { it.due } ?: break
                    if (c.due == Long.MAX_VALUE) {
                        // this store's day is over: switch to the prefetched next day and prefetch the one after
                        val nextPlan = c.next!!.get()
                        log.info("  {} day {} done → {} ({} shoppers)", c.plan.slug, c.plan.date, nextPlan.date, nextPlan.shoppers)
                        c.plan = nextPlan
                        c.idx = 0
                        c.next = prefetch(nextPlan)
                        continue
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastHoldCheck >= 5_000) {
                        lastHoldCheck = now
                        if (s.holdFile != null && Files.exists(s.holdFile)) {
                            reason = "HOLD"
                            log.warn("HOLD set ({}) — stopping", s.holdFile)
                            break
                        }
                    }
                    if (now - lastSave >= 30_000) {
                        lastSave = now
                        saveState(s, now, cursors.map { it.plan })
                    }
                    if (now - lastMinute >= 60_000) {
                        log.info(
                            "LIVE minute: {} events ({}/s) · {} arrivals · {} on the floor · worst lag {} ms · total {}",
                            minuteEvents,
                            "%.1f".format(minuteEvents / ((now - lastMinute) / 1000.0)),
                            minuteArrivals,
                            active.size,
                            worstLag,
                            published,
                        )
                        minuteEvents = 0
                        minuteArrivals = 0
                        worstLag = 0
                        lastMinute = now
                    }
                    val wait = c.due - now
                    if (wait > 0) {
                        Thread.sleep(minOf(wait, 1_000L))
                        continue
                    }
                    worstLag = maxOf(worstLag, -wait)
                    val w = c.plan.wires[c.idx++]
                    val sample = w.sample
                    if (sample == null) {
                        c.emitter.objEviction(ObjEviction(objectId = w.objectId, layoutId = c.plan.spaceId), ts = w.dueMs, id = "${w.objectId}-evict")
                        active.remove(w.objectId)
                    } else {
                        c.emitter.objLocation(
                            ObjLocation(
                                objectId = w.objectId,
                                x = sample.p.x,
                                y = sample.p.y,
                                isMale = true,
                                hasTag = false, // staff are excluded at the gate; a shopper never carries the tag
                                viewDirection = sample.viewDir?.let { arrayOf(it.x, it.y) },
                                layoutId = c.plan.spaceId,
                            ),
                            ts = w.dueMs,
                            id = "${w.objectId}-${w.seq}",
                        )
                        if (w.seq == 0) {
                            active[w.objectId] = c
                            minuteArrivals++
                        }
                        minuteEvents++
                    }
                    published++
                }
            } finally {
                // every shopper still on the floor leaves now, so no session is left open on the platform
                val end = System.currentTimeMillis()
                active.forEach { (id, c) -> c.emitter.objEviction(ObjEviction(objectId = id, layoutId = c.plan.spaceId), ts = end, id = "$id-evict") }
                saveState(s, end, cursors.map { it.plan })
                log.info(
                    "SIM-RUN live stop reason={} published={} evicted_at_stop={} wall={} plan_clocks={}",
                    reason,
                    published,
                    active.size,
                    fmtMs(end - t0),
                    cursors.joinToString(",") {
                        "${it.plan.slug}@${Instant.ofEpochMilli(it.plan.clock.planOf(end)).atZone(it.plan.zone).toLocalDateTime()}"
                    },
                )
                cursors.forEach { it.emitter.close() }
                gen.shutdownNow()
                done.countDown()
            }
        }
    }
}
