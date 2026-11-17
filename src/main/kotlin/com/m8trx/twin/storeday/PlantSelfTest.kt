package com.m8trx.twin.storeday

import com.fasterxml.jackson.databind.JsonNode
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.history.ChainKeys
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.system.exitProcess

/**
 * `./gradlew storeDayPlantSelfTest` — offline proof of slice 2d. Writes only under `build/storeday-selftest/plant/`; sends nothing.
 *
 *  1. the plant config: Denver and Paris only, the seven archetypes in each, entities that exist, levers that do not collide
 *  2. every planted scenario verifies against the truth ledger in a 28-day Denver + Paris run
 *  3. the answer key has the eval's fields and sound entities, and matches the committed example
 *  4. the plan edit (swaps, required quantity) is a pure function and the weekly changes leave planted lines alone
 *  5. non-planted stores are byte-identical with planting off and on
 *  6. determinism, and the planted runs still conserve units and never sell a unit twice
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.storeday.PlantSelfTest")
private var plantPassed = 0
private var plantFailed = 0

private fun ok(name: String, cond: Boolean, detail: String) {
    if (cond) plantPassed++ else plantFailed++
    log.info("  {} {} - {}", if (cond) "✓" else "✗", name, detail)
}

private val OUT: Path = Path.of("build/storeday-selftest/plant")
private val EXAMPLE: Path = Path.of("reference/data/scenarios/answer-key-example.json")
private val ARCHETYPES = listOf("promote", "layout", "move_fast_seller", "under_seen", "restock_first", "quiet_stock", "look_not_buy")

private fun digest(dir: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    Files.walk(dir).use { s ->
        s.filter { Files.isRegularFile(it) }.sorted().forEach {
            md.update(dir.relativize(it).toString().toByteArray())
            md.update(Files.readAllBytes(it))
        }
    }
    return md.digest().take(8).joinToString("") { "%02x".format(it) }
}

fun main() {
    val cfg = PlantConfig.load()
    val from = cfg.from
    val days = cfg.days
    Files.createDirectories(OUT)

    // ── 1. the config ─────────────────────────────────────────────────────────────────────────────────
    log.info("[1] plant config")
    ok(
        "two stores are planted, never all ten: Denver plus Paris",
        cfg.stores.keys == setOf("dec-us-denver", "dec-fr-paris"),
        cfg.stores.keys.toString(),
    )
    ok(
        "every planted store carries the seven archetypes once",
        cfg.stores.values.all {
            it.scenarios.map { s -> s.archetype }.sorted() ==
                ARCHETYPES.sorted()
        },
        cfg.stores.values.joinToString { "${it.slug} ${it.scenarios.size}" },
    )
    ok(
        "entrance_promo is not planted",
        cfg.stores.values.none { s ->
            s.scenarios.any { it.archetype == "entrance_promo" }
        },
        PlantKey.NOT_PLANTED.keys.toString(),
    )
    ok(
        "a window starts on a Monday and lasts at least 28 days",
        cfg.stores.values.all { it.from.dayOfWeek == java.time.DayOfWeek.MONDAY } && days >= 28,
        "$from + $days days",
    )
    val sims = cfg.stores.mapValues { (slug, p) -> buildSim(slug, plant = p) }
    val exist = cfg.stores.all { (slug, p) ->
        val sim = sims.getValue(slug)
        val fixtures = sim.behavior.fixtures.fixtures.map { it.code }.toSet()
        val depts = ChainKeys.load().store(slug).departments.map { it.department }.toSet()
        p.scenarios.all { sc ->
            sc.roles.values.flatten().all { it in sim.model.demand || it in fixtures || it in depts } &&
                sc.levers.fixtures.all { it in fixtures } && sc.levers.eans.all { it in sim.model.demand }
        }
    }
    ok("every entity and lever names a fixture, product or department the store has", exist, "2 stores")
    val colliding = cfg.stores.values.none { p -> runCatching { PlantLevers.merge(p.scenarios.map { it.levers }) }.isFailure }
    ok("no two scenarios pull the same lever", colliding, "merged without a clash")
    ok(
        "a collision is refused",
        runCatching {
            PlantLevers.merge(listOf(PlantLevers(fixtureMul = mapOf("A" to 2.0)), PlantLevers(fixtureMul = mapOf("A" to 3.0))))
        }.isFailure,
        "the same fixture_mul twice",
    )
    ok(
        "planting needs the period plan (periods off is refused)",
        runCatching {
            buildSim("dec-us-denver", knobs = Knobs(periods = false), plant = cfg.stores.getValue("dec-us-denver"))
        }.isFailure,
        "Knobs.periods=false",
    )

    // ── 2. the run and its verification ───────────────────────────────────────────────────────────────
    log.info("[2] planted run, {} days from {}", days, from)
    val knobs = Knobs()
    val runs = cfg.stores.values.map { plantRunStore(it, knobs, from, days) }
    runs.forEach { r ->
        r.checks.forEach { ok("${r.slug} ${it.scenario.substringAfter(':')}: ${it.name}", it.ok, it.measured) }
    }
    ok(
        "conservation holds and no unit sells twice in a planted run",
        runs.all {
            it.tally.conservationOk && it.tally.duplicateSold == 0
        },
        runs.joinToString { "${it.slug} ${it.tally.units} units" },
    )
    Files.writeString(OUT.resolve("VERIFY.md"), PlantReport.verifyMarkdown(runs, "selftest"))

    // ── 3. the answer key ─────────────────────────────────────────────────────────────────────────────
    log.info("[3] answer key")
    val keyText = PlantKey.json(runs.flatMap { it.records })
    Files.writeString(OUT.resolve("answer-key.json"), keyText)
    val key: JsonNode = ConnectMappers.snake.readTree(keyText)
    val sites = PlantKey.siteIds()
    val chain = ChainKeys.load()
    ok("one record per planted scenario", key.size() == runs.sumOf { it.records.size } && key.size() == 14, "${key.size()} records")
    ok(
        "every record carries the eval's fields",
        key.all { r ->
            PlantKey.EVAL_FIELDS.all { r.has(it) && !r[it].isNull }
        },
        PlantKey.EVAL_FIELDS.toString(),
    )
    ok("scenario ids are unique", key.map { it["scenario_id"].asText() }.toSet().size == key.size(), "14 ids")
    ok(
        "store_id is the platform site id",
        key.all {
            it["store_id"].asText() == sites[it["store_slug"].asText()] &&
                it["store_id"].asText().length == 36
        },
        key.map { it["store_id"].asText() }.toSet().toString(),
    )
    ok(
        "planted_from and planted_to are store-local dates, at least 28 days apart (inclusive)",
        key.all { LocalDate.parse(it["planted_to"].asText()).toEpochDay() - LocalDate.parse(it["planted_from"].asText()).toEpochDay() + 1 >= 28 },
        "${key[0]["planted_from"].asText()} to ${key[0]["planted_to"].asText()}",
    )
    ok(
        "entities hold the zone ids, zone names and product ids the pattern is built from",
        key.all { r ->
            val e = r["entities"]
            val store = chain.store(r["store_slug"].asText())
            e["zone_ids"].size() > 0 && e["zone_ids"].size() == e["zone_names"].size() &&
                e["zone_ids"].all { z -> store.zones.any { it.zoneId == z.asText() } } &&
                e["product_eans"].size() == e["item_cds"].size() && e["roles"].size() > 0
        },
        "zone ids exist in the keys export",
    )
    ok(
        "each expected recommendation names its entities",
        key.all { r ->
            r["expected_recommendation"].asText().length > 40 &&
                r["evidence_expected"].size() > 0
        },
        "text and evidence present",
    )
    ok("the committed example key matches this run byte for byte", Files.exists(EXAMPLE) && Files.readString(EXAMPLE) == keyText, EXAMPLE.toString())

    // ── 4. the plan edit ──────────────────────────────────────────────────────────────────────────────
    log.info("[4] plan edit")
    val den = runs.first { it.slug == "dec-us-denver" }
    val model = den.sim.model
    val edit = PlantPlanEdit(model, den.plant)
    val once = edit.apply(model.lines)
    val twice = edit.apply(model.lines)
    ok(
        "the edit keeps every line, conserves the products and is a pure function of the unedited plan",
        once.size == model.lines.size && once.map { it.ean }.sorted() == model.lines.map { it.ean }.sorted() && once == twice,
        "${once.size} lines",
    )
    val swapOk = den.plant.levers.swaps.all { sw ->
        val origin = model.lines.first { it.ean == sw.ean }.fixture
        once.any { it.fixture == sw.toFixture && it.ean == sw.ean } && once.any { it.fixture == origin && it.ean == sw.displaces }
    }
    ok(
        "a swap puts the product on the fixture, sends the displaced one back, and each line's department follows its product",
        swapOk && den.plant.levers.swaps.isNotEmpty() && once.all { it.department == (model.demand[it.ean]?.department ?: it.department) },
        "${den.plant.levers.swaps.size} swaps in Denver",
    )
    ok(
        "no lever raises a line's required quantity above the Phase 1 capacity (every edited line at or below its unedited line, Denver and Paris)",
        runs.all { r ->
            PlantPlanEdit(r.sim.model, r.plant).apply(r.sim.model.lines).zip(r.sim.model.lines).all { (e, b) -> e.required <= b.required }
        },
        "2 stores, ${runs.sumOf { it.sim.model.lines.size }} lines",
    )
    ok(
        "no planted slot exceeds its planned quantity at any close, in 28 days",
        runs.all {
            it.ledger.overCapacity.isEmpty()
        },
        runs.joinToString { "${it.slug} ${it.ledger.overCapacity.size}" },
    )
    val p1 = den.sim.periods.periodOf(from)
    val p5 = den.sim.periods.periodOf(from.plusWeeks(4))
    val prot = den.plant.protectedEans
    fun shape(p: PeriodPlan) = p.lines.filter {
        it.ean in prot || it.fixture in den.plant.protectedFixtures
    }.map { listOf(it.fixture, it.level, it.position, it.ean, it.facings, it.required) }.sortedBy { it.joinToString() }
    ok(
        "the weekly plan changes leave planted fixtures and products alone",
        shape(p1) == shape(p5) && p5.linesChanged > 0,
        "week 1 vs week 5: ${shape(p1).size} planted lines identical, week 5 changed ${p5.linesChanged} others",
    )

    // ── 5. non-planted stores are byte-identical, planting off or on ─────────────────────────────────
    log.info("[5] non-planted stores, planting off vs on")
    val others = listOf("dec-fr-bordeaux", "dec-kr-seoul", "dec-us-nyc")
    val digests = others.map { slug ->
        fun run(enabled: Boolean): String {
            val dir = OUT.resolve(if (enabled) "on" else "off")
            runStore(slug, knobs, from, 7, dir, null, null, write = true, plant = if (enabled) cfg.stores[slug] else null)
            return digest(dir.resolve(slug))
        }
        Triple(slug, run(false), run(true))
    }
    ok(
        "three non-planted stores: planting off and on write the same bytes",
        digests.all {
            it.second == it.third
        },
        digests.joinToString { "${it.first} ${it.second}" },
    )
    val offDen = run {
        runStore("dec-us-denver", knobs, from, 7, OUT.resolve("off"), null, null, write = true, plant = null)
        digest(OUT.resolve("off/dec-us-denver"))
    }
    runStore("dec-us-denver", knobs, from, 7, OUT.resolve("on"), null, null, write = true, plant = cfg.stores["dec-us-denver"])
    val onDen = digest(OUT.resolve("on/dec-us-denver"))
    ok("a planted store does change when planting is on", offDen != onDen, "Denver off $offDen, on $onDen")

    // ── 6. determinism ────────────────────────────────────────────────────────────────────────────────
    log.info("[6] determinism")
    val again = cfg.stores.values.map { plantRunStore(it, knobs, from, days) }
    ok("a second run writes the same answer key", PlantKey.json(again.flatMap { it.records }) == keyText, "14 records")
    ok(
        "and the same ledger",
        again.map { PlantReport.fixturesCsv(it) + PlantReport.plantedProductsCsv(it) } ==
            runs.map { PlantReport.fixturesCsv(it) + PlantReport.plantedProductsCsv(it) },
        "fixture and product ledgers",
    )

    log.info("storeDayPlantSelfTest: {} passed, {} failed", plantPassed, plantFailed)
    if (plantFailed > 0) exitProcess(1)
}
