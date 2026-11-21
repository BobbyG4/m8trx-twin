package com.m8trx.twin.ops

import com.fasterxml.jackson.databind.ObjectMapper
import com.m8trx.twin.history.ChainKeys
import java.nio.file.Path
import java.time.Instant

/** Part of `demoStepsSelfTest`: the targeted count's parsing, name-to-code mapping, read set (stolen unread) and pull time. */
internal fun stockTargeted(chain: Path, keys: ChainKeys) {
    val t = StockTargeted.parse("dec-us-denver:GF-R2-U2, GB-R3-U5,GF-R2-U2;dec-fr-lyon:PE-02")
    check(t == mapOf("dec-us-denver" to listOf("GF-R2-U2", "GB-R3-U5"), "dec-fr-lyon" to listOf("PE-02"))) { "parse: $t" }
    check(StockTargeted.parse(StockTargeted.format(t)) == t) { "format does not round-trip" }
    for (bad in listOf("", "dec-us-denver", "dec-us-denver:", "a:X;a:Y")) {
        check(runCatching { StockTargeted.parse(bad) }.isFailure) { "'$bad' parsed" }
    }
    // name -> code from the committed layouts, every store
    for (s in keys.stores.filter { it.fixtures.isNotEmpty() }) {
        val names = StockTargeted.loadNamesToCodes(chain, s.slug)
        check(names.isNotEmpty()) { "${s.slug}: no names in layout.json" }
        s.fixtures.forEach { f -> check(names[f.name] == null || names.getValue(f.name) == f.code) { "${s.slug}: ${f.name} maps to the wrong code" } }
    }
    val denver = keys.store("dec-us-denver")
    val names = StockTargeted.loadNamesToCodes(chain, "dec-us-denver")
    val fx = denver.fixtures.first { names[it.name] == it.code }

    // targets: stolen at a fixture the platform has in stock; a missing one, a back-room one and an unknown fixture are skipped
    fun u(e: String, state: String = "in_stock", space: String = "Sales Floor", fixture: String = fx.name) =
        StockTargeted.UnitRow(e, "Decathlon Denver", state, space, fixture)
    val units = listOf(u("S1"), u("S2"), u("S3", state = "missing"), u("S4", space = "Back Room"), u("S5", fixture = "No Such Fixture"), u("N1"))
    val tg = StockTargeted.targets(setOf("s1", "S2", "S3", "S4", "S5", "S9"), units) { StockTargeted.loadNamesToCodes(chain, it) }
    check(tg.byStore == mapOf("dec-us-denver" to listOf(fx.code)) && tg.units["dec-us-denver"] == 2) { "targets: $tg" }
    check(tg.skipped.size == 4) { "skipped: ${tg.skipped}" }
    check(StockTargeted.slugOf("Decathlon Villeneuve-d'Ascq") == "dec-fr-lille" && StockTargeted.slugOf("New York") == "dec-us-nyc") { "site slugs" }

    // plan: the read set is the platform's units at the fixture minus the stolen; pulls clear the plateau; the count ends before now
    val zone = denver.zones.associate { it.code to it.zoneId }
    val here = (1..40).map { "E%03d".format(it) }
    val stolen = setOf("E001", "e002", "E003")
    val stock = StoreStock(denver.slug, mapOf(fx.code to here, "BR-x" to listOf("B1")))
    val now = Instant.parse("2026-11-21T03:00:00Z")
    val (plan, empty) = StockTargeted.plan(denver, now, listOf(fx.code), stock, stolen)
    check(plan != null && empty.isEmpty()) { "no plan" }
    val read = plan.pulls.flatMap { it.epcs }.toSet()
    check(read.size == 37 && read.none { it.uppercase() in setOf("E001", "E002", "E003") }) { "stolen units read: ${read.size}" }
    check(plan.scopeZoneIds == listOf(zone.getValue(fx.code)) && plan.missing.isEmpty() && plan.over.isEmpty()) { "scope/missing/over" }
    check(StockTargeted.pullSeconds(plan).values.all { it >= StockTargeted.WORKED_SECONDS_FLOOR * 15 }) { "pull under the worked floor" }
    check(plan.endedAt.isBefore(now)) { "count ends in the future" }
    check(plan.countId != StockTargeted.plan(denver, now.plusSeconds(1), listOf(fx.code), stock, stolen).first!!.countId) { "count id repeats" }
    val bare = StoreStock(denver.slug, mapOf("BR-x" to listOf("B1")))
    check(StockTargeted.plan(denver, now, listOf(fx.code), bare, stolen).first == null) { "empty fixture planned" }

    // round ordering: round 2's pulls must all start after round 1's close; the earliest allowed now is printed
    val closed1 = Instant.parse("2026-11-21T03:05:00Z")
    val key = StockTargeted.targetKey(denver.slug, listOf(fx.code, "A"))
    check(key == StockTargeted.targetKey(denver.slug, listOf("A", fx.code))) { "target key depends on order" }
    val span = java.time.Duration.between(plan.startedAt, now) // the plan's total pull span + margin
    val early = StockTargeted.gate(StockTargeted.Prior(closed1, emptyList()), plan.startedAt, now, false)
    check(early.refusal != null && early.refusal.contains("round ordering")) { "round 2 not refused before round 1 closed" }
    check(early.earliestNow == closed1.plusSeconds(span.seconds + 1)) { "earliest allowed start: ${early.earliestNow}" }
    val onTime = early.earliestNow!!
    val p2 = StockTargeted.plan(denver, onTime, listOf(fx.code), stock, stolen).first!!
    check(
        p2.startedAt.isAfter(closed1) && StockTargeted.gate(StockTargeted.Prior(closed1, emptyList()), p2.startedAt, onTime, false).refusal == null,
    ) {
        "round 2 refused at the earliest allowed start"
    }
    check(StockTargeted.gate(null, plan.startedAt, now, false).refusal == null) { "round 1 gated" }

    // guard: a non-stolen notYetHeard unit blocks round 2 until accepted; prior read back from targeted-*.json (latest closed round wins)
    val dir = java.nio.file.Files.createTempDirectory("targeted-prior")
    java.nio.file.Files.writeString(
        dir.resolve("targeted-20261121T030500.json"),
        """[{"targetKey":"$key","endState":"closed","closedAt":"2026-11-21T03:05:00Z","notYetHeardUnits":""" +
            """[{"epc":"A1","stolen":true},{"epc":"N9","stolen":false}]},""" +
            """{"targetKey":"$key","endState":"closed_empty","closedAt":"2026-11-21T02:00:00Z","notYetHeardUnits":[]},""" +
            """{"targetKey":"other","endState":"closed","closedAt":"2026-11-21T04:00:00Z"},""" +
            """{"targetKey":"$key","endState":"open_failed","closedAt":null}]""",
    )
    val prior = StockTargeted.loadPrior(dir, key)
    check(prior != null && prior.closedAt == closed1 && prior.nonStolen == listOf("N9")) { "prior: $prior" }
    check(StockTargeted.loadPrior(dir, "nope") == null) { "prior for another target" }
    val later = closed1.plusSeconds(3600)
    val blocked = StockTargeted.gate(prior, later.minusSeconds(span.seconds), later, false)
    check(blocked.refusal != null && blocked.refusal.contains("N9") && blocked.refusal.contains("ACCEPT_NONSTOLEN")) { "non-stolen not blocked" }
    check(StockTargeted.gate(prior, later.minusSeconds(span.seconds), later, true).refusal == null) { "accept flag ignored" }
    check(StockTargeted.gate(StockTargeted.Prior(closed1, emptyList()), later.minusSeconds(span.seconds), later, false).refusal == null) {
        "stolen-only blocked"
    }

    // result reading: totals + notYetHeard entries
    val body = """{"reconciled":true,"totals":{"under":2,"misplaced":1},""" +
        """"summary":{"notYetHeard":[{"itemId":"i1","zoneId":"z1","reason":"first_silence"},{"itemId":"i2","zoneId":"z1","reason":"first_silence"}]}}"""
    val r = StockTargeted.readResult(ObjectMapper().readTree(body))
    check(r.missing == 2 && r.misplaced == 1 && r.notYetHeard == 2 && r.entries.map { it.itemId } == listOf("i1", "i2")) { "result: $r" }
}
