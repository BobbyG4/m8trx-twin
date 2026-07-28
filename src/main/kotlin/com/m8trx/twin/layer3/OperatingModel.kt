package com.m8trx.twin.layer3

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.m8trx.twin.connect.model.ConnectMappers
import java.nio.file.Files
import java.nio.file.Path

/**
 * Typed view of `reference/data/store-operating-model.json` — the single realism source every Layer-3
 * generator reads. Parameter sheet and the §1 reconciliation identity live in `STORE-OPERATING-MODEL.md`.
 *
 * Built to `TRAFFIC-GENERATOR-SKETCH.md` §1, with two corrections the sketch could not have known:
 *
 *  1. **§8 zone affinity is NOT read from this file.** Its keys are literal codes (`Z-04_main_sales_floor`,
 *     `Z-10_fitting_rooms`) from the superseded single-store model, and neither zone exists in the seeded
 *     chain. Affinity now comes from [com.m8trx.twin.layer1.ZoneAffinityModel], re-keyed off structure.
 *  2. **Confidence-tagged values are `{value, range, conf}`** for some fields and bare scalars for others
 *     (`visitors_per_day`, `hourly_curve`, `category_*_mix`). [Tagged] reads only `.value`; bare fields are
 *     mapped directly.
 *
 * Only what the generators consume is parsed; doc-facing `_note`/`_meta` fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OperatingModel(
    val store: Store,
    val traffic: Traffic,
    val conversion: Conversion,
    val basket: Basket,
    @JsonProperty("persona_mix") val personaMix: PersonaMix,
    val dwell: Dwell,
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Store(@JsonProperty("operating_hours") val operatingHours: Hours, val currency: String = "USD")

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Hours(val open: String, val close: String, @JsonProperty("open_hours") val openHours: Int)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Traffic(
        @JsonProperty("visitors_per_day") val visitorsPerDay: VisitorsPerDay,
        @JsonProperty("hourly_curve") val hourlyCurve: HourlyCurve,
        @JsonProperty("seasonality_factor_range") val seasonalityRange: List<Double> = listOf(0.75, 1.25),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class VisitorsPerDay(val weekday: Int, val weekend: Int)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class HourlyCurve(val weekday: List<Double>, val weekend: List<Double>)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Conversion(
        @JsonProperty("overall_rate") val overallRate: Tagged<Double>,
        @JsonProperty("try_on_engagement_rate") val tryOnEngagementRate: Tagged<Double>,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Basket(
        @JsonProperty("atv_usd") val atvUsd: Tagged<Double>,
        @JsonProperty("units_per_txn") val unitsPerTxn: Tagged<Double>,
        @JsonProperty("avg_line_price_usd") val avgLinePriceUsd: Tagged<Double>,
        // Deliberately Map<String, Any>: the JSON mix blocks carry a documentation `_note` STRING alongside
        // the numeric weights, so a Map<String, Double> fails to deserialize before any filter can run.
        @JsonProperty("category_unit_mix") val categoryUnitMix: Map<String, Any> = emptyMap(),
    ) {
        /** Numeric weights only — drops `_note` and anything else non-numeric. */
        val cleanUnitMix: Map<String, Double>
            get() = categoryUnitMix
                .filterKeys { !it.startsWith("_") }
                .mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toDouble() } }
                .toMap()
    }

    /** Session-share by behaviour. Keys are journey impl slugs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PersonaMix(
        @JsonProperty("browse_and_leave") val browseAndLeave: Double,
        @JsonProperty("shop_and_buy") val shopAndBuy: Double,
        @JsonProperty("try_on_and_partial_buy") val tryOnAndPartialBuy: Double,
        @JsonProperty("shoplift_baseline") val shopliftBaseline: Double = 0.0,
    ) {
        /** Renormalised so the weights sum to exactly 1.0. */
        fun journeyWeights(): Map<String, Double> {
            val raw = mapOf(
                "BrowseAndLeave" to browseAndLeave,
                "ShopAndBuy" to shopAndBuy,
                "TryOnAndPartialBuy" to tryOnAndPartialBuy,
                "Shoplift" to shopliftBaseline,
            ).filterValues { it > 0.0 }
            val sum = raw.values.sum()
            return raw.mapValues { it.value / sum }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Dwell(
        @JsonProperty("total_median_min") val totalMedianMin: Tagged<Int>,
        @JsonProperty("zones_visited_per_session") val zonesVisitedPerSession: Tagged<Int>,
        @JsonProperty("by_zone_min") val byZoneMin: Map<String, Double> = emptyMap(),
    )

    companion object {
        fun load(path: Path): OperatingModel = ConnectMappers.camel.readValue(Files.readAllBytes(path), OperatingModel::class.java)
    }
}

/** The `{ value, range, conf }` shape; range/conf are for humans tuning the doc. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Tagged<T>(val value: T)
