package com.m8trx.twin.history

import java.math.BigDecimal
import java.nio.file.Path
import java.util.Locale

/** Per-store totals read back FROM THE FILES (not from memory) — so the table is what mig 259 will load. */
object HistoryReport {

    data class StoreTotals(
        val slug: String,
        val days: Int,
        val visits: Long,
        val transactions: Long,
        val units: Long,
        val cents: Long,
        val complianceMean: Double?,
        val expectedVisits: Double,
        val expectedTransactions: Double,
        val plannedSalesCents: Double,
        /** the model's units and revenue for the expected transactions: × `units_per_txn`, × `atv_usd` */
        val expectedUnits: Double,
        val expectedCents: Double,
    ) {
        val conversion: Double get() = if (visits == 0L) 0.0 else transactions.toDouble() / visits
        val atv: Double get() = if (transactions == 0L) 0.0 else cents / 100.0 / transactions
        fun pct(obs: Double, exp: Double) = if (exp == 0.0) 0.0 else (obs - exp) / exp * 100.0
    }

    fun cents(s: String): Long = BigDecimal(s).movePointRight(2).longValueExact()

    fun totals(gen: HistoryGenerator, dir: Path): List<StoreTotals> {
        val (_, commerce) = HistoryCsv.read(dir.resolve("commerce_store_hourly.csv"))
        val (_, traffic) = HistoryCsv.read(dir.resolve("traffic_store_hourly.csv"))
        val (_, compliance) = HistoryCsv.read(dir.resolve("compliance_weekly.csv"))
        val hoursPerDay = gen.model.store.operatingHours.openHours
        return gen.stores.map { s ->
            val c = commerce.filter { it[0] == s.slug }
            val t = traffic.filter { it[0] == s.slug }
            val comp = compliance.filter { it[0] == s.slug }.map { it[2].toDouble() }
            val dates = gen.days(s)
            val plans = dates.map { gen.plan(s, it) }
            val expV = plans.sumOf { gen.expectedVisitors(it) }
            val expT = plans.sumOf { gen.expectedVisitors(it) * it.buyerShare }
            val planT = dates.sumOf { gen.story.expectedVisitors(s, it, withStory = false) * gen.buyerShare(s, it, withStory = false) }
            StoreTotals(
                slug = s.slug,
                days = c.size / hoursPerDay,
                visits = t.sumOf { it[3].toLong() },
                transactions = c.sumOf { it[5].toLong() },
                units = c.sumOf { it[3].toLong() },
                cents = c.sumOf { cents(it[4]) },
                complianceMean = comp.takeIf { it.isNotEmpty() }?.average(),
                expectedVisits = expV,
                expectedTransactions = expT,
                plannedSalesCents = planT * gen.model.basket.atvUsd.value * 100.0,
                expectedUnits = expT * gen.model.basket.unitsPerTxn.value,
                expectedCents = expT * gen.model.basket.atvUsd.value * 100.0,
            )
        }
    }

    fun totalsTable(gen: HistoryGenerator, dir: Path): String = render(totals(gen, dir))

    fun render(rows: List<StoreTotals>): String = buildString {
        fun n(v: Long) = "%,d".format(Locale.ROOT, v)
        fun d(o: Double, e: Double) = "%+.1f%%".format(Locale.ROOT, if (e == 0.0) 0.0 else (o - e) / e * 100.0)
        appendLine(
            "| store | days | visits | transactions | conversion | units | sales USD | ATV | compliance mean " +
                "| Δ visits | Δ txn | Δ units | Δ sales | sales vs plan |",
        )
        appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
        rows.forEach { r ->
            appendLine(
                "| ${r.slug} | ${r.days} | ${n(r.visits)} | ${n(r.transactions)} | ${"%.3f".format(Locale.ROOT, r.conversion)} | ${n(r.units)} | " +
                    "${"%,.0f".format(Locale.ROOT, r.cents / 100.0)} | ${"%.2f".format(Locale.ROOT, r.atv)} | " +
                    "${r.complianceMean?.let { "%.1f".format(Locale.ROOT, it) } ?: "—"} | " +
                    "${d(r.visits.toDouble(), r.expectedVisits)} | ${d(r.transactions.toDouble(), r.expectedTransactions)} | " +
                    "${d(
                        r.units.toDouble(),
                        r.expectedUnits,
                    )} | ${d(r.cents.toDouble(), r.expectedCents)} | ${d(r.cents.toDouble(), r.plannedSalesCents)} |",
            )
        }
        val v = rows.sumOf { it.visits }
        val t = rows.sumOf { it.transactions }
        appendLine(
            "| **chain** | ${rows.sumOf { it.days }} | ${n(v)} | ${n(t)} | ${"%.3f".format(Locale.ROOT, if (v == 0L) 0.0 else t.toDouble() / v)} | " +
                "${n(rows.sumOf { it.units })} | ${"%,.0f".format(Locale.ROOT, rows.sumOf { it.cents } / 100.0)} | | | | | | | |",
        )
        append("Δ = generated vs the operating model's expectation for the generated days (model volumes × seasonality × story × weather; ")
        append("units at `units_per_txn` 2.2, sales at `atv_usd` \$58). Sales vs plan = vs the KPI plan (the model without the store's story).")
    }
}
