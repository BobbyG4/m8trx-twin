package com.m8trx.twin.connect.sim

import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.WebhookClient
import com.m8trx.twin.connect.WebhookDataType
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.webhook.DirectiveTarget
import com.m8trx.twin.connect.model.webhook.PlanogramDirective
import com.m8trx.twin.connect.model.webhook.PlanogramDocument
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Planogram-directive driver (Connect Mode 3) — built to the **AS-BUILT** ingest shape (services #64).
 *
 * Loads a store's m8trx_standard planogram document (`scripts/build_planogram.py` → `planogram.json`), maps
 * it to the as-built [PlanogramDirective] (resolving the real site UUID per target, optionally filtered to a
 * fixture subset for a bounded smoke), and POSTs it to `/v1/webhook/{tenant}/twin-pos` with
 * `X-Data-Type: planogram_directive`. Lands as `compliance_directive` + `_site` + one `compliance_target`
 * per placement.
 *
 * GATED on BACKEND confirming services #64 is merged + deployed — until then [drive] is wired-but-unfired
 * ("hold fire") and [dryRun] is the offline assertion path.
 */
class PlanogramDirectiveDriver(private val webhook: WebhookClient) {
    private val log = LoggerFactory.getLogger(PlanogramDirectiveDriver::class.java)

    /** Load an m8trx_standard planogram document from disk. */
    fun loadDocument(planogramJson: Path): PlanogramDocument = ConnectMappers.snake.readValue(Files.readAllBytes(planogramJson))

    /**
     * Map a planogram document to the as-built [PlanogramDirective] for a single resolved [siteId]. A
     * non-empty [fixtures] restricts the targets to those fixture codes (a bounded smoke). EAN →
     * `raw_item_identifier`; required-qty / facings / position carry through.
     */
    fun toDirective(
        doc: PlanogramDocument,
        siteId: String,
        externalDirectiveId: String = doc.directiveRef,
        name: String = "Twin planogram — ${doc.storeId} (${doc.directiveRef})",
        effectiveDate: String? = null,
        fixtures: Set<String> = emptySet(),
    ): PlanogramDirective {
        val lines = if (fixtures.isEmpty()) doc.lines else doc.lines.filter { it.fixtureCode in fixtures }
        return PlanogramDirective(
            name = name,
            externalDirectiveId = externalDirectiveId,
            effectiveDate = effectiveDate,
            targets = lines.map {
                DirectiveTarget(
                    siteId = siteId,
                    rawFixtureCode = it.fixtureCode,
                    rawItemIdentifier = it.ean,
                    requiredQuantity = it.requiredQty,
                    facingCount = it.facings,
                    positionSequence = it.positionSequence,
                )
            },
        )
    }

    /** Serialize the directive WITHOUT sending — the offline assertion path + the dry-run log shape. */
    fun dryRun(directive: PlanogramDirective): String = ConnectMappers.snake.writeValueAsString(directive)

    /** LIVE: POST at `X-Data-Type: planogram_directive`. Creates real compliance targets — opt in explicitly. */
    fun drive(directive: PlanogramDirective, auth: WebhookClient.AuthMode = WebhookClient.AuthMode.HMAC): ConnectResponse {
        val resp = webhook.push(WebhookDataType.PLANOGRAM_DIRECTIVE, directive, auth)
        when (resp) {
            is ConnectResponse.Ok ->
                log.info(
                    "drivePlanogram name=\"{}\" extId={} targets={} ack status={}",
                    directive.name,
                    directive.externalDirectiveId,
                    directive.targets.size,
                    resp.status,
                )

            is ConnectResponse.Err ->
                log.error(
                    "drivePlanogram extId={} failed status={} code={} message={}",
                    directive.externalDirectiveId,
                    resp.error.status,
                    resp.error.code,
                    resp.error.message,
                )
        }
        return resp
    }
}
