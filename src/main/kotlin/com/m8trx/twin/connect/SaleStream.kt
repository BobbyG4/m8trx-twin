package com.m8trx.twin.connect

import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.webhook.SaleEvent
import com.m8trx.twin.connect.sim.InboundPushDriver
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.random.Random

/**
 * Live sale-stream driver (COORD hand-off, S11) — opens the "real-data tap".
 *
 * Drives a realistic stream of EPC-attributed `sale_event`s through the proven webhook plane from
 * the seeded in-stock Denver inventory, so the cockpit + analytics surfaces fill with real twin
 * data. Webhook-plane ONLY (X-API-Key): every sale uses the verified NoScope → item-band PROCESS
 * path (epc only — NO location code, NO site_id), the same path the S188 case #1 smoke proved SOLD.
 *
 * Discipline: a FRESH external_sale_id per sale; DISTINCT EPCs drawn fresh from the sales FLOOR
 * (back-of-house racks + the 3 known-sold artifacts excluded); paced over a window with jitter
 * (a "Denver afternoon"). Inventory is finite + monotonic — each EPC sells exactly once.
 *
 * Env (all optional): `M8TRX_STREAM_EPC_FILE`, `M8TRX_STREAM_COUNT` (40), `M8TRX_STREAM_DURATION_SEC`
 * (180), `M8TRX_STREAM_WARMUP` (fire 1 + stop — the validate-first gate), `M8TRX_STREAM_SEED`,
 * `M8TRX_RUN_ID`, `M8TRX_STREAM_EXCLUDE` (extra EPCs, comma-sep), `M8TRX_STREAM_SOLD_LOG` (a local
 * auto-exclude list of already-sold EPCs, appended per sale). Run: `./gradlew connectSaleStream`.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.SaleStream")

/** EPCs already sold in prior exercises (S9 + the S188 case #1 smoke) — never re-sell these. */
private val SOLD_ARTIFACTS = setOf(
    "3039606303C19FC00021C51C",
    "3039606303C19FC0005756B1",
    "3039606303C19FC0008F4287",
)

fun main() {
    val config = ConnectConfig.fromEnv()
    val driver = InboundPushDriver(WebhookClient(config), ConnectClient(config))

    val epcFile = env("M8TRX_STREAM_EPC_FILE", "reference/data/chain/stores/dec-us-denver/epcs.csv")
    val count = envInt("M8TRX_STREAM_COUNT", 40)
    val durationSec = envInt("M8TRX_STREAM_DURATION_SEC", 180)
    val warmup = System.getenv("M8TRX_STREAM_WARMUP")?.toBooleanStrictOrNull() ?: false
    val seed = System.getenv("M8TRX_STREAM_SEED")?.toLongOrNull() ?: System.currentTimeMillis()
    val runId = env("M8TRX_RUN_ID", "twin-s11-denver-$seed")
    val extraExcludes = System.getenv("M8TRX_STREAM_EXCLUDE")?.split(",").orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    val soldLog = Path.of(env("M8TRX_STREAM_SOLD_LOG", ".twin-state/sold-epcs.txt"))
    val priorSold = readPriorSold(soldLog)
    val excludes = SOLD_ARTIFACTS + extraExcludes + priorSold

    val pool = loadFloorEpcs(Path.of(epcFile), excludes)
    check(pool.isNotEmpty()) { "no eligible floor EPCs in $epcFile (after BOH + exclude filters)" }

    val rng = Random(seed)
    val n = if (warmup) 1 else minOf(count, pool.size)
    val picks = pool.shuffled(rng).take(n)

    log.info(
        "Sale stream → tenant={} slug={} file={} pool={} priorSold={} firing={} window={}s warmup={} runId={}",
        config.tenantId,
        config.integrationSlug,
        epcFile,
        pool.size,
        priorSold.size,
        n,
        if (warmup) 0 else durationSec,
        warmup,
        runId,
    )

    var ok = 0
    var err = 0
    val startMs = System.currentTimeMillis()
    picks.forEachIndexed { i, epc ->
        val saleId = "$runId-${i + 1}"
        val sale = SaleEvent.byEpc(saleId, Instant.now().toString(), epc)
        when (val resp = driver.pushSale(sale, WebhookClient.AuthMode.API_KEY)) {
            is ConnectResponse.Ok -> {
                ok++
                appendSold(soldLog, epc)
                log.info("[{}/{}] {} epc={} → ack {}", i + 1, n, saleId, epc, resp.status)
            }
            is ConnectResponse.Err -> {
                err++
                log.error("[{}/{}] {} epc={} → ERR {} {}", i + 1, n, saleId, epc, resp.error.status, resp.error.code)
            }
        }
        if (!warmup && i < n - 1) Thread.sleep(jitterGapMs(durationSec, n, rng))
    }

    val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
    val perMin = if (elapsed > 0) ok / elapsed * 60 else 0.0
    log.info(
        "Stream done — fired={} ok={} err={} elapsed={}s throughput={}/min runId={}",
        n,
        ok,
        err,
        "%.1f".format(elapsed),
        "%.1f".format(perMin),
        runId,
    )
}

/** Per-sale gap: mean = window/n, jittered ±50%, floored at 50ms so a tight window still flows. */
private fun jitterGapMs(durationSec: Int, n: Int, rng: Random): Long {
    if (n <= 1) return 0
    val meanMs = durationSec.toDouble() * 1000 / n
    val jittered = meanMs * (0.5 + rng.nextDouble())
    return maxOf(50L, jittered.toLong())
}

/** Prior-sold EPCs from the local sold-log (empty if none yet) — inventory is finite + monotonic. */
private fun readPriorSold(soldLog: Path): Set<String> {
    if (!Files.exists(soldLog)) return emptySet()
    return Files.readAllLines(soldLog).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

/** Append a sold EPC so later runs never re-sell it (the in_stock read-side lives on mother, not here). */
private fun appendSold(soldLog: Path, epc: String) {
    soldLog.parent?.let { Files.createDirectories(it) }
    Files.writeString(soldLog, "$epc\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
}

/**
 * Read sales-FLOOR EPCs from a chain `epcs.csv` (cols: epc,ean,item_cd,category,fixture,store_id).
 * Drops the header, back-of-house racks (fixture `BR-*`), [excludes], blanks, and duplicate EPCs.
 */
fun loadFloorEpcs(file: Path, excludes: Set<String> = emptySet()): List<String> {
    val out = ArrayList<String>()
    val seen = HashSet<String>()
    for (line in Files.readAllLines(file).drop(1)) {
        val c = line.split(",")
        if (c.size < 6) continue
        val epc = c[0].trim()
        val fixture = c[4].trim()
        if (epc.isEmpty() || epc in excludes || fixture.startsWith("BR-")) continue
        if (seen.add(epc)) out.add(epc)
    }
    return out
}

private fun env(key: String, default: String) = System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

private fun envInt(key: String, default: Int) = System.getenv(key)?.toIntOrNull() ?: default
