package com.m8trx.twin.connect.model.webhook

/*
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
 *
 * [priceMinor] + [currency] (Connect §8, mig 311, CORE-REQ-007): what **one unit** sold for, net of discount, in the
 * ISO 4217 minor unit of [currency]. The price applies to every item the event resolves, so a receipt with several
 * prices goes as one event per price under the same [externalSaleId]. Absent, the platform prices the sale from its
 * history or catalog (`price_source` `history` / `catalog_now`), never `till`. Set both through [priced].
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
    val priceMinor: Long? = null,
    val currency: String? = null,
) {
    /** This sale at one unit's [minor] price in [ccy] (the catalog's, USD cents, unless said); null leaves it unpriced. */
    fun priced(minor: Long?, ccy: String = CATALOG_CURRENCY): SaleEvent = if (minor == null) this else copy(priceMinor = minor, currency = ccy)

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

/**
 * `product_catalog` — one product per event.
 *
 * [gtin] carries the EAN-13, which **is** a GTIN-13, and is the field that actually identifies a sellable
 * unit in this catalog. [sku] is Decathlon's `item_cd`, a supplier **style/model code** — two sizes of one
 * shoe share it (see `5391035`). Sending `sku` alone is what makes a catalog delivery update *both* product
 * rows on the platform, so the two are sent together and a consumer may key on whichever it trusts.
 */
data class ProductCatalogItem(val sku: String, val name: String, val gtin: String? = null)

/** `shipment_manifest` — [externalShipmentId] + [destinationSiteId] + [items]. */
data class ShipmentManifest(val externalShipmentId: String, val destinationSiteId: String, val items: List<ShipmentLine>)

data class ShipmentLine(val sku: String, val expectedQuantity: Int)

/**
 * `pricing_update` — [sku] + [priceMinor] (integer minor units of [currency]), plus [gtin].
 *
 * The `gtin` matters more here than anywhere: `5391035`'s two sizes are priced **$80 and $109**, so a
 * pricing delivery keyed on the style code alone does not merely update two rows, it writes one variant's
 * price onto the other.
 *
 * [currency] goes on **every** update (Connect §8, 2026-11-06): with it the platform reads [priceMinor] by that
 * currency's ISO 4217 exponent and refuses a mismatch with the product's currency; without it the number is read
 * as US cents, which is how the won-as-cents prices of CORE-REQ-007 got in. It is not optional on the twin's side:
 * the default is USD, but the churn always sets the tenant's base currency itself (`BaseCatalog`, EUR for Decathlon,
 * CORE-REQ-008). No `price_zone` is sent until the core's zones round ships.
 */
data class PricingUpdate(val sku: String, val priceMinor: Long, val gtin: String? = null, val currency: String = CATALOG_CURRENCY)

/** The currency every product row on the platform carries, and the unit every `price_minor` the twin sends is in. */
const val CATALOG_CURRENCY = "USD"
