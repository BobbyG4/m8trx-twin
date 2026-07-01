package com.m8trx.twin.connect

import com.m8trx.twin.connect.sim.SaleArm
import com.m8trx.twin.connect.sim.StressHarness
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Launch-quality stress drive (CORE-REQ-005 part 2) — fires a concurrent, multi-arm `sale_event` storm across
 * the chain's retail stores to validate the ingesters + eval engine under load and generate data density.
 * See [StressHarness] for the campaign shape (scale · concurrency · the 3 site-resolution arms · dedup +
 * unmapped edge probes · breakage report).
 *
 * SAFE BY DEFAULT — dry-run: reports the campaign volume it WOULD generate, sends nothing.
 * `M8TRX_STRESS_LIVE=true` hammers the tenant — **only after a coordinated "clear-to-hammer"** (never
 * blind-stress prod / the shared M8trxDemo tenant while core is mid-build). Needs `M8TRX_TWIN_WEBHOOK_KEY`
 * (sales) + `M8TRX_TWIN_BEARER` (the SOLD-verify sample) in `.env`.
 *
 * Env: M8TRX_STRESS_STORES (default ALL retail), M8TRX_STRESS_SALES_PER_STORE (50), M8TRX_STRESS_ARMS
 * (noscope,store_xref,site_scoped), M8TRX_STRESS_CONCURRENCY (8), M8TRX_STRESS_DEDUP (true),
 * M8TRX_STRESS_UNMAPPED (true), M8TRX_STRESS_VERIFY_SAMPLE (20), M8TRX_STRESS_SEED / M8TRX_STREAM_SEED,
 * M8TRX_RUN_ID, M8TRX_STRESS_AUTH (api_key|hmac), M8TRX_STRESS_LIVE (false), M8TRX_CHAIN_DIR,
 * M8TRX_STREAM_SOLD_LOG. Run: `./gradlew connectStress`.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ConnectStress")

fun main() {
    val config = ConnectConfig.fromEnv()
    val chainDir = Path.of(env("M8TRX_CHAIN_DIR", "reference/data/chain"))
    val soldLog = Path.of(env("M8TRX_STREAM_SOLD_LOG", ".twin-state/sold-epcs.txt"))
    val live = System.getenv("M8TRX_STRESS_LIVE")?.toBooleanStrictOrNull() ?: false
    val seed = System.getenv("M8TRX_STRESS_SEED")?.toLongOrNull()
        ?: System.getenv("M8TRX_STREAM_SEED")?.toLongOrNull()
        ?: System.currentTimeMillis()

    val params = StressHarness.StressParams(
        stores = System.getenv("M8TRX_STRESS_STORES")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
        salesPerStore = envInt("M8TRX_STRESS_SALES_PER_STORE", 50),
        arms = parseArms(env("M8TRX_STRESS_ARMS", "noscope,store_xref,site_scoped")),
        concurrency = envInt("M8TRX_STRESS_CONCURRENCY", 8),
        injectDedupReplay = System.getenv("M8TRX_STRESS_DEDUP")?.toBooleanStrictOrNull() ?: true,
        injectUnmappedStore = System.getenv("M8TRX_STRESS_UNMAPPED")?.toBooleanStrictOrNull() ?: true,
        verifySample = envInt("M8TRX_STRESS_VERIFY_SAMPLE", 20),
        seed = seed,
        runId = env("M8TRX_RUN_ID", "twin-stress-$seed"),
        webhookAuth = if (env("M8TRX_STRESS_AUTH", "api_key").equals("hmac", ignoreCase = true)) {
            WebhookClient.AuthMode.HMAC
        } else {
            WebhookClient.AuthMode.API_KEY
        },
    )

    val r = StressHarness(config, chainDir, soldLog).run(params, live)
    val mode = if (live) "LIVE" else "DRY-RUN"
    if (live) {
        log.info(
            "Stress done ({}) — fired {} ok={} err={} rateLimited={} throughput={}/s soldVerified={}/{}",
            mode,
            r.fired,
            r.ok,
            r.err,
            r.rateLimited,
            "%.1f".format(r.throughputPerSec),
            r.soldVerified,
            r.soldSampled,
        )
        if (r.fired > 0 && r.ok == 0) error("every stress sale failed — see the breakage report")
    } else {
        log.info("Stress plan ({}) — {} sale(s) planned; set M8TRX_STRESS_LIVE=true after clear-to-hammer to fire", mode, r.fired)
    }
}

private fun parseArms(raw: String): List<SaleArm> {
    val arms = raw.split(",").mapNotNull {
        when (it.trim().lowercase()) {
            "noscope" -> SaleArm.NOSCOPE
            "store_xref", "store-xref", "xref" -> SaleArm.STORE_XREF
            "site_scoped", "site-scoped", "site" -> SaleArm.SITE_SCOPED
            else -> null
        }
    }.distinct()
    return arms.ifEmpty { listOf(SaleArm.NOSCOPE, SaleArm.STORE_XREF, SaleArm.SITE_SCOPED) }
}

private fun env(key: String, default: String) = System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

private fun envInt(key: String, default: Int) = System.getenv(key)?.toIntOrNull() ?: default
