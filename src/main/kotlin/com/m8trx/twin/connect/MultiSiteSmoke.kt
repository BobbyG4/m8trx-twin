package com.m8trx.twin.connect

import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.webhook.SaleEvent
import com.m8trx.twin.connect.sim.InboundPushDriver
import org.slf4j.LoggerFactory

/**
 * Multi-site behavioral smoke — BACKEND hand-off, S188 Connect multi-site canary (services 3f09462979).
 *
 * Fires the TWO `sale_event`s BACKEND verifies for the multi-site EPC fix, both through the twin's own
 * [WebhookClient] / [InboundPushDriver] on the X-API-Key path:
 *
 *   #1  a NORMAL EPC sale (no site_id, no store_id) → should now PROCESS → item transitions to SOLD.
 *       (It QUARANTINED pre-fix; #1 passing proves the multi-site EPC regression is closed.)
 *   #2  a sale carrying an UNKNOWN external store_id (`SMOKE-9999` / `Smoke Test`) + SKU/qty →
 *       should QUARANTINE (severity=error) + create an unmapped `integration_site_xref` row, which
 *       surfaces in the web cockpit's Site Map → Unmapped queue for an operator to map.
 *
 * A 200 ack means RECEIVED, not PROCESSED — BACKEND asserts the PROCESSED / quarantine halves via psql
 * (+ shows #2 live in the cockpit). This entrypoint just fires both and logs each `external_sale_id`
 * + ack so the two halves can be correlated.
 *
 * Config: [ConnectConfig.fromEnv] (`M8TRX_CONNECT_WEBHOOK_BASE`, `M8TRX_TENANT_ID`,
 * `M8TRX_CONNECT_INTEGRATION_SLUG`, `M8TRX_TWIN_WEBHOOK_KEY`). Smoke inputs:
 *   - `M8TRX_SMOKE_EPC`        (case #1, REQUIRED — a real in-stock EPC; BACKEND verifies item→sold)
 *   - `M8TRX_SMOKE_SKU`        (case #2, default a real Decathlon item_cd so quarantine isolates to the store)
 *   - `M8TRX_SMOKE_STORE_ID` / `M8TRX_SMOKE_STORE_NAME` (case #2 overrides; default SMOKE-9999 / Smoke Test)
 *   - `M8TRX_RUN_ID`          (external_sale_id prefix; default `twin-multisite`)
 *
 * Run: `./gradlew connectMultiSiteSmoke`.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.MultiSiteSmoke")

fun main() {
    val config = ConnectConfig.fromEnv()
    val driver = InboundPushDriver(WebhookClient(config), ConnectClient(config))
    val runId = env("M8TRX_RUN_ID", "twin-multisite")
    val occurredAt = "2026-06-29T18:00:00Z"

    log.info("Multi-site smoke → base={} tenant={} slug={}", config.webhookBase, config.tenantId, config.integrationSlug)

    // ── Case #1 — normal EPC sale → expect PROCESS → item SOLD (proves the multi-site EPC fix) ──
    val epc = System.getenv("M8TRX_SMOKE_EPC")?.takeIf { it.isNotBlank() }
        ?: error("M8TRX_SMOKE_EPC is required for case #1 — a real in-stock EPC (BACKEND verifies it transitions to SOLD)")
    val case1Id = "$runId-epc-001"
    log.info("── Case #1: normal EPC sale id={} epc={} (expect PROCESS → SOLD) ──", case1Id, epc)
    report("case#1 EPC", driver.pushSale(SaleEvent.byEpc(case1Id, occurredAt, epc), WebhookClient.AuthMode.API_KEY))

    // ── Case #2 — unknown external store_id → expect QUARANTINE + unmapped integration_site_xref ──
    val storeId = env("M8TRX_SMOKE_STORE_ID", "SMOKE-9999")
    val storeName = env("M8TRX_SMOKE_STORE_NAME", "Smoke Test")
    val sku = env("M8TRX_SMOKE_SKU", "2456185")
    val case2Id = "$runId-store-001"
    log.info("── Case #2: store_id={} name='{}' sku={} qty=1 id={} (expect QUARANTINE + unmapped xref) ──", storeId, storeName, sku, case2Id)
    report("case#2 store_id", driver.pushSale(SaleEvent.byStore(case2Id, occurredAt, storeId, storeName, sku, 1), WebhookClient.AuthMode.API_KEY))

    log.info("Both fired — external_sale_ids: case#1={} case#2={}. BACKEND verifies PROCESSED / quarantine.", case1Id, case2Id)
}

private fun report(label: String, resp: ConnectResponse) {
    when (resp) {
        is ConnectResponse.Ok -> log.info("[OK] {} → ack {} {}", label, resp.status, resp.rawBody)
        is ConnectResponse.Err -> log.error("[ERR] {} → {} {} {}", label, resp.error.status, resp.error.code, resp.error.rawBody)
    }
}

private fun env(key: String, default: String) = System.getenv(key)?.takeIf { it.isNotBlank() } ?: default
