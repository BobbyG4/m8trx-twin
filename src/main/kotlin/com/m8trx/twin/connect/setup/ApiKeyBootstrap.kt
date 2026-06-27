package com.m8trx.twin.connect.setup

import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.ConnectClient
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.bearer.ApiKeyResponse
import com.m8trx.twin.connect.model.bearer.CreateApiKeyRequest
import org.slf4j.LoggerFactory

/**
 * Simulator #1 — API-key bootstrap (Connect API doc §7 api-keys).
 *
 * Emulates a tenant admin minting the `m8trx_<hex>` Bearer token that every
 * other simulator uses to authenticate against the Connect data plane.
 *
 * Server-side this endpoint is gated by the `integration:manage` capability
 * and the `m8trx_connect` entitlement — nothing to enforce here, just note it
 * so the caller knows which user/service-principal must hold the calling Bearer.
 */
class ApiKeyBootstrap(private val client: ConnectClient) {
    private val log = LoggerFactory.getLogger(ApiKeyBootstrap::class.java)

    /**
     * Mint a new API key for [integrationId].
     *
     * The plaintext key is shown ONCE in the response. On success it is logged to
     * stdout with a loud warning — store it in env `M8TRX_TWIN_SERVICE_BEARER`
     * immediately and NEVER commit it anywhere.
     *
     * @return the plaintext `apiKey` string, or null on error or absent key.
     */
    fun mint(integrationId: String, name: String? = null, scopes: List<String>? = null): String? {
        val resp = client.createApiKey(integrationId, CreateApiKeyRequest(name, scopes))
        return when (resp) {
            is ConnectResponse.Ok -> {
                val parsed = client.mapper.readValue<ApiKeyResponse>(resp.rawBody)
                val key = parsed.apiKey
                if (key != null) {
                    log.info("=================================================================")
                    log.info("  !!  API KEY MINTED — PLAINTEXT SHOWN ONCE — DO NOT COMMIT  !!")
                    log.info("  KEY : {}", key)
                    log.info("  Store in env: M8TRX_TWIN_SERVICE_BEARER")
                    log.info("=================================================================")
                } else {
                    log.warn("mint: 2xx response but apiKey field absent (id={}, keyId={})", parsed.id, parsed.keyId)
                }
                key
            }
            is ConnectResponse.Err -> {
                val e = resp.error
                log.error("mint failed: status={} code={} message={}", e.status, e.code, e.message)
                null
            }
        }
    }

    /** Rotate an existing key by [keyId] under [integrationId]. */
    fun rotate(integrationId: String, keyId: String): ConnectResponse = client.rotateApiKey(integrationId, keyId)

    /** Revoke an existing key by [keyId] under [integrationId]. */
    fun revoke(integrationId: String, keyId: String): ConnectResponse = client.revokeApiKey(integrationId, keyId)

    /** List all API keys for [integrationId]. */
    fun list(integrationId: String): ConnectResponse = client.listApiKeys(integrationId)

    /**
     * Preview the JSON body that [mint] would send — offline, no HTTP call, no key required.
     * Useful for confirming scope lists before a live run.
     */
    fun dryRun(name: String? = null, scopes: List<String>? = null): String = client.mapper.writeValueAsString(CreateApiKeyRequest(name, scopes))
}
