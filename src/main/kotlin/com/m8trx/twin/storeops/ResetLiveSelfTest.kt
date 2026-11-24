package com.m8trx.twin.storeops

import com.m8trx.twin.connect.ReceiveStock
import com.m8trx.twin.connect.RestockMoves
import com.m8trx.twin.connect.Sgtin96
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.bearer.ItemDetail
import com.m8trx.twin.connect.model.bearer.SpatialIdentityResponse
import com.m8trx.twin.ops.StoreStock
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Offline tests of the live reset path ([ResetLiveRunner], [ResetGate], [ResetBooks], [SpotCount], [BackroomZones], [OldDirectiveExport],
 * [LiveSender.receiveOutcome]) with a FAKE [OpsSender] and a virtual clock. Called from `storeOpsSelfTest`; nothing here touches a network.
 */
private val RT: Path = Path.of("build/storeops-selftest/reset-live")
private const val EAN = "3583780104122"
private const val SLUG = "dec-us-denver"

private fun epc(i: Int) = Sgtin96.encode(EAN, 40_000_000_000L + i)

private fun receiveItem(seq: Int, epcs: List<String>): SendItem {
    val payload = ConnectMappers.camel.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
        com.m8trx.twin.connect.model.bearer.ItemReceiveRequest(
            "@site",
            "@backroom_space",
            epcs.map {
                com.m8trx.twin.connect.model.bearer.ReceiveItem(it)
            },
        ),
    )
    return SendItem(seq, 0, 0, "receive", "rst-t-r-$seq", payload, epcs)
}

private fun moveItem(seq: Int, dest: String, epcs: List<String>): SendItem {
    val n = ConnectMappers.snake.createObjectNode()
    n.put("external_movement_id", "rst-t-m-$seq")
    if (dest.startsWith("BR-")) n.put("to_zone_id", "${Placeholders.ZONE_PREFIX}$dest") else n.put("to_fixture_code", dest)
    val a = n.putArray("items")
    epcs.forEach { a.addObject().put("epc", it) }
    return SendItem(seq, 0, 1, "move", "rst-t-m-$seq", n, epcs)
}

private fun directive(seq: Int, id: String): SendItem {
    val n = ConnectMappers.snake.createObjectNode()
    n.put("external_directive_id", id)
    return SendItem(seq, 0, 2, "planogram", id, n, emptyList(), afterLocalMidnight = true)
}

/** 6 receives of 3 EPCs, 3 moves of units already on hand, 7 moves of received units (one spans two receives), end + new directive. */
private class Schedule(tags: Int = 3) {
    val received = (0 until 6).map { r -> (0 until tags).map { epc(r * tags + it) } }
    val onHand = (0 until 9).map { epc(1000 + it) }
    val items: List<SendItem>

    init {
        val l = ArrayList<SendItem>()
        var s = 0
        received.forEach { l += receiveItem(s++, it) }
        onHand.chunked(3).forEach { l += moveItem(s++, "F-H", it) }
        received.forEachIndexed { i, r -> l += moveItem(s++, "F-$i", r) }
        l += moveItem(s++, "BR-02", listOf(received[0][0], received[5][2]))
        l += directive(s++, "rst-t-end-OLD")
        l += directive(s++, "PLNW-t")
        items = l
    }

    val inRun: Set<String> = received.flatten().toSet()
    val body: Set<String> get() = items.filter { it.kind != "planogram" }.map { it.extId }.toSet()
}

private class VirtualClock(start: Long) : ResetClock {
    val t = AtomicLong(start)

    override fun now(): Long = t.get()

    override fun sleep(ms: Long) {
        t.addAndGet(ms)
        Thread.sleep(1)
    }
}

private class FakeSender(private val sched: Schedule, private val clock: VirtualClock, private val receiveMs: Long = 4) : OpsSender {
    val order: MutableList<String> = Collections.synchronizedList(ArrayList())
    val acceptedEpcs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val violations: MutableList<String> = Collections.synchronizedList(ArrayList())
    val attempts = ConcurrentHashMap<String, AtomicInteger>()
    val sends = AtomicInteger()
    val bodies: MutableList<String> = Collections.synchronizedList(ArrayList())
    val directiveAt = ConcurrentHashMap<String, Long>()
    val orderAtDirective = ConcurrentHashMap<String, Set<String>>()
    var script: (SendItem, Int) -> Outcome? = { _, _ -> null }
    var peakReceives = AtomicInteger()
    var onReceiveStart: (Int) -> Unit = {}
    private val inFlight = AtomicInteger()

    /** The health answer for the n-th poll (1-based); the default is always ready. A test sets it to model a restart. */
    var healthScript: (Int) -> Pair<Int, String?> = { 200 to "{\"status\":\"ok\"}" }
    private val healthPolls = AtomicInteger()

    override fun health(): Pair<Int, String?> = healthScript(healthPolls.incrementAndGet())

    override fun send(item: SendItem): Outcome {
        sends.incrementAndGet()
        val n = attempts.computeIfAbsent(item.extId) { AtomicInteger() }.incrementAndGet()
        script(item, n)?.let { o ->
            if (o is Outcome.ReceiveAnswer) {
                Thread.sleep(receiveMs)
                acceptedEpcs += item.epcs - o.errors.mapNotNull { it.epc }.toSet()
                bodies += item.payload.toString()
                if (o.errors.isEmpty()) order += item.extId
            }
            return o
        }
        when (item.kind) {
            "receive" -> {
                val now = inFlight.incrementAndGet()
                peakReceives.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                onReceiveStart(now)
                Thread.sleep(receiveMs)
                acceptedEpcs += item.epcs
                inFlight.decrementAndGet()
            }

            "move" -> item.epcs.filter { it in sched.inRun && it !in acceptedEpcs }.takeIf { it.isNotEmpty() }
                ?.let { violations += "${item.extId} sent before the receive of $it was accepted" }

            "planogram" -> {
                directiveAt[item.extId] = clock.now()
                orderAtDirective[item.extId] = order.toSet()
            }
        }
        order += item.extId
        return Outcome.Accepted
    }
}

private fun params(
    lanes: Int,
    midnight: Long,
    statusEveryMs: Long = Long.MAX_VALUE / 4,
    perMin: Int = 0,
    perMinWithReceive: Int = perMin,
    healthWaitCapMs: Long = 600_000,
) = ResetLiveParams(
    healthWaitCapMs = healthWaitCapMs,
    moveItemsPerMin = perMin,
    moveItemsPerMinWithReceive = perMinWithReceive,
    receiveLanes = lanes,
    moveSpacingMs = 10,
    directiveSpacingMs = 10,
    healthPollMs = 10,
    idlePollMs = 5,
    midnightMs = midnight,
    progressEveryMs = 1,
    retryDelayMs = 5,
    statusEveryMs = statusEveryMs,
)

private fun run(
    sched: Schedule,
    sender: OpsSender,
    clock: VirtualClock,
    lanes: Int,
    ledger: SentLedger = SentLedger(null),
    policy: RetryPolicy = RetryPolicy(),
    midnight: Long = 1_000_000,
    books: ResetBooks? = null,
    hold: () -> Boolean = { false },
    lanesNow: (() -> Int)? = null,
    onEvent: (String) -> Unit = {},
    statusEveryMs: Long = Long.MAX_VALUE / 4,
    perMin: Int = 0,
    perMinWithReceive: Int = perMin,
    moveWatch: MoveResultsWatch? = null,
    healthWaitCapMs: Long = 600_000,
): ResetLiveRunner.Result = ResetLiveRunner(
    sched.items, sender, policy, ledger, books, params(lanes, midnight, statusEveryMs, perMin, perMinWithReceive, healthWaitCapMs), hold, clock,
    onEvent = onEvent,
    lanesNow = lanesNow ?: { lanes }, moveWatch = moveWatch,
).run()

fun resetLiveTests(): List<Pair<String, () -> Unit>> = listOf(
    "reset live: 3 receive lanes run at once; a move waits for its receives, on-hand moves do not; every item sent once" to ::liveDependency,
    "reset live: the directives go last, after every receive and move, and not before the store's local midnight" to ::liveDirectiveLast,
    "reset live: M8TRX_RESET_HOLD_DIRECTIVES sends every receive and move and NO directive (old plan stays in force)" to ::liveHoldDirectives,
    "reset live: resume skips accepted ids; HOLD stops cleanly (no new request) and a rerun finishes the rest without resending" to ::liveResumeHold,
    "reset live: a receive with errors[] stops the run, prints the entries, and is not marked accepted" to ::liveReceiveErrors,
    "reset live: internal_error tags are re-sent in a retry request (different body), moves wait for them; limits stop the night" to ::liveInternal,
    "reset live: a timeout / 504 on a receive waits 60 s before the same request is re-sent" to ::liveTimeout,
    "reset live: every move body is to_zone_id (none to_fixture_code), move-all gives every on-hand unit a move, zone ids cross-checked" to
        ::liveZoneMoves,
    "reset live: existence pre-check (items/details chunks <= 200, > 2% not in_stock stops, <= 2000 EPCs)" to ::liveExistence,
    "reset live: the post-check runs once, after every receive and move and 120 s after the last move, before the directives; it never gates" to
        ::livePostCheck,
    "reset live: lowering the lanes 4 -> 2 with 4 in flight starts nothing new until in flight is below 2 (claim under the lock)" to ::liveLanesDrop,
    "reset live: each internal_error retry round sends a different body (rotated tags)" to ::liveRetryBodies,
    "reset live: the receive lane count can change during the run (file), is clamped to 4, logged; a status line is logged" to ::liveLanesStatus,
    "reset live: 429 honours Retry-After, 503 backs off and retries, 10 failures in a row halt" to ::liveRetries,
    "reset live: owner file, CONFIRM, probe, old directive, spot count, bearer and lane-count refusals; each is satisfiable" to ::liveGate,
    "reset live: box ledger appended in the other steps' formats (received-stock.csv, placements.csv), never rewritten" to ::liveBooks,
    "reset live: receive answer parsing, back-room zone map, old-directive converter, spot count rule, distinct move bodies, no live client" to
        ::liveParts,
    "move budget: rolling 60 s item window, mixed batch sizes reach the limit and never exceed it, an oversize batch is not split" to ::budgetMath,
    "move budget: the rate follows whether a receive is due or in flight (150 beside receives, 200 alone); 240 is the ceiling" to ::budgetRateSwitch,
    "move results: pending over 60 s halts, refused halts, warning/failed halts, confirmed entries are counted; answers file rows before and after" to
        ::resultsWatch,
    "move results: the reset halts (resumable) on a pending move, a refused move and a move answered slower than 10 s" to ::resultsHalts,
    "reset live: a health outage (502, refused) is waited out and the run finishes from the ledger; past the cap it halts; a 4xx still halts" to
        ::liveWaitsOutRestart,
    "reset live: a move answered slowly just after an outage does not halt; the same answer in normal running still does" to ::liveSlowAfterOutage,
)

private fun liveDependency() {
    val sched = Schedule()
    val clock = VirtualClock(0)
    val sender = FakeSender(sched, clock)
    val res = run(sched, sender, clock, 3)
    check(res.end == ResetLiveRunner.End.DONE) { "run did not finish: ${res.end} ${res.haltReason}" }
    check(sender.violations.isEmpty()) { "dependency broken: ${sender.violations}" }
    check(sender.order.toSet() == sched.items.map { it.extId }.toSet() && sender.order.size == sched.items.size) { "every item sent exactly once" }
    check(sender.peakReceives.get() >= 2) { "with 3 lanes receives overlap, peak was ${sender.peakReceives.get()}" }
    val lastReceive = sched.items.filter { it.kind == "receive" }.maxOf { sender.order.indexOf(it.extId) }
    val firstOnHand = sched.items.filter { it.kind == "move" && it.epcs.all { e -> e !in sched.inRun } }.minOf { sender.order.indexOf(it.extId) }
    check(firstOnHand < lastReceive) { "on-hand moves start before the receives are done (own lane): $firstOnHand vs $lastReceive" }
    val one = FakeSender(sched, VirtualClock(0)).also { run(sched, it, VirtualClock(0), 1) }
    check(one.violations.isEmpty() && one.peakReceives.get() == 1) { "one lane: one request at a time, peak ${one.peakReceives.get()}" }
    check(runCatching { ResetLiveParams(5, 1, 1, 1, 1, 0) }.isFailure && runCatching { ResetLiveParams(0, 1, 1, 1, 1, 0) }.isFailure) {
        "lanes beyond 1..4 refused"
    }
}

private fun liveHoldDirectives() {
    val sched = Schedule()
    val clock = VirtualClock(0)
    val sender = FakeSender(sched, clock)
    val events = mutableListOf<String>()
    val p = params(3, 0)
    val held = ResetLiveParams(
        receiveLanes = p.receiveLanes, moveSpacingMs = p.moveSpacingMs, directiveSpacingMs = p.directiveSpacingMs,
        healthPollMs = p.healthPollMs, idlePollMs = p.idlePollMs, midnightMs = 0, progressEveryMs = 1, retryDelayMs = 5,
        statusEveryMs = Long.MAX_VALUE / 4, holdDirectives = true,
    )
    val res = ResetLiveRunner(
        sched.items, sender, RetryPolicy(), SentLedger(null), null, held, { false }, clock,
        onEvent = { synchronized(events) { events += it } },
    ).run()
    check(res.end == ResetLiveRunner.End.DONE) { "done: ${res.end} ${res.haltReason}" }
    check(sender.directiveAt.isEmpty() && "rst-t-end-OLD" !in sender.order && "PLNW-t" !in sender.order) {
        "no directive sent: ${sender.directiveAt}"
    }
    check(sender.order.toSet() == sched.body.toSet()) { "every receive and move sent" }
    check(events.any { it.contains("directive steps HELD") && it.contains("PLNW-t") }) { "the hold is logged: $events" }
}

private fun liveDirectiveLast() {
    val sched = Schedule()
    val clock = VirtualClock(0)
    val sender = FakeSender(sched, clock)
    val midnight = 5_000_000L
    val res = run(sched, sender, clock, 3, midnight = midnight)
    check(res.end == ResetLiveRunner.End.DONE) { "done" }
    check(sender.order.takeLast(2) == listOf("rst-t-end-OLD", "PLNW-t")) { "old end, then the new plan, last: ${sender.order.takeLast(3)}" }
    check(sender.directiveAt.values.all { it >= midnight }) { "gated to after midnight: ${sender.directiveAt} vs $midnight" }
    check(sender.orderAtDirective.getValue("rst-t-end-OLD").containsAll(sched.body)) {
        "every receive and move was accepted before the first directive"
    }
    // before the midnight the tail waits (HOLD still honoured): hold stops it while it waits
    val c2 = VirtualClock(0)
    val s2 = FakeSender(sched, c2)
    var holdAfterBody = false
    val r2 = run(sched, s2, c2, 2, midnight = 900_000_000L, hold = { holdAfterBody || s2.order.containsAll(sched.body).also { holdAfterBody = it } })
    check(r2.end == ResetLiveRunner.End.HOLD && s2.directiveAt.isEmpty()) {
        "HOLD while waiting for midnight: ${r2.end}, directives ${s2.directiveAt}"
    }
}

private fun liveResumeHold() {
    val sched = Schedule()
    val file = RT.resolve("resume-sent.tsv")
    Files.createDirectories(RT)
    Files.deleteIfExists(file)
    // resume: two receives and the move that needs them are already in the ledger
    val led0 = SentLedger(file)
    listOf(sched.items[0], sched.items[1], sched.items.first { it.extId == "rst-t-m-${6 + 3}" }).forEach { led0.markSent(it) }
    val c = VirtualClock(0)
    val s = FakeSender(sched, c)
    s.acceptedEpcs += sched.items[0].epcs + sched.items[1].epcs
    val r = run(sched, s, c, 2, SentLedger(file))
    check(r.end == ResetLiveRunner.End.DONE && r.skippedByLedger == 3) { "resume skipped 3, got ${r.skippedByLedger} ${r.end}" }
    check(
        s.order.none {
            it == sched.items[0].extId || it == sched.items[1].extId
        } && s.order.size == sched.items.size - 3,
    ) { "accepted ids never re-sent" }
    // HOLD after 5 sends: nothing new starts, the run says HOLD; a rerun does the rest without resending anything accepted
    val f2 = RT.resolve("hold-sent.tsv")
    Files.deleteIfExists(f2)
    val c1 = VirtualClock(0)
    val s1 = FakeSender(sched, c1)
    val r1 = run(sched, s1, c1, 1, SentLedger(f2), hold = { s1.sends.get() >= 5 })
    check(r1.end == ResetLiveRunner.End.HOLD && s1.order.size in 5..6 && s1.directiveAt.isEmpty()) {
        "HOLD stopped it: ${r1.end}, ${s1.order.size} sent, directives ${s1.directiveAt}"
    }
    val c2 = VirtualClock(0)
    val s2 = FakeSender(sched, c2)
    s2.acceptedEpcs += s1.acceptedEpcs
    val r2 = run(sched, s2, c2, 2, SentLedger(f2))
    check(r2.end == ResetLiveRunner.End.DONE && s2.violations.isEmpty()) { "rerun finished: ${r2.end} ${r2.haltReason} ${s2.violations}" }
    check(s1.order.intersect(s2.order.toSet()).isEmpty() && (s1.order + s2.order).toSet().size == sched.items.size) {
        "together exactly the schedule, nothing twice"
    }
}

private fun liveReceiveErrors() {
    val sched = Schedule()
    val f = RT.resolve("errors-sent.tsv")
    Files.deleteIfExists(f)
    val c = VirtualClock(0)
    val s = FakeSender(sched, c)
    val bad = sched.items[2]
    s.script = { it, _ ->
        if (it.extId ==
            bad.extId
        ) {
            Outcome.ReceiveAnswer(
                1,
                1,
                listOf(ItemError(bad.epcs[1], "unknown_product: no product for the EAN"), ItemError(bad.epcs[2], "not_admitted")),
            )
        } else {
            null
        }
    }
    val events = ArrayList<String>()
    val led = SentLedger(f)
    val r = ResetLiveRunner(sched.items, s, RetryPolicy(), led, null, params(3, 1), {
        false
    }, c, onEvent = { e -> synchronized(events) { events.add(e) } }).run()
    check(r.end == ResetLiveRunner.End.HALTED && r.haltReason!!.contains(bad.extId) && r.haltReason!!.contains("unknown_product")) {
        "halted: ${r.haltReason}"
    }
    check(events.any { it.contains("unknown_product") } && events.any { it.contains("not_admitted") }) { "the entries are printed: $events" }
    check(!led.isSent(bad.extId) && !SentLedger(f).isSent(bad.extId)) { "the refused request is not marked accepted" }
    check(s.directiveAt.isEmpty() && "rst-t-end-OLD" !in s.order) { "nothing goes past it: no directive" }
    check(sched.items.filter { it.kind == "move" && bad.epcs.any { e -> e in it.epcs } }.none { it.extId in s.order }) {
        "no move of an EPC in the refused receive"
    }
}

private fun liveInternal() {
    val f = RT.resolve("internal-sent.tsv")
    Files.deleteIfExists(f)
    val sched = Schedule()
    val c = VirtualClock(0)
    val s = FakeSender(sched, c)
    val target = sched.items[2]
    s.script = { it, n ->
        if (it.extId == target.extId &&
            n == 1
        ) {
            Outcome.ReceiveAnswer(2, 2, listOf(ItemError(target.epcs[1], "internal_error: pool is short")))
        } else {
            null
        }
    }
    val r = run(sched, s, c, 2, SentLedger(f), midnight = 0)
    check(r.end == ResetLiveRunner.End.DONE && r.retryRequests == 1) { "the night goes on past a handful: ${r.end} ${r.haltReason}" }
    check(s.violations.isEmpty()) { "no move before its tag was counted: ${s.violations}" }
    check("${target.extId}-retry1" in s.order) { "the retry request was sent" }
    check(s.bodies.size == 1 && s.bodies[0] == target.payload.toString()) { "the original body went once" }
    check(
        sched.items.filter { it.kind == "move" && target.epcs[1] in it.epcs }.all {
            s.order.indexOf(it.extId) >
                s.order.indexOf("${target.extId}-retry1")
        },
    ) {
        "a move holding the failed tag goes only after the retry counted it"
    }
    check(SentLedger(f).isSent(target.extId)) { "the original is marked accepted once its last tag is counted" }
    // limits: more than 10 in one request, or more than 5 in a row, stop; 5 in a row does not
    val big = Schedule(12)
    fun runWith(bad: List<Int>): ResetLiveRunner.Result {
        val c2 = VirtualClock(0)
        val s2 = FakeSender(big, c2)
        val t = big.items[1]
        s2.script = { it, n ->
            if (it.extId == t.extId &&
                n == 1
            ) {
                Outcome.ReceiveAnswer(12 - bad.size, 0, bad.map { i -> ItemError(t.epcs[i], "internal_error") })
            } else {
                null
            }
        }
        return run(big, s2, c2, 1, midnight = 0)
    }
    check(runWith(listOf(0, 1, 2, 3, 4)).end == ResetLiveRunner.End.DONE) { "5 in a row is ok" }
    check(runWith(listOf(0, 1, 2, 3, 4, 5)).end == ResetLiveRunner.End.HALTED) { "6 in a row stops" }
    check(runWith(listOf(0, 2, 4, 6, 8, 10, 1, 3, 5, 7, 9)).end == ResetLiveRunner.End.HALTED) { "11 in one request stops" }
    // any other code stops
    listOf("unknown_product", "undecodable_tag", "invalid_space", "not_admitted", "something_new").forEach { code ->
        val c3 = VirtualClock(0)
        val s3 = FakeSender(sched, c3)
        s3.script = { it, _ ->
            if (it.extId == target.extId) Outcome.ReceiveAnswer(2, 2, listOf(ItemError(target.epcs[0], "$code: x"))) else null
        }
        val r3 = run(sched, s3, c3, 2, midnight = 0)
        check(r3.end == ResetLiveRunner.End.HALTED && r3.haltReason!!.contains(code)) { "$code stops the run: ${r3.end} ${r3.haltReason}" }
    }
}

private fun liveTimeout() {
    val sched = Schedule()
    val c0 = VirtualClock(0)
    check(run(sched, FakeSender(sched, c0), c0, 1, midnight = 0).end == ResetLiveRunner.End.DONE && c0.now() < 60_000) {
        "control run is quick: ${c0.now()}"
    }
    listOf(Outcome.Unreachable("request timed out"), Outcome.Http(504)).forEach { o ->
        val c = VirtualClock(0)
        val s = FakeSender(sched, c)
        s.script = { it, n -> if (it.extId == sched.items[0].extId && n == 1) o else null }
        val r = run(sched, s, c, 1, midnight = 0)
        check(r.end == ResetLiveRunner.End.DONE && s.attempts.getValue(sched.items[0].extId).get() == 2) { "re-sent once: $o ${r.end}" }
        check(c.now() >= 60_000) { "$o: the same request waited at least 60 s, the clock reached ${c.now()}" }
    }
}

private fun liveLanesDrop() {
    val sched = Schedule()
    val c = VirtualClock(0)
    val s = FakeSender(sched, c, receiveMs = 200)
    val lanes = AtomicInteger(4)
    val dropped = java.util.concurrent.atomic.AtomicBoolean(false)
    val after = Collections.synchronizedList(ArrayList<Pair<Int, Int>>())
    s.onReceiveStart = { inFlight ->
        if (!dropped.get() && inFlight == 4) {
            lanes.set(2)
            dropped.set(true)
        } else if (dropped.get()) {
            after += inFlight to lanes.get()
        }
    }
    val r = run(sched, s, c, 4, midnight = 0, lanesNow = { lanes.get() })
    check(r.end == ResetLiveRunner.End.DONE && s.violations.isEmpty()) { "done: ${r.end} ${r.haltReason}" }
    check(s.peakReceives.get() == 4) { "4 were in flight before the drop: ${s.peakReceives.get()}" }
    check(after.isNotEmpty()) { "the remaining receives ran after the drop" }
    check(after.all { (inFlight, limit) -> inFlight <= limit }) {
        "no new claim started until in flight fell below 2, and in flight never exceeded the limit after the drop: $after"
    }
}

private fun liveRetryBodies() {
    // every retry round of the same tags rotates them, so the bodies differ (3 tags, rounds 1 and 2)
    val sched = Schedule()
    val c = VirtualClock(0)
    val s = FakeSender(sched, c)
    val t = sched.items[0]
    s.script = { it, n ->
        when {
            it.extId == t.extId && n == 1 -> Outcome.ReceiveAnswer(0, 0, t.epcs.map { e -> ItemError(e, "internal_error") })
            it.extId == "${t.extId}-retry1" && n == 1 -> Outcome.ReceiveAnswer(0, 0, t.epcs.map { e -> ItemError(e, "internal_error") })
            else -> null
        }
    }
    val r = run(sched, s, c, 1, midnight = 0)
    // 3 in a row is within the limit (5), so the night goes on through two retry rounds
    check(r.end == ResetLiveRunner.End.DONE && r.retryRequests == 2) { "two retry rounds: ${r.end} ${r.haltReason} ${r.retryRequests}" }
    check(s.bodies.size == 2 && s.bodies[0] != s.bodies[1]) { "the two failed bodies differ: ${s.bodies}" }
    check(s.order.contains("${t.extId}-retry2")) { "round 2 was sent" }
}

private fun liveZoneMoves() {
    val chain = Path.of("reference/data/chain")
    val state = RT.resolve("zone-moves-state")
    Files.createDirectories(state)
    val day = LocalDate.parse("2026-11-23")
    val (inputs, b, _) = StoreDayReset.build(SLUG, day, chain, state, ResetPacing())
    val moves = b.items.filter { it.kind == "move" }
    check(moves.isNotEmpty() && moves.all { it.payload.has("to_zone_id") && !it.payload.has("to_fixture_code") && !it.payload.has("occurred_at") }) {
        "every move body has to_zone_id, none has to_fixture_code or occurred_at"
    }
    check(
        moves.map {
            it.payload.toString()
        }.toSet().size == moves.size && moves.map { it.extId }.toSet().size == moves.size,
    ) { "bodies are distinct" }
    val keys = com.m8trx.twin.history.ChainKeys.load(chain.resolve(com.m8trx.twin.history.ChainKeys.DEFAULT_PATH.fileName)).store(SLUG)
    val zoneIds = keys.zones.map { it.zoneId }.toSet()
    val floor = moves.map { it.payload.path("to_zone_id").asText() }.filter { !it.startsWith(Placeholders.ZONE_PREFIX) }
    check(floor.isNotEmpty() && floor.all { it in zoneIds }) { "floor to_zone_id values are the keys export's zone ids" }
    check(
        moves.map {
            it.payload.path("to_zone_id").asText()
        }.any { it.startsWith(Placeholders.ZONE_PREFIX) },
    ) { "back-room racks stay placeholders for the live resolve" }
    // move-all: every on-hand unit has an explicit move
    val onHand = inputs.ledger.byFixture.values.flatten().toSet()
    val moved = moves.flatMap { it.epcs }.toSet()
    check(onHand.all { it in moved }) { "every on-hand unit has a move: ${onHand.count { it !in moved }} without" }
    val (_, bNo, _) = StoreDayReset.build(SLUG, day, chain, state, ResetPacing(moveAll = false))
    val noMoves = bNo.items.count { it.kind == "move" }
    check(moves.size > noMoves) { "move-all adds batches: ${moves.size} vs $noMoves without" }
    // the ledger books a floor move at its fixture code
    val code = keys.zones.associate { it.zoneId to it.code }
    val sample = moves.first { !it.payload.path("to_zone_id").asText().startsWith(Placeholders.ZONE_PREFIX) }
    check(ResetBooks.destOf(sample.payload, code) == code.getValue(sample.payload.path("to_zone_id").asText())) { "placements book the fixture code" }
    // the cross-check against /spatial/identity: missing code, differing id, truncated, no read
    val needed = FixtureZones.needed(moves, keys.zones.associate { it.code to FixtureZones.Keyed(it.zoneId, it.name) })
    fun identity(zones: List<Pair<String, String>>, truncated: Boolean = false): SpatialIdentityResponse = ConnectMappers.camel.readValue(
        "{\"truncated\":$truncated,\"sites\":[{\"site_id\":\"S\",\"spaces\":[{\"space_id\":\"${keys.salesFloorSpaceId}\",\"zones\":[" +
            zones.joinToString(",") { "{\"zone_id\":\"${it.first}\",\"code\":\"${it.second}\"}" } + "]}]}]}",
        SpatialIdentityResponse::class.java,
    )
    val good = needed.map { (c, k) -> k.zoneId to c }
    check(FixtureZones.problems(identity(good), keys.salesFloorSpaceId, needed).isEmpty()) { "every id matches: no problem" }
    val dropped = good.drop(1)
    check(FixtureZones.problems(identity(dropped), keys.salesFloorSpaceId, needed).single().contains(good[0].second)) { "a missing code is named" }
    val swapped = listOf("other-id" to good[0].second) + good.drop(1)
    check(
        FixtureZones.problems(identity(swapped), keys.salesFloorSpaceId, needed).single().let {
            it.contains(good[0].second) &&
                it.contains("other-id")
        },
    ) {
        "a differing id is named with the code"
    }
    check(
        FixtureZones.problems(identity(good, truncated = true), keys.salesFloorSpaceId, needed).isNotEmpty() &&
            FixtureZones.problems(null, "", needed).isNotEmpty(),
    ) {
        "truncated or unreadable identity refuses"
    }
}

private fun liveExistence() {
    val ledger = StoreStock(SLUG, mapOf("A" to (1..300).map { "a$it" }, "B" to (1..300).map { "b$it" }))
    val chunks = ArrayList<Int>()
    fun reader(missing: Set<String>, nulls: Boolean): (List<String>) -> List<ItemDetail>? = { chunk ->
        chunks += chunk.size
        chunk.map { e ->
            if (e in missing) {
                if (nulls) ItemDetail(e) else ItemDetail(e, "id", "sku", "sold")
            } else {
                ItemDetail(e, "id-$e", "sku", "in_stock")
            }
        }
    }
    val epcs = ExistenceCheck.sample(ledger, listOf("A", "B"), 1L, SLUG)
    check(epcs.size == 600) { "all 600 sampled" }
    val ok = ExistenceCheck.evaluate(epcs, reader((1..12).map { "a$it" }.toSet(), true))
    check(
        !ok.stop && ok.missing == 12 && chunks.all {
            it <= 200
        } && chunks.size == 3,
    ) { "2% exactly is ok, chunks of <= 200: ${ok.summary()} $chunks" }
    val bad = ExistenceCheck.evaluate(epcs, reader((1..13).map { "a$it" }.toSet(), true))
    check(bad.stop && bad.examples.isNotEmpty()) { "13 of 600 (> 2%) STOPS: ${bad.summary()}" }
    check(ExistenceCheck.evaluate(epcs, reader((1..13).map { "b$it" }.toSet(), false)).stop) { "a unit that is not in_stock counts as missing" }
    check(ExistenceCheck.evaluate(listOf("x"), { emptyList() }).missing == 1) { "a unit with no row at all counts as missing" }
    check(runCatching { ExistenceCheck.evaluate(epcs) { null } }.isFailure) { "a failed read is an error (the run refuses)" }
    val big = StoreStock(SLUG, mapOf("A" to (1..5000).map { "a$it" }))
    check(ExistenceCheck.sample(big, listOf("A"), 1L, SLUG).size == 2000) { "at most 2000 EPCs" }
}

private fun livePostCheck() {
    val sched = Schedule()
    val c = VirtualClock(0)
    val s = FakeSender(sched, c)
    val calls = ArrayList<Pair<Int, Long>>()
    val check = object : PostMovesCheck {
        override val waitMs = 120_000L

        override fun run(): String {
            calls += s.order.size to c.now()
            s.order += "POSTCHECK"
            return "ok"
        }
    }
    val lastMove = AtomicLong(0)
    val r = ResetLiveRunner(
        sched.items,
        object : OpsSender by s {
            override fun send(item: SendItem): Outcome = s.send(item).also { if (item.kind == "move") lastMove.set(c.now()) }
        },
        RetryPolicy(),
        SentLedger(null),
        null,
        params(2, 0),
        { false },
        c,
        postMovesCheck = check,
    ).run()
    check(r.end == ResetLiveRunner.End.DONE && calls.size == 1) { "done, post-check once: ${r.end} ${calls.size}" }
    val moveAndReceive = sched.items.filter { it.kind != "planogram" }.map { it.extId }.toSet()
    val at = s.order.indexOf("POSTCHECK")
    check(s.order.take(at).containsAll(moveAndReceive)) { "after every receive and move" }
    check(s.order.drop(at + 1).toSet() == setOf("rst-t-end-OLD", "PLNW-t")) { "before the directives" }
    check(calls[0].second - lastMove.get() >= 120_000) { "120 s after the last move: ${calls[0].second - lastMove.get()} ms" }
    // a post-check that throws is only reported
    val s2 = FakeSender(sched, VirtualClock(0))
    val c2 = VirtualClock(0)
    val r2 = ResetLiveRunner(
        sched.items, FakeSender(sched, c2), RetryPolicy(), SentLedger(null), null, params(1, 0), { false }, c2,
        postMovesCheck = object : PostMovesCheck {
            override val waitMs = 0L

            override fun run(): String = error("platform read failed")
        },
    ).run()
    check(r2.end == ResetLiveRunner.End.DONE && s2.violations.isEmpty()) { "a failing post-check never gates the run: ${r2.end}" }
}

private fun liveLanesStatus() {
    val sched = Schedule()
    val c = VirtualClock(0)
    val s = FakeSender(sched, c, receiveMs = 30)
    val lanes = AtomicInteger(1)
    s.script = { _, _ ->
        lanes.set(9)
        null
    }
    val events = Collections.synchronizedList(ArrayList<String>())
    val r = run(sched, s, c, 1, midnight = 0, lanesNow = { lanes.get() }, onEvent = { events += it }, statusEveryMs = 50)
    check(r.end == ResetLiveRunner.End.DONE && s.violations.isEmpty()) { "done ${r.end}" }
    check(
        events.any {
            it.contains("receive lanes changed: 1 -> 4")
        },
    ) { "the change is logged and clamped to 4: ${events.filter { it.contains("lanes") }}" }
    check(s.peakReceives.get() in 2..4) { "after the raise requests overlap, never more than 4: ${s.peakReceives.get()}" }
    val st = events.firstOrNull { it.startsWith("STATUS") }
    check(
        st != null && st.contains(
            "receives in flight",
        ) && st.contains("tags received") && st.contains("created") && st.contains("move batches sent") &&
            st.contains("health") && st.contains("ETA"),
    ) { "status line carries in flight, tags received/created, move batches, health, ETA: $st" }
}

private fun liveRetries() {
    val sched = Schedule()
    // 429 with Retry-After 120: waits at least 120 s (virtual), then goes; 503 twice then ok
    val c = VirtualClock(0)
    val s = FakeSender(sched, c)
    s.script = { it, n ->
        when {
            it.extId == sched.items[0].extId && n == 1 -> Outcome.Http(429, 120)
            it.extId == sched.items[1].extId && n <= 2 -> Outcome.Http(503)
            it.extId == sched.items[2].extId && n == 1 -> Outcome.Unreachable("reset")
            else -> null
        }
    }
    val r = run(sched, s, c, 1, policy = RetryPolicy(), midnight = 0)
    check(r.end == ResetLiveRunner.End.DONE && s.violations.isEmpty()) { "recovered: ${r.end} ${r.haltReason}" }
    check(c.now() >= 120_000) { "Retry-After 120 s honoured, the clock only reached ${c.now()}" }
    check(s.attempts.getValue(sched.items[1].extId).get() == 3 && s.attempts.getValue(sched.items[0].extId).get() == 2) { "retried the same item" }
    // 10 failures in a row on one item halt the run
    val c2 = VirtualClock(0)
    val s2 = FakeSender(sched, c2)
    s2.script = { it, _ -> if (it.extId == sched.items[0].extId) Outcome.Http(503) else null }
    val r2 = run(sched, s2, c2, 2, policy = RetryPolicy())
    check(r2.end == ResetLiveRunner.End.HALTED && r2.haltReason!!.contains("10 failures") && s2.attempts.getValue(sched.items[0].extId).get() == 10) {
        "halted after 10: ${r2.haltReason}, ${s2.attempts[sched.items[0].extId]}"
    }
    // a 400 is the platform refusing: halt at once
    val c3 = VirtualClock(0)
    val s3 = FakeSender(sched, c3)
    s3.script = { it, _ -> if (it.extId == sched.items[0].extId) Outcome.Http(400) else null }
    check(run(sched, s3, c3, 1).end == ResetLiveRunner.End.HALTED) { "400 halts" }
}

private fun liveGate() {
    val d = RT.resolve("gate")
    Files.createDirectories(d)
    val owner = d.resolve("owner.csv")
    val probe = ReceiveProbe.resultFile(d, SLUG)
    Files.createDirectories(probe.parent)
    Files.writeString(owner, "slug,owner\n$SLUG,storeday\n")
    // the content the operator writes for the 2026-11-19 receive proof (documented in ops/INSTALL.md and ops/reset-probe-result.example.json)
    Files.writeString(
        probe,
        "{\"passed\":true,\"at\":\"2026-11-19T09:13:05Z\",\"request_id\":\"r-a39ac9b48b\",\"received\":50,\"created\":50," +
            "\"errors\":[],\"checks\":{\"receive_200_created_50_no_errors\":true,\"details_50_of_50_sku_and_in_stock\":true," +
            "\"server_read_50_of_50_in_back_room\":true}}",
    )
    val ok = mapOf(
        "M8TRX_RESET_CONFIRM" to SLUG,
        "M8TRX_TWIN_SERVICE_BEARER" to "x",
        "M8TRX_RESET_RECEIVE_LANES" to "4",
    )

    fun v(env: Map<String, String>, ends: Int = 1, ownerFile: Path = owner) = ResetGate.check(SLUG, { env[it] }, d, ownerFile, ends)
    check(!v(ok).refused) { "all gates met: ${v(ok).reasons}" }
    check(v(ok - "M8TRX_RESET_CONFIRM").reasons.any { it.contains("M8TRX_RESET_CONFIRM") }) { "no second key" }
    val hourly = d.resolve("owner-hourly.csv")
    Files.writeString(hourly, "slug,owner\n$SLUG,hourly\n")
    check(v(ok, ownerFile = hourly).reasons.any { it.contains("hourly pass") }) { "owner hourly refused" }
    check(v(ok, ownerFile = d.resolve("nope.csv")).refused) { "no owner file refused" }
    check(v(ok, ends = 0).reasons.any { it.contains("old directive") }) { "no old directive refused" }
    check(v(ok - "M8TRX_TWIN_SERVICE_BEARER").refused) { "no receive bearer refused" }
    check(v(ok + ("M8TRX_RESET_RECEIVE_LANES" to "5")).refused && !v(ok + ("M8TRX_RESET_RECEIVE_LANES" to "1")).refused) { "lanes 1..4" }
    Files.writeString(probe, "{\"passed\":false}")
    check(v(ok).reasons.any { it.contains("probe") }) { "failed probe refused" }
    Files.deleteIfExists(probe)
    check(v(ok).reasons.any { it.contains("probe") }) { "no probe result refused" }
    check(ResetGate.MAX_LANES == 4) { "max lanes 4" }
}

private fun liveBooks() {
    val st = RT.resolve("books-state")
    st.toFile().deleteRecursively()
    val date = LocalDate.parse("2026-11-20")
    val b = ResetBooks(st, SLUG, date) { "BR-03" }
    val rc = listOf(epc(1), epc(2))
    b.record(receiveItem(0, rc))
    b.record(receiveItem(0, rc)) // a re-send after a crash: no second row
    b.record(moveItem(1, "F-9", rc))
    b.record(moveItem(2, "BR-05", listOf(epc(1))))
    val rows = ReceiveStock.readStock(ReceiveStock.stockFile(st.resolve("receive")))
    check(
        rows.size == 2 && rows.all {
            it.slug == SLUG && it.fixture == "BR-03" && it.ean == EAN && it.date == date
        },
    ) { "received-stock rows: $rows" }
    check(Files.readAllLines(ReceiveStock.stockFile(st.resolve("receive")))[0] == "epc,ean,slug,fixture,date") { "same header" }
    val pl = RestockMoves.readPlacements(st.resolve("moves/placements.csv"))
    check(pl[epc(1)] == "BR-05" && pl[epc(2)] == "F-9") { "placements, the later line wins: $pl" }
    check(Files.readAllLines(st.resolve("moves/placements.csv"))[0] == "epc,fixture,date") { "placements header" }
    // appended, never rewritten: a second writer adds to what is there
    ResetBooks(st, SLUG, date) { "BR-03" }.record(receiveItem(3, listOf(epc(3), epc(1))))
    check(ReceiveStock.readStock(ReceiveStock.stockFile(st.resolve("receive"))).size == 3) {
        "an EPC already in the file is not added again, the new one is"
    }
    // through the runner: the files follow the accepted items
    val sched = Schedule()
    val st2 = RT.resolve("books-state2")
    st2.toFile().deleteRecursively()
    val c = VirtualClock(0)
    val s = FakeSender(sched, c)
    val res = run(sched, s, c, 3, books = ResetBooks(st2, SLUG, date) { "BR-01" })
    check(res.end == ResetLiveRunner.End.DONE) { "done" }
    check(ReceiveStock.readStock(ReceiveStock.stockFile(st2.resolve("receive"))).size == 18) { "18 received EPCs booked" }
    val p2 = RestockMoves.readPlacements(st2.resolve("moves/placements.csv"))
    check(p2[sched.received[1][0]] == "F-1" && p2[sched.onHand[0]] == "F-H" && p2[sched.received[0][0]] == "BR-02") {
        "placements follow the moves: ${p2.size}"
    }
}

private fun liveParts() {
    val item = receiveItem(0, listOf(epc(1), epc(2)))
    check(LiveSender.receiveOutcome(item, "{\"received\":2,\"created\":2,\"errors\":[]}") == Outcome.ReceiveAnswer(2, 2, emptyList())) {
        "clean answer"
    }
    check(LiveSender.receiveOutcome(item, "{\"received\":2,\"created\":0,\"errors\":[]}") == Outcome.ReceiveAnswer(2, 0, emptyList())) {
        "a repeat (created 0)"
    }
    val e = LiveSender.receiveOutcome(item, "{\"received\":1,\"created\":1,\"errors\":[{\"epc\":\"${epc(2)}\",\"reason\":\"not_admitted: x\"}]}")
    check(e is Outcome.ReceiveAnswer && e.errors == listOf(ItemError(epc(2), "not_admitted: x")) && e.errors[0].code == "not_admitted") {
        "errors[] entries surface with their code: $e"
    }
    check(LiveSender.receiveOutcome(item, "{\"received\":1,\"created\":1,\"errors\":[]}") is Outcome.ItemErrors) {
        "fewer received than sent and no entry"
    }
    check(LiveSender.receiveOutcome(item, "<html>") is Outcome.ItemErrors) { "an unparseable answer is not accepted" }
    check(RetryPolicy().decide(e, 1) is Decision.Halt && RetryPolicy().decide(Outcome.ReceiveAnswer(1, 1, emptyList()), 1) == Decision.Done) {
        "policy: errors halt, a clean answer is done"
    }
    check(ResetGate.oversizeReceives(listOf(receiveItem(0, (0 until 51).map { epc(it) }), receiveItem(1, listOf(epc(1))))) == listOf("rst-t-r-0")) {
        "a receive over 50 tags is reported (the run refuses)"
    }
    val lf = RT.resolve("reset-lanes")
    Files.deleteIfExists(lf)
    val ls = LanesSource(lf, 2)
    check(ls.current() == 2) { "no file: the env value" }
    Files.writeString(lf, "3\n")
    check(ls.current() == 3) { "file overrides" }
    Files.writeString(lf, "9\n")
    check(ls.current() == 4) { "clamped to 4" }
    Files.writeString(lf, "junk\n")
    check(ls.current() == 4) { "a garbled file keeps the last valid value (4), not the env default" }
    Files.writeString(lf, "")
    check(ls.current() == 4) { "an empty / half-written file keeps the last valid value" }
    Files.deleteIfExists(lf)
    check(ls.current() == 2) { "file removed: back to the env value" }

    // the reset's hold file is its own, never the shared $OPS/HOLD
    val hf = ResetGate.holdFile { null }
    check(hf.toString() == "/opt/m8trx/twin-ops/reset-HOLD" && hf.fileName.toString() != "HOLD") { "default reset hold file: $hf" }
    check(ResetGate.holdFile { if (it == "M8TRX_OPS_DIR") "/x" else null }.toString() == "/x/reset-HOLD") { "under M8TRX_OPS_DIR" }
    check(ResetGate.holdFile { if (it == "M8TRX_RESET_HOLD_FILE") "/y/h" else null }.toString() == "/y/h") { "overridable" }
    check(ResetGate.paceRefusals(25, 3_750).isEmpty() && ResetGate.paceRefusals(26, 3_750).size == 1 && ResetGate.paceRefusals(25, 3_000).size == 1) {
        "move chunk over 25 and spacing under 3.5 s are refused"
    }

    // receive answer cross-check: error tags must be in the request (case-insensitive), received = sent - distinct error tags
    val four = listOf("aa", "bb", "cc", "dd")
    check(ReceiveCheck.mismatch(four, Outcome.ReceiveAnswer(3, 3, listOf(ItemError("BB", "internal_error")))) == null) { "case-insensitive match" }
    check(ReceiveCheck.mismatch(four, Outcome.ReceiveAnswer(3, 3, listOf(ItemError("zz", "internal_error")))) != null) { "foreign error tag" }
    check(ReceiveCheck.mismatch(four, Outcome.ReceiveAnswer(4, 4, listOf(ItemError("bb", "internal_error")))) != null) { "counts disagree" }
    check(
        ReceiveCheck.mismatch(four, Outcome.ReceiveAnswer(3, 3, listOf(ItemError("bb", "x"), ItemError("BB", "y")))) == null &&
            ReceiveCheck.mismatch(four, Outcome.ReceiveAnswer(2, 2, listOf(ItemError("bb", "x"), ItemError("BB", "y")))) != null,
    ) {
        "duplicate error tag counts once"
    }
    check(
        LiveSender.receiveOutcome(
            item,
            "{\"received\":2,\"created\":2,\"errors\":[{\"epc\":\"NOPE\",\"reason\":\"internal_error\"}]}",
        ) is Outcome.ItemErrors,
    ) {
        "LiveSender refuses a mismatching answer"
    }

    // 401 / 403 never loop: one pause of >= 10 s, then halt; 429 halts after 30 in a row
    val pol = RetryPolicy()
    listOf(401, 403).forEach { st ->
        val w = pol.decide(Outcome.Http(st), 1)
        check(w is Decision.Wait && w.ms >= 10_000) { "$st first attempt waits >= 10 s: $w" }
        check(pol.decide(Outcome.Http(st), 2) is Decision.Halt) { "$st halts at the second attempt" }
    }
    check(pol.decide(Outcome.Http(429), 29) is Decision.Wait && pol.decide(Outcome.Http(429), 30) is Decision.Halt) { "429: halt after 30 in a row" }

    // health: the TOP-LEVEL status must be "ok"
    check(Health.isReady(200, "{\"status\":\"ok\"}") && !Health.isReady(200, "{\"status\":\"degraded\",\"detail\":{\"status\":\"ok\"}}")) {
        "top-level status"
    }
    check(
        !Health.isReady(200, "{\"msg\":\"\\\"status\\\":\\\"ok\\\"\"}") && !Health.isReady(200, "[{\"status\":\"ok\"}]") &&
            !Health.isReady(200, "not json \"status\":\"ok\""),
    ) {
        "a status found elsewhere in the body does not count"
    }

    val resp = ConnectMappers.camel.readValue(
        "{\"sites\":[{\"site_id\":\"S\",\"spaces\":[{\"space_id\":\"SP\",\"zones\":[" +
            "{\"zone_id\":\"z1\",\"name\":\"Backroom Fixture 1\"},{\"zone_id\":\"z2\",\"code\":\"BR-02\",\"name\":\"x\"}," +
            "{\"zone_id\":\"z9\",\"name\":\"Receiving Dock\"}]},{\"space_id\":\"OTHER\",\"zones\":[{\"zone_id\":\"zz\",\"code\":\"BR-03\"}]}]}]}",
        SpatialIdentityResponse::class.java,
    )
    val zm = BackroomZones.fromIdentity(resp, "SP")
    check(zm == mapOf("BR-01" to "z1", "BR-02" to "z2")) { "zones by code or name, only the stockroom space: $zm" }
    val mv = listOf(moveItem(0, "BR-01", listOf("a")), moveItem(1, "BR-07", listOf("b")), moveItem(2, "F-1", listOf("c")))
    check(BackroomZones.missing(mv, zm) == listOf("BR-07")) { "a rack without a zone id is reported" }

    val old = OldDirectiveExport.convert(
        ConnectMappers.snake.readTree(
            "{\"directive_ref\":\"PLN-x\",\"name\":\"Twin planogram\",\"targets\":[{\"position\":1,\"site_id\":\"S\",\"item_identifier\":\"$EAN\"," +
                "\"zone_name\":\"Gondola R3 Back U1\",\"required_quantity\":4,\"actual_quantity\":4}]}",
        ),
    )
    check(old.path("external_directive_id").asText() == "PLN-x" && old.path("targets")[0].path("raw_fixture_code").asText() == "Gondola R3 Back U1") {
        "converter: $old"
    }
    val pd = ConnectMappers.snake.treeToValue(old, com.m8trx.twin.connect.model.webhook.PlanogramDirective::class.java)
    check(pd.targets.single().requiredQuantity == 4 && pd.targets.single().rawItemIdentifier == EAN) {
        "the converted file loads as the reset's PlanogramDirective"
    }

    val ledger = StoreStock(SLUG, mapOf("A" to (1..100).map { "a$it" }, "B" to (1..100).map { "b$it" }))
    val same = SpotCount.evaluate(ledger, StoreStock(SLUG, ledger.byFixture), listOf("A", "B"))
    check(!same.stop && same.disagreeing == 0) { "no disagreement" }
    val two = SpotCount.evaluate(
        ledger,
        StoreStock(
            SLUG,
            mapOf(
                "A" to (1..100).map {
                    "a$it"
                }.drop(2),
                "B" to ledger.byFixture.getValue("B"),
            ),
        ),
        listOf("A", "B"),
    )
    check(!two.stop && two.disagreeing == 2) { "2 of 200 is 1 %, ok: ${two.summary()}" }
    val many = SpotCount.evaluate(
        ledger,
        StoreStock(
            SLUG,
            mapOf(
                "A" to (1..100).map {
                    "a$it"
                }.drop(5),
                "B" to ledger.byFixture.getValue("B"),
            ),
        ),
        listOf("A", "B"),
    )
    check(many.stop) { "5 of 200 is 2.5 %, more than 2 %: ${many.summary()}" }
}

private fun budgetMath() {
    for (limit in listOf(150, 200)) {
        val b = ItemBudget(limit)
        val sizes = listOf(25, 10, 25, 17, 3, 25, 25, 8, 25, 12)
        var t = 0L
        val log = ArrayList<Pair<Long, Int>>()
        var i = 0
        while (t < 600_000) {
            val n = sizes[i++ % sizes.size]
            t += b.waitMsFor(n, t)
            check(b.waitMsFor(n, t) == 0L) { "after the wait the batch may go" }
            b.record(n, t)
            log += t to n
            val inWindow = log.filter { it.first > t - 60_000 }.sumOf { it.second }
            check(inWindow <= limit) { "limit $limit: $inWindow items in the 60 s ending at $t" }
        }
        val total = log.filter { it.first <= 540_000 }.sumOf { it.second }
        check(total >= limit * 9 * 0.9) { "limit $limit: only $total items in 9 minutes (should be near ${limit * 9})" }
    }
    val b = ItemBudget(10)
    check(b.waitMsFor(25, 0) == 0L) { "an oversize batch goes when the window is empty" }
    b.record(25, 0)
    check(b.waitMsFor(1, 1_000) == 59_000L && b.waitMsFor(25, 1_000) == 59_000L) { "then everything waits for it to leave the window" }
    check(b.waitMsFor(5, 60_000) == 0L) { "the window is (now-60 s, now]" }
    var rate = 100
    val v = ItemBudget({ rate })
    v.record(90, 0)
    check(v.waitMsFor(20, 10_000) == 50_000L) { "over the limit waits" }
    rate = 200
    check(v.waitMsFor(20, 10_000) == 0L) { "a raised limit is read on the next call" }
}

private fun budgetRateSwitch() {
    val sched = Schedule()
    val c = VirtualClock(0)
    val s = FakeSender(sched, c, receiveMs = 30)
    val events = Collections.synchronizedList(ArrayList<String>())
    val r = run(sched, s, c, 1, midnight = 0, perMin = 200, perMinWithReceive = 150, onEvent = { events += it })
    check(r.end == ResetLiveRunner.End.DONE && s.violations.isEmpty()) { "done ${r.end} ${r.haltReason}" }
    check(
        events.any {
            it.contains("move budget changed: 150 -> 200")
        },
    ) { "the rate switches 150 -> 200 once no receive is left: ${events.filter { "budget" in it }}" }
    check(ResetGate.paceRefusals(25, 0, 200, 150).isEmpty()) { "200 / 150 with no floor is accepted" }
    check(ResetGate.paceRefusals(25, 0, 241, 150).size == 1 && ResetGate.paceRefusals(25, 0, 200, 241).size == 1) { "above 240 refused" }
    check(ResetGate.paceRefusals(25, 0, 0, 150).isNotEmpty()) { "a zero budget is refused" }
    check(ResetGate.paceRefusals(25, 3_750).isEmpty() && ResetGate.paceRefusals(25, 3_000).size == 1) {
        "without a budget the old 3.5 s refusal stands"
    }
    check(ResetGate.paceRefusals(25, 500, 200, 150).isEmpty()) { "with the budget in use the 3.5 s spacing refusal does not apply" }
}

private fun ok(key: String) = MoveResultEntry(key, "ok", 0, """{"idempotency_key":"$key","outcome":"ok"}""")

private fun resultsWatch() {
    val pendingFor = { keys: List<String> -> keys.map { MoveResultEntry(it, "pending", 0, "") } }
    val w1 = MoveResultsWatch(pendingFor, everyMs = 10, pendingLimitMs = 60_000)
    w1.beforeSend("m1", 0)
    w1.answered("m1", """{"eventKey":"k1"}""", 100)
    check(w1.check(30_000) == null && w1.outstanding() == 1) { "pending 30 s after the post is fine" }
    check(w1.check(59_000) == null) { "59 s still fine" }
    check(w1.check(70_000)?.contains("still pending") == true) { "pending over 60 s halts" }
    val w2 = MoveResultsWatch({ k -> k.map { MoveResultEntry(it, "ok", 2, "r") } }, everyMs = 10)
    w2.answered("m", """{"eventKey":"k"}""", 0)
    check(w2.check(100)?.contains("refused 2") == true) { "refused > 0 halts" }
    for (o in listOf("warning", "failed")) {
        val w = MoveResultsWatch({ k -> k.map { MoveResultEntry(it, o, 0, "r") } }, everyMs = 10)
        w.answered("m", """{"eventKey":"k"}""", 0)
        check(w.check(100)?.contains(o) == true) { "$o halts" }
    }
    val w3 = MoveResultsWatch({ k -> k.map { ok(it) } }, everyMs = 1_000)
    for (i in 0 until 95) w3.answered("m$i", """{"eventKey":"k$i"}""", 0)
    check(w3.check(10) == null && w3.confirmed == 95 && w3.outstanding() == 0) { "confirmed entries are counted and dropped: ${w3.confirmed}" }
    val chunks = ArrayList<Int>()
    val w4 = MoveResultsWatch({ k ->
        chunks += k.size
        k.map { ok(it) }
    }, everyMs = 1)
    for (i in 0 until 95) w4.answered("m$i", """{"eventKey":"k$i"}""", 0)
    w4.check(10)
    check(chunks.all { it <= 40 } && chunks.sum() == 95) { "at most 40 keys a request: $chunks" }
    val w5 = MoveResultsWatch({ null }, everyMs = 1, pendingLimitMs = 10)
    w5.answered("m", """{"eventKey":"k"}""", 0)
    check(w5.check(1_000_000) == null && w5.outstanding() == 1) { "an unreadable results route never halts by itself" }
    // a chunk whose read failed is skipped, never "not listed"; only answers halt
    var fail = true
    val w7 = MoveResultsWatch({ k -> if (fail) null else k.map { MoveResultEntry(it, "pending", 0, "") } }, everyMs = 1, pendingLimitMs = 10)
    w7.answered("m", """{"eventKey":"k"}""", 0)
    for (i in 1..6) check(w7.check(1_000_000L * i) == null) { "a failed read never halts" }
    fail = false
    check(w7.check(10_000_000)?.contains("still pending") == true) { "after a good read the pending event halts" }
    val w8 = MoveResultsWatch({ k -> if ("k0" in k) null else k.map { ok(it) } }, everyMs = 1, pendingLimitMs = 10)
    for (i in 0 until 45) w8.answered("m$i", """{"eventKey":"k$i"}""", 0)
    check(w8.check(1_000_000) == null && w8.confirmed == 5 && w8.outstanding() == 40) { "only the failed chunk stays unconfirmed: ${w8.confirmed}" }
    val f = RT.resolve("move-answers.tsv")
    Files.createDirectories(RT)
    Files.deleteIfExists(f)
    val w6 = MoveResultsWatch({ k -> k.map { ok(it) } }, f, everyMs = 1)
    w6.beforeSend("mv-1", 5)
    val before = Files.readAllLines(f)
    w6.answered("mv-1", """{"eventKey":"EK1"}""", 9)
    val after = Files.readAllLines(f)
    check(before.size == 1 && before[0].startsWith("mv-1\t\t") && after.size == 2 && after[1].startsWith("mv-1\tEK1\t")) {
        "a row with an empty key before the post, the key after: $after"
    }
    check(
        ResultsRoute.parse(
            """{"results":[{"idempotency_key":"a","outcome":"pending"},{"idempotency_key":"b","outcome":"ok","result":{"type":"move","moved":3,"refused":1,"refused_items":["x"]}}]}""",
        )
            .let { it[0].pending && it[1].refused == 1 && it[1].key == "b" },
    ) { "results body parsed" }
}

private fun withWatch(inner: OpsSender, watch: MoveResultsWatch, c: VirtualClock, moveTakesMs: Long = 0) = object : OpsSender {
    override fun health() = inner.health()

    override fun send(item: SendItem): Outcome {
        val o = inner.send(item)
        if (item.kind == "move") {
            c.t.addAndGet(moveTakesMs)
            if (o == Outcome.Accepted) watch.answered(item.extId, """{"eventKey":"k-${item.extId}"}""", c.now())
        }
        return o
    }
}

private fun resultsHalts() {
    // all confirmed: the run finishes and every move is counted
    val sched = Schedule()
    val c = VirtualClock(0)
    val w = MoveResultsWatch({ k -> k.map { ok(it) } }, everyMs = 10)
    val r = run(sched, withWatch(FakeSender(sched, c), w, c), c, 1, perMin = 200, perMinWithReceive = 150, moveWatch = w)
    check(r.end == ResetLiveRunner.End.DONE && w.confirmed == 10 && w.outstanding() == 0) {
        "confirmed run: ${r.end} ${r.haltReason} confirmed=${w.confirmed}"
    }
    // pending forever: 9 items a minute makes the 4th batch wait, and the pending ones age past 60 s
    val c2 = VirtualClock(0)
    val w2 = MoveResultsWatch({ k -> k.map { MoveResultEntry(it, "pending", 0, "") } }, everyMs = 10, pendingLimitMs = 60_000)
    val s2 = Schedule()
    val r2 = run(s2, withWatch(FakeSender(s2, c2), w2, c2), c2, 1, perMin = 9, perMinWithReceive = 9, moveWatch = w2)
    check(r2.end == ResetLiveRunner.End.HALTED && r2.haltReason!!.contains("still pending")) { "pending halts: ${r2.end} ${r2.haltReason}" }
    // refused
    val c3 = VirtualClock(0)
    val w3 = MoveResultsWatch({ k -> k.map { MoveResultEntry(it, "ok", 1, "r") } }, everyMs = 10)
    val s3 = Schedule()
    val led3 = SentLedger(null)
    val r3 = run(s3, withWatch(FakeSender(s3, c3), w3, c3), c3, 1, ledger = led3, perMin = 200, perMinWithReceive = 150, moveWatch = w3)
    check(r3.end == ResetLiveRunner.End.HALTED && r3.haltReason!!.contains("refused")) { "refused halts: ${r3.end} ${r3.haltReason}" }
    check(led3.size < 10 + 6 + 2) { "the run stopped early (resumable), ledger ${led3.size}" }
    // slow answer
    val c4 = VirtualClock(0)
    val w4 = MoveResultsWatch({ k -> k.map { ok(it) } }, everyMs = 10)
    val s4 = Schedule()
    val r4 = run(s4, withWatch(FakeSender(s4, c4), w4, c4, moveTakesMs = 11_000), c4, 1, perMin = 200, perMinWithReceive = 150, moveWatch = w4)
    check(r4.end == ResetLiveRunner.End.HALTED && r4.haltReason!!.contains("answered after")) {
        "a move answered after 11 s halts: ${r4.end} ${r4.haltReason}"
    }
}

private fun liveWaitsOutRestart() {
    // 50 polls of 502 (500 virtual ms) with a 1 s cap: waited out, every item sent exactly once, run done
    val sched = Schedule()
    val c = VirtualClock(0)
    val s = FakeSender(sched, c)
    s.healthScript = { n -> if (n <= 50) 502 to null else 200 to "{\"status\":\"ok\"}" }
    val r = run(sched, s, c, 1, healthWaitCapMs = 1_000)
    check(r.end == ResetLiveRunner.End.DONE) { "a 500 ms outage is waited out: ${r.end} ${r.haltReason}" }
    check(s.order.toSet() == sched.items.map { it.extId }.toSet() && s.sends.get() == sched.items.size) { "nothing sent twice or skipped" }
    // refused connection (no answer at all) is the same
    val c1 = VirtualClock(0)
    val s1 = FakeSender(sched, c1)
    s1.healthScript = { n -> if (n <= 50) 0 to null else 200 to "{\"status\":\"ok\"}" }
    check(run(sched, s1, c1, 2, healthWaitCapMs = 1_000).end == ResetLiveRunner.End.DONE) { "a refused connection is waited out" }
    // sends answered 502 across the outage are retried, ledger intact
    val c2 = VirtualClock(0)
    val s2 = FakeSender(sched, c2)
    s2.script = { it, n -> if (it.extId == sched.items[0].extId && n <= 3) Outcome.Http(502) else null }
    s2.healthScript = { n -> if (n in 2..30) 502 to null else 200 to "{\"status\":\"ok\"}" }
    val r2 = run(sched, s2, c2, 1, healthWaitCapMs = 60_000)
    check(r2.end == ResetLiveRunner.End.DONE && s2.violations.isEmpty()) { "502s on a send then health back: ${r2.end} ${r2.haltReason}" }
    // an outage past the cap halts, resumable (items left unsent, nothing marked)
    val c3 = VirtualClock(0)
    val s3 = FakeSender(sched, c3)
    s3.healthScript = { 502 to null }
    val led3 = SentLedger(null)
    val r3 = run(sched, s3, c3, 1, led3, healthWaitCapMs = 1_000)
    check(r3.end == ResetLiveRunner.End.HALTED && r3.haltReason!!.contains("health not ready for") && led3.size == 0) {
        "past the cap it halts: ${r3.end} ${r3.haltReason}"
    }
    // a 4xx with a reason still halts at once, outage or not
    val c4 = VirtualClock(0)
    val s4 = FakeSender(sched, c4)
    s4.script = { it, _ -> if (it.extId == sched.items[0].extId) Outcome.Http(400) else null }
    check(run(sched, s4, c4, 1, healthWaitCapMs = 1_000).end == ResetLiveRunner.End.HALTED) { "a 400 still halts" }
}

private fun liveSlowAfterOutage() {
    // the first move answers after 11 s, right after a health outage: not a full queue
    val sched = Schedule()
    val c = VirtualClock(0)
    val inner = FakeSender(sched, c)
    inner.healthScript = { n -> if (n <= 5) 502 to null else 200 to "{\"status\":\"ok\"}" }
    var slowOnce = true
    val sender = object : OpsSender {
        override fun health() = inner.health()

        override fun send(item: SendItem): Outcome {
            val o = inner.send(item)
            if (item.kind == "move" && slowOnce) {
                slowOnce = false
                c.t.addAndGet(11_000)
            }
            return o
        }
    }
    val r = run(sched, sender, c, 1)
    check(r.end == ResetLiveRunner.End.DONE) { "a slow answer just after an outage does not halt: ${r.end} ${r.haltReason}" }
}
