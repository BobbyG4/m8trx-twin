package com.m8trx.twin.connect.sim

import com.m8trx.twin.connect.ConnectClient
import com.m8trx.twin.connect.WebhookClient
import com.m8trx.twin.connect.WebhookDataType
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.connect.model.bearer.AlertQueryRequest
import com.m8trx.twin.connect.model.webhook.AlarmEnvelope
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * The §8.1 alarm driver — twin as a **third-party EAS gate vendor** pushing alarms into M8TRX.
 *
 * ## What this is for
 *
 * §A of the Connect definition of done is the only item where *done* means **something traversed the
 * path**: `external source → alert row → routed to an LP role → visible on /alerts → dispositioned`.
 * Nothing had ever traversed it. This driver sends the first half; whether the second half is
 * *observable* is itself the measurement, because a vendor's only documented diagnostics are the
 * §7 DLQ and `/alerts/query` and twin's key holds the scope for neither.
 *
 * ## The trap this driver is built around
 *
 * **`200 {accepted:true}` is not evidence.** `WebhookDispatcher` is `@Async` by documented contract
 * for all seven data-types, so the ack returns *before* ingest runs — and in S11 five real core
 * defects sat behind `200` acks on other data-types, found only by verifying server-side. *(This note
 * previously claimed twin had seen an alarm ack 200 then dead-letter; twin has never sent one outside
 * dry-run and cannot read a DLQ. Corrected 2026-08-01.)* So [drive] logs the ack as a *receipt*, never as a result, and
 * [verify] is a separate step whose honest answer is allowed to be **UNPROVEN**. Reporting
 * "unproven" is a real outcome here; substituting a `psql` twin does not hold, and a vendor could
 * never run, would prove nothing about the contract.
 *
 * ## Two envelope shapes, sent deliberately
 *
 * §8.1 and `DESIGN-ALERT-INGEST-API` §2 disagree (top-level extras vs nested `payload`; `subject_id`
 * vs `subject_ref`) — see [AlarmEnvelope]. [gateExitUnpaid] builds the **published §8.1** shape,
 * which is what a vendor reading the contract would author; [gateExitUnpaidDesignShape] builds the
 * §2 shape. Sending both is how the difference gets measured instead of guessed.
 */
class AlarmDriver(private val webhook: WebhookClient, private val client: ConnectClient? = null) {
    private val log = LoggerFactory.getLogger(AlarmDriver::class.java)

    /**
     * The §8.1 shape: source detail at the **top level**, exactly as the published example renders it.
     *
     * ⚠ One documented field is deliberately not sent as documented. §8.1 examples `subject_id` as
     * `"…uuid…"`, and **a gate vendor has no M8TRX UUID** — it reads an EPC off a tag, and no §6.5
     * read maps an EPC to a `thing` id. Twin sends `subject_ref` (the design-§2 spelling, and a *ref*
     * per §6.5 rule 1) carrying the EPC, and reports the substitution rather than hiding it.
     */
    fun gateExitUnpaid(
        source: String,
        siteSlug: String,
        gateCode: String,
        epc: String,
        occurredAtMillis: Long,
        proposedSeverity: String = "critical",
        antennaGroup: String = "A2",
        kind: String = KIND_GATE_EXIT_UNPAID,
    ): AlarmEnvelope = AlarmEnvelope(
        source = source,
        kind = kind,
        siteRef = siteSlug,
        zoneRef = gateCode,
        occurredAt = Instant.ofEpochMilli(occurredAtMillis).toString(),
        dedupeKey = dedupeKey(gateCode, epc, occurredAtMillis),
        severity = proposedSeverity,
        title = "Unpaid tagged item passed $gateCode",
        subjectType = "item",
        subjectRef = epc,
        extras = mapOf(
            "epc_list" to listOf(epc),
            "antenna_group" to antennaGroup,
            "native_level" to "Alarm-1",
            "footfall_direction" to "outbound",
            "staff_mode_active" to false,
            "gate_id" to gateCode,
        ),
    )

    /** The `DESIGN` §2 shape: identical facts, source detail nested under `payload`. */
    fun gateExitUnpaidDesignShape(
        source: String,
        siteSlug: String,
        gateCode: String,
        epc: String,
        occurredAtMillis: Long,
        proposedSeverity: String = "critical",
        antennaGroup: String = "A3",
        kind: String = KIND_GATE_EXIT_UNPAID,
    ): AlarmEnvelope = AlarmEnvelope(
        source = source,
        kind = kind,
        siteRef = siteSlug,
        zoneRef = gateCode,
        occurredAt = Instant.ofEpochMilli(occurredAtMillis).toString(),
        dedupeKey = dedupeKey(gateCode, epc, occurredAtMillis),
        severity = proposedSeverity,
        title = "Unpaid tagged item passed $gateCode",
        subjectType = "item",
        subjectRef = epc,
        payload = mapOf(
            "epc_list" to listOf(epc),
            "antenna_group" to antennaGroup,
            "native_level" to "Alarm-1",
            "footfall_direction" to "outbound",
            "staff_mode_active" to false,
            "gate_id" to gateCode,
        ),
    )

    /** `gate:epc:millis` — the §8.1 example's own composition. Stable, so a retry reproduces it byte-for-byte. */
    fun dedupeKey(gateCode: String, epc: String, occurredAtMillis: Long): String = "$gateCode:$epc:$occurredAtMillis"

    /** Render without sending — the offline assertion path and the dry-run log shape. */
    fun dryRun(alarm: AlarmEnvelope): String = ConnectMappers.snake.writeValueAsString(alarm)

    /**
     * LIVE: POST at `X-Data-Type: alarm`.
     *
     * The returned [ConnectResponse] is a **receipt of delivery, not of ingest** — see the class note.
     * Callers must follow with [verify].
     */
    fun drive(alarm: AlarmEnvelope, auth: WebhookClient.AuthMode = WebhookClient.AuthMode.API_KEY): ConnectResponse {
        val resp = webhook.push(WebhookDataType.ALARM, alarm, auth)
        when (resp) {
            is ConnectResponse.Ok ->
                log.info(
                    "alarm SENT source={} kind={} site={} zone={} dedupe={} → {} (RECEIPT ONLY — async ingest, not proof it landed)",
                    alarm.source,
                    alarm.kind,
                    alarm.siteRef,
                    alarm.zoneRef,
                    alarm.dedupeKey,
                    resp.status,
                )

            is ConnectResponse.Err ->
                log.error(
                    "alarm REFUSED source={} kind={} dedupe={} status={} code={} message={}",
                    alarm.source,
                    alarm.kind,
                    alarm.dedupeKey,
                    resp.error.status,
                    resp.error.code,
                    resp.error.message,
                )
        }
        return resp
    }

    /**
     * Try every read a vendor is *documented* to have, and report which are actually callable.
     *
     * Returns the raw responses rather than a verdict: an outside prover's job is to report what the
     * surface did, and "all three refused" is the finding, not a failure to try hard enough.
     */
    fun verify(integrationId: String?, source: String, siteSlug: String?, since: Instant): VerifyAttempt {
        val c = client ?: return VerifyAttempt(null, null, null)
        val alerts = runCatching {
            c.queryAlerts(
                AlertQueryRequest(
                    siteRef = siteSlug,
                    source = listOf(source),
                    from = since.toString(),
                    to = Instant.now().plusSeconds(60).toString(),
                ),
            )
        }.getOrElse { return VerifyAttempt(null, null, null, it.message) }
        val dlq = integrationId?.let { id -> runCatching { c.getDeadLetter(id) }.getOrNull() }
        val health = integrationId?.let { id -> runCatching { c.getHealth(id) }.getOrNull() }
        return VerifyAttempt(alerts, dlq, health)
    }

    /**
     * What the three documented diagnostics answered. Each is nullable because "not attempted" and
     * "attempted and refused" are different states and collapsing them would overstate coverage.
     */
    data class VerifyAttempt(
        val alertsQuery: ConnectResponse?,
        val deadLetter: ConnectResponse?,
        val health: ConnectResponse?,
        val error: String? = null,
    ) {
        /** True only if at least one documented diagnostic actually answered. */
        val anyObservable: Boolean
            get() = listOfNotNull(alertsQuery, deadLetter, health).any { it.isOk }
    }

    companion object {
        /** The vendor's own vocabulary — unregistered at run time, so the first send auto-registers it. */
        const val KIND_GATE_EXIT_UNPAID = "gate_exit_unpaid"
    }
}
