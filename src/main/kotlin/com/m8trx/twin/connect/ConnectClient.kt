package com.m8trx.twin.connect

import com.fasterxml.jackson.databind.ObjectMapper
import com.m8trx.twin.connect.http.ConnectHttp
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.bearer.AlertQueryRequest
import com.m8trx.twin.connect.model.bearer.ComplianceStateRequest
import com.m8trx.twin.connect.model.bearer.CreateApiKeyRequest
import com.m8trx.twin.connect.model.bearer.CreateIntegrationRequest
import com.m8trx.twin.connect.model.bearer.ImpressionQueryRequest
import com.m8trx.twin.connect.model.bearer.ItemDetailsRequest
import com.m8trx.twin.connect.model.bearer.ItemReceiveRequest
import com.m8trx.twin.connect.model.bearer.ReadCaps
import com.m8trx.twin.connect.model.bearer.ScanBatch
import com.m8trx.twin.connect.model.bearer.SpatialIdentityRequest
import com.m8trx.twin.connect.model.bearer.TaskQueryRequest
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

    fun itemDetails(req: ItemDetailsRequest): ConnectResponse = send("POST", "/inventory/items/details", req)

    // ── READ half (§6.5, live 2026-07-30) ─────────────────────────────────────
    //
    // Four reads, one shape: resolve by the ref YOU supplied, site-confined, bounded, truncation
    // announced, one capability per plane. Each exists because an integrator otherwise had to ask a
    // human — for UUIDs by email, for someone to run psql, or for a backend engineer to watch a
    // screen while the integrator drove the input. Twin was that integrator in all three cases.
    //
    // ⚠ Reachable ≠ callable. `@ConnectExposed` opens the endpoint; the KEY still needs the
    // capability, and pre-SEC-3 keys hold no `vision_ai:*` and no `task:read`. An ingest-only key
    // 403s the people read even though it may POST impressions — `view` ≠ `ingest`. Grant via
    // `PATCH /api/v2/connect/service-keys/{keyId}/scopes` (§7). `connectReadProbe` reports which of
    // the four this key actually holds.

    /**
     * `POST /spatial/identity` — site → space → zone map (`inventory:read`).
     * **Call this first:** `items/receive` requires a `spaceId` that nothing else on the Connect
     * surface emits, which is what made self-serve onboarding impossible before §6.5.
     */
    fun spatialIdentity(req: SpatialIdentityRequest): ConnectResponse {
        require(req.limit == null || req.limit in 1..ReadCaps.ZONES_MAX) {
            "limit must be 1..${ReadCaps.ZONES_MAX} (asked for ${req.limit}) — the server refuses with 400, it does not clamp"
        }
        return send("POST", "/spatial/identity", req)
    }

    /**
     * `POST /visionai/impressions/query` — read back twin's own people-plane traffic
     * (**`vision_ai:view`**, never `inventory:*` — §4 SEC-3).
     */
    fun queryImpressions(req: ImpressionQueryRequest): ConnectResponse {
        require(req.limit == null || req.limit in 1..ReadCaps.ROWS_MAX) {
            "limit must be 1..${ReadCaps.ROWS_MAX} (asked for ${req.limit}) — the server refuses with 400, it does not clamp"
        }
        return send("POST", "/visionai/impressions/query", req)
    }

    /**
     * `POST /tasks/query` — the tasks YOUR directive generated (`task:read`).
     * An unknown `directive_ref` is a 404 `DIRECTIVE_NOT_FOUND`, NOT an empty 200.
     */
    fun queryTasks(req: TaskQueryRequest): ConnectResponse {
        require(req.limit == null || req.limit in 1..ReadCaps.ROWS_MAX) {
            "limit must be 1..${ReadCaps.ROWS_MAX} (asked for ${req.limit}) — the server refuses with 400, it does not clamp"
        }
        return send("POST", "/tasks/query", req)
    }

    /** `POST /compliance/state` — directive + per-fixture compliance (`inventory:read`; TWIN-REQ-003). */
    fun complianceState(req: ComplianceStateRequest): ConnectResponse = send("POST", "/compliance/state", req)

    /**
     * `POST /alerts/query` — read back the alarms you raised (**`alert:read`**, §8.2).
     *
     * ⚠ **No api_key held `alert:read` when this was written** (verified across all 11 keys, and
     * re-verified live by [com.m8trx.twin.connect.sim.AlarmDriver] on every run), so this returns
     * `403 PERMISSION_DENIED` until an administrator grants it. `PERMISSION_DENIED` ≠
     * `CONNECT_NOT_EXPOSED`: the endpoint is reachable and the gate is the key's scope set.
     */
    fun queryAlerts(req: AlertQueryRequest): ConnectResponse {
        require(req.limit == null || req.limit in 1..ReadCaps.ROWS_MAX) {
            "limit must be 1..${ReadCaps.ROWS_MAX} (asked for ${req.limit}) — the server refuses with 400, it does not clamp"
        }
        return send("POST", "/alerts/query", req)
    }

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
