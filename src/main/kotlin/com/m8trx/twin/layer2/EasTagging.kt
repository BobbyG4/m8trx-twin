package com.m8trx.twin.layer2

import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.layer1.Pt
import java.nio.file.Files
import java.nio.file.Path

/**
 * Which stock carries an EAS tag, and where the gate is.
 *
 * ## ⚠ THIS IS TWIN-SIDE STATE. THE PLATFORM DOES NOT KNOW IT.
 *
 * EAS tag state belongs to the **vendor**, not to M8TRX. A real third-party EAS system decides which items
 * it tagged and the platform never sees that decision — which is precisely why twin minting it is **faithful
 * emulation rather than fabrication.** The condition on that is absolute: it must be **declared as twin's
 * own**, and never implied to be something the platform stores, derives or could verify. Nothing in
 * `assortment.csv` carries a tag column, no M8TRX table has one, and none of this is sent to core as fact —
 * it only shapes which EPC a [Journeys] shoplift arc carries out through the gate.
 *
 * ## The rule, and why it is a rule rather than a list
 *
 * ```
 *   EAS-tagged  ⟺  price_usd >= 150  AND  category != "outdoor"
 * ```
 *
 * **Derivable, not inherited.** `CLAUDE.md` and `STATUS.md` named *"W-series sports watches ($29.99–$89.99),
 * EAS-tagged, 40 items"* as the LP anchor for months. **The live 2,586-SKU chain catalog contains ZERO watch
 * SKUs** — that anchor belongs to the superseded 920-SKU Manhattan concept, and Session 8's static-seed audit
 * flagged it and it was never closed. A literal list would rot the same way the next time the catalog is
 * rebuilt; a predicate re-derives itself.
 *
 * **Why these two clauses:**
 *  - **`price >= 150`** — retailers tag what is worth tagging. Denver's median assortment price is well under
 *    this, so the floor selects genuinely premium stock rather than most of the shop.
 *  - **`category != "outdoor"`** — the other half of real EAS practice is **concealability**, and this single
 *    exclusion captures it exactly in this catalog: every bulky line lives in `outdoor` (measured — road
 *    bikes 83, gravel 9, mountain 3, tents 5, tent poles 1, home trainers 3). Nobody pockets a road bike.
 *
 * **Yield on Denver: 271 SKUs** — 166 footwear, 93 apparel, 8 bag_pack, 4 accessories; zero bulky items
 * wrongly included. Concentrated on `PE-02`/`PW-02` (Kiprun premium footwear), which is the natural LP
 * anchor and matches the *intent* of the old doc line — `CLAUDE.md` already named "Kiprun premium shoes"
 * alongside the watches that do not exist.
 */
object EasTagging {

    /** Price floor for a tag. Denver's median assortment price is far below this, so it selects, not sweeps. */
    const val PRICE_FLOOR_USD = 150.0

    /**
     * Categories excluded as unconcealable. In this catalog `outdoor` is exactly the bulky set — verified by
     * enumerating every bike/tent/trainer `product_type` and finding all of them in it, and by confirming the
     * rule admits none of them.
     */
    val UNCONCEALABLE_CATEGORIES = setOf("outdoor")

    /** Would a vendor have tagged this? Pure, so a test can assert it without a store. */
    fun isTagged(priceUsd: Double, category: String): Boolean = priceUsd >= PRICE_FLOOR_USD && category.lowercase() !in UNCONCEALABLE_CATEGORIES

    fun isTagged(sku: StoreCatalog.Sku): Boolean = isTagged(sku.priceUsd, sku.category)

    /**
     * The EAS gate, read from the store's own layout rather than assumed.
     *
     * ⚠ **I nearly reported that twin had no EAS substrate at all.** A first pass scanned `zones` only, found
     * nothing, and would have filed "twin has no EAS gates" — which would have argued against building this.
     * The substrate is real and lives in `crossing_slices` and `sensors`, which a zone scan never touches.
     * Partial-listing errors of exactly this shape bit three people on 2026-07-31. **Look where the data is,
     * not where you expect it.**
     */
    data class Gate(val code: String, val name: String, val zoneCode: String, val y: Double, val xStart: Double, val xEnd: Double) {
        /** A point on the line, [frac] of the way along it — where a shopper actually walks through. */
        fun crossingPoint(frac: Double) = Pt(xStart + (xEnd - xStart) * frac.coerceIn(0.0, 1.0), y)
    }

    /**
     * Load the `eas_gate` crossing slices for a space. Present in **all 10 stores** as `CS-01`
     * "Main Entrance Gate" (`y=600`, `x 6800→18200` in Denver's Sales Floor SRF).
     *
     * ⚠ Note the irony worth carrying: this geometry sits on **`crossing_slices`** — the same v1
     * `crossing`/`sliceId` concept S15 descoped as having no v2 consumer. Fine for twin's own emission (twin
     * needs the line to know where the shopper walks), but it means the gate is modelled on a structure the
     * platform does not read. `Z-01`'s zone UUID is the only gate-adjacent identifier that resolves
     * platform-side today, which is why the §8.1 alarm envelope places alarms with `zone_ref`.
     */
    fun loadGates(layoutJson: Path, spaceType: String = "sales_floor"): List<Gate> {
        val root = ConnectMappers.camel.readTree(Files.readAllBytes(layoutJson))
        val space = root["spaces"]?.firstOrNull { it["space_type"]?.asText() == spaceType || it["spaceType"]?.asText() == spaceType }
            ?: root["spaces"]?.firstOrNull()
            ?: return emptyList()
        return space["crossing_slices"].orEmpty()
            .filter { it["eas_gate"]?.asBoolean() == true }
            .map {
                Gate(
                    code = it["code"].asText(),
                    name = it["name"]?.asText() ?: it["code"].asText(),
                    zoneCode = it["zone_code"]?.asText() ?: "",
                    y = it["y"].asDouble(),
                    xStart = it["x_start"].asDouble(),
                    xEnd = it["x_end"].asDouble(),
                )
            }
    }

    private fun com.fasterxml.jackson.databind.JsonNode?.orEmpty(): List<com.fasterxml.jackson.databind.JsonNode> = this?.toList() ?: emptyList()
}
