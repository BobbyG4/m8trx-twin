package com.m8trx.twin.ops

import com.fasterxml.jackson.databind.JsonNode
import com.m8trx.twin.connect.ConnectConfig
import com.m8trx.twin.connect.UserAuthClient
import com.m8trx.twin.connect.http.ConnectHttp
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import org.slf4j.LoggerFactory

/**
 * The user-JWT plane for the store-operations steps: REST verbs on `{apiBase}` and Hasura reads on `{hasuraUrl}`, each
 * call as one named store user. Logins are cached per email for the run. Nothing here writes outside the platform's own
 * endpoints; the Hasura use is READ-ONLY (queries, never mutations — the twin posture).
 *
 * Every request body is a plain map with the platform's own keys (the count inbox is camelCase, the task surface is
 * snake_case, the zone update camelCase) — what goes on the wire is exactly what is written here, nulls dropped.
 */
class OpsClient(
    private val apiBase: String,
    private val hasuraUrl: String,
    private val password: () -> String,
    private val http: ConnectHttp = ConnectHttp(),
) {
    private val log = LoggerFactory.getLogger(OpsClient::class.java)
    private val mapper = ConnectMappers.camel
    private val auth = UserAuthClient(ConnectConfig(apiBase, "", null, null, null, null, null, null), http)
    private val tokens = HashMap<String, String?>()

    /** JWT for [email], or null when the login is refused (logged once). */
    fun jwt(email: String): String? = tokens.getOrPut(email) { auth.login(email, password())?.accessToken }

    fun send(jwt: String, method: String, path: String, body: Map<String, Any?>? = null): ConnectResponse {
        val bytes = body?.let { mapper.writeValueAsBytes(it.filterValues { v -> v != null }) }
        val headers = buildMap {
            put("Authorization", "Bearer $jwt")
            if (bytes != null) put("Content-Type", "application/json")
        }
        return http.send(method, apiBase + path, headers, bytes)
    }

    fun json(r: ConnectResponse): JsonNode = mapper.readTree(r.rawBody.ifBlank { "{}" })

    /** A Hasura READ as [jwt]; returns `data` or throws with the first GraphQL error (a refused read is never an empty answer). */
    fun query(jwt: String, gql: String, variables: Map<String, Any?> = emptyMap()): JsonNode {
        val body = mapper.writeValueAsBytes(mapOf("query" to gql, "variables" to variables))
        val headers = mapOf("Authorization" to "Bearer $jwt", "Content-Type" to "application/json")
        return when (val r = http.send("POST", hasuraUrl, headers, body)) {
            is ConnectResponse.Ok -> {
                val node = mapper.readTree(r.rawBody)
                val errors = node.path("errors")
                if (errors.isArray && errors.size() > 0) error("graphql: ${errors[0].path("message").asText()}")
                node.path("data")
            }

            is ConnectResponse.Err -> error("graphql HTTP ${r.error.status} ${r.error.code ?: ""}")
        }
    }

    fun describe(r: ConnectResponse): String = when (r) {
        is ConnectResponse.Ok -> "${r.status}"
        is ConnectResponse.Err -> "${r.error.status} ${r.error.code ?: ""} ${r.error.message ?: r.error.rawBody.take(160)}".trim()
    }.also { if (r is ConnectResponse.Err) log.debug("refused: {}", it) }
}
