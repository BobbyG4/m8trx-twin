package com.m8trx.twin.history

import com.m8trx.twin.layer3.OperatingModel
import com.m8trx.twin.layer3.TrafficGenerator.DayType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp

/**
 * The year's story — every multiplier the history generator applies ON TOP of the operating model, in one
 * place so the dashboards' narrative can be read (and tuned) without reading the generator.
 *
 * Nothing here replaces a model number: footfall, the hourly curves, the persona mix, the basket sampler and
 * the impression oracle are all the existing Layer-1..3 code. This file only says *how much* of a given day
 * each store gets, per the brief's § 3 story beats:
 *
 *  - **Seasonality** — a January trough, a spring rise, a July peak (summer sports), back-to-school
 *    (US August, France the September *rentrée*, Korea March), clamped to the model's own
 *    `seasonality_factor_range` (0.75..1.25). The French five carry the **August dip**.
 *  - **Promo weeks** — one per quarter, chain-wide, Monday..Sunday: +35 % visits, +20 % conversion.
 *  - **Holidays** — per country, each either CLOSED (no rows at all) or a day with the weekend curve and
 *    a volume factor. Korea carries Seollal and **Chuseok** with the pre-holiday gift surge.
 *    ⚠ The brief named Chuseok "Oct 3–6, 2026". Chuseok 2026 is **Sep 24–26** (Thu–Sat, the day itself
 *    the 25th); Oct 3 is Gaecheonjeol. The real dates are used and the correction is in the RESULTS file.
 *  - **Store stories** — Marseille consistently below plan; Lille below plan in H1 and turned around in
 *    Q3; Denver the best store in the US; the rest near plan.
 *  - **One slow category chain-wide** — Climbing & Mountaineering, a planner beat, plus ordinary category
 *    seasonality (snow in winter, cycling/hiking in summer, running in January and September).
 *
 * Every factor here is **[A-new]**: authored for a legible demo year, not calibrated research.
 */
class StoryCalendar(private val model: OperatingModel, private val referenceAreaSqm: Double) {

    enum class Country { US, FR, KR }

    data class Holiday(val closed: Boolean, val shape: DayType, val factor: Double, val name: String)

    data class Promo(val start: LocalDate, val name: String) {
        val end: LocalDate get() = start.plusDays(6)
        fun contains(d: LocalDate) = !d.isBefore(start) && !d.isAfter(end)
    }

    fun country(store: ChainKeys.Store): Country = when {
        store.zone.id.startsWith("America/") -> Country.US
        store.zone.id == "Asia/Seoul" -> Country.KR
        else -> Country.FR
    }

    // ── seasonality ───────────────────────────────────────────────────────────

    /** Monthly anchors at mid-month, Jan..Dec; linearly interpolated by day so a month boundary is never a step. */
    private val seasonAnchors: Map<Country, DoubleArray> = mapOf(
        Country.US to doubleArrayOf(0.80, 0.86, 0.95, 1.00, 1.05, 1.10, 1.20, 1.12, 1.04, 0.98, 1.00, 1.10),
        // the August dip — Paris empties; the rentrée makes September strong
        Country.FR to doubleArrayOf(0.80, 0.86, 0.96, 1.02, 1.06, 1.10, 1.14, 0.78, 1.10, 1.00, 1.00, 1.10),
        // the Korean school year starts in March; summer vacation July-August
        Country.KR to doubleArrayOf(0.82, 0.88, 1.02, 1.00, 1.06, 1.06, 1.14, 1.12, 1.04, 1.00, 1.00, 1.08),
    )

    fun seasonality(country: Country, date: LocalDate): Double {
        val v = interpolateMonthly(seasonAnchors.getValue(country), date)
        val (lo, hi) = model.traffic.seasonalityRange.let { it[0] to it[1] }
        return v.coerceIn(lo, hi)
    }

    // ── promos ────────────────────────────────────────────────────────────────

    val promos: List<Promo> = listOf(
        Promo(LocalDate.of(2026, 3, 16), "Spring Sports Days"),
        Promo(LocalDate.of(2026, 6, 8), "Summer Sports Week"),
        Promo(LocalDate.of(2026, 9, 7), "Back-to-School Sports"),
    )

    fun promoOn(date: LocalDate): Promo? = promos.firstOrNull { it.contains(date) }

    /** The week right after a promo — fixtures were moved for it, so compliance dips and remediation tasks spike. */
    fun weekAfterPromo(date: LocalDate): Boolean = promos.any { !date.isBefore(it.end.plusDays(1)) && !date.isAfter(it.end.plusDays(7)) }

    /** The week right before a promo — pre-staging stock. */
    fun weekBeforePromo(date: LocalDate): Boolean = promos.any { !date.isBefore(it.start.minusDays(7)) && date.isBefore(it.start) }

    // ── holidays ──────────────────────────────────────────────────────────────

    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)
    private fun open(shape: DayType, factor: Double, name: String) = Holiday(false, shape, factor, name)
    private fun closed(name: String) = Holiday(true, DayType.WEEKEND, 0.0, name)

    private val holidays: Map<Country, Map<LocalDate, Holiday>> = mapOf(
        Country.US to mapOf(
            d(1, 1) to open(DayType.WEEKEND, 0.55, "New Year's Day"),
            d(2, 16) to open(DayType.WEEKEND, 0.90, "Presidents' Day"),
            d(4, 5) to open(DayType.WEEKEND, 0.55, "Easter Sunday"),
            d(5, 25) to open(DayType.WEEKEND, 1.05, "Memorial Day"),
            d(7, 3) to open(DayType.WEEKEND, 0.85, "Independence Day (observed)"),
            d(7, 4) to open(DayType.WEEKEND, 0.65, "Independence Day"),
            d(9, 7) to open(DayType.WEEKEND, 1.05, "Labor Day"),
        ),
        Country.FR to mapOf(
            d(1, 1) to closed("Jour de l'An"),
            d(4, 6) to open(DayType.WEEKEND, 0.80, "Lundi de Pâques"),
            d(5, 1) to closed("Fête du Travail"),
            d(5, 8) to open(DayType.WEEKEND, 0.85, "Victoire 1945"),
            d(5, 14) to open(DayType.WEEKEND, 0.90, "Ascension"),
            d(5, 25) to open(DayType.WEEKEND, 0.80, "Lundi de Pentecôte"),
            d(7, 14) to open(DayType.WEEKEND, 0.60, "Fête nationale"),
            d(8, 15) to open(DayType.WEEKEND, 0.70, "Assomption"),
        ),
        Country.KR to mapOf(
            d(1, 1) to open(DayType.WEEKEND, 0.70, "New Year's Day"),
            d(2, 16) to open(DayType.WEEKEND, 0.60, "Seollal holiday"),
            d(2, 17) to closed("Seollal"),
            d(2, 18) to open(DayType.WEEKEND, 0.75, "Seollal holiday"),
            d(3, 2) to open(DayType.WEEKEND, 0.85, "Samiljeol (substitute)"),
            d(5, 5) to open(DayType.WEEKEND, 1.15, "Children's Day"),
            d(5, 25) to open(DayType.WEEKEND, 0.85, "Buddha's Birthday (substitute)"),
            d(6, 3) to open(DayType.WEEKEND, 0.85, "Local elections"),
            d(8, 15) to open(DayType.WEEKEND, 0.90, "Liberation Day"),
            d(9, 24) to open(DayType.WEEKEND, 0.55, "Chuseok holiday"),
            d(9, 25) to closed("Chuseok"),
            d(9, 26) to open(DayType.WEEKEND, 0.70, "Chuseok holiday"),
            d(10, 3) to open(DayType.WEEKEND, 0.95, "Gaecheonjeol"),
        ),
    )

    fun holiday(store: ChainKeys.Store, date: LocalDate): Holiday? = holidays.getValue(country(store))[date]

    fun isOpen(store: ChainKeys.Store, date: LocalDate): Boolean = holiday(store, date)?.closed != true

    fun dayType(store: ChainKeys.Store, date: LocalDate): DayType = holiday(store, date)?.shape
        ?: if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) DayType.WEEKEND else DayType.WEEKDAY

    /**
     * The Korean pre-holiday surge (gift shopping, the five days before Seollal and Chuseok) and Busan's
     * hometown inflow over Chuseok itself. 1.0 elsewhere.
     */
    private fun koreanHolidayEffect(store: ChainKeys.Store, date: LocalDate): Double {
        if (country(store) != Country.KR) return 1.0
        val busan = store.slug == "dec-kr-busan"
        fun before(holiday: LocalDate) = date.isBefore(holiday) && !date.isBefore(holiday.minusDays(5))
        return when {
            before(d(2, 16)) || before(d(9, 24)) -> if (busan) 1.12 else 1.25
            busan && (date == d(9, 24) || date == d(9, 26)) -> 1.25
            else -> 1.0
        }
    }

    // ── store stories ─────────────────────────────────────────────────────────

    /** (visits multiplier, conversion multiplier) — the store's own story, before seasonality and promos. */
    fun storeStory(slug: String, date: LocalDate): Pair<Double, Double> = when (slug) {
        "dec-us-denver" -> 1.03 to 1.06

        // the best store in the US

        "dec-us-nyc" -> 0.97 to 0.95

        "dec-us-sf" -> 0.94 to 0.98

        "dec-fr-paris" -> 1.02 to 1.00

        "dec-fr-lyon" -> 1.00 to 1.02

        "dec-fr-bordeaux" -> 1.00 to 0.98

        "dec-fr-marseille" -> 0.90 to 0.86

        // consistently below plan

        "dec-fr-lille" -> {
            // Q3 turnaround: below plan through H1, a new store manager in late June, above plan by August.
            val t = turnaround(date)
            lerp(0.92, 1.05, t) to lerp(0.88, 1.06, t)
        }

        "dec-kr-seoul" -> 1.00 to 1.00

        "dec-kr-busan" -> 0.98 to 1.00

        else -> 1.0 to 1.0
    }

    /** 0 → 1 logistic centred on 20 July, ~3 weeks wide. */
    fun turnaround(date: LocalDate): Double {
        val x = ChronoUnit.DAYS.between(d(7, 20), date).toDouble()
        return 1.0 / (1.0 + exp(-x / 7.0))
    }

    // ── category seasonality + the slow category ─────────────────────────────

    private val departmentAnchors: Map<String, DoubleArray> = mapOf(
        "snow" to doubleArrayOf(1.80, 1.60, 1.00, 0.50, 0.30, 0.30, 0.30, 0.30, 0.50, 0.90, 1.40, 1.80),
        "cycling" to doubleArrayOf(0.60, 0.70, 1.00, 1.30, 1.40, 1.40, 1.30, 1.10, 1.00, 0.80, 0.70, 0.70),
        "hike_camp" to doubleArrayOf(0.70, 0.80, 0.95, 1.10, 1.25, 1.35, 1.40, 1.25, 1.00, 0.90, 0.80, 0.80),
        "running" to doubleArrayOf(1.20, 1.05, 1.15, 1.15, 1.10, 1.00, 0.90, 0.95, 1.15, 1.10, 1.00, 1.00),
        "water" to doubleArrayOf(0.60, 0.60, 0.70, 0.90, 1.20, 1.60, 1.70, 1.60, 1.00, 0.70, 0.60, 0.60),
        // THE SLOW CATEGORY — Climbing & Mountaineering under-sells everywhere, and slips through the year.
        "climb" to doubleArrayOf(0.66, 0.65, 0.64, 0.63, 0.62, 0.61, 0.60, 0.60, 0.59, 0.58, 0.58, 0.58),
    )

    /** The chain-wide slow department — named so the self-test and the RESULTS file can point at it. */
    val slowDepartment = "climb"

    /** Relative line-acceptance weight per department slug on [date]; departments absent here weigh 1.0. */
    fun departmentWeights(date: LocalDate): Map<String, Double> = departmentAnchors.mapValues { interpolateMonthly(it.value, date) }

    // ── the day, assembled ────────────────────────────────────────────────────

    fun areaScale(store: ChainKeys.Store): Double = store.areaSqm / referenceAreaSqm

    /**
     * Everything that scales a store-day's footfall EXCEPT the day-type base (weekday/weekend volume, which
     * [com.m8trx.twin.layer3.TrafficGenerator] applies itself) and EXCEPT the seasonality (passed separately
     * as the generator's `seasonalityFactor`) and the daily weather noise.
     */
    fun visitMultiplier(store: ChainKeys.Store, date: LocalDate, withStory: Boolean = true): Double {
        val h = holiday(store, date)
        val promo = if (promoOn(date) != null) PROMO_VISITS else 1.0
        val story = if (withStory) storeStory(store.slug, date).first else 1.0
        return areaScale(store) * (h?.factor ?: 1.0) * promo * koreanHolidayEffect(store, date) * story
    }

    fun conversionMultiplier(store: ChainKeys.Store, date: LocalDate, withStory: Boolean = true): Double {
        val promo = if (promoOn(date) != null) PROMO_CONVERSION else 1.0
        val story = if (withStory) storeStory(store.slug, date).second else 1.0
        return promo * story
    }

    /** Expected visitors on an open store-day, before weather noise — the analytic twin of the generated count. */
    fun expectedVisitors(store: ChainKeys.Store, date: LocalDate, withStory: Boolean = true): Double {
        if (!isOpen(store, date)) return 0.0
        val base = when (dayType(store, date)) {
            DayType.WEEKDAY -> model.traffic.visitorsPerDay.weekday
            DayType.WEEKEND -> model.traffic.visitorsPerDay.weekend
        }.toDouble()
        return base * seasonality(country(store), date) * visitMultiplier(store, date, withStory)
    }

    private fun interpolateMonthly(anchors: DoubleArray, date: LocalDate): Double {
        // anchor i sits on the 15th of month i+1
        val mid = LocalDate.of(date.year, date.monthValue, 15)
        val (a, b) = if (date.isBefore(mid)) mid.minusMonths(1) to mid else mid to mid.plusMonths(1)
        val span = ChronoUnit.DAYS.between(a, b).toDouble()
        val t = ChronoUnit.DAYS.between(a, date) / span
        return lerp(anchors[a.monthValue - 1], anchors[b.monthValue - 1], t)
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    companion object {
        const val PROMO_VISITS = 1.35
        const val PROMO_CONVERSION = 1.20
    }
}
