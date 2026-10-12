package com.m8trx.twin.connect

import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.sim.MovementDriver
import com.m8trx.twin.connect.sim.PlanogramDirectiveDriver
import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.history.HistoryCsv
import com.m8trx.twin.history.RecentDay
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Inventory-movement drive (remediation demo) — Backend's AS-PROPOSED contract (services S178). Loads a
 * from-location EPC list (backroom/other-gondola stock for the planogram SKUs sold in the compliance-drift
 * demo), builds a `relocation` [com.m8trx.twin.connect.model.webhook.InventoryMovement] to the target
 * fixture, and POSTs at `X-Data-Type: inventory_movement` on the existing twin-pos webhook.
 *
 * SAFE BY DEFAULT — dry-run (builds + logs the movement envelope, sends nothing).
 * `M8TRX_MOVEMENT_LIVE=true` fires.
 *
 * INGESTER LIVE (services #71→#72, deployed 2026-07-01). LIVE-PROVEN in S13: twin drove real relocations
 * that recovered compliance targets (#6 `2→3` compliant, #2 `0→2`; 24/2/2 → 25/2/1) — the first live
 * runtime `thing_location` writes. Dry-run stays the default because this mutates real inventory location,
 * not because the surface is unproven.
 *
 * Env: M8TRX_MOVEMENT_EPC_FILE (a from-location EPC list, one EPC per line — no header),
 * M8TRX_MOVEMENT_TO_FIXTURE (default GB-R3-U1), M8TRX_MOVEMENT_TYPE (default relocation),
 * M8TRX_MOVEMENT_EXT_ID (override external_movement_id), M8TRX_MOVEMENT_SITE_ID (optional),
 * M8TRX_MOVEMENT_AUTH (hmac|api_key, default api_key), M8TRX_MOVEMENT_LIVE (false).
 * Run: `./gradlew connectMovementDrive`.
 *
 * ## N moves per store (2026-10-12, BRIEF-TWIN-RECENT-WINDOW § 3.4)
 *
 * Set `M8TRX_MOVEMENT_PER_STORE=N` and the run plans N relocations per store per day instead of the one
 * hand-staged move: each takes 2–6 back-of-house (`BR-*`) units of one SKU on the store's curated plan and
 * moves them to the wall the plan puts that SKU on (other walls of the full plan once the curated ones run
 * out) — a restock from the stockroom, so "what moved this week"
 * is true and the compliance targets the moves land on can recover. Distinct walls per store-day; units
 * already sold or reserved by a dated sale plan are never moved. Deterministic from (seed, store, date).
 * External id `MV-<slug>-<yyyymmdd>-<k>` (idempotent per day). The destination is the fixture CODE (the
 * proven path, resolved via `fixture_code_mapping`) or, with `M8TRX_MOVEMENT_TARGET=zone`, the wall's zone
 * UUID from the key export (no mapping needed). ⚠ The movement payload has no event time: it lands at send time.
 * Env: M8TRX_MOVEMENT_PER_STORE · M8TRX_MOVEMENT_STORES (csv, default all ten) · M8TRX_MOVEMENT_DATE (today) ·
 * M8TRX_MOVEMENT_SEED (20261016) · M8TRX_MOVEMENT_TARGET (code|zone) · M8TRX_MOVEMENT_LIVE · M8TRX_STREAM_SOLD_LOG.
 *
 * ## Restock moves (2026-10-12, Bob: "yes do the restock moves")
 *
 * `M8TRX_MOVEMENT_RESTOCK=true` (default false) adds, after the random moves, one `inventory_movement` per
 * (fixture, EAN) that the date's SENT sales drew down: the same number of stockroom units of that EAN, moved onto the
 * wall that sold them — so compliance holds where the history left it. Reads the date's sales plan
 * (`.twin-state/stream/twin-day-<yyyymmdd>-<seed>.plan.csv` + its `.sent` ledger; absent → says so and does nothing),
 * keeps `.twin-state/moves/restock-<yyyymmdd>-<seed>.sent` (idempotent, resumable) and `moves/placements.csv`
 * (epc,fixture,date — where a moved unit now sits). Works with `M8TRX_MOVEMENT_PER_STORE` unset or 0 (restock only).
 * Live sends are paced `M8TRX_MOVEMENT_GAP_MS` (120) apart, like the sale stream.
 * Units the `connectReceiveDrive` step created (`.twin-state/receive/received-stock.csv`) count as stockroom stock;
 * `M8TRX_MOVEMENT_ASSUME_RECEIVED=true` (dry runs only) counts the receive step's PLAN as already landed.
 * Full rules, ids and the limitation (the sales stream does not read placements): [RestockMoves].
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ConnectMovementDrive")

fun main() {
    val config = ConnectConfig.fromEnv()
    val driver = MovementDriver(WebhookClient(config))
    val live = System.getenv("M8TRX_MOVEMENT_LIVE")?.toBooleanStrictOrNull() ?: false
    val perStore = System.getenv("M8TRX_MOVEMENT_PER_STORE")?.toIntOrNull()
    val restock = System.getenv("M8TRX_MOVEMENT_RESTOCK")?.toBooleanStrictOrNull() ?: false
    if (perStore != null || restock) {
        multiStore(driver, perStore ?: 0, restock, live)
        return
    }
    val epcFile = Path.of(env("M8TRX_MOVEMENT_EPC_FILE", "reference/data/chain/stores/dec-us-denver/movement-epcs.txt"))
    val toFixtureCode = env("M8TRX_MOVEMENT_TO_FIXTURE", "GB-R3-U1")
    val movementType = env("M8TRX_MOVEMENT_TYPE", "relocation")
    val siteId = System.getenv("M8TRX_MOVEMENT_SITE_ID")?.takeIf { it.isNotBlank() }
    val extId = env("M8TRX_MOVEMENT_EXT_ID", "twin-movement-${System.currentTimeMillis()}")
    val auth = if (env("M8TRX_MOVEMENT_AUTH", "api_key").equals("hmac", ignoreCase = true)) {
        WebhookClient.AuthMode.HMAC
    } else {
        WebhookClient.AuthMode.API_KEY
    }

    log.info(
        "Movement drive → toFixture={} type={} auth={} live={} epcFile={}",
        toFixtureCode,
        movementType,
        auth,
        live,
        epcFile,
    )

    if (!Files.exists(epcFile)) {
        error("no from-location EPC file at $epcFile — stage one EPC per line (see reference/data/chain staging)")
    }
    val epcs = Files.readAllLines(epcFile).map { it.trim() }.filter { it.isNotEmpty() }
    if (epcs.isEmpty()) {
        error("$epcFile contained no EPCs — nothing to relocate")
    }

    val movement = driver.build(
        toFixtureCode = toFixtureCode,
        epcs = epcs,
        movementType = movementType,
        externalMovementId = extId,
        siteId = siteId,
    )

    if (live) {
        when (val resp = driver.drive(movement, auth)) {
            is ConnectResponse.Ok -> log.info("Movement drive done — 1 ok / 0 err LIVE")
            is ConnectResponse.Err -> error("movement drive failed — status=${resp.error.status} code=${resp.error.code}")
        }
    } else {
        val json = driver.dryRun(movement)
        log.info(
            "[DRY] toFixture={} extId={} items={} bytes={}",
            toFixtureCode,
            movement.externalMovementId,
            movement.items.size,
            json.toByteArray().size,
        )
        log.info("[DRY] envelope={}", json)
        log.info("Movement drive done — 1 ok / 0 err DRY-RUN")
    }
}

/** One planned relocation: [epcs] of one SKU from the stockroom to the plan's wall [toFixture]. */
data class PlannedMove(val extId: String, val store: String, val toFixture: String, val toZoneId: String?, val ean: String, val epcs: List<String>)

/**
 * Plan [perStore] moves for [slug] on [date]. Pure (reads files only) and deterministic. Candidates are the
 * curated plan's lines whose SKU has back-of-house units left; walls are drawn without replacement.
 */
fun planMoves(chainDir: Path, slug: String, date: LocalDate, perStore: Int, seed: Long, excluded: Set<String>): List<PlannedMove> {
    val keys = ChainKeys.load(chainDir.resolve(ChainKeys.DEFAULT_PATH.fileName)).store(slug)
    val walls = keys.fixtures.filter { !it.department.isNullOrBlank() }.associate { it.code to it.department!! }
    val zoneIds = keys.fixtures.associate { it.code to it.zoneId }
    val doc = PlanogramDirectiveDriver(WebhookClient(ConnectConfig.fromEnv())).loadDocument(chainDir.resolve("stores/$slug/planogram.json"))
    val lines = PlanogramDirectiveDriver.curate(doc, walls)
    val boh = DatedStream.loadEpcRows(chainDir.resolve("stores/$slug/epcs.csv"))
        .filter { it.backOfHouse && it.epc !in excluded }
        .groupBy { it.ean }
        .mapValues { (_, rs) -> rs.map { it.epc }.sorted() }
    val rng = Random(HistoryCsv.derive(seed, slug, date, "moves"))
    // the curated plan's walls first (moves that can lift a compliance target), then any other wall of the plan
    val onPlan = lines.filter { (boh[it.ean]?.size ?: 0) >= 2 }.distinctBy { it.fixtureCode }.shuffled(rng)
    val planned = onPlan.map { it.fixtureCode }.toSet()
    val otherWalls = doc.lines.filter { it.fixtureCode in walls && it.fixtureCode !in planned && (boh[it.ean]?.size ?: 0) >= 2 }
        .sortedWith(compareBy({ it.fixtureCode }, { it.positionSequence }))
        .distinctBy { it.fixtureCode }
        .shuffled(rng)
    val candidates = onPlan + otherWalls
    val ymd = date.format(DateTimeFormatter.BASIC_ISO_DATE)
    return candidates.take(perStore).mapIndexed { k, line ->
        val units = boh.getValue(line.ean)
        val n = (2 + rng.nextInt(5)).coerceAtMost(units.size)
        PlannedMove("MV-$slug-$ymd-${k + 1}", slug, line.fixtureCode, zoneIds[line.fixtureCode], line.ean, units.take(n))
    }
}

/** One movement to send: a random stockroom → wall move, or a restock of what sold. */
private data class Outgoing(
    val kind: String,
    val extId: String,
    val store: String,
    val toFixture: String,
    val toZoneId: String?,
    val ean: String,
    val epcs: List<String>,
)

private fun multiStore(driver: MovementDriver, perStore: Int, restock: Boolean, live: Boolean) {
    val chainDir = Path.of(env("M8TRX_CHAIN_DIR", "reference/data/chain"))
    val date = System.getenv("M8TRX_MOVEMENT_DATE")?.takeIf { it.isNotBlank() }?.let(LocalDate::parse) ?: LocalDate.now()
    val seed = System.getenv("M8TRX_MOVEMENT_SEED")?.toLongOrNull() ?: RecentDay.HISTORY_SEED
    val byZone = env("M8TRX_MOVEMENT_TARGET", "code").equals("zone", ignoreCase = true)
    val soldLog = Path.of(env("M8TRX_STREAM_SOLD_LOG", ".twin-state/sold-epcs.txt"))
    val keys = ChainKeys.load(chainDir.resolve(ChainKeys.DEFAULT_PATH.fileName))
    val stores = System.getenv("M8TRX_MOVEMENT_STORES")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: keys.stores.map { it.slug }
    val streamDir = soldLog.parent?.resolve("stream")
    val reserved = if (streamDir != null && Files.isDirectory(streamDir)) {
        Files.list(streamDir).use { s ->
            s.filter { it.fileName.toString().endsWith(".plan.csv") }.toList()
        }.flatMap { DatedStream.readPlan(it).flatMap { p -> p.epcs } }.toSet()
    } else {
        emptySet()
    }
    val movesDir = RestockMoves.movesDir(soldLog)
    val placementsFile = movesDir.resolve("placements.csv")
    val placements = RestockMoves.readPlacements(placementsFile)
    val excluded = readPriorSold(soldLog) + reserved + placements.keys
    val t0 = System.currentTimeMillis()
    log.info(
        "Movement drive (multi) → date={} stores={} perStore={} restock={} target={} live={}",
        date,
        stores.size,
        perStore,
        restock,
        if (byZone) "zone_id" else "fixture_code",
        live,
    )
    val random = if (perStore > 0) stores.flatMap { planMoves(chainDir, it, date, perStore, seed, excluded) } else emptyList()

    // restock: what the date's SENT sales drew down from the walls, refilled from the stockroom
    val restockPlans = if (restock) {
        val planFile = RestockMoves.planFile(requireNotNull(streamDir), date, seed)
        if (!Files.exists(planFile)) {
            log.info("  restock: no sales plan at {} — the sales have not been planned/sent; nothing to restock", planFile)
            emptyList()
        } else {
            val sentFile = planFile.resolveSibling(planFile.fileName.toString().removeSuffix(".plan.csv") + ".sent")
            val sentIds = RestockMoves.readLedger(sentFile)
            val sales = DatedStream.readPlan(planFile)
            val ledger = RestockMoves.readLedger(RestockMoves.ledgerFile(movesDir, date, seed))
            val taken = excluded + random.flatMap { it.epcs }
            // stockroom units the receive step created (.twin-state/receive/received-stock.csv), lowest EPC first like the rest
            val received = ReceiveStock.readStock(ReceiveStock.stockFile(ReceiveStock.receiveDir(soldLog)))
            // DRY chain: with M8TRX_MOVEMENT_ASSUME_RECEIVED=true the receive step's PLAN for the date counts as already
            // received, so a dry run shows the restock AFTER receiving. Never with live moves: those EPCs do not exist yet.
            val assume = System.getenv("M8TRX_MOVEMENT_ASSUME_RECEIVED")?.toBooleanStrictOrNull() ?: false
            require(!(assume && live)) { "M8TRX_MOVEMENT_ASSUME_RECEIVED is for dry runs only - a live move of un-received EPCs would be refused" }
            val assumed = if (assume) ReceiveStock.planAll(chainDir, soldLog, date, seed, stores, log).flatMap { it.toReceive } else emptyList()
            if (assume) log.info("  restock: assuming {} planned receipts already landed (dry chain)", assumed.size)
            stores.map { slug ->
                val zoneIds = keys.store(slug).zones.associate { it.code to it.zoneId }
                val extra = ReceiveStock.stockRows(received + assumed, slug)
                RestockMoves.plan(
                    slug, date, DatedStream.loadEpcRows(chainDir.resolve("stores/$slug/epcs.csv")) + extra,
                    sales.filter { it.store == slug }, sentIds, taken, ledger, placements, zoneIds,
                )
            }
        }
    } else {
        emptyList()
    }
    restockPlans.filter { it.soldWallUnits + it.soldBohUnits > 0 }.forEach { p ->
        log.info(
            "  restock {} sold: {} wall + {} stockroom · already restocked {} · {} moves / {} units · shortfall {}",
            p.store,
            p.soldWallUnits,
            p.soldBohUnits,
            p.alreadyRestocked,
            p.moves.size,
            p.units,
            p.shortfallUnits,
        )
    }
    val restockMoves = restockPlans.flatMap { it.moves }
    val restockUnits = restockMoves.sumOf { it.epcs.size }
    val shortfall = restockPlans.sumOf { it.shortfallUnits }
    if (restock) {
        log.info("PLAN: restock {} moves / {} units, shortfall {} (stores {})", restockMoves.size, restockUnits, shortfall, stores.size)
    }

    val outgoing = random.map { Outgoing("random", it.extId, it.store, it.toFixture, it.toZoneId, it.ean, it.epcs) } +
        restockMoves.map { Outgoing("restock", it.extId, it.store, it.toFixture, it.toZoneId, it.ean, it.epcs) }
    val ledgerFile = RestockMoves.ledgerFile(movesDir, date, seed)
    var ok = 0
    var err = 0
    var unmapped = 0
    val gapMs = System.getenv("M8TRX_MOVEMENT_GAP_MS")?.toLongOrNull() ?: 120L
    for ((i, m) in outgoing.withIndex()) {
        val mv = driver.build(toFixtureCode = m.toFixture, epcs = m.epcs, externalMovementId = m.extId).let {
            when {
                byZone && m.toZoneId != null -> it.copy(toFixtureCode = null, toZoneId = m.toZoneId)
                byZone -> it.also { unmapped++ }
                else -> it
            }
        }
        log.info(
            "  {} {} {} {} → {} ({}) ean={} units={}",
            if (live) "[LIVE]" else "[DRY]",
            m.kind,
            m.store,
            m.extId,
            m.toFixture,
            m.toZoneId,
            m.ean,
            m.epcs.size,
        )
        if (!live) continue
        when (val r = driver.drive(mv, WebhookClient.AuthMode.API_KEY)) {
            is ConnectResponse.Ok -> {
                ok++
                RestockMoves.appendPlacements(placementsFile, m.epcs, m.toFixture, date)
                if (m.kind == "restock") RestockMoves.appendLedger(ledgerFile, m.epcs)
                if (i < outgoing.size - 1) Thread.sleep(gapMs) // a restock is hundreds of sends: stay under the measured ingest ceiling
            }

            is ConnectResponse.Err -> {
                err++
                log.error("  {} refused {} {} — stopping", m.extId, r.error.status, r.error.code)
                break
            }
        }
    }
    if (unmapped > 0) log.warn("  {} restock/random move(s) had no zone id in the key export and went by fixture code", unmapped)
    outgoing.firstOrNull()?.let {
        log.info("  first envelope: {}", driver.dryRun(driver.build(toFixtureCode = it.toFixture, epcs = it.epcs, externalMovementId = it.extId)))
    }
    log.info(
        "SIM-RUN movement date={} seed={} moves={} units={} restock_moves={} restock_units={} shortfall_units={} sent_ok={} sent_err={} wall={}s",
        date,
        seed,
        random.size,
        random.sumOf { it.epcs.size },
        restockMoves.size,
        restockUnits,
        shortfall,
        ok,
        err,
        (System.currentTimeMillis() - t0) / 1000,
    )
    if (live && err > 0) error("a movement was refused — see the log")
}

private fun env(key: String, default: String) = System.getenv(key)?.takeIf { it.isNotBlank() } ?: default
