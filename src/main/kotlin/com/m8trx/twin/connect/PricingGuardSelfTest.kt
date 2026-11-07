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
        val locals = readLocalIndependently(GUARD_CHAIN.resolve("stores/$store/assortment.csv"))
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
            val churn = checkNotNull(op.churn) { "$store ${op.id}: a pricing op must carry its store's local price" }
            val local = checkNotNull(locals[p.gtin]) { "$store ${op.id}: ${p.gtin} has no price_local" }
            check(churn.localCurrency == local.second) {
                "$store ${op.id}: local currency ${churn.localCurrency}, the store sells in ${local.second}"
            }
            check(churn.localMinor >= (local.first * CHURN_MIN).toLong() - 1 && churn.localMinor <= (local.first * CHURN_MAX).toLong()) {
                "$store ${op.id}: local ${churn.localMinor} ${churn.localCurrency} outside the band of its list ${local.first}"
            }
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

/** `ean -> (round(price_local × 10^digits), currency)`, parsed here on purpose, apart from the loader. */
private fun readLocalIndependently(file: Path): Map<String, Pair<Long, String>> {
    val lines = Files.readAllLines(file)
    val h = cells(lines.first())
    val iEan = h.indexOf("ean")
    val iLocal = h.indexOf("price_local")
    val iCcy = h.indexOf("currency")
    check(iEan >= 0 && iLocal >= 0 && iCcy >= 0) { "$file lacks ean / price_local / currency" }
    val out = HashMap<String, Pair<Long, String>>()
    for (line in lines.drop(1)) {
        val c = cells(line)
        if (c.size <= maxOf(iEan, iLocal, iCcy)) continue
        val v = c[iLocal].trim().toDoubleOrNull() ?: continue
        val ccy = c[iCcy].trim().uppercase()
        val digits = java.util.Currency.getInstance(ccy).defaultFractionDigits
        out.putIfAbsent(c[iEan].trim(), Math.round(v * Math.pow(10.0, digits.toDouble())).coerceAtLeast(1) to ccy)
    }
    return out
}

/** The one currency a store sells in, read from its assortment on its own. */
private fun storeCurrency(store: String): String =
    readLocalIndependently(GUARD_CHAIN.resolve("stores/$store/assortment.csv")).values.map { it.second }.distinct().single()

/**
 * Every dated sale carries its price in its store's currency (CORE-REQ-007, Bob's ruling 2026-11-07): the book prices a
 * store's till from that store's changes only, in its own currency; a receipt splits per price; the memory round-trips; a
 * line in another currency than the store's never prices it; a sold unit is never re-sent.
 */
internal fun saleTillPriceGuard() {
    if (!Files.isDirectory(GUARD_CHAIN.resolve("stores"))) {
        log.warn("[SKIP] sale till-price guard — {} not found (run from repo root)", GUARD_CHAIN)
        return
    }
    val t0 = Instant.parse("2026-11-07T01:00:00Z")
    val list = mapOf(
        ("seoul" to "EAN-A") to PriceBook.Price(13500, "KRW"),
        ("seoul" to "EAN-B") to PriceBook.Price(27000, "KRW"),
        ("paris" to "EAN-A") to PriceBook.Price(949, "EUR"),
    )
    val entries = listOf(
        PriceMemory.Entry("u1", t0.plusSeconds(3600), "seoul", "EAN-A", "A", 800, "USD", 10800, "KRW"),
        PriceMemory.Entry("u2", t0.plusSeconds(3600), "seoul", "EAN-B", "B", 1999, "USD", 1999, "USD"),
    )
    val book = PriceBook(list, entries)
    val eans = mapOf("e1" to "EAN-A", "e2" to "EAN-B", "e3" to "EAN-A", "e4" to "EAN-X")
    fun sale(id: String, store: String, at: Instant, vararg epcs: String) = DatedStream.PlannedSale(id, store, at, epcs.toList())
    val before = DatedStream.priceSale(sale("r1", "seoul", t0, "e1", "e2", "e3"), eans, book)
    check(before.map { Triple(it.id, it.priceMinor, it.currency) } == listOf(Triple("r1#1", 13500L, "KRW"), Triple("r1#2", 27000L, "KRW"))) {
        "mixed prices split per price, at the store's list in its currency: $before"
    }
    check(before[0].epcs == listOf("e1", "e3")) { "one event carries both units of the one price: $before" }
    val after = DatedStream.priceSale(sale("r2", "seoul", t0.plusSeconds(7200), "e1", "e3"), eans, book)
    check(after.single().let { it.id == "r2" && it.priceMinor == 10800L && it.currency == "KRW" && it.source == PriceBook.Source.PRICING_UPDATE }) {
        "after the store's change, its local price: $after"
    }
    val paris = DatedStream.priceSale(sale("r5", "paris", t0.plusSeconds(7200), "e1"), eans, book).single()
    check(paris.priceMinor == 949L && paris.currency == "EUR") { "a Seoul change never prices a Paris sale: $paris" }
    val bAfter = DatedStream.priceSale(sale("r3", "seoul", t0.plusSeconds(7200), "e2"), eans, book).single()
    check(bAfter.priceMinor == 27000L && bAfter.currency == "KRW") { "a memory line in another currency than the store's never prices it: $bAfter" }
    val unknown = DatedStream.priceSale(sale("r4", "seoul", t0, "e4"), eans, book).single()
    check(unknown.priceMinor == null && !ConnectMappers.snake.writeValueAsString(DatedStream.toSaleEvent(unknown)).contains("price_minor")) {
        "a unit the twin cannot price goes unpriced, never guessed: $unknown"
    }
    val wire = ConnectMappers.snake.writeValueAsString(DatedStream.toSaleEvent(before[1]))
    check(wire.contains("\"external_sale_id\":\"r1\"") && wire.contains("\"price_minor\":27000") && wire.contains("\"currency\":\"KRW\"")) {
        "a part goes under the receipt's id with price_minor + the store's currency: $wire"
    }
    check(DatedStream.alreadySent("r1#1", "r1", setOf("r1")) && !DatedStream.alreadySent("r1#1", "r1", setOf("r1#2"))) {
        "a receipt sent whole before the split must never be re-sent as parts"
    }
    val resplit = DatedStream.unsoldParts(after + before, setOf("e1", "e2"))
    check(resplit.map { it.id to it.epcs } == listOf("r2" to listOf("e3"), "r1#1" to listOf("e3"))) { "a sold EPC is never re-sent: $resplit" }

    // ── the memory file ──
    val dir = Files.createTempDirectory("twin-price-memory")
    val mem = dir.resolve("pricing-sent.csv")
    PriceMemory.record(mem, entries[0])
    PriceMemory.record(mem, entries[0].copy(localMinor = 1))
    PriceMemory.record(mem, PriceMemory.Entry("u3", t0.plusSeconds(9000), "seoul", "EAN-A", "A", 900, "USD", 12100, "KRW"))
    val back = PriceMemory.read(mem)
    check(back.map { it.id to it.localMinor } == listOf("u1" to 10800L, "u3" to 12100L)) { "memory round-trip, first line per id wins: $back" }
    check(PriceBook(list, back).at("seoul", "EAN-A", t0.plusSeconds(9999))?.minor == 12100L) { "the last change in force prices the sale" }
    val old = dir.resolve("old.csv")
    Files.writeString(old, "id,at,ean,sku,price_minor,currency\nu1,$t0,EAN-A,A,800,USD\n")
    check(PriceMemory.read(old).isEmpty()) { "a memory file of another shape never prices a sale" }
    dir.toFile().deleteRecursively()

    // ── one real day in Seoul, Paris and Denver: the store's currency, its minor unit, the price in force ──
    val recent = RecentDay(chainDir = GUARD_CHAIN)
    val samples = ArrayList<String>()
    var events = 0
    var units = 0
    for (store in listOf("dec-kr-seoul", "dec-fr-paris", "dec-us-denver")) {
        val (date, day) = (0L until 14L).map { LocalDate.of(2026, 10, 6).plusDays(it) }.firstNotNullOf { d -> recent.run(store, d)?.let { d to it } }
        val rows = DatedStream.loadEpcRows(GUARD_CHAIN.resolve("stores/$store/epcs.csv"))
        val plan = DatedStream.allocate(day, rows, emptySet(), "guard", DatedStream.loadCatalog(GUARD_CHAIN.resolve("stores/$store/assortment.csv")))
        val ops = DatedStream.plannedOps(listOf(plan), plan.sales, GUARD_CHAIN, RecentDay.HISTORY_SEED, "guard")
        val planned = ops.mapNotNull { op -> op.churn?.let { PriceMemory.entryOf(op.id, op.at, op.store, it) } }
        val dayBook = PriceBook(PriceBook.listOf(mapOf(store to loadAssortment(GUARD_CHAIN.resolve("stores/$store/assortment.csv")))), planned)
        val local = readLocalIndependently(GUARD_CHAIN.resolve("stores/$store/assortment.csv"))
        val ccy = storeCurrency(store)
        val eanByEpc = rows.associate { it.epc to it.ean }
        for (s in plan.sales) {
            val parts = DatedStream.priceSale(s, eanByEpc, dayBook)
            check(parts.flatMap { it.epcs }.sorted() == s.epcs.sorted()) { "${s.saleId}: the parts must carry exactly the receipt's units: $parts" }
            for (part in parts) {
                val minor = checkNotNull(part.priceMinor) { "${part.id}: a $store unit went unpriced (its EAN is not in the assortment)" }
                check(part.currency == ccy) { "${part.id}: sale currency ${part.currency}, $store sells in $ccy" }
                for (epc in part.epcs) {
                    val l = checkNotNull(local[eanByEpc.getValue(epc)]) { "${part.id}: no price_local" }
                    check(minor <= 5 * l.first) { "${part.id}: price_minor $minor above 5× the store's list ${l.first} $ccy — a unit error" }
                    check(minor == dayBook.at(store, eanByEpc.getValue(epc), s.at)?.minor) { "${part.id}: $minor is not the price in force" }
                    units++
                }
                val json = ConnectMappers.snake.writeValueAsString(DatedStream.toSaleEvent(part))
                check(json.contains("\"price_minor\":$minor") && json.contains("\"currency\":\"$ccy\"")) { "${part.id}: wire form $json" }
                if (part === parts.first() && s === plan.sales.first()) {
                    val major = java.math.BigDecimal.valueOf(minor).movePointLeft(java.util.Currency.getInstance(ccy).defaultFractionDigits)
                    val ean = eanByEpc.getValue(part.epcs.first())
                    val usd = readListCentsIndependently(GUARD_CHAIN.resolve("stores/$store/assortment.csv")).getValue(ean)
                    samples +=
                        "$store $date ean $ean (catalog list $usd USD cents): {price_minor $minor, currency $ccy} → sale_line unit_price $major $ccy, price_source till"
                }
                events++
            }
        }
        check(plan.sales.flatMap { it.epcs }.size == plan.units) { "$store: priced units off the plan" }
    }
    log.info(
        "[PASS] sale till-price guard: {} priced sale events, {} units, each in its store's currency at the price in force; {}",
        events,
        units,
        samples,
    )
}

/**
 * A sale's `currency` is always its store's (Bob's ruling, 2026-11-07): one open day in every store, every event priced
 * in the currency that store's assortment sells in, never above 5× its list in that currency's minor unit.
 */
internal fun saleCurrencyIsStoresGuard() {
    if (!Files.isDirectory(GUARD_CHAIN.resolve("stores"))) {
        log.warn("[SKIP] sale currency guard — {} not found (run from repo root)", GUARD_CHAIN)
        return
    }
    val recent = RecentDay(chainDir = GUARD_CHAIN)
    val stores = Files.list(GUARD_CHAIN.resolve("stores")).use {
        it.filter { d -> Files.isDirectory(d) }.map { d -> d.fileName.toString() }.sorted().toList()
    }
    val seen = sortedMapOf<String, String>()
    var events = 0
    for (store in stores) {
        val day = (0L until 14L).map { LocalDate.of(2026, 10, 13).plusDays(it) }.firstNotNullOf { d -> recent.run(store, d) }
        val rows = DatedStream.loadEpcRows(GUARD_CHAIN.resolve("stores/$store/epcs.csv"))
        val plan = DatedStream.allocate(
            day,
            rows,
            emptySet(),
            "cur",
            DatedStream.loadCatalog(GUARD_CHAIN.resolve("stores/$store/assortment.csv")),
        )
        val ops = DatedStream.plannedOps(listOf(plan), plan.sales, GUARD_CHAIN, RecentDay.HISTORY_SEED, "cur")
        val book = PriceBook(
            PriceBook.listOf(mapOf(store to loadAssortment(GUARD_CHAIN.resolve("stores/$store/assortment.csv")))),
            ops.mapNotNull { op -> op.churn?.let { PriceMemory.entryOf(op.id, op.at, op.store, it) } },
        )
        val ccy = storeCurrency(store)
        val local = readLocalIndependently(GUARD_CHAIN.resolve("stores/$store/assortment.csv"))
        val eanByEpc = rows.associate { it.epc to it.ean }
        for (part in plan.sales.flatMap { DatedStream.priceSale(it, eanByEpc, book) }) {
            check(part.currency == ccy) { "$store ${part.id}: sale currency ${part.currency}, the store sells in $ccy" }
            val worst = part.epcs.maxOf { checkNotNull(local[eanByEpc.getValue(it)]).first }
            check(checkNotNull(part.priceMinor) <= 5 * worst) { "$store ${part.id}: ${part.priceMinor} $ccy above 5× list" }
            events++
        }
        seen[store] = ccy
    }
    check(seen.values.toSet() == setOf("KRW", "EUR", "USD")) { "the guard must cover all three markets: $seen" }
    log.info(
        "[PASS] sale currency guard: {} sale events over one day in each of {} stores, every one in its store's currency {}",
        events,
        stores.size,
        seen,
    )
}

/**
 * The memory of accepted updates (CORE-REQ-007): a planned churn's line carries the catalog's update as sent (USD cents,
 * USD) and the store's local price moved by the same factor, in the store's currency; it round-trips through the file,
 * the first line per id wins, and a file of another shape is not read.
 */
internal fun priceMemoryGuard() {
    if (!Files.isDirectory(GUARD_CHAIN.resolve("stores"))) {
        log.warn("[SKIP] price memory guard — {} not found (run from repo root)", GUARD_CHAIN)
        return
    }
    val store = "dec-kr-seoul"
    val file = GUARD_CHAIN.resolve("stores/$store/assortment.csv")
    val sk = loadAssortment(file).first()
    val churn = churnFor(sk, Random(7))
    val local = checkNotNull(readLocalIndependently(file)[sk.ean])
    check(churn.update.currency == CATALOG_CURRENCY && churn.localCurrency == local.second && local.second == "KRW") {
        "a Seoul churn: the update in USD, the local half in KRW: $churn"
    }
    val f = churn.update.priceMinor.toDouble() / sk.priceMinor
    check(kotlin.math.abs(churn.localMinor.toDouble() / local.first - f) < 0.01) { "one factor moves both halves: $churn vs $sk" }
    val at = Instant.parse("2026-11-07T01:00:00Z")
    val e = checkNotNull(PriceMemory.entryOf("p1", at, store, churn))
    check(e.store == store && e.ean == sk.ean && e.priceMinor == churn.update.priceMinor && e.localMinor == churn.localMinor) { "entry: $e" }
    val dir = Files.createTempDirectory("twin-price-memory")
    val mem = dir.resolve("pricing-sent.csv")
    PriceMemory.record(mem, e)
    PriceMemory.record(mem, e.copy(localMinor = 1))
    PriceMemory.record(mem, e.copy(id = "p2", at = at.plusSeconds(60)))
    val back = PriceMemory.read(mem)
    check(back == listOf(e, e.copy(id = "p2", at = at.plusSeconds(60)))) { "memory round-trip, first line per id wins: $back" }
    val old = dir.resolve("old.csv")
    Files.writeString(old, "id,at,ean,sku,price_minor,currency\np1,$at,${sk.ean},${sk.itemCd},800,USD\n")
    check(PriceMemory.read(old).isEmpty()) { "a memory file of another shape is not read" }
    dir.toFile().deleteRecursively()
    log.info("[PASS] price memory guard: a Seoul churn remembered as {} USD cents + {} KRW, one factor, round-trips", e.priceMinor, e.localMinor)
}
