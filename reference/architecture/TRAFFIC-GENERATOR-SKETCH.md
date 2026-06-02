# TrafficGenerator — Design Sketch (config-driven, inhomogeneous-Poisson)

**Status:** SKETCH 2026-06-02 (Session 4). Design-committed against the locked Layer 4 runtime
contract (`LAYER4-CONFIG-SCHEMA.md` § Runtime model Q2/Q3/Q6 + § Layer 2 Journey). Not yet in
`src/` — the orchestrator runtime types it depends on (`GeneratorContext`, `Scheduler`, `EventBus`,
`JourneyLibrary`) are locked in design but unbuilt. This drops into `com.m8trx.twin.layer3` once
that skeleton lands.

**What this sketch establishes:**
1. The `OperatingModel` config loader — bridge from `store-operating-model.json` to typed Kotlin.
2. `TrafficGenerator` consuming it: per-hour inhomogeneous-Poisson arrivals off the §3 hourly curve,
   weighted persona/journey assignment off §6 persona mix, journey hand-off per the Layer 2 contract.
3. The reconciliation check that proves a generated day ties out to §1 of the operating model.

**Upgrade vs the strawman.** `LAYER4-CONFIG-SCHEMA.md` § generators sketched `TrafficGenerator` with
a single gaussian peak (`peakHour` + `peakSigmaHours`). The operating model's hourly curve is bimodal
(weekday lunch + evening peaks; weekend midday-heavy), which a single gaussian can't represent. This
sketch reads the 11-bucket curve directly. The gaussian params are superseded for the realistic
baseline; a scenario may still override the curve inline.

---

## 1. Config loader — `OperatingModel.kt`

Pure data + Jackson; no runtime deps. Lives at `com.m8trx.twin.layer3.OperatingModel`. Parses only
what the generators consume; ignores the doc-facing `_meta` / `range` / `conf` annotation fields
(`@JsonIgnoreProperties(ignoreUnknown = true)`).

```kotlin
package com.m8trx.twin.layer3

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path

/**
 * Typed view of reference/data/store-operating-model.json — the single realism source
 * every Layer-3 generator reads. See reference/data/STORE-OPERATING-MODEL.md for the
 * parameter sheet + reconciliation identity.
 *
 * Confidence-tagged values in the JSON are shaped { value, range, conf }; we read `.value`
 * via the Tagged<T> wrapper and drop range/conf (those are for humans tuning the doc).
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
    data class Store(
        @JsonProperty("sales_floor_sqm") val salesFloorSqm: Int,
        @JsonProperty("operating_hours") val operatingHours: Hours,
    )
    data class Hours(@JsonProperty("open_hours") val openHours: Int, val open: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Traffic(
        @JsonProperty("visitors_per_day") val visitorsPerDay: VisitorsPerDay,
        @JsonProperty("weekend_weekday_multiplier") val weekendMultiplier: Tagged<Double>,
        @JsonProperty("seasonality_factor_range") val seasonalityRange: List<Double>,
        @JsonProperty("hourly_curve") val hourlyCurve: HourlyCurve,
    )
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
    )

    /** Session-share by behavior; keys are journey impl slugs (see §3 mapping). Sums to ~1.0. */
    data class PersonaMix(
        @JsonProperty("browse_and_leave") val browseAndLeave: Double,
        @JsonProperty("shop_and_buy") val shopAndBuy: Double,
        @JsonProperty("try_on_and_partial_buy") val tryOnAndPartialBuy: Double,
        @JsonProperty("shoplift_baseline") val shopliftBaseline: Double,
    ) {
        /** Weighted journey table; renormalized so the four weights sum to 1.0 exactly. */
        fun journeyWeights(): Map<String, Double> {
            val raw = mapOf(
                "BrowseAndLeave" to browseAndLeave,
                "ShopAndBuy" to shopAndBuy,
                "TryOnAndBuy" to tryOnAndPartialBuy,
                "Shoplift" to shopliftBaseline,
            )
            val sum = raw.values.sum()
            return raw.mapValues { it.value / sum }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Dwell(@JsonProperty("total_median_min") val totalMedianMin: Tagged<Int>)

    companion object {
        private val mapper: ObjectMapper = jacksonObjectMapper()
        fun load(path: Path): OperatingModel = mapper.readValue(Files.readAllBytes(path))
    }
}

/** Wrapper for the JSON's confidence-tagged { value, range, conf } shape; we keep only value. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Tagged<T>(val value: T)
```

> If `store-operating-model.json` ever drops the `{value, range, conf}` wrappers for raw scalars,
> `Tagged<T>` collapses to a Jackson `@JsonCreator` reading either shape. Keeping the wrapper now
> matches the calibrated-v1 file as written.

---

## 2. Arrival math — inhomogeneous Poisson off the hourly curve

The store day is a **piecewise-constant-rate Poisson process**: each of the 11 open hours has its
own rate. For day-type `d` and hour bucket `h`:

```
N           = visitorsPerDay[d] × seasonalityFactor          # daily total
λ_h         = N × hourlyCurve[d][h]                          # expected arrivals in hour h
k_h ~ Poisson(λ_h)                                           # actual arrivals in hour h
t_i ~ Uniform(hourStart_h, hourStart_h + 1h)  for i in 1..k_h
```

Why per-hour Poisson + uniform-within-hour rather than sampling N gaussian times:
- **Represents the real bimodal shape** — a single gaussian can't do "lunch peak AND evening peak."
- **Deterministic & seed-stable** — `k_h` and each `t_i` come from `ctx.rng`; same seed → same day.
- **Composable with seasonality / scenario deltas** — Saturday Rush just scales `N` and swaps the
  curve; the sampler is unchanged.

`Poisson(λ)` via Knuth for the small λ here (≤ ~190 × 0.13 ≈ 25 per hour) is cheap and exact.

---

## 3. `TrafficGenerator.kt` (sketch)

Fits the locked `Generator` contract: all arrivals are pre-scheduled in `start()` from `ctx.rng`
(deterministic), then the scheduler drains them. No `tick()`, no mutable instance state.

```kotlin
package com.m8trx.twin.layer3

import com.m8trx.twin.domain.CustomerEntered      // DomainEvent (bus-internal)
import com.m8trx.twin.domain.ObjLocation          // Layer 0 atom body
import com.m8trx.twin.runtime.Generator           // locked Q2 contract
import com.m8trx.twin.runtime.GeneratorContext
import com.m8trx.twin.runtime.CustomerActor
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.exp

/**
 * Layer 3 — People dimension. Spawns the day's customer arrivals as an inhomogeneous Poisson
 * process off OperatingModel §3 hourly curve, assigns each a journey (§6 persona mix) and a
 * lightweight shopper demographic, hands the actor to its Journey (Layer 2 contract), and
 * publishes CustomerEntered for downstream generators.
 *
 * Reads realism constants from OperatingModel; per-scenario knobs (date, day-type override,
 * entry gate, seasonality) come from Params.
 */
class TrafficGenerator(
    private val model: OperatingModel,
    private val params: Params,
) : Generator {

    override val id = "traffic"

    data class Params(
        val date: LocalDate,                       // drives weekday/weekend selection
        val dayTypeOverride: DayType? = null,      // force weekday/weekend (scenario delta)
        val seasonalityFactor: Double = 1.0,       // 0.75..1.25 per model §3
        val entryGateZoneId: String,               // STORE-LAYOUT Z-01 / CS-01
        val entryX: Double = 10_000.0,             // entrance centroid (mm) — gate midpoint
        val entryY: Double = 600.0,
    )
    enum class DayType { WEEKDAY, WEEKEND }

    override fun start(ctx: GeneratorContext) {
        val dayType = params.dayTypeOverride ?: dayTypeOf(params.date)
        val dailyTotal = dailyVisitors(dayType) * params.seasonalityFactor
        val curve = curveFor(dayType)
        val openHour = LocalTime.parse(model.store.operatingHours.open).hour   // 10
        val journeyWeights = model.personaMix.journeyWeights()

        var spawned = 0
        curve.forEachIndexed { h, share ->
            val lambda = dailyTotal * share
            val arrivals = poisson(lambda, ctx.rng)
            repeat(arrivals) {
                val minuteOffset = ctx.rng.nextDouble() * 60.0       // uniform within the hour
                val t = scenarioInstant(ctx, openHour + h, minuteOffset)
                val custId = "cust-${id}-${spawned++}"
                ctx.scheduler.scheduleAt(t) { spawnCustomer(ctx, custId, journeyWeights) }
            }
        }
        ctx.log.info("traffic: scheduled {} arrivals for {} ({})", spawned, params.date, dayType)
    }

    private fun spawnCustomer(
        ctx: GeneratorContext,
        custId: String,
        journeyWeights: Map<String, Double>,
    ) {
        val journeyImpl = weightedPick(journeyWeights, ctx.rng)      // "BrowseAndLeave" | "ShopAndBuy" | …
        val persona = sampleShopper(ctx, custId)                    // demographics for the objLocation atom
        val actor = CustomerActor(id = custId, persona = persona)

        // 1. M8TRX boundary: entry pin at the gate. The assigned Journey emits the subsequent
        //    objLocation walk + any try-on / sale atoms. TrafficGenerator only emits the entry.
        ctx.emit.objLocation(
            ObjLocation(
                objectId = custId,
                x = params.entryX, y = params.entryY,
                isMale = persona.isMale,
                layoutId = ctx.tenantSite.spaceId,
            ),
            ts = ctx.clock.now().toEpochMilli(),
            id = "$custId-entry",
        )

        // 2. Internal correlation: tell other generators a customer arrived.
        ctx.bus.publish(CustomerEntered(ctx.clock.now(), custId, persona.id, params.entryGateZoneId))

        // 3. Hand off to Layer 2: instantiate + start the assigned journey. The journey owns the
        //    walk, dwell, try-on, purchase, and the terminal CustomerExited.
        val journey = ctx.journeys[journeyImpl]
            ?: run { ctx.log.warn("no journey impl '{}' registered; customer {} idles", journeyImpl, custId); return }
        journey.start(ctx.journeyContextFor(custId), actor, ctx.journeyParamsFor(journeyImpl))
    }

    override fun stop(ctx: GeneratorContext) {}

    // ── helpers ───────────────────────────────────────────────────────────

    private fun dayTypeOf(d: LocalDate): DayType =
        if (d.dayOfWeek.value >= 6) DayType.WEEKEND else DayType.WEEKDAY

    private fun dailyVisitors(t: DayType) = when (t) {
        DayType.WEEKDAY -> model.traffic.visitorsPerDay.weekday.toDouble()
        DayType.WEEKEND -> model.traffic.visitorsPerDay.weekend.toDouble()
    }

    private fun curveFor(t: DayType) = when (t) {
        DayType.WEEKDAY -> model.traffic.hourlyCurve.weekday
        DayType.WEEKEND -> model.traffic.hourlyCurve.weekend
    }

    /** Knuth's Poisson sampler — exact, fine for small λ. Uses the per-generator seeded rng. */
    private fun poisson(lambda: Double, rng: java.util.Random): Int {
        if (lambda <= 0.0) return 0
        val l = exp(-lambda); var k = 0; var p = 1.0
        do { k++; p *= rng.nextDouble() } while (p > l)
        return k - 1
    }

    /** Weighted categorical pick over a normalized weight map. */
    private fun <K> weightedPick(weights: Map<K, Double>, rng: java.util.Random): K {
        var r = rng.nextDouble()
        for ((k, w) in weights) { r -= w; if (r <= 0.0) return k }
        return weights.keys.last()   // float-rounding guard
    }

    /** Build the scenario Instant for (hour, minuteOffset) on params.date in the scenario zone. */
    private fun scenarioInstant(ctx: GeneratorContext, hour: Int, minute: Double): java.time.Instant {
        val zone = ctx.clock.now().atZone(java.time.ZoneOffset.UTC).zone   // scenario zone via clock
        return ZonedDateTime.of(params.date, LocalTime.of(hour, minute.toInt(), ((minute % 1) * 60).toInt()), zone)
            .toInstant()
    }

    /** Lightweight demographic for the entry atom; full ShopperPersona library wiring is post-sketch. */
    private fun sampleShopper(ctx: GeneratorContext, custId: String): ShopperLite {
        val male = ctx.rng.nextBoolean()
        val walk = 1.0 + ctx.rng.nextDouble() * 0.6                 // 1.0–1.6 m/s
        return ShopperLite(id = "shopper.us.sporting_goods.$custId", isMale = male, walkSpeedMps = walk)
    }
    data class ShopperLite(val id: String, val isMale: Boolean, val walkSpeedMps: Double)
}
```

> `ShopperLite` is a sketch stand-in for the locked `ShopperPersona` (`PERSONA-SCHEMA.md`). When the
> `PersonaLibrary` lands, `sampleShopper` pulls weighted archetypes (dwell tendency, basket dist,
> accompaniment) instead of inventing two fields. The arrival math and journey hand-off don't change.

---

## 4. Reconciliation check (the realism gate)

A generated day is **correct** only if the emitted stream ties out to OperatingModel §1. Run this as
a post-scenario assertion (and as the nightly regression check):

```
expected_visitors      = visitorsPerDay[dayType] × seasonality        (± Poisson noise, ~√N)
observed_visitors      = count(CustomerEntered)
expected_transactions  = observed_visitors × conversion.overallRate
observed_transactions  = count(SaleCompleted)
expected_revenue       = observed_transactions × basket.atvUsd
observed_revenue       = Σ SaleCompleted.totalCents / 100
```

Tolerances: visitors within ±3√N of expected (Poisson); transactions/revenue within ±10% (sampling
+ basket variance). A miss means a generator drifted from the model — exactly the signal the
reconciliation identity exists to catch. TrafficGenerator owns `observed_visitors`;
TransactionGenerator (next sketch) owns transactions/revenue against the same model instance.

---

## 5. What's real vs assumed-runtime

| Symbol | Status |
|---|---|
| `OperatingModel` + `Tagged<T>` | **Real, compilable today** (pure Jackson) — could land in `src/` now |
| `ObjLocation`, `CustomerEntered` atom/event bodies | `ObjLocation` exists (`domain/AreaEvent.kt`); `CustomerEntered` is in the locked DomainEvent v1 taxonomy, not yet coded |
| `Generator`, `GeneratorContext`, `Scheduler`, `EventBus`, `CustomerActor`, `JourneyLibrary` | **Assumed** — locked in `LAYER4-CONFIG-SCHEMA.md` Q2/Q3/Q6 + Layer 2, unbuilt. Building these = the orchestrator-runtime skeleton task |
| `ctx.journeyContextFor` / `journeyParamsFor` | Sketch convenience accessors; final shape decided when the orchestrator wires JourneyContext (Layer 2) |

**Next build step to make this run:** the orchestrator-runtime skeleton (`com.m8trx.twin.runtime`):
`Clock` + `Scheduler` (priority queue, rate modes) + `EventBus` (sync queue-drain) + `GeneratorContext`
+ `JourneyLibrary`/`PersonaLibrary` stubs. Then `OperatingModel` + this `TrafficGenerator` + one
`BrowseAndLeave` journey compile and a `rate=+∞` headless run produces a reconcilable day with zero
M8TRX emits (dry mode) — the first end-to-end determinism test.
```
