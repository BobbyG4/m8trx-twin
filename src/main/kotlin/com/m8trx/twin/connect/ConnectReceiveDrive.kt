package com.m8trx.twin.connect

import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.bearer.ItemReceiveRequest
import com.m8trx.twin.connect.model.bearer.ItemReceiveResult
import com.m8trx.twin.connect.model.bearer.ReceiveItem
import com.m8trx.twin.connect.model.bearer.SpatialIdentityRequest
import com.m8trx.twin.connect.model.bearer.SpatialIdentityResponse
import com.m8trx.twin.connect.sim.DeviceDriver
import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.history.RecentDay
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Item-receive drive (compliance-remediation demo, #7 receive→relocate) — fires the existing
 * [DeviceDriver.receive] (Bearer data-plane, `POST /api/v2/inventory/items/receive`, Connect API
 * doc §6). Loads an EPC list, builds an [ItemReceiveRequest], and receives them into inventory at
 * the target site/space.
 *
 * SAFE BY DEFAULT — dry-run (builds + logs the request envelope, sends nothing).
 * `M8TRX_RECEIVE_LIVE=true` fires.
 * !! HOLD until you have a real Denver space UUID + Bearer — receive CREATES inventory server-side. !!
 *
 * Env: M8TRX_RECEIVE_SITE_ID (required), M8TRX_RECEIVE_SPACE_ID (required),
 * M8TRX_RECEIVE_EPC_FILE (default reference/data/chain/stores/dec-us-denver/receive-epcs.txt),
 * M8TRX_RECEIVE_LIVE (false), M8TRX_RECEIVE_RUN_ID (optional correlation tag).
 * Run: `./gradlew connectReceiveDrive`.
 *
 * ## Dated, ledgered receiving (2026-10-12) — `M8TRX_RECEIVE_DATE` set
 *
 * The unit-creating half of the restock loop: the manifest webhook records expected quantities only; `items/receive`
 * creates the units. For the date's persisted sales plan (`.twin-state/stream/twin-day-<yyyymmdd>-<seed>.plan.csv` + its
 * `.sent` ledger) it reproduces each store's restock manifest ([DatedStream.manifestLines]: units sold per item code,
 * rounded up to cases of 6), mints new SGTIN-96 EPCs for those EANs from the receiving serial block, and sends them to the
 * store's stockroom space — ONE request per store, ≤ 200 EPCs each. Rules, EPC block and ledgers: [ReceiveStock].
 *
 * Env: `M8TRX_RECEIVE_DATE` (yyyy-mm-dd) · `M8TRX_RECEIVE_SEED` (20261016) · `M8TRX_RECEIVE_LIVE` (false) ·
 * `M8TRX_RECEIVE_STORES` (csv, default all ten) · `M8TRX_RECEIVE_GAP_MS` (250) · `M8TRX_CHAIN_DIR` · `M8TRX_STREAM_SOLD_LOG`
 * (state dir) · `M8TRX_RECEIVE_SPACES` (csv `slug,space_id` of the stockroom spaces; default
 * `reference/data/chain/stockroom_spaces.csv`, then `.twin-state/receive/stockroom-spaces.csv`; a store in neither is
 * looked up LIVE through `POST /spatial/identity` (space_type stockroom) and cached in the second file).
 * Dry-run (the default) builds and prints everything and writes nothing.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ConnectReceiveDrive")

fun main() {
    val config = ConnectConfig.fromEnv()
    val live = System.getenv("M8TRX_RECEIVE_LIVE")?.toBooleanStrictOrNull() ?: false
    if (!System.getenv("M8TRX_RECEIVE_DATE").isNullOrBlank()) {
        datedReceive(config, live)
        return
    }
    val siteId = System.getenv("M8TRX_RECEIVE_SITE_ID")?.takeIf { it.isNotBlank() }
        ?: error("M8TRX_RECEIVE_SITE_ID is not set — the target site UUID to receive into")
    val spaceId = System.getenv("M8TRX_RECEIVE_SPACE_ID")?.takeIf { it.isNotBlank() }
        ?: error("M8TRX_RECEIVE_SPACE_ID is not set — the target space UUID to receive into")
    val epcFile = Path.of(env("M8TRX_RECEIVE_EPC_FILE", "reference/data/chain/stores/dec-us-denver/receive-epcs.txt"))
    val runId = System.getenv("M8TRX_RECEIVE_RUN_ID")?.takeIf { it.isNotBlank() }

    log.info("Receive drive → siteId={} spaceId={} live={} epcFile={}", siteId, spaceId, live, epcFile)

    if (!Files.exists(epcFile)) {
        error("no EPC file at $epcFile — stage one EPC per line (see reference/data/chain staging)")
    }
    val epcs = Files.readAllLines(epcFile).map { it.trim() }.filter { it.isNotEmpty() }
    if (epcs.isEmpty()) {
        error("$epcFile contained no EPCs — nothing to receive")
    }

    val req = ItemReceiveRequest(siteId = siteId, spaceId = spaceId, items = epcs.map { ReceiveItem(it) })

    if (live) {
        val driver = DeviceDriver(ConnectClient(config))
        when (val resp = driver.receive(siteId, spaceId, epcs, runId)) {
            is ConnectResponse.Ok -> log.info("Receive drive done — 1 ok / 0 err LIVE")
            is ConnectResponse.Err -> error("receive drive failed — status=${resp.error.status} code=${resp.error.code}")
        }
    } else {
        val json = ConnectMappers.camel.writeValueAsString(req)
        log.info("[DRY] siteId={} spaceId={} items={} bytes={}", siteId, spaceId, epcs.size, json.toByteArray().size)
        log.info("[DRY] request={}", json)
        log.info("Receive drive done — 1 ok / 0 err DRY-RUN")
    }
}

/** Server replies that mean "this EPC is already in stock" - a retry of an accepted batch, not a failure. */
private val ALREADY_RECEIVED = Regex("exist|already|duplicate|in.?stock", RegexOption.IGNORE_CASE)

private fun datedReceive(config: ConnectConfig, live: Boolean) {
    val chainDir = Path.of(env("M8TRX_CHAIN_DIR", "reference/data/chain"))
    val date = LocalDate.parse(env("M8TRX_RECEIVE_DATE", ""))
    val seed = System.getenv("M8TRX_RECEIVE_SEED")?.toLongOrNull() ?: RecentDay.HISTORY_SEED
    val gapMs = System.getenv("M8TRX_RECEIVE_GAP_MS")?.toLongOrNull() ?: 250L
    val soldLog = Path.of(env("M8TRX_STREAM_SOLD_LOG", ".twin-state/sold-epcs.txt"))
    val keys = ChainKeys.load(chainDir.resolve(ChainKeys.DEFAULT_PATH.fileName))
    val stores = System.getenv("M8TRX_RECEIVE_STORES")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: keys.stores.map { it.slug }
    val siteIds = DatedStream.readSiteIds(chainDir.resolve("site_ids.csv"))
    val dir = ReceiveStock.receiveDir(soldLog)
    val t0 = System.currentTimeMillis()
    log.info("Receive drive (dated) → date={} seed={} stores={} live={}", date, seed, stores.size, live)

    val plans = ReceiveStock.planAll(chainDir, soldLog, date, seed, stores, log)
    plans.filter { it.soldUnits > 0 }.forEach { p ->
        log.info(
            "  receive {} sold {} · manifest {} lines / {} cases-units · already received {} · {} new units",
            p.slug,
            p.soldUnits,
            p.lines.size,
            p.targetUnits,
            p.alreadyReceived,
            p.units,
        )
    }
    val units = plans.sumOf { it.units }
    val lineCount = plans.sumOf { it.lines.size }
    val batches = plans.sumOf { (it.units + ReceiveStock.BATCH_MAX - 1) / ReceiveStock.BATCH_MAX }
    log.info("PLAN: receive {} units / {} manifest lines in {} requests (stores {})", units, lineCount, batches, stores.size)

    // the stockroom space of each store: files first; a store with units to receive and no entry is looked up live
    val overrideFile = Path.of(env("M8TRX_RECEIVE_SPACES", chainDir.resolve("stockroom_spaces.csv").toString()))
    val cacheFile = dir.resolve("stockroom-spaces.csv")
    val spaces = HashMap(ReceiveStock.readSpaces(overrideFile) + ReceiveStock.readSpaces(cacheFile))
    val client = if (live) ConnectClient(config) else null

    var ok = 0
    var err = 0
    var epcErrors = 0
    var received = 0
    var stop = false
    for (p in plans) {
        if (p.units == 0 || stop) continue
        val siteId = siteIds[p.slug] ?: error("no site id for ${p.slug} in site_ids.csv")
        val manifestId = ReceiveStock.manifestId(date, seed, p.slug)
        if (p.slug !in spaces && client != null) {
            val found = resolveStockroom(client, p.slug)
            if (found.failed) {
                // a refused lookup is NOT "no stockroom": never fall back to the sales floor on it
                err++
                log.error("  {} skipped: the stockroom space could not be looked up - give M8TRX_RECEIVE_SPACES (csv slug,space_id)", p.slug)
                continue
            }
            found.spaceId?.let {
                spaces[p.slug] = it
                Files.createDirectories(dir)
                if (!Files.exists(cacheFile)) Files.writeString(cacheFile, "slug,space_id\n")
                Files.writeString(cacheFile, "${p.slug},$it\n", java.nio.file.StandardOpenOption.APPEND)
            }
        }
        val spaceId = spaces[p.slug]
            ?: if (live) {
                log.warn("  {} has no stockroom space; receiving into the SALES FLOOR space (BOH zone not addressable)", p.slug)
                keys.store(p.slug).salesFloorSpaceId
            } else {
                "<stockroom space of ${p.slug}: looked up live via spatial/identity>"
            }
        for ((b, chunk) in p.toReceive.chunked(ReceiveStock.BATCH_MAX).withIndex()) {
            val req = ItemReceiveRequest(siteId = siteId, spaceId = spaceId, items = chunk.map { ReceiveItem(it.epc) })
            log.info(
                "  {} {} {} (manifest {}) batch {} → space {} items={}",
                if (live) "[LIVE]" else "[DRY]",
                "receive",
                p.slug,
                manifestId,
                b + 1,
                spaceId,
                chunk.size,
            )
            if (client == null) continue
            when (val r = client.receiveItems(req)) {
                is ConnectResponse.Ok -> {
                    val result = runCatching { client.mapper.readValue<ItemReceiveResult>(r.rawBody) }.getOrDefault(ItemReceiveResult())
                    val failed = result.errors.filter {
                        it.epc != null && !ALREADY_RECEIVED.containsMatchIn(it.reason.orEmpty())
                    }.mapNotNull { it.epc }.toSet()
                    val accepted = chunk.filter { it.epc !in failed }
                    ok++
                    epcErrors += failed.size
                    received += accepted.size
                    if (accepted.isNotEmpty()) {
                        ReceiveStock.appendStock(
                            ReceiveStock.stockFile(dir),
                            ReceiveStock.ledgerFile(dir, date, seed),
                            accepted,
                        )
                    }
                    log.info("    received={} created={} refused={} (of {})", result.received, result.created, failed.size, chunk.size)
                    result.errors.take(3).forEach { log.warn("    epc {} → {}", it.epc, it.reason) }
                    Thread.sleep(gapMs)
                }

                is ConnectResponse.Err -> {
                    err++
                    log.error("  {} refused {} {} - stopping; a re-run resumes from the ledger", manifestId, r.error.status, r.error.code)
                    stop = true
                }
            }
            if (stop) break
        }
    }
    plans.firstOrNull { it.units > 0 }?.let { p ->
        log.info(
            "  first request: {}",
            ConnectMappers.camel.writeValueAsString(
                ItemReceiveRequest(siteIds[p.slug].orEmpty(), spaces[p.slug] ?: "<stockroom space>", p.toReceive.take(2).map { ReceiveItem(it.epc) }),
            ),
        )
    }
    log.info(
        "SIM-RUN receive date={} seed={} stores={} lines={} units={} sent_ok={} sent_err={} epc_errors={} received={} wall={}s",
        date, seed, stores.size, lineCount, units, ok, err, epcErrors, received, (System.currentTimeMillis() - t0) / 1000,
    )
    if (live && (err > 0 || epcErrors > 0)) error("receiving was refused in part - see the log; a re-run resumes from the ledger")
}

private data class StockroomLookup(val spaceId: String?, val failed: Boolean)

/** The stockroom space of [slug]: `POST /spatial/identity` (inventory:read), the space whose `space_type` is stockroom. */
private fun resolveStockroom(client: ConnectClient, slug: String): StockroomLookup =
    when (val r = client.spatialIdentity(SpatialIdentityRequest(siteRef = slug, includeZones = false))) {
        is ConnectResponse.Ok -> StockroomLookup(
            client.mapper.readValue<SpatialIdentityResponse>(r.rawBody).sites.flatMap { it.spaces }
                .firstOrNull { it.spaceType.equals("stockroom", ignoreCase = true) }?.spaceId,
            failed = false,
        )

        is ConnectResponse.Err -> {
            log.warn("  spatial/identity for {} failed {} {}", slug, r.error.status, r.error.code)
            StockroomLookup(null, failed = true)
        }
    }

private fun env(key: String, default: String) = System.getenv(key)?.takeIf { it.isNotBlank() } ?: default
