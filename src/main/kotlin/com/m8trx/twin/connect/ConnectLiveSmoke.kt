package com.m8trx.twin.connect

import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.bearer.DeadLetterResponse
import com.m8trx.twin.connect.model.webhook.SaleEvent
import com.m8trx.twin.connect.sim.InboundPushDriver
import org.slf4j.LoggerFactory

/**
 * Live smoke — fires ONE `sale_event` at the configured Connect webhook through the twin's own
 * [WebhookClient] / [InboundPushDriver], and logs the ack. The first live exercise of the harness.
 *
 * Config comes from env (`ConnectConfig.fromEnv`): `M8TRX_CONNECT_WEBHOOK_BASE`, `M8TRX_TENANT_ID`,
 * `M8TRX_CONNECT_INTEGRATION_SLUG`, and the inbound auth (`M8TRX_TWIN_WEBHOOK_KEY` for the X-API-Key
 * path). Optional resolution inputs: `M8TRX_SMOKE_SITE_ID`, `M8TRX_SMOKE_SKU`, `M8TRX_SMOKE_EPC`,
 * `M8TRX_RUN_ID`. If `M8TRX_TWIN_SERVICE_BEARER` is set, also polls the DLQ for the integration.
 *
 * A 200 ack means RECEIVED, not processed — to actually transition an item to SOLD, supply a real
 * `M8TRX_SMOKE_SITE_ID` + (`M8TRX_SMOKE_EPC` or a real `M8TRX_SMOKE_SKU`).
 *
 * Run: `./gradlew connectLiveSmoke`.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ConnectLiveSmoke")

fun main() {
    val config = ConnectConfig.fromEnv()
    log.info("Connect live smoke → base={} tenant={} slug={}", config.webhookBase, config.tenantId, config.integrationSlug)

    val webhook = WebhookClient(config)
    val client = ConnectClient(config)
    val driver = InboundPushDriver(webhook, client)

    val runId = env("M8TRX_RUN_ID", "twin-live")
    val siteId = env("M8TRX_SMOKE_SITE_ID", "00000000-0000-0000-0000-000000000000")
    val occurredAt = "2026-06-27T18:00:00Z"
    val epc = System.getenv("M8TRX_SMOKE_EPC")?.takeIf { it.isNotBlank() }

    val sale = if (epc != null) {
        SaleEvent.byEpc("$runId-sale-001", occurredAt, epc)
    } else {
        SaleEvent.bySku("$runId-sale-001", occurredAt, siteId, env("M8TRX_SMOKE_SKU", "ABC123"), 1)
    }

    when (val resp = driver.pushSale(sale, WebhookClient.AuthMode.API_KEY)) {
        is ConnectResponse.Ok -> {
            log.info("[OK] webhook ack {} → {}", resp.status, resp.rawBody)
            maybePollDlq(client, resp.rawBody)
        }

        is ConnectResponse.Err ->
            log.error("[ERR] webhook {} {} → {}", resp.error.status, resp.error.code, resp.error.rawBody)
    }
}

/** Best-effort DLQ check — only if a Bearer key (api/v2 plane, `integration:manage`) is configured. */
private fun maybePollDlq(client: ConnectClient, ackBody: String) {
    if (System.getenv("M8TRX_TWIN_SERVICE_BEARER").isNullOrBlank()) {
        log.info("(no M8TRX_TWIN_SERVICE_BEARER set — skipping DLQ poll; a 200 ack is 'received', not yet processed)")
        return
    }
    val integrationId = client.mapper.readValue<Map<String, Any?>>(ackBody)["integrationId"] as? String ?: return
    when (val resp = client.getDeadLetter(integrationId)) {
        is ConnectResponse.Ok -> {
            val dlq = client.mapper.readValue<DeadLetterResponse>(resp.rawBody)
            log.info("DLQ integrationId={} count={}", integrationId, dlq.count)
            dlq.events.forEach { log.warn("DLQ dataType={} status={} error={}", it.dataType, it.status, it.errorDetail) }
        }

        is ConnectResponse.Err ->
            log.warn("DLQ poll unavailable with this key (HTTP {} {}); needs a Bearer with integration:manage", resp.error.status, resp.error.code)
    }
}

private fun env(key: String, default: String) = System.getenv(key)?.takeIf { it.isNotBlank() } ?: default
