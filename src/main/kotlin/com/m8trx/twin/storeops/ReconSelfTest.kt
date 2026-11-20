package com.m8trx.twin.storeops

import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.bearer.SpatialIdentityResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.system.exitProcess

/**
 * `./gradlew reconcileSelfTest` - the reconcile sender, offline: no network, no credentials; files only under `build/recon-selftest/`.
 */
private val OUT: Path = Path.of("build/recon-selftest")
private var failures = 0

private fun check(ok: Boolean, msg: () -> String) {
    if (!ok) {
        failures++
        println("      FAIL ${msg()}")
    }
}

private val LAYOUT = ConnectMappers.snake.readTree(
    """{"spaces":[
      {"space_type":"sales_floor","zones":[{"code":"GF-R1-U1","name":"Gondola R1 Front U1"},{"code":"GF-R1-U2","name":"Gondola R1 Front U2"},
        {"code":"NOID-01","name":"Gondola Ghost"}]},
      {"space_type":"stockroom","zones":[{"code":"BR-01","name":"Backroom Fixture 1"},{"code":"BR-02","name":"Backroom Fixture 2"}]}]}""",
)

private val IDENTITY = ConnectMappers.snake.readValue(
    """{"sites":[{"site_id":"s1","slug":"dec-x","spaces":[
      {"space_id":"sp1","space_type":"sales_floor","zones":[
        {"zone_id":"Z-F1","code":"GF-R1-U1","name":"Gondola R1 Front U1"},{"zone_id":"Z-F2","code":"GF-R1-U2","name":"Gondola R1 Front U2"}]},
      {"space_id":"sp2","space_type":"stockroom","zones":[
        {"zone_id":"Z-B1","code":"BR-01","name":"Backroom Fixture 1"},{"zone_id":"Z-B2","code":null,"name":"Backroom Fixture 2"}]}]}]}""",
    SpatialIdentityResponse::class.java,
)

private fun row(epc: String, loc: String, plat: String, cls: String = "e", site: String = "dec-x", ts: String = "in_stock", ps: String = "in_stock") =
    Recon.Row(epc, site, ts, loc, ps, plat, cls)

private fun epc(n: Int) = "E%04d".format(n)

fun main() {
    Files.createDirectories(OUT)
    val clientsBefore = LiveClients.constructed
    val resolver = Recon.ZoneResolver.of(LAYOUT, IDENTITY)

    println("[select] kinds pick the right rows; the diff file is parsed")
    val mixed = listOf(
        row("A1", "Gondola R1 Front U1", "Gondola R1 Front U2"),
        row("A2", "BACKROOM:Backroom Fixture 1", "Gondola R1 Front U2"),
        row("A3", "BACKROOM:Backroom Fixture 1", Recon.NONE_LOCATION),
        row("A4", "Gondola R1 Front U1", Recon.NONE_LOCATION),
        row("A5", "BACKROOM:(none)", "Gondola R1 Front U2"),
        row("A6", "Gondola R1 Front U1", "Gondola R1 Front U1"),
        row("S1", "", "", cls = "c", ts = "sold", ps = "in_stock"),
        row("S2", "", "", cls = "c", ts = "sold", ps = "sold"),
    )
    check(
        Recon.select(Recon.Kind.MOVES, mixed).map {
            it.epc
        } == listOf("A1", "A2"),
        { "moves picks floor/rack to a differing non-(none) platform spot" },
    )
    check(Recon.select(Recon.Kind.RACKS, mixed).map { it.epc } == listOf("A3"), { "racks picks BACKROOM:<rack> -> BACKROOM:(none) only" })
    check(Recon.select(Recon.Kind.SALES, mixed).map { it.epc } == listOf("S1"), { "sales picks class c sold/in_stock" })
    check(Recon.select(Recon.Kind.MOVES, mixed, setOf("dec-other")).isEmpty(), { "site filter applies" })
    val csv = OUT.resolve("diff.csv")
    Files.writeString(
        csv,
        "epc,twin_site,twin_state,twin_location,twin_source,platform_site,platform_state,platform_location," +
            "platform_made_at,platform_state_changed_at,class\n" +
            "A1,dec-x,in_stock,Gondola R1 Front U1,seed,dec-x,in_stock,BACKROOM:(none),2026-01-01,2026-01-01,e\n",
    )
    val parsed = Recon.readDiff(csv)
    check(
        parsed.size == 1 && parsed[0].twinLocation == "Gondola R1 Front U1" && parsed[0].platformLocation == Recon.NONE_LOCATION &&
            parsed[0].cls == "e",
        { "diff csv parsed: $parsed" },
    )
    check(Recon.siteOrder(mapOf("b" to 5, "a" to 5, "c" to 1)) == listOf("c", "a", "b"), { "smallest store first, ties by slug" })

    println("[batching] at most 25 per move, one destination per batch, by to_zone_id only")
    val rows =
        (1..60).map { row(epc(it), "Gondola R1 Front U1", "x") } + (100..110).map { row(epc(it), "BACKROOM:Backroom Fixture 2", Recon.NONE_LOCATION) }
    val plan = Recon.planMoves(Recon.Kind.MOVES, "dec-x", rows, resolver)
    check(plan.unresolved.isEmpty(), { "everything resolves: ${plan.unresolved}" })
    check(plan.planned.all { it.item.epcs.size <= 25 }, { "batch over 25: ${plan.planned.map { it.item.epcs.size }}" })
    check(plan.planned.map { it.item.epcs.size } == listOf(11, 25, 25, 10), { "sizes ${plan.planned.map { it.item.epcs.size }}" })
    check(plan.planned.sumOf { it.item.epcs.size } == rows.size, { "every unit is in exactly one batch" })
    check(plan.planned.flatMap { it.item.epcs }.toSet().size == rows.size, { "no unit twice" })
    plan.planned.forEach { p ->
        val z = p.item.payload.path("to_zone_id").asText()
        check(z == (if (p.destName.startsWith(Recon.BACKROOM)) "Z-B2" else "Z-F1"), { "destination zone ${p.destName} -> $z" })
        check(!p.item.payload.has("to_fixture_code") || p.item.payload.path("to_fixture_code").isNull, { "never to_fixture_code: ${p.item.payload}" })
        check(p.item.payload.path("items").map { it.path("epc").asText() } == p.item.epcs, { "items carry the batch's EPCs" })
        check(p.item.kind == "move" && p.item.route == Route.WEBHOOK_MOVEMENT, { "a move rides the inventory_movement webhook" })
    }
    check(plan.planned.first { it.destName.startsWith(Recon.BACKROOM) }.destCode == "BR-02", { "rack code kept for placements" })

    println("[ids] deterministic: same input, same ids; a different destination or unit set, a different id")
    val again = Recon.planMoves(Recon.Kind.MOVES, "dec-x", rows.shuffled(java.util.Random(7)), resolver)
    check(again.planned.map { it.item.extId } == plan.planned.map { it.item.extId }, { "ids are stable across input order" })
    check(plan.planned.map { it.item.extId }.toSet().size == plan.planned.size, { "ids are unique" })
    check(Recon.moveId(Recon.Kind.MOVES, "dec-x", "Z-F1", listOf("A")) != Recon.moveId(Recon.Kind.MOVES, "dec-x", "Z-F2", listOf("A")), {
        "id depends on the destination"
    })
    check(Recon.moveId(Recon.Kind.MOVES, "dec-x", "Z-F1", listOf("A")) != Recon.moveId(Recon.Kind.MOVES, "dec-x", "Z-F1", listOf("B")), {
        "id depends on the units"
    })
    check(Recon.moveId(Recon.Kind.RACKS, "dec-x", "Z-F1", listOf("A")) != Recon.moveId(Recon.Kind.MOVES, "dec-x", "Z-F1", listOf("A")), {
        "racks and moves never share an id"
    })

    println("[zones] resolution refuses what it cannot resolve")
    check(resolver.resolve("Gondola R1 Front U2")?.zoneId == "Z-F2", { "floor fixture by code" })
    check(resolver.resolve("BACKROOM:Backroom Fixture 2")?.zoneId == "Z-B2", { "rack with no code on the platform resolves by name" })
    check(resolver.resolve("Gondola Nowhere") == null, { "a name not in the layout is refused" })
    check(resolver.resolve("Gondola Ghost") == null, { "a layout fixture with no platform zone is refused" })
    check(resolver.resolve("BACKROOM:Backroom Fixture 9") == null, { "an unknown rack is refused" })
    val bad = Recon.planMoves(
        Recon.Kind.MOVES,
        "dec-x",
        listOf(row("U1", "Gondola Ghost", "x"), row("U2", "Gondola Ghost", "x"), row("O1", "Gondola R1 Front U1", "x")),
        resolver,
    )
    check(bad.unresolved == mapOf("Gondola Ghost" to 2) && bad.planned.single().item.epcs == listOf("O1"), {
        "unresolved listed with counts, never planned: ${bad.unresolved}"
    })

    println("[sales] a resend keeps occurred_at and carries only the unlanded units, under a new id")
    val rc = ReconSaleIndex.Receipt("run1", "twin-day-1-dec-x-0042", "2026-11-09T12:44:05.997Z", wholeAcked = false, partsAcked = true)
    val rc2 = ReconSaleIndex.Receipt("run1", "twin-day-1-dec-x-0043", "2026-11-09T13:00:00.000Z", wholeAcked = false, partsAcked = false)
    val idx = ReconSaleIndex.of(mapOf("U1" to listOf(rc), "U2" to listOf(rc), "U3" to listOf(rc2)), alarmEpcs = setOf("AL1"))
    val srows = listOf("U1", "U3", "AL1", "NR1").map { row(it, "", "", cls = "c", ts = "sold") }
    val sp = Recon.planSales(srows, idx)
    check(sp.planned.size == 2, { "two sourced receipts: ${sp.planned.size}" })
    val ev = sp.planned.first { it.item.epcs == listOf("U1") }
    check(ev.item.extId == Recon.resendId(rc.saleId, listOf("U1")) && ev.item.extId.startsWith("twin-day-1-dec-x-0042-r1-"), {
        "new derived id: ${ev.item.extId}"
    })
    check(Recon.resendId(rc.saleId, listOf("U1")) != Recon.resendId(rc.saleId, listOf("U1", "U2")), { "id depends on the EPC set" })
    check(ev.item.extId != rc.saleId, { "never the original id" })
    check(ev.item.payload.path("occurred_at").asText() == "2026-11-09T12:44:05.997Z", { "original occurred_at kept: ${ev.item.payload}" })
    check(ev.item.payload.path("epc").asText() == "U1" && !ev.item.payload.has("epc_list"), {
        "only the unlanded unit (U2 landed): ${ev.item.payload}"
    })
    check(ev.item.route == Route.WEBHOOK_SALE, { "a sale rides the sale_event webhook" })
    check(sp.unsourced.map { it.epc }.sorted() == listOf("AL1", "NR1"), { "no source: listed, not planned: ${sp.unsourced.map { it.epc }}" })
    check(
        sp.causes[Recon.Cause.STREAM_PART_ONLY] == 1 && sp.causes[Recon.Cause.STREAM_NOT_IN_LEDGER] == 1 &&
            sp.causes[Recon.Cause.ALARM_UNIT] == 1 && sp.causes[Recon.Cause.NO_RECORD] == 1,
        { "cause breakdown: ${sp.causes}" },
    )
    val two = Recon.planSales(listOf("U1", "U2").map { row(it, "", "", cls = "c", ts = "sold") }, idx)
    check(
        two.planned.single().item.payload.path("epc_list").map {
            it.asText()
        } == listOf("U1", "U2"),
        { "two units of one receipt go back as one epc_list event" },
    )
    val timed = Recon.planSales(listOf(row("AL1", "", "", cls = "c", ts = "sold")), idx, mapOf("AL1" to "2026-10-15T17:03:08.754Z"))
    check(timed.planned.single().item.payload.path("occurred_at").asText() == "2026-10-15T17:03:08.754Z" && timed.unsourced.isEmpty(), {
        "an operator time sources an unsourced unit"
    })

    println("[plan file] frozen plan round-trips")
    val pf = OUT.resolve("plan.jsonl")
    Recon.writePlan(pf, plan.planned + sp.planned)
    val back = Recon.readPlan(pf)
    check(back.map { it.item.extId } == (plan.planned + sp.planned).map { it.item.extId }, { "ids round-trip" })
    check(back.map { it.item.epcs } == (plan.planned + sp.planned).map { it.item.epcs }, { "epcs round-trip" })
    check(back.first().destCode == plan.planned.first().destCode && back.last().occurredAt == sp.planned.last().occurredAt, {
        "dest code and occurred_at round-trip"
    })

    println("[plan hash] a frozen plan carries a hash of diff, kind and sites")
    val h1 = Recon.planHash(csv, Recon.Kind.MOVES, setOf("dec-x"))
    check(
        h1 == Recon.planHash(csv, Recon.Kind.MOVES, setOf("dec-x")) && h1 != Recon.planHash(csv, Recon.Kind.RACKS, setOf("dec-x")) &&
            h1 != Recon.planHash(csv, Recon.Kind.MOVES, setOf("dec-y")),
        { "hash depends on kind and sites" },
    )
    Recon.writePlan(pf, plan.planned, h1)
    check(Recon.readPlanHash(pf) == h1 && Recon.readPlan(pf).size == plan.planned.size, { "header read back, plan lines unaffected" })

    println("[dry run] sends nothing; live needs both keys and the credentials")
    check(LiveClients.constructed == clientsBefore, { "planning built a live client" })
    check(runCatching { LiveSender.createForRecon(false) }.isFailure, { "createForRecon refuses a non-live build" })
    check(ReconSendMain.liveRefusals(false, Recon.Kind.MOVES, emptySet(), null) { null }.isEmpty(), { "a dry run has no live refusals" })
    val full = mapOf(
        "M8TRX_TWIN_WEBHOOK_KEY" to "k",
        "M8TRX_TENANT_ID" to "t",
        "M8TRX_CONNECT_INTEGRATION_SLUG" to "s",
        "M8TRX_TWIN_SERVICE_BEARER" to "b",
    )
    check(ReconSendMain.liveRefusals(true, Recon.Kind.MOVES, setOf("dec-x"), "dec-x", full::get).isEmpty(), { "all keys: allowed" })
    check(ReconSendMain.liveRefusals(true, Recon.Kind.MOVES, setOf("dec-x"), null, full::get).size == 1, { "no confirm: refused" })
    check(ReconSendMain.liveRefusals(true, Recon.Kind.MOVES, setOf("dec-x"), "dec-y", full::get).size == 1, { "wrong confirm: refused" })
    check(ReconSendMain.liveRefusals(true, Recon.Kind.MOVES, emptySet(), "", full::get).isNotEmpty(), { "no sites: refused" })
    check(ReconSendMain.liveRefusals(true, Recon.Kind.MOVES, setOf("dec-x"), "dec-x", emptyMap<String, String>()::get).size == 4, {
        "no credentials: refused"
    })
    check(ReconSendMain.liveRefusals(true, Recon.Kind.SALES, setOf("dec-x"), "dec-x", (full - "M8TRX_TWIN_SERVICE_BEARER")::get).isEmpty(), {
        "sales needs no identity bearer"
    })
    val dry = DryRunSender()
    val clock = SimClock(0)
    val ledger = SentLedger(null)
    val pump =
        Pump(
            plan.planned.map {
                it.item
            },
            dry,
            RetryPolicy(),
            ledger,
            OrderingGuard(plan.planned.flatMap { it.item.epcs }),
            mapOf("move" to Recon.SPACING_MS),
        )
    while (pump.pump(clock.nowMs) != Pump.State.IDLE) clock.advance(250)
    check(dry.sent.size == plan.planned.size, { "every batch handed to the (recording) sender once" })
    check(clock.nowMs >= (plan.planned.size - 1) * Recon.SPACING_MS, { "paced at 3.75 s: ${clock.nowMs} ms for ${plan.planned.size} batches" })

    println("[halt] an answer that reports an item not accepted stops the lane; a 2xx ack with nothing refused does not")
    check(WebhookAnswer.refusal("""{"status":"ok"}""") == null && WebhookAnswer.refusal("") == null && WebhookAnswer.refusal("OK") == null, {
        "clean acks pass"
    })
    check(WebhookAnswer.refusal("""{"received":true,"errors":[{"epc":"A","reason":"destination_unresolved"}]}""") != null, { "errors[] refused" })
    check(WebhookAnswer.refusal("""{"status":"quarantined"}""") != null, { "quarantined refused" })
    check(
        listOf(
            """{"success":false}""", """{"ok":false}""", """{"accepted":0}""", """{"skipped":["A"]}""", """{"ignored":[1]}""",
            """{"refused_item_ids":["A"]}""", """{"unknown_epcs":["A"]}""", """{"not_found":["A"]}""", """[{"a":1}]""",
        ).all { WebhookAnswer.refusal(it) != null },
        { "each refusal marker halts" },
    )
    check(
        WebhookAnswer.isPlain("""{"status":"ok"}""") && !WebhookAnswer.isPlain("""{"status":"ok","extra":1}""") && !WebhookAnswer.isPlain("[]"),
        { "plain vs logged in full" },
    )
    check(WebhookAnswer.refusal("""{"errors":[]}""") == null, { "an empty errors[] is fine" })
    val refusing = object : OpsSender {
        var n = 0
        override fun health() = 200 to """{"status":"ok"}"""
        override fun send(item: SendItem): Outcome = if (++n ==
            2
        ) {
            Outcome.ItemErrors(listOf(ItemError(null, "webhook answer: errors")))
        } else {
            Outcome.Accepted
        }
    }
    val clock2 = SimClock(0)
    val led2 = SentLedger(null)
    val p2 =
        Pump(
            plan.planned.map {
                it.item
            },
            refusing,
            RetryPolicy(),
            led2,
            OrderingGuard(plan.planned.flatMap { it.item.epcs }),
            mapOf("move" to Recon.SPACING_MS),
        )
    var st = Pump.State.WAITING
    while (st == Pump.State.WAITING) {
        st = p2.pump(clock2.nowMs)
        clock2.advance(250)
    }
    check(st == Pump.State.HALTED && refusing.n == 2, { "halted on the refused item: $st after ${refusing.n} sends" })
    check(led2.size == 1 && !led2.isSent(plan.planned[1].item.extId), { "the refused item is not in the ledger (stays due)" })
    val holdFile = OUT.resolve("recon-HOLD")
    Files.writeString(holdFile, "")
    val p3 =
        Pump(
            plan.planned.map {
                it.item
            },
            DryRunSender(),
            RetryPolicy(),
            SentLedger(null),
            OrderingGuard(plan.planned.flatMap { it.item.epcs }),
            hold = { Files.exists(holdFile) },
        )
    check(p3.pump(0) == Pump.State.HOLD, { "the stop file holds the lane" })
    Files.delete(holdFile)

    println("[resume] the sent-ledger skips accepted ids")
    val lf = OUT.resolve("sent.log")
    Files.deleteIfExists(lf)
    val l1 = SentLedger(lf)
    l1.markSent(plan.planned[0].item)
    val p4 = Pump(plan.planned.map { it.item }, DryRunSender(), RetryPolicy(), SentLedger(lf), OrderingGuard(plan.planned.flatMap { it.item.epcs }))
    check(p4.skippedByLedger == 1 && p4.queue.size == plan.planned.size - 1, { "resume skips ${p4.skippedByLedger}" })

    println("[placements] appended only for a unit whose recorded fixture differs")
    val pl = OUT.resolve("placements.csv")
    Files.writeString(pl, "epc,fixture,date\nE0001,GF-R1-U1,2026-10-05\nE0002,GF-R1-U2,2026-10-05\n")
    val first = plan.planned.first { it.destCode == "GF-R1-U1" }.item
    val ps = ReconSendMain.PlacementSender(DryRunSender(), plan.planned.associate { it.item.extId to it.destCode }, pl, LocalDate.parse("2026-11-22"))
    ps.send(first)
    check(ps.appended == 1 && Files.readAllLines(pl).last() == "E0002,GF-R1-U1,2026-11-22", {
        "E0002 differs and is appended; E0001 and unknown units are not: ${Files.readAllLines(pl)}"
    })

    if (failures > 0) {
        println("reconcileSelfTest: $failures FAILED")
        exitProcess(1)
    }
    println("reconcileSelfTest: all checks passed")
}
