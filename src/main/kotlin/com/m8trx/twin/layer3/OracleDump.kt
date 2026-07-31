package com.m8trx.twin.layer3

import com.m8trx.twin.layer1.FixtureSet
import com.m8trx.twin.layer1.ImpressionOracle
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * `./gradlew oracleDump` — write the oracle's per-impression predictions for a drive slice, so they can be
 * diffed against the rows the platform actually recorded (`POST /visionai/impressions/query`).
 *
 * This is the §B5 oracle-vs-actual instrument. It only became meaningful once two things were true:
 *
 *  1. **Twin can read its own rows** (the Connect read surface), so "actual" no longer means asking a human
 *     to run `psql`.
 *  2. **The transport loss is understood** (jnats client-side discard under backpressure). Before that, an
 *     oracle-vs-rows comparison silently mixed *model* error with *transport* loss and could not attribute a
 *     discrepancy to either. The honest comparison for the RULE is oracle vs **wire count**; the comparison
 *     against ROWS additionally measures transport, and the two must never be conflated.
 *
 * Columns: `fixtureCode`, `offsetMs` (from slice start, on the compressed publish timeline — comparable to a
 * row's `recordedAt` minus run start), `durationMs` (core's `min(lastDwell-firstDwell, lastLook-firstDwell)`),
 * `viewMs`, `dwellMs`, and the shopper id so per-session grain can be checked.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.layer3.OracleDump")

private const val GAP_MS = 1_000L

fun main() {
    val date = LocalDate.parse(System.getenv("M8TRX_DAY_DATE") ?: "2026-07-28")
    val seed = (System.getenv("M8TRX_DAY_SEED") ?: "4242").toLong()
    val scale = (System.getenv("M8TRX_DAY_SCALE") ?: "1.0").toDouble()
    val compress = (System.getenv("M8TRX_DAY_COMPRESS") ?: "18.0").toDouble()
    val fromHour = System.getenv("M8TRX_DAY_FROM_HOUR")?.toIntOrNull()
    val toHour = System.getenv("M8TRX_DAY_TO_HOUR")?.toIntOrNull()
    val out = Path.of(System.getenv("M8TRX_ORACLE_OUT") ?: "/tmp/oracle_dump.csv")

    val chainDir = Path.of(System.getenv("M8TRX_CHAIN_DIR") ?: "reference/data/chain")
    val fixtures = FixtureSet.load(chainDir.resolve("stores/dec-us-denver/layout.json"))
    val runner = ScenarioRun()
    val model = OperatingModel.load(Path.of("reference/data/store-operating-model.json"))
    val openHour = java.time.LocalTime.parse(model.store.operatingHours.open).hour

    val all = runner.streamsFor(date, seed, scale)
    val dayStart = all.values.flatten().minOf { it.tsMs }
    val sliced = if (fromHour == null && toHour == null) {
        all
    } else {
        val lo = dayStart + ((fromHour ?: openHour) - openHour) * 3_600_000L
        val hi = dayStart + ((toHour ?: 24) - openHour) * 3_600_000L
        all.filterValues { it.minOf { s -> s.tsMs } in lo until hi }
    }
    val sliceStart = sliced.values.flatten().minOf { it.tsMs }
    log.info("slice: {} shoppers (date={} seed={} scale={} compress={}x window={}..{})", sliced.size, date, seed, scale, compress, fromHour, toHour)

    val oracle = ImpressionOracle()
    var n = 0
    Files.newBufferedWriter(out).use { w ->
        w.write("customer,fixtureCode,offsetMs,durationMs,viewMs,dwellMs\n")
        sliced.forEach { (cust, raw) ->
            val ordered = raw.sortedBy { it.tsMs }
            var t = sliceStart + ((ordered.first().tsMs - sliceStart) / compress).toLong()
            val wire = ArrayList<Long>(ordered.size)
            wire.add(t)
            for (i in 1 until ordered.size) {
                val d = ordered[i].tsMs - ordered[i - 1].tsMs
                t += if (d <= GAP_MS) d else (d / compress).toLong().coerceAtLeast(GAP_MS + 1)
                wire.add(t)
            }
            val idx = HashMap<Long, Int>(ordered.size)
            ordered.forEachIndexed { i, s -> idx.putIfAbsent(s.tsMs, i) }
            oracle.run(fixtures, ordered).forEach { p ->
                val i = idx[p.firstLookMs] ?: return@forEach
                w.write(
                    "$cust,${p.fixtureCode},${wire[i] - sliceStart},${p.durationMs}," +
                        "${p.lastLookMs - p.firstLookMs},${p.lastDwellMs - p.firstDwellMs}\n",
                )
                n++
            }
        }
    }
    log.info("wrote {} predicted impressions → {}", n, out)
}
