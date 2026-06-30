package com.m8trx.twin.connect

import com.m8trx.twin.connect.sim.OutboundReceiver
import org.slf4j.LoggerFactory

/**
 * Outbound receiver runner (Connect §9, C3) — stands up the [OutboundReceiver] on a LAN-reachable
 * address so M8TRX's OutboundWebhookDispatcher can POST a signed `stocktake_result` to twin. The
 * receiver verifies `X-M8TRX-Signature` over the raw body, dedupes by `sessionId`, and returns
 * 200 (new) / 200 {dedup} (replay) / 401 (bad sig) / 500 (failMode — for the retry→poison test).
 *
 * Env: `M8TRX_CONNECT_OUTBOUND_VERIFY_SECRET` (REQUIRED — the shared HMAC secret, out-of-band),
 * `M8TRX_OUTBOUND_BIND` (default `0.0.0.0` — all interfaces / LAN-reachable), `M8TRX_OUTBOUND_PORT`
 * (default 8088), `M8TRX_OUTBOUND_PATH` (default `/hooks/m8trx`), `M8TRX_OUTBOUND_FAILMODE` (default
 * false). Run: `./gradlew connectOutboundReceiver` — blocks until killed.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ConnectOutboundReceiver")

fun main() {
    val secret = System.getenv("M8TRX_CONNECT_OUTBOUND_VERIFY_SECRET")?.takeIf { it.isNotBlank() }
        ?: error("M8TRX_CONNECT_OUTBOUND_VERIFY_SECRET is not set — the shared HMAC secret (provisioned out-of-band)")
    val bind = System.getenv("M8TRX_OUTBOUND_BIND")?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
    val port = System.getenv("M8TRX_OUTBOUND_PORT")?.toIntOrNull() ?: 8088
    val path = System.getenv("M8TRX_OUTBOUND_PATH")?.takeIf { it.isNotBlank() } ?: "/hooks/m8trx"
    val failMode = System.getenv("M8TRX_OUTBOUND_FAILMODE")?.toBooleanStrictOrNull() ?: false

    val receiver = OutboundReceiver(verifySecret = secret, port = port, path = path, bindHost = bind, failMode = failMode)
    val bound = receiver.start()
    log.info("Outbound receiver UP — POST to http://{}:{}{}  (failMode={}). Blocking until killed.", bind, bound, path, failMode)
    Runtime.getRuntime().addShutdownHook(Thread { receiver.stop() })

    while (true) {
        Thread.sleep(30_000)
        log.info(
            "receiver counters — received={} accepted={} deduped={} rejected={} failed={}",
            receiver.received.get(),
            receiver.accepted.get(),
            receiver.deduped.get(),
            receiver.rejected.get(),
            receiver.failed.get(),
        )
    }
}
