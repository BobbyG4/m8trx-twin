package com.m8trx.twin.connect

import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.bearer.ItemDetail
import com.m8trx.twin.connect.model.bearer.ItemDetailsRequest
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Self-verify (Connect §6 read-side) — reads back the current server-side state of EPCs twin has
 * acted on, via `POST /api/v2/inventory/items/details` (Bearer, `inventory:read`). Closes the loop:
 * twin fires (webhook) → core processes → twin confirms `state=sold` HERE, no psql, no BACKEND.
 *
 * EPCs come from `M8TRX_VERIFY_EPCS` (csv) or, absent that, the tail of the sold-log
 * (`M8TRX_STREAM_SOLD_LOG`, default `.twin-state/sold-epcs.txt`); `M8TRX_VERIFY_N` caps how many
 * (default 20). READ-ONLY — fires nothing. Run: `./gradlew connectSelfVerify`.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ConnectSelfVerify")

fun main() {
    val client = ConnectClient(ConnectConfig.fromEnv())
    val epcs = verifyEpcs()
    if (epcs.isEmpty()) {
        log.warn("no EPCs to verify — set M8TRX_VERIFY_EPCS, or run a sale stream first to populate the sold-log")
        return
    }
    log.info("Self-verify → {} EPC(s) via /inventory/items/details", epcs.size)

    when (val resp = client.itemDetails(ItemDetailsRequest(epcs))) {
        is ConnectResponse.Ok -> {
            val details = client.mapper.readValue<List<ItemDetail>>(resp.rawBody)
            details.forEach { log.info("  {} → state={} item={} sku={}", it.epc, it.state, it.itemId, it.sku) }
            val byState = details.groupingBy { it.state ?: "unknown" }.eachCount()
            val missing = epcs.size - details.size
            val miss = if (missing > 0) " ($missing not found)" else ""
            log.info(
                "Self-verify done — {}/{} sold; states={}{}",
                byState["sold"] ?: 0,
                details.size,
                byState.toSortedMap(),
                miss,
            )
        }

        is ConnectResponse.Err ->
            log.error("Self-verify failed — HTTP {} {} → {}", resp.error.status, resp.error.code, resp.error.rawBody)
    }
}

/** EPCs to verify: explicit env list, else the tail of the sold-log. */
private fun verifyEpcs(): List<String> {
    val explicit = System.getenv("M8TRX_VERIFY_EPCS")?.split(",").orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    if (explicit.isNotEmpty()) return explicit
    val n = System.getenv("M8TRX_VERIFY_N")?.toIntOrNull() ?: 20
    val soldLog = Path.of(System.getenv("M8TRX_STREAM_SOLD_LOG") ?: ".twin-state/sold-epcs.txt")
    if (!Files.exists(soldLog)) return emptyList()
    return Files.readAllLines(soldLog).map { it.trim() }.filter { it.isNotEmpty() }.takeLast(n)
}
