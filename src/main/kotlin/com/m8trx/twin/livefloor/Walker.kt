package com.m8trx.twin.livefloor

import com.m8trx.twin.behavior.Visit
import com.m8trx.twin.layer1.Pt
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** What a walker is doing at a moment: moving along a clear aisle, stepping in to / out of a stand, or standing. */
enum class St { WALK, APPROACH, DEPART, BROWSE, HELP, FITTING, IDLE }

/**
 * One stretch of a walker's day. A moving segment covers arc length [s0]..[s1] of [poly] at constant speed over [t0]..[t1] (epoch ms); a
 * standing one holds ([x], [y]) and faces ([fx], [fy]) when it has a face (NaN otherwise).
 */
class Seg(
    val t0: Long,
    val t1: Long,
    val state: St,
    val poly: Polyline?,
    val s0: Double,
    val s1: Double,
    val x: Double,
    val y: Double,
    val fx: Double,
    val fy: Double,
    val fixture: String?,
) {
    /** mm per ms, which is numerically metres per second. */
    val speed: Double get() = if (poly == null || t1 <= t0) 0.0 else (s1 - s0) / (t1 - t0)
}

/** A stop the walker made: where, and when they got there and left. */
class StopRec(val stand: Stand, val arrMs: Long, val depMs: Long, val visit: Visit?, val fixture: String)

/** One sample of the 5 Hz position feed: epoch ms, position in mm, unit heading, and what the walker was doing. */
class FloorSample(
    val tMs: Long,
    val x: Double,
    val y: Double,
    val hx: Double,
    val hy: Double,
    val state: St,
    val fixture: String?,
    val speed: Double,
)

/**
 * A person on the floor from [tEnterMs] to [tExitMs]. Customers are the twin's own store-day shoppers ([objectId] is the REST trackingId);
 * staff carry `hasTag=true` and a `live-staff-…` id. [isMale] is null when the gender is unknown (the wire field is then omitted).
 */
class Walker(
    val objectId: String,
    val staff: Boolean,
    val isMale: Boolean?,
    val speedMps: Double,
    val tEnterMs: Long,
    val tExitMs: Long,
    val segs: List<Seg>,
    val stops: List<StopRec>,
    val shopperRef: String?,
) {
    /** 5 Hz samples from entry (a sample every [STEP_MS], the last exactly at exit), heading smoothed at [MAX_TURN_RAD_S]. */
    fun samples(): Sequence<FloorSample> = sequence {
        var k = 0
        var idx = 0
        var hx = Double.NaN
        var hy = 0.0
        var t = tEnterMs
        var lastT = tEnterMs - STEP_MS
        while (true) {
            val tt = if (t > tExitMs) tExitMs else t
            while (idx < segs.size - 1 && segs[idx].t1 <= tt) idx++
            val sg = segs[idx]
            val (p, tgt) = place(sg, tt)
            val dt = (tt - lastT) / 1000.0
            if (tgt.x.isNaN()) {
                if (hx.isNaN()) {
                    hx = 0.0
                    hy = 1.0
                }
            } else if (hx.isNaN()) {
                hx = tgt.x
                hy = tgt.y
            } else {
                val cur = atan2(hy, hx)
                var diff = atan2(tgt.y, tgt.x) - cur
                while (diff > PI) diff -= 2 * PI
                while (diff < -PI) diff += 2 * PI
                val step = MAX_TURN_RAD_S * dt
                val a = if (kotlin.math.abs(diff) <= step) cur + diff else cur + step * kotlin.math.sign(diff)
                hx = cos(a)
                hy = sin(a)
            }
            yield(FloorSample(tt, p.x, p.y, hx, hy, sg.state, sg.fixture, sg.speed))
            if (tt >= tExitMs) break
            lastT = tt
            k++
            t = tEnterMs + k * STEP_MS
        }
    }

    /** Where the walker is at [t] (clamped to the stay), found by binary search over the segments. */
    fun posAt(t: Long): Pt {
        var lo = 0
        var hi = segs.size - 1
        val tt = t.coerceIn(tEnterMs, tExitMs)
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (segs[mid].t1 <= tt) lo = mid + 1 else hi = mid
        }
        return place(segs[lo], tt).first
    }

    private fun place(sg: Seg, t: Long): Pair<Pt, Pt> {
        val poly = sg.poly
        if (poly == null) {
            val face = if (sg.fx.isNaN()) Pt(Double.NaN, Double.NaN) else Pt(sg.fx, sg.fy)
            return Pt(sg.x, sg.y) to (if (face.x.isNaN()) Pt(Double.NaN, 0.0) else face)
        }
        val f = if (sg.t1 <= sg.t0) 1.0 else ((t - sg.t0).toDouble() / (sg.t1 - sg.t0)).coerceIn(0.0, 1.0)
        val s = sg.s0 + (sg.s1 - sg.s0) * f
        val p = poly.at(s)
        val q = poly.at(minOf(poly.length, s + LOOKAHEAD_MM))
        var dx = q.x - p.x
        var dy = q.y - p.y
        var d = hypot(dx, dy)
        if (d < 1.0) {
            val b = poly.at(maxOf(0.0, s - LOOKAHEAD_MM))
            dx = p.x - b.x
            dy = p.y - b.y
            d = hypot(dx, dy)
        }
        return p to (if (d < 1.0) Pt(Double.NaN, 0.0) else Pt(dx / d, dy / d))
    }

    companion object {
        const val STEP_MS = 200L
        const val MAX_TURN_RAD_S = 3.5
        const val LOOKAHEAD_MM = 400.0
    }
}
