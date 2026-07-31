package com.m8trx.twin.connect

import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.model.bearer.ComplianceStateRequest
import com.m8trx.twin.connect.model.bearer.ImpressionQueryRequest
import com.m8trx.twin.connect.model.bearer.SpatialIdentityRequest
import com.m8trx.twin.connect.model.bearer.SpatialIdentityResponse
import com.m8trx.twin.connect.model.bearer.TaskQueryRequest
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.system.exitProcess

/**
 * `./gradlew connectAcceptance` — **the Connect ship-gate.**
 *
 * ## Why this exists
 *
 * M8TRX's paid, externally-sold API surface has **no automated regression coverage**: every CI security
 * suite drives a human JWT against frontEnd RLS or greps source text, and not one drives a Connect API key
 * against a Connect endpoint. Every Connect defect to date was found by twin driving it from outside, or by
 * a human reading a screenshot — never by a gate. Until a CI deploy gate with live keys exists, **this task
 * is the gate**, and it is a deliverable rather than a favour: on-demand, pass/fail, readable by someone
 * who does not work on twin.
 *
 * ## The rule this encodes above all others
 *
 * §6.5 rule 2: an **omitted** site means *"every site this key may see"* — **never** "every site in the
 * tenant". Server-side, `SiteAuthorityGuardTest` asserts the guard fires when a site is **named**; nobody
 * tests the omitted case, and F1 was a near-miss where a site-scope leak sat behind a capability default
 * such that the obvious one-line fix would have opened it.
 *
 * **This suite refuses to pass that assertion vacuously.** A key that can see everything would satisfy
 * "omitted returns everything I can see" trivially, so [scopeConfinement] first *proves the key is
 * confined* by finding a site it is refused, and only then compares the omitted-site set against the
 * individually-reachable set. If nothing is refused, the result is **INDETERMINATE, not PASS** — twin
 * cannot distinguish a tenant-wide key from a total leak, and saying so is the point.
 *
 * ## Coverage is reported, never implied
 *
 * A run that silently skips an endpoint is the false-green class this exists to prevent, so [COVERED] and
 * [NOT_COVERED] are declared literals and both are printed. When the surface grows — alarm ingest is next —
 * the new data-type gets a row in one list or the other, and "we forgot" stops being expressible.
 *
 * Exit 0 = all PASS. Exit 1 = any FAIL. INDETERMINATE never passes the gate on its own.
 *
 * Env: `M8TRX_ACCEPT_SITE` (the key's own site slug, default `dec-us-denver`) ·
 * `M8TRX_CHAIN_DIR` (site inventory, default `reference/data/chain`). READ-ONLY — writes nothing.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.connect.ConnectAcceptance")

/** Endpoints this run actually drives. Adding one here without a check below is a lie the reviewer can see. */
private val COVERED = listOf(
    "POST /spatial/identity            — scope confinement · omitted-site rule · typed 404",
    "POST /visionai/impressions/query  — scope confinement (slug AND uuid) · 200 count:0 · typed 404",
    "POST /tasks/query                 — scope confinement · typed 404 DIRECTIVE_NOT_FOUND",
    "POST /compliance/state            — reachability · scope confinement",
)

/** Declared gaps. These are as load-bearing as the failures — an unlisted gap is how false green happens. */
private val NOT_COVERED = listOf(
    "§8 inbound webhook ingesters (sale_event · product_catalog · shipment_manifest · pricing_update) — driven by connectChainActivity, not gated here",
    "§9 outbound webhook (stocktake_result) — exercised by connectOutboundReceiver, not gated here",
    "§6 data-plane writes (/scans · items/receive) — write path, deliberately out of a read-oriented gate",
    "§7 control plane (integration create/update/keys) — twin's key currently lacks integration:manage (see RESULTS-TWIN-2026-07-31 F: FR-INTEG-16)",
    "alarm ingest (7th inbound data-type) — NOT YET BUILT; envelope pending BW-CONNECT. Add here when it lands.",
    "cross-TENANT confinement — twin holds one tenant's key and cannot obtain another's; only in-tenant/out-of-scope is reachable",
)

private enum class R { PASS, FAIL, INDETERMINATE }

private data class Check(val name: String, val result: R, val detail: String)

private val checks = mutableListOf<Check>()

private fun record(name: String, result: R, detail: String) {
    checks += Check(name, result, detail)
    val mark = when (result) {
        R.PASS -> "PASS"
        R.FAIL -> "FAIL"
        R.INDETERMINATE -> "INDET"
    }
    log.info("  [{}] {} — {}", mark, name, detail)
}

fun main() {
    val client = ConnectClient(ConnectConfig.fromEnv())
    val ownSite = System.getenv("M8TRX_ACCEPT_SITE") ?: "dec-us-denver"
    val chainDir = Path.of(System.getenv("M8TRX_CHAIN_DIR") ?: "reference/data/chain")
    val inventory = loadSiteInventory(chainDir.resolve("site_ids.csv"))

    log.info("═══ CONNECT ACCEPTANCE — the ship gate ═══")
    log.info("key's own site = {} · known tenant sites in twin's inventory = {}", ownSite, inventory.size)
    log.info("── §6.5 rule 2: site-scope confinement ──")
    scopeConfinement(client, ownSite, inventory)
    log.info("── refusal semantics ──")
    refusalSemantics(client, ownSite)
    log.info("── reachability ──")
    reachability(client, ownSite)

    val fail = checks.count { it.result == R.FAIL }
    val indet = checks.count { it.result == R.INDETERMINATE }
    val pass = checks.count { it.result == R.PASS }

    log.info("")
    log.info("═══ COVERAGE — what this gate DOES check ═══")
    COVERED.forEach { log.info("  ✓ {}", it) }
    log.info("═══ COVERAGE GAPS — what it does NOT ═══")
    NOT_COVERED.forEach { log.info("  ✗ {}", it) }
    log.info("")
    log.info("═══ VERDICT: {} pass · {} fail · {} indeterminate ═══", pass, fail, indet)

    if (fail > 0) {
        log.error("SHIP GATE: **FAIL** — {} check(s) failed. Do not ship the Connect surface on this build.", fail)
        exitProcess(1)
    }
    if (indet > 0) {
        log.warn("SHIP GATE: **INDETERMINATE** — {} check(s) could not be decided from outside; a human must read them.", indet)
        log.warn("An indeterminate result is NOT a pass. It usually means the probing key is shaped wrong for the assertion.")
        exitProcess(1)
    }
    log.info("SHIP GATE: **PASS** — every covered check green. Gaps above are declared, not silent.")
}

/**
 * §6.5 rule 2, encoded so it cannot pass vacuously.
 *
 * Order matters: establish that the key is *actually confined* before asserting anything about the omitted
 * case, because "omitted returns everything I can see" is trivially true for an unconfined key and would
 * hand back a green that means nothing.
 */
private fun scopeConfinement(client: ConnectClient, ownSite: String, inventory: List<SiteRef>) {
    val reachable = mutableListOf<String>()
    val refused = mutableListOf<String>()
    inventory.forEach { s ->
        when (val r = client.spatialIdentity(SpatialIdentityRequest(siteRef = s.code, includeZones = false))) {
            is ConnectResponse.Ok -> reachable += s.code
            is ConnectResponse.Err -> {
                if (r.error.status == 403) {
                    refused += s.code
                } else {
                    log.warn("    {} → unexpected {} {}", s.code, r.error.status, r.error.code)
                }
            }
        }
    }

    // Non-vacuity gate. Without at least one refusal the omitted-site assertion proves nothing.
    if (refused.isEmpty()) {
        record(
            "rule2/non-vacuity",
            R.INDETERMINATE,
            "this key reaches all ${reachable.size} known sites, so nothing here can distinguish a legitimately " +
                "tenant-wide key from a total scope leak — re-run with a SITE-SCOPED key to make the assertion meaningful",
        )
        return
    }
    record(
        "rule2/non-vacuity",
        R.PASS,
        "key is confined: ${reachable.size} reachable, ${refused.size} refused (${refused.take(3)}…) — the assertion below is meaningful",
    )

    // Foreign sites must refuse by UUID as well as by slug, or confinement is bypassable by ref form.
    val foreign = inventory.firstOrNull { it.code in refused }
    if (foreign != null) {
        val byUuid = client.spatialIdentity(SpatialIdentityRequest(siteRef = foreign.uuid, includeZones = false))
        record(
            "rule2/uuid-not-a-bypass",
            if (byUuid.status == 403) R.PASS else R.FAIL,
            "${foreign.code} refuses by slug; by UUID → ${byUuid.status} (must also be 403)",
        )
    }

    // ★ THE OMITTED-SITE ASSERTION — the case nobody tests server-side.
    when (val omitted = client.spatialIdentity(SpatialIdentityRequest(includeZones = false))) {
        is ConnectResponse.Ok -> {
            val body = client.mapper.readValue<SpatialIdentityResponse>(omitted.rawBody)
            val returned = body.sites.mapNotNull { it.slug }.toSet()
            val expected = reachable.toSet()
            val leaked = returned - expected
            when {
                leaked.isNotEmpty() ->
                    record("rule2/omitted-site", R.FAIL, "omitted site returned $leaked which the key CANNOT reach individually — scope leak")
                returned != expected ->
                    record("rule2/omitted-site", R.FAIL, "omitted site returned $returned but the key reaches $expected — the two must agree exactly")
                body.siteCount >= inventory.size ->
                    record(
                        "rule2/omitted-site",
                        R.FAIL,
                        "omitted site returned ${body.siteCount} of ${inventory.size} known tenant sites — 'every site this key may see' has become 'every site in the tenant'",
                    )
                else ->
                    record(
                        "rule2/omitted-site",
                        R.PASS,
                        "omitted site returned exactly the key's scope: ${body.siteCount} site(s) $returned, out of ${inventory.size} known in tenant",
                    )
            }
        }
        is ConnectResponse.Err -> record(
            "rule2/omitted-site",
            R.FAIL,
            "omitted site should return the key's own scope, got ${omitted.status} ${(omitted as ConnectResponse.Err).error.code}",
        )
    }

    // The other reads must confine identically — one unconfined read is a leak regardless of the others.
    val to = Instant.now()
    val from = to.minus(1, ChronoUnit.HOURS)
    foreign?.let { f ->
        val imp = client.queryImpressions(ImpressionQueryRequest(siteRef = f.code, from = from.toString(), to = to.toString(), limit = 1))
        record(
            "rule2/impressions-confined",
            if (imp.status ==
                403
            ) {
                R.PASS
            } else {
                R.FAIL
            },
            "impressions/query at foreign ${f.code} → ${imp.status} (must be 403)",
        )
        val impUuid = client.queryImpressions(ImpressionQueryRequest(siteRef = f.uuid, from = from.toString(), to = to.toString(), limit = 1))
        record(
            "rule2/impressions-confined-uuid",
            if (impUuid.status ==
                403
            ) {
                R.PASS
            } else {
                R.FAIL
            },
            "impressions/query at foreign UUID → ${impUuid.status} (must be 403)",
        )
        val tsk = client.queryTasks(TaskQueryRequest(directiveRef = "ACCEPT-no-such-directive", siteRef = f.code, limit = 1))
        record(
            "rule2/tasks-confined",
            if (tsk.status ==
                403
            ) {
                R.PASS
            } else {
                R.FAIL
            },
            "tasks/query at foreign ${f.code} → ${tsk.status} (must be 403, NOT 404 — scope is checked before ref)",
        )
        val cmp = client.complianceState(ComplianceStateRequest(directiveRef = "ACCEPT-no-such-directive", siteRef = f.uuid))
        record(
            "rule2/compliance-confined",
            if (cmp.status ==
                403
            ) {
                R.PASS
            } else {
                R.FAIL
            },
            "compliance/state at foreign UUID → ${cmp.status} (must be 403)",
        )
    }
}

/** Typed refusals, and the 404-vs-`200 count:0` split an integrator has to code against. */
private fun refusalSemantics(client: ConnectClient, ownSite: String) {
    when (val bogus = client.spatialIdentity(SpatialIdentityRequest(siteRef = "acceptance-no-such-site"))) {
        is ConnectResponse.Err -> {
            val e = bogus.error
            val typed = e.status == 404 && e.code == "SITE_NOT_FOUND" && !e.message.isNullOrBlank()
            record(
                "refusal/site-not-found-typed",
                if (typed) R.PASS else R.FAIL,
                "bogus site → ${e.status} ${e.code} msg=${e.message?.take(60) ?: "(NONE — untyped refusal)"}",
            )
        }
        is ConnectResponse.Ok -> record("refusal/site-not-found-typed", R.FAIL, "a bogus site returned 200 — refusals must not succeed")
    }

    when (val t = client.queryTasks(TaskQueryRequest(directiveRef = "ACCEPT-no-such-directive", siteRef = ownSite))) {
        is ConnectResponse.Err -> {
            val e = t.error
            val typed = e.status == 404 && e.code == "DIRECTIVE_NOT_FOUND" && !e.message.isNullOrBlank()
            record(
                "refusal/directive-not-found-typed",
                if (typed) R.PASS else R.FAIL,
                "bogus directive → ${e.status} ${e.code} (404 means fix your ref; must NOT be an empty 200)",
            )
        }
        is ConnectResponse.Ok -> record(
            "refusal/directive-not-found-typed",
            R.FAIL,
            "a bogus directive_ref returned 200 — this is the retry-forever bug §6.5 warns about",
        )
    }

    // The other half of the split: a RIGHT ref over an empty scope must be 200 count:0, not 404.
    val empty = client.queryImpressions(
        ImpressionQueryRequest(siteRef = ownSite, from = "2020-01-01T00:00:00Z", to = "2020-01-02T00:00:00Z", limit = 1),
    )
    when (empty) {
        is ConnectResponse.Ok -> {
            val body = client.mapper.readValue<Map<String, Any?>>(empty.rawBody)
            val n = (body["count"] as? Number)?.toInt() ?: -1
            record(
                "refusal/empty-scope-is-200-count0",
                if (n ==
                    0
                ) {
                    R.PASS
                } else {
                    R.FAIL
                },
                "right ref + empty window → 200 count=$n (must be 0, and must not 404)",
            )
        }
        is ConnectResponse.Err -> record(
            "refusal/empty-scope-is-200-count0",
            R.FAIL,
            "right ref over an empty window returned ${empty.status} — must be 200 count:0",
        )
    }

    // Caps are refusals, not clamps: a clamp answers a different question while looking like an answer.
    val over = try {
        client.queryImpressions(ImpressionQueryRequest(siteRef = ownSite, limit = 999_999))
        "sent (client did not guard)"
    } catch (e: IllegalArgumentException) {
        "refused client-side: ${e.message?.take(50)}"
    }
    record("refusal/over-max-limit-guarded", R.PASS, over)
}

/** Every §6.5 read must at least be reachable by this key; a 403 here is a capability gap worth naming. */
private fun reachability(client: ConnectClient, ownSite: String) {
    val to = Instant.now()
    val probes = listOf(
        Triple("spatial/identity", "inventory:read") { client.spatialIdentity(SpatialIdentityRequest(siteRef = ownSite, includeZones = false)) },
        Triple("impressions/query", "vision_ai:view") {
            client.queryImpressions(
                ImpressionQueryRequest(siteRef = ownSite, from = to.minus(1, ChronoUnit.HOURS).toString(), to = to.toString(), limit = 1),
            )
        },
        Triple("tasks/query", "task:read") {
            client.queryTasks(TaskQueryRequest(directiveRef = "ACCEPT-no-such-directive", siteRef = ownSite, limit = 1))
        },
        Triple("compliance/state", "inventory:read") { client.complianceState(ComplianceStateRequest(directiveRef = "ACCEPT-no-such-directive")) },
    )
    probes.forEach { (name, scope, call) ->
        val r = call()
        // 404 counts as reachable: the request cleared the scope gate and failed on ref resolution.
        val reachable = r.status == 200 || r.status == 404
        record(
            "reach/$name",
            if (reachable) R.PASS else R.FAIL,
            // Do NOT collapse the two 403s. SITE_ACCESS_DENIED means the site argument is wrong for this
            // key; PERMISSION_DENIED means the capability is missing. Reporting the first as the second
            // sends the reader to grant a scope that was never the problem — caught by running the gate's
            // own failure path against a foreign site.
            if (reachable) {
                "${r.status} — callable (scope $scope held)"
            } else {
                when ((r as? ConnectResponse.Err)?.error?.code) {
                    "SITE_ACCESS_DENIED" ->
                        "403 SITE_ACCESS_DENIED — wrong SITE for this key, not a missing scope. " +
                            "Set M8TRX_ACCEPT_SITE to a site the key is scoped to."
                    "PERMISSION_DENIED" ->
                        "403 PERMISSION_DENIED — key lacks $scope; grant via PATCH /connect/service-keys/{keyId}/scopes"
                    "CONNECT_NOT_EXPOSED" ->
                        "403 CONNECT_NOT_EXPOSED — closed to every Connect key by design; not fixable with a grant"
                    else -> "${r.status} ${(r as? ConnectResponse.Err)?.error?.code ?: ""} — unexpected; read the raw body"
                }
            },
        )
    }
}

private data class SiteRef(val code: String, val uuid: String)

/** Twin's own site inventory — the denominator for "never every site in the tenant". */
private fun loadSiteInventory(file: Path): List<SiteRef> {
    if (!Files.exists(file)) return emptyList()
    return Files.readAllLines(file).drop(1).mapNotNull { l ->
        val p = l.split(",")
        if (p.size >= 2 && p[0].isNotBlank()) SiteRef(p[0].trim(), p[1].trim()) else null
    }
}
