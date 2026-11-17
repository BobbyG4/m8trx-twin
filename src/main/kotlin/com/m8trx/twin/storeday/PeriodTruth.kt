package com.m8trx.twin.storeday

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

/**
 * Slice 2c: the twin's own compliance truth, per planogram period and per fixture x EAN: what the plan required against what was
 * on the fixture, at each weekly count (when the fixture was read) and at the period's end (the last open day's close).
 * `gap = required - on_hand`: positive is short, negative is over. The platform's compliance, run on the same floor, should agree.
 */
object PeriodTruth {
    class Row(val period: LocalDate, val point: String, val atMs: Long, val fixture: String, val ean: String, val required: Int, val onHand: Int) {
        val gap: Int get() = required - onHand
    }

    /** One row per planned slot of each counted fixture, plus any unit of another EAN the read found there. */
    fun countRows(p: PeriodPlan, c: CountRecord): List<Row> = c.pulls.flatMap { pull ->
        val planned = p.required.keys.filter { it.fixture == pull.fixture }.map { it.ean }
        (planned + pull.reads.keys).toSortedSet().map { ean ->
            Row(p.start, "count", pull.atMs, pull.fixture, ean, p.required[Slot(pull.fixture, ean)] ?: 0, pull.reads[ean] ?: 0)
        }
    }

    /** The period's end: every slot of the plan and every stray the floor held at the last open day's close. */
    fun endRows(p: PeriodPlan, atMs: Long, closeOnHand: Map<Slot, Int>): List<Row> = (p.required.keys + closeOnHand.keys).toSortedSet().map { s ->
        Row(
            p.start,
            "period_end",
            atMs,
            s.fixture,
            s.ean,
            p.required[s] ?: 0,
            closeOnHand[s] ?: 0,
        )
    }

    fun csv(rows: List<Row>): String = buildString {
        append("period_start,point,at_utc,fixture,ean,required_qty,on_hand,gap\n")
        rows.forEach {
            append("${it.period},${it.point},${Instant.ofEpochMilli(it.atMs)},${it.fixture},${it.ean},${it.required},${it.onHand},${it.gap}\n")
        }
    }

    fun write(dir: Path, rows: List<Row>) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("compliance_truth.csv"), csv(rows))
    }

    /** Lines (required > 0) with at least their requirement on the fixture at count time. */
    fun compliant(r: Row) = r.required > 0 && r.onHand >= r.required
}
