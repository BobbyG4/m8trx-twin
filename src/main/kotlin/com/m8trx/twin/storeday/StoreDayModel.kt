package com.m8trx.twin.storeday

import com.m8trx.twin.behavior.PlanLine
import com.m8trx.twin.behavior.StoreStock
import com.m8trx.twin.connect.model.ConnectMappers
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Phase 1 store model (`reference/data/chain/stores/<slug>/model/`), read once for the store-day run.
 * Read-only: nothing here writes under `model/` except the calibration file ([Calibration]).
 */
class StoreDayModel(
    val slug: String,
    val chainDir: Path = Path.of("reference/data/chain"),
    /**
     * Back-room cover in days (`M8TRX_RESET_BACKROOM_COVER_DAYS`). Null (unset env, the default) keeps the stock plan's own back-room rows
     * (`stock-plan.csv`, from `build_store_model.py`: ceil(demand/day x 7) rounded up to cases, at least one case a line). Set, the back
     * room target is recomputed from this store's demand: units/day x days, rounded up to cases (bikes: singles); no demand gives 0.
     */
    val backroomCoverDays: Int? = System.getenv("M8TRX_RESET_BACKROOM_COVER_DAYS")?.trim()?.takeIf { it.isNotEmpty() }?.toInt(),
    /** Test seam: edits the profile tree after it is read (hours, saturday_lift), so a self-test can vary one thing. Null in every real run. */
    profileTweak: ((com.fasterxml.jackson.databind.node.ObjectNode) -> Unit)? = null,
) {
    val modelDir: Path = chainDir.resolve("stores/$slug/model")

    data class DemandRow(
        val ean: String,
        val department: String,
        val category: String,
        val priceUsd: Double,
        val perDayWeekday: Double,
        val perDayWeekend: Double,
        val share: Double,
    )

    /** One planogram v2 line. [level] counts from the top (1 = top). */
    data class V2Line(
        val fixture: String,
        val zoneId: String,
        val level: Int,
        val form: String,
        val position: Int,
        val ean: String,
        val facings: Int,
        val required: Int,
        val department: String,
    )

    /** One stock-plan row: [kind] is `floor` (zone = a sales-floor fixture) or `backroom` (zone = a BR rack). */
    data class StockRow(val ean: String, val zone: String, val qty: Int, val kind: String)

    val profile = ConnectMappers.snake.readTree(Files.readAllBytes(modelDir.resolve("profile.json"))).also {
        profileTweak?.invoke(it as com.fasterxml.jackson.databind.node.ObjectNode)
    }
    val timezone: String = profile.path("timezone").asText()
    val deliveryLeadMin: Int = profile.path("delivery_lead_min").asInt(60)
    val areaSqm: Double = profile.path("area_sqm").asDouble()
    val locationFactor: Double = profile.path("location_factor").asDouble(1.0)

    /** Saturday's visitor lift where the store is closed on Sunday (`closed_sunday_saturday_lift`, resolved per store); 1.0 otherwise. */
    val saturdayLift: Double = profile.path("saturday_lift").asDouble(1.0)

    /**
     * Footfall of this store against Denver's (the operating model's volumes are Denver's): `location_factor x area / Denver area`,
     * the same product `build_store_model.py` writes into `demand.csv`. 1.0 for Denver.
     */
    val visitScale: Double = locationFactor * areaSqm / denverArea(chainDir)

    /** One day's opening hours from the profile (`"10:00-21:00"`), or null when the store is closed that weekday. */
    fun hoursOn(day: java.time.DayOfWeek): Pair<java.time.LocalTime, java.time.LocalTime>? {
        val key = day.name.take(3).lowercase()
        val m = Regex("""(\d{1,2}:\d{2})-(\d{1,2}:\d{2})""").matchEntire(profile.path("hours").path(key).asText().trim()) ?: return null
        return java.time.LocalTime.parse(m.groupValues[1].padStart(5, '0')) to java.time.LocalTime.parse(m.groupValues[2].padStart(5, '0'))
    }

    val demand: Map<String, DemandRow> = StoreStock.readCsv(modelDir.resolve("demand.csv")).associate { r ->
        r.getValue("ean") to DemandRow(
            r.getValue("ean"),
            r.getValue("department"),
            r.getValue("category"),
            r.getValue("price_usd").toDouble(),
            r.getValue("units_per_day_weekday").toDouble(),
            r.getValue("units_per_day_weekend").toDouble(),
            r.getValue("share").toDouble(),
        )
    }

    val lines: List<V2Line> = ConnectMappers.snake.readTree(Files.readAllBytes(modelDir.resolve("planogram.json"))).path("lines").map {
        V2Line(
            it.path("fixture_code").asText(),
            it.path("zone_id").asText(),
            it.path("level").asInt(1),
            it.path("form").asText(),
            it.path("position_sequence").asInt(0),
            it.path("ean").asText(),
            it.path("facings").asInt(1),
            it.path("required_qty").asInt(0),
            it.path("department").asText(),
        )
    }

    val stockPlan: List<StockRow> = StoreStock.readCsv(modelDir.resolve("stock-plan.csv")).map {
        StockRow(it.getValue("ean"), it.getValue("zone_code"), it.getValue("qty").toInt(), it.getValue("kind"))
    }

    /** Plan lines as the behavior package reads them; the v2 `level` rides as the line's display level. */
    val planLines: List<PlanLine> = lines.map { PlanLine(it.fixture, it.ean, it.facings, it.position, it.level) }

    /** Units a (fixture, EAN) slot should hold: the sum of its lines' required quantity (a style can repeat on one fixture). */
    val required: Map<Slot, Int> = lines.groupBy { Slot(it.fixture, it.ean) }.mapValues { (_, ls) -> ls.sumOf { it.required } }

    /** The level a slot's units show on: that of its first line by position. */
    val homeLevel: Map<Slot, Int> = lines.groupBy { Slot(it.fixture, it.ean) }.mapValues { (_, ls) -> ls.minBy { it.position }.level }

    /** A fixture's department: the one holding most of its facings (ties: name order). */
    val fixtureDepartment: Map<String, String> = lines.groupBy { it.fixture }.mapValues { (_, ls) ->
        ls.groupBy {
            it.department
        }.mapValues { (_, d) -> d.sumOf { it.facings } }.entries.sortedWith(compareBy({ -it.value }, { it.key })).first().key
    }

    /** The rack each EAN's backroom stock lives on (stock plan), and the cover each rack row holds — the reorder target. */
    val backroomTarget: Map<String, Int> by lazy {
        if (backroomCoverDays == null) {
            stockPlan.filter { it.kind == "backroom" }.groupBy { it.ean }.mapValues { (_, r) -> r.sumOf { it.qty } }
        } else {
            coverTarget(backroomCoverDays)
        }
    }

    /** Expected units/day for an EAN averaged over the week by the store's own hours (closed days sell nothing, Saturday carries the lift). */
    fun unitsPerDay(ean: String): Double {
        val d = demand[ean] ?: return 0.0
        val lift = profile.path("saturday_lift").asDouble(1.0)
        val week = java.time.DayOfWeek.values().sumOf { day ->
            when {
                hoursOn(day) == null -> 0.0
                day.value <= 5 -> d.perDayWeekday
                day == java.time.DayOfWeek.SATURDAY -> d.perDayWeekend * lift
                else -> d.perDayWeekend
            }
        }
        return week / 7.0
    }

    /** `M8TRX_RESET_BACKROOM_CASE_PACK` (unset: the model's case of 6): a smaller pack rounds cover up to that many units (1 = single units). */
    private val casePackOverride: Int? = System.getenv("M8TRX_RESET_BACKROOM_CASE_PACK")?.trim()?.takeIf { it.isNotEmpty() }?.toInt()

    /** Back-room units per EAN for [days] of cover: units/day x days, rounded up to cases; a line with no demand gets 0. */
    fun coverTarget(days: Int): Map<String, Int> = demand.keys.mapNotNull { ean ->
        val need = Math.ceil(unitsPerDay(ean) * days - 1e-9).toInt()
        val cs = casePackOverride?.let { minOf(it, caseSize(ean)) } ?: caseSize(ean)
        val q = Math.floorDiv(need + cs - 1, cs) * cs
        if (q > 0) ean to q else null
    }.toMap()
    val homeRack: Map<String, String> = stockPlan.filter { it.kind == "backroom" }.groupBy { it.ean }.mapValues { (_, r) -> r.minBy { it.zone }.zone }

    /** Case size per EAN: 6, or 1 for a bike-priced line (the stock plan rounds those to singles). */
    fun caseSize(ean: String): Int = if ((demand[ean]?.priceUsd ?: 0.0) >= 500.0) 1 else 6

    /** Slice 2e: each fixture's category and its goods mix (facings per level goods class), from `fixtures.json`. Read only. */
    class FixtureSpec(val category: String, val goodsFacings: Map<String, Int>)

    val fixtureSpecs: Map<String, FixtureSpec> = ConnectMappers.snake.readTree(Files.readAllBytes(modelDir.resolve("fixtures.json")))
        .path("fixtures").fields().asSequence().associate { (code, f) ->
            code to FixtureSpec(
                f.path("fixture_category").asText(),
                f.path("levels").groupBy({ it.path("goods").asText() }, { it.path("facings").asInt() }).mapValues { it.value.sum() },
            )
        }

    /** The store's staffing: associates on the floor off-peak (operating model `staffing`). */
    val offpeakAssociates: Int = ConnectMappers.snake.readTree(Files.readAllBytes(Path.of("reference/data/store-operating-model.json")))
        .path("staffing").path("associates_on_floor_offpeak").asInt(3)
}

private fun denverArea(chainDir: Path): Double = ConnectMappers.snake.readTree(
    Files.readAllBytes(chainDir.resolve("../store-profiles.json")),
).path("stores").path("dec-us-denver").path("area_sqm").asDouble()

data class Slot(val fixture: String, val ean: String) : Comparable<Slot> {
    override fun compareTo(other: Slot): Int = compareValuesBy(this, other, { it.fixture }, { it.ean })
}
