package com.m8trx.twin.ops

import com.fasterxml.jackson.databind.JsonNode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The small polygon toolbox the `fixmoves` step needs to keep a move inside the room and off its neighbors. Twin's own
 * code (planar millimetres, SRID 0 — the same frame as the zone table), not `com.m8trx.geometry`.
 *
 * A ring is the GeoJSON ring as the platform returns it: `[[x, y, z], …]` closed (first point repeated) or open; only x and
 * y are read. Overlap means a POSITIVE area of intersection (above [AREA_EPS_MM2], so float noise never counts); two fixtures
 * that merely share an edge do not overlap. Convex clip shapes get the exact area (Sutherland–Hodgman); a non-convex pair
 * falls back to its bounding boxes, which can only over-report an overlap (a skipped move, never a bad one).
 */
object PolyGeom {
    const val AREA_EPS_MM2 = 100.0
    private const val EPS = 1e-6

    data class P(val x: Double, val y: Double)

    fun ring(coords: List<List<Double>>): List<P> = open(coords.map { P(it[0], it[1]) })

    /** GeoJSON `Polygon` node → its outer ring; null for anything else. */
    fun outerRing(geometry: JsonNode): List<P>? {
        if (geometry.path("type").asText() != "Polygon") return null
        val outer = geometry.path("coordinates").path(0)
        if (!outer.isArray || outer.size() < 3) return null
        return open(outer.map { P(it[0].asDouble(), it[1].asDouble()) })
    }

    /** A circle fixture (Point geometry + radius) as a 32-gon, for use as an obstacle. */
    fun circle(cx: Double, cy: Double, rx: Double, ry: Double, n: Int = 32): List<P> =
        List(n) { i -> P(cx + rx * cos(2 * Math.PI * i / n), cy + ry * sin(2 * Math.PI * i / n)) }

    private fun open(pts: List<P>): List<P> =
        if (pts.size > 1 && abs(pts.first().x - pts.last().x) < EPS && abs(pts.first().y - pts.last().y) < EPS) pts.dropLast(1) else pts

    fun translate(r: List<P>, dx: Double, dy: Double): List<P> = r.map { P(it.x + dx, it.y + dy) }

    private fun area2(r: List<P>): Double {
        var s = 0.0
        for (i in r.indices) {
            val a = r[i]
            val b = r[(i + 1) % r.size]
            s += a.x * b.y - b.x * a.y
        }
        return s
    }

    fun area(r: List<P>): Double = abs(area2(r)) / 2.0

    private fun ccw(r: List<P>): List<P> = if (area2(r) < 0) r.reversed() else r

    fun isConvex(r: List<P>): Boolean {
        var sign = 0
        for (i in r.indices) {
            val a = r[i]
            val b = r[(i + 1) % r.size]
            val c = r[(i + 2) % r.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (cross > EPS) {
                1
            } else if (cross < -EPS) {
                -1
            } else {
                0
            }
            if (s != 0) {
                if (sign != 0 && s != sign) return false
                sign = s
            }
        }
        return true
    }

    private fun bboxOverlapMm2(a: List<P>, b: List<P>): Double {
        val w = min(a.maxOf { it.x }, b.maxOf { it.x }) - max(a.minOf { it.x }, b.minOf { it.x })
        val h = min(a.maxOf { it.y }, b.maxOf { it.y }) - max(a.minOf { it.y }, b.minOf { it.y })
        return if (w <= 0 || h <= 0) 0.0 else w * h
    }

    /** Area (mm²) of the intersection of [subject] with the convex [clip]. */
    private fun clip(subject: List<P>, clip: List<P>): Double {
        val c = ccw(clip)
        var out = ccw(subject)
        for (i in c.indices) {
            val a = c[i]
            val b = c[(i + 1) % c.size]
            val input = out
            out = ArrayList()
            if (input.isEmpty()) return 0.0
            fun side(p: P) = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            fun cross(p: P, q: P): P {
                val sp = side(p)
                val sq = side(q)
                val t = sp / (sp - sq)
                return P(p.x + t * (q.x - p.x), p.y + t * (q.y - p.y))
            }
            for (j in input.indices) {
                val cur = input[j]
                val prev = input[(j + input.size - 1) % input.size]
                val curIn = side(cur) >= -EPS
                val prevIn = side(prev) >= -EPS
                if (curIn) {
                    if (!prevIn) out.add(cross(prev, cur))
                    out.add(cur)
                } else if (prevIn) {
                    out.add(cross(prev, cur))
                }
            }
        }
        return if (out.size < 3) 0.0 else area(out)
    }

    /** Intersection area in mm² (exact when one side is convex, else the bounding-box overlap — never an under-report). */
    fun overlapArea(a: List<P>, b: List<P>): Double {
        if (a.size < 3 || b.size < 3) return 0.0
        if (bboxOverlapMm2(a, b) <= 0.0) return 0.0
        return when {
            isConvex(b) -> clip(a, b)
            isConvex(a) -> clip(b, a)
            else -> bboxOverlapMm2(a, b)
        }
    }

    fun overlaps(a: List<P>, b: List<P>): Boolean = overlapArea(a, b) > AREA_EPS_MM2

    /** True when [inner] lies inside [outer]: no part of it is outside (its area minus the part inside is ~0). */
    fun within(inner: List<P>, outer: List<P>): Boolean {
        if (inner.size < 3 || outer.size < 3) return false
        val a = area(inner)
        return when {
            isConvex(outer) -> a - clip(inner, outer) <= AREA_EPS_MM2
            else -> inner.all { pointIn(it, outer) } && !edgesCross(inner, outer)
        }
    }

    private fun pointIn(p: P, r: List<P>): Boolean {
        var inside = false
        var j = r.size - 1
        for (i in r.indices) {
            val a = r[i]
            val b = r[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        if (inside) return true
        // on the boundary counts as inside
        for (i in r.indices) {
            val a = r[i]
            val b = r[(i + 1) % r.size]
            val cr = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            val len = Math.hypot(b.x - a.x, b.y - a.y)
            if (len > 0 && abs(cr) / len < 1e-3 && p.x >= min(a.x, b.x) - 1e-3 && p.x <= max(a.x, b.x) + 1e-3 &&
                p.y >= min(a.y, b.y) - 1e-3 && p.y <= max(a.y, b.y) + 1e-3
            ) {
                return true
            }
        }
        return false
    }

    private fun edgesCross(a: List<P>, b: List<P>): Boolean {
        for (i in a.indices) {
            for (j in b.indices) {
                val p1 = a[i]
                val p2 = a[(i + 1) % a.size]
                val q1 = b[j]
                val q2 = b[(j + 1) % b.size]
                fun o(p: P, q: P, r: P) = (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)
                val d1 = o(q1, q2, p1)
                val d2 = o(q1, q2, p2)
                val d3 = o(p1, p2, q1)
                val d4 = o(p1, p2, q2)
                if (((d1 > EPS && d2 < -EPS) || (d1 < -EPS && d2 > EPS)) && ((d3 > EPS && d4 < -EPS) || (d3 < -EPS && d4 > EPS))) return true
            }
        }
        return false
    }
}
