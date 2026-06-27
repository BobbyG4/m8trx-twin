package com.m8trx.twin.connect.sim

import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.Hmac
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.outbound.StocktakeResult
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Simulator #4 — the customer ERP endpoint M8TRX pushes to (Connect API doc §9, the event-push
 * shape). A JDK [HttpServer] that:
 *   1. reads the request body to a [ByteArray] **once**,
 *   2. verifies the `X-M8TRX-Signature` HMAC over **those exact bytes** (never a re-serialization),
 *   3. parses `stocktake_result` from the **same bytes** (camelCase — §9 is M8TRX-canonical),
 *   4. dedupes on [StocktakeResult.sessionId] (the stable key),
 *   5. returns a status that drives core's delivery semantics:
 *        valid + new → 200 · valid + replay → 200 (idempotent, not re-processed) ·
 *        bad signature → 401 · [failMode] → 500 (exercise retry→backoff→poison→alert).
 *
 * Serial executor by default (deterministic for a sim); the dedupe set is a bounded concurrent set
 * so swapping in a thread pool stays safe. Counters are exposed for the harness self-test + demo.
 */
class OutboundReceiver(
    private val verifySecret: String,
    private val port: Int = 0,
    private val path: String = "/hooks/m8trx",
    @Volatile var failMode: Boolean = false,
) {
    private val log = LoggerFactory.getLogger(OutboundReceiver::class.java)
    private val mapper = ConnectMappers.camel
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private var server: HttpServer? = null

    val received = AtomicInteger()
    val accepted = AtomicInteger()
    val deduped = AtomicInteger()
    val rejected = AtomicInteger()
    val failed = AtomicInteger()

    /** Start listening; returns the bound port (useful when [port] is 0 = ephemeral). */
    fun start(): Int {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        s.createContext(path, ::handle)
        s.executor = null // serial — deterministic for the simulator
        s.start()
        server = s
        val bound = s.address.port
        log.info("OutboundReceiver listening on http://127.0.0.1:{}{}", bound, path)
        return bound
    }

    fun stop() = server?.stop(0) ?: Unit

    val boundPort: Int get() = server?.address?.port ?: -1

    private fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                respond(exchange, 405, """{"error":"METHOD_NOT_ALLOWED"}""")
                return
            }
            received.incrementAndGet()
            val bytes = exchange.requestBody.readAllBytes() // read ONCE — never re-read the stream
            val sig = exchange.requestHeaders.getFirst("X-M8TRX-Signature")

            if (!Hmac.verify(verifySecret, bytes, sig)) {
                rejected.incrementAndGet()
                log.warn("rejected stocktake_result: bad/missing X-M8TRX-Signature")
                respond(exchange, 401, """{"error":"UNAUTHORIZED"}""")
                return
            }
            if (failMode) {
                failed.incrementAndGet()
                log.info("failMode → 500 (exercising core retry/backoff/poison)")
                respond(exchange, 500, """{"error":"SIMULATED_FAILURE"}""")
                return
            }

            val result = mapper.readValue<StocktakeResult>(bytes) // parse from the SAME bytes
            if (!seen.add(result.sessionId)) {
                deduped.incrementAndGet()
                log.info("deduped replayed stocktake_result session={}", result.sessionId)
                respond(exchange, 200, """{"ack":true,"dedup":true}""")
                return
            }
            evictIfLarge()
            accepted.incrementAndGet()
            log.info(
                "stocktake_result accepted session={} site={} status={} expected={} scanned={} missing={}",
                result.sessionId,
                result.siteId,
                result.status,
                result.totalExpected,
                result.totalScanned,
                result.missingCount,
            )
            respond(exchange, 200, """{"ack":true}""")
        } catch (e: Exception) {
            log.warn("receiver error: {}", e.message)
            respond(exchange, 400, """{"error":"BAD_REQUEST"}""")
        }
    }

    /** Crude bound to avoid an unbounded dedupe set over a long demo; sim-safe. */
    private fun evictIfLarge() {
        if (seen.size > MAX_SEEN) seen.clear()
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val b = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, b.size.toLong())
        exchange.responseBody.use { it.write(b) }
    }

    private companion object {
        const val MAX_SEEN = 10_000
    }
}
