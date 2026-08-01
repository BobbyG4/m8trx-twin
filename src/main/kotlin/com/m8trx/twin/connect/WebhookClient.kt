package com.m8trx.twin.connect

import com.fasterxml.jackson.databind.ObjectMapper
import com.m8trx.twin.connect.http.ConnectHttp
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers

/** The X-Data-Type routing header values for the §8 inbound ingesters. */
object WebhookDataType {
    const val SALE_EVENT = "sale_event"
    const val PRODUCT_CATALOG = "product_catalog"
    const val SHIPMENT_MANIFEST = "shipment_manifest"
    const val PRICING_UPDATE = "pricing_update"

    /**
     * Mode-3 planogram directive routing header (AS-BUILT ingest, services #64). Routes the flat directive
     * envelope (`name` + `external_directive_id` + `targets[]`) to the planogram landing
     * (`compliance_directive` + `compliance_target`s) — rides the existing twin-pos webhook auth, no
     * dedicated channel needed to fire.
     */
    const val PLANOGRAM_DIRECTIVE = "planogram_directive"

    /**
     * Inventory-movement routing header — AS-PROPOSED (services S178, Backend's directional contract for
     * the remediation-demo ingester; not yet confirmed deployed). Routes the relocation envelope
     * (`to_fixture_code`/`to_zone_id` + `items[]`) to update `thing_location.zone_id` for each item (stays
     * `in_stock`) — rides the existing twin-pos webhook auth, no dedicated channel needed to fire.
     */
    const val INVENTORY_MOVEMENT = "inventory_movement"

    /**
     * Third-party alarm ingest — the **seventh** inbound data-type (Connect §8.1, published
     * 2026-07-31; `IntegrationIngesters.kt:55` routes `"alarm" -> ingestAlarm(...)`). Twin drives it
     * as an outside EAS gate vendor: `source` must be a registered `alert_source`, the payload is the
     * §8.1 envelope, and it rides the existing `twin-pos` webhook auth — a vendor cannot create its
     * own integration (`POST /integrations` needs `integration:manage`, which twin's key does not hold).
     */
    const val ALARM = "alarm"
}

/**
 * Inbound webhook-plane client — Connect API doc §8 (the separate `/v1/webhook` surface, NOT
 * `/api/v2`). POSTs snake_case canonical payloads to `/{tenantId}/{integrationKey}` with an
 * `X-Data-Type` routing header, authenticated by EITHER an `X-API-Key` OR an HMAC
 * `X-M8TRX-Signature` — a mode switch ([AuthMode]), never both (so a run deliberately exercises
 * each path; the integration-health dashboard renders them differently).
 *
 * The body is serialized to a [ByteArray] ONCE; that exact array is both signed and sent.
 *
 * A 200 ack means *received*, not *processed* — async failures dead-letter, so an end-to-end
 * assertion polls the DLQ (see InboundPushDriver).
 */
class WebhookClient(
    private val config: ConnectConfig,
    private val http: ConnectHttp = ConnectHttp(),
    private val mapper: ObjectMapper = ConnectMappers.snake,
) {
    enum class AuthMode { API_KEY, HMAC }

    /** POST a canonical payload [body] under data-type [dataType] (the `X-Data-Type` header). */
    fun push(dataType: String, body: Any, auth: AuthMode): ConnectResponse {
        val bytes = mapper.writeValueAsBytes(body)
        val url = "${config.webhookBase}/${config.requireTenantId()}/${config.requireIntegrationSlug()}"
        val headers =
            buildMap {
                put("Content-Type", "application/json")
                put("X-Data-Type", dataType)
                when (auth) {
                    AuthMode.API_KEY -> put("X-API-Key", config.requireWebhookApiKey())
                    AuthMode.HMAC ->
                        put(
                            "X-M8TRX-Signature",
                            Hmac.signatureHeader(config.requireInboundHmacSecret(), bytes),
                        )
                }
            }
        return http.send("POST", url, headers, bytes)
    }
}
