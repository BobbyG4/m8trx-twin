package com.m8trx.twin.connect

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.BaseCatalog")

/** 1 EUR = 1.1165 USD: the ECB reference rate core migration 319 used to convert the Decathlon catalog (2026-11-07). */
internal const val CORE_EUR_USD_RATE = 1.1165

/**
 * The currency the twin sends the catalog's **base** price in (CORE-REQ-008 part 1), and where the figure comes from.
 *
 * `M8TRX_BASE_CURRENCY` (default `EUR`, the Decathlon tenant's functional currency; `USD` for the M8trxIsUs tenant, whose
 * products stay in dollars). In EUR the base is the assortment's own **EUR price**: the French stores' `price_local`, which
 * is one figure per EAN across the chain (2,586 of 2,586 EANs carry exactly one). It is NOT `price_usd` converted: the
 * core converted its catalog from USD at [CORE_EUR_USD_RATE] once, and the twin's feed must now put the EUR figure itself.
 * `price_usd` stays for the US tills (`sale_event`) and nothing else. An EAN no French store carries falls back to
 * `price_usd / [CORE_EUR_USD_RATE]` and is logged (the first, then every 500th); none today. The fallback is a stand-in, not a French shelf price.
 *
 * Zone prices (`pricing_update.price_zone`) wait for the core's zones round: nothing here sets one.
 */
internal class BaseCatalog(val currency: String, private val eurMinor: Map<String, Long>) {
    private val fallbacks = java.util.concurrent.atomic.AtomicInteger()

    /** [ean]'s base price in [currency]'s minor unit, given its `price_usd` cents. */
    fun minorOf(ean: String, usdCents: Long): Long = when (currency) {
        "USD" -> usdCents

        "EUR" -> eurMinor[ean] ?: run {
            val n = fallbacks.incrementAndGet()
            if (n == 1 || n % 500 == 0) log.warn("EAN {} has no French EUR price: base = price_usd / {} ({} such so far)", ean, CORE_EUR_USD_RATE, n)
            Math.round(usdCents / CORE_EUR_USD_RATE).coerceAtLeast(1)
        }

        else -> error("M8TRX_BASE_CURRENCY=$currency: only EUR and USD are wired")
    }

    companion object {
        private val eurCache = ConcurrentHashMap<Path, Map<String, Long>>()

        fun currencyFromEnv(): String = System.getenv("M8TRX_BASE_CURRENCY")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: "EUR"

        /** The catalog for the chain under [chainDir] (`stores/<code>/assortment.csv`), in [currency] (default: the env's). */
        fun of(chainDir: Path, currency: String = currencyFromEnv()): BaseCatalog =
            BaseCatalog(currency, if (currency == "EUR") eurCache.computeIfAbsent(chainDir.toAbsolutePath().normalize(), ::loadEur) else emptyMap())

        /** The catalog for an assortment file laid out as `<chain>/stores/<store>/assortment.csv`. */
        fun forFile(file: Path): BaseCatalog = of(file.toAbsolutePath().parent.parent.parent)

        private fun loadEur(chainDir: Path): Map<String, Long> {
            val stores = chainDir.resolve("stores")
            // A wrong M8TRX_CHAIN_DIR (or a file outside `<chain>/stores/<store>/assortment.csv`) must fail, not send every SKU a converted price.
            check(Files.isDirectory(stores)) {
                "EUR base: no $stores (M8TRX_CHAIN_DIR wrong, or the assortment is not under <chain>/stores/<store>/)"
            }
            val out = HashMap<String, Long>()
            Files.list(stores).use { s -> s.sorted().toList() }.forEach { d ->
                val f = d.resolve("assortment.csv")
                if (!Files.exists(f)) return@forEach
                val lines = Files.readAllLines(f)
                val h = csvCells(lines.firstOrNull() ?: return@forEach)
                val iEan = h.indexOf("ean")
                val iLocal = h.indexOf("price_local")
                val iCcy = h.indexOf("currency")
                if (iEan < 0 || iLocal < 0 || iCcy < 0) return@forEach
                for (line in lines.drop(1)) {
                    val c = csvCells(line)
                    if (c.size <= maxOf(iEan, iLocal, iCcy) || c[iCcy].trim().uppercase() != "EUR") continue
                    val price = c[iLocal].trim().toDoubleOrNull() ?: continue
                    out.putIfAbsent(c[iEan].trim(), localMinor(price, "EUR"))
                }
            }
            return out
        }
    }
}
