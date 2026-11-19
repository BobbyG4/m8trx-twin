package com.m8trx.twin.storeops

import com.fasterxml.jackson.databind.JsonNode
import com.m8trx.twin.connect.ConnectClient
import com.m8trx.twin.connect.ConnectConfig
import com.m8trx.twin.connect.ReceiveStock
import com.m8trx.twin.connect.RestockMoves
import com.m8trx.twin.connect.Sgtin96
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.bearer.ReadCaps
import com.m8trx.twin.connect.model.bearer.SpatialIdentityRequest
import com.m8trx.twin.connect.model.bearer.SpatialIdentityResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate

/*
 * The LIVE path of the Denver reset (`M8TRX_RESET_LIVE=true` + `M8TRX_RESET_CONFIRM=<slug>`): the schedule that [StoreDayReset] plans,
 * run through [LiveSender], the only class that talks to a server. This file holds the pieces around the sender, all of them pure or
 * file-only so `storeOpsSelfTest` proves them with a fake [OpsSender]: the gates ([ResetGate]), the concurrent lanes
 * ([ResetLiveRunner]), the box ledger writer ([ResetBooks]) and the back-room zone map.
 */

/** A clock the runner sleeps on; the tests run a virtual one. */
interface ResetClock {
    fun now(): Long

    fun sleep(ms: Long)
}

object SystemResetClock : ResetClock {
    override fun now(): Long = System.currentTimeMillis()

    override fun sleep(ms: Long) = Thread.sleep(ms)
}

class ResetLiveParams(
    /** 1..4 concurrent receive requests ([ResetGate.MAX_LANES]). */
    val receiveLanes: Int,
    /** The move lane's pace: one batch per this long (A16: 2 s). */
    val moveSpacingMs: Long,
    /** Between the old-directive end(s) and the new plan. */
    val directiveSpacingMs: Long,
    /** When the health gate says not ready, ask again after this long. */
    val healthPollMs: Long,
    /** A move lane with nothing runnable (its receives are not accepted yet) looks again after this long. */
    val idlePollMs: Long,
    /** The store's local midnight of the reset day: the gated items (old end, new plan) do not go before it. */
    val midnightMs: Long,
    val progressEveryMs: Long = 5_000,
    /**
     * `M8TRX_RESET_HOLD_DIRECTIVES=true`: the directive steps (old end, new plan) are NOT sent; the run ends after receives and moves.
     * 2026-11-19 (server lane): the webhook route refuses bodies over 256 KiB and a second delivery under one id REPLACES the targets, so
     * a 6,365-target period plan cannot go as one body; both plan items stay out until Bob picks the form (the old plan is ended only in
     * the same step that lands the new one, so Denver is never without a plan in force).
     */
    val holdDirectives: Boolean = false,
    /** The move lane re-checks health at least this often (receives and directives check before every request). */
    val moveHealthEveryMs: Long = 30_000,
    /** A health answer slower than this is NOT ready (the server lane: GET health over 1 s means the event loops are busy). */
    val healthMaxMs: Long = 1_000,
    /** A tag that came back `internal_error` is re-sent in a retry request no sooner than this. */
    val retryDelayMs: Long = 30_000,
    /** The status line (in flight, tags, move batches, health latency, ETA) is logged this often. */
    val statusEveryMs: Long = 60_000,
) {
    init {
        require(receiveLanes in 1..ResetGate.MAX_LANES) { "M8TRX_RESET_RECEIVE_LANES must be 1..${ResetGate.MAX_LANES}, got $receiveLanes" }
    }
}

/** A snapshot of the run, written to `progress.json` and printed. */
class ResetProgress(
    val receivesDone: Int,
    val receivesTotal: Int,
    val receiveUnitsDone: Int,
    val receiveUnitsTotal: Int,
    val movesDone: Int,
    val movesTotal: Int,
    val tailDone: Int,
    val tailTotal: Int,
    val state: String,
    val atMs: Long,
)

/**
 * The box ledger, appended to as each item is accepted, in the formats the other steps write (never rewritten wholesale):
 *  - receive: `receive/received-stock.csv` (`epc,ean,slug,fixture,date`, [ReceiveStock.appendStock]; the fixture is the BR rack the twin books
 *    the unit to) and the receive step's EPC ledger `receive/receive-reset-<yyyymmdd>.sent` (a name the hourly receive plan never reads);
 *  - move: `moves/placements.csv` (`epc,fixture,date`, [RestockMoves.appendPlacements]; the destination fixture code, or the BR rack for the
 *    back room), which [com.m8trx.twin.ops.StoreStock.load] reads as overriding the unit's fixture.
 * After the run, StoreStock.load(chain, state, slug) therefore equals the plan's post-reset placement of every unit.
 */
class ResetBooks(private val stateDir: Path, private val slug: String, private val date: LocalDate, private val rackOf: (String) -> String) {
    private val stockFile = ReceiveStock.stockFile(stateDir.resolve("receive"))
    private val epcLedger = stateDir.resolve("receive/receive-reset-${date.toString().replace("-", "")}.sent")
    private val placementsFile = stateDir.resolve("moves/placements.csv")
    private val known = HashSet(ReceiveStock.readStock(stockFile).map { it.epc })

    fun record(item: SendItem) {
        when (item.kind) {
            "receive" -> {
                val rows = item.epcs.filter { known.add(it) }.map {
                    val ean = Sgtin96.decodeEan(it)
                    ReceiveStock.ReceivedRow(it, ean, slug, rackOf(ean), date)
                }
                if (rows.isNotEmpty()) ReceiveStock.appendStock(stockFile, epcLedger, rows)
            }

            "move" -> RestockMoves.appendPlacements(placementsFile, item.epcs, destOf(item.payload), date)
        }
    }

    companion object {
        /** A move's destination as a ledger fixture: the fixture code, or the rack of a `@backroom_zone:BR-nn` zone. */
        fun destOf(payload: JsonNode): String = payload.path("to_fixture_code").asText("").ifBlank { null }
            ?: payload.path("to_zone_id").asText("").removePrefix(Placeholders.ZONE_PREFIX).ifBlank { null }
            ?: error("a move with no destination: $payload")
    }
}

/** Why a live reset is refused (exit 3), checked before anything is sent. */
object ResetGate {
    const val MAX_LANES = 4

    /** The reset's own hold file: `M8TRX_RESET_HOLD_FILE`, default `$M8TRX_OPS_DIR/reset-HOLD`. Never `$OPS/HOLD`: the hourly pass and the live plane share that one. */
    fun holdFile(env: (String) -> String?): Path =
        Path.of(env("M8TRX_RESET_HOLD_FILE") ?: "${env("M8TRX_OPS_DIR") ?: "/opt/m8trx/twin-ops"}/reset-HOLD")

    /** A live run refuses a move chunk over 25 or a move spacing under 3.5 s (the server answers a move batch before it processes it). */
    fun paceRefusals(moveChunk: Int, moveSpacingMs: Long): List<String> = buildList {
        if (moveChunk > 25) add("a live move batch carries at most 25 tags, got $moveChunk (M8TRX_RESET_MOVE_CHUNK)")
        if (moveSpacingMs <
            3_500
        ) {
            add("a live move batch goes no faster than every 3.5 s, got ${moveSpacingMs / 1000.0} s (M8TRX_RESET_MOVE_EVERY_S)")
        }
    }

    /** At most 50 tags a receive request: a 200-tag request got a 504. */
    const val MAX_RECEIVE_TAGS = 50

    /** The ext ids of receive requests over [MAX_RECEIVE_TAGS]; any makes the run refuse. */
    fun oversizeReceives(items: List<SendItem>): List<String> =
        items.filter { it.kind == "receive" && it.epcs.size > MAX_RECEIVE_TAGS }.map { it.extId }

    class Verdict(val reasons: List<String>) {
        val refused: Boolean get() = reasons.isNotEmpty()
    }

    /**
     * Every gate, all reasons collected:
     *  - the second key `M8TRX_RESET_CONFIRM` = the slug;
     *  - the owner file lists the slug as `storeday` ([StoreOwnership]): the hourly pass is not writing the store;
     *  - the receive-probe result file records a pass ([ReceiveProbe.refusal]);
     *  - the schedule ends the old directive (A14): [endSteps] > 0;
     *  - the spot count: `M8TRX_RESET_SPOTCOUNT_ACK` holds the operator's note (the platform read is not wired, see [StoreDayReset]);
     *  - the receive bearer `M8TRX_TWIN_SERVICE_BEARER` is set, and the lane count is 1..4.
     */
    fun check(slug: String, env: (String) -> String?, stateDir: Path, ownerFile: Path, endSteps: Int): Verdict {
        val r = ArrayList<String>()
        if (env("M8TRX_RESET_CONFIRM") != slug) r += "a live reset needs the second key M8TRX_RESET_CONFIRM=$slug"
        try {
            StoreOwnership.load(ownerFile).refusal(slug)?.let { r += "$it (owner file $ownerFile)" }
        } catch (e: IllegalArgumentException) {
            r += e.message ?: "owner file unreadable"
        }
        ReceiveProbe.refusal(ReceiveProbe.resultFile(stateDir, slug))?.let { r += it }
        if (endSteps == 0) {
            r += "no old directive: the schedule has no end step (A14). Produce ${StoreDayReset.defaultOldFile(stateDir, slug)} " +
                "(./gradlew storeDayResetOldDirective) and plan again"
        }
        if (env("M8TRX_RESET_SPOTCOUNT_ACK").isNullOrBlank() && env("M8TRX_OPS_PASSWORD").isNullOrBlank() &&
            env("M8TRX_AUDIT_PASSWORD").isNullOrBlank()
        ) {
            r += "the spot count needs the store-manager login for its platform read (M8TRX_OPS_PASSWORD), or, as the fallback, " +
                "M8TRX_RESET_SPOTCOUNT_ACK=\"<who counted by hand, which fixtures, how many disagreed>\""
        }
        if (env("M8TRX_TWIN_SERVICE_BEARER").isNullOrBlank()) r += "M8TRX_TWIN_SERVICE_BEARER is not set (the receive key, inventory:create)"
        val lanes = env("M8TRX_RESET_RECEIVE_LANES")?.toIntOrNull()
        if (env("M8TRX_RESET_RECEIVE_LANES") != null && (lanes == null || lanes !in 1..MAX_LANES)) {
            r += "M8TRX_RESET_RECEIVE_LANES must be 1..$MAX_LANES, got '${env("M8TRX_RESET_RECEIVE_LANES")}'"
        }
        return Verdict(r)
    }
}

/** The back-room rack codes the reset's moves need, mapped to the stockroom space's fixture-zone ids ([Placeholders.ZONE_PREFIX]). */
object BackroomZones {
    private val CODE = Regex("(?i)^BR-(\\d+)$")
    private val NAME = Regex("(?i)back\\s?room\\s+fixture\\s+(\\d+)")

    fun overrideFile(stateDir: Path, slug: String): Path = stateDir.resolve("reset/backroom-zones-$slug.csv")

    /** `BR-01 -> zone id` from one stockroom space of a `/spatial/identity` answer: by zone `code` BR-nn, else by name "Backroom Fixture n". */
    fun fromIdentity(resp: SpatialIdentityResponse, spaceId: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        resp.sites.flatMap { it.spaces }.filter { it.spaceId == spaceId }.flatMap { it.zones }.forEach { z ->
            val rack = z.code?.let { c -> CODE.matchEntire(c)?.let { "BR-%02d".format(it.groupValues[1].toInt()) } }
                ?: z.name?.let { n -> NAME.find(n)?.let { "BR-%02d".format(it.groupValues[1].toInt()) } }
            if (rack != null) out.putIfAbsent(rack, z.zoneId)
        }
        return out
    }

    /** The operator's own map, `BR-01,<zone uuid>` per line; wins over the read. */
    fun readOverride(file: Path): Map<String, String> = if (!Files.exists(file)) {
        emptyMap()
    } else {
        Files.readAllLines(file).map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() && !it.startsWith("rack") }.associate {
            val c = it.split(",").map(String::trim)
            require(c.size == 2) { "backroom zone override line must be 'BR-nn,<zone id>': '$it'" }
            c[0].uppercase() to c[1]
        }
    }

    /** One READ (`POST /spatial/identity`, inventory:read, key M8TRX_TWIN_BEARER) for the stockroom space; the override file wins. */
    fun resolve(slug: String, spaceId: String, stateDir: Path): Map<String, String> {
        val override = readOverride(overrideFile(stateDir, slug))
        val client = ConnectClient(ConnectConfig.fromEnv())
        val read = when (
            val r = client.spatialIdentity(
                SpatialIdentityRequest(siteRef = slug, spaceRef = spaceId, includeZones = true, limit = ReadCaps.ZONES_MAX),
            )
        ) {
            is ConnectResponse.Ok -> fromIdentity(client.mapper.readValue(r.rawBody, SpatialIdentityResponse::class.java), spaceId)
            is ConnectResponse.Err -> emptyMap()
        }
        return read + override
    }

    /** The racks the schedule's back-room moves name that have no zone id; empty means every move can resolve. */
    fun missing(items: List<SendItem>, zones: Map<String, String>): List<String> =
        items.filter { it.kind == "move" }.map { ResetBooks.destOf(it.payload) }.filter { it.startsWith("BR-") && it !in zones }.distinct().sorted()
}

/**
 * The receive-lane count in force, re-read on every call: the number in [file] (`reset-lanes`) when present and valid; no file means
 * [default] (the env value). A file that is garbled, empty or half-written keeps the LAST VALID value, never the default. Clamped to 1..4.
 */
class LanesSource(private val file: Path, default: Int) {
    private val envDefault = default.coerceIn(1, ResetGate.MAX_LANES)

    @Volatile
    private var last = envDefault

    fun current(): Int {
        try {
            if (!Files.exists(file)) {
                last = envDefault
            } else {
                Files.readString(file).trim().lineSequence().firstOrNull()?.trim()?.toIntOrNull()?.let { last = it.coerceIn(1, ResetGate.MAX_LANES) }
            }
        } catch (e: java.io.IOException) {
            // unreadable right now: keep the last valid value
        }
        return last
    }
}

/** The frozen plan of a live run: once a live run has started, resumes read THIS, never re-plan from the (now changing) ledger. */
object ResetLivePlan {
    fun markerFile(out: Path): Path = out.resolve("live-started.json")

    fun sentLedgerFile(out: Path): Path = out.resolve("sent-ledger.tsv")

    fun rankOf(kind: String): Int = when (kind) {
        "receive" -> 0
        "move" -> 1
        else -> 2
    }

    fun load(out: Path): List<SendItem> = Files.readAllLines(out.resolve("schedule.jsonl")).filter { it.isNotBlank() }.map { line ->
        val n = ConnectMappers.snake.readTree(line)
        val kind = n.path("kind").asText()
        val payload = n.path("payload")
        SendItem(
            n.path("seq").asInt(),
            n.path("due_offset_ms").asLong(),
            rankOf(kind),
            kind,
            n.path("ext_id").asText(),
            payload,
            SendItem.epcsOf(kind, payload),
            n.path("after_local_midnight").asBoolean(false),
        )
    }

    fun writeAtomic(file: Path, text: String) {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(tmp, text)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    }

    fun progressJson(p: ResetProgress): String {
        val n = ConnectMappers.snake.createObjectNode()
        n.put("state", p.state)
        n.put("at", Instant.ofEpochMilli(p.atMs).toString())
        n.put("receive_requests_done", p.receivesDone)
        n.put("receive_requests_total", p.receivesTotal)
        n.put("receive_units_done", p.receiveUnitsDone)
        n.put("receive_units_total", p.receiveUnitsTotal)
        n.put("move_batches_done", p.movesDone)
        n.put("move_batches_total", p.movesTotal)
        n.put("directive_steps_done", p.tailDone)
        n.put("directive_steps_total", p.tailTotal)
        return ConnectMappers.snake.writerWithDefaultPrettyPrinter().writeValueAsString(n) + "\n"
    }
}

/**
 * The 20-fixture spot count (brief § 1 step 1, platform half): the ledger's units on the sampled fixtures against where the PLATFORM says
 * the units are ([com.m8trx.twin.ops.StoreStock.fromPlatform], a Hasura read as the store manager). A unit counts as disagreeing when it is
 * on the fixture in one record and not in the other; MORE than [LIMIT] of the sampled ledger units disagreeing STOPS the run before any send.
 */
object SpotCount {
    const val LIMIT = 0.02

    class Verdict(val sampledUnits: Int, val disagreeing: Int, val perFixture: List<String>) {
        val rate: Double get() = disagreeing.toDouble() / maxOf(1, sampledUnits)
        val stop: Boolean get() = rate > LIMIT

        fun summary(): String = "spot count: $disagreeing of $sampledUnits sampled ledger units disagree with the platform " +
            "(${"%.2f".format(rate * 100)} %, limit ${"%.0f".format(LIMIT * 100)} %) -> ${if (stop) "STOP" else "ok"}"
    }

    fun evaluate(ledger: com.m8trx.twin.ops.StoreStock, platform: com.m8trx.twin.ops.StoreStock, fixtures: List<String>): Verdict {
        var sampled = 0
        var bad = 0
        val lines = ArrayList<String>()
        fixtures.forEach { f ->
            val l = ledger.byFixture[f].orEmpty().toSet()
            val p = platform.byFixture[f].orEmpty().toSet()
            val d = (l - p).size + (p - l).size
            sampled += l.size
            bad += d
            lines += "  - $f: ledger ${l.size}, platform ${p.size}, disagreeing $d"
        }
        return Verdict(sampled, bad, lines)
    }

    /** The same 20 fixtures the report lists ([StoreDayReset.spotCountDesign]). */
    fun sample(slug: String, ledger: com.m8trx.twin.ops.StoreStock, seed: Long, k: Int = 20): List<String> =
        ledger.wall.filterValues { it.isNotEmpty() }.keys.sorted()
            .sortedBy { com.m8trx.twin.history.HistoryCsv.derive(seed, slug, "spot-count", it) }.take(k).sorted()
}
