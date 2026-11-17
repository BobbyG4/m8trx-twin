package com.m8trx.twin.storeday

import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("com.m8trx.twin.storeday.PlantRun")

/** One planted store after its run: the ledger, the plan in force at the end, the checks and the key records. */
class PlantStoreRun(
    val slug: String,
    val plant: StorePlant,
    val sim: StoreDaySim,
    val ledger: PlantLedger,
    val plan: PeriodPlan,
    val tally: RunTally,
    val checks: List<PlantCheck>,
    val records: List<ObjectNode>,
    val from: LocalDate,
    val to: LocalDate,
)

/**
 * `./gradlew storeDayPlantRun` — slice 2d. RECORD ONLY, offline: simulates the planted stores for `run.days` days, then writes
 * `out/scenarios/<run>/answer-key.json` (the eval's `twin_scenario_key` records), `VERIFY.md` (each pattern against the truth ledger) and
 * the per-store ledger CSVs. Nothing is sent to the product and nothing is written under `reference/`.
 *
 * Env: `M8TRX_PLANT_CONFIG` (reference/data/scenarios/plant-config.json) · `M8TRX_PLANT_STORES` (comma list; default every store in the
 * config: Denver and Paris) · `M8TRX_PLANT_RUN` (run id; default `<from>-<days>d`) · `M8TRX_PLANT_OUT` (out/scenarios) · `M8TRX_STORE_DAY_FROM`
 * and `M8TRX_STORE_DAY_DAYS` override the config's window.
 */
fun plantRunStore(plant: StorePlant, knobs: Knobs, from: LocalDate, days: Int, seed: Long? = null): PlantStoreRun {
    var ledger: PlantLedger? = null
    var tallySim: StoreDaySim? = null
    val tally = runStore(
        plant.slug, knobs, from, days, Path.of("out/unused"), seed, null, write = false, plant = plant,
        observer = { sim, r ->
            if (ledger == null) {
                ledger = PlantLedger(sim)
                tallySim = sim
            }
            ledger!!.observe(r)
        },
    )
    val l = ledger!!
    val sim = tallySim!!
    val to = from.plusDays(days - 1L)
    val plan = sim.periods.periodOf(PeriodPlans.periodStart(to))
    val checks = PlantVerify.verify(l, plant, plan)
    val siteId = PlantKey.siteIds()[plant.slug] ?: plant.slug
    val records = plant.scenarios.map { PlantKey.record(sim, it, plan, siteId, maxOf(from, plant.from), to) }
    return PlantStoreRun(plant.slug, plant, sim, l, plan, tally, checks, records, maxOf(from, plant.from), to)
}

object PlantReport {
    fun verifyMarkdown(runs: List<PlantStoreRun>, runId: String): String = buildString {
        appendLine("# Planted scenarios: verification against the twin's truth ledger ($runId)")
        appendLine()
        appendLine(
            "Offline, record only. Every row is computed from the simulation's own per-day results (impressions, dwell, pickups, units sold, stock-outs, floor and back-room on-hand).",
        )
        appendLine()
        val total = runs.sumOf { it.checks.size }
        val ok = runs.sumOf { r -> r.checks.count { it.ok } }
        appendLine("**$ok of $total checks pass.**")
        appendLine()
        runs.forEach { append(PlantVerify.markdown(it.slug, it.from, it.to, it.checks)) }
        appendLine("Not planted: " + PlantKey.NOT_PLANTED.entries.joinToString("; ") { "`${it.key}` (${it.value})" })
    }

    fun fixturesCsv(r: PlantStoreRun): String = buildString {
        val l = r.ledger
        append("fixture,region,impressions,dwell_s,pickups,units_sold,value_sold_usd,stock_outs,lost_sales,mean_on_hand_value_usd,empty_days\n")
        l.fixtures.forEach { (f, s) ->
            append(
                "$f,${l.regionOf(
                    f,
                )},${s.impressions},${s.dwellMs / 1000},${s.pickups},${s.units},${"%.2f".format(s.valueUsd)},${s.stockOuts},${s.lost},",
            )
            append("${"%.2f".format(l.onHandValue(f))},${s.emptyDays}\n")
        }
    }

    /** The products the scenarios name, with what the ledger holds for each. */
    fun plantedProductsCsv(r: PlantStoreRun): String = buildString {
        val l = r.ledger
        append("scenario,ean,department,units_sold,units_per_day,pickups,lost_sales,mean_on_hand_units,cover_days\n")
        r.plant.scenarios.forEach { sc ->
            sc.roles.values.flatten().filter { r.sim.model.demand.containsKey(it) }.forEach { e ->
                append(
                    "${sc.id},$e,${r.sim.model.demand[e]?.department},${l.eans[e]?.units ?: 0},${"%.3f".format(
                        l.velocity(e),
                    )},${l.eans[e]?.pickups ?: 0},",
                )
                append("${l.eans[e]?.lost ?: 0},${"%.2f".format(l.onHandUnits(e))},${"%.2f".format(l.coverDays(e))}\n")
            }
        }
    }
}

fun main() {
    val cfg = PlantConfig.load(Path.of(env("M8TRX_PLANT_CONFIG", PlantConfig.DEFAULT_PATH.toString())))
    val from = LocalDate.parse(env("M8TRX_STORE_DAY_FROM", cfg.from.toString()))
    val days = env("M8TRX_STORE_DAY_DAYS", cfg.days.toString()).toInt()
    val stores = System.getenv("M8TRX_PLANT_STORES")?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim() } ?: cfg.stores.keys.toList()
    val runId = env("M8TRX_PLANT_RUN", "$from-${days}d")
    val out = Path.of(env("M8TRX_PLANT_OUT", "out/scenarios")).resolve(runId)
    Files.createDirectories(out)
    val knobs = knobsFromEnv()
    val runs = stores.map { plantRunStore(cfg.stores[it] ?: error("$it is not planted in the config"), knobs, from, days) }
    Files.writeString(out.resolve("answer-key.json"), PlantKey.json(runs.flatMap { it.records }))
    Files.writeString(out.resolve("VERIFY.md"), PlantReport.verifyMarkdown(runs, runId))
    runs.forEach {
        Files.writeString(out.resolve("ledger-${it.slug}-fixtures.csv"), PlantReport.fixturesCsv(it))
        Files.writeString(out.resolve("ledger-${it.slug}-planted-products.csv"), PlantReport.plantedProductsCsv(it))
    }
    val bad = runs.sumOf { r -> r.checks.count { !it.ok } }
    log.info("done: {} ({} scenarios, {} checks failed)", out.toAbsolutePath(), runs.sumOf { it.records.size }, bad)
    if (bad > 0) exitProcess(1)
}
