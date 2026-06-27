package com.m8trx.twin.connect.setup

import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.ConnectClient
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.bearer.ChannelConfig
import com.m8trx.twin.connect.model.bearer.CreateIntegrationRequest
import com.m8trx.twin.connect.model.bearer.CreateIntegrationResponse
import com.m8trx.twin.connect.model.bearer.TestRequest
import com.m8trx.twin.connect.model.bearer.TransformsRequest
import com.m8trx.twin.connect.model.bearer.UpdateChannelRequest
import org.slf4j.LoggerFactory

/**
 * Simulator #5a — Connection/channel provisioner (Connect API doc §7).
 *
 * Emulates a tenant IT admin self-configuring an integration and its channels via REST:
 * inbound webhook, outbound webhook, and inbound SFTP file-drop.
 *
 * **Slug discipline (MVP):** the inbound webhook slug equals `integration.name` (Connect doc §8;
 * the P1 `webhook_slug` column will introduce an independent stable slug field). For now the name
 * sent in [CreateIntegrationRequest] IS the slug — [ProvisionedIntegration.slug] surfaces that
 * name and is the only place slug identity is expressed. No re-derivation elsewhere.
 *
 * Non-2xx responses are treated as DATA, not exceptions — logged with status/code/message and
 * returned as `null` from the provisioning helpers.
 */
class Provisioner(private val client: ConnectClient) {
    private val log = LoggerFactory.getLogger(Provisioner::class.java)

    // ── Public provisioning helpers ───────────────────────────────────────────

    /**
     * Creates an inbound webhook integration with one channel per [dataTypes] entry.
     * [integrationType] is set to `dataTypes.first()` (doubles as the default data_type).
     * Optional [hmacSecret] lands in each channel's `endpoint_config.hmac_secret` (§8).
     */
    fun createInboundWebhook(
        name: String,
        dataTypes: List<String>,
        hmacSecret: String? = null,
        description: String? = null,
    ): ProvisionedIntegration? = issue(client.createIntegration(buildInboundWebhookRequest(name, dataTypes, hmacSecret, description)), name)

    /**
     * Creates an outbound webhook integration: one channel pushing `stocktake_result` to [url].
     * Optional [hmacSecret] signs outbound payloads (§9 `X-M8TRX-Signature`).
     */
    fun createOutboundWebhook(name: String, url: String, hmacSecret: String? = null, timeoutMs: Int = 5000): ProvisionedIntegration? {
        val endpointConfig =
            buildMap<String, Any?> {
                put("url", url)
                if (hmacSecret != null) put("hmac_secret", hmacSecret)
                put("timeout_ms", timeoutMs)
            }
        val req =
            CreateIntegrationRequest(
                name = name,
                integrationType = "stocktake_result",
                channels =
                listOf(
                    ChannelConfig(
                        direction = "outbound",
                        dataType = "stocktake_result",
                        transport = "generic_rest_webhook",
                        endpointConfig = endpointConfig,
                    ),
                ),
            )
        return issue(client.createIntegration(req), name)
    }

    /**
     * Creates an inbound SFTP file-drop integration. M8TRX polls [directory] for files matching
     * [filePattern] and ingests each row as a [dataType] canonical event (§7 SFTP section).
     */
    fun createInboundSftp(name: String, dataType: String, directory: String, filePattern: String = "*.csv"): ProvisionedIntegration? {
        val req =
            CreateIntegrationRequest(
                name = name,
                integrationType = dataType,
                channels =
                listOf(
                    ChannelConfig(
                        direction = "inbound",
                        dataType = dataType,
                        transport = "sftp_file_drop",
                        endpointConfig = mapOf("directory" to directory, "file_pattern" to filePattern),
                    ),
                ),
            )
        return issue(client.createIntegration(req), name)
    }

    // ── Passthroughs ──────────────────────────────────────────────────────────

    fun updateChannel(id: String, channelId: String, req: UpdateChannelRequest): ConnectResponse = client.updateChannel(id, channelId, req)

    fun putTransforms(id: String, req: TransformsRequest): ConnectResponse = client.putTransforms(id, req)

    fun test(id: String, req: TestRequest): ConnectResponse = client.testIntegration(id, req)

    fun health(id: String): ConnectResponse = client.getHealth(id)

    fun deadLetter(id: String): ConnectResponse = client.getDeadLetter(id)

    // ── Dry-run ───────────────────────────────────────────────────────────────

    /**
     * Builds the same [CreateIntegrationRequest] as [createInboundWebhook] and serialises it to
     * JSON — OFFLINE, no HTTP, no API key required. Useful for auditing request shape in tests.
     */
    fun dryRunInboundWebhook(name: String, dataTypes: List<String>, hmacSecret: String? = null): String =
        client.mapper.writeValueAsString(buildInboundWebhookRequest(name, dataTypes, hmacSecret))

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildInboundWebhookRequest(
        name: String,
        dataTypes: List<String>,
        hmacSecret: String? = null,
        description: String? = null,
    ): CreateIntegrationRequest = CreateIntegrationRequest(
        name = name,
        integrationType = dataTypes.first(),
        description = description,
        channels =
        dataTypes.map { dt ->
            ChannelConfig(
                direction = "inbound",
                dataType = dt,
                transport = "generic_rest_webhook",
                endpointConfig = hmacSecret?.let { mapOf<String, Any?>("hmac_secret" to it) } ?: emptyMap(),
            )
        },
    )

    private fun issue(resp: ConnectResponse, name: String): ProvisionedIntegration? = when (resp) {
        is ConnectResponse.Ok -> {
            val body = client.mapper.readValue<CreateIntegrationResponse>(resp.rawBody)
            ProvisionedIntegration(
                id = body.id,
                slug = name,
                channelIds = body.channels.map { it.id },
            )
        }
        is ConnectResponse.Err -> {
            val e = resp.error
            log.error("Provisioner: create failed — HTTP {} code={} message={}", e.status, e.code, e.message)
            null
        }
    }
}

/** Result of a successful [Provisioner] create call. [slug] == [name] sent to the API (MVP). */
data class ProvisionedIntegration(val id: String, val slug: String, val channelIds: List<String>)
