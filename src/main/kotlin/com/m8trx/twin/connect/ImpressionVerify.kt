package com.m8trx.twin.connect

import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.bearer.ImpressionQueryRequest
import com.m8trx.twin.connect.model.bearer.ImpressionQueryResponse
import com.m8trx.twin.connect.model.bearer.ImpressionRow
import com.m8trx.twin.connect.model.bearer.ReadCaps
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * `./gradlew impressionVerify` — twin reads back **its own** persisted impressions over
 * `POST /visionai/impressions/query` (§6.5), and optionally diffs them against the oracle's
 * predictions.
 *
 * ## Why this is in the repo rather than a curl
 *
 * [com.m8trx.twin.layer1.ImpressionWatcher] exists because S15's `observed 3,664` had been measured
 * out-of-band and left no artifact, so the whole persistence question turned on a number twin could
 * not substantiate from its own records. The read side then did exactly the same thing: the
 * 2026-07-30 drive self-served its row counts over this endpoint and wrote the CSVs under
 * `status/active/data/`, but **no code in this repo could reproduce them**. Same failure class, one
 * plane over. So: the read now lives here, and a twin claim about persisted volume has code behind it.
 *
 * ## Three planes, never conflated
 *
 * ```
 *   oracle prediction   twin's model of core's rule        (oracleDump)
 *   wire                what core actually published       (impressionWatch)
 *   rows                what survived to storage           (THIS)
 * ```
 *
 * `oracle vs wire` measures the MODEL, transport-free. `wire vs rows` measures TRANSPORT. This task
 * compares oracle vs rows when given a dump, which is **model + transport combined** — useful as a
 * top-line, useless for attribution, and labelled as such in the output. The 2026-07-30 decisive
 * drive is the worked example: 1512 predicted / 1512 wire / 1370 rows — model exact, transport −9.4%.
 * Reporting that as "the oracle was 9.4% off" would have been false.
 *
 * ## Paging: there isn't any
 *
 * `truncated: true` means **narrow the query**, not "fetch page 2" — there is no offset and no cursor.
 * The window IS the cursor, so this walks the requested range in slices and **recursively halves any
 * slice that still truncates**, which is the only way to get completeness from a bounded, cursor-less
 * read. If a slice cannot be narrowed further and is still truncated, that is reported loudly rather
 * than silently dropped — per §6.5, that is the signal that a real cursor is needed, and it is not
 * built yet.
 *
 * Slices are **half-open** `[from, to)` and rows are deduped by `id`, so a row on a boundary is
 * counted once. Filtering is on `recorded_at`, which is EVENT time (it equals `firstLook`) — not
 * wall-clock ingest — so the window means what it says even for a late-arriving row, and it is NOT
 * the clock `impressionWatch` buckets on.
 *
 * ## Env
 *
 * `M8TRX_VERIFY_SITE_REF` (default `dec-us-denver`) · `M8TRX_VERIFY_FROM` / `M8TRX_VERIFY_TO`
 * (ISO-8601 or epoch millis; default: the last hour) · `M8TRX_VERIFY_SPACE_REF` · `M8TRX_VERIFY_ZONE_REF`
 * · `M8TRX_VERIFY_SLICE_MIN` (initial slice minutes, default 15) · `M8TRX_ORACLE_IN` (an
 * `oracleDump` CSV to diff against) · `M8TRX_VERIFY_OUT` (write fetched rows as CSV).
 *
 * READ-ONLY — fires nothing.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ImpressionVerify")

/** Below this a slice is too thin to be a real narrowing; truncation here is a genuine cursor gap. */
private val MIN_SLICE = Duration.ofSeconds(30)

fun main() {
    val client = ConnectClient(ConnectConfig.fromEnv())
    val siteRef = System.getenv("M8TRX_VERIFY_SITE_REF") ?: "dec-us-denver"
    val to = parseWhen(System.getenv("M8TRX_VERIFY_TO")) ?: Instant.now()
    val from = parseWhen(System.getenv("M8TRX_VERIFY_FROM")) ?: to.minus(Duration.ofHours(1))
    val slice = Duration.ofMinutes((System.getenv("M8TRX_VERIFY_SLICE_MIN") ?: "15").toLong())
    val spaceRef = System.getenv("M8TRX_VERIFY_SPACE_REF")
    val zoneRef = System.getenv("M8TRX_VERIFY_ZONE_REF")

    require(from.isBefore(to)) { "from ($from) must precede to ($to)" }
    val days = Duration.between(from, to).toDays()
    require(days <= ReadCaps.WINDOW_MAX_DAYS) {
        "window must not exceed ${ReadCaps.WINDOW_MAX_DAYS} days (asked for $days) — the server refuses with 400"
    }

    log.info("Impression read-back — site={} window={}..{} initial slice={}m", siteRef, from, to, slice.toMinutes())

    val fetch = Fetcher(client, siteRef, spaceRef, zoneRef)
    var cursor = from
    while (cursor.isBefore(to)) {
        val end = minOf(cursor.plus(slice), to)
        fetch.walk(cursor, end)
        cursor = end
    }

    val rows = fetch.rows.values.toList()
    log.info(
        "fetched {} distinct rows in {} call(s){}",
        rows.size,
        fetch.calls,
        if (fetch.unresolvedTruncations > 0) " — ⚠ ${fetch.unresolvedTruncations} slice(s) STILL truncated at the floor" else "",
    )
    if (fetch.unresolvedTruncations > 0) {
        log.warn(
            "A slice hit the row cap with nothing left to narrow (floor {}s). Per §6.5 that is the signal " +
                "that a cursor is needed and is not built — the count below is a FLOOR, not a total. Say so upstream.",
            MIN_SLICE.seconds,
        )
    }
    if (rows.isEmpty()) {
        log.warn("no rows — check the window (recorded_at is EVENT time, not ingest time) and that the drive actually ran")
        return
    }

    summarize(rows)
    System.getenv("M8TRX_VERIFY_OUT")?.let { writeCsv(rows, Path.of(it)) }
    System.getenv("M8TRX_ORACLE_IN")?.let { diffOracle(rows, Path.of(it)) }
}

/** Walks a window, halving any slice the server marks `truncated`. Dedupes by row `id`. */
private class Fetcher(private val client: ConnectClient, private val siteRef: String, private val spaceRef: String?, private val zoneRef: String?) {
    val rows = LinkedHashMap<String, ImpressionRow>()
    var calls = 0
    var unresolvedTruncations = 0

    /** Half-open `[from, to)`. Recurses on truncation until the slice floor. */
    fun walk(from: Instant, to: Instant) {
        val resp = query(from, to) ?: return
        resp.impressions.forEach { r -> rows[r.id ?: syntheticKey(r)] = r }

        if (!resp.truncated) return
        val span = Duration.between(from, to)
        if (span <= MIN_SLICE) {
            unresolvedTruncations++
            log.warn("slice {}..{} still truncated at {} rows and cannot be narrowed further", from, to, resp.count)
            return
        }
        val mid = from.plus(span.dividedBy(2))
        log.info("slice {}..{} truncated at {} rows → halving", from, to, resp.count)
        walk(from, mid)
        walk(mid, to)
    }

    private fun query(from: Instant, to: Instant): ImpressionQueryResponse? {
        calls++
        val req =
            ImpressionQueryRequest(
                siteRef = siteRef,
                spaceRef = spaceRef,
                zoneRef = zoneRef,
                from = from.toString(),
                to = to.toString(),
                limit = ReadCaps.ROWS_MAX,
            )
        return when (val resp = client.queryImpressions(req)) {
            is ConnectResponse.Ok -> client.mapper.readValue<ImpressionQueryResponse>(resp.rawBody)
            is ConnectResponse.Err -> {
                val e = resp.error
                if (e.status == 403) {
                    log.error(
                        "403 on the people read — this key lacks `vision_ai:view`. That is a KEY gap, not an endpoint gap " +
                            "(§6.5 live since PR #210; `view` != `ingest`). Grant via PATCH /api/v2/connect/service-keys/{{keyId}}/scopes.",
                    )
                } else {
                    log.error("read failed {}..{} → {} {} {}", from, to, e.status, e.code, e.message ?: e.rawBody.take(120))
                }
                null
            }
        }
    }

    /** Only if a row arrives without an `id`; keeps dedupe total rather than silently collapsing rows. */
    private fun syntheticKey(r: ImpressionRow) = "${r.personSessionId}|${r.zoneId}|${r.firstLook}"
}

private fun summarize(rows: List<ImpressionRow>) {
    val byFixture = rows.groupingBy { it.zoneCode ?: it.zoneId ?: "(unattributed)" }.eachCount()
    val sessions = rows.mapNotNull { it.personSessionId }.distinct().size
    val view = rows.sumOf { it.viewTimeSeconds ?: 0.0 }
    val dwell = rows.sumOf { it.dwellTimeSeconds ?: 0.0 }

    log.info("──── persisted ────")
    log.info("  rows={} distinct fixtures={} sessions={}", rows.size, byFixture.size, sessions)
    log.info(
        "  view {}s  dwell {}s  ({} impressions/session)",
        "%.1f".format(view),
        "%.1f".format(dwell),
        "%.2f".format(
            rows.size.toDouble() / sessions.coerceAtLeast(1),
        ),
    )
    val zeroes = byFixture.filterValues { it == 0 }
    log.info("  top fixtures: {}", byFixture.entries.sortedByDescending { it.value }.take(8).joinToString(", ") { "${it.key}=${it.value}" })
    if (zeroes.isNotEmpty()) log.warn("  fixtures at zero: {}", zeroes.keys)
}

/**
 * Diff against an `oracleDump` CSV (`customer,fixtureCode,offsetMs,durationMs,viewMs,dwellMs`).
 *
 * ⚠ This is **model + transport combined**. A gap here does NOT localize to the oracle — pair it with
 * `impressionWatch` (wire) to separate the two, or the conclusion will be wrong in the same way
 * "the oracle was EXACT" was wrong as a persistence claim.
 */
private fun diffOracle(rows: List<ImpressionRow>, dump: Path) {
    if (!Files.exists(dump)) {
        log.warn("oracle dump {} not found — skipping diff", dump)
        return
    }
    val predicted = Files.readAllLines(dump).drop(1).filter { it.isNotBlank() }
        .mapNotNull { it.split(",").getOrNull(1) }
    val predByFixture = predicted.groupingBy { it }.eachCount()
    val actualByFixture = rows.groupingBy { it.zoneCode ?: "(unattributed)" }.eachCount()

    val pTotal = predicted.size
    val aTotal = rows.size
    val delta = if (pTotal == 0) 0.0 else (aTotal - pTotal) * 100.0 / pTotal

    log.info("──── oracle vs rows (MODEL + TRANSPORT combined — not an oracle verdict) ────")
    log.info("  predicted={} rows={} delta={}%", pTotal, aTotal, "%+.2f".format(delta))
    log.info("  distinct fixtures: oracle={} actual={}", predByFixture.size, actualByFixture.size)

    val oracleOnly = predByFixture.keys - actualByFixture.keys
    val actualOnly = actualByFixture.keys - predByFixture.keys
    if (oracleOnly.isNotEmpty()) log.warn("  predicted but never persisted: {}", oracleOnly.sorted())
    if (actualOnly.isNotEmpty()) log.warn("  persisted but never predicted: {}", actualOnly.sorted())
    if (oracleOnly.isEmpty() && actualOnly.isEmpty()) log.info("  fixture sets MATCH — attribution is aligned, not just the total")

    // A correct total can hide wrong attribution, so name the worst per-fixture divergences too.
    val worst = predByFixture.keys.union(actualByFixture.keys)
        .map { f -> Triple(f, predByFixture[f] ?: 0, actualByFixture[f] ?: 0) }
        .sortedByDescending { abs(it.second - it.third) }
        .take(5)
        .filter { it.second != it.third }
    if (worst.isNotEmpty()) {
        log.info("  largest per-fixture gaps: {}", worst.joinToString(", ") { "${it.first} oracle=${it.second} rows=${it.third}" })
    }
    log.info("  ⚠ to attribute this gap, compare against impressionWatch's WIRE count — oracle-vs-rows alone cannot.")
}

private fun writeCsv(rows: List<ImpressionRow>, out: Path) {
    Files.newBufferedWriter(out).use { w ->
        w.write("id,personSessionId,zoneCode,zoneName,firstLook,lastLook,firstDwell,lastDwell,viewTimeSeconds,dwellTimeSeconds,recordedAt\n")
        rows.forEach { r ->
            w.write(
                "${r.id},${r.personSessionId},${r.zoneCode},\"${r.zoneName ?: ""}\",${r.firstLook},${r.lastLook}," +
                    "${r.firstDwell},${r.lastDwell},${r.viewTimeSeconds},${r.dwellTimeSeconds},${r.recordedAt}\n",
            )
        }
    }
    log.info("wrote {} rows → {}", rows.size, out)
}

/** ISO-8601 or epoch millis — §6.5 accepts both, and the NATS event carries millis natively. */
private fun parseWhen(s: String?): Instant? {
    if (s.isNullOrBlank()) return null
    return s.toLongOrNull()?.let { Instant.ofEpochMilli(it) } ?: Instant.parse(s)
}
