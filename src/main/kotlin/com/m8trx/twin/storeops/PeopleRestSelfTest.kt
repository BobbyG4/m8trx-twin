package com.m8trx.twin.storeops

import com.m8trx.twin.connect.DatedStream
import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.storeday.DayResult
import com.m8trx.twin.storeday.PeopleRest
import com.m8trx.twin.storeday.StoreDayOutput
import com.m8trx.twin.storeday.StoreDaySim
import com.m8trx.twin.storeday.buildSim
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.exitProcess

/**
 * `./gradlew storeDayPeopleRestSelfTest` - the people-by-REST wire plan and its dry-run sender (part of `offlineSelfTest`). Offline: no
 * network, no credentials, files only under `build/people-rest-selftest/`.
 *
 * Nine stores x one weekday (Mon 2026-11-23) and one Saturday (2026-11-21) are simulated once and shared by every check.
 */
private val OUT: Path = Path.of("build/people-rest-selftest")
private val WEEKDAY: LocalDate = LocalDate.parse("2026-11-23")
private val SATURDAY: LocalDate = LocalDate.parse("2026-11-21")
private val CAP = PeoplePace.DEFAULT_CAP_PER_MIN

private class Day(val slug: String, val date: LocalDate, val sim: StoreDaySim, val r: DayResult, val lines: List<PeopleRest.Line>)

private val chainDir = Path.of("reference/data/chain")
private val keys by lazy { ChainKeys.load(chainDir.resolve(ChainKeys.DEFAULT_PATH.fileName)) }
private val siteIds by lazy { DatedStream.readSiteIds(chainDir.resolve("site_ids.csv")) }

private fun simulate(slug: String, date: LocalDate, seed: Long? = null): Day {
    val sim = buildSim(slug)
    val r = sim.simulate(sim.initialState(), date, seed ?: sim.behavior.params.seed)
    return Day(slug, date, sim, r, PeopleRest.plan(slug, date, r, PeopleRest.idsFor(slug, keys, siteIds)))
}

private val days: Map<Pair<String, LocalDate>, Day> by lazy {
    val m = LinkedHashMap<Pair<String, LocalDate>, Day>()
    for (d in listOf(WEEKDAY, SATURDAY)) {
        for (s in PeopleRest.STORES) {
            println("      simulating $s $d")
            m[s to d] = simulate(s, d).also { x ->
                StoreDayOutput.write(OUT.resolve("day").resolve(s).resolve(d.toString()), x.sim, x.r)
                PeopleRest.write(PeopleRest.sidecar(OUT.resolve("day"), s, d), x.lines)
            }
        }
    }
    m
}

private fun isoMs(s: String) = Instant.parse(s).toEpochMilli()

/** The truth ledger read back from the file the store-day wrote: (hour, fixture) -> impressions. */
private fun ledger(d: Day): Map<Pair<Int, String>, Int> {
    val f = OUT.resolve("day").resolve(d.slug).resolve(d.date.toString()).resolve("truth/fixture_hour.csv")
    return Files.readAllLines(f).drop(1).filter { it.isNotBlank() }.map { it.split(",") }
        .filter { it[3].toInt() > 0 }.associate { (it[1].toInt() to it[2]) to it[3].toInt() }
}

private fun plan(slug: String, date: LocalDate) = days.getValue(slug to date)

private fun counts() {
    for ((k, d) in days) {
        val begins = d.lines.filter { it.kind == "person_begin" }
        val imps = d.lines.filter { it.kind == "person_impression" }
        val zone = ZoneId.of(d.sim.model.timezone)
        val summary = StoreDayOutput.summary(d.sim, d.r)
        check(begins.size == summary["visitors"] && begins.size == d.r.records.size) {
            "$k: sessions ${begins.size} = visitors ${summary["visitors"]}"
        }
        // per fixture-hour, from the written ledger file
        val led = ledger(d)
        val zoneToFixture = keys.store(d.slug).fixtures.associate { it.zoneId to it.code }
        val got = HashMap<Pair<Int, String>, Int>()
        imps.forEach { l ->
            val fx = zoneToFixture[l.body.path("zoneId").asText()] ?: error("$k: unknown zone ${l.body.path("zoneId").asText()}")
            got.merge(Instant.ofEpochMilli(isoMs(l.body.path("firstLook").asText())).atZone(zone).hour to fx, 1, Int::plus)
        }
        check(got == led) { "$k: impressions per fixture and hour differ from truth/fixture_hour.csv (${got.values.sum()} vs ${led.values.sum()})" }
        check(imps.size == led.values.sum() && imps.size == d.r.records.sumOf { r -> r.visits.count { it.impression } }) { "$k: impression total" }
        val sales = d.r.records.count { it.bought }
        check(sales == summary["transactions"]) { "$k: the shoppers whose purchases the sim made are these sessions ($sales)" }
        check(d.lines.map { it.id }.toSet().size == d.lines.size) { "$k: ids are unique" }
        check(d.r.records.map { it.id.substringAfterLast('-') }.toSet().size == d.r.records.size) { "$k: shopper ids unique after the suffix cut" }
    }
}

private fun invariants() {
    for ((k, d) in days) {
        val ids = PeopleRest.idsFor(d.slug, keys, siteIds)
        val zones = ids.zoneByFixture.values.toSet()
        val begin = d.lines.filter { it.kind == "person_begin" }.associateBy { it.id }
        val closes = d.lines.filter { it.kind == "person_close" }
        check(closes.size == begin.size && closes.map { it.sessionRef }.toSet() == begin.keys) { "$k: every begun session is closed exactly once" }
        check(d.lines.sortedBy { it.dueMs } == d.lines || d.lines.zipWithNext().all { (a, b) -> a.dueMs <= b.dueMs }) { "$k: ordered by due time" }
        val seen = HashSet<String>()
        for (l in d.lines) {
            val b = begin[l.sessionRef ?: l.id]
            when (l.kind) {
                "person_begin" -> {
                    check(l.body.path("siteId").asText() == ids.siteId && l.body.path("spaceId").asText() == ids.spaceId) {
                        "$k ${l.id}: site and space"
                    }
                    check(l.body.path("trackingId").asText() == l.id.removeSuffix("-b")) { "$k ${l.id}: trackingId" }
                    seen += l.id
                }

                "person_impression" -> {
                    check(l.sessionRef in seen) { "$k ${l.id}: its begin is due earlier" }
                    val bt = isoMs(b!!.body.path("entryTime").asText())
                    val ct = isoMs(closes.first { it.sessionRef == l.sessionRef }.body.path("exitTime").asText())
                    val first = isoMs(l.body.path("firstDwell").asText())
                    val last = isoMs(l.body.path("lastDwell").asText())
                    check(first >= bt && last <= ct) { "$k ${l.id}: inside its session ($bt <= $first..$last <= $ct)" }
                    check(isoMs(l.body.path("firstLook").asText()) <= isoMs(l.body.path("lastLook").asText()) && l.dueMs == last) {
                        "$k ${l.id}: look order, due"
                    }
                    check(l.body.path("zoneId").asText() in zones) { "$k ${l.id}: zone belongs to ${d.slug}" }
                    check(l.body.path("trackingId").asText() == l.sessionRef!!.removeSuffix("-b")) { "$k ${l.id}: trackingId" }
                }

                "person_close" -> {
                    check(l.sessionRef in seen) { "$k ${l.id}: its begin is due earlier" }
                    check(isoMs(l.body.path("exitTime").asText()) > isoMs(b!!.body.path("entryTime").asText())) { "$k ${l.id}: exit after entry" }
                }
            }
        }
    }
}

private fun refusals() {
    check(PeopleRest.refusal(PeopleRest.DENVER) != null && PeopleRest.refusal(PeopleRest.DENVER, true) == null) {
        "Denver refused by default, allowed by the flag"
    }
    check(PeopleRest.STORES.size == 9 && PeopleRest.DENVER !in PeopleRest.STORES) { "the nine stores exclude Denver" }
    check(PeopleRest.STORES.all { PeopleRest.refusal(it) == null }) { "the nine are accepted" }
    check(PeopleRest.refusal("dec-us-denver-office") != null) { "an office site is refused" }
    check(
        PeoplePace.refusal(true, false) != null && PeoplePace.refusal(false, true)!!.contains("backfill") && PeoplePace.refusal(false, false) == null,
    ) {
        "live and backfill are refused with a message"
    }
    // Denver can be mapped when the test flag is set (not part of the nine, never in a default run)
    val d = simulate(PeopleRest.DENVER, WEEKDAY)
    check(
        d.lines.isNotEmpty() && d.lines.count {
            it.kind == "person_begin"
        } == d.r.records.size,
    ) { "Denver maps with the flag (${d.lines.size} requests)" }
}

private fun determinism() {
    val a = plan("dec-fr-marseille", WEEKDAY)
    val b = simulate("dec-fr-marseille", WEEKDAY)
    check(
        a.lines.map {
            PeopleRest.toJson(it).toString()
        } == b.lines.map { PeopleRest.toJson(it).toString() },
    ) { "same seed, same plan, byte for byte" }
    val c = simulate("dec-fr-marseille", WEEKDAY, b.sim.behavior.params.seed + 1)
    check(c.lines.map { PeopleRest.toJson(it).toString() } != a.lines.map { PeopleRest.toJson(it).toString() }) { "another seed, another plan" }
    // the sidecar round-trips
    val f = PeopleRest.sidecar(OUT.resolve("day"), "dec-fr-marseille", WEEKDAY)
    check(
        PeopleRest.read(f).map {
            PeopleRest.toJson(it).toString()
        } == a.lines.map { PeopleRest.toJson(it).toString() },
    ) { "the sidecar reads back as written" }
}

private fun merged(date: LocalDate) = PeoplePace.merge(PeopleRest.STORES.map { plan(it, date).lines })

private fun pacing() {
    val before = LiveClients.constructed
    val plan = merged(SATURDAY)
    val demand = PeoplePace.peakPerMinute(plan.map { it.dueMs })
    val run = PeopleRestPacer.run(plan, CAP)
    val paced = PeoplePace.peakPerMinute(run.sentAtMs)
    println(
        "      busy Saturday, nine stores: ${plan.size} requests, demand peak $demand/min, paced peak $paced/min (cap $CAP), max lag ${run.maxLagMs / 1000} s",
    )
    check(run.halted == null && run.sent.map { it.extId } == plan.map { it.extId }) { "every request sent once, in plan order" }
    check(paced <= CAP) { "paced peak $paced/min is within the cap $CAP" }
    check(demand <= CAP) { "the raw demand peak $demand/min is itself within the cap (no backlog is needed on this day)" }
    check(run.sentAtMs.zip(plan).all { (t, i) -> t >= i.dueMs }) { "nothing is sent before it is due" }
    // a lower cap forces a backlog: the pace holds and the order holds
    val slow = PeopleRestPacer.run(plan.take(2000), 30)
    check(PeoplePace.peakPerMinute(slow.sentAtMs) <= 30 && slow.sent.size == 2000) { "a cap of 30/min holds under a burst" }
    check(runCatching { PeoplePace.gapMs(601) }.isFailure) { "a cap above the per-key 600/min is refused" }
    check(LiveClients.constructed == before) { "no live client was constructed" }
}

private fun backoff() {
    val plan = merged(WEEKDAY).take(400)
    val hits = HashMap<String, Int>()
    val run = PeopleRestPacer.run(
        plan,
        CAP,
        faults = { item, attempt ->
            when {
                item.seq == 50 && attempt == 1 -> Outcome.Http(429, 5)
                item.seq == 100 && attempt == 1 -> Outcome.Http(502)
                item.seq == 150 && attempt == 1 -> Outcome.Unreachable("reset")
                else -> null
            }?.also { hits.merge(item.extId, 1, Int::plus) }
        },
    )
    check(hits.size == 3 && run.halted == null) { "three injected failures were absorbed (${hits.size})" }
    check(run.sent.map { it.extId } == plan.map { it.extId }) { "a failed request keeps its place; nothing skipped, nothing reordered" }
    val at = run.sent.indices.associate { run.sent[it].seq to run.sentAtMs[it] }
    val s50 = at.getValue(50)
    check(s50 - at.getValue(49) >= 60_000L) { "429 waits a full minute (${(s50 - at.getValue(49)) / 1000} s)" }
    check(
        run.healthChecks > 0 && run.waits.any {
            it.contains("429")
        } && run.waits.any { it.contains("502") },
    ) { "502 and no-answer go through the health gate" }
    // a 4xx refusal halts the lane
    val halt = PeopleRestPacer.run(plan, CAP, faults = { item, _ -> if (item.seq == 10) Outcome.Http(400) else null })
    check(halt.halted != null && halt.sent.size == 10) { "a 400 halts the lane with the event still due (${halt.sent.size} sent)" }
    // a close of a session that was never begun is refused (422) and halts: the sender resolves the id from the begin's 201
    val broken = plan.filter { it.payload.path("kind").asText() != "person_begin" }.take(5)
    check(PeopleRestPacer.run(broken, CAP).halted != null) { "an unresolved session ref halts" }
}

private fun noLive() {
    val before = LiveClients.constructed
    PeopleRestPacer.run(merged(WEEKDAY).take(50), CAP)
    check(LiveClients.constructed == before && LiveClients.constructed == 0) { "LiveClients.constructed stays 0" }
    val src = Path.of("src/main/kotlin/com/m8trx/twin")
    val files = listOf(src.resolve("storeday/PeopleRest.kt"), src.resolve("storeops/PeopleRestDryRun.kt"))
    val bad = Regex("""java\.net|HttpClient|ConnectClient|WebhookClient|ConnectHttp|Socket""")
    check(
        files.all { f ->
            Files.readAllLines(f).filter { !it.trimStart().startsWith("*") && !it.trimStart().startsWith("//") }.none { bad.containsMatchIn(it) }
        },
    ) {
        "the mapper and the dry-run sender reference no network class"
    }
}

private fun summary() {
    for ((label, date) in listOf("weekday" to WEEKDAY, "Saturday" to SATURDAY)) {
        println("      per-store summary, $label $date")
        println("      %-16s %9s %12s %9s %9s".format("store", "sessions", "impressions", "requests", "peak/min"))
        var s = 0
        var i = 0
        var q = 0
        PeopleRest.STORES.forEach { slug ->
            val d = plan(slug, date)
            val ns = d.lines.count { it.kind == "person_begin" }
            val ni = d.lines.count { it.kind == "person_impression" }
            println("      %-16s %9d %12d %9d %9d".format(slug, ns, ni, d.lines.size, PeoplePace.peakPerMinute(d.lines.map { it.dueMs })))
            s += ns
            i += ni
            q += d.lines.size
        }
        val m = merged(date)
        println("      %-16s %9d %12d %9d %9d".format("all nine", s, i, q, PeoplePace.peakPerMinute(m.map { it.dueMs })))
    }
}

private var failures = 0

private fun check(ok: Boolean, msg: () -> String) {
    if (!ok) {
        failures++
        println("      FAIL ${msg()}")
    }
}

fun main() {
    Files.createDirectories(OUT)
    val tests = listOf<Pair<String, () -> Unit>>(
        "mapping: sessions = visitors, impressions = the truth ledger per fixture and hour, same shoppers as the sales" to ::counts,
        "plan: begin/close once, exit after entry, impressions inside their session, begin due earlier, zones of the store, ids unique" to
            ::invariants,
        "Denver refused by default (flag for tests), live and backfill refused with a message" to ::refusals,
        "deterministic by seed; the sidecar reads back" to ::determinism,
        "pacing: nine stores on a busy Saturday stay under the cap; a low cap holds under a burst" to ::pacing,
        "backoff: 429, 502, no answer keep the request due and in place; a 400 halts; an unresolved session ref halts" to ::backoff,
        "no network: no live client built, no network class referenced" to ::noLive,
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
    summary()
    println("storeDayPeopleRestSelfTest: ${tests.size - bad}/${tests.size} passed")
    if (bad > 0) exitProcess(1)
}
