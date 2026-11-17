package com.m8trx.twin.storeops

import com.fasterxml.jackson.databind.JsonNode
import com.m8trx.twin.connect.DatedStream
import com.m8trx.twin.connect.ReceiveStock
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.bearer.ItemReceiveRequest
import com.m8trx.twin.connect.model.bearer.ReceiveItem
import com.m8trx.twin.connect.model.webhook.InventoryMovement
import com.m8trx.twin.connect.model.webhook.MovementItem
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
 * `M8TRX_RESET_OUT` (out/reset) · `M8TRX_STREAM_SOLD_LOG` (.twin-state/sold-epcs.txt, as the other ops steps; its parent is the state
 * dir) · `M8TRX_CHAIN_DIR` · pace: `M8TRX_RESET_RECEIVE_INTERVAL_S` (10: one receive request per 10 s, never under 0.25 s) ·
 * `M8TRX_RESET_MOVES_PER_S` (2, the ceiling) · `M8TRX_RESET_LIVE` (false; **refused in this slice**, see [liveRefusal]).
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.storeops.StoreDayReset")

data class ResetPacing(
    val receiveIntervalMs: Long = 10_000,
    val movesPerS: Double = 2.0,
    val receiveChunk: Int = 200,
    val moveChunk: Int = 200,
    val directiveGapMs: Long = 60_000,
) {
    init {
        require(receiveChunk in 1..200) { "a receive request carries at most 200 EPCs, got $receiveChunk" }
        require(receiveIntervalMs >= 250) {
            "the data-plane key allows 300 requests a minute: one receive request per $receiveIntervalMs ms is too fast"
        }
        require(movesPerS > 0 && movesPerS <= 2.0) { "movement batches go at 2 a second at most, got $movesPerS" }
    }

    val moveSpacingMs: Long get() = Math.ceil(1000.0 / movesPerS).toLong()
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
    )

    /** Receipts first (paced), then the moves (paced), then the period directive, each item with its offset from the start. */
    fun schedule(slug: String, forDate: LocalDate, plan: ResetPlanner.Result, directive: JsonNode, pacing: ResetPacing): List<SendItem> {
        val day = forDate.toString().replace("-", "")
        val items = ArrayList<SendItem>()
        var seq = 0
        var t = 0L
        val chunks = plan.received.chunked(pacing.receiveChunk)
        chunks.forEach { chunk ->
            val payload = ConnectMappers.camel.valueToTree<JsonNode>(ItemReceiveRequest("@site", "@backroom_space", chunk.map { ReceiveItem(it) }))
            items += SendItem(seq++, t, 0, "receive", "rst-$slug-$day-r-${SendItem.hash(chunk.joinToString(","))}", payload, chunk)
            t += pacing.receiveIntervalMs
        }
        plan.moves.forEach { m ->
            val id = "rst-$slug-$day-m-${SendItem.hash(m.dest, m.ean, m.epcs.joinToString(","))}"
            val mv = InventoryMovement(
                externalMovementId = id,
                toFixtureCode = if (m.toBack) null else m.dest,
                // Q15 (open): a back-room destination goes by the stockroom space, as the store-day plan does, until B/W says a rack zone is accepted
                toZoneId = if (m.toBack) "@backroom_space" else null,
                items = m.epcs.map { MovementItem(epc = it) },
            )
            items += SendItem(seq++, t, 1, "move", id, ConnectMappers.snake.valueToTree(mv), m.epcs)
            t += pacing.moveSpacingMs
        }
        val dirId = directive.path("external_directive_id").asText()
        t += pacing.directiveGapMs
        items += SendItem(seq++, t, 2, "planogram", dirId, directive, emptyList())
        return items
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

    fun liveRefusal(): String = "REFUSING: M8TRX_RESET_LIVE=true is not honoured in the Phase 3 tooling slice. " +
        "The 20-fixture spot count is a stub and the reset night is not scheduled; " +
        "the live send path is added in the slice that Bob approves for the night."

    fun report(
        slug: String,
        forDate: LocalDate,
        period: PeriodPlan,
        inputs: ResetInputs,
        plan: ResetPlanner.Result,
        items: List<SendItem>,
        pacing: ResetPacing,
        directive: JsonNode,
    ): String = buildString {
        val rec = items.filter { it.kind == "receive" }
        val mov = items.filter { it.kind == "move" }
        val dirs = items.filter { it.kind == "planogram" }
        val ms = items.maxOf { it.dueMs }
        appendLine("# storeDayReset DRY-RUN report: $slug, first live day $forDate (period ${period.start} to ${period.end})")
        appendLine()
        appendLine("TRUTH: ${inputs.truthNote}")
        inputs.files.forEach { appendLine("  - $it") }
        inputs.soldFileDate?.let { d ->
            if (d < forDate.minusDays(1)) {
                appendLine(
                    "WARNING: the sold log was last written on $d, ${java.time.temporal.ChronoUnit.DAYS.between(
                        d,
                        forDate,
                    )} days before the first live day: units sold since are still counted on hand. Copy the box's .twin-state right before a real run.",
                )
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
        appendLine(
            "- conservation: ${plan.ledgerUnits} = ${plan.placedFloor} + ${plan.toBackroom} + ${plan.unplaceable.size} -> ${plan.placedFloor + plan.toBackroom + plan.unplaceable.size}",
        )
        appendLine(
            "- the period plan wants ${plan.planFloor} floor units and the stock plan ${plan.planBackroom} back-room units: ${plan.planFloor + plan.planBackroom} in all",
        )
        appendLine(
            "- to receive (new EPCs): ${plan.received.size} (${plan.receivedToCells} then moved to a cell, ${plan.received.size - plan.receivedToCells} rest in the back room)",
        )
        appendLine(
            "- new EPCs come from the store's delivery serial block (${StoreState.deliveryBase(
                slug,
            )} to ${StoreState.deliveryEnd(slug)}); next serial after the reset ${plan.nextSerial}",
        )
        appendLine()
        appendLine(
            "## Schedule (pace: 1 receive request per ${pacing.receiveIntervalMs / 1000.0} s, at most ${pacing.receiveChunk} EPCs; moves 1 per ${pacing.moveSpacingMs} ms = ${pacing.movesPerS}/s)",
        )
        appendLine("| kind | requests | units |")
        appendLine("|---|---|---|")
        appendLine("| receive | ${rec.size} | ${rec.sumOf { it.epcs.size }} |")
        appendLine("| move | ${mov.size} | ${mov.sumOf { it.epcs.size }} |")
        appendLine(
            "| planogram directive | ${dirs.size} | ${directive.path(
                "targets",
            ).size()} target lines (effective ${directive.path("effective_date").asText()}, expiry ${directive.path("expiry_date").asText()}) |",
        )
        appendLine()
        appendLine(
            "- estimated duration at this pace: ${fmt(
                ms,
            )} (receives ${fmt(
                rec.size * pacing.receiveIntervalMs,
            )}, moves ${fmt(mov.size * pacing.moveSpacingMs)}), plus health checks and any 429/502 waits",
        )
        appendLine("- first item: ${items.first().let { "${it.kind} ${it.extId} at +${it.dueMs / 1000.0} s, ${it.epcs.size} units" }}")
        appendLine("- last item: ${items.last().let { "${it.kind} ${it.extId} at +${it.dueMs / 1000.0} s" }}")
        appendLine(
            "- idempotent ids: receive `rst-<slug>-<yyyymmdd>-r-<hash of its EPCs>`, move `rst-<slug>-<yyyymmdd>-m-<hash of dest, ean, EPCs>`, directive `${dirs.firstOrNull()?.extId}`; stable across reruns",
        )
        appendLine(
            "- resumable: the sent-ledger (one line per accepted send) is read at start and accepted ids are skipped; a rerun of this plan reproduces the same ids",
        )
        appendLine()
        appendLine("## Gates still open (nothing here is decided by this tool)")
        appendLine(
            "- TODO(Q14): the old curated directive (`PLN-<slug>-<date>`, 28 lines in Denver, no stop date) is NOT ended by this schedule. Pending B/W: re-send with the same id and an `expiry_date` of the reset day, or leave it (its lines stay required beside the new plan)",
        )
        appendLine(
            "- TODO(Q15): back-room moves go to `to_zone_id=@backroom_space` (the stockroom space) as the store-day plan does; pending B/W on whether a rack zone is accepted",
        )
        appendLine(
            "- Q16: pace is the default above; pending B/W on the audit-trigger load. The night is announced to the lanes before it starts (Bob schedules it)",
        )
        appendLine(
            "- A3: prove the directive's `expiry_date` through Connect on ONE fixture before this directive goes out (B/W: not yet exercised on dev)",
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
        if (plan.unplaceable.isNotEmpty()) {
            appendLine()
            appendLine("## Unplaceable units (first 20 of ${plan.unplaceable.size})")
            plan.unplaceable.take(20).forEach { appendLine("- ${it.epc} ean=${it.ean} at ${it.at}") }
        } else {
            appendLine()
            appendLine("## Unplaceable units: none")
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d h %02d min %02d s (%d s)".format(s / 3600, s % 3600 / 60, s % 60, s)
    }

    fun build(slug: String, forDate: LocalDate, chainDir: Path, stateDir: Path, pacing: ResetPacing): Triple<ResetInputs, Built, StoreDayModel> {
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
            model.demand.keys, inputs.reserved, rackDefault, pacing.receiveChunk.coerceAtMost(pacing.moveChunk),
        )
        val directive = sim.periods.directiveJson(period)
        val items = schedule(slug, forDate, plan, directive, pacing)
        return Triple(inputs, Built(plan, items, period, directive, items.count { it.kind == "receive" }, items.count { it.kind == "move" }), model)
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
                n.set<JsonNode>("payload", i.payload)
                w.write(ConnectMappers.snake.writeValueAsString(n))
                w.write("\n")
            }
        }
        postState(slug, b.plan, forDate, b.period.start).save(out.resolve("state/post-reset"))
        Files.writeString(out.resolve("report.md"), report(slug, forDate, b.period, inputs, b.plan, b.items, pacing, b.directive))
    }

    fun zoneOf(slug: String): ZoneId = com.m8trx.twin.history.ChainKeys.load().store(slug).zone
}

fun main() {
    fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
    if (env("M8TRX_RESET_LIVE")?.toBoolean() == true) {
        log.error(StoreDayReset.liveRefusal())
        exitProcess(3)
    }
    val slug = env("M8TRX_RESET_SITE") ?: "dec-us-denver"
    val forDate = env("M8TRX_RESET_DATE")?.let(LocalDate::parse) ?: LocalDate.now(StoreDayReset.zoneOf(slug))
    val out = Path.of(env("M8TRX_RESET_OUT") ?: "out/reset").resolve(slug).resolve("reset-$forDate")
    val chainDir = Path.of(env("M8TRX_CHAIN_DIR") ?: "reference/data/chain")
    val stateDir = (env("M8TRX_STREAM_SOLD_LOG")?.let(Path::of) ?: Path.of(".twin-state/sold-epcs.txt")).let { it.parent ?: Path.of(".twin-state") }
    val pacing = ResetPacing(
        receiveIntervalMs = ((env("M8TRX_RESET_RECEIVE_INTERVAL_S")?.toDouble() ?: 10.0) * 1000).toLong(),
        movesPerS = env("M8TRX_RESET_MOVES_PER_S")?.toDouble() ?: 2.0,
    )
    log.info(
        "storeDayReset DRY-RUN {} first live day {} (period starts {}) state={} out={}",
        slug,
        forDate,
        PeriodPlans.periodStart(forDate),
        stateDir,
        out,
    )
    val (inputs, built, _) = StoreDayReset.build(slug, forDate, chainDir, stateDir, pacing)
    StoreDayReset.write(out, slug, forDate, inputs, built, pacing)
    println(Files.readString(out.resolve("report.md")))
    log.info("wrote {}/schedule.jsonl, report.md, state/post-reset.*", out)
}
