package com.m8trx.twin.livefloor

import com.m8trx.twin.layer1.Fixture
import com.m8trx.twin.layer1.Pt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The aisle graph of one floor: a 50 mm grid whose nodes keep at least [hardMm] from every obstacle (and off the walls), 8-connected.
 *
 * **Obstacles.** Every fixture footprint, plus the gap between adjacent units of one run: fixtures whose codes share a prefix once the trailing
 * unit number is cut (`GF-R2-U1..U5` -> run `GF-R2`; `PW-01..07` -> `PW`; `CO-01..04` -> `CO`; `GPS-01..06` -> `GPS`; `ACC-01..04` -> `ACC`) and whose
 * rectangles lie in line with a gap of at most [RUN_JOIN_MM] are one obstacle for path planning, so nobody is routed between two bays. Round and
 * single fixtures are never joined. (`joinRuns = false` gives the raw footprints, for the diagnosis.)
 *
 * **Clearance.** [CLEAR_MM] (0.3 m) is preferred everywhere: a path is first planned with [PLAN_CLEAR_MM] room all along, then at the hard floor with a
 * step cost that rises steeply below 0.3 m, so a narrower passage is taken only when the floor connects no other way, and at its centre line.
 * Paths are string-pulled (a shortcut never goes tighter than the tightest passage it skips) and their corners rounded.
 */
class AisleGraph(val floor: StoreFloor, val hardMm: Double = HARD_DEFAULT_MM, joinRuns: Boolean = true) {
    companion object {
        const val CELL = 50.0
        const val CLEAR_MM = 300.0
        const val PLAN_CLEAR_MM = 420.0
        private const val NODE_EXTRA = 10.0

        /** The hard floor: no walker comes closer than this to an obstacle, and only inside a passage the floor cannot do without. */
        const val HARD_DEFAULT_MM = 150.0
        const val WALL_MM = 150.0
        const val STAND_MM = 250.0

        /** A relaxed stand may come this close to a neighbouring fixture (0.10 m). */
        const val RELAX_MM = 100.0
        const val RUN_JOIN_MM = 400.0

        /** A face shorter than this is a thin fixture's end, not somewhere to stand. */
        const val MIN_FACE_MM = 300.0
        private const val BUCKET = 500.0
        private const val REACH = 1500.0
        private val RUN_KEY = Regex("^(.*?)(-U\\d+|-\\d+)$")
    }

    private class Obst(val code: String, val kind: Int, val p: DoubleArray, val members: Set<String>, val fixture: Fixture?) {
        val x0: Double
        val y0: Double
        val x1: Double
        val y1: Double

        init {
            if (kind == 1) {
                x0 = p[0] - p[2]
                y0 = p[1] - p[2]
                x1 = p[0] + p[2]
                y1 = p[1] + p[2]
            } else {
                x0 = p[0]
                y0 = p[1]
                x1 = p[2]
                y1 = p[3]
            }
        }

        fun dist(x: Double, y: Double): Double = when (kind) {
            0 -> {
                val dx = max(max(x0 - x, x - x1), 0.0)
                val dy = max(max(y0 - y, y - y1), 0.0)
                Math.sqrt(dx * dx + dy * dy)
            }

            1 -> max(0.0, Math.sqrt((x - p[0]) * (x - p[0]) + (y - p[1]) * (y - p[1])) - p[2])

            else -> fixture!!.let { f -> if (f.contains(Pt(x, y))) 0.0 else f.distanceToEdge(Pt(x, y)) }
        }
    }

    private val obstacles = ArrayList<Obst>()
    private val buckets = HashMap<Long, List<Obst>>()
    val nx = (floor.widthMm / CELL).toInt() + 1
    val ny = (floor.depthMm / CELL).toInt() + 1
    private val clr = FloatArray(nx * ny)
    private val comp = IntArray(nx * ny)
    private var mainComp = -1
    private var gateNode = -1

    /** Passages narrower than 0.6 m (a node under [CLEAR_MM]) that routes actually used: cluster -> stats. */
    class PassageStat(var x: Double, var y: Double, var minClearMm: Double, val between: List<String>) {
        var uses = 0
    }

    val usedPassages = HashMap<String, PassageStat>()

    init {
        val rects = ArrayList<Obst>()
        for (f in floor.fixtures) {
            val o = if (f.kind == Fixture.Kind.CIRCLE) {
                Obst(f.code, 1, doubleArrayOf(f.centre.x, f.centre.y, f.radiusMm ?: 0.0), setOf(f.code), f)
            } else {
                val x0 = f.ring.minOf { it.x }
                val y0 = f.ring.minOf { it.y }
                val x1 = f.ring.maxOf { it.x }
                val y1 = f.ring.maxOf { it.y }
                if (f.ring.all { (it.x == x0 || it.x == x1) && (it.y == y0 || it.y == y1) }) {
                    Obst(f.code, 0, doubleArrayOf(x0, y0, x1, y1), setOf(f.code), f).also { rects += it }
                } else {
                    Obst(f.code, 2, doubleArrayOf(), setOf(f.code), f)
                }
            }
            obstacles += o
        }
        if (joinRuns) {
            val groups = rects.groupBy { RUN_KEY.find(it.code)?.groupValues?.get(1) }.filterKeys { it != null }
            for ((_, g) in groups) {
                for (i in g.indices) {
                    for (j in i + 1 until g.size) {
                        val a = g[i]
                        val b = g[j]
                        val gx = max(0.0, max(a.x0 - b.x1, b.x0 - a.x1))
                        val gy = max(0.0, max(a.y0 - b.y1, b.y0 - a.y1))
                        if ((gx == 0.0 && gy in 1.0..RUN_JOIN_MM) || (gy == 0.0 && gx in 1.0..RUN_JOIN_MM)) {
                            val p = if (gx > 0.0) {
                                doubleArrayOf(if (a.x1 <= b.x0) a.x1 else b.x1, max(a.y0, b.y0), if (a.x1 <= b.x0) b.x0 else a.x0, min(a.y1, b.y1))
                            } else {
                                doubleArrayOf(max(a.x0, b.x0), if (a.y1 <= b.y0) a.y1 else b.y1, min(a.x1, b.x1), if (a.y1 <= b.y0) b.y0 else a.y0)
                            }
                            obstacles += Obst("FILL|${a.code}|${b.code}", 0, p, setOf(a.code, b.code), null)
                        }
                    }
                }
            }
        }
        val bx = (floor.widthMm / BUCKET).toInt() + 1
        val by = (floor.depthMm / BUCKET).toInt() + 1
        for (i in 0..bx) {
            for (j in 0..by) {
                val x0 = i * BUCKET
                val y0 = j * BUCKET
                buckets[key(i, j)] = obstacles.filter { o ->
                    o.x0 - REACH <= x0 + BUCKET && o.x1 + REACH >= x0 && o.y0 - REACH <= y0 + BUCKET && o.y1 + REACH >= y0
                }
            }
        }
        for (i in 0 until nx) for (j in 0 until ny) clr[j * nx + i] = fixtureClearance(i * CELL, j * CELL).toFloat()
        label(hardMm, comp)
        pickMain()
    }

    private fun key(i: Int, j: Int) = i.toLong() * 100_000L + j

    /** Distance from (x,y) to the nearest obstacle in mm (0 inside one), capped at [REACH]; [skip] ignores that fixture. */
    fun fixtureClearance(x: Double, y: Double, skip: String? = null): Double {
        val list = buckets[key((x / BUCKET).toInt().coerceAtLeast(0), (y / BUCKET).toInt().coerceAtLeast(0))] ?: obstacles
        var best = REACH
        for (o in list) {
            if (skip != null && o.code == skip) continue
            if (x < o.x0 - best || x > o.x1 + best || y < o.y0 - best || y > o.y1 + best) continue
            val d = o.dist(x, y)
            if (d < best) best = d
            if (best == 0.0) return 0.0
        }
        return best
    }

    /** The (at most two) nearest distinct fixtures within [r] mm of (x,y), nearest first, with `wall` among them when a wall is that close. */
    fun nearFixtures(x: Double, y: Double, r: Double): List<String> {
        val list = buckets[key((x / BUCKET).toInt().coerceAtLeast(0), (y / BUCKET).toInt().coerceAtLeast(0))] ?: obstacles
        val found = ArrayList<Pair<Double, String>>()
        for (o in list) {
            val d = o.dist(x, y)
            if (d <= r) for (m in if (o.fixture == null) o.members else setOf(o.code)) found += d to m
        }
        if (wallClearance(x, y) <= r) found += wallClearance(x, y) to "wall"
        return found.sortedBy { it.first }.map { it.second }.distinct().take(2)
    }

    fun wallClearance(x: Double, y: Double): Double = minOf(x, floor.widthMm - x, y, floor.depthMm - y)

    /** True when (x,y) is a point a person may walk through at clearance [clearMm]. */
    fun walkable(x: Double, y: Double, clearMm: Double = hardMm): Boolean = wallClearance(x, y) >= WALL_MM && fixtureClearance(x, y) >= clearMm

    private fun ok(i: Int, j: Int, clearMm: Double) = i in 0 until nx && j in 0 until ny && clr[j * nx + i] >= clearMm + NODE_EXTRA &&
        wallClearance(i * CELL, j * CELL) >= WALL_MM

    /** Connected components of the nodes that keep [clearMm]: fills [labels] (-1 for a node that does not) and returns the component sizes. */
    private fun label(clearMm: Double, labels: IntArray): List<Int> {
        labels.fill(-1)
        val sizes = ArrayList<Int>()
        var id = 0
        val stack = IntArray(nx * ny)
        for (s in 0 until nx * ny) {
            if (labels[s] != -1 || !ok(s % nx, s / nx, clearMm)) continue
            var sp = 0
            stack[sp++] = s
            labels[s] = id
            var n = 0
            while (sp > 0) {
                val c = stack[--sp]
                n++
                val ci = c % nx
                val cj = c / nx
                for (dj in -1..1) {
                    for (di in -1..1) {
                        if (di == 0 && dj == 0) continue
                        val a = ci + di
                        val b = cj + dj
                        if (!ok(a, b, clearMm) || labels[b * nx + a] != -1) continue
                        if (di != 0 && dj != 0 && (!ok(ci + di, cj, clearMm) || !ok(ci, cj + dj, clearMm))) continue
                        labels[b * nx + a] = id
                        stack[sp++] = b * nx + a
                    }
                }
            }
            sizes += n
            id++
        }
        return sizes
    }

    /** The node of [labels] nearest the entrance gate (any label), or -1. */
    private fun gateNodeOf(labels: IntArray): Int {
        val gx = Math.round((floor.gateX0 + floor.gateX1) / 2 / CELL).toInt()
        val gy = Math.round((floor.gateY + 100.0) / CELL).toInt()
        for (r in 0..80) {
            var best = -1
            var bd = Double.MAX_VALUE
            for (dj in -r..r) {
                for (di in -r..r) {
                    val a = gx + di
                    val b = gy + dj
                    if (a !in 0 until nx || b !in 0 until ny || labels[b * nx + a] < 0) continue
                    val d = (di * di + dj * dj).toDouble()
                    if (d < bd) {
                        bd = d
                        best = b * nx + a
                    }
                }
            }
            if (best >= 0) return best
        }
        return -1
    }

    /** The component people can reach is the one the entrance gate opens into, whatever its size. */
    private fun pickMain() {
        gateNode = gateNodeOf(comp)
        mainComp = if (gateNode >= 0) comp[gateNode] else -1
    }

    val mainComponentNodes: Int by lazy { comp.count { it == mainComp } }
    val totalNodes: Int by lazy { comp.count { it >= 0 } }

    /** The graph node nearest (x,y) in the entrance's component, or -1. */
    fun nearestNode(x: Double, y: Double, clearMm: Double = hardMm, anyComp: Boolean = false): Int {
        val ci = Math.round(x / CELL).toInt()
        val cj = Math.round(y / CELL).toInt()
        for (r in 0..60) {
            var best = -1
            var bd = Double.MAX_VALUE
            for (dj in -r..r) {
                for (di in -r..r) {
                    if (max(abs(di), abs(dj)) != r) continue
                    val a = ci + di
                    val b = cj + dj
                    if (!ok(a, b, clearMm) || (!anyComp && comp[b * nx + a] != mainComp)) continue
                    val d = hypot(a * CELL - x, b * CELL - y)
                    if (d < bd) {
                        bd = d
                        best = b * nx + a
                    }
                }
            }
            if (best >= 0) return best
        }
        return -1
    }

    fun nodePoint(n: Int) = Pt((n % nx) * CELL, (n / nx) * CELL)

    /** A point on the floor the entrance's component serves: a node within 2 cells with a clear line to it (relaxed: only not through a fixture). */
    fun inMainComponent(x: Double, y: Double, relaxed: Boolean = false): Boolean {
        val n = nearestNode(x, y)
        if (n < 0) return false
        val p = nodePoint(n)
        return if (relaxed) {
            hypot(p.x - x, p.y - y) <= 4.0 * CELL && lineOfSight(x, y, p.x, p.y, 60.0)
        } else {
            hypot(p.x - x, p.y - y) <= 2.0 * CELL && lineOfSight(x, y, p.x, p.y, hardMm)
        }
    }

    /** Every point of the segment keeps [clearMm]. */
    fun lineOfSight(x0: Double, y0: Double, x1: Double, y1: Double, clearMm: Double): Boolean {
        val len = hypot(x1 - x0, y1 - y0)
        val n = max(1, (len / 25.0).toInt())
        for (k in 0..n) {
            val f = k.toDouble() / n
            if (!walkable(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, clearMm)) return false
        }
        return true
    }

    // reusable search state (a stamp per call instead of clearing arrays): one planner thread at a time
    private val gCost = FloatArray(nx * ny)
    private val parentOf = IntArray(nx * ny)
    private val seen = IntArray(nx * ny)
    private val closedAt = IntArray(nx * ny)
    private var stamp = 0
    private var heap = LongArray(1 shl 16)
    private var heapN = 0

    private fun push(v: Long) {
        if (heapN == heap.size) heap = heap.copyOf(heap.size * 2)
        var i = heapN++
        while (i > 0) {
            val p = (i - 1) ushr 1
            if (heap[p] <= v) break
            heap[i] = heap[p]
            i = p
        }
        heap[i] = v
    }

    private fun pop(): Long {
        val top = heap[0]
        val last = heap[--heapN]
        var i = 0
        while (true) {
            var c = 2 * i + 1
            if (c >= heapN) break
            if (c + 1 < heapN && heap[c + 1] < heap[c]) c++
            if (heap[c] >= last) break
            heap[i] = heap[c]
            i = c
        }
        if (heapN > 0) heap[i] = last
        return top
    }

    private fun astar(from: Int, to: Int, clearMm: Double, penalise: Boolean): IntArray? {
        if (from < 0 || to < 0) return null
        if (from == to) return intArrayOf(from)
        stamp++
        heapN = 0
        val tx = (to % nx).toDouble()
        val ty = (to / nx).toDouble()
        gCost[from] = 0f
        seen[from] = stamp
        parentOf[from] = -1
        push(from.toLong())
        var found = false
        while (heapN > 0) {
            val c = (pop() and 0xffffffffL).toInt()
            if (closedAt[c] == stamp) continue
            closedAt[c] = stamp
            if (c == to) {
                found = true
                break
            }
            val ci = c % nx
            val cj = c / nx
            for (dj in -1..1) {
                for (di in -1..1) {
                    if (di == 0 && dj == 0) continue
                    val a = ci + di
                    val b = cj + dj
                    if (!ok(a, b, clearMm)) continue
                    if (di != 0 && dj != 0 && (!ok(ci + di, cj, clearMm) || !ok(ci, cj + dj, clearMm))) continue
                    val m = b * nx + a
                    if (closedAt[m] == stamp) continue
                    // below the preferred clearance a step costs steeply more (x4 at 250 mm, x10 at 150 mm)
                    val pen = if (penalise && clr[m] < CLEAR_MM) 1.0 + 3.0 * (CLEAR_MM - clr[m]) / 50.0 else 1.0
                    val ng = gCost[c] + (if (di != 0 && dj != 0) 1.41421356f else 1f) * pen.toFloat()
                    if (seen[m] != stamp || ng < gCost[m]) {
                        seen[m] = stamp
                        gCost[m] = ng
                        parentOf[m] = c
                        val h = Math.sqrt((a - tx) * (a - tx) + (b - ty) * (b - ty))
                        push((((ng + h) * 1000).toLong() shl 32) or m.toLong())
                    }
                }
            }
        }
        if (!found) return null
        val out = ArrayList<Int>()
        var c = to
        while (c != -1) {
            out += c
            c = parentOf[c]
        }
        return out.reversed().toIntArray()
    }

    /** The shortest clear route from (ax,ay) to (bx,by): string-pulled and corner-rounded, or null when none exists. */
    fun route(ax: Double, ay: Double, bx: Double, by: Double): Polyline? {
        val dist = hypot(bx - ax, by - ay)
        // a short hop with room all along is a straight line (never a trip out to the nearest grid node and back)
        if (dist <= 2_000.0 && lineOfSight(ax, ay, bx, by, PLAN_CLEAR_MM - 20)) return direct(ax, ay, bx, by)
        // a relaxed stand's approach point may sit under the hard floor: its last stretch is its own step-in and is not held to it
        val skipA = if (fixtureClearance(ax, ay) < hardMm - 1) 350.0 else 0.0
        val skipB = if (fixtureClearance(bx, by) < hardMm - 1) 350.0 else 0.0
        for (planClear in doubleArrayOf(PLAN_CLEAR_MM, hardMm)) {
            val roomy = planClear == PLAN_CLEAR_MM
            val a = nearestNode(ax, ay, planClear)
            val b = nearestNode(bx, by, planClear)
            if (a < 0 || b < 0) continue
            // at the roomy level the end points must sit on the graph, else the route would detour to the nearest node
            if (roomy &&
                (hypot(nodePoint(a).x - ax, nodePoint(a).y - ay) > 150.0 || hypot(nodePoint(b).x - bx, nodePoint(b).y - by) > 150.0)
            ) {
                continue
            }
            if (!roomy && dist <= 3_000.0 && lineOfSight(ax, ay, bx, by, CLEAR_MM)) return direct(ax, ay, bx, by)
            val nodes = astar(a, b, planClear, penalise = !roomy) ?: continue
            val pts = ArrayList<Pt>()
            val floors = ArrayList<Double>()
            pts += Pt(ax, ay)
            floors += min(CLEAR_MM, fixtureClearance(ax, ay))
            nodes.forEach {
                pts += nodePoint(it)
                floors += clr[it].toDouble()
            }
            pts += Pt(bx, by)
            floors += min(CLEAR_MM, fixtureClearance(bx, by))
            val pulled = pull(pts, floors, if (roomy) PLAN_CLEAR_MM - 20 else CLEAR_MM)
            val rounded = round(pulled)
            if (verify(rounded, skipA, skipB)) return rounded.also { record(it, skipA, skipB) }
            val plain = flatten(pulled)
            if (verify(plain, skipA, skipB)) return plain.also { record(it, skipA, skipB) }
        }
        return null
    }

    private fun direct(ax: Double, ay: Double, bx: Double, by: Double): Polyline = flatten(listOf(Pt(ax, ay), Pt(bx, by)))

    /** String-pulling: a shortcut may only be as tight as the tightest node it skips (never under [hardMm]), so a passage is not cut across. */
    private fun pull(pts: List<Pt>, floors: List<Double>, cap: Double): List<Pt> {
        val out = ArrayList<Pt>()
        var anchor = 0
        out += pts[0]
        while (anchor < pts.size - 1) {
            var far = anchor + 1
            var minFloor = min(floors[anchor], floors[anchor + 1])
            for (k in anchor + 2 until pts.size) {
                minFloor = min(minFloor, floors[k])
                val lim = if (minFloor >= cap) cap else max(hardMm, minFloor - 10.0)
                if (lineOfSight(pts[anchor].x, pts[anchor].y, pts[k].x, pts[k].y, lim)) {
                    far = k
                } else if (k > far + 12) {
                    break
                }
            }
            out += pts[far]
            anchor = far
        }
        return out
    }

    private fun flatten(pts: List<Pt>): Polyline {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (i in 0 until pts.size - 1) {
            val len = hypot(pts[i + 1].x - pts[i].x, pts[i + 1].y - pts[i].y)
            val n = max(1, (len / 50.0).toInt())
            for (k in 0 until n) {
                xs += pts[i].x + (pts[i + 1].x - pts[i].x) * k / n
                ys += pts[i].y + (pts[i + 1].y - pts[i].y) * k / n
            }
        }
        xs += pts.last().x
        ys += pts.last().y
        return Polyline(xs.toDoubleArray(), ys.toDoubleArray())
    }

    /** Quadratic-Bezier corners (radius up to 0.5 m, never more than half a leg), so the heading turns smoothly instead of snapping. */
    private fun round(pts: List<Pt>): Polyline {
        if (pts.size < 3) return flatten(pts)
        val out = ArrayList<Pt>()
        out += pts[0]
        for (i in 1 until pts.size - 1) {
            val p = pts[i]
            val a = pts[i - 1]
            val b = pts[i + 1]
            val la = hypot(p.x - a.x, p.y - a.y)
            val lb = hypot(b.x - p.x, b.y - p.y)
            val r = min(500.0, min(la, lb) / 2.0)
            if (r < 1.0) {
                out += p
                continue
            }
            val s = Pt(p.x + (a.x - p.x) / la * r, p.y + (a.y - p.y) / la * r)
            val e = Pt(p.x + (b.x - p.x) / lb * r, p.y + (b.y - p.y) / lb * r)
            for (k in 0..8) {
                val t = k / 8.0
                val u = 1 - t
                out += Pt(u * u * s.x + 2 * u * t * p.x + t * t * e.x, u * u * s.y + 2 * u * t * p.y + t * t * e.y)
            }
        }
        out += pts.last()
        return flatten(out)
    }

    private fun verify(p: Polyline, skipStart: Double, skipEnd: Double): Boolean {
        var s = 0.0
        while (s <= p.length) {
            if (s >= skipStart && s <= p.length - skipEnd) {
                val q = p.at(s)
                if (!walkable(q.x, q.y, hardMm - 1.0)) return false
            }
            s += 25.0
        }
        return true
    }

    /**
     * Count the passages under 0.6 m (a point under 290 mm from an obstacle) this route goes through. One passage = the same pair of fixtures
     * within about 3 m, so a long alley is one entry, and its width is twice the smallest clearance any route had in it.
     */
    private fun record(p: Polyline, skipStart: Double, skipEnd: Double) {
        val seenHere = HashSet<String>()
        var s = skipStart
        while (s <= p.length - skipEnd) {
            val q = p.at(s)
            val c = fixtureClearance(q.x, q.y)
            if (c < CLEAR_MM - 10 && c > 0.0) {
                val between = nearFixtures(q.x, q.y, c + 120.0)
                val k = between.sorted().joinToString("+") + "@" + Math.round(q.x / 3000.0) + "," + Math.round(q.y / 3000.0)
                val st = usedPassages.getOrPut(k) { PassageStat(q.x, q.y, c, between) }
                if (c < st.minClearMm) {
                    st.minClearMm = c
                    st.x = q.x
                    st.y = q.y
                }
                if (seenHere.add(k)) st.uses++
            }
            s += 50.0
        }
    }

    /** A uniformly random node of the entrance's component with plenty of room. */
    fun randomNode(rng: Random): Int {
        repeat(200) {
            val i = rng.nextInt(nx)
            val j = rng.nextInt(ny)
            if (ok(i, j, PLAN_CLEAR_MM) && comp[j * nx + i] == mainComp) return j * nx + i
        }
        return nearestNode(floor.widthMm / 2, floor.depthMm / 2)
    }

    // ── diagnosis ─────────────────────────────────────────────────────────────────────────────────────────

    class Pinch(val x: Double, val y: Double, val clearMm: Double, val between: List<String>) {
        val widthMm: Double get() = 2 * clearMm
    }

    private var bnParent = IntArray(0)

    /**
     * Widest-path clearance from the entrance: for every node, the largest value of "the tightest node on the way" over all routes (0 when
     * unreachable at this graph's hard floor). Where every route must pass a pinch, this is the pinch's clearance.
     */
    fun bottleneck(): FloatArray {
        val bn = FloatArray(nx * ny)
        bnParent = IntArray(nx * ny) { -1 }
        if (gateNode < 0) return bn
        heapN = 0
        bn[gateNode] = clr[gateNode]
        push(((10_000_000L - (clr[gateNode] * 1000).toLong()) shl 32) or gateNode.toLong())
        val done = BooleanArray(nx * ny)
        while (heapN > 0) {
            val c = (pop() and 0xffffffffL).toInt()
            if (done[c]) continue
            done[c] = true
            val ci = c % nx
            val cj = c / nx
            for (dj in -1..1) {
                for (di in -1..1) {
                    if (di == 0 && dj == 0) continue
                    val a = ci + di
                    val b = cj + dj
                    if (!ok(a, b, hardMm)) continue
                    if (di != 0 && dj != 0 && (!ok(ci + di, cj, hardMm) || !ok(ci, cj + dj, hardMm))) continue
                    val m = b * nx + a
                    val v = min(bn[c], clr[m])
                    if (!done[m] && v > bn[m]) {
                        bn[m] = v
                        bnParent[m] = c
                        push(((10_000_000L - (v * 1000).toLong()) shl 32) or m.toLong())
                    }
                }
            }
        }
        return bn
    }

    /** The tightest node on the widest route to [node] (call [bottleneck] first), with the fixtures forming that pinch. */
    fun pinchTo(node: Int): Pinch? {
        if (node < 0 || bnParent.isEmpty()) return null
        var c = node
        var worst = node
        while (c != -1) {
            if (clr[c] < clr[worst]) worst = c
            c = bnParent[c]
        }
        val p = nodePoint(worst)
        return Pinch(p.x, p.y, clr[worst].toDouble(), nearFixtures(p.x, p.y, clr[worst] + 120.0))
    }

    /** The node nearest (x,y) at this graph's hard floor, in any component. */
    fun anyNode(x: Double, y: Double): Int = nearestNode(x, y, hardMm, anyComp = true)

    /** A part of the floor (at least [Defect.nodes] clear nodes) that the preferred 0.3 m clearance does not connect to the entrance. */
    class Defect(val nodes: Int, val bestClearMm: Double, val pinch: Pinch?)

    /**
     * Parts of the floor cut off from the entrance at the preferred clearance, each with the widest link the hard floor gives it (the
     * only link when a route exists at all). A link under 0.6 m (clearance under [CLEAR_MM]) is a floor-plan defect; [Defect.pinch] null means
     * unreachable even at the hard floor.
     */
    fun defects(minNodes: Int = 200): List<Defect> {
        val bn = bottleneck()
        val lab = IntArray(nx * ny)
        val sizes = label(CLEAR_MM, lab)
        val entrance = gateNodeOf(lab).let { if (it < 0) -1 else lab[it] }
        val out = ArrayList<Defect>()
        for (id in sizes.indices) {
            if (id == entrance || sizes[id] < minNodes) continue
            var best = -1
            for (n in lab.indices) if (lab[n] == id && (best < 0 || bn[n] > bn[best])) best = n
            out += Defect(sizes[id], bn[best].toDouble(), if (bn[best] > 0f) pinchTo(best) else null)
        }
        return out
    }

    // ── stands ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Every usable stand of every fixture: 0.25 m off an exposed face, facing it, inside no obstacle and at least [hardMm] from the others
     * (a joined run is one obstacle, so a bay's side faces are closed), with an approach point 0.28-0.45 m off the face in the entrance's component.
     * A fixture with none gets ONE relaxed stand: its most open face, the neighbours as close as [RELAX_MM]. A fixture enclosed on every side gets none.
     */
    val stands: Map<String, List<Stand>> by lazy {
        val out = LinkedHashMap<String, List<Stand>>()
        for (f in floor.fixtures) out[f.code] = standsOf(f)
        out
    }

    /** Fixtures whose only stand is the relaxed one. */
    val relaxedStands: Set<String> by lazy {
        floor.fixtures.map { it.code }.filter { code ->
            stands[code].orEmpty().let {
                it.size == 1 &&
                    it[0].relaxed
            }
        }.toSet()
    }

    /**
     * A face a person may stand at. Never the end of a thin fixture (a face under [MIN_FACE_MM] long: a dot there sits on the fixture's own line,
     * 250 mm beyond its end), and for a perimeter fixture only the side that faces the sales floor, never the wall side: `perimeter_west` shelves are
     * stood at from the east, `perimeter_east` from the west (the other sides are the non-retail strip and the checkout side).
     */
    private fun faceUsable(f: Fixture, n: Pt): Boolean {
        if (f.kind == Fixture.Kind.POLYGON) {
            val xs = f.ring.map { it.x }
            val ys = f.ring.map { it.y }
            val alongX = abs(n.x) < 0.5 // the face runs along x when its normal is vertical
            val len = if (alongX) xs.max() - xs.min() else ys.max() - ys.min()
            if (len < MIN_FACE_MM) return false
        }
        return when (floor.category[f.code]) {
            "perimeter_west" -> n.x > 0.5
            "perimeter_east" -> n.x < -0.5
            else -> true
        }
    }

    private fun facesOf(f: Fixture): List<Pair<Pt, Pt>> {
        val faces = ArrayList<Pair<Pt, Pt>>() // face point, outward normal
        if (f.kind == Fixture.Kind.CIRCLE) {
            val r = f.radiusMm ?: 0.0
            for (k in 0 until 8) {
                val a = k * Math.PI / 4
                faces += Pt(f.centre.x + r * cos(a), f.centre.y + r * sin(a)) to Pt(cos(a), sin(a))
            }
        } else {
            for (i in 0 until f.ring.size - 1) {
                val a = f.ring[i]
                val b = f.ring[i + 1]
                val len = hypot(b.x - a.x, b.y - a.y)
                if (len < 1.0) continue
                val tx = (b.x - a.x) / len
                val ty = (b.y - a.y) / len
                var nxv = ty
                var nyv = -tx
                val mid = Pt((a.x + b.x) / 2, (a.y + b.y) / 2)
                if (f.contains(Pt(mid.x + nxv * 40, mid.y + nyv * 40))) {
                    nxv = -nxv
                    nyv = -nyv
                }
                val count = max(1, (len / 1200.0).toInt())
                for (k in 0 until count) {
                    val t = (k + 0.5) / count * len
                    faces += Pt(a.x + tx * t, a.y + ty * t) to Pt(nxv, nyv)
                }
            }
        }
        return faces
    }

    /** [reach] false lists the geometrically legal stands whether or not the aisles connect them. */
    fun standsOf(f: Fixture, reach: Boolean = true): List<Stand> {
        val strict = ArrayList<Stand>()
        var relaxed: Stand? = null
        var relaxedOpen = -1.0
        for ((p, n) in facesOf(f)) {
            if (!faceUsable(f, n)) continue
            val sx = p.x + n.x * STAND_MM
            val sy = p.y + n.y * STAND_MM
            if (wallClearance(sx, sy) < 100.0) continue
            if (abs(distanceTo(f, sx, sy) - STAND_MM) > 15.0) continue
            val other = fixtureClearance(sx, sy, skip = f.code)
            if (other < RELAX_MM) continue
            if (other >= max(hardMm, RELAX_MM)) {
                var ap: Pt? = null
                for (a in doubleArrayOf(450.0, 400.0, 350.0, 320.0, 300.0, 280.0)) {
                    val qx = p.x + n.x * a
                    val qy = p.y + n.y * a
                    if (walkable(qx, qy, hardMm + 5.0) && (!reach || inMainComponent(qx, qy))) {
                        ap = Pt(qx, qy)
                        break
                    }
                }
                if (ap != null) {
                    strict += Stand(f.code, sx, sy, ap.x, ap.y, -n.x, -n.y)
                    continue
                }
            }
            if (other > relaxedOpen) {
                for (a in doubleArrayOf(400.0, 350.0, 300.0, 280.0, 250.0)) {
                    val qx = p.x + n.x * a
                    val qy = p.y + n.y * a
                    if (fixtureClearance(qx, qy) >= RELAX_MM && wallClearance(qx, qy) >= 100.0 &&
                        (!reach || inMainComponent(qx, qy, relaxed = true))
                    ) {
                        relaxed = Stand(f.code, sx, sy, qx, qy, -n.x, -n.y, relaxed = true)
                        relaxedOpen = other
                        break
                    }
                }
            }
        }
        return if (strict.isNotEmpty()) strict else listOfNotNull(relaxed)
    }

    fun distanceTo(f: Fixture, x: Double, y: Double): Double = if (f.contains(Pt(x, y))) 0.0 else f.distanceToEdge(Pt(x, y))

    /** A stand beside [s] along the same face, [offsetMm] along its tangent, or null when that spot is not a legal (strict) stand. */
    fun beside(s: Stand, offsetMm: Double): Stand? {
        val f = floor.fixtures.firstOrNull { it.code == s.fixture } ?: return null
        val sx = s.x + s.tx * offsetMm
        val sy = s.y + s.ty * offsetMm
        val ax = s.ax + s.tx * offsetMm
        val ay = s.ay + s.ty * offsetMm
        if (wallClearance(sx, sy) < 100.0 || fixtureClearance(sx, sy, skip = f.code) < max(hardMm, RELAX_MM)) return null
        if (abs(distanceTo(f, sx, sy) - STAND_MM) > 15.0) return null
        if (!walkable(ax, ay, hardMm + 5.0) || !inMainComponent(ax, ay)) return null
        return Stand(s.fixture, sx, sy, ax, ay, s.fx, s.fy)
    }
}
