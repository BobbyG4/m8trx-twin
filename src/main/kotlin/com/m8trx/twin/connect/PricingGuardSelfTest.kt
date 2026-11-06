package com.m8trx.twin.connect

import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.webhook.CATALOG_CURRENCY
import com.m8trx.twin.connect.model.webhook.PricingUpdate
import com.m8trx.twin.history.RecentDay
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * Offline guard on the money the twin puts on the wire (CORE-REQ-007). Run by [main] in ConnectHarness.
 *
 * The defect it pins: until 2026-11-07 `pricing_update.price_minor` was the store's `price_local` in that currency's
 * minor unit, with no currency field. For the two Korean stores that is whole won, and the platform read it as US
 * cents: 81,000 KRW for sku 4193725 arrived as $810.00 against a $59.99 list, 9.45×–16.10× list on 119 Decathlon
 * SKUs (core mig 310a reset them; three were re-sent the same day). Every assertion here is on the number that
 * leaves the loader and the churn, against `price_usd` read independently from the same CSV.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.PricingGuardSelfTest")

/** The three SKUs mother saw re-sent at won-as-cents after the 310a reset, with their USD list in cents. */
private val RESENT_AFTER_310A = mapOf("4193725" to 5999L, "2094206" to 3500L, "4228776" to 1999L)

/** The loader prices every unit at `price_usd` cents; the churn never leaves 70–120 % of list; never 5× list. */
internal fun pricingUnitsGuard() {
    val storesDir = Path.of("reference/data/chain/stores")
    if (!Files.isDirectory(storesDir)) {
        log.warn("[SKIP] pricing units guard — {} not found (run from repo root)", storesDir)
        return
    }
    val rng = Random(20261107)
    var units = 0
    var draws = 0
    var pinned = 0
    val stores = Files.list(storesDir).use { it.filter { d -> Files.isDirectory(d) }.sorted().toList() }
    check(stores.isNotEmpty()) { "no store folders under $storesDir" }
    for (dir in stores) {
        val store = dir.fileName.toString()
        val file = dir.resolve("assortment.csv")
        if (!Files.exists(file)) continue
        val loaded = loadAssortment(file)
        check(loaded.isNotEmpty()) { "$store: loadAssortment returned no units" }
        val listByEan = readListCentsIndependently(file)
        for (sk in loaded) {
            val list = checkNotNull(listByEan[sk.ean]) { "$store ${sk.itemCd}/${sk.ean}: no price_usd in the CSV" }
            check(sk.priceMinor == list) {
                "$store ${sk.itemCd}/${sk.ean}: loader priced ${sk.priceMinor} but list is $list USD cents — " +
                    "a store-currency amount leaked onto the wire (CORE-REQ-007)"
            }
            check(sk.priceMinor > 0) { "$store ${sk.itemCd}/${sk.ean}: non-positive price ${sk.priceMinor}" }
            units++
            repeat(8) {
                val p = pricingUpdateFor(sk, rng)
                check(p.priceMinor <= 5 * list) {
                    "$store ${p.sku}/${p.gtin}: pricing_update price_minor ${p.priceMinor} is above 5× list $list — " +
                        "the won-as-cents shape (CORE-REQ-007)"
                }
                check(p.priceMinor >= (list * CHURN_MIN).toLong() && p.priceMinor <= (list * CHURN_MAX).toLong()) {
                    "$store ${p.sku}/${p.gtin}: churned ${p.priceMinor} outside ${CHURN_MIN}–${CHURN_MAX} × list $list"
                }
                check(p.sku == sk.itemCd && p.gtin == sk.ean) { "$store: pricing_update must key on the unit's sku + gtin: $p" }
                draws++
            }
        }
        if (store.startsWith("dec-kr-")) {
            for (sk in loaded.filter { it.itemCd in RESENT_AFTER_310A }) {
                val want = RESENT_AFTER_310A.getValue(sk.itemCd)
                check(sk.priceMinor == want) { "$store ${sk.itemCd}: expected list $want USD cents, loader gave ${sk.priceMinor}" }
                pinned++
            }
        }
    }
    check(pinned >= RESENT_AFTER_310A.size) { "the Korean assortments no longer carry the three re-sent SKUs (pinned=$pinned)" }
    log.info(
        "[PASS] pricing units guard: {} units across {} stores priced at price_usd cents; {} churn draws inside {}–{} × list, none above 5×; {} Korean rows of the three re-sent SKUs at list",
        units,
        stores.size,
        draws,
        CHURN_MIN,
        CHURN_MAX,
        pinned,
    )
}

/** `ean -> round(price_usd × 100)`, parsed here on purpose so the loader is checked against the CSV, not against itself. */
private fun readListCentsIndependently(file: Path): Map<String, Long> {
    val lines = Files.readAllLines(file)
    val h = cells(lines.first())
    val iEan = h.indexOf("ean")
    val iUsd = h.indexOf("price_usd")
    check(iEan >= 0 && iUsd >= 0) { "$file lacks ean / price_usd" }
    val out = HashMap<String, Long>()
    for (line in lines.drop(1)) {
        val c = cells(line)
        if (c.size <= maxOf(iEan, iUsd)) continue
        val usd = c[iUsd].trim().toDoubleOrNull() ?: continue
        out.putIfAbsent(c[iEan].trim(), Math.round(usd * 100).coerceAtLeast(1))
    }
    return out
}

/** The guard's own quote-aware splitter (names carry commas), kept apart from the loader's so the check shares nothing with it. */
private fun cells(line: String): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var quoted = false
    for (ch in line) {
        when {
            ch == '"' -> quoted = !quoted

            ch == ',' && !quoted -> {
                out.add(sb.toString())
                sb.setLength(0)
            }

            else -> sb.append(ch)
        }
    }
    out.add(sb.toString())
    return out
}

private val GUARD_CHAIN: Path = Path.of("reference/data/chain")

/**
 * The live path's own output (the hourly pass runs the dated stream, not the undated churn): every `pricing_update`
 * [DatedStream.plannedOps] plans for every store over five weeks is in USD cents inside the churn band, never above 5×
 * the CSV's `price_usd`, and its wire form carries `"currency":"USD"`.
 */
internal fun datedPricingGuard() {
    if (!Files.isDirectory(GUARD_CHAIN.resolve("stores"))) {
        log.warn("[SKIP] dated pricing guard — {} not found (run from repo root)", GUARD_CHAIN)
        return
    }
    val stores = Files.list(GUARD_CHAIN.resolve("stores")).use {
        it.filter { d -> Files.isDirectory(d) }.map { d -> d.fileName.toString() }.sorted().toList()
    }
    var updates = 0
    var korean = 0
    for (store in stores) {
        val list = readListCentsIndependently(GUARD_CHAIN.resolve("stores/$store/assortment.csv"))
        val days = (0L until 38L).map { LocalDate.of(2026, 10, 1).plusDays(it) }
        val plans = days.map { d ->
            val open = d.atTime(1, 0).toInstant(ZoneOffset.UTC)
            DatedStream.StorePlan(store, d, false, emptyList(), 0, 0, 0, 0, 0, 0, 0.0, open, open.plusSeconds(11 * 3600))
        }
        for (op in DatedStream.plannedOps(plans, emptyList(), GUARD_CHAIN, RecentDay.HISTORY_SEED, "guard")) {
            val p = op.body as? PricingUpdate ?: continue
            val l = checkNotNull(p.gtin?.let { list[it] }) { "$store ${op.id}: pricing_update gtin ${p.gtin} has no price_usd" }
            check(p.priceMinor <= 5 * l) { "$store ${op.id}: price_minor ${p.priceMinor} above 5× list $l — the won-as-cents shape (CORE-REQ-007)" }
            check(p.priceMinor >= (l * CHURN_MIN).toLong() && p.priceMinor <= (l * CHURN_MAX).toLong()) {
                "$store ${op.id}: price_minor ${p.priceMinor} outside ${CHURN_MIN}–${CHURN_MAX} × list $l"
            }
            check(p.currency == CATALOG_CURRENCY) { "$store ${op.id}: currency ${p.currency}" }
            val json = ConnectMappers.snake.writeValueAsString(p)
            check(json.contains("\"price_minor\":${p.priceMinor}") && json.contains("\"currency\":\"USD\"")) { "$store ${op.id}: wire form $json" }
            updates++
            if (store.startsWith("dec-kr-")) korean++
        }
    }
    check(korean > 0) { "the guard planned no Korean pricing_update — it would not have caught the won" }
    log.info(
        "[PASS] dated pricing guard: {} planned pricing_updates over 38 days × {} stores ({} Korean), all USD cents ≤ 5× list, currency USD on the wire",
        updates,
        stores.size,
        korean,
    )
}

/**
 * Every dated sale carries its price (CORE-REQ-007): a real Seoul day, allocated and priced, sends one event per price
 * per receipt, each unit at the book's price, never above 5× list, `price_minor` + `currency` on the wire; the memory
 * round-trips; a later update reprices, a non-USD line never does; a receipt sent whole is never re-sent as parts.
 */
internal fun saleTillPriceGuard() {
    if (!Files.isDirectory(GUARD_CHAIN.resolve("stores"))) {
        log.warn("[SKIP] sale till-price guard — {} not found (run from repo root)", GUARD_CHAIN)
        return
    }
    // ── synthetic book: list, an update, a non-USD line, the split ──
    val t0 = Instant.parse("2026-11-07T01:00:00Z")
    val entries = listOf(
        PriceMemory.Entry("u1", t0.plusSeconds(3600), "EAN-A", "A", 800, "USD"),
        PriceMemory.Entry("u2", t0.plusSeconds(3600), "EAN-B", "B", 999_999, "KRW"),
    )
    val book = PriceBook(mapOf("EAN-A" to 1000L, "EAN-B" to 2000L), entries)
    val eans = mapOf("e1" to "EAN-A", "e2" to "EAN-B", "e3" to "EAN-A", "e4" to "EAN-X")
    val before = DatedStream.priceSale(DatedStream.PlannedSale("r1", "s", t0, listOf("e1", "e2", "e3")), eans, book)
    check(
        before.map {
            it.id to it.priceMinor
        } == listOf("r1#1" to 1000L, "r1#2" to 2000L),
    ) { "mixed prices split per price, at list before the update: $before" }
    check(before[0].epcs == listOf("e1", "e3")) { "one event carries both units of the one price: $before" }
    val after = DatedStream.priceSale(DatedStream.PlannedSale("r2", "s", t0.plusSeconds(7200), listOf("e1", "e3")), eans, book)
    check(
        after.single().let {
            it.id == "r2" && it.priceMinor == 800L && it.source == PriceBook.Source.PRICING_UPDATE
        },
    ) { "after the update: $after" }
    val bAfter = DatedStream.priceSale(DatedStream.PlannedSale("r3", "s", t0.plusSeconds(7200), listOf("e2")), eans, book).single()
    check(bAfter.priceMinor == 2000L) { "a non-USD memory line never prices a sale: $bAfter" }
    val unknown = DatedStream.priceSale(DatedStream.PlannedSale("r4", "s", t0, listOf("e4")), eans, book).single()
    check(unknown.priceMinor == null && !ConnectMappers.snake.writeValueAsString(DatedStream.toSaleEvent(unknown)).contains("price_minor")) {
        "a unit the twin cannot price goes unpriced, never guessed: $unknown"
    }
    val wire = ConnectMappers.snake.writeValueAsString(DatedStream.toSaleEvent(before[1]))
    check(wire.contains("\"external_sale_id\":\"r1\"") && wire.contains("\"price_minor\":2000") && wire.contains("\"currency\":\"USD\"")) {
        "a part goes under the receipt's id with price_minor + currency: $wire"
    }
    check(DatedStream.alreadySent("r1#1", "r1", setOf("r1")) && !DatedStream.alreadySent("r1#1", "r1", setOf("r1#2"))) {
        "a receipt sent whole before the split must never be re-sent as parts"
    }
    // A partly sent receipt re-split under other ids (the book changed between passes): only its unsold units go out.
    val resplit = DatedStream.unsoldParts(after + before, setOf("e1", "e2"))
    check(resplit.map { it.id to it.epcs } == listOf("r2" to listOf("e3"), "r1#1" to listOf("e3"))) { "a sold EPC is never re-sent: $resplit" }

    // ── the memory file ──
    val dir = Files.createTempDirectory("twin-price-memory")
    val mem = dir.resolve("pricing-sent.csv")
    PriceMemory.record(mem, entries[0])
    PriceMemory.record(mem, entries[0].copy(priceMinor = 1))
    PriceMemory.record(mem, PriceMemory.Entry("u3", t0.plusSeconds(9000), "EAN-A", "A", 1100, "USD"))
    val back = PriceMemory.read(mem)
    check(back.map { it.id to it.priceMinor } == listOf("u1" to 800L, "u3" to 1100L)) { "memory round-trip, first line per id wins: $back" }
    check(PriceBook(mapOf("EAN-A" to 1000L), back).at("EAN-A", t0.plusSeconds(9999))?.minor == 1100L) { "the last update in force prices the sale" }
    dir.toFile().deleteRecursively()

    // ── a real Seoul day: every event in USD cents, at the book's price, never above 5× list ──
    val recent = RecentDay(chainDir = GUARD_CHAIN)
    val store = "dec-kr-seoul"
    val date = (0L until 14L).map { LocalDate.of(2026, 10, 6).plusDays(it) }.first { recent.run(store, it) != null }
    val day = checkNotNull(recent.run(store, date))
    val rows = DatedStream.loadEpcRows(GUARD_CHAIN.resolve("stores/$store/epcs.csv"))
    val plan = DatedStream.allocate(day, rows, emptySet(), "guard", DatedStream.loadCatalog(GUARD_CHAIN.resolve("stores/$store/assortment.csv")))
    val ops = DatedStream.plannedOps(listOf(plan), plan.sales, GUARD_CHAIN, RecentDay.HISTORY_SEED, "guard")
    val planned = ops.mapNotNull { op -> (op.body as? PricingUpdate)?.let { PriceMemory.entryOf(op.id, op.at, it) } }
    val seoulBook = PriceBook(DatedStream.listCentsOf(GUARD_CHAIN, store), planned)
    val list = readListCentsIndependently(GUARD_CHAIN.resolve("stores/$store/assortment.csv"))
    val eanByEpc = rows.associate { it.epc to it.ean }
    var events = 0
    var units = 0
    for (sale in plan.sales) {
        val parts = DatedStream.priceSale(sale, eanByEpc, seoulBook)
        check(parts.flatMap { it.epcs }.sorted() == sale.epcs.sorted()) { "${sale.saleId}: the parts must carry exactly the receipt's units: $parts" }
        check(parts.map { it.priceMinor }.distinct().size == parts.size) { "${sale.saleId}: one event per price: $parts" }
        for (part in parts) {
            val minor = checkNotNull(part.priceMinor) { "${part.id}: a Seoul unit went unpriced (its EAN is not in the assortment)" }
            for (epc in part.epcs) {
                val ean = eanByEpc.getValue(epc)
                val l = checkNotNull(list[ean]) { "${part.id}: $ean has no price_usd" }
                check(minor <= 5 * l) { "${part.id}: sale price_minor $minor above 5× list $l — the won-as-cents shape (CORE-REQ-007)" }
                check(minor == seoulBook.at(ean, sale.at)?.minor) { "${part.id}: $minor is not the price in force for $ean at ${sale.at}" }
                units++
            }
            val json = ConnectMappers.snake.writeValueAsString(DatedStream.toSaleEvent(part))
            check(
                json.contains("\"price_minor\":$minor") && json.contains("\"currency\":\"USD\"") &&
                    json.contains("\"external_sale_id\":\"${sale.saleId}\""),
            ) {
                "${part.id}: wire form $json"
            }
            events++
        }
    }
    check(units == plan.units) { "priced $units units of the day's ${plan.units}" }
    log.info(
        "[PASS] sale till-price guard: Seoul {} — {} receipts → {} priced sale events, {} units in USD cents ≤ 5× list at the price in force; split, memory, non-USD and re-send rules hold",
        date,
        plan.sales.size,
        events,
        units,
    )
}
