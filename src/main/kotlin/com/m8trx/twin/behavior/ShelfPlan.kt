package com.m8trx.twin.behavior

import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.layer2.StoreCatalog
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

/**
 * One planogram cell: a SKU (one size of one style) placed on a fixture at a position and level.
 *
 * Built from the plan line joined to the store's catalog, so a cell knows its style ([handle]), [size], [color]
 * and price — the inputs the size-run, color-story, adjacency and price truths need. [level] is 1 (top) to the
 * fixture's floor level; see [ShelfPlan.of] for how it is derived when the plan carries none.
 */
data class Cell(
    val fixture: String,
    val itemCd: String,
    val ean: String,
    val handle: String,
    val category: String,
    val size: String,
    val color: String,
    val priceUsd: Double,
    val facings: Int,
    val position: Int,
    val level: Int,
)

/**
 * The arrangement a shopper meets: fixture code → its cells in position order. Immutable; a plan change is a new
 * [ShelfPlan] in a [PlanTimeline], never an edit.
 *
 * [id] is a content digest, so two plans with the same arrangement are the same plan and a reset that changes
 * nothing is detectable as such.
 */
class ShelfPlan(val name: String, val cellsByFixture: Map<String, List<Cell>>) {
    val id: String = digest(cellsByFixture)

    fun cells(fixture: String): List<Cell> = cellsByFixture[fixture].orEmpty()

    val allCells: List<Cell> get() = cellsByFixture.values.flatten()

    /** Total facings on a fixture — the denominator of its color-story share. */
    fun facings(fixture: String): Int = cells(fixture).sumOf { it.facings }

    companion object {
        /**
         * Build a plan from `planogram.json` lines joined to `assortment.csv` by EAN.
         *
         * **Level is derived, by a stated convention, because the plan has no level field.** The published Denver
         * plan carries `position_sequence` but no `display_level`. Twin's convention: a fixture's lines in
         * `position_sequence` order fill levels 1 (top) .. [levels] (floor) evenly, so position 1 is top-left and
         * the last position is on the floor. A line that carries `display_level` keeps it. This is the twin's
         * call, documented in `reference/data/behavior/README.md`; the planogram lane can override it per line.
         */
        fun of(
            name: String,
            lines: List<PlanLine>,
            catalog: Map<String, StoreCatalog.Sku>,
            sizes: Map<String, String>,
            colors: Map<String, String>,
            levels: Int,
        ): ShelfPlan {
            val byFixture = lines.filter { it.ean in catalog }.groupBy { it.fixtureCode }.mapValues { (_, ls) ->
                val ordered = ls.sortedBy { it.positionSequence }
                ordered.mapIndexed { i, l ->
                    val sku = catalog.getValue(l.ean)
                    Cell(
                        fixture = l.fixtureCode,
                        itemCd = sku.itemCd,
                        ean = l.ean,
                        handle = sku.handle,
                        category = sku.category,
                        size = sizes[l.ean].orEmpty(),
                        color = colors[l.ean].orEmpty(),
                        priceUsd = sku.priceUsd,
                        facings = l.facings.coerceAtLeast(1),
                        position = l.positionSequence,
                        level = l.displayLevel ?: (1 + i * levels / ordered.size).coerceIn(1, levels),
                    )
                }
            }
            return ShelfPlan(name, byFixture)
        }

        private fun digest(m: Map<String, List<Cell>>): String {
            val md = MessageDigest.getInstance("SHA-256")
            m.toSortedMap().forEach { (f, cs) -> cs.forEach { c -> md.update("$f|${c.ean}|${c.position}|${c.level}|${c.facings};".toByteArray()) } }
            return md.digest().take(6).joinToString("") { "%02x".format(it) }
        }
    }
}

/** A plan line as the twin reads it from `planogram.json` (the same shape `ConnectPlanogramDrive` publishes). */
data class PlanLine(val fixtureCode: String, val ean: String, val facings: Int, val positionSequence: Int, val displayLevel: Int? = null)

/**
 * Which plan is on the floor when — requirement 5. A shopper reads the plan active at the moment they enter, so
 * a reset effective at an hour boundary changes behavior from the next arrival onward ("within a simulated day").
 */
class PlanTimeline(entries: List<Pair<Instant, ShelfPlan>>) {
    private val sorted = entries.sortedBy { it.first }

    init {
        require(sorted.isNotEmpty()) { "a plan timeline needs at least one plan" }
    }

    fun at(t: Instant): ShelfPlan = sorted.lastOrNull { !it.first.isAfter(t) }?.second ?: sorted.first().second

    val plans: List<ShelfPlan> get() = sorted.map { it.second }
    val changes: List<Pair<Instant, ShelfPlan>> get() = sorted

    companion object {
        fun single(plan: ShelfPlan) = PlanTimeline(listOf(Instant.EPOCH to plan))
    }
}

/**
 * The store's catalog and published plan, loaded once — everything [ShelfPlan] and the reset script need.
 */
class StoreStock(val storeDir: Path, val levels: Int) {
    private val rows: List<Map<String, String>> = readCsv(storeDir.resolve("assortment.csv"))

    val catalog: Map<String, StoreCatalog.Sku> = rows.associate { r ->
        r.getValue("ean") to StoreCatalog.Sku(
            itemCd = r.getValue("item_cd"),
            ean = r.getValue("ean"),
            priceUsd = r.getValue("price_usd").toDouble(),
            department = r.getValue("department"),
            fixture = r.getValue("fixture"),
            category = r.getValue("category"),
            handle = r.getValue("handle"),
        )
    }
    val sizes: Map<String, String> = rows.associate { it.getValue("ean") to it.getValue("size_us") }
    val colors: Map<String, String> = rows.associate { it.getValue("ean") to it.getValue("color") }

    /** handle → every size the catalog carries for that style. A size run is complete against THIS set. */
    val catalogSizes: Map<String, Set<String>> = rows.groupBy {
        it.getValue("handle")
    }.mapValues { (_, rs) -> rs.map { it.getValue("size_us") }.toSet() }

    val publishedLines: List<PlanLine> = run {
        val root = ConnectMappers.snake.readTree(Files.readAllBytes(storeDir.resolve("planogram.json")))
        root.path("lines").filter { it.path("line_item_type").asText() == "product_placement" }.map {
            PlanLine(
                fixtureCode = it.path("fixture_code").asText(),
                ean = it.path("ean").asText(),
                facings = it.path("facings").asInt(1),
                positionSequence = it.path("position_sequence").asInt(0),
                displayLevel = it.path("display_level").takeIf { n -> n.isInt }?.asInt(),
            )
        }
    }

    fun plan(name: String, lines: List<PlanLine>): ShelfPlan = ShelfPlan.of(name, lines, catalog, sizes, colors, levels)

    val published: ShelfPlan by lazy { plan("published", publishedLines) }

    /**
     * The scripted reset (requirement 5), as two plans built from the published one.
     *
     * - **before**: the hero styles' lines move off their home fixtures onto the back-corner fixture.
     * - **after**: the same lines move onto the entrance table, followed by the complement styles' lines
     *   (so a complement sits beside a hero, which the adjacency truth reads), all other lines untouched.
     *
     * Moving lines (not copying) keeps total stock constant, so a difference between the two is placement only.
     */
    fun resetPair(params: BehaviorParams): Pair<ShelfPlan, ShelfPlan> {
        val heroes = params.content.heroes
        val compl = params.reset.complementHandles.toSet()
        fun handleOf(l: PlanLine) = catalog[l.ean]?.handle
        val heroLines = publishedLines.filter { handleOf(it) in heroes }
        val complLines = publishedLines.filter { handleOf(it) in compl }
        require(heroLines.isNotEmpty()) { "no plan lines for hero styles $heroes" }
        require(complLines.isNotEmpty()) { "no plan lines for complement styles $compl" }
        val rest = publishedLines - heroLines.toSet() - complLines.toSet()

        fun onto(fixture: String, ls: List<PlanLine>) = ls.mapIndexed { i, l -> l.copy(fixtureCode = fixture, positionSequence = i + 1) }

        val before = rest + complLines + onto(params.reset.heroBackCornerFixture, heroLines)
        // Hero then complement, interleaved by style so each complement cell has a hero cell beside it.
        val after = rest + onto(params.reset.entranceTableFixture, interleave(heroLines, complLines))
        return plan("before-reset", before) to plan("after-reset", after)
    }

    private fun interleave(a: List<PlanLine>, b: List<PlanLine>): List<PlanLine> {
        val out = ArrayList<PlanLine>(a.size + b.size)
        val step = if (b.isEmpty()) a.size else maxOf(1, a.size / b.size)
        var j = 0
        a.forEachIndexed { i, l ->
            out += l
            if ((i + 1) % step == 0 && j < b.size) out += b[j++]
        }
        while (j < b.size) out += b[j++]
        return out
    }

    companion object {
        internal fun readCsv(path: Path): List<Map<String, String>> {
            val lines = Files.readAllLines(path)
            val header = StoreCatalog.splitCsv(lines.first())
            return lines.drop(1).filter { it.isNotBlank() }.map { row ->
                val c = StoreCatalog.splitCsv(row)
                header.indices.associate { header[it] to c.getOrElse(it) { "" } }
            }
        }
    }
}
