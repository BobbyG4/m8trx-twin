package com.m8trx.twin.storeops

import com.fasterxml.jackson.databind.JsonNode
import com.m8trx.twin.connect.ConnectConfig
import com.m8trx.twin.connect.RestockMoves
import com.m8trx.twin.connect.model.ConnectMappers
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate

/**
 * The live side of the daily delivery, kept apart from the pure plan ([DailyDelivery]): the results route (services #535, connect API § 8.3),
 * the move verifier that books `placements.csv` only for units the platform confirms moved, the sender wrapper that records every answer's
 * `eventKey` BEFORE the sent-ledger marks the item, and the refusals. Everything here is file-only or goes through an injected reader, so
 * `dailyDeliverySelfTest` proves it with fakes.
 */
class ResultEntry(
    val key: String,
    val outcome: String,
    val moved: Int,
    val skipped: Int,
    val superseded: Int,
    val refused: Int,
    /** The whole entry as the platform sent it, printed when the site halts. */
    val raw: String,
) {
    companion object {
        /** `{"results":[{idempotency_key, outcome, result:{moved, skipped, superseded, refused, refused_items[]}}]}`; entries without a key are dropped. */
        fun parse(body: String): List<ResultEntry> {
            val root = ConnectMappers.snake.readTree(body)
            val arr = if (root.isArray) root else root.path("results")
            return arr.mapNotNull { e ->
                val key = e.path("idempotency_key").asText("").ifBlank { null } ?: return@mapNotNull null
                val r = e.path("result")
                ResultEntry(
                    key,
                    e.path("outcome").asText("").lowercase(),
                    r.path("moved").asInt(0),
                    r.path("skipped").asInt(0),
                    r.path("superseded").asInt(0),
                    r.path("refused").asInt(0),
                    e.toString(),
                )
            }
        }
    }
}

/** Reads the outcome of up to [MAX_KEYS] posted events. Null means the read failed (the verifier tries again later). */
fun interface ResultsReader {
    fun read(keys: List<String>): List<ResultEntry>?

    companion object {
        const val MAX_KEYS = 40

        fun url(config: ConnectConfig, keys: List<String>): String =
            "${config.webhookBase}/${config.requireTenantId()}/${config.requireIntegrationSlug()}/results?" +
                keys.joinToString("&") { "idempotency_key=$it" }
    }
}

/**
 * Follows every move the sender posted to its outcome. A tracked move is read [minAgeMs] after its post, [ResultsReader.MAX_KEYS] keys to a request:
 *  - `pending` / `not_found` / no entry: asked again later; still not settled [timeoutMs] after the post, the site halts;
 *  - any `refused > 0`, or an outcome of `warning`, `failed`, `quarantined`, `dropped` or `skipped`: the site HALTS ([bad]), the entry printed;
 *  - `processed` with every unit moved (or already at the destination: `skipped`) and none `superseded`: the batch is BOOKED ([onConfirmed]);
 *  - `processed` otherwise (a unit superseded): nothing is booked, the batch is listed as unconfirmed, the run goes on.
 */
class MoveVerifier(
    private val reader: ResultsReader,
    private val onConfirmed: (SendItem) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val onEvent: (String) -> Unit = {},
    private val minAgeMs: Long = 15_000,
    private val timeoutMs: Long = 600_000,
    /** Called with the extId when a move leaves the pending set verified (booked or listed unconfirmed): the box's `verified.tsv`. */
    private val onSettled: (String, String) -> Unit = { _, _ -> },
) {
    private class Tracked(val item: SendItem, val key: String, val postedAt: Long)

    private val pending = LinkedHashMap<String, Tracked>()

    @Volatile
    var bad: String? = null
        private set
    var confirmedBatches = 0
        private set
    var confirmedUnits = 0
        private set
    val unconfirmed = ArrayList<String>()

    @Synchronized
    fun track(item: SendItem, eventKey: String) {
        pending.putIfAbsent(item.extId, Tracked(item, eventKey, now()))
    }

    @Synchronized
    fun fail(reason: String) {
        if (bad == null) bad = reason
    }

    @get:Synchronized
    val pendingCount: Int get() = pending.size

    /** One round: reads every tracked move that is old enough. */
    @Synchronized
    fun poll() {
        val t = now()
        val due = pending.values.filter { t - it.postedAt >= minAgeMs }
        due.chunked(ResultsReader.MAX_KEYS).forEach { chunk ->
            val entries = reader.read(chunk.map { it.key })?.associateBy { it.key } ?: return@forEach
            chunk.forEach { tr -> settle(tr, entries[tr.key], t) }
        }
    }

    private fun settle(tr: Tracked, e: ResultEntry?, t: Long) {
        val n = tr.item.epcs.size
        if (e == null || e.outcome == "pending" || e.outcome == "not_found" || e.outcome.isEmpty()) {
            if (t - tr.postedAt > timeoutMs) {
                fail("${tr.item.extId}: still ${e?.outcome ?: "unanswered"} ${(t - tr.postedAt) / 1000} s after the post (event key ${tr.key})")
            }
            return
        }
        if (e.refused > 0 || e.outcome in HALTING) {
            fail("${tr.item.extId}: outcome ${e.outcome}, refused ${e.refused}: ${e.raw.take(600)}")
            return
        }
        pending.remove(tr.item.extId)
        if (e.outcome == "processed" && e.moved + e.skipped == n && e.superseded == 0) {
            onConfirmed(tr.item)
            confirmedBatches++
            confirmedUnits += n
            onSettled(tr.item.extId, "booked")
        } else {
            unconfirmed += "${tr.item.extId}: ${e.raw.take(300)}"
            onEvent("NOT BOOKED ${tr.item.extId}: moved ${e.moved} skipped ${e.skipped} superseded ${e.superseded} of $n")
            onSettled(tr.item.extId, "unconfirmed")
        }
    }

    /** Polls until nothing is pending, [bad] is set, or [maxWaitMs] has passed; true when everything settled. */
    fun drain(maxWaitMs: Long, sleep: (Long) -> Unit = Thread::sleep): Boolean {
        val end = now() + maxWaitMs
        while (true) {
            poll()
            if (bad != null) return false
            if (pendingCount == 0) return true
            if (now() >= end) return false
            sleep(2_000)
        }
    }

    companion object {
        val HALTING = setOf("warning", "failed", "quarantined", "dropped", "skipped")
    }
}

/** What the delivery books: receives into the stock file as the reset does, moves NEVER on acceptance (the [MoveVerifier] books them on confirmation). */
class DeliveryBooks(private val receives: RunBooks) : RunBooks {
    override fun record(item: SendItem) {
        if (item.kind == "receive") receives.record(item)
    }

    companion object {
        /** A confirmed move: `placements.csv` gets the destination (the rack for a back-room move, else the floor fixture code). */
        fun bookMove(placementsFile: Path, item: SendItem, zoneCode: Map<String, String>, date: LocalDate) {
            RestockMoves.appendPlacements(placementsFile, item.epcs, ResetBooks.destOf(item.payload, zoneCode), date)
        }
    }
}

/**
 * Wraps the live sender. For a move, `answers.tsv` (extId, site, eventKey, receivedAt) gets the row BEFORE the post with an empty key, and
 * the same row again with the key once the answer is in (the last row of an extId wins); the sent-ledger marks the item only after
 * [send] returns, so no accepted move is ever without its row. A send answered after more than [slowMs] sets [slow]: the server's queue
 * is full and is making the sender wait, so the site halts.
 */
class DeliverySender(
    private val inner: OpsSender,
    private val site: String,
    private val answersFile: Path,
    private val slowMs: Long = ReconSendMain.SLOW_ANSWER_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : OpsSender {
    @Volatile
    var slow: String? = null
        private set

    private val lock = Any()

    fun appendAnswer(extId: String, key: String, receivedAt: String) = synchronized(lock) {
        answersFile.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(answersFile, "$extId\t$site\t$key\t$receivedAt\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    override fun health() = inner.health()

    override fun dropToken() = inner.dropToken()

    override fun send(item: SendItem): Outcome {
        if (item.kind != "move") return inner.send(item)
        appendAnswer(item.extId, "", "")
        val t0 = clockMs()
        val o = inner.send(item)
        val ms = clockMs() - t0
        if (ms > slowMs && slow == null) slow = "${item.extId} answered after $ms ms (limit $slowMs ms)"
        return o
    }

    companion object {
        /** The extIds with a key in an `answers.tsv` (the last row of an id wins), id to key. */
        fun readKeys(file: Path): Map<String, String> {
            if (!Files.exists(file)) return emptyMap()
            val out = LinkedHashMap<String, String>()
            Files.readAllLines(file).forEach { l ->
                val c = l.split('\t')
                if (c.size >= 3 && c[0].isNotBlank()) out[c[0]] = c[2]
            }
            return out
        }
    }
}

/** The key of a webhook 200: `{"accepted":true,"eventKey":"<64 hex>","receivedAt":"..."}`; blanks when the body does not carry them. */
object EventKey {
    fun of(body: String?): Pair<String, String> {
        val n: JsonNode? = runCatching { ConnectMappers.snake.readTree(body ?: "") }.getOrNull()
        return (n?.path("eventKey")?.asText("").orEmpty()) to (n?.path("receivedAt")?.asText("").orEmpty())
    }
}

/** Why a live delivery is refused (exit 3), checked before anything is sent. */
object DeliveryGate {
    fun holdFile(env: (String) -> String?): Path =
        Path.of(env("M8TRX_DELIVERY_HOLD_FILE") ?: "${env("M8TRX_OPS_DIR") ?: "/opt/m8trx/twin-ops"}/delivery-HOLD")

    /** All reasons, collected. [sites] is the run's set; [confirm] must equal it. */
    fun check(
        live: Boolean,
        sites: Set<String>,
        confirm: String?,
        env: (String) -> String?,
        ownerFile: Path,
        moveChunk: Int,
        moveSpacingMs: Long,
    ): List<String> {
        if (!live) return emptyList()
        val r = ArrayList<String>()
        if (sites.isEmpty()) r += "a live delivery names its sites: M8TRX_DELIVERY_SITES=<csv>"
        val confirmed = confirm?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        if (confirmed !=
            sites
        ) {
            r += "a live delivery needs the second key M8TRX_DELIVERY_CONFIRM=${sites.sorted().joinToString(",")} (got '${confirm ?: ""}')"
        }
        listOf("M8TRX_TWIN_WEBHOOK_KEY", "M8TRX_TENANT_ID", "M8TRX_CONNECT_INTEGRATION_SLUG", "M8TRX_TWIN_SERVICE_BEARER").forEach {
            if (env(it).isNullOrBlank()) r += "$it is not set"
        }
        try {
            val owners = StoreOwnership.load(ownerFile)
            sites.sorted().forEach { s -> owners.refusal(s)?.let { r += "$it (owner file $ownerFile)" } }
        } catch (e: IllegalArgumentException) {
            r += e.message ?: "owner file unreadable"
        }
        if (moveChunk > DailyDelivery.MOVE_CHUNK) r += "a live move batch carries at most ${DailyDelivery.MOVE_CHUNK} units, got $moveChunk"
        if (moveSpacingMs < DailyDelivery.MOVE_SPACING_MS) {
            r +=
                "a live move batch goes no faster than every ${DailyDelivery.MOVE_SPACING_MS / 1000.0} s (200 items a minute), got ${moveSpacingMs / 1000.0} s"
        }
        return r
    }

    /** The store's opening on [date] (local); null when it is closed that weekday. A live run refuses to start once [nowMs] is past it. */
    fun openRefusal(slug: String, date: LocalDate, open: java.time.LocalTime?, zone: java.time.ZoneId, nowMs: Long, allowOpen: Boolean): String? {
        if (allowOpen || open == null) return null
        val openMs = date.atTime(open).atZone(zone).toInstant().toEpochMilli()
        return if (nowMs >= openMs) {
            "$slug opens at $open ($zone) on $date and it is already past that: the delivery runs BEFORE opening (M8TRX_DELIVERY_ALLOW_OPEN=true overrides)"
        } else {
            null
        }
    }
}
