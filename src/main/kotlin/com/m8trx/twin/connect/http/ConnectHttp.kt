package com.m8trx.twin.connect.http

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Thin shared wrapper over the JDK [HttpClient] for both Connect planes.
 *
 * Callers serialize the body to a [ByteArray] ONCE and pass it here, so the exact bytes
 * transmitted are the same bytes signed for HMAC (see [com.m8trx.twin.connect.Hmac]). The §3
 * error model is mapped to [ConnectResponse.Err] (parsing `{error, message}` + `Retry-After`);
 * 2xx → [ConnectResponse.Ok]. Nothing here throws on non-2xx — failure is data.
 */
class ConnectHttp(private val errorMapper: ObjectMapper = jacksonObjectMapper(), private val http: HttpClient = HttpClient.newHttpClient()) {
    private val log = LoggerFactory.getLogger(ConnectHttp::class.java)

    fun send(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): ConnectResponse {
        val builder = HttpRequest.newBuilder().uri(URI.create(url))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val publisher =
            if (body != null) {
                HttpRequest.BodyPublishers.ofByteArray(body)
            } else {
                HttpRequest.BodyPublishers.noBody()
            }
        builder.method(method, publisher)

        val resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        log.debug("{} {} → {}", method, url, resp.statusCode())

        return if (resp.statusCode() in 200..299) {
            ConnectResponse.Ok(resp.statusCode(), resp.body())
        } else {
            ConnectResponse.Err(parseError(resp))
        }
    }

    private fun parseError(resp: HttpResponse<String>): ConnectError {
        val body = resp.body() ?: ""
        var code: String? = null
        var message: String? = null
        if (body.isNotBlank()) {
            try {
                val node = errorMapper.readTree(body)
                code = node.get("error")?.asText()
                message = node.get("message")?.asText()
            } catch (e: Exception) {
                log.debug("error body was not JSON: {}", e.message)
            }
        }
        val retryAfter =
            resp
                .headers()
                .firstValue("Retry-After")
                .orElse(null)
                ?.toLongOrNull()
        return ConnectError(resp.statusCode(), code, message, body, retryAfter)
    }
}
