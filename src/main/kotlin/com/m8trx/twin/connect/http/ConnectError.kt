package com.m8trx.twin.connect.http

/**
 * A non-2xx response from the Connect API, parsed against the §3 error model
 * `{"error":"<CODE>","message":"<text>"}`.
 *
 * Non-2xx is first-class DATA in this harness — the whole point is to exercise core's
 * retry / poison / DLQ surfaces — so clients pattern-match on [ConnectResponse] rather than
 * treating every failure as an exception to abort on.
 */
data class ConnectError(val status: Int, val code: String?, val message: String?, val rawBody: String, val retryAfterSeconds: Long?) {
    /** 429 + 5xx are worth retrying; 4xx (validation / auth / entitlement / scope) are not. */
    val retriable: Boolean get() = status == 429 || status in 500..599
}

/** Result of a Connect HTTP call: [Ok] for 2xx, [Err] otherwise. */
sealed interface ConnectResponse {
    val status: Int
    val rawBody: String

    data class Ok(override val status: Int, override val rawBody: String) : ConnectResponse

    data class Err(val error: ConnectError) : ConnectResponse {
        override val status: Int get() = error.status
        override val rawBody: String get() = error.rawBody
    }

    val isOk: Boolean get() = this is Ok

    /** Happy-path convenience: returns the body on 2xx, throws [ConnectException] otherwise. */
    fun bodyOrThrow(): String = when (this) {
        is Ok -> rawBody
        is Err -> throw ConnectException(error)
    }
}

class ConnectException(val error: ConnectError) :
    RuntimeException(
        "Connect call failed: ${error.status} ${error.code ?: ""} ${error.message ?: error.rawBody}".trim(),
    )
