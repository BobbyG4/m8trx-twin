package com.m8trx.twin.connect

import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.bearer.ItemReceiveRequest
import com.m8trx.twin.connect.model.bearer.ReceiveItem
import com.m8trx.twin.connect.sim.DeviceDriver
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Item-receive drive (compliance-remediation demo, #7 receive→relocate) — fires the existing
 * [DeviceDriver.receive] (Bearer data-plane, `POST /api/v2/inventory/items/receive`, Connect API
 * doc §6). Loads an EPC list, builds an [ItemReceiveRequest], and receives them into inventory at
 * the target site/space.
 *
 * SAFE BY DEFAULT — dry-run (builds + logs the request envelope, sends nothing).
 * `M8TRX_RECEIVE_LIVE=true` fires.
 * !! HOLD until you have a real Denver space UUID + Bearer — receive CREATES inventory server-side. !!
 *
 * Env: M8TRX_RECEIVE_SITE_ID (required), M8TRX_RECEIVE_SPACE_ID (required),
 * M8TRX_RECEIVE_EPC_FILE (default reference/data/chain/stores/dec-us-denver/receive-epcs.txt),
 * M8TRX_RECEIVE_LIVE (false), M8TRX_RECEIVE_RUN_ID (optional correlation tag).
 * Run: `./gradlew connectReceiveDrive`.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ConnectReceiveDrive")

fun main() {
    val config = ConnectConfig.fromEnv()
    val live = System.getenv("M8TRX_RECEIVE_LIVE")?.toBooleanStrictOrNull() ?: false
    val siteId = System.getenv("M8TRX_RECEIVE_SITE_ID")?.takeIf { it.isNotBlank() }
        ?: error("M8TRX_RECEIVE_SITE_ID is not set — the target site UUID to receive into")
    val spaceId = System.getenv("M8TRX_RECEIVE_SPACE_ID")?.takeIf { it.isNotBlank() }
        ?: error("M8TRX_RECEIVE_SPACE_ID is not set — the target space UUID to receive into")
    val epcFile = Path.of(env("M8TRX_RECEIVE_EPC_FILE", "reference/data/chain/stores/dec-us-denver/receive-epcs.txt"))
    val runId = System.getenv("M8TRX_RECEIVE_RUN_ID")?.takeIf { it.isNotBlank() }

    log.info("Receive drive → siteId={} spaceId={} live={} epcFile={}", siteId, spaceId, live, epcFile)

    if (!Files.exists(epcFile)) {
        error("no EPC file at $epcFile — stage one EPC per line (see reference/data/chain staging)")
    }
    val epcs = Files.readAllLines(epcFile).map { it.trim() }.filter { it.isNotEmpty() }
    if (epcs.isEmpty()) {
        error("$epcFile contained no EPCs — nothing to receive")
    }

    val req = ItemReceiveRequest(siteId = siteId, spaceId = spaceId, items = epcs.map { ReceiveItem(it) })

    if (live) {
        val driver = DeviceDriver(ConnectClient(config))
        when (val resp = driver.receive(siteId, spaceId, epcs, runId)) {
            is ConnectResponse.Ok -> log.info("Receive drive done — 1 ok / 0 err LIVE")
            is ConnectResponse.Err -> error("receive drive failed — status=${resp.error.status} code=${resp.error.code}")
        }
    } else {
        val json = ConnectMappers.camel.writeValueAsString(req)
        log.info("[DRY] siteId={} spaceId={} items={} bytes={}", siteId, spaceId, epcs.size, json.toByteArray().size)
        log.info("[DRY] request={}", json)
        log.info("Receive drive done — 1 ok / 0 err DRY-RUN")
    }
}

private fun env(key: String, default: String) = System.getenv(key)?.takeIf { it.isNotBlank() } ?: default
