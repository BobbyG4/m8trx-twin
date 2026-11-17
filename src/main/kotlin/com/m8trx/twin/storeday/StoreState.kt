package com.m8trx.twin.storeday

import com.m8trx.twin.connect.Sgtin96
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.TreeMap

enum class UnitState { ON_FLOOR, BACKROOM, SOLD, IN_TRANSIT }

/**
 * Where every unit of one store is, at EPC level (slice 2a).
 *
 * **Serial ranges (this is the contract that keeps offline EPCs away from every real one):**
 *  - chain data (`epcs.csv`): serials 1,000 .. 9,999,999
 *  - `ReceiveStock` receiving block: 1,000,000,000 .. 11,000,000,000
 *  - **offline initial snapshot (this class, [INITIAL_BASE])**: 20,000,000,000 .. < [DELIVERY_BASE] (10 billion serials)
 *  - **store-day deliveries ([DELIVERY_BASE])**: 30,000,000,000 upward, one counter ([nextSerial]) carried in the snapshot
 *  - the SGTIN-96 serial field is 38 bits = 274,877,906,944, so the delivery counter has ~245 billion serials of room.
 *  - **per store (slice 2b):** the same EAN is sold in all ten stores and an EPC is global, so each store gets its own slice.
 *    Store index 0 is Denver (its ranges are exactly the constants above); the other nine take indices 1..9 in slug order.
 *    Initial snapshot: [INITIAL_BASE] + index x 1e9. Deliveries: [DELIVERY_BASE] + index x 2e10 (the last store ends below 2.3e11).
 *
 * A unit is either `ON_FLOOR` (location = a sales-floor fixture code, [Unit.level] its shelf level, 1 = top), `BACKROOM`
 * (location = a BR rack), `IN_TRANSIT` (minted on a delivery truck, or carried by staff between back room and fixture), or
 * `SOLD` (gone for good; location blank). Units are FIFO per (location, EAN): the head is the oldest to arrive.
 *
 * Iteration order is canonical (TreeMap by location then EAN, deque order inside) and the snapshot is written in that order,
 * so loading a snapshot rebuilds the very same structure and a resumed run replays a continuous one.
 */
class StoreState(val slug: String) {
    class Unit(val epc: String, val ean: String, var state: UnitState, var location: String, var level: Int)

    private val units = HashMap<String, Unit>()
    private val index = TreeMap<String, TreeMap<String, ArrayDeque<String>>>()
    private val soldOrder = ArrayList<String>()
    var nextSerial: Long = deliveryBase(slug)
    var asOf: LocalDate? = null

    /**
     * Slice 2c: the first day of the planogram period the floor is arranged for (null = the Phase 1 baseline, nothing issued yet).
     * A day in a different period issues that period's directive and resets the floor from this one to it.
     */
    var planPeriod: LocalDate? = null

    val size: Int get() = units.size
    fun unit(epc: String): Unit? = units[epc]
    fun soldEpcs(): List<String> = soldOrder
    fun isBackroomLoc(loc: String): Boolean = loc.startsWith("BR-")

    fun count(location: String, ean: String): Int = index[location]?.get(ean)?.size ?: 0

    /** EPCs at a location for an EAN, head (oldest) first. */
    fun epcsAt(location: String, ean: String): List<String> = index[location]?.get(ean)?.toList().orEmpty()

    fun locations(): Set<String> = index.keys

    fun eansAt(location: String): Set<String> = index[location]?.keys.orEmpty()

    /** Units of [ean] in the whole back room. */
    fun backroomCount(ean: String): Int = index.entries.filter { isBackroomLoc(it.key) }.sumOf { it.value[ean]?.size ?: 0 }

    fun count(state: UnitState): Int = units.values.count { it.state == state }

    fun add(epc: String, ean: String, state: UnitState, location: String, level: Int) {
        require(epc !in units) { "EPC $epc already in the store state" }
        units[epc] = Unit(epc, ean, state, location, level)
        if (state == UnitState.SOLD) {
            soldOrder += epc
        } else if (state != UnitState.IN_TRANSIT) {
            enqueue(location, ean, epc)
        }
    }

    private fun enqueue(location: String, ean: String, epc: String, front: Boolean = false) {
        val dq = index.getOrPut(location) { TreeMap() }.getOrPut(ean) { ArrayDeque() }
        if (front) dq.addFirst(epc) else dq.addLast(epc)
    }

    private fun dequeue(location: String, ean: String, epc: String) {
        val m = index[location] ?: error("no stock at $location")
        val dq = m[ean] ?: error("no $ean at $location")
        check(dq.remove(epc)) { "$epc is not at $location" }
        if (dq.isEmpty()) m.remove(ean)
        if (m.isEmpty()) index.remove(location)
    }

    /** Detach the oldest unit of [ean] at [location]; it becomes IN_TRANSIT until [place]d. Null when there is none. */
    fun pickUp(location: String, ean: String): String? {
        val epc = index[location]?.get(ean)?.firstOrNull() ?: return null
        dequeue(location, ean, epc)
        units.getValue(epc).also {
            it.state = UnitState.IN_TRANSIT
            it.location = ""
        }
        return epc
    }

    /** Detach one specific unit from wherever it is; it becomes IN_TRANSIT. Returns the EPC. */
    fun pickUpEpc(epc: String): String {
        val u = units.getValue(epc)
        dequeue(u.location, u.ean, epc)
        u.state = UnitState.IN_TRANSIT
        u.location = ""
        return epc
    }

    /** Detach the NEWEST unit (a shopper's last put-back sits on top). */
    fun pickUpLast(location: String, ean: String): String? {
        val epc = index[location]?.get(ean)?.lastOrNull() ?: return null
        dequeue(location, ean, epc)
        units.getValue(epc).also {
            it.state = UnitState.IN_TRANSIT
            it.location = ""
        }
        return epc
    }

    /** Put an IN_TRANSIT unit at [location] (a BR rack → BACKROOM, anything else → ON_FLOOR). */
    fun place(epc: String, location: String, level: Int) {
        val u = units.getValue(epc)
        check(u.state == UnitState.IN_TRANSIT) { "$epc is ${u.state}, not in transit" }
        u.state = if (isBackroomLoc(location)) UnitState.BACKROOM else UnitState.ON_FLOOR
        u.location = location
        u.level = if (isBackroomLoc(location)) 0 else level
        enqueue(location, u.ean, epc)
    }

    /** Take a unit off its fixture for good: the sale. Returns the unit's (EPC, level it was shown at); null if none. */
    fun sell(location: String, ean: String): Pair<String, Int>? {
        val epc = pickUp(location, ean) ?: return null
        val u = units.getValue(epc)
        val level = u.level
        u.state = UnitState.SOLD
        soldOrder += epc
        return epc to level
    }

    /** Mint [n] new EPCs for [ean] (IN_TRANSIT, on the truck) from the delivery serial counter. */
    fun mint(ean: String, n: Int): List<String> = (0 until n).map {
        val epc = Sgtin96.encode(ean, nextSerial++)
        add(epc, ean, UnitState.IN_TRANSIT, "", 0)
        epc
    }

    fun totalNotSold(): Int = units.size - soldOrder.size

    // ── snapshot ──────────────────────────────────────────────────────────────────────────────────────

    /** Write `<base>.csv` (one row per unit, canonical order, sold last) and `<base>.meta`. */
    fun save(base: Path) {
        Files.createDirectories(base.toAbsolutePath().parent)
        Files.newBufferedWriter(Path.of("$base.csv")).use { w ->
            w.write("epc,ean,state,zone_code,level\n")
            for ((loc, byEan) in index) {
                for ((ean, dq) in byEan) {
                    for (epc in dq) {
                        val u = units.getValue(epc)
                        w.write("$epc,$ean,${u.state.name.lowercase()},$loc,${u.level}\n")
                    }
                }
            }
            // Carried / in-transit units (none at a day's end) keep a row so a mid-day snapshot loses nothing.
            units.values.filter { it.state == UnitState.IN_TRANSIT }.sortedBy { it.epc }.forEach { w.write("${it.epc},${it.ean},in_transit,,0\n") }
            soldOrder.forEach { epc -> w.write("$epc,${units.getValue(epc).ean},sold,,0\n") }
        }
        Files.writeString(
            Path.of("$base.meta"),
            "slug=$slug\nnext_serial=$nextSerial\nas_of=${asOf ?: ""}\nplan_period=${planPeriod ?: ""}\nunits=${units.size}\nsold=${soldOrder.size}\n",
        )
    }

    companion object {
        const val INITIAL_BASE = 20_000_000_000L
        const val DELIVERY_BASE = 30_000_000_000L
        const val SERIAL_CEILING = 1L shl Sgtin96.SERIAL_BITS
        private const val INITIAL_STRIDE = 1_000_000_000L
        private const val DELIVERY_STRIDE = 20_000_000_000L
        private const val DENVER = "dec-us-denver"

        /** The chain's ten sales-floor stores, Denver first (index 0), then the rest by slug. */
        val STORE_SLUGS = listOf(
            DENVER, "dec-fr-bordeaux", "dec-fr-lille", "dec-fr-lyon", "dec-fr-marseille", "dec-fr-paris", "dec-kr-busan", "dec-kr-seoul",
            "dec-us-nyc", "dec-us-sf",
        )

        fun serialIndex(slug: String): Int = STORE_SLUGS.indexOf(slug).also { require(it >= 0) { "no serial slice for store '$slug'" } }

        fun initialBase(slug: String): Long = INITIAL_BASE + serialIndex(slug) * INITIAL_STRIDE

        fun deliveryBase(slug: String): Long = DELIVERY_BASE + serialIndex(slug) * DELIVERY_STRIDE

        /** Serial range a store's units may take: initial snapshot first, deliveries after. */
        fun deliveryEnd(slug: String): Long = deliveryBase(slug) + DELIVERY_STRIDE

        fun load(base: Path): StoreState {
            val meta = Files.readAllLines(Path.of("$base.meta")).associate { it.substringBefore('=') to it.substringAfter('=') }
            val s = StoreState(meta.getValue("slug"))
            s.nextSerial = meta.getValue("next_serial").toLong()
            s.asOf = meta["as_of"]?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
            s.planPeriod = meta["plan_period"]?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
            Files.newBufferedReader(Path.of("$base.csv")).useLines { ls ->
                ls.drop(1).forEach { l ->
                    val c = l.split(",")
                    val st = UnitState.valueOf(c[2].uppercase())
                    s.add(c[0], c[1], st, c[3], c[4].toInt())
                }
            }
            return s
        }

        /**
         * The offline initial snapshot, minted from `stock-plan.csv`: a unit per planned floor unit and per planned back-room
         * unit, serials [INITIAL_BASE] + n in stock-plan row order. The store starts at the plan's own starting position
         * (92% of slots exact, 6% short, 2% over), not a perfect planogram.
         */
        fun initial(m: StoreDayModel): StoreState {
            val s = StoreState(m.slug)
            var serial = initialBase(m.slug)
            for (r in m.stockPlan) {
                val isFloor = r.kind == "floor"
                val level = if (isFloor) m.homeLevel[Slot(r.zone, r.ean)] ?: 1 else 0
                repeat(r.qty) {
                    s.add(Sgtin96.encode(r.ean, serial++), r.ean, if (isFloor) UnitState.ON_FLOOR else UnitState.BACKROOM, r.zone, level)
                }
            }
            check(serial < initialBase(m.slug) + INITIAL_STRIDE) { "initial snapshot overran its serial range" }
            return s
        }
    }
}
