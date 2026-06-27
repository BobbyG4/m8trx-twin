package com.m8trx.twin.connect.model

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * One mapper per casing convention — the single most effective guard against the silent
 * wire-bug the two-surface split invites (Connect API doc §2 "Field-naming boundary").
 *
 *   - [camel] — the Bearer plane (§6 data + §7 control) AND the §9 outbound `stocktake_result`,
 *               all of which are camelCase. The handful of snake_case control-plane fields
 *               (`integration_type`, `data_type`, `endpoint_config`, `notify_user_ids`, …) carry
 *               an explicit `@JsonProperty` on their DTO, so this one mapper serves the whole plane.
 *   - [snake] — ONLY the §8 inbound webhook ingester payloads (`sale_event`, `product_catalog`,
 *               `shipment_manifest`, `pricing_update`), which are uniformly snake_case.
 *
 * Both: omit nulls (so the `sale_event` one-of paths drop absent fields) and tolerate unknown
 * keys on the way in (responses carry more than we model).
 */
object ConnectMappers {
    val camel: ObjectMapper = jacksonObjectMapper().configureCommon()

    val snake: ObjectMapper =
        jacksonObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configureCommon()

    private fun ObjectMapper.configureCommon(): ObjectMapper = this
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
}
