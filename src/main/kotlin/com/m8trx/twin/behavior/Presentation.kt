package com.m8trx.twin.behavior

import com.m8trx.twin.layer1.Fixture
import com.m8trx.twin.layer1.FixtureSet
import com.m8trx.twin.layer1.Pt
import com.m8trx.twin.layer1.RoleZone
import com.m8trx.twin.layer1.ZoneRole
import com.m8trx.twin.layer2.EasTagging
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Where a fixture sits, in the terms the truths use. */
enum class Placement { ENTRANCE_TABLE, BACK_CORNER, FLOOR, CHECKOUT, TRY_ON }

/**
 * The store's fixed geometry, resolved once per site: where the door is, which fixtures are the entrance table,
 * which are the back corner, which are checkout counters and the try-on bench.
 *
 * **Back corner is derived from geometry, not listed.** A fixture is back-corner when it is in the farthest
 * `back_corner_distance_quantile` of fixtures from the door AND its centre is more than `back_corner_off_path_mm`
 * sideways from the door's centre line. That is the brief's "farthest from the entrance and off the main path"
 * stated as a rule a presenter can check on the floor plan, and it resolves in any store with a door.
 */
class StoreWorld(val fixtures: FixtureSet, val zones: List<RoleZone>, gates: List<EasTagging.Gate>, truths: BehaviorParams.Truths) {
    /** Door centre: the middle of the entrance gate line, or the bottom-centre of the entrance zone's fixtures. */
    val door: Pt = gates.firstOrNull()?.crossingPoint(0.5) ?: Pt(fixtures.fixtures.map { it.centre.x }.average(), 0.0)

    private val roleByZone: Map<String, ZoneRole> = zones.filter { it.spaceType == "sales_floor" }.associate { it.zoneCode to it.role }

    fun roleOf(f: Fixture): ZoneRole? = f.inAreaZone?.let { roleByZone[it] }

    val visible: List<Fixture> = fixtures.impressionVisible

    val placement: Map<String, Placement> = run {
        // The quantile is over fixtures a shopper can actually browse (the same role weight the agent uses),
        // so non-retail strips and service desks never set where "the back" begins.
        val floor = visible.filter {
            roleOf(it) !in setOf(ZoneRole.ENTRANCE, ZoneRole.CHECKOUT, ZoneRole.FOOTWEAR_BENCH, ZoneRole.GAIT_ANALYSIS) &&
                ShopperAgent.roleWeight(this, it) > 0.0
        }
        val dists = floor.map { distanceFromDoor(it) }.sorted()
        val cut = if (dists.isEmpty()) Double.MAX_VALUE else dists[min(dists.size - 1, (dists.size * truths.backCornerDistanceQuantile).toInt())]
        visible.associate { f ->
            f.code to when (roleOf(f)) {
                ZoneRole.ENTRANCE -> Placement.ENTRANCE_TABLE

                ZoneRole.CHECKOUT -> Placement.CHECKOUT

                ZoneRole.FOOTWEAR_BENCH, ZoneRole.GAIT_ANALYSIS -> Placement.TRY_ON

                else -> if (distanceFromDoor(f) >= cut &&
                    abs(f.centre.x - door.x) > truths.backCornerOffPathMm
                ) {
                    Placement.BACK_CORNER
                } else {
                    Placement.FLOOR
                }
            }
        }
    }

    fun distanceFromDoor(f: Fixture): Double = hypot(f.centre.x - door.x, f.centre.y - door.y)

    fun fixturesIn(p: Placement): List<Fixture> = visible.filter { placement[it.code] == p }

    /** Checkout counters a buyer queues at — the impulse fixture is a separate pick. */
    val counters: List<Fixture> = fixturesIn(Placement.CHECKOUT).filter { it.code.startsWith("CO-") && it.code != IMPULSE_FIXTURE }
    val impulse: Fixture? = visible.firstOrNull { it.code == IMPULSE_FIXTURE }
    val footwearBench: Fixture? = visible.firstOrNull { roleOf(it) == ZoneRole.FOOTWEAR_BENCH }

    companion object {
        const val IMPULSE_FIXTURE = "CO-IR"
    }
}

/**
 * A fixture's presentation under one plan, with every truth's multiplier and the reason for it.
 *
 * **Each truth multiplies exactly one measure**, which is what makes "1.0 = off" and "double (value - 1) = double
 * the effect" hold:
 *
 * | truth | measure it multiplies |
 * |---|---|
 * | entrance table | impressions (stop chance) and, separately, pickup |
 * | back corner | impressions (stop chance) |
 * | eye level / floor | pickup of a cell |
 * | color story (coherent / clash) | impressions and pickup of every cell on the face |
 * | broken size run | conversion of a style on the fixture |
 * | complement beside hero | conversion of the complement cell |
 */
class FixturePresentation(
    val code: String,
    val placement: Placement,
    val cells: List<Cell>,
    /** Content appeal: empty, hero-bearing, or ordinary (1.0). Not a truth; it is what makes contents matter. */
    val contentAppeal: Double,
    val colorStory: ColorStory,
    val impressionFactor: Double,
    val pickupFactor: Double,
    private val sizeRunFactor: Map<String, Double>,
    private val adjacentToHero: Set<Int>,
    private val truths: BehaviorParams.Truths,
) {
    enum class ColorStory { COHERENT, CLASH, NONE }

    val isEmpty: Boolean get() = cells.isEmpty()

    /** Pickup multiplier for one cell: the fixture's (entrance, color story) times the level's. */
    fun cellPickup(c: Cell): Double = pickupFactor * levelFactor(c.level)

    /** Conversion multiplier for one cell: the style's size run times the complement-beside-hero lift. */
    fun cellConversion(c: Cell, index: Int): Double =
        (sizeRunFactor[c.handle] ?: 1.0) * (if (index in adjacentToHero) truths.adjacencyComplement else 1.0)

    fun sizeRunOf(handle: String): Double = sizeRunFactor[handle] ?: 1.0

    fun isComplementBesideHero(index: Int): Boolean = index in adjacentToHero

    private fun levelFactor(level: Int): Double = when (level) {
        truths.eyeLevel -> truths.eyeLevelPickup
        truths.levelsPerFixture -> truths.floorLevelPickup
        else -> 1.0
    }

    companion object {
        fun of(world: StoreWorld, f: Fixture, plan: ShelfPlan, stock: StoreStock, p: BehaviorParams): FixturePresentation {
            val t = p.truths
            val placement = world.placement.getValue(f.code)
            val cells = plan.cells(f.code)

            val content = when {
                cells.isEmpty() -> p.content.emptyFixtureAppeal

                else -> {
                    val total = cells.sumOf { it.facings }.toDouble()
                    val heroShare = cells.filter { it.handle in p.content.heroes }.sumOf { it.facings } / total
                    // Any hero presence pulls; a face that is mostly hero pulls the full amount.
                    if (heroShare > 0.0) 1.0 + (p.content.heroAppeal - 1.0) * min(1.0, heroShare * 2.0) else 1.0
                }
            }

            val story = colorStory(cells, t)
            val storyFactor = when (story) {
                ColorStory.COHERENT -> t.colorStoryCoherent
                ColorStory.CLASH -> t.colorStoryClash
                ColorStory.NONE -> 1.0
            }
            val impression = storyFactor * when (placement) {
                Placement.ENTRANCE_TABLE -> t.entranceTableImpressions
                Placement.BACK_CORNER -> t.backCornerImpressions
                else -> 1.0
            }
            val pickup = storyFactor * (if (placement == Placement.ENTRANCE_TABLE) t.entranceTablePickup else 1.0)

            val sizeRun = cells.groupBy { it.handle }.mapValues { (h, cs) ->
                val run = stock.catalogSizes[h].orEmpty()
                if (run.size <= 1) {
                    1.0
                } else {
                    val missing = 1.0 - cs.map { it.size }.toSet().intersect(run).size.toDouble() / run.size
                    max(t.sizeRunConversion, 1.0 - (1.0 - t.sizeRunConversion) * missing / t.sizeRunFullBreakShare)
                }
            }

            val heroIdx = cells.indices.filter { cells[it].handle in p.content.heroes }.toSet()
            val adjacent = cells.indices.filter { i ->
                val c = cells[i]
                c.handle !in p.content.heroes &&
                    (heroIdx.contains(i - 1) || heroIdx.contains(i + 1)) &&
                    listOfNotNull(cells.getOrNull(i - 1), cells.getOrNull(i + 1))
                        .any { h -> h.handle in p.content.heroes && c.category in p.content.complements[h.category].orEmpty() }
            }.toSet()

            return FixturePresentation(f.code, placement, cells, content, story, impression, pickup, sizeRun, adjacent, t)
        }

        /**
         * Color story of a face. Coherent: one color family holds >= `color_story_min_share` of facings across
         * 3+ cells. Clash: `color_story_clash_families` or more families with none above 35%. Otherwise none.
         */
        fun colorStory(cells: List<Cell>, t: BehaviorParams.Truths): ColorStory {
            if (cells.size < 3) return ColorStory.NONE
            // Denominator is EVERY facing on the face, unknown colors included: a face of two "Default Color"
            // cells and one black one is not a black story. A story also needs 3+ cells of its family, so one
            // fat-facing cell cannot make a face coherent on its own.
            val groups = cells.groupBy { colorFamily(it.color) }.filterKeys { it != "unknown" }
            val total = cells.sumOf { it.facings }.toDouble()
            if (groups.isEmpty() || total == 0.0) return ColorStory.NONE
            val topCells = groups.values.maxBy { cs -> cs.sumOf { it.facings } }
            val top = topCells.sumOf { it.facings } / total
            return when {
                top >= t.colorStoryMinShare && topCells.size >= 3 -> ColorStory.COHERENT
                groups.size >= t.colorStoryClashFamilies && top <= 0.35 -> ColorStory.CLASH
                else -> ColorStory.NONE
            }
        }

        /**
         * Catalog color name → family. The catalog has 135 marketing names ("Smoked Black", "Asphalt Blue",
         * "Bronze Khaki Green"); a story is about the family a shopper sees. The LAST matching word wins, because
         * marketing names put the base color last ("Smoked Black" is black, "Black Orange" is orange-accented,
         * read as orange). Names with no color word ("Default Color", "Unspecified") are unknown and do not vote.
         */
        fun colorFamily(name: String): String {
            val words = name.lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() }
            return words.reversed().firstNotNullOfOrNull { FAMILY_WORDS[it] } ?: "unknown"
        }

        private val FAMILY_WORDS: Map<String, String> = buildMap {
            listOf("black", "carbon", "graphite", "anthracite", "asphalt").forEach { put(it, "black") }
            listOf("gray", "grey", "silver", "slate", "granite", "smoke").forEach { put(it, "gray") }
            listOf("white", "snowy", "ivory", "cream", "ecru", "beige", "sand", "stone").forEach { put(it, "white") }
            listOf("blue", "navy", "indigo", "denim", "petrol", "turquoise", "teal", "abyss", "cyan").forEach { put(it, "blue") }
            listOf("green", "khaki", "olive", "sage", "mint", "lime", "emerald").forEach { put(it, "green") }
            listOf("red", "burgundy", "bordeaux", "crimson", "coral").forEach { put(it, "red") }
            listOf("orange", "rust", "copper").forEach { put(it, "orange") }
            listOf("yellow", "mustard", "gold", "ochre").forEach { put(it, "yellow") }
            listOf("pink", "fushia", "fuchsia", "rose", "magenta").forEach { put(it, "pink") }
            listOf("purple", "violet", "lavender", "lilac", "plum").forEach { put(it, "purple") }
            listOf("brown", "sepia", "bronze", "chocolate", "camel", "tan", "caramel").forEach { put(it, "brown") }
        }
    }
}

/** Presentations of every visible fixture under one plan, cached by plan id. */
class PresentationCache(private val world: StoreWorld, private val stock: StoreStock, private val params: BehaviorParams) {
    private val cache = HashMap<String, Map<String, FixturePresentation>>()

    fun of(plan: ShelfPlan): Map<String, FixturePresentation> =
        cache.getOrPut(plan.id) { world.visible.associate { it.code to FixturePresentation.of(world, it, plan, stock, params) } }
}
