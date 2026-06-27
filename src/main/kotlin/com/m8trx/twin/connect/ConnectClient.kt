package com.m8trx.twin.connect

import com.fasterxml.jackson.databind.ObjectMapper
import com.m8trx.twin.connect.http.ConnectHttp
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.bearer.CreateApiKeyRequest
import com.m8trx.twin.connect.model.bearer.CreateIntegrationRequest
import com.m8trx.twin.connect.model.bearer.ItemReceiveRequest
import com.m8trx.twin.connect.model.bearer.ScanBatch
import com.m8trx.twin.connect.model.bearer.TestRequest
import com.m8trx.twin.connect.model.bearer.TransformsRequest
import com.m8trx.twin.connect.model.bearer.UpdateChannelRequest
import com.m8trx.twin.connect.model.bearer.UpdateIntegrationRequest

/**
 * Bearer-plane client — Connect API doc §6 (data) + §7 (control). One gateway: every call carries
 * `Authorization: Bearer <key>`, serializes the body ONCE with the camel mapper (the exact bytes
 * sent), and returns the raw [ConnectResponse] so callers can treat non-2xx as DATA (DLQ-driven
 * flows) or `.bodyOrThrow()` for fatal setup steps. Response parsing is left to callers via
 * [mapper] (the shapes vary in maturity).
 *
 * `requireBearer()` only bites on an actual live call — the offline dry-run paths in the setup
 * simulators render request bodies straight off [mapper] without touching this client.
 */
class ConnectClient(
    private val config: ConnectConfig,
    private val http: ConnectHttp = ConnectHttp(),
    val mapper: ObjectMapper = ConnectMappers.camel,
) {
    private val base = config.apiBase

    // ── Data plane (§6) ───────────────────────────────────────────────────────
    fun submitScans(batch: ScanBatch): ConnectResponse = send("POST", "/scans", batch)

    fun receiveItems(req: ItemReceiveRequest): ConnectResponse = send("POST", "/inventory/items/receive", req)

    // ── Control plane (§7) ────────────────────────────────────────────────────
    fun createIntegration(req: CreateIntegrationRequest): ConnectResponse = send("POST", "/integrations", req)

    fun updateIntegration(id: String, req: UpdateIntegrationRequest): ConnectResponse = send("PATCH", "/integrations/$id", req)

    fun updateChannel(id: String, channelId: String, req: UpdateChannelRequest): ConnectResponse =
        send("PUT", "/integrations/$id/channels/$channelId", req)

    fun putTransforms(id: String, req: TransformsRequest): ConnectResponse = send("PUT", "/integrations/$id/transforms", req)

    fun testIntegration(id: String, req: TestRequest): ConnectResponse = send("POST", "/integrations/$id/test", req)

    fun getHealth(id: String): ConnectResponse = send("GET", "/integrations/$id/health", null)

    fun getDeadLetter(id: String): ConnectResponse = send("GET", "/integrations/$id/dead-letter", null)

    fun listConnectors(): ConnectResponse = send("GET", "/integrations/connectors", null)

    // ── API keys (§7) ─────────────────────────────────────────────────────────
    fun createApiKey(integrationId: String, req: CreateApiKeyRequest): ConnectResponse = send("POST", "/integrations/$integrationId/api-keys", req)

    fun rotateApiKey(integrationId: String, keyId: String): ConnectResponse =
        send("POST", "/integrations/$integrationId/api-keys/$keyId/rotate", null)

    fun revokeApiKey(integrationId: String, keyId: String): ConnectResponse = send("DELETE", "/integrations/$integrationId/api-keys/$keyId", null)

    fun listApiKeys(integrationId: String): ConnectResponse = send("GET", "/integrations/$integrationId/api-keys", null)

    private fun send(method: String, path: String, body: Any?): ConnectResponse {
        val bytes = body?.let { mapper.writeValueAsBytes(it) }
        val headers =
            buildMap {
                put("Authorization", "Bearer ${config.requireBearer()}")
                if (bytes != null) put("Content-Type", "application/json")
            }
        return http.send(method, base + path, headers, bytes)
    }
}
