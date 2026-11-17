package com.m8trx.twin.storeops

import com.m8trx.twin.connect.Sgtin96
import com.m8trx.twin.storeday.Slot
import com.m8trx.twin.storeday.StoreState
import com.m8trx.twin.storeday.UnitState

/**
 * The reset night's mapping (brief § 1 step 2), PURE: the twin's ledger of where each unsold unit is, the period plan, and the
 * stock plan's back-room cover in; the moves, the receipts and the post-reset state out. Nothing is read or sent here.
 *
 * Per EAN, with F = the units the period plan puts on the floor (the sum of its cells' required quantity) and B = the back-room
 * cover the stock plan holds for it, and L = the unsold units the ledger has:
 *  1. each cell keeps the units already standing on its own fixture, up to its required quantity (no move);
 *  2. a cell still short takes units from the rest of the ledger, off-plan or surplus floor units first, then back-room units
 *     (a move to the cell);
 *  3. whatever is left goes to the back room (a move when it stood on the floor, none when it was already there);
 *  4. what the plan still lacks is **received** as new EPCs from the store's delivery serial block into the stockroom, and the
 *     part that fills cells is then moved there: received = (F + B) - min(L, F + B).
 * A unit whose EAN is not in the store's catalog (or whose EPC does not decode) is left alone and listed as unplaceable.
 *
 * Conservation, asserted by the self-test: L = placed on the floor + to the back room + unplaceable.
 */
object ResetPlanner {
    class LedgerUnit(val epc: String, val ean: String, val at: String)

    class Final(val epc: String, val ean: String, val state: UnitState, val location: String, val level: Int)

    /** A batch of units to one destination: a floor fixture code, or [BACKROOM]. */
    class Move(val dest: String, val ean: String, val epcs: List<String>) {
        val toBack: Boolean get() = dest == BACKROOM
    }

    class Result(
        val ledgerUnits: Int,
        val placedFloor: Int,
        val toBackroom: Int,
        val unplaceable: List<LedgerUnit>,
        val stayedFloor: Int,
        val stayedBackroom: Int,
        val received: List<String>,
        val receivedToCells: Int,
        val moves: List<Move>,
        val finals: List<Final>,
        val nextSerial: Long,
        /** Floor units the period plan wants (sum of required) and back-room cover the stock plan holds, over the EANs the plan names. */
        val planFloor: Int,
        val planBackroom: Int,
    ) {
        val movedUnits: Int get() = moves.sumOf { it.epcs.size }
    }

    const val BACKROOM = "BR"

    fun plan(
        slug: String,
        ledger: Map<String, List<String>>,
        required: Map<Slot, Int>,
        homeLevel: Map<Slot, Int>,
        backroomTarget: Map<String, Int>,
        homeRack: Map<String, String>,
        knownEans: Set<String>,
        reserved: Set<String>,
        defaultRack: String = "BR-01",
        chunk: Int = 200,
    ): Result {
        // ── the ledger as units, EAN decoded from the EPC itself ─────────────────────────────────────────
        val units = ArrayList<LedgerUnit>()
        val unplaceable = ArrayList<LedgerUnit>()
        for ((at, epcs) in ledger.toSortedMap()) {
            for (epc in epcs.sorted()) {
                val ean = runCatching { Sgtin96.decodeEan(epc) }.getOrNull()
                if (ean == null || ean !in knownEans) unplaceable += LedgerUnit(epc, ean ?: "?", at) else units += LedgerUnit(epc, ean, at)
            }
        }
        val byEan = units.groupBy { it.ean }.toSortedMap()
        val cellsOf = required.entries.filter {
            it.value > 0
        }.groupBy({ it.key.ean }, { it.key to it.value }).mapValues { (_, v) -> v.sortedBy { it.first } }
        val eans = (byEan.keys + cellsOf.keys + backroomTarget.keys).toSortedSet()

        val serialStart = StoreState.deliveryBase(slug).let { base ->
            val taken = reserved.mapNotNull { runCatching { Sgtin96.serial(it) }.getOrNull() }.filter {
                it >= base &&
                    it < StoreState.deliveryEnd(slug)
            }
            maxOf(base, (taken.maxOrNull() ?: (base - 1)) + 1)
        }
        var serial = serialStart
        val finals = ArrayList<Final>()
        val cellMoves = java.util.TreeMap<Pair<String, String>, ArrayList<String>>(compareBy({ it.first }, { it.second }))
        val backMoves = java.util.TreeMap<String, ArrayList<String>>()
        val received = ArrayList<String>()
        var stayedFloor = 0
        var stayedBack = 0
        var receivedToCells = 0
        var planFloor = 0
        var planBack = 0

        for (ean in eans) {
            val have = byEan[ean].orEmpty()
            val cells = cellsOf[ean].orEmpty()
            val rack = homeRack[ean] ?: defaultRack
            val fl = ArrayDeque(have.filter { !it.at.startsWith("BR-") })
            val br = ArrayDeque(have.filter { it.at.startsWith("BR-") })
            val need = cells.associate { it.first to it.second }.toMutableMap()
            planFloor += cells.sumOf { it.second }
            planBack += backroomTarget[ean] ?: 0

            // 1. a cell keeps what already stands on its own fixture
            for ((slot, q) in cells) {
                val mine = fl.filter { it.at == slot.fixture }.take(q)
                mine.forEach {
                    fl.remove(it)
                    finals += Final(it.epc, ean, UnitState.ON_FLOOR, slot.fixture, homeLevel[slot] ?: 1)
                    stayedFloor++
                }
                need[slot] = q - mine.size
            }
            // 2. a cell still short takes surplus floor units, then back-room units
            for ((slot, _) in cells) {
                while ((need[slot] ?: 0) > 0 && (fl.isNotEmpty() || br.isNotEmpty())) {
                    val u = if (fl.isNotEmpty()) fl.removeFirst() else br.removeFirst()
                    cellMoves.getOrPut(slot.fixture to ean) { ArrayList() } += u.epc
                    finals += Final(u.epc, ean, UnitState.ON_FLOOR, slot.fixture, homeLevel[slot] ?: 1)
                    need[slot] = need.getValue(slot) - 1
                }
            }
            // 3. the rest to the back room
            for (u in fl) {
                backMoves.getOrPut(ean) { ArrayList() } += u.epc
                finals += Final(u.epc, ean, UnitState.BACKROOM, rack, 0)
            }
            for (u in br) {
                finals += Final(u.epc, ean, UnitState.BACKROOM, u.at, 0)
                stayedBack++
            }
            val leftover = fl.size + br.size
            // 4. what is still missing is received into the stockroom; the part that fills cells is moved there
            val deficit = need.values.sum()
            val topUp = maxOf(0, (backroomTarget[ean] ?: 0) - leftover)
            repeat(deficit + topUp) { k ->
                var epc: String
                do {
                    epc = Sgtin96.encode(ean, serial++)
                } while (epc in reserved)
                received += epc
                if (k < deficit) receivedToCells++
                // minted units fill the cells that are still short, in cell order; the others rest on the ean's home rack
                val slot = if (k < deficit) cells.map { it.first }.first { (need[it] ?: 0) > 0 } else null
                if (slot != null) {
                    need[slot] = need.getValue(slot) - 1
                    cellMoves.getOrPut(slot.fixture to ean) { ArrayList() } += epc
                    finals += Final(epc, ean, UnitState.ON_FLOOR, slot.fixture, homeLevel[slot] ?: 1)
                } else {
                    finals += Final(epc, ean, UnitState.BACKROOM, rack, 0)
                }
            }
        }
        check(serial <= StoreState.deliveryEnd(slug)) { "$slug: the reset overran its delivery serial block" }

        val moves = cellMoves.flatMap { (k, epcs) -> epcs.chunked(chunk).map { Move(k.first, k.second, it) } } +
            backMoves.flatMap { (ean, epcs) -> epcs.chunked(chunk).map { Move(BACKROOM, ean, it) } }
        val placed = finals.count { it.state == UnitState.ON_FLOOR }
        val toBack = finals.count { it.state == UnitState.BACKROOM }
        return Result(
            ledgerUnits = ledger.values.sumOf { it.size },
            placedFloor = placed - receivedToCells,
            toBackroom = toBack - (received.size - receivedToCells),
            unplaceable = unplaceable,
            stayedFloor = stayedFloor,
            stayedBackroom = stayedBack,
            received = received,
            receivedToCells = receivedToCells,
            moves = moves,
            finals = finals,
            nextSerial = serial,
            planFloor = planFloor,
            planBackroom = planBack,
        )
    }
}
