package com.m8trx.twin.connect

/**
 * Configuration for the M8TRX Connect simulator harness (CORE-REQ-003).
 *
 * Two API surfaces — do NOT conflate (Connect API doc §2):
 *   - Bearer plane  (data + control): [apiBase], `Authorization: Bearer m8trx_<hex>`, camelCase.
 *   - Webhook plane (inbound ingest): [webhookBase], `X-API-Key` OR HMAC, snake_case.
 *
 * Live-only fields are nullable: the offline self-tests in [ConnectHarness] run with ZERO core
 * dependency, so a build + self-test does not require a provisioned key. The `require*()` accessors
 * fail loudly with provisioning guidance when a live call needs a value that is not set.
 *
 * Secrets come from env / .env (gitignored) — never committed. See `.env.example` for the var names.
 *
 * Note the TWO distinct HMAC secrets (modelling them as one would pass the loopback self-test and
 * then fail against real core):
 *   - [inboundHmacSecret]     signs OUR inbound webhook pushes (= the inbound channel's hmac_secret).
 *   - [outboundVerifySecret]  verifies M8TRX's signature on the stocktake_result it pushes to us.
 */
data class ConnectConfig(
    val apiBase: String,
    val webhookBase: String,
    val tenantId: String?,
    val integrationSlug: String?,
    val serviceBearer: String?,
    val webhookApiKey: String?,
    val inboundHmacSecret: String?,
    val outboundVerifySecret: String?,
) {
    fun requireBearer(): String = serviceBearer
        ?: error("M8TRX_TWIN_BEARER is not set — the minted m8trx_<hex> service Bearer, provisioned out-of-band")

    fun requireTenantId(): String = tenantId
        ?: error("M8TRX_TENANT_ID is not set")

    fun requireIntegrationSlug(): String = integrationSlug
        ?: error("M8TRX_CONNECT_INTEGRATION_SLUG is not set — the {integrationKey} path segment (= integration.name for MVP)")

    fun requireWebhookApiKey(): String = webhookApiKey
        ?: error("M8TRX_TWIN_WEBHOOK_KEY is not set — the per-integration X-API-Key for the webhook plane")

    fun requireInboundHmacSecret(): String = inboundHmacSecret
        ?: error("M8TRX_CONNECT_INBOUND_HMAC_SECRET is not set — the inbound channel's endpoint_config.hmac_secret")

    fun requireOutboundVerifySecret(): String = outboundVerifySecret
        ?: error("M8TRX_CONNECT_OUTBOUND_VERIFY_SECRET is not set — verifies M8TRX's outbound X-M8TRX-Signature")

    companion object {
        fun fromEnv(): ConnectConfig = ConnectConfig(
            apiBase = env("M8TRX_CONNECT_API_BASE", "https://dev.m8trx.com/server/api/v2"),
            webhookBase = env("M8TRX_CONNECT_WEBHOOK_BASE", "https://dev.m8trx.com/server/v1/webhook"),
            tenantId = optEnv("M8TRX_TENANT_ID"),
            integrationSlug = optEnv("M8TRX_CONNECT_INTEGRATION_SLUG"),
            serviceBearer = optEnv("M8TRX_TWIN_BEARER") ?: optEnv("M8TRX_TWIN_SERVICE_BEARER"),
            webhookApiKey = optEnv("M8TRX_TWIN_WEBHOOK_KEY"),
            inboundHmacSecret = optEnv("M8TRX_CONNECT_INBOUND_HMAC_SECRET"),
            outboundVerifySecret = optEnv("M8TRX_CONNECT_OUTBOUND_VERIFY_SECRET"),
        )

        private fun env(key: String, default: String) = System.getenv(key) ?: default

        private fun optEnv(key: String) = System.getenv(key)?.takeIf { it.isNotBlank() }
    }
}
