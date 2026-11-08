package com.m8trx.twin.behavior

import com.m8trx.twin.layer1.BrowseEpisode
import com.m8trx.twin.layer1.Fixture
import com.m8trx.twin.layer1.ImpressionOracle
import com.m8trx.twin.layer1.Pt
import com.m8trx.twin.layer1.ZoneAffinityModel
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * What a shopper did at one place. [impression] is the agent's claim that this stop forms one; the oracle checks
 * it. [dwellMs] is the ENGAGED (looking) span. The episode adds [ShopperAgent.APPROACH_MS] before it and
 * [ShopperAgent.DISENGAGE_MS] after, so the platform's impression duration (`lastLook - firstDwell`) is about
 * `dwellMs + APPROACH_MS` and its dwell span about `dwellMs + APPROACH_MS + DISENGAGE_MS`. A TRY_ON visit names
 * the cell tried in [triedCell]; the pickup itself is recorded once, at the fixture it came from.
 */
data class Visit(
    val fixture: String,
    val kind: Kind,
    val startMs: Long,
    val dwellMs: Long,
    val impression: Boolean,
    val pickups: List<Pickup>,
    val triedCell: Cell? = null,
) {
    enum class Kind { BROWSE, TRY_ON, CHECKOUT, GRAB }
}

/** A cell picked up (engagement). [bought] is decided after any try-on. */
data class Pickup(val cell: Cell, val tried: Boolean, val bought: Boolean)

/**
 * One sale line, carrying the fixture and level it was taken from (requirement 3). [noLook] marks the stated
 * exceptions to "no sale without a prior impression on that fixture": the gift persona's quick grab and the
 * checkout impulse add-on.
 */
data class SoldLine(val cell: Cell, val noLook: Boolean)

/** The oracle's whole account of one shopper — the link a real store keeps through a loyalty or receipt id. */
data class ShopperRecord(
    val id: String,
    val persona: String,
    val planId: String,
    val entryMs: Long,
    val exitMs: Long,
    val visits: List<Visit>,
    val lines: List<SoldLine>,
) {
    val bought: Boolean get() = lines.isNotEmpty()
    val revenueUsd: Double get() = lines.sumOf { it.cell.priceUsd }
}

/**
 * Layer 2 — **one shopper, one causal chain** (TWIN-REQ-007 requirement 8).
 *
 * The existing `Journeys` drew a basket first and then walked to the fixtures that held it, so the look followed
 * the sale. Here the order is the store's: the shopper picks which fixtures to stop at from what is ON them
 * ([FixturePresentation]), looks and dwells (a real 5 Hz [BrowseEpisode], so the edge forms the impression),
 * picks cells up, maybe tries them on, and only then decides whether to buy what is in hand. Every sale line is
 * therefore a cell this shopper handled at that fixture, and impressions, engagement and sales for a fixture all
 * come from the same people, so a correlation in the data is real.
 *
 * Determinism: the agent is handed one [Random] seeded from (run seed, date, arrival index), takes one seed from
 * it, and derives a separate stream per decision from that seed (see `run`), so the same inputs replay to the
 * sample and a change at one fixture re-randomises only that fixture's decisions.
 */
class ShopperAgent(
    private val world: StoreWorld,
    private val params: BehaviorParams,
    private val browse: BrowseEpisode,
    private val categoryMix: Map<String, Double>,
    /**
     * Σ zone-role weight × content appeal over the PUBLISHED plan's browsable fixtures, truths excluded. A fixed
     * constant per site, so a truth that lifts one fixture's weight lifts its stop chance by exactly that factor
     * instead of being renormalised away (which is what a fixed-size draw does).
     */
    private val stopNormalizer: Double,
) {
    fun run(
        id: String,
        persona: String,
        entryMs: Long,
        plan: ShelfPlan,
        pres: Map<String, FixturePresentation>,
        rng: Random,
        sink: SampleSink?,
    ): ShopperRecord {
        val visits = mutableListOf<Visit>()
        val lines = mutableListOf<SoldLine>()
        var t = entryMs

        // Common random numbers: every decision draws from its OWN stream, keyed by what it decides (this
        // fixture's stop, this fixture's visit, the checkout ...). Changing one fixture (a truth, a plan move)
        // changes only the decisions that read that fixture, and an untouched control fixture sees the same
        // shoppers decide the same things. One shared stream would re-randomise everything after the first
        // changed draw, and every before/after would compare two different samples.
        val seed = rng.nextLong()
        fun stream(key: String) = Random(BehaviorModel.mix(seed, key.hashCode().toLong(), key.length.toLong()))
        val jitter = HashMap<String, Double>()
        fun noise(code: String) = jitter.getOrPut(code) {
            if (params.noise.appealSd == 0.0) 1.0 else exp(params.noise.appealSd * gaussian(stream("noise:$code")))
        }

        t = walk(id, t, inward = true, rng = stream("door-in"), sink = sink)

        // ── entrance table: everyone passes it; a minority stops ──────────────────────────────────────────
        for (f in world.fixturesIn(Placement.ENTRANCE_TABLE)) {
            val p = pres.getValue(f.code)
            val stop = params.browse.entranceStopRate * p.contentAppeal * p.impressionFactor * noise(f.code)
            if (persona != "gift" && stream("stop:${f.code}").nextDouble() < min(params.engagement.maxProbability, stop)) {
                t = browseAt(id, persona, f, p, t, stream("visit:${f.code}"), sink, visits, lines)
            }
        }

        if (persona == "gift") {
            t = giftGrab(id, pres, t, stream("gift"), ::stream, sink, visits, lines)
        } else {
            val target = if (persona == "mission" && categoryMix.isNotEmpty()) weightedPick(categoryMix, stream("target")) else null
            val stops = pickStops(persona, pres, target, stream("stops"), ::stream, ::noise)
            for (f in stops) {
                if (persona == "mission" && lines.isNotEmpty() &&
                    stream("continue:${f.code}").nextDouble() >= params.purchase.missionContinueAfterBuy
                ) {
                    break
                }
                t = browseAt(id, persona, f, pres.getValue(f.code), t, stream("visit:${f.code}"), sink, visits, lines)
            }
        }

        // ── checkout: buyers queue at a counter; a few add an impulse line ──────────────────────────────────
        if (lines.isNotEmpty() && world.counters.isNotEmpty()) {
            val co = stream("checkout")
            val counter = world.counters[co.nextInt(world.counters.size)]
            val q = uniformMs(params.browse.checkoutQueueSeconds, co)
            visits += Visit(counter.code, Visit.Kind.CHECKOUT, t, q, impression = true, pickups = emptyList())
            t = episode(id, counter, q, t, co, sink)
            val impulseCells = world.impulse?.let { plan.cells(it.code) }.orEmpty()
            if (impulseCells.isNotEmpty() && co.nextDouble() < params.purchase.impulseAtCheckout) {
                lines += SoldLine(impulseCells[co.nextInt(impulseCells.size)], noLook = true)
            }
        }

        t = walk(id, t, inward = false, rng = stream("door-out"), sink = sink)
        sink?.evict(id)
        return ShopperRecord(id, persona, plan.id, entryMs, t, visits, lines)
    }

    // ── stops ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Which floor fixtures this visit stops at. Each browsable fixture is an independent chance:
     * `stops × weight / normalizer`, weight = zone role × content appeal × the impression truths × (mission:
     * target-category share) × jitter, where `stops` is a number drawn from the persona's range. Independent
     * chances keep each truth exactly multiplicative on a fixture's impressions; the stop count then varies around
     * the persona's range, and rises when the floor holds more that is worth stopping for. Visited in order of
     * distance from the door, the way a shopper works into a store.
     */
    private fun pickStops(
        persona: String,
        pres: Map<String, FixturePresentation>,
        target: String?,
        rng: Random,
        stream: (String) -> Random,
        noise: (String) -> Double,
    ): List<Fixture> {
        val range = params.browse.stops.getValue(persona)
        val n = range[0] + rng.nextDouble() * (range[1] - range[0])
        return world.visible
            .filter { world.placement[it.code] == Placement.FLOOR || world.placement[it.code] == Placement.BACK_CORNER }
            .filter { f ->
                stream("stop:${f.code}").nextDouble() <
                    min(params.engagement.maxProbability, n * stopWeight(f, pres.getValue(f.code), target) * noise(f.code) / stopNormalizer)
            }
            .sortedBy { world.distanceFromDoor(it) }
    }

    private fun stopWeight(f: Fixture, p: FixturePresentation, target: String?): Double {
        val mission = if (target == null || p.isEmpty) {
            1.0
        } else {
            1.0 +
                params.browse.missionCategoryBoost * p.cells.filter { it.category == target }.sumOf { it.facings } /
                p.cells.sumOf { it.facings }.toDouble()
        }
        return roleWeight(f) * p.contentAppeal * p.impressionFactor * mission
    }

    // ── one fixture: look, dwell, handle, maybe try on, maybe buy ────────────────────────────────────────

    private fun browseAt(
        id: String,
        persona: String,
        f: Fixture,
        p: FixturePresentation,
        start: Long,
        rng: Random,
        sink: SampleSink?,
        visits: MutableList<Visit>,
        lines: MutableList<SoldLine>,
    ): Long {
        val dwell = (uniformMs(params.browse.dwellSeconds, rng) * sqrt(p.contentAppeal.coerceAtLeast(0.25))).toLong().coerceAtLeast(MIN_DWELL_MS)
        var t = episode(id, f, dwell, start, rng, sink)
        val pickups = mutableListOf<Pickup>()
        // Reserve the browse visit's slot so any try-on it leads to is recorded after it, in time order.
        val slot = visits.size
        visits += Visit(f.code, Visit.Kind.BROWSE, start, dwell, impression = true, pickups = emptyList())

        for (idx in considered(p.cells, rng)) {
            val c = p.cells[idx]
            val pick = min(params.engagement.maxProbability, params.engagement.basePickup * p.cellPickup(c))
            if (rng.nextDouble() >= pick) continue
            var tried = false
            if ((c.category == "apparel" || c.category == "footwear") && rng.nextDouble() < params.engagement.tryOnRate.getValue(persona)) {
                tried = true
                t = tryOn(id, c, t, rng, sink, visits)
            }
            val buy = params.purchase.baseBuy.getValue(persona) * p.cellConversion(c, idx) * priceFactor(c) *
                (if (tried) params.purchase.tryOnBuyUplift else 1.0)
            val bought = rng.nextDouble() < min(params.engagement.maxProbability, buy)
            pickups += Pickup(c, tried, bought)
            if (bought) lines += SoldLine(c, noLook = false)
        }
        visits[slot] = visits[slot].copy(pickups = pickups)
        return t
    }

    /**
     * Willingness to pay: (reference / price)^`purchase.price_elasticity`, bounded to [0.15, 1.25]. A $10 tee in hand
     * is bought more readily than a $160 shoe in hand. Not a truth (it does not depend on placement), so it is
     * outside the six; it is what keeps basket value near the operating model's ATV.
     */
    private fun priceFactor(c: Cell): Double =
        (params.content.priceReferenceUsd / c.priceUsd.coerceAtLeast(1.0)).pow(params.purchase.priceElasticity).coerceIn(0.15, MAX_PRICE_FACTOR)

    /** Cells a shopper looks at closely: facings × price preference, without replacement. */
    private fun considered(cells: List<Cell>, rng: Random): List<Int> {
        if (cells.isEmpty()) return emptyList()
        val ref = params.content.priceReferenceUsd
        val e = params.content.priceElasticity
        return cells.indices.map { i ->
            val c = cells[i]
            val w = c.facings * (ref / c.priceUsd.coerceAtLeast(1.0)).pow(e)
            i to rng.nextDouble().pow(1.0 / w)
        }.sortedByDescending { it.second }.take(params.engagement.cellsConsidered).map { it.first }
    }

    /** Footwear is tried at the bench (a real fixture, so a real episode); apparel in the fitting room space. */
    private fun tryOn(id: String, c: Cell, start: Long, rng: Random, sink: SampleSink?, visits: MutableList<Visit>): Long {
        val d = uniformMs(params.engagement.tryOnSeconds, rng)
        val bench = world.footwearBench
        return if (c.category == "footwear" && bench != null) {
            val end = episode(id, bench, d, start, rng, sink)
            visits += Visit(bench.code, Visit.Kind.TRY_ON, start, d, impression = true, pickups = emptyList(), triedCell = c)
            end
        } else {
            // The fitting room is its own space with its own SRF; this floor's stream simply goes quiet.
            visits += Visit(FITTING_ROOM, Visit.Kind.TRY_ON, start, d, impression = false, pickups = emptyList(), triedCell = c)
            start + d + WALK_GAP_MS
        }
    }

    /** Gift persona: straight to a stocked fixture, a 3 s grab (under the impression floor), one item, pay. */
    private fun giftGrab(
        id: String,
        pres: Map<String, FixturePresentation>,
        start: Long,
        rng: Random,
        stream: (String) -> Random,
        sink: SampleSink?,
        visits: MutableList<Visit>,
        lines: MutableList<SoldLine>,
    ): Long {
        val stocked = world.visible.filter {
            (world.placement[it.code] == Placement.FLOOR || world.placement[it.code] == Placement.ENTRANCE_TABLE) &&
                !pres.getValue(it.code).isEmpty
        }
        if (stocked.isEmpty()) return start
        val f = stocked.maxBy { stream("gift:${it.code}").nextDouble().pow(1.0 / pres.getValue(it.code).contentAppeal) }
        val p = pres.getValue(f.code)
        val idx = considered(p.cells, rng).first()
        val end = episode(id, f, GRAB_MS, start, rng, sink)
        visits +=
            Visit(f.code, Visit.Kind.GRAB, start, GRAB_MS, impression = false, pickups = listOf(Pickup(p.cells[idx], tried = false, bought = true)))
        lines += SoldLine(p.cells[idx], noLook = true)
        return end
    }

    // ── emission ─────────────────────────────────────────────────────────────────────────────────────────

    /** A 5 Hz episode at [f]; returns the cursor after it plus the walk to the next place. */
    private fun episode(id: String, f: Fixture, dwellMs: Long, start: Long, rng: Random, sink: SampleSink?): Long {
        if (sink != null) {
            sink.emit(id, browse.browse(f.code, dwellMs, start, Random(rng.nextLong()), approachMs = APPROACH_MS, disengageMs = DISENGAGE_MS))
        } else {
            rng.nextLong() // keep the stream identical whether or not samples are recorded
        }
        return start + APPROACH_MS + dwellMs + DISENGAGE_MS + WALK_GAP_MS
    }

    /**
     * Walk through the door: 3 s at 5 Hz along the door's centre line, crossing the entrance gate line. Short
     * enough that no impression can form on the way past the entrance table (the floor is 5 s); the shopper is
     * moving, which is exactly what makes the entrance a place on the wire rather than a teleport.
     */
    private fun walk(id: String, start: Long, inward: Boolean, rng: Random, sink: SampleSink?): Long {
        val x = world.door.x + (rng.nextDouble() - 0.5) * 2_000
        val steps = (DOOR_WALK_MS / SAMPLE_MS).toInt()
        if (sink != null) {
            val samples = (0..steps).map { i ->
                val frac = i.toDouble() / steps
                val y = if (inward) world.door.y - 400 + frac * 2_400 else world.door.y + 2_000 - frac * 2_400
                ImpressionOracle.Sample(start + i * SAMPLE_MS, Pt(x, y), Pt(0.0, if (inward) 1.0 else -1.0))
            }
            sink.emit(id, samples)
        }
        return start + DOOR_WALK_MS + WALK_GAP_MS
    }

    private fun roleWeight(f: Fixture): Double = roleWeight(world, f)

    private fun uniformMs(range: List<Int>, rng: Random): Long = ((range[0] + rng.nextDouble() * (range[1] - range[0])) * 1000).toLong()

    companion object {
        /** How likely a shopper in this fixture's zone is to browse at all — the existing role tables, unchanged. */
        fun roleWeight(world: StoreWorld, f: Fixture): Double {
            val role = world.roleOf(f) ?: return 0.0
            return ZoneAffinityModel.probability(role, 0.0) * ZoneAffinityModel.fixtureBrowseRate(role)
        }

        const val FITTING_ROOM = "fitting_room"
        const val MIN_DWELL_MS = 6_000L
        const val GRAB_MS = 3_000L
        const val WALK_GAP_MS = 4_000L

        // The three-phase episode around the engaged span, passed to BrowseEpisode explicitly so its env
        // overrides (DayDrive's A/B switch) can never silently change this model's timing.
        const val APPROACH_MS = 1_200L
        const val DISENGAGE_MS = 1_200L
        const val DOOR_WALK_MS = 3_000L
        const val SAMPLE_MS = 200L

        fun gaussian(rng: Random): Double {
            val u1 = rng.nextDouble().coerceAtLeast(1e-12)
            return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * rng.nextDouble())
        }

        fun <K> weightedPick(weights: Map<K, Double>, rng: Random): K {
            var r = rng.nextDouble() * weights.values.sum()
            for ((k, w) in weights) {
                r -= w
                if (r <= 0) return k
            }
            return weights.keys.last()
        }
    }
}

/** Where a shopper's samples go. Null in decision-only runs (the self-test's seeded weeks). */
interface SampleSink {
    fun emit(id: String, samples: List<ImpressionOracle.Sample>)
    fun evict(id: String)
}

/** Keeps every stream in memory for the oracle and the drive's pacing. */
class MemorySampleSink : SampleSink {
    val streams = LinkedHashMap<String, MutableList<ImpressionOracle.Sample>>()
    override fun emit(id: String, samples: List<ImpressionOracle.Sample>) {
        streams.getOrPut(id) { mutableListOf() }.addAll(samples)
    }

    override fun evict(id: String) {}
}
