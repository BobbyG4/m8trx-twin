package com.m8trx.twin.connect

import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.bearer.ComplianceStateRequest
import com.m8trx.twin.connect.model.bearer.ComplianceStateResponse
import com.m8trx.twin.connect.model.webhook.PlanogramDirective
import com.m8trx.twin.connect.sim.PlanogramDirectiveDriver
import com.m8trx.twin.history.ChainKeys
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Planogram-directive drive (Connect Mode 3, AS-BUILT ingest #64). Loads a store's `planogram.json`, maps
 * it to the as-built directive (real site UUID per target, optional fixture filter for a bounded smoke),
 * and POSTs at `X-Data-Type: planogram_directive` on the existing twin-pos webhook.
 *
 * SAFE BY DEFAULT — dry-run (builds + logs the directive, sends nothing). `M8TRX_PLANOGRAM_LIVE=true` fires.
 *
 * INGEST LIVE (services #64, deployed 2026-06-30; resolver #65/#66). LIVE-PROVEN in S12: the Denver
 * `GB-R3-U1` slice (28 targets) landed and all 28 resolved, triple-verified. Dry-run stays the default
 * because a full directive is 2,504 targets, not because the surface is unproven.
 *
 * Defaults to **Denver only** + dry-run so an accidental run can't fire 10 full directives. For the smoke,
 * bound it to a fixture slice: `M8TRX_PLANOGRAM_FIXTURES=GB-R3-U1`.
 *
 * ## The ten-store plans (demo sprint 2026-10, BRIEF-TWIN-RECENT-WINDOW § 2)
 *
 * `M8TRX_PLANOGRAM_STORES=all` (or a csv) × `M8TRX_PLANOGRAM_GRAIN=curated` × `M8TRX_PLANOGRAM_REF_DATE=20261012`
 * sends one plan per store: [PlanogramDirectiveDriver.curate]'s 28 lines spread over the store's department
 * walls (the key export's fixtures with a department), each under its OWN external ref
 * `PLN-<slug>-<yyyymmdd>`. The ref matters: a re-send under the same ref deletes and rebuilds that
 * directive's targets, so two stores sharing one would overwrite each other — the run refuses before
 * sending anything if the refs are not distinct. A live run stops at the FIRST refused store rather than
 * pressing on across the chain. After each accepted send it reads the directive back over
 * `POST /compliance/state` (Bearer, `inventory:read`) for its id; a refused read-back is reported, not fatal.
 * `/evaluate` is JWT-only and is not called here.
 *
 * Env: M8TRX_PLANOGRAM_STORES (default dec-us-denver; `all` = every store in site_ids.csv),
 * M8TRX_PLANOGRAM_EXCLUDE (csv), M8TRX_PLANOGRAM_FIXTURES (comma list → bounded smoke; default ALL),
 * M8TRX_PLANOGRAM_GRAIN (full|curated, default full), M8TRX_PLANOGRAM_LINES (curated size, 28),
 * M8TRX_PLANOGRAM_REF_DATE (yyyymmdd or yyyy-mm-dd → ref `PLN-<slug>-<date>`; default the document's own ref),
 * M8TRX_PLANOGRAM_EXT_ID (override external_directive_id — single store only), M8TRX_PLANOGRAM_EFFECTIVE_DATE,
 * M8TRX_PLANOGRAM_AUTH (hmac|api_key, default hmac), M8TRX_PLANOGRAM_LIVE (false),
 * M8TRX_PLANOGRAM_READBACK_WAIT_MS (8000), M8TRX_CHAIN_DIR. Run: `./gradlew connectPlanogramDrive`.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ConnectPlanogramDrive")

fun main() {
    val config = ConnectConfig.fromEnv()
    val driver = PlanogramDirectiveDriver(WebhookClient(config))
    val chainDir = Path.of(env("M8TRX_CHAIN_DIR", "reference/data/chain"))
    val live = System.getenv("M8TRX_PLANOGRAM_LIVE")?.toBooleanStrictOrNull() ?: false
    val siteIds = loadSiteIds(chainDir.resolve("site_ids.csv"))
    val exclude = csvEnv("M8TRX_PLANOGRAM_EXCLUDE").toSet()
    val storesArg = env("M8TRX_PLANOGRAM_STORES", "dec-us-denver")
    val stores = (if (storesArg.equals("all", ignoreCase = true)) siteIds.keys.toList() else csv(storesArg)).filter { it !in exclude }
    val fixtures = csvEnv("M8TRX_PLANOGRAM_FIXTURES").toSet()
    val curated = env("M8TRX_PLANOGRAM_GRAIN", "full").equals("curated", ignoreCase = true)
    val curatedLines = System.getenv("M8TRX_PLANOGRAM_LINES")?.toIntOrNull() ?: PlanogramDirectiveDriver.CURATED_LINES
    val refDate = System.getenv("M8TRX_PLANOGRAM_REF_DATE")?.takeIf { it.isNotBlank() }?.let(::parseRefDate)
    val effectiveDate = System.getenv("M8TRX_PLANOGRAM_EFFECTIVE_DATE")?.takeIf { it.isNotBlank() }
    val extIdOverride = System.getenv("M8TRX_PLANOGRAM_EXT_ID")?.takeIf { it.isNotBlank() }
    val readbackWaitMs = System.getenv("M8TRX_PLANOGRAM_READBACK_WAIT_MS")?.toLongOrNull() ?: 8_000L
    val auth = if (env("M8TRX_PLANOGRAM_AUTH", "hmac").equals("api_key", ignoreCase = true)) {
        WebhookClient.AuthMode.API_KEY
    } else {
        WebhookClient.AuthMode.HMAC
    }
    require(extIdOverride == null || stores.size <= 1) {
        "M8TRX_PLANOGRAM_EXT_ID would give ${stores.size} stores ONE ref — each would rebuild the last"
    }
    val walls: Map<String, Map<String, String>> = if (curated) {
        ChainKeys.load(chainDir.resolve(ChainKeys.DEFAULT_PATH.fileName)).stores.associate { st ->
            st.slug to st.fixtures.filter { !it.department.isNullOrBlank() }.associate { it.code to it.department!! }
        }
    } else {
        emptyMap()
    }

    log.info(
        "Planogram drive → stores={} grain={} fixtures={} refDate={} auth={} live={}",
        stores,
        if (curated) "curated($curatedLines)" else "full",
        if (fixtures.isEmpty()) "ALL" else fixtures,
        refDate,
        auth,
        live,
    )

    // ── build every directive first; refuse the whole run on a shared ref BEFORE anything is sent ──
    data class Planned(val storeId: String, val siteId: String, val directive: PlanogramDirective)
    val planned = ArrayList<Planned>()
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
        val lines = if (curated) {
            val storeWalls = walls[storeId] ?: error("$storeId is not in the key export — curated grain needs its department walls")
            PlanogramDirectiveDriver.curate(doc, storeWalls, curatedLines)
        } else {
            doc.lines
        }
        val extId = extIdOverride ?: refDate?.let { PlanogramDirectiveDriver.externalRef(storeId, it) } ?: doc.directiveRef
        val directive = driver.toDirective(
            doc,
            siteId,
            externalDirectiveId = extId,
            name = "Twin planogram — $storeId ($extId)",
            effectiveDate = effectiveDate,
            fixtures = fixtures,
            lines = lines,
        )
        if (directive.targets.isEmpty()) {
            log.warn("skip {} — 0 targets after fixture filter {}", storeId, fixtures)
            continue
        }
        planned += Planned(storeId, siteId, directive)
    }
    val dupes = planned.groupBy { it.directive.externalDirectiveId }.filterValues { it.size > 1 }
    check(dupes.isEmpty()) { "external refs are not distinct per store — ${dupes.mapValues { (_, v) -> v.map { it.storeId } }}; nothing sent" }

    var ok = 0
    val client = if (live) ConnectClient(config) else null
    for (p in planned) {
        val d = p.directive
        log.info(
            "{} store={} site={} extId={} targets={} walls={} depts={}",
            if (live) "[LIVE]" else "[DRY]",
            p.storeId,
            p.siteId,
            d.externalDirectiveId,
            d.targets.size,
            d.targets.map { it.rawFixtureCode }.distinct().size,
            walls[p.storeId]?.let { w -> d.targets.mapNotNull { w[it.rawFixtureCode] }.distinct().sorted() } ?: "-",
        )
        if (!live) {
            val json = driver.dryRun(d)
            log.info("      bytes={} first={}", json.toByteArray().size, d.targets.take(3).map { "${it.rawFixtureCode}/${it.rawItemIdentifier}" })
            ok++
            continue
        }
        when (val resp = driver.drive(d, auth)) {
            is ConnectResponse.Ok -> {
                ok++
                log.info("      ack {} body={}", resp.status, resp.rawBody.take(400))
                readBack(client!!, d.externalDirectiveId, p.storeId, readbackWaitMs)
            }

            is ConnectResponse.Err -> {
                err++
                log.error("      REFUSED {} {} — stopping here; no further store is sent", resp.error.status, resp.error.code)
                break
            }
        }
    }
    val mode = if (live) "LIVE" else "DRY-RUN"
    log.info("Planogram drive done — {} ok / {} err {} (planned {})", ok, err, mode, planned.size)
    if (live && err > 0) error("$err planogram directive(s) failed — see logs")
}

/**
 * The webhook ack is a receipt (ingest is async), so the directive id comes from the documented read-back,
 * `POST /compliance/state` keyed on the ref we just sent. A refusal or an empty answer is reported and the
 * run goes on — the ref itself is the handle the Coordinator evaluates by.
 */
private fun readBack(client: ConnectClient, ref: String, storeId: String, waitMs: Long) {
    if (waitMs > 0) Thread.sleep(waitMs)
    when (val r = client.complianceState(ComplianceStateRequest(directiveRef = ref, siteRef = storeId))) {
        is ConnectResponse.Ok -> {
            val st = runCatching { client.mapper.readValue(r.rawBody, ComplianceStateResponse::class.java) }.getOrNull()
            if (st?.directiveId != null) {
                log.info(
                    "      ★ read-back {} → directive_id={} status={} targets={} resolved={} evaluated={}",
                    ref,
                    st.directiveId,
                    st.status,
                    st.totalTargets,
                    st.resolvedTargets,
                    st.evaluatedTargets,
                )
            } else {
                log.warn("      read-back {} → 200 but no directive_id yet: {}", ref, r.rawBody.take(300))
            }
        }

        is ConnectResponse.Err -> log.warn("      read-back {} refused {} {} — the ref is the handle", ref, r.error.status, r.error.code)
    }
}

private fun parseRefDate(s: String): LocalDate =
    if (s.length == 8 && s.all { it.isDigit() }) LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE) else LocalDate.parse(s)

private fun csv(s: String) = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }

private fun csvEnv(key: String) = System.getenv(key)?.let(::csv).orEmpty()

/** `site_ids.csv` → store_code → site UUID (cols: code,site_id,…). */
private fun loadSiteIds(file: Path): Map<String, String> {
    if (!Files.exists(file)) return emptyMap()
    return Files.readAllLines(file).drop(1).mapNotNull { line ->
        val c = line.split(",")
        if (c.size < 2 || c[0].isBlank() || c[1].isBlank()) null else c[0].trim() to c[1].trim()
    }.toMap()
}

private fun env(key: String, default: String) = System.getenv(key)?.takeIf { it.isNotBlank() } ?: default
