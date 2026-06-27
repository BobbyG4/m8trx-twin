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
 * `sale_event` — one-of attribution path: [epcList] OR [epc] OR ([sku] + [quantity]). Always
 * carries the receipt id + site + time. Use the factory helpers to build a well-formed one-of.
 */
data class SaleEvent(
    val externalSaleId: String,
    val siteId: String,
    val occurredAt: String,
    val epcList: List<String>? = null,
    val epc: String? = null,
    val sku: String? = null,
    val quantity: Int? = null,
) {
    companion object {
        fun byEpcList(externalSaleId: String, siteId: String, occurredAt: String, epcs: List<String>) =
            SaleEvent(externalSaleId, siteId, occurredAt, epcList = epcs)

        fun byEpc(externalSaleId: String, siteId: String, occurredAt: String, epc: String) = SaleEvent(externalSaleId, siteId, occurredAt, epc = epc)

        fun bySku(externalSaleId: String, siteId: String, occurredAt: String, sku: String, quantity: Int) =
            SaleEvent(externalSaleId, siteId, occurredAt, sku = sku, quantity = quantity)
    }
}

/** `product_catalog` — requires [sku] + [name]; any extra keys land in `product.metadata` server-side. */
data class ProductCatalogItem(val sku: String, val name: String)

/** `shipment_manifest` — [externalShipmentId] + [destinationSiteId] + [items]. */
data class ShipmentManifest(val externalShipmentId: String, val destinationSiteId: String, val items: List<ShipmentLine>)

data class ShipmentLine(val sku: String, val expectedQuantity: Int)

/** `pricing_update` — [sku] + [priceMinor] (integer minor units). */
data class PricingUpdate(val sku: String, val priceMinor: Long)
