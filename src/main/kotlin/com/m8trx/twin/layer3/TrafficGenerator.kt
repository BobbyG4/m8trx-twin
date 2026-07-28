package com.m8trx.twin.layer3

import com.m8trx.twin.domain.CustomerEntered
import com.m8trx.twin.layer2.Journeys
import com.m8trx.twin.runtime.Generator
import com.m8trx.twin.runtime.GeneratorContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.exp
import kotlin.random.Random as KRandom

/**
 * Layer 3 — the People dimension. Spawns a day's arrivals as an inhomogeneous Poisson process off
 * [OperatingModel] §3's hourly curve, assigns each a journey per §6 persona mix, and hands the actor to
 * Layer 2.
 *
 * Fits the locked Q2 contract: all arrivals are pre-scheduled in [start] from `ctx.rng`, so the same seed
 * yields the same day. No `tick()`, no mutable instance state.
 *
 * **Why per-hour Poisson rather than one gaussian:** the real curve is bimodal (weekday lunch AND evening
 * peaks; weekend midday-heavy), which a single peak cannot represent. `LAYER4-CONFIG-SCHEMA.md`'s original
 * `peakHour`/`peakSigmaHours` sketch is superseded for the realistic baseline.
 */
class TrafficGenerator(private val model: OperatingModel, private val journeys: Journeys, private val params: Params) : Generator {

    override val id = "traffic"

    data class Params(
        val date: LocalDate,
        val zone: ZoneId,
        val dayTypeOverride: DayType? = null,
        val seasonalityFactor: Double = 1.0,
        /** Scales the whole day down for a fast smoke without distorting the arrival SHAPE. */
        val populationScale: Double = 1.0,
    )

    enum class DayType { WEEKDAY, WEEKEND }

    var scheduled: Int = 0
        private set

    override fun start(ctx: GeneratorContext) {
        val dayType = params.dayTypeOverride ?: if (params.date.dayOfWeek.value >= 6) DayType.WEEKEND else DayType.WEEKDAY
        val curve = when (dayType) {
            DayType.WEEKDAY -> model.traffic.hourlyCurve.weekday
            DayType.WEEKEND -> model.traffic.hourlyCurve.weekend
        }
        val dailyTotal = when (dayType) {
            DayType.WEEKDAY -> model.traffic.visitorsPerDay.weekday
            DayType.WEEKEND -> model.traffic.visitorsPerDay.weekend
        } * params.seasonalityFactor * params.populationScale

        val openHour = LocalTime.parse(model.store.operatingHours.open).hour
        val weights = model.personaMix.journeyWeights()

        curve.forEachIndexed { h, share ->
            val lambda = dailyTotal * share
            repeat(poisson(lambda, ctx.rng)) {
                val minuteOffset = ctx.rng.nextDouble() * 60.0
                val t = ZonedDateTime.of(
                    params.date,
                    LocalTime.of(openHour + h, minuteOffset.toInt(), (((minuteOffset % 1) * 60).toInt()).coerceIn(0, 59)),
                    params.zone,
                ).toInstant()
                val customerId = "cust-${params.date}-${scheduled++}"
                ctx.scheduler.scheduleAt(t) {
                    val journeyId = weightedPick(weights, ctx.rng)
                    ctx.bus.publish(CustomerEntered(ctx.clock.now(), customerId, journeyId))
                    journeys.run(journeyId, ctx, customerId, KRandom(ctx.rng.nextLong()))
                }
            }
        }
        ctx.log.info(
            "traffic: scheduled {} arrivals for {} ({}, scale={}) across {} open hours",
            scheduled,
            params.date,
            dayType,
            params.populationScale,
            curve.size,
        )
    }

    /** Knuth's sampler — exact, and λ here is small (≤ ~190 × 0.13 ≈ 25/hour). */
    private fun poisson(lambda: Double, rng: java.util.Random): Int {
        if (lambda <= 0.0) return 0
        val l = exp(-lambda)
        var k = 0
        var p = 1.0
        do {
            k++
            p *= rng.nextDouble()
        } while (p > l)
        return k - 1
    }

    private fun weightedPick(weights: Map<String, Double>, rng: java.util.Random): String {
        var r = rng.nextDouble()
        for ((k, w) in weights) {
            r -= w
            if (r <= 0.0) return k
        }
        return weights.keys.last()
    }
}
