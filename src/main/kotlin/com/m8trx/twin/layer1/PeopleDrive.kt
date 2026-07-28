package com.m8trx.twin.layer1

import com.m8trx.twin.TwinConfig
import com.m8trx.twin.domain.ObjEviction
import com.m8trx.twin.domain.ObjLocation
import com.m8trx.twin.layer0.NatsEmitter
import com.m8trx.twin.layer0.objEviction
import com.m8trx.twin.layer0.objLocation
import io.nats.client.Nats
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Drive core's real fixture-impression pipeline over NATS — `./gradlew connectPeopleDrive`.
 *
 * Publishes `objLocation` at Xovis fidelity into the **twin edge**, where `XovisImpressionEvaluator` runs
 * genuine point-in-polygon against the live fixture geometry, `ImpressionStateMachine` computes real dwell
 * and look clocks, and `FixtureImpression` is published on cache expiry. No camera, no `xovisIngress` —
 * exactly what a camera's ingress would have produced.
 *
 * **The primary output is not "did rows land" but "did the oracle agree."** [ImpressionOracle] predicts the
 * outcome BEFORE anything is published, and the prediction is printed first so it cannot be retrofitted to
 * the result. Agreement validates the oracle as a local proxy so future iteration needs no edge.
 * Disagreement is worth more than a passing test and must be reported before either side is adjusted.
 *
 * ## Safety interlocks — two edges share host .29
 *
 *  1. **`server_name` assertion.** The connection's advertised name must equal [EXPECTED_SERVER] before a
 *     single event is emitted. `:4223` is `edge-twin-denver`; `:4222` is `edge-itx-office`, the production
 *     office edge running real Xovis hardware. A port check alone is too weak — ports get fat-fingered and
 *     forwarded; server identity does not.
 *  2. **Space denylist.** [OFFICE_SPACE] is refused outright regardless of which edge answered.
 *
 * Both are hard failures, not warnings. Dry-run is the default; `M8TRX_PEOPLE_LIVE=true` fires.
 *
 * Env: `M8TRX_NATS_URL` (required — no default, see [TwinConfig]) · `M8TRX_SPACE_ID` · `M8TRX_SITE_ID` ·
 * `M8TRX_TENANT_ID` · `M8TRX_PEOPLE_STORE` (dec-us-denver) · `M8TRX_PEOPLE_FIXTURE` (default: oracle picks) ·
 * `M8TRX_PEOPLE_DWELL_MS` (12000) · `M8TRX_PEOPLE_HZ` (5.0) · `M8TRX_PEOPLE_NO_VIEWDIR` (false — the
 * required negative-control case) · `M8TRX_PEOPLE_LIVE` (false).
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.layer1.PeopleDrive")

private const val EXPECTED_SERVER = "edge-twin-denver"
private const val OFFICE_SPACE = "0efb9aaa"

fun main() {
    val natsUrl = req("M8TRX_NATS_URL")
    val spaceId = req("M8TRX_SPACE_ID")
    val siteId = req("M8TRX_SITE_ID")
    val tenantId = req("M8TRX_TENANT_ID")
    val storeCode = env("M8TRX_PEOPLE_STORE", "dec-us-denver")
    val dwellMs = env("M8TRX_PEOPLE_DWELL_MS", "12000").toLong()
    val emitHz = env("M8TRX_PEOPLE_HZ", "5.0").toDouble()
    val noViewDir = env("M8TRX_PEOPLE_NO_VIEWDIR", "false").toBoolean()
    val live = env("M8TRX_PEOPLE_LIVE", "false").toBoolean()
    val expectServer = env("M8TRX_EXPECT_SERVER_NAME", EXPECTED_SERVER)

    // ── interlock 2: space denylist (checked before we even dial) ─────────────
    if (spaceId.startsWith(OFFICE_SPACE)) {
        log.error("REFUSING: M8TRX_SPACE_ID={} is the OFFICE space. Real Xovis hardware runs there.", spaceId)
        exitProcess(2)
    }

    val chainDir = env("M8TRX_CHAIN_DIR", "reference/data/chain")
    val fixtures = FixtureSet.load(Path.of(chainDir, "stores", storeCode, "layout.json"))
    val oracle = ImpressionOracle(
        dwellProximityMm = env("M8TRX_DWELL_PROXIMITY_MM", "1000.0").toDouble(),
        goAwayAllowanceMs = env("M8TRX_GO_AWAY_MS", "1000").toLong(),
        lookAwayAllowanceMs = env("M8TRX_LOOK_AWAY_MS", "1000").toLong(),
        millisTillImpression = env("M8TRX_MILLIS_TILL_IMPRESSION", "5000").toLong(),
    )

    val target = System.getenv("M8TRX_PEOPLE_FIXTURE")?.takeIf { it.isNotBlank() }
        ?: fixtures.impressionVisible.first { it.code.startsWith("GF-") || it.code.startsWith("GB-") }.code

    log.info("═══ PeopleDrive ═══")
    log.info("store={} space={} fixtures={} (impression-visible {})", storeCode, spaceId, fixtures.fixtures.size, fixtures.impressionVisible.size)
    log.info("target={} dwell={}ms emitHz={} noViewDir={} live={}", target, dwellMs, emitHz, noViewDir, live)
    log.info(
        "oracle constants: proximity={}mm goAway={}ms lookAway={}ms tillImpression={}ms",
        oracle.dwellProximityMm,
        oracle.goAwayAllowanceMs,
        oracle.lookAwayAllowanceMs,
        oracle.millisTillImpression,
    )

    // ── build the episode on a synthetic timeline, then rebase onto wall clock ─
    val rng = Random(env("M8TRX_PEOPLE_SEED", "42").toLong())
    val episode = BrowseEpisode(fixtures, emitHz = emitHz).browse(target, dwellMs, 0L, rng)
    val samples = if (noViewDir) episode.map { it.copy(viewDir = null) } else episode

    // ── ★ PREDICTION FIRST — printed before publish so it cannot be retrofitted ─
    val predicted = oracle.run(fixtures, samples)
    log.info("")
    log.info("──── ORACLE PREDICTION (recorded BEFORE publish) ────")
    log.info("samples={} span={}ms interval={}ms", samples.size, samples.last().tsMs - samples.first().tsMs, (1000.0 / emitHz).toLong())
    if (predicted.isEmpty()) {
        log.info("PREDICT: NO impressions. Reasons:")
        oracle.explainSilence(fixtures, samples).forEach { log.info("   · {}", it) }
    } else {
        log.info("PREDICT: {} impression(s)", predicted.size)
        predicted.forEach {
            log.info(
                "   · fixture={} dwellWindow={}ms lookWindow={}ms reportedDuration={}ms",
                it.fixtureCode,
                it.lastDwellMs - it.firstDwellMs,
                it.lastLookMs - it.firstLookMs,
                it.durationMs,
            )
        }
    }
    log.info("─────────────────────────────────────────────────────")
    log.info("")

    if (!live) {
        log.info("DRY-RUN (M8TRX_PEOPLE_LIVE!=true) — nothing published. Set M8TRX_PEOPLE_LIVE=true to fire.")
        return
    }

    // ── interlock 1: assert the edge identity BEFORE emitting anything ────────
    val probe = Nats.connect(natsUrl)
    val serverName = probe.serverInfo?.serverName
    log.info("connected: url={} server_name={} version={}", natsUrl, serverName, probe.serverInfo?.version)
    if (serverName != expectServer) {
        probe.close()
        log.error("REFUSING TO PUBLISH: server_name='{}' but expected '{}'.", serverName, expectServer)
        log.error("  :4223 = edge-twin-denver (twin)   :4222 = edge-itx-office (PRODUCTION, real Xovis).")
        log.error("  Publishing synthetic traffic to the office edge would contaminate a result only real hardware can produce.")
        exitProcess(3)
    }
    probe.close()
    log.info("✓ interlock: edge identity confirmed as '{}'", serverName)

    val config = TwinConfig(
        natsUrl = natsUrl,
        restBaseUrl = "",
        serviceBearer = "",
        tenantId = tenantId,
        siteId = siteId,
        spaceId = spaceId,
    )
    val nats = NatsEmitter(config)
    val objectId = env("M8TRX_PEOPLE_OBJECT_ID", "twin-shopper-${Instant.now().epochSecond}")

    // Rebase the synthetic timeline onto wall clock and pace in real time — core's clocks read the
    // envelope `ts`, and real pacing also lets the 10s expireAfterWrite cache behave naturally.
    val t0 = System.currentTimeMillis()
    val stepMs = (1000.0 / emitHz).toLong()
    log.info(
        "publishing {} samples as objectId={} at {} Hz → subject area.{}.objLocation",
        samples.size,
        objectId,
        emitHz,
        spaceId.replace("-", ""),
    )

    samples.forEachIndexed { i, s ->
        val ts = t0 + (s.tsMs - samples.first().tsMs)
        nats.objLocation(
            ObjLocation(
                objectId = objectId,
                x = s.p.x,
                y = s.p.y,
                isMale = true,
                hasTag = false, // must not be true — staff are excluded at the gate
                viewDirection = s.viewDir?.let { arrayOf(it.x, it.y) },
                layoutId = spaceId,
            ),
            ts = ts,
            id = "$objectId-$i",
        )
        if (i % 10 == 0) log.info("  … sample {}/{} t=+{}ms", i, samples.size, ts - t0)
        Thread.sleep(stepMs)
    }

    // Evict so the state machine drops the object cleanly, mirroring a shopper leaving frame.
    nats.objEviction(ObjEviction(objectId = objectId, layoutId = spaceId), ts = System.currentTimeMillis(), id = "$objectId-evict")
    log.info("published {} samples + eviction in {}ms", samples.size, System.currentTimeMillis() - t0)
    nats.close()

    log.info("")
    log.info("★ NOW WAIT ~20s — FixtureImpression sits in a 10s expireAfterWrite cache and publishes ON EXPIRY.")
    log.info("  Then compare the ACTUAL result against the prediction above. Report disagreement before adjusting either side.")
    log.info("  objectId={} (use it to find the impression)", objectId)
}

private fun env(k: String, d: String) = System.getenv(k)?.takeIf { it.isNotBlank() } ?: d
private fun req(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() } ?: error("Required env var $k is not set")
