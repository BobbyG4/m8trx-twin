package com.m8trx.twin.connect

import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.bearer.ComplianceStateRequest
import com.m8trx.twin.connect.model.bearer.ImpressionQueryRequest
import com.m8trx.twin.connect.model.bearer.SpatialIdentityRequest
import com.m8trx.twin.connect.model.bearer.TaskQueryRequest
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * `./gradlew connectReadProbe` — which of the four §6.5 reads does **this key** actually hold?
 *
 * ## Why this is its own task
 *
 * §6.5 shipped live on 2026-07-30, but `@ConnectExposed` only makes an endpoint *reachable* — the key
 * still needs the capability, and the doc is explicit that **pre-SEC-3 keys hold no `vision_ai:*`**
 * and that **no existing key holds `task:read`**. So "the read surface is live" and "twin can call it"
 * are different claims, and the gap between them is a `403` that says nothing about the endpoint being
 * broken. Twin's own key currently carries `integration:manage` + `scan:submit` + `inventory:create` +
 * `inventory:read` — enough for two of the four, and not the two that matter most.
 *
 * This probe answers that in one cheap read-only pass, per endpoint, with the refusal code named. It
 * is the thing to run first on any box, after any key rotation, and after any scope grant.
 *
 * ## Reading the result
 *
 *  - `200` — held. (For `/tasks/query` a `404 DIRECTIVE_NOT_FOUND` ALSO proves the capability: the
 *    request got past the scope gate and into ref resolution. That is a PASS for this probe's purpose,
 *    and the probe says so rather than reporting a bare failure.)
 *  - `403` — **capability missing, not endpoint missing.** Grant with
 *    `PATCH /api/v2/connect/service-keys/{keyId}/scopes` (§7).
 *  - `404 SITE_NOT_FOUND` — the key is fine; `M8TRX_PROBE_SITE_REF` names a site this tenant/key
 *    cannot see. Note the doc's deliberate ambiguity here: "no such site" and "someone else's tenant"
 *    are the same answer, so a caller cannot probe other tenants' slugs.
 *
 * READ-ONLY — fires nothing, writes nothing. Env: `M8TRX_PROBE_SITE_REF` (default `dec-us-denver`),
 * `M8TRX_PROBE_DIRECTIVE_REF` (optional; without it the task probe uses a deliberately-absent ref and
 * treats 404 as the pass signal).
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ConnectReadProbe")

/** A probe's verdict, kept separate from the raw HTTP so the summary can explain rather than dump. */
private enum class Verdict { HELD, MISSING_CAPABILITY, REF_NOT_FOUND, OTHER }

private data class ProbeResult(val name: String, val scope: String, val verdict: Verdict, val detail: String)

fun main() {
    val client = ConnectClient(ConnectConfig.fromEnv())
    val siteRef = System.getenv("M8TRX_PROBE_SITE_REF") ?: "dec-us-denver"
    val directiveRef = System.getenv("M8TRX_PROBE_DIRECTIVE_REF")
    val to = Instant.now()
    val from = to.minus(1, ChronoUnit.HOURS)

    log.info("Connect §6.5 read probe — site_ref={} (read-only, nothing is written)", siteRef)

    val results =
        listOf(
            probe("spatial/identity", "inventory:read") {
                // include_zones=false keeps this a cheap site/space skeleton — capability, not payload.
                client.spatialIdentity(SpatialIdentityRequest(siteRef = siteRef, includeZones = false))
            },
            probe("visionai/impressions/query", "vision_ai:view") {
                client.queryImpressions(ImpressionQueryRequest(siteRef = siteRef, from = from.toString(), to = to.toString(), limit = 1))
            },
            probe("tasks/query", "task:read") {
                client.queryTasks(TaskQueryRequest(directiveRef = directiveRef ?: "PROBE-no-such-directive", siteRef = siteRef, limit = 1))
            },
            probe("compliance/state", "inventory:read") {
                client.complianceState(ComplianceStateRequest(directiveRef = directiveRef ?: "PROBE-no-such-directive"))
            },
        )

    log.info("──── §6.5 read capability for this key ────")
    results.forEach { r ->
        val mark = when (r.verdict) {
            Verdict.HELD -> "HELD    "
            Verdict.MISSING_CAPABILITY -> "MISSING "
            Verdict.REF_NOT_FOUND -> "HELD*   "
            Verdict.OTHER -> "?       "
        }
        // slf4j takes only `{}` — a format specifier like `{:<34}` prints literally and shifts every
        // argument one position right, which is how the first live run reported the endpoint name in
        // the detail column and dropped the refusal message entirely. Pad before handing it over.
        log.info("  {} {} scope={} {}", mark, r.name.padEnd(34), r.scope.padEnd(16), r.detail)
    }

    val held = results.count { it.verdict == Verdict.HELD || it.verdict == Verdict.REF_NOT_FOUND }
    val missing = results.filter { it.verdict == Verdict.MISSING_CAPABILITY }
    log.info("{}/{} reads callable with this key", held, results.size)
    if (missing.isNotEmpty()) {
        log.warn(
            "MISSING capability: {} — grant via PATCH /api/v2/connect/service-keys/{{keyId}}/scopes (§7). " +
                "This is a KEY gap, not an endpoint gap: the endpoints are live since PR #210.",
            missing.joinToString(", ") { it.scope },
        )
    }
    log.info("HELD* = a 404 on the ref, which still proves the request cleared the scope gate.")
}

private fun probe(name: String, scope: String, call: () -> ConnectResponse): ProbeResult = try {
    when (val resp = call()) {
        is ConnectResponse.Ok -> ProbeResult(name, scope, Verdict.HELD, "200")

        is ConnectResponse.Err -> {
            val e = resp.error
            val verdict = when {
                e.status == 403 -> Verdict.MISSING_CAPABILITY
                e.status == 404 -> Verdict.REF_NOT_FOUND
                else -> Verdict.OTHER
            }
            // The message matters: on first release §6.5 refusals lost their message before
            // serialization, so a caller saw a bare {"status":404,"error":"Not Found"} and could
            // not tell WHICH ref missed. If message is null here, this box is talking to a
            // pre-fix deployment — worth knowing, so surface the raw body rather than hide it.
            val detail = "${e.status} ${e.code ?: "(no code)"} ${e.message ?: "(no message — pre-fix deployment? raw=${e.rawBody.take(80)})"}"
            ProbeResult(name, scope, verdict, detail)
        }
    }
} catch (ex: IllegalArgumentException) {
    ProbeResult(name, scope, Verdict.OTHER, "local validation refused: ${ex.message}")
}
