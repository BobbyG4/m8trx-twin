package com.m8trx.twin.ops

import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.history.HistoryCsv
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.random.Random

/**
 * The `fixmoves` step's generator — pure and deterministic in (seed, store, ISO week, the offsets already applied).
 * Per store per week it plans [perWeek] fixture moves spread over Monday–Saturday: one wall fixture each, nudged 250–500 mm
 * along one floor axis (a merchandiser re-spacing an aisle, not a re-plan), and never more than [maxOffsetMm] from where
 * the platform had it when the twin first touched it — a move that would cross that bound goes the other way.
 *
 * The plan is per WEEK so the daily run for any date executes exactly that date's share and the week adds up to
 * [perWeek] whatever day the driver starts on. Fixtures are distinct within a week.
 */
object FixtureMoves {
    data class Move(
        val extId: String,
        val slug: String,
        val code: String,
        val zoneId: String,
        val date: LocalDate,
        val dxMm: Double,
        val dyMm: Double,
    )

    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * [offsets] = zoneId → (dx, dy) already applied by earlier weeks' moves (the drive's `offsets.csv`). Moves in the
     * same week compound in order, so the bound holds after each one.
     */
    fun week(
        store: ChainKeys.Store,
        anyDayOfWeek: LocalDate,
        seed: Long,
        perWeek: Int,
        offsets: Map<String, Pair<Double, Double>>,
        maxOffsetMm: Double = 600.0,
    ): List<Move> {
        val monday = weekStart(anyDayOfWeek)
        val wk = "${monday.get(IsoFields.WEEK_BASED_YEAR)}-W%02d".format(monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
        val rng = Random(HistoryCsv.derive(seed, store.slug, wk, "fixmoves"))
        val walls = store.fixtures.filter { !it.department.isNullOrBlank() }.sortedBy { it.code }.shuffled(rng)
        val days = List(perWeek) { monday.plusDays(rng.nextInt(6).toLong()) }.sorted()
        val running = HashMap(offsets)
        return walls.take(perWeek).mapIndexed { i, z ->
            val mag = 250.0 + rng.nextInt(251)
            val alongX = rng.nextBoolean()
            var sign = if (rng.nextBoolean()) 1.0 else -1.0
            val (cx, cy) = running[z.zoneId] ?: (0.0 to 0.0)
            val cur = if (alongX) cx else cy
            if (abs(cur + sign * mag) > maxOffsetMm) sign = -sign
            val dx = if (alongX) sign * mag else 0.0
            val dy = if (alongX) 0.0 else sign * mag
            running[z.zoneId] = (cx + dx) to (cy + dy)
            val date = days[i]
            Move("FM-${store.slug}-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}-${z.code}", store.slug, z.code, z.zoneId, date, dx, dy)
        }
    }

    fun forDate(store: ChainKeys.Store, date: LocalDate, seed: Long, perWeek: Int, offsets: Map<String, Pair<Double, Double>>): List<Move> =
        week(store, date, seed, perWeek, offsets).filter { it.date == date }

    /** Translate every vertex of a GeoJSON polygon ring set by (dx, dy); z and extra members are kept. */
    fun translate(coordinates: List<List<List<Double>>>, dx: Double, dy: Double): List<List<List<Double>>> =
        coordinates.map { ring -> ring.map { v -> listOf(v[0] + dx, v[1] + dy) + v.drop(2) } }
}
