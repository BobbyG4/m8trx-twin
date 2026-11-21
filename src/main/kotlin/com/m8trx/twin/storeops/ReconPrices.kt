package com.m8trx.twin.storeops

import com.m8trx.twin.connect.DatedStream
import com.m8trx.twin.connect.PriceBook
import com.m8trx.twin.connect.PriceMemory
import com.m8trx.twin.connect.ReceiveStock
import com.m8trx.twin.connect.Sku
import com.m8trx.twin.connect.loadAssortment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The price an ORIGINAL sale carried, rebuilt the way the stream builds it (`DatedStream.priceSale`): EPC -> EAN from the store's seed
 * `epcs.csv` plus the received-stock ledger, then the stream's [PriceBook] (last remembered price change at or before the sale's time, else the
 * store's list price in its own currency). Read-only. A unit with no EAN or no price is unpriced, as the stream sends it.
 */
class ReconPrices internal constructor(private val eanByEpc: Map<String, String>, private val book: PriceBook) {
    data class Price(val minor: Long, val currency: String)

    fun priceOf(site: String, epc: String, at: String): Price? {
        val ean = eanByEpc[epc] ?: return null
        val t = runCatching { Instant.parse(at) }.getOrNull() ?: return null
        return book.at(site, ean, t)?.let { Price(it.minor, it.currency) }
    }

    companion object {
        /** [received] (`received-stock.csv`) and [memory] (`pricing-sent.csv`) may be absent (seed / list only). */
        fun load(chainDir: Path, sites: Collection<String>, received: Path?, memory: Path?): ReconPrices {
            val eanByEpc = HashMap<String, String>()
            val list = HashMap<String, List<Sku>>()
            for (s in sites) {
                val dir = chainDir.resolve("stores/$s")
                if (Files.exists(dir.resolve("epcs.csv"))) DatedStream.loadEpcRows(dir.resolve("epcs.csv")).forEach { eanByEpc[it.epc] = it.ean }
                list[s] = loadAssortment(dir.resolve("assortment.csv"))
            }
            if (received != null) ReceiveStock.readStock(received).forEach { eanByEpc.putIfAbsent(it.epc, it.ean) }
            val entries = if (memory != null) PriceMemory.read(memory) else emptyList()
            return ReconPrices(eanByEpc, PriceBook(PriceBook.listOf(list), entries))
        }

        /** For tests. */
        internal fun of(eanByEpc: Map<String, String>, book: PriceBook) = ReconPrices(eanByEpc, book)
    }
}
