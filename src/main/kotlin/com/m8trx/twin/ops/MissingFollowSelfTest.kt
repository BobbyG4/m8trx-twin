package com.m8trx.twin.ops

import com.m8trx.twin.connect.model.bearer.ItemDetail
import java.nio.file.Files

private typealias Reader = (List<String>) -> List<ItemDetail>?

/** A fake `items/details`: the platform's state by EPC (an EPC not in the map has no row), every chunk recorded. */
private class FakePlatform(private val platform: Map<String, String>) : Reader {
    val calls = ArrayList<List<String>>()

    override fun invoke(chunk: List<String>): List<ItemDetail> {
        calls += chunk
        return chunk.map { e -> ItemDetail(e, platform[e]?.let { "id-$e" }, state = platform[e]) }
    }
}

private val FAILING: Reader = { null }

/** Part of `demoStepsSelfTest`: the missing list follows the platform (close filtered, un-miss, stolen kept, atomic rewrite). */
internal fun missingFollowsPlatform() {
    val state = Files.createTempDirectory("missing-follow")
    val missingFile = StoreStock.missingFile(state)
    Files.createDirectories(missingFile.parent)
    // m1 stays missing, i1/i2 were found again, st1 is a stolen unit the platform reads in_stock, so1 sold, ghost unknown to the platform
    Files.writeString(missingFile, "m1\ni1\nst1\ni2\nso1\nghost\n")
    Files.writeString(MissingFollow(FakePlatform(emptyMap()), state).stolenFile(), "st1\n")
    val platform = mapOf(
        "m1" to "missing",
        "i1" to "in_stock",
        "i2" to "in_stock",
        "st1" to "in_stock",
        "so1" to "sold",
        "c1" to "missing",
        "c2" to "in_stock",
    )
    val f = MissingFollow(FakePlatform(platform), state)

    // close-missing is cut to what the platform reports missing
    val split = f.closeMissing(listOf("c1", "c2", "nope"))!!
    check(split.listed == 3 && split.platformMissing == listOf("c1") && split.platformOther == 2) { "split $split" }
    check(MissingFollow(FAILING, state).closeMissing(listOf("c1")) == null) { "a failed read must not look like an answer" }

    // un-miss: in_stock non-stolen leave; stolen / sold / missing / unknown stay; unrelated lines and order kept
    val r = f.reconcile()!!
    check(r.checked == 6 && r.removed == 2 && r.keptStolen == 1 && r.sold == 1 && r.other == 1) { "reconcile $r" }
    check(Files.readAllLines(missingFile) == listOf("m1", "st1", "so1", "ghost")) { "rewrite: ${Files.readAllLines(missingFile)}" }
    check(!Files.exists(missingFile.resolveSibling("missing-epcs.txt.tmp"))) { "temp file left behind" }
    check(MissingFollow(FAILING, state).reconcile() == null) { "a failed read must be reported" }
    check(Files.readAllLines(missingFile).size == 4) { "file changed on a failed read" }

    // chunks of 200: 450 EPCs = 3 requests
    Files.writeString(missingFile, (1..450).joinToString("") { "e$it\n" })
    val big = FakePlatform(emptyMap())
    val rb = MissingFollow(big, state).reconcile()!!
    check(rb.requests == 3 && big.calls.size == 3 && big.calls.all { it.size <= 200 }) { "chunks ${big.calls.map { it.size }}" }
}

/** Part of `demoStepsSelfTest`: close candidates the platform has not marked missing wait in missing-pending.txt. */
internal fun missingPending() {
    val state = Files.createTempDirectory("missing-pending")
    val now = java.time.Instant.parse("2026-11-21T00:00:00Z")
    val missingFile = StoreStock.missingFile(state)
    Files.createDirectories(missingFile.parent)
    Files.writeString(missingFile, "keep\n")
    val old = now.minus(4, java.time.temporal.ChronoUnit.DAYS)
    val recent = now.minus(1, java.time.temporal.ChronoUnit.DAYS)
    val pf = MissingFollow(FakePlatform(emptyMap()), state, { now }).pendingFile()
    Files.writeString(pf, "flip,$recent\nsold1,$recent\nwait,$recent\nstale,$old\nstaleflip,$old\n")
    val platform = mapOf(
        "keep" to "missing",
        "flip" to "missing",
        "sold1" to "sold",
        "wait" to "in_stock",
        "stale" to "in_stock",
        "staleflip" to "missing",
    )
    val f = MissingFollow(FakePlatform(platform), state, { now })

    // a close: c1 missing (booked), c2 in_stock (pending), c3 sold (neither), wait already pending (not duplicated)
    val closeF = MissingFollow(FakePlatform(mapOf("c1" to "missing", "c2" to "in_stock", "c3" to "sold", "wait" to "in_stock")), state, { now })
    val split = closeF.closeMissing(listOf("c1", "c2", "c3", "wait"))!!
    check(split.platformMissing == listOf("c1") && split.pending == listOf("c2", "wait")) { "split $split" }
    closeF.addPending(split.pending)
    check(Files.readAllLines(pf).count { it.startsWith("wait,") } == 1) { "wait duplicated" }
    check(Files.readAllLines(pf).contains("c2,$now")) { "c2 not pending: ${Files.readAllLines(pf)}" }

    val r = f.reconcile()!!
    check(r.promoted == 2 && r.pendingSold == 1 && r.pendingExpired == 1) { "reconcile $r" }
    check(Files.readAllLines(missingFile) == listOf("keep", "flip", "staleflip")) { "missing ${Files.readAllLines(missingFile)}" }
    check(Files.readAllLines(pf) == listOf("wait,$recent", "c2,$now")) { "pending ${Files.readAllLines(pf)}" }

    // idempotent: a second pass changes nothing; a failed read leaves both files alone
    val before = Files.readAllLines(missingFile) to Files.readAllLines(pf)
    val r2 = f.reconcile()!!
    check(r2.promoted == 0 && r2.pendingSold == 0 && r2.pendingExpired == 0) { "second pass $r2" }
    check(before == (Files.readAllLines(missingFile) to Files.readAllLines(pf))) { "second pass changed the files" }
    check(!Files.exists(pf.resolveSibling("missing-pending.txt.tmp"))) { "temp left" }
    check(MissingFollow(FAILING, state, { now }).reconcile() == null) { "failed read not reported" }
    check(before == (Files.readAllLines(missingFile) to Files.readAllLines(pf))) { "failed read changed files" }
}
