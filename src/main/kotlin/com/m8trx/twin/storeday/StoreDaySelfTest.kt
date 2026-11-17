package com.m8trx.twin.storeday

import com.fasterxml.jackson.databind.JsonNode
import com.m8trx.twin.behavior.StoreStock
import com.m8trx.twin.connect.Sgtin96
import com.m8trx.twin.connect.model.ConnectMappers
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.exitProcess

/**
 * `./gradlew storeDaySelfTest` — offline proof of slice 2a. Every assertion the brief lists, plus the knobs and the
 * hard rules. Writes only under `build/storeday-selftest/`; sends nothing.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.storeday.StoreDaySelfTest")
private var passed = 0
private var failed = 0

private fun check(name: String, ok: Boolean, detail: String) {
    if (ok) passed++ else failed++
    log.info("  {} {} - {}", if (ok) "✓" else "✗", name, detail)
}

private val OUT: Path = Path.of("build/storeday-selftest")
private val FROM: LocalDate = LocalDate.of(2026, 11, 2)
private const val SITE = "dec-us-denver"

private fun digest(vararg files: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    files.forEach { md.update(Files.readAllBytes(it)) }
    return md.digest().take(8).joinToString("") { "%02x".format(it) }
}

/** The tolerance for a department's share: 10 %, widened to two standard deviations of a Poisson count where the department is tiny. */
private fun tol(expectedUnits: Double) = maxOf(0.10, 2.0 / Math.sqrt(maxOf(expectedUnits, 1.0)))

fun main() {
    val cal = loadCalibration(SITE)
    check(
        "calibration file is committed",
        Files.exists(calibrationPath(SITE)) && cal.fixtureScale.isNotEmpty(),
        "${cal.fixtureScale.size} fixture scales, ${cal.departmentBuy.size} department buy scales",
    )
    val sim = buildSim(SITE, cal)
    val model = sim.model
    val seed = sim.behavior.params.seed
    Files.createDirectories(OUT)

    // ── 1. the initial snapshot and the serial ranges ───────────────────────────────────────────────────
    log.info("[1] initial snapshot and serial ranges")
    val init = StoreState.initial(model)
    val planned = model.stockPlan.sumOf { it.qty }
    check("snapshot holds the stock plan", init.size == planned, "${init.size} units = $planned planned (floor + back room)")
    val floorOk = model.stockPlan.filter { it.kind == "floor" }.all { init.count(it.zone, it.ean) == it.qty }
    check("every floor row of the plan is on its fixture", floorOk, "${model.stockPlan.count { it.kind == "floor" }} rows")
    val initSerials = init.locations().flatMap { l -> init.eansAt(l).flatMap { e -> init.epcsAt(l, e) } }.map(Sgtin96::serial)
    check(
        "initial serials in the offline range, clear of seed and receiving blocks",
        initSerials.all { it >= StoreState.INITIAL_BASE && it < StoreState.DELIVERY_BASE } && initSerials.toSet().size == initSerials.size,
        "${initSerials.min()} .. ${initSerials.max()} (seed 1,000..9,999,999; receiving 1e9..1.1e10)",
    )
    val chainEpcs = StoreStock.readCsv(sim.behavior.stock.storeDir.resolve("epcs.csv")).map { it.getValue("epc") }.toHashSet()
    val allInit = init.locations().flatMap { l -> init.eansAt(l).flatMap { e -> init.epcsAt(l, e) } }
    check("no initial EPC collides with the chain's epcs.csv", allInit.none { it in chainEpcs }, "${chainEpcs.size} chain EPCs")
    init.save(OUT.resolve("init"))
    val reloaded = StoreState.load(OUT.resolve("init"))
    check(
        "snapshot save/load round trip",
        reloaded.size == init.size && Files.readAllBytes(Path.of("${OUT.resolve("init")}.csv")).contentEquals(
            run {
                reloaded.save(OUT.resolve("init2"))
                Files.readAllBytes(Path.of("${OUT.resolve("init2")}.csv"))
            },
        ),
        "${reloaded.size} units, byte-identical re-save",
    )

    // ── 2. the 28-day run: replay the wire plan from its files ───────────────────────────────────────────
    log.info("[2] 28 simulated days, wire plan replayed from the files")
    val state = StoreState.initial(model)
    val tally = RunTally(sim)
    val results = ArrayList<DayResult>()
    for (i in 0 until 28) {
        val r = sim.simulate(state, FROM.plusDays(i.toLong()), seed)
        StoreDayOutput.write(OUT.resolve("run/${r.date}"), sim, r)
        tally.add(r)
        results += r
    }
    val loc = HashMap<String, String>()
    init.locations().forEach { l -> init.eansAt(l).forEach { e -> init.epcsAt(l, e).forEach { loc[it] = l } } }
    val sold = HashSet<String>()
    val lastEventDue = HashMap<String, Instant>()
    val truthFixture = results.flatMap { it.saleUnits }.associate { it.epc to it.fixture }
    var violations = 0
    var order = 0
    var prev = Instant.MIN
    var sales = 0
    var wrongPlace = 0
    var soldTwice = 0
    var lateMove = 0
    var events = 0
    for (r in results) {
        Files.newBufferedReader(OUT.resolve("run/${r.date}/wire-plan.jsonl")).useLines { ls ->
            ls.forEach { line ->
                val n = ConnectMappers.snake.readTree(line)
                val due = Instant.parse(n.path("due_utc").asText())
                events++
                if (due < prev) order++
                prev = due
                val kind = n.path("kind").asText()
                val p = n.path("payload")
                when (kind) {
                    "receive" -> p.path("items").forEach {
                        loc[it.path("epc").asText()] = "BR"
                        lastEventDue[it.path("epc").asText()] = due
                    }

                    "move" -> p.path("items").forEach {
                        val e = it.path("epc").asText()
                        if (e in sold || e !in loc) violations++
                        loc[e] = p.path("to_fixture_code").asText()
                        lastEventDue[e] = due
                    }

                    "sale" -> {
                        val epcs = if (p.has("epc_list")) p.path("epc_list").map(JsonNode::asText) else listOf(p.path("epc").asText())
                        epcs.forEach { e ->
                            sales++
                            if (!sold.add(e)) soldTwice++
                            if (loc[e] != truthFixture[e]) wrongPlace++
                            if (lastEventDue[e]?.let { it > due } == true) lateMove++
                        }
                    }
                }
            }
        }
    }
    check("wire plan is ordered by due time", order == 0, "$events events over 28 days, $order out of order")
    check(
        "ordering rule: every unit's last move precedes its sale, none after it",
        violations == 0 && lateMove == 0,
        "$violations moves of sold/unknown units, $lateMove moves due after their unit's sale, $sales sold units",
    )
    check(
        "every sold EPC was on the fixture the shopper took it from, at sale time",
        wrongPlace == 0 && sales == tally.units,
        "$wrongPlace mismatches over $sales sales (tally ${tally.units})",
    )
    check(
        "no EPC sold twice",
        soldTwice == 0 && tally.duplicateSold == 0 && state.soldEpcs().toSet().size == state.soldEpcs().size,
        "${state.soldEpcs().size} sold EPCs, all distinct",
    )
    check(
        "units conserved every day: start + received = end + sold",
        tally.conservationOk && results.all { it.startUnits + it.receivedEpcs.size == it.endUnits + it.saleUnits.size },
        "28 days; whole run ${results.first().startUnits} + ${tally.received} = ${results.last().endUnits} + ${tally.units}",
    )
    check(
        "delivery EPCs are in the delivery serial range",
        results.flatMap { it.receivedEpcs }.all {
            Sgtin96.serial(it) in
                StoreState.DELIVERY_BASE until StoreState.SERIAL_CEILING
        },
        "${tally.received} minted",
    )
    val floorTotal = model.required.values.sum()
    val w1 = tally.weekUnits.getValue(1)
    val w4 = tally.weekUnits.getValue(4)
    check(
        "no drain: week 4 sales >= 80% of week 1",
        w4 >= 0.8 * w1,
        "w1=$w1 w4=$w4 (${"%.0f".format(100.0 * w4 / w1)} %); by week ${tally.weekUnits}",
    )
    check(
        "on hand stays in a band",
        tally.floorCloseMin >= 0.85 * floorTotal && tally.storeUnitsMin >= 0.95 * planned && tally.storeUnitsMax <= 1.05 * planned,
        "floor at close ${tally.floorCloseMin}..${tally.floorCloseMax} of $floorTotal planned; store ${tally.storeUnitsMin}..${tally.storeUnitsMax} of $planned",
    )
    val wdRate = tally.weekdayUnits.sum() / tally.weekdayVisitors.sum()
    val weRate = tally.weekendUnits.sum() / tally.weekendVisitors.sum()
    val ratio = tally.weekendUnits.average() / tally.weekdayUnits.average()
    val modelRatio = tally.weekendVisitors.average() / tally.weekdayVisitors.average()
    check(
        "weekday/weekend ratio as the model",
        Math.abs(ratio / modelRatio - 1.0) <= 0.12 && Math.abs(weRate / wdRate - 1.0) <= 0.12,
        "units/day weekend:weekday ${"%.2f".format(
            ratio,
        )} vs expected visitors ${"%.2f".format(
            modelRatio,
        )}; units per expected visitor weekday ${"%.3f".format(wdRate)} weekend ${"%.3f".format(weRate)}",
    )
    val target = tally.deptTarget()
    val tt = target.values.sum()
    tally.unitsByDept.forEach { (d, n) ->
        val share = n.toDouble() / tally.units
        val want = target.getValue(d) / tt
        check(
            "department share $d within tolerance of demand",
            Math.abs(share / want - 1.0) <= tol(want * tally.units),
            "${"%.1f".format(100 * share)} % vs ${"%.1f".format(100 * want)} % (tol ±${"%.0f".format(100 * tol(want * tally.units))} %)",
        )
    }
    check(
        "stock-outs and lost sales are recorded",
        tally.stockOutEvents > 0 && tally.lost > 0 && tally.lost <= tally.stockOutEvents,
        "${tally.stockOutEvents} stock-out events, ${tally.lost} lost sales",
    )
    check(
        "units sold match the sale lines the shoppers carry",
        results.all { r ->
            r.records.sumOf { it.lines.size } == r.saleUnits.size
        },
        "no basket line without an EPC",
    )

    // ── 3. a fully stocked day sells demand.csv ──────────────────────────────────────────────────────────
    log.info("[3] fully stocked days (hold-out window) vs demand.csv")
    val m = measure(sim, LocalDate.of(2027, 1, 4), 28)
    m.actual.forEach { (d, n) ->
        val t = m.target.getValue(d)
        check(
            "stocked $d units within tolerance of demand.csv",
            Math.abs(n / t - 1.0) <= tol(t),
            "$n vs ${"%.0f".format(t)} (${"%+.1f".format(100 * (n / t - 1))} %, tol ±${"%.0f".format(100 * tol(t))} %)",
        )
    }
    check(
        "stocked total within 10% of demand.csv",
        Math.abs(m.actual.values.sum() / m.target.values.sum() - 1.0) <= 0.10,
        "${m.actual.values.sum()} vs ${"%.0f".format(m.target.values.sum())}",
    )

    // ── 4. truth ledger and wire files of one day ────────────────────────────────────────────────────────
    log.info("[4] truth ledger")
    val d0 = results[5] // a Saturday
    val tdir = OUT.resolve("run/${d0.date}/truth")
    fun rows(f: String) = Files.readAllLines(tdir.resolve(f)).drop(1).filter { it.isNotBlank() }.map { it.split(",") }
    val s = StoreDayOutput.summary(sim, d0)
    check(
        "fixture_hour units and stock-outs add up to the day",
        rows("fixture_hour.csv").sumOf { it[8].toInt() } == s["units_sold"] &&
            rows("fixture_hour.csv").sumOf {
                it[10].toInt()
            } == s["stock_out_events"],
        "units ${s["units_sold"]}, stock-outs ${s["stock_out_events"]}",
    )
    check(
        "fixture_product_day units add up to the day",
        rows("fixture_product_day.csv").sumOf {
            it[3].toInt()
        } == s["units_sold"],
        "${rows("fixture_product_day.csv").size} fixture x EAN rows",
    )
    check(
        "onhand.csv carries plan, open and close for every slot",
        rows("onhand.csv").size >= model.required.size && rows("onhand.csv").sumOf {
            it[4].toInt()
        } == d0.openOnHand.values.sum(),
        "${rows("onhand.csv").size} rows",
    )
    val flows = rows("flows.csv")
    check(
        "flows.csv: every visitor enters and leaves by the door",
        flows.filter { it[2] == "DOOR" }.sumOf { it[4].toInt() } == d0.records.size &&
            flows.filter { it[3] == "DOOR" }.sumOf { it[4].toInt() } == d0.records.size,
        "${flows.size} zone pairs, ${d0.records.size} visitors",
    )
    check(
        "summary.json exists with the headline fields",
        Files.exists(tdir.resolve("summary.json")) &&
            ConnectMappers.snake.readTree(Files.readAllBytes(tdir.resolve("summary.json"))).has("lost_sales"),
        "visitors ${s["visitors"]}, transactions ${s["transactions"]}",
    )

    // ── 5. delivery, staff throughput, quiet hours ──────────────────────────────────────────────────────
    log.info("[5] delivery, staff, quiet hours")
    val zone = ZoneId.of(model.timezone)
    val day2 = results[1]
    val perEan = day2.receivedEpcs.groupingBy { Sgtin96.decodeEan(it) }.eachCount()
    check(
        "deliveries are whole cases (6; bike lines 1)",
        perEan.all { (e, n) ->
            n % model.caseSize(e) == 0
        } && perEan.isNotEmpty(),
        "${perEan.size} EANs, ${day2.receivedEpcs.size} units on day 2",
    )
    val recv = results.flatMap { r -> r.wire.filter { it.kind == "receive" } }
    check(
        "receive requests carry at most 200 EPCs",
        recv.all {
            (it.ids["units"] as Int) <= 200
        },
        "${recv.size} requests, largest ${recv.maxOf { it.ids["units"] as Int }}",
    )
    check(
        "the delivery lands at open minus the lead",
        results.all { r ->
            val open = java.time.ZonedDateTime.of(r.date, java.time.LocalTime.of(10, 0), zone).toInstant().toEpochMilli()
            r.wire.filter { it.kind == "receive" }.minOfOrNull { it.dueMs } == open - model.deliveryLeadMin * 60_000L
        },
        "lead ${model.deliveryLeadMin} min before 10:00 local",
    )
    val knobs = sim.knobs
    val preCap = ((60 - 6 - 1) * 60_000L * knobs.preOpenStockers * knobs.staffUnitsPerHour / 3_600_000.0).toInt()
    check(
        "pre-open stocking respects staff throughput",
        results.all { r ->
            r.moves.filter { it.reason == "stocking" || it.reason == "stray-return" }.sumOf { it.epcs.size } <=
                preCap
        },
        "cap $preCap units/day (${knobs.preOpenStockers} staff x ${knobs.staffUnitsPerHour.toInt()}/h x 53 min)",
    )
    val walkCap = (45 * 60_000L * knobs.topUpStockers * knobs.staffUnitsPerHour / 3_600_000.0).toInt()
    check(
        "top-ups respect staff throughput",
        results.all { r ->
            r.moves.filter { it.reason == "top-up" }.sumOf { it.epcs.size } <=
                knobs.quietHours * walkCap
        },
        "cap ${knobs.quietHours * walkCap} units/day",
    )
    val quietOk = results.all { r ->
        val wk = r.date.dayOfWeek.value >= 6
        val curve = if (wk) sim.behavior.model.traffic.hourlyCurve.weekend else sim.behavior.model.traffic.hourlyCurve.weekday
        val quiet = curve.indices.sortedWith(compareBy({ curve[it] }, { it })).take(knobs.quietHours).map { 10 + it }.toSet()
        r.moves.filter { it.reason == "top-up" }.all { Instant.ofEpochMilli(it.dueMs).atZone(zone).hour in quiet }
    }
    check(
        "top-ups happen only in the quietest hours of the curve",
        quietOk && results.sumOf { r ->
            r.moves.count { it.reason == "top-up" }
        } > 0,
        "${results.sumOf { r -> r.moves.count { it.reason == "top-up" } }} batches, ${knobs.quietHours} quietest hours/day",
    )
    check(
        "a move batch is one (fixture, EAN)",
        results.flatMap {
            it.moves
        }.all { mv -> mv.epcs.all { Sgtin96.decodeEan(it) == mv.ean } },
        "${results.sumOf { it.moves.size }} batches",
    )

    // ── 6. knobs: empty floor, no staff; misplacement ───────────────────────────────────────────────────
    log.info("[6] stock-out and misplacement knobs")
    val bare = StoreState.initial(model)
    for (l in bare.locations().filter { !bare.isBackroomLoc(it) }) {
        for (e in bare.eansAt(l).toList()) while (bare.count(l, e) > 0) bare.place(bare.pickUp(l, e)!!, model.homeRack[e] ?: "BR-01", 0)
    }
    val noStaff = buildSim(SITE, cal, Knobs(preOpenStockers = 0, topUpStockers = 0, misplaceProb = 0.0))
    val emptyDay = noStaff.simulate(bare, FROM, seed)
    check(
        "empty fixtures: nothing sells, every reach is a stock-out",
        emptyDay.saleUnits.isEmpty() && emptyDay.records.sumOf { it.stockOuts.size } > 0 && emptyDay.wire.none { it.kind == "sale" },
        "${emptyDay.records.size} visitors, ${emptyDay.records.sumOf {
            it.stockOuts.size
        }} stock-outs, ${emptyDay.records.sumOf { r -> r.stockOuts.count { it.lost } }} lost sales",
    )
    val clumsy = buildSim(SITE, cal, Knobs(misplaceProb = 1.0))
    val cs = StoreState.initial(model)
    val c1 = clumsy.simulate(cs, FROM, seed)
    val c2 = clumsy.simulate(cs, FROM.plusDays(1), seed)
    check(
        "misplacement knob 1.0: put-backs land on a neighbour and move on the wire",
        c1.misplacements > 0 && c1.moves.count { it.reason == "misplaced" } == c1.misplacements,
        "${c1.misplacements} misplacements on day 1",
    )
    check(
        "staff return strays the next morning",
        c2.moves.any {
            it.reason == "stray-return"
        },
        "${c2.moves.filter { it.reason == "stray-return" }.sumOf { it.epcs.size }} units returned on day 2",
    )
    val noMis = buildSim(SITE, cal, Knobs(misplaceProb = 0.0)).simulate(StoreState.initial(model), FROM, seed)
    check("misplacement knob 0: none", noMis.misplacements == 0, "default knob ${Knobs().misplaceProb}: ${results.first().misplacements} on day 1")

    // ── 7. determinism and resume ────────────────────────────────────────────────────────────────────────
    log.info("[7] determinism and resume")
    fun threeDays(tag: String): String {
        val st = StoreState.initial(model)
        for (i in 0 until 3) {
            StoreDayOutput.write(
                OUT.resolve("$tag/${FROM.plusDays(i.toLong())}"),
                sim,
                sim.simulate(st, FROM.plusDays(i.toLong()), seed),
            )
        }
        st.save(OUT.resolve("$tag/state"))
        return digest(
            *(
                (0 until 3).flatMap { i ->
                    listOf("wire-plan.jsonl", "truth/fixture_hour.csv", "truth/onhand.csv").map {
                        OUT.resolve("$tag/${FROM.plusDays(i.toLong())}/$it")
                    }
                } +
                    listOf(Path.of("${OUT.resolve("$tag/state")}.csv"))
                ).toTypedArray(),
        )
    }
    val a = threeDays("detA")
    val b = threeDays("detB")
    check("same seed => same wire plan, truth and snapshot", a == b, "digest $a")
    val sm = StoreState.initial(model)
    sim.simulate(sm, FROM, seed)
    sm.save(OUT.resolve("resume"))
    val sm2 = StoreState.load(OUT.resolve("resume"))
    val cont = sim.simulate(sm, FROM.plusDays(1), seed)
    val resumed = sim.simulate(sm2, FROM.plusDays(1), seed)
    val sameWire =
        cont.wire.size == resumed.wire.size &&
            cont.wire.indices.all {
                cont.wire[it].dueMs == resumed.wire[it].dueMs && cont.wire[it].kind == resumed.wire[it].kind &&
                    cont.wire[it].payload.toString() == resumed.wire[it].payload.toString()
            }
    check(
        "a day started from a saved snapshot replays the continuous run",
        sameWire && cont.saleUnits == resumed.saleUnits,
        "${cont.wire.size} events",
    )
    val other = sim.simulate(StoreState.initial(model), FROM, seed + 1)
    check(
        "a different seed differs",
        other.saleUnits.map {
            it.epc
        } != results[0].saleUnits.map { it.epc },
        "${other.saleUnits.size} vs ${results[0].saleUnits.size} units",
    )

    // ── 8. the position-sample sidecar ───────────────────────────────────────────────────────────────────
    log.info("[8] samples sidecar")
    val withSamples = buildSim(SITE, cal, Knobs(samples = true))
    val sd = withSamples.simulate(StoreState.initial(model), FROM, seed)
    StoreDayOutput.write(OUT.resolve("samples-day"), withSamples, sd)
    val lines = Files.readAllLines(OUT.resolve("samples-day/samples.jsonl")).size
    check(
        "samples.jsonl has one stream per shopper, each referenced from the wire plan",
        lines == sd.records.size && sd.wire.count { it.kind == "person_samples_ref" } == sd.records.size,
        "$lines streams",
    )
    check("samples do not change the day", sd.saleUnits.map { it.epc } == results[0].saleUnits.map { it.epc }, "same sales with and without the sink")

    // ── 9. hard rules ────────────────────────────────────────────────────────────────────────────────────
    log.info("[9] offline rules")
    val src = Files.list(Path.of("src/main/kotlin/com/m8trx/twin/storeday")).use { it.toList() }.filter { it.toString().endsWith(".kt") }
    val banned = listOf("java.net", "io.nats", "WebhookClient", "ConnectClient", "HttpClient", "java.net.Socket", "NatsEmitter")
    val hits = src.filter { !it.fileName.toString().startsWith("StoreDaySelfTest") }.flatMap { f ->
        Files.readAllLines(f).filter { l ->
            banned.any {
                it in
                    l
            }
        }.map { "${f.fileName}: $it" }
    }
    check(
        "the store-day package opens no connection",
        hits.isEmpty(),
        if (hits.isEmpty()) "${src.size} source files scanned" else hits.joinToString(),
    )
    val live =
        listOf("assortment.csv", "epcs.csv", "layout.json", "planogram.json", "fixture_ids.csv").map {
            "reference/data/chain/stores/dec-us-denver/$it"
        } +
            "reference/data/store-operating-model.json"
    val diff = runCatching {
        ProcessBuilder(listOf("git", "diff", "--name-only", "origin/main", "--") + live).redirectErrorStream(true).start().let { p ->
            p.inputStream.bufferedReader().readText() to
                p.waitFor()
        }
    }.getOrNull()
    check(
        "files the live box reads are untouched",
        diff == null || diff.second != 0 || diff.first.isBlank(),
        if (diff == null ||
            diff.second != 0
        ) {
            "git/origin not available here: skipped"
        } else {
            "git diff vs origin/main: none changed"
        },
    )

    log.info("storeDaySelfTest: {} passed, {} failed", passed, failed)
    if (failed > 0) exitProcess(1)
}
