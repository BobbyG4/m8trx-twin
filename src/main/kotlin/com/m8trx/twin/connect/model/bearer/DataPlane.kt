package com.m8trx.twin.connect.model.bearer

/**
 * Bearer data-plane DTOs — Connect API doc §6, camelCase via
 * [com.m8trx.twin.connect.model.ConnectMappers.camel].
 */

/** `POST /api/v2/scans` request — submit a scan batch (→ 202). */
data class ScanBatch(
    val siteId: String,
    val readerId: String,
    val readerType: String = "RFID",
    val zoneId: String? = null,
    val spaceId: String? = null,
    val fixtureId: String? = null,
    val reads: List<ScanRead>,
)

data class ScanRead(
    val identifier: String,
    val rssi: Int? = null,
    val readCount: Int? = null,
    val timestamp: String? = null,
    val antennaPort: Int? = null,
)

/** `POST /api/v2/scans` response. */
data class ScanBatchResult(val accepted: Int = 0, val deduplicated: Int = 0, val resolved: Int = 0, val eventBatchId: String? = null)

/** `POST /api/v2/inventory/items/receive` request. */
data class ItemReceiveRequest(val siteId: String, val spaceId: String, val items: List<ReceiveItem>)

data class ReceiveItem(val epc: String)

/** `POST /api/v2/inventory/items/receive` response. */
data class ItemReceiveResult(val received: Int = 0, val created: Int = 0, val errors: List<ReceiveError> = emptyList())

data class ReceiveError(val epc: String? = null, val reason: String? = null)
