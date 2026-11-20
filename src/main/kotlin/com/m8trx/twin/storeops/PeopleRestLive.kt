package com.m8trx.twin.storeops

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.storeday.PeopleRest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

/**
 * The LIVE side of people-by-REST (Phase 3 step 1): gates, window selection, the frozen plan, the sent-ledger, the sender and the runner.
 * Everything here is pure or file-only: the one network class is `peoplelive.PeopleRestHttp`, handed in as a [PeopleTransport]. Backfill is
 * REFUSED (Bob: backfill only after live is tested).
 *
 * WINDOW RULE (`M8TRX_PEOPLE_REST_FROM` / `_UNTIL`, store-local HH:mm): a SESSION is in the window when its BEGIN is due in [FROM, UNTIL). A
 * session that begins before FROM is skipped whole (no orphan impressions or close). A session that begins in the window keeps its
 * impressions and its close even when those fall after UNTIL, so no session is left open: the run lasts until the last such close is due.
 * A line is sent only when due <= now: nothing in the future goes out early.
 */
interface PeopleTransport {
    fun health(): Pair<Int, String?>

    /** One request; throws [IOException] when nothing answers. [path] starts with `/api/v2/...`. */
    fun call(method: String, path: String, body: String): PeopleAnswer
}

class PeopleAnswer(val status: Int, val body: String?, val retryAfterS: Long?)

object PeopleLiveGate {
    const val BEARER_ENV = "M8TRX_TWIN_PEOPLE_BEARER"

    /**
     * Every reason the run is refused (empty = go). [backdated] = stores whose date is before their store-local today (that is backfill),
     * unless [resuming] a frozen plan. [confirm] must name exactly [stores].
     */
    fun refusals(
        live: Boolean,
        backfill: Boolean,
        stores: List<String>,
        confirm: String?,
        bearerPresent: Boolean,
        backdated: List<String> = emptyList(),
        resuming: Boolean = false,
    ): List<String> {
        val out = ArrayList<String>()
        if (backfill) out += "M8TRX_PEOPLE_REST_BACKFILL: backfill is refused; Bob: backfill only after the live path is tested"
        if (stores.isEmpty()) out += "no stores selected"
        stores.forEach { s ->
            val r = PeopleRest.refusal(s, false)
            if (r != null) {
                out += r
            } else if (s !in PeopleRest.STORES) {
                out += "$s is not one of the nine REST-people stores"
            }
        }
        if (stores.toSet().size != stores.size) out += "a store is listed twice"
        if (live) {
            val c = confirm?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            if (c == null) {
                out += "M8TRX_PEOPLE_REST_CONFIRM is not set: it must name exactly the stores being sent (${stores.joinToString(",")})"
            } else if (c != stores.toSet()) {
                out +=
                    "M8TRX_PEOPLE_REST_CONFIRM (${c.sorted().joinToString(
                        ",",
                    )}) does not match the stores of the run (${stores.sorted().joinToString(",")})"
            }
            if (!bearerPresent) out += "$BEARER_ENV is not set (the service key, scope vision_ai:ingest)"
            if (backdated.isNotEmpty() && !resuming) {
                out += "date is before the store-local today for ${backdated.joinToString(",")}: that is backfill, refused"
            }
        }
        return out
    }
}

object PeopleWindow {
    /** "HH:mm" to minutes of the day; "24:00" is allowed (end of day). */
    fun parseMinutes(s: String): Int {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(s.trim()) ?: throw IllegalArgumentException("not HH:mm: '$s'")
        val v = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        require(v in 0..1440 && m.groupValues[2].toInt() < 60) { "time out of range: '$s'" }
        return v
    }

    /** The whole-session rule: keep every line of a session whose BEGIN is due in [fromMin, untilMin) of [date] in [zone]. */
    fun select(lines: List<PeopleRest.Line>, zone: ZoneId, date: LocalDate, fromMin: Int, untilMin: Int): List<PeopleRest.Line> {
        require(fromMin < untilMin) { "FROM must be before UNTIL" }
        val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val inside = lines.filter { it.kind == "person_begin" }.filter {
            val min = (it.dueMs - midnight).floorDiv(60_000L)
            min >= fromMin && min < untilMin
        }.map { sessionKey(it.id) }.toSet()
        return lines.filter { sessionKey(it.id) in inside }
    }

    fun sessionKey(id: String): String = id.substringBeforeLast('-')
}

/** The frozen plan: lines of [PeopleRest.toJson] plus `store`, in merged (due, rank, store order, seq) order. */
object PeopleLivePlan {
    fun item(l: PeopleRest.Line, slug: String, seq: Int): SendItem {
        val n = PeopleRest.toJson(l)
        n.put("store", slug)
        return SendItem(seq, l.dueMs, l.rank, PeoplePace.KIND, l.id, n, emptyList())
    }

    fun merge(perStore: List<Pair<String, List<PeopleRest.Line>>>): List<SendItem> {
        val all = perStore.flatMapIndexed { si, (slug, ls) -> ls.map { Triple(si to slug, it, it.seq) } }
            .sortedWith(compareBy({ it.second.dueMs }, { it.second.rank }, { it.first.first }, { it.third }))
        return all.mapIndexed { i, t -> item(t.second, t.first.second, i) }
    }

    fun write(file: Path, items: List<SendItem>) {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(file, items.joinToString("") { ConnectMappers.snake.writeValueAsString(it.payload) + "\n" })
    }

    fun read(file: Path): List<SendItem> = Files.readAllLines(file).filter { it.isNotBlank() }.mapIndexed { i, s ->
        val n = ConnectMappers.snake.readTree(s)
        val rank = when (n.path("kind").asText()) {
            "person_begin" -> PeopleRest.RANK_BEGIN
            "person_impression" -> PeopleRest.RANK_IMPRESSION
            else -> PeopleRest.RANK_CLOSE
        }
        SendItem(i, Instant.parse(n.path("due_utc").asText()).toEpochMilli(), rank, PeoplePace.KIND, n.path("id").asText(), n, emptyList())
    }

    fun store(i: SendItem): String = i.payload.path("store").asText()

    fun wireKind(i: SendItem): String = i.payload.path("kind").asText()

    /** Exact expected counts per store and kind (`begins`, `impressions`, `closes`). */
    fun counts(items: List<SendItem>): Map<String, Map<String, Int>> {
        val m = TreeMap<String, TreeMap<String, Int>>()
        items.forEach { m.getOrPut(store(it)) { TreeMap() }.merge(kindLabel(wireKind(it)), 1, Int::plus) }
        return m
    }

    fun kindLabel(wire: String) = when (wire) {
        "person_begin" -> "begins"
        "person_impression" -> "impressions"
        else -> "closes"
    }
}

/** Append-only `id <TAB> kind <TAB> http status <TAB> server id <TAB> ts`, one line per ACCEPTED request. */
class PeopleLedger(private val file: Path?) {
    private val done = LinkedHashSet<String>()
    private val serverIds = HashMap<String, String>()

    init {
        if (file != null && Files.exists(file)) {
            Files.readAllLines(file).filter { it.isNotBlank() }.forEach {
                val c = it.split('\t')
                done += c[0]
                c.getOrNull(3)?.takeIf { s -> s.isNotBlank() }?.let { s -> serverIds[c[0]] = s }
            }
        }
    }

    val size: Int get() = done.size

    fun isSent(id: String) = id in done

    fun serverId(id: String): String? = serverIds[id]

    fun record(id: String, kind: String, status: Int, serverId: String?, ts: Instant) {
        if (id in done) return
        // file first, then memory: a failed write throws IOException and the id is NOT marked sent
        file?.let {
            it.toAbsolutePath().parent?.let { p -> Files.createDirectories(p) }
            Files.writeString(it, "$id\t$kind\t$status\t${serverId.orEmpty()}\t$ts\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
        done += id
        serverId?.let { serverIds[id] = it }
    }
}

private val UUID_SHAPE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

class PeopleCell(var sent: Int = 0, var refused: Int = 0, var retried: Int = 0)

class PeopleCounters {
    val cells = TreeMap<String, TreeMap<String, PeopleCell>>()
    var synthesized = 0
    var missingServerIds = 0

    fun cell(store: String, kind: String) = cells.getOrPut(store) { TreeMap() }.getOrPut(kind) { PeopleCell() }

    fun toJson(): ObjectNode {
        val n = ConnectMappers.snake.createObjectNode()
        cells.forEach { (s, ks) ->
            val o = n.putObject(s)
            ks.forEach { (k, c) -> o.putObject(k).put("sent", c.sent).put("refused", c.refused).put("retried", c.retried) }
        }
        return n
    }
}

/**
 * Sends one request of the plan. Accepted requests go in the [ledger] here (they know the status and the server id). Ordering rule: an
 * impression or a close is sent only when its begin is accepted (ledger); otherwise 422 (the lane halts, loudly). [onAttempt] sees every
 * request actually made, for the sliding cap.
 */
class PeopleLiveSender(
    private val transport: PeopleTransport,
    private val ledger: PeopleLedger,
    val counters: PeopleCounters,
    private val now: () -> Long,
    private val onAttempt: (Long) -> Unit = {},
    private val warn: (String) -> Unit = {},
) : OpsSender {
    private val json = ObjectMapper()
    private val attempts = HashMap<String, Int>()
    var lastStatus = 0
        private set

    override fun health(): Pair<Int, String?> = transport.health()

    override fun send(item: SendItem): Outcome {
        val p = item.payload
        val wire = p.path("kind").asText()
        val store = p.path("store").asText()
        val label = PeopleLivePlan.kindLabel(wire)
        val ref = p.path("session_ref").asText().ifBlank { null }
        if (wire != "person_begin" && (ref == null || !ledger.isSent(ref))) {
            counters.cell(store, label).refused++
            lastStatus = 422
            return Outcome.Http(422) // never an impression or a close before its begin is accepted
        }
        val path = p.path("path").asText()
        val sessionUuid = if (wire == "person_begin") p.path("body").path("id").asText() else path.substringAfterLast('/')
        // a begin goes out only with its caller UUID id (services #525), and a close path ends in a UUID: never a placeholder or a line id
        if (path.contains('{') || (!UUID_SHAPE.matches(sessionUuid) && wire != "person_impression")) {
            lastStatus = 422
            counters.cell(store, label).refused++
            return Outcome.Http(422)
        }
        val n = attempts.merge(item.extId, 1, Int::plus)!!
        val cell = counters.cell(store, label)
        if (n > 1) cell.retried++
        val t = now()
        onAttempt(t)
        val a = try {
            transport.call(p.path("method").asText(), path, ConnectMappers.snake.writeValueAsString(p.path("body")))
        } catch (e: IOException) {
            return Outcome.Unreachable(e.message ?: e.javaClass.simpleName)
        }
        lastStatus = a.status
        if (a.status !in 200..299) {
            if (a.status in 400..499 && a.status != 429) cell.refused++
            return Outcome.Http(a.status, a.retryAfterS)
        }
        val answer = a.body?.let { b -> runCatching { json.readTree(b) }.getOrNull() }
        var serverId: String? = null
        if (wire == "person_begin") {
            serverId = listOf(answer?.path("id"), answer?.path("data")?.path("id"), answer?.path("sessionId"))
                .firstNotNullOfOrNull { it?.takeIf(JsonNode::isTextual)?.asText()?.ifBlank { null } }
            if (serverId == null) {
                counters.missingServerIds++
                warn("WARNING ${item.extId}: the 201 carries no parseable id; carrying on with our UUID $sessionUuid")
            } else if (!serverId.equals(sessionUuid, ignoreCase = true)) {
                // the server ignored our id: the close would PATCH a session that is not the one made. Halt, loudly; the begin stays due.
                lastStatus = 500
                return Outcome.ItemErrors(listOf(ItemError(item.extId, "server_id_mismatch: sent id $sessionUuid, the 201 answered $serverId")))
            }
        } else if (answer?.path("sessionSynthesized")?.asBoolean(false) == true) {
            counters.synthesized++
        }
        try {
            ledger.record(item.extId, wire, a.status, serverId, Instant.ofEpochMilli(t))
        } catch (e: IOException) {
            return Outcome.ItemErrors(listOf(ItemError(item.extId, "ledger_write_failed: ${e.message}; the request WAS accepted (HTTP ${a.status})")))
        }
        cell.sent++
        return Outcome.Accepted
    }
}

/** A sliding 60 s window of request times: the global cap, counting failed attempts too. */
class AttemptWindow(private val cap: Int) {
    private val times = ArrayDeque<Long>()

    fun add(t: Long) {
        times.addLast(t)
    }

    /** How long to wait before another request may go out at [now] (0 = now). */
    fun waitMs(now: Long): Long {
        while (times.isNotEmpty() && now - times.first() >= 60_000L) times.removeFirst()
        return if (times.size < cap) 0L else times.first() + 60_000L - now
    }
}

class PeopleLiveParams(val capPerMin: Int = PeoplePace.DEFAULT_CAP_PER_MIN, val healthEveryMs: Long = 30_000, val progressEveryMs: Long = 5_000)

class PeopleLiveRunner(
    private val items: List<SendItem>,
    private val transport: PeopleTransport,
    private val ledger: PeopleLedger,
    private val params: PeopleLiveParams,
    private val clock: () -> Long,
    private val sleeper: (Long) -> Unit,
    private val hold: () -> Boolean,
    private val dir: Path?,
    private val policy: RetryPolicy = RetryPolicy(),
) {
    enum class End { DONE, HOLD, HALTED }

    val counters = PeopleCounters()
    val events = ArrayList<String>()
    var haltReason: String? = null
        private set
    var healthChecks = 0
        private set
    private val window = AttemptWindow(params.capPerMin)
    private val skipped = items.count { ledger.isSent(it.extId) }
    private var lastProgress = Long.MIN_VALUE / 2

    private fun event(s: String) {
        val line = "${Instant.ofEpochMilli(clock())} $s"
        events += line
        dir?.let {
            Files.createDirectories(it)
            Files.writeString(it.resolve("live-run.log"), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    fun run(): End {
        val sender = PeopleLiveSender(transport, ledger, counters, clock, { window.add(it) }, { event(it) })
        val pump = Pump(
            items.filter { !ledger.isSent(it.extId) }, sender, policy, SentLedger(null), OrderingGuard(emptyList()),
            mapOf(PeoplePace.KIND to PeoplePace.gapMs(params.capPerMin)), params.healthEveryMs, 5_000, hold, { event(it) },
        )
        event("start: ${items.size} planned, $skipped already in the sent-ledger, ${pump.queue.size} to send, cap ${params.capPerMin}/min")
        val end: End
        while (true) {
            val now = clock()
            val wait = window.waitMs(now)
            if (wait > 0) {
                sleeper(minOf(wait, 1_000L))
                continue
            }
            val st = pump.pump(now)
            healthChecks = pump.healthChecks
            progress(st.name, pump, false)
            if (st == Pump.State.IDLE) {
                end = End.DONE
                break
            }
            if (st == Pump.State.HOLD) {
                end = End.HOLD
                break
            }
            if (st == Pump.State.HALTED) {
                haltReason = if (sender.lastStatus == 402) {
                    "402 NOT_ENTITLED: the key is not entitled to vision_ai ingest; nothing more is sent. ${pump.haltReason}"
                } else {
                    pump.haltReason
                }
                end = End.HALTED
                break
            }
            val head = pump.queue.head()
            val until = if (head != null && head.dueMs > now) head.dueMs - now else 100L
            sleeper(maxOf(1L, minOf(until, 1_000L)))
        }
        event("end: $end ${haltReason.orEmpty()}")
        progress(end.name, pump, true)
        if (end == End.DONE) complete(pump)
        return end
    }

    private fun progress(state: String, pump: Pump, force: Boolean) {
        val d = dir ?: return
        val now = clock()
        if (!force && now - lastProgress < params.progressEveryMs) return
        lastProgress = now
        val n = ConnectMappers.snake.createObjectNode()
        n.put("state", state).put("at", Instant.ofEpochMilli(now).toString()).put("planned", items.size).put("skipped_by_ledger", skipped)
        n.put("remaining", pump.queue.size).put("health_checks", pump.healthChecks).put("halt", haltReason)
        n.put("synthesized_sessions", counters.synthesized).put("begins_without_parseable_id", counters.missingServerIds)
        n.set<JsonNode>("counts", counters.toJson())
        Files.createDirectories(d)
        Files.writeString(d.resolve("progress.json"), n.toPrettyString())
    }

    private fun complete(pump: Pump) {
        val d = dir ?: return
        val n = ConnectMappers.snake.createObjectNode()
        n.put("completed_at", Instant.ofEpochMilli(clock()).toString()).put("planned", items.size).put("skipped_by_ledger", skipped)
        n.put(
            "alerts",
            pump.alerts.size,
        ).put("synthesized_sessions", counters.synthesized).put("begins_without_parseable_id", counters.missingServerIds)
        n.set<JsonNode>("counts_this_run", counters.toJson())
        n.set<JsonNode>("expected", ConnectMappers.snake.valueToTree(PeopleLivePlan.counts(items)))
        Files.writeString(d.resolve("run-complete.json"), n.toPrettyString())
    }
}
