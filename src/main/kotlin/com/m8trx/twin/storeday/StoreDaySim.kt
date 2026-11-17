package com.m8trx.twin.storeday

import com.m8trx.twin.behavior.BehaviorModel
import com.m8trx.twin.behavior.Cell
import com.m8trx.twin.behavior.FloorHooks
import com.m8trx.twin.behavior.MemorySampleSink
import com.m8trx.twin.behavior.PlanTimeline
import com.m8trx.twin.behavior.ShelfPlan
import com.m8trx.twin.behavior.ShopperRecord
import com.m8trx.twin.connect.model.ConnectMappers
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
)

/**
 * Slice 2a: one stock-aware store day. Pre-open delivery and stocking, shoppers against the real units on the fixtures
 * (the behavior model's own agents, through [FloorHooks]), quiet-hour top-ups, and the wire plan and truth that fall out.
 * Offline and record-only: this class never opens a socket.
 */
class StoreDaySim(val model: StoreDayModel, val behavior: BehaviorModel, val cal: Calibration, val knobs: Knobs = Knobs()) {
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

    /** Cells of one EAN share that EAN's demand by facings. */
    private val weights = IdentityHashMap<Cell, Double>().also { w ->
        plan.allCells.groupBy { it.ean }.forEach { (ean, cs) ->
            val total = cs.sumOf { it.facings }.toDouble()
            val share = model.demand[ean]?.share ?: 1e-7
            cs.forEach { c -> w[c] = Math.pow(share * c.facings / total, cal.cellWeightPower) }
        }
    }

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

    private val demandRank: Map<String, Double> = model.demand.mapValues { it.value.share }

    // ── hooks ─────────────────────────────────────────────────────────────────────────────────────────

    private inner class Hooks(
        val state: StoreState?,
        val log: DayLog,
        val conversion: Double = 1.0,
        val schedule: (Long, () -> Unit) -> Unit = { _, a -> a() },
    ) : FloorHooks {
        override fun cellWeight(c: Cell): Double = weights[c] ?: 1e-7

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
                st.place(epc, to, model.homeLevel[Slot(to, c.ean)] ?: 1)
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
        var misplaced = 0
        private var seq = 0
        fun nextSeq(): Int = seq++
    }

    private fun slug() = model.slug

    private fun moveEvent(dueMs: Long, actor: String, toFixture: String, epcs: List<String>, seq: Int): WireEvent {
        val d = LocalDate.ofInstant(Instant.ofEpochMilli(dueMs), zone)
        val payload = com.m8trx.twin.connect.model.webhook.InventoryMovement(
            externalMovementId = "sd-${slug()}-$d-m$seq",
            toFixtureCode = toFixture,
            items = epcs.map { com.m8trx.twin.connect.model.webhook.MovementItem(epc = it) },
        )
        return WireEvent(
            dueMs,
            1,
            seq,
            "move",
            ConnectMappers.snake.valueToTree<com.fasterxml.jackson.databind.JsonNode>(payload),
            mapOf("store" to slug(), "actor" to actor, "units" to epcs.size),
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

    fun simulate(state: StoreState, date: LocalDate, seed: Long): DayResult {
        val log = DayLog(date)
        val dayPlan = overlay.plan(date)
        val startUnits = state.totalNotSold()
        if (dayPlan.closed) {
            // A closed day: no delivery, no staff, no shoppers. Tomorrow's order covers what today would have.
            state.asOf = date
            val onHand = floorCounts(state)
            return DayResult(
                date, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0, startUnits, startUnits, onHand, onHand, 0, null, 0.0,
                dayPlan,
            )
        }
        val shape = dayPlan.shape!!
        val openTime = shape.open
        val open = ZonedDateTime.of(date, openTime, zone).toInstant().toEpochMilli()
        val t0 = open - model.deliveryLeadMin * 60_000L
        val startSold = state.soldEpcs().size
        val queue = PriorityQueue<Queued>(compareBy({ it.dueMs }, { it.seq }))
        var qseq = 0L
        val hooks = Hooks(state, log, dayPlan.conversion) { due, action -> queue += Queued(due, qseq++, action) }
        val ratePerMs = { stockers: Int -> stockers * knobs.staffUnitsPerHour / 3_600_000.0 }

        // 1. delivery ---------------------------------------------------------------------------------
        val order = planOrder(state)
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
            val level = model.homeLevel[Slot(fixture, ean)] ?: 1
            epcs.forEach { state.place(it, fixture, level) }
            log.moves += MoveLog(due, reason, fixture, ean, epcs)
            log.wire += moveEvent(due, "staff-preopen", fixture, epcs, log.nextSeq())
        }
        for (stray in strayReturns(state)) {
            if (cap < stray.epcs.size) break
            cap -= stray.epcs.size
            stage(stray.fixture, stray.ean, stray.epcs.map { state.pickUpEpc(it) }, "stray-return")
        }
        for (slot in deficitOrder(state, 1.0)) {
            if (cap <= 0) break
            val n = min(min(model.required.getValue(slot) - state.count(slot.fixture, slot.ean), state.backroomCount(slot.ean)), cap)
            if (n <= 0) continue
            cap -= n
            stage(slot.fixture, slot.ean, takeFromBackroom(state, slot.ean, n), "stocking")
        }
        val openOnHand = floorCounts(state)

        // 3. quiet-hour walks and the day, interleaved by time ---------------------------------------------
        val curve = shape.curve
        val quiet = curve.indices.sortedWith(compareBy({ curve[it] }, { it })).take(knobs.quietHours).sorted()
        for (idx in quiet) {
            val walkAt = open + idx * 3_600_000L + 5 * 60_000L
            queue += Queued(walkAt, qseq++) {
                val rate = ratePerMs(topUpStockers)
                var budget = floor(45 * 60_000L * rate).toInt()
                var done = 0
                for (slot in deficitOrder(state, knobs.topUpThreshold)) {
                    if (budget <= 0) break
                    val n = min(min(model.required.getValue(slot) - state.count(slot.fixture, slot.ean), state.backroomCount(slot.ean)), budget)
                    if (n <= 0) continue
                    budget -= n
                    done += n
                    val epcs = takeFromBackroom(state, slot.ean, n)
                    val due = walkAt + (done / rate).toLong()
                    queue += Queued(due, qseq++) {
                        epcs.forEach { state.place(it, slot.fixture, model.homeLevel[Slot(slot.fixture, slot.ean)] ?: 1) }
                        log.moves += MoveLog(due, "top-up", slot.fixture, slot.ean, epcs)
                        log.wire += moveEvent(due, "staff-quiet-hour", slot.fixture, epcs, log.nextSeq())
                    }
                }
            }
        }
        fun drain(untilMs: Long) {
            while (queue.isNotEmpty() && queue.peek().dueMs <= untilMs) queue.poll().action()
        }

        val sink = if (knobs.samples) MemorySampleSink() else null
        val run = behavior.runDay(
            date,
            PlanTimeline.single(plan),
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
        )
    }

    // ── planning helpers ──────────────────────────────────────────────────────────────────────────────

    /**
     * What to order today, EAN → units. The back room is refilled to its stock-plan cover after the floor's deficit is
     * taken out of it: `shortfall = cover − (back room − floor deficit)`, in cases of 6 (bike lines: single units) and
     * rounded to the nearest case, so slow lines are not over-ordered and the back room stays within half a case of cover.
     */
    fun planOrder(state: StoreState): Map<String, Int> {
        val deficit = HashMap<String, Int>()
        model.required.forEach { (slot, req) -> deficit.merge(slot.ean, max(0, req - state.count(slot.fixture, slot.ean)), Int::plus) }
        val out = java.util.TreeMap<String, Int>()
        for ((ean, cover) in model.backroomTarget) {
            val cs = model.caseSize(ean)
            val shortfall = cover - (state.backroomCount(ean) - (deficit[ean] ?: 0))
            val cases = floor((shortfall + cs / 2.0) / cs).toInt()
            if (cases > 0) out[ean] = cases * cs
        }
        return out
    }

    /** Slots below [share] of their requirement that have back-room stock, emptiest first, then fastest-selling. */
    private fun deficitOrder(state: StoreState, share: Double): List<Slot> = model.required.filter { (slot, req) ->
        req > 0 && state.count(slot.fixture, slot.ean) < req * share - 1e-9 && state.backroomCount(slot.ean) > 0
    }.keys.sortedWith(
        compareBy({
            state.count(it.fixture, it.ean).toDouble() / model.required.getValue(it)
        }, { -(demandRank[it.ean] ?: 0.0) }, { it.fixture }, { it.ean }),
    )

    private data class Stray(val ean: String, val fixture: String, val epcs: List<String>)

    /** Units sitting on a fixture that has no plan line for them (a put-back that landed wrong), with the slot to return them to. */
    private fun strayReturns(state: StoreState): List<Stray> {
        val out = ArrayList<Stray>()
        val homeDeficit = HashMap<Slot, Int>()
        val slotsByEan = model.required.keys.groupBy { it.ean }
        for (loc in state.locations().filter { !state.isBackroomLoc(it) }) {
            for (ean in state.eansAt(loc).toList()) {
                if (Slot(loc, ean) in model.required) continue
                val homes = slotsByEan[ean] ?: continue
                val epcs = state.epcsAt(loc, ean)
                val home = homes.sortedWith(
                    compareBy({ -(model.required.getValue(it) - state.count(it.fixture, it.ean) - (homeDeficit[it] ?: 0)) }, { it.fixture }),
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
        return behavior.runDay(date, PlanTimeline.single(plan), seed, model.visitScale, null, null, Hooks(null, log))
    }
}
