package com.m8trx.twin.storeops

import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.storeday.PeopleRest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.exitProcess

/**
 * `./gradlew storeDayPeopleRestLiveSelfTest` - the live people-by-REST sender, offline with a fake transport on a simulated clock. No network,
 * no credentials; files only under `build/people-rest-live-selftest/`.
 */
private val OUT: Path = Path.of("build/people-rest-live-selftest")
private val ZONE = ZoneId.of("America/Denver")
private val DATE = LocalDate.parse("2026-11-23")
private val T0 = DATE.atStartOfDay(ZONE).toInstant().toEpochMilli()
private const val SLUG = "dec-us-nyc"

private var failures = 0

private fun check(ok: Boolean, msg: () -> String) {
    if (!ok) {
        failures++
        println("      FAIL ${msg()}")
    }
}

private fun body(vararg kv: Pair<String, String>) = ConnectMappers.camel.createObjectNode().also { n -> kv.forEach { n.put(it.first, it.second) } }

/** One session: begin at [startMin], [imps] impressions every 2 min after it, close [lenMin] after the begin. */
private fun session(n: Int, startMin: Int, imps: Int, lenMin: Int = 20, slug: String = SLUG): List<PeopleRest.Line> {
    val base = "ppl-$slug-20261123-s$n"
    val t = T0 + startMin * 60_000L
    val out = ArrayList<PeopleRest.Line>()
    out +=
        PeopleRest.Line(
            t, PeopleRest.RANK_BEGIN, 0, "person_begin", "$base-b", "POST", PeopleRest.SESSIONS_PATH, null,
            body(
                "id" to PeopleRest.sessionUuid(base),
                "trackingId" to base,
            ),
            "s$n",
        )
    for (i in 1..imps) {
        out += PeopleRest.Line(
            t + i * 120_000L, PeopleRest.RANK_IMPRESSION, i, "person_impression", "$base-i$i", "POST", PeopleRest.IMPRESSIONS_PATH, "$base-b",
            body("trackingId" to base), "s$n",
        )
    }
    out += PeopleRest.Line(
        t + lenMin * 60_000L, PeopleRest.RANK_CLOSE, 99, "person_close", "$base-c", "PATCH",
        "${PeopleRest.SESSIONS_PATH}/${PeopleRest.sessionUuid(
            base,
        )}",
        "$base-b",
        body("exitTime" to "x"), "s$n",
    )
    return out
}

private fun plan(vararg s: List<PeopleRest.Line>): List<SendItem> = PeopleLivePlan.merge(listOf(SLUG to s.toList().flatten()))

/** A scripted transport: [script] answers (method, path, attempt-of-this-path); every call is recorded with the simulated time. */
private class Fake(val clock: SimClock, val script: (String, String, Int) -> PeopleAnswer? = { _, _, _ -> null }) : PeopleTransport {
    val calls = ArrayList<Triple<Long, String, String>>()
    val bodies = ArrayList<String>()
    var healthStatus = 200
    private val seen = HashMap<String, Int>()

    override fun health() = healthStatus to (if (healthStatus == 200) "{\"status\":\"ok\"}" else null)

    override fun call(method: String, path: String, body: String): PeopleAnswer {
        val n = seen.merge("$method $path $body", 1, Int::plus)!!
        calls += Triple(clock.nowMs, method, path)
        bodies += body
        return script(method, path, n) ?: PeopleAnswer(if (method == "POST") 201 else 200, "{}", null)
    }
}

private fun runner(items: List<SendItem>, f: Fake, ledger: PeopleLedger, cap: Int = 300, dir: Path? = null, hold: () -> Boolean = { false }) =
    PeopleLiveRunner(items, f, ledger, PeopleLiveParams(cap), { f.clock.nowMs }, { f.clock.advance(it) }, hold, dir)

private fun refusals() {
    val nine = PeopleRest.STORES
    fun r(
        live: Boolean = true,
        backfill: Boolean = false,
        stores: List<String> = listOf(SLUG),
        confirm: String? = SLUG,
        bearer: Boolean = true,
        backdated: List<String> = emptyList(),
        resuming: Boolean = false,
    ) = PeopleLiveGate.refusals(live, backfill, stores, confirm, bearer, backdated, resuming)
    check(nine.size == 9 && PeopleRest.DENVER !in nine && SLUG in nine) { "nine non-Denver stores, $SLUG among them" }
    check(r().isEmpty()) { "a good live run is not refused: ${r()}" }
    check(r(stores = listOf(PeopleRest.DENVER), confirm = PeopleRest.DENVER).any { "edge" in it }) { "Denver refused" }
    check(r(stores = listOf(SLUG, PeopleRest.DENVER), confirm = "$SLUG,${PeopleRest.DENVER}").isNotEmpty()) { "Denver refused in a list" }
    check(r(stores = listOf("dec-xx-nowhere"), confirm = "dec-xx-nowhere").isNotEmpty()) { "an unknown store refused" }
    check(r(confirm = null).any { "CONFIRM is not set" in it }) { "no confirm refused" }
    check(r(confirm = "dec-us-other").any { "does not match" in it }) { "confirm mismatch refused" }
    check(
        r(stores = nine.take(2), confirm = nine.take(1).joinToString(",")).any {
            "does not match" in it
        },
    ) { "a confirm naming fewer stores refused" }
    check(r(stores = nine.take(2), confirm = nine.take(2).reversed().joinToString(",")).isEmpty()) { "confirm order does not matter" }
    check(r(backfill = true).any { "backfill" in it }) { "backfill refused" }
    check(r(live = false, backfill = true).any { "backfill" in it }) { "backfill refused even dry" }
    check(r(bearer = false).any { PeopleLiveGate.BEARER_ENV in it }) { "no bearer refused" }
    check(r(backdated = listOf(SLUG)).any { "backfill" in it }) { "a past date is backfill, refused" }
    check(r(backdated = listOf(SLUG), resuming = true).isEmpty()) { "a frozen plan may be resumed after midnight" }
    check(r(live = false, confirm = null, bearer = false).isEmpty()) { "dry needs neither confirm nor bearer" }
    check(runCatching { PeopleRestLiveGuard.liveClientWithoutLive() }.exceptionOrNull() is IllegalArgumentException) { "no client without live" }
}

private fun window() {
    check(PeopleWindow.parseMinutes("09:30") == 570 && PeopleWindow.parseMinutes("24:00") == 1440) { "HH:mm parse" }
    check(runCatching { PeopleWindow.parseMinutes("9:75") }.isFailure) { "bad minutes refused" }
    val all = session(1, 5, 2) + session(2, 59, 3, lenMin = 40) + session(3, 60, 1) + session(4, 119, 1, lenMin = 90) + session(5, 125, 1)
    val sel = PeopleWindow.select(all, ZONE, DATE, 60, 120)
    val ids = sel.map { it.id }.toSet()
    check(
        sel.none {
            "-s1-" in it.id || "-s2-" in it.id || "-s5-" in it.id
        },
    ) { "sessions beginning before FROM (or at/after UNTIL) are skipped whole" }
    check(ids.containsAll(listOf("ppl-$SLUG-20261123-s3-b", "ppl-$SLUG-20261123-s3-c", "ppl-$SLUG-20261123-s4-b"))) { "begin in [FROM, UNTIL) is in" }
    val s4 = sel.filter { "-s4-" in it.id }
    check(s4.size == 3 && s4.last().dueMs > T0 + 120 * 60_000L) { "a session begun in the window keeps its close after UNTIL" }
    check(PeopleWindow.select(all, ZONE, DATE, 0, 1440).size == all.size) { "the full day keeps everything" }
    check(PeopleWindow.select(all, ZONE, DATE, 60, 61).count { it.kind == "person_begin" } == 1) { "FROM inclusive, UNTIL exclusive" }
    val c = PeopleLivePlan.counts(plan(sel))
    check(c[SLUG] == mapOf("begins" to 2, "closes" to 2, "impressions" to 2)) { "expected counts per store and kind: $c" }
}

private fun waitsForBegin() {
    val items = plan(session(1, 0, 2, lenMin = 10))
    val clock = SimClock(T0)
    // the begin answers 503 twice: the impressions and the close must wait and never be called first
    val f = Fake(clock) { m, p, n -> if (m == "POST" && p == PeopleRest.SESSIONS_PATH && n <= 2) PeopleAnswer(503, null, null) else null }
    val end = runner(items, f, PeopleLedger(null)).run()
    check(end == PeopleLiveRunner.End.DONE) { "done after the begin recovers ($end)" }
    check(f.calls.first().third == PeopleRest.SESSIONS_PATH && f.calls.size == 3 + 2 + 1) {
        "begin retried, then impressions, then close: ${f.calls.map { it.third }}"
    }
    check(f.calls.last().second == "PATCH") { "the close is last" }
    // the sender itself refuses an impression whose begin is not accepted
    val s = PeopleLiveSender(f, PeopleLedger(null), PeopleCounters(), { 0L })
    val imp = items.first { PeopleLivePlan.wireKind(it) == "person_impression" }
    val before = f.calls.size
    check(s.send(imp) == Outcome.Http(422) && f.calls.size == before) { "an impression before its begin is refused locally, nothing sent" }
    // a begin that never gets through: the lane is held at the head, nothing else is sent
    val f2 = Fake(SimClock(T0)) { m, p, _ -> if (m == "POST" && p == PeopleRest.SESSIONS_PATH) PeopleAnswer(503, null, null) else null }
    val r2 = runner(items, f2, PeopleLedger(null))
    val e2 = r2.run()
    check(
        e2 == PeopleLiveRunner.End.HALTED && f2.calls.all {
            it.third == PeopleRest.SESSIONS_PATH
        },
    ) { "a begin that keeps failing halts with nothing behind it sent" }
}

private fun due() {
    val items = plan(session(1, 0, 1, lenMin = 10))
    val clock = SimClock(T0 - 5 * 60_000L)
    val f = Fake(clock)
    runner(items, f, PeopleLedger(null)).run()
    check(f.calls.size == 3) { "all three sent" }
    check(f.calls.indices.all { f.calls[it].first >= items[it].dueMs }) { "nothing is sent before its due time: ${f.calls.map { it.first - T0 }}" }
}

private fun resume() {
    val dir = OUT.resolve("resume")
    dir.toFile().deleteRecursively()
    val items = plan(session(1, 0, 2), session(2, 3, 1))
    val f1 = Fake(SimClock(T0))
    val first = PeopleLedger(dir.resolve("sent-ledger.tsv"))
    // stop (HOLD) after three sends
    var stop = false
    val r1 = runner(items, f1, first, dir = dir, hold = { stop || f1.calls.size >= 3 })
    check(r1.run() == PeopleLiveRunner.End.HOLD && first.size == 3) { "HOLD stops cleanly after the request in flight (${first.size})" }
    val f2 = Fake(SimClock(T0))
    val second = PeopleLedger(dir.resolve("sent-ledger.tsv"))
    val r2 = runner(items, f2, second, dir = dir)
    check(r2.run() == PeopleLiveRunner.End.DONE) { "second run done" }
    check(f2.calls.size == items.size - 3) { "the rerun skips what the ledger holds (${f2.calls.size} sent of ${items.size - 3})" }
    val ids = Files.readAllLines(dir.resolve("sent-ledger.tsv")).map { it.substringBefore('\t') }
    check(ids.size == items.size && ids.toSet().size == items.size) { "every id once in the ledger" }
    check(Files.exists(dir.resolve("run-complete.json")) && Files.exists(dir.resolve("progress.json")) && Files.exists(dir.resolve("live-run.log"))) {
        "progress, log and run-complete written"
    }
    val rc = ConnectMappers.snake.readTree(Files.readString(dir.resolve("run-complete.json")))
    check(rc.path("counts_this_run").path(SLUG).path("closes").path("sent").asInt() == 2) { "counts per store and kind: $rc" }
    stop = true
}

private fun cap() {
    // a burst of 400 sessions all due at once, with 429 and 502 injected, under a cap of 60
    val lines = (1..400).flatMap { session(it, 0, 1, lenMin = 1) }
    val items = plan(lines)
    val clock = SimClock(T0 + 3_600_000L)
    val f = Fake(clock) { _, p, n ->
        when {
            p == PeopleRest.IMPRESSIONS_PATH && n == 1 && clock.nowMs % 7 == 0L -> PeopleAnswer(502, null, null)
            else -> null
        }
    }
    var k = 0
    val f3 = object : PeopleTransport by f {
        override fun call(method: String, path: String, body: String): PeopleAnswer {
            k++
            if (k % 97 == 0) {
                f.calls += Triple(clock.nowMs, method, path)
                return PeopleAnswer(429, null, 5)
            }
            return f.call(method, path, body)
        }
    }
    val end = PeopleLiveRunner(items, f3, PeopleLedger(null), PeopleLiveParams(60), { clock.nowMs }, { clock.advance(it) }, { false }, null).run()
    check(end == PeopleLiveRunner.End.DONE) { "burst drains ($end)" }
    val peak = PeoplePace.peakPerMinute(f.calls.map { it.first })
    check(peak <= 60) { "no 60 s window holds more than the cap (peak $peak of 60) even with retries" }
    check(f.calls.size >= items.size) { "every request made" }
}

private fun decisions() {
    val one = plan(session(1, 0, 0, lenMin = 5))
    fun go(answer: (String, Int) -> PeopleAnswer?, ledger: PeopleLedger = PeopleLedger(null)): Triple<PeopleLiveRunner, Fake, PeopleLiveRunner.End> {
        val f = Fake(SimClock(T0)) { m, _, n -> answer(m, n) }
        val r = runner(one, f, ledger)
        return Triple(r, f, r.run())
    }
    // 429: waits at least a minute (Retry-After honoured when longer), then goes on
    val (_, f429, e429) = go({ m, n -> if (m == "POST" && n == 1) PeopleAnswer(429, null, 90) else null })
    check(e429 == PeopleLiveRunner.End.DONE && f429.calls[1].first - f429.calls[0].first >= 90_000L) { "429 waits Retry-After (90 s)" }
    // 5xx: retried after a health check, same request, nothing skipped
    val (r5, f5, e5) = go({ m, n -> if (m == "PATCH" && n == 1) PeopleAnswer(500, null, null) else null })
    check(e5 == PeopleLiveRunner.End.DONE && f5.calls.size == 3 && r5.counters.cell(SLUG, "closes").retried == 1) { "500 retried once" }
    check(r5.healthChecks >= 2) { "a 5xx goes back through the health gate (${r5.healthChecks})" }
    // unreachable
    val f6 = Fake(SimClock(T0))
    var thrown = false
    val t6 = object : PeopleTransport by f6 {
        override fun call(method: String, path: String, body: String): PeopleAnswer {
            if (!thrown) {
                thrown = true
                throw IOException("reset")
            }
            return f6.call(method, path, body)
        }
    }
    check(
        PeopleLiveRunner(one, t6, PeopleLedger(null), PeopleLiveParams(), { f6.clock.nowMs }, { f6.clock.advance(it) }, { false }, null).run() ==
            PeopleLiveRunner.End.DONE,
    ) { "no answer retried" }
    // 401: one more try, then halt, nothing after it sent
    val (r401, f401, e401) = go({ _, _ -> PeopleAnswer(401, null, null) })
    check(e401 == PeopleLiveRunner.End.HALTED && f401.calls.size == 2 && r401.haltReason != null) {
        "401: retry once, then halt (${f401.calls.size})"
    }
    val (_, f403, e403) = go({ _, _ -> PeopleAnswer(403, null, null) })
    check(e403 == PeopleLiveRunner.End.HALTED && f403.calls.size == 2) { "403: retry once, then halt" }
    // 402: halt at once with a clear message
    val (r402, f402, e402) = go({ _, _ -> PeopleAnswer(402, "{\"error\":\"NOT_ENTITLED\"}", null) })
    check(e402 == PeopleLiveRunner.End.HALTED && f402.calls.size == 1 && r402.haltReason!!.startsWith("402 NOT_ENTITLED")) {
        "402 halts clearly: ${r402.haltReason}"
    }
    check(r402.counters.cell(SLUG, "begins").refused == 1) { "a refusal is counted" }
    // 400: refused, halts
    val (_, f400, e400) = go({ _, _ -> PeopleAnswer(400, null, null) })
    check(e400 == PeopleLiveRunner.End.HALTED && f400.calls.size == 1) { "400 halts" }
    val uuid = PeopleRest.sessionUuid("ppl-$SLUG-20261123-s1")
    // the 201 echoes our UUID: fine, the close PATCHes the same UUID, the answer's id is recorded
    val led = PeopleLedger(null)
    val (r7, f7, e7) = go({ m, _ -> if (m == "POST") PeopleAnswer(201, "{\"id\":\"$uuid\"}", null) else null }, led)
    check(e7 == PeopleLiveRunner.End.DONE && f7.calls.last().third == "${PeopleRest.SESSIONS_PATH}/$uuid") {
        "the close PATCHes our UUID: ${f7.calls.last().third}"
    }
    check(led.serverId("ppl-$SLUG-20261123-s1-b") == uuid && r7.counters.missingServerIds == 0) { "the echoed id is recorded" }
    // the 201 has no parseable id: a warning, carry on with ours, never the ppl-...-b line id
    val (r8, f8, e8) = go({ _, _ -> null })
    check(e8 == PeopleLiveRunner.End.DONE && f8.calls.last().third.endsWith("/$uuid") && r8.counters.missingServerIds == 1) {
        "no id in the 201: carry on with ours"
    }
    check(r8.events.any { "WARNING" in it } && f8.calls.none { "ppl-" in it.third }) { "warned, and no line id ever goes in a path" }
    // the 201 carries a DIFFERENT id: the server ignored ours, halt loudly, nothing after the begin is sent, begin not in the ledger
    val led9 = PeopleLedger(null)
    val (r9, f9, e9) = go({ m, _ -> if (m == "POST") PeopleAnswer(201, "{\"id\":\"11111111-2222-3333-4444-555555555555\"}", null) else null }, led9)
    check(e9 == PeopleLiveRunner.End.HALTED && f9.calls.size == 1 && r9.haltReason!!.contains("server_id_mismatch") && led9.size == 0) {
        "a different id in the 201 halts (${r9.haltReason})"
    }
}

private fun sessionId() {
    val items = plan(session(1, 0, 1, lenMin = 5))
    val uuid = PeopleRest.sessionUuid("ppl-$SLUG-20261123-s1")
    check(
        runCatching {
            java.util.UUID.fromString(uuid)
        }.isSuccess && uuid == PeopleRest.sessionUuid("ppl-$SLUG-20261123-s1"),
    ) { "deterministic UUID" }
    val beginBody = items.first { PeopleLivePlan.wireKind(it) == "person_begin" }.payload.path("body")
    check(beginBody.path("id").asText() == uuid) { "the begin body carries the UUID id" }
    val f = Fake(SimClock(T0))
    check(runner(items, f, PeopleLedger(null)).run() == PeopleLiveRunner.End.DONE) { "run done" }
    check(ConnectMappers.snake.readTree(f.bodies.first()).path("id").asText() == uuid) { "the wire begin carries the UUID id" }
    check(f.calls.last().third == "${PeopleRest.SESSIONS_PATH}/$uuid") { "the close path uses the same UUID" }
    // the begin's answer is lost (no answer), the begin is resent: same body, same id
    val f2 = Fake(SimClock(T0))
    var lost = false
    val t2 = object : PeopleTransport by f2 {
        override fun call(method: String, path: String, body: String): PeopleAnswer {
            val a = f2.call(method, path, body)
            if (!lost && method == "POST" && path == PeopleRest.SESSIONS_PATH) {
                lost = true
                throw IOException("answer lost")
            }
            return a
        }
    }
    val e2 = PeopleLiveRunner(items, t2, PeopleLedger(null), PeopleLiveParams(), { f2.clock.nowMs }, { f2.clock.advance(it) }, { false }, null).run()
    val begins = f2.bodies.filter { "\"id\"" in it }
    check(e2 == PeopleLiveRunner.End.DONE && begins.size == 2 && begins[0] == begins[1]) {
        "a resent begin is byte-identical, same id (${begins.size})"
    }
    // a begin body without a UUID id is never sent
    val bad = items.first { PeopleLivePlan.wireKind(it) == "person_begin" }
    (bad.payload.path("body") as com.fasterxml.jackson.databind.node.ObjectNode).put("id", "ppl-x-b")
    val f3 = Fake(SimClock(T0))
    check(
        PeopleLiveSender(f3, PeopleLedger(null), PeopleCounters(), {
            0L
        }).send(bad) == Outcome.Http(422) && f3.calls.isEmpty(),
    ) { "a non-UUID id is not sent" }
}

private fun ledgerFailure() {
    val dir = OUT.resolve("ledger-fail")
    dir.toFile().deleteRecursively()
    Files.createDirectories(dir)
    // the ledger parent is a regular file: the append fails
    Files.writeString(dir.resolve("blocker"), "x")
    val led = PeopleLedger(dir.resolve("blocker").resolve("sent-ledger.tsv"))
    val items = plan(session(1, 0, 0, lenMin = 5))
    val f = Fake(SimClock(T0))
    val r = runner(items, f, led)
    check(r.run() == PeopleLiveRunner.End.HALTED && r.haltReason!!.contains("ledger_write_failed") && !led.isSent(items.first().extId)) {
        "a ledger write failure halts cleanly and the id is not marked sent (${r.haltReason})"
    }
}

private fun frozenPlan() {
    val items = plan(session(1, 0, 2), session(2, 3, 1))
    val f = OUT.resolve("plan.jsonl")
    PeopleLivePlan.write(f, items)
    val back = PeopleLivePlan.read(f)
    check(
        back.map {
            it.extId
        } == items.map { it.extId } && back.map { it.dueMs } == items.map { it.dueMs },
    ) { "the frozen plan reads back in order" }
    check(back.all { PeopleLivePlan.store(it) == SLUG && it.kind == PeoplePace.KIND }) { "store and lane kind survive" }
    check(back.map { it.rank } == items.map { it.rank }) { "ranks survive" }
    check(Instant.ofEpochMilli(back.first().dueMs).toString().endsWith("Z")) { "UTC due times" }
}

private fun noSecrets() {
    val src = Path.of("src/main/kotlin/com/m8trx/twin")
    val live = Files.readString(src.resolve("storeops/PeopleRestLive.kt")) + Files.readString(src.resolve("storeops/PeopleRestLiveMain.kt"))
    val names = listOf("java\\.net", "Http" + "Client", "Connect" + "Client", "Webhook" + "Client", "Connect" + "Http", "Sock" + "et")
    check(!Regex(names.joinToString("|")).containsMatchIn(live)) {
        "the live logic and main reference no network class (only peoplelive/PeopleRestHttp does)"
    }
    val all = Files.walk(src).use { s -> s.filter { it.toString().endsWith(".kt") && "PeopleRest" in it.fileName.toString() }.toList() }
    check(all.none { Regex("m8trx_[A-Za-z0-9]{8,}").containsMatchIn(Files.readString(it)) }) { "no key in the source" }
    check(LiveClients.constructed == 0) { "no live client was built in this self-test" }
}

private object PeopleRestLiveGuard {
    fun liveClientWithoutLive() = com.m8trx.twin.peoplelive.PeopleRestHttp.create(false, "http://localhost", "x")
}

fun main() {
    Files.createDirectories(OUT)
    val tests = listOf<Pair<String, () -> Unit>>(
        "refusals: Denver, confirm mismatch, backfill, past date, no bearer; dry needs neither confirm nor bearer" to ::refusals,
        "window: whole-session rule, FROM inclusive, UNTIL exclusive, close after UNTIL kept, expected counts" to ::window,
        "an impression and a close wait for their begin (retry, halt, local refusal)" to ::waitsForBegin,
        "nothing is sent before its due time" to ::due,
        "resume: HOLD stops cleanly, a rerun skips the sent-ledger, progress and run-complete written" to ::resume,
        "cap: a 400-session burst with 429 and 502 never exceeds the sliding 60 s cap" to ::cap,
        "decisions: 429 Retry-After, 5xx and no answer via health, 401/403 once then halt, 402 clear halt, 400 halts, server id used" to ::decisions,
        "begin carries a UUID id, the close uses it, a resent begin is identical" to ::sessionId,
        "a ledger write failure halts cleanly" to ::ledgerFailure,
        "frozen plan reads back" to ::frozenPlan,
        "no network class in the live logic, no key in the source" to ::noSecrets,
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
    println("storeDayPeopleRestLiveSelfTest: ${tests.size - bad}/${tests.size} passed")
    if (bad > 0) exitProcess(1)
}
