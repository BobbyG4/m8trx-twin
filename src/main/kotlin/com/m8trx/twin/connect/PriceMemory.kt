package com.m8trx.twin.connect

import com.m8trx.twin.connect.model.webhook.CATALOG_CURRENCY
import com.m8trx.twin.connect.model.webhook.PricingUpdate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * The twin's memory of the prices it has set (CORE-REQ-007): every `pricing_update` it sends is one line of
 * `.twin-state/pricing-sent.csv`, and every sale is priced from the last one in force at the sale's moment.
 *
 * Without it the churn was memoryless: the platform recorded each `pricing_update` as the product's price from that
 * moment (core mig 310), while a sale priced from the CSV would carry the list price, so the core's price history and
 * the twin's till disagreed on every churned product.
 *
 * **Chain-wide, keyed on the EAN (the `gtin` the update carries).** The platform's `pricing_update` sets the price of
 * every product row with that gtin, tenant-wide, not per store; and `price_usd` is the same for an EAN in every store
 * (0 of 2,586 EANs differ across the ten assortments, 2026-11-07). A per-store memory would price a Denver sale at list
 * after a Seoul update the platform applied to Denver too, which is the disagreement this exists to remove.
 *
 * File: `id,at,ean,sku,price_minor,currency` — `at` is the update's planned moment (the dated stream's clock, the same
 * clock its sales' `occurred_at` is on). Lines are appended only after the platform answered 200. Duplicate ids keep
 * the first line. Nothing else is ever written.
 */
internal object PriceMemory {
    data class Entry(val id: String, val at: Instant, val ean: String, val sku: String, val priceMinor: Long, val currency: String)

    private const val HEADER = "id,at,ean,sku,price_minor,currency"

    /** `M8TRX_PRICING_MEMORY`, else `pricing-sent.csv` beside the sold-log (`.twin-state/` by default). */
    fun file(soldLog: Path): Path = System.getenv("M8TRX_PRICING_MEMORY")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?: (soldLog.parent ?: Path.of(".twin-state")).resolve("pricing-sent.csv")

    fun read(file: Path): List<Entry> {
        if (!Files.exists(file)) return emptyList()
        val seen = HashSet<String>()
        return Files.readAllLines(file).drop(1).mapNotNull { line ->
            val c = line.split(",")
            if (c.size < 6 || !seen.add(c[0])) return@mapNotNull null
            val at = runCatching { Instant.parse(c[1]) }.getOrNull() ?: return@mapNotNull null
            val minor = c[4].toLongOrNull() ?: return@mapNotNull null
            Entry(c[0], at, c[2], c[3], minor, c[5].trim())
        }
    }

    fun record(file: Path, e: Entry) {
        file.parent?.let { Files.createDirectories(it) }
        if (!Files.exists(file)) Files.writeString(file, "$HEADER\n", StandardOpenOption.CREATE)
        Files.writeString(file, "${e.id},${e.at},${e.ean},${e.sku},${e.priceMinor},${e.currency}\n", StandardOpenOption.APPEND)
    }

    /** The memory line for an update sent at planned moment [at]; null when it carries no gtin (it cannot price a unit). */
    fun entryOf(id: String, at: Instant, p: PricingUpdate): Entry? = p.gtin?.let { Entry(id, at, it, p.sku, p.priceMinor, p.currency) }
}
