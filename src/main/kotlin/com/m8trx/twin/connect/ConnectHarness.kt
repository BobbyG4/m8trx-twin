package com.m8trx.twin.connect

import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.bearer.ChannelConfig
import com.m8trx.twin.connect.model.bearer.CreateIntegrationRequest
import com.m8trx.twin.connect.model.bearer.ImpressionQueryRequest
import com.m8trx.twin.connect.model.bearer.ImpressionQueryResponse
import com.m8trx.twin.connect.model.bearer.ReadCaps
import com.m8trx.twin.connect.model.bearer.SpatialIdentityRequest
import com.m8trx.twin.connect.model.bearer.SpatialIdentityResponse
import com.m8trx.twin.connect.model.bearer.TaskQueryRequest
import com.m8trx.twin.connect.model.outbound.StocktakeResult
import com.m8trx.twin.connect.model.webhook.DirectiveTarget
import com.m8trx.twin.connect.model.webhook.InventoryMovement
import com.m8trx.twin.connect.model.webhook.MovementItem
import com.m8trx.twin.connect.model.webhook.PlanogramDirective
import com.m8trx.twin.connect.model.webhook.PlanogramDocument
import com.m8trx.twin.connect.model.webhook.PricingUpdate
import com.m8trx.twin.connect.model.webhook.ProductCatalogItem
import com.m8trx.twin.connect.model.webhook.SaleEvent
import com.m8trx.twin.connect.model.webhook.ShipmentLine
import com.m8trx.twin.connect.model.webhook.ShipmentManifest
import com.m8trx.twin.connect.setup.ApiKeyBootstrap
import com.m8trx.twin.connect.setup.Provisioner
import com.m8trx.twin.connect.sim.FullLoopDriver
import com.m8trx.twin.connect.sim.OutboundReceiver
import com.m8trx.twin.connect.sim.PlanogramDirectiveDriver
import com.m8trx.twin.connect.sim.RemediateMode
import com.m8trx.twin.connect.sim.SaleArm
import com.m8trx.twin.connect.sim.SftpDropDriver
import com.m8trx.twin.connect.sim.StressHarness
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
    planogramDirectiveCasing()
    inventoryMovementCasing()
    readPlaneCasing()
    readPlaneCaps()
    fullLoopPlan()
    stressPlan()
    log.info("=== ALL OFFLINE SELF-TESTS PASSED ===")
}

/**
 * §6.5 is the one response shape on the whole surface that carries BOTH casings — snake_case
 * envelopes and refs, camelCase impression rows mirroring the ingest response. A field added without
 * its `@JsonProperty` serializes camelCase, the server ignores it as unknown, and the read silently
 * answers a different question than the one asked. This pins both halves.
 */
private fun readPlaneCasing() {
    // ── requests: refs are snake, always ──
    val impressionReq =
        ImpressionQueryRequest(siteRef = "dec-us-denver", spaceRef = "Sales Floor", zoneRef = "GF-R6-U1", from = "0", to = "1", limit = 500)
    val impressionJson = ConnectMappers.camel.writeValueAsString(impressionReq)
    listOf("site_ref", "space_ref", "zone_ref", "from", "to", "limit").forEach {
        check(impressionJson.contains("\"$it\"")) { "impression query must serialize snake ref $it: $impressionJson" }
    }
    listOf("siteRef", "spaceRef", "zoneRef").forEach {
        check(!impressionJson.contains(it)) { "read plane must not leak camelCase ref $it: $impressionJson" }
    }

    val spatialJson = ConnectMappers.camel.writeValueAsString(
        SpatialIdentityRequest(siteRef = "dec-us-denver", includeZones = false, zoneTypes = listOf("fixture")),
    )
    listOf("site_ref", "include_zones", "zone_types").forEach {
        check(spatialJson.contains("\"$it\"")) { "spatial/identity must serialize snake field $it: $spatialJson" }
    }
    val taskJson = ConnectMappers.camel.writeValueAsString(
        TaskQueryRequest(directiveRef = "PLN-1", siteRef = "dec-us-denver", status = listOf("open")),
    )
    check(taskJson.contains("\"directive_ref\"")) { "tasks/query must serialize directive_ref: $taskJson" }
    check(!taskJson.contains("directiveRef")) { "tasks/query must not leak directiveRef: $taskJson" }

    // ── response: snake envelope, CAMEL rows. Both must deserialize off the SAME mapper. ──
    val body = """
        {"site_id":"s-1","from":"2026-07-30T07:00:00Z","to":"2026-07-30T08:00:00Z","count":2,"truncated":true,
         "summary":{"zones":2,"sessions":2,"view_time_seconds":12.5,"dwell_time_seconds":9.25},
         "impressions":[
           {"id":"i-1","personSessionId":"ps-1","zoneId":"z-1","zoneCode":"PI-01","zoneName":"Promo Island 1",
            "spaceId":"sp-1","firstLook":"2026-07-28T04:30:46.691Z","lastLook":"2026-07-28T04:30:54.691Z",
            "firstDwell":"2026-07-28T04:30:46.891Z","lastDwell":"2026-07-28T04:30:54.491Z",
            "viewTimeSeconds":8.0,"dwellTimeSeconds":7.6,"classification":"adult","recordedAt":"2026-07-30T07:00:01Z"},
           {"id":"i-2","personSessionId":"ps-2","zoneId":"z-2","zoneCode":"GPS-04","firstLook":2000,"lastLook":6500,
            "viewTimeSeconds":4.5,"dwellTimeSeconds":4.1,"recordedAt":"2026-07-30T07:01:00Z"}]}
    """.trimIndent()
    val parsed = ConnectMappers.camel.readValue<ImpressionQueryResponse>(body)
    check(parsed.count == 2) { "snake envelope field count must bind" }
    check(parsed.truncated) { "truncated must bind — ignoring it turns a page into an answer" }
    check(parsed.siteId == "s-1") { "snake site_id must bind to siteId" }
    check(parsed.summary?.viewTimeSeconds == 12.5) { "snake summary view_time_seconds must bind" }
    check(parsed.impressions.size == 2) { "rows must bind" }
    val first = parsed.impressions.first()
    check(first.personSessionId == "ps-1") { "CAMEL row field personSessionId must bind off the same mapper" }
    check(first.zoneCode == "PI-01") { "CAMEL row field zoneCode must bind" }
    // The clocks are ISO-8601 STRINGS on the wire (measured live 2026-07-31), not epoch millis. A
    // Long here compiles and passes a hand-written fixture, then throws on the first real call — so
    // this fixture uses the shape the server actually sent, and row 2 keeps the millis form to prove
    // the accessor tolerates both.
    check(first.firstLook == "2026-07-28T04:30:46.691Z") { "row clocks bind as the ISO strings the server sends" }
    check(first.firstLookMs == java.time.Instant.parse("2026-07-28T04:30:46.691Z").toEpochMilli()) {
        "ISO clock must parse to millis: ${first.firstLookMs}"
    }
    check(parsed.impressions[1].firstLookMs == 2000L) { "an epoch-millis clock must still parse: ${parsed.impressions[1].firstLookMs}" }
    check(first.lastLookMs!! - first.firstLookMs!! == 8_000L) { "parsed clocks must support arithmetic" }
    check(first.recordedAt == "2026-07-30T07:00:01Z") { "recordedAt (event time, == firstLook) must bind" }
    check(parsed.impressions[1].classification == null) { "an absent optional row field must stay null, not throw" }

    // Unknown keys must not break the read — responses carry more than twin models.
    val withExtra = ConnectMappers.camel.readValue<ImpressionQueryResponse>(
        """{"count":1,"truncated":false,"someFutureField":"x","impressions":[{"id":"i-9","zoneCode":"RR-02","unknownRowField":7}]}""",
    )
    check(withExtra.impressions.first().zoneCode == "RR-02") { "unknown keys must be tolerated on both envelope and row" }

    val spatial = ConnectMappers.camel.readValue<SpatialIdentityResponse>(
        """{"sites":[{"site_id":"s-1","slug":"dec-us-denver","spaces":[{"space_id":"sp-1","space_type":"sales_floor",
           "zones":[{"zone_id":"z-1","code":"GB-R3-U1","external_code":null,"zone_type":"fixture","enabled":true}]}]}],
           "site_count":1,"space_count":1,"zone_count":1,"truncated":false}""",
    )
    check(spatial.sites.first().spaces.first().zones.first().code == "GB-R3-U1") { "zone code must bind — it is the key to map on, not name" }
    check(spatial.sites.first().spaces.first().zones.first().externalCode == null) { "external_code stays null until registered" }
    check(spatial.zoneCount == 1) { "snake zone_count must bind" }
    log.info("[PASS] §6.5 read-plane casing — snake envelopes/refs + camel impression rows, one mapper")
}

/**
 * The caps are refusals, not clamps: an over-max `limit` comes back 400 rather than silently
 * answering a narrower question. Twin checks locally first so that costs zero round trips, and so a
 * caller cannot mistake a clamped answer for a complete one.
 */
private fun readPlaneCaps() {
    val client =
        ConnectClient(
            ConnectConfig(
                apiBase = "http://localhost:1/api/v2",
                webhookBase = "",
                tenantId = null,
                integrationSlug = null,
                serviceBearer = "m8trx_x",
                webhookApiKey = null,
                inboundHmacSecret = null,
                outboundVerifySecret = null,
            ),
        )
    var refused = false
    try {
        client.queryImpressions(ImpressionQueryRequest(siteRef = "s", limit = ReadCaps.ROWS_MAX + 1))
    } catch (e: IllegalArgumentException) {
        refused = true
        check(e.message!!.contains("${ReadCaps.ROWS_MAX}")) { "the refusal must name the ceiling: ${e.message}" }
    }
    check(refused) { "an over-max impression limit must be refused locally, not sent" }

    refused = false
    try {
        client.spatialIdentity(SpatialIdentityRequest(limit = ReadCaps.ZONES_MAX + 1))
    } catch (e: IllegalArgumentException) {
        refused = true
    }
    check(refused) { "an over-max zone limit must be refused locally, not sent" }
    check(ReadCaps.ROWS_DEFAULT == 500 && ReadCaps.ZONES_DEFAULT == 2_000 && ReadCaps.WINDOW_MAX_DAYS == 31L) {
        "documented §6.5 ceilings must not drift silently from the API doc"
    }
    log.info("[PASS] §6.5 caps refuse locally and name the ceiling")
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
    // gtin rides both catalog and pricing as a snake-plane field, and is OMITTED when absent (NON_NULL)
    // so the payload stays byte-identical for a consumer that has not adopted the re-key yet.
    val pricedByGtin = ConnectMappers.snake.writeValueAsString(PricingUpdate("5391035", 8000, gtin = "3608392174964"))
    check(pricedByGtin.contains("\"gtin\":\"3608392174964\"")) { "pricing_update must carry gtin: $pricedByGtin" }
    check(pricedByGtin.contains("\"sku\":\"5391035\"")) { "pricing_update keeps sku alongside gtin: $pricedByGtin" }
    check(!ConnectMappers.snake.writeValueAsString(PricingUpdate("SKU-1", 1)).contains("gtin")) {
        "gtin must be omitted entirely when null, not serialized as null"
    }
    val catByGtin = ConnectMappers.snake.writeValueAsString(ProductCatalogItem("5391035", "Quechua MH500", gtin = "3608392174988"))
    check(catByGtin.contains("\"gtin\":\"3608392174988\"")) { "product_catalog must carry gtin: $catByGtin" }
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

/**
 * Mode-3 planogram directive (AS-BUILT ingest #64): snake_case on the inbound plane, round-trips, and the
 * driver maps the REAL builder output (`build_planogram.py` → planogram.json) to the as-built directive.
 */
private fun planogramDirectiveCasing() {
    val directive = PlanogramDirective(
        name = "Twin planogram — dec-us-denver (smoke)",
        externalDirectiveId = "TWIN-PLN-TEST-1",
        effectiveDate = "2026-07-01T00:00:00Z",
        targets = listOf(
            DirectiveTarget(
                siteId = "site-uuid-1",
                rawFixtureCode = "GB-R3-U1",
                rawItemIdentifier = "3608449847032",
                requiredQuantity = 4,
                facingCount = 1,
                positionSequence = 1,
                displayLevel = 2,
            ),
            DirectiveTarget(siteId = "site-uuid-1", rawFixtureCode = "WALL-A2", requiredQuantity = 8),
        ),
    )
    val json = ConnectMappers.snake.writeValueAsString(directive)
    listOf(
        "external_directive_id", "effective_date", "targets", "site_id", "raw_fixture_code",
        "raw_item_identifier", "required_quantity", "facing_count", "position_sequence", "display_level",
    ).forEach {
        check(json.contains("\"$it\"")) { "planogram_directive must serialize snake field $it: $json" }
    }
    listOf("externalDirectiveId", "rawFixtureCode", "requiredQuantity", "facingCount").forEach {
        check(!json.contains(it)) { "inbound plane must not leak camelCase $it: $json" }
    }
    check(json.contains("\"raw_item_identifier\":\"3608449847032\"")) { "first target keeps its EAN: $json" }
    check(ConnectMappers.snake.readValue<PlanogramDirective>(json) == directive) { "planogram_directive must round-trip" }

    // map the REAL Denver doc → directive (proves build_planogram.py ⇄ the as-built mapping agree)
    val denver = Path.of("reference/data/chain/stores/dec-us-denver/planogram.json")
    if (Files.exists(denver)) {
        val doc = ConnectMappers.snake.readValue<PlanogramDocument>(Files.readAllBytes(denver))
        val driver = PlanogramDirectiveDriver(WebhookClient(ConnectConfig.fromEnv()))
        val mapped = driver.toDirective(doc, siteId = "denver-uuid", fixtures = setOf("GB-R3-U1"))
        check(mapped.targets.isNotEmpty()) { "GB-R3-U1 slice must produce targets" }
        check(mapped.targets.all { it.siteId == "denver-uuid" && it.rawFixtureCode == "GB-R3-U1" && it.requiredQuantity > 0 }) {
            "mapped targets must carry the site UUID + fixture + a positive qty"
        }
        check(mapped.targets.all { it.rawItemIdentifier?.length == 13 }) { "EAN raw_item_identifier must be 13 digits" }
        log.info("[PASS] planogram directive (as-built #64): casing + round-trip + mapped {} Denver GB-R3-U1 targets", mapped.targets.size)
    } else {
        log.warn("[PASS] planogram directive (as-built #64): casing + round-trip (Denver planogram.json absent — run scripts/build_planogram.py)")
    }
}

/**
 * Inventory-movement (remediation demo, AS-PROPOSED services S178): snake_case on the inbound plane,
 * round-trips. Mirrors [planogramDirectiveCasing]'s casing assertion shape for the new ingester.
 */
private fun inventoryMovementCasing() {
    val movement = InventoryMovement(
        externalMovementId = "TWIN-MOV-TEST-1",
        siteId = "site-uuid-1",
        toFixtureCode = "GB-R3-U1",
        items = listOf(
            MovementItem(epc = "3039606303C19FC00021C51C"),
            MovementItem(epc = "3039606303C19FC0005756B1"),
        ),
        movementType = "relocation",
    )
    val json = ConnectMappers.snake.writeValueAsString(movement)
    listOf(
        "external_movement_id",
        "site_id",
        "to_fixture_code",
        "items",
        "epc",
        "movement_type",
    ).forEach {
        check(json.contains("\"$it\"")) { "inventory_movement must serialize snake field $it: $json" }
    }
    listOf("externalMovementId", "toFixtureCode", "movementType").forEach {
        check(!json.contains(it)) { "inbound plane must not leak camelCase $it: $json" }
    }
    check(!json.contains("to_zone_id")) { "null one-of fields must be omitted (NON_NULL): $json" }
    check(json.contains("\"to_fixture_code\":\"GB-R3-U1\"")) { "to_fixture_code must carry the target fixture: $json" }
    check(ConnectMappers.snake.readValue<InventoryMovement>(json) == movement) { "inventory_movement must round-trip" }
    log.info("[PASS] inventory movement (as-proposed S178): casing + round-trip, {} items", movement.items.size)
}

/**
 * Full-loop plan integrity (CORE-REQ-005 part 1): [FullLoopDriver.plan] is pure/offline (reads local data
 * files, no core), so we assert the composed plan's invariants on the REAL Denver GB-R3-U1 slice — drift is
 * bounded by on-floor stock, floor (drift) and back-of-house (relocate) pools are disjoint, BOH is consumed
 * once, and remediation never lowers observed. This proves the composition before any live wiring.
 */
private fun fullLoopPlan() {
    val siteIds = Path.of("reference/data/chain/site_ids.csv")
    val denverPj = Path.of("reference/data/chain/stores/dec-us-denver/planogram.json")
    val denverEpcs = Path.of("reference/data/chain/stores/dec-us-denver/epcs.csv")
    if (!Files.exists(siteIds) || !Files.exists(denverPj) || !Files.exists(denverEpcs)) {
        log.warn("[SKIP] full-loop plan — site_ids.csv / Denver planogram.json / epcs.csv absent (run from repo root)")
        return
    }
    val plan = FullLoopDriver(ConnectConfig.fromEnv()).plan(
        FullLoopDriver.LoopParams(
            stores = listOf("dec-us-denver"),
            fixtures = setOf("GB-R3-U1"),
            driftPerTarget = 2,
            remediate = RemediateMode.BOTH,
            seed = 42L,
        ),
    )
    if (plan.targets.isEmpty()) {
        log.warn("[SKIP] full-loop plan — 0 targets (GB-R3-U1 not in Denver planogram, or Denver not in site_ids)")
        return
    }
    val allDrift = ArrayList<String>()
    val allReloc = ArrayList<String>()
    for (t in plan.targets) {
        check(t.requiredQty > 0) { "target ${t.fixture}/${t.sku} must have required_qty > 0" }
        check(t.driftPicks.size == minOf(2, t.onFloorNow)) { "driftPerTarget=2 → picks=min(2,onFloor): ${t.fixture}/${t.sku}" }
        check(t.driftPicks.toSet().size == t.driftPicks.size) { "drift picks must be distinct: ${t.fixture}/${t.sku}" }
        check(t.survivingFloor.size == t.onFloorNow - t.driftPicks.size) { "surviving = onFloor - drift: ${t.fixture}/${t.sku}" }
        check(t.driftPicks.toSet().intersect(t.survivingFloor.toSet()).isEmpty()) { "drift ∩ surviving must be empty: ${t.fixture}" }
        check(t.driftPicks.toSet().intersect(t.relocatePicks.toSet()).isEmpty()) { "floor(drift) ∩ BOH(relocate) must be empty: ${t.fixture}" }
        check(t.relocatePicks.size <= t.bohAvailable) { "relocate picks can't exceed BOH available: ${t.fixture}/${t.sku}" }
        check(t.observedAfterRemediate >= t.observedAfterDrift) { "remediation must not lower observed: ${t.fixture}/${t.sku}" }
        allDrift += t.driftPicks
        allReloc += t.relocatePicks
    }
    check(allDrift.toSet().size == allDrift.size) { "no EPC may be drifted by two targets" }
    check(allReloc.toSet().size == allReloc.size) { "no BOH EPC may be relocated by two targets (consumption)" }
    log.info(
        "[PASS] full-loop plan (CORE-REQ-005): {} Denver GB-R3-U1 target(s), drift+relocate disjoint + BOH consumed once",
        plan.targets.size,
    )
}

/**
 * Stress-campaign plan integrity (CORE-REQ-005 part 2): [StressHarness.plan] is pure/offline, so we assert
 * the campaign it builds on the real Denver floor — every requested arm is represented, NoScope jobs carry a
 * distinct unsold EPC while the SKU arms carry none, and the dedup-replay + unmapped-store edge probes are
 * present. Proves the at-scale campaign shape before any live fire.
 */
private fun stressPlan() {
    val siteIds = Path.of("reference/data/chain/site_ids.csv")
    val denverEpcs = Path.of("reference/data/chain/stores/dec-us-denver/epcs.csv")
    if (!Files.exists(siteIds) || !Files.exists(denverEpcs)) {
        log.warn("[SKIP] stress plan — site_ids.csv / Denver epcs.csv absent (run from repo root)")
        return
    }
    val plan = StressHarness(ConnectConfig.fromEnv()).plan(
        StressHarness.StressParams(stores = listOf("dec-us-denver"), salesPerStore = 30, seed = 7L),
    )
    check(plan.jobs.isNotEmpty()) { "stress plan produced no jobs" }
    check(plan.jobs.map { it.arm }.toSet() == setOf(SaleArm.NOSCOPE, SaleArm.STORE_XREF, SaleArm.SITE_SCOPED)) {
        "all three site-resolution arms must be represented"
    }
    val noscope = plan.jobs.filter { it.arm == SaleArm.NOSCOPE && it.note.isEmpty() }
    check(noscope.all { it.epc != null }) { "NoScope jobs must carry an EPC" }
    check(noscope.mapNotNull { it.epc }.toSet().size == noscope.size) { "NoScope EPCs must be distinct (no double-sell)" }
    check(plan.jobs.filter { it.arm != SaleArm.NOSCOPE }.all { it.epc == null && it.sku.isNotEmpty() }) {
        "SKU arms must carry a SKU and no EPC"
    }
    check(plan.jobs.any { it.storeCode == StressHarness.UNMAPPED_STORE }) { "unmapped-store quarantine probe must be present" }
    check(plan.jobs.groupingBy { it.saleId }.eachCount().any { it.value > 1 }) { "dedup-replay (duplicate external_sale_id) must be present" }
    log.info("[PASS] stress plan (CORE-REQ-005 part 2): {} jobs across 3 arms + dedup + unmapped probes", plan.jobs.size)
}

private fun post(http: HttpClient, url: String, body: ByteArray, signature: String?): HttpResponse<String> {
    val builder = HttpRequest.newBuilder().uri(URI.create(url)).header("Content-Type", "application/json")
    if (signature != null) builder.header("X-M8TRX-Signature", signature)
    builder.POST(HttpRequest.BodyPublishers.ofByteArray(body))
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
}
