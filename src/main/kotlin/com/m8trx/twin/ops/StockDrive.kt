package com.m8trx.twin.ops

import com.m8trx.twin.connect.RestockMoves
import com.m8trx.twin.connect.http.ConnectHttp
import com.m8trx.twin.connect.http.ConnectResponse
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
 * Auth: the store manager's JWT throughout (`stocktake:create_session` to open, `stocktake:execute` to pull and close,
 * `inventory:read` for the exceptions — the site-manager kit holds all three) — and for the one read that decides what
 * the count reads: where the PLATFORM currently holds each in-stock unit (Hasura `thing_location`, `frontEnd` role). A live
 * pass makes that read only for a store that has a count due (not closed, start time reached).
 *
 * SAFE BY DEFAULT — dry run (plans, prints the first request bodies, sends nothing, logs no one in).
 * `M8TRX_STOCK_LIVE=true` sends (a dry run uses the twin's own stock model and reads nothing). Env: [OpsConfig] (prefix `M8TRX_STOCK`) plus
 * `M8TRX_STOCK_FULL_FLOOR` (`dec-us-denver@2026-10-15`: slug@date pairs, csv — the demo-day whole-floor count; Denver
 * local Oct 15 is the morning of Oct 16 KST) · `M8TRX_STOCK_PULL_GAP_MS` (60).
 *
 * Ledger `.twin-state/stock/stock-<yyyymmdd>-<seed>.sent` (`countId,slug,k,closed`): a closed count is never re-sent.
 * After a live close: the `missing` EPCs go to `stock/missing-epcs.txt` (the twin's stock model drops them) and the
 * over units' new rack goes to `moves/placements.csv` (the restock step then sees them where the platform has them).
 */
class StockDrive(private val cfg: OpsConfig, private val http: ConnectHttp = ConnectHttp()) {
    private val log = LoggerFactory.getLogger(StockDrive::class.java)
    private val ops = OpsClient(cfg.apiBase, cfg.hasuraUrl, cfg::requirePassword, http, cfg.stateDir)

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
            // a store that fails (login, a refused read, a dropped connection) fails alone; the pass goes on to the next store
            try {
                val full = fullFloorOf(slug)
                val ids = (0 until StockCounts.countsFor(store, cfg.date, cfg.seed, full)).map {
                    it to
                        StockCounts.countId(store, cfg.date, cfg.seed, it)
                }
                val pending = ids.filter { (k, id) ->
                    id !in done && (cfg.allowFuture || !StockCounts.startAt(store, cfg.date, k, full).isAfter(cfg.now))
                }
                var jwt: String? = null
                val stock = if (!cfg.live) {
                    // a dry run is the twin's own model: no login, no read
                    StoreStock.load(cfg.chainDir, cfg.stateDir, slug)
                } else if (pending.isEmpty()) {
                    log.info("  {} nothing to count this pass ({} count(s): closed, or not yet due) — no platform read", slug, ids.size)
                    counts += ids.size
                    held += ids.count { (k, id) -> id !in done && StockCounts.startAt(store, cfg.date, k, full).isAfter(cfg.now) }
                    continue
                } else {
                    val email = cfg.actorEmail(slug)
                    jwt = ops.jwt(email)
                    if (jwt == null) {
                        err++
                        log.error("  {} login refused for {} — this store is skipped for the pass", slug, email)
                        continue
                    }
                    platformStock(jwt, store, cfg.siteId(slug))
                }
                val plans = StockCounts.plan(store, cfg.date, cfg.seed, stock, full)
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
            } catch (e: Exception) {
                err++
                log.error("  {} FAILED — {}: {} (the pass goes on to the next store)", slug, e.javaClass.simpleName, e.message)
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

    /**
     * Where the PLATFORM says each in-stock unit of the store is right now — one Hasura read as the store manager
     * (`thing_location` ∪ `item.state = in_stock` ∪ `item_identifiers`, all selectable by the `frontEnd` role), paged. The
     * read sets are built from THIS, never from the twin's own wall record: a unit the platform keeps in the Back Room is
     * never read at a wall (it would come out as an `over` row), and a unit it keeps at a wall is the one a `missing` is
     * chosen from. A read that returns no unit at all is an error, not an empty store.
     */
    private fun platformStock(jwt: String, store: com.m8trx.twin.history.ChainKeys.Store, siteId: String): StoreStock {
        val located = ArrayList<StoreStock.Located>()
        var offset = 0
        while (true) {
            val page = ops.query(jwt, LOCATIONS_GQL, mapOf("site" to siteId, "limit" to LOCATION_PAGE, "offset" to offset)).path("thing_location")
            if (page.size() == 0) break
            page.forEach { row ->
                val zone = row.path("zone_id").asText("").ifBlank { null } ?: return@forEach
                row.path("item").path("item_identifiers").forEach { located += StoreStock.Located(zone, it.path("value").asText()) }
            }
            offset += page.size()
        }
        check(located.isNotEmpty()) { "the platform reports no located in-stock unit for ${store.slug} — refusing to count blind" }
        return StoreStock.fromPlatform(store, located, StoreStock.readLines(StoreStock.missingFile(cfg.stateDir)))
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
        const val LOCATION_PAGE = 5000

        const val LOCATIONS_GQL = "query TwinStockLocations(\$site: uuid!, \$limit: Int!, \$offset: Int!) { " +
            "thing_location(where: {site_id: {_eq: \$site}, item: {state: {_eq: in_stock}}}, " +
            "order_by: {item_id: asc}, limit: \$limit, offset: \$offset) " +
            "{ zone_id item { item_identifiers(where: {identifier_type: {_eq: epc}}) { value } } } }"

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
