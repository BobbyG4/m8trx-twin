package com.m8trx.twin.ops

import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.history.HistoryCsv
import com.m8trx.twin.history.HistoryGenerator
import com.m8trx.twin.layer1.FixtureSet
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import kotlin.math.abs

/**
 * `./gradlew demoStepsSelfTest` — offline tests of the four demo-sprint steps' generators (part of `offlineSelfTest`).
 * No network, no credentials, no state dir: every input is built here or read from the committed chain files, and every
 * seed is fixed, so a failure is a change in the generator, never in the world.
 */
fun main() {
    val chain = Path.of("reference/data/chain")
    val keys = ChainKeys.load(chain.resolve(ChainKeys.DEFAULT_PATH.fileName))
    val tests = listOf<Pair<String, () -> Unit>>(
        "stock: plan shape per store-day (1-2 counts, 2-4 fixtures, 2-4 missing, 1-2 over)" to { stockShape(chain, keys) },
        "stock: read sets — missing never read, over homed outside the scope, no blip, frames <= 250" to { stockReadSets(chain, keys) },
        "stock: deterministic, ids stable, a new date or seed changes the plan" to { stockDeterminism(chain, keys) },
        "stock: platform truth - read sets from the platform's locations (drifted units never read at a wall)" to { stockPlatformTruth(chain, keys) },
        "stock: Denver full floor covers every stocked wall fixture (6-10 missing, over from the stockroom)" to { stockFullFloor(chain, keys) },
        "fixmoves: N per store-week, Mon-Sat, distinct walls, bounded offsets, stable through the week" to { fixMoves(keys) },
        "fixmoves: geometry - overlap, boundary, the Lyon GB-R1-U1 / GF-R1-U1 case, every chosen move clean" to { fixMovesGeometry(chain, keys) },
        "ops: login pacing (<= 6/min), JWT exp decode" to { loginPacing() },
        "tasks: backlog, target, top-up, assignees, completion shares, carried" to { taskDay() },
        "traffic9: HISTORY_ONLY=people writes exactly the three people files + MANIFEST" to { traffic9() },
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
    println("demoStepsSelfTest: ${tests.size - failed}/${tests.size} passed")
    if (failed > 0) error("$failed self-test(s) failed")
}

private val SEED = 20261016L
private val DATE: LocalDate = LocalDate.parse("2026-10-14")

private fun stockOf(chain: Path, slug: String): StoreStock = StoreStock.load(chain, Path.of("/nonexistent-twin-state"), slug)

private fun stockShape(chain: Path, keys: ChainKeys) {
    for (s in keys.stores) {
        val plans = StockCounts.plan(s, DATE, SEED, stockOf(chain, s.slug), fullFloor = false)
        check(plans.size in 1..2) { "${s.slug}: ${plans.size} counts" }
        plans.forEach { p ->
            check(p.pulls.size in 2..4) { "${s.slug}: ${p.pulls.size} fixtures" }
            check(p.missing.size in 2..4) { "${s.slug}: ${p.missing.size} missing" }
            check(p.over.size in 1..2) { "${s.slug}: ${p.over.size} over" }
            check(p.observations.size in 1..2 && p.observations.all { o -> o.code in p.pulls.map { it.code } }) { "1-2 notes on counted fixtures" }
            check(p.scopeZoneIds.toSet().size == p.pulls.size) { "scope = the counted fixtures" }
            check(p.endedAt.isAfter(p.startedAt)) { "time runs forward" }
            check(p.startedAt.atZone(s.zone).toLocalDate() == DATE) { "the count is on its store-local date" }
        }
        val fixtures = plans.flatMap { p -> p.pulls.map { it.code } }
        check(fixtures.toSet().size == fixtures.size) { "${s.slug}: a fixture counted twice in a day" }
    }
}

private fun stockReadSets(chain: Path, keys: ChainKeys) {
    for (s in keys.stores) {
        val stock = stockOf(chain, s.slug)
        for (p in StockCounts.plan(s, DATE, SEED, stock, fullFloor = false)) {
            val read = p.pulls.flatMap { it.epcs }
            check(read.toSet().size == read.size) { "an EPC read on two racks (bleed / check)" }
            check(p.missing.none { it.epc in read }) { "a missing unit was read" }
            val scope = p.pulls.map { it.code }.toSet()
            p.missing.forEach { m ->
                check(m.fixture in scope && m.epc in stock.byFixture.getValue(m.fixture)) { "missing ${m.epc} not homed on a counted fixture" }
            }
            p.over.forEach { o ->
                check(o.fromFixture !in scope) { "over ${o.epc} homed inside the scope" }
                check(o.epc in stock.byFixture.getValue(o.fromFixture)) { "over ${o.epc} is not stock of ${o.fromFixture}" }
                check(o.toFixture in scope) { "over read off the scope" }
                check(o.epc in read) { "over ${o.epc} not read" }
            }
            val homeRead = read.toSet() - p.over.map { it.epc }.toSet()
            p.pulls.forEach { pull ->
                val expected = stock.wall.getValue(pull.code).toSet() - p.missing.map { it.epc }.toSet()
                check(pull.epcs.toSet().filter { it in homeRead }.toSet() == expected) { "${pull.code}: read set != stock - missing" }
                val counts = pull.frames.flatMap { f -> f.tags.map { it.readCount } }.sorted()
                val median = counts[counts.size / 2]
                check(counts.all { it >= 0.2 * median }) { "${pull.code}: a blip (D18 would keep it home)" }
                check(pull.frames.all { it.tags.size <= StockCounts.FRAME_MAX_TAGS }) { "frame over 250 tags" }
                check(pull.frames.map { it.seq } == pull.frames.indices.toList()) { "seq 0..n" }
                pull.frames.forEach { f ->
                    f.tags.forEach { t ->
                        check(!t.firstSeenAt.isBefore(f.startedAt) && !t.lastSeenAt.isAfter(f.endedAt) && !t.lastSeenAt.isBefore(t.firstSeenAt)) {
                            "tag time outside its frame"
                        }
                    }
                }
            }
        }
    }
}

private fun stockDeterminism(chain: Path, keys: ChainKeys) {
    val s = keys.store("dec-us-denver")
    val stock = stockOf(chain, s.slug)
    val a = StockCounts.plan(s, DATE, SEED, stock, false)
    val b = StockCounts.plan(s, DATE, SEED, stock, false)
    check(a == b) { "same inputs, different plan" }
    check(a.first().countId == StockCounts.uuid("twin-stock", s.slug, DATE, SEED, 0)) { "countId is not the name-based id" }
    check(StockCounts.plan(s, DATE.plusDays(1), SEED, stock, false) != a) { "the date does not move the plan" }
    check(StockCounts.plan(s, DATE, SEED + 1, stock, false) != a) { "the seed does not move the plan" }
}

private fun stockFullFloor(chain: Path, keys: ChainKeys) {
    val s = keys.store("dec-us-denver")
    val stock = stockOf(chain, s.slug)
    val plans = StockCounts.plan(s, LocalDate.parse("2026-10-15"), SEED, stock, fullFloor = true)
    check(plans.size == 1) { "${plans.size} counts on the full-floor day" }
    val p = plans.single()
    val zoneCodes = s.fixtures.map { it.code }.toSet()
    val stocked = stock.wall.filter { (c, u) -> c in zoneCodes && u.size >= 4 }.keys
    check(p.pulls.map { it.code }.toSet() == stocked) { "full floor = ${p.pulls.size} fixtures, stocked walls = ${stocked.size}" }
    check(p.missing.size in 6..10) { "${p.missing.size} missing" }
    check(p.over.size in 2..3 || stock.backroom.size < 2) { "${p.over.size} over" }
    check(p.over.all { StoreStock.isBoh(it.fromFixture) }) { "full-floor over must come from the stockroom" }
    println(
        "      denver full floor: ${p.pulls.size} fixtures · ${p.reads} reads · ${p.pulls.sumOf {
            it.frames.size
        }} frames · ${p.missing.size} missing · ${p.over.size} over",
    )
}

private fun fixMoves(keys: ChainKeys) {
    val monday = LocalDate.parse("2026-10-12")
    for (s in keys.stores) {
        val week = FixtureMoves.week(s, monday.plusDays(3), SEED, 3, emptyMap())
        check(week.size == 3) { "${s.slug}: ${week.size} moves" }
        check(week.all { it.date >= monday && it.date <= monday.plusDays(5) && it.date.dayOfWeek != DayOfWeek.SUNDAY }) { "a move outside Mon-Sat" }
        check(week.map { it.code }.toSet().size == 3) { "a wall moved twice in a week" }
        check(week.all { (abs(it.dxMm) + abs(it.dyMm)) in 250.0..500.0 && (it.dxMm == 0.0 || it.dyMm == 0.0) }) { "one axis, 250-500 mm" }
        // the same plan whichever day of the week it is read on
        val daily = (0..6).flatMap { FixtureMoves.forDate(s, monday.plusDays(it.toLong()), SEED, 3, emptyMap()) }
        check(daily == week) { "${s.slug}: the daily reads do not add up to the week" }
    }
    // the bound: a fixture already at +600 mm moves back
    val s = keys.store("dec-us-denver")
    val first = FixtureMoves.week(s, monday, SEED, 3, emptyMap())
    val pinned: Map<String, Pair<Double, Double>> = first.associate { m ->
        m.zoneId to Pair(600.0 * Math.signum(m.dxMm), 600.0 * Math.signum(m.dyMm))
    }
    FixtureMoves.week(s, monday, SEED, 3, pinned).forEach { m ->
        val (cx, cy) = pinned.getValue(m.zoneId)
        check(abs(cx + m.dxMm) <= 600.0 && abs(cy + m.dyMm) <= 600.0) { "offset bound crossed: $m from ($cx,$cy)" }
    }
    val ring = listOf(listOf(listOf(0.0, 0.0, 0.0), listOf(1000.0, 0.0, 0.0), listOf(1000.0, 500.0, 0.0), listOf(0.0, 0.0, 0.0)))
    check(FixtureMoves.translate(ring, 300.0, -50.0)[0][1] == listOf(1300.0, -50.0, 0.0)) { "translate keeps z, moves x/y" }
}

private fun taskDay() {
    val zone = ZoneId.of("America/Denver")
    val d = LocalDate.parse("2026-10-14")
    fun at(day: LocalDate, h: Int): Instant = day.atTime(h, 0).atZone(zone).toInstant()
    val open = listOf(
        TaskDay.Existing("old-1", "replenishment_request", "overdue", "old", "z1", null, at(LocalDate.parse("2026-10-02"), 10)),
        TaskDay.Existing("old-2", "compliance_check", "open", "old", "z2", null, at(LocalDate.parse("2026-10-04"), 18)),
        TaskDay.Existing("new-1", "replenishment_request", "open", "today", "z3", null, at(d, 9)),
        TaskDay.Existing("new-2", "replenishment_request", "open", "today", "z4", null, at(d, 11)),
        TaskDay.Existing("mine", "replenishment_request", "assigned", "today", "z5", "someone", at(d, 12)),
        TaskDay.Existing("yday", "replenishment_request", "open", "yday", "z6", null, at(d.minusDays(1), 12)),
    )
    val mgr = TaskDay.Person("u-m", "Jennifer Anderson", listOf("site-manager"))
    val people =
        listOf(
            mgr,
            TaskDay.Person("u-1", "A", listOf("Floor Associate")),
            TaskDay.Person("u-2", "B", listOf("Floor Associate")),
            TaskDay.Person("u-x", "X", listOf("member")),
        )
    val sold = mapOf("GB-R1-U1" to 7, "GB-R2-U1" to 3, "BR-01" to 9, "XX" to 2)
    val zoneOf = mapOf("GB-R1-U1" to "zg1", "GB-R2-U1" to "zg2")
    val p = TaskDay.plan(
        "dec-us-denver", d, zone, SEED, open, people, mgr, sold, zoneOf,
        listOf(
            "c1",
            "c2",
            "c3",
        ),
        LocalDate.parse("2026-10-05"), false, at(d, 21),
    )
    check(p.cancel.map { it.id } == listOf("old-1", "old-2")) { "backlog = the pre-Oct-5 open tasks: ${p.cancel.map { it.id }}" }
    check(p.target in 3..5) { "target ${p.target}" }
    check(p.assign.map { it.first.id } == listOf("new-1", "new-2")) { "today's unassigned rule-fired tasks first: ${p.assign.map { it.first.id }}" }
    check(p.create.size == minOf(p.target - 2, 2)) { "top-up = the shortfall, only walls with a zone: ${p.create.size}" }
    check(p.create.firstOrNull()?.first?.title?.startsWith("Refill GB-R1-U1") != false) { "the most sold-down wall first" }
    check(
        (
            p.assign.map {
                it.second
            } + p.create.map { it.second }
            ).all { it.userId in setOf("u-m", "u-1", "u-2") },
    ) { "assigned to a manager or floor staff only" }
    val keys = p.assign.map { it.first.id } + p.create.map { it.first.key }
    check(p.completeToday.all { it in keys }) { "completions are today's tasks" }
    check(p.completeToday.size.toDouble() / keys.size in 0.25..0.75) { "same-day share ${p.completeToday.size}/${keys.size}" }
    check(p.completeCarried.size == 2 && p.completeCarried.all { it in setOf("c1", "c2", "c3") }) { "70 % of yesterday's open carry" }
    val again = TaskDay.plan(
        "dec-us-denver", d, zone, SEED, open, people, mgr, sold, zoneOf,
        listOf(
            "c1",
            "c2",
            "c3",
        ),
        LocalDate.parse("2026-10-05"), true, at(d, 21),
    )
    check(again.cancel.isEmpty()) { "the backlog closes once" }
    check(again.copy(cancel = p.cancel) == p) { "deterministic" }
    val capped = TaskDay.plan(
        "dec-us-denver", d, zone, SEED, open, people, mgr, sold, zoneOf, emptyList(),
        LocalDate.parse(
            "2026-10-05",
        ),
        true, at(d, 21), alreadyToday = p.target,
    )
    check(capped.assign.isEmpty() && capped.create.isEmpty()) { "a later pass of the same day must not exceed the day's target" }
    val partial = TaskDay.plan(
        "dec-us-denver", d, zone, SEED, open, people, mgr, sold, zoneOf, emptyList(), LocalDate.parse("2026-10-05"), true, at(d, 21),
        alreadyToday =
        p.target - 1,
    )
    check(partial.assign.size + partial.create.size <= 1) { "the cap counts what earlier passes did" }
}

private fun traffic9() {
    val out = Files.createTempDirectory("traffic9-selftest")
    val gen = HistoryGenerator(
        HistoryGenerator.Params(
            seed = SEED,
            from = LocalDate.parse("2026-10-07"),
            to = LocalDate.parse("2026-10-07"),
            out = out,
            stores = listOf("dec-fr-lyon"),
            threads = 2,
            only = HistoryGenerator.ONLY_PEOPLE,
        ),
    )
    val o = gen.generate()
    val files = Files.list(out).use { s -> s.map { it.fileName.toString() }.toList() }.toSet()
    check(files == setOf("traffic_store_hourly.csv", "traffic_department_daily.csv", "behavioral_fixture_daily.csv", "MANIFEST.csv")) {
        "files: $files"
    }
    check(o.files.getValue("traffic_store_hourly.csv") > 0) { "no traffic rows" }
    val (_, rows) = HistoryCsv.read(out.resolve("traffic_store_hourly.csv"))
    check(rows.all { it[0] == "dec-fr-lyon" && it[1].startsWith("2026-10-07") }) { "a row outside the store/day" }
    println("      lyon 2026-10-07: ${o.files}")
    // a US store's one-day people run is not empty (its local day closes after the next UTC midnight)
    val us = Files.createTempDirectory("traffic9-selftest-us")
    val o2 = HistoryGenerator(
        HistoryGenerator.Params(
            SEED,
            LocalDate.parse("2026-10-07"),
            LocalDate.parse("2026-10-07"),
            us,
            listOf("dec-us-sf"),
            2,
            only = HistoryGenerator.ONLY_PEOPLE,
        ),
    ).generate()
    check(o2.files.getValue("traffic_store_hourly.csv") > 0) { "a one-day SF people run came out empty (cutoff)" }
}

/**
 * The platform's locations, not the twin's record, decide a count's reads. Every 4th wall unit is re-homed to a Back Room zone
 * (as 26 % of Denver's are on the platform) and every plan must then (a) never read one at a wall except as a deliberate
 * `over` unit, (b) pick `missing` only among units the platform has AT the counted wall, (c) take `over` from units the
 * platform has elsewhere (another wall or the Back Room).
 */
private fun stockPlatformTruth(chain: Path, keys: ChainKeys) {
    var drifted = 0
    for (s in keys.stores) {
        val twin = stockOf(chain, s.slug)
        val zoneOfCode = s.zones.associate { it.code to it.zoneId }
        val backZone = "bbbbbbbb-0000-0000-0000-000000000001"
        val backRoom = HashSet<String>()
        var i = 0
        val located = twin.byFixture.flatMap { (code, units) ->
            units.map { u ->
                val z = zoneOfCode[code]
                if (z == null || (!StoreStock.isBoh(code) && i++ % 4 == 0)) {
                    backRoom += u
                    if (z != null) drifted++
                    StoreStock.Located(backZone, u)
                } else {
                    StoreStock.Located(z, u)
                }
            }
        }
        val platform = StoreStock.fromPlatform(s, located, emptySet())
        check(platform.wall.values.flatten().none { it in backRoom }) { "${s.slug}: a Back Room unit counted as wall stock" }
        check(platform.backroom.toSet() == backRoom) { "${s.slug}: back room != the units the platform keeps there" }
        for (p in StockCounts.plan(s, DATE, SEED, platform, fullFloor = false)) {
            val overEpcs = p.over.map { it.epc }.toSet()
            val read = p.pulls.flatMap { it.epcs }.toSet()
            check((read - overEpcs).none { it in backRoom }) { "${s.slug}: a Back Room unit read at a wall (it would be an unplanned over)" }
            p.missing.forEach { m ->
                check(m.epc in platform.wall.getValue(m.fixture)) { "${s.slug}: missing ${m.epc} is not at ${m.fixture} on the platform" }
            }
            p.over.forEach { o ->
                check(o.epc in platform.byFixture.getValue(o.fromFixture) && o.fromFixture !in p.pulls.map { it.code }) {
                    "${s.slug}: over ${o.epc} is not elsewhere on the platform"
                }
            }
        }
    }
    check(drifted > 0) { "the test drifted nothing" }
    println("      drifted $drifted wall units to the Back Room across ${keys.stores.size} stores; none read at a wall")
}

private fun pt(x: Double, y: Double) = PolyGeom.P(x, y)

private fun rect(x0: Double, y0: Double, x1: Double, y1: Double) = listOf(pt(x0, y0), pt(x1, y0), pt(x1, y1), pt(x0, y1))

/** Polygon fixtures (and circles as 32-gons) by code and the sales floor's boundary, from a store's committed layout.json. */
private fun layoutOf(chain: Path, slug: String): Pair<Map<String, List<PolyGeom.P>>, List<PolyGeom.P>> {
    val file = chain.resolve("stores/$slug/layout.json")
    val fixtures = FixtureSet.load(file).fixtures.associate { f ->
        f.code to
            if (f.kind == com.m8trx.twin.layer1.Fixture.Kind.POLYGON) {
                f.ring.dropLast(1).map { pt(it.x, it.y) }
            } else {
                PolyGeom.circle(f.centre.x, f.centre.y, f.radiusMm ?: 1.0, f.radiusMm ?: 1.0)
            }
    }
    val sf = com.m8trx.twin.connect.model.ConnectMappers.camel.readTree(Files.readAllBytes(file)).path("spaces")
        .first { it.path("space_type").asText() == "sales_floor" }.path("footprint_mm")
    return fixtures to rect(0.0, 0.0, sf.path("width").asDouble(), sf.path("depth").asDouble())
}

private fun fixMovesGeometry(chain: Path, keys: ChainKeys) {
    // the primitives
    val a = rect(0.0, 0.0, 1000.0, 500.0)
    check(!PolyGeom.overlaps(a, rect(1000.0, 0.0, 2000.0, 500.0))) { "a shared edge is not an overlap" }
    check(PolyGeom.overlaps(a, rect(900.0, 0.0, 2000.0, 500.0))) { "100 mm x 500 mm of overlap missed" }
    check(PolyGeom.overlaps(a, rect(0.0, 250.0, 1000.0, 750.0))) { "equal-width stacked overlap missed (shared side edges)" }
    check(PolyGeom.overlaps(a, a)) { "identical rectangles" }
    check(Math.abs(PolyGeom.overlapArea(a, rect(900.0, 0.0, 2000.0, 500.0)) - 50_000.0) < 1.0) { "overlap area" }
    check(PolyGeom.within(a, rect(0.0, 0.0, 28500.0, 23000.0)) && !PolyGeom.within(rect(-1.0, 0.0, 999.0, 500.0), rect(0.0, 0.0, 28500.0, 23000.0))) {
        "within"
    }

    // the Lyon case from the review (2026-10-13): GB-R1-U1's planned Oct 17 shift lands on GF-R1-U1
    val (lyonFx, lyonRoom) = layoutOf(chain, "dec-fr-lyon")
    val lyon = keys.store("dec-fr-lyon")
    val week = FixtureMoves.week(lyon, LocalDate.parse("2026-10-12"), SEED, 3, emptyMap())
    val gb = week.firstOrNull { it.code == "GB-R1-U1" } ?: error("the Lyon week no longer holds the GB-R1-U1 move: ${week.map { it.code }}")
    val origin = lyonFx.getValue("GB-R1-U1")
    val planned = PolyGeom.translate(origin, gb.dxMm, gb.dyMm)
    val others = lyonFx.filterKeys { it != "GB-R1-U1" }
    check(PolyGeom.overlapArea(planned, lyonFx.getValue("GF-R1-U1")) > 100_000.0) {
        "expected the planned GB-R1-U1 shift (${gb.dxMm}, ${gb.dyMm}) to land on GF-R1-U1, overlap ${PolyGeom.overlapArea(
            planned,
            lyonFx.getValue("GF-R1-U1"),
        )}"
    }
    val pick = FixtureMoves.choose(gb, origin, 0.0 to 0.0, others.values, lyonRoom)
    check(pick != (gb.dxMm to gb.dyMm)) { "the overlapping planned shift was kept" }
    if (pick != null) {
        val moved = PolyGeom.translate(origin, pick.first, pick.second)
        check(
            others.values.none {
                PolyGeom.overlaps(moved, it)
            } && PolyGeom.within(moved, lyonRoom),
        ) { "the alternative still overlaps or leaves the room" }
    }
    println(
        "      lyon GB-R1-U1: planned (${gb.dxMm}, ${gb.dyMm}) overlaps GF-R1-U1 by ${Math.round(
            PolyGeom.overlapArea(planned, lyonFx.getValue("GF-R1-U1")) / 1e6 * 100,
        ) / 100.0} m2 -> chosen $pick",
    )

    // the demo week's moves at the two proof stores, planned vs chosen against the committed layout
    for (slug in listOf("dec-us-denver", "dec-fr-lyon")) {
        val (fx, room) = layoutOf(chain, slug)
        FixtureMoves.week(keys.store(slug), LocalDate.parse("2026-10-12"), SEED, 3, emptyMap()).forEach { m ->
            val rest = fx.filterKeys { it != m.code }.values
            val pick = FixtureMoves.choose(m, fx.getValue(m.code), 0.0 to 0.0, rest, room)
            println("      $slug ${m.date} ${m.code}: planned (${m.dxMm}, ${m.dyMm}) -> chosen $pick")
        }
    }

    // every store, four weeks of three moves: whatever choose() returns is clean; count how many first choices were not
    var total = 0
    var firstClean = 0
    var rerouted = 0
    var skipped = 0
    for (s in keys.stores) {
        val (fx, room) = layoutOf(chain, s.slug)
        for (w in 0 until 4) {
            for (m in FixtureMoves.week(s, LocalDate.parse("2026-10-12").plusWeeks(w.toLong()), SEED, 3, emptyMap())) {
                val o = fx[m.code] ?: continue
                if (o.size < 3) continue
                total++
                val rest = fx.filterKeys { it != m.code }.values
                val p0 = PolyGeom.translate(o, m.dxMm, m.dyMm)
                if (rest.none { PolyGeom.overlaps(p0, it) } && PolyGeom.within(p0, room)) firstClean++
                val c = FixtureMoves.choose(m, o, 0.0 to 0.0, rest, room)
                if (c == null) {
                    skipped++
                } else {
                    if (c != (m.dxMm to m.dyMm)) rerouted++
                    val moved = PolyGeom.translate(o, c.first, c.second)
                    check(
                        rest.none {
                            PolyGeom.overlaps(moved, it)
                        } && PolyGeom.within(moved, room),
                    ) { "${s.slug} ${m.code}: chosen shift $c is not clean" }
                    check(Math.abs(c.first) + Math.abs(c.second) in 250.0..500.0 && (c.first == 0.0 || c.second == 0.0)) {
                        "$c: one axis, 250-500 mm"
                    }
                }
            }
        }
    }
    println(
        "      ${keys.stores.size} stores x 4 weeks: $total moves - $firstClean first choices clean, $rerouted rerouted, $skipped skipped, every chosen move clean",
    )
    // the bound still holds for a rerouted move: a fixture already 600 mm out never goes further
    val m0 = week.first()
    val c0 = FixtureMoves.choose(m0, lyonFx.getValue(m0.code), 600.0 to 600.0, emptyList(), null)
    check(c0 == null || (Math.abs(600.0 + c0.first) <= 600.0 && Math.abs(600.0 + c0.second) <= 600.0)) {
        "offset bound crossed by a rerouted move: $c0"
    }
}

private fun loginPacing() {
    val p = LoginPacer()
    var t = 1_000_000L
    repeat(LoginPacer.MAX_PER_MINUTE) {
        check(p.waitMs(t) == 0L) { "login ${it + 1} should go at once" }
        p.record(t)
        t += 1000
    }
    // the 7th waits until the first leaves the 60 s window
    val w = p.waitMs(t)
    check(w == 1_000_000L + LoginPacer.WINDOW_MS - t) { "7th login waits $w ms" }
    t += w
    check(p.waitMs(t) == 0L) { "after the wait the login goes" }
    // a quiet minute clears the window
    check(LoginPacer().also { q -> repeat(6) { q.record(0L) } }.waitMs(LoginPacer.WINDOW_MS) == 0L) { "a full minute later the window is empty" }
    fun jwt(payload: String) = "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".s"
    check(OpsClient.expiry(jwt("{\"exp\":1792000000}"))?.epochSecond == 1792000000L) { "exp decode" }
    check(OpsClient.expiry(jwt("{\"sub\":\"x\"}")) == null && OpsClient.expiry("not-a-jwt") == null) { "no exp / not a JWT = null" }
}
