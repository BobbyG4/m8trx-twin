package com.m8trx.twin.behavior

import com.m8trx.twin.TwinConfig
import com.m8trx.twin.connect.ConnectConfig
import com.m8trx.twin.connect.WebhookClient
import com.m8trx.twin.connect.WebhookDataType
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.webhook.SaleEvent
import com.m8trx.twin.domain.ObjEviction
import com.m8trx.twin.domain.ObjLocation
import com.m8trx.twin.layer0.NatsEmitter
import com.m8trx.twin.layer0.objEviction
import com.m8trx.twin.layer0.objLocation
import com.m8trx.twin.layer1.ImpressionOracle
import io.nats.client.Nats
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.exitProcess

/**
 * `./gradlew behaviorDrive` — drive a slice of a simulated day through the platform's own paths: positions to the
 * **twin edge** over NATS (the edge forms `person_session`, `impression_event` and the heat grid from them), and
 * each buyer's basket to Connect as a `sale_event` at the moment they leave (requirements 2, 3, 8).
 *
 * **Record mode is the default and sends nothing.** It generates the slice, checks the oracle against the
 * agents' own claims, and writes the oracle files a reader can check the platform against:
 * `shoppers.csv`, `visits.csv`, `sales.csv` (with the EPC each line would sell), `fixtures.csv` (expected
 * per-fixture totals), and `summary.txt`.
 *
 * ## Guards (requirement 11)
 *  - The NATS broker's `server_name` must be `edge-twin-denver` (`:4223`). `:4222` is `edge-itx-office`, the
 *    production office edge with real Xovis hardware; publishing there is refused before the first sample.
 *  - The office space id is denylisted, and an office SITE has no layout in the twin dataset, so
 *    [BehaviorModel] refuses it before anything is generated.
 *  - Every wire object id is prefixed with the run tag, and every sale id with it too, so a run is findable and
 *    a re-run with a new tag never collides with an old one.
 *
 * ## Pacing
 * Same rule as `DayDrive`: shopper ARRIVALS are compressed by `M8TRX_BEHAVIOR_COMPRESS`; inside a shopper, sample
 * spacing (≤ 1 s) replays at real time and longer gaps are divided. Wire `ts` is anchored to the planned
 * timeline, so the oracle's prediction stays exact. A sale is sent when its shopper's last sample is due.
 *
 * Env: `M8TRX_BEHAVIOR_SITE` (dec-us-denver) · `M8TRX_BEHAVIOR_DATE` (today at the site) · `M8TRX_BEHAVIOR_FROM_HOUR`
 * / `M8TRX_BEHAVIOR_TO_HOUR` (inclusive store hours; default 14..14) · `M8TRX_BEHAVIOR_SEED` (the file's seed) ·
 * `M8TRX_BEHAVIOR_SCALE` (1.0) · `M8TRX_BEHAVIOR_COMPRESS` (18) · `M8TRX_BEHAVIOR_PLAN` (published|before|after) ·
 * `M8TRX_BEHAVIOR_TAG` · `M8TRX_BEHAVIOR_OUT` · `M8TRX_BEHAVIOR_LIVE` (false). Live adds `M8TRX_NATS_URL`,
 * `M8TRX_TENANT_ID`, `M8TRX_SITE_ID`, `M8TRX_SPACE_ID`, and for sales the Connect webhook vars (`ConnectConfig`).
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.behavior.BehaviorDrive")

private const val EXPECTED_SERVER = "edge-twin-denver"
private const val OFFICE_SPACE = "0efb9aaa"
private const val EPISODE_GAP_MS = 1_000L

fun main() {
    val site = env("M8TRX_BEHAVIOR_SITE", "dec-us-denver")
    val m = BehaviorModel.forSite(site)
    val date = System.getenv("M8TRX_BEHAVIOR_DATE")?.let(LocalDate::parse) ?: LocalDate.now(ZoneId.of(m.params.timezone))
    val hours = env("M8TRX_BEHAVIOR_FROM_HOUR", "14").toInt()..env("M8TRX_BEHAVIOR_TO_HOUR", env("M8TRX_BEHAVIOR_FROM_HOUR", "14")).toInt()
    val seed = System.getenv("M8TRX_BEHAVIOR_SEED")?.toLong() ?: m.params.seed
    val scale = env("M8TRX_BEHAVIOR_SCALE", "1.0").toDouble()
    val compress = env("M8TRX_BEHAVIOR_COMPRESS", "18").toDouble()
    val live = env("M8TRX_BEHAVIOR_LIVE", "false").toBoolean()
    val tag = env("M8TRX_BEHAVIOR_TAG", "req007-$site-$date-h${hours.first}-${hours.last}-s$seed")
    val out = Path.of(env("M8TRX_BEHAVIOR_OUT", "build/behavior/drive-$tag"))
    require(compress >= 1.0) { "M8TRX_BEHAVIOR_COMPRESS must be >= 1" }

    val plan = when (val p = env("M8TRX_BEHAVIOR_PLAN", "published")) {
        "published" -> m.stock.published
        "before" -> m.stock.resetPair(m.params).first
        "after" -> m.stock.resetPair(m.params).second
        else -> error("M8TRX_BEHAVIOR_PLAN must be published|before|after, got '$p'")
    }

    // ── generate + check offline, before anything is sent ──────────────────────────────────────────────
    val sink = MemorySampleSink()
    val run = m.runDay(date, PlanTimeline.single(plan), seed, scale, sink, hours)
    if (run.records.isEmpty()) {
        log.error("no shoppers arrive in store hours {} on {} — nothing to drive", hours, date)
        exitProcess(1)
    }
    val predicted = oracleImpressions(m.fixtures, sink.streams)
    val predictedCount = predicted.values.sumOf { it.size }
    val claimedCount = run.records.sumOf { r -> r.visits.count { it.impression } }
    check(predictedCount == claimedCount) {
        "oracle predicts $predictedCount impressions but the agents claim $claimedCount — refusing to drive a slice the model cannot account for"
    }

    val epcs = EpcPool.load(m, Path.of(env("M8TRX_BEHAVIOR_SOLD_LOG", ".twin-state/sold-epcs.txt")))
    val sales = run.records.filter { it.bought }.map { r -> PlannedSale(r, r.lines.map { epcs.take(it.cell) }) }

    // ── pacing: compress arrivals, keep each shopper's internal spacing ─────────────────────────────────
    val sliceStart = run.records.minOf { it.entryMs }
    val wire = ArrayList<Wire>()
    val exitAt = HashMap<String, Long>()
    sink.streams.forEach { (id, samples) ->
        val ordered = samples.sortedBy { it.tsMs }
        var t = sliceStart + ((ordered.first().tsMs - sliceStart) / compress).toLong()
        wire += Wire(t, id, ordered.first())
        for (i in 1 until ordered.size) {
            val d = ordered[i].tsMs - ordered[i - 1].tsMs
            t += if (d <= EPISODE_GAP_MS) d else (d / compress).toLong().coerceAtLeast(EPISODE_GAP_MS + 1)
            wire += Wire(t, id, ordered[i])
        }
        exitAt[id] = t
    }
    wire.sortBy { it.ts }
    val planEpoch = wire.first().ts
    val spanMs = wire.last().ts - planEpoch

    RecordFiles.write(out, tag, m, run, sales, predictedCount, spanMs, compress)
    log.info(
        "behaviorDrive {} — {} {} h{}..{} seed={} plan={}({}): {} shoppers, {} buyers, {} units, \${} · {} samples · {} impressions expected · wall ~{}s at {}x",
        if (live) "LIVE" else "RECORD",
        site,
        date,
        hours.first,
        hours.last,
        seed,
        plan.name,
        plan.id,
        run.records.size,
        sales.size,
        sales.sumOf { it.epcs.size },
        "%.2f".format(sales.sumOf { it.record.revenueUsd }),
        wire.size,
        predictedCount,
        spanMs / 1000,
        compress,
    )
    log.info("oracle files: {}", out.toAbsolutePath())
    if (!live) {
        log.info("RECORD mode (M8TRX_BEHAVIOR_LIVE!=true) — nothing sent.")
        return
    }

    // ── live: interlocks first ─────────────────────────────────────────────────────────────────────────
    val natsUrl = req("M8TRX_NATS_URL")
    val spaceId = req("M8TRX_SPACE_ID")
    if (spaceId.startsWith(OFFICE_SPACE)) {
        log.error("REFUSING: M8TRX_SPACE_ID={} is the OFFICE space (real Xovis hardware).", spaceId)
        exitProcess(2)
    }
    val serverName = Nats.connect(natsUrl).use { it.serverInfo.serverName }
    if (serverName != EXPECTED_SERVER) {
        log.error("REFUSING TO PUBLISH: server_name='{}' expected '{}'. :4222 is the PRODUCTION office edge.", serverName, EXPECTED_SERVER)
        exitProcess(3)
    }
    log.info("✓ interlock: edge identity '{}'", serverName)

    val connect = ConnectConfig.fromEnv()
    val webhook = if (connect.webhookApiKey != null && connect.tenantId != null && connect.integrationSlug != null) WebhookClient(connect) else null
    if (webhook == null) {
        log.warn("SALES NOT SENT: no Connect webhook key/tenant/slug in env — people plane only. The planned sales are in {}/sales.csv.", out)
    }

    val nats = NatsEmitter(TwinConfig(natsUrl, "", "", req("M8TRX_TENANT_ID"), req("M8TRX_SITE_ID"), spaceId))
    // Every shopper leaves at their last sample: evict then (closing the visit), and send a buyer's sale then.
    val saleOf = sales.associateBy { it.record.id }
    val departures = ArrayDeque(exitAt.entries.sortedWith(compareBy({ it.value }, { it.key })).map { it.key })
    val sent = mutableListOf<String>()
    var saleOk = 0
    var saleErr = 0
    val wireEpoch = System.currentTimeMillis()
    fun wallOf(planTs: Long) = wireEpoch + (planTs - planEpoch)

    fun departUpTo(planTs: Long) {
        while (departures.isNotEmpty() && exitAt.getValue(departures.first()) <= planTs) {
            val id = departures.removeFirst()
            nats.objEviction(ObjEviction(objectId = "$tag-$id", layoutId = spaceId), ts = wallOf(exitAt.getValue(id)), id = "$tag-$id-evict")
            val s = saleOf[id] ?: continue
            if (webhook == null) continue
            val at = Instant.ofEpochMilli(wallOf(exitAt.getValue(id))).toString()
            val epcList = s.epcs.filterNotNull()
            if (epcList.isEmpty()) continue
            val sale = if (epcList.size == 1) SaleEvent.byEpc("$tag-$id", at, epcList[0]) else SaleEvent.byEpcList("$tag-$id", at, epcList)
            when (val r = webhook.push(WebhookDataType.SALE_EVENT, sale, WebhookClient.AuthMode.API_KEY)) {
                is ConnectResponse.Ok -> {
                    saleOk++
                    sent += epcList
                }

                else -> {
                    saleErr++
                    log.warn("sale {} → {}", "$tag-$id", r)
                }
            }
        }
    }

    for (i in wire.indices) {
        val w = wire[i]
        val due = wallOf(w.ts)
        val sleep = due - System.currentTimeMillis()
        if (sleep > 0) Thread.sleep(sleep)
        nats.objLocation(
            ObjLocation(
                objectId = "$tag-${w.id}",
                x = w.s.p.x,
                y = w.s.p.y,
                isMale = true,
                hasTag = false,
                viewDirection = w.s.viewDir?.let {
                    arrayOf(it.x, it.y)
                },
                layoutId = spaceId,
            ),
            ts = due,
            id = "$tag-${w.id}-$i",
        )
        departUpTo(w.ts)
        if (i % 5000 == 0) log.info("  … {}/{} samples, {} sales sent", i, wire.size, saleOk)
    }
    departUpTo(Long.MAX_VALUE)
    nats.close()
    if (sent.isNotEmpty()) epcs.recordSold(sent)
    log.info(
        "published {} samples for {} shoppers; sales sent ok={} err={}{}",
        wire.size,
        sink.streams.size,
        saleOk,
        saleErr,
        if (webhook ==
            null
        ) {
            " (sales path not configured)"
        } else {
            ""
        },
    )
    log.info("★ expected {} impressions under object prefix '{}'. Allow ~20 s for the edge cache to flush.", predictedCount, tag)
}

private data class Wire(val ts: Long, val id: String, val s: ImpressionOracle.Sample)

/** A buyer's basket with the EPC chosen for each line (null where the store has no unsold unit of that SKU). */
data class PlannedSale(val record: ShopperRecord, val epcs: List<String?>)

/**
 * Unsold EPCs per EAN, preferring units the platform has at the fixture the shopper took the item from, so a
 * sale by EPC attributes that fixture platform-side with no new field. Deterministic: EPCs are consumed in file
 * order. Units sold by earlier live runs (the local sold log) are skipped.
 */
class EpcPool(private val byEan: Map<String, ArrayDeque<Pair<String, String>>>, private val soldLog: Path) {
    fun take(c: Cell): String? {
        val q = byEan[c.ean] ?: return null
        val i = q.indexOfFirst { it.second == c.fixture }.takeIf { it >= 0 } ?: 0
        return if (q.isEmpty()) null else q.removeAt(i).first
    }

    fun recordSold(epcs: List<String>) {
        Files.createDirectories(soldLog.parent)
        Files.write(soldLog, epcs, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }

    companion object {
        fun load(m: BehaviorModel, soldLog: Path): EpcPool {
            val sold = if (Files.exists(soldLog)) Files.readAllLines(soldLog).map { it.trim() }.toSet() else emptySet()
            val rows = StoreStock.readCsv(m.stock.storeDir.resolve("epcs.csv"))
            val byEan = rows.filter { it["epc"] !in sold && !it.getValue("fixture").startsWith("BR-") && !it.getValue("fixture").startsWith("RCV") }
                .groupBy { it.getValue("ean") }
                .mapValues { (_, rs) -> ArrayDeque(rs.map { it.getValue("epc") to it.getValue("fixture") }) }
            return EpcPool(byEan, soldLog)
        }
    }
}

/** The record-mode oracle files. */
private object RecordFiles {
    fun write(
        dir: Path,
        tag: String,
        m: BehaviorModel,
        run: BehaviorModel.DayRun,
        sales: List<PlannedSale>,
        impressions: Int,
        spanMs: Long,
        compress: Double,
    ) {
        Files.createDirectories(dir)
        Files.newBufferedWriter(dir.resolve("shoppers.csv")).use { w ->
            w.write("shopper,wire_object_id,persona,plan_id,entry,exit,visits,impressions,pickups,units,revenue_usd\n")
            run.records.forEach { r ->
                w.write(
                    "${r.id},$tag-${r.id},${r.persona},${r.planId},${Instant.ofEpochMilli(
                        r.entryMs,
                    )},${Instant.ofEpochMilli(r.exitMs)},${r.visits.size}," +
                        "${r.visits.count { it.impression }},${r.visits.sumOf { it.pickups.size }},${r.lines.size},${"%.2f".format(r.revenueUsd)}\n",
                )
            }
        }
        Files.newBufferedWriter(dir.resolve("visits.csv")).use { w ->
            w.write("shopper,fixture,kind,start,dwell_ms,impression,picked_up,tried,bought\n")
            run.records.forEach { r ->
                r.visits.forEach { v ->
                    w.write(
                        "${r.id},${v.fixture},${v.kind},${Instant.ofEpochMilli(v.startMs)},${v.dwellMs},${v.impression}," +
                            "${v.pickups.joinToString(";") { it.cell.ean }},${v.pickups.count { it.tried }},${v.pickups.count { it.bought }}\n",
                    )
                }
            }
        }
        Files.newBufferedWriter(dir.resolve("sales.csv")).use { w ->
            w.write("external_sale_id,shopper,fixture,level,ean,item_cd,style,size,price_usd,no_look,epc\n")
            sales.forEach { s ->
                s.record.lines.forEachIndexed { i, l ->
                    val c = l.cell
                    w.write(
                        "$tag-${s.record.id},${s.record.id},${c.fixture},${c.level},${c.ean},${c.itemCd},${c.handle},\"${c.size}\",${c.priceUsd},${l.noLook},${s.epcs[i] ?: ""}\n",
                    )
                }
            }
        }
        Files.newBufferedWriter(dir.resolve("fixtures.csv")).use { w ->
            w.write(
                "fixture,placement,stops,impressions,look_s,platform_duration_s,engagements,try_ons,units,revenue_usd,dwelled,dwelled_and_bought_here\n",
            )
            run.ledger.rows.toSortedMap().forEach { (f, r) ->
                w.write(
                    "$f,${m.world.placement[f] ?: ""},${r.stops},${r.impressions},${r.dwellMs / 1000},${r.platformDurationMs / 1000}," +
                        "${r.engagements},${r.tryOns},${r.units}," +
                        "${"%.2f".format(r.revenueUsd)},${r.dwelled.size},${r.dwelledAndBoughtHere.size}\n",
                )
            }
        }
        val rec = run.reconcile()
        Files.writeString(
            dir.resolve("summary.txt"),
            buildString {
                appendLine("tag=$tag site=${m.site} date=${run.date}")
                appendLine("shoppers=${run.records.size} buyers=${run.transactions} units=${run.units} revenue_usd=${"%.2f".format(run.revenueUsd)}")
                appendLine("expected_impressions=$impressions (oracle = agents' claims)")
                appendLine("sales_lines_without_epc=${sales.sumOf { s -> s.epcs.count { it == null } }}")
                appendLine("wire_span_s=${spanMs / 1000} compress=$compress")
                appendLine("reconciliation (slice — tolerances are for whole days):")
                append(rec.render())
                appendLine(ConnectMappers.camel.writeValueAsString(mapOf("personas" to run.records.groupingBy { it.persona }.eachCount())))
            },
        )
    }
}

private fun env(k: String, d: String) = System.getenv(k)?.takeIf { it.isNotBlank() } ?: d
private fun req(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() } ?: error("Required env var $k is not set")
