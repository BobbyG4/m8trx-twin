package com.m8trx.twin.storeops

import com.m8trx.twin.storeday.PeopleRest
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * People-by-REST BACKFILL (2026-11-21): the nine non-Denver stores have people up to 2026-10-04 (history load) and from the first live day
 * (2026-11-20, live REST runs); the days between are empty. A backfill is the SAME plan the live run would have built for the day
 * ([PeopleRest.plan] of the same store-day simulation, full day 00:00-23:59 store-local), sent through the same route by the same sender, with
 * event times in the past. Pure functions only; the runner is `PeopleRestBackfillMain`.
 *
 * The first live day is a GAP backfill: only the sessions with no line in any live sent-ledger, whole (begin + impressions + close), so a
 * session the live run already sent is never counted twice. A session with SOME lines in a ledger is not a gap: it is reported as partial.
 */
object PeopleBackfill {
    const val LIVE_ENV = "M8TRX_PEOPLE_BACKFILL_LIVE"
    const val CONFIRM_ENV = "M8TRX_PEOPLE_BACKFILL_CONFIRM"
    const val DEFAULT_CAP_PER_MIN = 60

    val DEFAULT_FROM: LocalDate = LocalDate.parse("2026-10-05")

    /** The store-local day the live REST runs began, for every one of the nine stores (box ledgers, 2026-11-20). */
    val FIRST_LIVE_DAY: LocalDate = LocalDate.parse("2026-11-20")

    /** First live event per store (UTC, from the box ledgers); documentation for the report, the gap rule is the ledger. */
    val FIRST_LIVE_EVENT_UTC: Map<String, String> = mapOf(
        "dec-kr-seoul" to "2026-11-20T01:30:29Z",
        "dec-kr-busan" to "2026-11-20T04:17:37Z",
        "dec-fr-bordeaux" to "2026-11-20T08:34:37Z",
        "dec-fr-lille" to "2026-11-20T08:30:54Z",
        "dec-fr-lyon" to "2026-11-20T08:32:16Z",
        "dec-fr-marseille" to "2026-11-20T08:30:13Z",
        "dec-fr-paris" to "2026-11-20T08:31:16Z",
        "dec-us-nyc" to "2026-11-20T15:00:15Z",
        "dec-us-sf" to "2026-11-20T18:00:16Z",
    )

    /** Every store-local date of the backfill, [from] through [firstLive] inclusive (the last one is a gap-only day). */
    fun dates(from: LocalDate, firstLive: LocalDate): List<LocalDate> {
        require(!from.isAfter(firstLive)) { "FROM $from is after the first live day $firstLive" }
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(firstLive) }.toList()
    }

    fun isWeekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

    /** Every ledger id found in the live sent-ledgers under [dir] (any depth). */
    fun sentIds(dir: Path): Set<String> {
        if (!Files.isDirectory(dir)) return emptySet()
        val out = HashSet<String>()
        Files.walk(dir).use { s ->
            s.filter { it.fileName.toString() == "sent-ledger.tsv" }.forEach { f ->
                Files.readAllLines(f).filter { it.isNotBlank() }.forEach { out += it.substringBefore('\t') }
            }
        }
        return out
    }

    /** The session keys (`ppl-<slug>-<yyyymmdd>-<n>`) of [ids]. */
    fun sessionKeys(ids: Set<String>): Set<String> = ids.map { PeopleWindow.sessionKey(it) }.toSet()

    /** The lines of the sessions with NO id in [sentKeys]: whole sessions, in the plan's order. */
    fun gap(day: List<PeopleRest.Line>, sentKeys: Set<String>): List<PeopleRest.Line> = day.filter { PeopleWindow.sessionKey(it.id) !in sentKeys }

    /** Sessions with some but not all of their lines in the ledger: neither a gap nor whole; counted for the report. */
    fun partialSessions(day: List<PeopleRest.Line>, sentIds: Set<String>): Int =
        day.groupBy { PeopleWindow.sessionKey(it.id) }.count { (_, ls) -> ls.any { it.id in sentIds } && ls.any { it.id !in sentIds } }

    /** The lines to send for [date]: the whole day, or only the gap sessions on the first live day. */
    fun linesFor(date: LocalDate, firstLive: LocalDate, day: List<PeopleRest.Line>, sentKeys: Set<String>): List<PeopleRest.Line> =
        if (date == firstLive) gap(day, sentKeys) else day

    /** Every reason the run is refused (empty = go). Live takes exactly one store, named by [confirm]. */
    fun refusals(
        live: Boolean,
        stores: List<String>,
        confirm: String?,
        bearerPresent: Boolean,
        firstLiveOverridden: Boolean = false,
        datesGiven: Boolean = true,
    ): List<String> {
        val out = ArrayList<String>()
        if (stores.isEmpty()) out += "no stores selected"
        stores.forEach { s ->
            val r = PeopleRest.refusal(s, false)
            if (r != null) {
                out += r
            } else if (s !in PeopleRest.STORES) {
                out += "$s is not one of the nine REST-people stores"
            }
        }
        if (stores.toSet().size != stores.size) out += "a store is listed twice"
        if (live) {
            if (stores.size != 1) out += "a LIVE backfill takes one store at a time (got ${stores.size}); the dry run takes any"
            if (confirm == null) {
                out += "$CONFIRM_ENV is not set: it must be the slug being sent (${stores.joinToString(",")})"
            } else if (confirm != stores.singleOrNull()) {
                out += "$CONFIRM_ENV ($confirm) does not match the store of the run (${stores.joinToString(",")})"
            }
            if (!bearerPresent) out += "${PeopleLiveGate.BEARER_ENV} is not set (the service key, scope vision_ai:ingest)"
            if (firstLiveOverridden) {
                out +=
                    "M8TRX_PEOPLE_BACKFILL_FIRST_LIVE cannot be overridden on a live run: the first live day is $FIRST_LIVE_DAY"
            }
            if (!datesGiven) out += "a LIVE backfill requires M8TRX_PEOPLE_BACKFILL_DATES=<from>..<to> (no default range is ever sent)"
        }
        return out
    }

    fun begins(ls: List<PeopleRest.Line>) = ls.count { it.kind == "person_begin" }

    fun count(ls: List<PeopleRest.Line>, kind: String) = ls.count { it.kind == kind }

    const val BATCH_SIZE = 100

    /** Requests if the batch routes took [size] items each (sizing only; the strict per-visit order is NOT kept across batches). */
    fun batchedRequests(ls: List<PeopleRest.Line>, size: Int = BATCH_SIZE): Int =
        listOf("person_begin", "person_impression", "person_close").sumOf { (count(ls, it) + size - 1) / size }

    /**
     * Every ordering or identity problem of a plan, empty = none: a trackingId or derived UUID used twice; an impression whose firstLook is
     * before its begin or whose lastDwell is after its close (the sender would put it after the close); a close before its begin.
     */
    fun problems(lines: List<PeopleRest.Line>): List<String> {
        val out = ArrayList<String>()
        val tids = lines.filter { it.kind == "person_begin" }.map { PeopleWindow.sessionKey(it.id) }
        if (tids.toSet().size != tids.size) out += "a trackingId is used by two visits"
        val uuids = tids.map { PeopleRest.sessionUuid(it) }
        if (uuids.toSet().size != uuids.size) out += "two trackingIds derive the same UUID"
        lines.groupBy { PeopleWindow.sessionKey(it.id) }.forEach { (k, ls) ->
            val b = ls.firstOrNull { it.kind == "person_begin" }
            val c = ls.firstOrNull { it.kind == "person_close" }
            if (b == null || c == null) {
                out += "$k: begin or close missing"
            } else {
                if (c.dueMs < b.dueMs) out += "$k: close before begin"
                ls.filter { it.kind == "person_impression" }.forEach { i ->
                    val first =
                        i.body.path("firstLook").asText().takeIf { it.isNotBlank() }?.let { java.time.Instant.parse(it).toEpochMilli() } ?: b.dueMs
                    if (first < b.dueMs) out += "${i.id}: firstLook before the begin"
                    if (i.dueMs > c.dueMs) out += "${i.id}: ends after the close (would be sent after it)"
                }
            }
        }
        return out
    }

    /** The history load ends at 2026-10-05T00:00Z: no begin may be earlier than this or than the store's local 2026-10-05 00:00. */
    val HISTORY_END_UTC: java.time.Instant = java.time.Instant.parse("2026-10-05T00:00:00Z")

    fun beginFloorMs(zone: java.time.ZoneId, from: LocalDate): Long =
        maxOf(from.atStartOfDay(zone).toInstant().toEpochMilli(), HISTORY_END_UTC.toEpochMilli())

    /** Begins earlier than [floorMs]. */
    fun beforeFloor(lines: List<PeopleRest.Line>, floorMs: Long): List<PeopleRest.Line> = lines.filter {
        it.kind == "person_begin" &&
            it.dueMs < floorMs
    }

    /** How many ids of [slug]'s [date] are in the live ledgers ([ids] is every id found under the ledger dir, of every group and store dir). */
    fun ledgerLines(ids: Set<String>, slug: String, date: LocalDate): Int {
        val prefix = "ppl-$slug-${PeopleRest.stamp(date)}-"
        return ids.count { it.startsWith(prefix) }
    }

    /**
     * Why the first live day of [slug] may not be sent (empty = ok): the store has no live ledger line for the day (the gap would be the whole day,
     * or the ledgers were not fetched), or [futureBegins] gap sessions begin after now (the live run is still going to send them).
     */
    fun gapRefusals(slug: String, date: LocalDate, ledgerLines: Int, futureBegins: Int): List<String> {
        val out = ArrayList<String>()
        if (ledgerLines ==
            0
        ) {
            out +=
                "$slug $date: no live ledger line for this store and day (check every ledger dir, incl. the old group dirs kr/fr and people-rest/live)"
        }
        if (futureBegins >
            0
        ) {
            out += "$slug $date: $futureBegins gap sessions begin in the future; the live run will send them. Wait until the live run has ended"
        }
        return out
    }

    const val SINGLE_LEDGER = "sent-ledger.tsv"
    const val BATCH_LEDGER = "batch-sent-ledger.tsv"

    /** The ledger of the OTHER mode in [dir] when it exists: a live run must not switch mode midway (the two never see each other's ledger). */
    fun otherModeLedger(dir: Path, mode: String): Path? =
        dir.resolve(if (mode == "batch") SINGLE_LEDGER else BATCH_LEDGER).takeIf { Files.exists(it) }
}
