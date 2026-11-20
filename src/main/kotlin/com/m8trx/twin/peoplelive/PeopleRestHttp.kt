package com.m8trx.twin.peoplelive

import com.m8trx.twin.connect.http.ConnectHttp
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.storeops.LiveClients
import com.m8trx.twin.storeops.PeopleAnswer
import com.m8trx.twin.storeops.PeopleTransport
import java.time.Duration

/**
 * The ONLY network class of the people-by-REST live path (the storeops package keeps its no-network scan; this lives outside it). Built only
 * by `PeopleRestLiveMain`, only on a live run that passed every gate. The bearer is held here and is never logged or written.
 */
class PeopleRestHttp private constructor(private val base: String, private val bearer: String) : PeopleTransport {
    init {
        LiveClients.constructed++
    }

    private val http = ConnectHttp(requestTimeout = Duration.ofSeconds(30))
    private val healthHttp = ConnectHttp(requestTimeout = Duration.ofSeconds(10))

    override fun health(): Pair<Int, String?> = try {
        when (val r = healthHttp.send("GET", "$base/api/health")) {
            is ConnectResponse.Ok -> r.status to r.rawBody
            is ConnectResponse.Err -> r.status to r.rawBody
        }
    } catch (e: java.io.IOException) {
        0 to null
    }

    /** Throws [java.io.IOException] when nothing answers (the sender maps that to "no answer"). */
    override fun call(method: String, path: String, body: String): PeopleAnswer {
        val headers = mapOf("Authorization" to "Bearer $bearer", "Content-Type" to "application/json", "Accept" to "application/json")
        return when (val r = http.send(method, base + path, headers, body.toByteArray())) {
            is ConnectResponse.Ok -> PeopleAnswer(r.status, r.rawBody, null)
            is ConnectResponse.Err -> PeopleAnswer(r.error.status, r.error.rawBody, r.error.retryAfterSeconds)
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://dev.m8trx.com/server"

        fun create(live: Boolean, base: String, bearer: String): PeopleRestHttp {
            require(live) { "PeopleRestHttp is built only on a live run (M8TRX_PEOPLE_REST_LIVE=true); a dry run never constructs it" }
            require(bearer.isNotBlank()) { "no bearer" }
            return PeopleRestHttp(base.trimEnd('/'), bearer)
        }
    }
}
