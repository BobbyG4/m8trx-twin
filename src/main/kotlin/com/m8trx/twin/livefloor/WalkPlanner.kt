package com.m8trx.twin.livefloor

import com.m8trx.twin.behavior.ShopperAgent
import com.m8trx.twin.behavior.ShopperRecord
import com.m8trx.twin.behavior.Visit
import com.m8trx.twin.layer1.Pt
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** A route plus where its stepping-in and stepping-out ends are, as arc lengths in mm. */
class Leg(val poly: Polyline, val depEnd: Double, val appStart: Double)

/** What the planner did that a reader of the numbers should know about. */
class WalkStats {
    var stops = 0
    var droppedUnreachable = 0
    var droppedNoTime = 0
    var visits = 0
    var fallbacks = 0
    var relaxedStops = 0
    val enclosed = java.util.TreeMap<String, Int>()
    var purchasers = 0
    var purchasersAtCheckout = 0
    var detours = 0
    var idleFills = 0
    val lateMs = ArrayList<Long>()
    val earlyMs = ArrayList<Long>()
    val tailShortMs = ArrayList<Long>()
    var helps = 0
    var helpsSkipped = 0
}

/** Deterministic seed from strings (FNV-1a over the UTF-8 bytes), identical on every JVM and machine. */
fun seedOf(vararg parts: String): Long {
    var h = -0x340d631b7bdddcdbL
    for (p in parts) {
        for (b in p.toByteArray()) h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
        h = (h xor 0x1f) * 0x100000001b3L
    }
    return h
}

/**
 * The day's walking plan for one store: each store-day shopper (from the twin's own store-day simulation) as a [Walker] along the aisle graph,
 * plus the floor staff. Everything is a pure function of (slug, date, the shoppers), so the same day replays to the same positions.
 */
class WalkPlanner(private val graph: AisleGraph, private val slug: String, private val date: LocalDate) {
    companion object {
        const val V_MIN = 0.75
        const val V_MAX = 1.10
        const val STAFF_V = 0.95
        const val LINGER_MAX_MS = 5_000L
        const val DETOUR_MIN_MS = 3_000L
        const val HELP_OFFSET_MM = 800.0
        const val HELP_RATE = 0.14
        const val STEP_IN_MM = 200.0
        const val JUNCTION_R_MM = 100.0
    }

    private val floor = graph.floor
    val stats = WalkStats()
    private val occ = HashMap<String, MutableList<DoubleArray>>()
    private val standList: List<Stand> = graph.stands.values.flatten()

    /** The sales-floor frame has no fitting-room door; the non-retail strip is the nearest place, so the fitting-room visit stands there. */
    private val fitting: Stand? = floor.strip?.let { r ->
        val n = graph.nearestNode((r.x0 + r.x1) / 2, (r.y0 + r.y1) / 2)
        if (n < 0) null else graph.nodePoint(n).let { Stand("fitting_room", it.x, it.y, it.x, it.y, -1.0, 0.0) }
    }

    private fun gate(rng: Random): Pt {
        val x = floor.gateX0 + 400 + rng.nextDouble() * max(1.0, floor.gateX1 - floor.gateX0 - 800)
        return graph.nodePoint(graph.nearestNode(x, floor.gateY + 100))
    }

    // ── legs ──────────────────────────────────────────────────────────────────────────────────────────────

    private fun end(p: Pt?, s: Stand?): Pt = if (s != null) (if (s.fixture == "fitting_room") Pt(s.x, s.y) else Pt(s.ax, s.ay)) else p!!

    /** From a stand or a point to a stand or a point, optionally through [via]; null when the aisles do not connect them. */
    fun leg(fromStand: Stand?, fromPt: Pt?, toStand: Stand?, toPt: Pt?, via: Pt? = null): Leg? {
        val a = end(fromPt, fromStand)
        val b = end(toPt, toStand)
        val first = graph.route(a.x, a.y, via?.x ?: b.x, via?.y ?: b.y) ?: return null
        val second = via?.let { graph.route(it.x, it.y, b.x, b.y) ?: return null }
        if (second != null) {
            // the two routes must not double back on each other at the waypoint
            val e1 = first.at(first.length)
            val b1 = first.at(max(0.0, first.length - 400.0))
            val s2 = second.at(0.0)
            val e2 = second.at(min(second.length, 400.0))
            val n1 = hypot(e1.x - b1.x, e1.y - b1.y)
            val n2 = hypot(e2.x - s2.x, e2.y - s2.y)
            if (n1 > 1.0 && n2 > 1.0 &&
                ((e1.x - b1.x) * (e2.x - s2.x) + (e1.y - b1.y) * (e2.y - s2.y)) / (n1 * n2) < Math.cos(Math.toRadians(110.0))
            ) {
                return null
            }
        }
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val junctions = ArrayList<Double>()
        var depLen = 0.0
        if (fromStand != null && fromStand.fixture != "fitting_room") {
            xs += fromStand.x
            ys += fromStand.y
            depLen = hypot(a.x - fromStand.x, a.y - fromStand.y)
            junctions += depLen
        }
        for (i in first.xs.indices) {
            xs += first.xs[i]
            ys += first.ys[i]
        }
        if (second != null) {
            junctions += first.length + depLen
            for (i in 1 until second.xs.size) {
                xs += second.xs[i]
                ys += second.ys[i]
            }
        }
        var appLen = 0.0
        if (toStand != null && toStand.fixture != "fitting_room") {
            appLen = hypot(b.x - toStand.x, b.y - toStand.y)
            xs += toStand.x
            ys += toStand.y
        }
        val raw = Polyline(xs.toDoubleArray(), ys.toDoubleArray())
        if (raw.length < 1.0) return Leg(Polyline(doubleArrayOf(a.x, b.x + 1.0), doubleArrayOf(a.y, b.y)), 0.0, raw.length + 2.0)
        junctions += raw.length - appLen
        for (r in doubleArrayOf(400.0, 200.0, 100.0, 0.0)) {
            val rounded = if (r == 0.0) raw else roundJunctions(raw, junctions, r)
            val dEnd = if (depLen > 0) depLen + min(r, depLen) else 0.0
            val aStart = if (appLen > 0) rounded.length - appLen - min(r, appLen) else rounded.length
            if (verifyWalk(rounded, dEnd, aStart)) return Leg(rounded, dEnd, aStart)
        }
        return null
    }

    private fun verifyWalk(p: Polyline, from: Double, to: Double): Boolean {
        var s = from
        while (s <= to) {
            val q = p.at(s)
            if (!graph.walkable(q.x, q.y, graph.hardMm - 1.0)) return false
            s += 25.0
        }
        return true
    }

    /** Replace each junction's corner by a quadratic Bezier of radius up to [r] mm (bounded by the neighbouring stretches). */
    private fun roundJunctions(p: Polyline, junctions: List<Double>, r: Double): Polyline {
        val js = junctions.filter { it > 1.0 && it < p.length - 1.0 }.sorted()
        if (js.isEmpty()) return p
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        var s = 0.0
        val pts = ArrayList<Pt>()
        val bounds = listOf(0.0) + js + listOf(p.length)
        for ((k, j) in js.withIndex()) {
            val rj = min(r, min(j - bounds[k], bounds[k + 2] - j) / 2.0)
            val a = p.at(j - rj)
            val c = p.at(j)
            val b = p.at(j + rj)
            while (s < j - rj) {
                pts += p.at(s)
                s += 25.0
            }
            for (i in 0..8) {
                val t = i / 8.0
                val u = 1 - t
                pts += Pt(u * u * a.x + 2 * u * t * c.x + t * t * b.x, u * u * a.y + 2 * u * t * c.y + t * t * b.y)
            }
            s = j + rj
        }
        while (s < p.length) {
            pts += p.at(s)
            s += 25.0
        }
        pts += p.at(p.length)
        pts.forEach {
            xs += it.x
            ys += it.y
        }
        return Polyline(xs.toDoubleArray(), ys.toDoubleArray())
    }

    private fun addLeg(segs: MutableList<Seg>, leg: Leg, tDep: Long, tArr: Long, fixture: String?) {
        if (tArr <= tDep) return
        val total = leg.poly.length
        if (total < 5.0) {
            val p0 = leg.poly.at(0.0)
            segs += Seg(tDep, tArr, St.IDLE, null, 0.0, 0.0, p0.x, p0.y, Double.NaN, Double.NaN, null)
            return
        }
        var t = tDep
        val dEnd = min(leg.depEnd, total)
        val aStart = min(max(leg.appStart, dEnd), total)
        val states = ArrayList<Triple<Double, Double, St>>()
        if (dEnd > 0.0) states += Triple(0.0, dEnd, St.DEPART)
        states += Triple(dEnd, aStart, St.WALK)
        if (aStart < total) states += Triple(aStart, total, St.APPROACH)
        for ((a, b, st) in states) {
            if (b - a < 1e-6) continue
            val t1 = if (b >= total - 1e-6) tArr else tDep + Math.round((tArr - tDep) * (b / total))
            if (t1 > t) segs += Seg(t, t1, st, leg.poly, a, b, 0.0, 0.0, Double.NaN, Double.NaN, fixture)
            t = max(t, t1)
        }
        if (t < tArr && segs.isNotEmpty()) {
            val l = segs.removeAt(segs.size - 1)
            segs += Seg(l.t0, tArr, l.state, l.poly, l.s0, l.s1, 0.0, 0.0, Double.NaN, Double.NaN, l.fixture)
        }
    }

    private fun stand(segs: MutableList<Seg>, st: Stand?, pt: Pt?, t0: Long, t1: Long, state: St) {
        if (t1 <= t0) return
        val p = if (st != null) Pt(st.x, st.y) else pt!!
        segs += Seg(t0, t1, state, null, 0.0, 0.0, p.x, p.y, st?.fx ?: Double.NaN, st?.fy ?: Double.NaN, st?.fixture)
    }

    // ── customers ─────────────────────────────────────────────────────────────────────────────────────────

    private class Node(val stand: Stand?, val pt: Pt?, val s: Long, val e: Long, val minStay: Long, val visit: Visit?)

    private fun standFor(code: String, prev: Pt, s: Long, e: Long, rng: Random): Stand? {
        if (code == "fitting_room") return fitting
        // a visit stands at ITS OWN fixture: no stand there (a fixture enclosed on every side) means no standing phase for that stop
        val list = graph.stands[code].orEmpty()
        if (list.isEmpty()) {
            stats.enclosed.merge(code, 1, Int::plus)
            return null
        }
        if (list[0].relaxed) stats.relaxedStops++
        val taken = occ[list.first().fixture].orEmpty()
        val free = list.filter { c -> taken.none { o -> o[3] > s && o[2] < e && hypot(o[0] - c.x, o[1] - c.y) < 700.0 } }
        val pool = free.ifEmpty { list }
        return pool.minByOrNull { hypot(it.x - prev.x, it.y - prev.y) + rng.nextDouble() * 1_500.0 }
    }

    /**
     * One REST visit as a walker. Never null: a visit the full plan cannot walk (an unreachable leg, no time) is drawn by [fallback] and counted in
     * [WalkStats.fallbacks], so every REST session of the day is on the map.
     */
    fun customer(rec: ShopperRecord, trackingId: String, isMale: Boolean?): Walker =
        planned(rec, trackingId, isMale) ?: fallback(rec, trackingId, isMale)

    /** Enter, walk to the first stop that can be reached and left again inside the window, stay, leave at the exit time; else wait at the gate. */
    private fun fallback(rec: ShopperRecord, trackingId: String, isMale: Boolean?): Walker {
        stats.fallbacks++
        val rng = Random(seedOf("fallback", slug, date.toString(), trackingId))
        val v0 = V_MIN + (V_MAX - V_MIN) * rng.nextDouble()
        val gin = gate(rng)
        val entry = rec.entryMs
        val exit = rec.exitMs
        val segs = ArrayList<Seg>()
        val stops = ArrayList<StopRec>()
        for (v in rec.visits.sortedBy { it.startMs }) {
            val st = (if (v.fixture == "fitting_room") fitting else graph.stands[v.fixture]?.firstOrNull()) ?: continue
            val a = leg(null, gin, st, null) ?: continue
            val b = leg(st, null, null, gin) ?: continue
            val v1 = listOf(v0, V_MAX).firstOrNull { sp ->
                entry + ceil(a.poly.length / sp).toLong() + 1_000L + ceil(b.poly.length / sp).toLong() <= exit
            } ?: continue
            val arr = entry + ceil(a.poly.length / v1).toLong()
            val dep = exit - ceil(b.poly.length / v1).toLong()
            addLeg(segs, a, entry, arr, null)
            stand(segs, st, null, arr, dep, if (st.fixture == "fitting_room") St.FITTING else St.BROWSE)
            addLeg(segs, b, dep, exit, null)
            stops += StopRec(st, arr, dep, v, st.fixture)
            return Walker(trackingId, false, isMale, v1, entry, exit, segs, stops, rec.id)
        }
        stand(segs, null, gin, entry, max(exit, entry + 1), St.IDLE)
        return Walker(trackingId, false, isMale, v0, entry, max(exit, entry + 1), segs, stops, rec.id)
    }

    private fun planned(rec: ShopperRecord, trackingId: String, isMale: Boolean?): Walker? {
        val rng = Random(seedOf("walk", slug, date.toString(), trackingId))
        val v0 = V_MIN + (V_MAX - V_MIN) * rng.nextDouble()
        val gin = gate(rng)
        val gout = if (rec.visits.isEmpty()) gin else gate(rng)
        val entry = rec.entryMs
        val exit = rec.exitMs
        val nodes = ArrayList<Node>()
        var prev = gin
        for (v in rec.visits.sortedBy { it.startMs }) {
            val full = v.startMs + ShopperAgent.APPROACH_MS + v.dwellMs + ShopperAgent.DISENGAGE_MS
            val e = if (v.fixture == "fitting_room") v.startMs + v.dwellMs else full
            stats.visits++
            val st = standFor(v.fixture, prev, v.startMs, e, rng) ?: continue
            nodes += Node(st, null, v.startMs, e, max(3_000L, (e - v.startMs) * 6 / 10).coerceAtMost(e - v.startMs), v)
            prev = Pt(st.x, st.y)
        }
        stats.stops += nodes.size
        var list: MutableList<Node> = nodes
        for (attempt in 0..list.size + 2) {
            val all = listOf(Node(null, gin, entry, entry, 0, null)) + list + Node(null, gout, exit, exit, 0, null)
            val legs = ArrayList<Leg>()
            var bad = -1
            for (i in 0 until all.size - 1) {
                val l = leg(all[i].stand, all[i].pt, all[i + 1].stand, all[i + 1].pt)
                if (l == null) {
                    bad = if (i + 1 < all.size - 1) i + 1 else i
                    break
                }
                legs += l
            }
            if (bad >= 1 && bad <= list.size) {
                list = list.toMutableList().also { it.removeAt(bad - 1) }
                stats.droppedUnreachable++
                continue
            }
            if (bad != -1) return null
            val plan = solve(all, legs, v0, rng)
            if (plan.failedAt >= 1 && plan.failedAt <= list.size) {
                list = list.toMutableList().also { it.removeAt(plan.failedAt - 1) }
                stats.droppedNoTime++
                continue
            }
            if (plan.failedAt != -1) return null
            for (lt in plan.late) stats.lateMs += lt
            for (er in plan.early) stats.earlyMs += er
            list.forEach { n ->
                occ.getOrPut(n.stand!!.fixture) { ArrayList() } += doubleArrayOf(n.stand.x, n.stand.y, n.s.toDouble(), n.e.toDouble())
            }
            if (rec.bought) {
                stats.purchasers++
                if (list.any { floor.category[it.stand!!.fixture] == "checkout" }) stats.purchasersAtCheckout++
            }
            return assemble(trackingId, rec, all, plan, isMale, v0)
        }
        return null
    }

    private class Plan(
        val legs: List<Leg>,
        val tDep: LongArray,
        val tArr: LongArray,
        val stayEnd: LongArray,
        val failedAt: Int,
        val late: List<Long>,
        val early: List<Long>,
    )

    private fun solve(all: List<Node>, legs0: List<Leg>, v0: Double, rng: Random): Plan {
        val k = all.size - 2 // stops
        val legs = legs0.toMutableList()
        val latArr = LongArray(all.size)
        val latDep = LongArray(all.size)
        latArr[all.size - 1] = all.last().s
        for (i in all.size - 2 downTo 0) {
            latDep[i] = latArr[i + 1] - ceil(legs[i].poly.length / V_MAX).toLong()
            if (i >= 1) latArr[i] = latDep[i] - all[i].minStay
        }
        if (latDep[0] < all[0].s) return Plan(legs, LongArray(0), LongArray(0), LongArray(0), if (k >= 1) 1 else -1, emptyList(), emptyList())
        val tDep = LongArray(all.size - 1)
        val tArr = LongArray(all.size)
        val stayEnd = LongArray(all.size)
        val late = ArrayList<Long>()
        val early = ArrayList<Long>()
        var depPrev = all[0].s
        for (i in 0 until all.size - 1) {
            val target = min(all[i + 1].s, latArr[i + 1])
            var leg = legs[i]
            var len = leg.poly.length
            var slack = (target - depPrev) - len / v0
            var v: Double
            if (slack >= 0) {
                if (i >= 1) {
                    val linger = min(slack.toLong(), min(LINGER_MAX_MS, latDep[i] - depPrev)).coerceAtLeast(0L)
                    depPrev += linger
                    slack -= linger
                }
                v = v0
                if (slack > DETOUR_MIN_MS) {
                    val d = detour(all[i], all[i + 1], len + slack * v0, target - depPrev, rng)
                    if (d != null) {
                        leg = d
                        len = d.poly.length
                        v = (len / (target - depPrev)).coerceIn(V_MIN, V_MAX)
                        stats.detours++
                    }
                }
            } else {
                v = if (target <= depPrev) V_MAX else min(V_MAX, len / (target - depPrev))
            }
            legs[i] = leg
            tDep[i] = depPrev
            tArr[i + 1] = depPrev + ceil(len / v).toLong()
            if (i + 1 <= k) {
                val n = all[i + 1]
                if (tArr[i + 1] > n.s) late += tArr[i + 1] - n.s
                if (tArr[i + 1] < n.s) early += n.s - tArr[i + 1]
                if (tArr[i + 1] + n.minStay > latDep[i + 1]) return Plan(legs, tDep, tArr, stayEnd, i + 1, late, early)
                // leave when the next stop can be reached on time at the walker's own speed, but never cut the stay below its minimum
                val nextTarget = min(all[i + 2].s, latArr[i + 2])
                val onTime = nextTarget - ceil(legs[i + 1].poly.length / v0).toLong()
                val dep = (if (onTime >= n.e) n.e else onTime).coerceIn(tArr[i + 1] + n.minStay, latDep[i + 1])
                stayEnd[i + 1] = dep
                depPrev = dep
            }
        }
        return Plan(legs, tDep, tArr, stayEnd, -1, late, early)
    }

    /** A leg A->W->B whose length is near [wantMm] and whose speed over [windowMs] is in range, or null. */
    private fun detour(from: Node, to: Node, wantMm: Double, windowMs: Long, rng: Random): Leg? {
        val a = from.stand?.let { Pt(it.ax, it.ay) } ?: from.pt!!
        val b = to.stand?.let { Pt(it.ax, it.ay) } ?: to.pt!!
        var best: Leg? = null
        var bestErr = Double.MAX_VALUE
        repeat(10) {
            val w = graph.nodePoint(graph.randomNode(rng))
            val est = hypot(w.x - a.x, w.y - a.y) + hypot(b.x - w.x, b.y - w.y)
            if (est < wantMm * 0.55 || est > wantMm * 1.25) return@repeat
            val ux = w.x - a.x
            val uy = w.y - a.y
            val vx = b.x - w.x
            val vy = b.y - w.y
            val nu = hypot(ux, uy)
            val nv = hypot(vx, vy)
            if (nu > 1 && nv > 1 && acos(((ux * vx + uy * vy) / (nu * nv)).coerceIn(-1.0, 1.0)) > Math.toRadians(150.0)) return@repeat
            val l = leg(from.stand, from.pt, to.stand, to.pt, via = w) ?: return@repeat
            val sp = l.poly.length / windowMs
            if (sp < V_MIN || sp > V_MAX) return@repeat
            val err = abs(l.poly.length - wantMm)
            if (err < bestErr) {
                bestErr = err
                best = l
            }
        }
        return best
    }

    private fun assemble(id: String, rec: ShopperRecord, all: List<Node>, plan: Plan, isMale: Boolean?, v0: Double): Walker {
        val segs = ArrayList<Seg>()
        val stops = ArrayList<StopRec>()
        for (i in 0 until all.size - 1) {
            addLeg(segs, plan.legs[i], plan.tDep[i], plan.tArr[i + 1], null)
            val m = all[i + 1]
            if (i + 1 < all.size - 1) {
                val st = m.stand!!
                stand(segs, st, null, plan.tArr[i + 1], plan.stayEnd[i + 1], if (st.fixture == "fitting_room") St.FITTING else St.BROWSE)
                stops += StopRec(st, plan.tArr[i + 1], plan.stayEnd[i + 1], m.visit, st.fixture)
                // the lingering before the next departure is part of this stay
                if (i + 1 < all.size - 1 && plan.tDep[i + 1] > plan.stayEnd[i + 1]) {
                    stand(segs, st, null, plan.stayEnd[i + 1], plan.tDep[i + 1], if (st.fixture == "fitting_room") St.FITTING else St.BROWSE)
                    stops[stops.size - 1] = StopRec(st, plan.tArr[i + 1], plan.tDep[i + 1], m.visit, st.fixture)
                }
            } else if (plan.tArr[i + 1] < rec.exitMs) {
                stand(segs, null, m.pt, plan.tArr[i + 1], rec.exitMs, St.IDLE)
            }
        }
        if (segs.isEmpty()) stand(segs, null, all[0].pt, rec.entryMs, rec.exitMs, St.IDLE)
        return Walker(id, false, isMale, v0, rec.entryMs, rec.exitMs, segs, stops, rec.id)
    }

    // ── staff ─────────────────────────────────────────────────────────────────────────────────────────────

    /** Every customer stop of the day by fixture: x, y, arrival, departure; a help spot must stay clear of all of them. */
    private val allStops = HashMap<String, MutableList<DoubleArray>>()

    private fun spotTaken(spot: Stand, t0: Long, t1: Long): Boolean =
        allStops[spot.fixture].orEmpty().any { o -> o[3] > t0 && o[2] < t1 && hypot(o[0] - spot.x, o[1] - spot.y) < 650.0 }

    /** A customer's stop that a member of staff may walk over to. */
    class HelpReq(val stand: Stand, val arrMs: Long, val depMs: Long, val key: String)

    /** Which of the customers' stops draw a help visit: a deterministic draw on the walker's id, for stops long enough to be approached. */
    fun helpRequests(customers: List<Walker>): List<HelpReq> = customers.also { cs ->
        allStops.clear()
        cs.forEach { w ->
            w.stops.forEach {
                allStops.getOrPut(it.fixture) { ArrayList() } +=
                    doubleArrayOf(it.stand.x, it.stand.y, it.arrMs.toDouble(), it.depMs.toDouble())
            }
        }
    }.flatMap { w ->
        w.stops.filter { it.fixture != "fitting_room" && it.depMs - it.arrMs >= 9_000L }.mapIndexedNotNull { i, s ->
            val r = Random(seedOf("help", slug, date.toString(), w.objectId, i.toString())).nextDouble()
            if (r < HELP_RATE) HelpReq(s.stand, s.arrMs, s.depMs, "${w.objectId}#$i") else null
        }
    }.sortedBy { it.arrMs }

    private inner class Cursor(val idx: Int, val speed: Double, start: Long, rng: Random) {
        var stand: Stand? = null
        var pt: Pt = gate(rng)
        var free: Long = start
        val segs = ArrayList<Seg>()
        val enter = start

        fun idleState() = if (stand == null) St.IDLE else St.BROWSE

        fun legTo(to: Stand?, toPt: Pt?): Leg? = leg(stand, if (stand == null) pt else null, to, toPt)

        fun goTo(to: Stand?, toPt: Pt?, arrAt: Long, until: Long, state: St): Boolean {
            val l = legTo(to, toPt) ?: return false
            val dur = ceil(l.poly.length / speed).toLong()
            val dep = max(free, arrAt - dur)
            if (dep > free) stand(segs, stand, pt, free, dep, idleState())
            addLeg(segs, l, dep, dep + dur, null)
            val arrive = dep + dur
            if (to != null) {
                stand(segs, to, null, arrive, max(until, arrive + 1), state)
                stand = to
                pt = Pt(to.x, to.y)
                free = max(until, arrive + 1)
            } else {
                pt = toPt!!
                stand = null
                free = arrive
            }
            return true
        }
    }

    fun staff(count: Int, open: Long, close: Long, helps: List<HelpReq>): List<Walker> {
        if (count <= 0) return emptyList()
        val cursors = (0 until count).map { i ->
            Cursor(
                i,
                STAFF_V + ((i % 3) - 1) * 0.02,
                open - 15 * 60_000L + i * 2 * 60_000L,
                Random(seedOf("staff", slug, date.toString(), i.toString())),
            )
        }
        val shiftEnd = { c: Cursor -> close + 10 * 60_000L - c.idx * 2 * 60_000L }
        val rng = Random(seedOf("staffplan", slug, date.toString()))
        for (h in helps) {
            val side = if (rng.nextBoolean()) 1.0 else -1.0
            val spot = graph.beside(h.stand, side * HELP_OFFSET_MM) ?: graph.beside(h.stand, -side * HELP_OFFSET_MM)
            if (spot == null || spotTaken(spot, h.arrMs, h.depMs)) {
                stats.helpsSkipped++
                continue
            }
            var best: Cursor? = null
            var bestArr = Long.MAX_VALUE
            for (c in cursors) {
                if (c.free > h.depMs - 8_000L) continue
                val l = c.legTo(spot, null) ?: continue
                val arrAt = max(h.arrMs + 3_000L, c.free + ceil(l.poly.length / c.speed).toLong())
                if (arrAt <= h.depMs - 6_000L && arrAt < bestArr && arrAt < shiftEnd(c) - 120_000L) {
                    best = c
                    bestArr = arrAt
                }
            }
            val c = best
            if (c == null) {
                stats.helpsSkipped++
                continue
            }
            fill(c, spot, bestArr, rng)
            val stay = min(h.depMs - 1_000L, bestArr + 10_000L + rng.nextInt(15_000))
            if (c.goTo(spot, null, bestArr, stay, St.HELP)) stats.helps++
        }
        return cursors.map { c ->
            val end = shiftEnd(c)
            val gateOut = gate(Random(seedOf("staffout", slug, date.toString(), c.idx.toString())))
            fill(c, null, end, rng, gateOut)
            c.goTo(null, gateOut, end, end, St.IDLE)
            if (c.free < end) stand(c.segs, null, c.pt, c.free, end, St.IDLE)
            val first = c.segs.first().t0
            Walker("live-staff-$slug-${c.idx + 1}", true, c.idx % 2 == 1, c.speed, first, end, c.segs, emptyList(), null)
        }
    }

    /** Fill the time before [arrAt] with tidying stops at random fixtures while there is room to do that and still arrive. */
    private fun fill(c: Cursor, target: Stand?, arrAt: Long, rng: Random, targetPt: Pt? = null) {
        var guard = 0
        while (guard++ < 40) {
            val direct = c.legTo(target, targetPt) ?: return
            val directMs = ceil(direct.poly.length / c.speed).toLong()
            val remaining = arrAt - c.free
            if (remaining - directMs < 25_000L) return
            var done = false
            repeat(5) {
                if (done) return@repeat
                val t = standList[rng.nextInt(standList.size)]
                val a = c.legTo(t, null) ?: return@repeat
                val b = leg(t, null, target, targetPt) ?: return@repeat
                val travel = ceil((a.poly.length + b.poly.length) / c.speed).toLong()
                if (remaining - travel < 12_000L) return@repeat
                val stay = min(60_000L, 12_000L + rng.nextInt(45_000)).coerceAtMost(remaining - travel - 1_000L)
                val arr = c.free + ceil(a.poly.length / c.speed).toLong()
                if (c.goTo(t, null, arr, arr + stay, St.BROWSE)) done = true
            }
            if (!done) return
        }
    }
}

/** `yyyymmdd`, the stamp the REST ids carry. */
object PeopleStamp {
    fun of(date: LocalDate): String = date.toString().replace("-", "")
}
