package com.m8trx.twin.connect.model.bearer

import com.fasterxml.jackson.annotation.JsonProperty
import com.m8trx.twin.connect.model.webhook.AlarmEnvelope
import com.m8trx.twin.connect.model.webhook.WebhookAck

/**
 * `POST /api/v2/alerts/query` — the read half of alarm ingest (Connect API doc **§8.2**, scope
 * **`alert:read`**), shaped exactly like the four §6.5 reads: resolve by the ref you supplied,
 * site-confined, bounded, truncation announced, one capability per plane.
 *
 * ## ⚠ The scope has been granted twice and reverted once — read the arc, not a snapshot
 *
 * Measured 2026-08-01, before the first alarms were sent that morning:
 * ```
 * ran: POST /api/v2/alerts/query {}      (twin's Connect Bearer)
 * got: 403 {"error":"PERMISSION_DENIED","message":"Insufficient permissions"}
 * ```
 * `PERMISSION_DENIED`, **not** `CONNECT_NOT_EXPOSED` — the endpoint is Connect-reachable and the gate
 * is the key's scope set.
 *
 * ## The full arc, because each state was real and none of them lasted
 *
 * ```
 * 08-01 08:07Z  403 — granted, but to `twin-data-plane-bearer`, a row twin does not use
 * 08-01 08:2xZ  200 — re-granted to `twin-s280-lockdown`, the row twin presents. THE READ HALF WORKED.
 * 08-02 12:23Z  403 — reverted by core `mig-211` (unapproved production writes undone)
 * ```
 *
 * ★ **So §8.2's read half IS proven to work, and is currently unreachable.** Those are both true and
 * the distinction is the whole point: the shapes below were validated against real responses (that is
 * why [AlertRow] is camelCase — measured, not read off a doc), while the capability to call them has
 * never existed by a sanctioned route. Do not soften either half.
 *
 * ⚠ **The lesson worth more than the fix:** `api_key.scopes` reading correct is not evidence the
 * caller holds the capability — **only a request is** — and a grant reported as live can be inert if
 * it landed on a sibling row. Treat every grant as unproven until a run says otherwise;
 * [com.m8trx.twin.connect.sim.AlarmDriver] re-probes on every run and prints what comes back.
 *
 * Same §4 SEC-3 pattern as `vision_ai:*`: a write shipped without anyone holding its read.
 * ⚠ *Instance-counting deliberately dropped here — this said "third … after `vision_ai:*` and
 * `task:read`" while STATUS says **second**. The disagreement is real: pre-SEC-3 keys hold no
 * `task:read`, but twin's key called `/tasks/query` successfully all through S17, so `task:read` is
 * not an instance of "held by no key" from where twin stands. Report the pattern, not a tally.*
 *
 * *(The transcript above was briefly relabelled "expected, not witnessed" on 2026-08-01, on the
 * reasoning that no artifact of the probe survived in the repo. The probe was real — the same sitting
 * that ran it sent the `03:16Z` alarms. Restored as a measurement.)*
 *
 * These DTOs were written before the first 200 so the read would be *ready* the moment a grant landed,
 * and they have since been corrected **against real responses** — which is why [AlertRow] is camelCase.
 * That correction is the argument for building the consumer ahead of the capability: the doc-shaped
 * version compiled, self-tested green, and was wrong.
 * [com.m8trx.twin.connect.sim.AlarmDriver] re-probes on every run and reports **unproven** rather
 * than substituting a DB query — twin does not hold a mother credential and would not use one here
 * if it did: a vendor cannot, so a verification a vendor cannot perform proves nothing about the
 * contract.
 *
 * Casing follows §6.5: envelope + refs `snake_case` via explicit [JsonProperty], serialized through
 * [com.m8trx.twin.connect.model.ConnectMappers.camel] like the rest of the Bearer plane.
 */
data class AlertQueryRequest(
    /** Optional. Omitted = **every site this key may see**, never the tenant (§6.5 rule 2). */
    @JsonProperty("site_ref") val siteRef: String? = null,
    /** Filter to your own alarms — the vendor case: `["twin-eas"]`. */
    @JsonProperty("source") val source: List<String>? = null,
    @JsonProperty("kind") val kind: List<String>? = null,
    @JsonProperty("status") val status: List<String>? = null,
    @JsonProperty("severity") val severity: List<String>? = null,
    @JsonProperty("from") val from: String? = null,
    @JsonProperty("to") val to: String? = null,
    /** Default 500, max [ReadCaps.ROWS_MAX]. An over-max is **refused, never clamped**. */
    @JsonProperty("limit") val limit: Int? = null,
)

data class AlertQueryResponse(
    @JsonProperty("site_id") val siteId: String? = null,
    @JsonProperty("from") val from: String? = null,
    @JsonProperty("to") val to: String? = null,
    @JsonProperty("count") val count: Int = 0,
    @JsonProperty("truncated") val truncated: Boolean = false,
    @JsonProperty("summary") val summary: Map<String, Int> = emptyMap(),
    @JsonProperty("alerts") val alerts: List<AlertRow> = emptyList(),
)

/**
 * One alert, mirroring the ingest envelope field-for-field — including the vendor's own fields,
 * returned verbatim in [payload], so the DTO written for the write is the DTO read back with.
 *
 * ★ [severity] vs [nativeLevel] is the pair worth reading together, and the reason this run exists.
 * With `may_set_severity=false` the **proposed** severity is not discarded: it is preserved verbatim
 * as [nativeLevel] while [severity] carries the routing decision. So a vendor can report
 * proposed-vs-effective — *"we sent `critical`, the platform routed it `info`, and it kept our
 * `critical`"* — which is a far more useful finding than either number alone.
 */
data class AlertRow(
    // ⚠ MEASURED 2026-08-01: the ROWS are camelCase under a snake_case envelope — the same mixed
    // shape §6.5's impression rows use. These carried snake `@JsonProperty` names until this was run
    // live, which bound `occurredAt`/`dedupeKey`/`zoneCode` to null and made twin's first read-back
    // report three fields as "absent from the server" when the server was sending them. The
    // self-test fixture was snake too, so it passed and agreed with the bug. Fixture the SHAPE THE
    // SERVER SENDS, never the shape the doc describes.
    val alertId: String? = null,
    val source: String? = null,
    val kind: String? = null,
    val type: String? = null,
    /** What the platform routes on. */
    val severity: String? = null,
    /** The vendor's proposal, documented as preserved verbatim when `may_set_severity=false`. */
    val nativeLevel: String? = null,
    val status: String? = null,
    val siteId: String? = null,
    val zoneId: String? = null,
    val zoneCode: String? = null,
    val occurredAt: String? = null,
    val createdAt: String? = null,
    val dedupeKey: String? = null,
    val conditionKey: String? = null,
    val title: String? = null,
    val description: String? = null,
    val subjectType: String? = null,
    val subjectRef: String? = null,
    val assignedTo: String? = null,
    val assignedToRole: String? = null,
    val acknowledgedAt: String? = null,
    val resolvedAt: String? = null,
    /** The vendor's own fields, echoed. Keys stay verbatim as sent (snake), so no annotation here. */
    val payload: Map<String, Any?> = emptyMap(),
)

/**
 * The `POST /api/v2/alerts` ack (§6.6 Bearer ingest arm, scope **`alert:ingest`**, SITE-scoped).
 *
 * ★ **This ack is the whole reason the Bearer arm matters to a vendor.** The webhook plane answers
 * `{accepted:true}` — a receipt that says nothing about what the alarm became. Here the answer is
 * synchronous and tells the vendor the one thing it could never see: **whether its alarm will page
 * anyone.** Three separate fields, not one enum, because `twin-eas` reaches `info` by *two independent
 * roads* and collapsing them would hide which one applied:
 *
 *  - [autoRegistered] — the kind was unknown and got timid defaults (`severity=info`).
 *  - [proposedSeverityIgnored] — the source is not registered `may_set_severity`, so the proposal was
 *    dropped regardless of the kind.
 *  - [severity] — what the platform will actually route on.
 *
 * A theft alarm coming back `severity=info` with `proposedSeverityIgnored=critical` is **correct
 * behaviour, and a finding worth reporting** — proposed-vs-effective is more useful than either
 * number alone.
 *
 * ⚠ camelCase, bound with [com.m8trx.twin.connect.model.ConnectMappers.camel] — the *request* is the
 * snake_case §8.1 envelope on the same call. Same per-direction boundary as [WebhookAck].
 */
data class AlertIngestAck(
    val alertId: String? = null,
    /** `recorded` on first raise · `deduped` when [AlarmEnvelope.dedupeKey] was already seen. */
    val disposition: String? = null,
    /** Echoed back — assert it matches what was sent, or dedupe identity is not what the vendor thinks. */
    val dedupeKey: String? = null,
    /** What the platform routes on. May differ from the proposal; that difference is the point. */
    val severity: String? = null,
    /** True ⇒ the kind was unreviewed and defaulted, i.e. nobody has decided what this alarm means. */
    val autoRegistered: Boolean? = null,
    /** The severity the vendor asked for and did not get. Non-null is itself the finding. */
    val proposedSeverityIgnored: String? = null,
    /** One sentence per flag. Print verbatim — paraphrasing a warning loses the contract's own words. */
    val warnings: List<String> = emptyList(),
)

/**
 * `POST /api/v2/alerts/clear` (§6.6) — clear by **the vendor's own `dedupe_key`**, never by M8TRX's
 * `alertId`, which is the right call: a gate vendor holds the key it minted and has no reason to have
 * retained a platform UUID.
 *
 * ⚠ **`cleared: 0` is a SUCCESS, not a 404** — the operation is idempotent, so clearing an
 * already-cleared or never-raised key is a no-op rather than an error. Reading `0` as failure is the
 * mistake this note exists to prevent.
 *
 * ## ✅ Shape CONFIRMED live 2026-08-01, and the one thing twin got wrong
 *
 * The field list was inferred ([source] and [siteRef] from the §8.1 envelope's required fields) and
 * **the inference was right** — the request bound and reached `reason` validation. What twin got wrong
 * was the *enum*, which no document twin held had published:
 * ```
 * sent: {"dedupe_key":"CS-01:…","reason":"no_longer_present","source":"twin-eas","site_ref":"dec-us-denver"}
 * got:  400 INVALID_REQUEST  "reason 'no_longer_present' is not one of [resolved, expired, auto_resolved]"
 * ```
 * ★ **The refusal named the valid set, so the API closed its own documentation gap.** That is the
 * behaviour to keep: an integrator who guesses wrong is told what is right, in one round trip, without
 * asking a human. Compare the `alert_source` registrations, where a wrong guess yields nothing to act on.
 *
 * `acknowledged` is refused **by design**, and the message says why rather than just refusing:
 * *"a person SEEING an alarm is not the alarm being over"* — confirmed live, and it is a distinction
 * an LP workflow depends on.
 */
data class AlertClearRequest(
    @JsonProperty("dedupe_key") val dedupeKey: String,
    /**
     * One of **`resolved` · `expired` · `auto_resolved`** (measured from the server's own 400).
     * ⚠ `acknowledged` is refused by design — acknowledging ≠ clearing.
     */
    @JsonProperty("reason") val reason: String? = null,
    /** Inferred: the same registered `alert_source` that raised it. */
    @JsonProperty("source") val source: String? = null,
    /** Inferred: site confinement mirrors every other §6.5/§6.6 call. */
    @JsonProperty("site_ref") val siteRef: String? = null,
)

/** The clear ack. [cleared] is a **count**, and `0` means "nothing matched", which is a success. */
data class AlertClearAck(
    /** How many alerts the key matched. **`0` is a success** — idempotent, not "not found". */
    val cleared: Int? = null,
    /** Echoed back, so a vendor can confirm the platform read the key it actually sent. */
    val dedupeKey: String? = null,
    /** Print verbatim — e.g. a refusal reason such as `acknowledged`, which is rejected by design. */
    val warnings: List<String> = emptyList(),
)
