package com.m8trx.twin.storeday

import com.fasterxml.jackson.databind.JsonNode
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.history.ChainKeys
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Slice 2c checks of `storeDaySelfTest`: weekly planogram periods (directives, resets) and weekly counts, per store
 * ([checkPeriods]) and across the chain ([checkPeriodsChain]). Offline; writes only under `build/storeday-selftest/`.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.storeday.StoreDayPeriodsSelfTest")
private val FROM_DATE: LocalDate = LocalDate.of(2026, 11, 2)
private const val DAYS = 28
private val OUT_DIR: Path = Path.of("build/storeday-selftest")
private val DENVER_PERIODS: Path = Path.of("reference/data/chain/stores/dec-us-denver/model/periods")

/** One store's 28 days, with the tally the report quotes. */
private class Run(val sim: StoreDaySim, val results: List<DayResult>, val tally: RunTally, val wireDigest: String)

private fun run28(site: String, knobs: Knobs = Knobs()): Run {
    val sim = buildSim(site, loadCalibration(site), knobs)
    val seed = sim.behavior.params.seed
    val state = StoreState.initial(sim.model)
    val tally = RunTally(sim)
    val md = MessageDigest.getInstance("SHA-256")
    val rs = (0 until DAYS).map { i ->
        val r = sim.simulate(state, FROM_DATE.plusDays(i.toLong()), seed)
        tally.add(r)
        r.wire.forEach { md.update("${it.dueMs}|${it.kind}|${it.payload}\n".toByteArray()) }
        r
    }
    return Run(sim, rs, tally, md.digest().take(8).joinToString("") { "%02x".format(it) })
}

private val WireEvent.node: JsonNode get() = payload as JsonNode

private val cache = HashMap<String, Run>()

private fun runOf(site: String) = cache.getOrPut(site) { run28(site) }

fun checkPeriods(site: String) {
    log.info("════ {} · planogram periods, resets and counts (2c) ════", site)
    val run = runOf(site)
    val sim = run.sim
    val model = sim.model
    val keys = ChainKeys.load().store(site)
    val names = keys.zones.filter { it.zoneType == "fixture" }.map { it.name }.toSet()
    val allNames = keys.zones.map { it.name }.toSet()
    val starts = (0 until DAYS / 7).map { FROM_DATE.plusDays(7L * it) }
    val plans = starts.map { sim.periods.periodOf(it) }

    // ── 1. the directives ──────────────────────────────────────────────────────────────────────────────
    log.info("[2c-1] directive payloads")
    val directives = plans.map { sim.periods.directiveJson(it) }
    val ids = directives.map { it.path("external_directive_id").asText() }
    check(
        "one directive id per store-period, in the PLN-<slug>-<yyyymmdd> form",
        ids.toSet().size == ids.size && ids.all { Regex("PLN-$site-\\d{8}").matches(it) },
        ids.joinToString(", "),
    )
    check(
        "every directive carries the required fields (name, targets, id, effective and expiry date)",
        directives.all { d ->
            d.path("name").asText().isNotBlank() && d.path("targets").size() > 0 && d.path("external_directive_id").asText().isNotBlank() &&
                d.path("effective_date").asText().isNotBlank() && d.path("expiry_date").asText().isNotBlank()
        },
        "${directives.size} directives, ${directives.first().path("targets").size()} targets in the first",
    )
    check(
        "expiry is after effective; effective is the period's Monday; each period expires on the next one's first day",
        directives.indices.all { i ->
            val e = LocalDate.parse(directives[i].path("effective_date").asText())
            val x = LocalDate.parse(directives[i].path("expiry_date").asText())
            x.isAfter(e) && e == starts[i] && e.dayOfWeek == DayOfWeek.MONDAY && x == e.plusDays(7) &&
                (i + 1 >= directives.size || directives[i + 1].path("effective_date").asText() == x.toString())
        },
        starts.joinToString(", ") { "$it..${it.plusDays(7)}" },
    )
    val badTargets = directives.sumOf { d ->
        d.path("targets").count { t ->
            !(
                t.path("site_id").asText().isNotBlank() && t.path("raw_fixture_code").asText() in allNames &&
                    t.path("raw_item_identifier").asText().matches(Regex("\\d{13}")) && t.path("required_quantity").asInt(-1) >= 1 &&
                    t.path("position_sequence").isInt && t.path("facing_count").asInt(0) >= 1 && t.path("display_level").isInt
                )
        }
    }
    val notFixtureNames = directives.sumOf { d -> d.path("targets").count { it.path("raw_fixture_code").asText() !in names } }
    check(
        "every target has site, a fixture NAME from the keys export, a 13-digit EAN, quantity >= 1, position, facings and level",
        badTargets == 0 && notFixtureNames == 0,
        "${directives.sumOf {
            it.path("targets").size()
        }} targets over ${plans.size} periods: $badTargets malformed, $notFixtureNames not a keys fixture name",
    )
    check(
        "a directive covers the whole plan: one target per plan line, never the old 28-line curated slice",
        plans.indices.all { directives[it].path("targets").size() == plans[it].lines.size } && plans.all { it.lines.size == model.lines.size },
        "${model.lines.size} lines each period",
    )

    // ── 2. the plan changes ────────────────────────────────────────────────────────────────────────────
    log.info("[2c-2] period plans")
    check(
        "the anchor period is the Phase 1 plan unchanged",
        plans.first().lines === model.lines && plans.first().linesChanged == 0,
        "week of $FROM_DATE",
    )
    check(
        "every later period changes some lines and keeps every level's facings (a level is full)",
        plans.drop(1).all { it.linesChanged > 0 } && plans.zipWithNext().all { (a, b) ->
            fun levels(p: PeriodPlan) = p.lines.groupBy { it.fixture to it.level }.mapValues { (_, ls) -> ls.sumOf { it.facings } }
            levels(a) == levels(b)
        },
        "lines changed ${plans.joinToString("/") { it.linesChanged.toString() }}",
    )
    check(
        "plans are deterministic and memoised: a fresh generator reproduces every digest",
        run28Plans(site, starts) == plans.map { it.digest },
        plans.joinToString(", ") { it.digest.take(6) },
    )
    if (site == "dec-us-denver") {
        val sample = plans.all { p ->
            val f = DENVER_PERIODS.resolve("${p.start}.json")
            Files.exists(f) && Files.readString(f) == sim.periods.periodJson(p)
        }
        check(
            "the committed 4-week Denver sample equals what the seed generates",
            sample,
            "reference/data/chain/stores/dec-us-denver/model/periods/*.json",
        )
    }

    // ── 3. resets in the store day ─────────────────────────────────────────────────────────────────────
    log.info("[2c-3] resets")
    val issueDays = run.results.filter { it.issued != null }
    check(
        "one directive is issued per period, on the period's first open day",
        issueDays.size == plans.size && issueDays.map { it.issued!!.start } == starts,
        issueDays.joinToString(", ") { it.date.toString() },
    )
    val orderOk = issueDays.all { r ->
        val pg = r.wire.filter { it.kind == "planogram" }
        val firstMove = r.wire.filter { it.kind == "move" || it.kind == "receive" }.minOfOrNull { it.dueMs } ?: Long.MAX_VALUE
        pg.size == 1 && pg.first().dueMs < firstMove && pg.first().node == sim.periods.directiveJson(r.issued!!)
    }
    check(
        "the planogram event is due before the first reset move or receipt, and carries the period's exact directive",
        orderOk,
        "${issueDays.size} issue days",
    )
    val resetDays = run.results.filter { it.resetFrom != null }
    check(
        "periods after the first reset the floor: there are reset moves, all after the directive and before the day opens",
        resetDays.size == plans.size - 1 && resetDays.all { r ->
            val rm = r.moves.filter { it.reason.startsWith("reset-") }
            val pgDue = r.wire.first { it.kind == "planogram" }.dueMs
            val open = java.time.ZonedDateTime.of(r.date, r.plan.shape!!.open, java.time.ZoneId.of(model.timezone)).toInstant().toEpochMilli()
            rm.isNotEmpty() && rm.all { it.dueMs > pgDue && it.dueMs < open }
        },
        "${resetDays.size} reset days, ${resetDays.joinToString("/") { d ->
            d.moves.filter { it.reason.startsWith("reset-") }.sumOf { it.epcs.size }.toString()
        }} units",
    )
    val unfilledCap = resetDays.sumOf { r -> r.unfilled.values.count { it == "capacity" } }
    val emptyCells = resetDays.sumOf { r -> r.required.count { (s, q) -> q > 0 && (r.openOnHand[s] ?: 0) == 0 } }
    val recorded = resetDays.sumOf { r -> r.unfilled.size }
    check(
        "after a reset day's pre-open window every planned cell holds its EAN, or is a recorded stock-out (nothing anywhere)",
        unfilledCap == 0 && emptyCells == recorded,
        "$emptyCells empty planned cells at open, $recorded recorded as no-stock, $unfilledCap held back by staff capacity",
    )
    val leftover = resetDays.sumOf { r ->
        r.resetFrom!!.required.keys.filter { (r.required[it] ?: 0) == 0 }.count { (r.openOnHand[it] ?: 0) > 0 }
    }
    check(
        "a style dropped from a fixture is off it by opening (displaced to the back room or moved to its new fixture)",
        leftover == 0,
        "$leftover slots still holding a dropped style",
    )
    val conserved = run.results.all { it.startUnits + it.receivedEpcs.size == it.endUnits + it.saleUnits.size }
    check("units are conserved through the resets", conserved, "start + received = end + sold on all $DAYS days")
    check(
        "the wire plan is in due order on every day, resets included",
        run.results.all { r -> r.wire.zipWithNext().all { (a, b) -> a.dueMs <= b.dueMs } },
        "${run.results.sumOf { it.wire.size }} events",
    )

    // ── 4. weekly counts ───────────────────────────────────────────────────────────────────────────────
    log.info("[2c-4] weekly counts")
    val counts = run.results.flatMap { it.counts }
    val floor = plans.first().fixtures
    val counted = counts.flatMap { c -> c.pulls.map { it.fixture } }
    check(
        "one count per week",
        counts.size == plans.size && counts.map {
            PeriodPlans.periodStart(it.date)
        } == starts,
        counts.joinToString(", ") { it.date.toString() },
    )
    check(
        "the counts cover every floor fixture within 4 weeks, each once, a quarter at a time",
        counted.toSet() == floor.toSet() && counted.size == floor.size,
        "${counted.toSet().size} of ${floor.size} fixtures, ${counts.joinToString("/") { it.pulls.size.toString() }} per count",
    )
    check(
        "a count falls on Tuesday to Thursday (or the next open day) and in the day's quietest usable hour",
        counts.all { c ->
            val r = run.results.first { it.date == c.date }
            val curve = r.plan.shape!!.curve
            val h = curve.indices.filter { it <= curve.size - 3 }.minWith(compareBy({ curve[it] }, { it }))
            val open = java.time.ZonedDateTime.of(c.date, r.plan.shape.open, java.time.ZoneId.of(model.timezone)).toInstant().toEpochMilli()
            c.date.dayOfWeek in DayOfWeek.TUESDAY..DayOfWeek.THURSDAY && c.startMs == open + h * 3_600_000L
        },
        counts.joinToString(", ") { "${it.date} ${it.date.dayOfWeek.name.take(3)}" },
    )
    val countEvents = run.results.flatMap { r -> r.wire.filter { it.kind == "count" } }
    val pullEvents = countEvents.filter { it.node.path("step").asText() == "pull" }
    val tagsOnWire = pullEvents.sumOf { it.node.path("body").path("tags").size() }
    val readsTruth = counts.sumOf { c -> c.pulls.sumOf { it.epcs } }
    check(
        "count events have the StockDrive shape (open, one pull per frame, close) and carry exactly the units the truth had on each fixture",
        countEvents.map { it.node.path("step").asText() }.toSet() == setOf("open", "pull", "close") && tagsOnWire == readsTruth &&
            pullEvents.all { shapeOk(it.node.path("body")) } &&
            countEvents.filter { it.node.path("step").asText() == "open" }.all { it.node.path("body").path("scopeZoneIds").size() > 0 },
        "${countEvents.size} events, $tagsOnWire tags read = $readsTruth units on the counted fixtures",
    )

    // ── 5. compliance truth ────────────────────────────────────────────────────────────────────────────
    log.info("[2c-5] compliance truth")
    val dir = OUT_DIR.resolve("$site-periods")
    run.tally.periodStats.values.forEach { PeriodTruth.write(dir.resolve(it.start.toString()), it.rows) }
    val fileRows = run.tally.periodStats.values.flatMap { ps ->
        Files.readAllLines(dir.resolve(ps.start.toString()).resolve("compliance_truth.csv")).drop(1).map { it.split(",") }
    }
    check(
        "compliance truth is required minus on hand on every row, for every period, as written",
        fileRows.isNotEmpty() && fileRows.all { it[5].toInt() - it[6].toInt() == it[7].toInt() },
        "${fileRows.size} rows over ${run.tally.periodStats.size} periods",
    )
    val ends = run.tally.periodStats.values.map { ps ->
        val req = ps.plan.required
        ps.rows.filter { it.point == "period_end" }.all { (req[Slot(it.fixture, it.ean)] ?: 0) == it.required }
    }
    check("each period's rows carry that period's requirement (not the Phase 1 plan's)", ends.all { it }, "${ends.size} periods")
    val countRowsOk = run.tally.periodStats.values.all { ps ->
        val reads = ps.counts.sumOf { c -> c.pulls.sumOf { it.epcs } }
        ps.countRows.sumOf { it.onHand } == reads
    }
    check("the on-hand in the count rows is exactly what the count read", countRowsOk, "count rows sum to the units read")
    val cc = run.tally.countCompliance()
    check(
        "simulated compliance at count time is reported and plausible (most lines at their requirement, none of the counts empty)",
        cc.lines > 0 && cc.meanPct in 20.0..100.0 && cc.atLeast60Pct >= cc.meanPct,
        "${"%.1f".format(
            cc.meanPct,
        )} % of lines at their requirement, ${"%.1f".format(cc.atLeast60Pct)} % at 60 %, worst ${cc.worstFixture} ${"%.1f".format(cc.worstPct)} %",
    )

    // ── 6. determinism, and the switch ─────────────────────────────────────────────────────────────────
    log.info("[2c-6] determinism")
    val again = run28(site)
    check("a second 28-day run reproduces every wire event", again.wireDigest == run.wireDigest, run.wireDigest)
    val off = run28(site, Knobs(periods = false))
    check(
        "periods = false is the 2b day: no directive, no reset, no count on the wire",
        off.results.none { r -> r.wire.any { it.kind == "planogram" || it.kind == "count" } || r.moves.any { it.reason.startsWith("reset-") } },
        "${off.results.sumOf { it.saleUnits.size }} units sold in 28 days",
    )
}

private fun shapeOk(body: JsonNode): Boolean = body.path("countId").isTextual && body.path("pullId").isTextual && body.path("zoneId").isTextual &&
    body.path("startedAt").isTextual && body.path("endedAt").isTextual && body.path("tags").isArray &&
    body.path("tags").all { it.has("identifier") && it.has("readCount") && it.has("rssiBest") }

private fun run28Plans(site: String, starts: List<LocalDate>): List<String> {
    val sim = buildSim(site, loadCalibration(site))
    return starts.map { sim.periods.periodOf(it).digest }
}

/** What the ten stores share: ids are unique chain-wide, and the counts everywhere are the same kind of event. */
fun checkPeriodsChain() {
    log.info("════ the chain · periods and counts ════")
    val all = StoreState.STORE_SLUGS.map { runOf(it) }
    val ids = all.flatMap { r -> r.results.mapNotNull { it.issued }.map { r.sim.periods.directiveId(it.start) } }
    check("directive ids are unique across the ten stores and four weeks", ids.size == 40 && ids.toSet().size == 40, "${ids.size} ids")
    check(
        "every store issued 4 periods and ran 4 counts covering its floor",
        all.all { r ->
            r.tally.periodStats.values.count { it.issued } == 4 &&
                r.results.flatMap { it.counts }.flatMap { c -> c.pulls.map { it.fixture } }.toSet() ==
                r.sim.periods.periodOf(FROM_DATE).fixtures.toSet()
        },
        all.joinToString(", ") { "${it.sim.model.slug.removePrefix("dec-")} ${it.results.sumOf { r -> r.counts.size }}" },
    )
    val seeds = all.flatMap { r -> r.tally.periodStats.values.drop(1).map { r.sim.model.slug to it.plan.digest } }
    check(
        "stores do not share a plan digest (the seed includes the store)",
        seeds.map {
            it.second
        }.toSet().size == seeds.size,
        "${seeds.size} plans",
    )
    val cc = all.map { it.tally.countCompliance() }
    check(
        "the floor has gaps the platform's compliance can find: most stores are below 100 % at count time, and a fixture somewhere is badly short",
        cc.count { it.meanPct < 99.9 } >= 8 && cc.any { it.worstPct < 50.0 },
        cc.joinToString(", ") { "%.0f".format(it.meanPct) } + " % mean, worst " + "%.0f".format(cc.minOf { it.worstPct }) + " %",
    )
    val txt = ConnectMappers.snake.writeValueAsString(mapOf("stores" to all.map { it.sim.model.slug }))
    check("the report names every store", txt.contains("dec-us-denver") && all.size == 10, "10 stores")
}
