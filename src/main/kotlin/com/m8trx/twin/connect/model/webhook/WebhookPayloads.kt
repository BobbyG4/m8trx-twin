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
 * `sale_event` — one-of attribution path: [epcList] OR [epc] OR ([siteId] + [sku] + [quantity]) OR
 * ([storeId] + [storeName] + [sku] + [quantity]). Always carries the receipt id + time.
 *  - EPC paths attribute a SPECIFIC item directly — no location field needed (Connect §8).
 *  - The SKU path resolves N items by recency at a known M8TRX [siteId].
 *  - The external-store path carries the source system's OWN [storeId]/[storeName]; Connect resolves
 *    it to an M8TRX site via `integration_site_xref`. An UNMAPPED store_id quarantines
 *    (severity=error) and creates an unmapped xref row for the cockpit's Site Map → Unmapped queue.
 * Use the factory helpers to build a well-formed one-of.
 */
data class SaleEvent(
    val externalSaleId: String,
    val occurredAt: String,
    val siteId: String? = null,
    val storeId: String? = null,
    val storeName: String? = null,
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

        // SKU path resolves N items by recency at a known M8TRX site — requires site_id.
        fun bySku(externalSaleId: String, occurredAt: String, siteId: String, sku: String, quantity: Int) =
            SaleEvent(externalSaleId = externalSaleId, occurredAt = occurredAt, siteId = siteId, sku = sku, quantity = quantity)

        // External-store path (Connect multi-site): the source system's own store identifier,
        // resolved to an M8TRX site via integration_site_xref. site_id absent — an UNMAPPED store_id
        // quarantines (severity=error) + surfaces in the cockpit Site Map → Unmapped queue.
        fun byStore(externalSaleId: String, occurredAt: String, storeId: String, storeName: String, sku: String, quantity: Int) = SaleEvent(
            externalSaleId = externalSaleId,
            occurredAt = occurredAt,
            storeId = storeId,
            storeName = storeName,
            sku = sku,
            quantity = quantity,
        )
    }
}

/** `product_catalog` — requires [sku] + [name]; any extra keys land in `product.metadata` server-side. */
data class ProductCatalogItem(val sku: String, val name: String)

/** `shipment_manifest` — [externalShipmentId] + [destinationSiteId] + [items]. */
data class ShipmentManifest(val externalShipmentId: String, val destinationSiteId: String, val items: List<ShipmentLine>)

data class ShipmentLine(val sku: String, val expectedQuantity: Int)

/** `pricing_update` — [sku] + [priceMinor] (integer minor units). */
data class PricingUpdate(val sku: String, val priceMinor: Long)
