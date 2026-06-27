package com.m8trx.twin.connect.model.bearer

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Bearer control-plane DTOs — Connect API doc §7, served by
 * [com.m8trx.twin.connect.model.ConnectMappers.camel].
 *
 * The §7 surface is MIXED-CASING (code-verified): the create / patch-integration bodies are
 * snake_case, while channel-update / transforms / test / health / DLQ are camelCase. The snake
 * fields therefore carry an explicit [JsonProperty] so the single camel mapper serves the plane.
 * Responses are modelled tolerantly (mapper ignores unknown keys) since several are lightly
 * documented; callers also keep the raw body.
 */

// ── POST /api/v2/integrations (snake_case body) ───────────────────────────────

data class CreateIntegrationRequest(
    val name: String,
    @JsonProperty("integration_type") val integrationType: String,
    val description: String? = null,
    val properties: Map<String, Any?>? = null,
    val channels: List<ChannelConfig>,
)

data class ChannelConfig(
    val direction: String,
    @JsonProperty("data_type") val dataType: String,
    val transport: String,
    @JsonProperty("endpoint_config") val endpointConfig: Map<String, Any?>,
    @JsonProperty("schedule_config") val scheduleConfig: Map<String, Any?>? = null,
    @JsonProperty("rate_limit") val rateLimit: Map<String, Any?>? = null,
)

data class CreateIntegrationResponse(val id: String, val channels: List<CreatedChannel> = emptyList())

data class CreatedChannel(val id: String)

// ── PATCH /api/v2/integrations/{id} (snake_case body) ─────────────────────────

data class UpdateIntegrationRequest(
    val name: String? = null,
    val description: String? = null,
    val enabled: Boolean? = null,
    @JsonProperty("notify_user_ids") val notifyUserIds: List<String>? = null,
)

// ── PUT /api/v2/integrations/{id}/channels/{channelId} (camelCase body) ────────

data class UpdateChannelRequest(
    val endpointConfig: Map<String, Any?>? = null,
    val scheduleConfig: Map<String, Any?>? = null,
    val enabled: Boolean? = null,
)

// ── PUT /api/v2/integrations/{id}/transforms (camelCase body) ─────────────────

data class TransformsRequest(
    val direction: String,
    val dataType: String,
    val fieldMappings: Map<String, Any?>,
    val valueLookups: Map<String, Any?>? = null,
    val transformScript: String? = null,
)

// ── POST /api/v2/integrations/{id}/test (camelCase body) ──────────────────────
// NOTE: `payload` is a JSON-ENCODED STRING, not an embedded object (doc §7).

data class TestRequest(val dataType: String, val payload: String)

// ── GET /api/v2/integrations/{id}/health (camelCase response) ─────────────────

data class IntegrationHealth(
    val integrationId: String? = null,
    val healthStatus: String? = null,
    val lastEventAt: String? = null,
    val eventsLastHour: Int? = null,
    val errorsLastHour: Int? = null,
    val errorRate: Double? = null,
    val p99LatencyMs: Double? = null,
    val deadLetterDepth: Int? = null,
)

// ── GET /api/v2/integrations/{id}/dead-letter (camelCase response) ────────────

data class DeadLetterResponse(val integrationId: String? = null, val count: Int = 0, val events: List<DeadLetterEvent> = emptyList())

data class DeadLetterEvent(
    val id: String? = null,
    val channelId: String? = null,
    val direction: String? = null,
    val dataType: String? = null,
    val status: String? = null,
    val attemptCount: Int? = null,
    val errorDetail: String? = null,
    val createdAt: String? = null,
    val processedAt: String? = null,
)

// ── POST /api/v2/integrations/{id}/api-keys (lightly documented; tolerant) ────

data class CreateApiKeyRequest(val name: String? = null, val scopes: List<String>? = null)

data class ApiKeyResponse(
    val id: String? = null,
    val keyId: String? = null,
    val apiKey: String? = null,
    val prefix: String? = null,
    val scopes: List<String>? = null,
)
