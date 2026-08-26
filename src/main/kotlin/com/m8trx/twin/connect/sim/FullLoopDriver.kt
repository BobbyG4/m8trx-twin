package com.m8trx.twin.connect.sim

import com.fasterxml.jackson.module.kotlin.readValue
import com.m8trx.twin.connect.ConnectClient
import com.m8trx.twin.connect.ConnectConfig
import com.m8trx.twin.connect.WebhookClient
import com.m8trx.twin.connect.appendSold
import com.m8trx.twin.connect.http.ConnectResponse
import com.m8trx.twin.connect.jitterGapMs
import com.m8trx.twin.connect.model.bearer.ItemDetail
import com.m8trx.twin.connect.model.bearer.ItemDetailsRequest
import com.m8trx.twin.connect.model.webhook.PlanogramDocument
import com.m8trx.twin.connect.model.webhook.SaleEvent
import com.m8trx.twin.connect.readPriorSold
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.random.Random

/** Which remediation arm(s) to drive after the drift. */
enum class RemediateMode { NONE, SCAN, MOVEMENT, BOTH }

/**
 * The path-(b) demo-nucleus loop, composed into ONE parameterized run (CORE-REQ-005 part 1).
 *
 * Chains the already-built P0 drivers end to end against a set of compliance targets:
 *
 *   (opt) publish directive → **drift** (sale_event) → **assert input** (items/details = sold)
 *       → **remediate** (inventory_movement relocate and/or data-plane scan)
 *       → **assert input** (items/details = present) → **report** the compliance expectation.
 *
 * **Assertion split (corrected per the S198 read-back 403):** twin asserts the *inputs* it drove
 * over Connect — a sale is SOLD, a relocated unit is present/in_stock — via `items/details`
 * (`inventory:read`). The compliance **output** (`compliance_target_state`) is NOT ConnectExposed
 * (`GET /state` → 403), so it is verified by the paired backend session in the synchronized smoke.
 * [plan] therefore computes a precise per-target **expectation** (baseline → after-drift →
 * after-remediate) as the oracle that session checks against.
 *
 * Targets are modeled at **(fixture × SKU)** grain — one per planogram line, mirroring
 * `compliance_target` — so an EPC-only sale of SKU X on fixture F drifts exactly target (F, X).
 *
 * SAFE BY DEFAULT: [run] with `live=false` builds the full plan and logs every phase's intended
 * action + the expectation, and **sends nothing**. `live=true` drives it (needs `.env` creds:
 * webhook key for drift/movement/directive, Bearer for scans + items/details).
 */
class FullLoopDriver(
    private val config: ConnectConfig,
    private val chainDir: Path = Path.of("reference/data/chain"),
    private val soldLog: Path = Path.of(".twin-state/sold-epcs.txt"),
) {
    private val log = LoggerFactory.getLogger(FullLoopDriver::class.java)

    private val webhook = WebhookClient(config)
    private val client = ConnectClient(config)
    private val inbound = InboundPushDriver(webhook, client)
    private val device = DeviceDriver(client)
    private val planogram = PlanogramDirectiveDriver(webhook)
    private val movement = MovementDriver(webhook)

    // ---- parameters -----------------------------------------------------------------------------

    data class LoopParams(
        val stores: List<String> = listOf("dec-us-denver"),
        val fixtures: Set<String> = setOf("GB-R3-U1"),
        val driftPerTarget: Int = 2,
        val driftDeplete: Boolean = false,
        val remediate: RemediateMode = RemediateMode.BOTH,
        val remediateToRequired: Boolean = true,
        val publishDirective: Boolean = false,
        val durationSec: Int = 60,
        val readSize: Int = 25,
        val seed: Long = 0L,
        val runId: String = "twin-loop",
        val webhookAuth: WebhookClient.AuthMode = WebhookClient.AuthMode.API_KEY,
        val directiveAuth: WebhookClient.AuthMode = WebhookClient.AuthMode.HMAC,
    )

    // ---- plan (pure, offline: reads local data files, no network) --------------------------------

    /** One compliance target = one (fixture × SKU) placement, with its drift + relocate picks resolved. */
    data class SkuTarget(
        val storeCode: String,
        val siteId: String,
        val fixture: String,
        val sku: String,
        val ean: String,
        val requiredQty: Int,
        val onFloorNow: Int,
        val driftPicks: List<String>,
        val survivingFloor: List<String>,
        val bohAvailable: Int,
        val relocatePicks: List<String>,
    ) {
        val observedAfterDrift: Int get() = onFloorNow - driftPicks.size
        val observedAfterRemediate: Int get() = observedAfterDrift + relocatePicks.size

        fun status(observed: Int): String = when {
            observed <= 0 -> "non_compliant"
            observed < requiredQty -> "partially_compliant"
            else -> "compliant"
        }
    }

    data class StorePlan(val storeCode: String, val siteId: String, val doc: PlanogramDocument, val targets: List<SkuTarget>)

    data class LoopPlan(val params: LoopParams, val stores: List<StorePlan>, val warnings: List<String>) {
        val targets: List<SkuTarget> get() = stores.flatMap { it.targets }
    }

    /**
     * Resolve every target for the requested stores × fixtures and compute the drift / relocate / scan
     * picks. Pure — reads `site_ids.csv`, each store's `planogram.json` + `epcs.csv`, and the sold-log;
     * makes no network calls. Reproducible for a given [LoopParams.seed].
     */
    fun plan(params: LoopParams): LoopPlan {
        val warnings = ArrayList<String>()
        val siteIds = loadSiteIds(chainDir.resolve("site_ids.csv"))
        val sold = readPriorSold(soldLog)
        val rng = Random(params.seed)
        val stores = ArrayList<StorePlan>()

        for (storeCode in params.stores) {
            val siteId = siteIds[storeCode]
            if (siteId == null) {
                warnings += "skip $storeCode — no site UUID in site_ids.csv"
                continue
            }
            val pj = chainDir.resolve("stores/$storeCode/planogram.json")
            val ecsv = chainDir.resolve("stores/$storeCode/epcs.csv")
            if (!Files.exists(pj) || !Files.exists(ecsv)) {
                warnings += "skip $storeCode — missing planogram.json or epcs.csv (run scripts/build_planogram.py)"
                continue
            }
            val doc = planogram.loadDocument(pj)
            val epcs = loadStoreEpcs(ecsv, sold)
            // deterministic target order → reproducible BOH consumption when fixtures share a SKU
            val lines = doc.lines
                .filter { params.fixtures.isEmpty() || it.fixtureCode in params.fixtures }
                .sortedWith(compareBy({ it.fixtureCode }, { it.sku }))

            val targets = ArrayList<SkuTarget>()
            for (line in lines) {
                val floorPool = epcs.floor[line.fixtureCode]?.get(line.sku).orEmpty()
                val onFloorNow = floorPool.size
                val driftN = if (params.driftDeplete) onFloorNow else minOf(params.driftPerTarget, onFloorNow)
                val shuffledFloor = floorPool.shuffled(rng)
                val driftPicks = shuffledFloor.take(driftN)
                val survivingFloor = shuffledFloor.drop(driftN)

                val afterDrift = onFloorNow - driftN
                val relocateNeed = if (params.remediateToRequired) {
                    maxOf(0, line.requiredQty - afterDrift)
                } else {
                    driftN
                }
                val bohPool = epcs.boh.getOrPut(line.sku) { ArrayList() }
                val bohAvailable = bohPool.size
                val relocatePicks = if (params.remediate == RemediateMode.MOVEMENT || params.remediate == RemediateMode.BOTH) {
                    val take = minOf(relocateNeed, bohPool.size)
                    val picks = bohPool.shuffled(rng).take(take)
                    bohPool.removeAll(picks.toSet()) // consume so a shared SKU can't be relocated twice
                    if (picks.size < relocateNeed) {
                        warnings += "under-stock $storeCode/${line.fixtureCode}/${line.sku} — " +
                            "need $relocateNeed to restore, BOH has $bohAvailable (relocating ${picks.size})"
                    }
                    picks
                } else {
                    emptyList()
                }

                targets += SkuTarget(
                    storeCode = storeCode,
                    siteId = siteId,
                    fixture = line.fixtureCode,
                    sku = line.sku,
                    ean = line.ean,
                    requiredQty = line.requiredQty,
                    onFloorNow = onFloorNow,
                    driftPicks = driftPicks,
                    survivingFloor = survivingFloor,
                    bohAvailable = bohAvailable,
                    relocatePicks = relocatePicks,
                )
            }
            if (targets.isEmpty()) {
                warnings += "skip $storeCode — 0 targets after fixture filter ${params.fixtures}"
            } else {
                stores += StorePlan(storeCode, siteId, doc, targets)
            }
        }
        return LoopPlan(params, stores, warnings)
    }

    // ---- execute --------------------------------------------------------------------------------

    data class LoopResult(
        val salesFired: Int,
        val salesOk: Int,
        val soldVerified: Int,
        val movementsFired: Int,
        val unitsRelocated: Int,
        val relocatedVerified: Int,
        val scansFired: Int,
        val warnings: List<String>,
    )

    /** Build the plan then drive it. */
    fun run(params: LoopParams, live: Boolean): LoopResult = execute(plan(params), live)

    /**
     * Walk the loop phase by phase. `live=false` logs each intended action + the compliance expectation
     * and sends nothing (no sleeps — it is a fast preview); `live=true` drives it, paced with jitter.
     */
    fun execute(plan: LoopPlan, live: Boolean): LoopResult {
        val p = plan.params
        val mode = if (live) "LIVE" else "DRY-RUN"
        val rng = Random(p.seed)
        plan.warnings.forEach { log.warn("[loop] {}", it) }
        log.info(
            "[loop] {} runId={} stores={} targets={} drift={} remediate={} publishDirective={}",
            mode,
            p.runId,
            plan.stores.map { it.storeCode },
            plan.targets.size,
            if (p.driftDeplete) "deplete" else "${p.driftPerTarget}/target",
            p.remediate,
            p.publishDirective,
        )
        if (plan.targets.isEmpty()) {
            log.warn("[loop] no targets — nothing to drive")
            return LoopResult(0, 0, 0, 0, 0, 0, 0, plan.warnings)
        }

        // ---- Phase 1: (optional) publish the directive → compliant baseline ----------------------
        if (p.publishDirective) {
            for (sp in plan.stores) {
                val directive = planogram.toDirective(sp.doc, sp.siteId, fixtures = p.fixtures)
                if (directive.targets.isEmpty()) continue
                if (live) {
                    planogram.drive(directive, p.directiveAuth)
                } else {
                    log.info(
                        "[loop][1-directive][DRY] store={} extId={} targets={}",
                        sp.storeCode,
                        directive.externalDirectiveId,
                        directive.targets.size,
                    )
                }
            }
        }

        // ---- Phase 2: drift — sell the drift picks on the target fixtures -------------------------
        val soldEpcs = ArrayList<String>()
        var salesFired = 0
        var salesOk = 0
        val driftJobs = plan.targets.flatMap { t -> t.driftPicks.map { t to it } }
        driftJobs.forEachIndexed { i, (t, epc) ->
            salesFired++
            val saleId = "${p.runId}-sale-${i + 1}"
            if (live) {
                val sale = SaleEvent.byEpc(saleId, Instant.now().toString(), epc)
                when (inbound.pushSale(sale, p.webhookAuth)) {
                    is ConnectResponse.Ok -> {
                        salesOk++
                        soldEpcs += epc
                        appendSold(soldLog, epc)
                    }

                    is ConnectResponse.Err -> log.error("[loop][2-drift] sale {} epc={} FAILED", saleId, epc)
                }
                if (i < driftJobs.size - 1) Thread.sleep(jitterGapMs(p.durationSec, driftJobs.size, rng))
            } else {
                soldEpcs += epc
                log.info("[loop][2-drift][DRY] {} fixture={} sku={} epc={}", saleId, t.fixture, t.sku, epc)
            }
        }
        log.info("[loop][2-drift] {} — {} sale(s) fired ({} ok)", mode, salesFired, if (live) salesOk else salesFired)

        // ---- Phase 3: assert INPUT — the drifted units are SOLD (items/details) -------------------
        val soldVerified = if (live && soldEpcs.isNotEmpty()) {
            verifyState(soldEpcs, "sold", "3-assert-drift")
        } else {
            log.info("[loop][3-assert-drift][DRY] would verify {} EPC(s) → state=sold via items/details", soldEpcs.size)
            0
        }

        // ---- Phase 4: remediate — relocate BOH stock onto the fixture, then scan to verify --------
        val relocatedEpcs = ArrayList<String>()
        var movementsFired = 0
        var scansFired = 0
        val doMovement = p.remediate == RemediateMode.MOVEMENT || p.remediate == RemediateMode.BOTH
        val doScan = p.remediate == RemediateMode.SCAN || p.remediate == RemediateMode.BOTH
        for (t in plan.targets) {
            if (doMovement && t.relocatePicks.isNotEmpty()) {
                movementsFired++
                val extId = "${p.runId}-mov-${t.fixture}-${t.sku}"
                val mv = movement.build(
                    toFixtureCode = t.fixture,
                    epcs = t.relocatePicks,
                    externalMovementId = extId,
                    siteId = t.siteId,
                )
                if (live) {
                    when (movement.drive(mv, p.webhookAuth)) {
                        is ConnectResponse.Ok -> relocatedEpcs += t.relocatePicks
                        is ConnectResponse.Err -> log.error("[loop][4-remediate] movement {} FAILED", extId)
                    }
                } else {
                    relocatedEpcs += t.relocatePicks
                    log.info("[loop][4-remediate][DRY][move] fixture={} sku={} relocate={} → {}", t.fixture, t.sku, t.relocatePicks.size, extId)
                }
            }
            if (doScan) {
                // post-remediation reader view: surviving floor units at (fixture,sku) + the relocated ones
                val scanSet = (t.survivingFloor + t.relocatePicks).take(p.readSize)
                if (scanSet.isNotEmpty()) {
                    scansFired++
                    val readerId = "RDR-${t.storeCode}-${t.fixture}"
                    if (live) {
                        device.scan(t.siteId, readerId, scanSet, fixtureId = t.fixture, runId = p.runId)
                        Thread.sleep(jitterGapMs(p.durationSec, plan.targets.size, rng))
                    } else {
                        log.info("[loop][4-remediate][DRY][scan] reader={} fixture={} reads={}", readerId, t.fixture, scanSet.size)
                    }
                }
            }
        }
        log.info("[loop][4-remediate] {} — {} movement(s), {} scan(s)", mode, movementsFired, scansFired)

        // ---- Phase 5: assert INPUT — relocated units are present/in_stock (items/details) ---------
        val relocatedVerified = if (live && doMovement && relocatedEpcs.isNotEmpty()) {
            verifyState(relocatedEpcs, "in_stock", "5-assert-remediate")
        } else {
            if (doMovement) {
                log.info("[loop][5-assert-remediate][DRY] would verify {} relocated EPC(s) → present/in_stock", relocatedEpcs.size)
            }
            0
        }

        // ---- Phase 6: report the compliance expectation (the backend session's oracle) ------------
        reportExpectation(plan)

        return LoopResult(
            salesFired = salesFired,
            salesOk = if (live) salesOk else salesFired,
            soldVerified = soldVerified,
            movementsFired = movementsFired,
            unitsRelocated = relocatedEpcs.size,
            relocatedVerified = relocatedVerified,
            scansFired = scansFired,
            warnings = plan.warnings,
        )
    }

    /** items/details read-back: how many of [epcs] are in [wantState]. */
    private fun verifyState(epcs: List<String>, wantState: String, phase: String): Int =
        when (val resp = client.itemDetails(ItemDetailsRequest(epcs))) {
            is ConnectResponse.Ok -> {
                val details = client.mapper.readValue<List<ItemDetail>>(resp.rawBody)
                val hits = details.count { it.state == wantState }
                val byState = details.groupingBy { it.state ?: "unknown" }.eachCount().toSortedMap()
                val missing = epcs.size - details.size
                log.info(
                    "[loop][{}] {}/{} {} (states={}{})",
                    phase,
                    hits,
                    epcs.size,
                    wantState,
                    byState,
                    if (missing > 0) ", $missing not found" else "",
                )
                hits
            }

            is ConnectResponse.Err -> {
                log.error("[loop][{}] items/details FAILED status={} code={}", phase, resp.error.status, resp.error.code)
                0
            }
        }

    /**
     * The per-target compliance expectation: baseline → after-drift → after-remediate. This is what the
     * paired backend session verifies against `compliance_target_state` in the synchronized smoke (the
     * output twin cannot read over Connect, `/state` → 403).
     */
    private fun reportExpectation(plan: LoopPlan) {
        log.info("[loop][6-expect] per-target compliance expectation (backend session verifies /state):")
        val tally = HashMap<String, Int>()
        for (t in plan.targets) {
            val base = t.status(t.onFloorNow)
            val drifted = t.status(t.observedAfterDrift)
            val healed = t.status(t.observedAfterRemediate)
            tally[healed] = (tally[healed] ?: 0) + 1
            log.info(
                "[loop][6-expect]   {}/{} sku={} req={} | baseline {}({}) → drift {}({}) → remediate {}({})",
                t.storeCode,
                t.fixture,
                t.sku,
                t.requiredQty,
                t.onFloorNow,
                base,
                t.observedAfterDrift,
                drifted,
                t.observedAfterRemediate,
                healed,
            )
        }
        log.info("[loop][6-expect] end-state tally (expected): {}", tally.toSortedMap())
    }

    // ---- local loaders (fixture/SKU-aware; the shared loaders are flat) ---------------------------

    private class StoreEpcs(val floor: Map<String, Map<String, List<String>>>, val boh: MutableMap<String, MutableList<String>>)

    /**
     * Read a store's `epcs.csv` (cols epc,ean,item_cd,category,fixture,store_id) into floor units grouped
     * by fixture→SKU and back-of-house units grouped by SKU, dropping the header, blanks, dupes, and
     * [sold]. Back-of-house = fixture `BR-*` (the relocate from-pool); everything else is on the floor.
     */
    private fun loadStoreEpcs(file: Path, sold: Set<String>): StoreEpcs {
        val floor = HashMap<String, HashMap<String, MutableList<String>>>()
        val boh = HashMap<String, MutableList<String>>()
        val seen = HashSet<String>()
        for (line in Files.readAllLines(file).drop(1)) {
            val c = line.split(",")
            if (c.size < 6) continue
            val epc = c[0].trim()
            val sku = c[2].trim()
            val fixture = c[4].trim()
            if (epc.isEmpty() || sku.isEmpty() || fixture.isEmpty() || epc in sold) continue
            if (!seen.add(epc)) continue
            if (fixture.startsWith("BR-")) {
                boh.getOrPut(sku) { ArrayList() }.add(epc)
            } else {
                floor.getOrPut(fixture) { HashMap() }.getOrPut(sku) { ArrayList() }.add(epc)
            }
        }
        return StoreEpcs(floor, boh)
    }

    /** `site_ids.csv` → store_code → site UUID (cols: code,site_id,…). */
    private fun loadSiteIds(file: Path): Map<String, String> {
        if (!Files.exists(file)) return emptyMap()
        return Files.readAllLines(file).drop(1).mapNotNull { line ->
            val c = line.split(",")
            if (c.size < 2 || c[0].isBlank() || c[1].isBlank()) null else c[0].trim() to c[1].trim()
        }.toMap()
    }
}
