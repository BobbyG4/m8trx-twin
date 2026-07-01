package com.m8trx.twin.connect

import com.m8trx.twin.connect.sim.FullLoopDriver
import com.m8trx.twin.connect.sim.RemediateMode
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Full-loop drive (CORE-REQ-005 part 1) — composes the built P0 drivers into ONE parameterized run of the
 * path-(b) demo-nucleus loop:
 *
 *   (opt) publish directive → drift (sale_event) → assert SOLD (items/details)
 *       → remediate (inventory_movement relocate and/or data-plane scan) → assert present (items/details)
 *       → report the per-target compliance expectation.
 *
 * Assertion split (S198 read-back 403): twin asserts the INPUTS over Connect; the compliance OUTPUT is
 * verified by the paired backend session in the synchronized smoke — the report is that session's oracle.
 *
 * SAFE BY DEFAULT — dry-run: builds + logs the whole plan + expectation, sends nothing. `M8TRX_LOOP_LIVE=true`
 * drives it (needs `.env`: `M8TRX_TWIN_WEBHOOK_KEY` for drift/movement/directive, `M8TRX_TWIN_BEARER` for
 * scans + items/details). Re-runnable per surface smoke + at scale (multi-store, many-target).
 *
 * Env: M8TRX_LOOP_STORES (default dec-us-denver; comma list), M8TRX_LOOP_FIXTURES (default GB-R3-U1; ALL/empty
 * = every fixture), M8TRX_LOOP_DRIFT (per-target sales, default 2), M8TRX_LOOP_DEPLETE (sell all on-floor,
 * default false), M8TRX_LOOP_REMEDIATE (none|scan|movement|both, default both), M8TRX_LOOP_REMEDIATE_TO_REQUIRED
 * (relocate enough to restore to required_qty, default true), M8TRX_LOOP_PUBLISH_DIRECTIVE (default false),
 * M8TRX_LOOP_DURATION_SEC (pacing window, default 60), M8TRX_LOOP_READ_SIZE (scan reads, default 25),
 * M8TRX_LOOP_SEED / M8TRX_STREAM_SEED, M8TRX_RUN_ID, M8TRX_LOOP_AUTH (api_key|hmac for drift/movement, default
 * api_key), M8TRX_LOOP_DIRECTIVE_AUTH (default hmac), M8TRX_LOOP_LIVE (false), M8TRX_CHAIN_DIR,
 * M8TRX_STREAM_SOLD_LOG. Run: `./gradlew connectFullLoop`.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ConnectFullLoop")

fun main() {
    val config = ConnectConfig.fromEnv()
    val chainDir = Path.of(env("M8TRX_CHAIN_DIR", "reference/data/chain"))
    val soldLog = Path.of(env("M8TRX_STREAM_SOLD_LOG", ".twin-state/sold-epcs.txt"))
    val live = System.getenv("M8TRX_LOOP_LIVE")?.toBooleanStrictOrNull() ?: false

    val fixturesRaw = env("M8TRX_LOOP_FIXTURES", "GB-R3-U1")
    val fixtures = if (fixturesRaw.equals("ALL", ignoreCase = true)) {
        emptySet()
    } else {
        fixturesRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
    val seed = System.getenv("M8TRX_LOOP_SEED")?.toLongOrNull()
        ?: System.getenv("M8TRX_STREAM_SEED")?.toLongOrNull()
        ?: System.currentTimeMillis()

    val params = FullLoopDriver.LoopParams(
        stores = env("M8TRX_LOOP_STORES", "dec-us-denver").split(",").map { it.trim() }.filter { it.isNotEmpty() },
        fixtures = fixtures,
        driftPerTarget = envInt("M8TRX_LOOP_DRIFT", 2),
        driftDeplete = System.getenv("M8TRX_LOOP_DEPLETE")?.toBooleanStrictOrNull() ?: false,
        remediate = parseRemediate(env("M8TRX_LOOP_REMEDIATE", "both")),
        remediateToRequired = System.getenv("M8TRX_LOOP_REMEDIATE_TO_REQUIRED")?.toBooleanStrictOrNull() ?: true,
        publishDirective = System.getenv("M8TRX_LOOP_PUBLISH_DIRECTIVE")?.toBooleanStrictOrNull() ?: false,
        durationSec = envInt("M8TRX_LOOP_DURATION_SEC", 60),
        readSize = envInt("M8TRX_LOOP_READ_SIZE", 25),
        seed = seed,
        runId = env("M8TRX_RUN_ID", "twin-loop-$seed"),
        webhookAuth = if (env("M8TRX_LOOP_AUTH", "api_key").equals("hmac", ignoreCase = true)) {
            WebhookClient.AuthMode.HMAC
        } else {
            WebhookClient.AuthMode.API_KEY
        },
        directiveAuth = if (env("M8TRX_LOOP_DIRECTIVE_AUTH", "hmac").equals("api_key", ignoreCase = true)) {
            WebhookClient.AuthMode.API_KEY
        } else {
            WebhookClient.AuthMode.HMAC
        },
    )

    val result = FullLoopDriver(config, chainDir, soldLog).run(params, live)

    val mode = if (live) "LIVE" else "DRY-RUN"
    log.info(
        "Full loop done ({}) — sales {}/{} ok, sold-verified {}, movements {} ({} units, {} verified), scans {}{}",
        mode,
        result.salesOk,
        result.salesFired,
        result.soldVerified,
        result.movementsFired,
        result.unitsRelocated,
        result.relocatedVerified,
        result.scansFired,
        if (result.warnings.isNotEmpty()) " — ${result.warnings.size} warning(s)" else "",
    )
    if (live && result.salesFired > 0 && result.salesOk == 0) {
        error("all ${result.salesFired} drift sale(s) failed — see logs")
    }
}

private fun parseRemediate(raw: String): RemediateMode = when (raw.trim().lowercase()) {
    "none" -> RemediateMode.NONE
    "scan" -> RemediateMode.SCAN
    "movement" -> RemediateMode.MOVEMENT
    else -> RemediateMode.BOTH
}

private fun env(key: String, default: String) = System.getenv(key)?.takeIf { it.isNotBlank() } ?: default

private fun envInt(key: String, default: Int) = System.getenv(key)?.toIntOrNull() ?: default
