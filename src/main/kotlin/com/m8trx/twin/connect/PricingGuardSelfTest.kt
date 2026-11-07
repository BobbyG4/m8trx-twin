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
