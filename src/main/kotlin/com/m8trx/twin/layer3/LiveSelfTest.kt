package com.m8trx.twin.layer3

import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.layer1.ImpressionOracle
import com.m8trx.twin.layer1.Pt
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * `./gradlew liveSelfTest` — offline tests of the live people mode ([LiveDrive]); part of `offlineSelfTest`. No network, no
 * edge, no state: fixed seeds, fixed instants, the committed chain files.
 */
fun main() {
    val chain = Path.of("reference/data/chain")
    val keys = ChainKeys.load(chain.resolve(ChainKeys.DEFAULT_PATH.fileName))
    val tests = listOf<Pair<String, () -> Unit>>(
        "clock: plan runs speed x the wall clock, wallOf/planOf inverse" to { clock() },
        "scale: hard cap 0.6 refused above, weekday table scaled so Saturday = the cap" to { scale() },
        "shift: open = the store's 10:00 local today, at:HH:mm, hours; each store on its own zone" to { shift() },
        "wires: scaled keeps 5 Hz on the wall at any speed and runs a shopper speed x faster; 1x scaled == real" to { wiresSynthetic() },
        "wires: real keeps every in-episode delta, compresses arrivals and gaps" to { wiresReal() },
        "thinning: whole shoppers, deterministic, proportional, one keep for every store" to { thinning() },
        "edges: only a store whose space an edge is bound to publishes (Denver today); Paris refused" to { edges(keys) },
        "denver plan: real generator at 1x and 5x — object ids carry plan time, rates under the caps at 1x" to { denverPlan(keys) },
    )
    var failed = 0
    for ((name, t) in tests) {
        try {
            t()
            println("PASS  $name")
        } catch (e: Throwable) {
            failed++
            println("FAIL  $name — ${e.message}")
        }
    }
    println("liveSelfTest: ${tests.size - failed}/${tests.size} passed")
    if (failed > 0) error("$failed self-test(s) failed")
}

private val DENVER: ZoneId = ZoneId.of("America/Denver")
private val PARIS: ZoneId = ZoneId.of("Europe/Paris")
private val NOW: Instant = Instant.parse("2026-10-14T06:00:00Z") // 15:00 KST · 00:00 Denver · 08:00 Paris

private fun clock() {
    for (sp in LiveDrive.SPEEDS) {
        val c = LiveDrive.Clock(1_000_000L, 5_000_000L, sp)
        check(c.planOf(c.wallOf(5_000_000L + 60_000L * sp)) == 5_000_000L + 60_000L * sp) { "inverse at ${sp}x" }
        check(c.wallOf(5_000_000L + 60_000L * sp) - 1_000_000L == 60_000L) { "${sp}x: $sp plan minutes must take one wall minute" }
    }
}

private fun scale() {
    check(runCatching { LiveDrive.liveScale(LocalDate.parse("2026-10-14"), 0.61) }.isFailure) { "0.61 must be refused" }
    check(runCatching { LiveDrive.liveScale(LocalDate.parse("2026-10-14"), 1.0) }.isFailure) { "1.0 must be refused" }
    check(LiveDrive.liveScale(LocalDate.parse("2026-10-14"), 0.6) == 0.6) { "0.6 is allowed" }
    check(abs(LiveDrive.liveScale(LocalDate.parse("2026-10-17"), null) - 0.6) < 1e-9) { "Saturday = the cap" }
    check(abs(LiveDrive.liveScale(LocalDate.parse("2026-10-14"), null) - 0.48) < 1e-9) { "Wednesday = 0.48" }
    check(LiveDrive.daySeed(20261016L, LocalDate.parse("2026-10-14")) == 20261016L + 287) { "per-date seed = SEED + day-of-year (day.sh)" }
}

private fun shift() {
    val open = LiveDrive.Shift.parse(null)
    check(open == LiveDrive.Shift.Open && LiveDrive.Shift.parse("open") == LiveDrive.Shift.Open) { "default = open" }
    val d = LiveDrive.planAnchor(NOW, DENVER, open).atZone(DENVER)
    check(d.toLocalTime() == LocalTime.of(10, 0) && d.toLocalDate() == LocalDate.parse("2026-10-14")) { "Denver open anchor $d" }
    val p = LiveDrive.planAnchor(NOW, PARIS, open).atZone(PARIS)
    check(p.toLocalTime() == LocalTime.of(10, 0) && p.toLocalDate() == LocalDate.parse("2026-10-14")) { "Paris open anchor $p" }
    check(LiveDrive.planAnchor(NOW, DENVER, LiveDrive.Shift.parse("at:14:30")).atZone(DENVER).toLocalTime() == LocalTime.of(14, 30)) { "at:" }
    check(LiveDrive.planAnchor(NOW, DENVER, LiveDrive.Shift.parse("0")) == NOW) { "0 h = the real local clock" }
    check(LiveDrive.planAnchor(NOW, DENVER, LiveDrive.Shift.parse("-2.5")) == NOW.minusSeconds(9_000)) { "hours" }
    check(runCatching { LiveDrive.Shift.parse("noon") }.isFailure) { "garbage refused" }
}

/** Two shoppers, 5 Hz episodes of 10 s, a 60 s gap between the episodes, arriving 30 s apart. */
private fun synthetic(planStart: Long): Map<String, List<ImpressionOracle.Sample>> {
    fun shopper(t0: Long): List<ImpressionOracle.Sample> {
        val ep1 = (0 until 50).map { ImpressionOracle.Sample(t0 + it * 200L, Pt(1000.0 + it, 2000.0), Pt(0.0, 1.0)) }
        val ep2 = (0 until 50).map { ImpressionOracle.Sample(t0 + 70_000L + it * 200L, Pt(5000.0 + it, 2000.0), Pt(0.0, 1.0)) }
        return ep1 + ep2
    }
    return linkedMapOf("cust-2026-10-14-0" to shopper(planStart), "cust-2026-10-14-1" to shopper(planStart + 30_000L))
}

private fun wiresSynthetic() {
    val planStart = LiveDrive.planAnchor(NOW, DENVER, LiveDrive.Shift.Open).toEpochMilli()
    val streams = synthetic(planStart)
    for (sp in LiveDrive.SPEEDS) {
        val c = LiveDrive.Clock(NOW.toEpochMilli(), planStart, sp)
        val w = LiveDrive.storeDayWires("dec-us-denver", DENVER, streams, c, LiveDrive.Dwell.SCALED)
        val one = w.filter { it.objectId.endsWith("-0") && it.sample != null }
        val gaps = one.zipWithNext { a, b -> b.dueMs - a.dueMs }
        val inEpisode = gaps.filter { it <= 1_000 }
        check(
            inEpisode.isNotEmpty() && inEpisode.all {
                it == 200L
            },
        ) { "${sp}x: in-episode wall spacing must stay 200 ms (5 Hz), got ${inEpisode.distinct()}" }
        val span = one.last().dueMs - one.first().dueMs
        check(abs(span - (79_800L / sp)) <= 200) { "${sp}x: a 79.8 s visit must take ${79_800 / sp} ms on the wall, took $span" }
        check(w.first().dueMs == NOW.toEpochMilli()) { "the first shopper of an open-shift day arrives at now" }
        val arrivals = w.filter { it.seq == 0 }.map { it.dueMs - NOW.toEpochMilli() }
        check(arrivals == listOf(0L, 30_000L / sp)) { "${sp}x arrivals $arrivals" }
        check(w.filter { it.sample == null }.size == 2 && w.groupBy { it.objectId }.values.all { it.maxBy { x -> x.dueMs }.sample == null }) {
            "each shopper ends with one eviction"
        }
        check(w.zipWithNext().all { (a, b) -> a.dueMs <= b.dueMs }) { "wires sorted by due" }
    }
    val c1 = LiveDrive.Clock(NOW.toEpochMilli(), planStart, 1)
    val scaled = LiveDrive.storeDayWires("dec-us-denver", DENVER, streams, c1, LiveDrive.Dwell.SCALED)
    val real = LiveDrive.storeDayWires("dec-us-denver", DENVER, streams, c1, LiveDrive.Dwell.REAL)
    check(scaled.map { it.dueMs to it.objectId } == real.map { it.dueMs to it.objectId }) { "at 1x the two dwell modes are the same timeline" }
}

private fun wiresReal() {
    val planStart = LiveDrive.planAnchor(NOW, DENVER, LiveDrive.Shift.Open).toEpochMilli()
    val c = LiveDrive.Clock(NOW.toEpochMilli(), planStart, 5)
    val w = LiveDrive.storeDayWires("dec-us-denver", DENVER, synthetic(planStart), c, LiveDrive.Dwell.REAL)
    val one = w.filter { it.objectId.endsWith("-0") && it.sample != null }
    check(one.size == 100) { "real mode publishes every sample" }
    val gaps = one.zipWithNext { a, b -> b.dueMs - a.dueMs }
    check(gaps.count { it == 200L } == 98) { "every in-episode delta stays 200 ms" }
    check(gaps.single { it != 200L } == 60_200L / 5) { "the 60.2 s gap is divided by the speed: ${gaps.filter { it != 200L }}" }
    check(w.filter { it.seq == 0 }.map { it.dueMs - NOW.toEpochMilli() } == listOf(0L, 6_000L)) { "arrival 30 s apart at 5x = 6 s" }
}

private fun thinning() {
    val ids = (0 until 2000).map { "live-dec-us-denver-1000-cust-2026-10-14-$it" }
    val kept = ids.count { LiveDrive.keepShopper(it, 0.5) }
    check(kept in 900..1100) { "keep 0.5 kept $kept of 2000" }
    check(ids.all { LiveDrive.keepShopper(it, 0.5) == LiveDrive.keepShopper(it, 0.5) }) { "deterministic" }
    check(ids.all { LiveDrive.keepShopper(it, 1.0) }) { "keep 1 keeps everyone" }
    val planStart = LiveDrive.planAnchor(NOW, DENVER, LiveDrive.Shift.Open).toEpochMilli()
    val w = LiveDrive.storeDayWires(
        "dec-us-denver",
        DENVER,
        (0 until 400).associate { "cust-2026-10-14-$it" to synthetic(planStart + it * 1_000L).values.first() },
        LiveDrive.Clock(NOW.toEpochMilli(), planStart, 1),
        LiveDrive.Dwell.SCALED,
    )
    val thin = LiveDrive.thin(w, 0.25)
    val byShopper = w.groupBy { it.objectId }.mapValues { it.value.size }
    thin.groupBy { it.objectId }.forEach { (id, ws) -> check(ws.size == byShopper.getValue(id)) { "$id was thinned in part" } }
    val load = LiveDrive.Load.of(w)
    check(LiveDrive.keepFraction(load, 1e9, 1e9) == 1.0) { "under both caps → keep 1" }
    val k = LiveDrive.keepFraction(load, load.peakEventsPerS / 4, 1e9)
    check(abs(k - 0.25) < 1e-9) { "events cap at a quarter of the peak → keep 0.25, got $k" }
    val k2 = LiveDrive.keepFraction(load, 1e9, load.peakArrivalsPerMin / 2.0)
    check(abs(k2 - 0.5) < 1e-9) { "arrivals cap at half → keep 0.5, got $k2" }
}

private fun edges(keys: ChainKeys) {
    val denver = keys.store("dec-us-denver")
    val paris = keys.store("dec-fr-paris")
    check(LiveDrive.KNOWN_EDGES.getValue("dec-us-denver").spaceId == denver.salesFloorSpaceId) { "the Denver edge is bound to Denver's sales floor" }
    check(LiveDrive.KNOWN_EDGES.keys == setOf("dec-us-denver")) { "one twin edge today" }
    // a mis-typed override that points Paris at Denver's edge must not publish Paris on Denver's floor
    val wrong = LiveDrive.Settings.parseEdges("dec-fr-paris=nats://192.168.55.29:4223|edge-twin-denver|${denver.salesFloorSpaceId}")
    check(wrong.getValue("dec-fr-paris").spaceId != paris.salesFloorSpaceId) { "the override test lost its premise" }
    check(LiveDrive.edgeProblem(wrong, "dec-fr-paris", paris.salesFloorSpaceId)?.contains("refused") == true) {
        "Paris on Denver's edge must be refused"
    }
    check(LiveDrive.edgeProblem(LiveDrive.KNOWN_EDGES, "dec-fr-paris", paris.salesFloorSpaceId)?.contains("no twin edge") == true) {
        "Paris has no edge"
    }
    check(LiveDrive.edgeProblem(LiveDrive.KNOWN_EDGES, "dec-us-denver", denver.salesFloorSpaceId) == null) { "Denver publishes" }
    val office = LiveDrive.Settings.parseEdges("dec-us-denver=nats://192.168.55.29:4222|edge-itx-office|0efb9aaa-0000-0000-0000-000000000000")
    check(LiveDrive.edgeProblem(office, "dec-us-denver", denver.salesFloorSpaceId)?.contains("OFFICE") == true) { "the office space is refused" }
}

private fun denverPlan(keys: ChainKeys) {
    val store = keys.store("dec-us-denver")
    val date = LocalDate.parse("2026-10-14")
    val scale = LiveDrive.liveScale(date, null)
    val streams = ScenarioRun(storeCode = "dec-us-denver").streamsFor(date, LiveDrive.daySeed(LiveDrive.HISTORY_SEED, date), scale, store.zone)
    val planStart = LiveDrive.planAnchor(NOW, store.zone, LiveDrive.Shift.Open).toEpochMilli()
    for (sp in listOf(1, 5)) {
        val c = LiveDrive.Clock(NOW.toEpochMilli(), planStart, sp)
        val w = LiveDrive.storeDayWires("dec-us-denver", store.zone, streams, c, LiveDrive.Dwell.SCALED, planStart)
        check(w.isNotEmpty() && w.first().dueMs >= NOW.toEpochMilli()) { "nothing published before the clock starts" }
        val first = w.first { it.seq == 0 }
        val local = Instant.ofEpochMilli(c.planOf(first.dueMs)).atZone(store.zone).toLocalTime()
        check(first.objectId.startsWith("live-dec-us-denver-%02d%02d-cust-2026-10-14-".format(local.hour, local.minute))) {
            "object id must carry the plan time: ${first.objectId} vs $local"
        }
        val load = LiveDrive.Load.of(w)
        val day = (w.last().dueMs - w.first().dueMs) / 60_000
        println(
            "      denver $date ${sp}x scale $scale: ${w.count {
                it.seq == 0
            }} shoppers · ${w.count { it.sample != null }} samples over $day wall min · " +
                "peak ${"%.1f".format(load.peakEventsPerS)} ev/s · ${load.peakArrivalsPerMin} arrivals/min · keep " +
                "%.3f".format(
                    LiveDrive.keepFraction(load, LiveDrive.DEFAULT_MAX_EVENTS_PER_S.toDouble(), LiveDrive.DEFAULT_MAX_ARRIVALS_PER_MIN.toDouble()),
                ),
        )
        if (sp == 1) {
            check(load.peakEventsPerS < LiveDrive.DEFAULT_MAX_EVENTS_PER_S && load.peakArrivalsPerMin < LiveDrive.DEFAULT_MAX_ARRIVALS_PER_MIN) {
                "Denver alone at 1x must sit under both caps"
            }
        }
    }
}
