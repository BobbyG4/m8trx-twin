package com.m8trx.twin.layer0

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.TwinConfig
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID

/**
 * REST Layer 0 — all calls against dev.m8trx.com / LAN main-server.
 * Auth: Authorization: Bearer <service api_key>.
 */
class RestEmitter(
    private val config: TwinConfig,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
) {
    private val log = LoggerFactory.getLogger(RestEmitter::class.java)
    private val http = HttpClient.newHttpClient()

    fun personSessionStart(trackingId: String, spaceId: UUID, siteId: UUID, entryTime: Instant?): UUID {
        val body = buildMap {
            put("trackingId", trackingId)
            put("spaceId", spaceId.toString())
            put("siteId", siteId.toString())
            if (entryTime != null) put("entryTime", entryTime.toString())
        }
        val resp = post("/api/v2/visionai/person-sessions", body)
        check(resp.statusCode() == 201) { "personSessionStart failed ${resp.statusCode()}: ${resp.body()}" }
        val json = mapper.readValue<Map<String, Any?>>(resp.body())
        return UUID.fromString(json["id"] as String)
    }

    fun personSessionClose(sessionId: UUID, exitTime: Instant?) {
        val body = if (exitTime != null) mapOf("exitTime" to exitTime.toString()) else emptyMap<String, Any>()
        val resp = patch("/api/v2/visionai/person-sessions/$sessionId", body)
        check(resp.statusCode() in 200..299) { "personSessionClose failed ${resp.statusCode()}: ${resp.body()}" }
    }

    fun rfidScanBatch(siteId: UUID, spaceId: UUID?, zoneId: UUID?, readerId: String, reads: List<RfidRead>) {
        val body = buildMap {
            put("siteId", siteId.toString())
            if (spaceId != null) put("spaceId", spaceId.toString())
            if (zoneId != null) put("zoneId", zoneId.toString())
            put("readerId", readerId)
            put("readerType", "RFID")
            put("reads", reads.map { r ->
                buildMap {
                    put("identifier", r.identifier)
                    if (r.rssi != null) put("rssi", r.rssi)
                    if (r.readCount != null) put("readCount", r.readCount)
                    if (r.timestamp != null) put("timestamp", r.timestamp.toString())
                    if (r.antennaPort != null) put("antennaPort", r.antennaPort)
                }
            })
        }
        val resp = post("/api/v2/scans", body)
        check(resp.statusCode() == 202) { "rfidScanBatch failed ${resp.statusCode()}: ${resp.body()}" }
    }

    fun saleNative(siteId: UUID, externalSaleId: String, lineItems: List<SaleLineItem>, occurredAt: Instant?) {
        val body = buildMap {
            put("siteId", siteId.toString())
            put("externalSaleId", externalSaleId)
            if (occurredAt != null) put("occurredAt", occurredAt.toString())
            put("lineItems", lineItems.map { li ->
                buildMap {
                    if (li.sku != null) put("sku", li.sku)
                    if (li.epc != null) put("epc", li.epc)
                    if (li.epcList != null) put("epcList", li.epcList)
                    put("quantity", li.quantity)
                }
            })
        }
        val resp = post("/api/v2/sales", body)
        check(resp.statusCode() in 200..299) { "saleNative failed ${resp.statusCode()}: ${resp.body()}" }
    }

    fun inventoryReceive(siteId: UUID, spaceId: UUID, epcs: List<String>) {
        val body = mapOf("siteId" to siteId.toString(), "spaceId" to spaceId.toString(), "items" to epcs.map { mapOf("epc" to it) })
        val resp = post("/api/v2/inventory/items/receive", body)
        check(resp.statusCode() in 200..299) { "inventoryReceive failed ${resp.statusCode()}: ${resp.body()}" }
    }

    fun inventoryBulkSku(skus: List<SkuRecord>) {
        val body = mapOf("skus" to skus.map { mapOf("sku" to it.sku, "attribs" to it.attribs) })
        val resp = post("/api/v2/inventory/skus/bulk", body)
        check(resp.statusCode() in 200..299) { "inventoryBulkSku failed ${resp.statusCode()}: ${resp.body()}" }
    }

    private fun post(path: String, body: Any): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("${config.restBaseUrl}$path"))
            .header("Authorization", "Bearer ${config.serviceBearer}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        log.debug("POST {} → {}", path, resp.statusCode())
        return resp
    }

    private fun patch(path: String, body: Any): HttpResponse<String> {
        val req = HttpRequest.newBuilder()
            .uri(URI.create("${config.restBaseUrl}$path"))
            .header("Authorization", "Bearer ${config.serviceBearer}")
            .header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        log.debug("PATCH {} → {}", path, resp.statusCode())
        return resp
    }
}
