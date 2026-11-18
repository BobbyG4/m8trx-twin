package com.m8trx.twin.storeops

import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.storeday.PeopleRest
import com.m8trx.twin.storeday.buildSim
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.system.exitProcess

/**
 * `./gradlew storeDayPeopleRestDryRun` - Phase 3 step 1, OFFLINE. Simulates the store days of the nine non-Denver stores, writes each day's
 * `people-rest.jsonl` sidecar under `out/day/<slug>/<date>/`, then plays the merged plan of all stores through ONE paced lane (one key) against a
 * simulated clock and writes what it would send to `out/people-rest/would-send.jsonl`. Nothing is sent and no client exists: there is no live
 * class here, and the live and backfill switches are REFUSED with a message (Bob 2026-11-19: going live is a later decision; backfill only
 * after live is tested).
 *
 * Pacing: ONE key serves every store, so the cap is global: at most [PeoplePace.capPerMin] requests in any 60 s window (default 300, half the
 * per-key default of 600; the 2026-10-12 incident starved the server at about 370 writes a minute and 660 a minute was measured OK, A7). The
 * rules of [RetryPolicy] apply through [Pump] (429 waits a minute or Retry-After, 502/503/504 and no answer wait for the health gate, a refusal
 * halts the lane, a failed request stays due and keeps its place).
 *
 * Env: `M8TRX_PEOPLE_REST_SITES` (the nine; comma list) · `_FROM` (2026-11-21, a Saturday) · `_DAYS` (1) · `_SEED` (the behavior file's) ·
 * `_CAP_PER_MIN` (300) · `_OUT` (out/people-rest; sidecars go to `M8TRX_STORE_DAY_OUT`, default out/day) ·
 * `_INCLUDE_DENVER` (false; offline tests only) · `_LIVE` / `_BACKFILL` (refused).
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.storeops.PeopleRestDryRun")

object PeoplePace {
    const val DEFAULT_CAP_PER_MIN = 300
    const val KIND = "people"

    /** The spacing that keeps every 60 s window at or under [capPerMin]: ceil(60000 / cap). */
    fun gapMs(capPerMin: Int): Long {
        require(capPerMin in 1..600) { "cap per minute must be 1..600 (the per-key default is 600), got $capPerMin" }
        return (60_000L + capPerMin - 1) / capPerMin
    }

    /** Null when the run may go ahead; the refusal text otherwise (live and backfill are not part of this slice). */
    fun refusal(live: Boolean, backfill: Boolean): String? = when {
        live -> "M8TRX_PEOPLE_REST_LIVE: there is no live people-rest sender in this slice (offline build; going live is a later decision)"
        backfill -> "M8TRX_PEOPLE_REST_BACKFILL: backfill is refused; Bob: backfill only after the live path is tested"
        else -> null
    }

    fun item(l: PeopleRest.Line, seq: Int): SendItem = SendItem(seq, l.dueMs, l.rank, KIND, l.id, PeopleRest.toJson(l), emptyList())

    /** Merge several stores' lines into one plan in (due, rank, store order) with fresh seqs: what one key's lane would face. */
    fun merge(perStore: List<List<PeopleRest.Line>>): List<SendItem> {
        val all = perStore.flatMapIndexed { si, ls -> ls.map { Triple(si, it, it.seq) } }
            .sortedWith(compareBy({ it.second.dueMs }, { it.second.rank }, { it.first }, { it.third }))
        return all.mapIndexed { i, t -> item(t.second, i) }
    }

    /** The most requests in any 60 s window (t-60 s, t] over [times] (sorted or not). */
    fun peakPerMinute(times: List<Long>): Int {
        val ts = times.sorted()
        var lo = 0
        var best = 0
        for (hi in ts.indices) {
            while (ts[hi] - ts[lo] >= 60_000L) lo++
            best = maxOf(best, hi - lo + 1)
        }
        return best
    }
}

/** What a paced run came to. [sentAtMs] is the simulated time each request went out. */
class PacedRun(
    val planned: List<SendItem>,
    val sent: List<SendItem>,
    val sentAtMs: List<Long>,
    val halted: String?,
    val healthChecks: Int,
    val waits: List<String>,
    val maxLagMs: Long,
    val finishedMs: Long,
)

/**
 * The dry-run sender: records, answers 2xx, resolves a close's session from the begin's (simulated) 201. [faults] may answer an attempt with
 * a failure instead (tests). A close or an impression whose begin was never accepted is refused 422: the lane halts, loudly.
 */
class PeopleDrySender(
    private val sink: ((SendItem, String, Long) -> Unit)? = null,
    private val faults: (SendItem, Int) -> Outcome? = { _, _ -> null },
    private val now: () -> Long = { 0L },
) : OpsSender {
    val sent = ArrayList<SendItem>()
    val sentAt = ArrayList<Long>()
    private val sessions = HashMap<String, String>()
    private val attempts = HashMap<String, Int>()
    var healthDown = false

    override fun health(): Pair<Int, String?> = if (healthDown) 502 to null else 200 to "{\"status\":\"ok\"}"

    override fun send(item: SendItem): Outcome {
        val n = attempts.merge(item.extId, 1, Int::plus)!!
        faults(item, n)?.let { return it }
        val p = item.payload
        val ref = p.path("session_ref").asText().ifBlank { null }
        val path = when (p.path("kind").asText()) {
            "person_begin" -> {
                // the 201 carries the server's id; here a stable one derived from the idempotent id
                sessions[item.extId] = UUID.nameUUIDFromBytes(item.extId.toByteArray()).toString()
                p.path("path").asText()
            }

            "person_close" -> {
                val sid = sessions[ref] ?: return Outcome.Http(422)
                p.path("path").asText().replace("{session_ref}", sid)
            }

            else -> {
                if (ref !in sessions) return Outcome.Http(422)
                p.path("path").asText()
            }
        }
        sent += item
        sentAt += now()
        sink?.invoke(item, path, now())
        return Outcome.Accepted
    }
}

object PeopleRestPacer {
    /** Play [plan] through one lane at [capPerMin], from the first due time until the lane drains (or halts). */
    fun run(
        plan: List<SendItem>,
        capPerMin: Int,
        faults: (SendItem, Int) -> Outcome? = { _, _ -> null },
        sink: ((SendItem, String, Long) -> Unit)? = null,
        healthEveryMs: Long = 30_000,
    ): PacedRun {
        val gap = PeoplePace.gapMs(capPerMin)
        val clock = SimClock(plan.firstOrNull()?.dueMs ?: 0L)
        val sender = PeopleDrySender(sink, faults) { clock.nowMs }
        val waits = ArrayList<String>()
        val pump = Pump(
            plan, sender, RetryPolicy(), SentLedger(null), OrderingGuard(emptyList()), mapOf(PeoplePace.KIND to gap), healthEveryMs, 5_000, { false },
            { waits += it },
        )
        var halted: String? = null
        var lag = 0L
        while (true) {
            val head = pump.queue.head()
            val before = sender.sent.size
            when (pump.pump(clock.nowMs)) {
                Pump.State.IDLE -> break

                Pump.State.HALTED -> {
                    halted = pump.haltReason
                    break
                }

                Pump.State.HOLD -> error("no hold in a dry run")

                Pump.State.WAITING -> {}
            }
            if (sender.sent.size > before && head != null) lag = maxOf(lag, clock.nowMs - head.dueMs)
            val next = pump.queue.head()
            // jump to the next due time when nothing can be sent before it; otherwise tick at the pace
            clock.nowMs = maxOf(clock.nowMs + minOf(gap, 1_000L), if (next != null && next.dueMs > clock.nowMs + gap) next.dueMs else 0L)
            check(clock.nowMs < (plan.lastOrNull()?.dueMs ?: 0L) + 7 * 24 * 3_600_000L) {
                "the lane did not drain within a week of the last due time"
            }
        }
        return PacedRun(plan, sender.sent, sender.sentAt, halted, pump.healthChecks, waits, lag, clock.nowMs)
    }
}

fun main() {
    fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
    PeoplePace.refusal(env("M8TRX_PEOPLE_REST_LIVE")?.toBoolean() == true, env("M8TRX_PEOPLE_REST_BACKFILL")?.toBoolean() == true)?.let {
        log.error("REFUSING: {}", it)
        exitProcess(3)
    }
    val includeDenver = env("M8TRX_PEOPLE_REST_INCLUDE_DENVER")?.toBoolean() == true
    val sites = (env("M8TRX_PEOPLE_REST_SITES")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: PeopleRest.STORES)
    sites.forEach { s ->
        PeopleRest.refusal(s, includeDenver)?.let {
            log.error("REFUSING: {}", it)
            exitProcess(3)
        }
    }
    val from = LocalDate.parse(env("M8TRX_PEOPLE_REST_FROM") ?: "2026-11-21")
    val days = env("M8TRX_PEOPLE_REST_DAYS")?.toInt() ?: 1
    val seed = env("M8TRX_PEOPLE_REST_SEED")?.toLong()
    val cap = env("M8TRX_PEOPLE_REST_CAP_PER_MIN")?.toInt() ?: PeoplePace.DEFAULT_CAP_PER_MIN
    val out = Path.of(env("M8TRX_PEOPLE_REST_OUT") ?: "out/people-rest")
    val dayOut = Path.of(env("M8TRX_STORE_DAY_OUT") ?: "out/day")
    val chainDir = Path.of("reference/data/chain")
    val keys = ChainKeys.load(chainDir.resolve(ChainKeys.DEFAULT_PATH.fileName))
    val siteIds = com.m8trx.twin.connect.DatedStream.readSiteIds(chainDir.resolve("site_ids.csv"))
    log.info("storeDayPeopleRestDryRun: simulated clock, nothing is sent, no client exists. stores={} {} +{}d cap={}/min", sites, from, days, cap)
    val perStore = ArrayList<List<PeopleRest.Line>>()
    for (slug in sites) {
        val sim = buildSim(slug)
        val ids = PeopleRest.idsFor(slug, keys, siteIds)
        val state = sim.initialState()
        val s = seed ?: sim.behavior.params.seed
        val lines = ArrayList<PeopleRest.Line>()
        for (i in 0 until days) {
            val date = from.plusDays(i.toLong())
            val r = sim.simulate(state, date, s)
            val day = PeopleRest.plan(slug, date, r, ids)
            PeopleRest.write(PeopleRest.sidecar(dayOut, slug, date), day)
            lines += day
            log.info("{} {}: {} sessions, {} requests", slug, date, r.records.size, day.size)
        }
        perStore += lines
    }
    val plan = PeoplePace.merge(perStore)
    Files.createDirectories(out)
    val before = LiveClients.constructed
    val run = Files.newBufferedWriter(out.resolve("would-send.jsonl")).use { w ->
        PeopleRestPacer.run(plan, cap, sink = { item, path, at ->
            val n = ConnectMappers.snake.createObjectNode()
            n.put("sim_time", Instant.ofEpochMilli(at).toString())
            n.put("due", Instant.ofEpochMilli(item.dueMs).toString())
            n.put("kind", item.payload.path("kind").asText())
            n.put("method", item.payload.path("method").asText())
            n.put("path", path)
            n.put("id", item.extId)
            w.write(ConnectMappers.snake.writeValueAsString(n) + "\n")
        })
    }
    println(
        "people-rest dry run: ${plan.size} requests planned, ${run.sent.size} would be sent, halted=${run.halted}, " +
            "demand peak ${PeoplePace.peakPerMinute(
                plan.map {
                    it.dueMs
                },
            )}/min, paced peak ${PeoplePace.peakPerMinute(run.sentAtMs)}/min (cap $cap), " +
            "max lag ${run.maxLagMs / 1000} s, drained ${Instant.ofEpochMilli(
                run.finishedMs,
            )}, live clients built: ${LiveClients.constructed - before}",
    )
    if (run.halted != null || run.sent.size != plan.size) exitProcess(1)
}
