package com.m8trx.twin.connect

import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.sim.PlanogramDirectiveDriver
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Planogram-directive drive (Connect Mode 3, AS-BUILT ingest #64). Loads a store's `planogram.json`, maps
 * it to the as-built directive (real site UUID per target, optional fixture filter for a bounded smoke),
 * and POSTs at `X-Data-Type: planogram_directive` on the existing twin-pos webhook.
 *
 * SAFE BY DEFAULT — dry-run (builds + logs the directive, sends nothing). `M8TRX_PLANOGRAM_LIVE=true` fires.
 * !! HOLD FIRE until BACKEND confirms services #64 is merged + deployed (else the POST falls through the
 * generic data_type path, not the planogram landing). !!
 *
 * Defaults to **Denver only** + dry-run so an accidental run can't fire 10 full directives. For the smoke,
 * bound it to a fixture slice: `M8TRX_PLANOGRAM_FIXTURES=GB-R3-U1`.
 *
 * Env: M8TRX_PLANOGRAM_STORES (default dec-us-denver), M8TRX_PLANOGRAM_FIXTURES (comma list → bounded smoke;
 * default ALL), M8TRX_PLANOGRAM_EXT_ID (override external_directive_id), M8TRX_PLANOGRAM_EFFECTIVE_DATE,
 * M8TRX_PLANOGRAM_AUTH (hmac|api_key, default hmac), M8TRX_PLANOGRAM_LIVE (false), M8TRX_CHAIN_DIR.
 * Run: `./gradlew connectPlanogramDrive`.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ConnectPlanogramDrive")

fun main() {
    val config = ConnectConfig.fromEnv()
    val driver = PlanogramDirectiveDriver(WebhookClient(config))
    val chainDir = Path.of(env("M8TRX_CHAIN_DIR", "reference/data/chain"))
    val live = System.getenv("M8TRX_PLANOGRAM_LIVE")?.toBooleanStrictOrNull() ?: false
    val stores = env("M8TRX_PLANOGRAM_STORES", "dec-us-denver").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    val fixtures = System.getenv("M8TRX_PLANOGRAM_FIXTURES")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    val effectiveDate = System.getenv("M8TRX_PLANOGRAM_EFFECTIVE_DATE")?.takeIf { it.isNotBlank() }
    val extIdOverride = System.getenv("M8TRX_PLANOGRAM_EXT_ID")?.takeIf { it.isNotBlank() }
    val auth = if (env("M8TRX_PLANOGRAM_AUTH", "hmac").equals("api_key", ignoreCase = true)) {
        WebhookClient.AuthMode.API_KEY
    } else {
        WebhookClient.AuthMode.HMAC
    }

    val siteIds = loadSiteIds(chainDir.resolve("site_ids.csv"))

    log.info("Planogram drive → stores={} fixtures={} auth={} live={}", stores, if (fixtures.isEmpty()) "ALL" else fixtures, auth, live)
    var ok = 0
    var err = 0
    for (storeId in stores) {
        val pj = chainDir.resolve("stores/$storeId/planogram.json")
        if (!Files.exists(pj)) {
            log.warn("skip {} — no planogram.json (run scripts/build_planogram.py)", storeId)
            continue
        }
        val siteId = siteIds[storeId]
        if (siteId == null) {
            log.error("skip {} — no site UUID in site_ids.csv", storeId)
            err++
            continue
        }
        val doc = driver.loadDocument(pj)
        val directive = driver.toDirective(
            doc,
            siteId,
            externalDirectiveId = extIdOverride ?: doc.directiveRef,
            effectiveDate = effectiveDate,
            fixtures = fixtures,
        )
        if (directive.targets.isEmpty()) {
            log.warn("skip {} — 0 targets after fixture filter {}", storeId, fixtures)
            continue
        }
        if (live) {
            when (driver.drive(directive, auth)) {
                is ConnectResponse.Ok -> ok++
                is ConnectResponse.Err -> err++
            }
        } else {
            val json = driver.dryRun(directive)
            log.info(
                "[DRY] store={} site={} extId={} targets={} fixtures={} bytes={}",
                storeId,
                siteId,
                directive.externalDirectiveId,
                directive.targets.size,
                directive.targets.map { it.rawFixtureCode }.distinct().size,
                json.toByteArray().size,
            )
            ok++
        }
    }
    val mode = if (live) "LIVE" else "DRY-RUN"
    log.info("Planogram drive done — {} ok / {} err {}", ok, err, mode)
    if (live && err > 0) error("$err planogram directive(s) failed — see logs")
}

/** `site_ids.csv` → store_code → site UUID (cols: code,site_id,…). */
private fun loadSiteIds(file: Path): Map<String, String> {
    if (!Files.exists(file)) return emptyMap()
    return Files.readAllLines(file).drop(1).mapNotNull { line ->
        val c = line.split(",")
        if (c.size < 2 || c[0].isBlank() || c[1].isBlank()) null else c[0].trim() to c[1].trim()
    }.toMap()
}

private fun env(key: String, default: String) = System.getenv(key)?.takeIf { it.isNotBlank() } ?: default
