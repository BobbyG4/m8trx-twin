package com.m8trx.twin.connect.model.webhook

/**
 * Inbound webhook ingester payloads — Connect API doc §8, serialized snake_case via
 * [com.m8trx.twin.connect.model.ConnectMappers.snake]. These are the canonical shapes the
 * ingester reads AFTER any per-channel field map is applied; twin's MVP sends them canonical.
 *
 * Kotlin property names are camelCase (ktlint-clean); the snake mapper renders the wire form
 * (`externalSaleId` → `external_sale_id`, `epcList` → `epc_list`, …).
 *
 * Built to the code-verified Connect doc, which SUPERSEDES the older shapes in
 * `reference/integration/M8TRX-API-SURFACE.md` rows #22/#24 (nested `line_items[]` / `manifest_id`).
 */

/**
 * `sale_event` — one-of attribution path: [epcList] OR [epc] OR ([sku] + [quantity]). Always carries
 * the receipt id + time. [siteId] is required ONLY on the SKU path (the EPC paths attribute a
 * specific item directly, Connect §8); use the factory helpers to build a well-formed one-of.
 */
data class SaleEvent(
    val externalSaleId: String,
    val occurredAt: String,
    val siteId: String? = null,
    val epcList: List<String>? = null,
    val epc: String? = null,
    val sku: String? = null,
    val quantity: Int? = null,
) {
    companion object {
        // EPC paths attribute a SPECIFIC item — no site_id needed.
        fun byEpcList(externalSaleId: String, occurredAt: String, epcs: List<String>) =
            SaleEvent(externalSaleId = externalSaleId, occurredAt = occurredAt, epcList = epcs)

        fun byEpc(externalSaleId: String, occurredAt: String, epc: String) =
            SaleEvent(externalSaleId = externalSaleId, occurredAt = occurredAt, epc = epc)

        // SKU path resolves N items by recency — requires site_id.
        fun bySku(externalSaleId: String, occurredAt: String, siteId: String, sku: String, quantity: Int) =
            SaleEvent(externalSaleId = externalSaleId, occurredAt = occurredAt, siteId = siteId, sku = sku, quantity = quantity)
    }
}

/** `product_catalog` — requires [sku] + [name]; any extra keys land in `product.metadata` server-side. */
data class ProductCatalogItem(val sku: String, val name: String)

/** `shipment_manifest` — [externalShipmentId] + [destinationSiteId] + [items]. */
data class ShipmentManifest(val externalShipmentId: String, val destinationSiteId: String, val items: List<ShipmentLine>)

data class ShipmentLine(val sku: String, val expectedQuantity: Int)

/** `pricing_update` — [sku] + [priceMinor] (integer minor units). */
data class PricingUpdate(val sku: String, val priceMinor: Long)
