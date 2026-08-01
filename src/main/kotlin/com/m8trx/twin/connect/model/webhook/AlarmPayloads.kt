package com.m8trx.twin.connect.model.webhook

import com.fasterxml.jackson.annotation.JsonAnyGetter

/**
 * `alarm` — the seventh §8 inbound data-type (Connect API doc **§8.1**, published 2026-07-31).
 *
 * **Twin drives this as a third-party EAS gate vendor, not as M8TRX.** That framing is the whole
 * point: §8.1 was written for a vendor M8TRX does not control, M8TRX's own EAS module is parked, and
 * so the first implementation of this contract is a stranger's. Everything twin cannot do from
 * outside the building is a finding rather than an obstacle to route around.
 *
 * ## ⚠ Two published shapes, and they disagree — which is why this DTO carries both
 *
 * The envelope is specified twice and the specifications differ where it matters to a vendor:
 *
 * | | §8.1 (published contract) | `DESIGN-ALERT-INGEST-API` §2 |
 * |---|---|---|
 * | source-specific detail | **top level** (`epc_list`, `antenna_group`, `native_level`) | nested under **`payload`** |
 * | what the alarm is about | `subject_type` + **`subject_id`**, exampled as `"…uuid…"` | `subject_type` + **`subject_ref`** |
 *
 * The second row is the load-bearing one. **`subject_id` as a UUID is an identifier a vendor does not
 * have** — an EAS gate reads an EPC off a tag; it has never seen an M8TRX `thing` UUID and no §6.5
 * read returns one from an EPC. That is precisely the `items/receive`-requires-a-`spaceId` defect one
 * layer up, and the design doc's `subject_ref` (a *ref*, per §6.5 rule 1) is the shape that avoids it.
 *
 * So this DTO models the union and lets the caller choose per send: [extras] serializes at the top
 * level ([JsonAnyGetter], keys verbatim), [payload] nests, and both subject spellings are present and
 * NON_NULL-omitted. Twin sends the §8.1 shape first — that is what a vendor reading the published
 * contract would author — and the §2 shape second, so the difference between them is *measured*
 * rather than assumed. Neither is "fixed" locally to make a send succeed.
 *
 * ## What twin deliberately does NOT send
 *
 * - **No coordinate.** §8.1 has no coordinate field and says so on purpose: a coordinate without a
 *   declared frame reads as data and is not. Twin supplied the finding that made that explicit —
 *   `scan_event.position` is twin's own seed in per-space unregistered mm frames (SRID 0, Z=0), so
 *   even M8TRX cannot compare positions across two spaces. A vendor is strictly worse off: it was
 *   never told the frame. Alarms are placed with [zoneRef].
 * - **No claim that M8TRX knows about the tag.** EAS tag state is the *vendor's* — see
 *   [com.m8trx.twin.layer2.EasTagging]. Twin minting it is faithful emulation exactly as long as it
 *   is never implied to be something the platform stores, derives or could verify.
 *
 * Casing is `snake_case` like every other §8 payload — serialize through
 * [com.m8trx.twin.connect.model.ConnectMappers.snake].
 */
data class AlarmEnvelope(
    /** ✅ Must be a **registered** `alert_source`. Unregistered → refused and dead-lettered, not dropped. */
    val source: String,
    /**
     * ✅ The vendor's own vocabulary, registered per source (`alert_source_kind`).
     *
     * ⚠ `alert_source_kind` held **0 rows** at run time, so the first alarm of a kind **auto-registers
     * it** with deliberately timid defaults (`severity=info`, `lifecycle=point_event`). Combined with
     * `may_set_severity=false` on the source, a theft alarm lands as `info` and no proposal can lift
     * it. That is the system behaving as designed and is the single most useful thing this send
     * measures — so twin sends cold rather than asking for a kind to be pre-registered.
     */
    val kind: String,
    /** ✅ The site **slug** the vendor was onboarded with — never a primary key it has no way to know. */
    val siteRef: String,
    /** ✅ **Event time, not send time.** ISO-8601 or epoch millis. */
    val occurredAt: String,
    /** ✅ for point events. Idempotent across **all** statuses forever: a retry after resolution must not create a second alarm. */
    val dedupeKey: String? = null,
    /** ✅ for conditions instead of [dedupeKey] — identity of the *condition*, which is how it auto-clears. */
    val conditionKey: String? = null,
    /**
     * The gate. Zone UUID · M8TRX zone code · or the vendor's own `external_code` registered via
     * `fixture_code_mapping` — §8.1 recommends the third, and §8.2 records that a Connect key cannot
     * register one (`POST /compliance/fixture-codes` is not Connect-reachable). Unresolvable is
     * documented as *"the alarm still lands, site-level, and we log it"*.
     */
    val zoneRef: String? = null,
    /** A **proposal**, honored only if the source is registered `may_set_severity`. Otherwise kept verbatim as `native_level`. */
    val severity: String? = null,
    val title: String? = null,
    val description: String? = null,
    val subjectType: String? = null,
    /** §8.1 spelling. Exampled as a UUID — an identifier no vendor holds. See the class note. */
    val subjectId: String? = null,
    /** `DESIGN` §2 spelling, and the one a vendor can actually fill: an EPC it read off the tag. */
    val subjectRef: String? = null,
    /** `DESIGN` §2 nesting for source-specific detail. Mutually exclusive with [extras] in practice, not in the type. */
    val payload: Map<String, Any?>? = null,
    /**
     * §8.1's *"anything else — your fields stay yours"*, serialized at the **top level** with keys
     * verbatim (no naming strategy is applied to any-getter keys, so supply them already snake_case).
     */
    @get:JsonAnyGetter val extras: Map<String, Any?> = emptyMap(),
)

/**
 * The `200` body from the webhook plane. ⚠ **`accepted:true` means *received*, not *processed*** —
 * `WebhookDispatcher` is `@Async` by documented contract for all seven data-types, so the response
 * returns before ingest runs. Verify by observing the `alert` row, never by the status code.
 *
 * The evidence for that caution is **S11, not alarms**: five real core defects were all sitting behind
 * `200` acks and surfaced only because twin insisted on server-side verification (plus the
 * dedup-shadows-failed-retry finding). ⚠ *An earlier draft of this note claimed twin had watched an
 * alarm ack `200` and then dead-letter. Twin has never sent an alarm outside dry-run, and holds
 * neither `alert:read` nor `integration:manage` to read a DLQ — so it cannot have observed that.
 * Corrected 2026-08-01: the lesson is real and transferable, the first-person alarm sighting was not.*
 *
 * ⚠ Parse this with [com.m8trx.twin.connect.model.ConnectMappers.camel], not `snake`: the §8 *request*
 * bodies are snake_case but §8's documented ack is `{"accepted","integrationId","receivedAt"}` —
 * camelCase, on the same call. The casing boundary is per-direction here, not per-plane.
 */
data class WebhookAck(val accepted: Boolean? = null, val integrationId: String? = null, val receivedAt: String? = null)
