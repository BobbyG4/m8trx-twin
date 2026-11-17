package com.m8trx.twin.storeday

import com.fasterxml.jackson.databind.JsonNode
import com.m8trx.twin.connect.model.ConnectMappers
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Slice 2d: the levers a planted scenario pulls. Every one is a mechanism the simulation already has, never an output:
 *
 *  - [fixtureMul]: a fixture's stop weight (how many shoppers stop there, so its impressions and dwell). The same lever the
 *    calibration uses ([Calibration.fixtureScale]).
 *  - [fixtureDwellMul] (slice 2e): the engaged span at a fixture, on top of what its kind and content give ([DwellModel]): the
 *    presentation that holds shoppers (a demo, seating, an associate). It moves the span, not the visits.
 *  - [fixtureBuyMul] / [buyMul]: a shopper's chance to buy what is in hand, per fixture or per EAN (price shock, a presentation
 *    that does not convert).
 *  - [cellMul]: how readily a cell is looked at closely (the demand weight of [FloorHooks.cellWeight]).
 *  - the period plan ([PlantPlanEdit]): [swaps] of two products between fixtures and the required quantity per line
 *    ([requiredAbs], [requiredEanMul], [requiredFixtureMul]).
 *  - stock policy: [backroomAbs] (the back-room cover the order tops up to) and [initialUnits] (the units a store starts with).
 */
data class PlantLevers(
    val fixtureMul: Map<String, Double> = emptyMap(),
    val fixtureBuyMul: Map<String, Double> = emptyMap(),
    val cellMul: Map<String, Double> = emptyMap(),
    val buyMul: Map<String, Double> = emptyMap(),
    val requiredAbs: Map<String, Int> = emptyMap(),
    val requiredEanMul: Map<String, Double> = emptyMap(),
    val requiredFixtureMul: Map<String, Double> = emptyMap(),
    val backroomAbs: Map<String, Int> = emptyMap(),
    val initialUnits: Map<String, Int> = emptyMap(),
    val swaps: List<PlantSwap> = emptyList(),
    val fixtureDwellMul: Map<String, Double> = emptyMap(),
) {
    /** Every fixture code the levers touch. */
    val fixtures: Set<String> get() = fixtureMul.keys + fixtureBuyMul.keys + fixtureDwellMul.keys + requiredFixtureMul.keys +
        swaps.map { it.toFixture }

    /** Every EAN the levers touch. */
    val eans: Set<String>
        get() = cellMul.keys + buyMul.keys + requiredAbs.keys + requiredEanMul.keys + backroomAbs.keys + initialUnits.keys +
            swaps.flatMap { listOf(it.ean, it.displaces) }

    fun fixtureMul(code: String): Double = fixtureMul[code] ?: 1.0

    fun dwellMul(code: String): Double = fixtureDwellMul[code] ?: 1.0

    fun buyMul(ean: String, fixture: String): Double = (buyMul[ean] ?: 1.0) * (fixtureBuyMul[fixture] ?: 1.0)

    fun cellMul(ean: String): Double = cellMul[ean] ?: 1.0

    companion object {
        private fun <V> merge(a: Map<String, V>, b: Map<String, V>, what: String): Map<String, V> {
            val dup = a.keys.intersect(b.keys)
            require(dup.isEmpty()) { "two scenarios pull the same $what lever on $dup" }
            return a + b
        }

        fun merge(all: List<PlantLevers>): PlantLevers = all.fold(PlantLevers()) { x, y ->
            PlantLevers(
                merge(x.fixtureMul, y.fixtureMul, "fixture_mul"),
                merge(x.fixtureBuyMul, y.fixtureBuyMul, "fixture_buy_mul"),
                merge(x.cellMul, y.cellMul, "cell_mul"),
                merge(x.buyMul, y.buyMul, "buy_mul"),
                merge(x.requiredAbs, y.requiredAbs, "required_abs"),
                merge(x.requiredEanMul, y.requiredEanMul, "required_ean_mul"),
                merge(x.requiredFixtureMul, y.requiredFixtureMul, "required_fixture_mul"),
                merge(x.backroomAbs, y.backroomAbs, "backroom_abs"),
                merge(x.initialUnits, y.initialUnits, "initial_units"),
                x.swaps + y.swaps,
                merge(x.fixtureDwellMul, y.fixtureDwellMul, "fixture_dwell_mul"),
            )
        }
    }
}

/** Put [ean] on [toFixture] in place of [displaces] (which takes the first line [ean] had): a swap between two fixtures. */
data class PlantSwap(val ean: String, val toFixture: String, val displaces: String)

/** One planted scenario: an archetype, the entities it is built from ([roles]: role name to fixture codes, EANs or departments), its levers. */
data class PlantScenario(val id: String, val archetype: String, val roles: Map<String, List<String>>, val levers: PlantLevers) {
    fun role(name: String): List<String> = roles[name] ?: error("scenario $id has no role '$name'")

    fun one(name: String): String = role(name).singleOrNull() ?: error("scenario $id: role '$name' must hold exactly one value")
}

/** What is planted in one store: from [from] (a Monday, store-local) on, until the run ends. */
class StorePlant(val slug: String, val from: LocalDate, val scenarios: List<PlantScenario>) {
    init {
        require(from.dayOfWeek == java.time.DayOfWeek.MONDAY) { "$slug: a planted window starts on a Monday (period start): $from" }
        val ids = scenarios.map { it.id }
        require(ids.size == ids.toSet().size) { "$slug: duplicate scenario ids $ids" }
    }

    val levers: PlantLevers = PlantLevers.merge(scenarios.map { it.levers })

    /** Weekly plan changes leave these alone, so the pattern is not undone by a facing change or a style swap. */
    val protectedEans: Set<String> = levers.eans + scenarios.flatMap { it.roles.values.flatten() }
    val protectedFixtures: Set<String> = levers.fixtures + scenarios.flatMap { it.roles.values.flatten() }

    fun activeOn(date: LocalDate): Boolean = date >= from
}

class PlantConfig(val from: LocalDate, val days: Int, val stores: Map<String, StorePlant>) {
    companion object {
        val DEFAULT_PATH: Path = Path.of("reference/data/scenarios/plant-config.json")

        fun load(path: Path = DEFAULT_PATH): PlantConfig {
            val root = ConnectMappers.snake.readTree(Files.readAllBytes(path))
            require(root.path("format").asText() == "twin_scenario_plant_config/v1") { "$path: not a plant config" }
            val from = LocalDate.parse(root.path("run").path("from").asText())
            val days = root.path("run").path("days").asInt(28)
            val stores = root.path("stores").fields().asSequence().associate { (slug, n) ->
                val sFrom = n.path("from").asText("").takeIf { it.isNotBlank() }?.let(LocalDate::parse) ?: from
                slug to StorePlant(slug, sFrom, n.path("scenarios").map(::scenario))
            }
            return PlantConfig(from, days, stores)
        }

        private fun dMap(n: JsonNode): Map<String, Double> = n.fields().asSequence().associate { it.key to it.value.asDouble() }

        private fun iMap(n: JsonNode): Map<String, Int> = n.fields().asSequence().associate { it.key to it.value.asInt() }

        private fun scenario(n: JsonNode): PlantScenario {
            val l = n.path("levers")
            return PlantScenario(
                n.path("id").asText(),
                n.path("archetype").asText(),
                n.path("roles").fields().asSequence().associate { (k, v) -> k to if (v.isArray) v.map { it.asText() } else listOf(v.asText()) },
                PlantLevers(
                    dMap(l.path("fixture_mul")),
                    dMap(l.path("fixture_buy_mul")),
                    dMap(l.path("cell_mul")),
                    dMap(l.path("buy_mul")),
                    iMap(l.path("required_abs")),
                    dMap(l.path("required_ean_mul")),
                    dMap(l.path("required_fixture_mul")),
                    iMap(l.path("backroom_abs")),
                    iMap(l.path("initial_units")),
                    l.path("swaps").map { PlantSwap(it.path("ean").asText(), it.path("to_fixture").asText(), it.path("displaces").asText()) },
                    dMap(l.path("fixture_dwell_mul")),
                ),
            )
        }
    }
}

/** The period plan edit of a planted store: swaps first, then the required quantity of every line. A pure function of the unedited plan. */
class PlantPlanEdit(private val model: StoreDayModel, val plant: StorePlant) {
    fun activeOn(start: LocalDate): Boolean = plant.activeOn(start)

    fun apply(lines: List<StoreDayModel.V2Line>): List<StoreDayModel.V2Line> {
        val out = lines.toMutableList()
        val lv = plant.levers
        for (sw in lv.swaps) {
            if (out.any { it.fixture == sw.toFixture && it.ean == sw.ean }) continue
            val iE = out.indexOfFirst { it.ean == sw.ean && it.fixture != sw.toFixture }
            val iG = out.indexOfFirst { it.fixture == sw.toFixture && it.ean == sw.displaces }
            require(iE >= 0 && iG >= 0) { "${plant.slug}: swap $sw has no line to exchange" }
            val e = out[iE]
            val g = out[iG]
            out[iG] = g.copy(ean = sw.ean, department = model.demand[sw.ean]?.department ?: g.department)
            out[iE] = e.copy(ean = sw.displaces, department = model.demand[sw.displaces]?.department ?: e.department)
        }
        return out.map { l ->
            val abs = lv.requiredAbs[l.ean]
            val fm = lv.requiredFixtureMul[l.fixture]
            val em = lv.requiredEanMul[l.ean]
            when {
                abs != null -> l.copy(required = abs)
                fm == null && em == null -> l
                fm == 0.0 -> l.copy(required = 0)
                else -> l.copy(required = maxOf(1, Math.round(l.required * (fm ?: 1.0) * (em ?: 1.0)).toInt()))
            }
        }
    }
}
