package com.m8trx.twin.storeday

import com.m8trx.twin.behavior.BehaviorModel
import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.history.StoryCalendar
import com.m8trx.twin.layer3.TrafficGenerator.DayType
import java.time.LocalDate
import java.time.LocalTime

/** What the calendar says about one store-day. [shape] is null when the store is closed (or when the calendar is off). */
class DayPlan(
    val closed: Boolean,
    val shape: BehaviorModel.DayShape?,
    /** Multiplies every buy chance: promo-week and store-story conversion. 1.0 when the calendar is off. */
    val conversion: Double,
    val holiday: String?,
    val promo: String?,
    val seasonality: Double,
    val visitMultiplier: Double,
)

/**
 * Slice 2b: the history's own calendar ([StoryCalendar]) applied per store and date, so live days continue the January to
 * October history's shape rather than the behavior model's synthetic week wave:
 *
 *  - **closed days** (French 1 Jan and 1 May, Seollal, Chuseok): no store day at all, zero visitors, zero sales;
 *  - **holidays that open**: the weekend curve and the holiday's volume factor;
 *  - **promo weeks**: +35 % visits and +20 % conversion ([StoryCalendar.PROMO_VISITS], [StoryCalendar.PROMO_CONVERSION]);
 *  - **store stories**: `storeStory` visits and conversion (Denver best in the US, Marseille below plan, Lille's July turnaround);
 *  - **country seasonality** (the August dip in France, the Korean school year) and the Korean pre-holiday surge.
 *
 * Expected visitors = weekday-or-weekend base x the behavior file's day-of-week factor (not on a holiday, whose shape is the
 * factor) x country seasonality x [StoryCalendar.visitMultiplier] (area scale, holiday, promo, Korean effect, story) x the
 * profile's `location_factor`. With [enabled] false the day is the behavior model's own (2a), scaled by the store's footfall.
 */
class DayOverlay(private val model: StoreDayModel, private val behavior: BehaviorModel, val enabled: Boolean = true) {
    private val keys = ChainKeys.load()
    private val store = keys.store(model.slug)
    private val story = StoryCalendar(behavior.model, keys.store(StoreState.STORE_SLUGS.first()).areaSqm)

    /** The story calendar's relative weight per department on [date] (the season): slice 2c's plan changes follow it. */
    fun departmentWeights(date: LocalDate): Map<String, Double> = story.departmentWeights(date)

    fun plan(date: LocalDate): DayPlan {
        val hours = model.hoursOn(date.dayOfWeek)
        if (hours == null || (enabled && !story.isOpen(store, date))) {
            return DayPlan(true, null, 1.0, story.holiday(store, date)?.name, story.promoOn(date)?.name, 1.0, 0.0)
        }
        val (open, close) = hours
        val hol = if (enabled) story.holiday(store, date) else null
        val weekend = if (enabled) story.dayType(store, date) == DayType.WEEKEND else date.dayOfWeek.value >= 6
        val base = behavior.model.traffic.visitorsPerDay.let { if (weekend) it.weekend else it.weekday }.toDouble()
        val dayFactor = if (hol != null) 1.0 else behavior.params.traffic.dayFactor(date)
        val season = if (enabled) story.seasonality(story.country(store), date) else behavior.params.traffic.seasonality(date)
        val visit = if (enabled) story.visitMultiplier(store, date) else 1.0
        val lift = if (date.dayOfWeek == java.time.DayOfWeek.SATURDAY) model.saturdayLift else 1.0
        val expected = base * dayFactor * season * visit * (if (enabled) model.locationFactor else model.visitScale) * lift
        val conv = if (enabled) story.conversionMultiplier(store, date) else 1.0
        return DayPlan(
            false,
            BehaviorModel.DayShape(expected, curveFor(weekend, open, close), open, weekend, lengthHours(open, close)),
            conv,
            hol?.name,
            if (enabled) story.promoOn(date)?.name else null,
            season,
            visit,
        )
    }

    private fun lengthHours(open: LocalTime, close: LocalTime): Double = (close.toSecondOfDay() - open.toSecondOfDay()) / 3600.0

    /**
     * The model's hourly curve (one share per hour of its own 11-hour day) stretched or compressed onto the store's actual window,
     * keeping its shape (morning ramp, midday and afternoon peaks, evening tail) and its total: each window hour takes the integral
     * of the model's piecewise-constant density over the span it maps to, and the last bucket is partial when the window is not a
     * whole number of hours. Re-normalised to 1. Identical to the model's own curve for an 11-hour window.
     */
    private fun curveFor(weekend: Boolean, open: LocalTime, close: LocalTime): List<Double> {
        val c = if (weekend) behavior.model.traffic.hourlyCurve.weekend else behavior.model.traffic.hourlyCurve.weekday
        val len = lengthHours(open, close)
        require(len > 0.0) { "${model.slug}: opening hours $open-$close are empty" }
        return stretchCurve(c, len)
    }
}

/** See [DayOverlay]: [c] (one share per hour of the model's day) laid over a window of [len] hours; one bucket per started hour. */
internal fun stretchCurve(c: List<Double>, len: Double): List<Double> {
    if (kotlin.math.abs(len - c.size) < 1e-9) return c
    val n = kotlin.math.ceil(len - 1e-9).toInt()
    val k = c.size / len // model hours per window hour
    val raw = (0 until n).map { j ->
        val from = j * k
        val to = minOf((j + 1).toDouble(), len) * k
        var acc = 0.0
        var x = from
        while (x < to - 1e-12) {
            val cell = minOf(x.toInt(), c.size - 1)
            val end = minOf(to, (cell + 1).toDouble())
            acc += c[cell] * (end - x)
            x = end
        }
        acc
    }
    val total = raw.sum()
    return raw.map { it / total }
}
