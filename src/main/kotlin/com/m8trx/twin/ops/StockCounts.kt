package com.m8trx.twin.ops

import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.history.HistoryCsv
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.random.Random

/**
 * The `stock` step's generator — pure and deterministic in (seed, store, date, the store's stock). Per store per day it
 * plans 1–2 real cloud counts on 2–4 wall fixtures each (or, on the full-floor date, one count over every stocked wall
 * fixture), and derives each fixture's READ SET from the store's stock so the platform's own reconcile closes the count
 * with a few `missing` items and 1–2 `over` items:
 *
 *  - **read**: every in-stock unit the stock places on the fixture, once, on its own fixture, 3–9 reads at −45…−64 dBm.
 *    Never a blip (the count rule's D18: a tag under 0.2 of its pull's median read count never moves an item), never on
 *    two racks (no bleed, no tie → no `check`).
 *  - **missing**: 2–4 units of the counted fixtures (6–10 on the full floor) are simply not read. The reconcile marks
 *    them `missing` (an under row each) because they are in stock on a zone in the count's scope.
 *  - **over**: 1–2 units homed OUTSIDE the scope are read strongly on a counted fixture (5–9 reads at −48…−55 dBm): the
 *    first from another wall fixture when one is outside the scope (the platform also lists it as misplaced), the rest from
 *    the stockroom. The count rule moves them (their home was not pulled and has no recent sighting), and the reconcile
 *    writes an `over` row (needs migration 272 on mother; without it the reconcile skips over rows with a WARN).
 *
 * Plus 1–2 floor notes per count (3–4 on the full floor) as `stocktake_observation` rows on counted fixtures.
 *
 * Only EPCs that resolve to an item of the tenant are ever read (D39 ignores the rest), and the scope is declared
 * (`scopeZoneIds` = the counted fixtures), so nothing outside it can go missing.
 *
 * Ids are name-based UUIDs (`twin-stock|<slug>|<date>|<seed>|<k>`), so `POST /count` with the same `countId` is a no-op
 * re-open and a re-sent pull is a no-op replay on `(countId, pullId, seq)` — a re-run never makes a second count.
 */
object StockCounts {
    const val FRAME_MAX_TAGS = 250
    private val DAY_START: LocalTime = LocalTime.of(8, 30)
    private val FULL_FLOOR_START: LocalTime = LocalTime.of(7, 0)

    data class Tag(val epc: String, val readCount: Int, val rssiBest: Int, val rssiMean: Double, val firstSeenAt: Instant, val lastSeenAt: Instant)

    data class Frame(val seq: Int, val startedAt: Instant, val endedAt: Instant, val tags: List<Tag>)

    data class FixturePull(val code: String, val zoneId: String, val pullId: String, val frames: List<Frame>) {
        val startedAt: Instant get() = frames.first().startedAt
        val endedAt: Instant get() = frames.last().endedAt
        val epcs: List<String> get() = frames.flatMap { f -> f.tags.map { it.epc } }
    }

    data class Over(val epc: String, val fromFixture: String, val toFixture: String)

    data class Missing(val epc: String, val fixture: String)

    /** A floor note taken during the walk — `POST /stocktake/sessions/{countId}/observations` (the count IS the session). */
    data class Observation(val code: String, val zoneId: String, val type: String, val severity: String, val note: String)

    private val NOTES = listOf(
        Triple("display_condition", "warning", "Facing untidy, sizes mixed on the front row"),
        Triple("display_condition", "info", "Shelf strip missing on the lower bay"),
        Triple("damaged_tag", "warning", "Two tags torn on the hanging rail"),
        Triple("slow_moving", "info", "Same units as last week, no movement"),
        Triple("pick_required", "info", "Back-up stock needed, front row thin"),
    )

    data class CountPlan(
        val countId: String,
        val slug: String,
        val date: LocalDate,
        val k: Int,
        val fullFloor: Boolean,
        val pulls: List<FixturePull>,
        val missing: List<Missing>,
        val over: List<Over>,
        val observations: List<Observation> = emptyList(),
    ) {
        val scopeZoneIds: List<String> get() = pulls.map { it.zoneId }
        val startedAt: Instant get() = pulls.first().startedAt
        val endedAt: Instant get() = pulls.last().endedAt
        val reads: Int get() = pulls.sumOf { it.epcs.size }
    }

    fun uuid(vararg parts: Any): String = UUID.nameUUIDFromBytes(parts.joinToString("|").toByteArray()).toString()

    /**
     * Plan [store]'s counts for [date]. [stock] is what the store holds (already narrowed to the platform's in-stock set on
     * a live run). Fixtures with fewer than [minUnits] units are not counted (nothing to count, nothing to miss).
     */
    fun plan(store: ChainKeys.Store, date: LocalDate, seed: Long, stock: StoreStock, fullFloor: Boolean, minUnits: Int = 4): List<CountPlan> {
        val rng = Random(HistoryCsv.derive(seed, store.slug, date, "stock"))
        val zoneOf = store.fixtures.associate { it.code to it.zoneId }
        val eligible = stock.wall.filter { (code, units) -> code in zoneOf && units.size >= minUnits }.keys.sorted()
        if (eligible.isEmpty()) return emptyList()
        val counts = if (fullFloor) 1 else 1 + rng.nextInt(2)
        val pool = eligible.shuffled(rng).toMutableList()
        val out = ArrayList<CountPlan>()
        for (k in 0 until counts) {
            val chosen = if (fullFloor) {
                eligible
            } else {
                val n = (2 + rng.nextInt(3)).coerceAtMost(pool.size)
                if (n < 2) break
                List(n) { pool.removeAt(0) }.sorted()
            }
            out += planOne(store, date, seed, k, chosen, zoneOf, stock, fullFloor, rng, eligible)
        }
        return out
    }

    private fun planOne(
        store: ChainKeys.Store,
        date: LocalDate,
        seed: Long,
        k: Int,
        chosen: List<String>,
        zoneOf: Map<String, String>,
        stock: StoreStock,
        fullFloor: Boolean,
        rng: Random,
        eligible: List<String>,
    ): CountPlan {
        val countId = uuid("twin-stock", store.slug, date, seed, k)
        // missing: drawn from the counted fixtures, each fixture keeps at least 2 units read
        val nMissing = if (fullFloor) 6 + rng.nextInt(5) else 2 + rng.nextInt(3)
        val candidates = chosen.flatMap { f -> stock.wall.getValue(f).drop(2).map { f to it } }.shuffled(rng)
        val missing = candidates.take(nMissing).map { (f, e) -> Missing(e, f) }.sortedBy { it.epc }
        val missingSet = missing.map { it.epc }.toSet()
        // over: homed outside the scope — another wall fixture first (also misplaced), then the stockroom
        val nOver = if (fullFloor) 2 + rng.nextInt(2) else 1 + rng.nextInt(2)
        val outside = (eligible - chosen.toSet()).flatMap { f -> stock.wall.getValue(f).map { f to it } }
        val boh = stock.byFixture.filterKeys { StoreStock.isBoh(it) }.flatMap { (f, us) -> us.map { f to it } }
        val overSources = buildList {
            if (outside.isNotEmpty()) add(outside[rng.nextInt(outside.size)])
            val bohPick = boh.shuffled(rng)
            addAll(bohPick.take(nOver - size))
            if (size < nOver && outside.size > 1) addAll(outside.shuffled(rng).filter { it !in this }.take(nOver - size))
        }.take(nOver)
        val over = overSources.map { (from, e) -> Over(e, from, chosen[rng.nextInt(chosen.size)]) }

        val local = ZonedDateTime.of(date, if (fullFloor) FULL_FLOOR_START else DAY_START.plusMinutes(75L * k), store.zone).toInstant()
        var t = local
        val pulls = chosen.map { code ->
            val read = stock.wall.getValue(code).filter { it !in missingSet }
            val tags = read.map { it to false } + over.filter { it.toFixture == code }.map { it.epc to true }
            val seconds = 60L + tags.size / 4
            val pullStart = t
            val pullEnd = t.plusSeconds(seconds)
            t = pullEnd.plusSeconds(20)
            val pullId = uuid("twin-stock-pull", countId, code)
            val chunks = tags.chunked(FRAME_MAX_TAGS)
            val span = seconds / chunks.size
            val frames = chunks.mapIndexed { seq, chunk ->
                val fs = pullStart.plusSeconds(span * seq)
                val fe = if (seq == chunks.lastIndex) pullEnd else fs.plusSeconds(span)
                Frame(seq, fs, fe, chunk.map { (epc, isOver) -> tag(epc, isOver, fs, fe, rng) })
            }
            FixturePull(code, zoneOf.getValue(code), pullId, frames)
        }
        val nObs = if (fullFloor) 3 + rng.nextInt(2) else 1 + rng.nextInt(2)
        val observations = chosen.shuffled(rng).take(nObs).map { code ->
            val (type, sev, note) = NOTES[rng.nextInt(NOTES.size)]
            Observation(code, zoneOf.getValue(code), type, sev, note)
        }
        return CountPlan(countId, store.slug, date, k, fullFloor, pulls, missing, over, observations)
    }

    private fun tag(epc: String, over: Boolean, from: Instant, to: Instant, rng: Random): Tag {
        val reads = if (over) 5 + rng.nextInt(5) else 3 + rng.nextInt(7)
        val rssi = if (over) -48 - rng.nextInt(8) else -45 - rng.nextInt(20)
        val span = (to.epochSecond - from.epochSecond).coerceAtLeast(1)
        val first = from.plusSeconds(rng.nextLong(span))
        val last = first.plusSeconds(rng.nextLong((to.epochSecond - first.epochSecond).coerceAtLeast(0) + 1))
        return Tag(epc, reads, rssi, rssi - 3.5, first, last)
    }
}
