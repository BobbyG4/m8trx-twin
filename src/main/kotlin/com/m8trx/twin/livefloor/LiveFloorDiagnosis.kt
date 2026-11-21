package com.m8trx.twin.livefloor

import com.m8trx.twin.history.ChainKeys
import java.nio.file.Path
import java.time.LocalDate

/**
 * `./gradlew liveFloorDiagnose` - where each store's floor is cut off from the entrance, offline, layouts read only. For every store, with the raw
 * footprints and with the runs joined: the parts of the floor the 0.3 m rule does not connect (the widest link each has and the two fixtures that
 * form it), and the smallest clearance at which the whole floor is one piece (every fixture's best stand, and the checkout, reachable).
 *
 * `M8TRX_LIVE_FLOOR_TEST_STORES` narrows it. `M8TRX_LIVE_FLOOR_WHATIF` (e.g. `CO-01@x0=+600;PE-last@y1=-600`, see [StoreFloor.parseEdits]) repeats the
 * runs-joined analysis on an in-memory edited copy of each floor, beside the unedited one; with `M8TRX_LIVE_FLOOR_WHATIF_WALK=<slug>` it also walks
 * that store's day on the edited copy and prints the share of walking samples under 0.3 m. Nothing is written to reference/data.
 */
fun main() {
    val chain = Path.of("reference/data/chain")
    val all = com.m8trx.twin.storeday.StoreState.STORE_SLUGS
    val slugs = System.getenv("M8TRX_LIVE_FLOOR_TEST_STORES")?.split(",")?.map { it.trim() }?.filter { it in all } ?: all
    System.getenv("M8TRX_LIVE_FLOOR_STANDS")?.takeIf { it.isNotBlank() }?.let { slug ->
        standTable(StoreFloor.load(slug, chain))
        return
    }
    val whatIf = System.getenv("M8TRX_LIVE_FLOOR_WHATIF")?.takeIf { it.isNotBlank() }?.let(StoreFloor::parseEdits)
    for (slug in slugs) {
        val floor = StoreFloor.load(slug, chain)
        if (whatIf == null) {
            analyse("$slug  raw footprints", floor, join = false)
            analyse("$slug  runs joined", floor, join = true)
        } else {
            analyse("$slug  runs joined, unedited", floor, join = true)
            analyse("$slug  runs joined, WHAT IF ${System.getenv("M8TRX_LIVE_FLOOR_WHATIF")}", floor.withEdits(whatIf), join = true)
        }
    }
    val walk = System.getenv("M8TRX_LIVE_FLOOR_WHATIF_WALK")?.takeIf { it.isNotBlank() }
    if (whatIf != null && walk != null) {
        val keys = ChainKeys.load(chain.resolve(ChainKeys.DEFAULT_PATH.fileName))
        val date = LocalDate.parse("2026-11-23")
        for ((label, edit) in listOf<Pair<String, (StoreFloor) -> StoreFloor>>("unedited" to { it }, "what if" to { it.withEdits(whatIf) })) {
            val d = LiveFloorPlanner(chain, keys, LiveFloorPlanner.siteIds(chain), edit).plan(walk, date)
            var n = 0L
            var below = 0L
            var min = Double.MAX_VALUE
            for (w in d.everyone) {
                for (s in w.samples()) {
                    if (s.state != St.WALK) continue
                    n++
                    val c = d.graph.fixtureClearance(s.x, s.y)
                    if (c < AisleGraph.CLEAR_MM - 1.0) below++
                    if (c < min) min = c
                }
            }
            println(
                "walk $walk ($label): ${d.customers.size} customers, $n walking samples, ${"%.2f".format(
                    100.0 * below / maxOf(1, n),
                )}% under 0.3 m, " +
                    "min ${"%.1f".format(min)} mm, defects ${d.graph.defects().size}, passages<0.6m used ${d.graph.usedPassages.values.count {
                        it.between.size >= 2
                    }}",
            )
        }
    }
}

private fun analyse(label: String, floor: StoreFloor, join: Boolean) {
    val g = AisleGraph(floor, hardMm = 90.0, joinRuns = join)
    val bn = g.bottleneck()
    println("== $label")
    for (d in g.defects()) {
        val p = d.pinch
        val link = if (p == null) {
            "unreachable even at 90 mm"
        } else {
            "widest link ${p.widthMm.toInt()} mm wide between ${p.between} at (${p.x.toInt()}, ${p.y.toInt()})"
        }
        println("   cut off at 0.3 m: part of ${d.nodes} nodes (%.1f m2): $link".format(d.nodes * 0.0025))
    }
    var limiting: String? = null
    var limit = Double.MAX_VALUE
    var limitNode = -1
    val noFace = ArrayList<String>()
    var checkoutLimit = Double.MAX_VALUE
    var atPreferred = 0
    var withFace = 0
    for (f in floor.fixtures) {
        val stands = g.standsOf(f, reach = false)
        if (stands.isEmpty()) {
            noFace += f.code
            continue
        }
        withFace++
        var best = 0.0
        var bestNode = -1
        for (st in stands) {
            val n = g.anyNode(st.ax, st.ay)
            if (n >= 0 && bn[n] > best) {
                best = bn[n].toDouble()
                bestNode = n
            }
        }
        if (best >= AisleGraph.CLEAR_MM) atPreferred++
        if (best < limit) {
            limit = best
            limiting = f.code
            limitNode = bestNode
        }
        if (floor.category[f.code] == "checkout" && best < checkoutLimit) checkoutLimit = best
    }
    val pin = if (limitNode >= 0) g.pinchTo(limitNode) else null
    println(
        "   enclosed: $noFace; smallest clearance connecting every best stand: ${limit.toInt()} mm (a gap of ${(2 * limit).toInt()} mm), " +
            "limited by $limiting" + (pin?.let { " via the pinch between ${it.between} at (${it.x.toInt()}, ${it.y.toInt()})" } ?: "") +
            "; checkout ${if (checkoutLimit == Double.MAX_VALUE) "n/a" else "${checkoutLimit.toInt()} mm"}; " +
            "fixtures whose best stand is reachable at the preferred 0.3 m: $atPreferred/$withFace",
    )
}

/** The stands of every perimeter fixture of [floor] (`M8TRX_LIVE_FLOOR_STANDS=<slug>`): footprint, face, stand point, checks. */
private fun standTable(floor: StoreFloor) {
    val g = AisleGraph(floor)
    val cx = floor.widthMm / 2
    val cy = floor.depthMm / 2
    println("== ${floor.slug} floor ${floor.widthMm.toInt()} x ${floor.depthMm.toInt()} mm; stands of the perimeter fixtures")
    for (f in floor.fixtures.filter { floor.category[it.code]?.startsWith("perimeter") == true }) {
        val x0 = f.ring.minOf { it.x }
        val y0 = f.ring.minOf { it.y }
        val x1 = f.ring.maxOf { it.x }
        val y1 = f.ring.maxOf { it.y }
        println("${f.code} footprint x ${x0.toInt()}..${x1.toInt()} y ${y0.toInt()}..${y1.toInt()}")
        for (st in g.stands[f.code].orEmpty()) {
            val nxv = -st.fx
            val nyv = -st.fy
            val side = if (nxv * (cx - f.centre.x) + nyv * (cy - f.centre.y) > 0) "floor side" else "WALL side"
            val inside = g.fixtureClearance(st.x, st.y) == 0.0
            val outside = st.x < 0 || st.x > floor.widthMm || st.y < 0 || st.y > floor.depthMm
            println(
                "   face normal (%+.0f,%+.0f) %s  stand (%d,%d)  to edge %.0f mm  inside a fixture: %s  outside floor: %s%s".format(
                    nxv, nyv, side, st.x.toInt(), st.y.toInt(), g.distanceTo(f, st.x, st.y), inside, outside, if (st.relaxed) "  RELAXED" else "",
                ),
            )
        }
    }
}
