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
        /**
         * ⚠ `natsUrl` has NO default on purpose. Two edges now run on .29 and the old default pointed at
         * the WRONG one: `:4222` is `edge-itx-office` (production office edge, real Xovis hardware —
         * synthetic traffic there contaminates results only real hardware can produce). The twin edge is
         * `:4223` / `edge-twin-denver`. Publishers must state which edge they mean; see
         * `PeopleDrive`, which additionally asserts the NATS `server_name` before emitting.
         */
        fun fromEnv(): TwinConfig = TwinConfig(
            natsUrl = requireEnv("M8TRX_NATS_URL"),
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
