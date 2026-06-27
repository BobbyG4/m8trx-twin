package com.m8trx.twin.connect.model.outbound

/**
 * The §9 outbound `stocktake_result` event-push payload that M8TRX POSTs to our [OutboundReceiver].
 *
 * NOTE: although it rides the webhook transport, this payload is **camelCase** (M8TRX-canonical
 * shape, Connect API doc §9) — NOT snake_case like the §8 inbound ingesters — so it is parsed with
 * [com.m8trx.twin.connect.model.ConnectMappers.camel]. [sessionId] is the stable dedupe key.
 */
data class StocktakeResult(
    val sessionId: String,
    val siteId: String,
    val status: String,
    val totalExpected: Int,
    val totalScanned: Int,
    val missingCount: Int,
    val ghostCount: Int,
    val financialValueMissing: Double,
    val completedAt: String,
)
