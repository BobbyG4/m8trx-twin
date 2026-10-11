package com.m8trx.twin.history

import com.m8trx.twin.history.HistoryCsv.fixed
import com.m8trx.twin.history.HistoryCsv.money
import com.m8trx.twin.history.HistoryCsv.row
import com.m8trx.twin.layer2.StoreCatalog
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Random
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The files that are not projections — compliance scores, tasks, alarms, stocktakes, KPI targets.
 *
 * Tasks and alarms are DERIVED from the generated days (a restock task names the fixture that actually sold
 * most that day; an `investigate` task follows each gate alarm), so the operational record and the
 * projections tell the same story. Each store-day / store-week / store-month draws from its own derived seed.
 */
class DerivedHistory(private val gen: HistoryGenerator, private val chainDir: Path, private val days: List<HistoryGenerator.DayRows>) {

    private val seed = gen.params.seed
    private val story = gen.story

    private data class Product(val ean: String, val brand: String, val productType: String, val priceUsd: Double)

    private val products: Map<String, Map<String, Product>> = gen.stores.associate { s ->
        val lines = Files.readAllLines(chainDir.resolve("stores/${s.slug}/assortment.csv"))
        val h = StoreCatalog.splitCsv(lines.first())
        val iEan = h.indexOf("ean")
        val iBrand = h.indexOf("brand")
        val iType = h.indexOf("product_type")
        val iPrice = h.indexOf("price_usd")
        s.slug to lines.drop(1).map { StoreCatalog.splitCsv(it) }.filter { it.size > maxOf(iEan, iBrand, iType, iPrice) }
            .associate { it[iEan] to Product(it[iEan], it[iBrand], it[iType], it[iPrice].toDoubleOrNull() ?: 0.0) }
    }

    /** store → EPC rows (epc, ean), file order. */
    private val epcs: Map<String, List<Pair<String, String>>> = gen.stores.associate { s ->
        val lines = Files.readAllLines(chainDir.resolve("stores/${s.slug}/epcs.csv"))
        val h = lines.first().split(",")
        val iEpc = h.indexOf("epc")
        val iEan = h.indexOf("ean")
        s.slug to lines.drop(1).map { it.split(",") }.map { it[iEpc] to it[iEan] }
    }
    private val epcsByEan: Map<String, Map<String, List<String>>> = epcs.mapValues { (_, l) -> l.groupBy({ it.second }, { it.first }) }

    private fun local(store: ChainKeys.Store, date: LocalDate, minutesAfterMidnight: Int): Instant =
        ZonedDateTime.of(date, LocalTime.MIDNIGHT, store.zone).plusMinutes(minutesAfterMidnight.toLong()).toInstant()

    private val openMin = 10 * 60
    private val closeMin = 21 * 60

    // ── compliance ────────────────────────────────────────────────────────────

    /** Base weekly score per store — most 85–95 %, Marseille 70–80 %, Lille climbing with its turnaround. */
    private fun baseScore(slug: String, weekStart: LocalDate): Double = when (slug) {
        "dec-us-denver" -> 93.0
        "dec-us-nyc" -> 90.0
        "dec-us-sf" -> 89.0
        "dec-fr-paris" -> 91.0
        "dec-fr-lyon" -> 92.0
        "dec-fr-bordeaux" -> 88.0
        "dec-fr-marseille" -> 75.0
        "dec-fr-lille" -> 85.5 + 7.0 * story.turnaround(weekStart.plusDays(3))
        "dec-kr-seoul" -> 94.0
        "dec-kr-busan" -> 90.0
        else -> 90.0
    }

    fun compliance(): List<String> = gen.stores.flatMap { store ->
        val last = gen.lastDay(store) ?: return@flatMap emptyList()
        val targets = store.fixtures.count { !it.department.isNullOrBlank() }
        val marseille = store.slug == "dec-fr-marseille"
        generateSequence(gen.params.from.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))) { it.plusWeeks(1) }
            .takeWhile { !it.plusDays(6).isAfter(last) }
            .map { week ->
                val rng = Random(HistoryCsv.derive(seed, store.slug, week, "compliance"))
                var score = baseScore(store.slug, week) + rng.nextGaussian() * 2.0
                if (story.weekAfterPromo(week)) score -= 7.0 + rng.nextDouble() * 3.0 // fixtures moved for the promo
                score = score.coerceIn(if (marseille) 68.0 else 80.0, 98.5)
                if (marseille) score = score.coerceAtMost(80.0)
                val nonCompliant = (targets * (100.0 - score) / 100.0).roundToInt()
                val drift = (nonCompliant * (0.35 + rng.nextDouble() * 0.25)).roundToInt()
                val remediatedShare = if (marseille) 0.60 + rng.nextDouble() * 0.15 else 0.82 + rng.nextDouble() * 0.15
                row(store.slug, week, fixed(score, 1), targets, nonCompliant, drift, (nonCompliant * remediatedShare).roundToInt())
            }.toList()
    }

    // ── alarms ────────────────────────────────────────────────────────────────

    private data class AlarmRow(
        val store: ChainKeys.Store,
        val at: Instant,
        val epc: String,
        val ean: String,
        val fixture: String,
        val resolution: String?,
        val resolvedAt: Instant?,
    )

    private val alarmRows: List<AlarmRow> by lazy { buildAlarms() }

    private fun buildAlarms(): List<AlarmRow> = days.groupBy { it.slug }.toSortedMap().flatMap { (slug, ds) ->
        val store = gen.keys.store(slug)
        val last = gen.lastDay(store)!!
        ds.flatMap { d ->
            d.alarms.mapIndexedNotNull { i, a ->
                val rng = Random(HistoryCsv.derive(seed, slug, d.date, "alarm", i))
                val pool = epcsByEan[slug]?.get(a.ean)
                if (pool.isNullOrEmpty()) return@mapIndexedNotNull null
                val epc = pool[rng.nextInt(pool.size)]
                val recent = ChronoUnit.DAYS.between(d.date, last) < 14
                if (recent && rng.nextDouble() < OPEN_ALARM_SHARE) {
                    AlarmRow(store, a.at, epc, a.ean, a.fixture, null, null)
                } else {
                    val r = rng.nextDouble()
                    val resolution = when {
                        r < 0.55 -> "recovered"
                        r < 0.80 -> "false_alarm"
                        else -> "not_recovered"
                    }
                    AlarmRow(store, a.at, epc, a.ean, a.fixture, resolution, a.at.plusSeconds(180L + rng.nextInt(22 * 60)))
                }
            }
        }
    }

    fun alarms(): List<String> = alarmRows.map { a ->
        val price = products[a.store.slug]?.get(a.ean)?.priceUsd ?: 0.0
        row(a.store.slug, a.at, a.epc, a.ean, GATE_CODE, if (price >= 300.0) "critical" else "warning", a.resolvedAt, a.resolution)
    }

    // ── tasks ─────────────────────────────────────────────────────────────────

    private data class Task(
        val zoneKey: String?,
        val type: String,
        val priority: String,
        val title: String,
        val role: String,
        /** minutes after local midnight the task cannot be created before (a follow-up to an alarm) */
        val notBeforeMin: Int? = null,
    )

    fun tasks(): List<String> {
        val alarmsByDay = alarmRows.groupBy { it.store.slug to it.at.atZone(it.store.zone).toLocalDate() }
        return days.flatMap { d ->
            val store = gen.keys.store(d.slug)
            val last = gen.lastDay(store)!!
            val rng = Random(HistoryCsv.derive(seed, d.slug, d.date, "tasks"))
            val base = when {
                store.areaSqm < 350 -> 3 + rng.nextInt(2)
                store.areaSqm < 550 -> 3 + rng.nextInt(3)
                else -> 4 + rng.nextInt(3)
            }
            val planned = (0 until base).map { pickTask(store, d, rng) } +
                alarmsByDay[d.slug to d.date].orEmpty().flatMap { followUps(store, it) }
            val daysFromEnd = ChronoUnit.DAYS.between(d.date, last)
            planned.map { t -> scheduleTask(store, d.date, daysFromEnd, t, rng) }
        }
    }

    private fun pickTask(store: ChainKeys.Store, d: HistoryGenerator.DayRows, rng: Random): Task {
        val depts = store.departments.sortedBy { it.name }
        val dept = depts[rng.nextInt(depts.size)]
        val deptFixtures = store.fixtures.filter { it.department == dept.department }.ifEmpty { store.fixtures }
        val anyFixture = deptFixtures[rng.nextInt(deptFixtures.size)].code
        val sold = d.topSold.getOrNull(rng.nextInt(d.topSold.size.coerceAtLeast(1)))
        val soldProduct = sold?.let { products[d.slug]?.get(it.third) }
        val weights = buildList {
            add("restock" to 0.24)
            add("replenishment_request" to 0.12)
            add("clean_refold" to 0.11)
            add("compliance_check" to 0.09)
            add("spatial_check" to 0.08)
            add("pick" to 0.09)
            add("remove_excess" to 0.05)
            add("tag_replacement" to 0.05)
            add("custom" to 0.03)
            if (story.weekBeforePromo(d.date)) add("pre_stage" to 0.25)
            if (story.weekAfterPromo(d.date)) {
                add("fixture_relocation" to 0.20)
                add("spatial_remediation" to 0.15)
            }
            val month = d.date.monthValue
            if (month in 3..4 || month == 9) add("clearance_relocate" to 0.06)
        }
        val promo = story.promos.firstOrNull { d.date.isBefore(it.start) } ?: story.promos.last()
        val prevPromo = story.promos.lastOrNull { !d.date.isBefore(it.end) } ?: story.promos.first()
        return when (pickWeighted(weights, rng)) {
            "restock" -> if (sold != null && soldProduct != null) {
                Task(
                    sold.first,
                    "restock",
                    pri(rng, 0.65, 0.30),
                    "Restock ${soldProduct.brand} ${soldProduct.productType.lowercase()} at ${sold.first}",
                    ROLE_FLOOR,
                )
            } else {
                Task(anyFixture, "restock", pri(rng, 0.7, 0.2), "Restock ${dept.name} bay $anyFixture", ROLE_FLOOR)
            }

            "replenishment_request" -> if (soldProduct != null) {
                Task(
                    sold.first,
                    "replenishment_request",
                    pri(rng, 0.6, 0.3),
                    "Replenish ${soldProduct.brand} sizes from stockroom — ${sold.first}",
                    ROLE_FLOOR,
                )
            } else {
                Task(anyFixture, "replenishment_request", pri(rng, 0.7, 0.2), "Replenish $anyFixture from stockroom", ROLE_FLOOR)
            }

            "clean_refold" -> Task(anyFixture, "clean_refold", pri(rng, 0.5, 0.05), "Refold and face up ${dept.name} — $anyFixture", ROLE_FLOOR)

            "compliance_check" -> Task(
                anyFixture,
                "compliance_check",
                pri(rng, 0.7, 0.25),
                "Planogram check $anyFixture — ${dept.name}",
                ROLE_MANAGER,
            )

            "spatial_check" -> Task(dept.name, "spatial_check", pri(rng, 0.75, 0.15), "Count ${dept.name.lowercase()} wall", ROLE_FLOOR)

            "pick" -> if (soldProduct != null) {
                Task(sold.first, "pick", pri(rng, 0.5, 0.45), "Pick click-and-collect order — ${soldProduct.brand} at ${sold.first}", ROLE_FLOOR)
            } else {
                Task(anyFixture, "pick", pri(rng, 0.5, 0.45), "Pick click-and-collect order at $anyFixture", ROLE_FLOOR)
            }

            "remove_excess" -> Task(anyFixture, "remove_excess", pri(rng, 0.6, 0.1), "Move excess stock from $anyFixture to stockroom", ROLE_FLOOR)

            "tag_replacement" -> Task(dept.name, "tag_replacement", pri(rng, 0.6, 0.1), "Replace damaged price tags — ${dept.name}", ROLE_FLOOR)

            "custom" -> Task(null, "custom", pri(rng, 0.7, 0.2), "Follow up customer special order", ROLE_MANAGER)

            "pre_stage" -> Task(dept.name, "pre_stage", pri(rng, 0.4, 0.55), "Pre-stage ${promo.name} stock — ${dept.name}", ROLE_FLOOR)

            "fixture_relocation" -> Task(
                anyFixture,
                "fixture_relocation",
                pri(rng, 0.35, 0.55),
                "Move $anyFixture back to its planogram position",
                ROLE_FLOOR,
            )

            "spatial_remediation" -> Task(
                anyFixture,
                "spatial_remediation",
                pri(rng, 0.3, 0.6),
                "Fix endcap $anyFixture — planogram after ${prevPromo.name}",
                ROLE_MANAGER,
            )

            else -> Task(
                anyFixture,
                "clearance_relocate",
                pri(rng, 0.7, 0.2),
                "Move end-of-season ${dept.name.lowercase()} to clearance — $anyFixture",
                ROLE_FLOOR,
            )
        }
    }

    /** One investigation per gate alarm; one recovery task when the unit came back. */
    private fun followUps(store: ChainKeys.Store, a: AlarmRow): List<Task> {
        val p = products[store.slug]?.get(a.ean)
        val what = p?.let { "${it.brand} ${it.productType.lowercase()}" } ?: a.ean
        val key = a.fixture.takeIf { store.byCode.containsKey(it) }
        val at = a.at.atZone(store.zone)
        val after = at.hour * 60 + at.minute + 5
        val urgency = if ((p?.priceUsd ?: 0.0) >= 300.0) "urgent" else "high"
        val out = mutableListOf(Task(key, "investigate", urgency, "Investigate gate alarm $GATE_CODE — $what", ROLE_MANAGER, after))
        if (a.resolution == "recovered") out += Task(key, "recovery", "normal", "Return recovered $what to ${a.fixture}", ROLE_FLOOR, after + 20)
        return out
    }

    private fun scheduleTask(store: ChainKeys.Store, date: LocalDate, daysFromEnd: Long, t: Task, rng: Random): String {
        val drawn = openMin + rng.nextInt(8 * 60 + 30)
        val createdMin = t.notBeforeMin?.coerceIn(openMin, closeMin - 1) ?: drawn
        val created = local(store, date, createdMin)
        val dueMin = createdMin + 120 + rng.nextInt(4 * 60)
        val due = if (dueMin <= closeMin) local(store, date, dueMin) else local(store, nextOpen(store, date), 12 * 60)
        val startMin = createdMin + 5 + rng.nextInt(40)
        val workMin = 10 + rng.nextInt(80)
        // work that cannot start before close starts the next morning (only possible for a late alarm follow-up)
        val startsToday = startMin <= closeMin - 10
        val nextMorning = openMin + 5 + rng.nextInt(40)
        val next = nextOpen(store, date)

        if (daysFromEnd < 3) {
            // the last three days hold the open work — a live floor, not a closed ledger
            val r = rng.nextDouble()
            val status = when {
                daysFromEnd > 0 && r < 0.70 -> "completed"
                daysFromEnd == 0L && r < 0.40 -> "completed"
                r < 0.80 -> "in_progress"
                r < 0.88 -> "assigned"
                r < 0.96 -> "open"
                else -> "overdue"
            }
            return when (status) {
                "completed" -> if (startsToday) {
                    taskRow(store, t, status, created, due, local(store, date, startMin), local(store, date, minOf(startMin + workMin, closeMin - 1)))
                } else {
                    taskRow(store, t, "in_progress", created, due, local(store, date, closeMin - 1), null)
                }

                "in_progress" -> taskRow(store, t, status, created, due, local(store, date, minOf(startMin, closeMin - 1)), null)

                // overdue: it was due within the hour and nobody has picked it up
                "overdue" -> taskRow(store, t, status, created, local(store, date, minOf(createdMin + 60, closeMin)), null, null)

                else -> taskRow(store, t, status, created, due, null, null)
            }
        }
        if (rng.nextDouble() < CANCEL_SHARE) return taskRow(store, t, "canceled", created, due, null, null)
        val endMin = startMin + workMin
        return when {
            !startsToday -> taskRow(store, t, "completed", created, due, local(store, next, nextMorning), local(store, next, nextMorning + workMin))

            // ~10 % slip past close and are finished the next morning — "within a day", inside hours
            endMin > closeMin - 5 -> taskRow(store, t, "completed", created, due, local(store, date, startMin), local(store, next, nextMorning + 20))

            else -> taskRow(store, t, "completed", created, due, local(store, date, startMin), local(store, date, endMin))
        }
    }

    private fun nextOpen(store: ChainKeys.Store, date: LocalDate): LocalDate {
        var d = date.plusDays(1)
        while (!story.isOpen(store, d)) d = d.plusDays(1)
        return d
    }

    private fun taskRow(store: ChainKeys.Store, t: Task, status: String, created: Instant, due: Instant, started: Instant?, completed: Instant?) =
        row(store.slug, t.zoneKey, t.type, t.priority, t.title, status, created, due, started, completed, t.role)

    /** priority draw: P(normal)=pNormal, P(high)=pHigh, remainder split low / urgent 2:1 */
    private fun pri(rng: Random, pNormal: Double, pHigh: Double): String {
        val r = rng.nextDouble()
        return when {
            r < pNormal -> "normal"
            r < pNormal + pHigh -> "high"
            r < pNormal + pHigh + (1 - pNormal - pHigh) * 2 / 3 -> "low"
            else -> "urgent"
        }
    }

    private fun pickWeighted(weights: List<Pair<String, Double>>, rng: Random): String {
        var r = rng.nextDouble() * weights.sumOf { it.second }
        for ((k, w) in weights) {
            r -= w
            if (r <= 0) return k
        }
        return weights.last().first
    }

    // ── stocktakes ────────────────────────────────────────────────────────────

    fun stocktakes(): List<String> = gen.stores.flatMap { store ->
        val days = gen.days(store).toSet()
        val last = gen.lastDay(store) ?: return@flatMap emptyList()
        val all = epcs.getValue(store.slug)
        val price = products.getValue(store.slug)
        generateSequence(gen.params.from.withDayOfMonth(1)) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(last) }
            .mapNotNull { month ->
                // the first Tuesday of the month the store is open (a holiday pushes it a day)
                var day = month.with(TemporalAdjusters.firstInMonth(DayOfWeek.TUESDAY))
                while (day !in days && !day.isAfter(last)) day = day.plusDays(1)
                if (day.isAfter(last)) return@mapNotNull null
                val rng = Random(HistoryCsv.derive(seed, store.slug, month, "stocktake"))
                val expected = all.size
                val missRate = if (store.slug == "dec-fr-marseille") 0.030 + rng.nextDouble() * 0.010 else 0.010 + rng.nextDouble() * 0.020
                val missing = (expected * missRate).roundToInt()
                val misplaced = (expected * (0.003 + rng.nextDouble() * 0.005)).roundToInt()
                val ghost = (expected * (0.0005 + rng.nextDouble() * 0.001)).roundToInt().coerceAtLeast(1)
                // value of the missing units at unit_price: a seeded sample of the store's own EPCs
                val idx = (0 until expected).shuffled(rng).take(missing)
                val missingCents = idx.sumOf { i -> ((price[all[i].second]?.priceUsd ?: 0.0) * 100).roundToLong() }
                // RFID stocktake before opening: 08:00 local, ~3,000 tags an hour per walker, two walkers
                val start = local(store, day, 8 * 60)
                val minutes = (expected / 6000.0 * 60).roundToInt() + 20 + rng.nextInt(20)
                row(
                    store.slug, start,
                    start.plusSeconds(
                        minutes * 60L,
                    ),
                    "general", expected - missing + ghost, expected, missing, misplaced, ghost, money(missingCents),
                )
            }.toList()
    }

    // ── KPI targets ───────────────────────────────────────────────────────────

    /**
     * The plan the dashboards compare against: the operating model at the store's area with seasonality,
     * holidays and promos, but WITHOUT the store's own story — so Marseille reads below plan, Lille below
     * then above, Denver ahead. Monthly targets; the plan's ATV is the model's `atv_usd`.
     */
    fun kpiTargets(): List<String> = gen.stores.flatMap { store ->
        val (visitsPerDay, txnPerDay, conversion) = gen.plannedDaily(store)
        val perMonth = 365.0 / 12.0
        val visits = (visitsPerDay * perMonth / 100.0).roundToLong() * 100
        val sales = (txnPerDay * gen.model.basket.atvUsd.value * perMonth / 1000.0).roundToLong() * 1000
        listOf(
            row(store.slug, "sales_value", "$sales.00", "month", "USD"),
            row(store.slug, "visit_count", visits, "month", "USD"),
            row(store.slug, "conversion_rate", fixed(conversion, 4), "month", "USD"),
        )
    }

    companion object {
        const val GATE_CODE = "CS-01"
        const val ROLE_FLOOR = "floor_associate"
        const val ROLE_MANAGER = "site_manager"
        const val OPEN_ALARM_SHARE = 0.15
        const val CANCEL_SHARE = 0.03
    }
}
