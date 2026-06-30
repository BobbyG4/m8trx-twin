package com.m8trx.twin.connect.model.webhook

/**
 * Mode-3 Connect inbound DIRECTIVE envelope + the m8trx_standard planogram document it carries.
 *
 * The twin is the MVP external driver that posts a planogram directive over the ONE shared
 * inbound-directive Connect channel (fork #11), discriminated by [directiveKind]. Built to the
 * `PLANOGRAM-RESOLVED-DESIGN-2026-06-30` §6.1 envelope contract; the channel + site-resolution are a
 * named Connect transport DEPENDENCY (`mig 152a` / fork #11), NOT designed here. Serialized snake_case
 * on the inbound plane via [com.m8trx.twin.connect.model.ConnectMappers.snake], exactly like the §8
 * ingester payloads.
 *
 * Wire shape (snake): `directive_kind`, `integration_id`, `source_format`, `site_ref`,
 * `effective_date`, `payload`. Connect resolves `site_ref → site_id` via `integration_site_xref`
 * (INHERITS); the per-site fixture-code resolution (`fixture_code → zone_id`) is core-side and
 * NON-inheriting (R5) — the twin sends the raw `fixture_code`, which on the demo tenant matches
 * `zone.name`/`code` and resolves by exact-name match (resolver step 1, no mapping rows needed).
 */
data class DirectiveEnvelope(
    val directiveKind: String, // "planogram" | "compliance" | "fulfillment" (fork #11 discriminator)
    val integrationId: String,
    val sourceFormat: String, // → directive_format_profile.schema_fingerprint match (e.g. "m8trx_standard")
    val siteRef: String, // Connect resolves → site_id via integration_site_xref
    val effectiveDate: String, // ISO-8601 → compliance_directive.effective_date
    val payload: PlanogramDocument,
) {
    companion object {
        fun planogram(integrationId: String, doc: PlanogramDocument, effectiveDate: String) = DirectiveEnvelope(
            directiveKind = "planogram",
            integrationId = integrationId,
            sourceFormat = doc.format,
            siteRef = doc.siteRef,
            effectiveDate = effectiveDate,
            payload = doc,
        )
    }
}

/**
 * The m8trx_standard planogram document (emitted by `scripts/build_planogram.py`). Each [PlanogramLine]
 * maps to a `compliance_target` row (PLANOGRAM-RESOLVED-DESIGN §1): `fixture_code → zone_id`,
 * `sku → product_id`, `required_qty → required_quantity`, `facings → facing_count`,
 * `position_sequence → position_sequence`. Doc-level builder metadata (`units_per_facing`, `notes`,
 * `counts`) is intentionally NOT modelled — unknown keys drop on read (the snake mapper tolerates them),
 * keeping the wire payload lean.
 */
data class PlanogramDocument(
    val format: String, // "m8trx_standard"
    val version: String, // "v1"
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
    val lineItemType: String, // "product_placement" | "fixture_relocation"
    val requiredQty: Int,
    val facings: Int,
    val positionSequence: Int,
)
