package com.m8trx.twin.storeops

import com.m8trx.twin.connect.DatedStream
import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.peoplelive.PeopleRestHttp
import com.m8trx.twin.storeday.PeopleRest
import com.m8trx.twin.storeday.buildSim
import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("com.m8trx.twin.storeops.PeopleRestBackfill")

/**
 * `./gradlew storeDayPeopleRestBackfill` - backfill of people-by-REST for the nine non-Denver stores, from 2026-10-05 to each store's first
 * live day. DRY by default: prints the per-day table and the summary, sends nothing, builds no client, writes no file (except the CSV named by
 * `M8TRX_PEOPLE_BACKFILL_REPORT`, if set). LIVE only with `M8TRX_PEOPLE_BACKFILL_LIVE=true` AND `M8TRX_PEOPLE_BACKFILL_CONFIRM=<the one slug>`
 * AND `M8TRX_TWIN_PEOPLE_BEARER`.
 *
 * Env: `_STORES` (comma subset; default all nine) · `_FROM` (2026-10-05) · `_FIRST_LIVE` (2026-11-20; that day is gap-only) ·
 * `_LEDGER_DIR` (a directory holding the live `sent-ledger.tsv` files at any depth; default `out/people-rest-live-ledgers`) · `_CAP_PER_MIN`
 * (default 60, max 600) · `_OUT` (out/people-rest-backfill; the run is in `<out>/<slug>/`) · `_HOLD_FILE` (`$M8TRX_OPS_DIR/people-HOLD`) ·
 * `M8TRX_PEOPLE_REST_BASE_URL`. Exit: 0 done, 1 halted, 3 refused, 6 HOLD.
 */
object PeopleRestBackfillMain {
    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }

    class DayRow(val slug: String, val date: LocalDate, val mode: String, val lines: List<PeopleRest.Line>, val partial: Int)

    @Suppress("LongMethod", "ReturnCount")
    fun run(): Int {
        val live = env(PeopleBackfill.LIVE_ENV)?.toBoolean() == true
        val stores = env("M8TRX_PEOPLE_BACKFILL_STORES")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: PeopleRest.STORES
        val range = env("M8TRX_PEOPLE_BACKFILL_DATES")?.split("..")
        if (range != null &&
            range.size != 2
        ) {
            return refuse(listOf("M8TRX_PEOPLE_BACKFILL_DATES must be <from>..<to>, got ${env("M8TRX_PEOPLE_BACKFILL_DATES")}"))
        }
        val from = range?.get(0)?.let(LocalDate::parse) ?: env("M8TRX_PEOPLE_BACKFILL_FROM")?.let(LocalDate::parse) ?: PeopleBackfill.DEFAULT_FROM
        val mode = env("M8TRX_PEOPLE_BACKFILL_MODE") ?: "single"
        if (mode != "single" && mode != "batch") return refuse(listOf("M8TRX_PEOPLE_BACKFILL_MODE must be batch or single, got $mode"))
        val firstLive = env("M8TRX_PEOPLE_BACKFILL_FIRST_LIVE")?.let(LocalDate::parse) ?: PeopleBackfill.FIRST_LIVE_DAY
        val cap = env("M8TRX_PEOPLE_BACKFILL_CAP_PER_MIN")?.toInt()
            ?: if (mode == "batch") PeopleBatch.DEFAULT_CAP_PER_MIN else PeopleBackfill.DEFAULT_CAP_PER_MIN
        val out = Path.of(env("M8TRX_PEOPLE_BACKFILL_OUT") ?: "out/people-rest-backfill")
        val ledgerDir = Path.of(env("M8TRX_PEOPLE_BACKFILL_LEDGER_DIR") ?: "out/people-rest-live-ledgers")
        val bearer = env(PeopleLiveGate.BEARER_ENV)
        val refusals = PeopleBackfill.refusals(live, stores, env(PeopleBackfill.CONFIRM_ENV), bearer != null)
        if (refusals.isNotEmpty()) return refuse(refusals)
        PeoplePace.gapMs(cap)
        if (mode == "batch" &&
            cap > PeopleBatch.DEFAULT_CAP_PER_MIN
        ) {
            return refuse(listOf("batch mode is at most ${PeopleBatch.DEFAULT_CAP_PER_MIN} requests a minute"))
        }

        val chainDir = Path.of("reference/data/chain")
        val keys = ChainKeys.load(chainDir.resolve(ChainKeys.DEFAULT_PATH.fileName))
        val siteIds = DatedStream.readSiteIds(chainDir.resolve("site_ids.csv"))
        val sentIds = PeopleBackfill.sentIds(ledgerDir)
        val sentKeys = PeopleBackfill.sessionKeys(sentIds)
        val to = range?.get(1)?.let(LocalDate::parse) ?: firstLive
        if (to.isAfter(firstLive)) return refuse(listOf("TO $to is after the first live day $firstLive"))
        val dates = PeopleBackfill.dates(from, to)
        if (firstLive in dates && sentIds.isEmpty()) {
            return refuse(listOf("no live sent-ledger lines under $ledgerDir: the gap of $firstLive cannot be computed (it would be the whole day)"))
        }
        val rows = ArrayList<DayRow>()
        stores.forEach { slug ->
            val sim = buildSim(slug)
            val ids = PeopleRest.idsFor(slug, keys, siteIds)
            dates.forEach { d ->
                val r = sim.simulate(sim.initialState(), d, sim.behavior.params.seed)
                val day = PeopleRest.plan(slug, d, r, ids)
                val isGap = d == firstLive
                rows += DayRow(
                    slug,
                    d,
                    if (isGap) "gap" else "full",
                    PeopleBackfill.linesFor(d, firstLive, day, sentKeys),
                    if (isGap) PeopleBackfill.partialSessions(day, sentIds) else 0,
                )
                if (isGap) {
                    val planned = day.map { it.id }.toSet()
                    val first = PeopleBackfill.FIRST_LIVE_EVENT_UTC[slug]?.let { java.time.Instant.parse(it).toEpochMilli() } ?: 0L
                    val prefix = "ppl-$slug-${PeopleRest.stamp(d)}-"
                    val strays = sentIds.count { it.startsWith(prefix) && it !in planned }
                    val now = System.currentTimeMillis()
                    val future = PeopleBackfill.gap(day, sentKeys).count { it.kind == "person_begin" && it.dueMs > now }
                    val before = PeopleBackfill.gap(day, sentKeys).count { it.kind == "person_begin" && it.dueMs < first }
                    println(
                        "  gap check $slug $d: ledger ids not in this plan: $strays; gap sessions that begin before the first live event: $before; " +
                            "gap sessions whose begin is still in the future (the live run will send them): $future",
                    )
                }
            }
        }
        report(rows, firstLive, cap)
        if (mode == "batch") {
            val bs = PeopleBatch.batches(rows.flatMap { it.lines })
            println(
                "batch mode: ${stores.joinToString(",")} ${dates.first()}..${dates.last()}: ${PeopleBackfill.begins(
                    rows.flatMap {
                        it.lines
                    },
                )} visits, " +
                    "${PeopleBackfill.count(rows.flatMap { it.lines }, "person_impression")} impressions, ${bs.size} batch requests " +
                    "(begins ${bs.count { it.kind == "person_begin" }}, impressions ${bs.count { it.kind == "person_impression" }}, " +
                    "closes ${bs.count { it.kind == "person_close" }}); at $cap/min = %.1f minutes".format(bs.size / cap.toDouble()),
            )
        }
        val bad = checkIdentity(rows, sentIds).toMutableList()
        stores.forEach { slug ->
            val floor = PeopleBackfill.beginFloorMs(keys.store(slug).zone, from)
            val ls = rows.filter { it.slug == slug }.flatMap { it.lines }
            val earliest = ls.filter { it.kind == "person_begin" }.minOfOrNull { it.dueMs }
            println("earliest begin $slug: ${earliest?.let { java.time.Instant.ofEpochMilli(it) }} (floor ${java.time.Instant.ofEpochMilli(floor)})")
            val early = PeopleBackfill.beforeFloor(ls, floor)
            if (early.isNotEmpty()) bad += "$slug: ${early.size} begins before the floor ${java.time.Instant.ofEpochMilli(floor)}"
        }
        if (bad.isNotEmpty()) return refuse(bad.take(20))
        env("M8TRX_PEOPLE_BACKFILL_REPORT")?.let { writeCsv(Path.of(it), rows) }
        if (!live) {
            println("DRY: ${PeopleBackfill.LIVE_ENV} is not true; nothing was sent, no client was built, no plan file was written.")
            return 0
        }
        val slug = stores.single()
        val dir = out.resolve(slug)
        if (mode == "batch") {
            val lines = rows.filter { it.slug == slug }.flatMap { it.lines }
            return runBatch(slug, lines, dir, out, cap, bearer!!)
        }
        val items = PeopleLivePlan.merge(listOf(slug to rows.filter { it.slug == slug }.flatMap { it.lines }))
        val ops = env("M8TRX_OPS_DIR") ?: "/opt/m8trx/twin-ops"
        val hold = Path.of(env("M8TRX_PEOPLE_BACKFILL_HOLD_FILE") ?: "$ops/people-HOLD")
        if (Files.exists(hold)) {
            log.error("REFUSING: HOLD is set ({}): nothing is sent until it is removed", hold)
            return 6
        }
        Files.createDirectories(dir)
        Files.createDirectories(out)
        FileChannel.open(out.resolve("backfill.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            val lock =
                ch.tryLock()
                    ?: return refuse(
                        listOf("another backfill run holds ${out.resolve("backfill.lock")} (one backfill request at a time, across stores)"),
                    )
            lock.use {
                PeopleLivePlan.write(dir.resolve("plan.jsonl"), items)
                val transport = PeopleRestHttp.create(true, env("M8TRX_PEOPLE_REST_BASE_URL") ?: PeopleRestHttp.DEFAULT_BASE, bearer!!)
                val runner = PeopleLiveRunner(
                    items,
                    transport,
                    PeopleLedger(dir.resolve("sent-ledger.tsv")),
                    PeopleLiveParams(cap),
                    System::currentTimeMillis,
                    { Thread.sleep(it) },
                    { Files.exists(hold) },
                    dir,
                )
                val end = runner.run()
                println("people-rest backfill $slug: $end ${runner.haltReason.orEmpty()} counts=${runner.counters.toJson()}")
                return when (end) {
                    PeopleLiveRunner.End.DONE -> 0
                    PeopleLiveRunner.End.HOLD -> 6
                    PeopleLiveRunner.End.HALTED -> 1
                }
            }
        }
    }

    private fun runBatch(slug: String, lines: List<PeopleRest.Line>, dir: Path, out: Path, cap: Int, bearer: String): Int {
        val ops = env("M8TRX_OPS_DIR") ?: "/opt/m8trx/twin-ops"
        val hold = Path.of(env("M8TRX_PEOPLE_BACKFILL_HOLD_FILE") ?: "$ops/people-HOLD")
        if (Files.exists(hold)) {
            log.error("REFUSING: HOLD is set ({}): nothing is sent until it is removed", hold)
            return 6
        }
        Files.createDirectories(dir)
        FileChannel.open(out.resolve("backfill.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            val lock = ch.tryLock() ?: return refuse(listOf("another backfill run holds ${out.resolve("backfill.lock")}"))
            lock.use {
                val ledger = PeopleLedger(dir.resolve("batch-sent-ledger.tsv"))
                val batches = PeopleBatch.batches(lines, ledger::isSent)
                val transport = PeopleRestHttp.create(true, env("M8TRX_PEOPLE_REST_BASE_URL") ?: PeopleRestHttp.DEFAULT_BASE, bearer)
                val runner = PeopleBatchRunner(
                    batches,
                    transport,
                    ledger,
                    cap,
                    System::currentTimeMillis,
                    { Thread.sleep(it) },
                    { Files.exists(hold) },
                    dir,
                )
                val end = runner.run()
                println(
                    "people-rest backfill (batch) $slug: $end ${runner.haltReason.orEmpty()} requests=${runner.requests} items=${runner.itemsSent}",
                )
                return when (end) {
                    PeopleBatchRunner.End.DONE -> 0
                    PeopleBatchRunner.End.HOLD -> 6
                    PeopleBatchRunner.End.HALTED -> 1
                }
            }
        }
    }

    private fun refuse(reasons: List<String>): Int {
        reasons.forEach { log.error("REFUSING: {}", it) }
        return 3
    }

    private fun writeCsv(file: Path, rows: List<DayRow>) {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val sb = StringBuilder("store,date,weekday,mode,visits,impressions,closes,partial_sessions\n")
        rows.forEach {
            sb.append(
                "${it.slug},${it.date},${it.date.dayOfWeek},${it.mode},${PeopleBackfill.begins(it.lines)}," +
                    "${PeopleBackfill.count(it.lines, "person_impression")},${PeopleBackfill.count(it.lines, "person_close")},${it.partial}\n",
            )
        }
        Files.writeString(file, sb.toString())
    }

    /** Ids and order across all days and stores, plus against the live ledgers (the planned lines must not be in them). */
    private fun checkIdentity(rows: List<DayRow>, sentIds: Set<String>): List<String> {
        val out = ArrayList<String>()
        val allBegins = rows.flatMap { r -> r.lines.filter { it.kind == "person_begin" }.map { PeopleWindow.sessionKey(it.id) } }
        val uuids = allBegins.map { PeopleRest.sessionUuid(it) }.toSet().size
        println("identity: ${allBegins.size} visits, ${allBegins.toSet().size} distinct trackingIds, $uuids distinct UUIDs")
        if (allBegins.toSet().size != allBegins.size) out += "a trackingId repeats across days or stores"
        val clash = rows.flatMap { it.lines }.count { it.id in sentIds }
        println("identity: planned lines already in a live ledger: $clash")
        if (clash > 0) out += "$clash planned lines are already in a live ledger (would be counted twice)"
        rows.forEach { r -> PeopleBackfill.problems(r.lines).forEach { out += "${r.slug} ${r.date}: $it" } }
        println("order: ${if (out.isEmpty()) "every visit is begin <= impressions <= close" else "${out.size} problems"}")
        return out
    }

    private fun report(rows: List<DayRow>, firstLive: LocalDate, cap: Int) {
        println("people-rest BACKFILL plan (store-local days, full day 00:00-23:59; $firstLive gap-only)")
        println("%-16s %-10s %-9s %-4s %7s %12s %7s %7s".format("store", "date", "weekday", "mode", "visits", "impressions", "closes", "partial"))
        rows.forEach {
            println(
                "%-16s %-10s %-9s %-4s %7d %12d %7d %7d".format(
                    it.slug,
                    it.date,
                    it.date.dayOfWeek,
                    it.mode,
                    PeopleBackfill.begins(it.lines),
                    PeopleBackfill.count(it.lines, "person_impression"),
                    PeopleBackfill.count(it.lines, "person_close"),
                    it.partial,
                ),
            )
        }
        println("--- summary (full days only for the means) ---")
        println("%-16s %6s %12s %12s %9s".format("store", "days", "wkday mean", "wkend mean", "events"))
        var all = 0
        rows.groupBy { it.slug }.forEach { (s, rs) ->
            val full = rs.filter { it.mode == "full" }
            val wd = full.filter { !PeopleBackfill.isWeekend(it.date) }.map { PeopleBackfill.begins(it.lines) }
            val we = full.filter { PeopleBackfill.isWeekend(it.date) }.map { PeopleBackfill.begins(it.lines) }
            val ev = rs.sumOf { it.lines.size }
            all += ev
            println("%-16s %6d %12.1f %12.1f %9d".format(s, rs.size, wd.average(), we.average(), ev))
        }
        println("--- requests per site (2 per visit + 1 per impression; batched = sizing at ${PeopleBackfill.BATCH_SIZE} items per request) ---")
        println("%-16s %8s %12s %10s %10s".format("store", "visits", "impressions", "requests", "batched"))
        var tv = 0
        var ti = 0
        var tr = 0
        var tb = 0
        rows.groupBy { it.slug }.forEach { (s, rs) ->
            val ls = rs.flatMap { it.lines }
            val v = PeopleBackfill.begins(ls)
            val i = PeopleBackfill.count(ls, "person_impression")
            val b = rs.sumOf { PeopleBackfill.batchedRequests(it.lines) }
            tv += v
            ti += i
            tr += ls.size
            tb += b
            println("%-16s %8d %12d %10d %10d".format(s, v, i, ls.size, b))
        }
        println("%-16s %8d %12d %10d %10d  (batched: per store-day)".format("all", tv, ti, tr, tb))
        println("total events: $all; at $cap/min = %.1f hours".format(all / cap / 60.0))
    }
}

fun main() {
    exitProcess(PeopleRestBackfillMain.run())
}
