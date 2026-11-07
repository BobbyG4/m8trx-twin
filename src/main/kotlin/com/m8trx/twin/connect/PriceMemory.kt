package com.m8trx.twin.connect

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * The twin's memory of the prices it has set (CORE-REQ-007): every `pricing_update` the platform accepts is one line of
 * `.twin-state/pricing-sent.csv`, and every sale is priced from the last one in force at the sale's moment.
 *
 * Without it the churn was memoryless: the platform recorded each `pricing_update` as the product's price from that
 * moment (core mig 310), while a sale priced from the CSV carried list, so the core's history and the twin's till disagreed
 * on every churned product.
 *
 * **Per SKU and per store, in the store's own currency** (Bob's ruling, 2026-11-07, and CORE-REQ-007's text): each market
 * has its own currency, and the platform carries it at the sale (`sale_line.unit_price` + `currency`, `price_source` `till`).
 * A line keeps both halves of one change: the catalog's update as sent (`price_minor` in `currency`: EUR cents for the Decathlon
 * tenant since CORE-REQ-008, USD before it, the product's single base price) and the store's new local price (`local_minor` in the ISO 4217 minor unit of
 * `local_currency`), moved by the same factor. A sale reads only the local half, and only for its own store.
 *
 * File: `id,at,store,ean,sku,price_minor,currency,local_minor,local_currency`. `at` is the update's planned moment (the dated
 * stream's clock, the clock its sales' `occurred_at` is on). Lines are appended only after the platform answered 200.
 * Duplicate ids keep the first line. Nothing else is ever written.
 */
internal object PriceMemory {
    data class Entry(
        val id: String,
        val at: Instant,
        val store: String,
        val ean: String,
        val sku: String,
        val priceMinor: Long,
        val currency: String,
        val localMinor: Long,
        val localCurrency: String,
    )

    private const val HEADER = "id,at,store,ean,sku,price_minor,currency,local_minor,local_currency"

    /** `M8TRX_PRICING_MEMORY`, else `pricing-sent.csv` beside the sold-log (`.twin-state/` by default). */
    fun file(soldLog: Path): Path = System.getenv("M8TRX_PRICING_MEMORY")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        ?: (soldLog.parent ?: Path.of(".twin-state")).resolve("pricing-sent.csv")

    fun read(file: Path): List<Entry> {
        if (!Files.exists(file)) return emptyList()
        val lines = Files.readAllLines(file)
        if (lines.firstOrNull()?.trim() != HEADER) return emptyList() // an older or foreign file never prices a sale
        val seen = HashSet<String>()
        return lines.drop(1).mapNotNull { line ->
            val c = line.split(",")
            if (c.size < 9 || !seen.add(c[0])) return@mapNotNull null
            val at = runCatching { Instant.parse(c[1]) }.getOrNull() ?: return@mapNotNull null
            val minor = c[5].toLongOrNull() ?: return@mapNotNull null
            val local = c[7].toLongOrNull() ?: return@mapNotNull null
            Entry(c[0], at, c[2], c[3], c[4], minor, c[6], local, c[8].trim())
        }
    }

    fun record(file: Path, e: Entry) {
        file.parent?.let { Files.createDirectories(it) }
        if (!Files.exists(file)) Files.writeString(file, "$HEADER\n", StandardOpenOption.CREATE)
        Files.writeString(
            file,
            "${e.id},${e.at},${e.store},${e.ean},${e.sku},${e.priceMinor},${e.currency},${e.localMinor},${e.localCurrency}\n",
            StandardOpenOption.APPEND,
        )
    }

    /** The memory line for [churn] at [store], planned at [at]; null when the update carries no gtin (it cannot price a unit). */
    fun entryOf(id: String, at: Instant, store: String, churn: Churn): Entry? = churn.update.gtin?.let {
        Entry(id, at, store, it, churn.update.sku, churn.update.priceMinor, churn.update.currency, churn.localMinor, churn.localCurrency)
    }
}

/**
 * The price a store's till charges for a unit at a moment, in the store's own currency: the last change remembered for that
 * store and EAN, else its list price there (`price_local`). Keyed on (store, EAN); one store's change never prices another's sale.
 */
internal class PriceBook(private val list: Map<Pair<String, String>, Price>, entries: List<PriceMemory.Entry>) {
    enum class Source { PRICING_UPDATE, LIST }

    /** One unit's price: [minor] in the ISO 4217 minor unit of [currency]. */
    data class Price(val minor: Long, val currency: String, val source: Source = Source.LIST)

    private val changes: Map<Pair<String, String>, List<PriceMemory.Entry>> = entries.asSequence()
        .distinctBy { it.id }
        // A line in another currency than the store's list never prices its till (a store sells in one currency).
        .filter { e -> list[e.store to e.ean]?.currency == e.localCurrency }
        .groupBy { it.store to it.ean }
        .mapValues { (_, l) -> l.sortedWith(compareBy({ it.at }, { it.id })) }

    /** The catalog halves, keyed on the currency they were sent in: a USD memory line never answers an EUR question (CORE-REQ-008). */
    private val sent: Map<Triple<String, String, String>, List<PriceMemory.Entry>> = entries.asSequence()
        .distinctBy { it.id }
        .groupBy { Triple(it.store, it.ean, it.currency) }
        .mapValues { (_, l) -> l.sortedWith(compareBy({ it.at }, { it.id })) }

    /**
     * Latent API (no production caller yet; today only the self-test reads it): the memory's catalog half is not what prices a
     * sale, but anything that asks for it must ask per currency. The last base price (catalog half) sent for [store]/[ean] **in [currency]** at or before [t]; null when none was sent in that currency.
     */
    fun baseAt(store: String, ean: String, currency: String, t: Instant): Long? =
        sent[Triple(store, ean, currency)]?.lastOrNull { !it.at.isAfter(t) }?.priceMinor

    fun at(store: String, ean: String, t: Instant): Price? = changes[store to ean]?.lastOrNull { !it.at.isAfter(t) }
        ?.let { Price(it.localMinor, it.localCurrency, Source.PRICING_UPDATE) }
        ?: list[store to ean]

    companion object {
        /** The list side of a book from loaded assortments: (store, EAN) → the store's own list price. */
        fun listOf(byStore: Map<String, List<Sku>>): Map<Pair<String, String>, Price> =
            byStore.flatMap { (store, skus) -> skus.map { (store to it.ean) to Price(it.localMinor, it.localCurrency) } }.toMap()
    }
}
