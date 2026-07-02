package com.m8trx.twin.connect

import com.fasterxml.jackson.databind.JsonNode
import com.m8trx.twin.connect.http.ConnectHttp
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * A logged-in user session: the JWT + the scope signals decoded from its Hasura claims. This is the
 * **user-JWT plane** the site-scope audit probes with — distinct from the service Bearer (`ConnectClient`).
 * Public surface only (`POST {apiBase}/auth/login`), no psql.
 */
data class UserSession(
    val email: String,
    val accessToken: String,
    val tenantId: String?,
    val role: String?,
    val capabilityCount: Int,
    val siteClaimPresent: Boolean,
    val allowedSiteIds: List<String>,
)

/**
 * Logs a demo user in (email + password → JWT) and decodes the token's `https://hasura.io/jwt/claims`.
 * The presence/absence of a **site claim** is the token-plane confinement signal: a site-scoped user
 * whose JWT carries only `x-hasura-tenant-id` (no site claim) is un-confined at the token layer.
 */
class UserAuthClient(private val config: ConnectConfig, private val http: ConnectHttp = ConnectHttp()) {
    private val log = LoggerFactory.getLogger(UserAuthClient::class.java)
    private val mapper = ConnectMappers.camel

    /** Returns the session on success, or null on auth failure / non-2xx (logged). */
    fun login(email: String, password: String): UserSession? {
        val url = "${config.apiBase}/auth/login"
        val body = mapper.writeValueAsBytes(mapOf("email" to email, "password" to password))
        return when (val resp = http.send("POST", url, mapOf("Content-Type" to "application/json"), body)) {
            is ConnectResponse.Ok -> {
                val node = mapper.readTree(resp.rawBody)
                val token = node.path("accessToken").asText("").ifBlank { null }
                if (token == null) {
                    log.warn("login {} — 2xx but no accessToken (status={})", email, node.path("status").asText())
                    null
                } else {
                    decode(email, token, node)
                }
            }
            is ConnectResponse.Err -> {
                log.warn("login {} FAILED — status={} code={}", email, resp.error.status, resp.error.code)
                null
            }
        }
    }

    private fun decode(email: String, token: String, authNode: JsonNode): UserSession {
        val claims = runCatching {
            val payload = token.split(".")[1]
            mapper.readTree(String(Base64.getUrlDecoder().decode(payload.padBase64())))
        }.getOrNull()
        val hasura = claims?.path("https://hasura.io/jwt/claims")
        val allowedSites = hasura?.path("x-hasura-allowed-site-ids")
            ?.takeIf { it.isArray }
            ?.map { it.asText() }
            .orEmpty()
        // any Hasura claim key mentioning "site" = a site-scoping signal (absent today = the leak root)
        val siteClaim = hasura != null && hasura.fieldNames().asSequence().any { it.contains("site", ignoreCase = true) }
        return UserSession(
            email = email,
            accessToken = token,
            tenantId = claims?.path("tid")?.asText("")?.ifBlank { null },
            role = hasura?.path("x-hasura-default-role")?.asText("")?.ifBlank { null },
            capabilityCount = authNode.path("capabilities").let { if (it.isArray) it.size() else 0 },
            siteClaimPresent = siteClaim,
            allowedSiteIds = allowedSites,
        )
    }
}

/** Pad a base64url segment to a multiple of 4 so the JDK decoder accepts it. */
private fun String.padBase64(): String = this + "=".repeat((4 - length % 4) % 4)
