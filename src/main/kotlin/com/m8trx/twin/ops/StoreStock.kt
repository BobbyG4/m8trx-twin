package com.m8trx.twin.ops

import com.m8trx.twin.connect.DatedStream
import com.m8trx.twin.connect.ReceiveStock
import com.m8trx.twin.connect.RestockMoves
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Where the twin believes each unit of a store sits today, from its own state (the same sources the restock step reads):
 * the chain's `epcs.csv` + the units the receive step created, each moved by `moves/placements.csv`, minus everything in
 * the sold log and everything a previous count left `missing` (`stock/missing-epcs.txt`). A fixture code starting `BR-`
 * is the stockroom. On a live run the stock step narrows this further to what the PLATFORM says is `in_stock`.
 */
class StoreStock(val slug: String, val byFixture: Map<String, List<String>>) {
    val wall: Map<String, List<String>> get() = byFixture.filterKeys { !isBoh(it) }
    val backroom: List<String> get() = byFixture.filterKeys { isBoh(it) }.values.flatten().sorted()

    /** Only the units [keep] says are in stock (the platform-truth filter). */
    fun narrowedTo(keep: Set<String>): StoreStock =
        StoreStock(slug, byFixture.mapValues { (_, v) -> v.filter { it in keep } }.filterValues { it.isNotEmpty() })

    companion object {
        fun isBoh(fixture: String) = fixture.startsWith("BR-")

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
