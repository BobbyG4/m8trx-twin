package com.m8trx.twin.livefloor

import com.m8trx.twin.connect.DatedStream
import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.storeday.DayResult
import com.m8trx.twin.storeday.PeopleRest
import com.m8trx.twin.storeday.StoreDaySim
import com.m8trx.twin.storeday.buildSim
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * One store-day on the floor: the shoppers the twin's own store-day model made (each [Walker.objectId] is that shopper's REST `trackingId`)
 * and the floor staff. [openMs]/[closeMs] are the store's opening hours that day (0 on a closed day).
 */
class DayWalk(
    val slug: String,
    val date: LocalDate,
    val customers: List<Walker>,
    val staff: List<Walker>,
    val openMs: Long,
    val closeMs: Long,
    val restTrackingIds: Set<String>,
    val result: DayResult,
    val lines: List<PeopleRest.Line>,
    val stats: WalkStats,
    val graph: AisleGraph,
) {
    val everyone: List<Walker> get() = customers + staff
}

/**
 * Plans a store-day's people for the live floor map. The visits are NOT planned here: they are the store-day model's own
 * (`StoreDaySim.simulate(initialState, date, behavior seed)`), the same call and seed the people-by-REST dry run, live sender and backfill make,
 * and [PeopleRest.plan] supplies the ids (so a walker's `objectId` is, by construction, the REST `trackingId`). This class only gives those
 * visits legs, speeds and a body.
 */
class LiveFloorPlanner(
    private val chainDir: Path,
    private val keys: ChainKeys,
    private val siteIds: Map<String, String>,
    /** An in-memory change to each floor (the diagnosis "what if"); the default leaves it as layout.json has it. */
    private val edit: (StoreFloor) -> StoreFloor = { it },
) {
    // planning is confined to one thread at a time (the graph keeps search state) and the caches are concurrent maps
    private val floors = java.util.concurrent.ConcurrentHashMap<String, AisleGraph>()
    private val sims = java.util.concurrent.ConcurrentHashMap<String, StoreDaySim>()

    fun graph(slug: String): AisleGraph = floors.computeIfAbsent(slug) { AisleGraph(edit(StoreFloor.load(slug, chainDir))) }

    private fun sim(slug: String): StoreDaySim = sims.computeIfAbsent(slug) { buildSim(slug) }

    @Synchronized
    fun plan(slug: String, date: LocalDate): DayWalk {
        val sim = sim(slug)
        val r = sim.simulate(sim.initialState(), date, sim.behavior.params.seed)
        return plan(slug, date, r)
    }

    @Synchronized
    fun plan(slug: String, date: LocalDate, r: DayResult): DayWalk {
        val graph = graph(slug)
        val lines = PeopleRest.plan(slug, date, r, PeopleRest.idsFor(slug, keys, siteIds))
        val trackingByShopper = lines.filter { it.kind == "person_begin" }.associate { it.shopper to it.body.path("trackingId").asText() }
        val planner = WalkPlanner(graph, slug, date)
        val ordered = r.records.sortedBy { trackingByShopper.getValue(it.id) }
        val genders = genders(slug, date, ordered.size)
        val customers = ordered.mapIndexed { i, rec -> planner.customer(rec, trackingByShopper.getValue(rec.id), genders[i]) }
            .sortedBy { it.tEnterMs }
        var open = 0L
        var close = 0L
        r.plan.shape?.let { sh ->
            val z = ZoneId.of(keys.store(slug).zone.id)
            open = ZonedDateTime.of(date, sh.open, z).toInstant().toEpochMilli()
            close = open + (sh.lengthHours * 3_600_000).toLong()
        }
        val staff = if (open == 0L) {
            emptyList()
        } else {
            planner.staff(staffCount(keys.store(slug).areaSqm), open, close, planner.helpRequests(customers))
        }
        return DayWalk(slug, date, customers, staff, open, close, trackingByShopper.values.toSet(), r, lines, planner.stats, graph)
    }

    companion object {
        /** About one on the floor per 150 m2, two at least and five at most. */
        fun staffCount(areaSqm: Double): Int = (areaSqm / 150.0).roundToInt().coerceIn(2, 5)

        /** Female / male / unknown at 60 / 30 / 10 % exactly (to the person), shuffled by a seed of (slug, date). `null` = unknown. */
        fun genders(slug: String, date: LocalDate, n: Int): List<Boolean?> {
            val f = (n * 0.6).roundToInt()
            val m = (n * 0.3).roundToInt()
            val all = ArrayList<Boolean?>()
            repeat(f) { all += false }
            repeat(m) { all += true }
            repeat(max(0, n - f - m)) { all += null }
            all.shuffle(Random(seedOf("gender", slug, date.toString())))
            return all.take(n)
        }

        fun siteIds(chainDir: Path): Map<String, String> = DatedStream.readSiteIds(chainDir.resolve("site_ids.csv"))
    }
}
