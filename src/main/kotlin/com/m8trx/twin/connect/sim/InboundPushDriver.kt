package com.m8trx.twin.connect.sim

import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.ConnectClient
import com.m8trx.twin.connect.WebhookClient
import com.m8trx.twin.connect.WebhookDataType
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.bearer.DeadLetterResponse
import com.m8trx.twin.connect.model.webhook.PricingUpdate
import com.m8trx.twin.connect.model.webhook.ProductCatalogItem
import com.m8trx.twin.connect.model.webhook.SaleEvent
import com.m8trx.twin.connect.model.webhook.ShipmentManifest
import org.slf4j.LoggerFactory

/**
 * Simulator #2 — Inbound push driver (Connect API doc §8).
 *
 * Emulates a POS/ERP POSTing canonical payloads to the `/v1/webhook` receiver, then polling
 * the DLQ for async failures. A 200 ack means RECEIVED, not PROCESSED — failures dead-letter,
 * so [pollDeadLetter] is the end-to-end assertion path.
 */
class InboundPushDriver(private val webhook: WebhookClient, private val client: ConnectClient) {
    private val log = LoggerFactory.getLogger(InboundPushDriver::class.java)

    /** POST a pre-built [SaleEvent] to the webhook receiver. */
    fun pushSale(sale: SaleEvent, auth: WebhookClient.AuthMode = WebhookClient.AuthMode.HMAC): ConnectResponse {
        val resp = webhook.push(WebhookDataType.SALE_EVENT, sale, auth)
        logResponse("pushSale", resp)
        return resp
    }

    /**
     * Build a SKU-attributed [SaleEvent] and push it.
     *
     * The [externalSaleId] encodes [runId] as `"$runId-sale-$seq"`. The §8 payload has no
     * run_id field, so traceability rides the externalSaleId — this lets reset-to-opening-state
     * queries filter by run prefix without needing a schema change.
     */
    fun pushSaleBySku(
        runId: String,
        seq: Int,
        siteId: String,
        occurredAt: String,
        sku: String,
        quantity: Int,
        auth: WebhookClient.AuthMode = WebhookClient.AuthMode.HMAC,
    ): ConnectResponse {
        val sale =
            SaleEvent.bySku(
                externalSaleId = "$runId-sale-$seq",
                siteId = siteId,
                occurredAt = occurredAt,
                sku = sku,
                quantity = quantity,
            )
        return pushSale(sale, auth)
    }

    /** POST a [ProductCatalogItem] to the webhook receiver. */
    fun pushCatalog(item: ProductCatalogItem, auth: WebhookClient.AuthMode = WebhookClient.AuthMode.HMAC): ConnectResponse {
        val resp = webhook.push(WebhookDataType.PRODUCT_CATALOG, item, auth)
        logResponse("pushCatalog", resp)
        return resp
    }

    /** POST a [ShipmentManifest] to the webhook receiver. */
    fun pushShipment(manifest: ShipmentManifest, auth: WebhookClient.AuthMode = WebhookClient.AuthMode.HMAC): ConnectResponse {
        val resp = webhook.push(WebhookDataType.SHIPMENT_MANIFEST, manifest, auth)
        logResponse("pushShipment", resp)
        return resp
    }

    /** POST a [PricingUpdate] to the webhook receiver. */
    fun pushPricing(p: PricingUpdate, auth: WebhookClient.AuthMode = WebhookClient.AuthMode.HMAC): ConnectResponse {
        val resp = webhook.push(WebhookDataType.PRICING_UPDATE, p, auth)
        logResponse("pushPricing", resp)
        return resp
    }

    /**
     * Poll the dead-letter queue for [integrationId]. Returns parsed [DeadLetterResponse] on
     * success, or null on transport / auth error (logged).
     */
    fun pollDeadLetter(integrationId: String): DeadLetterResponse? = when (val resp = client.getDeadLetter(integrationId)) {
        is ConnectResponse.Ok -> {
            val dlq = client.mapper.readValue<DeadLetterResponse>(resp.rawBody)
            log.info("DLQ integrationId={} count={}", integrationId, dlq.count)
            dlq.events.forEach { e ->
                log.warn(
                    "DLQ event id={} dataType={} status={} error={}",
                    e.id,
                    e.dataType,
                    e.status,
                    e.errorDetail,
                )
            }
            dlq
        }

        is ConnectResponse.Err -> {
            log.error(
                "DLQ poll failed integrationId={} status={} code={} message={}",
                integrationId,
                resp.error.status,
                resp.error.code,
                resp.error.message,
            )
            null
        }
    }

    private fun logResponse(op: String, resp: ConnectResponse) {
        when (resp) {
            is ConnectResponse.Ok -> log.info("{} ack status={}", op, resp.status)

            is ConnectResponse.Err ->
                log.error(
                    "{} failed status={} code={} message={}",
                    op,
                    resp.error.status,
                    resp.error.code,
                    resp.error.message,
                )
        }
    }
}
