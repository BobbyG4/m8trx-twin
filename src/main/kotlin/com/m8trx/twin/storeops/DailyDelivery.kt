package com.m8trx.twin.storeops

import com.fasterxml.jackson.databind.JsonNode
import com.m8trx.twin.connect.Sgtin96
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.bearer.ItemReceiveRequest
import com.m8trx.twin.connect.model.bearer.ReceiveItem
import com.m8trx.twin.connect.model.webhook.InventoryMovement
import com.m8trx.twin.connect.model.webhook.MovementItem
import com.m8trx.twin.storeday.Slot
import com.m8trx.twin.storeday.StoreDayModel
import com.m8trx.twin.storeday.StoreState
import java.time.LocalDate

/**
 * The daily delivery, PURE (nothing is read or sent here): every store-local morning before opening a delivery replaces what the store
 * sold, then staff put the stock on the shelves.
 *
 *  1. **Order** per EAN = target - on hand, only the positive gaps, each rounded to the NEAREST whole case ([StoreDayModel.caseSize]: 6, bikes singles; half a case rounds up).
 *     target = the period plan's floor quantity + the back-room cover (`StoreDayModel.backroomTarget`); on hand = every unit the ledger
 *     holds (floor, back room, anywhere). It replaces what sold and heals any drift.
 *  2. **Receive** the order into the stockroom as new EPCs minted from the store's delivery serial block (never one the ledger has used).
 *  3. **Put-away**: every received unit goes by `inventory_movement` (`to_zone_id` only) to its EAN's back-room RACK.
 *  4. **Top-up**: from the back room (units already there, oldest first, then the received) to the floor cells below their plan quantity,
 *     most-short first, until the floor reaches plan or the back room runs out of that EAN. Never past plan, never more than the back room holds.
 */
object DailyDelivery {
    /** A live receive request carries at most 50 tags (a 200-tag request got a 504). */
    const val RECEIVE_CHUNK = 50

    /** A move batch carries at most 25 units; one batch every 7.5 s is 200 items a minute, the platform's bulk-move ceiling. */
    const val MOVE_CHUNK = 25
    const val MOVE_SPACING_MS = 7_500L

    /** While a receive lane runs the cap is 150 items a minute: one 25-unit batch every 10 s. */
    const val MOVE_SPACING_WITH_RECEIVES_MS = 10_000L
    const val MAX_ITEMS_PER_MINUTE = 200

    /** One expected receive request: what the daily run estimates for it (one request of 50 tags, measured at about 1 s a tag). */
    const val RECEIVE_EST_MS = 50_000L

    class OrderLine(val ean: String, val target: Int, val onHand: Int, val qty: Int) {
        val gap: Int get() = target - onHand
    }

    /** Positive gaps only, in EAN order, each rounded to the nearest case of [caseSize] (shortfall 1-2 orders nothing, 3-8 one case, 9-14 two); a line that rounds to 0 is dropped. */
    fun order(floorTarget: Map<String, Int>, backTarget: Map<String, Int>, onHand: Map<String, Int>, caseSize: (String) -> Int): List<OrderLine> =
        (floorTarget.keys + backTarget.keys).toSortedSet().mapNotNull { ean ->
            val target = (floorTarget[ean] ?: 0) + (backTarget[ean] ?: 0)
            val have = onHand[ean] ?: 0
            val gap = target - have
            val cs = caseSize(ean)
            val qty = if (gap <= 0) 0 else Math.floorDiv(gap + cs / 2, cs) * cs
            if (qty <= 0) null else OrderLine(ean, target, have, qty)
        }

    class Plan(
        val slug: String,
        val date: LocalDate,
        val order: List<OrderLine>,
        val received: List<String>,
        val putAway: List<ResetPlanner.Move>,
        val topUp: List<ResetPlanner.Move>,
        val items: List<SendItem>,
        val nextSerial: Long,
        val onHandUnits: Int,
        val targetUnits: Int,
        val floorShort: Int,
        val topUpUnits: Int,
    ) {
        val receiveRequests: Int get() = items.count { it.kind == "receive" }
        val orderUnits: Int get() = order.sumOf { it.qty }

        /**
         * Minutes: phase 1 runs the receive lane and the put-away lane side by side (the longer one), phase 2 the top-up, plus about a minute
         * for the results route to confirm the last batches.
         */
        fun estimateMinutes(receiveLanes: Int = 1): Double {
            val receive = receiveRequests * RECEIVE_EST_MS / maxOf(1, receiveLanes)
            val phase1 = maxOf(receive, putAway.size * MOVE_SPACING_WITH_RECEIVES_MS)
            return (phase1 + topUp.size * MOVE_SPACING_MS + 60_000) / 60_000.0
        }
    }

    /** First serial a mint may take: the store's delivery base, or one past the highest ledger serial inside its block. */
    fun serialStart(slug: String, reserved: Set<String>): Long {
        val base = StoreState.deliveryBase(slug)
        val taken = reserved.mapNotNull { runCatching { Sgtin96.serial(it) }.getOrNull() }.filter { it >= base && it < StoreState.deliveryEnd(slug) }
        return maxOf(base, (taken.maxOrNull() ?: (base - 1)) + 1)
    }

    fun ymd(date: LocalDate): String = date.toString().replace("-", "")

    /**
     * Phase 2's top-up batches without any unit whose put-away is not confirmed: a unit received in THIS plan (it is in a put-away batch) whose
     * batch's extId is not in [confirmedPutAways] is dropped (it stays in the stockroom). Returns the kept items and the dropped EPCs.
     */
    fun dropUnconfirmed(topUps: List<SendItem>, putAways: List<SendItem>, confirmedPutAways: Set<String>): Pair<List<SendItem>, List<String>> {
        val unconfirmed = putAways.filter { it.extId !in confirmedPutAways }.flatMap { it.epcs }.toSet()
        val dropped = ArrayList<String>()
        val kept = topUps.mapNotNull { m ->
            val bad = m.epcs.filter { it in unconfirmed }
            if (bad.isEmpty()) return@mapNotNull m
            dropped += bad
            val good = m.epcs.filter { it !in unconfirmed }
            if (good.isEmpty()) return@mapNotNull null
            val payload = m.payload.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            val arr = payload.putArray("items")
            good.forEach { arr.addObject().put("epc", it).put("quantity", 1) }
            val id = "${m.extId}f${SendItem.hash(good.joinToString(","))}"
            payload.put("external_movement_id", id)
            SendItem(m.seq, m.dueMs, m.rank, m.kind, id, payload, good)
        }
        return kept to dropped
    }

    fun isTopUp(item: SendItem): Boolean = item.kind == "move" && "-t-" in item.extId

    fun isPutAway(item: SendItem): Boolean = item.kind == "move" && "-p-" in item.extId

    /** The units of each EAN the ledger holds, any location; EPCs that do not decode, or whose EAN is not in [known], are not counted. */
    fun onHandByEan(byFixture: Map<String, List<String>>, known: Set<String>): Map<String, Int> {
        val out = HashMap<String, Int>()
        byFixture.values.forEach { l ->
            l.forEach { epc ->
                val ean = runCatching { Sgtin96.decodeEan(epc) }.getOrNull()
                if (ean != null && ean in known) out.merge(ean, 1, Int::plus)
            }
        }
        return out
    }

    fun plan(
        slug: String,
        date: LocalDate,
        model: StoreDayModel,
        required: Map<Slot, Int>,
        byFixture: Map<String, List<String>>,
        reserved: Set<String>,
        /** Sales-floor fixture code to zone id (the keys export). */
        zoneIds: Map<String, String>,
        rackDefault: String = "BR-01",
        receiveChunk: Int = RECEIVE_CHUNK,
        moveChunk: Int = MOVE_CHUNK,
    ): Plan {
        val known = model.demand.keys + required.keys.map { it.ean } + model.backroomTarget.keys
        val floorTarget = required.entries.filter { it.value > 0 }.groupBy({ it.key.ean }, { it.value }).mapValues { (_, v) -> v.sum() }
        val have = onHandByEan(byFixture, known)
        val order = order(floorTarget, model.backroomTarget, have, model::caseSize)
        fun rackOf(ean: String) = model.homeRack[ean] ?: rackDefault

        // ── mint ──
        var serial = serialStart(slug, reserved)
        val received = ArrayList<String>()
        val receivedByEan = java.util.TreeMap<String, ArrayList<String>>()
        for (line in order) {
            repeat(line.qty) {
                var epc: String
                do {
                    epc = Sgtin96.encode(line.ean, serial++)
                } while (epc in reserved)
                received += epc
                receivedByEan.getOrPut(line.ean) { ArrayList() } += epc
            }
        }
        check(serial <= StoreState.deliveryEnd(slug)) { "$slug: the delivery overran its serial block" }

        // ── put-away: every received unit to its EAN's rack ──
        val putAway = receivedByEan.flatMap { (ean, epcs) -> epcs.chunked(moveChunk).map { ResetPlanner.Move(rackOf(ean), ean, it) } }

        // ── top-up: back room to the cells below plan, most short first ──
        val floorHave = HashMap<Slot, Int>()
        val backUnits = HashMap<String, ArrayList<String>>()
        byFixture.toSortedMap().forEach { (loc, epcs) ->
            epcs.sorted().forEach { epc ->
                val ean = runCatching { Sgtin96.decodeEan(epc) }.getOrNull() ?: return@forEach
                if (loc.startsWith("BR-")) {
                    backUnits.getOrPut(ean) { ArrayList() } += epc
                } else {
                    floorHave.merge(Slot(loc, ean), 1, Int::plus)
                }
            }
        }
        val cellsOf = required.entries.filter { it.value > 0 }.groupBy({ it.key.ean }, { it.key })
        val topMoves = java.util.TreeMap<Pair<String, String>, ArrayList<String>>(compareBy({ it.first }, { it.second }))
        var floorShort = 0
        var topUpUnits = 0
        for (ean in (cellsOf.keys).toSortedSet()) {
            val deficit = cellsOf.getValue(ean).sorted().associateWith { maxOf(0, (required[it] ?: 0) - (floorHave[it] ?: 0)) }.toMutableMap()
            floorShort += deficit.values.sum()
            val supply = ArrayDeque<String>().also {
                it.addAll(backUnits[ean].orEmpty())
                it.addAll(receivedByEan[ean].orEmpty())
            }
            while (supply.isNotEmpty()) {
                val cell = deficit.entries.filter { it.value > 0 }.minWithOrNull(compareBy({ -it.value }, { it.key })) ?: break
                topMoves.getOrPut(cell.key.fixture to ean) { ArrayList() } += supply.removeFirst()
                deficit[cell.key] = cell.value - 1
                topUpUnits++
            }
        }
        val topUp = topMoves.flatMap { (k, epcs) -> epcs.chunked(moveChunk).map { ResetPlanner.Move(k.first, k.second, it) } }

        // ── items ──
        val day = ymd(date)
        val items = ArrayList<SendItem>()
        var seq = 0
        var t = 0L
        received.chunked(receiveChunk).forEach { chunk ->
            val payload = ConnectMappers.camel.valueToTree<JsonNode>(ItemReceiveRequest("@site", "@backroom_space", chunk.map { ReceiveItem(it) }))
            items += SendItem(seq++, t, 0, "receive", "dly-$slug-$day-r-${SendItem.hash(chunk.joinToString(","))}", payload, chunk)
            t += RECEIVE_EST_MS
        }
        fun moveItem(m: ResetPlanner.Move, phase: String): SendItem {
            val id = "dly-$slug-$day-$phase-${SendItem.hash(m.dest, m.ean, m.epcs.joinToString(","))}"
            val mv = InventoryMovement(
                externalMovementId = id,
                // every move goes by to_zone_id, never to_fixture_code (see StoreDayReset.schedule); a rack is a placeholder resolved at run time
                toFixtureCode = null,
                toZoneId = if (m.toBack) {
                    "${Placeholders.ZONE_PREFIX}${m.dest}"
                } else {
                    zoneIds[m.dest] ?: error("no zone id for fixture ${m.dest} in the keys export")
                },
                items = m.epcs.map { MovementItem(epc = it) },
            )
            return SendItem(seq++, t, 1, "move", id, ConnectMappers.snake.valueToTree(mv), m.epcs).also { t += MOVE_SPACING_MS }
        }
        putAway.forEach { items += moveItem(it, "p") }
        topUp.forEach { items += moveItem(it, "t") }

        return Plan(
            slug, date, order, received, putAway, topUp, items, serial, have.values.sum(),
            (floorTarget.values.sum() + model.backroomTarget.filterKeys { it in known }.values.sum()), floorShort, topUpUnits,
        )
    }
}

/**
 * What a store's daily order would be if it were already AT target and only the sales of the days before had to be replaced: a seeded
 * Poisson simulation of the demand (`demand.csv` units/day, the store's own hours, Saturday lift), one order a day rounded to cases, the
 * first [burnIn] days dropped. Aggregate per EAN only (the order does not depend on where a unit stands).
 */
object SteadyState {
    class Day(val units: Int, val lines: Int, val batches: Int)

    class Result(val meanUnits: Double, val meanLines: Double, val meanBatches: Double, val expectedSoldPerDay: Double, val days: Int) {
        /** One day's run: receive lane and put-away side by side (about 1 s a tag), then the top-up (as many batches again), plus a minute to verify. */
        fun minutes(): Double {
            val moves = meanBatches * DailyDelivery.MOVE_SPACING_MS
            val withReceives = meanBatches * DailyDelivery.MOVE_SPACING_WITH_RECEIVES_MS
            return (maxOf(meanUnits * 1_000, withReceives) + moves + 60_000) / 60_000.0
        }
    }

    private fun poisson(rnd: java.util.Random, mean: Double): Int {
        if (mean <= 0.0) return 0
        if (mean > 30) return maxOf(0, Math.round(mean + Math.sqrt(mean) * rnd.nextGaussian()).toInt())
        val l = Math.exp(-mean)
        var k = 0
        var p = 1.0
        do {
            k++
            p *= rnd.nextDouble()
        } while (p > l)
        return k - 1
    }

    fun rateOn(model: StoreDayModel, ean: String, day: java.time.DayOfWeek): Double {
        val d = model.demand[ean] ?: return 0.0
        return when {
            model.hoursOn(day) == null -> 0.0
            day.value <= 5 -> d.perDayWeekday
            day == java.time.DayOfWeek.SATURDAY -> d.perDayWeekend * model.saturdayLift
            else -> d.perDayWeekend
        }
    }

    fun simulate(model: StoreDayModel, required: Map<Slot, Int>, from: LocalDate, days: Int = 60, burnIn: Int = 20, seed: Long = 1L): Result {
        val floor = required.entries.filter { it.value > 0 }.groupBy({ it.key.ean }, { it.value }).mapValues { (_, v) -> v.sum() }
        val target = (floor.keys + model.backroomTarget.keys).associateWith { (floor[it] ?: 0) + (model.backroomTarget[it] ?: 0) }
        val onHand = target.toMutableMap()
        val rnd = java.util.Random(seed xor model.slug.hashCode().toLong())
        val out = ArrayList<Day>()
        var expected = 0.0
        for (i in 0 until days) {
            val date = from.minusDays((days - i).toLong())
            // the day's sales, then the next morning's order
            target.keys.forEach { ean ->
                val rate = rateOn(model, ean, date.dayOfWeek)
                if (i >= burnIn) expected += rate
                onHand[ean] = (onHand.getValue(ean) - poisson(rnd, rate)).coerceAtLeast(0)
            }
            val lines = DailyDelivery.order(floor, model.backroomTarget, onHand, model::caseSize)
            lines.forEach { onHand[it.ean] = onHand.getValue(it.ean) + it.qty }
            if (i >= burnIn) {
                val units = lines.sumOf { it.qty }
                // put-away: every unit goes to a rack in batches of 25 per EAN; top-up: about the same count again to the cells
                out += Day(units, lines.size, lines.sumOf { (it.qty + DailyDelivery.MOVE_CHUNK - 1) / DailyDelivery.MOVE_CHUNK })
            }
        }
        val n = out.size.toDouble()
        return Result(out.sumOf { it.units } / n, out.sumOf { it.lines } / n, out.sumOf { it.batches } / n, expected / n, out.size)
    }
}
