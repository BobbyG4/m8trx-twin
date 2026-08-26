package com.m8trx.twin.connect.sim

import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.ConnectClient
import com.m8trx.twin.connect.ConnectConfig
import com.m8trx.twin.connect.WebhookClient
import com.m8trx.twin.connect.appendSold
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.bearer.ItemDetail
import com.m8trx.twin.connect.model.bearer.ItemDetailsRequest
import com.m8trx.twin.connect.model.webhook.SaleEvent
import com.m8trx.twin.connect.readPriorSold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.random.Random

/** The three site-resolution input shapes a sale can take over Connect (CORE-REQ-005 part 2). */
enum class SaleArm {
    /** EPC only, no site/store hint — server resolves the site from the item band (the S11 NoScope path). */
    NOSCOPE,

    /** External `store_id` + SKU — server resolves the site via `integration_site_xref` (unmapped → quarantine). */
    STORE_XREF,

    /** Real site UUID + SKU — site supplied directly, server picks an in-stock unit of that SKU. */
    SITE_SCOPED,
}

/**
 * Launch-quality stress harness (CORE-REQ-005 part 2) — drives the demo-nucleus **sale load** at scale to
 * validate the ingesters + eval engine under pressure and generate the data density the surfaces render.
 *
 * Composes part 1: it sources realistic per-store sellable inventory the same way [FullLoopDriver] does
 * (floor `epcs.csv` minus the sold-log) and fires a **concurrent, multi-arm** `sale_event` storm across the
 * chain's retail stores. Coverage the smoke can't give:
 *
 * - **scale** — every retail store, many SKUs, high volume;
 * - **concurrency** — bounded-parallel fire (the ingesters under real contention);
 * - **all three site-resolution arms** ([SaleArm]) — NoScope EPC / store-code xref / site-scoped SKU;
 * - **edge cases** twin has surfaced — a same-`external_sale_id` **dedup replay** and an **unmapped store**
 *   (expected quarantine);
 * - **breakage report** — per-arm ok/err, HTTP status + error-code histograms, `429` rate-limit count, and
 *   throughput. That report is the force-function: it is what twin hands core (scale limits / breakages).
 *
 * SAFE BY DEFAULT: [run] with `live=false` builds the campaign and reports the volume it WOULD generate,
 * sending nothing. `live=true` hammers the tenant — gated behind a coordinated **"clear-to-hammer"** beat
 * (never blind-stress prod / the shared M8trxDemo tenant while core is mid-build).
 *
 * A 200 ack is *received*, not processed — so a live run optionally samples the NoScope sales back through
 * `items/details` (`inventory:read`) to assert they truly went SOLD (the part-1 input-assertion, at scale).
 */
class StressHarness(
    private val config: ConnectConfig,
    private val chainDir: Path = Path.of("reference/data/chain"),
    private val soldLog: Path = Path.of(".twin-state/sold-epcs.txt"),
) {
    private val log = LoggerFactory.getLogger(StressHarness::class.java)

    private val webhook = WebhookClient(config)
    private val client = ConnectClient(config)
    private val inbound = InboundPushDriver(webhook, client)

    companion object {
        /** A deliberately-unmapped external store code — the xref quarantine probe. */
        const val UNMAPPED_STORE = "STRESS-UNMAPPED-9999"
    }

    data class StressParams(
        val stores: List<String> = emptyList(), // empty = every retail store with an epcs.csv
        val salesPerStore: Int = 50,
        val arms: List<SaleArm> = listOf(SaleArm.NOSCOPE, SaleArm.STORE_XREF, SaleArm.SITE_SCOPED),
        val concurrency: Int = 8,
        val injectDedupReplay: Boolean = true,
        val injectUnmappedStore: Boolean = true,
        val verifySample: Int = 20, // live only: sample N NoScope SOLD-verifies via items/details (0 = skip)
        val seed: Long = 0L,
        val runId: String = "twin-stress",
        val webhookAuth: WebhookClient.AuthMode = WebhookClient.AuthMode.API_KEY,
    )

    /** One planned sale: an arm + the fields that arm needs. `epc` set only for NoScope. */
    data class SaleJob(
        val storeCode: String,
        val siteId: String,
        val arm: SaleArm,
        val sku: String,
        val saleId: String,
        val epc: String? = null,
        val note: String = "",
    )

    data class StressPlan(val params: StressParams, val jobs: List<SaleJob>, val storesPlanned: List<String>, val warnings: List<String>)

    // ---- plan (pure, offline) -------------------------------------------------------------------

    fun plan(params: StressParams): StressPlan {
        val warnings = ArrayList<String>()
        val siteIds = loadSiteIds(chainDir.resolve("site_ids.csv"))
        val sold = readPriorSold(soldLog)
        val rng = Random(params.seed)

        val requested = params.stores.ifEmpty { siteIds.keys.toList() }
        val stores = requested.filter { Files.exists(chainDir.resolve("stores/$it/epcs.csv")) && siteIds[it] != null }
        (requested - stores.toSet()).forEach { warnings += "skip $it — not a retail store (no epcs.csv) or no site UUID" }

        val jobs = ArrayList<SaleJob>()
        for (storeCode in stores) {
            val siteId = siteIds.getValue(storeCode)
            val floor = loadFloorEpcsWithSku(chainDir.resolve("stores/$storeCode/epcs.csv"), sold)
            if (floor.isEmpty()) {
                warnings += "skip $storeCode — no eligible floor EPCs (all sold?)"
                continue
            }
            val skus = floor.map { it.second }.distinct()
            val epcIter = floor.shuffled(rng).iterator()
            for (i in 0 until params.salesPerStore) {
                val arm = params.arms[i % params.arms.size]
                val saleId = "${params.runId}-$storeCode-${i + 1}"
                when (arm) {
                    SaleArm.NOSCOPE -> {
                        if (!epcIter.hasNext()) {
                            warnings += "$storeCode — floor pool exhausted at $i, dropping remaining NoScope jobs"
                            continue
                        }
                        val (epc, sku) = epcIter.next()
                        jobs += SaleJob(storeCode, siteId, arm, sku, saleId, epc = epc)
                    }

                    SaleArm.STORE_XREF ->
                        jobs += SaleJob(storeCode, siteId, arm, skus[rng.nextInt(skus.size)], saleId)

                    SaleArm.SITE_SCOPED ->
                        jobs += SaleJob(storeCode, siteId, arm, skus[rng.nextInt(skus.size)], saleId)
                }
            }
        }

        // edge injections
        if (params.injectUnmappedStore && stores.isNotEmpty()) {
            val siteId = siteIds.getValue(stores.first())
            repeat(3) { k ->
                jobs += SaleJob(
                    storeCode = UNMAPPED_STORE,
                    siteId = siteId,
                    arm = SaleArm.STORE_XREF,
                    sku = "STRESS-SKU-$k",
                    saleId = "${params.runId}-unmapped-${k + 1}",
                    note = "unmapped-store → expect quarantine",
                )
            }
        }
        if (params.injectDedupReplay) {
            jobs.firstOrNull { it.arm == SaleArm.NOSCOPE }?.let { orig ->
                jobs += orig.copy(note = "dedup-replay (same external_sale_id → expect dedupe, no double-sell)")
            }
        }

        return StressPlan(params, jobs, stores, warnings)
    }

    // ---- execute --------------------------------------------------------------------------------

    private data class SaleOutcome(val arm: SaleArm, val ok: Boolean, val status: Int, val code: String?, val epcSold: String?)

    data class StressResult(
        val fired: Int,
        val ok: Int,
        val err: Int,
        val byArm: Map<SaleArm, Pair<Int, Int>>, // arm -> (ok, err)
        val statusHist: Map<Int, Int>,
        val codeHist: Map<String, Int>,
        val rateLimited: Int,
        val elapsedSec: Double,
        val throughputPerSec: Double,
        val soldVerified: Int,
        val soldSampled: Int,
        val warnings: List<String>,
    )

    fun run(params: StressParams, live: Boolean): StressResult = execute(plan(params), live)

    fun execute(plan: StressPlan, live: Boolean): StressResult {
        val p = plan.params
        val mode = if (live) "LIVE" else "DRY-RUN"
        plan.warnings.forEach { log.warn("[stress] {}", it) }

        val byArmPlanned = plan.jobs.groupingBy { it.arm }.eachCount()
        log.info(
            "[stress] {} runId={} stores={} jobs={} arms={} concurrency={}",
            mode,
            p.runId,
            plan.storesPlanned.size,
            plan.jobs.size,
            byArmPlanned.toSortedMap(),
            p.concurrency,
        )

        if (!live) {
            log.info(
                "[stress][DRY] campaign — {} sale(s) across {} store(s); dedup-replay={} unmapped-probe={}; " +
                    "would fire {} Connect webhook POSTs. Ping the channel for clear-to-hammer, then M8TRX_STRESS_LIVE=true.",
                plan.jobs.size,
                plan.storesPlanned.size,
                p.injectDedupReplay,
                p.injectUnmappedStore,
                plan.jobs.size,
            )
            return StressResult(plan.jobs.size, 0, 0, emptyMap(), emptyMap(), emptyMap(), 0, 0.0, 0.0, 0, 0, plan.warnings)
        }

        val startMs = System.currentTimeMillis()
        val outcomes = runBlocking {
            val sem = Semaphore(p.concurrency)
            coroutineScope {
                plan.jobs.map { job ->
                    async(Dispatchers.IO) { sem.withPermit { fire(job, p.webhookAuth) } }
                }.awaitAll()
            }
        }
        val elapsed = (System.currentTimeMillis() - startMs) / 1000.0

        // persist NoScope sold EPCs (after the concurrent fire → no interleaved file writes)
        outcomes.mapNotNull { it.epcSold }.forEach { appendSold(soldLog, it) }

        val ok = outcomes.count { it.ok }
        val err = outcomes.size - ok
        val byArm = SaleArm.entries.associateWith { arm ->
            val a = outcomes.filter { it.arm == arm }
            a.count { it.ok } to a.count { !it.ok }
        }.filterValues { it.first + it.second > 0 }
        val statusHist = outcomes.groupingBy { it.status }.eachCount().toSortedMap()
        val codeHist = outcomes.filter { !it.ok }.mapNotNull { it.code }.groupingBy { it }.eachCount()
        val rateLimited = outcomes.count { it.status == 429 }
        val throughput = if (elapsed > 0) ok / elapsed else 0.0

        // sampled input-assertion: did the NoScope sales actually go SOLD? (200 = received, not processed)
        val soldEpcs = outcomes.filter { it.ok }.mapNotNull { it.epcSold }
        val sample = soldEpcs.shuffled(Random(p.seed)).take(p.verifySample)
        val soldVerified = if (sample.isNotEmpty()) verifySold(sample) else 0

        log.info("========== [stress] BREAKAGE REPORT ({}) ==========", mode)
        log.info(
            "[stress] fired={} ok={} err={} elapsed={}s throughput={}/s",
            outcomes.size,
            ok,
            err,
            "%.1f".format(elapsed),
            "%.1f".format(throughput),
        )
        log.info("[stress] by-arm (ok/err): {}", byArm.mapValues { "${it.value.first}/${it.value.second}" }.toSortedMap(compareBy { it.name }))
        log.info("[stress] http status histogram: {}", statusHist)
        if (codeHist.isNotEmpty()) log.info("[stress] error-code histogram: {}", codeHist.toSortedMap())
        if (rateLimited > 0) log.warn("[stress] RATE-LIMITED {} time(s) (429) — a scale ceiling; report to core", rateLimited)
        if (sample.isNotEmpty()) {
            log.info("[stress] SOLD-verify sample: {}/{} confirmed sold via items/details", soldVerified, sample.size)
        }
        log.info("[stress] ==================================================")

        return StressResult(
            fired = outcomes.size,
            ok = ok,
            err = err,
            byArm = byArm,
            statusHist = statusHist,
            codeHist = codeHist,
            rateLimited = rateLimited,
            elapsedSec = elapsed,
            throughputPerSec = throughput,
            soldVerified = soldVerified,
            soldSampled = sample.size,
            warnings = plan.warnings,
        )
    }

    private fun fire(job: SaleJob, auth: WebhookClient.AuthMode): SaleOutcome {
        val now = Instant.now().toString()
        val sale = when (job.arm) {
            SaleArm.NOSCOPE -> SaleEvent.byEpc(job.saleId, now, job.epc!!)
            SaleArm.STORE_XREF -> SaleEvent.byStore(job.saleId, now, job.storeCode, job.storeCode, job.sku, 1)
            SaleArm.SITE_SCOPED -> SaleEvent.bySku(job.saleId, now, job.siteId, job.sku, 1)
        }
        return when (val resp = inbound.pushSale(sale, auth)) {
            is ConnectResponse.Ok -> SaleOutcome(job.arm, true, resp.status, null, if (job.arm == SaleArm.NOSCOPE) job.epc else null)
            is ConnectResponse.Err -> SaleOutcome(job.arm, false, resp.error.status, resp.error.code, null)
        }
    }

    /** items/details read-back on a sample — how many of [epcs] the server reports `state=sold`. */
    private fun verifySold(epcs: List<String>): Int = when (val resp = client.itemDetails(ItemDetailsRequest(epcs))) {
        is ConnectResponse.Ok -> client.mapper.readValue<List<ItemDetail>>(resp.rawBody).count { it.state == "sold" }

        is ConnectResponse.Err -> {
            log.warn("[stress] SOLD-verify items/details failed — status={} code={}", resp.error.status, resp.error.code)
            0
        }
    }

    // ---- loaders --------------------------------------------------------------------------------

    /** epcs.csv (epc,ean,item_cd,category,fixture,store_id) → floor (epc, sku) pairs, minus BOH + [sold] + dupes. */
    private fun loadFloorEpcsWithSku(file: Path, sold: Set<String>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        for (line in Files.readAllLines(file).drop(1)) {
            val c = line.split(",")
            if (c.size < 6) continue
            val epc = c[0].trim()
            val sku = c[2].trim()
            val fixture = c[4].trim()
            if (epc.isEmpty() || sku.isEmpty() || epc in sold || fixture.startsWith("BR-")) continue
            if (seen.add(epc)) out.add(epc to sku)
        }
        return out
    }

    private fun loadSiteIds(file: Path): Map<String, String> {
        if (!Files.exists(file)) return emptyMap()
        return Files.readAllLines(file).drop(1).mapNotNull { line ->
            val c = line.split(",")
            if (c.size < 2 || c[0].isBlank() || c[1].isBlank()) null else c[0].trim() to c[1].trim()
        }.toMap()
    }
}
