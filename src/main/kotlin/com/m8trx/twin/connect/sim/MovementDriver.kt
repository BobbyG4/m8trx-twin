package com.m8trx.twin.connect.sim

import com.m8trx.twin.connect.WebhookClient
import com.m8trx.twin.connect.WebhookDataType
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.webhook.InventoryMovement
import com.m8trx.twin.connect.model.webhook.MovementItem
import org.slf4j.LoggerFactory

/**
 * Inventory-movement driver (remediation demo) — built to Backend's AS-PROPOSED contract (services
 * S178). Moves stock BACK onto a compliance fixture (backroom/other-gondola EPCs → the target fixture)
 * so a drifted `compliance_target` can climb non_compliant → partial → compliant.
 *
 * Builds an [InventoryMovement] from a from-location EPC pool + a destination fixture code, and POSTs it
 * to `/v1/webhook/{tenant}/twin-pos` with `X-Data-Type: inventory_movement`. Lands as a
 * `thing_location.zone_id` update per item (item stays `in_stock`) — NOT a sale, NOT a stock adjustment.
 *
 * Ingester LIVE since services #71→#72 (2026-07-01) and live-proven in S13. [dryRun] remains the offline
 * assertion path; [drive] mutates real `thing_location` rows, so callers opt in explicitly.
 */
class MovementDriver(private val webhook: WebhookClient) {
    private val log = LoggerFactory.getLogger(MovementDriver::class.java)

    /**
     * Build an [InventoryMovement] relocating [epcs] to [toFixtureCode] (resolved server-side via
     * `fixture_code_mapping`). EPC paths imply quantity=1 per item; [siteId] is optional (single-site
     * tenant omits it).
     */
    fun build(
        toFixtureCode: String,
        epcs: List<String>,
        movementType: String = "relocation",
        externalMovementId: String,
        siteId: String? = null,
    ): InventoryMovement = InventoryMovement(
        externalMovementId = externalMovementId,
        siteId = siteId,
        toFixtureCode = toFixtureCode,
        items = epcs.map { MovementItem(epc = it) },
        movementType = movementType,
    )

    /** Serialize the movement WITHOUT sending — the offline assertion path + the dry-run log shape. */
    fun dryRun(mv: InventoryMovement): String = ConnectMappers.snake.writeValueAsString(mv)

    /** LIVE: POST at `X-Data-Type: inventory_movement`. Mutates real `thing_location` — opt in explicitly. */
    fun drive(mv: InventoryMovement, auth: WebhookClient.AuthMode = WebhookClient.AuthMode.API_KEY): ConnectResponse {
        val resp = webhook.push(WebhookDataType.INVENTORY_MOVEMENT, mv, auth)
        when (resp) {
            is ConnectResponse.Ok ->
                log.info(
                    "driveMovement extId={} toFixture={} items={} ack status={}",
                    mv.externalMovementId,
                    mv.toFixtureCode,
                    mv.items.size,
                    resp.status,
                )
            is ConnectResponse.Err ->
                log.error(
                    "driveMovement extId={} toFixture={} failed status={} code={} message={}",
                    mv.externalMovementId,
                    mv.toFixtureCode,
                    resp.error.status,
                    resp.error.code,
                    resp.error.message,
                )
        }
        return resp
    }
}
