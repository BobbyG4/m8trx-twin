package com.m8trx.twin.livefloor

import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.layer3.LiveDrive
import io.nats.client.Nats
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * The live floor map's people from the store-day model, ADDITIVE and per store: the stores in `M8TRX_LIVE_FLOOR_STORES` (default empty = off) get
 * this feed; every other site in `M8TRX_LIVE_SITES` keeps the generator exactly as before ([LiveDrive] starts this in a thread beside it).
 * `M8TRX_LIVE_PEOPLE_SOURCE` still names only the generator sites' source. A store in both lists is refused at start.
 *
 *  - **Whose people.** The twin's own store-day shoppers ([LiveFloorPlanner]): the same visits the people-by-REST lane sends, each walker's
 *    `objectId` the visit's REST `trackingId`, plus the floor staff (`hasTag=true`, `live-staff-…` ids).
 *  - **Which stores / where.** `M8TRX_LIVE_FLOOR_STORES` (comma list). No edge binding: a live run needs `M8TRX_LIVE_FLOOR_NATS_URL` (no default)
 *    and the broker's `server_name` must equal `M8TRX_LIVE_FLOOR_SERVER_NAME` (default `edge-twin-denver`), nothing else.
 *  - **Subjects.** ONLY `m8trx.<tenant>.<site>.xovis.objLocation` and `…xovis.objEviction` (through [FloorPublisher], whose [SubjectGuard] throws on anything else); never `area.*`.
 *  - **Clock.** Plan time is the store's local clock shifted by `M8TRX_LIVE_SHIFT` (default `0` here: real local time, which is what the
 *    people-by-REST live lane sends against, so a person on the map is the person whose session is open). Positions 5 Hz per person at any
 *    `M8TRX_LIVE_SPEED` (every speed-th sample is kept). Wire `ts` = the scheduled wall time.
 *  - **Guards.** The `server_name` of the broker must equal the edge's (never `:4222`, the production office edge), the office space is refused,
 *    HOLD stops the run, SIGTERM evicts everyone still on the floor.
 *  - **Dry mode** (everything unless `M8TRX_PEOPLE_LIVE=true`): no connection is opened; the would-be messages of a window go to NDJSON under
 *    `M8TRX_LIVE_FLOOR_DRY_DIR` (default `out/live-floor-dry`): `M8TRX_LIVE_FLOOR_DRY_DATE` (default the store's local date), `_DRY_FROM`
 *    (store-local HH:mm, default open + 1 h), `_DRY_MINUTES` (default 10).
 */
object LiveFloorDrive {
    private val log = LoggerFactory.getLogger("com.m8trx.twin.livefloor.LiveFloorDrive")
    const val ENV_STORES = "M8TRX_LIVE_FLOOR_STORES"
    const val ENV_URL = "M8TRX_LIVE_FLOOR_NATS_URL"
    const val ENV_SERVER_NAME = "M8TRX_LIVE_FLOOR_SERVER_NAME"
    const val DEFAULT_SERVER_NAME = "edge-twin-denver"
    private const val OFFICE_SPACE = "0efb9aaa"
    private const val EXPECTED_PRODUCTION_PORT = 4222

    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }

    /** The stores that get the livefloor feed; empty (the default) means the feature is off. */
    fun stores(): List<String> = (env(ENV_STORES) ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** The one port the twin edge's broker listens on. */
    const val TWIN_PORT = 4223

    /**
     * Null when [url] may be used: exactly one server, with an explicit port that is the twin edge's [TWIN_PORT]. A list is refused, a missing
     * port is refused (jnats would default to 4222, the production office edge) and so is 4222 itself.
     */
    fun urlProblem(url: String): String? {
        val parts = url.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 1) return "exactly one server is required, got ${parts.size}"
        val uri = runCatching { java.net.URI(parts[0]) }.getOrNull() ?: return "'${parts[0]}' is not a valid URI"
        return when {
            uri.host == null -> "'${parts[0]}' has no host"
            uri.port == -1 -> "'${parts[0]}' has no port (jnats would default to $EXPECTED_PRODUCTION_PORT, the production office edge)"
            uri.port == EXPECTED_PRODUCTION_PORT -> "port $EXPECTED_PRODUCTION_PORT is edge-itx-office, production hardware - refused"
            uri.port != TWIN_PORT -> "port ${uri.port} is not the twin edge's $TWIN_PORT"
            else -> null
        }
    }

    /**
     * Every setting of the floor feed that can be checked without a connection, so a bad one refuses the WHOLE start (exit 3 in [LiveDrive.main],
     * before the generator path begins) instead of killing a running process later. Empty = fine. The broker's `server_name` needs a connection and is
     * checked inside the floor path, which never exits the process.
     */
    fun validate(s: LiveDrive.Settings, keys: ChainKeys): List<String> {
        val out = ArrayList<String>()
        val slugs = stores()
        val known = keys.stores.map { it.slug }
        slugs.filter { it !in known }.forEach { out += "unknown store '$it' in $ENV_STORES (known: $known)" }
        slugs.filter { it in known }.forEach { slug ->
            if (keys.store(slug).salesFloorSpaceId.startsWith(OFFICE_SPACE)) out += "$slug is the OFFICE space - refused"
        }
        if (slugs.isNotEmpty()) {
            val url = env(ENV_URL)
            if (url == null && s.live) out += "$ENV_URL is required for a live run (e.g. nats://192.168.55.29:4223), there is no default"
            if (url != null) urlProblem(url)?.let { out += "$ENV_URL: $it" }
        }
        return out
    }

    /**
     * The floor path, run in a daemon thread by [LiveDrive.main] after [validate] passed. It NEVER exits the process: any failure is logged as an
     * ERROR with its stack trace (the line starts `LIVEFLOOR STOPPED`, the same log the live wrapper script posts from) and the thread returns, so the
     * generator sites carry on. Plan time is real local time: `M8TRX_LIVE_SHIFT` is ignored here (no shift, no backdating).
     */
    fun run(s: LiveDrive.Settings, keys: ChainKeys, siteIds: Map<String, String>) {
        try {
            val slugs = stores()
            val shift = LiveDrive.Shift.Hours(0.0)
            val planner = LiveFloorPlanner(s.chainDir, keys, siteIds)
            log.info(
                "=== LiveFloorDrive === stores={} speed={}x live={} - real local time: M8TRX_LIVE_SHIFT is ignored on this path",
                slugs,
                s.speed,
                s.live,
            )
            if (!s.live) {
                dry(s, keys, siteIds, planner, slugs, shift)
                return
            }
            live(s, keys, siteIds, planner, slugs, shift)
        } catch (t: Throwable) {
            log.error("LIVEFLOOR STOPPED: the floor feed failed ({}); the generator sites are not affected", t.toString(), t)
        }
    }

    // ── dry ───────────────────────────────────────────────────────────────────────────────────────────────

    private fun dry(
        s: LiveDrive.Settings,
        keys: ChainKeys,
        siteIds: Map<String, String>,
        planner: LiveFloorPlanner,
        slugs: List<String>,
        shift: LiveDrive.Shift,
    ) {
        val dir = Path.of(env("M8TRX_LIVE_FLOOR_DRY_DIR") ?: "out/live-floor-dry")
        val minutes = env("M8TRX_LIVE_FLOOR_DRY_MINUTES")?.toLong() ?: 10L
        log.info("DRY-RUN (M8TRX_PEOPLE_LIVE!=true) - nothing is published and no connection is opened; NDJSON under {}", dir)
        for (slug in slugs) {
            val store = keys.store(slug)
            val now = System.currentTimeMillis()
            val planNow = LiveDrive.planAnchor(Instant.ofEpochMilli(now), store.zone, shift)
            val date = env("M8TRX_LIVE_FLOOR_DRY_DATE")?.let(LocalDate::parse) ?: planNow.atZone(store.zone).toLocalDate()
            val day = planner.plan(slug, date)
            val from = env("M8TRX_LIVE_FLOOR_DRY_FROM")?.let { ZonedDateTime.of(date, LocalTime.parse(it), store.zone).toInstant().toEpochMilli() }
                ?: (day.openMs + 3_600_000L)
            val file = dir.resolve(
                "$slug-$date-${java.time.Instant.ofEpochMilli(from).atZone(store.zone).toLocalTime().toString().replace(":", "")}.ndjson",
            )
            val guard = SubjectGuard(s.tenantId, listOf(siteIds[slug] ?: error("no site id for $slug")))
            var n = 0L
            val subjects = java.util.TreeMap<String, Int>()
            NdjsonWriter(file).use { w ->
                for (m in LiveFloorFeed.stream(day.everyone, from, from + minutes * 60_000L, s.speed)) {
                    val wire = LiveFloorFeed.wire(m, s.tenantId, siteIds[slug] ?: error("no site id for $slug"), store.salesFloorSpaceId, m.planMs)
                    w.write(wire.also { guard.check(it.subject) })
                    subjects.merge(wire.subject, 1, Int::plus)
                    n++
                }
            }
            log.info(
                "{} {}: {} customers ({} stops, {} stops with no stand (enclosed fixture), {} visits drawn by the fallback) + {} staff; window {} min -> {} messages in {}",
                slug, date, day.customers.size, day.stats.stops, day.stats.enclosed.values.sum(), day.stats.fallbacks,
                day.staff.size, minutes, n, file,
            )
            subjects.forEach { (k, v) -> log.info("  {} x {}", k, v) }
        }
    }

    // ── live ──────────────────────────────────────────────────────────────────────────────────────────────

    private class Cursor(
        val slug: String,
        val zone: ZoneId,
        val siteId: String,
        val layoutId: String,
        val emitter: FloorPublisher,
        val clock: LiveDrive.Clock,
        var date: LocalDate,
        var day: DayWalk,
        var it: Iterator<FloorMsg>,
        var head: FloorMsg?,
        var next: Future<DayWalk>?,
    ) {
        val due: Long get() = head?.let { clock.wallOf(it.planMs) } ?: Long.MAX_VALUE
    }

    private fun live(
        s: LiveDrive.Settings,
        keys: ChainKeys,
        siteIds: Map<String, String>,
        planner: LiveFloorPlanner,
        slugs: List<String>,
        shift: LiveDrive.Shift,
    ) {
        // HOLD at start: the generator path refuses (exit 6) on its own; here the floor feed simply does not start
        if (s.holdFile != null && Files.exists(s.holdFile)) {
            log.error("LIVEFLOOR STOPPED: HOLD is set ({}); the floor feed does not start", s.holdFile)
            return
        }
        val now = System.currentTimeMillis()
        // Broker only: no edge binding. The url was validated before start (one server, :4223); the broker proves its identity here.
        val url = env(ENV_URL) ?: error("$ENV_URL is not set")
        val expected = env(ENV_SERVER_NAME) ?: DEFAULT_SERVER_NAME
        val probe = Nats.connect(url)
        val name = probe.serverInfo?.serverName
        probe.close()
        if (name != expected) {
            log.error(
                "LIVEFLOOR STOPPED: {} answers as server_name='{}', expected '{}' (:4222 is the PRODUCTION office edge); nothing was published",
                url,
                name,
                expected,
            )
            return
        }
        log.info("interlock: {} is '{}'", url, name)
        (LoggerFactory.getLogger("com.m8trx.twin.layer0") as? ch.qos.logback.classic.Logger)?.level = ch.qos.logback.classic.Level.INFO
        // built once, from the configured tenant and the floor stores' site ids; every publish below goes through it
        val guard = SubjectGuard(s.tenantId, slugs.map { siteIds[it] ?: error("no site id for $it") })
        val gen = Executors.newSingleThreadExecutor { r -> Thread(r, "live-floor-gen").also { it.isDaemon = true } }
        val cursors = slugs.map { slug ->
            val store = keys.store(slug)
            val planAt = LiveDrive.planAnchor(Instant.ofEpochMilli(now), store.zone, shift).toEpochMilli()
            val clock = LiveDrive.Clock(now, planAt, s.speed)
            val date = LiveDrive.planDate(clock, store.zone, now)
            val siteId = siteIds[slug] ?: error("no site id for $slug")
            val day = planner.plan(slug, date)
            val it = LiveFloorFeed.stream(day.everyone, planAt, Long.MAX_VALUE, s.speed)
            Cursor(
                slug, store.zone, siteId, store.salesFloorSpaceId, FloorPublisher(url, guard), clock, date, day, it,
                if (it.hasNext()) it.next() else null, gen.submit<DayWalk> { planner.plan(slug, date.plusDays(1)) },
            ).also { log.info("  {} {} PUBLISHES via {}: {} customers, {} staff", slug, date, expected, day.customers.size, day.staff.size) }
        }
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val done = java.util.concurrent.CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(
            Thread {
                stop.set(true)
                done.await(20, java.util.concurrent.TimeUnit.SECONDS)
            },
        )
        val active = HashMap<String, Cursor>()
        var published = 0L
        var lastHold = 0L
        var reason = "stopped"
        try {
            while (!stop.get()) {
                val c = cursors.minByOrNull { it.due } ?: break
                if (c.head == null) {
                    val nd = c.next!!.get()
                    val nextDate = c.date.plusDays(1)
                    log.info("  {} day {} done -> {} ({} customers)", c.slug, c.date, nextDate, nd.customers.size)
                    c.date = nextDate
                    c.day = nd
                    val pa = c.clock.planOf(System.currentTimeMillis())
                    c.it = LiveFloorFeed.stream(nd.everyone, pa, Long.MAX_VALUE, s.speed)
                    c.head = if (c.it.hasNext()) c.it.next() else null
                    c.next = gen.submit<DayWalk> { planner.plan(c.slug, nextDate.plusDays(1)) }
                    if (c.head == null) Thread.sleep(1_000)
                    continue
                }
                val t = System.currentTimeMillis()
                if (t - lastHold >= 5_000) {
                    lastHold = t
                    if (s.holdFile != null && Files.exists(s.holdFile)) {
                        reason = "HOLD"
                        break
                    }
                }
                val wait = c.due - t
                if (wait > 0) {
                    Thread.sleep(minOf(wait, 1_000L))
                    continue
                }
                val m = c.head!!
                c.head = if (c.it.hasNext()) c.it.next() else null
                val ts = c.clock.wallOf(m.planMs)
                val sample = m.sample
                c.emitter.publish(LiveFloorFeed.wire(m, s.tenantId, c.siteId, c.layoutId, ts))
                if (sample == null) active.remove(m.walker.objectId) else active[m.walker.objectId] = c
                published++
            }
        } finally {
            val end = System.currentTimeMillis()
            var failed = 0
            active.forEach { (id, c) ->
                try {
                    c.emitter.publish(LiveFloorFeed.evictionWire(id, s.tenantId, c.siteId, c.layoutId, end))
                } catch (t: Throwable) {
                    failed++
                    log.error("eviction of {} at stop failed: {}", id, t.toString())
                }
            }
            log.info("LiveFloorDrive stop reason={} published={} evicted_at_stop={} eviction_failures={}", reason, published, active.size, failed)
            cursors.forEach { runCatching { it.emitter.close() } }
            gen.shutdownNow()
            done.countDown()
        }
    }
}
