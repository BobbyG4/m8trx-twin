package com.m8trx.twin.storeops

import com.m8trx.twin.connect.ConnectClient
import com.m8trx.twin.connect.ConnectConfig
import com.m8trx.twin.connect.RestockMoves
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.bearer.ReadCaps
import com.m8trx.twin.connect.model.bearer.SpatialIdentityRequest
import com.m8trx.twin.connect.model.bearer.SpatialIdentityResponse
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("com.m8trx.twin.storeops.ReconSend")

/**
 * `./gradlew reconcileSend` - re-sends what the platform missed, through Connect, from the twin-vs-platform differences file.
 *
 * DRY by default: prints counts per site (units, batches, minutes at 8 a minute), the first bodies and every unresolved location or unsourced
 * sale, writes the frozen plan (`<out>/<kind>/plan.jsonl`) and sends NOTHING (no client is built; the identity read is a plain READ and is
 * skipped when `M8TRX_RECON_IDENTITY_DIR` holds saved `<slug>.json` answers). LIVE only with `M8TRX_RECON_LIVE=true` AND
 * `M8TRX_RECON_CONFIRM=<the sites of the run, csv>` (equal to `M8TRX_RECON_SITES`).
 *
 * Env: `M8TRX_RECON_DIFF` (the differences CSV; never copied into the repo) - `M8TRX_RECON_KIND` sales|moves|racks - `M8TRX_RECON_SALES_FROM` in_stock (default)|missing (sales: the platform's state of the unit) - `M8TRX_RECON_PRICED` (sales; default true for `missing`: price each unit as the original sale did, from `M8TRX_RECON_RECEIVED` + `M8TRX_RECON_PRICING_MEMORY`) - `M8TRX_RECON_SITES` (csv; live
 * requires it, dry defaults to every site in the file) - `M8TRX_RECON_STREAM_DIR` (`.twin-state/stream`; READ-ONLY) - `M8TRX_RECON_ALARM_DIR`
 * (sibling `alarm`) - `M8TRX_RECON_SALE_TIMES` (optional `epc,occurred_at` for units with no original sale) - `M8TRX_RECON_SKIP_UNSOURCED`
 * (live sales: send the sourced ones, leave the rest) - `M8TRX_RECON_OUT` (`out/recon`) - `M8TRX_RECON_HOLD` (`/opt/m8trx/twin-ops/recon-HOLD`) -
 * `M8TRX_RECON_CHAIN_DIR` (`reference/data/chain`) - `M8TRX_RECON_STATE_DIR` (`.twin-state`; `moves/placements.csv`) - `M8TRX_RECON_IDENTITY_DIR`.
 * Credentials come from the environment only and are never printed: `M8TRX_TWIN_WEBHOOK_KEY`, `M8TRX_TENANT_ID`,
 * `M8TRX_CONNECT_INTEGRATION_SLUG`, and `M8TRX_TWIN_SERVICE_BEARER` for the `/spatial/identity` read.
 *
 * **Operator:** run it under the shared lock (`flock /opt/m8trx/twin-ops/lock ./gradlew reconcileSend`), never beside the hourly pass or the day
 * step. The stop file ends it between sends (exit 6); the ledger (`<out>/<kind>/sent.log`, ids DELIVERED = answered 2xx) makes a re-run resume; a second run of the same kind is refused by a lock on `sent.log.lock`. "Delivered" is not "applied": the webhook answers 200 and processes async, so an unresolved destination is not reported in the body. Verification is the re-run comparison against the platform, which the operator does after each site; the full body of the first 3 answers per site and of any non-plain answer is logged. A webhook answer
 * that reports an item not accepted HALTS the lane (exit 1, the item stays due). Exit: 0 done, 1 halted, 3 refused, 6 HOLD.
 */
object ReconSendMain {
    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }

    /** Every reason a live run must refuse, all collected. */
    fun liveRefusals(live: Boolean, kind: Recon.Kind, sites: Set<String>, confirm: String?, env: (String) -> String?): List<String> {
        if (!live) return emptyList()
        val r = ArrayList<String>()
        if (sites.isEmpty()) r += "a live run names its sites: M8TRX_RECON_SITES=<csv>"
        val confirmed = confirm?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        if (confirmed !=
            sites
        ) {
            r += "a live run needs the second key M8TRX_RECON_CONFIRM=${sites.sorted().joinToString(",")} (got '${confirm ?: ""}')"
        }
        listOf("M8TRX_TWIN_WEBHOOK_KEY", "M8TRX_TENANT_ID", "M8TRX_CONNECT_INTEGRATION_SLUG").forEach {
            if (env(it).isNullOrBlank()) {
                r +=
                    "$it is not set"
            }
        }
        if (kind != Recon.Kind.SALES &&
            env("M8TRX_TWIN_SERVICE_BEARER").isNullOrBlank()
        ) {
            r += "M8TRX_TWIN_SERVICE_BEARER is not set (the /spatial/identity read)"
        }
        return r
    }

    // Server lane, 2026-11-21: with its move queue full the platform makes the sender wait instead of refusing; stop and say so.
    const val SLOW_ANSWER_MS = 10_000L

    /** Wraps the live sender: after an accepted move, `placements.csv` gets a line only for a unit whose recorded fixture DIFFERS from the destination. */
    class PlacementSender(
        private val inner: OpsSender,
        private val destCodeOf: Map<String, String?>,
        private val placementsFile: Path,
        private val today: LocalDate,
    ) : OpsSender {
        var appended = 0
            private set
        private val placements = HashMap(RestockMoves.readPlacements(placementsFile))

        override fun health() = inner.health()

        override fun dropToken() = inner.dropToken()

        /** Set when one send took longer than [SLOW_ANSWER_MS] to be answered: the server's move queue is full and it is making the sender wait. */
        var slow: String? = null
            private set

        override fun send(item: SendItem): Outcome {
            val t0 = System.currentTimeMillis()
            val o = sendInner(item)
            val ms = System.currentTimeMillis() - t0
            if (ms > SLOW_ANSWER_MS && slow == null) slow = "${item.extId} answered after $ms ms (limit $SLOW_ANSWER_MS ms)"
            return o
        }

        private fun sendInner(item: SendItem): Outcome = inner.send(item).also { o ->
            if (o == Outcome.Accepted && item.kind == "move") {
                val code = destCodeOf[item.extId] ?: return@also
                val differ = item.epcs.filter { placements[it] != null && placements[it] != code }
                if (differ.isNotEmpty()) {
                    RestockMoves.appendPlacements(placementsFile, differ, code, today)
                    differ.forEach { placements.put(it, code) }
                    appended += differ.size
                }
            }
        }
    }

    private fun identityFrom(dir: String?, slug: String, readOk: Boolean): SpatialIdentityResponse? {
        if (dir != null) {
            val f = Path.of(dir).resolve("$slug.json")
            return if (Files.exists(f)) ConnectMappers.snake.readValue(Files.readAllBytes(f), SpatialIdentityResponse::class.java) else null
        }
        if (!readOk) return null
        val cfg = ConnectConfig.fromEnv().copy(serviceBearer = env("M8TRX_TWIN_SERVICE_BEARER"))
        val client = ConnectClient(cfg)
        return when (val r = client.spatialIdentity(SpatialIdentityRequest(siteRef = slug, includeZones = true, limit = ReadCaps.ZONES_MAX))) {
            is ConnectResponse.Ok -> client.mapper.readValue(r.rawBody, SpatialIdentityResponse::class.java)
            is ConnectResponse.Err -> null
        }
    }

    fun run(): Int {
        val live = env("M8TRX_RECON_LIVE")?.toBoolean() == true
        val kind = try {
            if (env("M8TRX_RECON_KIND") == null) Recon.Kind.MOVES else Recon.Kind.parse(env("M8TRX_RECON_KIND"))
        } catch (e: IllegalArgumentException) {
            log.error("REFUSING: {}", e.message)
            return 3
        }
        val diff = env("M8TRX_RECON_DIFF")?.let(Path::of)
        if (diff == null || !Files.exists(diff)) {
            log.error("REFUSING: M8TRX_RECON_DIFF must name the differences CSV (got {})", diff)
            return 3
        }
        val sites = env("M8TRX_RECON_SITES")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        val refusals = liveRefusals(live, kind, sites, env("M8TRX_RECON_CONFIRM"), ::env)
        if (refusals.isNotEmpty()) {
            refusals.forEach { log.error("REFUSING: {}", it) }
            return 3
        }
        val out = Path.of(env("M8TRX_RECON_OUT") ?: "out/recon").resolve(kind.name.lowercase())
        val planFile = out.resolve("plan.jsonl")
        val chainDir = Path.of(env("M8TRX_RECON_CHAIN_DIR") ?: "reference/data/chain")
        val stateDir = Path.of(env("M8TRX_RECON_STATE_DIR") ?: ".twin-state")
        val hold = Path.of(env("M8TRX_RECON_HOLD") ?: "/opt/m8trx/twin-ops/recon-HOLD")

        val from = try {
            Recon.SalesFrom.parse(env("M8TRX_RECON_SALES_FROM"))
        } catch (e: IllegalArgumentException) {
            log.error("REFUSING: {}", e.message)
            return 3
        }
        val priced = env("M8TRX_RECON_PRICED")?.toBoolean() ?: (from == Recon.SalesFrom.MISSING)
        // the default (in_stock, unpriced) keeps its old hash, so an existing frozen plan stays valid
        val hash = Recon.planHash(
            diff,
            kind,
            sites,
            if (kind ==
                Recon.Kind.SALES
            ) {
                (
                    if (from ==
                        Recon.SalesFrom.IN_STOCK
                    ) {
                        ""
                    } else {
                        "|from=${from.platformState}"
                    }
                    ) + (if (priced) "|priced" else "")
            } else {
                ""
            },
        )
        val rows = Recon.select(kind, Recon.readDiff(diff), sites, from)
        log.info(
            "reconcileSend {} {}: {} rows selected{}",
            kind,
            if (live) "LIVE" else "DRY RUN",
            rows.size,
            if (sites.isEmpty()) "" else " in ${sites.sorted()}",
        )

        // ── build the plan ──
        val planned = ArrayList<Recon.Planned>()
        val problems = ArrayList<String>()
        val extra = ArrayList<String>()
        if (kind == Recon.Kind.SALES) {
            val streamDir = Path.of(env("M8TRX_RECON_STREAM_DIR") ?: stateDir.resolve("stream").toString())
            val alarmDir = env("M8TRX_RECON_ALARM_DIR")?.let(Path::of) ?: streamDir.resolveSibling("alarm")
            val prices = if (priced) {
                ReconPrices.load(
                    chainDir,
                    rows.map { it.site }.distinct(),
                    env("M8TRX_RECON_RECEIVED")?.let(Path::of) ?: stateDir.resolve("receive/received-stock.csv"),
                    env("M8TRX_RECON_PRICING_MEMORY")?.let(Path::of) ?: stateDir.resolve("pricing-sent.csv"),
                )
            } else {
                null
            }
            val sp = Recon.planSales(
                rows,
                ReconSaleIndex.load(streamDir, alarmDir),
                Recon.readTimes(env("M8TRX_RECON_SALE_TIMES")?.let(Path::of)),
                prices,
            )
            planned += sp.planned
            sp.causes.forEach { (c, n) -> extra += "cause $c: $n unit(s) - ${c.text}" }
            if (sp.unsourced.isNotEmpty()) {
                extra += "${sp.unsourced.size} unit(s) have NO original sale time and are not planned (give M8TRX_RECON_SALE_TIMES to send them):"
                sp.unsourced.groupBy { it.site to it.cause }.toSortedMap(compareBy({ it.first }, { it.second })).forEach { (k, v) ->
                    extra += "  ${k.first} ${k.second}: ${v.size}"
                }
                if (live && env("M8TRX_RECON_SKIP_UNSOURCED")?.toBoolean() != true) {
                    problems +=
                        "${sp.unsourced.size} unit(s) have no original sale time (set M8TRX_RECON_SKIP_UNSOURCED=true to send only the sourced ones)"
                }
            }
        } else {
            for (slug in rows.map { it.site }.distinct().sorted()) {
                val layoutFile = chainDir.resolve("stores/$slug/layout.json")
                if (!Files.exists(layoutFile)) {
                    problems += "$slug: no layout file $layoutFile"
                    continue
                }
                val identity = identityFrom(env("M8TRX_RECON_IDENTITY_DIR"), slug, readOk = !env("M8TRX_TWIN_SERVICE_BEARER").isNullOrBlank())
                if (identity != null && (identity.sites.size != 1 || identity.sites.single().slug != slug)) {
                    problems +=
                        "$slug: the /spatial/identity answer must hold exactly one site and it must be $slug, got ${identity.sites.map {
                            it.slug
                        }}; refused"
                    continue
                }
                if (identity == null || identity.truncated) {
                    val why = if (identity == null) "could not read /spatial/identity" else "/spatial/identity answered truncated"
                    problems += "$slug: $why (set M8TRX_TWIN_SERVICE_BEARER or M8TRX_RECON_IDENTITY_DIR)"
                    continue
                }
                val mp = Recon.planMoves(
                    kind,
                    slug,
                    rows.filter {
                        it.site == slug
                    },
                    Recon.ZoneResolver.of(ConnectMappers.snake.readTree(layoutFile.toFile()), identity),
                )
                planned += mp.planned
                mp.unresolved.forEach { (loc, n) -> problems += "$slug: location '$loc' does not resolve to a platform zone ($n unit(s)); refused" }
            }
        }

        // ── report ──
        val bySite = planned.groupBy { it.site }
        val order = Recon.siteOrder(bySite.mapValues { it.value.size })
        log.info("plan ({}; {} ms between sends = 8 a minute, at most 200 items; one store at a time, smallest first):", kind, Recon.SPACING_MS)
        order.forEach { s ->
            val items = bySite.getValue(s)
            log.info(
                "  {}: {} units in {} {} -> {} min at 8/min",
                s,
                items.sumOf { it.item.epcs.size },
                items.size,
                if (kind == Recon.Kind.SALES) "sale events" else "batches",
                "%.1f".format(items.size * Recon.SPACING_MS / 60_000.0),
            )
        }
        log.info(
            "  total: {} items, {} units, {} min",
            planned.size,
            planned.sumOf {
                it.item.epcs.size
            },
            "%.1f".format(planned.size * Recon.SPACING_MS / 60_000.0),
        )
        extra.forEach { log.info("  {}", it) }
        problems.forEach { log.warn("  PROBLEM: {}", it) }
        planned.take(3).forEach { log.info("  body: {}", it.item.payload) }

        if (!live) {
            Recon.writePlan(planFile, planned, hash)
            log.info("DRY RUN: nothing sent, no client built; frozen plan written to {}", planFile)
            return 0
        }

        // ── live ──
        if (problems.isNotEmpty()) {
            log.error("REFUSING: {} problem(s) above; nothing is sent", problems.size)
            return 3
        }
        if (Files.exists(planFile) && Recon.readPlanHash(planFile) != hash) {
            log.error(
                "REFUSING: the frozen plan {} was built from a different differences file, kind or sites; delete {} and run a dry run again",
                planFile,
                planFile,
            )
            return 3
        }
        val frozen = if (Files.exists(planFile)) Recon.readPlan(planFile).filter { it.site in sites } else planned.filter { it.site in sites }
        if (!Files.exists(planFile)) Recon.writePlan(planFile, planned, hash)
        if (frozen.isEmpty()) {
            log.error("REFUSING: the plan holds nothing for {}", sites.sorted())
            return 3
        }
        if (Files.exists(hold)) {
            log.error("REFUSING: the stop file {} exists; nothing is sent until it is removed", hold)
            return 6
        }
        Files.createDirectories(out)
        val lockChannel = java.nio.channels.FileChannel.open(
            out.resolve("sent.log.lock"),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
        )
        val lock = try {
            lockChannel.tryLock()
        } catch (e: java.nio.channels.OverlappingFileLockException) {
            null
        }
        if (lock == null) {
            log.error("REFUSING: another reconcile {} run holds {}; two runs of one kind never write together", kind, out.resolve("sent.log.lock"))
            return 3
        }
        val ledger = SentLedger(out.resolve("sent.log"))
        val liveBySite = frozen.groupBy { it.site }
        val liveOrder = Recon.siteOrder(liveBySite.mapValues { it.value.size })
        val destCodes = frozen.associate { it.item.extId to it.destCode }
        val siteOf = frozen.associate { it.item.extId to it.site }
        val shown = HashMap<String, Int>()
        val liveSender = LiveSender.createForRecon(true)
        liveSender.onAnswer = { item, body ->
            val site = siteOf[item.extId] ?: "?"
            val n = (shown[site] ?: 0) + 1
            shown[site] = n
            if (n <= 3 || !WebhookAnswer.isPlain(body)) log.info("webhook answer {} #{} {}: {}", site, n, item.extId, body)
        }
        val sender = PlacementSender(liveSender, destCodes, stateDir.resolve("moves/placements.csv"), LocalDate.now())
        val policy = RetryPolicy()
        for (slug in liveOrder) {
            val items = liveBySite.getValue(slug).map { it.item }
            val pump = Pump(
                items,
                sender,
                policy,
                ledger,
                OrderingGuard(items.flatMap { it.epcs }),
                spacingMs = mapOf("move" to Recon.SPACING_MS, "sale" to Recon.SPACING_MS),
                healthEveryMs = 30_000,
                hold = { Files.exists(hold) },
                onEvent = { log.warn("{}", it) },
            )
            log.info("{}: {} item(s) to send, {} already in the ledger", slug, pump.queue.size, pump.skippedByLedger)
            while (true) {
                when (pump.pump(System.currentTimeMillis())) {
                    Pump.State.IDLE -> break

                    Pump.State.HOLD -> {
                        log.warn("HOLD: stop file {} found; stopped at {}, re-run resumes", hold, slug)
                        return 6
                    }

                    Pump.State.HALTED -> {
                        log.error("HALTED at {}: {}", slug, pump.haltReason)
                        return 1
                    }

                    Pump.State.WAITING -> Thread.sleep(250)
                }
                sender.slow?.let {
                    log.error("HALTED at {}: slow answer, {} - the server's queue is full; tell the server lane, re-run resumes", slug, it)
                    return 1
                }
            }
            log.info("{}: done ({} delivered this run; verify by the re-run comparison against the platform)", slug, pump.queue.acked)
            if (liveOrder.last() != slug) Thread.sleep(Recon.SPACING_MS)
        }
        log.info("reconcileSend {} done; {} placement line(s) appended", kind, sender.appended)
        return 0
    }
}

fun main() {
    exitProcess(ReconSendMain.run())
}
