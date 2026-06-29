package com.m8trx.twin.connect

import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.sim.DeviceDriver
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * Multi-site scan sweep (S11) — emulates edge RFID readers periodically scanning their zones: the
 * §6 data-plane "inventory presence" stream that complements sales. A reader at a fixture reports
 * the in-stock EPCs located there (epcs.csv `fixture`, minus the sold-log), via [DeviceDriver] on
 * the Bearer plane (`scan:submit`).
 *
 * SAFE BY DEFAULT — dry-run: it PLANS + logs scan batches and sends nothing. Set `M8TRX_SCAN_LIVE=true`
 * to actually `POST /scans`. Live submission is held for backend's verify (reader-topology +
 * scan→location semantics get confirmed before twin submits live).
 *
 * Env: M8TRX_SCAN_STORES (default all), M8TRX_SCAN_SWEEPS (30), M8TRX_SCAN_DURATION_SEC (180),
 * M8TRX_SCAN_READ_SIZE (25), M8TRX_SCAN_LIVE (false), M8TRX_STREAM_SOLD_LOG, M8TRX_STREAM_SEED,
 * M8TRX_RUN_ID, M8TRX_CHAIN_DIR. Run: `./gradlew connectScanSweep`.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ScanStream")

private data class Reader(val storeCode: String, val siteId: String, val fixture: String, val epcs: List<String>)

fun main() {
    val config = ConnectConfig.fromEnv()
    val driver = DeviceDriver(ConnectClient(config))
    val chainDir = Path.of(env("M8TRX_CHAIN_DIR", "reference/data/chain"))

    val live = System.getenv("M8TRX_SCAN_LIVE")?.toBooleanStrictOrNull() ?: false
    val sweeps = envInt("M8TRX_SCAN_SWEEPS", 30)
    val durationSec = envInt("M8TRX_SCAN_DURATION_SEC", 180)
    val readSize = envInt("M8TRX_SCAN_READ_SIZE", 25)
    val seed = System.getenv("M8TRX_STREAM_SEED")?.toLongOrNull() ?: System.currentTimeMillis()
    val runId = env("M8TRX_RUN_ID", "twin-s11-scan-$seed")
    val soldLog = Path.of(env("M8TRX_STREAM_SOLD_LOG", ".twin-state/sold-epcs.txt"))
    val rng = Random(seed)

    val filter = System.getenv("M8TRX_SCAN_STORES")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
    val sold = readPriorSold(soldLog)
    val readers = loadStores(chainDir.resolve("site_ids.csv"))
        .filter { filter == null || it.first in filter }
        .flatMap { (code, siteId) ->
            loadEpcsByFixture(chainDir.resolve("stores/$code/epcs.csv"), sold)
                .map { (fx, epcs) -> Reader(code, siteId, fx, epcs) }
        }
        .filter { it.epcs.isNotEmpty() }
    check(readers.isNotEmpty()) { "no readers (no in-stock EPCs by fixture) — check $chainDir / sold-log" }

    log.info("Scan sweep → readers={} sweeps={} window={}s readSize={} live={} runId={}", readers.size, sweeps, durationSec, readSize, live, runId)

    var ok = 0
    var err = 0
    val startMs = System.currentTimeMillis()
    repeat(sweeps) { i ->
        val r = readers[rng.nextInt(readers.size)]
        val sample = r.epcs.shuffled(rng).take(minOf(readSize, r.epcs.size))
        val readerId = "RDR-${r.storeCode}-${r.fixture}"
        if (live) {
            when (driver.scan(r.siteId, readerId, sample, fixtureId = r.fixture, runId = runId)) {
                is ConnectResponse.Ok -> ok++
                is ConnectResponse.Err -> err++
            }
        } else {
            log.info("[{}/{}] DRY reader={} site={} fixture={} reads={}", i + 1, sweeps, readerId, r.siteId, r.fixture, sample.size)
            ok++
        }
        if (i < sweeps - 1) Thread.sleep(jitterGapMs(durationSec, sweeps, rng))
    }

    val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
    val mode = if (live) "LIVE" else "DRY-RUN"
    log.info("Scan sweep done — {} batches ({} ok / {} err) in {}s {} runId={}", sweeps, ok, err, "%.1f".format(elapsed), mode, runId)
}

/** site_ids.csv -> (store_code, site_id) pairs. */
private fun loadStores(file: Path): List<Pair<String, String>> {
    if (!Files.exists(file)) return emptyList()
    return Files.readAllLines(file).drop(1).mapNotNull { line ->
        val c = line.split(",")
        if (c.size < 2 || c[0].isBlank()) null else c[0].trim() to c[1].trim()
    }
}

/** epcs.csv -> in-stock EPCs grouped by fixture (cols epc,ean,item_cd,category,fixture,store_id), minus [sold]. */
private fun loadEpcsByFixture(file: Path, sold: Set<String>): Map<String, List<String>> {
    if (!Files.exists(file)) return emptyMap()
    val byFixture = HashMap<String, MutableList<String>>()
    for (line in Files.readAllLines(file).drop(1)) {
        val c = line.split(",")
        if (c.size < 6) continue
        val epc = c[0].trim()
        val fixture = c[4].trim()
        if (epc.isEmpty() || fixture.isEmpty() || epc in sold) continue
        byFixture.getOrPut(fixture) { ArrayList() }.add(epc)
    }
    return byFixture
}

private fun env(key: String, default: String) = System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

private fun envInt(key: String, default: Int) = System.getenv(key)?.toIntOrNull() ?: default
