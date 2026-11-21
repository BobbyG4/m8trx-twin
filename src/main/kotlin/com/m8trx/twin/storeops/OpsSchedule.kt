package com.m8trx.twin.storeops

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.storeday.WireEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Which door a send goes through. */
enum class Route(val label: String) {
    BEARER_RECEIVE("POST /api/v2/inventory/items/receive (M8TRX_TWIN_SERVICE_BEARER)"),
    WEBHOOK_MOVEMENT("webhook X-Data-Type: inventory_movement"),
    WEBHOOK_SALE("webhook X-Data-Type: sale_event"),
    WEBHOOK_PLANOGRAM("webhook X-Data-Type: planogram_directive"),
    USER_COUNT("store-manager JWT count steps (OpsClient)"),
}

/**
 * One thing to send. [dueMs] is absolute (epoch ms) for the live driver and relative to the reset's start for a reset schedule;
 * [rank] breaks ties at one instant the way the store-day wire plan does (receive, move, sale). [extId] is the idempotency key:
 * stable across reruns, content-derived where the platform has no id field of its own (a receive request).
 */
class SendItem(
    val seq: Int,
    val dueMs: Long,
    val rank: Int,
    val kind: String,
    val extId: String,
    val payload: JsonNode,
    val epcs: List<String>,
    /** Must not go out before the store's local midnight of the reset day (A14: the old directive's end and the new plan). */
    val afterLocalMidnight: Boolean = false,
) {
    val route: Route
        get() = routeOf(kind)

    companion object {
        fun routeOf(kind: String): Route = when (kind) {
            "receive" -> Route.BEARER_RECEIVE
            "move" -> Route.WEBHOOK_MOVEMENT
            "sale" -> Route.WEBHOOK_SALE
            "planogram" -> Route.WEBHOOK_PLANOGRAM
            "count" -> Route.USER_COUNT
            else -> error("no route for wire kind '$kind'")
        }

        fun hash(vararg parts: Any): String = MessageDigest.getInstance("SHA-1").digest(parts.joinToString("|").toByteArray())
            .joinToString("") { "%02x".format(it) }.take(10)

        fun epcsOf(kind: String, p: JsonNode): List<String> = when (kind) {
            "receive", "move" -> p.path("items").map { it.path("epc").asText() }
            "sale" -> if (p.has("epc_list")) p.path("epc_list").map(JsonNode::asText) else listOfNotNull(p.path("epc").asText().ifBlank { null })
            else -> emptyList()
        }

        /**
         * A store-day wire event as a send item. Kinds the driver does not send are not converted here: `person_samples_ref` belongs to
         * the people plane (the sidecar), and the caller counts those explicitly rather than dropping them unseen.
         */
        fun fromWire(e: WireEvent, slug: String): SendItem {
            var p = e.payload as JsonNode
            // A15: a back-room move targets a fixture ZONE of the stockroom space, never the space itself: the store-day plan carries
            // the placeholder `@backroom_space`, and its `to` id names the rack
            if (e.kind == "move" && p.path("to_zone_id").asText() == "@backroom_space") {
                p = (p.deepCopy<ObjectNode>()).also { it.put("to_zone_id", "@backroom_zone:${e.ids["to"]}") }
            }
            val epcs = epcsOf(e.kind, p)
            val id = when (e.kind) {
                "move" -> p.path("external_movement_id").asText()
                "sale" -> p.path("external_sale_id").asText()
                "planogram" -> p.path("external_directive_id").asText()
                "receive" -> "rcv-$slug-${hash(epcs.joinToString(","))}"
                else -> "${e.kind}-$slug-${hash(e.ids["count"] ?: "", e.seq, p.toString())}"
            }
            return SendItem(e.seq, e.dueMs, e.rank, e.kind, id, p, epcs)
        }
    }
}

class OrderingViolation(message: String) : IllegalStateException(message)

/**
 * The per-unit ordering rule, asserted before every send (the same rule `storeDaySelfTest` proves on the wire plan files):
 *  - items go out in non-decreasing (due, rank, seq) order;
 *  - a unit is received once, before anything else is said of it;
 *  - a move names a unit that is known (in the start state or already received) and not sold;
 *  - a unit is sold once, and no move of it comes after its sale.
 * [known] = the units the platform already has (the start state's unsold units).
 */
class OrderingGuard(known: Collection<String>) {
    private val here = HashSet<String>(known)
    private val sold = HashSet<String>()
    private var last: Triple<Long, Int, Int>? = null

    fun check(it: SendItem) {
        val key = Triple(it.dueMs, it.rank, it.seq)
        last?.let { l ->
            if (compareValuesBy(key, l, { k -> k.first }, { k -> k.second }, { k -> k.third }) < 0) {
                throw OrderingViolation("${it.extId} due ${it.dueMs} comes before the item sent last ($l)")
            }
        }
        when (it.kind) {
            "receive" -> it.epcs.forEach { e -> if (!here.add(e)) throw OrderingViolation("$e is received twice (${it.extId})") }

            "move" -> it.epcs.forEach { e ->
                if (e in sold) throw OrderingViolation("$e is moved after its sale (${it.extId})")
                if (e !in here) throw OrderingViolation("$e is moved before it is received or known (${it.extId})")
            }

            "sale" -> it.epcs.forEach { e ->
                if (e !in here) throw OrderingViolation("$e is sold but was never received or known (${it.extId})")
                if (!sold.add(e)) throw OrderingViolation("$e is sold twice (${it.extId})")
            }
        }
        last = key
    }
}

/**
 * The resumable sent-ledger: an append-only file, one line per ACCEPTED send (`ext_id <TAB> kind <TAB> units`). A rerun builds
 * the same schedule (ids are stable) and skips what the ledger holds; a send that failed or was in flight at a crash is not in
 * it, so it is sent again. A re-send of something the platform already took is harmless: movements and sales dedupe on their
 * external id, a directive on its directive id, and a receive on the EPC. [file] null keeps it in memory (dry runs and tests).
 */
class SentLedger(private val file: Path?) {
    private val done = LinkedHashSet<String>()

    init {
        if (file != null && Files.exists(file)) Files.readAllLines(file).filter { it.isNotBlank() }.forEach { done += it.substringBefore('\t') }
    }

    val size: Int get() = done.size

    fun isSent(extId: String): Boolean = extId in done

    fun markSent(item: SendItem) {
        if (!done.add(item.extId)) return
        if (file != null) {
            file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            Files.writeString(file, "${item.extId}\t${item.kind}\t${item.epcs.size}\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }
}

/** `@site` and `@backroom_space`, the placeholders the store-day wire plan carries, resolved to this store's real ids. */
class Placeholders(
    private val siteId: String?,
    private val backroomSpaceId: String?,
    /** Back-room rack code (`BR-01`) to the stockroom fixture zone's id, read from `/spatial/identity` at run time. */
    private val backroomZones: Map<String, String> = emptyMap(),
) {
    fun resolve(n: JsonNode): JsonNode = when {
        n.isTextual -> when (n.asText()) {
            "@site" -> ConnectMappers.snake.nodeFactory.textNode(siteId ?: "@site")

            "@backroom_space" -> ConnectMappers.snake.nodeFactory.textNode(backroomSpaceId ?: "@backroom_space")

            else -> if (n.asText().startsWith(ZONE_PREFIX)) {
                ConnectMappers.snake.nodeFactory.textNode(backroomZones[n.asText().removePrefix(ZONE_PREFIX)] ?: n.asText())
            } else {
                n
            }
        }

        n.isObject -> ConnectMappers.snake.createObjectNode().also { o ->
            (n as ObjectNode).fields().forEach { (k, v) -> o.set<JsonNode>(k, resolve(v)) }
        }

        n.isArray -> ConnectMappers.snake.createArrayNode().also { a -> (n as ArrayNode).forEach { a.add(resolve(it)) } }

        else -> n
    }

    /** True when every placeholder in [n] has an id to be replaced by. */
    fun resolvable(n: JsonNode): Boolean = !hasPlaceholder(resolve(n))

    companion object {
        const val ZONE_PREFIX = "@backroom_zone:"
    }

    private fun hasPlaceholder(n: JsonNode): Boolean = when {
        n.isTextual -> n.asText().startsWith("@site") || n.asText().startsWith("@backroom_space") || n.asText().startsWith(ZONE_PREFIX)
        n.isContainerNode -> n.any(::hasPlaceholder)
        else -> false
    }
}

/** A store's sending side. The dry-run implementation records; the live one is [LiveSender] and exists only behind the live flag. */
interface OpsSender {
    /** The health gate's answer: HTTP status and body (null when nothing answered at all). */
    fun health(): Pair<Int, String?>

    fun send(item: SendItem): Outcome

    /** Forget the cached login (a 401). Nothing to do for a sender that holds none. */
    fun dropToken() {}
}

/** Counts how many live clients were ever built in this JVM: dry-run paths must leave it at zero (a test asserts it). */
object LiveClients {
    @Volatile
    var constructed: Int = 0
}

/** Sends nothing, ever. Records what it was handed, in order. */
class DryRunSender(private val sink: ((SendItem) -> Unit)? = null) : OpsSender {
    val sent = ArrayList<SendItem>()

    override fun health(): Pair<Int, String?> = 200 to "{\"status\":\"ok\"}"

    override fun send(item: SendItem): Outcome {
        sent += item
        sink?.invoke(item)
        return Outcome.Accepted
    }
}

/** A clock the tests and the dry run own: time moves only when told to. */
class SimClock(var nowMs: Long) {
    fun advance(ms: Long) {
        nowMs += ms
    }
}

/**
 * Sends one lane's items in order, at their due times, under the retry policy. No sleeping in here: [pump] is called at the
 * driver's tick with the current time and does what is due (the driver sleeps; the dry run just moves its [SimClock]).
 *
 * Per send: HOLD checked first, the health gate (before every batch when [healthEveryMs] is 0, else when that long has passed
 * since the last ok or after any failure), the ordering guard, then the send. The pace per kind ([spacingMs]) holds even when
 * a backlog is due at once after an outage.
 */
class Pump(
    items: List<SendItem>,
    private val sender: OpsSender,
    private val policy: RetryPolicy,
    private val ledger: SentLedger,
    private val guard: OrderingGuard,
    private val spacingMs: Map<String, Long> = emptyMap(),
    private val healthEveryMs: Long = 0,
    private val healthPollMs: Long = 5_000,
    private val hold: () -> Boolean = { false },
    private val onEvent: (String) -> Unit = {},
    /** Moves also go under this item budget (items per rolling minute), on top of any [spacingMs] floor. */
    private val moveBudget: ItemBudget? = null,
) {
    enum class State { IDLE, WAITING, HOLD, HALTED }

    val queue = DueQueue(items.filter { !ledger.isSent(it.extId) }) { it.dueMs }
    private val lastSentAt = HashMap<String, Long>()
    private var lastHealthOkAt = Long.MIN_VALUE / 2
    private var needHealth = false
    private var attempt = 1
    private var checked: SendItem? = null
    var haltReason: String? = null
        private set
    val alerts = ArrayList<String>()
    val skippedByLedger: Int = items.size - queue.size
    var healthChecks = 0
        private set

    /** Do everything due at [now]; returns why it stopped. */
    fun pump(now: Long): State {
        if (haltReason != null) return State.HALTED
        if (hold()) return State.HOLD
        while (true) {
            val item = queue.due(now) ?: return if (queue.size == 0) State.IDLE else State.WAITING
            lastSentAt[item.kind]?.let { t ->
                val gap = spacingMs[item.kind] ?: 0L
                if (now < t + gap) {
                    queue.delayUntil(t + gap)
                    return State.WAITING
                }
            }
            if (item.kind == "move" && moveBudget != null) {
                val wait = moveBudget.waitMsFor(item.epcs.size, now)
                if (wait > 0) {
                    queue.delayUntil(now + wait)
                    return State.WAITING
                }
            }
            if (needHealth || (healthEveryMs >= 0 && now - lastHealthOkAt >= healthEveryMs)) {
                healthChecks++
                val (status, body) = sender.health()
                if (!Health.isReady(status, body)) {
                    needHealth = true
                    queue.delayUntil(now + healthPollMs)
                    onEvent("health not ready ($status): waiting, ${item.extId} stays due")
                    return State.WAITING
                }
                needHealth = false
                lastHealthOkAt = now
            }
            if (checked !== item) {
                guard.check(item)
                checked = item
            }
            val outcome = sender.send(item)
            when (val d = policy.decide(outcome, attempt)) {
                Decision.Done -> {
                    ledger.markSent(item)
                    queue.ack()
                    if (item.kind == "move") moveBudget?.record(item.epcs.size, now)
                    lastSentAt[item.kind] = now
                    attempt = 1
                }

                is Decision.Wait -> {
                    queue.failed(now, d.ms)
                    if (d.needHealth) needHealth = true
                    if (d.dropToken) sender.dropToken()
                    if (d.alert) alerts += "${item.extId}: ${d.reason} (attempt $attempt)"
                    onEvent("${item.extId} not sent: ${d.reason}; due again in ${d.ms} ms")
                    attempt++
                    return State.WAITING
                }

                is Decision.Halt -> {
                    haltReason = "${item.extId}: ${d.reason}"
                    onEvent("HALT ${item.extId}: ${d.reason}")
                    return State.HALTED
                }
            }
        }
    }
}
