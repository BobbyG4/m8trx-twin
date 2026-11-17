package com.m8trx.twin.storeday

import com.m8trx.twin.behavior.BehaviorModel
import com.m8trx.twin.behavior.Cell
import com.m8trx.twin.behavior.FloorHooks
import com.m8trx.twin.behavior.MemorySampleSink
import com.m8trx.twin.behavior.PlanLine
import com.m8trx.twin.behavior.PlanTimeline
import com.m8trx.twin.behavior.ShelfPlan
import com.m8trx.twin.behavior.ShopperRecord
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.history.HistoryCsv
import com.m8trx.twin.ops.StockCounts
import com.m8trx.twin.ops.StockDrive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.IdentityHashMap
import java.util.PriorityQueue
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * What makes a fully stocked day sell what `demand.csv` says (see [storeDayCalibrate]): a stop multiplier per FIXTURE (busy
 * fixtures draw more shoppers), a buy multiplier per DEPARTMENT (a $5 general line converts more readily than a $5,000 bike),
 * and the exponent applied to a cell's demand share when a shopper picks what to look at.
 */
data class Calibration(val fixtureScale: Map<String, Double>, val departmentBuy: Map<String, Double>, val cellWeightPower: Double) {
    fun toJson(): String = ConnectMappers.snake.writerWithDefaultPrettyPrinter().writeValueAsString(
        mapOf(
            "fixture_scale" to fixtureScale.toSortedMap().mapValues { Math.round(it.value * 10_000) / 10_000.0 },
            "department_buy" to departmentBuy.toSortedMap().mapValues { Math.round(it.value * 10_000) / 10_000.0 },
            "cell_weight_power" to cellWeightPower,
        ),
    ) + "\n"

    companion object {
        val NEUTRAL = Calibration(emptyMap(), emptyMap(), 1.0)

        fun load(path: Path): Calibration {
            val n = ConnectMappers.snake.readTree(Files.readAllBytes(path))
            fun map(k: String) = n.path(k).fields().asSequence().associate { it.key to it.value.asDouble() }
            return Calibration(map("fixture_scale"), map("department_buy"), n.path("cell_weight_power").asDouble(1.0))
        }
    }
}

/** The operational knobs of a store day (brief 2a). */
data class Knobs(
    /**
     * Chance a put-back lands on a neighbouring fixture instead of its own. Shoppers put back about ten units for every unit
     * they buy, so 0.005 puts roughly 5 % of units sold on the wrong fixture over a month (the 2a default, 0.02, was about 20 %).
     */
    val misplaceProb: Double = 0.005,
    /** Units one staff member moves per hour. */
    val staffUnitsPerHour: Double = 120.0,
    /** Staff stocking before open; default = the staffing model's off-peak associates on the floor. */
    val preOpenStockers: Int = 3,
    /** Staff walking the fixtures in a quiet hour. */
    val topUpStockers: Int = 2,
    /** A slot below this share of its required quantity is topped up. */
    val topUpThreshold: Double = 0.60,
    /** How many of the day's lowest-traffic hours get a top-up walk. */
    val quietHours: Int = 3,
    /** Record the 5 Hz position samples (the sidecar). Off by default; a month of them is gigabytes. */
    val samples: Boolean = false,
    /** Apply [StoryCalendar] overlays (slice 2b). False reproduces the 2a day: the behavior model's own week wave, no holidays. */
    val calendar: Boolean = true,
    /** Scale the staff counts by the store's size (never above the knob value, never below one). False uses the counts as given. */
    val scaleStaff: Boolean = true,
    /** Slice 2c: weekly planogram periods (a directive per period, resets on its first day) and the weekly counts. False is the 2b day. */
    val periods: Boolean = true,
    /** The weekly plan changes (lines per department, swaps). */
    val periodKnobs: PeriodKnobs = PeriodKnobs(),
    /** Staff start a reset day this long before opening (the delivery still lands [StoreDayModel.deliveryLeadMin] before). */
    val resetLeadMin: Int = 180,
    /** The share of the floor fixtures counted each week (1/4: every fixture is counted every four weeks). 0 turns the counts off. */
    val countShare: Double = 0.25,
)

/** One unit that left a fixture in a sale. [fixture] is where the shopper stood: the platform should stamp the sale there. */
data class SaleUnit(
    val epc: String,
    val ean: String,
    val fixture: String,
    val level: Int,
    val priceUsd: Double,
    val shopper: String,
    val exitMs: Long,
)

/** One wire-plan event, before serialisation. [rank] orders ties at the same instant: receive, move, sale, samples. */
class WireEvent(val dueMs: Long, val rank: Int, val seq: Int, val kind: String, val payload: Any, val ids: Map<String, Any?>)

data class MoveLog(val dueMs: Long, val reason: String, val fixture: String, val ean: String, val epcs: List<String>)

/** One fixture's pull in a weekly count: the units the truth had on it when it was read, per EAN. */
class CountPull(val fixture: String, val atMs: Long, val reads: Map<String, Int>, val epcs: Int)

/** A weekly count of a rotating subset of the floor fixtures (slice 2c). */
class CountRecord(val countId: String, val date: LocalDate, val startMs: Long, val endMs: Long, val pulls: List<CountPull>)

class DayResult(
    val date: LocalDate,
    val records: List<ShopperRecord>,
    val wire: List<WireEvent>,
    val moves: List<MoveLog>,
    val saleUnits: List<SaleUnit>,
    val receivedEpcs: List<String>,
    val receiveRequests: Int,
    val startUnits: Int,
    val endUnits: Int,
    val openOnHand: Map<Slot, Int>,
    val closeOnHand: Map<Slot, Int>,
    val misplacements: Int,
    val samples: MemorySampleSink?,
    val expectedVisitors: Double,
    val plan: DayPlan,
    /** The plan the floor is arranged for at the day's end, as units required per fixture and EAN (the period's, slice 2c). */
    val required: Map<Slot, Int> = emptyMap(),
    /** The period whose directive was issued today (null on every other day). */
    val issued: PeriodPlan? = null,
    /** The plan the floor was reset from, when a reset ran today. */
    val resetFrom: PeriodPlan? = null,
    /** Planned slots (required > 0) with no unit on the fixture when the shopper day opened, and why: `no-stock` or `capacity`. */
    val unfilled: Map<Slot, String> = emptyMap(),
    val counts: List<CountRecord> = emptyList(),
)

/**
 * Slice 2a: one stock-aware store day. Pre-open delivery and stocking, shoppers against the real units on the fixtures
 * (the behavior model's own agents, through [FloorHooks]), quiet-hour top-ups, and the wire plan and truth that fall out.
 * Slice 2c adds the weekly planogram period (its directive, the reset of the floor on its first day) and the weekly count.
 * Offline and record-only: this class never opens a socket.
 */
class StoreDaySim(val model: StoreDayModel, val behavior: BehaviorModel, val cal: Calibration, val knobs: Knobs = Knobs()) {
    /** The Phase 1 plan: the plan of the anchor period and of every date before it. */
    val plan: ShelfPlan = behavior.stock.plan("model-v2", model.planLines)
    private val zone: ZoneId = ZoneId.of(model.timezone)
    val overlay = DayOverlay(model, behavior, knobs.calendar)

    /** Staff scale with the store (Denver, the 3-and-2 reference, is 1.0): at least two (or the knob if lower), at most the knob. */
    private val staffScale = if (knobs.scaleStaff) minOf(1.0, model.visitScale / model.locationFactor) else 1.0

    private fun staff(n: Int) = if (n <= 0) 0 else maxOf(minOf(n, 2), minOf(n, Math.round(n * staffScale).toInt()))

    val preOpenStockers: Int = staff(knobs.preOpenStockers)
    val topUpStockers: Int = staff(knobs.topUpStockers)

    /** The store's operating-model volume for a weekday or weekend, before any scale: what `demand.csv` is built from. */
    fun baseVisitors(weekend: Boolean): Double = behavior.model.traffic.visitorsPerDay.let { if (weekend) it.weekend else it.weekday }.toDouble()

    /** Neighbours for a misplaced put-back: the three nearest other shelved fixtures by centre distance. */
    private val neighbours: Map<String, List<String>> = run {
        val shelved = plan.cellsByFixture.keys
        val fx = behavior.fixtures.fixtures.filter { it.code in shelved }
        fx.associate { f ->
            f.code to
                fx.filter { it.code != f.code }.sortedWith(compareBy({ Math.hypot(it.centre.x - f.centre.x, it.centre.y - f.centre.y) }, { it.code }))
                    .take(3).map { it.code }
        }
    }

    /** The zone names the keys export gives this store's zones: what a directive's `raw_fixture_code` resolves by. */
    val zoneNames: Map<String, String> = com.m8trx.twin.history.ChainKeys.load().store(model.slug).zones.associate { it.zoneId to it.name }

    /** The store's weekly planogram periods (slice 2c). */
    val periods = PeriodPlans(model, neighbours, overlay::departmentWeights, behavior.params.seed, knobs.periodKnobs, knobs.periods, zoneNames)

    /** One period's plan as the floor meets it: the shoppers' [ShelfPlan] and the demand weight over its cells. */
    inner class PeriodView(val period: PeriodPlan, shelf: ShelfPlan) {
        val plan: ShelfPlan = shelf
        val required: Map<Slot, Int> get() = period.required
        val homeLevel: Map<Slot, Int> get() = period.homeLevel

        /** Cells of one EAN share that EAN's demand by facings. */
        val weights = IdentityHashMap<Cell, Double>().also { w ->
            plan.allCells.groupBy { it.ean }.forEach { (ean, cs) ->
                val total = cs.sumOf { it.facings }.toDouble()
                val share = model.demand[ean]?.share ?: 1e-7
                cs.forEach { c -> w[c] = Math.pow(share * c.facings / total, cal.cellWeightPower) }
            }
        }
    }

    private val views = HashMap<LocalDate, PeriodView>()

    /** The view of the period starting [start]: the Phase 1 plan's own while no change has been made to it. */
    fun viewFor(start: LocalDate): PeriodView = views.getOrPut(start) {
        val p = periods.periodOf(start)
        PeriodView(
            p,
            if (p.lines ===
                model.lines
            ) {
                plan
            } else {
                behavior.stock.plan("period-$start", p.lines.map { PlanLine(it.fixture, it.ean, it.facings, it.position, it.level) })
            },
        )
    }

    private val baseView: PeriodView get() = viewFor(PeriodPlans.ANCHOR)

    private val demandRank: Map<String, Double> = model.demand.mapValues { it.value.share }

    // ── hooks ─────────────────────────────────────────────────────────────────────────────────────────

    private inner class Hooks(
        val state: StoreState?,
        val log: DayLog,
        val view: PeriodView,
        val conversion: Double = 1.0,
        val schedule: (Long, () -> Unit) -> Unit = { _, a -> a() },
    ) : FloorHooks {
        override fun cellWeight(c: Cell): Double = view.weights[c] ?: 1e-7

        override fun fixtureScale(code: String): Double = cal.fixtureScale[code] ?: 1.0

        override fun buyScale(c: Cell): Double = (cal.departmentBuy[model.demand[c.ean]?.department] ?: 1.0) * conversion

        override fun available(c: Cell): Boolean = state == null || state.count(c.fixture, c.ean) > 0

        override fun take(c: Cell, atMs: Long): String? {
            val st = state ?: return null
            val (epc, level) = st.sell(c.fixture, c.ean) ?: return null
            log.taken[epc] = Triple(c.fixture, level, atMs)
            return epc
        }

        override fun putBack(c: Cell, atMs: Long, u: Double) {
            val st = state ?: return
            if (u >= knobs.misplaceProb) return
            val near = neighbours[c.fixture].orEmpty()
            if (near.isEmpty()) return
            val to = near[min(near.size - 1, floor(u / knobs.misplaceProb * near.size).toInt())]
            // The unit is in the shopper's hand from now until [atMs] and lands on the neighbour then: held IN_TRANSIT, so no
            // other shopper can take it from either fixture before the move is due (the wire-order rule).
            val epc = st.pickUpLast(c.fixture, c.ean) ?: return
            schedule(atMs) {
                st.place(epc, to, view.homeLevel[Slot(to, c.ean)] ?: 1)
                log.misplaced++
                log.moves += MoveLog(atMs, "misplaced", to, c.ean, listOf(epc))
                log.wire += moveEvent(atMs, "shopper-misplacement", to, listOf(epc), log.nextSeq())
            }
        }
    }

    private class DayLog(val date: LocalDate) {
        val taken = HashMap<String, Triple<String, Int, Long>>()
        val moves = ArrayList<MoveLog>()
        val wire = ArrayList<WireEvent>()
        val counts = ArrayList<CountRecord>()
        var misplaced = 0
        private var seq = 0
        fun nextSeq(): Int = seq++
    }

    private fun slug() = model.slug

    private fun moveEvent(dueMs: Long, actor: String, toFixture: String, epcs: List<String>, seq: Int): WireEvent {
        val d = LocalDate.ofInstant(Instant.ofEpochMilli(dueMs), zone)
        // A back-room rack is not a fixture of the keys export: it goes by the same `@backroom_space` placeholder the receipt uses.
        val toBack = toFixture.startsWith("BR-")
        val payload = com.m8trx.twin.connect.model.webhook.InventoryMovement(
            externalMovementId = "sd-${slug()}-$d-m$seq",
            toFixtureCode = if (toBack) null else toFixture,
            toZoneId = if (toBack) "@backroom_space" else null,
            items = epcs.map { com.m8trx.twin.connect.model.webhook.MovementItem(epc = it) },
        )
        return WireEvent(
            dueMs,
            1,
            seq,
            "move",
            ConnectMappers.snake.valueToTree<com.fasterxml.jackson.databind.JsonNode>(payload),
            mapOf("store" to slug(), "actor" to actor, "units" to epcs.size, "to" to toFixture),
        )
    }

    // ── one day ───────────────────────────────────────────────────────────────────────────────────────

    private class Queued(val dueMs: Long, val seq: Long, val action: () -> Unit)

    /** The units of a slot a staff member can bring: back-room units of the EAN, oldest first, rack order. */
    private fun takeFromBackroom(st: StoreState, ean: String, n: Int): List<String> {
        val out = ArrayList<String>(n)
        for (rack in st.locations().filter { st.isBackroomLoc(it) }) {
            while (out.size < n) out += (st.pickUp(rack, ean) ?: break)
            if (out.size >= n) break
        }
        return out
    }

    /** The rack a displaced unit of [ean] goes to: its home rack, else the first rack that holds anything, else BR-01. */
    private fun rackFor(st: StoreState, ean: String): String = model.homeRack[ean] ?: st.locations().firstOrNull { st.isBackroomLoc(it) } ?: "BR-01"

    fun simulate(state: StoreState, date: LocalDate, seed: Long): DayResult {
        val log = DayLog(date)
        val dayPlan = overlay.plan(date)
        val startUnits = state.totalNotSold()
        val periodStart = PeriodPlans.periodStart(date)
        val view = viewFor(periodStart)
        if (dayPlan.closed) {
            // A closed day: no delivery, no staff, no shoppers. Tomorrow's order covers what today would have.
            state.asOf = date
            val onHand = floorCounts(state)
            val arranged = viewFor(state.planPeriod ?: PeriodPlans.ANCHOR)
            return DayResult(
                date, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0, startUnits, startUnits, onHand, onHand, 0, null, 0.0,
                dayPlan, arranged.required,
            )
        }
        val shape = dayPlan.shape!!
        val openTime = shape.open
        val open = ZonedDateTime.of(date, openTime, zone).toInstant().toEpochMilli()
        val t0 = open - model.deliveryLeadMin * 60_000L
        val startSold = state.soldEpcs().size
        val queue = PriorityQueue<Queued>(compareBy({ it.dueMs }, { it.seq }))
        var qseq = 0L
        val hooks = Hooks(state, log, view, dayPlan.conversion) { due, action -> queue += Queued(due, qseq++, action) }
        val ratePerMs = { stockers: Int -> stockers * knobs.staffUnitsPerHour / 3_600_000.0 }

        // 0. the period: its directive goes out on the first open day, and a changed plan is reset before the delivery ----------
        val issue = knobs.periods && state.planPeriod != periodStart
        val from = if (issue) periods.periodOf(state.planPeriod ?: PeriodPlans.ANCHOR) else null
        val resetNeeded = from != null && from.required != view.period.required
        val resetStart = if (resetNeeded) minOf(open - knobs.resetLeadMin * 60_000L, t0 - 30 * 60_000L) else t0
        if (issue) {
            val due = minOf(resetStart, t0) - 10 * 60_000L
            log.wire += WireEvent(
                due,
                -1,
                log.nextSeq(),
                "planogram",
                periods.directiveJson(view.period),
                mapOf(
                    "store" to slug(),
                    "directive" to periods.directiveId(periodStart),
                    "period_start" to periodStart.toString(),
                    "period_end" to view.period.end.toString(),
                    "lines_changed" to view.period.linesChanged,
                ),
            )
        }

        // 1. delivery ---------------------------------------------------------------------------------
        // (a changed plan is reset first: the delivery's order covers the NEW plan, and a displaced unit goes back before it)
        val unfilled = java.util.TreeMap<Slot, String>()
        if (resetNeeded) resetFloor(state, log, view, from!!.required, resetStart, t0, ratePerMs(preOpenStockers))
        val order = planOrder(state, view)
        val received = ArrayList<String>()
        var requests = 0
        val minted = order.flatMap { (ean, n) -> state.mint(ean, n) }
        minted.chunked(200).forEachIndexed { k, chunk ->
            val due = t0 + k * 2_000L
            val payload = ConnectMappers.camel.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                com.m8trx.twin.connect.model.bearer.ItemReceiveRequest(
                    "@site",
                    "@backroom_space",
                    chunk.map {
                        com.m8trx.twin.connect.model.bearer.ReceiveItem(it)
                    },
                ),
            )
            log.wire +=
                WireEvent(
                    due,
                    0,
                    log.nextSeq(),
                    "receive",
                    payload,
                    mapOf(
                        "store" to slug(),
                        "request" to k,
                        "units" to chunk.size,
                        "eans" to chunk.map { state.unit(it)!!.ean }.toSet().size,
                    ),
                )
            requests++
        }
        minted.forEach { epc ->
            val u = state.unit(epc)!!
            state.place(epc, model.homeRack[u.ean] ?: "BR-01", 0)
            received += epc
        }

        // 2. pre-open stocking: strays home first, then every slot below plan, fullest need first ------------
        val stockStart = t0 + 6 * 60_000L
        val stockEnd = open - 60_000L
        var cap = floor((stockEnd - stockStart) * ratePerMs(preOpenStockers)).toInt()
        var cum = 0
        fun stage(fixture: String, ean: String, epcs: List<String>, reason: String) {
            cum += epcs.size
            val due = stockStart + (cum / ratePerMs(preOpenStockers)).toLong()
            val level = view.homeLevel[Slot(fixture, ean)] ?: 1
            epcs.forEach { state.place(it, fixture, level) }
            log.moves += MoveLog(due, reason, fixture, ean, epcs)
            log.wire += moveEvent(due, "staff-preopen", fixture, epcs, log.nextSeq())
        }
        for (stray in strayReturns(state, view)) {
            if (cap < stray.epcs.size) break
            cap -= stray.epcs.size
            stage(stray.fixture, stray.ean, stray.epcs.map { state.pickUpEpc(it) }, "stray-return")
        }
        for (slot in deficitOrder(state, view, 1.0)) {
            if (cap <= 0) break
            val n = min(min(view.required.getValue(slot) - state.count(slot.fixture, slot.ean), state.backroomCount(slot.ean)), cap)
            if (n <= 0) continue
            cap -= n
            stage(slot.fixture, slot.ean, takeFromBackroom(state, slot.ean, n), "stocking")
        }
        val openOnHand = floorCounts(state)
        if (issue) {
            for ((slot, req) in view.required) {
                if (req > 0 && state.count(slot.fixture, slot.ean) == 0) {
                    unfilled[slot] = if (state.backroomCount(slot.ean) == 0) "no-stock" else "capacity"
                }
            }
        }

        // 3. quiet-hour walks and the day, interleaved by time ---------------------------------------------
        val curve = shape.curve
        val quiet = curve.indices.sortedWith(compareBy({ curve[it] }, { it })).take(knobs.quietHours).sorted()
        for (idx in quiet) {
            val walkAt = open + idx * 3_600_000L + 5 * 60_000L
            queue += Queued(walkAt, qseq++) {
                val rate = ratePerMs(topUpStockers)
                var budget = floor(45 * 60_000L * rate).toInt()
                var done = 0
                for (slot in deficitOrder(state, view, knobs.topUpThreshold)) {
                    if (budget <= 0) break
                    val n = min(min(view.required.getValue(slot) - state.count(slot.fixture, slot.ean), state.backroomCount(slot.ean)), budget)
                    if (n <= 0) continue
                    budget -= n
                    done += n
                    val epcs = takeFromBackroom(state, slot.ean, n)
                    val due = walkAt + (done / rate).toLong()
                    queue += Queued(due, qseq++) {
                        epcs.forEach { state.place(it, slot.fixture, view.homeLevel[Slot(slot.fixture, slot.ean)] ?: 1) }
                        log.moves += MoveLog(due, "top-up", slot.fixture, slot.ean, epcs)
                        log.wire += moveEvent(due, "staff-quiet-hour", slot.fixture, epcs, log.nextSeq())
                    }
                }
            }
        }
        if (knobs.periods) scheduleCount(state, log, view, date, open, curve, seed) { due, action -> queue += Queued(due, qseq++, action) }
        fun drain(untilMs: Long) {
            while (queue.isNotEmpty() && queue.peek().dueMs <= untilMs) queue.poll().action()
        }

        val sink = if (knobs.samples) MemorySampleSink() else null
        val run = behavior.runDay(
            date,
            PlanTimeline.single(view.plan),
            seed,
            1.0,
            sink,
            null,
            hooks,
            { entryMs -> drain(entryMs) },
            shape,
        )
        drain(Long.MAX_VALUE)
        check(state.count(UnitState.IN_TRANSIT) == 0) { "units still in transit at close" }
        state.asOf = date
        if (knobs.periods) state.planPeriod = periodStart

        // 4. sales on the wire --------------------------------------------------------------------------
        val saleUnits = ArrayList<SaleUnit>()
        run.records.forEachIndexed { i, r ->
            val epcs = r.lines.mapNotNull { it.epc }
            r.lines.forEach { l ->
                val e = l.epc ?: return@forEach
                val (fx, lvl, _) = log.taken.getValue(e)
                saleUnits += SaleUnit(e, l.cell.ean, fx, lvl, l.cell.priceUsd, r.id, r.exitMs)
            }
            if (epcs.isEmpty()) return@forEachIndexed
            val id = "sd-${slug()}-$date-${r.id.substringAfterLast('-')}"
            val sale = if (epcs.size == 1) {
                com.m8trx.twin.connect.model.webhook.SaleEvent.byEpc(id, Instant.ofEpochMilli(r.exitMs).toString(), epcs[0])
            } else {
                com.m8trx.twin.connect.model.webhook.SaleEvent.byEpcList(id, Instant.ofEpochMilli(r.exitMs).toString(), epcs)
            }
            log.wire += WireEvent(
                r.exitMs,
                2,
                log.nextSeq(),
                "sale",
                ConnectMappers.snake.valueToTree<com.fasterxml.jackson.databind.JsonNode>(sale),
                mapOf("store" to slug(), "shopper" to r.id, "units" to epcs.size, "occurred_at" to Instant.ofEpochMilli(r.exitMs).toString()),
            )
        }
        if (sink != null) {
            run.records.forEach { r ->
                log.wire += WireEvent(
                    r.entryMs,
                    3,
                    log.nextSeq(),
                    "person_samples_ref",
                    mapOf("file" to "samples.jsonl", "shopper" to r.id, "samples" to (sink.streams[r.id]?.size ?: 0)),
                    mapOf("store" to slug(), "shopper" to r.id),
                )
            }
        }
        check(state.soldEpcs().size - startSold == saleUnits.size) { "sold units and sale lines disagree" }
        return DayResult(
            date, run.records, log.wire.sortedWith(compareBy({ it.dueMs }, { it.rank }, { it.seq })), log.moves.sortedBy { it.dueMs },
            saleUnits, received, requests, startUnits, state.totalNotSold(), openOnHand,
            floorCounts(
                state,
            ),
            log.misplaced, sink, run.expectedVisitors, dayPlan,
            if (knobs.periods) view.required else baseView.required,
            if (issue) view.period else null,
            if (resetNeeded) from else null,
            unfilled,
            log.counts.sortedBy { it.startMs },
        )
    }

    // ── the reset (slice 2c) ──────────────────────────────────────────────────────────────────────────

    /**
     * Carry out a plan change before the delivery: for every slot whose requirement changed, the units above the new requirement
     * go to another slot of the same EAN that now needs them (a style swap: fixture to fixture) or to the back room; then each
     * slot that needs more is filled from the back-room stock the store already has (a new style that is on the truck is filled after
     * the delivery by the ordinary stocking). Staff work at [rate] units a millisecond from [startMs] and stop at [endMs]; whatever
     * is left is the ordinary stocking's. Returns the units moved. Every move is a wire event and a [MoveLog] with a `reset-*` reason.
     */
    private fun resetFloor(state: StoreState, log: DayLog, view: PeriodView, was: Map<Slot, Int>, startMs: Long, endMs: Long, rate: Double): Int {
        var cum = 0
        var cap = floor((endMs - startMs) * rate).toInt()
        fun stage(dest: String, ean: String, epcs: List<String>, reason: String) {
            cum += epcs.size
            val due = startMs + (cum / rate).toLong()
            val level = view.homeLevel[Slot(dest, ean)] ?: 1
            epcs.forEach { state.place(it, dest, level) }
            log.moves += MoveLog(due, reason, dest, ean, epcs)
            log.wire += moveEvent(due, "staff-reset", dest, epcs, log.nextSeq())
        }
        val now = view.required
        val changed = (was.keys + now.keys).filter { (was[it] ?: 0) != (now[it] ?: 0) }.toSortedSet()
        // what each slot of an EAN still needs once its own surplus has gone: the receiving end of a swap
        val need = HashMap<Slot, Int>()
        changed.forEach { s -> need[s] = max(0, (now[s] ?: 0) - state.count(s.fixture, s.ean)) }
        var moved = 0
        // 1. surplus out: to a slot of the same EAN that needs it, else to the back room
        for (s in changed) {
            val surplus = state.count(s.fixture, s.ean) - (now[s] ?: 0)
            if (surplus <= 0 || cap <= 0) continue
            var left = min(surplus, cap)
            val takers = changed.filter { it.ean == s.ean && it != s && (need[it] ?: 0) > 0 }
            for (t in takers) {
                if (left <= 0) break
                val n = min(left, need.getValue(t))
                val epcs = (0 until n).mapNotNull { state.pickUp(s.fixture, s.ean) }
                if (epcs.isEmpty()) break
                need[t] = need.getValue(t) - epcs.size
                left -= epcs.size
                cap -= epcs.size
                moved += epcs.size
                stage(t.fixture, t.ean, epcs, "reset-swap")
            }
            if (left > 0) {
                val epcs = (0 until left).mapNotNull { state.pickUp(s.fixture, s.ean) }
                if (epcs.isNotEmpty()) {
                    cap -= epcs.size
                    moved += epcs.size
                    stage(rackFor(state, s.ean), s.ean, epcs, "reset-displace")
                }
            }
        }
        // 2. fills from the back room, emptiest slot first
        val fills = changed.filter { (now[it] ?: 0) > state.count(it.fixture, it.ean) }
            .sortedWith(compareBy({ state.count(it.fixture, it.ean).toDouble() / now.getValue(it) }, { it.fixture }, { it.ean }))
        for (s in fills) {
            if (cap <= 0) break
            val n = min(min((now[s] ?: 0) - state.count(s.fixture, s.ean), state.backroomCount(s.ean)), cap)
            if (n <= 0) continue
            val epcs = takeFromBackroom(state, s.ean, n)
            cap -= epcs.size
            moved += epcs.size
            stage(s.fixture, s.ean, epcs, "reset-fill")
        }
        return moved
    }

    // ── the weekly count (slice 2c) ───────────────────────────────────────────────────────────────────

    /** The fixtures counted in the week of [periodStart]: one of [1 / countShare] groups of the floor fixtures, in a fixed seeded order. */
    fun countedFixtures(periodStart: LocalDate): List<String> {
        if (knobs.countShare <= 0.0) return emptyList()
        val fixtures = viewFor(periodStart).period.fixtures
        val order = fixtures.sortedBy { HistoryCsv.derive(behavior.params.seed, slug(), "count-order", it) }
        val groups = max(1, Math.round(1.0 / knobs.countShare).toInt())
        val g = Math.floorMod(PeriodPlans.weekIndex(periodStart), groups.toLong()).toInt()
        return order.filterIndexed { i, _ -> i % groups == g }.sorted()
    }

    /** The day of the period's count: Tuesday to Thursday by seed, or the next open day if that one is closed; null if none is open. */
    fun countDay(periodStart: LocalDate): LocalDate? {
        val pick = 1 + Math.floorMod(HistoryCsv.derive(behavior.params.seed, slug(), periodStart, "count-day"), 3L).toInt()
        return (pick until 7).map { periodStart.plusDays(it.toLong()) }.firstOrNull { !overlay.plan(it).closed }
    }

    /**
     * If [date] is the period's count day, queue the count: at the day's quietest hour (leaving two hours before closing), each
     * counted fixture read in turn, reading exactly the units the store has ON it at that moment (a unit in a shopper's hand is not
     * on a fixture). The wire plan gets the open, one pull per frame at its end time, and the close.
     */
    private fun scheduleCount(
        state: StoreState,
        log: DayLog,
        view: PeriodView,
        date: LocalDate,
        open: Long,
        curve: List<Double>,
        seed: Long,
        schedule: (Long, () -> Unit) -> Unit,
    ) {
        val start = view.period.start
        if (countDay(start) != date) return
        val fixtures = countedFixtures(start)
        if (fixtures.isEmpty()) return
        val usable = curve.indices.filter { it <= curve.size - 3 }.ifEmpty { listOf(0) }
        val hour = usable.minWith(compareBy({ curve[it] }, { it }))
        val startMs = open + hour * 3_600_000L
        val countId = StockCounts.uuid("twin-stock", slug(), date, behavior.params.seed, 0)
        val rng = kotlin.random.Random(HistoryCsv.derive(behavior.params.seed, slug(), date, "count"))
        val pulls = ArrayList<CountPull>()
        val spaceId = com.m8trx.twin.history.ChainKeys.load().store(slug()).salesFloorSpaceId
        val stub = StockCounts.CountPlan(countId, slug(), date, 0, false, emptyList(), emptyList(), emptyList())
        fun step(due: Long, kind: String, method: String, path: String, body: Any?, ids: Map<String, Any?>) {
            log.wire += WireEvent(
                due,
                1,
                log.nextSeq(),
                "count",
                ConnectMappers.camel.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                    mapOf("step" to kind, "method" to method, "path" to path, "body" to body),
                ),
                mapOf("store" to slug(), "count" to countId) + ids,
            )
        }
        val scope = fixtures.map { view.period.zoneIdOf.getValue(it) }
        val open0 = StockDrive.openBody(stub, "@site", spaceId).toMutableMap().also { it["scopeZoneIds"] = scope }
        schedule(startMs) {
            step(startMs, "open", "POST", "/count", open0, mapOf("fixtures" to fixtures.size))
            fun pull(i: Int, at: Long) {
                val code = fixtures[i]
                val epcs = state.eansAt(code).toList().sorted().flatMap { state.epcsAt(code, it) }
                val reads = state.eansAt(code).associateWith { state.count(code, it) }
                val p = StockCounts.truthPull(countId, code, view.period.zoneIdOf.getValue(code), epcs, Instant.ofEpochMilli(at), rng)
                val pullStub = StockCounts.CountPlan(countId, slug(), date, 0, false, listOf(p), emptyList(), emptyList())
                pulls += CountPull(code, at, reads, epcs.size)
                for (f in p.frames) {
                    step(
                        f.endedAt.toEpochMilli(),
                        "pull",
                        "POST",
                        "/count/pulls",
                        StockDrive.pullBody(pullStub, p, f, "@site", spaceId),
                        mapOf("fixture" to code, "seq" to f.seq, "tags" to f.tags.size),
                    )
                }
                val end = p.endedAt.toEpochMilli()
                if (i + 1 < fixtures.size) {
                    schedule(end + 20_000L) { pull(i + 1, end + 20_000L) }
                } else {
                    val closeAt = end + 10_000L
                    step(closeAt, "close", "POST", "/count/$countId/close", mapOf("reason" to "route_complete"), emptyMap())
                    log.counts += CountRecord(countId, date, startMs, closeAt, pulls.toList())
                }
            }
            pull(0, startMs + 30_000L)
        }
    }

    // ── planning helpers ──────────────────────────────────────────────────────────────────────────────

    /**
     * What to order today, EAN → units. The back room is refilled to its stock-plan cover after the floor's deficit is
     * taken out of it: `shortfall = cover − (back room − floor deficit)`, in cases of 6 (bike lines: single units) and
     * rounded to the nearest case, so slow lines are not over-ordered and the back room stays within half a case of cover.
     * With periods (slice 2c) a style the plan has just put on the floor with no cover of its own is ordered up to what the floor lacks.
     */
    fun planOrder(state: StoreState, view: PeriodView = baseView): Map<String, Int> {
        val deficit = HashMap<String, Int>()
        view.required.forEach { (slot, req) -> deficit.merge(slot.ean, max(0, req - state.count(slot.fixture, slot.ean)), Int::plus) }
        val out = java.util.TreeMap<String, Int>()
        val eans = if (knobs.periods) (model.backroomTarget.keys + deficit.keys).toSortedSet() else model.backroomTarget.keys
        for (ean in eans) {
            val cover = model.backroomTarget[ean] ?: 0
            val cs = model.caseSize(ean)
            val back = state.backroomCount(ean)
            val shortfall = cover - (back - (deficit[ean] ?: 0))
            var cases = floor((shortfall + cs / 2.0) / cs).toInt()
            if (knobs.periods) cases = max(cases, Math.ceil(max(0, (deficit[ean] ?: 0) - back).toDouble() / cs).toInt())
            if (cases > 0) out[ean] = cases * cs
        }
        return out
    }

    /** Slots below [share] of their requirement that have back-room stock, emptiest first, then fastest-selling. */
    private fun deficitOrder(state: StoreState, view: PeriodView, share: Double): List<Slot> = view.required.filter { (slot, req) ->
        req > 0 && state.count(slot.fixture, slot.ean) < req * share - 1e-9 && state.backroomCount(slot.ean) > 0
    }.keys.sortedWith(
        compareBy({
            state.count(it.fixture, it.ean).toDouble() / view.required.getValue(it)
        }, { -(demandRank[it.ean] ?: 0.0) }, { it.fixture }, { it.ean }),
    )

    private data class Stray(val ean: String, val fixture: String, val epcs: List<String>)

    /** Units sitting on a fixture that has no plan line for them (a put-back that landed wrong), with the slot to return them to. */
    private fun strayReturns(state: StoreState, view: PeriodView): List<Stray> {
        val out = ArrayList<Stray>()
        val homeDeficit = HashMap<Slot, Int>()
        val slotsByEan = view.required.keys.groupBy { it.ean }
        for (loc in state.locations().filter { !state.isBackroomLoc(it) }) {
            for (ean in state.eansAt(loc).toList()) {
                if (Slot(loc, ean) in view.required) continue
                val homes = slotsByEan[ean] ?: continue
                val epcs = state.epcsAt(loc, ean)
                val home = homes.sortedWith(
                    compareBy({ -(view.required.getValue(it) - state.count(it.fixture, it.ean) - (homeDeficit[it] ?: 0)) }, { it.fixture }),
                ).first()
                homeDeficit.merge(home, epcs.size, Int::plus)
                out += Stray(ean, home.fixture, epcs)
            }
        }
        return out.groupBy { it.ean to it.fixture }.map { (k, v) -> Stray(k.first, k.second, v.flatMap { it.epcs }) }
            .sortedWith(compareBy({ it.fixture }, { it.ean }))
    }

    /** Floor units per (fixture, EAN), plan slots and strays alike. */
    fun floorCounts(state: StoreState): Map<Slot, Int> {
        val out = java.util.TreeMap<Slot, Int>()
        for (loc in state.locations()) {
            if (state.isBackroomLoc(loc)) continue
            for (ean in state.eansAt(loc)) out[Slot(loc, ean)] = state.count(loc, ean)
        }
        return out
    }

    /** A fully stocked day: no state, every cell always has a unit. The calibration and the demand check read this. */
    fun stockedDay(date: LocalDate, seed: Long): BehaviorModel.DayRun {
        val log = DayLog(date)
        return behavior.runDay(date, PlanTimeline.single(plan), seed, model.visitScale, null, null, Hooks(null, log, baseView))
    }
}
