package com.m8trx.twin.storeops

import com.m8trx.twin.connect.Sgtin96
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.ops.StoreStock
import com.m8trx.twin.storeday.Slot
import com.m8trx.twin.storeday.StoreDayModel
import com.m8trx.twin.storeday.StoreState
import com.m8trx.twin.storeday.buildSim
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.system.exitProcess

/** `./gradlew dailyDeliverySelfTest` - the daily delivery, offline: no network, no credentials; files only under `build/delivery-selftest/`. */
private val OUT: Path = Path.of("build/delivery-selftest")
private const val DENVER = "dec-us-denver"
private val DATE: LocalDate = LocalDate.parse("2026-11-22")
private var failures = 0

private fun check(ok: Boolean, msg: () -> String) {
    if (!ok) {
        failures++
        println("      FAIL ${msg()}")
    }
}

private fun item(kind: String, id: String, vararg epcs: String, dest: String = "@backroom_zone:BR-01"): SendItem {
    val payload = ConnectMappers.snake.createObjectNode()
    payload.put("external_movement_id", id)
    payload.put("to_zone_id", dest)
    val a = payload.putArray("items")
    epcs.forEach { a.addObject().put("epc", it) }
    return SendItem(0, 0, 1, kind, id, payload, epcs.toList())
}

private fun entry(key: String, outcome: String, moved: Int = 0, skipped: Int = 0, superseded: Int = 0, refused: Int = 0) =
    ResultEntry(key, outcome, moved, skipped, superseded, refused, """{"idempotency_key":"$key","outcome":"$outcome"}""")

fun main() {
    Files.createDirectories(OUT)
    val clientsBefore = LiveClients.constructed
    orderRounding()
    planOnDenver()
    verifier()
    reviewRules()
    senderAndBooks()
    gates()
    parsing()
    check(LiveClients.constructed == clientsBefore) { "a dry path built a live client" }
    println(if (failures == 0) "dailyDeliverySelfTest: ALL PASS" else "dailyDeliverySelfTest: $failures FAILURE(S)")
    exitProcess(if (failures == 0) 0 else 1)
}

private fun orderRounding() {
    println("[order] target - on hand, positive gaps only, rounded to the NEAREST case (bikes singles)")
    val cs = { ean: String -> if (ean == "BIKE") 1 else 6 }
    val o = DailyDelivery.order(
        mapOf("A" to 4, "B" to 2, "C" to 0, "BIKE" to 1),
        mapOf("A" to 7, "B" to 0, "C" to 5, "BIKE" to 1),
        mapOf("A" to 3, "B" to 9, "C" to 5, "BIKE" to 0),
        cs,
    )
    val m = o.associateBy { it.ean }
    check(m["A"]?.qty == 6) { "A: target 11, on hand 3, gap 8 is one case, got ${m["A"]?.qty}" }
    check(m["B"] == null && m["C"] == null) { "no order for a met or exceeded target" }
    check(m["BIKE"]?.qty == 2) { "a bike orders singles: gap 2, got ${m["BIKE"]?.qty}" }
    // the ruling: shortfall 1-2 orders nothing, 3-8 one case, 9-14 two, 15-20 three
    val expect = mapOf(1 to 0, 2 to 0, 3 to 6, 8 to 6, 9 to 12, 14 to 12, 15 to 18, 20 to 18, 21 to 24)
    expect.forEach { (gap, qty) ->
        val q = DailyDelivery.order(mapOf("X" to gap), emptyMap(), emptyMap(), cs).firstOrNull()?.qty ?: 0
        check(q == qty) { "shortfall $gap orders $qty, got $q" }
    }
    check(
        o.all {
            it.qty % cs(it.ean) == 0 && Math.abs(it.qty - it.gap) <= cs(it.ean) / 2
        },
    ) { "every line is the nearest whole case, within 3 of its gap" }
}

private fun planOnDenver() {
    println("[plan] on the real Denver model: collisions, put-away by to_zone_id, top-up bounded by plan and back room")
    val chain = Path.of("reference/data/chain")
    val model = StoreDayModel(DENVER, chain, 7)
    val period = buildSim(DENVER).periods.forDate(DATE)
    val seed = StoreStock.load(chain, OUT.resolve("empty-state"), DENVER)
    val zoneIds = com.m8trx.twin.history.ChainKeys.load(chain.resolve(com.m8trx.twin.history.ChainKeys.DEFAULT_PATH.fileName)).store(DENVER)
        .zones.associate { it.code to it.zoneId }

    // the seed ledger with its floor sold down: 40% of the floor units gone, plus ONE cell emptied entirely
    val ledger = HashMap<String, List<String>>()
    seed.byFixture.forEach { (loc, epcs) -> ledger[loc] = if (loc.startsWith("BR-")) epcs else epcs.filterIndexed { i, _ -> i % 5 >= 2 } }
    val reserved = (seed.byFixture.values.flatten() + seed.byFixture.values.flatten()).toSet()
    val plan = DailyDelivery.plan(DENVER, DATE, model, period.required, ledger, reserved, zoneIds)
    check(plan.order.isNotEmpty() && plan.received.isNotEmpty()) { "a sold-down store has an order" }
    check(plan.received.size == plan.order.sumOf { it.qty }) { "units received = the order's units" }
    check(plan.received.toSet().size == plan.received.size) { "minted EPCs are distinct" }
    check(plan.received.none { it in reserved }) { "no minted EPC is one the ledger has used" }
    check(
        plan.received.all {
            Sgtin96.serial(it) >= StoreState.deliveryBase(DENVER) && Sgtin96.serial(it) < StoreState.deliveryEnd(DENVER)
        },
    ) { "serials stay in the delivery block" }
    val decodedOrder = plan.received.groupBy { Sgtin96.decodeEan(it) }.mapValues { it.value.size }
    check(plan.order.all { decodedOrder[it.ean] == it.qty }) { "each line's EPCs decode to its EAN, qty times" }
    check(plan.order.all { it.qty % model.caseSize(it.ean) == 0 }) { "orders are whole cases" }

    // a second run on a ledger that already holds the first run's EPCs mints past them
    val again = DailyDelivery.plan(DENVER, DATE, model, period.required, ledger, reserved + plan.received, zoneIds)
    check(again.received.none { it in plan.received }) { "a re-plan with the received EPCs reserved collides with nothing" }

    val moves = plan.items.filter { it.kind == "move" }
    check(
        moves.all {
            it.payload.path("to_fixture_code").isMissingNode || it.payload.path("to_fixture_code").isNull
        },
    ) { "no move goes by to_fixture_code" }
    check(moves.all { it.payload.path("to_zone_id").asText().isNotBlank() }) { "every move has a to_zone_id" }
    val pa = plan.items.filter(DailyDelivery::isPutAway)
    check(
        pa.isNotEmpty() && pa.all {
            it.payload.path("to_zone_id").asText().startsWith(Placeholders.ZONE_PREFIX)
        },
    ) { "put-away goes to a back-room rack zone" }
    check(pa.flatMap { it.epcs }.toSet() == plan.received.toSet()) { "every received unit, and only those, is put away" }
    check(moves.all { it.epcs.size <= DailyDelivery.MOVE_CHUNK }) { "move batches carry at most 25 units" }
    check(plan.items.filter { it.kind == "receive" }.all { it.epcs.size <= DailyDelivery.RECEIVE_CHUNK }) { "receive requests carry at most 50 tags" }
    val firstTop = plan.items.indexOfFirst(DailyDelivery::isTopUp)
    check(firstTop < 0 || plan.items.drop(firstTop).none(DailyDelivery::isPutAway)) { "every put-away precedes every top-up" }

    // top-up: never past plan, never more than the back room holds of the EAN
    val fixtureOfZone = zoneIds.entries.associate { it.value to it.key }
    val floorNow = HashMap<Slot, Int>()
    ledger.forEach { (loc, epcs) -> if (!loc.startsWith("BR-")) epcs.forEach { floorNow.merge(Slot(loc, Sgtin96.decodeEan(it)), 1, Int::plus) } }
    val backHolds = HashMap<String, Int>()
    ledger.forEach { (loc, epcs) -> if (loc.startsWith("BR-")) epcs.forEach { backHolds.merge(Sgtin96.decodeEan(it), 1, Int::plus) } }
    plan.received.forEach { backHolds.merge(Sgtin96.decodeEan(it), 1, Int::plus) }
    val movedTo = HashMap<Slot, Int>()
    val movedEan = HashMap<String, Int>()
    plan.items.filter(DailyDelivery::isTopUp).forEach { m ->
        val fx = fixtureOfZone.getValue(m.payload.path("to_zone_id").asText())
        m.epcs.forEach {
            val ean = Sgtin96.decodeEan(it)
            movedTo.merge(Slot(fx, ean), 1, Int::plus)
            movedEan.merge(ean, 1, Int::plus)
        }
    }
    check(movedTo.all { (s, n) -> (floorNow[s] ?: 0) + n <= (period.required[s] ?: 0) }) { "a cell is never topped up past its plan quantity" }
    check(movedEan.all { (e, n) -> n <= (backHolds[e] ?: 0) }) { "top-up never moves more of an EAN than the back room holds" }
    val reachable = period.required.entries.filter { it.value > 0 }.groupBy({ it.key.ean }, { it.key }).all { (ean, cells) ->
        val deficit = cells.sumOf { maxOf(0, (period.required[it] ?: 0) - (floorNow[it] ?: 0)) }
        (movedEan[ean] ?: 0) == minOf(deficit, backHolds[ean] ?: 0)
    }
    check(reachable) { "the floor reaches plan, or the back room runs out of that EAN (top-up moves exactly min(short, back room))" }
    check(plan.topUp.flatMap { it.epcs }.toSet().size == plan.topUp.sumOf { it.epcs.size }) { "no unit is topped up twice" }

    // most short first: for an EAN with several cells and some top-up, the cell with the largest deficit received units
    val mostShortOk = period.required.entries.filter { it.value > 0 }.groupBy({ it.key.ean }, { it.key }).all { (ean, cells) ->
        val deficit = cells.associateWith { maxOf(0, (period.required[it] ?: 0) - (floorNow[it] ?: 0)) }
        val most = deficit.maxOf { it.value }
        (movedEan[ean] ?: 0) == 0 || most == 0 || deficit.filter { it.value == most }.keys.any { (movedTo[it] ?: 0) > 0 }
    }
    check(mostShortOk) { "the most short cell of an EAN is served first" }

    // a store already at target orders nothing; a store at target minus a few orders only that
    val full = HashMap<String, List<String>>()
    val fullPlan = DailyDelivery.plan(DENVER, DATE, model, period.required, ledger, reserved, zoneIds)
    full.putAll(ledger)
    full["BR-99"] = fullPlan.received + fullPlan.order.flatMap { emptyList<String>() }
    val topped = DailyDelivery.plan(DENVER, DATE, model, period.required, full, reserved + fullPlan.received, zoneIds)
    check(topped.order.isEmpty() && topped.received.isEmpty()) { "a store holding its target orders nothing (${topped.order.size} lines left)" }

    // the dry plan writes files and builds no client
    val before = LiveClients.constructed
    val steady = SteadyState.simulate(model, period.required, DATE)
    check(steady.meanUnits > 0 && steady.meanLines > 0) { "the steady-state simulation orders something" }
    check(steady.meanUnits == SteadyState.simulate(model, period.required, DATE).meanUnits) { "the steady-state simulation is deterministic" }
    check(LiveClients.constructed == before) { "planning built a live client" }
    println(
        "      Denver sold-down: order ${plan.order.size} lines / ${plan.orderUnits} units, ${plan.receiveRequests} requests, " +
            "${plan.putAway.size} put-away + " +
            "${plan.topUp.size} top-up batches, ${"%.1f".format(plan.estimateMinutes())} min; steady ${"%.0f".format(steady.meanUnits)} units a day",
    )
}

private fun verifier() {
    println("[verify] results route: book only on confirmed moved; halt on refused / warning / failed; pending waits")
    var t = 0L
    val booked = ArrayList<String>()
    val calls = ArrayList<Int>()
    var answers: Map<String, ResultEntry> = emptyMap()
    val v = MoveVerifier({ keys ->
        calls += keys.size
        keys.mapNotNull { answers[it] }
    }, { booked += it.extId }, { t }, minAgeMs = 1_000, timeoutMs = 60_000)
    v.track(item("move", "m-ok", "E1", "E2"), "k-ok")
    v.track(item("move", "m-pend", "E3"), "k-pend")
    v.track(item("move", "m-sup", "E4", "E5"), "k-sup")
    v.track(item("move", "m-skip", "E6", "E7"), "k-skip")
    answers = mapOf(
        "k-ok" to entry("k-ok", "processed", moved = 2),
        "k-pend" to entry("k-pend", "pending"),
        "k-sup" to entry("k-sup", "processed", moved = 1, superseded = 1),
        "k-skip" to entry("k-skip", "processed", moved = 1, skipped = 1),
    )
    v.poll()
    check(booked.isEmpty()) { "nothing is read before the minimum age" }
    t = 2_000
    v.poll()
    check(booked.toSet() == setOf("m-ok", "m-skip")) { "booked $booked: a fully moved batch and one with units already at the destination" }
    check(v.unconfirmed.size == 1 && v.unconfirmed[0].startsWith("m-sup")) { "a superseded unit is listed unconfirmed, not booked" }
    check(v.bad == null && v.pendingCount == 1) { "pending stays pending, no halt yet (${v.bad})" }
    answers = answers + ("k-pend" to entry("k-pend", "processed", moved = 1))
    t = 4_000
    v.poll()
    check("m-pend" in booked && v.pendingCount == 0) { "the pending batch is booked once processed" }
    check(v.confirmedUnits == 2 + 1 + 2) { "confirmed units counted (${v.confirmedUnits})" }

    for ((label, e) in listOf(
        "refused" to entry("k", "processed", moved = 1, refused = 1),
        "warning" to entry("k", "warning", moved = 1),
        "failed" to entry("k", "failed"),
    )) {
        val b = ArrayList<String>()
        val w = MoveVerifier({ listOf(e) }, { b += it.extId }, { 10_000 }, minAgeMs = 0)
        w.track(item("move", "m-$label", "E1", "E2"), "k")
        w.poll()
        check(w.bad != null && b.isEmpty()) { "$label halts the site and books nothing" }
    }
    val stale = MoveVerifier({ keys -> keys.map { entry(it, "not_found") } }, {}, { t }, minAgeMs = 0, timeoutMs = 5_000)
    t = 0
    stale.track(item("move", "m-lost", "E1"), "kx")
    stale.poll()
    check(stale.bad == null) { "not_found right after the post is only pending" }
    t = 6_000
    stale.poll()
    check(stale.bad != null) { "not_found past the timeout halts" }

    val many = MoveVerifier({ keys ->
        calls += keys.size
        keys.map { entry(it, "processed", moved = 1) }
    }, {}, { 100_000 }, minAgeMs = 0)
    calls.clear()
    repeat(85) { many.track(item("move", "m$it", "E$it"), "k$it") }
    many.poll()
    check(calls == listOf(40, 40, 5) && many.pendingCount == 0) { "keys go 40 to a request: $calls" }
    check(many.drain(1_000) { }) { "drain returns once everything settled" }
}

private fun senderAndBooks() {
    println("[sender] answers.tsv row written before the post, slow answers halt, moves never booked on acceptance")
    val dir = OUT.resolve("sender")
    dir.toFile().deleteRecursively()
    Files.createDirectories(dir)
    val answers = dir.resolve("answers.tsv")
    var clock = 0L
    var rowsAtSend = -1
    val inner = object : OpsSender {
        override fun health() = 200 to "{\"status\":\"ok\"}"

        override fun send(item: SendItem): Outcome {
            rowsAtSend = Files.readAllLines(answers).size
            clock += 11_000
            return Outcome.Accepted
        }
    }
    val s = DeliverySender(inner, DENVER, answers, clockMs = { clock })
    s.send(item("move", "m1", "E1"))
    check(rowsAtSend == 1) { "the answers row exists before the post ($rowsAtSend)" }
    check(s.slow != null) { "an answer after 11 s halts the site" }
    s.appendAnswer("m1", "abc", "2026-11-22T00:00:00Z")
    check(DeliverySender.readKeys(answers)["m1"] == "abc") { "the later row fills the key" }
    check(EventKey.of("""{"accepted":true,"eventKey":"ff00","receivedAt":"t"}""") == ("ff00" to "t")) { "eventKey parsed from the 200" }
    check(EventKey.of("not json").first.isEmpty()) { "an answer without a key gives a blank" }

    val state = dir.resolve("state")
    val placed = state.resolve("moves/placements.csv")
    val recv = ArrayList<String>()
    val books = DeliveryBooks { recv += it.extId }
    books.record(item("move", "m2", "E1"))
    check(recv.isEmpty()) { "an accepted move is not booked by the runner" }
    books.record(SendItem(0, 0, 0, "receive", "r1", ConnectMappers.snake.createObjectNode(), listOf("E1")))
    check(recv == listOf("r1")) { "a counted receive goes to the receive books" }
    DeliveryBooks.bookMove(placed, item("move", "m3", "E8", "E9", dest = "@backroom_zone:BR-04"), emptyMap(), DATE)
    DeliveryBooks.bookMove(placed, item("move", "m4", "E7", dest = "Z-1"), mapOf("Z-1" to "GF-A1"), DATE)
    val p = com.m8trx.twin.connect.RestockMoves.readPlacements(placed)
    check(p["E8"] == "BR-04" && p["E9"] == "BR-04" && p["E7"] == "GF-A1") { "confirmed moves book the rack or the fixture code: $p" }
}

private fun gates() {
    println("[gates] live refusals, opening time, pace")
    val owner = OUT.resolve("owner.csv")
    Files.createDirectories(OUT)
    Files.writeString(owner, "slug,owner\n$DENVER,storeday\ndec-fr-paris,hourly\n")
    val full = mapOf(
        "M8TRX_TWIN_WEBHOOK_KEY" to "k",
        "M8TRX_TENANT_ID" to "t",
        "M8TRX_CONNECT_INTEGRATION_SLUG" to "s",
        "M8TRX_TWIN_SERVICE_BEARER" to "b",
    )
    val ok = DeliveryGate.check(true, setOf(DENVER), DENVER, { full[it] }, owner, 25, 7_500)
    check(ok.isEmpty()) { "a fully keyed live run passes: $ok" }
    check(DeliveryGate.check(false, emptySet(), null, { null }, owner, 99, 1).isEmpty()) { "a dry run is never refused" }
    check(DeliveryGate.check(true, setOf(DENVER), null, { full[it] }, owner, 25, 7_500).any { "second key" in it }) { "no confirm refused" }
    check(
        DeliveryGate.check(true, setOf(DENVER, "dec-fr-paris"), DENVER, {
            full[it]
        }, owner, 25, 7_500).any { "second key" in it },
    ) { "confirm must equal the sites" }
    check(
        DeliveryGate.check(true, setOf("dec-fr-paris"), "dec-fr-paris", {
            full[it]
        }, owner, 25, 7_500).any { "hourly" in it },
    ) { "an hourly-owned store is refused" }
    check(DeliveryGate.check(true, setOf(DENVER), DENVER, { null }, owner, 25, 7_500).size >= 4) { "missing credentials refused" }
    check(DeliveryGate.check(true, setOf(DENVER), DENVER, { full[it] }, owner, 26, 7_500).isNotEmpty()) { "a batch over 25 is refused" }
    check(
        DeliveryGate.check(true, setOf(DENVER), DENVER, {
            full[it]
        }, owner, 25, 7_000).isNotEmpty(),
    ) { "a pace over 200 items a minute is refused" }
    val z = java.time.ZoneId.of("America/Denver")
    val open = java.time.LocalTime.of(10, 0)
    val at = { h: Int -> DATE.atTime(h, 0).atZone(z).toInstant().toEpochMilli() }
    check(DeliveryGate.openRefusal(DENVER, DATE, open, z, at(8), false) == null) { "before opening is allowed" }
    check(DeliveryGate.openRefusal(DENVER, DATE, open, z, at(10), false) != null) { "at opening is refused" }
    check(DeliveryGate.openRefusal(DENVER, DATE, open, z, at(11), true) == null) { "ALLOW_OPEN overrides" }
    check(DeliveryGate.openRefusal(DENVER, DATE, null, z, at(11), false) == null) { "a closed day has no opening to beat" }
    check(DailyDelivery.MOVE_CHUNK * 60_000.0 / DailyDelivery.MOVE_SPACING_MS <= DailyDelivery.MAX_ITEMS_PER_MINUTE) {
        "25 every 7.5 s is at most 200 a minute"
    }
}

private fun parsing() {
    println("[results] parse and url")
    val body = """{"results":[{"idempotency_key":"a","outcome":"warning","result":{"moved":1,"skipped":0,"superseded":0,"refused":1,
        "refused_items":[{"epc":"E1","reason":"item_not_found"}]}},{"idempotency_key":"b","outcome":"not_found"}]}"""
    val e = ResultEntry.parse(body)
    check(e.size == 2 && e[0].refused == 1 && e[0].moved == 1 && e[0].outcome == "warning" && e[1].outcome == "not_found") { "entries parsed" }
    val cfg = com.m8trx.twin.connect.ConnectConfig("a", "https://h/v1/webhook", "T", "S", null, "K", null, null)
    check(ResultsReader.url(cfg, listOf("k1", "k2")) == "https://h/v1/webhook/T/S/results?idempotency_key=k1&idempotency_key=k2") {
        "the key is repeated once per event key: ${ResultsReader.url(cfg, listOf("k1", "k2"))}"
    }
}

private fun reviewRules() {
    println("[review] unconfirmed put-away halts and is dropped from the top-up; items-a-minute cap; no results read under the track lock")
    // 1. an unconfirmed put-away halts the site, books nothing, and stays pending (not settled)
    val booked = ArrayList<String>()
    val settled = ArrayList<String>()
    val v = MoveVerifier(
        { keys -> keys.map { entry(it, "processed", moved = 1, superseded = 1) } },
        { booked += it.extId },
        { 10_000 },
        minAgeMs = 0,
        onSettled = { id, how -> settled += "$id=$how" },
    )
    v.track(item("move", "dly-x-20261122-p-a", "E1", "E2"), "kp")
    v.poll()
    check(v.bad != null && "put-away not confirmed" in v.bad.orEmpty()) { "an unconfirmed put-away halts the site, entry printed: ${v.bad}" }
    check(booked.isEmpty() && settled.isEmpty() && v.pendingCount == 1) { "nothing booked, not settled (a resume reads it again)" }

    // the top-up drops units whose put-away is not confirmed
    val pa1 = item("move", "dly-x-20261122-p-1", "E1", "E2")
    val pa2 = item("move", "dly-x-20261122-p-2", "E3")
    val t1 = item("move", "dly-x-20261122-t-1", "E1", "E3", "E9", dest = "Z-9")
    val t2 = item("move", "dly-x-20261122-t-2", "E2", dest = "Z-9")
    val (kept, dropped) = DailyDelivery.dropUnconfirmed(listOf(t1, t2), listOf(pa1, pa2), setOf(pa2.extId))
    check(dropped.toSet() == setOf("E1", "E2")) { "dropped $dropped" }
    check(kept.size == 1 && kept[0].epcs == listOf("E3", "E9")) { "the batch keeps its other units, a batch left empty goes" }
    check(kept[0].payload.path("items").map { it.path("epc").asText() } == listOf("E3", "E9") && kept[0].extId != t1.extId) {
        "the body and the id follow the kept units"
    }
    check(DailyDelivery.dropUnconfirmed(listOf(t1), listOf(pa1, pa2), setOf(pa1.extId, pa2.extId)).first == listOf(t1)) {
        "all confirmed keeps everything"
    }
    check(DailyDeliveryMain.MAX_RECEIVE_LANES == 3) { "the delivery caps receive lanes at 3" }

    // 2. rolling items a minute: 150 with a receive lane, 200 alone, shared across batches
    for ((cap, batches) in listOf(RollingItemLimiter.WITH_RECEIVES to 6, RollingItemLimiter.ALONE to 8)) {
        var t = 0L
        val lim = RollingItemLimiter({ t }, { t += it })
        repeat(batches) { lim.acquire(25, cap) }
        check(t == 0L) { "cap $cap: $batches batches of 25 fit in the first minute (t=$t)" }
        lim.acquire(25, cap)
        check(t >= 60_000) { "cap $cap: the next batch waits for the window to roll (t=$t)" }
    }
    var tt = 0L
    val l2 = RollingItemLimiter({ tt }, { tt += it })
    var sent = 0
    repeat(40) {
        l2.acquire(25, RollingItemLimiter.WITH_RECEIVES)
        sent += 25
    }
    check(sent * 60_000.0 / maxOf(1L, tt) <= 175) { "40 batches average about 150 items a minute (${sent * 60_000.0 / tt})" }

    // 3. a slow results read never blocks track() or a send
    val entered = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    val slowV = MoveVerifier({ keys ->
        entered.countDown()
        release.await()
        keys.map { entry(it, "processed", moved = 1) }
    }, {}, { 100_000 }, minAgeMs = 0)
    slowV.track(item("move", "m-a", "E1"), "ka")
    val poller = Thread { slowV.poll() }
    poller.start()
    check(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "the read started" }
    val tracker = Thread {
        slowV.track(item("move", "m-b", "E2"), "kb")
        slowV.fail("probe")
    }
    tracker.start()
    tracker.join(2_000)
    val blocked = tracker.isAlive
    release.countDown()
    poller.join(5_000)
    check(!blocked) { "track() and fail() completed while a results read was in flight" }
}
