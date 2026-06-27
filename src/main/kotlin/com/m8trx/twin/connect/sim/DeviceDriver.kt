package com.m8trx.twin.connect.sim

import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.ConnectClient
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.bearer.ItemReceiveRequest
import com.m8trx.twin.connect.model.bearer.ItemReceiveResult
import com.m8trx.twin.connect.model.bearer.ReceiveItem
import com.m8trx.twin.connect.model.bearer.ScanBatch
import com.m8trx.twin.connect.model.bearer.ScanBatchResult
import com.m8trx.twin.connect.model.bearer.ScanRead
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Simulator #3 — Data-plane device driver (Connect API doc §6).
 *
 * Emulates an edge RFID reader / handheld scanner that drives live scans and item receipts
 * as a Bearer service principal. Covers the two data-plane endpoints: `POST /scans` and
 * `POST /inventory/items/receive`.
 *
 * §6 data-plane payloads carry NO run_id wire field; [runId] is twin-side correlation only
 * (logged for traceability), per the harness run-tracking convention.
 */
class DeviceDriver(private val client: ConnectClient) {
    private val log = LoggerFactory.getLogger(DeviceDriver::class.java)

    /**
     * Receive items into inventory at [siteId] / [spaceId] (§6 `POST /inventory/items/receive`).
     *
     * @param runId twin-side correlation tag — logged only, not sent on the wire.
     */
    fun receive(siteId: String, spaceId: String, epcs: List<String>, runId: String? = null): ConnectResponse {
        val req = ItemReceiveRequest(siteId = siteId, spaceId = spaceId, items = epcs.map { ReceiveItem(it) })
        val resp = client.receiveItems(req)
        if (resp.isOk) {
            val result = client.mapper.readValue<ItemReceiveResult>(resp.rawBody)
            log.info(
                "receive ok — received={} created={} errors={} siteId={} spaceId={} epcs={}{}",
                result.received,
                result.created,
                result.errors.size,
                siteId,
                spaceId,
                epcs.size,
                if (runId != null) " runId=$runId" else "",
            )
        } else {
            val err = (resp as ConnectResponse.Err).error
            log.warn(
                "receive failed — status={} code={} message={}{}",
                err.status,
                err.code,
                err.message,
                if (runId != null) " runId=$runId" else "",
            )
        }
        return resp
    }

    /**
     * Submit a scan batch from [readerId] at [siteId] (§6 `POST /scans`).
     *
     * @param runId twin-side correlation tag — logged only, not sent on the wire.
     */
    fun scan(
        siteId: String,
        readerId: String,
        epcs: List<String>,
        spaceId: String? = null,
        zoneId: String? = null,
        fixtureId: String? = null,
        runId: String? = null,
    ): ConnectResponse {
        val batch =
            ScanBatch(
                siteId = siteId,
                readerId = readerId,
                zoneId = zoneId,
                spaceId = spaceId,
                fixtureId = fixtureId,
                reads =
                epcs.map {
                    ScanRead(identifier = it, rssi = -55, readCount = 1, timestamp = Instant.now().toString(), antennaPort = 1)
                },
            )
        val resp = client.submitScans(batch)
        if (resp.isOk) {
            val result = client.mapper.readValue<ScanBatchResult>(resp.rawBody)
            log.info(
                "scan ok — accepted={} deduplicated={} resolved={} eventBatchId={} readerId={} epcs={}{}",
                result.accepted,
                result.deduplicated,
                result.resolved,
                result.eventBatchId,
                readerId,
                epcs.size,
                if (runId != null) " runId=$runId" else "",
            )
        } else {
            val err = (resp as ConnectResponse.Err).error
            log.warn(
                "scan failed — status={} code={} message={}{}",
                err.status,
                err.code,
                err.message,
                if (runId != null) " runId=$runId" else "",
            )
        }
        return resp
    }
}
