package com.m8trx.twin.ops

import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.history.HistoryCsv
import com.m8trx.twin.history.HistoryGenerator
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
        "stock: Denver full floor covers every stocked wall fixture (6-10 missing, over from the stockroom)" to { stockFullFloor(chain, keys) },
        "fixmoves: N per store-week, Mon-Sat, distinct walls, bounded offsets, stable through the week" to { fixMoves(keys) },
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
