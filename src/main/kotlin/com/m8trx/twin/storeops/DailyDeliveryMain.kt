package com.m8trx.twin.storeops

import com.fasterxml.jackson.databind.JsonNode
import com.m8trx.twin.connect.ConnectConfig
import com.m8trx.twin.connect.ReceiveStock
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.history.ChainKeys
import com.m8trx.twin.storeday.StoreState
import com.m8trx.twin.storeday.buildSim
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("com.m8trx.twin.storeops.DailyDelivery")

/**
 * `./gradlew dailyDelivery` - every store-local morning before opening a delivery replaces what the store sold, then the stock is put away
 * and the floor topped up ([DailyDelivery]). DRY by default: per store the order, the units to receive, the requests, the rack put-away and
 * the top-up batches, the estimated minutes and the first bodies; writes `<out>/<slug>/delivery-<date>/` (schedule.jsonl, order.csv, report.md)
 * and sends nothing (no client is built). LIVE only with `M8TRX_DELIVERY_LIVE=true` AND `M8TRX_DELIVERY_CONFIRM=<sites csv>` (equal to `M8TRX_DELIVERY_SITES`).
 *
 * Env: `M8TRX_DELIVERY_SITES` (csv; dry defaults to all ten stores, live requires it) - `M8TRX_DELIVERY_DATE` (store-local date; default today there) -
 * `M8TRX_DELIVERY_OUT` (`out/delivery`) - `M8TRX_STREAM_SOLD_LOG` (`.twin-state/sold-epcs.txt`; its parent is the state dir, as the reset reads it) -
 * `M8TRX_CHAIN_DIR` - `M8TRX_DELIVERY_RECEIVE_LANES` (1, max 4) - `M8TRX_DELIVERY_MOVE_EVERY_S` (7.5, never less) - `M8TRX_DELIVERY_ALLOW_OPEN`
 * (true: a live run may start after the store's opening) - `M8TRX_DELIVERY_HOLD_FILE` (`$M8TRX_OPS_DIR/delivery-HOLD`) - `M8TRX_STORE_OPS_OWNER_FILE` -
 * `M8TRX_RESET_BACKROOM_COVER_DAYS` (7) and `M8TRX_RESET_BACKROOM_CASE_PACK` (1), as the reset reads them - credentials from the environment only.
 *
 * Live sequence per store, one store at a time (so 200 items a minute is the TOTAL): receive lane and put-away lane side by side (a put-away waits for the
 * receive answer that counted its tags), then every put-away is verified through the results route, then the top-up moves, then verified. Exit: 0 done,
 * 1 halted (a refused unit, a warning or failed outcome, a send answered after 10 s, a receive refusal), 3 refused, 6 HOLD.
 * Run it under the shared lock: `flock /opt/m8trx/twin-ops/lock ./gradlew dailyDelivery` (the hourly pass skips while it holds the lock).
 */
object DailyDeliveryMain {
    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }

    /** The delivery runs at most 3 receive lanes: the moves share the platform's worker pool with them. */
    const val MAX_RECEIVE_LANES = 3

    class StoreRun(val slug: String, val plan: DailyDelivery.Plan, val steady: SteadyState.Result, val window: String, val minutes: Double)

    private fun fmt(d: Double) = "%.1f".format(d)

    fun outDir(root: Path, slug: String, date: LocalDate): Path = root.resolve(slug).resolve("delivery-$date")

    /** Builds one store's plan from the ledger files in [stateDir]; never sends. */
    fun build(slug: String, date: LocalDate, chainDir: Path, stateDir: Path, lanes: Int): StoreRun {
        val sim = buildSim(slug)
        val model = sim.model
        val period = sim.periods.forDate(date)
        val inputs = StoreDayReset.loadInputs(chainDir, stateDir, slug)
        val keys = ChainKeys.load(chainDir.resolve(ChainKeys.DEFAULT_PATH.fileName)).store(slug)
        val zoneIds = keys.zones.associate { it.code to it.zoneId }
        val rackDefault = model.stockPlan.filter { it.kind == "backroom" }.minOfOrNull { it.zone } ?: "BR-01"
        val plan = DailyDelivery.plan(slug, date, model, period.required, inputs.ledger.byFixture, inputs.reserved, zoneIds, rackDefault)
        val steady = SteadyState.simulate(model, period.required, date)
        val minutes = plan.estimateMinutes(lanes)
        val open = model.hoursOn(date.dayOfWeek)?.first
        val window = if (open == null) {
            "closed on $date (${date.dayOfWeek}); no opening to beat"
        } else {
            val z = keys.zone
            val openAt = date.atTime(open).atZone(z)
            val latest = openAt.minusSeconds((minutes * 60).toLong() + 15 * 60)
            "opens $open ${z.id}; start by ${latest.toLocalTime().withSecond(0).withNano(0)} local " +
                "(${latest.withZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalTime().withSecond(0).withNano(0)} KST) to finish 15 min before opening"
        }
        return StoreRun(slug, plan, steady, window, minutes)
    }

    fun writePlan(out: Path, r: StoreRun, cover: String) {
        Files.createDirectories(out)
        Files.newBufferedWriter(out.resolve("schedule.jsonl")).use { w ->
            r.plan.items.forEach { i ->
                val n = ConnectMappers.snake.createObjectNode()
                n.put("seq", i.seq)
                n.put("due_offset_ms", i.dueMs)
                n.put("kind", i.kind)
                n.put("route", i.route.label)
                n.put("ext_id", i.extId)
                n.put("units", i.epcs.size)
                n.put("after_local_midnight", false)
                n.set<JsonNode>("payload", i.payload)
                w.write(ConnectMappers.snake.writeValueAsString(n))
                w.write("\n")
            }
        }
        Files.writeString(
            out.resolve("order.csv"),
            "ean,target,on_hand,gap,order_qty\n" + r.plan.order.joinToString("") { "${it.ean},${it.target},${it.onHand},${it.gap},${it.qty}\n" },
        )
        Files.writeString(out.resolve("report.md"), report(r, cover))
    }

    fun report(r: StoreRun, cover: String): String {
        val p = r.plan
        val sb = StringBuilder()
        sb.appendLine("# Daily delivery ${r.slug} ${p.date}")
        sb.appendLine()
        sb.appendLine("- back-room cover: $cover")
        sb.appendLine("- on hand ${p.onHandUnits} units of target ${p.targetUnits}; floor short of plan ${p.floorShort} units")
        sb.appendLine(
            "- order: ${p.order.size} lines, ${p.orderUnits} units to receive in ${p.receiveRequests} request(s) of at most ${DailyDelivery.RECEIVE_CHUNK}",
        )
        sb.appendLine("- put-away: ${p.putAway.size} rack batch(es); top-up: ${p.topUp.size} batch(es), ${p.topUpUnits} units to the floor")
        sb.appendLine(
            "- estimated ${fmt(
                r.minutes,
            )} min (receive lane and put-away side by side, then top-up, 7.5 s a batch of at most 25 = 200 items a minute)",
        )
        sb.appendLine("- window: ${r.window}")
        val s = r.steady
        sb.appendLine(
            "- steady state at target (seeded Poisson, ${s.days} days): ${fmt(s.meanUnits)} units a day in ${fmt(s.meanLines)} lines " +
                "(expected sold ${fmt(s.expectedSoldPerDay)} a day), about ${fmt(s.minutes())} min a day",
        )
        sb.appendLine()
        sb.appendLine("## First bodies")
        listOf(
            "receive" to p.items.firstOrNull { it.kind == "receive" },
            "put-away" to p.items.firstOrNull(DailyDelivery::isPutAway),
            "top-up" to p.items.firstOrNull(DailyDelivery::isTopUp),
        ).forEach { (k, i) -> if (i != null) sb.appendLine("- $k ${i.extId}: ${i.payload.toString().take(400)}") }
        return sb.toString()
    }

    fun run(): Int {
        val live = env("M8TRX_DELIVERY_LIVE")?.toBoolean() == true
        val chainDir = Path.of(env("M8TRX_CHAIN_DIR") ?: "reference/data/chain")
        val stateDir = (env("M8TRX_STREAM_SOLD_LOG")?.let(Path::of) ?: Path.of(".twin-state/sold-epcs.txt")).let {
            it.parent ?: Path.of(".twin-state")
        }
        val outRoot = Path.of(env("M8TRX_DELIVERY_OUT") ?: "out/delivery")
        val sites =
            env("M8TRX_DELIVERY_SITES")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: if (live) emptyList() else StoreState.STORE_SLUGS
        val lanes = env("M8TRX_DELIVERY_RECEIVE_LANES")?.toIntOrNull() ?: 1
        val moveSpacingMs = ((env("M8TRX_DELIVERY_MOVE_EVERY_S")?.toDouble() ?: (DailyDelivery.MOVE_SPACING_MS / 1000.0)) * 1000).toLong()
        val ops = env("M8TRX_OPS_DIR") ?: "/opt/m8trx/twin-ops"
        val ownerFile = Path.of(env("M8TRX_STORE_OPS_OWNER_FILE") ?: "$ops/store-ops-owner.csv")
        if (lanes !in 1..MAX_RECEIVE_LANES) {
            log.error("REFUSING: M8TRX_DELIVERY_RECEIVE_LANES must be 1..{}, got {}", MAX_RECEIVE_LANES, lanes)
            return 3
        }
        val refusals = DeliveryGate.check(
            live,
            sites.toSet(),
            env("M8TRX_DELIVERY_CONFIRM"),
            ::env,
            ownerFile,
            DailyDelivery.MOVE_CHUNK,
            moveSpacingMs,
        )
        if (refusals.isNotEmpty()) {
            refusals.forEach { log.error("REFUSING: {}", it) }
            return 3
        }
        val unknown = sites.filter { it !in StoreState.STORE_SLUGS }
        if (unknown.isNotEmpty()) {
            log.error("REFUSING: unknown store(s) {}", unknown)
            return 3
        }
        val cover = env("M8TRX_RESET_BACKROOM_COVER_DAYS")?.let {
            "$it days, case pack ${env("M8TRX_RESET_BACKROOM_CASE_PACK") ?: "model (6)"}"
        } ?: "NOT SET (M8TRX_RESET_BACKROOM_COVER_DAYS): the stock plan's own back-room rows"
        log.info("dailyDelivery {} {} sites, state={}, back-room cover: {}", if (live) "LIVE" else "DRY RUN", sites.size, stateDir, cover)

        val rows = ArrayList<StoreRun>()
        for (slug in sites) {
            val zone = ChainKeys.load(chainDir.resolve(ChainKeys.DEFAULT_PATH.fileName)).store(slug).zone
            val date = env("M8TRX_DELIVERY_DATE")?.let(LocalDate::parse) ?: LocalDate.now(zone)
            val out = outDir(outRoot, slug, date)
            if (live) {
                val code = DeliveryLiveRun(slug, date, chainDir, stateDir, out, lanes, moveSpacingMs, ops).run()
                if (code != 0) return code
                continue
            }
            val r = build(slug, date, chainDir, stateDir, lanes)
            writePlan(out, r, cover)
            rows += r
            val p = r.plan
            log.info(
                "{} {}: on hand {} of {}, order {} lines / {} units in {} request(s), put-away {} batch(es), top-up {} batch(es) ({} units), {} min; {}",
                slug, date, p.onHandUnits, p.targetUnits, p.order.size, p.orderUnits, p.receiveRequests, p.putAway.size, p.topUp.size, p.topUpUnits,
                fmt(r.minutes), r.window,
            )
            p.items.take(3).forEach { log.info("  body {} {}: {}", it.kind, it.extId, it.payload.toString().take(300)) }
        }
        if (!live) {
            val summary =
                StringBuilder(
                    "slug\tdate\ton_hand\ttarget\torder_lines\tunits\treceive_requests\tputaway_batches\ttopup_batches\tminutes\tsteady_units\tsteady_lines\tsteady_minutes\n",
                )
            rows.forEach {
                val p = it.plan
                summary.append(
                    "${it.slug}\t${p.date}\t${p.onHandUnits}\t${p.targetUnits}\t${p.order.size}\t${p.orderUnits}\t${p.receiveRequests}\t" +
                        "${p.putAway.size}\t" +
                        "${p.topUp.size}\t${fmt(
                            it.minutes,
                        )}\t${fmt(it.steady.meanUnits)}\t${fmt(it.steady.meanLines)}\t${fmt(it.steady.minutes())}\n",
                )
            }
            Files.createDirectories(outRoot)
            Files.writeString(outRoot.resolve("summary.tsv"), summary.toString())
            log.info("DRY RUN: nothing sent, no client built; plans under {}, summary {}", outRoot, outRoot.resolve("summary.tsv"))
        }
        return 0
    }
}

/** One store's live delivery. */
class DeliveryLiveRun(
    private val slug: String,
    private val date: LocalDate,
    private val chainDir: Path,
    private val stateDir: Path,
    private val out: Path,
    private val lanes: Int,
    private val moveSpacingMs: Long,
    private val ops: String,
) {
    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }

    fun run(): Int {
        val holdFile = DeliveryGate.holdFile(::env)
        if (Files.exists(holdFile)) {
            log.error("REFUSING: the stop file {} exists; nothing is sent until it is removed", holdFile)
            return 6
        }
        val keys = ChainKeys.load(chainDir.resolve(ChainKeys.DEFAULT_PATH.fileName)).store(slug)
        val model = buildSim(slug).model
        DeliveryGate.openRefusal(
            slug,
            date,
            model.hoursOn(date.dayOfWeek)?.first,
            keys.zone,
            System.currentTimeMillis(),
            env("M8TRX_DELIVERY_ALLOW_OPEN")?.toBoolean() == true,
        )?.let {
            log.error("REFUSING: {}", it)
            return 3
        }
        val marker = ResetLivePlan.markerFile(out)
        val frozen = Files.exists(marker)
        val cover = env("M8TRX_RESET_BACKROOM_COVER_DAYS") ?: "plan rows"
        val built = if (frozen) null else DailyDeliveryMain.build(slug, date, chainDir, stateDir, lanes)
        val items = built?.plan?.items ?: ResetLivePlan.load(out)
        log.info("dailyDelivery LIVE {} {}: {} plan ({} items), out={}", slug, date, if (frozen) "FROZEN (resuming)" else "fresh", items.size, out)
        val oversize = ResetGate.oversizeReceives(items)
        if (oversize.isNotEmpty()) {
            log.error("REFUSING: receive requests carry at most {} tags; over the limit: {}", ResetGate.MAX_RECEIVE_TAGS, oversize.take(5))
            return 3
        }
        val space = ReceiveStock.readSpaces(stateDir.resolve("receive/stockroom-spaces.csv"))[slug]
        if (space == null) {
            log.error("REFUSING: no stockroom space id for {} in {}", slug, stateDir.resolve("receive/stockroom-spaces.csv"))
            return 3
        }
        val identity = BackroomZones.fetchIdentity(slug)
        val zones = BackroomZones.resolve(identity, space, BackroomZones.readOverride(BackroomZones.overrideFile(stateDir, slug)))
        val missing = BackroomZones.missing(items, zones)
        if (missing.isNotEmpty()) {
            log.error(
                "REFUSING: no stockroom zone id for rack(s) {}; write {} as 'BR-nn,<zone id>' lines",
                missing,
                BackroomZones.overrideFile(stateDir, slug),
            )
            return 3
        }
        val keyed = keys.zones.associate { it.code to FixtureZones.Keyed(it.zoneId, it.name) }
        val zoneProblems = FixtureZones.problems(identity, keys.salesFloorSpaceId, FixtureZones.needed(items, keyed))
        if (zoneProblems.isNotEmpty()) {
            zoneProblems.forEach { log.error("REFUSING: {}", it) }
            return 3
        }
        if (built != null) {
            DailyDeliveryMain.writePlan(out, built, "$cover days")
            ResetLivePlan.writeAtomic(marker, "{\"slug\":\"$slug\",\"date\":\"$date\",\"started\":\"${Instant.now()}\",\"items\":${items.size}}\n")
        }
        val runLog = out.resolve("live-run.log")

        fun event(s: String) {
            log.info("{}", s)
            synchronized(runLog) { Files.writeString(runLog, "${Instant.now()} $s\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND) }
        }
        event("LIVE RUN start $slug $date frozen=$frozen items=${items.size}")

        val zoneCode = keys.zones.associate { it.zoneId to it.code }
        val placements = stateDir.resolve("moves/placements.csv")
        val answers = out.resolve("answers.tsv")
        val verifiedFile = out.resolve("verified.tsv")
        val rackDefault = model.stockPlan.filter { it.kind == "backroom" }.minOfOrNull { it.zone } ?: "BR-01"
        val ledger = SentLedger(ResetLivePlan.sentLedgerFile(out))
        val live = LiveSender.create(true, slug, chainDir, stateDir, zones)
        val sender = DeliverySender(
            live,
            slug,
            answers,
            limiter = RollingItemLimiter(),
            receivesRunning = { items.any { it.kind == "receive" && !ledger.isSent(it.extId) } },
        )
        val verifier = MoveVerifier(
            LiveResults.reader(ConnectConfig.fromEnv()),
            { DeliveryBooks.bookMove(placements, it, zoneCode, date) },
            onEvent = ::event,
            onSettled = { id, how ->
                Files.writeString(verifiedFile, "$id\t$how\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            },
        )
        live.onAnswer = { item, body ->
            val (key, at) = EventKey.of(body)
            sender.appendAnswer(item.extId, key, at)
            if (item.kind == "move") {
                if (key.isBlank()) {
                    verifier.fail("${item.extId}: the webhook answer carries no eventKey (is services #535 deployed?): ${body?.take(200)}")
                } else {
                    verifier.track(item, key)
                }
            }
        }
        // moves accepted by an earlier run but not yet verified there: read them again from their stored keys
        // only a batch the platform CONFIRMED is settled; one listed unconfirmed is read again
        val settled = if (Files.exists(verifiedFile)) {
            Files.readAllLines(verifiedFile).filter { it.substringAfter('\t', "") == "booked" }.map { it.substringBefore('\t') }.toSet()
        } else {
            emptySet()
        }
        val keysByMove = DeliverySender.readKeys(answers)
        items.filter { it.kind == "move" && ledger.isSent(it.extId) && it.extId !in settled }.forEach { m ->
            keysByMove[m.extId]?.takeIf { it.isNotBlank() }?.let { verifier.track(m, it) }
                ?: verifier.fail("${m.extId} was accepted by an earlier run but has no event key in $answers")
        }
        val poller = Thread({
            while (true) {
                try {
                    Thread.sleep(10_000)
                    verifier.poll()
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    event("results read failed (tried again in 10 s): ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }, "delivery-verify").also {
            it.isDaemon = true
            it.start()
        }
        val books = DeliveryBooks(ResetBooks(stateDir, slug, date, zoneCode, "receive-delivery") { ean -> model.homeRack[ean] ?: rackDefault })
        val params = ResetLiveParams(
            receiveLanes = lanes,
            moveSpacingMs = moveSpacingMs,
            directiveSpacingMs = 2_000,
            healthPollMs = 5_000,
            idlePollMs = 1_000,
            midnightMs = 0,
        )

        fun phase(name: String, list: List<SendItem>): ResetLiveRunner.Result {
            event("PHASE $name: ${list.count { !ledger.isSent(it.extId) }} of ${list.size} item(s) to send")
            return ResetLiveRunner(
                list, sender, RetryPolicy(), ledger, books, params,
                { Files.exists(holdFile) || verifier.bad != null || sender.slow != null },
                SystemResetClock, onEvent = ::event,
                onProgress = { p -> ResetLivePlan.writeAtomic(out.resolve("progress.json"), ResetLivePlan.progressJson(p)) },
                lanesNow = { lanes },
            ).run()
        }

        fun halted(r: ResetLiveRunner.Result?): Int? {
            verifier.bad?.let {
                event("HALT (results route): $it")
                return 1
            }
            sender.slow?.let {
                event("HALT slow answer, $it: the server's queue is full; tell the server lane, re-run resumes")
                return 1
            }
            if (r != null && r.end == ResetLiveRunner.End.HALTED) return 1
            if (r != null && r.end == ResetLiveRunner.End.HOLD) return 6
            return null
        }
        try {
            val r1 = phase("1 receive + put-away", items.filter { it.kind == "receive" || DailyDelivery.isPutAway(it) })
            halted(r1)?.let { return it }
            if (!verifier.drain(600_000)) {
                halted(null)?.let { return it }
                event("HALT: ${verifier.pendingCount} put-away batch(es) are not settled on the results route after 10 min; re-run resumes")
                return 1
            }
            halted(null)?.let { return it }
            val (topUps, left) = DailyDelivery.dropUnconfirmed(
                items.filter(DailyDelivery::isTopUp),
                items.filter(DailyDelivery::isPutAway),
                settled + verifier.booked(),
            )
            if (left.isNotEmpty()) event("${left.size} unit(s) left in the stockroom, put-away not confirmed: ${left.take(10)}")
            val r2 = phase("2 top-up", topUps)
            halted(r2)?.let { return it }
            if (!verifier.drain(600_000)) {
                halted(null)?.let { return it }
                event("HALT: ${verifier.pendingCount} top-up batch(es) are not settled on the results route after 10 min; re-run resumes")
                return 1
            }
            halted(null)?.let { return it }
        } finally {
            poller.interrupt()
        }
        val done = ConnectMappers.snake.createObjectNode()
        done.put("slug", slug)
        done.put("date", date.toString())
        done.put("finished", Instant.now().toString())
        done.put("move_batches_booked", verifier.confirmedBatches)
        done.put("units_booked", verifier.confirmedUnits)
        done.putArray("unconfirmed").also { a -> verifier.unconfirmed.forEach { a.add(it) } }
        ResetLivePlan.writeAtomic(
            out.resolve("delivery-complete.json"),
            ConnectMappers.snake.writerWithDefaultPrettyPrinter().writeValueAsString(done) + "\n",
        )
        event(
            "DONE $slug $date: ${verifier.confirmedBatches} move batch(es) / ${verifier.confirmedUnits} unit(s) booked, ${verifier.unconfirmed.size} unconfirmed",
        )
        return 0
    }
}

fun main() {
    exitProcess(DailyDeliveryMain.run())
}
