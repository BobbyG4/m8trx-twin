package com.m8trx.twin.history

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * `./gradlew historyGenerate` — generate the chain's history as the eleven-file contract mig 259 loads.
 * Offline: no network, no credentials, nothing published.
 *
 * Env: `M8TRX_HISTORY_SEED` (20261016) · `M8TRX_HISTORY_FROM` (2026-01-01) · `M8TRX_HISTORY_TO` (2026-10-04,
 * inclusive local date; US stores end a day earlier, see [HistoryGenerator.Params.cutoff]) ·
 * `M8TRX_HISTORY_OUT` (out/history-2026-10) · `M8TRX_HISTORY_STORES` (comma list, default all ten) ·
 * `M8TRX_HISTORY_THREADS` (cores) · `M8TRX_HISTORY_GENERATED_AT` (default: the generator commit's time).
 *
 * TODO (brief § 4, a later round, after the core fixes): the recent window Oct 4 → Oct 16 rides the live
 * pipeline — `M8TRX_DAY_EPOCH_MODE`, `M8TRX_STREAM_DATE`, `M8TRX_ALARM_DATE`. Not implemented here.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.history.HistoryGenerate")

fun main() {
    val params = HistoryEnv.params()
    val gen = HistoryGenerator(params)
    val out = gen.generate()
    log.info("")
    out.files.forEach { (f, n) -> log.info("  {} {}", f.padEnd(32), n) }
    log.info("\n{}", HistoryReport.totalsTable(gen, params.out))
    log.info("history written to {} in {}", params.out.toAbsolutePath(), fmtMs(out.wallMs))
}

internal fun fmtMs(ms: Long): String = "%dm%02ds".format(Locale.ROOT, ms / 60_000, (ms / 1000) % 60)

object HistoryEnv {
    private fun env(k: String, d: String) = System.getenv(k)?.takeIf { it.isNotBlank() } ?: d

    fun params(
        seed: Long = env("M8TRX_HISTORY_SEED", "20261016").toLong(),
        from: LocalDate = LocalDate.parse(env("M8TRX_HISTORY_FROM", "2026-01-01")),
        to: LocalDate = LocalDate.parse(env("M8TRX_HISTORY_TO", "2026-10-04")),
        out: Path = Path.of(env("M8TRX_HISTORY_OUT", "out/history-2026-10")),
        stores: List<String>? = System.getenv("M8TRX_HISTORY_STORES")?.takeIf { it.isNotBlank() }?.split(",")?.map { it.trim() },
    ): HistoryGenerator.Params {
        val sha = git("rev-parse", "HEAD") ?: "unknown"
        val dirty = git("status", "--porcelain", "--untracked-files=no")?.isNotBlank() == true
        return HistoryGenerator.Params(
            seed = seed,
            from = from,
            to = to,
            out = out,
            stores = stores,
            threads = env("M8TRX_HISTORY_THREADS", Runtime.getRuntime().availableProcessors().toString()).toInt(),
            gitSha = if (dirty) "$sha-dirty" else sha,
            // the commit's own time, not the wall clock — so the same commit + seed reproduces MANIFEST.csv too
            generatedAt = env("M8TRX_HISTORY_GENERATED_AT", git("log", "-1", "--format=%cd", "--date=iso-strict") ?: "unknown"),
        )
    }

    /** Local git read (no network). Null when git is unavailable. */
    private fun git(vararg args: String): String? = runCatching {
        val p = ProcessBuilder(listOf("git") + args).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) text else null
    }.getOrNull()
}
