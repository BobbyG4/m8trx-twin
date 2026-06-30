package com.m8trx.twin.connect.model.webhook

/**
 * Mode-3 planogram directive — the **AS-BUILT** ingest shape (services #64, BACKEND 2026-06-30), which
 * SUPERSEDES the earlier `PLANOGRAM-RESOLVED-DESIGN` §6.1 envelope sketch (the S9 lesson: confirm against
 * the real ingester, not the doc).
 *
 * Routing: POST to the existing inbound webhook `/v1/webhook/{tenant}/twin-pos` with header
 * `X-Data-Type: planogram_directive` ([com.m8trx.twin.connect.WebhookDataType.PLANOGRAM_DIRECTIVE]) — rides
 * the existing twin-pos auth; no dedicated channel needed to fire. Serialized snake_case on the inbound
 * plane via [com.m8trx.twin.connect.model.ConnectMappers.snake].
 *
 * Lands as: `compliance_directive` (source=api_push, status=pending) + `compliance_directive_site` (per
 * distinct `site_id`) + one `compliance_target` per placement (`mapping_status=pending_zone_mapping` — the
 * `raw_fixture_code → zone` resolver is a LATER slice).
 *
 * Wire (snake): `name`, `external_directive_id`, `effective_date`, `targets[]`.
 */
data class PlanogramDirective(
    val name: String,
    val externalDirectiveId: String, // idempotency key — re-send replaces this directive's sites/targets
    val effectiveDate: String?, // optional; the ingester defaults to now
    val targets: List<DirectiveTarget>,
)

/**
 * One placement. [siteId] is per-target (a directive is multi-site) and is the **real M8TRX site UUID** —
 * NOT a `site_ref` (this slice does no xref resolution). [rawItemIdentifier] (the EAN) + the presentation
 * fields are optional; [rawFixtureCode] is carried **unresolved** (`pending_zone_mapping`).
 *
 * Wire (snake): `site_id`, `raw_fixture_code`, `raw_item_identifier`, `required_quantity`, `facing_count`,
 * `position_sequence`, `display_level`.
 */
data class DirectiveTarget(
    val siteId: String,
    val rawFixtureCode: String,
    val rawItemIdentifier: String? = null,
    val requiredQuantity: Int,
    val facingCount: Int? = null,
    val positionSequence: Int? = null,
    val displayLevel: Int? = null,
)

/**
 * The twin's internal **m8trx_standard** planogram document (emitted by `scripts/build_planogram.py` →
 * `stores/<id>/planogram.json`). This is the twin's SOURCE format; the driver maps it to the as-built
 * [PlanogramDirective] (resolving the real site UUID) at send time. Kept distinct from the wire shape so
 * the committed document carries no env/tenant-specific UUIDs and stays rich (`fixture_name`, `department`…).
 * Doc-level builder metadata (`units_per_facing`, `notes`, `counts`) is dropped on read (snake mapper
 * tolerates unknown keys).
 */
data class PlanogramDocument(
    val format: String,
    val version: String,
    val directiveRef: String,
    val storeId: String,
    val siteRef: String,
    val sourceDigest: String,
    val lines: List<PlanogramLine>,
)

data class PlanogramLine(
    val fixtureCode: String,
    val fixtureName: String,
    val spaceType: String,
    val department: String,
    val sku: String,
    val ean: String,
    val name: String,
    val lineItemType: String,
    val requiredQty: Int,
    val facings: Int,
    val positionSequence: Int,
)
