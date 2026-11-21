package com.m8trx.twin.storeops

import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.storeday.PeopleRest
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.system.exitProcess

/**
 * `./gradlew storeDayPeopleRestBackfillSelfTest` - offline: the backfill date range, the first-live-day gap (no session counted twice), the
 * partial-session count and the LIVE gate. No network; files only under `build/people-rest-backfill-selftest/`.
 */
private val OUT: Path = Path.of("build/people-rest-backfill-selftest")
private var failures = 0

private fun check(ok: Boolean, msg: () -> String) {
    if (!ok) {
        failures++
        println("      FAIL ${msg()}")
    }
}

private fun session(n: Int, imps: Int): List<PeopleRest.Line> {
    val base = "ppl-dec-us-nyc-20261120-$n"
    val b = ConnectMappers.camel.createObjectNode()
    val out = ArrayList<PeopleRest.Line>()
    out += PeopleRest.Line(n * 1000L, PeopleRest.RANK_BEGIN, 0, "person_begin", "$base-b", "POST", PeopleRest.SESSIONS_PATH, null, b, "s$n")
    for (i in 1..imps) {
        out += PeopleRest.Line(n * 1000L + i, PeopleRest.RANK_IMPRESSION, i, "person_impression", "$base-i$i", "POST", "/i", "$base-b", b, "s$n")
    }
    out += PeopleRest.Line(n * 1000L + 99, PeopleRest.RANK_CLOSE, 99, "person_close", "$base-c", "PATCH", "/c", "$base-b", b, "s$n")
    return out
}

fun main() {
    println("people-rest backfill self-test")

    println("  date range")
    val d = PeopleBackfill.dates(PeopleBackfill.DEFAULT_FROM, PeopleBackfill.FIRST_LIVE_DAY)
    check(d.first() == LocalDate.parse("2026-10-05")) { "first date ${d.first()}" }
    check(d.last() == LocalDate.parse("2026-11-20")) { "last date ${d.last()}" }
    check(d.size == 47) { "47 days expected (10-05..11-20), got ${d.size}" }
    check(d.zipWithNext().all { (a, b) -> b == a.plusDays(1) }) { "dates are not consecutive" }
    check(
        runCatching {
            PeopleBackfill.dates(LocalDate.parse("2026-11-21"), PeopleBackfill.FIRST_LIVE_DAY)
        }.isFailure,
    ) { "from after first live accepted" }
    check(PeopleBackfill.FIRST_LIVE_EVENT_UTC.keys == PeopleRest.STORES.toSet()) { "first-live table is not the nine stores" }

    println("  gap: no double count")
    val day = (1..6).flatMap { session(it, it % 3) }
    // live ledger: sessions 1 and 2 whole, 3 begin only (partial), 4..6 nothing
    val ledgerIds = setOf("ppl-dec-us-nyc-20261120-1-b", "ppl-dec-us-nyc-20261120-1-c", "ppl-dec-us-nyc-20261120-2-b", "ppl-dec-us-nyc-20261120-3-b")
    val sentKeys = PeopleBackfill.sessionKeys(ledgerIds)
    val gap = PeopleBackfill.gap(day, sentKeys)
    check(gap.none { it.id in ledgerIds }) { "a gap line is already in the ledger" }
    check(gap.map { PeopleWindow.sessionKey(it.id) }.toSet() == setOf(4, 5, 6).map { "ppl-dec-us-nyc-20261120-$it" }.toSet()) { "gap sessions wrong" }
    check(gap.size == day.filter { PeopleWindow.sessionKey(it.id).substringAfterLast('-').toInt() >= 4 }.size) { "a gap session is not whole" }
    check(
        gap.groupBy { PeopleWindow.sessionKey(it.id) }.all { (_, ls) ->
            ls.count { it.kind == "person_begin" } == 1 &&
                ls.count { it.kind == "person_close" } == 1
        },
    ) { "gap session without begin or close" }
    check(PeopleBackfill.partialSessions(day, ledgerIds) == 3) {
        "partial sessions ${PeopleBackfill.partialSessions(day, ledgerIds)} (1,3 and 2 are partial)"
    }
    check(gap.size + day.count { PeopleWindow.sessionKey(it.id) in sentKeys } == day.size) { "gap + ledgered sessions != plan" }
    check(PeopleBackfill.gap(day, emptySet()).size == day.size) { "empty ledger must give the whole day" }
    check(PeopleBackfill.gap(day, PeopleBackfill.sessionKeys(day.map { it.id }.toSet())).isEmpty()) { "full ledger must give no gap" }

    println("  linesFor: full days whole, first live day gap-only")
    val live = PeopleBackfill.FIRST_LIVE_DAY
    check(PeopleBackfill.linesFor(live.minusDays(1), live, day, sentKeys).size == day.size) { "a past day must be whole" }
    check(PeopleBackfill.linesFor(live, live, day, sentKeys).size == gap.size) { "the first live day must be the gap" }

    println("  ledger reading")
    Files.createDirectories(OUT.resolve("a/b"))
    Files.writeString(
        OUT.resolve("a/b/sent-ledger.tsv"),
        "ppl-x-20261120-1-b\tperson_begin\t201\tid\tts\n\nppl-x-20261120-1-c\tperson_close\t200\t\tts\n",
    )
    Files.writeString(OUT.resolve("sent-ledger.tsv"), "ppl-x-20261120-2-b\tperson_begin\t201\tid\tts\n")
    val ids = PeopleBackfill.sentIds(OUT)
    check(ids == setOf("ppl-x-20261120-1-b", "ppl-x-20261120-1-c", "ppl-x-20261120-2-b")) { "ledger ids $ids" }
    check(PeopleBackfill.sessionKeys(ids) == setOf("ppl-x-20261120-1", "ppl-x-20261120-2")) { "session keys wrong" }
    check(PeopleBackfill.sentIds(OUT.resolve("nope")).isEmpty()) { "missing dir must be empty" }

    println("  batch sizing and plan problems")
    check(PeopleBackfill.batchedRequests(day, 100) == 3) { "6 visits fit one batch per route: ${PeopleBackfill.batchedRequests(day, 100)}" }
    check(PeopleBackfill.batchedRequests(day, 2) == 3 + 3 + 3 || PeopleBackfill.batchedRequests(day, 2) > 3) {
        "small batches must need more requests"
    }
    check(PeopleBackfill.batchedRequests(emptyList()) == 0) { "an empty plan needs no request" }
    check(PeopleBackfill.problems(day).isEmpty()) { "the fixture plan has problems: ${PeopleBackfill.problems(day)}" }
    check(PeopleBackfill.problems(day + session(1, 0)).isNotEmpty()) { "a repeated trackingId must be a problem" }
    check(PeopleBackfill.problems(day.filter { it.kind != "person_close" }).isNotEmpty()) { "a visit without a close must be a problem" }

    println("  begin floor")
    val seoul = java.time.ZoneId.of("Asia/Seoul")
    val nyc = java.time.ZoneId.of("America/New_York")
    val from = PeopleBackfill.DEFAULT_FROM
    check(PeopleBackfill.beginFloorMs(seoul, from) == java.time.Instant.parse("2026-10-05T00:00:00Z").toEpochMilli()) { "Seoul floor" }
    check(PeopleBackfill.beginFloorMs(nyc, from) == java.time.Instant.parse("2026-10-05T04:00:00Z").toEpochMilli()) { "NYC floor" }
    val f = PeopleBackfill.beginFloorMs(seoul, from)
    check(PeopleBackfill.beforeFloor(listOf(session(1, 0)[0]), f + 5000).size == 1) { "a begin before the floor must be flagged" }
    check(PeopleBackfill.beforeFloor(listOf(session(1, 0)[0]), 0).isEmpty()) { "a begin after the floor is fine" }

    batchSection()

    println("  gate")
    val s = "dec-us-nyc"
    check(PeopleBackfill.refusals(false, PeopleRest.STORES, null, false).isEmpty()) { "dry must go with nothing set" }
    check(PeopleBackfill.refusals(true, listOf(s), s, true).isEmpty()) { "live with confirm and bearer must go" }
    check(PeopleBackfill.refusals(true, listOf(s), null, true).isNotEmpty()) { "live without confirm must refuse" }
    check(PeopleBackfill.refusals(true, listOf(s), "dec-us-sf", true).isNotEmpty()) { "live with the wrong confirm must refuse" }
    check(PeopleBackfill.refusals(true, listOf(s), s, false).isNotEmpty()) { "live without bearer must refuse" }
    check(PeopleBackfill.refusals(true, PeopleRest.STORES, s, true).isNotEmpty()) { "live with nine stores must refuse" }
    check(PeopleBackfill.refusals(false, listOf(PeopleRest.DENVER), null, false).isNotEmpty()) { "Denver must be refused" }
    check(PeopleBackfill.refusals(false, listOf(s, s), null, false).isNotEmpty()) { "a store twice must be refused" }
    check(PeopleBackfill.refusals(false, emptyList(), null, false).isNotEmpty()) { "no stores must be refused" }

    println(if (failures == 0) "PASS" else "FAILED: $failures")
    exitProcess(if (failures == 0) 0 else 1)
}

/** A plan line with a real body: [n] visits on [stamp] with [imps] impressions each. */
private fun visits(stamp: String, n: Int, imps: Int): List<PeopleRest.Line> = (1..n).flatMap { v ->
    val base = "ppl-dec-kr-seoul-$stamp-$v"
    val uuid = PeopleRest.sessionUuid(base)
    val begin = ConnectMappers.camel.createObjectNode().also {
        it.put("id", uuid)
        it.put("trackingId", base)
        it.put("entryTime", "2026-10-05T01:00:00Z")
    }
    val out = ArrayList<PeopleRest.Line>()
    out += PeopleRest.Line(v * 10L, PeopleRest.RANK_BEGIN, 0, "person_begin", "$base-b", "POST", PeopleRest.SESSIONS_PATH, null, begin, "s$v")
    for (i in 1..imps) {
        val body = ConnectMappers.camel.createObjectNode().also {
            it.put("trackingId", base).put("zoneId", "z$i").put("firstLook", "2026-10-05T01:01:00Z")
        }
        out +=
            PeopleRest.Line(
                v * 10L + i, PeopleRest.RANK_IMPRESSION, i, "person_impression", "$base-i$i", "POST",
                PeopleRest.IMPRESSIONS_PATH, "$base-b", body, "s$v",
            )
    }
    val close = ConnectMappers.camel.createObjectNode().also { it.put("exitTime", "2026-10-05T01:20:00Z") }
    out += PeopleRest.Line(
        v * 10L + 9, PeopleRest.RANK_CLOSE, 99, "person_close", "$base-c", "PATCH", "${PeopleRest.SESSIONS_PATH}/$uuid", "$base-b", close, "s$v",
    )
    out
}

private class FakeBatchTransport(var failAt: Int = -1, var failIndex: Int = 3, var dedupe: Boolean = false) : PeopleTransport {
    val calls = ArrayList<Pair<String, com.fasterxml.jackson.databind.JsonNode>>()
    var inFlight = 0
    var overlapped = false
    var wrongCount = false

    override fun health(): Pair<Int, String?> = 200 to null

    override fun call(method: String, path: String, body: String): PeopleAnswer {
        inFlight++
        if (inFlight > 1) overlapped = true
        val arr = ConnectMappers.camel.readTree(body)
        calls += path to arr
        val callNo = calls.size - 1
        val results = arr.mapIndexed { i, _ ->
            val bad = callNo == failAt && i == failIndex
            "{\"index\":$i,\"status\":${if (bad) {
                422
            } else if (dedupe) {
                200
            } else {
                201
            }}${if (dedupe) ",\"deduplicated\":true" else ""}}"
        }
        val failed = results.count { it.contains("422") }
        val count = if (wrongCount) arr.size() - 1 else arr.size()
        inFlight--
        return PeopleAnswer(
            200,
            "{\"count\":$count,\"succeeded\":${arr.size() - failed},\"deduplicated\":0,\"failed\":$failed,\"results\":[${results.joinToString(
                ",",
            )}]}",
            null,
        )
    }
}

private fun batchSection() {
    println("  batch mode")
    val day1 = visits("20261005", 120, 2)
    val day2 = visits("20261006", 5, 1)
    val all = day1 + day2
    val bs = PeopleBatch.batches(all)
    check(bs.all { it.lines.size in 1..100 } && bs.all { PeopleBatch.body(it).size() <= 100 }) { "a batch has more than 100 items or is empty" }
    // day 1: 120 begins = 2, 240 imps = 3, 120 closes = 2; day 2: 1 + 1 + 1
    check(bs.size == 7 + 3) { "batch count ${bs.size}, expected 10" }
    val phases = bs.map { it.day to it.kind }.distinct()
    check(
        phases == listOf(
            "20261005" to "person_begin",
            "20261005" to "person_impression",
            "20261005" to "person_close",
            "20261006" to "person_begin",
            "20261006" to "person_impression",
            "20261006" to "person_close",
        ),
    ) { "phase order wrong: $phases" }
    check(bs.take(2).map { it.lines.size } == listOf(100, 20)) { "chunking of 120 begins wrong" }
    check(PeopleBatch.batches(all, size = 7).all { it.lines.size <= 7 }) { "size 7 not honored" }
    check(runCatching { PeopleBatch.batches(all, size = 101) }.isFailure) { "size 101 accepted" }
    val first = PeopleRest.sessionUuid("ppl-dec-kr-seoul-20261005-1")
    val b0 = PeopleBatch.body(bs[0])
    check(b0.isArray && b0[0].path("op").asText() == "begin" && b0[0].path("id").asText() == first) { "begin body shape: ${b0[0]}" }
    check(b0[0].has("trackingId") && b0[0].has("entryTime")) { "begin lost its single-route fields" }
    val imp = PeopleBatch.body(bs.first { it.kind == "person_impression" })
    check(imp.isArray && !imp[0].has("op") && imp[0].has("zoneId")) { "impression must be the single body" }
    val cl = PeopleBatch.body(bs.first { it.kind == "person_close" })
    check(cl[0].path("op").asText() == "close" && cl[0].path("id").asText() == first && cl[0].path("exitTime").asText() == "2026-10-05T01:20:00Z") {
        "close body shape: ${cl[0]}"
    }

    var now = 0L
    val clock = { now }
    val sleeper = { ms: Long -> now += ms }
    val led = OUT.resolve("batch-ledger.tsv")
    Files.createDirectories(OUT)
    Files.deleteIfExists(led)
    val t = FakeBatchTransport()
    val r = PeopleBatchRunner(bs, t, PeopleLedger(led), 60, clock, sleeper, { false }, null)
    check(r.run() == PeopleBatchRunner.End.DONE) { "a clean run did not finish: ${r.haltReason}" }
    check(t.calls.size == 10 && !t.overlapped) { "calls ${t.calls.size}, overlapped ${t.overlapped}" }
    check(t.calls.map { it.first } == bs.map { it.path }) { "wire order differs from the plan" }
    check(r.startedAt.zipWithNext().all { (a, b) -> b - a >= 1000 }) { "requests closer than 1 s apart (over 60 a minute)" }
    check(PeopleLedger(led).size == all.size) { "ledger has ${PeopleLedger(led).size} items, expected ${all.size}" }

    // a failed item (call 1 = the 20 remaining begins, index 3 = visit 104) halts, ledgers only the confirmed items, and a resume sends the rest once
    Files.deleteIfExists(led)
    val f = FakeBatchTransport(failAt = 1, failIndex = 3)
    val rf = PeopleBatchRunner(bs, f, PeopleLedger(led), 60, clock, sleeper, { false }, null)
    check(rf.run() == PeopleBatchRunner.End.HALTED && rf.haltReason!!.contains("ppl-dec-kr-seoul-20261005-104-b")) {
        "a failed item must halt and name the entry: ${rf.haltReason}"
    }
    check(f.calls.size == 2) { "nothing may be sent after a failed item, got ${f.calls.size} calls" }
    check(PeopleLedger(led).size == 100 + 19) { "ledger after the halt ${PeopleLedger(led).size}, expected 119" }
    val ledger2 = PeopleLedger(led)
    val resumed = PeopleBatch.batches(all, ledger2::isSent)
    check(resumed.sumOf { it.lines.size } == all.size - 119) { "resume plans ${resumed.sumOf { it.lines.size }} items" }
    check(resumed.none { b -> b.lines.any { ledger2.isSent(it.id) } }) { "resume would resend a confirmed item" }
    check(PeopleBatchRunner(resumed, FakeBatchTransport(), ledger2, 60, clock, sleeper, { false }, null).run() == PeopleBatchRunner.End.DONE) {
        "resume failed"
    }
    check(PeopleLedger(led).size == all.size) { "after the resume the ledger has ${PeopleLedger(led).size}" }

    Files.deleteIfExists(led)
    val w = FakeBatchTransport().also { it.wrongCount = true }
    check(PeopleBatchRunner(bs, w, PeopleLedger(led), 60, clock, sleeper, { false }, null).run() == PeopleBatchRunner.End.HALTED) {
        "count mismatch must halt"
    }
    Files.deleteIfExists(led)
    check(
        PeopleBatchRunner(bs, FakeBatchTransport(dedupe = true), PeopleLedger(led), 60, clock, sleeper, { false }, null).run() ==
            PeopleBatchRunner.End.DONE,
    ) {
        "dedup must succeed"
    }
    var t2 = 0L
    val slow = object : PeopleTransport {
        override fun health() = 200 to null

        override fun call(method: String, path: String, body: String): PeopleAnswer {
            t2 += 10_000
            val n = ConnectMappers.camel.readTree(body).size()
            return PeopleAnswer(
                200,
                "{\"count\":$n,\"failed\":0,\"results\":[${(0 until n).joinToString(",") {
                    "{\"index\":$it,\"status\":201}"
                }}]}",
                null,
            )
        }
    }
    val rs = PeopleBatchRunner(bs.take(3), slow, PeopleLedger(null), 60, { t2 }, { t2 += it }, { false }, null)
    rs.run()
    check(rs.startedAt.zipWithNext().all { (a, b) -> b - a >= 20_000 }) { "a 10 s batch must be followed by a longer wait: ${rs.startedAt}" }
    check(runCatching { PeopleBatchRunner(bs, FakeBatchTransport(), PeopleLedger(null), 61, clock, sleeper, { false }, null) }.isFailure) {
        "cap over 60 accepted"
    }
}
