package com.m8trx.twin.connect.model.bearer

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Bearer READ-plane DTOs — Connect API doc **§6.5** ("the READ half", live 2026-07-30, PR #210).
 *
 * ## Casing — read this before adding a field
 *
 * §6.5 is the one place on the Connect surface where a single response body carries **both** casings:
 *
 *  - **envelopes + refs are `snake_case`** (`site_ref`, `zone_count`, `truncated`, `task_id`, …)
 *  - **impression ROWS are `camelCase`**, mirroring the `POST /visionai/impressions` ingest response
 *    field-for-field, so the DTO you already wrote for the write is the DTO you read back with.
 *
 * These all serialize through [com.m8trx.twin.connect.model.ConnectMappers.camel] (the Bearer-plane
 * mapper, per the §2 casing boundary), so every snake_case field carries an explicit `@JsonProperty`
 * and the camelCase impression row carries none. That asymmetry is deliberate and load-bearing: a
 * field added here without its annotation serializes as camelCase, the server ignores it as unknown,
 * and the read silently answers a *different* question than the one asked — the exact failure class
 * §6.5 exists to end. `connectSelfTest` round-trips both shapes to keep that honest.
 *
 * ## The caps are real refusals, not clamps
 *
 * An over-wide window or over-max `limit` is refused with **400**, never silently clamped — a clamp
 * would answer a different question while looking like it answered yours. The constants below mirror
 * the documented ceilings so twin fails locally, with a message naming the field, before spending a
 * round trip.
 *
 * ## There is no paging
 *
 * `truncated: true` means **narrow the query**; there is no `offset` and no cursor, so there is no
 * page 2. For the impression read the window IS the cursor — walk it backwards in slices (see
 * [com.m8trx.twin.connect.ImpressionVerify]). Ignoring the flag is how a caller mistakes a page for
 * a day: twin's own full day is 3,119 impressions at one site, which a single call returns 500 of.
 */

// ── caps (Connect §6.5 "Page caps") ───────────────────────────────────────────────────────────────

/** Documented ceilings. Exceeding one is a 400 from the server, so twin checks locally first. */
object ReadCaps {
    const val ZONES_DEFAULT = 2_000
    const val ZONES_MAX = 10_000
    const val ROWS_DEFAULT = 500
    const val ROWS_MAX = 5_000
    const val WINDOW_MAX_DAYS = 31L
}

// ── §6.5.1 POST /api/v2/spatial/identity — the site → space → zone map (inventory:read) ───────────

/**
 * Request. Every field optional; omitting `siteRef` means "every site this key may see" — never
 * "every site in the tenant". `siteRef`/`spaceRef` accept a UUID **or** a slug/name.
 */
data class SpatialIdentityRequest(
    @JsonProperty("site_ref") val siteRef: String? = null,
    @JsonProperty("space_ref") val spaceRef: String? = null,
    @JsonProperty("zone_types") val zoneTypes: List<String>? = null,
    @JsonProperty("include_zones") val includeZones: Boolean? = null,
    @JsonProperty("limit") val limit: Int? = null,
)

data class SpatialIdentityResponse(
    @JsonProperty("sites") val sites: List<SpatialSite> = emptyList(),
    @JsonProperty("site_count") val siteCount: Int = 0,
    @JsonProperty("space_count") val spaceCount: Int = 0,
    @JsonProperty("zone_count") val zoneCount: Int = 0,
    @JsonProperty("truncated") val truncated: Boolean = false,
)

data class SpatialSite(
    @JsonProperty("site_id") val siteId: String,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("timezone") val timezone: String? = null,
    @JsonProperty("spaces") val spaces: List<SpatialSpace> = emptyList(),
)

data class SpatialSpace(
    @JsonProperty("space_id") val spaceId: String,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("floor_level") val floorLevel: Int? = null,
    @JsonProperty("space_type") val spaceType: String? = null,
    @JsonProperty("area_sqm") val areaSqm: Double? = null,
    @JsonProperty("zones") val zones: List<SpatialZone> = emptyList(),
)

/**
 * A zone's identity. **Key your map on [code]** — `name` is localized (EN/FR/KO) and zone UUIDs are
 * UUIDv5 but *not* derivable (twin tried: 130 name patterns × 8 namespaces, no match; that failed
 * effort is why this endpoint exists). [externalCode] is a `fixture_code_mapping` row — a code *you*
 * registered for the same zone — and stays null until you register one.
 */
data class SpatialZone(
    @JsonProperty("zone_id") val zoneId: String,
    @JsonProperty("code") val code: String? = null,
    @JsonProperty("external_code") val externalCode: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("zone_type") val zoneType: String? = null,
    @JsonProperty("parent_zone_id") val parentZoneId: String? = null,
    @JsonProperty("enabled") val enabled: Boolean? = null,
)

// ── §6.5.2 POST /api/v2/visionai/impressions/query — people-plane read-back (vision_ai:view) ──────

/**
 * Request. `siteRef` is **required**; the rest narrow. Timestamps are ISO-8601 **or epoch millis**
 * (what the NATS event carries natively). Window defaults to 24 h and caps at 31 d.
 *
 * ⚠ Scope is `vision_ai:view` — **never `inventory:*`** (§4, SEC-3). Borrowing the stock plane's verb
 * is how the people plane ended up behind a stock gate once already, and `view` ≠ `ingest`: a key
 * that can POST impressions still 403s this read.
 */
data class ImpressionQueryRequest(
    @JsonProperty("site_ref") val siteRef: String,
    @JsonProperty("space_ref") val spaceRef: String? = null,
    @JsonProperty("zone_ref") val zoneRef: String? = null,
    @JsonProperty("from") val from: String? = null,
    @JsonProperty("to") val to: String? = null,
    @JsonProperty("limit") val limit: Int? = null,
)

/**
 * Response. [summary] is computed over the **returned rows only**, never a second differently-bounded
 * query — so when [truncated] is true the summary describes your page, not the day.
 */
data class ImpressionQueryResponse(
    @JsonProperty("site_id") val siteId: String? = null,
    @JsonProperty("from") val from: String? = null,
    @JsonProperty("to") val to: String? = null,
    @JsonProperty("count") val count: Int = 0,
    @JsonProperty("truncated") val truncated: Boolean = false,
    @JsonProperty("summary") val summary: ImpressionSummary? = null,
    @JsonProperty("impressions") val impressions: List<ImpressionRow> = emptyList(),
)

data class ImpressionSummary(
    @JsonProperty("zones") val zones: Int = 0,
    @JsonProperty("sessions") val sessions: Int = 0,
    @JsonProperty("view_time_seconds") val viewTimeSeconds: Double = 0.0,
    @JsonProperty("dwell_time_seconds") val dwellTimeSeconds: Double = 0.0,
)

/**
 * One persisted impression — **camelCase, no annotations**, mirroring the ingest response field-for-field
 * (§6.5 casing rule). Do not "fix" this to snake_case.
 *
 * [recordedAt] is **event time** and equals `firstLook`, not wall-clock ingest — so a window means what
 * you think it means even for a late-arriving row, and it is NOT the same clock as
 * [com.m8trx.twin.layer1.ImpressionWatcher]'s publish-arrival buckets. Aligning those two clocks
 * naively is what made the first emitted-vs-accepted diff read a phantom 13% loss that was really a
 * one-minute bucket offset.
 *
 * [zoneId]/[zoneCode] are the attribution **stamped at ingest** and never re-resolved — re-resolving
 * would rewrite history and make before/after layout comparison (FR-INTEL-23/24) impossible.
 */
data class ImpressionRow(
    val id: String? = null,
    val personSessionId: String? = null,
    val zoneId: String? = null,
    val zoneCode: String? = null,
    val zoneName: String? = null,
    val spaceId: String? = null,
    val firstLook: Long? = null,
    val lastLook: Long? = null,
    val firstDwell: Long? = null,
    val lastDwell: Long? = null,
    val viewTimeSeconds: Double? = null,
    val dwellTimeSeconds: Double? = null,
    val classification: String? = null,
    val recordedAt: String? = null,
)

// ── §6.5.3 POST /api/v2/tasks/query — the tasks YOUR directive generated (task:read) ─────────────

/**
 * Request. [directiveRef] is **required** and is the `external_directive_id` twin assigned. An unknown
 * ref is a **404 `DIRECTIVE_NOT_FOUND`**, never an empty 200 — see [ReadRefusal].
 */
data class TaskQueryRequest(
    @JsonProperty("directive_ref") val directiveRef: String,
    @JsonProperty("site_ref") val siteRef: String? = null,
    @JsonProperty("status") val status: List<String>? = null,
    @JsonProperty("limit") val limit: Int? = null,
)

data class TaskQueryResponse(
    @JsonProperty("directive_ref") val directiveRef: String? = null,
    @JsonProperty("directive_id") val directiveId: String? = null,
    @JsonProperty("count") val count: Int = 0,
    @JsonProperty("truncated") val truncated: Boolean = false,
    @JsonProperty("summary") val summary: Map<String, Int> = emptyMap(),
    @JsonProperty("tasks") val tasks: List<TaskRow> = emptyList(),
)

/**
 * One generated task. [assignedTo] is a **UUID only** — no staff name or email crosses the Connect
 * surface. Only the READ is Connect-exposed; task lifecycle verbs (claim/start/complete/cancel/
 * reassign) are staff actions and stay `403 CONNECT_NOT_EXPOSED` to a service principal, by design.
 */
data class TaskRow(
    @JsonProperty("task_id") val taskId: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("priority") val priority: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("site_id") val siteId: String? = null,
    @JsonProperty("space_id") val spaceId: String? = null,
    @JsonProperty("zone_id") val zoneId: String? = null,
    @JsonProperty("zone_code") val zoneCode: String? = null,
    @JsonProperty("zone_name") val zoneName: String? = null,
    @JsonProperty("assigned_to") val assignedTo: String? = null,
    @JsonProperty("assigned_to_role") val assignedToRole: String? = null,
    @JsonProperty("assigned_by") val assignedBy: String? = null,
    @JsonProperty("compliance_target_id") val complianceTargetId: String? = null,
    @JsonProperty("rule_id") val ruleId: String? = null,
    @JsonProperty("rule_name") val ruleName: String? = null,
    @JsonProperty("reason") val reason: String? = null,
    @JsonProperty("delta_qty") val deltaQty: Int? = null,
    @JsonProperty("due_at") val dueAt: String? = null,
    @JsonProperty("started_at") val startedAt: String? = null,
    @JsonProperty("completed_at") val completedAt: String? = null,
    @JsonProperty("created_at") val createdAt: String? = null,
)

// ── §6.5.4 POST /api/v2/compliance/state — directive + per-fixture read-back (inventory:read) ─────

/**
 * Request. Shipped earlier as TWIN-REQ-003 (core PR #76) and documented into §6.5 on 2026-07-30 to
 * complete the family. ⚠ [siteRef] here is a site **UUID** — this endpoint predates slug resolution,
 * while the other three reads accept either.
 */
data class ComplianceStateRequest(@JsonProperty("directive_ref") val directiveRef: String, @JsonProperty("site_ref") val siteRef: String? = null)

/**
 * Response. ⚠ [activated] is a **truthful signal, not the lifecycle field** — directive/site
 * `activation_status` is not yet wired (both stay `pending` post-ingest), so `activated` means
 * "at least one target has been evaluated".
 */
data class ComplianceStateResponse(
    @JsonProperty("directive_ref") val directiveRef: String? = null,
    @JsonProperty("directive_id") val directiveId: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("effective_date") val effectiveDate: String? = null,
    @JsonProperty("activated") val activated: Boolean? = null,
    @JsonProperty("summary") val summary: Map<String, Int> = emptyMap(),
    @JsonProperty("total_targets") val totalTargets: Int = 0,
    @JsonProperty("resolved_targets") val resolvedTargets: Int = 0,
    @JsonProperty("evaluated_targets") val evaluatedTargets: Int = 0,
    @JsonProperty("compliance_score") val complianceScore: Double? = null,
)

// ── refusals (§6.5 "Error bodies") ────────────────────────────────────────────────────────────────

/**
 * The §6.5 refusal codes, as a closed set twin can branch on.
 *
 * ★ The distinction to code against is [DIRECTIVE_NOT_FOUND] (404) vs a **200 with `count: 0`**:
 * 404 means *fix your ref*; 200/0 means *the ref is right, nothing exists in that scope yet*.
 * Treating them the same is how an integrator retries a correct request forever, or gives up on a
 * working one.
 *
 * Note [SITE_NOT_FOUND] deliberately does not distinguish "no such site" from "a site in someone
 * else's tenant" — that would let a caller probe other tenants' slugs. A site *inside* your tenant
 * but outside a site-bound key's `site_scope` is a **403**, not this.
 */
enum class ReadRefusal(val code: String, val status: Int) {
    INVALID_REQUEST("INVALID_REQUEST", 400),
    SITE_NOT_FOUND("SITE_NOT_FOUND", 404),
    SPACE_NOT_FOUND("SPACE_NOT_FOUND", 404),
    ZONE_NOT_FOUND("ZONE_NOT_FOUND", 404),
    DIRECTIVE_NOT_FOUND("DIRECTIVE_NOT_FOUND", 404),
    ;

    companion object {
        fun of(code: String?): ReadRefusal? = entries.firstOrNull { it.code == code }
    }
}
