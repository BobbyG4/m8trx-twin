package com.m8trx.twin.storeops

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * Move pace is a budget of ITEMS (tags) per rolling minute, not of batches (server lane, 2026-11-21: the platform applies about 240 items
 * a minute). A batch may go when the items already sent in the last [windowMs] plus its own stay within the limit; [waitMsFor] says how
 * long to wait. A batch larger than the limit is never split: it goes once the window is empty. [perMin] is read on every call, so the
 * limit can change during the run (the reset lowers it while a receive lane runs beside the moves).
 */
class ItemBudget(private val perMin: () -> Int, private val windowMs: Long = 60_000) {
    constructor(perMin: Int, windowMs: Long = 60_000) : this({ perMin }, windowMs)

    private val sent = ArrayDeque<Pair<Long, Int>>()

    private fun prune(now: Long) {
        while (sent.isNotEmpty() && sent.first().first <= now - windowMs) sent.removeFirst()
    }

    /** How long, from [now], until a batch of [items] may go (0 = now). Does not record anything. */
    @Synchronized
    fun waitMsFor(items: Int, now: Long): Long {
        val limit = perMin()
        prune(now)
        var used = sent.sumOf { it.second }
        if (used + items <= limit) return 0
        var wait = 0L
        for ((t, n) in sent) {
            used -= n
            wait = t + windowMs - now
            if (used + items <= limit) break
        }
        return maxOf(0L, wait)
    }

    /** Books a batch of [items] sent at [now]. */
    @Synchronized
    fun record(items: Int, now: Long) {
        prune(now)
        sent += now to items
    }

    /** Items booked in the window ending at [now]. */
    @Synchronized
    fun used(now: Long): Int {
        prune(now)
        return sent.sumOf { it.second }
    }

    companion object {
        /** The platform applies about 240 items a minute; no budget above it is accepted. */
        const val PLATFORM_MAX_PER_MIN = 240
        const val DEFAULT_ALONE = 200
        const val DEFAULT_WITH_RECEIVE = 150

        /** Refusals (empty = fine) for a configured budget. */
        fun refusals(name: String, perMin: Int): List<String> = buildList {
            if (perMin < 1) add("$name must be at least 1 item a minute, got $perMin")
            if (perMin >
                PLATFORM_MAX_PER_MIN
            ) {
                add("$name is $perMin items a minute; the platform applies about $PLATFORM_MAX_PER_MIN, so nothing above it goes")
            }
        }
    }
}

/** One entry of the results route: `{idempotency_key, outcome, result:{type:"move", moved, skipped, superseded, refused, refused_items}}`. */
data class MoveResultEntry(val key: String, val outcome: String, val refused: Int, val raw: String) {
    val pending: Boolean get() = outcome.equals("pending", ignoreCase = true)
    val bad: Boolean get() = outcome.equals("warning", ignoreCase = true) || outcome.equals("failed", ignoreCase = true)
}

/** The webhook results route: `GET {base}/{tenant}/{integration}/results?idempotency_key=a&idempotency_key=b` (at most [MAX_KEYS] keys a request). */
object ResultsRoute {
    const val MAX_KEYS = 40
    private val mapper = ObjectMapper()

    fun parse(body: String): List<MoveResultEntry> {
        val results = mapper.readTree(body).path("results")
        return results.map { e ->
            val res = e.path("result")
            MoveResultEntry(e.path("idempotency_key").asText(""), e.path("outcome").asText(""), res.path("refused").asInt(0), e.toString().take(400))
        }
    }

    /** The key out of a webhook 200 body (`eventKey`), or null. */
    fun eventKey(body: String?): String? = try {
        if (body.isNullOrBlank()) null else mapper.readTree(body).path("eventKey").asText("").ifBlank { null }
    } catch (e: IOException) {
        null
    }
}

/**
 * Watches the move events a run posted. Each move's webhook 200 carries an `eventKey`; every [everyMs] the keys not yet confirmed are read from
 * the results route. [check] returns a HALT reason when an entry is still `pending` more than [pendingLimitMs] after its post (or not listed
 * at all that long after), has outcome `warning` / `failed`, or reports `refused > 0`; otherwise confirmed entries are counted and dropped.
 * An unreadable route is logged and tried again later (it never halts by itself). Thread-safe.
 *
 * With [answersFile] each move gets a row `extId<TAB>eventKey<TAB>sentAt` BEFORE the post (key empty) and a second row with the key after the
 * answer: the last row of an extId wins. (Recon writes its own answers.tsv and passes null.)
 */
class MoveResultsWatch(
    private val fetch: (List<String>) -> List<MoveResultEntry>?,
    private val answersFile: Path? = null,
    private val everyMs: Long = 30_000,
    private val pendingLimitMs: Long = pendingLimitFromEnv(),
    private val onEvent: (String) -> Unit = {},
) {
    companion object {
        /** `M8TRX_MOVE_PENDING_LIMIT_S` (default 60): a move event still pending this long after its own post halts the run. */
        fun pendingLimitFromEnv(): Long = ((System.getenv("M8TRX_MOVE_PENDING_LIMIT_S")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 60.0) * 1000).toLong()
    }

    private class Sent(val extId: String, val sentAt: Long)

    private val unconfirmed = LinkedHashMap<String, Sent>()
    private val sentAtOf = HashMap<String, Long>()
    private var lastCheckAt = Long.MIN_VALUE / 2
    private var failedReads = 0
    var confirmed = 0
        private set
    var unkeyed = 0
        private set

    private fun row(extId: String, key: String, sentAt: Long) {
        val f = answersFile ?: return
        f.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(f, "$extId\t$key\t${Instant.ofEpochMilli(sentAt)}\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    @Synchronized
    fun beforeSend(extId: String, now: Long) {
        sentAtOf[extId] = now
        row(extId, "", now)
    }

    /** The webhook answered 200 with [body]; its `eventKey` joins the keys to confirm. */
    @Synchronized
    fun answered(extId: String, body: String?, now: Long) {
        val sentAt = sentAtOf.remove(extId) ?: now
        val key = ResultsRoute.eventKey(body)
        if (key == null) {
            unkeyed++
            onEvent("move $extId: the webhook answer carries no eventKey, so it cannot be confirmed on the results route")
            return
        }
        row(extId, key, sentAt)
        unconfirmed[key] = Sent(extId, sentAt)
    }

    @Synchronized
    fun outstanding(): Int = unconfirmed.size

    /** A HALT reason, or null. Reads the route at most every [everyMs] unless [force]. */
    @Synchronized
    fun check(now: Long, force: Boolean = false): String? {
        if (unconfirmed.isEmpty() || (!force && now - lastCheckAt < everyMs)) return null
        lastCheckAt = now
        val seen = HashMap<String, MoveResultEntry>()
        val readKeys = HashSet<String>()
        for (chunk in unconfirmed.keys.toList().chunked(ResultsRoute.MAX_KEYS)) {
            val got = fetch(chunk)
            if (got == null) {
                failedReads++
                onEvent("results route unreadable ($failedReads in a row); ${chunk.size} move event(s) skipped this round, trying again")
                if (failedReads == 5) onEvent("WARN: the results route has failed 5 reads in a row; move events are going unchecked")
                continue
            }
            failedReads = 0
            readKeys += chunk
            got.forEach { seen[it.key] = it }
        }
        for ((key, sent) in unconfirmed.entries.toList()) {
            if (key !in readKeys) continue // its chunk failed to read: skipped this round, never "not listed"
            val e = seen[key]
            val age = now - sent.sentAt
            when {
                e == null -> if (age > pendingLimitMs) return "move ${sent.extId} ($key): not on the results route $age ms after its post"

                e.bad -> {
                    onEvent("results ${sent.extId}: ${e.raw}")
                    return "move ${sent.extId} ($key): outcome ${e.outcome}"
                }

                e.refused > 0 -> {
                    onEvent("results ${sent.extId}: ${e.raw}")
                    return "move ${sent.extId} ($key): refused ${e.refused}"
                }

                e.pending -> if (age > pendingLimitMs) {
                    onEvent("results ${sent.extId}: ${e.raw}")
                    return "move ${sent.extId} ($key): still pending $age ms after its post (limit $pendingLimitMs ms)"
                }

                else -> {
                    confirmed++
                    unconfirmed.remove(key)
                    onEvent("results ${sent.extId}: ${e.raw}")
                }
            }
        }
        return null
    }
}
