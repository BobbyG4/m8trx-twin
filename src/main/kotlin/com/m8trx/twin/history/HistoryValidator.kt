package com.m8trx.twin.history

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Checks a generated history directory against the file contract and mig 259's pre-conditions. Reads the
 * FILES, never the generator's memory — what is asserted is exactly what the loader will see.
 *
 * Each check yields `(name, ok, detail)`; the caller decides how to report.
 */
class HistoryValidator(private val gen: HistoryGenerator, private val dir: Path) {

    data class Check(val name: String, val ok: Boolean, val detail: String = "")

    private val out = ArrayList<Check>()
    private fun check(name: String, ok: Boolean, detail: String = "") {
        out += Check(name, ok, if (ok) "" else detail)
    }

    private fun read(f: String) = HistoryCsv.read(dir.resolve(f))
    private fun ts(s: String): Instant = Instant.parse(s)

    private val windowFloor: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val windowCeil: Instant get() = gen.params.cutoff

    fun all(totalsTolerancePct: Double = 5.0): List<Check> {
        manifest()
        headers()
        hourly()
        daily()
        keys()
        sums()
        vocabularies()
        tasks()
        alarms()
        totals(totalsTolerancePct)
        return out
    }

    private val files = listOf(
        "commerce_store_hourly.csv" to HistoryGenerator.COMMERCE_HOURLY_HEADER,
        "commerce_department_daily.csv" to HistoryGenerator.COMMERCE_DEPT_HEADER,
        "traffic_store_hourly.csv" to HistoryGenerator.TRAFFIC_HOURLY_HEADER,
        "traffic_department_daily.csv" to HistoryGenerator.TRAFFIC_DEPT_HEADER,
        "behavioral_fixture_daily.csv" to HistoryGenerator.BEHAVIORAL_HEADER,
        "compliance_weekly.csv" to HistoryGenerator.COMPLIANCE_HEADER,
        "tasks.csv" to HistoryGenerator.TASKS_HEADER,
        "alarms.csv" to HistoryGenerator.ALARMS_HEADER,
        "stocktakes.csv" to HistoryGenerator.STOCKTAKES_HEADER,
        "kpi_targets.csv" to HistoryGenerator.KPI_HEADER,
    )

    // ── MANIFEST ──────────────────────────────────────────────────────────────

    private fun manifest() {
        val (h, rows) = read("MANIFEST.csv")
        check("MANIFEST header is the contract's", h == HistoryGenerator.MANIFEST_HEADER, "got $h")
        check("MANIFEST lists every data file once", rows.map { it[0] }.sorted() == files.map { it.first }.sorted(), "got ${rows.map { it[0] }}")
        rows.forEach { r ->
            val p = dir.resolve(r[0])
            val actualRows = HistoryCsv.read(p).second.size
            check("MANIFEST rows = file rows — ${r[0]}", r[1].toInt() == actualRows, "manifest ${r[1]} vs file $actualRows")
            check("MANIFEST sha256 matches — ${r[0]}", r[2] == HistoryCsv.sha256(p), "manifest ${r[2]}")
            check("MANIFEST seed is the run's — ${r[0]}", r[3] == gen.params.seed.toString(), "manifest seed ${r[3]}")
        }
    }

    private fun headers() {
        files.forEach { (f, header) -> check("header — $f", read(f).first == header, "got ${read(f).first}") }
    }

    // ── operating hours ───────────────────────────────────────────────────────

    private val openHours = gen.model.store.operatingHours.openHours

    private fun hourly() {
        for (f in listOf("commerce_store_hourly.csv", "traffic_store_hourly.csv")) {
            val rows = read(f).second
            val bad = rows.filter { r ->
                val s = gen.keys.stores.firstOrNull { it.slug == r[0] } ?: return@filter true
                val a = ts(r[1]).atZone(s.zone)
                val b = ts(r[2])
                a.minute != 0 || a.hour !in 10 until 10 + openHours || Duration.between(a.toInstant(), b) != Duration.ofHours(1)
            }
            check(
                "no hourly row outside operating hours (10:00–21:00 local, 1 h windows) — $f",
                bad.isEmpty(),
                "${bad.size} bad, e.g. ${bad.firstOrNull()}",
            )
            val perDay = rows.groupingBy { it[0] to localDate(it[0], it[1]) }.eachCount()
            val notEleven = perDay.filterValues { it != openHours }
            check("eleven hourly rows per open store-day — $f", notEleven.isEmpty(), "e.g. ${notEleven.entries.take(3)}")
            val outside = rows.filter { ts(it[1]).isBefore(windowFloor) || ts(it[2]).isAfter(windowCeil) }
            check("every window inside 2026-01-01 .. ${HistoryCsv.ts(windowCeil)} — $f", outside.isEmpty(), "e.g. ${outside.firstOrNull()}")
            val dupes = rows.groupingBy { it[0] to it[1] }.eachCount().filterValues { it > 1 }
            check("no duplicate (store, window) — $f", dupes.isEmpty(), "e.g. ${dupes.keys.take(3)}")
        }
        // closed days carry no rows
        val days = read("commerce_store_hourly.csv").second.map { it[0] to localDate(it[0], it[1]) }.toSet()
        val closedWithRows = days.filter { (slug, d) -> !gen.story.isOpen(gen.keys.store(slug), d) }
        check("closed holidays carry no rows", closedWithRows.isEmpty(), "e.g. $closedWithRows")
    }

    private fun localDate(slug: String, isoTs: String): LocalDate = ts(isoTs).atZone(gen.keys.store(slug).zone).toLocalDate()

    private fun daily() {
        for (f in listOf("commerce_department_daily.csv", "traffic_department_daily.csv", "behavioral_fixture_daily.csv")) {
            val rows = read(f).second
            val bad = rows.filter { r ->
                val z = gen.keys.store(r[0]).zone
                val a = ts(r[2]).atZone(z)
                val b = ts(r[3]).atZone(z)
                a.hour != 10 || a.minute != 0 || b.hour != 10 + openHours || b.minute != 0 || a.toLocalDate() != b.toLocalDate()
            }
            check("daily windows are the operating day [10:00, 21:00) local — $f", bad.isEmpty(), "${bad.size} bad, e.g. ${bad.firstOrNull()}")
            val outside = rows.filter { ts(it[2]).isBefore(windowFloor) || ts(it[3]).isAfter(windowCeil) }
            check("every window inside the backfill range — $f", outside.isEmpty(), "e.g. ${outside.firstOrNull()}")
        }
    }

    // ── keys ──────────────────────────────────────────────────────────────────

    private fun keys() {
        val slugs = gen.keys.stores.map { it.slug }.toSet()
        files.forEach { (f, _) ->
            val unknown = read(f).second.map { it[0] }.filter { it !in slugs }.toSet()
            check("every site slug resolves — $f", unknown.isEmpty(), "unknown $unknown")
        }
        for (f in listOf("commerce_department_daily.csv", "traffic_department_daily.csv")) {
            val unknown = read(f).second.filter { r -> gen.keys.store(r[0]).regions.none { it.name == r[1] } }.map { it[0] + "/" + it[1] }.toSet()
            check("every department resolves to a REGION zone by name — $f", unknown.isEmpty(), "unknown ${unknown.take(5)}")
        }
        val unknownFix = read("behavioral_fixture_daily.csv").second
            .filter { r -> gen.keys.store(r[0]).fixtures.none { it.code == r[1] } }.map { it[0] + "/" + it[1] }.toSet()
        check("every fixture code resolves to a FIXTURE zone", unknownFix.isEmpty(), "unknown ${unknownFix.take(5)}")
        // mig 259 LEFT JOINs a task's key against name OR code across all zones — it must match exactly one
        val ambiguous = read("tasks.csv").second.filter { it[1].isNotEmpty() }.filter { r ->
            gen.keys.store(r[0]).zones.count { it.name == r[1] || it.code == r[1] } != 1
        }.map { it[0] + "/" + it[1] }.toSet()
        check("every task zone key resolves to exactly one zone (name or code)", ambiguous.isEmpty(), "bad ${ambiguous.take(5)}")
        val skus = gen.stores.associate { s ->
            s.slug to
                HistoryCsv.read(Path.of("reference/data/chain/stores/${s.slug}/assortment.csv")).second.map { it[0] }.toSet()
        }
        val badSku = read("alarms.csv").second.filter { r -> skus[r[0]]?.contains(r[3]) != true }
        check("every alarm sku (EAN) is in the store's assortment", badSku.isEmpty(), "e.g. ${badSku.firstOrNull()}")
    }

    // ── internal consistency ─────────────────────────────────────────────────

    private fun sums() {
        val ch = read("commerce_store_hourly.csv").second
        val th = read("traffic_store_hourly.csv").second
        val visitsByWindow = th.associate { (it[0] to it[1]) to it[3].toLong() }

        val convBad = ch.filter { r ->
            val txn = r[5].toLong()
            val visits = visitsByWindow[r[0] to r[1]] ?: return@filter true
            val conv = r[6].takeIf { it.isNotEmpty() }?.toDouble()
            if (visits == 0L) conv != null || txn != 0L else conv == null || conv > 1.0 || kotlin.math.abs(conv - txn.toDouble() / visits) > 1e-4
        }
        check(
            "conversion_rate ≤ 1 and = transactions ÷ visits in the same window",
            convBad.isEmpty(),
            "${convBad.size} bad, e.g. ${convBad.firstOrNull()}",
        )

        val basketBad = ch.filter { r ->
            val txn = r[5].toLong()
            if (txn == 0L) {
                r[7].isNotEmpty() || r[8].isNotEmpty() || r[3] != "0"
            } else {
                kotlin.math.abs(r[7].toDouble() - HistoryReport.cents(r[4]) / 100.0 / txn) > 0.006 ||
                    kotlin.math.abs(r[8].toDouble() - r[3].toDouble() / txn) > 0.0006
            }
        }
        check("avg_basket_value = value ÷ txn, units_per_transaction = units ÷ txn", basketBad.isEmpty(), "e.g. ${basketBad.firstOrNull()}")

        val transitBad = th.filter { it[4].toLong() > it[3].toLong() }
        check("transit_count ≤ visit_count", transitBad.isEmpty(), "e.g. ${transitBad.firstOrNull()}")

        // hourly sums = the day = department sums (units, cents and transactions all exact)
        data class Tot(var units: Long = 0, var cents: Long = 0, var txn: Long = 0, var visits: Long = 0)
        val day = HashMap<Pair<String, LocalDate>, Tot>()
        ch.forEach { r ->
            day.getOrPut(r[0] to localDate(r[0], r[1])) { Tot() }.apply {
                units += r[3].toLong()
                cents += HistoryReport.cents(r[4])
                txn += r[5].toLong()
            }
        }
        th.forEach { r -> day[r[0] to localDate(r[0], r[1])]?.apply { visits += r[3].toLong() } }
        val dept = HashMap<Pair<String, LocalDate>, Tot>()
        read("commerce_department_daily.csv").second.forEach { r ->
            dept.getOrPut(r[0] to localDate(r[0], r[2])) { Tot() }.apply {
                units += r[4].toLong()
                cents += HistoryReport.cents(r[5])
                txn += r[6].toLong()
            }
        }
        val deptBad = day.filter { (k, t) ->
            val d = dept[k] ?: return@filter true
            d.units != t.units || d.cents != t.cents || d.txn != t.txn
        }
        check(
            "department sums = the store's hourly sums for the day (units, value, transactions)",
            deptBad.isEmpty(),
            "${deptBad.size} days, e.g. ${deptBad.keys.firstOrNull()}",
        )
        check("every store-day has department rows and hourly rows alike", dept.keys == day.keys, "dept ${dept.size} vs hourly ${day.size}")

        val trafficDeptBad = read("traffic_department_daily.csv").second.filter { r ->
            r[4].toLong() > (day[r[0] to localDate(r[0], r[2])]?.visits ?: -1)
        }
        check("department visits ≤ the store's visits that day", trafficDeptBad.isEmpty(), "e.g. ${trafficDeptBad.firstOrNull()}")
        val fixBad = read("behavioral_fixture_daily.csv").second.filter { r ->
            r[4].toLong() > (day[r[0] to localDate(r[0], r[2])]?.visits ?: -1)
        }
        check("fixture impressions (distinct shoppers) ≤ the store's visits that day", fixBad.isEmpty(), "e.g. ${fixBad.firstOrNull()}")
        val negative = files.flatMap { (f, _) -> read(f).second.map { f to it } }.filter { (_, r) -> r.any { it.startsWith("-") } }
        check("no negative value anywhere", negative.isEmpty(), "e.g. ${negative.firstOrNull()}")
    }

    // ── vocabularies ──────────────────────────────────────────────────────────

    private fun vocabularies() {
        val tasks = read("tasks.csv").second
        check("task.type ∈ the live CHECK list", tasks.all { it[2] in TASK_TYPES }, "bad ${tasks.map { it[2] }.filter { it !in TASK_TYPES }.toSet()}")
        check("task.priority ∈ low/normal/high/urgent", tasks.all { it[3] in setOf("low", "normal", "high", "urgent") })
        check("task.status ∈ the task_status enum", tasks.all { it[5] in TASK_STATUSES })
        val kpi = read("kpi_targets.csv").second
        check(
            "kpi metric_type ∈ sales_value · visit_count · conversion_rate",
            kpi.all {
                it[1] in
                    setOf("sales_value", "visit_count", "conversion_rate")
            },
        )
        check(
            "kpi: one row per store × metric",
            kpi.map {
                it[0] to it[1]
            }.toSet().size == kpi.size && kpi.size == gen.stores.size * 3,
            "rows ${kpi.size}",
        )
        val st = read("stocktakes.csv").second
        check(
            "stocktakes: mode general, scanned = expected − missing + ghost",
            st.all {
                it[3] == "general" &&
                    it[4].toInt() == it[5].toInt() - it[6].toInt() + it[8].toInt()
            },
        )
        val comp = read("compliance_weekly.csv").second
        check(
            "compliance: score 0..100, counts consistent (drift, remediated ≤ non_compliant ≤ targets)",
            comp.all {
                val sc = it[2].toDouble()
                val t = it[3].toInt()
                val nc = it[4].toInt()
                sc in 0.0..100.0 && nc <= t && it[5].toInt() <= nc && it[6].toInt() <= nc
            },
        )
        check("compliance weeks start on Mondays", comp.all { LocalDate.parse(it[1]).dayOfWeek == java.time.DayOfWeek.MONDAY })
        // the trailing partial week (last day >= week_start + 5) is scored, none beyond it
        val lastWeek = comp.groupBy { it[0] }.mapValues { (_, r) -> r.maxOf { LocalDate.parse(it[1]) } }
        val trailing = gen.stores.filter { s ->
            val last = gen.lastDay(s) ?: return@filter false
            val expected = last.minusDays(5).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            lastWeek[s.slug] != expected
        }
        check("compliance scores the trailing partial week", trailing.isEmpty(), "e.g. ${trailing.take(3).map { it.slug to lastWeek[it.slug] }}")
    }

    // ── tasks ─────────────────────────────────────────────────────────────────

    private fun tasks() {
        val rows = read("tasks.csv").second
        val lastDay = gen.stores.associate { it.slug to gen.lastDay(it) }
        val openish = setOf("open", "assigned", "in_progress", "overdue")
        val oldOpen = rows.filter { r ->
            r[5] in openish && ChronoUnit.DAYS.between(localDate(r[0], r[6]), lastDay[r[0]]!!) >= 3
        }
        check("open work only in the store's last 3 days", oldOpen.isEmpty(), "e.g. ${oldOpen.firstOrNull()}")
        val past = rows.filter { ChronoUnit.DAYS.between(localDate(it[0], it[6]), lastDay[it[0]]!!) >= 3 }
        val done = past.count { it[5] == "completed" }
        val withinDay = past.count {
            it[5] == "completed" && Duration.between(ts(it[6]), ts(it[9])) <= Duration.ofHours(24)
        }
        check(
            "≥ 85 % of past tasks completed within a day",
            past.isEmpty() || withinDay >= 0.85 * past.size,
            "$withinDay of ${past.size} (completed at all: $done)",
        )
        val outside = rows.flatMap { r -> listOf(6, 8, 9).mapNotNull { i -> r[i].takeIf { it.isNotEmpty() }?.let { r to it } } }.filter { (r, t) ->
            val l = ts(t).atZone(gen.keys.store(r[0]).zone).toLocalTime()
            l.isBefore(java.time.LocalTime.of(10, 0)) || l.isAfter(java.time.LocalTime.of(21, 0))
        }
        check("task created/started/completed timestamps inside operating hours", outside.isEmpty(), "${outside.size}, e.g. ${outside.firstOrNull()}")
        val order = rows.filter { r ->
            val c = ts(r[6])
            (r[8].isNotEmpty() && ts(r[8]).isBefore(c)) || (r[9].isNotEmpty() && (r[8].isEmpty() || ts(r[9]).isBefore(ts(r[8]))))
        }
        check("task timestamps ordered created ≤ started ≤ completed", order.isEmpty(), "e.g. ${order.firstOrNull()}")
        val perDay = rows.groupingBy { it[0] to localDate(it[0], it[6]) }.eachCount()
        val few = perDay.filterValues { it < 3 }
        check("3+ tasks every store-day", few.isEmpty(), "e.g. ${few.entries.take(3)}")
    }

    private fun alarms() {
        val rows = read("alarms.csv").second
        val lastDay = gen.stores.associate { it.slug to gen.lastDay(it) }
        val oldOpen = rows.filter { it[6].isEmpty() && ChronoUnit.DAYS.between(localDate(it[0], it[1]), lastDay[it[0]]!!) >= 14 }
        check("unresolved alarms only within the last 14 days", oldOpen.isEmpty(), "e.g. ${oldOpen.firstOrNull()}")
        check(
            "alarms carry gate CS-01, an EPC and a severity",
            rows.all {
                it[4] == "CS-01" && it[2].length == 24 &&
                    it[5] in setOf("critical", "warning")
            },
        )
        // a gate alarm is a customer leaving: never stamped at or after close, nor before open
        val afterHours = rows.filter { r ->
            val s = gen.keys.store(r[0])
            val at = ts(r[1])
            val day = at.atZone(s.zone).toLocalDate()
            val close = gen.closeUtc(s, day)
            !at.isBefore(close) || at.isBefore(close.minusSeconds(3600L * openHours))
        }
        check("alarm created_at falls inside operating hours (close − 1 s at the latest)", afterHours.isEmpty(), "e.g. ${afterHours.firstOrNull()}")
        val weeks = gen.stores.sumOf { gen.days(it).size } / 7.0
        val perStoreWeek = if (weeks == 0.0) 0.0 else rows.size / weeks
        check("2–4 gate alarms per store-week (chain mean)", perStoreWeek in 1.8..4.2, "%.2f".format(perStoreWeek))
    }

    // ── totals vs the operating model ────────────────────────────────────────

    private fun totals(tol: Double) {
        HistoryReport.totals(gen, dir).forEach { t ->
            fun within(o: Double, e: Double) = e > 0 && kotlin.math.abs(o - e) / e * 100.0 <= tol
            check(
                "${t.slug}: visits within ±$tol% of the model",
                within(t.visits.toDouble(), t.expectedVisits),
                "${t.visits} vs %.0f".format(t.expectedVisits),
            )
            check(
                "${t.slug}: transactions within ±$tol% of the model",
                within(t.transactions.toDouble(), t.expectedTransactions),
                "${t.transactions} vs %.0f".format(t.expectedTransactions),
            )
            check(
                "${t.slug}: units within ±$tol% of the model",
                within(t.units.toDouble(), t.expectedUnits),
                "${t.units} vs %.0f".format(t.expectedUnits),
            )
            check(
                "${t.slug}: sales within ±$tol% of the model (txn × atv_usd)",
                within(t.cents.toDouble(), t.expectedCents),
                "%.0f vs %.0f".format(t.cents / 100.0, t.expectedCents / 100.0),
            )
        }
    }

    companion object {
        val TASK_TYPES = setOf(
            "replenishment_request", "remove_excess", "pre_stage", "restock", "pick", "fixture_relocation", "compliance_check",
            "tag_replacement", "clean_refold", "spatial_check", "spatial_remediation", "recovery", "investigate", "clearance_relocate",
            "custom", "srf_walk",
        )
        val TASK_STATUSES = setOf("open", "assigned", "in_progress", "completed", "canceled", "overdue")

        fun exists(dir: Path) = Files.exists(dir.resolve("MANIFEST.csv"))
    }
}
