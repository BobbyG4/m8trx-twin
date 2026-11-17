package com.m8trx.twin.storeday

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.m8trx.twin.behavior.StoreStock
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.history.ChainKeys
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Slice 2d: the answer key, one record per planted scenario, in the shape of the eval's `twin_scenario_key`
 * (`scenario_id, store_id, planted_from, planted_to, entities jsonb, expected_recommendation text`) plus `archetype`, `store_slug` and
 * `evidence_expected`. A file only: nothing here is ever sent to the product.
 */
object PlantKey {
    /** The eval's own column list: a record must carry all of these. */
    val EVAL_FIELDS = listOf("scenario_id", "store_id", "planted_from", "planted_to", "entities", "expected_recommendation")

    /** Archetypes the eval asks about that this slice does not plant, and why. */
    val NOT_PLANTED =
        mapOf("entrance_promo" to "needs a promotion model: no product table, fixture form or tier says what is on promotion where or when")

    fun siteIds(chainDir: Path = Path.of("reference/data/chain")): Map<String, String> =
        Files.readAllLines(chainDir.resolve("site_ids.csv")).drop(1).filter { it.isNotBlank() }.associate {
            it.substringBefore(',') to it.substringAfter(',').trim()
        }

    class Names(val sim: StoreDaySim) {
        private val keys = ChainKeys.load().store(sim.model.slug)
        private val itemCd: Map<String, String> =
            StoreStock.readCsv(sim.model.modelDir.resolve("demand.csv")).associate { it.getValue("ean") to it.getValue("item_cd") }

        fun zone(code: String) = keys.byCode[code] ?: error("${sim.model.slug}: no zone for fixture $code")

        fun item(ean: String) = itemCd[ean] ?: error("${sim.model.slug}: no item_cd for $ean")

        fun dept(key: String) = keys.departments.first { it.department == key }

        fun label(ean: String): String = "${item(ean)} (${sim.model.demand[ean]?.category} ${sim.model.demand[ean]?.priceUsd} USD)"
    }

    private fun ObjectNode.arr(name: String): ArrayNode = putArray(name)

    fun record(sim: StoreDaySim, sc: PlantScenario, plan: PeriodPlan, siteId: String, from: LocalDate, to: LocalDate): ObjectNode {
        val n = Names(sim)
        val m = ConnectMappers.snake
        val rec = m.createObjectNode()
        rec.put("scenario_id", sc.id)
        rec.put("archetype", sc.archetype)
        rec.put("store_id", siteId)
        rec.put("store_slug", sim.model.slug)
        rec.put("planted_from", from.toString())
        rec.put("planted_to", to.toString())
        val ent = rec.putObject("entities")
        val zoneIds = linkedSetOf<String>()
        val zoneNames = linkedSetOf<String>()
        val eans = linkedSetOf<String>()
        val roles = ent.putObject("roles")

        fun fixtureEntry(arr: ArrayNode, code: String) {
            val z = n.zone(code)
            zoneIds += z.zoneId
            zoneNames += z.name
            arr.addObject().apply {
                put("kind", "fixture")
                put("fixture_code", code)
                put("zone_id", z.zoneId)
                put("zone_name", z.name)
            }
        }

        fun productEntry(arr: ArrayNode, ean: String) {
            eans += ean
            val home = plan.lines.filter { it.ean == ean }.map { it.fixture }.sorted().first()
            val z = n.zone(home)
            zoneIds += z.zoneId
            zoneNames += z.name
            arr.addObject().apply {
                put("kind", "product")
                put("ean", ean)
                put("item_cd", n.item(ean))
                put("department", sim.model.demand[ean]?.department)
                put("category", sim.model.demand[ean]?.category)
                put("price_usd", sim.model.demand[ean]?.priceUsd)
                put("fixture_code", home)
                put("zone_id", z.zoneId)
                put("zone_name", z.name)
            }
        }

        fun deptEntry(arr: ArrayNode, key: String) {
            val z = n.dept(key)
            zoneIds += z.zoneId
            zoneNames += z.name
            arr.addObject().apply {
                put("kind", "department_region")
                put("department", key)
                put("region_code", z.code)
                put("zone_id", z.zoneId)
                put("zone_name", z.name)
            }
        }
        for ((role, values) in sc.roles) {
            val arr = roles.arr(role)
            values.forEach { v ->
                when {
                    sim.model.demand.containsKey(v) -> productEntry(arr, v)
                    sim.behavior.fixtures.fixtures.any { it.code == v } -> fixtureEntry(arr, v)
                    else -> deptEntry(arr, v)
                }
            }
        }
        // the fixtures the levers pull on belong to the pattern too (the quiet and the busy region of a layout, say)
        ent.arr("zone_ids").also { a -> zoneIds.forEach(a::add) }
        ent.arr("zone_names").also { a -> zoneNames.forEach(a::add) }
        ent.arr("product_eans").also { a -> eans.forEach(a::add) }
        ent.arr("item_cds").also { a -> eans.forEach { a.add(n.item(it)) } }
        rec.put("expected_recommendation", recommendation(sc, n, plan))
        val ev = rec.arr("evidence_expected")
        evidence(sc).forEach { ev.add(it) }
        return rec
    }

    private fun recommendation(sc: PlantScenario, n: Names, plan: PeriodPlan): String {
        fun fx(ean: String) = n.zone(plan.lines.filter { it.ean == ean }.map { it.fixture }.sorted().first()).name
        fun zn(code: String) = n.zone(code).name
        return when (sc.archetype) {
            "promote" -> "Promote ${n.label(
                sc.one("fast_quiet"),
            )}: it sells as fast as ${n.label(sc.one("fast_busy"))} but sits in the quiet fixture " +
                "'${fx(
                    sc.one("fast_quiet"),
                )}', so more exposure should lift it. Do not promote ${n.label(sc.one("fast_busy"))}: it already sits in the " +
                "busy fixture '${fx(sc.one("fast_busy"))}'. Do not promote ${n.label(sc.one("low_cover"))}: it has under 5 days of stock. " +
                "${sc.role("lookers").joinToString(" and ") {
                    n.label(it)
                }} are looked at and not bought: not promotion candidates (price or presentation)."

            "layout" -> "Swap the placement of '${n.dept(sc.one("strong_dept")).name}' (strong sellers, in the least-visited region) and " +
                "'${n.dept(sc.one("busy_dept")).name}' (slow sellers, in the most-visited region). A layout answer that moves anything else is wrong."

            "move_fast_seller" -> "Move ${n.label(
                sc.one("product"),
            )} from '${fx(sc.one("product"))}' into '${zn(sc.one("target_fixture"))}' (high visits), " +
                "displacing the slow seller ${n.label(sc.one("displaced"))}."

            "under_seen" -> "'${zn(
                sc.one("fixture"),
            )}' is the under-seen fixture: the largest on-hand value in the store, in the bottom decile of impressions " +
                "and dwell. Any other fixture is wrong."

            "restock_first" -> "Restock ${n.label(
                sc.one("a"),
            )} first (sells fast, stock for under 2 days), then ${n.label(sc.one("b"))} (sells fast, healthy stock). " +
                "Never ${n.label(sc.one("c"))} (sells slowly, nearly out)."

            "quiet_stock" -> "The ${sc.role("products").size} products ${sc.role("products").joinToString(", ") {
                n.label(it)
            }} are stuck: high on-hand, zero sales " +
                "for the whole window, on fixtures with below-median visits while their categories sell elsewhere. Move them to a busier spot or mark them down; do not restock."

            "look_not_buy" -> "Flag '${zn(
                sc.one("stockout_fixture"),
            )}' as 'fix the stock' (top-3 impressions, nothing on the shelf) and '${zn(sc.one("price_fixture"))}' " +
                "as 'price or presentation' (top-3 impressions, premium prices, near-zero sales). The control '${zn(
                    sc.one("control_fixture"),
                )}' has the same attention and normal sales: do not flag it."

            else -> error("no recommendation text for ${sc.archetype}")
        }
    }

    private fun evidence(sc: PlantScenario): List<String> = when (sc.archetype) {
        "promote" -> listOf(
            "sale_line per unit with the floor zone_id: units per day per product (the two promotion candidates equal, the lookers near zero)",
            "behavioral_projection impression per fixture per day: the quiet and the busy fixture",
            "inventory_zone_product_count per fixture per product: the low-cover product",
        )

        "layout" -> listOf(
            "traffic_projection zone visit_count per zone per day: the two department regions",
            "sale_line with the floor zone_id: units per product by department",
            "inventory_zone_product_count per fixture per product",
        )

        "move_fast_seller" -> listOf(
            "sale_line per unit per day: the product's velocity against its department",
            "traffic_projection zone visit_count and dwell_time_seconds: its fixture's zone against the target zone",
        )

        "under_seen" -> listOf(
            "inventory_zone_product_count x unit price per fixture: the largest on-hand value",
            "behavioral_projection impression and dwell per fixture per day: the bottom decile",
        )

        "restock_first" -> listOf(
            "sale_line per day: the two fast products",
            "inventory_zone_product_count per fixture: stock against velocity (days of cover)",
        )

        "quiet_stock" -> listOf(
            "inventory_zone_product_count per fixture per product: high on-hand",
            "sale_line: zero units for the window",
            "traffic_projection zone visit_count: below-median zones",
        )

        "look_not_buy" -> listOf(
            "behavioral_projection impression, dwell and engagement per fixture per day: the top three",
            "sale_line with the floor zone_id: near-zero units at two of them",
            "inventory_zone_product_count: qty zero at the stock-out fixture; product.unit_price at the price fixture",
        )

        else -> emptyList()
    }

    /** The key file's body: a JSON array of records, one per planted scenario, store by store. */
    fun json(records: List<ObjectNode>): String = ConnectMappers.snake.writerWithDefaultPrettyPrinter().writeValueAsString(records) + "\n"
}
