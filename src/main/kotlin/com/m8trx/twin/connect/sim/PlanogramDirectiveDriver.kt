package com.m8trx.twin.connect.sim

import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.WebhookClient
import com.m8trx.twin.connect.WebhookDataType
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.webhook.DirectiveTarget
import com.m8trx.twin.connect.model.webhook.PlanogramDirective
import com.m8trx.twin.connect.model.webhook.PlanogramDocument
import com.m8trx.twin.connect.model.webhook.PlanogramLine
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
        lines: List<PlanogramLine> = doc.lines,
    ): PlanogramDirective {
        val selected = if (fixtures.isEmpty()) lines else lines.filter { it.fixtureCode in fixtures }
        return PlanogramDirective(
            name = name,
            externalDirectiveId = externalDirectiveId,
            effectiveDate = effectiveDate,
            targets = selected.map {
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

    companion object {
        /** The default curated grain — the size of the Denver plan that has been live since S12. */
        const val CURATED_LINES = 28

        /** `PLN-<slug>-<yyyymmdd>` — one external ref per store AND per plan version (a re-send with the same ref rebuilds its targets). */
        fun externalRef(storeSlug: String, planDate: java.time.LocalDate): String =
            "PLN-$storeSlug-${planDate.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)}"

        /**
         * The curated slice of a store's plan: [n] lines spread over the store's **department walls** — the
         * fixtures the key export gives a department ([wallDepartments]: fixture code → department).
         *
         * Walls are ordered department-interleaved (departments sorted, codes sorted inside each, then
         * round-robin), so the first lines touch every department before any department gets a second wall.
         * Pass k takes each wall's k-th line by `position_sequence`; one line per wall while walls last, a
         * second pass only where a store has fewer walls than [n] (Bordeaux, Marseille and Busan). Pure and
         * deterministic — no RNG — so the same plan file always yields the same 28 targets.
         */
        fun curate(doc: PlanogramDocument, wallDepartments: Map<String, String>, n: Int = CURATED_LINES): List<PlanogramLine> {
            val byFixture = doc.lines.filter { it.fixtureCode in wallDepartments }
                .groupBy { it.fixtureCode }
                .mapValues { (_, ls) -> ls.sortedWith(compareBy({ it.positionSequence }, { it.ean })) }
            val perDept = byFixture.keys.groupBy { wallDepartments.getValue(it) }.toSortedMap().mapValues { (_, cs) -> cs.sorted() }
            val walls = ArrayList<String>()
            val depth = perDept.values.maxOfOrNull { it.size } ?: 0
            for (i in 0 until depth) perDept.values.forEach { cs -> cs.getOrNull(i)?.let(walls::add) }
            val out = ArrayList<PlanogramLine>()
            val maxPass = byFixture.values.maxOfOrNull { it.size } ?: 0
            for (k in 0 until maxPass) {
                for (code in walls) {
                    if (out.size >= n) return out
                    byFixture.getValue(code).getOrNull(k)?.let(out::add)
                }
            }
            return out
        }
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
