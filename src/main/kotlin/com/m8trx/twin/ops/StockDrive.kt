package com.m8trx.twin.ops

import com.m8trx.twin.connect.ConnectClient
import com.m8trx.twin.connect.ConnectConfig
import com.m8trx.twin.connect.RestockMoves
import com.m8trx.twin.connect.http.ConnectHttp
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.bearer.ItemDetailsRequest
import org.slf4j.LoggerFactory
import java.time.format.DateTimeFormatter

/**
 * `./gradlew connectStockDrive` — the `stock` step: real cloud counts through the count inbox, per store per day
 * ([StockCounts] plans them). One count = `POST /count` (countId, scopeZoneIds) → one `POST /count/pulls` per frame
 * (every fixture of the scope, the read set the plan derived) → `POST /count/{id}/close {reason: route_complete}` →
 * `GET /count/{id}/exceptions` (the totals the closing sheet reads, logged). Before the close, 1–2 floor notes go to
 * `POST /stocktake/sessions/{countId}/observations` (`stocktake:execute`), so the session carries observations too. The close runs the platform's own reconcile:
 * the session gets its segments (one per counted fixture), its observations, its `stocktake_discrepancy` rows and the
 * `summary.totalScanned` / `summary.discrepancyCounts` the web's Stocktake list reads.
 *
 * Auth: the store manager's JWT (`stocktake:create_session` to open, `stocktake:execute` to pull and close,
 * `inventory:read` for the exceptions — the site-manager kit holds all three). The platform-truth filter
 * (`POST /inventory/items/details`) rides the tenant-wide data-plane bearer (`inventory:read`).
 *
 * SAFE BY DEFAULT — dry run (plans, prints the first request bodies, sends nothing, logs no one in).
 * `M8TRX_STOCK_LIVE=true` sends. Env: [OpsConfig] (prefix `M8TRX_STOCK`) plus
 * `M8TRX_STOCK_FULL_FLOOR` (`dec-us-denver@2026-10-15`: slug@date pairs, csv — the demo-day whole-floor count; Denver
 * local Oct 15 is the morning of Oct 16 KST) · `M8TRX_STOCK_PULL_GAP_MS` (60).
 *
 * Ledger `.twin-state/stock/stock-<yyyymmdd>-<seed>.sent` (`countId,slug,k,closed`): a closed count is never re-sent.
 * After a live close: the `missing` EPCs go to `stock/missing-epcs.txt` (the twin's stock model drops them) and the
 * over units' new rack goes to `moves/placements.csv` (the restock step then sees them where the platform has them).
 */
class StockDrive(private val cfg: OpsConfig, private val http: ConnectHttp = ConnectHttp()) {
    private val log = LoggerFactory.getLogger(StockDrive::class.java)
    private val ops = OpsClient(cfg.apiBase, cfg.hasuraUrl, cfg::requirePassword, http)

    data class Summary(
        val counts: Int,
        val fixtures: Int,
        val reads: Int,
        val missing: Int,
        val over: Int,
        val closed: Int,
        val held: Int,
        val err: Int,
        val shapes: List<String>,
    )

    fun fullFloor(slug: String): Boolean = (System.getenv("M8TRX_STOCK_FULL_FLOOR") ?: "dec-us-denver@2026-10-15")
        .split(",").map { it.trim() }.any { it == "$slug@${cfg.date}" }

    fun run(fullFloorOf: (String) -> Boolean = ::fullFloor): Summary {
        val ymd = cfg.date.format(DateTimeFormatter.BASIC_ISO_DATE)
        val ledgerFile = cfg.stateDir.resolve("stock").resolve("stock-$ymd-${cfg.seed}.sent")
        val ledger = StoreStock.readLines(ledgerFile)
        val done = ledger.filter { it.endsWith(",closed") }.map { it.substringBefore(",") }.toSet()
        var counts = 0
        var fixtures = 0
        var reads = 0
        var missing = 0
        var over = 0
        var closed = 0
        var held = 0
        var err = 0
        val shapes = ArrayList<String>()
        for (slug in cfg.stores) {
            val store = cfg.keys.store(slug)
            cfg.pastRefusal(slug)?.let {
                log.warn("  {} HELD — {}", slug, it)
                held++
                continue
            }
            var stock = StoreStock.load(cfg.chainDir, cfg.stateDir, slug)
            if (cfg.live) stock = stock.narrowedTo(inStock(stock.byFixture.values.flatten()))
            val plans = StockCounts.plan(store, cfg.date, cfg.seed, stock, fullFloorOf(slug))
            for (p in plans) {
                counts++
                fixtures += p.pulls.size
                reads += p.reads
                missing += p.missing.size
                over += p.over.size
                log.info(
                    "  {} count#{} {} {} — {} fixtures {} · {} reads · {} missing · {} over · {}..{}",
                    if (cfg.live) "[LIVE]" else "[DRY]",
                    p.k,
                    slug,
                    p.countId,
                    p.pulls.size,
                    p.pulls.joinToString("/") { it.code }.take(120),
                    p.reads,
                    p.missing.size,
                    p.over.size,
                    p.startedAt,
                    p.endedAt,
                )
                if (shapes.isEmpty()) shapes += shapeLines(p)
                if (!cfg.live) continue
                if (p.countId in done) {
                    log.info("    already closed (ledger) — skipped")
                    continue
                }
                if (!cfg.allowFuture && p.endedAt.isAfter(cfg.now)) {
                    log.info("    HELD — the count's last pull ends {} (future); set M8TRX_OPS_ALLOW_FUTURE=true or run later", p.endedAt)
                    held++
                    continue
                }
                when (val r = send(p, ledger, ledgerFile)) {
                    null -> {
                        closed++
                        StoreStock.appendLines(ledgerFile, listOf("${p.countId},$slug,${p.k},closed"))
                        StoreStock.appendLines(StoreStock.missingFile(cfg.stateDir), p.missing.map { it.epc })
                        val placements = cfg.stateDir.resolve("moves").resolve("placements.csv")
                        p.over.forEach { RestockMoves.appendPlacements(placements, listOf(it.epc), it.toFixture, cfg.date) }
                    }

                    else -> {
                        err++
                        log.error("    {} — stopping this store", r)
                        break
                    }
                }
            }
        }
        log.info(
            "SIM-RUN stock date={} seed={} counts={} fixtures={} reads={} missing={} over={} closed={} held={} sent_err={}",
            cfg.date,
            cfg.seed,
            counts,
            fixtures,
            reads,
            missing,
            over,
            closed,
            held,
            err,
        )
        return Summary(counts, fixtures, reads, missing, over, closed, held, err, shapes)
    }

    /** One count end to end; null = closed, else what refused it. */
    private fun send(p: StockCounts.CountPlan, ledger: Set<String>, ledgerFile: java.nio.file.Path): String? {
        val email = cfg.actorEmail(p.slug)
        val jwt = ops.jwt(email) ?: return "login refused for $email"
        val siteId = cfg.siteId(p.slug)
        val space = cfg.keys.store(p.slug).salesFloorSpaceId
        val open = ops.send(jwt, "POST", "/count", openBody(p, siteId, space))
        if (open !is ConnectResponse.Ok) return "open ${ops.describe(open)}"
        if (ops.json(open).path("status").asText() == "closed") {
            log.info("    the platform already has this count closed (a re-run without its ledger line) — nothing re-sent")
            return null
        }
        val gap = System.getenv("M8TRX_STOCK_PULL_GAP_MS")?.toLongOrNull() ?: 60L
        for (pull in p.pulls) {
            for (f in pull.frames) {
                val r = ops.send(jwt, "POST", "/count/pulls", pullBody(p, pull, f, siteId, space))
                if (r !is ConnectResponse.Ok) return "pull ${pull.code}#${f.seq} ${ops.describe(r)}"
                if (gap > 0) Thread.sleep(gap)
            }
        }
        // the walk's floor notes, before the close (a count IS a stocktake session); each once (ledger)
        for ((i, o) in p.observations.withIndex()) {
            val line = "${p.countId},obs,$i"
            if (line in ledger) continue
            val r = ops.send(jwt, "POST", "/stocktake/sessions/${p.countId}/observations", observationBody(o))
            if (r !is ConnectResponse.Ok) return "observation ${o.code} ${ops.describe(r)}"
            StoreStock.appendLines(ledgerFile, listOf(line))
        }
        val close = ops.send(jwt, "POST", "/count/${p.countId}/close", mapOf("reason" to "route_complete"))
        if (close !is ConnectResponse.Ok) return "close ${ops.describe(close)}"
        val ex = ops.send(jwt, "GET", "/count/${p.countId}/exceptions")
        if (ex is ConnectResponse.Ok) {
            val n = ops.json(ex)
            log.info("    closed — reconciled={} totals={}", n.path("reconciled").asText(), n.path("totals"))
        } else {
            log.info("    closed — exceptions read {} (the reconcile may still be running)", ops.describe(ex))
        }
        return null
    }

    /** The platform's own answer to "which of these units are in stock" (tenant-wide data-plane bearer, `inventory:read`). */
    private fun inStock(epcs: List<String>): Set<String> {
        val bearer = cfg.dataBearer ?: error("M8TRX_TWIN_SERVICE_BEARER is not set — the stock step's in-stock filter reads /inventory/items/details")
        val client = ConnectClient(ConnectConfig(cfg.apiBase, "", null, null, bearer, null, null, null), http)
        val keep = HashSet<String>()
        for (chunk in epcs.chunked(200)) {
            val r = client.itemDetails(ItemDetailsRequest(chunk))
            if (r !is ConnectResponse.Ok) error("items/details refused: ${ops.describe(r)}")
            client.mapper.readTree(r.rawBody).forEach { if (it.path("state").asText() == "in_stock") keep += it.path("epc").asText() }
        }
        return keep
    }

    private fun shapeLines(p: StockCounts.CountPlan): List<String> {
        val site = cfg.siteIds[p.slug] ?: "<site>"
        val space = cfg.keys.store(p.slug).salesFloorSpaceId
        val pull = p.pulls.first()
        val frame = pull.frames.first().let { it.copy(tags = it.tags.take(2)) }
        val m = com.m8trx.twin.connect.model.ConnectMappers.camel
        return listOf(
            "POST /count ${m.writeValueAsString(openBody(p, site, space))}",
            "POST /count/pulls ${m.writeValueAsString(pullBody(p, pull, frame, site, space))} (tags shown: 2 of ${pull.frames.first().tags.size})",
            p.observations.firstOrNull()?.let { "POST /stocktake/sessions/${p.countId}/observations ${m.writeValueAsString(observationBody(it))}" }
                ?: "(no observation)",
            "POST /count/${p.countId}/close {\"reason\":\"route_complete\"}",
            "GET /count/${p.countId}/exceptions",
        )
    }

    companion object {
        fun observationBody(o: StockCounts.Observation): Map<String, Any?> = mapOf(
            "zoneId" to o.zoneId,
            "observationType" to o.type,
            "severity" to o.severity,
            "details" to mapOf("note" to o.note, "fixture_code" to o.code, "source" to "twin-stock"),
        )

        fun openBody(p: StockCounts.CountPlan, siteId: String, spaceId: String): Map<String, Any?> = mapOf(
            "countId" to p.countId,
            "siteId" to siteId,
            "spaceId" to spaceId,
            "deviceId" to "twin-stock-${p.slug}",
            "readerSerial" to "TWIN-SIM-01",
            "scopeZoneIds" to p.scopeZoneIds,
        )

        fun pullBody(
            p: StockCounts.CountPlan,
            pull: StockCounts.FixturePull,
            f: StockCounts.Frame,
            siteId: String,
            spaceId: String,
        ): Map<String, Any?> = mapOf(
            "countId" to p.countId,
            "pullId" to pull.pullId,
            "seq" to f.seq,
            "deviceId" to "twin-stock-${p.slug}",
            "readerSerial" to "TWIN-SIM-01",
            "siteId" to siteId,
            "spaceId" to spaceId,
            "zoneId" to pull.zoneId,
            "startedAt" to f.startedAt.toString(),
            "endedAt" to f.endedAt.toString(),
            "readerPowerDbm" to 27.0,
            "tags" to f.tags.map {
                mapOf(
                    "identifier" to it.epc,
                    "readCount" to it.readCount,
                    "rssiBest" to it.rssiBest,
                    "rssiMean" to it.rssiMean,
                    "firstSeenAt" to it.firstSeenAt.toString(),
                    "lastSeenAt" to it.lastSeenAt.toString(),
                )
            },
        )
    }
}

fun main() {
    val cfg = OpsConfig.fromEnv("M8TRX_STOCK")
    LoggerFactory.getLogger("com.m8trx.twin.ops.StockDrive").info(
        "Stock drive → date={} seed={} stores={} live={} state={}",
        cfg.date,
        cfg.seed,
        cfg.stores.size,
        cfg.live,
        cfg.stateDir,
    )
    val s = StockDrive(cfg).run()
    s.shapes.forEach { LoggerFactory.getLogger("com.m8trx.twin.ops.StockDrive").info("  shape: {}", it) }
    if (cfg.live && s.err > 0) error("a count was refused — see the log")
}
