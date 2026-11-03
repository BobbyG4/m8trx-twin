package com.m8trx.twin.behavior

import com.m8trx.twin.connect.model.ConnectMappers
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.sin

/**
 * Typed view of `reference/data/behavior/<site>.json` — TWIN-REQ-007 requirement 6, one human-readable file per
 * site holding every behavior number with its meaning in prose beside it. The `_doc` keys are for people and are
 * skipped on read (the snake mapper ignores unknown keys).
 *
 * The one rule a reader needs: **a truth multiplier of 1.0 switches that effect off**, and each truth multiplies
 * exactly one measure ([Truths]), so doubling `(value - 1)` doubles the effect. That is what lets a presenter say
 * "make the entrance table win harder" and edit one number.
 */
data class BehaviorParams(
    val site: String,
    val timezone: String,
    val seed: Long,
    val traffic: Traffic,
    val personas: Map<String, Double>,
    val browse: Browse,
    val content: Content,
    val engagement: Engagement,
    val purchase: Purchase,
    val truths: Truths,
    val noise: Noise,
    val reset: Reset,
) {
    data class Traffic(val dayOfWeekFactor: Map<DayOfWeek, Double>, val seasonalityAmplitude: Double, val seasonalityPeriodWeeks: Double) {
        /** Weekly wave anchored on Monday 2026-01-05, so a given date always gets the same factor. */
        fun seasonality(date: LocalDate): Double {
            if (seasonalityAmplitude == 0.0 || seasonalityPeriodWeeks <= 0.0) return 1.0
            val weeks = ChronoUnit.DAYS.between(SEASON_ANCHOR, date) / 7.0
            return 1.0 + seasonalityAmplitude * sin(2.0 * PI * weeks / seasonalityPeriodWeeks)
        }

        fun dayFactor(date: LocalDate): Double = dayOfWeekFactor[date.dayOfWeek] ?: 1.0
    }

    data class Browse(
        val stops: Map<String, List<Int>>,
        val dwellSeconds: List<Int>,
        val entranceStopRate: Double,
        val checkoutQueueSeconds: List<Int>,
        val missionCategoryBoost: Double,
    )

    data class Content(
        val emptyFixtureAppeal: Double,
        val heroAppeal: Double,
        val heroes: Set<String>,
        val complements: Map<String, List<String>>,
        val priceReferenceUsd: Double,
        val priceElasticity: Double,
    )

    data class Engagement(
        val cellsConsidered: Int,
        val basePickup: Double,
        val maxProbability: Double,
        val tryOnRate: Map<String, Double>,
        val tryOnSeconds: List<Int>,
    )

    data class Purchase(
        val baseBuy: Map<String, Double>,
        val tryOnBuyUplift: Double,
        val missionContinueAfterBuy: Double,
        val impulseAtCheckout: Double,
        val priceElasticity: Double = 0.0,
    )

    /** The six truths of requirement 10. Field names say which measure each one multiplies. */
    data class Truths(
        val entranceTableImpressions: Double,
        val entranceTablePickup: Double,
        val backCornerImpressions: Double,
        val backCornerDistanceQuantile: Double,
        val backCornerOffPathMm: Double,
        val eyeLevelPickup: Double,
        val floorLevelPickup: Double,
        val levelsPerFixture: Int,
        val eyeLevel: Int,
        val sizeRunConversion: Double,
        val sizeRunFullBreakShare: Double,
        val colorStoryCoherent: Double,
        val colorStoryClash: Double,
        val colorStoryMinShare: Double,
        val colorStoryClashFamilies: Int,
        val adjacencyComplement: Double,
    )

    data class Noise(val appealSd: Double)

    data class Reset(
        val heroBackCornerFixture: String,
        val entranceTableFixture: String,
        val complementHandles: List<String>,
        val controlFixture: String,
    )

    /** A copy with one truth changed — how the self-test toggles a truth off (1.0) or doubles it. */
    fun withTruths(f: (Truths) -> Truths): BehaviorParams = copy(truths = f(truths))

    companion object {
        private val SEASON_ANCHOR: LocalDate = LocalDate.of(2026, 1, 5)

        fun load(path: Path): BehaviorParams = ConnectMappers.snake.readValue(Files.readAllBytes(path), BehaviorParams::class.java)
            .also { it.validate() }

        fun forSite(site: String, dir: Path = Path.of("reference/data/behavior")): BehaviorParams = load(dir.resolve("$site.json"))
    }

    private fun validate() {
        require(personas.values.all { it >= 0.0 } && personas.values.sum() > 0.0) { "$site: persona shares must be >= 0 and not all zero" }
        PERSONAS.forEach { p ->
            require(p in browse.stops && p in engagement.tryOnRate && p in purchase.baseBuy) {
                "$site: persona '$p' missing a stops/try_on_rate/base_buy entry"
            }
        }
        require(browse.dwellSeconds.size == 2 && browse.dwellSeconds[0] >= 6) {
            "$site: dwell_seconds must be [min, max] with min >= 6 (the impression floor)"
        }
        require(truths.eyeLevel in 1..truths.levelsPerFixture) { "$site: eye_level must be within 1..levels_per_fixture" }
        require(truths.sizeRunFullBreakShare > 0.0) { "$site: size_run_full_break_share must be > 0" }
    }
}

/** Persona keys, in the order shares are sampled. */
val PERSONAS = listOf("browser", "mission", "try_on", "gift")
