package com.m8trx.twin.layer3

import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.layer1.ImpressionOracle
import com.m8trx.twin.layer1.Pt
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Where [LiveDrive] gets a store-day's shopper position streams (Phase 3 design, brief § 2 "People").
 *
 *  - [Generator] — the plane's own generator ([ScenarioRun.streamsFor]). **The default; LiveDrive's output with it is byte-identical
 *    to what it was before this switch existed** (pinned by `storeOpsSelfTest`).
 *  - [StoreDaySidecar] — the positions the store-day simulation wrote beside its wire plan (`<dir>/<slug>/<date>/samples.jsonl`,
 *    one `{"shopper": id, "samples": [[ts_ms, x, y], ...]}` line per shopper, written when `M8TRX_STORE_DAY_SAMPLES=true`), so the
 *    people on the floor are the very shoppers whose sales and stock-outs the store-day truth ledger records.
 *    The sidecar carries no gaze, so the samples have no view direction: the edge would see people walking but could not
 *    derive fixture impressions from them. Open question for the lane before this source is switched on for Denver.
 *
 * Switch: env `M8TRX_LIVE_PEOPLE_SOURCE` = `generator` (default) | `storeday`; sidecar root `M8TRX_LIVE_PEOPLE_SIDECAR_DIR` (default `out/day`).
 */
sealed interface PeopleSource {
    data object Generator : PeopleSource

    data class StoreDaySidecar(val dir: Path) : PeopleSource

    companion object {
        const val ENV_SOURCE = "M8TRX_LIVE_PEOPLE_SOURCE"
        const val ENV_DIR = "M8TRX_LIVE_PEOPLE_SIDECAR_DIR"

        fun parse(source: String?, dir: String?): PeopleSource = when (source?.trim()?.lowercase().orEmpty()) {
            "", "generator" -> Generator
            "storeday", "store-day", "sidecar" -> StoreDaySidecar(Path.of(dir?.takeIf { it.isNotBlank() } ?: "out/day"))
            else -> throw IllegalArgumentException("$ENV_SOURCE must be generator | storeday, got '$source'")
        }

        fun fromEnv(): PeopleSource = parse(System.getenv(ENV_SOURCE), System.getenv(ENV_DIR))

        fun sidecarFile(dir: Path, slug: String, date: LocalDate): Path = dir.resolve(slug).resolve(date.toString()).resolve("samples.jsonl")

        /** The shopper streams of one store-day from its sidecar file; refuses a missing file (never falls back silently). */
        fun readSidecar(file: Path): Map<String, List<ImpressionOracle.Sample>> {
            check(Files.exists(file)) { "no store-day position sidecar at $file (run the store day with M8TRX_STORE_DAY_SAMPLES=true)" }
            val out = LinkedHashMap<String, List<ImpressionOracle.Sample>>()
            Files.newBufferedReader(file).useLines { ls ->
                ls.filter { it.isNotBlank() }.forEach { l ->
                    val n = ConnectMappers.snake.readTree(l)
                    out[n.path("shopper").asText()] =
                        n.path("samples").map { a -> ImpressionOracle.Sample(a[0].asLong(), Pt(a[1].asDouble(), a[2].asDouble()), null) }
                }
            }
            return out
        }
    }
}
