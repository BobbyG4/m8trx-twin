package com.m8trx.twin.storeday

import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.webhook.DirectiveTarget
import com.m8trx.twin.connect.model.webhook.PlanogramDirective
import com.m8trx.twin.history.HistoryCsv
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.random.Random

/** One change a period makes to the plan before it: [kind] is `facings`, `swap` or `range`. */
data class PlanChange(val kind: String, val department: String, val fixture: String, val ean: String, val from: String, val to: String)

/**
 * One planogram period (slice 2c): the full plan in force from [start] (a Monday, store-local) until [end], the next period's
 * first day. [changes] are what this period did to the one before it; [linesChanged] counts the plan lines that differ.
 */
class PeriodPlan(val start: LocalDate, val lines: List<StoreDayModel.V2Line>, val changes: List<PlanChange>, val linesChanged: Int) {
    val end: LocalDate get() = start.plusDays(PeriodPlans.PERIOD_DAYS)

    val required: Map<Slot, Int> = lines.groupBy { Slot(it.fixture, it.ean) }.mapValues { (_, ls) -> ls.sumOf { it.required } }

    val homeLevel: Map<Slot, Int> = lines.groupBy { Slot(it.fixture, it.ean) }.mapValues { (_, ls) -> ls.minBy { it.position }.level }

    /** Floor fixtures the plan shelves, by code. */
    val fixtures: List<String> = lines.map { it.fixture }.toSortedSet().toList()

    val zoneIdOf: Map<String, String> = lines.associate { it.fixture to it.zoneId }

    /** A content digest of the plan: two periods with the same arrangement have the same digest. */
    val digest: String by lazy {
        val md = MessageDigest.getInstance("SHA-256")
        lines.sortedWith(compareBy({ it.fixture }, { it.level }, { it.position }, { it.ean })).forEach {
            md.update("${it.fixture}|${it.level}|${it.position}|${it.ean}|${it.facings}|${it.required}\n".toByteArray())
        }
        md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
}

/**
 * Slice 2c: the weekly planogram period calendar of one store. A period starts on a Monday (store-local) and the plan of the first
 * one, the [ANCHOR] week, is the Phase 1 plan unchanged. Every later period is the one before it with a small seeded set of changes
 * ([PeriodKnobs]): per department, a few facings move between lines of one level (a level is full, so a facing gained is a facing
 * taken from a neighbour on the same level), at most one style swaps places with a style on a neighbouring fixture of the same
 * department, and when the month changes the departments the season favours get one more facing change and one new style from
 * the back of the range in place of the slowest one.
 *
 * Generated on demand and memoised; the same (seed, store, period) always yields the same plan. [neighbours] is fixture code to
 * its nearest fixtures; [weights] is the story calendar's department weight on a date.
 */
class PeriodPlans(
    val model: StoreDayModel,
    private val neighbours: Map<String, List<String>>,
    private val weights: (LocalDate) -> Map<String, Double>,
    val seed: Long,
    private val knobs: PeriodKnobs,
    private val enabled: Boolean = true,
    private val zoneNames: Map<String, String> = emptyMap(),
    /** Slice 2d: a planted store's plan edit; null (every other store) leaves every period exactly as slice 2c made it. */
    private val plantEdit: PlantPlanEdit? = null,
) {
    private val cache = HashMap<LocalDate, PeriodPlan>()
    private val edited = HashMap<LocalDate, PeriodPlan>()
    private val base: PeriodPlan = PeriodPlan(ANCHOR, model.lines, emptyList(), 0)

    /** The plan in force on [date]. */
    fun forDate(date: LocalDate): PeriodPlan = periodOf(periodStart(date))

    /** Planted fixtures and products are left alone by the weekly changes (empty without a plant: the 2c draws, unchanged). */
    private val protectedEans: Set<String> = plantEdit?.plant?.protectedEans.orEmpty()
    private val protectedFixtures: Set<String> = plantEdit?.plant?.protectedFixtures.orEmpty()

    private fun protectedLine(l: StoreDayModel.V2Line) = l.ean in protectedEans || l.fixture in protectedFixtures

    /** The plan in force for the period starting [start]: slice 2c's plan, with a planted store's edit on top once its window has begun. */
    fun periodOf(start: LocalDate): PeriodPlan {
        val plain = plainOf(start)
        val edit = plantEdit?.takeIf { it.activeOn(start) } ?: return plain
        return edited.getOrPut(start) {
            val lines = edit.apply(plain.lines)
            val prev = if (start > ANCHOR) periodOf(start.minusDays(PERIOD_DAYS)).lines else plain.lines
            PeriodPlan(start, lines, plain.changes, countChanged(prev, lines))
        }
    }

    private fun countChanged(prev: List<StoreDayModel.V2Line>, now: List<StoreDayModel.V2Line>): Int {
        val old = prev.associate { Triple(it.fixture, it.level, it.position) to Triple(it.ean, it.facings, it.required) }
        val new = now.associate { Triple(it.fixture, it.level, it.position) to Triple(it.ean, it.facings, it.required) }
        return (old.keys + new.keys).count { old[it] != new[it] }
    }

    private fun plainOf(start: LocalDate): PeriodPlan {
        require(start.dayOfWeek == DayOfWeek.MONDAY) { "a period starts on a Monday: $start" }
        if (!enabled || start <= ANCHOR) return if (start == ANCHOR) base else PeriodPlan(start, base.lines, emptyList(), 0)
        return cache.getOrPut(start) { next(plainOf(start.minusDays(PERIOD_DAYS)), start) }
    }

    /** A period's directive id: one per store and period. */
    fun directiveId(start: LocalDate): String = "$ID_PREFIX-${model.slug}-${start.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)}"

    /** The exact `planogram_directive` body for a period (site left as the `@site` placeholder the other wire events use). */
    fun directive(p: PeriodPlan, siteId: String = "@site"): PlanogramDirective = PlanogramDirective(
        name = "Twin planogram - ${model.slug} - week of ${p.start}",
        externalDirectiveId = directiveId(p.start),
        effectiveDate = p.start.toString(),
        targets = p.lines.sortedWith(compareBy({ it.fixture }, { it.level }, { it.position }, { it.ean })).map {
            DirectiveTarget(
                siteId = siteId,
                rawFixtureCode = zoneNames[it.zoneId] ?: error("${model.slug}: zone ${it.zoneId} (${it.fixture}) has no name in the keys export"),
                rawItemIdentifier = it.ean,
                requiredQuantity = it.required,
                facingCount = it.facings,
                positionSequence = it.position,
                displayLevel = it.level,
            )
        },
        expiryDate = p.end.toString(),
    )

    fun directiveJson(p: PeriodPlan): com.fasterxml.jackson.databind.JsonNode = ConnectMappers.snake.valueToTree(directive(p))

    // ── generation ────────────────────────────────────────────────────────────────────────────────────

    private fun demandOf(ean: String) = model.demand[ean]?.share ?: 1e-7

    private fun pick(rng: Random, n: Int, w: (Int) -> Double): Int {
        val total = (0 until n).sumOf(w)
        var x = rng.nextDouble() * total
        for (i in 0 until n) {
            x -= w(i)
            if (x <= 0) return i
        }
        return n - 1
    }

    private fun next(prev: PeriodPlan, start: LocalDate): PeriodPlan {
        val rng = Random(HistoryCsv.derive(seed, model.slug, start, "period"))
        val lines = prev.lines.toMutableList()
        val changes = ArrayList<PlanChange>()
        val monthChange = start.month != start.minusDays(PERIOD_DAYS).month
        val ratio: Map<String, Double> = if (monthChange) {
            val a = weights(start.minusDays(PERIOD_DAYS))
            val b = weights(start)
            a.keys.associateWith { (b[it] ?: 1.0) / (a[it] ?: 1.0) }
        } else {
            emptyMap()
        }
        val depts = lines.map { it.department }.toSortedSet()
        val rising = if (monthChange) depts.filter { (ratio[it] ?: 1.0) >= 1.05 }.maxByOrNull { ratio[it] ?: 1.0 } else null

        for (dept in depts) {
            // 1. facings: a recipient line gains one or two facings from donor lines of its own level
            val transfers = 1 + (if (dept == rising) 1 else 0)
            repeat(transfers) {
                val span = knobs.linesMax - knobs.linesMin + 1
                val nLines = (knobs.linesMin + rng.nextInt(max(1, span))).coerceIn(2, 3)
                facingChange(lines, dept, nLines, rng, changes)
            }
            // 2. style swaps between neighbouring fixtures of the department
            repeat(rng.nextInt(knobs.swapMax + 1)) { styleSwap(lines, dept, rng, changes) }
        }
        // 3. seasonal range change: the rising department gets its best off-floor style in place of its slowest
        if (rising != null) rangeChange(lines, rising, changes)

        val old = prev.lines.associate { Triple(it.fixture, it.level, it.position) to Triple(it.ean, it.facings, it.required) }
        val new = lines.associate { Triple(it.fixture, it.level, it.position) to Triple(it.ean, it.facings, it.required) }
        val changed = (old.keys + new.keys).count { old[it] != new[it] }
        return PeriodPlan(start, lines.toList(), changes, changed)
    }

    private fun scaled(l: StoreDayModel.V2Line, facings: Int) =
        l.copy(facings = facings, required = max(1, Math.round(l.required.toDouble() * facings / l.facings).toInt()))

    private fun facingChange(lines: MutableList<StoreDayModel.V2Line>, dept: String, nLines: Int, rng: Random, changes: MutableList<PlanChange>) {
        val idx = lines.indices.filter { lines[it].department == dept && !protectedLine(lines[it]) }
        if (idx.size < 2) return
        repeat(8) {
            val r = idx[pick(rng, idx.size) { demandOf(lines[idx[it]].ean) / lines[idx[it]].facings }]
            val rec = lines[r]
            val donors = idx.filter { it != r && lines[it].fixture == rec.fixture && lines[it].level == rec.level && lines[it].facings >= 2 }
            val need = nLines - 1 // donors needed: one facing each
            if (donors.size < need) return@repeat
            val chosen = ArrayList<Int>()
            val pool = donors.toMutableList()
            repeat(need) {
                val d = pool.removeAt(pick(rng, pool.size) { 1.0 / (demandOf(lines[pool[it]].ean) * 1e4 + 1e-3) })
                chosen += d
            }
            changes += PlanChange("facings", dept, rec.fixture, rec.ean, "${rec.facings}", "${rec.facings + need}")
            lines[r] = scaled(rec, rec.facings + need)
            for (d in chosen) {
                val dl = lines[d]
                changes += PlanChange("facings", dept, dl.fixture, dl.ean, "${dl.facings}", "${dl.facings - 1}")
                lines[d] = scaled(dl, dl.facings - 1)
            }
            return
        }
    }

    private fun styleSwap(lines: MutableList<StoreDayModel.V2Line>, dept: String, rng: Random, changes: MutableList<PlanChange>) {
        val idx = lines.indices.filter { lines[it].department == dept && !protectedLine(lines[it]) }
        if (idx.isEmpty()) return
        repeat(8) {
            val a = idx[rng.nextInt(idx.size)]
            val la = lines[a]
            val near = neighbours[la.fixture].orEmpty().filter { model.fixtureDepartment[it] == dept }
            if (near.isEmpty()) return@repeat
            val bFix = near[rng.nextInt(near.size)]
            val cands = idx.filter { lines[it].fixture == bFix && lines[it].form == la.form && lines[it].ean != la.ean }
            if (cands.isEmpty()) return@repeat
            val same = cands.filter { lines[it].facings == la.facings }
            val pool = same.ifEmpty { cands }
            val b = pool[rng.nextInt(pool.size)]
            val lb = lines[b]
            changes += PlanChange("swap", dept, la.fixture, la.ean, "${la.fixture}", "${lb.fixture}")
            changes += PlanChange("swap", dept, lb.fixture, lb.ean, "${lb.fixture}", "${la.fixture}")
            lines[a] = la.copy(ean = lb.ean)
            lines[b] = lb.copy(ean = la.ean)
            return
        }
    }

    private fun rangeChange(lines: MutableList<StoreDayModel.V2Line>, dept: String, changes: MutableList<PlanChange>) {
        val onFloor = lines.map { it.ean }.toSet()
        val incoming =
            model.demand.values.filter {
                it.department == dept && it.ean !in onFloor && it.ean !in protectedEans
            }.sortedWith(compareBy({ -it.share }, { it.ean }))
                .firstOrNull() ?: return
        val slowest =
            lines.indices.filter {
                lines[it].department == dept && !protectedLine(lines[it])
            }.minWithOrNull(compareBy({ demandOf(lines[it].ean) }, { it }))
                ?: return
        val old = lines[slowest]
        changes += PlanChange("range", dept, old.fixture, old.ean, old.ean, incoming.ean)
        lines[slowest] = old.copy(ean = incoming.ean)
    }

    // ── files ─────────────────────────────────────────────────────────────────────────────────────────

    /** The period file's content: the header of the directive and what changed (the plan itself regenerates from the seed). */
    fun periodJson(p: PeriodPlan): String {
        val o = ConnectMappers.snake.createObjectNode()
        o.put("store", model.slug)
        o.put("period_start", p.start.toString())
        o.put("period_end", p.end.toString())
        o.put("external_directive_id", directiveId(p.start))
        o.put("previous_directive_id", directiveId(p.start.minusDays(PERIOD_DAYS)))
        o.put("lines", p.lines.size)
        o.put("total_required", p.lines.sumOf { it.required })
        o.put("lines_changed", p.linesChanged)
        o.put("plan_digest", p.digest)
        val arr = o.putArray("changes")
        p.changes.forEach {
            arr.addObject().apply {
                put("kind", it.kind)
                put("department", it.department)
                put("fixture", it.fixture)
                put("ean", it.ean)
                put("from", it.from)
                put("to", it.to)
            }
        }
        return ConnectMappers.snake.writerWithDefaultPrettyPrinter().writeValueAsString(o) + "\n"
    }

    /** Write `<dir>/<yyyy-mm-dd>.json` for [p] (no-op when the file already holds the same bytes). */
    fun writePeriodFile(dir: Path, p: PeriodPlan): Path {
        Files.createDirectories(dir)
        val f = dir.resolve("${p.start}.json")
        val text = periodJson(p)
        if (!Files.exists(f) || Files.readString(f) != text) Files.writeString(f, text)
        return f
    }

    companion object {
        /** The period the Phase 1 plan belongs to: the Monday the offline runs start on. Earlier dates use the same plan. */
        val ANCHOR: LocalDate = LocalDate.of(2026, 11, 2)
        const val PERIOD_DAYS = 7L

        /** Period plans use `PLNW-`, never the `PLN-` of the curated directives (a re-send of one id replaces that directive's targets). */
        const val ID_PREFIX = "PLNW"

        fun periodStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        /** Whole weeks from the anchor to the period starting [start] (negative before it). */
        fun weekIndex(start: LocalDate): Long = Math.floorDiv(ChronoUnit.DAYS.between(ANCHOR, start), PERIOD_DAYS)
    }
}

/** The knobs of the weekly plan changes (slice 2c); the per-department defaults are the brief's. */
data class PeriodKnobs(
    /** Lines that change facings per department per week: 2 to 3 (a level is full, so one gain needs at least one donor line). */
    val linesMin: Int = 2,
    val linesMax: Int = 3,
    /** Style swaps per department per week: 0 to this. */
    val swapMax: Int = 1,
)
