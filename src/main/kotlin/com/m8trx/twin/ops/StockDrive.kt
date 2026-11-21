package com.m8trx.twin.ops

import com.m8trx.twin.connect.RestockMoves
import com.m8trx.twin.connect.http.ConnectHttp
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.bearer.ItemDetail
import com.m8trx.twin.storeops.ExistenceCheck
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
 * After a live close: the close's `missing` EPCs that the PLATFORM reports `missing` ([MissingFollow], items/details) go to
 * `stock/missing-epcs.txt` (the twin's stock model drops them); each live run first takes off that file what the platform holds
 * `in_stock` again (never a stolen unit). The
 * over units' new rack goes to `moves/placements.csv` (the restock step then sees them where the platform has them).
 */
class StockDrive(
    private val cfg: OpsConfig,
    private val http: ConnectHttp = ConnectHttp(),
    /** The `POST /inventory/items/details` reader (live runs only; built on first use so a dry run needs no bearer). */
    details: () -> (List<String>) -> List<ItemDetail>? = { ExistenceCheck.liveDetails() },
) {
    private val detailsReader: (List<String>) -> List<ItemDetail>? by lazy { details() }
    private val follow: MissingFollow by lazy { MissingFollow(detailsReader, cfg.stateDir) }
    private var lastExceptions: com.fasterxml.jackson.databind.JsonNode? = null
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
        if (cfg.live) {
            // the missing list follows the platform: first take off it what the platform holds in stock again (never the twin's thefts)
            val r = try {
                follow.reconcile()
            } catch (e: Exception) {
                log.error("missing reconcile failed: {}", e.message)
                null
            }
            if (r == null) {
                log.warn("missing reconcile: items/details read failed — the missing list is left as it is")
            } else {
                log.info(
                    "missing reconcile: {} on the list · {} back in stock on the platform and taken off · {} stolen kept · " +
                        "{} sold (left) · {} other · {} request(s)",
                    r.checked,
                    r.removed,
                    r.keptStolen,
                    r.sold,
                    r.other,
                    r.requests,
                )
            }
        }
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
                            val split = follow.closeMissing(p.missing.map { it.epc })
                            if (split == null) {
                                log.error("    items/details read failed — this close's {} missing unit(s) NOT booked", p.missing.size)
                                err++
                            } else {
                                log.info(
                                    "    missing split: {} listed · {} platform-missing (booked) · {} platform-other (kept pending, re-read each run)",
                                    split.listed,
                                    split.platformMissing.size,
                                    split.platformOther,
                                )
                                StoreStock.appendLines(StoreStock.missingFile(cfg.stateDir), split.platformMissing)
                                follow.addPending(split.pending)
                            }
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

    data class TargetedSummary(val stores: Int, val counts: Int, val fixtures: Int, val reads: Int, val closed: Int, val skipped: Int, val err: Int)

    /**
     * Targeted mode (`M8TRX_STOCK_TARGET`, see [StockTargeted]): one count per store over exactly the named fixtures, now. Dry unless
     * `M8TRX_STOCK_LIVE=true`, and then only for the stores in [confirm] (`M8TRX_STOCK_TARGET_CONFIRM`, slugs csv). After a close:
     * the totals, the notYetHeard units (and whether each is a stolen one), the stolen units booked missing only as the platform
     * reports them ([MissingFollow] state), the rest kept pending; `stock/targeted-<ts>.json` records it.
     */
    fun runTargeted(target: Map<String, List<String>>, confirm: Set<String>): TargetedSummary {
        val ts = cfg.now.toString().replace(":", "").replace("-", "").substringBefore(".").substringBefore("Z")
        val stolen = StoreStock.readEpcs(follow.stolenFile())
        var counts = 0
        var fixtures = 0
        var reads = 0
        var closed = 0
        var skipped = 0
        var err = 0
        val shown = ArrayList<String>()
        val report = ArrayList<Map<String, Any?>>()
        for ((slug, codes) in target) {
            val store = cfg.keys.store(slug)
            if (cfg.live && slug !in confirm) {
                log.warn("  {} NOT CONFIRMED — add it to M8TRX_STOCK_TARGET_CONFIRM to send; skipped", slug)
                skipped++
                continue
            }
            try {
                var jwt: String? = null
                val stock = if (cfg.live) {
                    jwt = ops.jwt(cfg.actorEmail(slug))
                    if (jwt == null) {
                        err++
                        log.error("  {} login refused — skipped", slug)
                        continue
                    }
                    platformStock(jwt, store, cfg.siteId(slug))
                } else {
                    StoreStock.load(cfg.chainDir, cfg.stateDir, slug)
                }
                val (plan, empty) = StockTargeted.plan(store, cfg.now, codes, stock, stolen)
                if (empty.isNotEmpty()) log.warn("  {} no unit to read at {} — dropped from the count", slug, empty)
                if (plan == null) {
                    skipped++
                    continue
                }
                counts++
                fixtures += plan.pulls.size
                reads += plan.reads
                log.info(
                    "  {} {} {} — {} fixtures {} · {} reads · pull seconds {} (plateau {})",
                    if (cfg.live) "[LIVE]" else "[DRY]",
                    slug,
                    plan.countId,
                    plan.pulls.size,
                    plan.pulls.joinToString("/") { it.code },
                    plan.reads,
                    StockTargeted.pullSeconds(plan).values.toSortedSet().let { "${it.first()}..${it.last()}" },
                    StockTargeted.WORKED_SECONDS_FLOOR,
                )
                val key = StockTargeted.targetKey(slug, codes)
                val prior = StockTargeted.loadPrior(cfg.stateDir.resolve("stock"), key)
                val accept = System.getenv("M8TRX_STOCK_TARGET_ACCEPT_NONSTOLEN")?.toBooleanStrictOrNull() ?: false
                val gate = StockTargeted.gate(prior, plan.startedAt, cfg.now, accept)
                if (prior != null) {
                    log.info("    previous round of {} closed {} · earliest allowed start (now) {}", slug, prior.closedAt, gate.earliestNow)
                }
                if (gate.refusal != null) {
                    log.warn("  {} REFUSED — {}", slug, gate.refusal)
                    skipped++
                    counts--
                    continue
                }
                if (!cfg.live) {
                    if (shown.isEmpty()) shown += shapeLines(plan)
                    continue
                }
                val ledgerFile = cfg.stateDir.resolve("stock").resolve("stock-targeted.sent")
                val out = sendTargeted(plan)
                StoreStock.appendLines(ledgerFile, listOf("${plan.countId},$slug,target,${out.state}"))
                if (out.err != null) {
                    err++
                    log.error("    COUNT {} ({}) ended {} — {}", plan.countId, slug, out.state, out.err)
                }
                if (out.closedAt != null) closed++
                report += if (out.state.startsWith("closed") && out.err == null) {
                    targetedAfter(slug, plan, stock, stolen, lastExceptions) + targetedEnd(key, out)
                } else {
                    mapOf("slug" to slug, "countId" to plan.countId, "fixtures" to plan.pulls.map { it.code }, "error" to out.err) +
                        targetedEnd(key, out)
                }
                writeTargetedReport(ts, report)
            } catch (e: Exception) {
                err++
                log.error("  {} FAILED — {}: {}", slug, e.javaClass.simpleName, e.message)
            }
        }
        shown.forEach { log.info("  shape: {}", it) }
        log.info(
            "SIM-RUN stock-targeted stores={} counts={} fixtures={} reads={} closed={} skipped={} sent_err={}",
            target.size,
            counts,
            fixtures,
            reads,
            closed,
            skipped,
            err,
        )
        return TargetedSummary(target.size, counts, fixtures, reads, closed, skipped, err)
    }

    private fun targetedEnd(key: String, o: TargetedSend): Map<String, Any?> = mapOf(
        "targetKey" to key,
        "endState" to o.state,
        "closedAt" to o.closedAt?.toString(),
        "framesLanded" to o.framesLanded,
        "framesTotal" to o.framesTotal,
    )

    private fun writeTargetedReport(ts: String, report: List<Map<String, Any?>>) {
        val file = cfg.stateDir.resolve("stock").resolve("targeted-$ts.json")
        java.nio.file.Files.createDirectories(file.parent)
        java.nio.file.Files.writeString(
            file,
            com.m8trx.twin.connect.model.ConnectMappers.camel.writerWithDefaultPrettyPrinter().writeValueAsString(report),
        )
        log.info("targeted result written: {}", file)
    }

    /** What a targeted count ended as: closed | closed_partial | closed_empty | close_failed | open_failed. */
    data class TargetedSend(val err: String?, val state: String, val closedAt: java.time.Instant?, val framesLanded: Int, val framesTotal: Int)

    private fun retryable(r: ConnectResponse): Boolean = r is ConnectResponse.Err && r.error.status.let { it !in 400..499 || it == 408 || it == 429 }

    /** One request, up to [TRIES] attempts: 1 s, 2 s, 4 s, 8 s between them, a 429 waits [OpsClient.RATE_LIMIT_WAIT_MS]; a 4xx other than 408/429 is final. */
    private fun postRetry(jwt: String, method: String, path: String, body: Map<String, Any?>?, what: String, countId: String): ConnectResponse {
        var r = ops.send(jwt, method, path, body)
        var i = 1
        while (r !is ConnectResponse.Ok && retryable(r) && i < TRIES) {
            val wait = if ((r as ConnectResponse.Err).error.status == 429) OpsClient.RATE_LIMIT_WAIT_MS else 1000L shl (i - 1)
            log.warn("    count {} {} failed ({}) — retry {}/{} in {} ms", countId, what, ops.describe(r), i, TRIES - 1, wait)
            Thread.sleep(wait)
            r = ops.send(jwt, method, path, body)
            i++
        }
        return r
    }

    /**
     * A targeted count end to end, never left open: every request retries; if a pull/observation still fails, the count is closed
     * (`route_complete`) as partial when any frame landed, else closed empty (an empty count judges nothing; the twin knows of no
     * discard endpoint). The caller logs the countId loudly whatever the end.
     */
    private fun sendTargeted(p: StockCounts.CountPlan): TargetedSend {
        val total = p.pulls.sumOf { it.frames.size }
        val jwt = ops.jwt(cfg.actorEmail(p.slug)) ?: return TargetedSend("login refused", "open_failed", null, 0, total)
        val siteId = cfg.siteId(p.slug)
        val space = cfg.keys.store(p.slug).salesFloorSpaceId
        var landed = 0

        fun close(state: String, err: String?): TargetedSend {
            lastExceptions = null
            val c = postRetry(jwt, "POST", "/count/${p.countId}/close", mapOf("reason" to "route_complete"), "close", p.countId)
            if (c !is ConnectResponse.Ok) {
                log.error("    COUNT {} IS STILL OPEN — close failed: {}", p.countId, ops.describe(c))
                return TargetedSend("${err ?: "pulls ok"}; close ${ops.describe(c)}", "close_failed", null, landed, total)
            }
            val closedAt = java.time.Instant.now()
            val ex = ops.send(jwt, "GET", "/count/${p.countId}/exceptions")
            if (ex is ConnectResponse.Ok) {
                lastExceptions = ops.json(ex)
                log.info("    closed — reconciled={} totals={}", lastExceptions!!.path("reconciled").asText(), lastExceptions!!.path("totals"))
            } else {
                log.info("    closed — exceptions read {} (the reconcile may still be running)", ops.describe(ex))
            }
            return TargetedSend(err, state, closedAt, landed, total)
        }
        val open = postRetry(jwt, "POST", "/count", openBody(p, siteId, space), "open", p.countId)
        if (open !is ConnectResponse.Ok) {
            // the open may have landed with its answer lost: try to close whatever exists
            log.error("    COUNT {} open failed: {} — trying a close in case it exists", p.countId, ops.describe(open))
            val c = ops.send(jwt, "POST", "/count/${p.countId}/close", mapOf("reason" to "route_complete"))
            return TargetedSend("open ${ops.describe(open)}", if (c is ConnectResponse.Ok) "closed_empty" else "open_failed", null, 0, total)
        }
        if (ops.json(open).path("status").asText() == "closed") return TargetedSend(null, "closed", java.time.Instant.now(), 0, total)
        val gap = System.getenv("M8TRX_STOCK_PULL_GAP_MS")?.toLongOrNull() ?: 60L
        for (pull in p.pulls) {
            for (f in pull.frames) {
                val r = postRetry(jwt, "POST", "/count/pulls", pullBody(p, pull, f, siteId, space), "pull ${pull.code}#${f.seq}", p.countId)
                if (r !is ConnectResponse.Ok) {
                    return close(
                        if (landed >
                            0
                        ) {
                            "closed_partial"
                        } else {
                            "closed_empty"
                        },
                        "pull ${pull.code}#${f.seq} ${ops.describe(r)} after $TRIES tries",
                    )
                }
                landed++
                if (gap > 0) Thread.sleep(gap)
            }
        }
        for (o in p.observations) {
            val r = postRetry(jwt, "POST", "/stocktake/sessions/${p.countId}/observations", observationBody(o), "observation ${o.code}", p.countId)
            if (r !is ConnectResponse.Ok) return close("closed_partial", "observation ${o.code} ${ops.describe(r)} after $TRIES tries")
        }
        return close("closed", null)
    }

    private fun targetedAfter(
        slug: String,
        plan: StockCounts.CountPlan,
        stock: StoreStock,
        stolen: Set<String>,
        ex: com.fasterxml.jackson.databind.JsonNode?,
    ): Map<String, Any?> {
        val res = ex?.let { StockTargeted.readResult(it) }
        // units the count expected at its fixtures: what the platform holds there plus the stolen ones (they are on the missing list, so not in stock)
        val codes = plan.pulls.map { it.code }.toSet()
        val atFixtures = codes.flatMap { stock.byFixture[it].orEmpty() }
        val rows = (atFixtures + stolen).distinct().chunked(MissingFollow.CHUNK).flatMap { detailsReader(it) ?: emptyList() }
        val epcOfItem = rows.filter { it.itemId != null }.associate { it.itemId!! to it.epc.uppercase() }
        val stateOf = rows.filter { it.itemId != null }.associate { it.epc.uppercase() to it.state }
        val nyh = res?.entries.orEmpty().mapNotNull { e -> (e.epc ?: e.itemId?.let { epcOfItem[it] })?.uppercase()?.let { it to e } }
        // a stolen unit the platform now holds as missing is booked (the platform's word, per MissingFollow); one this count heard
        // nothing of but the platform still holds in stock stays pending until it says missing
        val booked = stolen.filter { stateOf[it] == "missing" }.filter { it !in StoreStock.readEpcs(StoreStock.missingFile(cfg.stateDir)) }
        StoreStock.appendLines(StoreStock.missingFile(cfg.stateDir), booked)
        follow.addPending(nyh.map { it.first }.filter { it in stolen && stateOf[it] == "in_stock" })
        log.info(
            "    totals: missing={} notYetHeard={} misplaced={} · notYetHeard EPCs {} ({} stolen) · stolen now platform-missing {}",
            res?.missing,
            res?.notYetHeard,
            res?.misplaced,
            nyh.size,
            nyh.count { it.first in stolen },
            booked.size,
        )
        nyh.forEach { (epc, e) -> log.info("      notYetHeard {} stolen={} reason={} zone={}", epc, epc in stolen, e.reason, e.zoneId) }
        nyh.filter { it.first !in stolen }.takeIf { it.isNotEmpty() }?.let { ns ->
            log.warn(
                "    {} NON-STOLEN notYetHeard unit(s) {} — round 2 for {} is refused until M8TRX_STOCK_TARGET_ACCEPT_NONSTOLEN=true",
                ns.size,
                ns.map { it.first },
                slug,
            )
        }
        return mapOf(
            "slug" to slug,
            "countId" to plan.countId,
            "fixtures" to plan.pulls.map { it.code },
            "reads" to plan.reads,
            "pullSeconds" to StockTargeted.pullSeconds(plan),
            "totals" to res?.totals,
            "missing" to res?.missing,
            "notYetHeard" to res?.notYetHeard,
            "misplaced" to res?.misplaced,
            "notYetHeardUnits" to
                nyh.map { (epc, e) ->
                    mapOf(
                        "epc" to epc,
                        "itemId" to e.itemId,
                        "zoneId" to e.zoneId,
                        "reason" to e.reason,
                        "stolen" to (epc in stolen),
                    )
                },
            "stolenBookedMissing" to booked,
            "exceptions" to ex,
        )
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
        lastExceptions = null
        val close = ops.send(jwt, "POST", "/count/${p.countId}/close", mapOf("reason" to "route_complete"))
        if (close !is ConnectResponse.Ok) return "close ${ops.describe(close)}"
        val ex = ops.send(jwt, "GET", "/count/${p.countId}/exceptions")
        if (ex is ConnectResponse.Ok) {
            val n = ops.json(ex)
            lastExceptions = n
            log.info("    closed — reconciled={} totals={}", n.path("reconciled").asText(), n.path("totals"))
        } else {
            log.info("    closed — exceptions read {} (the reconcile may still be running)", ops.describe(ex))
        }
        return null
    }

    /** The platform's stock of [slug] for a caller outside the drive (the reset's spot count): logs in as the store's manager, then reads. */
    fun platformStockFor(slug: String): StoreStock {
        val email = cfg.actorEmail(slug)
        val jwt = ops.jwt(email) ?: error("login refused for $email")
        return platformStock(jwt, cfg.keys.store(slug), cfg.siteId(slug))
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
            val page = ops.query(
                jwt,
                LOCATIONS_GQL,
                mapOf(
                    "site" to siteId,
                    "limit" to LOCATION_PAGE,
                    "offset" to offset,
                    "st" to "in_stock",
                    "idt" to "epc",
                ),
            ).path("thing_location")
            if (page.size() == 0) break
            page.forEach { row ->
                val zone = row.path("zone_id").asText("").ifBlank { null } ?: return@forEach
                row.path("item").path("item_identifiers").forEach { located += StoreStock.Located(zone, it.path("value").asText()) }
            }
            offset += page.size()
        }
        check(located.isNotEmpty()) { "the platform reports no located in-stock unit for ${store.slug} — refusing to count blind" }
        return StoreStock.fromPlatform(store, located, StoreStock.readEpcs(StoreStock.missingFile(cfg.stateDir)))
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
        const val TRIES = 5

        // item_state / identifier_type are custom scalars in the v2 schema: the values go in as variables, never as bare literals
        const val LOCATIONS_GQL =
            "query TwinStockLocations(\$site: uuid!, \$limit: Int!, \$offset: Int!, \$st: item_state!, \$idt: identifier_type!) { " +
                "thing_location(where: {site_id: {_eq: \$site}, item: {state: {_eq: \$st}}}, " +
                // item_id is thing_location's primary key, so it alone is a total order; zone_id is a belt-and-braces second key
                "order_by: [{item_id: asc}, {zone_id: asc}], limit: \$limit, offset: \$offset) " +
                "{ zone_id item { item_identifiers(where: {identifier_type: {_eq: \$idt}}) { value } } } }"

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
    System.getenv("M8TRX_STOCK_TARGET")?.takeIf { it.isNotBlank() }?.let { spec ->
        val confirm = (System.getenv("M8TRX_STOCK_TARGET_CONFIRM") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val s = StockDrive(cfg).runTargeted(StockTargeted.parse(spec), confirm)
        if (cfg.live && s.err > 0) error("a targeted count was refused — see the log")
        return
    }
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
