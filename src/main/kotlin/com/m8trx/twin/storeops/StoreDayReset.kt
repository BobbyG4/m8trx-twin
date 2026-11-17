package com.m8trx.twin.storeops

import com.fasterxml.jackson.databind.JsonNode
import com.m8trx.twin.connect.DatedStream
import com.m8trx.twin.connect.ReceiveStock
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.bearer.ItemReceiveRequest
import com.m8trx.twin.connect.model.bearer.ReceiveItem
import com.m8trx.twin.connect.model.webhook.InventoryMovement
import com.m8trx.twin.connect.model.webhook.MovementItem
import com.m8trx.twin.connect.model.webhook.PlanogramDirective
import com.m8trx.twin.history.HistoryCsv
import com.m8trx.twin.ops.StoreStock
import com.m8trx.twin.storeday.PeriodPlan
import com.m8trx.twin.storeday.PeriodPlans
import com.m8trx.twin.storeday.StoreDayModel
import com.m8trx.twin.storeday.StoreState
import com.m8trx.twin.storeday.buildSim
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.exitProcess

/**
 * `./gradlew storeDayReset` — the Denver reset night's tooling (Phase 3 brief § 1), **dry-run only in this slice**.
 *
 * It reads the twin's ledgers ([StoreStock.load]: the chain's `epcs.csv` + the units the receive step created, moved by
 * `moves/placements.csv`, minus `sold-epcs.txt` and `stock/missing-epcs.txt`), maps every unsold unit onto the store's plan for
 * the first live day's period ([ResetPlanner]), and writes the whole paced send schedule, the post-reset [StoreState] snapshot
 * and a report. It sends NOTHING and opens no socket; the live senders are never constructed (`LiveClients.constructed` stays 0).
 *
 * What it needs from the box for a real night: the state directory's `sold-epcs.txt`, `receive/received-stock.csv`,
 * `moves/placements.csv` and `stock/missing-epcs.txt` (copied from `/opt/m8trx/twin/.twin-state/` right before the run: the hourly
 * pass keeps selling and moving until Denver is handed over, so a copy that is hours old is not today's truth). Without
 * `sold-epcs.txt` the ledger is the seed only and the report says so in its first line.
 *
 * Env: `M8TRX_RESET_SITE` (dec-us-denver) · `M8TRX_RESET_DATE` (the first live store-day; default store-local today) ·
 * `M8TRX_RESET_OUT` (out/reset) · `M8TRX_RESET_OLD_DIRECTIVES` (the store's old directive export) · `M8TRX_STREAM_SOLD_LOG` (.twin-state/sold-epcs.txt, as the other ops steps; its parent is the state
 * dir) · `M8TRX_CHAIN_DIR` · pace (A16): `M8TRX_RESET_RECEIVE_EST_S` (40) · `M8TRX_RESET_RECEIVE_CHUNK` (50) ·
 * `M8TRX_RESET_MOVE_EVERY_S` (2) · `M8TRX_RESET_MOVE_CHUNK` (25) · `M8TRX_RESET_LIVE` (false; **refused in this slice**, see [liveRefusal]).
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.storeops.StoreDayReset")

data class ResetPacing(
    /** What one receive request is expected to take (A16: about 0.75 s an EPC, so 50 EPCs is about 40 s); requests go strictly ONE AT A TIME. */
    val receiveEstMs: Long = 40_000,
    /** One movement batch every 2 s (A16). */
    val moveEveryMs: Long = 2_000,
    val receiveChunk: Int = 50,
    /** Movement batches stay small: each unit is written one at a time, about 9 statements a unit (A15). */
    val moveChunk: Int = 25,
    val directiveGapMs: Long = 60_000,
) {
    init {
        require(receiveChunk in 1..200) { "a receive request carries at most 200 EPCs, got $receiveChunk" }
        require(moveChunk in 1..200) { "a move batch carries at most 200 units, got $moveChunk" }
        require(receiveEstMs >= 250) { "one receive request per $receiveEstMs ms is too fast (the data-plane key allows 300 a minute)" }
        require(moveEveryMs >= 500) { "movement batches go at 2 a second at most, got one per $moveEveryMs ms" }
    }

    val moveSpacingMs: Long get() = moveEveryMs
}

/** The ledger as the reset reads it, plus every EPC the twin has ever used (the collision set). */
class ResetInputs(
    val ledger: StoreStock,
    val reserved: Set<String>,
    val truthNote: String,
    val files: List<String>,
    val soldFileDate: LocalDate? = null,
)

object StoreDayReset {
    fun loadInputs(chainDir: Path, stateDir: Path, slug: String): ResetInputs {
        val ledger = StoreStock.load(chainDir, stateDir, slug)
        val sold = StoreStock.readLines(stateDir.resolve("sold-epcs.txt"))
        val missing = StoreStock.readLines(StoreStock.missingFile(stateDir))
        val seed = DatedStream.loadEpcRows(chainDir.resolve("stores/$slug/epcs.csv")).map { it.epc }
        val rec = ReceiveStock.readStock(ReceiveStock.stockFile(stateDir.resolve("receive"))).map { it.epc }
        val placed = com.m8trx.twin.connect.RestockMoves.readPlacements(stateDir.resolve("moves/placements.csv")).keys
        val reserved = HashSet<String>().also {
            it += seed
            it += rec
            it += sold
            it += missing
            it += placed
            ledger.byFixture.values.forEach { l ->
                it +=
                    l
            }
        }
        val soldFile = stateDir.resolve("sold-epcs.txt")
        val note = if (Files.exists(soldFile)) {
            "ledger = chain seed + received - sold(${sold.size}) - missing(${missing.size}), moved by placements"
        } else {
            "SEED-ONLY: no sold-epcs.txt in $stateDir, so every sold unit is still counted on hand. NOT today's truth; copy the box's .twin-state first"
        }
        fun mt(p: Path) = if (Files.exists(p)) "${p.fileName} ${Files.getLastModifiedTime(p).toInstant()}" else "${p.fileName} MISSING"
        return ResetInputs(
            ledger,
            reserved,
            note,
            listOf(
                soldFile,
                stateDir.resolve("receive/received-stock.csv"),
                stateDir.resolve("moves/placements.csv"),
                StoreStock.missingFile(stateDir),
            ).map(::mt),
            if (Files.exists(soldFile)) Files.getLastModifiedTime(soldFile).toInstant().atZone(ZoneId.of("UTC")).toLocalDate() else null,
        )
    }

    class Built(
        val plan: ResetPlanner.Result,
        val items: List<SendItem>,
        val period: PeriodPlan,
        val directive: JsonNode,
        val receiveItems: Int,
        val moveItems: Int,
        val oldDirectives: List<PlanogramDirective>,
        val oldDirectiveFile: Path,
    )

    /**
     * The old curated directives of a store, as the platform holds them: a JSON file with one object (or an array of them) in the shape
     * the twin sent (`name`, `external_directive_id`, `targets[]`). They cannot be rebuilt from the repo (the 28 Denver targets were a
     * curated subset), so the operator exports them with the planogram read before the night. A missing file is NOT an empty list: the
     * report calls it a blocker and the live path refuses.
     */
    fun loadOldDirectives(file: Path): List<PlanogramDirective> {
        if (!Files.exists(file)) return emptyList()
        val n = ConnectMappers.snake.readTree(Files.readAllBytes(file))
        return (if (n.isArray) n.toList() else listOf(n)).map { ConnectMappers.snake.treeToValue(it, PlanogramDirective::class.java) }
    }

    /**
     * A14: the end of an old directive is a re-send with the SAME `external_directive_id` and targets, NO `effective_date` (sending one
     * rewrites the stored start) and `expiry_date` = the reset day. It must go AFTER the store's local midnight of the reset day (a re-send
     * before the stop would leave the old plan with unresolved targets while it still counts), and BEFORE the first period plan.
     */
    fun endOf(old: PlanogramDirective, forDate: LocalDate): PlanogramDirective = old.copy(effectiveDate = null, expiryDate = forDate.toString())

    /**
     * Receipts first (one at a time, each estimated at [ResetPacing.receiveEstMs]), then the moves (one batch every 2 s), then, gated to
     * after the store's local midnight, each old directive's end and last the new period plan, each item with its offset from the start.
     */
    fun schedule(
        slug: String,
        forDate: LocalDate,
        plan: ResetPlanner.Result,
        directive: JsonNode,
        pacing: ResetPacing,
        oldDirectives: List<PlanogramDirective> = emptyList(),
    ): List<SendItem> {
        val day = forDate.toString().replace("-", "")
        val items = ArrayList<SendItem>()
        var seq = 0
        var t = 0L
        plan.received.chunked(pacing.receiveChunk).forEach { chunk ->
            val payload = ConnectMappers.camel.valueToTree<JsonNode>(ItemReceiveRequest("@site", "@backroom_space", chunk.map { ReceiveItem(it) }))
            items += SendItem(seq++, t, 0, "receive", "rst-$slug-$day-r-${SendItem.hash(chunk.joinToString(","))}", payload, chunk)
            t += pacing.receiveEstMs
        }
        plan.moves.forEach { m ->
            val id = "rst-$slug-$day-m-${SendItem.hash(m.dest, m.ean, m.epcs.joinToString(","))}"
            val mv = InventoryMovement(
                externalMovementId = id,
                toFixtureCode = if (m.toBack) null else m.dest,
                // A15: a back-room destination is a fixture ZONE of the stockroom space, by to_zone_id (resolved from /spatial/identity at run
                // time), never the space and never a fixture code
                toZoneId = if (m.toBack) "${Placeholders.ZONE_PREFIX}${m.dest}" else null,
                items = m.epcs.map { MovementItem(epc = it) },
            )
            items += SendItem(seq++, t, 1, "move", id, ConnectMappers.snake.valueToTree(mv), m.epcs)
            t += pacing.moveSpacingMs
        }
        t += pacing.directiveGapMs
        oldDirectives.sortedBy { it.externalDirectiveId }.forEach { old ->
            items += SendItem(
                seq++,
                t,
                2,
                "planogram",
                "rst-$slug-$day-end-${old.externalDirectiveId}",
                ConnectMappers.snake.valueToTree(endOf(old, forDate)),
                emptyList(),
                afterLocalMidnight = true,
            )
            t += 2_000
        }
        items +=
            SendItem(seq++, t, 2, "planogram", directive.path("external_directive_id").asText(), directive, emptyList(), afterLocalMidnight = true)
        return items
    }

    /** The earliest instant the run may start so that its first midnight-gated item falls after [midnightMs]. */
    fun earliestStartMs(items: List<SendItem>, midnightMs: Long): Long =
        midnightMs - (items.filter { it.afterLocalMidnight }.minOfOrNull { it.dueMs } ?: 0L)

    class Night(val from: Int, val to: Int, val durationMs: Long, val receives: Int, val moves: Int, val directives: Int)

    /** A proposal: the sequence cut at item boundaries into nights of at most [budgetMs] each (order is kept, so any cut is safe). */
    fun nights(items: List<SendItem>, budgetMs: Long = 6 * 3_600_000L): List<Night> {
        val out = ArrayList<Night>()
        var from = 0
        for (i in items.indices) {
            val span = items[i].dueMs - items[from].dueMs
            if (span > budgetMs && i > from) {
                out += night(items, from, i - 1)
                from = i
            }
        }
        if (from < items.size) out += night(items, from, items.size - 1)
        return out
    }

    private fun night(items: List<SendItem>, from: Int, to: Int): Night {
        val seg = items.subList(from, to + 1)
        return Night(
            from,
            to,
            seg.last().dueMs - seg.first().dueMs,
            seg.count { it.kind == "receive" },
            seg.count { it.kind == "move" },
            seg.count {
                it.kind ==
                    "planogram"
            },
        )
    }

    /** The unit-level state after the reset: the snapshot the store-day continues from. */
    fun postState(slug: String, plan: ResetPlanner.Result, forDate: LocalDate, periodStart: LocalDate): StoreState {
        val s = StoreState(slug)
        plan.finals.sortedWith(compareBy({ it.location }, { it.ean }, { it.epc })).forEach { s.add(it.epc, it.ean, it.state, it.location, it.level) }
        s.nextSerial = plan.nextSerial
        s.asOf = forDate.minusDays(1)
        s.planPeriod = periodStart
        return s
    }

    /** The 20-fixture spot count (brief § 1 step 1, platform half): a STUB that prints the reads it would make. Sends nothing. */
    fun spotCountDesign(slug: String, ledger: StoreStock, seed: Long, k: Int = 20): List<String> {
        val floor = ledger.wall.filterValues { it.isNotEmpty() }.keys.sorted()
        val pick = floor.sortedBy { HistoryCsv.derive(seed, slug, "spot-count", it) }.take(k).sorted()
        return buildList {
            add(
                "STUB (not run): 1 read of POST /api/v2/spatial/identity (site -> space -> zone ids; key M8TRX_TWIN_BEARER, inventory:read) for the sales-floor space",
            )
            add(
                "then, per fixture, one read-only thing_location query (Hasura, as StoreStock.fromPlatform) for these ${pick.size} fixtures, compared with the ledger:",
            )
            pick.forEach { add("  - $it: ledger ${ledger.byFixture[it]?.size ?: 0} units") }
            add(
                "rule: more than 2% of the sampled units disagreeing STOPS the night; the stop is not implemented in this slice, so the live path stays refused",
            )
        }
    }

    /** Why a live run is refused, or null. Everything here must be clear before any night; in this slice the spot count stays a stub, so it never is. */
    fun liveRefusal(slug: String, stateDir: Path): String {
        ReceiveProbe.refusal(ReceiveProbe.resultFile(stateDir, slug))?.let { return "REFUSING: $it" }
        return "REFUSING: M8TRX_RESET_LIVE=true is not honoured in the Phase 3 tooling slice. " +
            "The 20-fixture spot count is a stub and the reset night is not scheduled; " +
            "the live send path is added in the slice that Bob approves for the night."
    }

    fun report(
        slug: String,
        forDate: LocalDate,
        period: PeriodPlan,
        inputs: ResetInputs,
        plan: ResetPlanner.Result,
        items: List<SendItem>,
        pacing: ResetPacing,
        directive: JsonNode,
        oldDirectives: List<PlanogramDirective>,
        oldFile: Path,
        midnightMs: Long,
        zone: ZoneId,
    ): String = buildString {
        val rec = items.filter { it.kind == "receive" }
        val mov = items.filter { it.kind == "move" }
        val ends = items.filter { it.kind == "planogram" }.dropLast(1)
        val ms = items.maxOf { it.dueMs }
        appendLine("# storeDayReset DRY-RUN report: $slug, first live day $forDate (period ${period.start} to ${period.end})")
        appendLine()
        appendLine("TRUTH: ${inputs.truthNote}")
        inputs.files.forEach { appendLine("  - $it") }
        inputs.soldFileDate?.let { d ->
            if (d < forDate.minusDays(1)) {
                val days = java.time.temporal.ChronoUnit.DAYS.between(d, forDate)
                appendLine(
                    "WARNING: the sold log was last written on $d, $days days before the first live day: units sold since are still counted on hand.",
                )
                appendLine("Copy the box's .twin-state right before a real run.")
            }
        }
        appendLine("Nothing was sent. No client was constructed. (Live flag: M8TRX_RESET_LIVE, refused in this slice.)")
        appendLine()
        appendLine("## Ledger and plan")
        appendLine("- ledger units (unsold): ${plan.ledgerUnits}")
        appendLine(
            "- placed on the floor (kept in place ${plan.stayedFloor}, moved to a cell ${plan.placedFloor - plan.stayedFloor}): ${plan.placedFloor}",
        )
        appendLine("- to the back room (already there ${plan.stayedBackroom}, moved ${plan.toBackroom - plan.stayedBackroom}): ${plan.toBackroom}")
        appendLine("- unplaceable (EAN not in the store's catalog, or EPC does not decode; left alone): ${plan.unplaceable.size}")
        val sum = plan.placedFloor + plan.toBackroom + plan.unplaceable.size
        appendLine("- conservation: ${plan.ledgerUnits} = ${plan.placedFloor} + ${plan.toBackroom} + ${plan.unplaceable.size} -> $sum")
        appendLine(
            "- the period plan wants ${plan.planFloor} floor units and the stock plan ${plan.planBackroom} back-room units: ${plan.planFloor + plan.planBackroom} in all",
        )
        appendLine(
            "- to receive (new EPCs): ${plan.received.size} (${plan.receivedToCells} then moved to a cell, ${plan.received.size - plan.receivedToCells} rest in the back room)",
        )
        appendLine(
            "- new EPCs: delivery serial block ${StoreState.deliveryBase(
                slug,
            )} to ${StoreState.deliveryEnd(slug)}; next serial after the reset ${plan.nextSerial}",
        )
        appendLine()
        appendLine(
            "## Schedule (A16 pace: receives ONE AT A TIME, ${pacing.receiveChunk} EPCs a request, about ${pacing.receiveEstMs / 1000} s each; moves one batch of at most ${pacing.moveChunk} every ${pacing.moveSpacingMs / 1000.0} s)",
        )
        appendLine("| kind | requests | units |")
        appendLine("|---|---|---|")
        appendLine("| receive | ${rec.size} | ${rec.sumOf { it.epcs.size }} |")
        appendLine("| move | ${mov.size} | ${mov.sumOf { it.epcs.size }} |")
        appendLine("| old directive end (A14) | ${ends.size} | ${ends.sumOf { it.payload.path("targets").size() }} target lines |")
        appendLine(
            "| new period directive | 1 | ${directive.path(
                "targets",
            ).size()} target lines (effective ${directive.path("effective_date").asText()}, expiry ${directive.path("expiry_date").asText()}) |",
        )
        appendLine()
        appendLine(
            "- estimated duration at this pace: ${fmt(
                ms,
            )} (receives ${fmt(
                rec.size * pacing.receiveEstMs,
            )}, moves ${fmt(mov.size * pacing.moveSpacingMs)}), plus health checks and any 429/5xx waits",
        )
        val nights = nights(items)
        appendLine(
            "- PROPOSAL, a two-night split (nights of at most 6 h; any cut keeps the order, a cut between a receive and its move is safe because the unit simply rests in the stockroom):",
        )
        nights.forEachIndexed { i, n ->
            appendLine(
                "  - night ${i + 1}: items ${n.from} to ${n.to}, ${fmt(
                    n.durationMs,
                )}: ${n.receives} receive requests, ${n.moves} move batches, ${n.directives} directives",
            )
        }
        val receiveUnitsPerNight = 6 * 3_600_000L / pacing.receiveEstMs * pacing.receiveChunk
        appendLine(
            "- or a SMALLER Denver: one 6 h night of receives carries about $receiveUnitsPerNight new units; this reset needs " +
                "${plan.received.size}, " +
                "so a Denver trimmed by about ${maxOf(
                    0,
                    plan.received.size - receiveUnitsPerNight,
                )} received units fits one night of receives (moves are further hours)",
        )
        appendLine("- first item: ${items.first().let { "${it.kind} ${it.extId} at +${it.dueMs / 1000.0} s, ${it.epcs.size} units" }}")
        appendLine("- last item: ${items.last().let { "${it.kind} ${it.extId} at +${it.dueMs / 1000.0} s" }}")
        val start = Instant.ofEpochMilli(earliestStartMs(items, midnightMs)).atZone(zone)
        appendLine(
            "- ordering (A14): old directive end(s) and then the new plan are gated to AFTER $slug's local midnight of $forDate; for a single night the run starts no earlier than $start so those items fall after it",
        )
        appendLine(
            "- idempotent ids: receive `rst-<slug>-<yyyymmdd>-r-<hash of its EPCs>`, move `...-m-<hash of dest, ean, EPCs>`, old end `...-end-<old id>`, new plan `${items.last().extId}`; stable across reruns",
        )
        appendLine(
            "- resumable: the sent-ledger (one line per accepted send) is read at start and accepted ids are skipped; both calls are safe to repeat (A16)",
        )
        appendLine(
            "- stops: 5xx / no answer retried with backoff, health ok required before each batch; 10 failures in a row on one event halt the lane (pool or health trouble)",
        )
        appendLine()
        appendLine("## Old directive (A14)")
        if (oldDirectives.isEmpty()) {
            appendLine(
                "- BLOCKER: no old directive provided ($oldFile missing). The schedule therefore has NO end step for the store's old directive (Denver: 28 targets, ref `PLN-dec-us-denver-<date>`).",
            )
            appendLine(
                "  Export it with the planogram read (its `external_directive_id` and targets) into that file; the live path refuses until it exists.",
            )
        } else {
            oldDirectives.forEach {
                appendLine(
                    "- ends `${it.externalDirectiveId}` (${it.targets.size} targets): same id, same targets, no effective_date, expiry_date $forDate, sent after the store's midnight, before the new plan",
                )
            }
        }
        appendLine()
        appendLine("## Gates still open (nothing here is decided by this tool)")
        appendLine(
            "- F-9 BLOCKER (A16): a received item gets NO product and NO location on the platform. `storeDayReceiveProbe` (design only, not run) lists the one-unit test; the live path refuses without a recorded passing result file",
        )
        appendLine(
            "- A3/A14: a new pushed plan's targets may stay without a product until the directive is evaluated: prove on one fixture first (push a one-target plan, read the product back, run a count)",
        )
        appendLine(
            "- A15: back-room moves go by `to_zone_id` to the rack's fixture zone (Backroom Fixture 1 to 8, Receiving Dock), resolved from /spatial/identity at run time; never a space id, never a fixture code",
        )
        appendLine(
            "- A16: the night is not before the link limit (§ 12) and the own-site fix are deployed; announced to the Backend/Web and Android lanes with a start time; B/W reads the server in the first hour",
        )
        appendLine(
            "- A4: receiving uses `M8TRX_TWIN_SERVICE_BEARER` (inventory:create, 300 requests a minute); `M8TRX_TWIN_BEARER` is the Denver read key and gets 403",
        )
        appendLine(
            "- the hourly pass must stop sending $slug before the first live day (ownership file, see ops/INSTALL.md); two writers must never run together",
        )
        appendLine()
        appendLine("## Spot count (platform half of step 1)")
        spotCountDesign(slug, inputs.ledger, HistoryCsv.derive(1L, slug, forDate)).forEach { appendLine(it) }
        appendLine()
        if (plan.unplaceable.isNotEmpty()) {
            appendLine("## Unplaceable units (first 20 of ${plan.unplaceable.size})")
            plan.unplaceable.take(20).forEach { appendLine("- ${it.epc} ean=${it.ean} at ${it.at}") }
        } else {
            appendLine("## Unplaceable units: none")
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d h %02d min %02d s".format(s / 3600, s % 3600 / 60, s % 60)
    }

    fun build(
        slug: String,
        forDate: LocalDate,
        chainDir: Path,
        stateDir: Path,
        pacing: ResetPacing,
        oldFile: Path = stateDir.resolve("reset/old-directive-$slug.json"),
    ): Triple<ResetInputs, Built, StoreDayModel> {
        val sim = buildSim(slug)
        val model = sim.model
        val period = sim.periods.forDate(forDate)
        val inputs = loadInputs(chainDir, stateDir, slug)
        val keys = com.m8trx.twin.history.ChainKeys.load(chainDir.resolve(com.m8trx.twin.history.ChainKeys.DEFAULT_PATH.fileName))
        val zoneNames = keys.store(slug).zones.associate { it.zoneId to it.name }
        require(period.lines.all { it.zoneId in zoneNames }) { "$slug: a plan line names a zone the keys export does not have" }
        val rackDefault = model.stockPlan.filter { it.kind == "backroom" }.minOfOrNull { it.zone } ?: "BR-01"
        val plan = ResetPlanner.plan(
            slug, inputs.ledger.byFixture, period.required, period.homeLevel, model.backroomTarget, model.homeRack,
            model.demand.keys, inputs.reserved, rackDefault, pacing.moveChunk,
        )
        val directive = sim.periods.directiveJson(period)
        val old = loadOldDirectives(oldFile)
        val items = schedule(slug, forDate, plan, directive, pacing, old)
        return Triple(
            inputs,
            Built(
                plan,
                items,
                period,
                directive,
                items.count {
                    it.kind == "receive"
                },
                items.count { it.kind == "move" },
                old,
                oldFile,
            ),
            model,
        )
    }

    fun write(out: Path, slug: String, forDate: LocalDate, inputs: ResetInputs, b: Built, pacing: ResetPacing) {
        Files.createDirectories(out)
        Files.newBufferedWriter(out.resolve("schedule.jsonl")).use { w ->
            b.items.forEach { i ->
                val n = ConnectMappers.snake.createObjectNode()
                n.put("seq", i.seq)
                n.put("due_offset_ms", i.dueMs)
                n.put("kind", i.kind)
                n.put("route", i.route.label)
                n.put("ext_id", i.extId)
                n.put("units", i.epcs.size)
                n.put("after_local_midnight", i.afterLocalMidnight)
                n.set<JsonNode>("payload", i.payload)
                w.write(ConnectMappers.snake.writeValueAsString(n))
                w.write("\n")
            }
        }
        postState(slug, b.plan, forDate, b.period.start).save(out.resolve("state/post-reset"))
        val zone = zoneOf(slug)
        val midnight = forDate.atStartOfDay(zone).toInstant().toEpochMilli()
        Files.writeString(
            out.resolve("report.md"),
            report(slug, forDate, b.period, inputs, b.plan, b.items, pacing, b.directive, b.oldDirectives, b.oldDirectiveFile, midnight, zone),
        )
    }

    fun zoneOf(slug: String): ZoneId = com.m8trx.twin.history.ChainKeys.load().store(slug).zone
}

fun main() {
    fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
    val slug = env("M8TRX_RESET_SITE") ?: "dec-us-denver"
    val chainDir = Path.of(env("M8TRX_CHAIN_DIR") ?: "reference/data/chain")
    val stateDir = (env("M8TRX_STREAM_SOLD_LOG")?.let(Path::of) ?: Path.of(".twin-state/sold-epcs.txt")).let { it.parent ?: Path.of(".twin-state") }
    if (env("M8TRX_RESET_LIVE")?.toBoolean() == true) {
        log.error(StoreDayReset.liveRefusal(slug, stateDir))
        exitProcess(3)
    }
    val forDate = env("M8TRX_RESET_DATE")?.let(LocalDate::parse) ?: LocalDate.now(StoreDayReset.zoneOf(slug))
    val out = Path.of(env("M8TRX_RESET_OUT") ?: "out/reset").resolve(slug).resolve("reset-$forDate")
    val d = ResetPacing()
    val pacing = ResetPacing(
        receiveEstMs = env("M8TRX_RESET_RECEIVE_EST_S")?.let { (it.toDouble() * 1000).toLong() } ?: d.receiveEstMs,
        moveEveryMs = env("M8TRX_RESET_MOVE_EVERY_S")?.let { (it.toDouble() * 1000).toLong() } ?: d.moveEveryMs,
        receiveChunk = env("M8TRX_RESET_RECEIVE_CHUNK")?.toInt() ?: d.receiveChunk,
        moveChunk = env("M8TRX_RESET_MOVE_CHUNK")?.toInt() ?: d.moveChunk,
    )
    val oldFile = env("M8TRX_RESET_OLD_DIRECTIVES")?.let(Path::of) ?: stateDir.resolve("reset/old-directive-$slug.json")
    log.info(
        "storeDayReset DRY-RUN {} first live day {} (period starts {}) state={} out={}",
        slug,
        forDate,
        PeriodPlans.periodStart(forDate),
        stateDir,
        out,
    )
    val (inputs, built, _) = StoreDayReset.build(slug, forDate, chainDir, stateDir, pacing, oldFile)
    StoreDayReset.write(out, slug, forDate, inputs, built, pacing)
    println(Files.readString(out.resolve("report.md")))
    log.info("wrote {}/schedule.jsonl, report.md, state/post-reset.*", out)
}
