package com.m8trx.twin

data class TwinConfig(
    val natsUrl: String,
    val restBaseUrl: String,
    val serviceBearer: String,
    val tenantId: String,
    val siteId: String,
    val spaceId: String,
) {
    val spaceIdNoHyphens: String = spaceId.replace("-", "")

    companion object {
        fun fromEnv(): TwinConfig = TwinConfig(
            natsUrl = env("M8TRX_NATS_URL", "nats://192.168.55.29:4222"),
            restBaseUrl = env("M8TRX_REST_URL", "http://192.168.55.28:9999"),
            serviceBearer = requireEnv("M8TRX_TWIN_SERVICE_BEARER"),
            tenantId = requireEnv("M8TRX_TENANT_ID"),
            siteId = requireEnv("M8TRX_SITE_ID"),
            spaceId = requireEnv("M8TRX_SPACE_ID"),
        )

        private fun env(key: String, default: String) = System.getenv(key) ?: default
        private fun requireEnv(key: String) = System.getenv(key)
            ?: error("Required env var $key is not set")
    }
}
