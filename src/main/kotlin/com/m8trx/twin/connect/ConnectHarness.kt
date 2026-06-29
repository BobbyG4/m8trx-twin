package com.m8trx.twin.connect

import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.bearer.ChannelConfig
import com.m8trx.twin.connect.model.bearer.CreateIntegrationRequest
import com.m8trx.twin.connect.model.outbound.StocktakeResult
import com.m8trx.twin.connect.model.webhook.PricingUpdate
import com.m8trx.twin.connect.model.webhook.SaleEvent
import com.m8trx.twin.connect.model.webhook.ShipmentLine
import com.m8trx.twin.connect.model.webhook.ShipmentManifest
import com.m8trx.twin.connect.setup.ApiKeyBootstrap
import com.m8trx.twin.connect.setup.Provisioner
import com.m8trx.twin.connect.sim.OutboundReceiver
import com.m8trx.twin.connect.sim.SftpDropDriver
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

/**
 * Offline self-test harness for the M8TRX Connect simulators (CORE-REQ-003).
 *
 * Runs with ZERO core dependency — it proves the parts that must be correct before any live wiring:
 * HMAC sign↔verify, the two-mapper casing split, and the full OutboundReceiver loop (accept /
 * dedupe / tamper-reject / fail-mode) via a localhost round-trip. The live drivers' offline paths
 * (dry-run request shapes + the SFTP CSV formatter) are checked too.
 *
 * Run: `./gradlew connectSelfTest`. Assertions use `check(...)`; any failure aborts non-zero.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ConnectHarness")

fun main() {
    log.info("=== M8TRX Connect simulators — OFFLINE self-tests (zero core dependency) ===")
    hmacRoundTrip()
    dtoCasingRoundTrip()
    outboundReceiverLoopback()
    dryRunAndFormatterChecks()
    saleStreamEpcLoader()
    log.info("=== ALL OFFLINE SELF-TESTS PASSED ===")
}

/** The keystone: a signature verifies against the exact bytes it signed, and nothing else. */
private fun hmacRoundTrip() {
    val secret = "inbound-hmac-secret"
    val body = """{"hello":"world"}""".toByteArray()
    val sig = Hmac.signatureHeader(secret, body)

    check(sig.startsWith("sha256=")) { "signature header must carry the sha256= prefix" }
    check(Hmac.verify(secret, body, sig)) { "verify must accept the signature it produced" }
    check(Hmac.verify(secret, body, sig.removePrefix("sha256="))) { "verify must accept a bare hex signature" }
    check(!Hmac.verify(secret, body, "sha256=deadbeef")) { "verify must reject a wrong signature" }
    check(!Hmac.verify("other-secret", body, sig)) { "verify must reject under a different secret" }
    check(!Hmac.verify(secret, """{"hello":"WORLD"}""".toByteArray(), sig)) { "verify must reject a tampered body" }
    check(!Hmac.verify(secret, body, null)) { "verify must reject a missing signature" }
    log.info("[PASS] HMAC sign↔verify round-trip")
}

/** snake-case on the §8 webhook plane; camelCase on the bearer + §9 outbound plane. */
private fun dtoCasingRoundTrip() {
    val sale = SaleEvent.bySku("run1-sale-1", "2026-06-27T10:00:00Z", "site-1", "SKU-1", 2)
    val saleJson = ConnectMappers.snake.writeValueAsString(sale)
    listOf("external_sale_id", "site_id", "occurred_at", "sku", "quantity").forEach {
        check(saleJson.contains("\"$it\"")) { "sale_event must serialize snake field $it: $saleJson" }
    }
    check(!saleJson.contains("epc_list")) { "null one-of fields must be omitted (NON_NULL): $saleJson" }
    check(!saleJson.contains("externalSaleId")) { "webhook plane must not leak camelCase: $saleJson" }

    // External-store path (Connect multi-site) — store_id/store_name present, site_id omitted (one-of).
    val storeSale = SaleEvent.byStore("run1-sale-2", "2026-06-27T10:00:00Z", "SMOKE-9999", "Smoke Test", "SKU-1", 1)
    val storeJson = ConnectMappers.snake.writeValueAsString(storeSale)
    listOf("store_id", "store_name", "sku", "quantity").forEach {
        check(storeJson.contains("\"$it\"")) { "sale_event store path must serialize snake field $it: $storeJson" }
    }
    check(!storeJson.contains("site_id")) { "store path must omit site_id (NON_NULL one-of): $storeJson" }
    check(!storeJson.contains("storeId")) { "webhook plane must not leak camelCase: $storeJson" }

    val shipJson = ConnectMappers.snake.writeValueAsString(ShipmentManifest("ext-1", "site-1", listOf(ShipmentLine("SKU-1", 5))))
    listOf("external_shipment_id", "destination_site_id", "items", "expected_quantity").forEach {
        check(shipJson.contains("\"$it\"")) { "shipment_manifest must serialize snake field $it: $shipJson" }
    }
    check(ConnectMappers.snake.writeValueAsString(PricingUpdate("SKU-1", 2999)).contains("\"price_minor\"")) {
        "pricing_update must serialize price_minor"
    }

    val ctrlReq = CreateIntegrationRequest(
        name = "posfeed",
        integrationType = "sale_event",
        channels = listOf(ChannelConfig("inbound", "sale_event", "generic_rest_webhook", mapOf("hmac_secret" to "s"))),
    )
    val ctrlJson = ConnectMappers.camel.writeValueAsString(ctrlReq)
    listOf("integration_type", "data_type", "endpoint_config").forEach {
        check(ctrlJson.contains("\"$it\"")) { "control-plane @JsonProperty snake field $it must be present: $ctrlJson" }
    }
    check(ctrlJson.contains("\"name\"")) { "control-plane camelCase fields must serialize: $ctrlJson" }

    val stk = StocktakeResult("sess-1", "site-1", "complete", 10, 10, 0, 0, 0.0, "2026-06-27T10:00:00Z")
    val stkJson = ConnectMappers.camel.writeValueAsString(stk)
    listOf("sessionId", "siteId", "totalExpected", "completedAt").forEach {
        check(stkJson.contains("\"$it\"")) { "stocktake_result (§9 outbound) must be camelCase $it: $stkJson" }
    }
    check(ConnectMappers.camel.readValue<StocktakeResult>(stkJson) == stk) { "stocktake_result must round-trip" }
    log.info("[PASS] DTO casing + round-trip (snake webhook / camel bearer+outbound)")
}

/** The richest offline test: a localhost POST loop through the real OutboundReceiver. */
private fun outboundReceiverLoopback() {
    val secret = "outbound-verify-secret"
    val receiver = OutboundReceiver(verifySecret = secret, port = 0, path = "/hooks/m8trx")
    val port = receiver.start()
    try {
        val http = HttpClient.newHttpClient()
        val url = "http://127.0.0.1:$port/hooks/m8trx"
        val stk = StocktakeResult("sess-loop-1", "site-1", "complete", 100, 100, 0, 0, 0.0, "2026-06-27T10:00:00Z")
        val body = ConnectMappers.camel.writeValueAsBytes(stk)
        val sig = Hmac.signatureHeader(secret, body)

        check(post(http, url, body, sig).statusCode() == 200) { "valid signed stocktake_result must ack 200" }

        val replay = post(http, url, body, sig)
        check(replay.statusCode() == 200) { "replayed sessionId must still ack 200" }
        check(replay.body().contains("dedup")) { "replay must be flagged deduped: ${replay.body()}" }

        val tampered = ConnectMappers.camel.writeValueAsBytes(stk.copy(missingCount = 5))
        check(post(http, url, tampered, sig).statusCode() == 401) { "tampered body (stale sig) must be rejected 401" }
        check(post(http, url, body, null).statusCode() == 401) { "missing signature must be rejected 401" }

        receiver.failMode = true
        val stk2 = stk.copy(sessionId = "sess-loop-2")
        val body2 = ConnectMappers.camel.writeValueAsBytes(stk2)
        check(post(http, url, body2, Hmac.signatureHeader(secret, body2)).statusCode() == 500) {
            "failMode must return 500 to drive core retry/poison"
        }
        receiver.failMode = false

        check(receiver.accepted.get() == 1) { "accepted should be 1, got ${receiver.accepted.get()}" }
        check(receiver.deduped.get() == 1) { "deduped should be 1, got ${receiver.deduped.get()}" }
        check(receiver.rejected.get() == 2) { "rejected should be 2 (tamper + missing-sig), got ${receiver.rejected.get()}" }
        check(receiver.failed.get() == 1) { "failed should be 1, got ${receiver.failed.get()}" }
        log.info("[PASS] OutboundReceiver loopback: accept / dedupe / tamper-reject / missing-sig-reject / failMode")
    } finally {
        receiver.stop()
    }
}

/** The live drivers' OFFLINE paths: dry-run request bodies + the pure SFTP CSV formatter. */
private fun dryRunAndFormatterChecks() {
    val client = ConnectClient(ConnectConfig.fromEnv())

    val provisionJson = Provisioner(client).dryRunInboundWebhook("posfeed", listOf("sale_event", "product_catalog"), hmacSecret = "s3cr3t")
    listOf("integration_type", "data_type", "endpoint_config", "hmac_secret").forEach {
        check(provisionJson.contains("\"$it\"")) { "dryRunInboundWebhook must contain $it: $provisionJson" }
    }

    val keyJson = ApiKeyBootstrap(client).dryRun(name = "twin-sim", scopes = listOf("scan:submit", "inventory:create"))
    check(keyJson.contains("scan:submit")) { "api-key dry-run must contain the requested scopes: $keyJson" }

    val csv = SftpDropDriver.pricingUpdatesCsv(listOf("SKU-1" to 1999L, "SKU-2" to 2999L))
    check(csv.startsWith("sku,price_minor\r\n")) { "SFTP CSV must have a header row: $csv" }
    check(csv.contains("SKU-1,1999\r\n")) { "SFTP CSV must contain the data row: $csv" }

    val quoted = SftpDropDriver.toCsv(listOf("a", "b"), listOf(listOf("x,y", "he said \"hi\"")))
    check(quoted.contains("\"x,y\"")) { "SFTP CSV must quote comma fields: $quoted" }
    check(quoted.contains("\"he said \"\"hi\"\"\"")) { "SFTP CSV must double internal quotes: $quoted" }
    log.info("[PASS] dry-run request shapes + SFTP CSV formatter")
}

/** The sale-stream EPC loader (SaleStream.loadFloorEpcs): floor-only, artifacts excluded, distinct. */
private fun saleStreamEpcLoader() {
    val denver = Path.of("reference/data/chain/stores/dec-us-denver/epcs.csv")
    if (!Files.exists(denver)) {
        log.warn("[SKIP] sale-stream EPC loader — {} not found (run from repo root)", denver)
        return
    }
    val excl = setOf("3039606303C19FC0008F4287")
    val epcs = loadFloorEpcs(denver, excl)
    check(epcs.isNotEmpty()) { "loadFloorEpcs returned no floor EPCs" }
    check(epcs.toSet().size == epcs.size) { "loadFloorEpcs must return distinct EPCs" }
    check(excl.none { it in epcs }) { "loadFloorEpcs must drop excluded EPCs" }
    check(epcs.none { it.isBlank() }) { "loadFloorEpcs must drop blank EPCs" }
    log.info("[PASS] sale-stream EPC loader: {} floor EPCs (distinct, BOH + artifacts excluded)", epcs.size)
}

private fun post(http: HttpClient, url: String, body: ByteArray, signature: String?): HttpResponse<String> {
    val builder = HttpRequest.newBuilder().uri(URI.create(url)).header("Content-Type", "application/json")
    if (signature != null) builder.header("X-M8TRX-Signature", signature)
    builder.POST(HttpRequest.BodyPublishers.ofByteArray(body))
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
}
