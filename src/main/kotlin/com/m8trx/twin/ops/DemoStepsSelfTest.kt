package com.m8trx.twin.ops

import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.history.HistoryCsv
import com.m8trx.twin.history.HistoryGenerator
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64

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
        "fixmoves: movable types only, every store every day, <= 2, deterministic, the Denver gondolas never" to { fixMoves(chain, keys) },
        "fixmoves: geometry - own region, free slot, aisle + gate clearance, inside the room, >= 0.75 m, no slot = null" to
            { fixMovesGeometry(chain, keys) },
        "fixmoves: the hold - only the store's own local date, from 07:30 local" to { fixMovesHold(chain) },
        "fixrevert: sources, agreement, the default targets are the two Denver gondolas" to { fixRevert(keys) },
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

private fun fixMoves(chain: Path, keys: ChainKeys) {
    val categories = keys.stores.flatMap { s -> FixtureMoves.Floor.load(chain, s.slug).fixtures.values.map { it.category } }.toSet()
    check(categories.containsAll(FixtureMoves.MOVABLE_CATEGORIES)) {
        "a movable category the layouts do not have: ${FixtureMoves.MOVABLE_CATEGORIES - categories}"
    }
    listOf("gondola_front", "gondola_back", "perimeter_east", "perimeter_west", "accessories_wall", "checkout", "impulse_rack", "gps_case").forEach {
        check(it in categories && it !in FixtureMoves.MOVABLE_CATEGORIES) { "$it must be a fixed type" }
    }
    val denver = FixtureMoves.Floor.load(chain, "dec-us-denver")
    check(!FixtureMoves.isMovable(denver, "GF-R2-U2") && !FixtureMoves.isMovable(denver, "GB-R3-U5")) { "the two Denver gondolas are movable?" }
    val start = LocalDate.parse("2026-10-12")
    val perStore = LinkedHashMap<String, Int>()
    for (s in keys.stores) {
        val floor = FixtureMoves.Floor.load(chain, s.slug)
        val movable = FixtureMoves.movable(s, floor)
        check(movable.isNotEmpty()) { "${s.slug} has no movable fixture — it would sit out every day" }
        perStore[s.slug] = movable.size
        for (d in 0 until 14) {
            val date = start.plusDays(d.toLong())
            val one = FixtureMoves.forDate(s, floor, date, SEED, 1)
            check(one.size == 1) { "${s.slug} $date: ${one.size} moves at perDay=1 — every store moves every day" }
            val many = FixtureMoves.forDate(s, floor, date, SEED, 9)
            check(many.size == minOf(FixtureMoves.MAX_PER_DAY, movable.size)) {
                "${s.slug} $date: ${many.size} moves — the cap is ${FixtureMoves.MAX_PER_DAY}"
            }
            check(many.map { it.code }.toSet().size == many.size) { "a fixture moved twice in a day" }
            (one + many).forEach { m ->
                check(floor.fixtures.getValue(m.code).category in FixtureMoves.MOVABLE_CATEGORIES) {
                    "${m.code} is a ${floor.fixtures.getValue(m.code).category}"
                }
                check(m.reason.startsWith("twin: ") && m.region == floor.fixtures.getValue(m.code).region) { "reason / region: $m" }
            }
            check(one == FixtureMoves.forDate(s, floor, date, SEED, 1)) { "not deterministic" }
        }
    }
    println("      movable fixtures per store: $perStore")
    val ring = listOf(listOf(listOf(0.0, 0.0, 0.0), listOf(1000.0, 0.0, 0.0), listOf(1000.0, 500.0, 0.0), listOf(0.0, 0.0, 0.0)))
    check(FixtureMoves.translate(ring, 300.0, -50.0)[0][1] == listOf(1300.0, -50.0, 0.0)) { "translate keeps z, moves x/y" }
}

private fun fixMovesHold(chain: Path) {
    fun cfg(date: String, now: String, past: Boolean = false, future: Boolean = false) = OpsConfig(
        apiBase = "http://127.0.0.1:1", hasuraUrl = "http://127.0.0.1:1", chainDir = chain, stateDir = Path.of("/nonexistent-twin-state"),
        date = LocalDate.parse(date), seed = SEED, live = true, allowFuture = future, allowPast = past, stores = listOf("dec-us-denver"),
        password = null, now = Instant.parse(now),
    )
    // Denver is UTC-6 in October: 13:00Z = 07:00 local, 14:00Z = 08:00 local
    check(FixtureMoveDrive(cfg("2026-10-14", "2026-10-14T13:00:00Z")).holdReason("dec-us-denver")?.contains("before 07:30") == true) {
        "07:00 local holds"
    }
    check(FixtureMoveDrive(cfg("2026-10-14", "2026-10-14T14:00:00Z")).holdReason("dec-us-denver") == null) { "08:00 local goes" }
    check(FixtureMoveDrive(cfg("2026-10-15", "2026-10-14T14:00:00Z")).holdReason("dec-us-denver")?.contains("not yet") == true) { "tomorrow holds" }
    check(FixtureMoveDrive(cfg("2026-10-13", "2026-10-14T14:00:00Z")).holdReason("dec-us-denver")?.contains("before") == true) {
        "a past date is refused"
    }
    check(FixtureMoveDrive(cfg("2026-10-13", "2026-10-14T14:00:00Z", past = true)).holdReason("dec-us-denver") == null) { "allowPast lifts it" }
    check(FixtureMoveDrive(cfg("2026-10-15", "2026-10-14T05:00:00Z", future = true)).holdReason("dec-us-denver") == null) { "allowFuture lifts it" }
    // KST 22:57 on Oct 13 (the gondola move Bob saw) was 07:57 Denver on Oct 13: a v2 move for Denver's Oct 14 would wait a day
    check(FixtureMoveDrive(cfg("2026-10-14", "2026-10-13T13:57:00Z")).holdReason("dec-us-denver")?.contains("not yet") == true) {
        "KST-ahead dates wait"
    }
}

private fun fixRevert(keys: ChainKeys) {
    val targets = FixtureRevert.DEFAULT_TARGETS.split(",").map { it.split(":") }
    check(targets.map { it[1] } == listOf("GF-R2-U2", "GB-R3-U5") && targets.all { it[0] == "dec-us-denver" }) { "default targets $targets" }
    targets.forEach { (slug, code) ->
        val z = keys.store(slug).byCode[code] ?: error("$code missing from the key export")
        check(z.name == if (code == "GF-R2-U2") "Gondola R2 Front U2" else "Gondola R3 Back U5") { "$code is '${z.name}'" }
    }
    check(FixtureRevert.envKey("dec-us-denver", "GF-R2-U2") == "M8TRX_REVERT_GEOMETRY_DEC_US_DENVER_GF_R2_U2") { "env key" }
    val a = FixtureRevert.parseCoords("""{"type":"Polygon","coordinates":[[[0,0,0],[100,0,0],[100,50,0],[0,50,0],[0,0,0]]]}""")
    val b = FixtureRevert.parseCoords("[[[0.4,0,0],[100,0,0],[100,50,0],[0,50,0],[0.4,0,0]]]")
    check(FixtureRevert.ringsAgree(a, b) && !FixtureRevert.ringsAgree(a, FixtureMoves.translate(b, 312.0, 0.0))) { "agreement within 1 mm" }
    check(Instant.parse(FixtureRevert.DEFAULT_BEFORE).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalTime().toString() == "22:57") {
        "cutoff = 22:57 KST"
    }
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

    // every store, two weeks, at 1/day and at the cap of 2/day: every store gets its move(s) every day, and every destination is
    // in the fixture's own region, inside the room, >= 0.75 m away, clear of every other fixture by the aisle and of the gate line
    val shifts = ArrayList<Double>()
    val short = ArrayList<String>()
    var total = 0
    for (perDay in listOf(1, 2)) {
        for (s in keys.stores) {
            val floor = FixtureMoves.Floor.load(chain, s.slug)
            for (d in 0 until 14) {
                val date = LocalDate.parse("2026-10-12").plusDays(d.toLong())
                val cands = FixtureMoves.candidates(s, floor, date, SEED)
                val others = floor.fixtures.mapValues { it.value.footprint.ring() }.toMutableMap()
                val day = FixtureMoves.planDay(cands, perDay, { floor.fixtures.getValue(it.code).footprint }, others, { it.code }, floor, null, SEED)
                if (day.size < minOf(perDay, cands.size)) short += "${s.slug} $date ${day.size}/$perDay"
                for ((m, dx, dy) in day) {
                    total++
                    val moved = floor.fixtures.getValue(m.code).footprint.moved(dx, dy)
                    val rest = others.filterKeys { it != m.code }.values
                    check(PolyGeom.within(moved.ring(), floor.regions.getValue(m.region).ring)) { "${s.slug} ${m.code} left its region ${m.region}" }
                    check(PolyGeom.within(moved.ring(), floor.room)) { "${s.slug} ${m.code} left the room" }
                    check(
                        rest.none {
                            PolyGeom.overlaps(moved.inflated(FixtureMoves.CLEARANCE_MM), it)
                        },
                    ) { "${s.slug} ${m.code} lands in the aisle of a fixture" }
                    check(
                        floor.gates.all { g ->
                            Math.abs(moved.cy - g.y) >= moved.reach + FixtureMoves.GATE_CLEARANCE_MM
                        },
                    ) { "${s.slug} ${m.code} blocks the gate" }
                    val sh = Math.hypot(dx, dy)
                    check(sh >= FixtureMoves.MIN_SHIFT_MM) { "a $sh mm shuffle is not a move" }
                    shifts += sh
                }
            }
        }
    }
    check(short.none { it.endsWith("/1") }) { "a store went without its daily move: ${short.filter { it.endsWith("/1") }}" }
    println(
        "      ${keys.stores.size} stores x 14 days at 1/day and 2/day: $total moves; short of the target: ${short.size} store-days ${short.take(
            6,
        )}; " +
            "shifts ${shifts.minOrNull()?.toLong()}-${shifts.maxOrNull()?.toLong()} mm (median ${shifts.sorted().getOrNull(
                shifts.size / 2,
            )?.toLong()})",
    )
    // a region with no room left gives no destination
    val denver = FixtureMoves.Floor.load(chain, "dec-us-denver")
    val m = FixtureMoves.forDate(keys.store("dec-us-denver"), denver, LocalDate.parse("2026-10-14"), SEED, 1).single()
    val full = listOf(denver.regions.getValue(m.region).ring)
    check(FixtureMoves.destination(m, denver.fixtures.getValue(m.code).footprint, denver, full, null, SEED) == null) {
        "a full region still gave a slot"
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
