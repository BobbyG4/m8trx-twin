package com.m8trx.twin.storeops

import com.fasterxml.jackson.databind.JsonNode
import com.m8trx.twin.connect.ConnectClient
import com.m8trx.twin.connect.ConnectConfig
import com.m8trx.twin.connect.ReceiveStock
import com.m8trx.twin.connect.RestockMoves
import com.m8trx.twin.connect.Sgtin96
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.bearer.ItemDetail
import com.m8trx.twin.connect.model.bearer.ItemDetailsRequest
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
    /** The move lane's pace: one batch per this long (A16: 2 s). With [moveItemsPerMin] > 0 it is only a FLOOR between batches (0 = none). */
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
     * Move pace as an item budget (rolling 60 s), 0 = off (fixed [moveSpacingMs] between batches). [moveItemsPerMinWithReceive] applies while any
     * receive is still due, retrying or in flight; [moveItemsPerMin] once none is. The platform applies about 240 items a minute.
     */
    val moveItemsPerMin: Int = 0,
    val moveItemsPerMinWithReceive: Int = moveItemsPerMin,
    /** A move answered slower than this halts the run (the platform's queue is full and it is making the sender wait). */
    val slowAnswerMs: Long = 10_000,
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
fun interface RunBooks {
    fun record(item: SendItem)
}

class ResetBooks(
    private val stateDir: Path,
    private val slug: String,
    private val date: LocalDate,
    /** Zone id to fixture code (the keys export's floor zones), to book a `to_zone_id` move at its fixture code. */
    private val zoneCode: Map<String, String> = emptyMap(),
    /** The receive EPC ledger's name prefix: `receive-reset` for the reset, `receive-delivery` for the daily delivery (never a name the hourly plan reads). */
    ledgerPrefix: String = "receive-reset",
    private val rackOf: (String) -> String,
) : RunBooks {
    private val stockFile = ReceiveStock.stockFile(stateDir.resolve("receive"))
    private val epcLedger = stateDir.resolve("receive/$ledgerPrefix-${date.toString().replace("-", "")}.sent")
    private val placementsFile = stateDir.resolve("moves/placements.csv")
    private val known = HashSet(ReceiveStock.readStock(stockFile).map { it.epc })

    override fun record(item: SendItem) {
        when (item.kind) {
            "receive" -> {
                val rows = item.epcs.filter { known.add(it) }.map {
                    val ean = Sgtin96.decodeEan(it)
                    ReceiveStock.ReceivedRow(it, ean, slug, rackOf(ean), date)
                }
                if (rows.isNotEmpty()) ReceiveStock.appendStock(stockFile, epcLedger, rows)
            }

            "move" -> RestockMoves.appendPlacements(placementsFile, item.epcs, destOf(item.payload, zoneCode), date)
        }
    }

    companion object {
        /** A move's destination as a ledger fixture: the rack of a `@backroom_zone:BR-nn` zone, a floor zone id through [zoneCode], or a fixture code. */
        fun destOf(payload: JsonNode, zoneCode: Map<String, String> = emptyMap()): String {
            payload.path("to_fixture_code").asText("").ifBlank { null }?.let { return it }
            val z = payload.path("to_zone_id").asText("").ifBlank { null } ?: error("a move with no destination: $payload")
            return if (z.startsWith(Placeholders.ZONE_PREFIX)) {
                z.removePrefix(Placeholders.ZONE_PREFIX)
            } else {
                zoneCode[z]
                    ?: error("a move to unknown zone $z")
            }
        }
    }
}

/** Why a live reset is refused (exit 3), checked before anything is sent. */
object ResetGate {
    const val MAX_LANES = 4

    /** The reset's own hold file: `M8TRX_RESET_HOLD_FILE`, default `$M8TRX_OPS_DIR/reset-HOLD`. Never `$OPS/HOLD`: the hourly pass and the live plane share that one. */
    fun holdFile(env: (String) -> String?): Path =
        Path.of(env("M8TRX_RESET_HOLD_FILE") ?: "${env("M8TRX_OPS_DIR") ?: "/opt/m8trx/twin-ops"}/reset-HOLD")

    /** A live run refuses a move chunk over 25 or a move spacing under 3.5 s (the server answers a move batch before it processes it). */
    fun paceRefusals(moveChunk: Int, moveSpacingMs: Long, moveItemsPerMin: Int = 0, moveItemsPerMinWithReceive: Int = 0): List<String> = buildList {
        if (moveChunk > 25) add("a live move batch carries at most 25 tags, got $moveChunk (M8TRX_RESET_MOVE_CHUNK)")
        if (moveItemsPerMin > 0 || moveItemsPerMinWithReceive > 0) {
            // the item budget is the guard: refuse one above what the platform applies (a floor between batches is no longer checked)
            addAll(ItemBudget.refusals("M8TRX_RESET_MOVE_ITEMS_PER_MIN", moveItemsPerMin))
            addAll(ItemBudget.refusals("M8TRX_RESET_MOVE_ITEMS_PER_MIN_WITH_RECEIVE", moveItemsPerMinWithReceive))
        } else if (moveSpacingMs < 3_500) {
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
     *  - (the existence PRE-check [ExistenceCheck] is run by [ResetLiveMain] after these, from the bearer read; it is not an env gate);
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

    /** One READ (`POST /spatial/identity`, inventory:read, key M8TRX_TWIN_BEARER) of the whole site, every space with its zones; null when it fails. */
    fun fetchIdentity(slug: String): SpatialIdentityResponse? {
        val client = ConnectClient(ConnectConfig.fromEnv())
        return when (val r = client.spatialIdentity(SpatialIdentityRequest(siteRef = slug, includeZones = true, limit = ReadCaps.ZONES_MAX))) {
            is ConnectResponse.Ok -> client.mapper.readValue(r.rawBody, SpatialIdentityResponse::class.java)
            is ConnectResponse.Err -> null
        }
    }

    /** The rack code to zone id map: the identity read for the stockroom space, with the operator's override file winning. */
    fun resolve(identity: SpatialIdentityResponse?, spaceId: String, override: Map<String, String>): Map<String, String> =
        (identity?.let { fromIdentity(it, spaceId) } ?: emptyMap()) + override

    /** The back-room racks a move batch names (`@backroom_zone:BR-nn`); floor moves carry a plain zone id. */
    fun rackOf(payload: JsonNode): String? = payload.path("to_zone_id").asText("").takeIf { it.startsWith(Placeholders.ZONE_PREFIX) }
        ?.removePrefix(Placeholders.ZONE_PREFIX)

    /** The racks the schedule's back-room moves name that have no zone id; empty means every move can resolve. */
    fun missing(items: List<SendItem>, zones: Map<String, String>): List<String> =
        items.filter { it.kind == "move" }.mapNotNull { rackOf(it.payload) }.filter { it !in zones }.distinct().sorted()
}

/**
 * The sales-floor fixture zone ids of the schedule's moves. Every move goes by `to_zone_id` (the server resolves a fixture CODE only by an exact
 * zone name or a per-site mapping, so a code-addressed move is refused `destination_unresolved` while still answered 200). The ids come from the
 * keys export (the dry run's truth); a live run cross-checks each one against `/spatial/identity` (zone `code`, else name) and REFUSES, naming
 * the code, when a fixture is missing on the platform or the two ids disagree.
 */
object FixtureZones {
    class Keyed(val zoneId: String, val name: String)

    /** Zone ids named by the floor moves of [items] (not the `@backroom_zone:` racks), back to their codes via [keys]: code to (id, name). */
    fun needed(items: List<SendItem>, keys: Map<String, Keyed>): Map<String, Keyed> {
        val byId = keys.entries.associate { it.value.zoneId to it.key }
        val ids = items.filter { it.kind == "move" }.map { it.payload.path("to_zone_id").asText("") }.filter {
            it.isNotBlank() &&
                !it.startsWith(Placeholders.ZONE_PREFIX)
        }
        return ids.distinct().sorted().associate { id -> (byId[id] ?: "?$id") to (keys[byId[id]] ?: Keyed(id, "")) }
    }

    fun problems(identity: SpatialIdentityResponse?, floorSpaceId: String, needed: Map<String, Keyed>): List<String> {
        if (identity == null) return listOf("could not read /spatial/identity to cross-check the fixture zone ids")
        if (identity.truncated) return listOf("/spatial/identity answered truncated: the fixture zone ids cannot be cross-checked")
        val spaces = identity.sites.flatMap { it.spaces }
        val zones = spaces.filter { it.spaceId == floorSpaceId }.ifEmpty { spaces }.flatMap { it.zones }
        val byCode = zones.filter { !it.code.isNullOrBlank() }.associateBy { it.code!! }
        val byName = zones.filter { !it.name.isNullOrBlank() }.associateBy { it.name!! }
        return needed.entries.sortedBy { it.key }.mapNotNull { (code, k) ->
            val z = byCode[code] ?: byName[k.name]
            when {
                code.startsWith("?") -> "a move names zone ${k.zoneId} which the keys export does not have"
                z == null -> "fixture $code (keys zone ${k.zoneId}, \"${k.name}\") is not on the platform by code or name"
                z.zoneId != k.zoneId -> "fixture $code: the keys export says zone ${k.zoneId}, the platform says ${z.zoneId}"
                else -> null
            }
        }
    }
}

/**
 * PRE-check, gating: do the sampled ledger units exist on the platform? The 20 spot-count fixtures' ledger EPCs (at most [MAX_EPCS], a stable
 * subsample), read through `POST /inventory/items/details` in chunks of at most [CHUNK]; a unit counts as missing unless its row has an itemId
 * and state `in_stock` (unknown EPCs come back as rows of nulls). MORE than [LIMIT] missing STOPS the run before any send. The location
 * comparison is NOT a gate any more: it is the [SpotCount] post-check after the moves.
 */
object ExistenceCheck {
    const val LIMIT = 0.02
    const val MAX_EPCS = 2_000
    const val CHUNK = 200

    class Verdict(val sampled: Int, val missing: Int, val examples: List<String>) {
        val rate: Double get() = missing.toDouble() / maxOf(1, sampled)
        val stop: Boolean get() = rate > LIMIT

        fun summary(): String = "existence check: $missing of $sampled sampled ledger units are not in_stock with an itemId on the platform " +
            "(${"%.2f".format(rate * 100)} %, limit ${"%.0f".format(LIMIT * 100)} %) -> ${if (stop) "STOP" else "ok"}"
    }

    fun sample(ledger: com.m8trx.twin.ops.StoreStock, fixtures: List<String>, seed: Long, slug: String): List<String> {
        val epcs = fixtures.flatMap { ledger.byFixture[it].orEmpty() }.distinct()
        return if (epcs.size <=
            MAX_EPCS
        ) {
            epcs
        } else {
            epcs.sortedBy { com.m8trx.twin.history.HistoryCsv.derive(seed, slug, "exists", it) }.take(MAX_EPCS)
        }
    }

    /** [details] answers one chunk (null or an exception means the read failed, which refuses the run). */
    fun evaluate(epcs: List<String>, details: (List<String>) -> List<ItemDetail>?): Verdict {
        var missing = 0
        val examples = ArrayList<String>()
        epcs.chunked(CHUNK).forEach { chunk ->
            val rows = details(chunk) ?: error("the items/details read failed")
            val byEpc = rows.associateBy { it.epc.uppercase() }
            chunk.forEach { e ->
                val d = byEpc[e.uppercase()]
                if (d == null || d.itemId == null || d.state != "in_stock") {
                    missing++
                    if (examples.size < 10) examples += "$e: ${if (d == null) "no row" else "itemId=${d.itemId} state=${d.state}"}"
                }
            }
        }
        return Verdict(epcs.size, missing, examples)
    }

    /** The real read: the bearer `inventory:read` key (`M8TRX_TWIN_BEARER`, else the service bearer). */
    fun liveDetails(): (List<String>) -> List<ItemDetail>? {
        val client = ConnectClient(ConnectConfig.fromEnv())
        return { chunk ->
            when (val r = client.itemDetails(ItemDetailsRequest(chunk))) {
                is ConnectResponse.Ok -> client.mapper.readValue(
                    r.rawBody,
                    client.mapper.typeFactory.constructCollectionType(List::class.java, ItemDetail::class.java),
                )

                is ConnectResponse.Err -> null
            }
        }
    }
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
