package com.m8trx.twin.connect

import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.webhook.PricingUpdate
import com.m8trx.twin.connect.model.webhook.ProductCatalogItem
import com.m8trx.twin.connect.model.webhook.SaleEvent
import com.m8trx.twin.connect.model.webhook.ShipmentLine
import com.m8trx.twin.connect.model.webhook.ShipmentManifest
import com.m8trx.twin.connect.sim.InboundPushDriver
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.random.Random

/**
 * Multi-site chain-activity stream (S11, COORD "broaden the fill") — drives a realistic weighted mix
 * of webhook-plane events ACROSS the 10 Decathlon stores, so the cockpit + analytics fill with
 * chain-wide activity rather than Denver sales alone. Webhook-plane only (X-API-Key) — no Bearer.
 *
 * All four §8 inbound ingesters:
 *   - SALE     sale_event by EPC (NoScope -> item band -> the item's own site PROCESSES -> SOLD)
 *   - RESTOCK  shipment_manifest to the store's real site_id (partly closes the inventory loop)
 *   - PRICING  pricing_update — a churned price (markdown/markup) on a real SKU, store-currency minor units
 *   - CATALOG  product_catalog — idempotent re-assert of a real SKU's name (exercises the pipe, no pollution)
 *
 * Data (under M8TRX_CHAIN_DIR): site_ids.csv (store_code -> site_id), per-store assortment.csv
 * (item_cd / name_en / price_local / currency), per-store epcs.csv (floor EPCs). Sales draw EPCs
 * without replacement + persist to the shared sold-log; restock/pricing/catalog reuse SKUs.
 *
 * Env: M8TRX_ACTIVITY_STORES (csv of codes; default all), M8TRX_ACTIVITY_COUNT (60),
 * M8TRX_ACTIVITY_DURATION_SEC (300), weights M8TRX_W_SALE/_RESTOCK/_PRICING/_CATALOG (70/12/10/8),
 * M8TRX_ACTIVITY_VALIDATE (fire exactly 1 of each type + stop, unpaced), M8TRX_STREAM_SOLD_LOG,
 * M8TRX_STREAM_SEED, M8TRX_RUN_ID. Run: `./gradlew connectChainActivity`.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ChainActivityStream")

private data class Store(val code: String, val siteId: String)

/**
 * One **sellable unit**, keyed on [ean] (an EAN-13, i.e. a GTIN-13).
 *
 * [itemCd] is Decathlon's supplier **style code** and is NOT unique: `5391035` is one shoe in two sizes at
 * two prices. This record used to be deduped on [itemCd], which silently dropped the second variant and
 * left twin emitting a style code the platform then applied to both product rows.
 */
private data class Sku(val itemCd: String, val ean: String, val name: String, val priceMinor: Long)

private enum class Activity { SALE, RESTOCK, PRICING, CATALOG }

fun main() {
    val config = ConnectConfig.fromEnv()
    val driver = InboundPushDriver(WebhookClient(config), ConnectClient(config))
    val chainDir = Path.of(env("M8TRX_CHAIN_DIR", "reference/data/chain"))

    val filter = System.getenv("M8TRX_ACTIVITY_STORES")
        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
    val stores = loadStores(chainDir.resolve("site_ids.csv")).filter { filter == null || it.code in filter }
    check(stores.isNotEmpty()) { "no stores after filter=$filter (need site_ids.csv + matching codes)" }

    val validate = System.getenv("M8TRX_ACTIVITY_VALIDATE")?.toBooleanStrictOrNull() ?: false
    val durationSec = envInt("M8TRX_ACTIVITY_DURATION_SEC", 300)
    val seed = System.getenv("M8TRX_STREAM_SEED")?.toLongOrNull() ?: System.currentTimeMillis()
    val runId = env("M8TRX_RUN_ID", "twin-s11-chain-$seed")
    val soldLog = Path.of(env("M8TRX_STREAM_SOLD_LOG", ".twin-state/sold-epcs.txt"))
    val rng = Random(seed)
    val priorSold = readPriorSold(soldLog)

    val epcQueues = HashMap<String, ArrayDeque<String>>()
    val skuLists = HashMap<String, List<Sku>>()
    for (s in stores) {
        val floor = loadFloorEpcs(chainDir.resolve("stores/${s.code}/epcs.csv"), priorSold).shuffled(rng)
        epcQueues[s.code] = ArrayDeque(floor)
        skuLists[s.code] = loadAssortment(chainDir.resolve("stores/${s.code}/assortment.csv"))
    }

    val weights = mapOf(
        Activity.SALE to envInt("M8TRX_W_SALE", 70),
        Activity.RESTOCK to envInt("M8TRX_W_RESTOCK", 12),
        Activity.PRICING to envInt("M8TRX_W_PRICING", 10),
        Activity.CATALOG to envInt("M8TRX_W_CATALOG", 8),
    )
    val plan = if (validate) Activity.entries.toList() else weightedPlan(envInt("M8TRX_ACTIVITY_COUNT", 60), weights, rng)

    log.info(
        "Chain activity → stores={} validate={} events={} window={}s priorSold={} weights={} runId={}",
        stores.map { it.code },
        validate,
        plan.size,
        if (validate) 0 else durationSec,
        priorSold.size,
        weights,
        runId,
    )

    val tally = sortedMapOf<String, Int>()
    val startMs = System.currentTimeMillis()
    plan.forEachIndexed { i, type ->
        val store = stores[rng.nextInt(stores.size)]
        tally.merge("$type:${fire(driver, type, store, epcQueues, skuLists, soldLog, runId, i + 1, rng)}", 1, Int::plus)
        if (!validate && i < plan.size - 1) Thread.sleep(jitterGapMs(durationSec, plan.size, rng))
    }

    val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
    log.info("Chain activity done — {} in {}s runId={}", tally, "%.1f".format(elapsed), runId)
}

/** Fire one activity at [store] on the webhook plane; returns "ok" / "err" / "skip". */
private fun fire(
    driver: InboundPushDriver,
    type: Activity,
    store: Store,
    epcQueues: Map<String, ArrayDeque<String>>,
    skuLists: Map<String, List<Sku>>,
    soldLog: Path,
    runId: String,
    seq: Int,
    rng: Random,
): String {
    val id = "$runId-${type.name.lowercase()}-$seq"
    val now = Instant.now().toString()
    return when (type) {
        Activity.SALE -> {
            val epc = epcQueues[store.code]?.removeFirstOrNull() ?: return skip(seq, type, store, "floor pool empty")
            val resp = driver.pushSale(SaleEvent.byEpc(id, now, epc), WebhookClient.AuthMode.API_KEY)
            if (resp is ConnectResponse.Ok) appendSold(soldLog, epc)
            logFire(seq, type, store, "epc=$epc", resp)
        }

        Activity.RESTOCK -> {
            val skus = skuLists[store.code].orEmpty()
            if (skus.isEmpty()) return skip(seq, type, store, "no SKUs")
            val lines = (0..rng.nextInt(3)).map { ShipmentLine(skus[rng.nextInt(skus.size)].itemCd, 5 + rng.nextInt(26)) }
            val resp = driver.pushShipment(ShipmentManifest(id, store.siteId, lines), WebhookClient.AuthMode.API_KEY)
            logFire(seq, type, store, "site=${store.siteId} lines=${lines.size}", resp)
        }

        Activity.PRICING -> {
            val skus = skuLists[store.code].orEmpty()
            if (skus.isEmpty()) return skip(seq, type, store, "no SKUs")
            val sk = skus[rng.nextInt(skus.size)]
            val churned = (sk.priceMinor * (0.7 + rng.nextDouble() * 0.5)).toLong().coerceAtLeast(1)
            val resp = driver.pushPricing(PricingUpdate(sk.itemCd, churned, gtin = sk.ean), WebhookClient.AuthMode.API_KEY)
            logFire(seq, type, store, "sku=${sk.itemCd} gtin=${sk.ean} ${sk.priceMinor}->$churned", resp)
        }

        Activity.CATALOG -> {
            val skus = skuLists[store.code].orEmpty()
            if (skus.isEmpty()) return skip(seq, type, store, "no SKUs")
            val sk = skus[rng.nextInt(skus.size)]
            val resp = driver.pushCatalog(ProductCatalogItem(sk.itemCd, sk.name, gtin = sk.ean), WebhookClient.AuthMode.API_KEY)
            logFire(seq, type, store, "sku=${sk.itemCd} gtin=${sk.ean}", resp)
        }
    }
}

private fun skip(seq: Int, type: Activity, store: Store, why: String): String {
    log.warn("[{}] {} @{} skipped — {}", seq, type, store.code, why)
    return "skip"
}

private fun logFire(seq: Int, type: Activity, store: Store, detail: String, resp: ConnectResponse): String = when (resp) {
    is ConnectResponse.Ok -> {
        log.info("[{}] {} @{} {} → ack {}", seq, type, store.code, detail, resp.status)
        "ok"
    }

    is ConnectResponse.Err -> {
        log.error("[{}] {} @{} {} → ERR {} {}", seq, type, store.code, detail, resp.error.status, resp.error.code)
        "err"
    }
}

/** Build a length-[count] plan by weighted random draw across the activity types. */
private fun weightedPlan(count: Int, weights: Map<Activity, Int>, rng: Random): List<Activity> {
    val bag = weights.flatMap { (a, w) -> List(maxOf(0, w)) { a } }
    if (bag.isEmpty()) return List(count) { Activity.SALE }
    return List(count) { bag[rng.nextInt(bag.size)] }
}

/** site_ids.csv -> stores (store_code, site_id). */
private fun loadStores(file: Path): List<Store> {
    if (!Files.exists(file)) return emptyList()
    return Files.readAllLines(file).drop(1).mapNotNull { line ->
        val c = line.split(",")
        if (c.size < 2 || c[0].isBlank()) null else Store(c[0].trim(), c[1].trim())
    }
}

/** Per-store assortment.csv -> distinct SKUs (item_cd, name_en, price as store-currency minor units). */
private fun loadAssortment(file: Path): List<Sku> {
    if (!Files.exists(file)) return emptyList()
    val lines = Files.readAllLines(file)
    if (lines.size < 2) return emptyList()
    val h = splitCsv(lines.first())
    val iItem = h.indexOf("item_cd")
    val iEan = h.indexOf("ean")
    val iName = h.indexOf("name_en")
    val iPrice = h.indexOf("price_local")
    val iCcy = h.indexOf("currency")
    if (iItem < 0 || iEan < 0 || iName < 0 || iPrice < 0) return emptyList()
    // Dedupe on EAN, the sellable unit — NOT on item_cd. item_cd is a supplier STYLE code and deduping on
    // it dropped `5391035`'s second size entirely, so twin could not address the $109 variant at all while
    // its pricing deliveries rewrote both rows. EAN is measurably unique: 2,586 distinct across the chain,
    // zero blank.
    val seen = HashSet<String>()
    val out = ArrayList<Sku>()
    var blankSku = 0
    for (line in lines.drop(1)) {
        val c = splitCsv(line)
        if (c.size <= maxOf(iItem, iEan, iName, iPrice)) continue
        val itemCd = c[iItem].trim()
        val ean = c[iEan].trim()
        val price = c[iPrice].trim().toDoubleOrNull()
        if (ean.isEmpty() || price == null || !seen.add(ean)) continue
        // 5 products (the Van Rysel RCR Pro frame sizes) carry NO item_cd in source. They are skipped
        // rather than emitted with a blank sku — but counted and reported, because a silent skip is how
        // a catalog hole stays invisible. They become emittable once ingest keys on gtin.
        if (itemCd.isEmpty()) {
            blankSku++
            continue
        }
        val ccy = if (iCcy in c.indices) c[iCcy].trim() else "USD"
        out.add(Sku(itemCd, ean, c[iName].trim(), priceToMinor(price, ccy)))
    }
    if (blankSku >
        0
    ) {
        log.warn("{}: {} product(s) skipped — no item_cd in source (gtin present); emittable once ingest keys on gtin", file.fileName, blankSku)
    }
    return out
}

/** Minor units: zero-decimal currencies (KRW/JPY) x1, everything else x100. */
private fun priceToMinor(price: Double, currency: String): Long {
    val factor = if (currency.uppercase() in setOf("KRW", "JPY")) 1.0 else 100.0
    return (price * factor).toLong().coerceAtLeast(1)
}

/** Minimal RFC-4180-ish splitter — handles double-quoted fields + escaped quotes (names with commas). */
private fun splitCsv(line: String): List<String> {
    val out = ArrayList<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        when {
            ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                sb.append('"')
                i++
            }

            ch == '"' -> inQuotes = !inQuotes

            ch == ',' && !inQuotes -> {
                out.add(sb.toString())
                sb.setLength(0)
            }

            else -> sb.append(ch)
        }
        i++
    }
    out.add(sb.toString())
    return out
}

private fun env(key: String, default: String) = System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

private fun envInt(key: String, default: Int) = System.getenv(key)?.toIntOrNull() ?: default
