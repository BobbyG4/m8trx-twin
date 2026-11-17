package com.m8trx.twin.storeops

import com.m8trx.twin.connect.DatedStream
import com.m8trx.twin.connect.Sgtin96
import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.layer3.LiveDrive
import com.m8trx.twin.layer3.PeopleSource
import com.m8trx.twin.storeday.Slot
import com.m8trx.twin.storeday.StoreState
import com.m8trx.twin.storeday.UnitState
import com.m8trx.twin.storeday.buildSim
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate

/**
 * `./gradlew storeOpsSelfTest` — offline tests of the Phase 3 tooling (part of `offlineSelfTest`). No network, no credentials, no
 * state outside `build/storeops-selftest/`. The Denver reset is planned from the committed chain files only (a seed-only ledger).
 */
private val OUT: Path = Path.of("build/storeops-selftest")
private val FIRST_DAY: LocalDate = LocalDate.parse("2026-11-23")
private const val DENVER = "dec-us-denver"

/** SHA-256 of Denver's plan on 2026-10-14 at 1x, taken from LiveDrive BEFORE the people-source switch was added (651,435 wires). */
private const val LIVE_GOLDEN = "a42230fa284b6f3fc92faefbcd7d87dd40adda45283d69becb0e56b3efcb6d81"
private const val LIVE_GOLDEN_WIRES = 651_435

fun main() {
    Files.createDirectories(OUT)
    val tests = listOf<Pair<String, () -> Unit>>(
        "backoff: health gate is 200 + status ok only; degraded and 502 are not ready" to ::healthGate,
        "backoff: 429 waits 60 s and honours a longer Retry-After; 502/connection wait for health; 401 drops the token; 400 halts" to ::policy,
        "backoff: logins >= 7 s apart, tokens cached for their lifetime" to ::logins,
        "backoff: a failure leaves the event due, in place; nothing is skipped; pace holds after an outage" to ::pumpFailures,
        "ordering guard: receive before move, no move after sale, no double sale, non-decreasing time" to ::orderingGuard,
        "ownership: a store the hourly pass serves is refused; one file, two readers" to ::ownership,
        "reset (synthetic): conserves units, received = plan - reusable, minted EPCs never collide" to ::resetSynthetic,
        "reset (Denver, seed ledger): conservation, no collision, pace, ordering, idempotent ids, post-state" to ::resetDenver,
        "driver dry run: one Denver day sends exactly the wire plan in order; HOLD stops it; no live client built" to ::driverDry,
        "no network: LiveSender cannot be built unless live, and no dry path builds it" to ::noNetwork,
        "gap check: every signal has a surface or 'no surface'; template written" to ::gapCheck,
        "LiveDrive: default people source is the generator and its output is byte-identical to before the switch" to ::liveDefault,
        "LiveDrive: the store-day sidecar source reads shopper positions; unknown values refused" to ::liveSidecar,
    )
    var failed = 0
    for ((name, t) in tests) {
        try {
            t()
            println("PASS  $name")
        } catch (e: Throwable) {
            failed++
            println("FAIL  $name — ${e.message}")
            e.printStackTrace()
        }
    }
    println("storeOpsSelfTest: ${tests.size - failed}/${tests.size} passed")
    if (failed > 0) error("$failed self-test(s) failed")
}

private fun healthGate() {
    check(Health.isReady(200, "{\"status\":\"ok\",\"db\":\"up\",\"hasura\":\"up\"}")) { "ok" }
    check(Health.isReady(200, "{ \"status\" : \"ok\" }")) { "whitespace" }
    check(!Health.isReady(200, "{\"status\":\"degraded\",\"db\":\"down\"}")) { "degraded is not ready" }
    check(!Health.isReady(200, "<html>the web app's page</html>")) { "a page of HTML with 200 is not ready" }
    check(!Health.isReady(502, "{\"status\":\"ok\"}")) { "502 is not ready whatever the body" }
    check(!Health.isReady(0, null)) { "no answer" }
    check(Health.URL == "https://dev.m8trx.com/server/api/health") { "the corrected readiness URL" }
}

private fun policy() {
    val p = RetryPolicy()
    check(p.decide(Outcome.Accepted, 1) == Decision.Done) { "2xx done" }
    val a = p.decide(Outcome.Http(429), 1) as Decision.Wait
    check(a.ms == 60_000L && !a.needHealth) { "429 without Retry-After waits 60 s, got ${a.ms}" }
    check((p.decide(Outcome.Http(429, 5), 1) as Decision.Wait).ms == 60_000L) { "Retry-After 5 s never shortens the minute" }
    check((p.decide(Outcome.Http(429, 120), 1) as Decision.Wait).ms == 120_000L) { "Retry-After 120 s is honoured" }
    val b = p.decide(Outcome.Http(502), 1) as Decision.Wait
    check(b.needHealth && b.ms == 5_000L) { "502 waits for health: $b" }
    check((p.decide(Outcome.Unreachable("refused"), 1) as Decision.Wait).needHealth) { "no answer waits for health" }
    check((p.decide(Outcome.Http(500), 1) as Decision.Wait).needHealth) { "other 5xx retried" }
    check((p.decide(Outcome.Http(401), 1) as Decision.Wait).dropToken) { "401 drops the token" }
    check(p.decide(Outcome.Http(400), 1) is Decision.Halt) { "400 halts the lane, it is not retried and not skipped" }
    check(p.decide(Outcome.Http(403), 1) is Decision.Halt) { "403 halts" }
    check(
        (1..8).map {
            p.backoff(it)
        } == listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L),
    ) { "backoff doubles to the cap" }
    check((p.decide(Outcome.Http(502), 20) as Decision.Wait).alert && !(p.decide(Outcome.Http(502), 19) as Decision.Wait).alert) {
        "alert at the 20th failure"
    }
}

private fun logins() {
    val g = LoginGate()
    check(g.waitBeforeLoginMs(1_000) == 0L) { "first login is immediate" }
    g.recordLogin(1_000)
    check(g.waitBeforeLoginMs(1_000) == 7_000L && g.waitBeforeLoginMs(5_000) == 3_000L && g.waitBeforeLoginMs(8_000) == 0L) { "7 s spacing" }
    check(g.cached("a@x", 0) == null) { "no token yet" }
    g.store("a@x", "tok", 10_000)
    check(g.cached("a@x", 10_000 + 3_599_999) == "tok") { "kept for its lifetime" }
    check(g.cached("a@x", 10_000 + 3_600_000) == null) { "expired at the hour" }
    g.store("a@x", "tok2", 0)
    g.drop("a@x")
    check(g.cached("a@x", 1) == null) { "dropped" }
}

private fun item(seq: Int, due: Long, kind: String, id: String, vararg epcs: String) = SendItem(
    seq,
    due,
    if (kind ==
        "receive"
    ) {
        0
    } else if (kind == "move") {
        1
    } else {
        2
    },
    kind,
    id,
    com.m8trx.twin.connect.model.ConnectMappers.snake.createObjectNode(),
    epcs.toList(),
)

/** A sender that answers from a script, one answer per call, then accepts. */
private class ScriptedSender(private val sends: ArrayDeque<Outcome>, private val healths: ArrayDeque<Pair<Int, String?>> = ArrayDeque()) : OpsSender {
    val sentAt = ArrayList<Pair<String, Long>>()
    var clock: SimClock? = null
    var drops = 0

    override fun health(): Pair<Int, String?> = healths.removeFirstOrNull() ?: (200 to "{\"status\":\"ok\"}")

    override fun send(item: SendItem): Outcome {
        val o = sends.removeFirstOrNull() ?: Outcome.Accepted
        if (o == Outcome.Accepted) sentAt += item.extId to clock!!.nowMs
        return o
    }

    override fun dropToken() {
        drops++
    }
}

private fun runPump(
    items: List<SendItem>,
    s: ScriptedSender,
    known: List<String>,
    spacing: Map<String, Long> = emptyMap(),
    until: Long = 600_000,
): Pair<Pump, SimClock> {
    val clock = SimClock(0)
    s.clock = clock
    val pump = Pump(items, s, RetryPolicy(), SentLedger(null), OrderingGuard(known), spacing, healthEveryMs = 0)
    while (clock.nowMs <= until) {
        pump.pump(clock.nowMs)
        if (pump.queue.size == 0 || pump.haltReason != null) break
        clock.advance(1_000)
    }
    return pump to clock
}

private fun pumpFailures() {
    val items = listOf(item(0, 0, "receive", "r1", "A"), item(1, 0, "move", "m1", "A"), item(2, 0, "sale", "s1", "A"))
    // 429 on the first receive, then a 502, then everything accepts
    val s = ScriptedSender(
        ArrayDeque(listOf(Outcome.Http(429), Outcome.Http(502))),
        ArrayDeque(
            listOf(
                200 to "{\"status\":\"ok\"}",
                502 to null,
                200 to "{\"status\":\"degraded\"}",
            ),
        ),
    )
    val (pump, _) = runPump(items, s, emptyList())
    check(pump.haltReason == null && pump.queue.size == 0) { "all three eventually sent, halt=${pump.haltReason}, left=${pump.queue.size}" }
    check(s.sentAt.map { it.first } == listOf("r1", "m1", "s1")) { "order kept: ${s.sentAt}" }
    check(s.sentAt[0].second >= 60_000L) { "r1 waited the minute after the 429: ${s.sentAt[0]}" }
    // a 400 halts with the event still due (never dropped)
    val h = ScriptedSender(ArrayDeque(listOf(Outcome.Http(400))))
    val (p2, _) = runPump(items, h, emptyList())
    check(p2.haltReason != null && p2.queue.size == 3 && p2.queue.head()!!.extId == "r1") { "halt leaves all 3 due, head r1: ${p2.queue.size}" }
    // pace: 4 moves due at once go out 500 ms apart, even after an outage delayed them
    val moves = (0 until 4).map { item(it, 0, "move", "m$it", "U$it") }
    val s3 = ScriptedSender(ArrayDeque(listOf(Outcome.Http(502))))
    val (p3, _) = runPump(moves, s3, (0 until 4).map { "U$it" }, mapOf("move" to 2_000L), until = 600_000)
    check(p3.queue.size == 0) { "moves drained" }
    check(s3.sentAt.zipWithNext().all { (a, b) -> b.second - a.second >= 2_000L }) { "pace held: ${s3.sentAt}" }
    // a 401 tells the sender to drop its token
    val s4 = ScriptedSender(ArrayDeque(listOf(Outcome.Http(401))))
    runPump(listOf(item(0, 0, "sale", "s", "A")), s4, listOf("A"))
    check(s4.drops == 1) { "401 -> dropToken" }
    // the sent-ledger: a rerun skips what was accepted
    val led = SentLedger(null)
    led.markSent(items[0])
    val pump2 = Pump(items, DryRunSender(), RetryPolicy(), led, OrderingGuard(listOf("A")))
    check(pump2.queue.size == 2 && pump2.skippedByLedger == 1) { "ledger skip" }
}

private fun orderingGuard() {
    val g = OrderingGuard(listOf("K"))
    g.check(item(0, 10, "receive", "r", "A"))
    g.check(item(1, 10, "move", "m", "A", "K"))
    g.check(item(2, 20, "sale", "s", "A"))
    fun bad(f: () -> Unit) = check(runCatching(f).exceptionOrNull() is OrderingViolation) { "expected a violation" }
    bad { g.check(item(3, 30, "move", "m2", "A")) } // moved after its sale
    bad { g.check(item(4, 30, "sale", "s2", "A")) } // sold twice
    bad { g.check(item(5, 30, "move", "m3", "ZZ")) } // never received
    bad { g.check(item(6, 5, "move", "m4", "K")) } // goes back in time
    bad { OrderingGuard(emptyList()).also { it.check(item(0, 0, "receive", "r", "A")) }.check(item(1, 0, "receive", "r", "A")) } // received twice
}

private fun ownership() {
    val o = StoreOwnership.parse(listOf("# c", "slug,owner", "dec-us-denver,storeday", "dec-fr-paris,hourly  # x"))
    check(o.refusal("dec-us-denver") == null) { "denver storeday" }
    check(o.refusal("dec-fr-paris") != null && o.refusal("dec-kr-seoul") != null) { "hourly and unlisted refused" }
    check(o.hourlyStores(StoreState.STORE_SLUGS).size == 9 && "dec-us-denver" !in o.hourlyStores(StoreState.STORE_SLUGS)) {
        "the hourly pass keeps the other nine"
    }
    check(runCatching { StoreOwnership.parse(listOf("dec-us-denver,both")) }.isFailure) { "bad owner refused" }
    check(runCatching { StoreOwnership.load(OUT.resolve("no-such-owner-file.csv")) }.isFailure) { "a missing file is a refusal" }
    val ex = StoreOwnership.load(Path.of("ops/store-ops-owner.example.csv"))
    check(ex.refusal(DENVER) == null) { "the committed example gives Denver to the driver" }
}

private fun resetSynthetic() {
    val sim = buildSim(DENVER)
    val m = sim.model
    val period = sim.periods.forDate(FIRST_DAY)
    val slots = period.required.entries.filter { it.value > 0 }.sortedBy { it.key }.take(3)
    val s0 = slots[0].key
    val e0 = s0.ean
    val epc = { n: Int, ean: String -> Sgtin96.encode(ean, 5_000_000L + n) }
    // one EAN in cell s0: 1 unit already on it, 1 surplus on another fixture, 1 in the back room; plus a foreign EAN not in the catalog
    val ledger = mapOf(
        s0.fixture to listOf(epc(1, e0)),
        "ZZ-99" to listOf(epc(2, e0)),
        "BR-01" to listOf(epc(3, e0)),
    )
    val cap = period.required.filter { it.key.ean == e0 }.values.sum()
    val bt = m.backroomTarget[e0] ?: 0
    val reserved = ledger.values.flatten().toSet()
    val r = ResetPlanner.plan(DENVER, ledger, period.required, period.homeLevel, m.backroomTarget, m.homeRack, m.demand.keys, reserved)
    check(r.ledgerUnits == 3 && r.unplaceable.isEmpty()) { "3 units, all placeable" }
    check(r.placedFloor + r.toBackroom + r.unplaceable.size == r.ledgerUnits) { "conservation" }
    val plan = cap + bt
    val recE0 = r.received.filter { Sgtin96.decodeEan(it) == e0 }
    check(recE0.size == plan - minOf(3, plan)) { "received ${recE0.size} = plan $plan - reusable ${minOf(3, plan)}" }
    check(r.received.none { it in reserved } && r.received.toSet().size == r.received.size) { "no collision" }
    check(r.received.all { Sgtin96.serial(it) in StoreState.deliveryBase(DENVER) until StoreState.deliveryEnd(DENVER) }) { "delivery block" }
    // the unit already on its own fixture did not move
    check(r.moves.none { epc(1, e0) in it.epcs }) { "a unit on its own cell stays" }
    // an off-catalog EPC is unplaceable
    val foreign = Sgtin96.encode("4006381333931", 77) // not a Decathlon EAN in the model
    if ("4006381333931" !in m.demand.keys) {
        val r2 = ResetPlanner.plan(
            DENVER,
            mapOf("ZZ-99" to listOf(foreign)),
            period.required,
            period.homeLevel,
            m.backroomTarget,
            m.homeRack,
            m.demand.keys,
            setOf(foreign),
        )
        check(r2.unplaceable.size == 1 && r2.finals.none { it.epc == foreign }) { "off-catalog unit is unplaceable and left alone" }
    }
}

private fun resetDenver() {
    val chain = Path.of("reference/data/chain")
    val empty = OUT.resolve("empty-state")
    Files.createDirectories(empty)
    val pacing = ResetPacing()
    val (inputs, b, model) = StoreDayReset.build(DENVER, FIRST_DAY, chain, empty, pacing)
    val p = b.plan
    check(inputs.truthNote.startsWith("SEED-ONLY")) { "an empty state dir is announced as seed-only" }
    check(p.placedFloor + p.toBackroom + p.unplaceable.size == p.ledgerUnits) {
        "conservation ${p.placedFloor}+${p.toBackroom}+${p.unplaceable.size} != ${p.ledgerUnits}"
    }
    // received = plan - reusable, per EAN and in total
    val planPerEan = HashMap<String, Int>()
    b.period.required.forEach { (s, q) -> planPerEan.merge(s.ean, q, Int::plus) }
    model.backroomTarget.forEach { (e, q) -> planPerEan.merge(e, q, Int::plus) }
    val ledgerPerEan = HashMap<String, Int>()
    inputs.ledger.byFixture.values.flatten().forEach { e ->
        val ean = Sgtin96.decodeEan(e)
        if (ean in model.demand) ledgerPerEan.merge(ean, 1, Int::plus)
    }
    val recPerEan = p.received.groupingBy { Sgtin96.decodeEan(it) }.eachCount()
    for ((ean, want) in planPerEan) {
        val exp = want - minOf(ledgerPerEan[ean] ?: 0, want)
        check((recPerEan[ean] ?: 0) == exp) { "EAN $ean: received ${recPerEan[ean] ?: 0}, plan $want - reusable = $exp" }
    }
    check(recPerEan.keys.all { it in planPerEan }) { "nothing received for an EAN the plan does not name" }
    // every ledger unit appears exactly once in the final state; every final is placeable
    val finals = p.finals.map { it.epc }
    check(finals.toSet().size == finals.size) { "a unit appears twice in the final state" }
    check(finals.size == p.ledgerUnits - p.unplaceable.size + p.received.size) { "final units = ledger placeable + received" }
    // no EPC collides with anything the twin has ever used, and none repeats
    check(p.received.toSet().size == p.received.size && p.received.none { it in inputs.reserved }) { "no collision" }
    check(p.received.all { Sgtin96.serial(it) in StoreState.deliveryBase(DENVER) until StoreState.deliveryEnd(DENVER) }) { "delivery serial block" }
    // schedule: sizes, pace, order
    val items = b.items
    val rec = items.filter { it.kind == "receive" }
    check(
        rec.all {
            it.epcs.size in 1..200
        } && rec.sumOf { it.epcs.size } == p.received.size,
    ) { "receive requests of <= 200 EPCs carry every new EPC" }
    check(rec.zipWithNext().all { (x, y) -> y.dueMs - x.dueMs == pacing.receiveIntervalMs }) { "1 receive request per 10 s" }
    val mov = items.filter { it.kind == "move" }
    check(mov.all { it.epcs.size <= 200 }) { "move batches <= 200 units" }
    check(mov.zipWithNext().all { (x, y) -> y.dueMs - x.dueMs >= 500L }) { "moves at most 2 a second" }
    check(items.map { it.dueMs }.zipWithNext().all { (x, y) -> y >= x }) { "schedule is in time order" }
    check(items.last().kind == "planogram" && items.last().payload.path("expiry_date").asText() == b.period.end.toString()) {
        "directive last, with its stop date"
    }
    check(items.first().kind == "receive" || p.received.isEmpty()) { "receipts first" }
    val g = OrderingGuard(inputs.ledger.byFixture.values.flatten())
    items.forEach { g.check(it) } // the per-unit ordering rule holds across the whole schedule
    // ids: unique, and the same on a second build
    check(items.map { it.extId }.toSet().size == items.size) { "ids unique" }
    val b2 = StoreDayReset.build(DENVER, FIRST_DAY, chain, empty, pacing).second
    check(
        b2.items.map {
            it.extId
        } == items.map { it.extId } && b2.items.map { it.dueMs } == items.map { it.dueMs },
    ) { "ids and times stable across reruns" }
    // a hard pace error is refused
    check(
        runCatching { ResetPacing(movesPerS = 3.0) }.isFailure && runCatching { ResetPacing(receiveIntervalMs = 100) }.isFailure &&
            runCatching { ResetPacing(receiveChunk = 201) }.isFailure,
    ) { "pace ceilings enforced" }
    // post-reset state: loads back, every unit placed, the period is recorded so the store-day does not re-issue it
    val st = StoreDayReset.postState(DENVER, p, FIRST_DAY, b.period.start)
    st.save(OUT.resolve("post-reset"))
    val back = StoreState.load(OUT.resolve("post-reset"))
    check(back.size == finals.size && back.planPeriod == b.period.start && back.asOf == FIRST_DAY.minusDays(1) && back.nextSerial == p.nextSerial) {
        "post-reset snapshot round-trips"
    }
    check(back.count(UnitState.ON_FLOOR) == p.placedFloor + p.receivedToCells) { "floor units in the snapshot" }
    // the Denver numbers, for the PR body
    println(
        "      Denver seed ledger: ${p.ledgerUnits} units -> floor ${p.placedFloor}, back room ${p.toBackroom}, unplaceable ${p.unplaceable.size}; " +
            "receive ${p.received.size} in ${rec.size} requests, ${mov.size} move batches (${p.movedUnits} units)",
    )
}

private fun driverDry() {
    val before = LiveClients.constructed
    val sim = buildSim(DENVER)
    val state = sim.initialState()
    val zone = ChainKeys.load().store(DENVER).zone
    val sender = DryRunSender()
    val clock = SimClock(0)
    val served = StoreOpsLive.serve(sim, state, FIRST_DAY, 1, clock, 5_000, sender, RetryPolicy(), SentLedger(null), {
        false
    }, 30_000, OUT.resolve("driver-state"), zone)
    check(served.halted == null && !served.held) { "ran clean" }
    check(
        served.planned.isNotEmpty() && served.planned.any {
            it.kind == "sale"
        } && served.planned.any {
            it.kind == "move"
        },
    ) { "a Denver day plans receipts, moves and sales: ${served.planned.groupingBy { it.kind }.eachCount()}" }
    check(
        served.sent.map {
            it.extId
        } == served.planned.map {
            it.extId
        },
    ) { "the driver 'sent' exactly the planned items, in plan order (${served.sent.size} of ${served.planned.size})" }
    check(served.planned.none { it.kind == "person_samples_ref" }) { "no people refs among the sends" }
    // the plan equals the store-day's own wire plan for the same day (same seed, same state)
    val sim2 = buildSim(DENVER)
    val r = sim2.simulate(sim2.initialState(), FIRST_DAY, sim2.behavior.params.seed)
    check(
        served.planned.map {
            it.extId
        } == r.wire.filter { it.kind != "person_samples_ref" }.map { SendItem.fromWire(it, DENVER).extId },
    ) { "= StoreDaySim's wire plan" }
    // the saved day-start state is the one the day started from
    check(StoreState.load(OUT.resolve("driver-state/day-start")).asOf == null) { "day-start snapshot saved before the day was simulated" }
    // HOLD stops the driver without sending a thing
    val held = StoreOpsLive.serve(
        buildSim(DENVER).let {
            it
        },
        buildSim(DENVER).initialState(), FIRST_DAY, 1, SimClock(0), 5_000, DryRunSender(), RetryPolicy(), SentLedger(null), {
            true
        }, 30_000, null, zone,
    )
    check(held.held && held.sent.isEmpty()) { "HOLD set: nothing sent" }
    // a flaky platform: 502s and a 429 slow the day down but lose nothing and keep the order
    val flaky = object : OpsSender {
        val inner = DryRunSender()
        var n = 0
        var h = 0

        override fun health(): Pair<Int, String?> = if (++h in 3..9) 502 to null else 200 to "{\"status\":\"ok\"}"

        override fun send(item: SendItem): Outcome {
            n++
            return when (n) {
                5 -> Outcome.Http(429)
                8 -> Outcome.Http(502)
                else -> inner.send(item)
            }
        }
    }
    val sim3 = buildSim(DENVER)
    val f = StoreOpsLive.serve(sim3, sim3.initialState(), FIRST_DAY, 1, SimClock(0), 5_000, flaky, RetryPolicy(), SentLedger(null), {
        false
    }, 30_000, null, zone)
    check(
        f.halted == null && flaky.inner.sent.map {
            it.extId
        } == f.planned.map { it.extId },
    ) { "after 429/502 every event is still sent, once, in order (${flaky.inner.sent.size}/${f.planned.size})" }
    check(LiveClients.constructed == before) { "no live client was built" }
    println(
        "      one Denver day: ${served.planned.size} items ${served.planned.groupingBy {
            it.kind
        }.eachCount()}, ${served.peopleRefs} people refs left to the people plane",
    )
}

private fun noNetwork() {
    val n0 = LiveClients.constructed
    check(
        runCatching {
            LiveSender.create(false, DENVER, Path.of("reference/data/chain"), OUT)
        }.exceptionOrNull() is IllegalArgumentException,
    ) { "live=false refused" }
    check(LiveClients.constructed == n0) { "a refused build counts nothing" }
    check(DryRunSender().health().first == 200 && DryRunSender().send(item(0, 0, "sale", "s", "A")) == Outcome.Accepted) {
        "the dry sender answers from memory"
    }
    // no source file in the dry-run path calls the live factory: only its own file names it, and nothing in this slice calls create()
    val callers = Files.walk(Path.of("src/main/kotlin")).use { s ->
        s.filter { it.toString().endsWith(".kt") && !it.endsWith("LiveSender.kt") && !it.endsWith("StoreOpsSelfTest.kt") }
            .filter { Files.readString(it).contains("LiveSender.create(") }.map { it.toString() }.toList()
    }
    check(callers.isEmpty()) { "LiveSender.create is called from $callers" }
    // and the storeops package imports no http/nats class except LiveSender
    val offenders = Files.list(Path.of("src/main/kotlin/com/m8trx/twin/storeops")).use { s ->
        s.filter { it.toString().endsWith(".kt") && !it.endsWith("LiveSender.kt") && !it.endsWith("StoreOpsSelfTest.kt") }
            .filter { f ->
                Files.readString(f).let {
                    it.contains("ConnectHttp") || it.contains("io.nats") || it.contains("java.net.http") ||
                        it.contains("WebhookClient")
                }
            }
            .map { it.fileName.toString() }.toList()
    }
    check(offenders.isEmpty()) { "network classes referenced outside LiveSender.kt: $offenders" }
}

private fun gapCheck() {
    val s = StoreDayGapCheck.SIGNALS
    // the § 5.1 signals the brief names
    listOf("sales_per_fixture", "sales_per_item_on_fixture", "impressions", "dwell", "interactions", "flows", "on_hand_vs_plan").forEach { k ->
        check(s.any { it.key == k }) { "signal $k missing" }
    }
    check(s.all { it.surface.isNotBlank() } && s.any { it.surface.startsWith("no surface") }) { "each signal names a surface or 'no surface'" }
    check(
        s.any { it.surface.contains("/compliance/state") } &&
            s.any {
                it.surface.contains("spatial") || it.note.contains("spatial") || it.surface.contains("tasks") || it.surface.contains("task")
            },
    ) { "the named Connect reads appear" }
    StoreDayGapCheck.write(OUT.resolve("gap"), DENVER)
    val t = Files.readString(OUT.resolve("gap/GAP-LEDGER.template.md"))
    check(listOf("captured", "captured wrong", "not captured", "no surface").all { t.contains(it) }) { "classification columns in the template" }
    val committed = Path.of("status/GAP-LEDGER.md")
    check(Files.exists(committed) && Files.readString(committed) == t) { "status/GAP-LEDGER.md is the template (regenerate with storeDayGapCheck)" }
}

private fun liveDefault() {
    val chain = Path.of("reference/data/chain")
    val keys = ChainKeys.load(chain.resolve(ChainKeys.DEFAULT_PATH.fileName))
    val s = LiveDrive.Settings.fromEnv(keys)
    check(s.peopleSource == PeopleSource.Generator) {
        "default source = generator (env ${PeopleSource.ENV_SOURCE} unset: ${System.getenv(PeopleSource.ENV_SOURCE)})"
    }
    val siteIds = DatedStream.readSiteIds(chain.resolve("site_ids.csv"))
    val zone = keys.store(DENVER).zone
    val date = LocalDate.parse("2026-10-14")
    val now = Instant.parse("2026-10-14T06:00:00Z").toEpochMilli()
    val planAt = LiveDrive.planAnchor(Instant.ofEpochMilli(now), zone, LiveDrive.Shift.Open).toEpochMilli()
    val p = LiveDrive.planDay(s, keys, siteIds, DENVER, LiveDrive.Clock(now, planAt, 1), date, planAt)
    val md = MessageDigest.getInstance("SHA-256")
    p.wires.forEach { md.update("${it.dueMs}|${it.objectId}|${it.seq}|${it.sample}\n".toByteArray()) }
    val sha = md.digest().joinToString("") { "%02x".format(it) }
    check(p.wires.size == LIVE_GOLDEN_WIRES && sha == LIVE_GOLDEN) {
        "Denver's live plan changed: ${p.wires.size} wires sha $sha (golden $LIVE_GOLDEN_WIRES $LIVE_GOLDEN)"
    }
    // explicit generator = the default
    val p2 = LiveDrive.planDay(s.copy(peopleSource = PeopleSource.Generator), keys, siteIds, DENVER, LiveDrive.Clock(now, planAt, 1), date, planAt)
    check(p2.wires == p.wires) { "explicit generator == default" }
}

private fun liveSidecar() {
    check(
        PeopleSource.parse(null, null) == PeopleSource.Generator && PeopleSource.parse("", "x") == PeopleSource.Generator &&
            PeopleSource.parse("generator", null) == PeopleSource.Generator,
    ) { "defaults" }
    val sc = PeopleSource.parse("storeday", "out/day") as PeopleSource.StoreDaySidecar
    check(sc.dir == Path.of("out/day")) { "sidecar dir" }
    check(runCatching { PeopleSource.parse("both", null) }.isFailure) { "unknown refused" }
    val dir = OUT.resolve("sidecar/$DENVER/2026-11-23")
    Files.createDirectories(dir)
    Files.writeString(
        dir.resolve("samples.jsonl"),
        "{\"shopper\":\"cust-1\",\"samples\":[[1000,10,20],[1200,11,21]]}\n{\"shopper\":\"cust-2\",\"samples\":[[5000,1,2]]}\n",
    )
    val m = PeopleSource.readSidecar(PeopleSource.sidecarFile(OUT.resolve("sidecar"), DENVER, LocalDate.parse("2026-11-23")))
    check(
        m.keys.toList() == listOf("cust-1", "cust-2") && m.getValue("cust-1").size == 2 && m.getValue("cust-1")[1].tsMs == 1200L &&
            m.getValue("cust-1")[1].p.x == 11.0,
    ) { "sidecar read" }
    check(runCatching { PeopleSource.readSidecar(OUT.resolve("nope.jsonl")) }.isFailure) { "a missing sidecar is refused, never a silent fallback" }
}
