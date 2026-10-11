package com.m8trx.twin.history

import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

/**
 * The brief's story beats, asserted on a generated year — the dashboards are only legible if these hold:
 * Marseille below plan, Lille's Q3 turnaround, Denver best in the US, one slow category chain-wide, the
 * promo weeks, Chuseok, the French August dip, the January trough, and compliance with its story.
 */
class StoryChecks(private val gen: HistoryGenerator, private val dir: Path) {

    private val out = ArrayList<HistoryValidator.Check>()
    private fun check(name: String, ok: Boolean, detail: String) {
        out += HistoryValidator.Check(name, ok, detail)
    }

    private data class Day(var visits: Long = 0, var cents: Long = 0)

    private val days: Map<Pair<String, LocalDate>, Day> = run {
        val m = HashMap<Pair<String, LocalDate>, Day>()
        fun key(slug: String, ts: String) = slug to Instant.parse(ts).atZone(gen.keys.store(slug).zone).toLocalDate()
        HistoryCsv.read(dir.resolve("commerce_store_hourly.csv")).second.forEach {
            m.getOrPut(key(it[0], it[1])) { Day() }.cents +=
                HistoryReport.cents(it[4])
        }
        HistoryCsv.read(dir.resolve("traffic_store_hourly.csv")).second.forEach { m.getOrPut(key(it[0], it[1])) { Day() }.visits += it[3].toLong() }
        m
    }

    /** Planned sales (cents) for a store-day — the KPI plan's own basis: model without the store's story. */
    private fun planCents(slug: String, d: LocalDate): Double {
        val s = gen.keys.store(slug)
        return gen.story.expectedVisitors(s, d, withStory = false) * gen.buyerShare(s, d, withStory = false) * gen.model.basket.atvUsd.value * 100
    }

    private fun vsPlan(slug: String, range: ClosedRange<LocalDate>): Double {
        val ds = days.filterKeys { it.first == slug && it.second in range }
        val plan = ds.keys.sumOf { planCents(slug, it.second) }
        return ds.values.sumOf { it.cents } / plan
    }

    private fun visitsPerDay(slugs: Collection<String>, range: ClosedRange<LocalDate>): Double {
        val ds = days.filterKeys { it.first in slugs && it.second in range }
        return ds.values.sumOf { it.visits }.toDouble() / ds.size.coerceAtLeast(1)
    }

    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)
    private fun pct(v: Double) = "%.1f%%".format((v - 1) * 100)

    fun all(): List<HistoryValidator.Check> {
        val q1 = d(1, 1)..d(3, 31)
        val q2 = d(4, 1)..d(6, 30)
        val q3 = d(7, 1)..d(9, 30)
        val h1 = d(1, 1)..d(6, 30)
        val stores = gen.stores.map { it.slug }

        // Marseille — consistently below plan
        val mq = listOf(q1, q2, q3).map { vsPlan("dec-fr-marseille", it) }
        check("Marseille is below plan in every quarter (< −10 %)", mq.all { it < 0.90 }, "Q1..Q3 ${mq.map { pct(it) }}")

        // Lille — the Q3 turnaround
        val lilleH1 = vsPlan("dec-fr-lille", h1)
        val lilleSep = vsPlan("dec-fr-lille", d(9, 1)..d(9, 30))
        check(
            "Lille turns around in Q3: H1 below plan, September above, ≥ 15 points of swing",
            lilleH1 < 0.95 && lilleSep > 1.0 && lilleSep - lilleH1 >= 0.15,
            "H1 ${pct(lilleH1)} → Sep ${pct(lilleSep)}",
        )

        // Denver — the best US store
        val us = stores.filter { it.startsWith("dec-us-") }
        val usSales = us.associateWith { s -> days.filterKeys { it.first == s }.values.sumOf { it.cents } }
        check("Denver has the highest sales in the US", usSales.maxBy { it.value }.key == "dec-us-denver", usSales.toString())

        // the slow category — climbing's sales per SKU carried is the chain's lowest
        val dept = HistoryCsv.read(dir.resolve("commerce_department_daily.csv")).second
        val deptCents = dept.groupBy { r -> gen.keys.store(r[0]).departments.first { it.name == r[1] }.department!! }
            .mapValues { (_, rs) -> rs.sumOf { HistoryReport.cents(it[5]) }.toDouble() }
        val skuCount = gen.stores.flatMap { s ->
            HistoryCsv.read(Path.of("reference/data/chain/stores/${s.slug}/assortment.csv")).let { (h, rows) ->
                rows.map { it[h.indexOf("department")] }
            }
        }.groupingBy { it }.eachCount()
        val perSku = deptCents.filterKeys { (skuCount[it] ?: 0) > 100 }.mapValues { (k, v) -> v / skuCount.getValue(k) }
        check(
            "one slow category chain-wide: '${gen.story.slowDepartment}' has the lowest sales per SKU carried",
            perSku.minByOrNull { it.value }?.key == gen.story.slowDepartment,
            perSku.mapValues { "%.0f".format(it.value / 100) }.toString(),
        )
        val snowJan = dept.filter { it[1] == "Snow & Ski" && it[2].startsWith("2026-01") }.sumOf { HistoryReport.cents(it[5]) }
        val snowJul = dept.filter { it[1] == "Snow & Ski" && it[2].startsWith("2026-07") }.sumOf { HistoryReport.cents(it[5]) }
        check("snow sells in winter, not in July (Jan > 2.5 × Jul)", snowJan > 2.5 * snowJul, "Jan $snowJan vs Jul $snowJul cents")

        // promo weeks lift visits against the weeks either side
        gen.story.promos.forEach { p ->
            val wk = visitsPerDay(stores, p.start..p.end)
            val around =
                (visitsPerDay(stores, p.start.minusDays(7)..p.start.minusDays(1)) + visitsPerDay(stores, p.end.plusDays(8)..p.end.plusDays(14))) / 2
            check("promo '${p.name}' lifts chain visits ≥ 20 % over the weeks around it", wk / around >= 1.20, pct(wk / around))
        }

        // the French August dip; the US carries no such dip
        val fr = stores.filter { it.startsWith("dec-fr-") }
        val frDip = visitsPerDay(fr, d(8, 1)..d(8, 31)) / visitsPerDay(fr, d(7, 1)..d(7, 31))
        val usAug = visitsPerDay(us, d(8, 1)..d(8, 31)) / visitsPerDay(us, d(7, 1)..d(7, 31))
        check(
            "the French five dip in August (≥ 15 % below July); the US does not",
            frDip <= 0.85 && usAug > 0.90,
            "FR ${pct(frDip)}, US ${pct(usAug)}",
        )

        // January trough → July peak, chain-wide
        val jan = visitsPerDay(stores, d(1, 1)..d(1, 31))
        val jul = visitsPerDay(stores, d(7, 1)..d(7, 31))
        check("a January trough and a July peak (Jul ≥ 1.3 × Jan)", jul >= 1.3 * jan, "Jan %.0f/day, Jul %.0f/day".format(jan, jul))

        // Chuseok — the pre-holiday surge in Seoul, the day itself closed
        val pre = visitsPerDay(listOf("dec-kr-seoul"), d(9, 19)..d(9, 23))
        // baseline: the same Sat..Wed run three weeks earlier — the week right before holds the Sep 7–13 promo
        val before = visitsPerDay(listOf("dec-kr-seoul"), d(8, 29)..d(9, 2))
        check("Seoul's pre-Chuseok surge (Sep 19–23 ≥ 10 % over Aug 29–Sep 2, same weekdays, no promo)", pre >= 1.10 * before, pct(pre / before))
        check("Chuseok day (Sep 25) is closed in Korea", days.keys.none { it.first.startsWith("dec-kr-") && it.second == d(9, 25) }, "")

        // compliance with its story
        val comp = HistoryCsv.read(dir.resolve("compliance_weekly.csv")).second
        val mean = comp.groupBy { it[0] }.mapValues { (_, r) -> r.map { it[2].toDouble() }.average() }
        check("Marseille's compliance averages 70–80 %", mean["dec-fr-marseille"]!! in 70.0..80.0, "%.1f".format(mean["dec-fr-marseille"]))
        check(
            "every other store averages 85–95 %",
            mean.filterKeys { it != "dec-fr-marseille" }.values.all { it in 85.0..95.0 },
            mean.mapValues { "%.1f".format(it.value) }.toString(),
        )
        val after = comp.filter { gen.story.weekAfterPromo(LocalDate.parse(it[1])) }.map { it[2].toDouble() }.average()
        val other = comp.filter { !gen.story.weekAfterPromo(LocalDate.parse(it[1])) }.map { it[2].toDouble() }.average()
        check("compliance dips the week after each promo (≥ 4 points)", other - after >= 4.0, "after %.1f vs other %.1f".format(after, other))

        check("the history starts at the beginning of January", days.keys.minOf { it.second } <= d(1, 2), "first ${days.keys.minOf { it.second }}")
        return out
    }
}
