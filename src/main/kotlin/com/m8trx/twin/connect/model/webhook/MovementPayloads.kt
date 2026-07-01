package com.m8trx.twin.connect.model.webhook

/**
 * Inventory-movement ingest — AS-PROPOSED (services S178, Backend's directional contract for the
 * remediation demo). Treat as directional, NOT confirmed-deployed — mirrors [PlanogramDirective]'s
 * "AS-BUILT supersedes the doc sketch" lesson in reverse: adapt this DTO the instant Backend finalizes
 * the real ingest shape.
 *
 * Routing: POST to the existing inbound webhook `/v1/webhook/{tenant}/twin-pos` with header
 * `X-Data-Type: inventory_movement` ([com.m8trx.twin.connect.WebhookDataType.INVENTORY_MOVEMENT]) —
 * rides the same webhook plane + X-API-Key auth as `sale_event` / `planogram_directive`. Serialized
 * snake_case via [com.m8trx.twin.connect.model.ConnectMappers.snake].
 *
 * Semantics: a `relocation` updates `thing_location.zone_id` for each resolved item — the item stays
 * `in_stock` (this is NOT a sale, NOT a stock adjustment). [toFixtureCode] resolves server-side via
 * `fixture_code_mapping`; OR supply [toZoneId] directly (the real zone UUID) to skip that resolution.
 * `movement_type=transfer` (site-to-site) is a LATER slice — not built here.
 *
 * Wire (snake): `external_movement_id`, `site_id`, `to_fixture_code`, `to_zone_id`, `items[]`,
 * `movement_type`.
 */
data class InventoryMovement(
    val externalMovementId: String, // idempotency key
    val siteId: String? = null, // optional — omit for a single-site tenant
    val toFixtureCode: String? = null, // resolves via fixture_code_mapping; OR supply toZoneId directly
    val toZoneId: String? = null,
    val items: List<MovementItem>,
    val movementType: String = "relocation", // default; "transfer" = site->site (later)
)

/**
 * One relocated item. [epc] is the preferred attribution path (a SPECIFIC item — [quantity] implicitly
 * 1); [sku] is an alternate identifier for a non-EPC path. Unknown-key tolerant like the directive DTOs.
 */
data class MovementItem(val epc: String? = null, val quantity: Int? = 1, val sku: String? = null)
