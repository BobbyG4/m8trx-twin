package com.m8trx.twin.connect.sim

import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.WebhookClient
import com.m8trx.twin.connect.WebhookDataType
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.webhook.DirectiveEnvelope
import com.m8trx.twin.connect.model.webhook.PlanogramDocument
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Simulator — Planogram-directive driver (Connect Mode 3, PLANOGRAM-RESOLVED-DESIGN-2026-06-30 §6).
 *
 * The twin acting as the external planogram tool: loads a store's m8trx_standard planogram document
 * (`scripts/build_planogram.py` → `stores/<id>/planogram.json`), wraps it in the §6.1 directive
 * envelope (`directive_kind='planogram'`), and posts it over the ONE shared inbound-directive Connect
 * channel (fork #11) — the same signed-bytes-once transport as the §8 webhook ingest.
 *
 * GATED on core shipping the inbound-directive channel (`mig 152a` / fork #11). Until then [dryRun]
 * builds + serializes the exact envelope (offline, self-tested in [com.m8trx.twin.connect.ConnectHarness])
 * and [drive] is wired-but-unfired. The `X-Data-Type` route + as-built wire shape get code-verified
 * against the live channel the day it lands (the S9 lesson: confirm against the real ingester, not the doc).
 */
class PlanogramDirectiveDriver(private val webhook: WebhookClient, private val integrationId: String) {
    private val log = LoggerFactory.getLogger(PlanogramDirectiveDriver::class.java)

    /** Load an m8trx_standard planogram document from disk (snake plane → camel DTO; builder metadata dropped). */
    fun loadDocument(planogramJson: Path): PlanogramDocument = ConnectMappers.snake.readValue(Files.readAllBytes(planogramJson))

    /** Wrap a document in the §6.1 `directive_kind='planogram'` envelope. */
    fun envelope(doc: PlanogramDocument, effectiveDate: String): DirectiveEnvelope = DirectiveEnvelope.planogram(integrationId, doc, effectiveDate)

    /**
     * Serialize the directive envelope WITHOUT sending — there is no live channel yet (B1). Returns the
     * snake_case wire JSON: the offline assertion path (self-tested) and the dry-run log shape.
     */
    fun dryRun(doc: PlanogramDocument, effectiveDate: String): String = ConnectMappers.snake.writeValueAsString(envelope(doc, effectiveDate))

    /**
     * LIVE: POST the directive to the inbound-directive channel. GATED on `mig 152a` / fork #11 — fire
     * once the channel exists; verify landing via the compliance read-back (the connectSelfVerify analog).
     */
    fun drive(doc: PlanogramDocument, effectiveDate: String, auth: WebhookClient.AuthMode = WebhookClient.AuthMode.HMAC): ConnectResponse {
        val resp = webhook.push(WebhookDataType.DIRECTIVE, envelope(doc, effectiveDate), auth)
        when (resp) {
            is ConnectResponse.Ok ->
                log.info("drivePlanogram site={} lines={} ref={} ack status={}", doc.siteRef, doc.lines.size, doc.directiveRef, resp.status)
            is ConnectResponse.Err ->
                log.error(
                    "drivePlanogram site={} failed status={} code={} message={}",
                    doc.siteRef,
                    resp.error.status,
                    resp.error.code,
                    resp.error.message,
                )
        }
        return resp
    }
}
