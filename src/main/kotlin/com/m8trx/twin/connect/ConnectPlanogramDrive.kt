package com.m8trx.twin.connect

import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.sim.PlanogramDirectiveDriver
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Planogram-directive drive (Connect Mode 3 — PLANOGRAM-RESOLVED-DESIGN-2026-06-30 §6). The twin posts
 * each store's m8trx_standard planogram (`scripts/build_planogram.py` → `stores/<id>/planogram.json`)
 * as a `directive_kind='planogram'` envelope at the inbound-directive Connect channel.
 *
 * SAFE BY DEFAULT — dry-run: it loads + builds + logs the directive envelope and sends NOTHING. The
 * live send is additionally GATED on the channel existing (`mig 152a` / fork #11); set
 * M8TRX_PLANOGRAM_LIVE=true to attempt it once core ships the channel.
 *
 * Env: M8TRX_PLANOGRAM_STORES (comma list; default all), M8TRX_PLANOGRAM_EFFECTIVE_DATE (default
 * tomorrow 00:00), M8TRX_PLANOGRAM_LIVE (false), M8TRX_CHAIN_DIR (reference/data/chain).
 * Run: `./gradlew connectPlanogramDrive`.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ConnectPlanogramDrive")

fun main() {
    val config = ConnectConfig.fromEnv()
    val driver = PlanogramDirectiveDriver(WebhookClient(config), integrationId = config.integrationSlug ?: "twin-pos")
    val chainDir = Path.of(env("M8TRX_CHAIN_DIR", "reference/data/chain"))
    val live = System.getenv("M8TRX_PLANOGRAM_LIVE")?.toBooleanStrictOrNull() ?: false
    val effectiveDate = env("M8TRX_PLANOGRAM_EFFECTIVE_DATE", "${LocalDate.now().plusDays(1)}T00:00:00")

    val planograms = loadPlanograms(chainDir, System.getenv("M8TRX_PLANOGRAM_STORES"))
    check(planograms.isNotEmpty()) { "no planogram.json under $chainDir/stores — run scripts/build_planogram.py first" }

    log.info("Planogram drive → stores={} effectiveDate={} live={}", planograms.size, effectiveDate, live)
    var ok = 0
    var err = 0
    for (p in planograms) {
        val doc = driver.loadDocument(p)
        if (live) {
            when (driver.drive(doc, effectiveDate)) {
                is ConnectResponse.Ok -> ok++
                is ConnectResponse.Err -> err++
            }
        } else {
            val json = driver.dryRun(doc, effectiveDate)
            log.info(
                "[DRY] site={} kind=planogram format={} lines={} fixtures={} ref={} bytes={}",
                doc.siteRef,
                doc.format,
                doc.lines.size,
                doc.lines.map { it.fixtureCode }.distinct().size,
                doc.directiveRef,
                json.toByteArray().size,
            )
            ok++
        }
    }
    val mode = if (live) "LIVE" else "DRY-RUN"
    log.info("Planogram drive done — {} directives ({} ok / {} err) {}", planograms.size, ok, err, mode)
    if (live && err > 0) {
        error("$err planogram directive(s) failed — see logs (channel may not be provisioned yet: mig 152a / fork #11)")
    }
}

/** Find `stores/<id>/planogram.json`, optionally filtered by a comma-separated store list. */
private fun loadPlanograms(chainDir: Path, filterCsv: String?): List<Path> {
    val storesDir = chainDir.resolve("stores")
    if (!Files.isDirectory(storesDir)) return emptyList()
    val filter = filterCsv?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
    val storeDirs = Files.list(storesDir).use { stream -> stream.sorted().toList() }
    return storeDirs
        .filter { Files.isDirectory(it) }
        .filter { filter == null || it.fileName.toString() in filter }
        .map { it.resolve("planogram.json") }
        .filter { Files.exists(it) }
}

private fun env(key: String, default: String) = System.getenv(key)?.takeIf { it.isNotBlank() } ?: default
