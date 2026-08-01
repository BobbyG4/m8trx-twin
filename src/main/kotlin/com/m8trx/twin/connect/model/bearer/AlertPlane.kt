package com.m8trx.twin.connect.model.bearer

import com.fasterxml.jackson.annotation.JsonProperty
import com.m8trx.twin.connect.model.webhook.AlarmEnvelope
import com.m8trx.twin.connect.model.webhook.WebhookAck

/**
 * `POST /api/v2/alerts/query` — the read half of alarm ingest (Connect API doc **§8.2**, scope
 * **`alert:read`**), shaped exactly like the four §6.5 reads: resolve by the ref you supplied,
 * site-confined, bounded, truncation announced, one capability per plane.
 *
 * ## ⚠ No key holds `alert:read`, so this is written against the contract, not against a 200
 *
 * Measured 2026-08-01, before the first alarms were sent that morning:
 * ```
 * ran: POST /api/v2/alerts/query {}      (twin's Connect Bearer)
 * got: 403 {"error":"PERMISSION_DENIED","message":"Insufficient permissions"}
 * ```
 * `PERMISSION_DENIED`, **not** `CONNECT_NOT_EXPOSED` — the endpoint is Connect-reachable and the gate
 * is the key's scope set. **⛔ STILL 403 as of 2026-08-01 08:07Z, re-measured.** `alert:read` *was*
 * granted that day — **to `twin-data-plane-bearer`, which is not the key twin uses.** Twin presents
 * `twin-s280-lockdown` (Denver-bound; `inventory:read · vision_ai:view · task:read`), so this read
 * remains uncallable and the §8.2 read half is still unproven by anyone.
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
 * These DTOs exist so the read is *ready* the moment the grant lands, and so the shape twin asserts
 * against is the documented one rather than whatever the first live response happens to contain.
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
    @JsonProperty("alert_id") val alertId: String? = null,
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("kind") val kind: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("severity") val severity: String? = null,
    @JsonProperty("native_level") val nativeLevel: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("site_id") val siteId: String? = null,
    @JsonProperty("zone_id") val zoneId: String? = null,
    @JsonProperty("zone_code") val zoneCode: String? = null,
    @JsonProperty("occurred_at") val occurredAt: String? = null,
    @JsonProperty("dedupe_key") val dedupeKey: String? = null,
    @JsonProperty("condition_key") val conditionKey: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("subject_type") val subjectType: String? = null,
    @JsonProperty("subject_ref") val subjectRef: String? = null,
    @JsonProperty("assigned_to") val assignedTo: String? = null,
    @JsonProperty("assigned_to_role") val assignedToRole: String? = null,
    @JsonProperty("acknowledged_at") val acknowledgedAt: String? = null,
    @JsonProperty("resolved_at") val resolvedAt: String? = null,
    @JsonProperty("created_at") val createdAt: String? = null,
    @JsonProperty("payload") val payload: Map<String, Any?> = emptyMap(),
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
 * ⚠ **SHAPE PARTIALLY INFERRED — flagged, not guessed silently.** The contract states that the clear
 * is keyed on `dedupe_key` and that `reason: acknowledged` is **refused on purpose** (clearing is not
 * acknowledging). It does not state the full field list, so [source] and [siteRef] are twin's
 * inference from the §8.1 envelope's own required fields. Twin's dry-run prints these exact bytes for
 * confirmation before any live call, and a `400` here should be read as **a doc gap to file, not a
 * server defect** — see the §6.6 question raised with the surface's author.
 */
data class AlertClearRequest(
    @JsonProperty("dedupe_key") val dedupeKey: String,
    /** Why it cleared. ⚠ `acknowledged` is refused by design — acknowledging ≠ clearing. */
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
