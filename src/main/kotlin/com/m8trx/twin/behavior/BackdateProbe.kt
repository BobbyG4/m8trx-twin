package com.m8trx.twin.behavior

import com.m8trx.twin.TwinConfig
import com.m8trx.twin.connect.ConnectConfig
import com.m8trx.twin.connect.WebhookClient
import com.m8trx.twin.connect.WebhookDataType
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.webhook.SaleEvent
import com.m8trx.twin.domain.ObjEviction
import com.m8trx.twin.domain.ObjLocation
import com.m8trx.twin.layer0.NatsEmitter
import com.m8trx.twin.layer0.objEviction
import com.m8trx.twin.layer0.objLocation
import com.m8trx.twin.layer1.BrowseEpisode
import com.m8trx.twin.layer1.ImpressionOracle
import io.nats.client.Nats
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * `./gradlew behaviorBackdateProbe` — TWIN-REQ-007 requirement 9's first action: **re-measure** whether the
 * platform's paths honor a past timestamp, before anything is backfilled.
 *
 * **People plane (needs only the twin edge):** one shopper, one fixture, an 8 s look, every sample's `ts` set
 * [M8TRX_PROBE_DAYS_BACK] days in the past with the internal spacing intact. It listens on the same broker for the
 * edge's own `fixtureImpression` for that object and prints the times the edge stamped beside the times sent.
 * That answers "does the edge's impression rule run on payload time"; whether the persisted `impression_event`
 * and `person_session` rows keep it needs the Bearer read (`impressionVerify`) and is reported as such.
 *
 * **Sales plane (needs the Connect webhook key):** one `sale_event` with `occurred_at` the same number of days
 * back. Without the key it prints the exact bytes it would send and says the measurement waits on the key.
 *
 * Guards: the NATS `server_name` must be `edge-twin-denver`; the office space is refused. Live only with
 * `M8TRX_PROBE_LIVE=true`. The object id and sale id carry `req007-backdate-` so the rows are findable.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.behavior.BackdateProbe")

fun main() {
    val live = System.getenv("M8TRX_PROBE_LIVE")?.toBoolean() == true
    val daysBack = (System.getenv("M8TRX_PROBE_DAYS_BACK") ?: "30").toLong()
    val fixture = System.getenv("M8TRX_PROBE_FIXTURE") ?: "GB-R6-U3"
    val m = BehaviorModel.forSite(System.getenv("M8TRX_BEHAVIOR_SITE") ?: "dec-us-denver")
    val now = System.currentTimeMillis()
    val past = now - Duration.ofDays(daysBack).toMillis()
    val objectId = "req007-backdate-${Instant.ofEpochMilli(now).epochSecond}"
    val samples = BrowseEpisode(m.fixtures).browse(fixture, 8_000, past, Random(m.params.seed))
    val expected = ImpressionOracle().run(m.fixtures, samples)
    log.info(
        "probe object={} fixture={} {} samples, ts {} .. {} ({} days back); oracle expects {} impression(s)",
        objectId,
        fixture,
        samples.size,
        Instant.ofEpochMilli(samples.first().tsMs),
        Instant.ofEpochMilli(samples.last().tsMs),
        daysBack,
        expected.size,
    )

    val epc = EpcPool.load(m, java.nio.file.Path.of(".twin-state/sold-epcs.txt")).take(m.stock.published.cells(fixture).first())
    val sale = SaleEvent.byEpc(
        "req007-backdate-sale-${Instant.ofEpochMilli(now).epochSecond}",
        Instant.ofEpochMilli(past).toString(),
        epc ?: "NO-EPC",
    )
    log.info("sale_event that would be sent: {}", String(ConnectMappers.snake.writeValueAsBytes(sale)))

    if (!live) {
        log.info("DRY-RUN (M8TRX_PROBE_LIVE!=true) — nothing sent.")
        return
    }

    // ── people plane ────────────────────────────────────────────────────────────────────────────────────
    val natsUrl = System.getenv("M8TRX_NATS_URL") ?: error("M8TRX_NATS_URL is not set")
    val spaceId = System.getenv("M8TRX_SPACE_ID") ?: error("M8TRX_SPACE_ID is not set")
    if (spaceId.startsWith("0efb9aaa")) {
        log.error("REFUSING: office space")
        exitProcess(2)
    }
    val conn = Nats.connect(natsUrl)
    if (conn.serverInfo.serverName != "edge-twin-denver") {
        log.error("REFUSING: server_name='{}' — the probe publishes to the twin edge only", conn.serverInfo.serverName)
        conn.close()
        exitProcess(3)
    }
    val seen = CopyOnWriteArrayList<String>()
    val d = conn.createDispatcher { msg ->
        val body = String(msg.data)
        if (body.contains(objectId)) seen += "${msg.subject} ${body.take(900)}"
    }
    listOf("fixtureImpression", "lookingAtFixture", "dwellingNearbyFixture", "personStateChange").forEach {
        d.subscribe("area.*.$it")
        d.subscribe("m8trx.*.*.xovis.$it")
    }
    val nats = NatsEmitter(
        TwinConfig(
            natsUrl,
            "",
            "",
            System.getenv("M8TRX_TENANT_ID") ?: error("M8TRX_TENANT_ID"),
            System.getenv("M8TRX_SITE_ID") ?: error("M8TRX_SITE_ID"),
            spaceId,
        ),
    )
    val wallStart = System.currentTimeMillis()
    samples.forEachIndexed { i, s ->
        val due = wallStart + (s.tsMs - past)
        val sleep = due - System.currentTimeMillis()
        if (sleep > 0) Thread.sleep(sleep)
        nats.objLocation(
            ObjLocation(
                objectId = objectId,
                x = s.p.x,
                y = s.p.y,
                isMale = true,
                hasTag = false,
                viewDirection = s.viewDir?.let {
                    arrayOf(it.x, it.y)
                },
                layoutId = spaceId,
            ),
            ts = s.tsMs,
            id = "$objectId-$i",
        )
    }
    nats.objEviction(ObjEviction(objectId = objectId, layoutId = spaceId), ts = samples.last().tsMs + 1_000, id = "$objectId-evict")
    log.info("sent; waiting 25 s for the edge's output (impression cache expires ~10 s after the last write)")
    Thread.sleep(25_000)
    nats.close()
    conn.close()
    log.info("edge output for {}: {} message(s)", objectId, seen.size)
    seen.forEach { log.info("  {}", it) }
    log.info(
        "sent ts range: {} .. {} (epoch ms {} .. {})",
        Instant.ofEpochMilli(samples.first().tsMs),
        Instant.ofEpochMilli(samples.last().tsMs),
        samples.first().tsMs,
        samples.last().tsMs,
    )
    log.info(
        "PERSISTED times (impression_event.recorded_at/first_look, person_session.entry_time): UNMEASURED — needs the Bearer read (impressionVerify).",
    )

    // ── sales plane ─────────────────────────────────────────────────────────────────────────────────────
    val cfg = ConnectConfig.fromEnv()
    if (cfg.webhookApiKey == null || cfg.tenantId == null || cfg.integrationSlug == null || epc == null) {
        log.info("SALES PLANE: UNMEASURED — no Connect webhook key/tenant/slug in env (or no unsold EPC). Bytes above are what would be sent.")
        return
    }
    val r = WebhookClient(cfg).push(WebhookDataType.SALE_EVENT, sale, WebhookClient.AuthMode.API_KEY)
    log.info("SALES PLANE: sent backdated sale {} → {} (a 200 is a receipt; the stored occurred_at needs a read-back)", sale.externalSaleId, r)
}
