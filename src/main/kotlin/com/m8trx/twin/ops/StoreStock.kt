package com.m8trx.twin.ops

import com.m8trx.twin.connect.DatedStream
import com.m8trx.twin.connect.ReceiveStock
import com.m8trx.twin.connect.RestockMoves
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Where units sit, as a per-fixture list. Two sources:
 *  - [load]: the twin's OWN record (below) — used by a dry run and the offline tests, where nothing may be read.
 *  - [fromPlatform]: where the PLATFORM says each in-stock unit is right now (a Hasura `thing_location` read) — what a
 *    live count is built from. The twin's record drifts from the platform's (Denver's restock placements since Oct 5 never
 *    landed there: 26 % of its wall units are in the Back Room on the platform), and a count read off the twin's record
 *    reads Back Room units at a wall and comes out full of `over` rows.
 *
 * The twin's record: where the twin believes each unit of a store sits today, from its own state (the same sources the restock step reads):
 * the chain's `epcs.csv` + the units the receive step created, each moved by `moves/placements.csv`, minus everything in
 * the sold log and everything a previous count left `missing` (`stock/missing-epcs.txt`). A fixture code starting `BR-`
 * is the stockroom. On a live run the stock step narrows this further to what the PLATFORM says is `in_stock`.
 */
class StoreStock(val slug: String, val byFixture: Map<String, List<String>>) {
    val wall: Map<String, List<String>> get() = byFixture.filterKeys { !isBoh(it) }
    val backroom: List<String> get() = byFixture.filterKeys { isBoh(it) }.values.flatten().sorted()

    /** One in-stock unit and the zone the platform has it in. */
    data class Located(val zoneId: String, val epc: String)

    companion object {
        fun isBoh(fixture: String) = fixture.startsWith("BR-")

        /**
         * The store's stock as the PLATFORM holds it: each unit goes under the code of the keyed fixture zone it sits in
         * (a region or other sales-floor zone under that zone's code); a unit in any zone the chain keys do not list (the
         * Back Room's racks) goes under `BR-<zone id prefix>`, so [isBoh] holds and it is a stockroom unit. [gone] = units
         * to leave out (the ones an earlier count left `missing`, in case the reconcile has not flipped them yet).
         */
        fun fromPlatform(store: com.m8trx.twin.history.ChainKeys.Store, located: List<Located>, gone: Set<String>): StoreStock {
            val codeOf = store.zones.associate { it.zoneId to it.code }
            val by = located.asSequence()
                .filter { it.epc !in gone }
                .distinctBy { it.epc }
                .groupBy({ codeOf[it.zoneId] ?: "BR-${it.zoneId.take(8)}" }, { it.epc })
                .mapValues { (_, v) -> v.sorted() }
                .toSortedMap()
            return StoreStock(store.slug, by)
        }

        fun missingFile(stateDir: Path): Path = stateDir.resolve("stock").resolve("missing-epcs.txt")

        fun load(chainDir: Path, stateDir: Path, slug: String): StoreStock {
            val rows = DatedStream.loadEpcRows(chainDir.resolve("stores/$slug/epcs.csv")) +
                ReceiveStock.stockRows(ReceiveStock.readStock(ReceiveStock.stockFile(stateDir.resolve("receive"))), slug)
            val placements = RestockMoves.readPlacements(stateDir.resolve("moves").resolve("placements.csv"))
            val gone = readLines(stateDir.resolve("sold-epcs.txt")) + readLines(missingFile(stateDir))
            val by = rows.asSequence()
                .distinctBy { it.epc }
                .filter { it.epc !in gone }
                .groupBy({ placements[it.epc] ?: it.fixture }, { it.epc })
                .mapValues { (_, v) -> v.sorted() }
                .toSortedMap()
            return StoreStock(slug, by)
        }

        fun readLines(file: Path): Set<String> =
            if (Files.exists(file)) Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()

        fun appendLines(file: Path, lines: List<String>) {
            if (lines.isEmpty()) return
            file.parent?.let { Files.createDirectories(it) }
            Files.writeString(file, lines.joinToString("") { "$it\n" }, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }
}
