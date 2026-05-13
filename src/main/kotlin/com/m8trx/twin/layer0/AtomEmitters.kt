package com.m8trx.twin.layer0

import com.m8trx.twin.domain.AlarmEvent
import com.m8trx.twin.domain.Crossing
import com.m8trx.twin.domain.FittingRoomAddItem
import com.m8trx.twin.domain.FittingRoomEntry
import com.m8trx.twin.domain.FittingRoomExit
import com.m8trx.twin.domain.ObjEviction
import com.m8trx.twin.domain.ObjLocation
import java.time.Instant
import java.util.UUID

/**
 * Layer 0 — 1:1 mapping to M8TRX public API surfaces.
 * Wire shapes and endpoint addresses documented in reference/integration/M8TRX-API-SURFACE.md.
 */
interface AtomEmitters : AutoCloseable {

    // ── NATS atoms ────────────────────────────────────────────────────────

    fun objLocation(body: ObjLocation, ts: Long = now(), id: String = newId())
    fun objEviction(body: ObjEviction, ts: Long = now(), id: String = newId())
    fun crossing(body: Crossing, ts: Long = now(), id: String = newId())
    fun fittingRoomEntry(body: FittingRoomEntry, ts: Long = now(), id: String = newId())
    fun fittingRoomExit(body: FittingRoomExit, ts: Long = now(), id: String = newId())
    fun fittingRoomAddItem(body: FittingRoomAddItem, ts: Long = now(), id: String = newId())
    fun easAlarm(body: AlarmEvent, ts: Long = now(), id: String = newId())

    // ── REST atoms ────────────────────────────────────────────────────────

    /** POST /api/v2/visionai/person-sessions → returns personSessionId */
    fun personSessionStart(trackingId: String, spaceId: UUID, siteId: UUID, entryTime: Instant? = null): UUID

    /** PATCH /api/v2/visionai/person-sessions/{id} */
    fun personSessionClose(sessionId: UUID, exitTime: Instant? = null)

    /** POST /api/v2/scans */
    fun rfidScanBatch(
        siteId: UUID,
        spaceId: UUID?,
        zoneId: UUID?,
        readerId: String,
        reads: List<RfidRead>,
    )

    /** POST /api/v2/sales */
    fun saleNative(siteId: UUID, externalSaleId: String, lineItems: List<SaleLineItem>, occurredAt: Instant? = null)

    /** POST /api/v2/inventory/items/receive */
    fun inventoryReceive(siteId: UUID, spaceId: UUID, epcs: List<String>)

    /** POST /api/v2/inventory/skus/bulk */
    fun inventoryBulkSku(skus: List<SkuRecord>)
}

data class RfidRead(
    val identifier: String,
    val rssi: Short? = null,
    val readCount: Int? = null,
    val timestamp: Instant? = null,
    val antennaPort: Int? = null,
)

data class SaleLineItem(
    val sku: String? = null,
    val epc: String? = null,
    val epcList: List<String>? = null,
    val quantity: Int,
)

data class SkuRecord(
    val sku: String,
    val attribs: Map<String, Any?> = emptyMap(),
)

private fun now() = System.currentTimeMillis()
private fun newId() = UUID.randomUUID().toString()
