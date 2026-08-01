package com.m8trx.twin.connect

import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.bearer.ItemDetailsRequest
import com.m8trx.twin.connect.model.webhook.AlarmEnvelope
import com.m8trx.twin.connect.sim.AlarmDriver
import com.m8trx.twin.layer2.EasTagging
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.random.Random

/**
 * `./gradlew connectAlarmDrive` — **the §A acceptance run**: twin as a third-party EAS gate vendor,
 * driving the alarm chain M8TRX has never had anything traverse.
 *
 * ```
 * external source → alert row → routed to an LP role → visible on /alerts → dispositioned
 * ```
 *
 * ## What this run actually proves, and what it cannot
 *
 * The send is twin's. **Every step after it is observed through a documented diagnostic, and twin's
 * key holds the capability for none of them** — measured before the first alarm, not inherited:
 *
 * ```
 * POST /alerts/query            403 PERMISSION_DENIED   (needs alert:read — no key holds it)
 * GET  /integrations/{id}/dead-letter  403 PERMISSION_DENIED   (needs integration:manage — revoked from twin's key)
 * GET  /integrations/{id}/health       403 PERMISSION_DENIED   (same)
 * POST /alerts                  403 CONNECT_NOT_EXPOSED (the Bearer ack endpoint, not yet Connect-exposed)
 * ```
 *
 * So §8.1's own instruction — *"poll the §7 DLQ rather than assuming a 200 meant it landed"* — names
 * a diagnostic the vendor it was written for cannot call. That is the finding this run is for, and it
 * is why the verdict below can honestly read **SENT, UNPROVEN**. Twin does not close the gap with a
 * `psql` it neither holds nor could justify: a verification a vendor cannot perform says nothing
 * about whether the contract works for a vendor.
 *
 * The run re-probes all four on every execution, so the moment an administrator lands the grant the
 * same command upgrades from *unproven* to *proven* with no code change.
 *
 * ## The three sends
 *
 * 1. **A1** — the published §8.1 shape (source detail top-level), as a vendor reading the contract
 *    would author it.
 * 2. **A1 repeat** — byte-identical. Dedupe must collapse it; two rows would be a finding.
 * 3. **A2** — different `dedupe_key`, same kind, in the `DESIGN` §2 shape (detail nested under
 *    `payload`). The two documents disagree, so twin sends both rather than picking one.
 *
 * Every fact in the alarm is real: the gate is `CS-01` read from Denver's own layout (`eas_gate`
 * crossing slice, real SRF geometry), the EPC is a live Denver unit whose state is checked through
 * `items/details` first, and the SKU is premium-and-concealable by [EasTagging]'s rule. The one
 * twin-side fact is **which units carry a tag**, which is the vendor's domain by construction — the
 * platform neither stores it nor could verify it, and that is what makes the emulation faithful.
 *
 * ## Env
 *
 * `M8TRX_ALARM_LIVE=true` to send (default = dry-run, prints the exact bytes) ·
 * `M8TRX_ALARM_SOURCE` (default `twin-eas`, the registered `alert_source`) ·
 * `M8TRX_ALARM_SITE` (default `dec-us-denver`) · `M8TRX_ALARM_SEED` (default 4242) ·
 * `M8TRX_ALARM_INTEGRATION_ID` (for the DLQ/health probes) · `M8TRX_CHAIN_DIR`.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ConnectAlarmDrive")

private const val DEFAULT_INTEGRATION_ID = "5dfba5cd-fd74-4fb8-9c73-2a495419f863"

fun main() {
    val config = ConnectConfig.fromEnv()
    val client = ConnectClient(config)
    val driver = AlarmDriver(WebhookClient(config), client)

    val live = System.getenv("M8TRX_ALARM_LIVE")?.toBoolean() == true
    val source = System.getenv("M8TRX_ALARM_SOURCE") ?: "twin-eas"
    val siteSlug = System.getenv("M8TRX_ALARM_SITE") ?: "dec-us-denver"
    val seed = System.getenv("M8TRX_ALARM_SEED")?.toLongOrNull() ?: 4242L
    val integrationId = System.getenv("M8TRX_ALARM_INTEGRATION_ID") ?: DEFAULT_INTEGRATION_ID
    val chainDir = Path.of(System.getenv("M8TRX_CHAIN_DIR") ?: "reference/data/chain")
    val storeDir = chainDir.resolve("stores").resolve(siteSlug)

    log.info("═══ CONNECT §A ALARM CHAIN — twin as a third-party EAS vendor ═══")
    log.info("source={} site={} mode={}", source, siteSlug, if (live) "LIVE" else "DRY-RUN")

    // ── the gate, read from the store's own layout rather than assumed ────────────────────────────
    val gate = EasTagging.loadGates(storeDir.resolve("layout.json")).firstOrNull()
        ?: error("no eas_gate crossing slice in $siteSlug's layout — absence stays absence, no synthetic gate is invented")
    log.info("gate {} \"{}\" — zone_code={} y={} x={}..{}", gate.code, gate.name, gate.zoneCode, gate.y, gate.xStart, gate.xEnd)

    // ── the subject: a real EPC of stock a vendor would have tagged ───────────────────────────────
    val tagged = taggedEpcs(storeDir)
    check(tagged.isNotEmpty()) { "no EAS-taggable EPCs at $siteSlug — EasTagging's rule yields nothing, which is itself a finding" }
    val rng = Random(seed)
    val subject = tagged[rng.nextInt(tagged.size)]
    val second = tagged[rng.nextInt(tagged.size)]
    log.info("subject EPC {} — {} at USD {} on fixture {}", subject.epc, subject.name, subject.priceUsd, subject.fixture)

    // ── pre-flight: what can this vendor observe BEFORE it sends anything? ─────────────────────────
    log.info("── pre-flight: the documented diagnostics, measured not inherited ──")
    val before = driver.verify(integrationId, source, siteSlug, Instant.now().minusSeconds(3600))
    reportDiagnostics(before)
    if (live) {
        when (val d = client.itemDetails(ItemDetailsRequest(listOf(subject.epc)))) {
            is ConnectResponse.Ok -> log.info("  [ok] items/details — subject EPC resolves server-side: {}", d.rawBody.take(220))
            is ConnectResponse.Err -> log.warn("  [!!] items/details {} {} — subject EPC did not resolve", d.error.status, d.error.code)
        }
    }

    // ── the three sends ───────────────────────────────────────────────────────────────────────────
    val occurredAt = System.currentTimeMillis()
    val a1 = driver.gateExitUnpaid(source, siteSlug, gate.code, subject.epc, occurredAt)
    val a2 = driver.gateExitUnpaidDesignShape(source, siteSlug, gate.code, second.epc, occurredAt + 7_000)

    log.info("── A1: the published §8.1 shape (source detail top-level) ──")
    log.info("{}", driver.dryRun(a1))
    log.info("── A2: the DESIGN §2 shape (source detail nested under payload) ──")
    log.info("{}", driver.dryRun(a2))

    if (!live) {
        log.info("")
        log.info("DRY-RUN — nothing sent. Re-run with M8TRX_ALARM_LIVE=true to drive the chain.")
        return
    }

    val sends = listOf(
        "A1 (§8.1 shape)" to driver.drive(a1),
        "A1 repeat (byte-identical — dedupe must collapse it)" to driver.drive(a1),
        "A2 (§2 shape, distinct dedupe_key)" to driver.drive(a2),
    )

    // Async ingest: the ack returned before the ingester ran, so give it a beat before looking.
    Thread.sleep(8_000)

    log.info("── post-send: the same diagnostics, re-probed ──")
    val after = driver.verify(integrationId, source, siteSlug, Instant.ofEpochMilli(occurredAt).minusSeconds(120))
    reportDiagnostics(after)

    verdict(sends, after, a1, a2)
}

private fun reportDiagnostics(v: AlarmDriver.VerifyAttempt) {
    fun line(name: String, r: ConnectResponse?) = when (r) {
        null -> log.warn("  [--] {} — not attempted", name)
        is ConnectResponse.Ok -> log.info("  [ok] {} — {}", name, r.rawBody.take(400))
        is ConnectResponse.Err -> log.warn("  [{}] {} — {} {}", r.error.status, name, r.error.code, r.error.message)
    }
    line("POST /alerts/query (alert:read)", v.alertsQuery)
    line("GET  /integrations/{id}/dead-letter (integration:manage)", v.deadLetter)
    line("GET  /integrations/{id}/health (integration:manage)", v.health)
    v.error?.let { log.warn("  [!!] verification threw: {}", it) }
}

/**
 * The chain, step by step, with the stopping point named. A partial traverse with a precise stopping
 * point is worth more than a narrative — and an unobservable step is reported as **UNPROVEN**, never
 * as a pass inferred from a `200`.
 */
private fun verdict(sends: List<Pair<String, ConnectResponse>>, after: AlarmDriver.VerifyAttempt, a1: AlarmEnvelope, a2: AlarmEnvelope) {
    log.info("")
    log.info("═══ §A CHAIN VERDICT ═══")
    sends.forEach { (name, r) ->
        val mark = if (r.isOk) "SENT" else "REFUSED"
        log.info("  [{}] {} → {} {}", mark, name, r.status, r.rawBody.take(160))
    }
    log.info("  · A1 dedupe_key = {}", a1.dedupeKey)
    log.info("  · A2 dedupe_key = {}", a2.dedupeKey)

    val observable = after.anyObservable
    log.info("")
    log.info("  step 1 · external source → M8TRX ......... {}", if (sends.all { it.second.isOk }) "ACCEPTED (receipt only)" else "REFUSED")
    log.info("  step 2 · alert row created ............... {}", if (observable) "see /alerts/query above" else "UNPROVEN — no reachable diagnostic")
    log.info("  step 3 · dedupe collapsed the repeat ..... {}", if (observable) "see count above" else "UNPROVEN — needs the row count")
    log.info("  step 4 · routed to an LP role ............ {}", if (observable) "see assigned_to_role above" else "UNPROVEN")
    log.info(
        "  step 5 · visible on /alerts .............. {}",
        if (observable) "reachable" else "UNREACHABLE — 403 PERMISSION_DENIED, no key holds alert:read",
    )
    log.info("  step 6 · dispositioned ................... NOT ATTEMPTED — acknowledge/resolve are not Connect-exposed to a service key")
    if (!observable) {
        log.warn("")
        log.warn("  ⚠ VERDICT: SENT, UNPROVEN. Every documented diagnostic refused this key, including the")
        log.warn("    §7 DLQ that §8.1 itself tells a vendor to poll instead of trusting a 200. The chain")
        log.warn("    beyond ingest is not observable from outside the building, which is the finding —")
        log.warn("    not a reason to substitute a database query a vendor could never run.")
    }
}

private data class TaggedUnit(val epc: String, val ean: String, val name: String, val priceUsd: Double, val fixture: String)

/**
 * Join the store's own `epcs.csv` to `assortment.csv` and keep what [EasTagging]'s rule would have
 * tagged. Deriving it beats hard-coding an EPC: the rule re-derives itself when the catalog is
 * rebuilt, which is exactly why the dead "W-series watches" anchor was replaced by a predicate.
 */
private fun taggedEpcs(storeDir: Path): List<TaggedUnit> {
    val assortment = readCsv(storeDir.resolve("assortment.csv")).associateBy { it["ean"] ?: "" }
    return readCsv(storeDir.resolve("epcs.csv")).mapNotNull { row ->
        val p = assortment[row["ean"]] ?: return@mapNotNull null
        val price = p["price_usd"]?.toDoubleOrNull() ?: return@mapNotNull null
        val category = p["category"] ?: return@mapNotNull null
        if (!EasTagging.isTagged(price, category)) return@mapNotNull null
        TaggedUnit(row["epc"] ?: return@mapNotNull null, row["ean"] ?: "", p["name_en"] ?: "", price, row["fixture"] ?: "")
    }
}

/** Minimal CSV reader — quoted fields included, because product names carry commas. */
private fun readCsv(path: Path): List<Map<String, String>> {
    val lines = Files.readAllLines(path)
    if (lines.isEmpty()) return emptyList()
    val header = splitCsv(lines.first())
    return lines.drop(1).filter { it.isNotBlank() }.map { header.zip(splitCsv(it)).toMap() }
}

private fun splitCsv(line: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    for (ch in line) {
        when {
            ch == '"' -> inQuotes = !inQuotes
            ch == ',' && !inQuotes -> {
                out += sb.toString()
                sb.clear()
            }
            else -> sb.append(ch)
        }
    }
    out += sb.toString()
    return out
}
