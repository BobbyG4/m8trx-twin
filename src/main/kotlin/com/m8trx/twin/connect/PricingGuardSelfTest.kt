package com.m8trx.twin.connect

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
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
