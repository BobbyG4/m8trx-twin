package com.m8trx.twin.connect.model.bearer

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * `POST /api/v2/alerts/query` — the read half of alarm ingest (Connect API doc **§8.2**, scope
 * **`alert:read`**), shaped exactly like the four §6.5 reads: resolve by the ref you supplied,
 * site-confined, bounded, truncation announced, one capability per plane.
 *
 * ## ⚠ No key holds `alert:read`, so this is written against the contract, not against a 200
 *
 * Reported 2026-08-01, before the first alarm was sent:
 * ```
 * ran: POST /api/v2/alerts/query {}      (twin's Connect Bearer)
 * got: 403 {"error":"PERMISSION_DENIED","message":"Insufficient permissions"}
 * ```
 * ⚠ **Treat that as expected, not as witnessed.** No artifact of that probe survives in the repo, and
 * a dry-run cannot produce one (it runs without a Bearer). [com.m8trx.twin.connect.sim.AlarmDriver]
 * re-probes on **every** run and prints whatever actually comes back, so the first live run either
 * confirms this or contradicts it — either way from evidence rather than from this comment.
 *
 * `PERMISSION_DENIED`, **not** `CONNECT_NOT_EXPOSED` — the endpoint is Connect-reachable and the gate
 * is the key's scope set, fixable by an administrator in one `PATCH …/service-keys/{keyId}/scopes`
 * call. Same §4 SEC-3 pattern as `vision_ai:*`: a write shipped without anyone holding its read.
 * ⚠ *Instance-counting deliberately dropped here — this said "third … after `vision_ai:*` and
 * `task:read`" while STATUS says **second**. The disagreement is real: pre-SEC-3 keys hold no
 * `task:read`, but twin's key called `/tasks/query` successfully all through S17, so `task:read` is
 * not an instance of "held by no key" from where twin stands. Report the pattern, not a tally.*
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
