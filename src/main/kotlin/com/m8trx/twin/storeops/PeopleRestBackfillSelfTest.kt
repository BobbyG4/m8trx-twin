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
