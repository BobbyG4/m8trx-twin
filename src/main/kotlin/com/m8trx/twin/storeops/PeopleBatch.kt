package com.m8trx.twin.storeops

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.storeday.PeopleRest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * The BATCH mode of the people backfill (`M8TRX_PEOPLE_BACKFILL_MODE=batch`): the same visits through `person-sessions:batch` and
 * `impressions:batch`, at most [MAX] items a request. Per site-day: all begins, then all impressions, then all closes; days in date order;
 * one request at a time; every answer is read and every item checked before anything is ledgered.
 */
object PeopleBatch {
    const val SESSIONS_BATCH = "/api/v2/visionai/person-sessions:batch"
    const val IMPRESSIONS_BATCH = "/api/v2/visionai/impressions:batch"
    const val MAX = 100
    const val DEFAULT_CAP_PER_MIN = 60
    const val MAX_CAP_PER_MIN = 120

    /** One request: [lines] of one [kind] (person_begin | person_impression | person_close) of one store-day. */
    class Batch(val day: String, val kind: String, val lines: List<PeopleRest.Line>) {
        val path: String get() = if (kind == "person_impression") IMPRESSIONS_BATCH else SESSIONS_BATCH
    }

    /** The store-local day stamp (`yyyymmdd`) in a line id. */
    fun dayOf(l: PeopleRest.Line): String = PeopleWindow.sessionKey(l.id).substringBeforeLast('-').substringAfterLast('-')

    private val KINDS = listOf("person_begin", "person_impression", "person_close")

    /** The requests, in send order, for the lines not yet [sent]: day by day, begins then impressions then closes, [size] at most each. */
    fun batches(lines: List<PeopleRest.Line>, sent: (String) -> Boolean = { false }, size: Int = MAX): List<Batch> {
        require(size in 1..MAX) { "batch size must be 1..$MAX" }
        val out = ArrayList<Batch>()
        lines.filter { !sent(it.id) }.groupBy { dayOf(it) }.toSortedMap().forEach { (day, ls) ->
            KINDS.forEach { k -> ls.filter { it.kind == k }.chunked(size).forEach { out += Batch(day, k, it) } }
        }
        return out
    }

    /** The JSON ARRAY body of [b]: a begin is its single body plus `op`, a close is `op`, `id` (the session UUID) and `exitTime`. */
    fun body(b: Batch): ArrayNode {
        val arr = ConnectMappers.camel.createArrayNode()
        b.lines.forEach { l ->
            when (b.kind) {
                "person_begin" -> arr.add(
                    ConnectMappers.camel.createObjectNode().also {
                        it.put("op", "begin")
                        it.setAll<ObjectNode>(l.body)
                    },
                )

                "person_close" -> arr.add(
                    ConnectMappers.camel.createObjectNode().also {
                        it.put("op", "close")
                        it.put("id", l.path.substringAfterLast('/'))
                        it.put("exitTime", l.body.path("exitTime").asText())
                        listOf("gender", "heightMm", "staffBadge", "faceMask").forEach { f -> l.body.get(f)?.let { v -> it.set<JsonNode>(f, v) } }
                    },
                )

                else -> arr.add(l.body)
            }
        }
        return arr
    }

    /** What an answer says: the per-item statuses (index order) or the reason to stop. */
    class Verdict(val statuses: List<Int>, val problem: String?)

    private val json = ObjectMapper()

    /** Count must equal what was sent, failed must be 0, every item 2xx (a deduplicated item is a success). */
    fun evaluate(b: Batch, bodyText: String?): Verdict {
        val n = b.lines.size
        val a = bodyText?.let { runCatching { json.readTree(it) }.getOrNull() } ?: return Verdict(emptyList(), "the answer is not JSON")
        val results = a.path("results")
        if (!results.isArray) return Verdict(emptyList(), "the answer has no results array")
        val statuses = (0 until n).map { i -> results.firstOrNull { it.path("index").asInt(-1) == i }?.path("status")?.asInt(0) ?: 0 }
        val bad = statuses.indices.filter { statuses[it] !in 200..299 }
        val problems = ArrayList<String>()
        if (a.path("count").asInt(-1) != n) problems += "count ${a.path("count")} != sent $n"
        if (results.size() != n) problems += "results ${results.size()} != sent $n"
        if (a.path("failed").asInt(-1) != 0) problems += "failed=${a.path("failed")}"
        bad.take(5).forEach { i ->
            problems +=
                "item $i ${b.lines[i].id}: ${results.firstOrNull { it.path("index").asInt(-1) == i } ?: "missing result"}"
        }
        return Verdict(statuses, problems.takeIf { it.isNotEmpty() }?.joinToString("; "))
    }
}

/**
 * Sends [batches] in order, one request at a time. A whole-request 429 / 5xx / no answer is retried after a wait (a resend is safe: begins dedupe
 * by id, impressions by session+zone+firstLook, a second close is ignored), up to [maxAttempts]; any other 4xx halts. Each item that came back 2xx
 * is ledgered once the answer is read, so a resume never resends a confirmed item; a failed item halts the run and prints its entry.
 */
class PeopleBatchRunner(
    private val batches: List<PeopleBatch.Batch>,
    private val transport: PeopleTransport,
    private val ledger: PeopleLedger,
    private val capPerMin: Int,
    private val clock: () -> Long,
    private val sleeper: (Long) -> Unit,
    private val hold: () -> Boolean,
    private val dir: Path?,
    private val maxAttempts: Int = 5,
    private val slowMs: Long = 3_000,
) {
    enum class End { DONE, HOLD, HALTED }

    val events = ArrayList<String>()
    var haltReason: String? = null
        private set
    var requests = 0
        private set
    var itemsSent = 0
        private set
    val startedAt = ArrayList<Long>()

    init {
        require(capPerMin in 1..PeopleBatch.MAX_CAP_PER_MIN) { "batch cap is 1..${PeopleBatch.MAX_CAP_PER_MIN} requests a minute" }
    }

    private fun event(s: String) {
        val line = "${Instant.ofEpochMilli(clock())} $s"
        events += line
        println(line)
        dir?.let {
            Files.createDirectories(it)
            Files.writeString(it.resolve("batch-run.log"), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    private fun halt(s: String): End {
        haltReason = s
        event("HALT $s")
        return End.HALTED
    }

    fun run(): End {
        event("start: ${batches.size} requests, ${batches.sumOf { it.lines.size }} items, cap $capPerMin/min")
        for (b in batches) {
            if (hold()) return End.HOLD
            val text = PeopleBatch.body(b).let { ConnectMappers.camel.writeValueAsString(it) }
            var answer: PeopleAnswer? = null
            var attempt = 0
            var t0 = 0L
            while (answer == null) {
                attempt++
                t0 = clock()
                startedAt += t0
                requests++
                val a = try {
                    transport.call("POST", b.path, text)
                } catch (e: IOException) {
                    null
                }
                val retry = a == null || a.status == 429 || a.status >= 500
                if (a != null && a.status in 200..299) {
                    answer = a
                } else if (retry && attempt < maxAttempts) {
                    val wait = a?.retryAfterS?.times(1000) ?: (5_000L shl (attempt - 1)).coerceAtMost(60_000L)
                    event("${b.day} ${b.kind}: ${a?.status ?: "no answer"}; resending in $wait ms (attempt $attempt)")
                    sleeper(wait)
                } else {
                    return halt("${b.day} ${b.kind}: HTTP ${a?.status ?: "no answer"} ${a?.body?.take(300).orEmpty()}")
                }
            }
            val v = PeopleBatch.evaluate(b, answer.body)
            // every item the answer confirms is ledgered, even when the request halts the run
            v.statuses.forEachIndexed { i, st ->
                if (st in 200..299) {
                    val l = b.lines[i]
                    try {
                        ledger.record(l.id, l.kind, st, null, Instant.ofEpochMilli(clock()))
                        itemsSent++
                    } catch (e: IOException) {
                        return halt("ledger write failed for ${l.id}: ${e.message}; the item WAS accepted")
                    }
                }
            }
            if (v.problem != null) return halt("${b.day} ${b.kind}: ${v.problem}")
            val took = clock() - t0
            val gap = 60_000L / capPerMin
            // at most MAX_CAP_PER_MIN requests a minute, and when a batch is slow the next one waits as long again
            val wait = maxOf(gap - took, 0L) + if (took > slowMs) took else 0L
            if (wait > 0) sleeper(wait)
        }
        event("end: DONE, $requests requests, $itemsSent items")
        return End.DONE
    }
}
