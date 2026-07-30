package com.m8trx.twin.connect

import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.sim.MovementDriver
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Inventory-movement drive (remediation demo) — Backend's AS-PROPOSED contract (services S178). Loads a
 * from-location EPC list (backroom/other-gondola stock for the planogram SKUs sold in the compliance-drift
 * demo), builds a `relocation` [com.m8trx.twin.connect.model.webhook.InventoryMovement] to the target
 * fixture, and POSTs at `X-Data-Type: inventory_movement` on the existing twin-pos webhook.
 *
 * SAFE BY DEFAULT — dry-run (builds + logs the movement envelope, sends nothing).
 * `M8TRX_MOVEMENT_LIVE=true` fires.
 *
 * INGESTER LIVE (services #71→#72, deployed 2026-07-01). LIVE-PROVEN in S13: twin drove real relocations
 * that recovered compliance targets (#6 `2→3` compliant, #2 `0→2`; 24/2/2 → 25/2/1) — the first live
 * runtime `thing_location` writes. Dry-run stays the default because this mutates real inventory location,
 * not because the surface is unproven.
 *
 * Env: M8TRX_MOVEMENT_EPC_FILE (a from-location EPC list, one EPC per line — no header),
 * M8TRX_MOVEMENT_TO_FIXTURE (default GB-R3-U1), M8TRX_MOVEMENT_TYPE (default relocation),
 * M8TRX_MOVEMENT_EXT_ID (override external_movement_id), M8TRX_MOVEMENT_SITE_ID (optional),
 * M8TRX_MOVEMENT_AUTH (hmac|api_key, default api_key), M8TRX_MOVEMENT_LIVE (false).
 * Run: `./gradlew connectMovementDrive`.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ConnectMovementDrive")

fun main() {
    val config = ConnectConfig.fromEnv()
    val driver = MovementDriver(WebhookClient(config))
    val live = System.getenv("M8TRX_MOVEMENT_LIVE")?.toBooleanStrictOrNull() ?: false
    val epcFile = Path.of(env("M8TRX_MOVEMENT_EPC_FILE", "reference/data/chain/stores/dec-us-denver/movement-epcs.txt"))
    val toFixtureCode = env("M8TRX_MOVEMENT_TO_FIXTURE", "GB-R3-U1")
    val movementType = env("M8TRX_MOVEMENT_TYPE", "relocation")
    val siteId = System.getenv("M8TRX_MOVEMENT_SITE_ID")?.takeIf { it.isNotBlank() }
    val extId = env("M8TRX_MOVEMENT_EXT_ID", "twin-movement-${System.currentTimeMillis()}")
    val auth = if (env("M8TRX_MOVEMENT_AUTH", "api_key").equals("hmac", ignoreCase = true)) {
        WebhookClient.AuthMode.HMAC
    } else {
        WebhookClient.AuthMode.API_KEY
    }

    log.info(
        "Movement drive → toFixture={} type={} auth={} live={} epcFile={}",
        toFixtureCode,
        movementType,
        auth,
        live,
        epcFile,
    )

    if (!Files.exists(epcFile)) {
        error("no from-location EPC file at $epcFile — stage one EPC per line (see reference/data/chain staging)")
    }
    val epcs = Files.readAllLines(epcFile).map { it.trim() }.filter { it.isNotEmpty() }
    if (epcs.isEmpty()) {
        error("$epcFile contained no EPCs — nothing to relocate")
    }

    val movement = driver.build(
        toFixtureCode = toFixtureCode,
        epcs = epcs,
        movementType = movementType,
        externalMovementId = extId,
        siteId = siteId,
    )

    if (live) {
        when (val resp = driver.drive(movement, auth)) {
            is ConnectResponse.Ok -> log.info("Movement drive done — 1 ok / 0 err LIVE")
            is ConnectResponse.Err -> error("movement drive failed — status=${resp.error.status} code=${resp.error.code}")
        }
    } else {
        val json = driver.dryRun(movement)
        log.info(
            "[DRY] toFixture={} extId={} items={} bytes={}",
            toFixtureCode,
            movement.externalMovementId,
            movement.items.size,
            json.toByteArray().size,
        )
        log.info("[DRY] envelope={}", json)
        log.info("Movement drive done — 1 ok / 0 err DRY-RUN")
    }
}

private fun env(key: String, default: String) = System.getenv(key)?.takeIf { it.isNotBlank() } ?: default
