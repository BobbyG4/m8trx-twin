package com.m8trx.twin.connect

import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.bearer.AlertClearRequest
import com.m8trx.twin.connect.model.bearer.AlertIngestAck
import com.m8trx.twin.connect.model.bearer.AlertQueryResponse
import com.m8trx.twin.connect.model.bearer.AlertRow
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
 * The send is twin's; every step after it is observed only through a documented diagnostic. **State
 * moved twice on 2026-08-01, so this block records what is measured and when — nothing is inherited:**
 *
 * ```
 * 2026-08-01 08:07Z   alert:read 403 · integration:manage 403 · held: inventory:read, vision_ai:view, task:read
 * 2026-08-01 08:2x-08:51Z  ★ alert:read + alert:ingest TEMPORARILY GRANTED → the whole chain ran (below)
 * 2026-08-02 12:23Z   ⛔ REVERTED. alert:read 403 again. Key is back to its original three scopes.
 * ```
 *
 * ⚠⚠ **READ THIS BEFORE QUOTING ANY RESULT FROM THIS DRIVER.** The successful run happened inside a
 * window where twin's key held `alert:read` + `alert:ingest` **by way of unapproved production writes,
 * which were subsequently reverted** (core `mig-211`, *"revert every unapproved S285 production
 * write"*). So the two claims must never be merged:
 *
 *  - **The mechanism is PROVEN.** Raise, dedupe, refusal semantics, ack contents, read-back — all
 *    measured from outside, by this driver, and reproducible the moment the scopes exist.
 *  - **The access path is NOT.** No sanctioned route ever granted those scopes. The only way the chain
 *    has ever traversed is a write that had to be undone — which is TWIN-REQ-005 demonstrated rather
 *    than argued, and a stronger form of it than the brief originally made.
 *
 * A re-run today stops at the pre-flight with three `403`s. That is the correct current state, not a
 * regression in this code.
 *
 * ⚠ **`M8TRX_TWIN_BEARER` is `twin-s280-lockdown`, NOT `twin-data-plane-bearer`.** Two twin keys exist
 * and both carry the post-SEC-3 `vision_ai:view` + `task:read` pair, so **scope shape identifies a
 * key's vintage, never its row.** A grant of `alert:read` was written to the *other* row on 2026-08-01
 * and reported as live; twin ran this drive against that premise and got three `403`s. What resolved
 * it was `api_key.last_used_at`: twin's requests moved `twin-s280-lockdown`, while the edited row had
 * not moved since 2026-07-30. **Identify a key by what its traffic touches, not by its name or its
 * scope shape.** Recorded as a recurring class in TWIN-REQ-005 § Update 2026-08-01.
 *
 * Two lessons are encoded here rather than in a status doc. First, §8.1's own instruction — *"poll the
 * §7 DLQ rather than assuming a 200 meant it landed"* — named a diagnostic the vendor it was written
 * for could not call, which is why the verdict is allowed to read **SENT, UNPROVEN**. Second, and the
 * reason this driver re-probes on every run: **twin's first two live alarms (03:16Z) acked `200`, wrote
 * their `alert` rows, then dead-lettered** on an `alert_event` CHECK violation. Nobody could see it,
 * so `SENT, UNPROVEN` was the correct verdict and the defect stayed open for hours. Twin does not
 * close such a gap with a `psql` it neither holds nor could justify — a verification a vendor cannot
 * perform says nothing about whether the contract works for a vendor.
 *
 * ## The sends
 *
 * 1. **A1** — the published §8.1 shape (source detail top-level), as a vendor reading the contract
 *    would author it. Webhook plane.
 * 2. **A1 repeat** — byte-identical. Dedupe must collapse it; two rows would be a finding.
 * 3. **A2** — different `dedupe_key`, same kind, in the `DESIGN` §2 shape (detail nested under
 *    `payload`). The two documents disagree, so twin sends both rather than picking one.
 * 4. **B1–B4 (§6.6 Bearer arm)** — raise → byte-identical retry (must answer `deduped`) → clear by
 *    the vendor's own `dedupe_key` → a deliberate refusal (`reason=acknowledged`, rejected by design).
 *    This arm's ack is the point: `severity` · `autoRegistered` · `proposedSeverityIgnored` answer
 *    **"will this alarm page anyone?"**, which `{accepted:true}` structurally cannot.
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
 * `M8TRX_ALARM_INTEGRATION_ID` (for the DLQ/health probes) ·
 * `M8TRX_ALARM_LOOKBACK_MIN` (read-back window, default 60 — widen it to see earlier alarms) ·
 * `M8TRX_CHAIN_DIR`.
 *
 * **A dry-run is not a no-op**: it still runs the pre-flight diagnostics, so it is the right way to
 * test a scope grant or read back existing alarms without writing anything.
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
    // `resolved` | `expired` | `auto_resolved` — the set the server's own 400 named when twin guessed
    // `no_longer_present`. Configurable so the refusal path stays drivable without editing code.
    val clearReason = System.getenv("M8TRX_ALARM_CLEAR_REASON") ?: "resolved"
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
    //
    // The window matters and was a bug: a hardcoded 1h lookback could not see twin's own 03:16Z
    // alarms from earlier the same day, so the read-back would have reported an empty history and
    // looked like "no rows" rather than "wrong window". Same class as the S17 query-window errors.
    log.info("── pre-flight: the documented diagnostics, measured not inherited ──")
    val lookbackMin = System.getenv("M8TRX_ALARM_LOOKBACK_MIN")?.toLongOrNull() ?: 60L
    log.info("   read-back window: last {} min (M8TRX_ALARM_LOOKBACK_MIN)", lookbackMin)
    val before = driver.verify(integrationId, source, siteSlug, Instant.now().minusSeconds(lookbackMin * 60))
    reportDiagnostics(before)
    reportAlerts(client, before.alertsQuery, source, gate.code)
    if (live) {
        when (val d = client.itemDetails(ItemDetailsRequest(listOf(subject.epc)))) {
            is ConnectResponse.Ok -> log.info("  [ok] items/details — subject EPC resolves server-side: {}", d.rawBody.take(220))
            is ConnectResponse.Err -> log.warn("  [!!] items/details {} {} — subject EPC did not resolve", d.error.status, d.error.code)
        }
    }

    // ── the three sends ───────────────────────────────────────────────────────────────────────────
    // Every occurred_at is strictly in the PAST, A1 included. `/alerts/query`'s window is
    // `[now-24h, now)` with an EXCLUSIVE upper bound, so an alarm stamped exactly `now` sits on the
    // boundary. The margin costs nothing and removes the last way this run could report a row as
    // missing when it landed.
    val occurredAt = System.currentTimeMillis()
    val a1 = driver.gateExitUnpaid(source, siteSlug, gate.code, subject.epc, occurredAt - 3_000)

    // ⚠ A2 is offset into the PAST, never the future. It only needs a distinct dedupe_key, and the
    // sign of that offset is load-bearing: `alert.created_at` is taken from the vendor's
    // `occurred_at` and `/alerts/query`'s window is `[now-24h, now)`, so a future-dated alarm acks
    // 200 and is then invisible to its own read-back until wall-clock catches up. This line said
    // `+ 7_000` until 2026-08-01 — A2 would have been unfindable while A1 read back fine, which
    // presents as "A2 never landed" rather than "A2 is dated wrong". Past-dating is always in-window;
    // vendor clocks skew both ways and only one direction is safe.
    val a2 = driver.gateExitUnpaidDesignShape(source, siteSlug, gate.code, second.epc, occurredAt - 7_000)

    log.info("── A1: the published §8.1 shape (source detail top-level) ──")
    log.info("{}", driver.dryRun(a1))
    log.info("── A2: the DESIGN §2 shape (source detail nested under payload) ──")
    log.info("{}", driver.dryRun(a2))

    // ── B: the §6.6 Bearer arm — same envelope, synchronous ack that says what the alarm BECAME ──
    val b1 = driver.gateExitUnpaid(source, siteSlug, gate.code, subject.epc, occurredAt - 21_000, antennaGroup = "A4")
    log.info("── B1: §6.6 Bearer ingest `POST /alerts` (scope alert:ingest) ──")
    log.info("{}", client.mapper.writeValueAsString(b1))
    log.info("── B3: `POST /alerts/clear` keyed on the VENDOR's dedupe_key ──")
    log.info("{}", client.mapper.writeValueAsString(AlertClearRequest(b1.dedupeKey!!, clearReason, source, siteSlug)))

    if (!live) {
        log.info("")
        log.info("DRY-RUN — nothing sent. Re-run with M8TRX_ALARM_LIVE=true to drive the chain.")
        log.info("Bearer arm: `alert:ingest` was granted 2026-08-01 and REVERTED by mig-211 — expect 403 until it is re-granted.")
        return
    }

    // ⚠ A1-repeat is a WEAKER test than it looks, and reading the wire proved it.
    // Six webhook sends produced only FOUR `integration_event` rows: a byte-identical payload is
    // collapsed by the CONTENT HASH at the integration layer and never reaches the alert layer at all.
    // So "A1 sent twice → one alert row" is satisfied without alert-level `dedupe_key` dedupe ever
    // being exercised — a vacuous assertion of exactly the kind `connectAcceptance`'s omitted-site rule
    // exists to forbid. Only the Bearer arm's B2 genuinely tested it (disposition=deduped, same alertId).
    //
    // A3 fixes it: SAME `dedupe_key`, DIFFERENT bytes (antenna_group A2→A9). The content hash differs,
    // so it reaches the ingester; the dedupe_key matches, so the alert layer must collapse it. If a
    // second row appears for that key, alert-level dedupe is broken on the webhook plane — which no
    // test on either side currently covers.
    val a3 = driver.gateExitUnpaid(source, siteSlug, gate.code, subject.epc, occurredAt - 3_000, antennaGroup = "A9")
    check(a3.dedupeKey == a1.dedupeKey) { "A3 must carry A1's dedupe_key or it tests nothing: ${a3.dedupeKey} vs ${a1.dedupeKey}" }
    check(driver.dryRun(a3) != driver.dryRun(a1)) { "A3 must differ in bytes from A1 or the content hash stops it before the alert layer" }

    val sends = listOf(
        "A1 (§8.1 shape)" to driver.drive(a1),
        "A1 repeat (byte-identical — collapsed by CONTENT HASH at the integration layer, not by dedupe_key)" to driver.drive(a1),
        "A2 (§2 shape, distinct dedupe_key)" to driver.drive(a2),
        "A3 (SAME dedupe_key, DIFFERENT bytes — the only real test of alert-level dedupe on this plane)" to driver.drive(a3),
    )

    // ── B live: raise → retry (must dedupe) → clear → a refusal. Acks printed VERBATIM. ──
    log.info("── B: §6.6 Bearer arm, live ──")
    val bearer = mutableListOf<Pair<String, ConnectResponse>>()
    bearer += "B1 raise (POST /alerts)" to client.ingestAlert(b1)
    bearer += "B2 retry, byte-identical (must be disposition=deduped)" to client.ingestAlert(b1)
    bearer += "B3 clear by dedupe_key" to client.clearAlert(AlertClearRequest(b1.dedupeKey, clearReason, source, siteSlug))
    // A deliberate refusal probe: `acknowledged` is documented as rejected — clearing is not acknowledging.
    bearer += "B4 clear reason=acknowledged (MUST be refused by design)" to
        client.clearAlert(AlertClearRequest(b1.dedupeKey, "acknowledged", source, siteSlug))
    bearer.forEach { (name, r) -> log.info("  [{}] {} → {}", if (r.isOk) "ok" else r.status, name, r.rawBody.take(400)) }
    reportIngestAck(client, bearer.firstOrNull()?.second, b1.dedupeKey)

    // ⛔ The clear is a SUCCESS that did nothing, and the contract makes that unreadable.
    // Measured: B1 raised `dedupe_key` X (disposition=recorded), B2 re-sent X (disposition=deduped, SAME
    // alertId — so the platform certainly knows X), B3 cleared X with reason=`resolved` (a value the
    // server itself named) → `{"cleared":0,"alertIds":[]}`, and the row was still `status=active` on
    // read-back. `cleared:0` is documented as an idempotent success, which makes "nothing matched"
    // indistinguishable from "the thing I just raised was not cleared". Note the ack echoes
    // `conditionKey:null`: the clear may be built for CONDITIONS, which auto-clear, and not for point
    // events at all — in which case telling a vendor to clear by `dedupe_key` is the defect.
    val clearedNothing = (bearer.getOrNull(2)?.second as? ConnectResponse.Ok)?.rawBody?.contains("\"cleared\":0") == true
    if (clearedNothing) {
        log.error("  ⛔ FINDING — clear returned cleared:0 for a dedupe_key raised seconds earlier and still active.")
        log.error("     A vendor cannot tell an idempotent no-op from a clear that silently failed.")
        log.error("     Suggest: distinguish them — 404/`matched:false` for an unknown key, or state that")
        log.error("     point events are not clearable and only `condition_key` alarms are.")
    }

    // ⚠ MEASURED 2026-08-01, and 8s was NOT enough. The two planes settle at different speeds:
    //   · §6.6 Bearer raise — readable IMMEDIATELY (the ack is synchronous with the write)
    //   · §8 webhook alarm  — still absent at ~9s, present by ~50s (@Async dispatch)
    // At the old 8s this read reported 4 rows where 6 existed, i.e. a vendor doing
    // send → short wait → read-back sees its own alarm missing and concludes it never landed. That is
    // a false negative manufactured by the client, and it is the mirror of the window bugs above.
    // Configurable because the right value belongs to the environment, not to this source file.
    val settleMs = System.getenv("M8TRX_ALARM_SETTLE_MS")?.toLongOrNull() ?: 60_000L
    log.info("── waiting {}ms for @Async webhook ingest to settle (Bearer rows are already readable) ──", settleMs)
    Thread.sleep(settleMs)

    log.info("── post-send: the same diagnostics, re-probed ──")
    val after = driver.verify(integrationId, source, siteSlug, Instant.ofEpochMilli(occurredAt).minusSeconds(120))
    reportDiagnostics(after)
    reportAlerts(client, after.alertsQuery, source, gate.code)

    verdict(sends, after, a1, a2)
}

/**
 * The one question a vendor could never answer before §6.6: **will this alarm page anyone?**
 *
 * Reports the three fields separately because `twin-eas` reaches `info` by two independent roads —
 * an auto-registered kind AND `may_set_severity=false` — and a single effective number cannot say
 * which applied, nor whether anyone has ever reviewed what this alarm means.
 */
private fun reportIngestAck(client: ConnectClient, raise: ConnectResponse?, sentDedupeKey: String) {
    val ok = raise as? ConnectResponse.Ok ?: return
    val ack = runCatching { client.mapper.readValue(ok.rawBody, AlertIngestAck::class.java) }.getOrNull()
    if (ack == null) {
        log.warn("  [!!] could not bind the §6.6 ack — shape differs from §6.6 as published: {}", ok.rawBody.take(400))
        return
    }
    log.info("  ── does the ack tell the truth about severity? ──")
    log.info("     routed severity ......... {}", ack.severity ?: "(absent)")
    log.info("     proposed but ignored .... {}", ack.proposedSeverityIgnored ?: "(none — proposal honored)")
    log.info("     kind auto-registered .... {}", ack.autoRegistered ?: "(absent)")
    log.info("     disposition ............. {}", ack.disposition ?: "(absent)")
    ack.warnings.forEach { log.info("     warning: {}", it) }
    if (ack.dedupeKey != null && ack.dedupeKey != sentDedupeKey) {
        log.error(
            "  ⛔ FINDING: ack echoed dedupe_key '{}' but '{}' was sent — dedupe identity is not what the vendor thinks",
            ack.dedupeKey,
            sentDedupeKey,
        )
    }
    if (ack.severity == "critical" && ack.proposedSeverityIgnored == null) {
        log.warn("  ⚠ the proposal was HONORED — that contradicts may_set_severity=false. Report it; do not assume the doc is right.")
    }
    if (ack.severity != null && ack.severity != "critical") {
        log.warn(
            "  ★ a theft alarm routes as '{}'. Correct per the registration — and the reason the LP story needs a severity decision, not more code.",
            ack.severity,
        )
    }
}

/**
 * Print the `/alerts/query` result row by row, because the interesting facts are per-row and a
 * truncated raw body hides exactly the ones worth reporting.
 *
 * ★ The pair under test is **[AlertRow.severity] (routed) vs [AlertRow.nativeLevel] (what the vendor
 * proposed)**. §8.2 documents that when a source is not `may_set_severity`, the proposal is *preserved
 * verbatim* as `native_level` rather than discarded — that promise is the only thing that lets a vendor
 * report *"we sent critical, the platform routed info, and it kept our critical"*. If `native_level`
 * comes back null on a row whose severity was down-routed, **the proposal is gone and the promise is
 * not kept** — which is a finding, not a cosmetic gap.
 */
private fun reportAlerts(client: ConnectClient, resp: ConnectResponse?, source: String, gateCode: String) {
    val ok = resp as? ConnectResponse.Ok ?: return
    val parsed = runCatching { client.mapper.readValue(ok.rawBody, AlertQueryResponse::class.java) }.getOrNull()
    if (parsed == null) {
        log.warn("  [!!] /alerts/query body did not bind to the §8.2 shape: {}", ok.rawBody.take(600))
        return
    }
    log.info("  ── /alerts/query: {} row(s), truncated={}, site_id={} ──", parsed.count, parsed.truncated, parsed.siteId)
    log.info("     summary: {}", parsed.summary)
    // ⚠ `source` does NOT identify the producer. Every lane's CI drives also post as `twin-eas`, so
    // four of the six rows on the first real read-back were other sessions' tests. Twin's own alarms are
    // identified by the dedupe_key COMPOSITION its driver mints — `<gate>:<epc>:<millis>` — not by a
    // shared source name. Same lesson as the api_key row: identify by evidence, never by name.
    fun isTwins(a: AlertRow): Boolean {
        val k = a.dedupeKey ?: return false
        return k.startsWith("$gateCode:") && k.count { c -> c == ':' } == 2
    }
    parsed.alerts.forEach { a ->
        val mine = when {
            isTwins(a) -> "TWIN"
            a.source == source -> "other-lane"
            else -> "other-src"
        }
        log.info(
            "     [{}] {} {} sev={} native={} status={} zone={} occurred={} dedupe={}",
            mine,
            a.source,
            a.kind,
            a.severity,
            a.nativeLevel ?: "NULL",
            a.status,
            a.zoneCode ?: a.zoneId,
            a.occurredAt,
            a.dedupeKey,
        )
        if (a.payload.isNotEmpty()) log.info("            payload: {}", a.payload)
        if (a.title != null) log.info("            title: {}", a.title)
    }
    val mine = parsed.alerts.filter { isTwins(it) }
    val proposalLost = mine.filter { it.severity != null && it.severity != "critical" && it.nativeLevel == null }
    if (proposalLost.isNotEmpty()) {
        log.error("")
        log.error("  ⛔ FINDING — proposed severity is NOT recoverable on {} of twin's {} row(s).", proposalLost.size, mine.size)
        log.error(
            "     Twin sent severity='critical' on every alarm. These rows read sev='{}' with native_level=NULL.",
            proposalLost.first().severity,
        )
        log.error("     §8.2 documents the proposal being PRESERVED verbatim as native_level when may_set_severity=false.")
        log.error("     If it is null, proposed-vs-effective cannot be reported by the vendor at all — the routing")
        log.error("     decision is visible and the input to it is gone. A vendor cannot show it asked for critical.")
    }
    log.info("     → {} of {} row(s) were minted by THIS driver; the rest share the source name.", mine.size, parsed.count)
    val zoneUnresolved = parsed.alerts.filter { it.zoneId == null && it.zoneCode == null }
    if (zoneUnresolved.isNotEmpty()) {
        log.warn("  ⚠ zone UNRESOLVED on {} of {} row(s) — landed site-level.", zoneUnresolved.size, parsed.count)
        log.warn("     Twin sent zone_ref='{}', a real crossing-slice code from Denver's own layout. §8.1 allows", gateCode)
        log.warn("     site-level fallback, so this may be correct — but a gate is not a `zone`, so a vendor")
        log.warn("     given a gate code has no zone to name and every alarm loses its location.")
    }
    val subjectDemoted = mine.filter { it.subjectRef == null && it.payload.keys.any { k -> k == "subject_ref" } }
    if (subjectDemoted.isNotEmpty()) {
        log.error("  ⛔ FINDING — `subject_ref` is NOT bound as the subject on {} row(s); it is buried in payload.", subjectDemoted.size)
        log.error("     §8.1 documents `subject_id` (a UUID no vendor holds); the DESIGN doc says `subject_ref`.")
        log.error("     The deployed server treats `subject_ref` as an unknown vendor field, so the ONLY subject")
        log.error("     identifier a gate vendor can supply is silently demoted to opaque vendor data.")
    }
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
    log.info(
        "  step 3 · dedupe collapsed the repeat ..... {}",
        if (observable) {
            "count above — but read it per LAYER: A1-repeat is stopped by the integration content hash, " +
                "A3 (same dedupe_key, different bytes) and B2 are what actually test the ALERT layer"
        } else {
            "UNPROVEN — needs the row count"
        },
    )
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
