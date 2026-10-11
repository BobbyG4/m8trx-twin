package com.m8trx.twin.history

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.system.exitProcess

/**
 * `./gradlew historySelfTest` — the brief's § 3 self-tests, offline (no network, no credentials).
 *
 *  [1] A four-week window, all ten stores (Sep 7 → Oct 4: a promo week, Chuseok, the US cut-off day), run
 *      through [HistoryValidator]: MANIFEST row counts + sha256s, no row outside operating hours, every key
 *      resolves against the keys export, `conversion_rate ≤ 1`, department sums = store sums, hourly = day,
 *      vocabularies, task/alarm status rules, totals within ±5 % of the operating model per store.
 *  [2] Determinism — the same seed twice → byte-identical files (all eleven, MANIFEST included); another seed
 *      → different data.
 *  [3] The full-year directory (`M8TRX_HISTORY_OUT`, default `out/history-2026-10`) if it has been generated:
 *      the same validator, plus the story beats the dashboards depend on. Skipped (not failed) when absent,
 *      unless `M8TRX_HISTORY_SELFTEST_FULL=required`.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.history.HistorySelfTest")

private var passed = 0
private var failed = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        passed++
        log.info("  ✓ {}", name)
    } else {
        failed++
        log.error("  ✗ {} {}", name, detail)
    }
}

private fun report(checks: List<HistoryValidator.Check>) = checks.forEach { check(it.name, it.ok, it.detail) }

fun main() {
    val scratch = Path.of(System.getenv("M8TRX_HISTORY_SELFTEST_DIR") ?: "out/history-selftest")
    val seed = 20261016L

    // ── [1] four weeks, all stores ───────────────────────────────────────────
    log.info("[1] four weeks, all ten stores — 2026-09-07 .. 2026-10-04")
    val windowA = HistoryEnv.params(
        seed = seed,
        from = LocalDate.of(2026, 9, 7),
        to = LocalDate.of(2026, 10, 4),
        out = scratch.resolve("a"),
        stores = null,
    )
    val genA = HistoryGenerator(windowA)
    val outA = genA.generate()
    log.info("  generated {} store-days in {}", outA.days.size, fmtMs(outA.wallMs))
    log.info("\n{}", HistoryReport.totalsTable(genA, windowA.out))
    check("scheduler ran every store-day without errors", outA.days.all { it.schedulerErrors == 0 })
    check(
        "the keys export agrees with every store's layout.json (codes, names, types)",
        genA.stores.all { s -> layoutAgrees(s) },
        "a store's layout drifted from the export — regenerate the export or the layout",
    )
    check(
        "US stores end a day early (local Oct 4 closes after the 2026-10-05T00:00Z cut-off)",
        genA.stores.all { s ->
            val last = genA.lastDay(s)
            if (s.slug.startsWith("dec-us-")) last == LocalDate.of(2026, 10, 3) else last == LocalDate.of(2026, 10, 4)
        },
    )
    check(
        "Chuseok (Sep 25) is closed in Seoul and Busan, open elsewhere",
        genA.stores.all { s -> genA.days(s).contains(LocalDate.of(2026, 9, 25)) == !s.slug.startsWith("dec-kr-") },
    )
    report(HistoryValidator(genA, windowA.out).all())

    // ── [2] determinism ──────────────────────────────────────────────────────
    log.info("[2] determinism — same seed → byte-identical files")
    val trio = listOf("dec-fr-marseille", "dec-kr-busan", "dec-us-denver")
    fun small(s: Long, d: String) =
        HistoryEnv.params(seed = s, from = LocalDate.of(2026, 6, 1), to = LocalDate.of(2026, 6, 7), out = scratch.resolve(d), stores = trio)
    HistoryGenerator(small(seed, "b1")).generate()
    HistoryGenerator(small(seed, "b2")).generate()
    HistoryGenerator(small(seed + 1, "b3")).generate()
    val names = listOf("MANIFEST.csv") + manifestFiles(scratch.resolve("b1"))
    names.forEach { f ->
        check("byte-identical on re-run — $f", HistoryCsv.sha256(scratch.resolve("b1/$f")) == HistoryCsv.sha256(scratch.resolve("b2/$f")))
    }
    check(
        "a different seed yields a different history",
        HistoryCsv.sha256(scratch.resolve("b1/commerce_store_hourly.csv")) != HistoryCsv.sha256(scratch.resolve("b3/commerce_store_hourly.csv")),
    )

    // ── [3] the generated year ───────────────────────────────────────────────
    val full = HistoryEnv.params()
    if (!HistoryValidator.exists(full.out)) {
        val required = System.getenv("M8TRX_HISTORY_SELFTEST_FULL") == "required"
        if (required) check("full-year output exists at ${full.out}", false, "run ./gradlew historyGenerate first")
        log.warn("[3] SKIPPED — no generated year at {} (run ./gradlew historyGenerate)", full.out)
    } else {
        log.info("[3] the generated year at {}", full.out)
        val manifestSeed = HistoryCsv.read(full.out.resolve("MANIFEST.csv")).second.first()[3].toLong()
        val genFull = HistoryGenerator(full.copy(seed = manifestSeed))
        report(HistoryValidator(genFull, full.out).all())
        report(StoryChecks(genFull, full.out).all())
    }

    log.info("")
    log.info("historySelfTest — {} passed, {} failed", passed, failed)
    if (failed > 0) exitProcess(1)
}

private fun manifestFiles(dir: Path): List<String> = HistoryCsv.read(dir.resolve("MANIFEST.csv")).second.map { it[0] }

/** The keys export and the store's own layout.json must name the same sales-floor zones. */
private fun layoutAgrees(store: ChainKeys.Store): Boolean {
    val root = com.m8trx.twin.connect.model.ConnectMappers.camel.readTree(
        Files.readAllBytes(Path.of("reference/data/chain/stores/${store.slug}/layout.json")),
    )
    val sf = root.path("spaces").first { it.path("space_type").asText() == "sales_floor" }
    val layout = sf.path("zones").map { Triple(it.path("code").asText(), it.path("name").asText(), it.path("zone_type").asText()) }.toSet()
    return layout == store.zones.map { Triple(it.code, it.name, it.zoneType) }.toSet()
}
