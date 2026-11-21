package com.m8trx.twin.livefloor

import com.m8trx.twin.behavior.ShopperAgent
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.layer0.NatsEmitter
import com.m8trx.twin.layer3.LiveDrive
import com.m8trx.twin.layer3.PeopleSource
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.system.exitProcess

/**
 * `./gradlew liveFloorSelfTest` - the live floor walking model and its dry-mode feed (part of `offlineSelfTest`). Offline: no network, no
 * credentials, files only under `build/live-floor-selftest/`.
 *
 * Every one of the ten stores is planned for one weekday (Mon 2026-11-23) from the store-day model's own visits, then every sample of every
 * person is checked. The dry-mode feed is checked on dec-kr-busan.
 */
private val OUT: Path = Path.of("build/live-floor-selftest")
private val DATE: LocalDate = LocalDate.parse("2026-11-23")
private val chain = Path.of("reference/data/chain")
private val keys: ChainKeys by lazy { ChainKeys.load(chain.resolve(ChainKeys.DEFAULT_PATH.fileName)) }
private val planner by lazy { LiveFloorPlanner(chain, keys, LiveFloorPlanner.siteIds(chain)) }
private const val PILOT = "dec-kr-busan"

/** All ten stores; `M8TRX_LIVE_FLOOR_TEST_STORES=a,b` narrows a development run (the pilot is always included). */
private val slugs: List<String> = com.m8trx.twin.storeday.StoreState.STORE_SLUGS.let { all ->
    System.getenv("M8TRX_LIVE_FLOOR_TEST_STORES")?.split(",")?.map { it.trim() }?.filter { it in all }?.let { (it + PILOT).distinct() } ?: all
}
private const val TENANT = "selftest-tenant"

/** One planner per store, so the ten days can be planned on parallel threads (a planner itself plans one day at a time). */
private fun plannerFor() = LiveFloorPlanner(chain, keys, LiveFloorPlanner.siteIds(chain))

private val days: Map<String, DayWalk> by lazy {
    val planned = slugs.parallelStream().map { s ->
        val t0 = System.currentTimeMillis()
        s to plannerFor().plan(s, DATE).also {
            println("      planned $s: ${it.customers.size} customers + ${it.staff.size} staff in ${System.currentTimeMillis() - t0} ms")
        }
    }.toList()
    slugs.associateWith { s -> planned.first { it.first == s }.second }
}

private var failures = 0

private fun ok(cond: Boolean, msg: () -> String) {
    if (!cond) {
        failures++
        println("      FAIL ${msg()}")
    }
}

private fun pct(a: Int, b: Int) = if (b == 0) 0.0 else 100.0 * a / b

private fun f1(v: Double) = "%.1f".format(v)

/** Everything measured over one store's samples. */
private class Agg {
    var samples = 0L
    var walkN = 0L
    var walkMinClr = Double.MAX_VALUE
    var walkBelow300 = 0L
    var stepMinClr = Double.MAX_VALUE
    var standN = 0L
    var standMin = Double.MAX_VALUE
    var standMax = 0.0
    var otherMinClr = Double.MAX_VALUE
    var faceBad = 0L
    var faceN = 0L
    var worstJump = 0.0
    var maxSegSpeed = 0.0
    var minSegSpeed = Double.MAX_VALUE
    var minWindow = Double.MAX_VALUE
    var windows = 0L
    var windowsLow = 0L
    var staffMaxSpeed = 0.0
    var staffMinSpeed = Double.MAX_VALUE
    var badWindowMsg: String? = null
    var worstWindowInfo: String? = null
}

private fun measure(d: DayWalk): Agg {
    val a = Agg()
    val g = d.graph
    val fixtures = g.floor.fixtures.associateBy { it.code }
    for (w in d.everyone) {
        var prev: FloorSample? = null
        var segI = 0
        var prevSegI = 0
        val win = ArrayDeque<FloorSample>()
        for (s in w.samples()) {
            a.samples++
            while (segI < w.segs.size - 1 && w.segs[segI].t1 <= s.tMs) segI++
            val seg = w.segs[segI]
            when (s.state) {
                St.WALK -> {
                    a.walkN++
                    val c = g.fixtureClearance(s.x, s.y)
                    a.walkMinClr = min(a.walkMinClr, c)
                    if (c < AisleGraph.CLEAR_MM - 1.0) a.walkBelow300++
                }

                St.APPROACH, St.DEPART -> a.stepMinClr = min(a.stepMinClr, g.fixtureClearance(s.x, s.y))

                St.BROWSE, St.HELP -> {
                    val f = fixtures[s.fixture]
                    if (f != null) {
                        val dd = g.distanceTo(f, s.x, s.y)
                        a.standN++
                        a.standMin = min(a.standMin, dd)
                        a.standMax = max(a.standMax, dd)
                        a.otherMinClr = min(a.otherMinClr, g.fixtureClearance(s.x, s.y, skip = f.code))
                    }
                    if (!seg.fx.isNaN() && s.tMs - seg.t0 >= 1_500L) {
                        a.faceN++
                        if (s.hx * seg.fx + s.hy * seg.fy < Math.cos(Math.toRadians(12.0))) a.faceBad++
                    }
                }

                else -> Unit
            }
            if (seg.poly != null && seg.speed > 0.0 && seg.s1 - seg.s0 >= 100.0) {
                if (w.staff) {
                    if (seg.speed >
                        1.0
                    ) {
                        a.badWindowMsg =
                            "staff ${w.objectId} seg ${seg.state}[${seg.t0}..${seg.t1}] s=${seg.s0}..${seg.s1} len=${seg.poly?.length} speed=${seg.speed}"
                    }
                    a.staffMaxSpeed = max(a.staffMaxSpeed, seg.speed)
                    a.staffMinSpeed = min(a.staffMinSpeed, seg.speed)
                } else {
                    a.maxSegSpeed = max(a.maxSegSpeed, seg.speed)
                    a.minSegSpeed = min(a.minSegSpeed, seg.speed)
                }
            }
            val p = prev
            if (p != null) {
                val dt = s.tMs - p.tMs
                val dist = hypot(s.x - p.x, s.y - p.y)
                // the fastest segment the walker was in between the two samples (a short move can sit wholly between them)
                val between = (prevSegI..segI).maxOf { w.segs[it].speed }
                val allowed = max(max(s.speed, p.speed), between) * dt + 5.0
                a.worstJump = max(a.worstJump, dist - allowed)
                if (dist > allowed) {
                    val near = w.segs.filter { it.t1 >= s.tMs - 1_000 && it.t0 <= s.tMs + 1_000 }
                        .joinToString(" | ") {
                            "${it.state}[${it.t0}..${it.t1}] s=${it.s0.toInt()}..${it.s1.toInt()} at (${it.x.toInt()},${it.y.toInt()})"
                        }
                    a.badWindowMsg = "${w.objectId} t=${s.tMs} jump $dist mm > $allowed; segs: $near"
                }
            }
            // 1 s windows of pure walking: the straight-line speed must stay near the segment's speed
            if (s.state == St.WALK) {
                win.addLast(s)
                if (win.size > 5) win.removeFirst()
                if (win.size == 5 && win.first().tMs + 800 == win.last().tMs) {
                    val r = hypot(win.last().x - win.first().x, win.last().y - win.first().y) / 800.0 / max(1e-9, s.speed)
                    a.windows++
                    if (r < a.minWindow) {
                        val pts = win.joinToString(" ") { "(${it.x.toInt()},${it.y.toInt()})" }
                        a.worstWindowInfo = "${w.objectId} staff=${w.staff} t=${win.first().tMs}..${win.last().tMs} seg#$segI pts=$pts"
                    }
                    a.minWindow = min(a.minWindow, r)
                    if (r < 0.85) a.windowsLow++
                }
            } else {
                win.clear()
            }
            prev = s
            prevSegI = segI
        }
    }
    return a
}

/** What the per-store table at the end of the run prints. */
private class Row(val slug: String) {
    var below = 0.0
    var minClr = 0.0
    var reach = 0
    var fixtures = 0
    var enclosedPct = 0.0
    var relaxed = 0
    var match = 0.0
    var checkout = 0.0
    var passages = 0
    var defects = 0
    var fallbacks = 0
}

private val rows = LinkedHashMap<String, Row>()

private fun row(s: String) = rows.getOrPut(s) { Row(s) }

private fun walkingModel() {
    println("      store                 customers  staff stops  stand(mm)        other-clr  face-bad  jump  seg-speed(m/s)  win-min")
    java.nio.file.Files.createDirectories(OUT)
    val report = StringBuilder()
    for (s in slugs) {
        val d = days.getValue(s)
        val a = measure(d)
        val st = d.stats
        println(
            "      %-20s %9d %6d %5d %6s-%-6s %9s %5d/%-6d %5s %7s-%-6s %6s".format(
                s, d.customers.size, d.staff.size, st.stops, f1(a.standMin), f1(a.standMax), f1(a.otherMinClr), a.faceBad, a.faceN, f1(a.worstJump),
                "%.3f".format(a.minSegSpeed), "%.3f".format(a.maxSegSpeed), "%.2f".format(a.minWindow),
            ),
        )
        val g = d.graph
        val none = g.stands.filterValues { it.isEmpty() }.keys
        val r = row(s)
        r.below = pct(a.walkBelow300.toInt(), a.walkN.toInt())
        r.minClr = a.walkMinClr
        r.fixtures = g.stands.size
        r.reach = g.stands.size - none.size
        r.enclosedPct = pct(st.enclosed.values.sum(), st.visits)
        r.relaxed = g.relaxedStands.size
        r.checkout = pct(st.purchasersAtCheckout, st.purchasers)
        val everyPassage = g.usedPassages.values.sortedByDescending { it.uses }
        // two-sided (fixture-fixture or fixture-wall) = a passage; one-sided = a corner or end clipped under 0.3 m in open floor
        val passages = everyPassage.filter { it.between.size >= 2 }
        val clips = everyPassage.size - passages.size
        r.passages = passages.size
        val defects = g.defects()
        r.defects = defects.size
        r.fallbacks = st.fallbacks
        println(
            "      %-20s stands %d/%d (relaxed %s); enclosed %s; stops w/o stand %s%%; passages<0.6m used: %d (+%d clips); defects: %d".format(
                "",
                r.reach,
                r.fixtures,
                g.relaxedStands,
                none,
                f1(r.enclosedPct),
                passages.size,
                clips,
                defects.size,
            ),
        )
        report.append("== $s: passages under 0.6 m used by routes (width = 2 x clearance)\n")
        passages.forEach { p ->
            report.append(
                "   width %4d mm  between %-28s at (%5d, %5d)  routes %d\n".format(
                    (2 * p.minClearMm).toInt(),
                    p.between,
                    p.x.toInt(),
                    p.y.toInt(),
                    p.uses,
                ),
            )
        }
        report.append("   (plus $clips one-sided corner clips under 0.3 m)\n")
        report.append("== $s: parts of the floor linked to the entrance only through a gap under 0.6 m, or not at all (floor-plan defects)\n")
        defects.forEach { df ->
            val pc = df.pinch
            report.append(
                "   part of %d clear nodes (%.1f m2): %s\n".format(
                    df.nodes,
                    df.nodes * 0.0025,
                    if (pc ==
                        null
                    ) {
                        "UNREACHABLE at the hard floor"
                    } else {
                        "only link %d mm wide between %s at (%d, %d)".format(pc.widthMm.toInt(), pc.between, pc.x.toInt(), pc.y.toInt())
                    },
                ),
            )
        }
        passages.take(4).forEach { p ->
            println(
                "        passage %4d mm between %-26s at (%5d,%5d) routes %d".format(
                    (2 * p.minClearMm).toInt(),
                    p.between,
                    p.x.toInt(),
                    p.y.toInt(),
                    p.uses,
                ),
            )
        }
        defects.forEach { df ->
            val pc = df.pinch
            println(
                "        defect: part of %d nodes %s".format(
                    df.nodes,
                    if (pc ==
                        null
                    ) {
                        "UNREACHABLE"
                    } else {
                        "linked only by a %d mm gap between %s at (%d,%d)".format(pc.widthMm.toInt(), pc.between, pc.x.toInt(), pc.y.toInt())
                    },
                ),
            )
        }
        ok(r.checkout >= 99.9) { "$s: only ${f1(r.checkout)}% of purchasers passed a checkout counter" }
        ok(r.enclosedPct <= 2.0) { "$s: ${f1(r.enclosedPct)}% of stops have no stand" }
        ok(a.walkN > 1000 && a.standN > 100) { "$s: too few samples (walk ${a.walkN}, stand ${a.standN})" }
        ok(a.walkMinClr >= g.hardMm - 1.5) { "$s: a walking sample is ${f1(a.walkMinClr)} mm from a fixture (hard floor ${g.hardMm})" }
        ok(a.stepMinClr >= 95.0) { "$s: a step-in/out sample is ${f1(a.stepMinClr)} mm from a fixture" }
        ok(a.standMin >= 235.0 && a.standMax <= 265.0) { "$s: stand distance ${f1(a.standMin)}..${f1(a.standMax)} mm, not about 250" }
        ok(a.otherMinClr >= 99.0) { "$s: a stander is ${f1(a.otherMinClr)} mm from another fixture" }
        ok(a.faceN > 100 && a.faceBad * 100 <= a.faceN) { "$s: ${a.faceBad}/${a.faceN} standing samples do not face the fixture" }
        ok(a.minSegSpeed >= WalkPlanner.V_MIN * 0.995 && a.maxSegSpeed <= WalkPlanner.V_MAX * 1.005) {
            "$s: customer speed ${a.minSegSpeed}..${a.maxSegSpeed} outside ${WalkPlanner.V_MIN}..${WalkPlanner.V_MAX}"
        }
        ok(a.staffMaxSpeed <= 1.0 && a.staffMinSpeed >= 0.9) {
            "$s: staff speed ${a.staffMinSpeed}..${a.staffMaxSpeed} is not about 0.95 (${a.badWindowMsg})"
        }
        ok(a.worstJump <= 0.0) { "$s: a jump exceeds speed x dt + 5 mm by ${f1(a.worstJump)} mm (${a.badWindowMsg})" }
        ok(a.minWindow >= 0.5 && a.windowsLow * 100 <= 5 * a.windows) {
            "$s: the straight-line speed of walking windows is off (min ratio ${a.minWindow}, ${a.windowsLow}/${a.windows} low): ${a.worstWindowInfo}"
        }
    }
    java.nio.file.Files.writeString(OUT.resolve("passages-and-defects.txt"), report.toString())
}

private fun restIds() {
    println("      store                 REST sessions  walkers  ids-in-REST  window-exact  impressions: matched(>=50% overlap) / all")
    for (s in slugs) {
        val d = days.getValue(s)
        val begin = d.lines.filter { it.kind == "person_begin" }
        val closeByRef = d.lines.filter { it.kind == "person_close" }.associateBy { it.sessionRef }
        val tracking = begin.associate { it.body.path("trackingId").asText() to it }
        val byId = d.customers.associateBy { it.objectId }
        ok(d.customers.size == begin.size && byId.size == d.customers.size) { "$s: ${d.customers.size} walkers for ${begin.size} REST sessions" }
        ok(d.customers.all { it.objectId in tracking && it.objectId in d.restTrackingIds }) { "$s: a walker id is not a REST trackingId of the day" }
        ok(d.customers.all { it.objectId.startsWith("ppl-$s-${PeopleStamp.of(DATE)}-") }) { "$s: id shape" }
        var exact = 0
        for ((tid, line) in tracking) {
            val w = byId[tid] ?: continue
            val entry = Instant.parse(line.body.path("entryTime").asText()).toEpochMilli()
            val exit = Instant.parse(closeByRef.getValue(line.id).body.path("exitTime").asText()).toEpochMilli()
            if (w.tEnterMs == entry && w.tExitMs == exit && w.samples().first().tMs == entry && w.samples().last().tMs == exit) exact++
        }
        ok(exact == begin.size) { "$s: only $exact of ${begin.size} walkers span exactly their session's begin/close window" }
        // impressions: the walker stood at the visit's fixture during its look window
        var want = 0
        var got = 0
        var lateSum = 0L
        for (rec in d.result.records) {
            val tid = "ppl-$s-${PeopleStamp.of(DATE)}-${rec.id.substringAfterLast('-')}"
            val w = byId[tid] ?: continue
            for (v in rec.visits.filter { it.impression }) {
                want++
                val lookEnd = v.startMs + ShopperAgent.APPROACH_MS + v.dwellMs
                val st = w.stops.filter { it.fixture == v.fixture }
                val best = st.maxOfOrNull { max(0L, min(it.depMs, lookEnd) - max(it.arrMs, v.startMs)) } ?: 0L
                if (best * 2 >= lookEnd - v.startMs) got++
                st.minByOrNull { abs(it.arrMs - v.startMs) }?.let { lateSum += abs(it.arrMs - v.startMs) }
            }
        }
        println(
            "      %-20s %13d %8d %12s %13d  %d / %d = %s%% (mean |arrival - firstLook| %s s)".format(
                s, begin.size, d.customers.size, "all", exact, got, want, f1(pct(got, want)), f1(lateSum / 1000.0 / max(1, want)),
            ),
        )
        row(s).match = pct(got, want)
        ok(pct(got, want) >= 90.0) { "$s: only ${f1(pct(got, want))}% of impressions are matched by a stop (>= 50% overlap)" }
    }
}

private fun gender() {
    for (s in slugs) {
        val d = days.getValue(s)
        val n = d.customers.size
        val f = d.customers.count { it.isMale == false }
        val m = d.customers.count { it.isMale == true }
        val u = d.customers.count { it.isMale == null }
        println("      %-20s female %s%%  male %s%%  unknown %s%%  (n=%d)".format(s, f1(pct(f, n)), f1(pct(m, n)), f1(pct(u, n)), n))
        ok(abs(pct(f, n) - 60.0) <= 6.0 && abs(pct(m, n) - 30.0) <= 6.0 && abs(pct(u, n) - 10.0) <= 6.0) {
            "$s: gender mix off (f $f, m $m, u $u of $n)"
        }
        ok(d.staff.all { it.isMale != null }) { "$s: staff gender" }
    }
}

private fun staff() {
    for (s in slugs) {
        val d = days.getValue(s)
        ok(d.staff.size == LiveFloorPlanner.staffCount(keys.store(s).areaSqm)) { "$s: staff count" }
        var t = d.openMs
        var minPresent = Int.MAX_VALUE
        while (t <= d.closeMs) {
            minPresent = min(minPresent, d.staff.count { it.tEnterMs <= t && t <= it.tExitMs })
            t += 30_000L
        }
        // a staff member is on the floor at every moment of opening hours, always tagged
        val gaps = d.staff.all { w -> w.samples().zipWithNext().all { (a, b) -> b.tMs - a.tMs <= 5_000L } }
        ok(minPresent >= 1 && gaps) { "$s: staff missing during opening hours (min present $minPresent, gapless $gaps)" }
        ok(
            d.staff.all {
                it.staff && it.objectId == "live-staff-$s-${d.staff.indexOf(it) + 1}" && it.tEnterMs <= d.openMs && it.tExitMs >= d.closeMs
            },
        ) { "$s: staff shifts" }
        // a help stop: beside a browsing customer at the same fixture, apart from them
        var helps = 0
        var tooClose = 0
        var minGap = Double.MAX_VALUE
        var maxGap = 0.0
        for (w in d.staff) {
            for (sg in w.segs.filter { it.state == St.HELP }) {
                helps++
                val mid = (sg.t0 + sg.t1) / 2
                val c = d.customers.filter { cw -> cw.stops.any { it.fixture == sg.fixture && it.arrMs <= mid && mid <= it.depMs } }
                    .minByOrNull { cw -> hypot(cw.posAt(mid).x - sg.x, cw.posAt(mid).y - sg.y) }
                ok(c != null) { "$s: a help stop with no customer at ${sg.fixture}" }
                if (c != null) {
                    val p = c.posAt(mid)
                    val gap = hypot(p.x - sg.x, p.y - sg.y)
                    minGap = min(minGap, gap)
                    maxGap = max(maxGap, gap)
                    if (gap < 600.0) tooClose++
                }
            }
        }
        println(
            "      %-20s staff %d  min present %d  help stops %d (skipped %d)  staff-customer distance %s..%s mm".format(
                s,
                d.staff.size,
                minPresent,
                helps,
                d.stats.helpsSkipped,
                f1(minGap),
                f1(maxGap),
            ),
        )
        ok(tooClose == 0 && maxGap <= 1_100.0) { "$s: help offset ($tooClose too close, max ${f1(maxGap)} mm)" }
        ok(helps > 0 || d.customers.size < 100) { "$s: no help stops at all" }
    }
}

private fun table() {
    println(
        "      store                 below 0.3 m   min clearance  fixtures with a stand  stops w/o stand  impression match  checkout reached  passages<0.6m  defects  fallback visits",
    )
    for (r in rows.values) {
        println(
            "      %-20s %9s%% %12s mm %13d/%-8d %13s%% %15s%% %16s%% %13d %8d %10d".format(
                r.slug,
                "%.2f".format(
                    r.below,
                ),
                f1(r.minClr), r.reach, r.fixtures, "%.2f".format(r.enclosedPct), f1(r.match), f1(r.checkout), r.passages, r.defects, r.fallbacks,
            ),
        )
    }
    ok(rows.size == slugs.size) { "table incomplete" }
}

/** Every REST session of a weekday, a Saturday and a Sunday is a walker (planned in parallel, nothing sampled). */
private fun everyVisitDrawn() {
    val dates = listOf(DATE, LocalDate.parse("2026-11-28"), LocalDate.parse("2026-11-29"))
    println("      store                 " + dates.joinToString("   ") { "%-28s".format("$it ${it.dayOfWeek.name.take(3)}") })
    val results = slugs.parallelStream().map { s ->
        val pl = plannerFor()
        s to dates.map { dt ->
            val d = if (dt == DATE) days.getValue(s) else pl.plan(s, dt)
            val sessions = d.lines.count { it.kind == "person_begin" }
            Triple(sessions, d.customers.size, d.stats.fallbacks) to (d.customers.map { it.objectId }.toSet() == d.restTrackingIds)
        }
    }.toList()
    for (s in slugs) {
        val r = results.first { it.first == s }.second
        println(
            "      %-20s %s".format(
                s,
                r.joinToString("   ") { (t, same) ->
                    "%-28s".format("${t.first} sessions / ${t.second} walkers / ${t.third} fb ${if (same) "" else "IDS DIFFER"}")
                },
            ),
        )
        r.forEachIndexed { i, (t, same) ->
            ok(t.first == t.second && same) { "$s ${dates[i]}: ${t.second} walkers for ${t.first} REST sessions (ids equal: $same)" }
        }
    }
}

/** NOT skippable: nothing here reads M8TRX_LIVE_FLOOR_TEST_STORES or any other env, and the pilot is always planned. */
private fun subjectGuard() {
    val siteIds = LiveFloorPlanner.siteIds(chain)
    val busanSite = siteIds.getValue(PILOT)
    val denverSite = siteIds.getValue("dec-us-denver")
    val busanSpace = keys.store(PILOT).salesFloorSpaceId
    val g = SubjectGuard(TENANT, listOf(busanSite))
    fun refused(subject: String) = runCatching { g.check(subject) }.exceptionOrNull() is SubjectRefused
    ok(refused("area.${busanSpace.replace("-", "")}.objLocation")) { "the guard accepted an area.<busanSpace>.objLocation subject" }
    ok(refused("area.$busanSpace.objEviction")) { "the guard accepted an area.* eviction subject" }
    ok(refused("m8trx.$TENANT.$denverSite.xovis.objLocation")) { "the guard accepted a Denver m8trx subject" }
    ok(refused("m8trx.$TENANT.$busanSite.xovis.objFoo")) { "the guard accepted an unknown event type" }
    ok(refused("m8trx.other-tenant.$busanSite.xovis.objLocation")) { "the guard accepted another tenant" }
    ok(refused("m8trx.$TENANT.$busanSite.xovis.objLocation.extra")) { "the guard accepted a longer subject" }
    ok(!refused("m8trx.$TENANT.$busanSite.xovis.objLocation") && !refused("m8trx.$TENANT.$busanSite.xovis.objEviction")) {
        "the guard refused an allowed Busan subject"
    }
    // a full dry Busan day, every message through the same guard, zero refusals
    val day = days.getValue(PILOT)
    var n = 0L
    var bad = 0L
    for (m in LiveFloorFeed.stream(day.everyone, Long.MIN_VALUE)) {
        n++
        if (!g.allows(LiveFloorFeed.wire(m, TENANT, busanSite, busanSpace, m.planMs).subject)) bad++
    }
    println("      a full dry $PILOT day: $n messages, $bad subjects outside m8trx.<tenant>.<busanSite>.xovis.objLocation|objEviction")
    ok(n > 1_000_000L && bad == 0L) { "a dry Busan day: $n messages, $bad refused by the guard" }
    // by construction: the floor package never builds a NatsEmitter (whose publish also emits area.*) and has one publish call, guarded
    val dir = Path.of("src/main/kotlin/com/m8trx/twin/livefloor")
    val code = Files.list(dir).use {
        it.toList()
    }.filter { it.fileName.toString().endsWith(".kt") && it.fileName.toString() != "LiveFloorSelfTest.kt" }
        .associate {
            it.fileName.toString() to
                Files.readString(it).lines().filter { l -> !l.trim().startsWith("*") && !l.trim().startsWith("//") }.joinToString("\n")
        }
    ok(code.none { (_, t) -> t.contains("NatsEmitter(") || t.contains("publishModernOnly") }) { "the floor package can reach NatsEmitter's publish" }
    ok(
        code.filterKeys {
            it != "FloorPublisher.kt"
        }.none { (_, t) -> Regex("""\.publish\(""").containsMatchIn(t.replace("c.emitter.publish(", "")) },
    ) {
        "a publish call outside FloorPublisher"
    }
}

private fun digest(d: DayWalk): String {
    val md = MessageDigest.getInstance("SHA-256")
    for (w in d.everyone) {
        md.update(w.objectId.toByteArray())
        for (s in w.samples()) md.update("${s.tMs},${s.x},${s.y},${s.hx},${s.hy}".toByteArray())
    }
    return md.digest().joinToString("") { "%02x".format(it) }.take(16)
}

private fun determinism() {
    val a = digest(days.getValue(PILOT))
    val again = LiveFloorPlanner(chain, keys, LiveFloorPlanner.siteIds(chain)).plan(PILOT, DATE)
    val b = digest(again)
    val other = digest(LiveFloorPlanner(chain, keys, LiveFloorPlanner.siteIds(chain)).plan(PILOT, DATE.plusDays(1)))
    println("      $PILOT $DATE digest $a / replay $b / next day $other")
    ok(a == b) { "the same store and date did not replay to the same positions" }
    ok(a != other) { "two dates gave the same digest" }
}

private fun feed() {
    val d = days.getValue(PILOT)
    val store = keys.store(PILOT)
    val site = planner.let { LiveFloorPlanner.siteIds(chain).getValue(PILOT) }
    val from = d.openMs + 3_600_000L
    val to = from + 10 * 60_000L
    val file = OUT.resolve("$PILOT-dry.ndjson")
    var wrote = 0L
    NdjsonWriter(file).use { w ->
        for (m in LiveFloorFeed.stream(d.everyone, from, to)) {
            w.write(LiveFloorFeed.wire(m, TENANT, site, store.salesFloorSpaceId, m.planMs))
            wrote++
        }
    }
    val lines = Files.readAllLines(file)
    ok(lines.size.toLong() == wrote && lines.isNotEmpty()) { "ndjson lines ${lines.size} vs $wrote" }
    val re = Regex("^m8trx\\.$TENANT\\.$site\\.xovis\\.(objLocation|objEviction)$")
    val bySubject = java.util.TreeMap<String, Int>()
    val times = HashMap<String, MutableList<Long>>()
    val evictions = HashMap<String, Int>()
    val evictTs = HashMap<String, Long>()
    var withMale = 0
    var withoutMale = 0
    var staffTagged = 0
    var nonStaffTagFalse = 0
    var bad = 0
    var firstBad: String? = null
    val samples = ArrayList<String>()
    var sampleEvict: String? = null
    for (l in lines) {
        val n = ConnectMappers.snake.readTree(l)
        val subject = n.path("subject").asText()
        bySubject.merge(subject, 1, Int::plus)
        val env = n.path("payload")
        val body = env.path("body")
        val type = env.path("type").asText()
        val id = body.path("objectId").asText()
        var good =
            re.matches(subject) && !subject.startsWith("area.") && subject.endsWith(".$type") &&
                body.path("layoutId").asText() == store.salesFloorSpaceId &&
                body.path("type").asText() == type
        if (type == "objLocation") {
            val vd = body.path("viewDirection")
            good = good && body.path("x").isNumber && body.path("y").isNumber && vd.isArray && vd.size() == 2 &&
                abs(hypot(vd[0].asDouble(), vd[1].asDouble()) - 1.0) < 0.002 && body.path("hasTag").isBoolean
            if (body.has("isMale")) {
                good = good && body.path("isMale").isBoolean
                withMale++
            } else {
                withoutMale++
            }
            if (body.path("hasTag").asBoolean()) staffTagged++ else nonStaffTagFalse++
            good = good && (body.path("hasTag").asBoolean() == id.startsWith("live-staff-"))
            good = good && (id.startsWith("live-staff-") || id in d.restTrackingIds)
            times.getOrPut(id) { ArrayList() } += env.path("ts").asLong()
            if (samples.size < 3 && id.startsWith("ppl-") && body.has("isMale") && samples.size + 1 <= 3) samples += l
        } else {
            good = good && body.size() == 3 && !body.has("x")
            evictions.merge(id, 1, Int::plus)
            evictTs[id] = env.path("ts").asLong()
            if (sampleEvict == null) sampleEvict = l
        }
        if (!good) {
            bad++
            if (firstBad == null) firstBad = l
        }
    }
    println("      subjects: $bySubject")
    println(
        "      objLocation with isMale $withMale, isMale omitted $withoutMale; hasTag=true $staffTagged, hasTag=false $nonStaffTagFalse; people ${times.size}",
    )
    ok(bySubject.keys.all { re.matches(it) }) { "a subject outside m8trx.<tenant>.<site>.xovis.objLocation|objEviction: ${bySubject.keys}" }
    ok(bad == 0) { "$bad messages have a wrong shape; first: $firstBad" }
    ok(withMale > 0 && withoutMale > 0) { "isMale is neither present nor omitted as expected ($withMale / $withoutMale)" }
    ok(staffTagged > 0 && nonStaffTagFalse > 0) { "hasTag split" }
    // every person present in the window is heard at least every 5 s; one eviction per departing person
    var worstGap = 0L
    var leaving = 0
    var evictOk = true
    for (w in d.everyone) {
        if (w.tExitMs < from || w.tEnterMs >= to) continue
        val t = times[w.objectId] ?: run {
            ok(false) { "${w.objectId} present in the window was never heard" }
            return@run null
        } ?: continue
        val sorted = t.sorted()
        val head = sorted.first() - max(from, w.tEnterMs)
        val tail = (if (w.tExitMs < to) w.tExitMs else to) - sorted.last()
        worstGap = max(worstGap, max(head, tail))
        sorted.zipWithNext().forEach { (x, y) -> worstGap = max(worstGap, y - x) }
        val leaves = w.tExitMs < to
        if (leaves) {
            leaving++
            if (evictions[w.objectId] != 1 || evictTs[w.objectId] != w.tExitMs || sorted.last() > w.tExitMs) evictOk = false
        } else if (evictions.containsKey(w.objectId)) {
            evictOk = false
        }
    }
    println(
        "      window ${Instant.ofEpochMilli(
            from,
        )}..${Instant.ofEpochMilli(
            to,
        )}: $wrote messages, worst silence of a present person $worstGap ms, $leaving departures / ${evictions.size} evictions",
    )
    ok(worstGap <= 5_000L) { "a present person went $worstGap ms unheard" }
    ok(evictOk && leaving == evictions.size) {
        "evictions: one per departing person, none for the others ($leaving departures, ${evictions.size} evictions)"
    }
    println("      sample objLocation x3 and objEviction x1 (dry, $PILOT):")
    samples.forEach { println("        $it") }
    println("        $sampleEvict")
    // the speed-up keeps the camera cadence on the wall clock: at 5x every 5th position, evictions all kept
    val d5 = LiveFloorFeed.stream(d.everyone, from, to, 5).asSequence().toList()
    val full = LiveFloorFeed.stream(d.everyone, from, to).asSequence().toList()
    ok(d5.count { it.sample == null } == full.count { it.sample == null }) { "decimation dropped an eviction" }
    ok(d5.size < full.size / 3) { "decimation did not thin the positions" }
}

/** Livefloor is additive: Denver's generator plan is identical with and without a livefloor store, and a store in both lists is refused. */
private fun additive() {
    val denver = "dec-us-denver"
    val siteIds = LiveFloorPlanner.siteIds(chain)
    val all = com.m8trx.twin.storeday.StoreState.STORE_SLUGS
    fun settings(sites: List<String>) = LiveDrive.Settings(
        sites = sites,
        speed = 1,
        shift = LiveDrive.Shift.Open,
        scaleOverride = 0.05,
        dwell = LiveDrive.Dwell.SCALED,
        maxEventsPerS = 500.0,
        maxArrivalsPerMin = 40.0,
        seedBase = LiveDrive.HISTORY_SEED,
        live = false,
        holdFile = null,
        stateFile = OUT.resolve("clock.properties"),
        continueState = false,
        drySeconds = 60,
        dryPrint = emptyList(),
        tenantId = TENANT,
        edges = LiveDrive.KNOWN_EDGES,
        chainDir = chain,
    )
    val off = settings(LiveDrive.generatorSites(all, null, emptyList()))
    val on = settings(LiveDrive.generatorSites(all, null, listOf(PILOT)))
    ok(off.sites == all && denver in on.sites && PILOT !in on.sites && on.sites.size == all.size - 1) {
        "generator sites with and without livefloor: ${on.sites}"
    }
    ok(LiveDrive.generatorSites(listOf(denver), listOf(denver), listOf(PILOT)) == listOf(denver)) {
        "an explicit list without the floor store is kept"
    }
    val refused = runCatching { LiveDrive.generatorSites(all, listOf(denver, PILOT), listOf(PILOT)) }.exceptionOrNull()
    println("      a store in both lists: ${refused?.message}")
    ok(refused is IllegalArgumentException && refused.message!!.contains(PILOT)) { "a store in both lists must be refused" }
    val zone = keys.store(denver).zone
    val planAt = LiveDrive.planAnchor(Instant.parse("2026-11-23T12:00:00Z"), zone, LiveDrive.Shift.Open).toEpochMilli()
    val clock = LiveDrive.Clock(planAt, planAt, 1)
    val a = LiveDrive.planDay(off, keys, siteIds, denver, clock, DATE, Long.MIN_VALUE)
    val b = LiveDrive.planDay(on, keys, siteIds, denver, clock, DATE, Long.MIN_VALUE)
    println(
        "      denver generator plan: ${a.wires.size} wires, identical with livefloor on: ${a.wires == b.wires}; edge ${a.edge?.serverName} / ${b.edge?.serverName}",
    )
    ok(a.wires.isNotEmpty() && a.wires == b.wires && a.edge == b.edge && a.publishes == b.publishes) {
        "Denver's generator plan changed with livefloor on"
    }
    // and Busan's livefloor messages in the same run are only on the two xovis subjects
    val day = days.getValue(PILOT)
    val site = siteIds.getValue(PILOT)
    val subjects = HashSet<String>()
    for (m in LiveFloorFeed.stream(day.everyone, day.openMs + 3_600_000L, day.openMs + 3_720_000L)) {
        subjects += LiveFloorFeed.wire(m, TENANT, site, keys.store(PILOT).salesFloorSpaceId, m.planMs).subject
    }
    println("      busan livefloor subjects beside it: $subjects")
    ok(
        subjects.isNotEmpty() && subjects.all {
            it == "m8trx.$TENANT.$site.xovis.objLocation" || it == "m8trx.$TENANT.$site.xovis.objEviction"
        },
    ) { "busan subjects $subjects" }
}

private fun guards() {
    ok(NatsEmitter.xovisSubject("t", "s", "objLocation") == "m8trx.t.s.xovis.objLocation") { "xovis subject" }
    ok(NatsEmitter.xovisSubject("t", "s", "objEviction") == "m8trx.t.s.xovis.objEviction") { "xovis eviction subject" }
    ok(LiveFloorDrive.urlProblem("nats://192.168.55.29:4222") != null) { "the production office edge port must be refused" }
    ok(LiveFloorDrive.urlProblem("nats://h") != null) { "a url with no port (jnats defaults to 4222) must be refused" }
    ok(LiveFloorDrive.urlProblem("nats://a:4222,nats://b:4223") != null) { "a server list must be refused" }
    ok(LiveFloorDrive.urlProblem("nats://a:4223,nats://b:4223") != null) { "even a list of twin servers must be refused (exactly one)" }
    ok(LiveFloorDrive.urlProblem("nats://h:4223/") == null) { "nats://h:4223/ must be accepted" }
    ok(LiveFloorDrive.urlProblem("nats://h:5000") != null) { "a port other than 4223 must be refused" }
    ok(LiveFloorDrive.urlProblem("nats://192.168.55.29:4223") == null) { "the twin edge port must be accepted" }
    ok(PeopleSource.parse(null, null) == PeopleSource.Generator) { "the default people source must stay the generator" }
    ok(runCatching { PeopleSource.parse("livefloor", null) }.isFailure) { "livefloor is a store list now, not a people source value" }
    ok(PeopleSource.parse("storeday", null) is PeopleSource.StoreDaySidecar) { "the sidecar source is unchanged" }
    ok(LiveFloorDrive.stores().isEmpty() || System.getenv(LiveFloorDrive.ENV_STORES) != null) {
        "livefloor is off unless M8TRX_LIVE_FLOOR_STORES names stores"
    }
    val src = Files.readString(Path.of("src/main/kotlin/com/m8trx/twin/livefloor/LiveFloorDrive.kt"))
    ok(
        !Regex("""["']area\.""").containsMatchIn(
            src.lines().filter {
                !it.trim().startsWith("*") && !it.trim().startsWith("//")
            }.joinToString("\n"),
        ),
    ) {
        "LiveFloorDrive builds an area.* subject"
    }
}

fun main() {
    Files.createDirectories(OUT)
    val tests = listOf(
        "walking: >= 0.3 m from fixtures while walking, 0.25 m at the face and facing it, speeds, no jumps (every sample, ten stores)" to
            ::walkingModel,
        "same visits as REST: walker ids are the REST trackingIds, windows equal begin/close, stops match impressions" to ::restIds,
        "gender mix 60/30/10 (female/male/unknown)" to ::gender,
        "staff: on the floor through opening hours, tagged, help stops beside a browsing customer" to ::staff,
        "deterministic per store and date" to ::determinism,
        "every REST session of a weekday, a Saturday and a Sunday is a walker (never dropped)" to ::everyVisitDrawn,
        "subject guard (not skippable): area.*, Denver and unknown types refused, a full dry Busan day clean, no unguarded publish in the package" to
            ::subjectGuard,
        "per-store table (clearance, reachability, impression match, checkout)" to ::table,
        "dry feed ($PILOT): xovis-only subjects, body fields, ids match REST, heard every 5 s, one eviction per departure" to ::feed,
        "additive: Denver's generator plan unchanged with a livefloor store, both-lists refused, Busan only on the xovis subjects" to ::additive,
        "guards: xovis-only subject builder, :4222 refused, default source unchanged, no area.* subject in the drive" to ::guards,
    )
    var bad = 0
    for ((name, t) in tests) {
        val before = failures
        try {
            t()
        } catch (e: Throwable) {
            failures++
            println("      ERROR ${e.message}")
            e.printStackTrace()
        }
        if (failures > before) {
            bad++
            println("FAIL  $name")
        } else {
            println("PASS  $name")
        }
    }
    println("liveFloorSelfTest: ${tests.size - bad}/${tests.size} passed")
    if (bad > 0) exitProcess(1)
}
