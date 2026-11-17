package com.m8trx.twin.connect

import com.m8trx.twin.connect.DatedStream.EpcRow
import com.m8trx.twin.connect.DatedStream.PlannedSale
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Restock moves (Bob's ruling, 2026-10-12: "yes do the restock moves") — the `M8TRX_MOVEMENT_RESTOCK=true` mode of
 * `connectMovementDrive`. After a day's sales, the fixtures that sold down are refilled from the stockroom, so the
 * platform's compliance (re-evaluated whenever an item moves or sells) holds where the backfilled history left it
 * instead of collapsing as the live week drains the walls.
 *
 * Per store, for the date's persisted sales plan (`.twin-state/stream/twin-day-<yyyymmdd>-<seed>.plan.csv`):
 *
 *  1. **What sold from a wall.** Only sales in the plan's sent ledger count (a planned-but-unsent sale is not
 *     restocked). Each sold EPC resolves to its CURRENT fixture — the placements map first (`moves/placements.csv`),
 *     else the `epcs.csv` fixture. A unit that sold out of the stockroom (`BR-*`) leaves no hole on a wall and is skipped.
 *     Sold wall units are counted per (fixture, ean).
 *  2. **What is owed.** Sold minus the units this date's restock ledger already moved to that (fixture, ean), so a
 *     re-run of the same date is idempotent and picks up only sales sent since.
 *  3. **What moves.** The same number of stockroom units of that EAN, lowest EPC first, never one already sold,
 *     reserved by any persisted sales plan, moved (placements / ledger), or taken by this run's random moves. If the
 *     stockroom holds fewer, what exists moves and the rest is counted as shortfall. EPCs are never invented.
 *     ONE `inventory_movement` per (fixture, ean), through the same envelope and driver as the random moves.
 *
 * External id `RS-<slug>-<yyyymmdd>-<fixture>-<ean>-<n>`, `n` = units of that (fixture, ean) this date's ledger had
 * already moved — strictly increasing, so a resumed batch never reuses an id and a failed send re-sends the same id.
 *
 * Ledgers (under `.twin-state/moves/`, next to the sold log): `restock-<yyyymmdd>-<seed>.sent` (EPC per line, appended on
 * an accepted send) and `placements.csv` (`epc,fixture,date`, appended on an accepted send of ANY movement, restock or
 * random). Nothing is written in a dry run.
 *
 * ⚠ LIMITATION: the dated sales stream allocates from the `epcs.csv` fixtures and does not read placements. A unit
 * restocked onto a wall still looks like stockroom stock to it, so a later day can sell it "from the stockroom"; the
 * platform sees the sale of an item that is physically on the wall (which is what happened), and the next restock
 * resolves that EPC through placements and refills the wall it left. For the demo this is invisible: sales are by EPC.
 */
object RestockMoves {
    /** One planned restock: [epcs] of one EAN from the stockroom to the wall [toFixture] that sold [owed] of it. */
    data class Move(
        val extId: String,
        val store: String,
        val toFixture: String,
        val toZoneId: String?,
        val ean: String,
        val epcs: List<String>,
        val owed: Int,
    )

    data class StorePlan(
        val store: String,
        val moves: List<Move>,
        val soldWallUnits: Int,
        val soldBohUnits: Int,
        val alreadyRestocked: Int,
        val shortfallUnits: Int,
    ) {
        val units: Int get() = moves.sumOf { it.epcs.size }
    }

    /**
     * Pure plan for one store-day. [sales] are the date's plan rows for [slug]; [sentIds] the plan's sent ledger;
     * [taken] every EPC that must not move (sold log, reserved by any plan, placements, this run's random moves);
     * [ledger] the EPCs this date's restock already moved; [placements] epc → current fixture; [zoneIds] fixture → zone.
     */
    fun plan(
        slug: String,
        date: LocalDate,
        rows: List<EpcRow>,
        sales: List<PlannedSale>,
        sentIds: Set<String>,
        taken: Set<String>,
        ledger: Set<String>,
        placements: Map<String, String>,
        zoneIds: Map<String, String?> = emptyMap(),
    ): StorePlan {
        val byEpc = rows.distinctBy { it.epc }.associateBy { it.epc }
        fun fixtureOf(epc: String): String? = placements[epc] ?: byEpc[epc]?.fixture
        fun isBoh(fixture: String) = fixture.startsWith("BR-")

        val sold = HashMap<Pair<String, String>, Int>()
        var soldBoh = 0
        for (s in sales) {
            if (s.store != slug || s.saleId !in sentIds) continue
            for (epc in s.epcs) {
                val row = byEpc[epc] ?: continue
                val fixture = placements[epc] ?: row.fixture
                if (isBoh(fixture)) {
                    soldBoh++
                } else {
                    sold.merge(fixture to row.ean, 1, Int::plus)
                }
            }
        }
        val done = HashMap<Pair<String, String>, Int>()
        for (epc in ledger) {
            val row = byEpc[epc] ?: continue
            val f = placements[epc] ?: continue
            done.merge(f to row.ean, 1, Int::plus)
        }

        val stock: Map<String, ArrayDeque<String>> = byEpc.values
            .filter { it.epc !in taken && it.epc !in ledger && isBoh(fixtureOf(it.epc)!!) }
            .sortedBy { it.epc }
            .groupBy { it.ean }
            .mapValues { (_, rs) -> ArrayDeque(rs.map { it.epc }) }

        val ymd = date.format(DateTimeFormatter.BASIC_ISO_DATE)
        val moves = ArrayList<Move>()
        var shortfall = 0
        for ((key, n) in sold.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
            val (fixture, ean) = key
            val already = done[key] ?: 0
            val owed = n - already
            if (owed <= 0) continue
            val q = stock[ean]
            val take = minOf(owed, q?.size ?: 0)
            val epcs = List(take) { q!!.removeFirst() }
            shortfall += owed - take
            if (take > 0) moves += Move("RS-$slug-$ymd-$fixture-$ean-$already", slug, fixture, zoneIds[fixture], ean, epcs, owed)
        }
        return StorePlan(slug, moves, sold.values.sum(), soldBoh, done.values.sum(), shortfall)
    }

    // ── state files ───────────────────────────────────────────────────────────

    fun movesDir(soldLog: Path): Path = soldLog.parent?.resolve("moves") ?: Path.of(".twin-state/moves")

    fun ledgerFile(movesDir: Path, date: LocalDate, seed: Long): Path =
        movesDir.resolve("restock-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}-$seed.sent")

    fun planFile(streamDir: Path, date: LocalDate, seed: Long): Path =
        streamDir.resolve("twin-day-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}-$seed.plan.csv")

    fun readLedger(file: Path): Set<String> = if (Files.exists(file)) {
        Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    } else {
        emptySet()
    }

    fun appendLedger(file: Path, epcs: List<String>) {
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(file, epcs.joinToString("") { "$it\n" }, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    /** `epc,fixture,date` → epc → fixture (a later line wins). */
    fun readPlacements(file: Path): Map<String, String> {
        if (!Files.exists(file)) return emptyMap()
        val out = LinkedHashMap<String, String>()
        Files.readAllLines(file).drop(1).forEach { l ->
            val c = l.split(",")
            if (c.size >= 2 && c[0].isNotBlank()) out[c[0].trim()] = c[1].trim()
        }
        return out
    }

    fun appendPlacements(file: Path, epcs: List<String>, fixture: String, date: LocalDate) {
        file.parent?.let { Files.createDirectories(it) }
        val header = if (Files.exists(file)) "" else "epc,fixture,date\n"
        Files.writeString(file, header + epcs.joinToString("") { "$it,$fixture,$date\n" }, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}
