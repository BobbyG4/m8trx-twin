package com.m8trx.twin.connect

import com.m8trx.twin.connect.http.ConnectHttp
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers

/** Outcome of a GraphQL count probe: a row count, or an error (schema mismatch / RLS rejection / transport). */
sealed class GqlResult {
    data class Count(val n: Int) : GqlResult()
    data class Error(val message: String) : GqlResult()
}

/**
 * Minimal Hasura GraphQL **read** client — POSTs an aggregate-count query as a user JWT to the tenant's
 * Hasura (`mother.m8trx.com/v2/v1/graphql`), the read-side surface twin is sanctioned to query
 * (`M8TRX-API-SURFACE.md` §read). No mutations. RLS confinement is asserted by what the count returns.
 */
class HasuraClient(private val url: String, private val http: ConnectHttp = ConnectHttp()) {
    private val mapper = ConnectMappers.camel

    /** `{ <table>_aggregate(where: <whereJson>) { aggregate { count } } }` as [jwt]. */
    fun countWhere(jwt: String, table: String, whereJson: String): GqlResult =
        aggregateCount(jwt, "{ ${table}_aggregate(where: $whereJson) { aggregate { count } } }")

    /** Total rows of [table] visible to [jwt] (no filter) — the store-picker breadth probe. */
    fun countAll(jwt: String, table: String): GqlResult = aggregateCount(jwt, "{ ${table}_aggregate { aggregate { count } } }")

    private fun aggregateCount(jwt: String, gql: String): GqlResult {
        val body = mapper.writeValueAsBytes(mapOf("query" to gql))
        return when (val resp = http.send("POST", url, headers(jwt), body)) {
            is ConnectResponse.Ok -> {
                val node = mapper.readTree(resp.rawBody)
                val errors = node.path("errors")
                if (errors.isArray && errors.size() > 0) {
                    GqlResult.Error(errors[0].path("message").asText("unknown graphql error"))
                } else {
                    val data = node.path("data")
                    val first = data.fieldNames().asSequence().firstOrNull()
                        ?: return GqlResult.Error("no data in response")
                    GqlResult.Count(data.path(first).path("aggregate").path("count").asInt(-1))
                }
            }
            is ConnectResponse.Err -> GqlResult.Error("HTTP ${resp.error.status} ${resp.error.code ?: ""}".trim())
        }
    }

    private fun headers(jwt: String) = mapOf("Authorization" to "Bearer $jwt", "Content-Type" to "application/json")
}
