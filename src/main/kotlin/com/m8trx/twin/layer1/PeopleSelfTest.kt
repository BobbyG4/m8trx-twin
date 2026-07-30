package com.m8trx.twin.layer1

import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * Offline conformance harness for the fixture-impression rule — `./gradlew peopleSelfTest`.
 *
 * Proves the emit contract by CONSTRUCTION rather than by reading the rule: each case builds a sample
 * stream and asserts whether [ImpressionOracle] fires. The emit-rate cases are the point — they are the
 * failure Bob flagged as the easiest way to ship a generator that looks entirely correct and produces zero
 * impressions forever.
 *
 * No network, no credentials, no mother UUIDs. Runs against the committed Denver `layout.json`.
 */
private val log = LoggerFactory.getLogger("com.m8trx.twin.layer1.PeopleSelfTest")

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        passed++
        log.info("  ✓ {}", name)
    } else {
        failed++
        log.error("  ✗ {} {}", name, detail)
    }
}

private data class Clearance(val best: Pt, val bestClearanceMm: Double, val pctWithinRange: Int)

/**
 * Grid-scan the space for the point furthest from any fixture edge, and measure what fraction of the floor
 * sits inside the dwell-proximity band. High coverage is realistic for a dense specialty floor, but it also
 * means a walking shopper accumulates dwell against something almost continuously — worth knowing before
 * tuning journey paths.
 */
private fun openFloorProbe(fixtures: FixtureSet, dwellProximityMm: Double): Clearance {
    val xs = fixtures.fixtures.flatMap { f -> f.ring.map { it.x } + f.centre.x }
    val ys = fixtures.fixtures.flatMap { f -> f.ring.map { it.y } + f.centre.y }
    val minX = xs.min()
    val maxX = xs.max()
    val minY = ys.min()
    val maxY = ys.max()

    var best = Pt(minX, minY)
    var bestClear = -1.0
    var inRange = 0
    var total = 0
    val step = 250.0
    var x = minX
    while (x <= maxX) {
        var y = minY
        while (y <= maxY) {
            val p = Pt(x, y)
            val near = fixtures.impressionVisible.minOfOrNull { f -> if (f.contains(p)) 0.0 else f.distanceToEdge(p) } ?: Double.MAX_VALUE
            total++
            if (near < dwellProximityMm) inRange++
            if (near > bestClear) {
                bestClear = near
                best = p
            }
            y += step
        }
        x += step
    }
    return Clearance(best, bestClear, if (total == 0) 0 else inRange * 100 / total)
}

fun main() {
    val layout = Path.of(
        System.getenv("M8TRX_CHAIN_DIR") ?: "reference/data/chain",
        "stores",
        System.getenv("M8TRX_PEOPLE_STORE") ?: "dec-us-denver",
        "layout.json",
    )
    val fixtures = FixtureSet.load(layout)
    val oracle = ImpressionOracle()
    val rng = Random(42)

    log.info(
        "Fixture set — store={} space={} fixtures={} impression-visible={} (circles skipped by core: {})",
        fixtures.storeCode,
        fixtures.spaceCode,
        fixtures.fixtures.size,
        fixtures.impressionVisible.size,
        fixtures.fixtures.size - fixtures.impressionVisible.size,
    )

    val target = fixtures.impressionVisible.first { it.code.startsWith("GF-") || it.code.startsWith("GB-") }
    log.info("Target fixture: {} ({}) centre=({}, {})", target.code, target.name, target.centre.x.toInt(), target.centre.y.toInt())

    // ── 1. geometry sanity ────────────────────────────────────────────────────
    log.info("[1] geometry")
    check("centroid is inside its own polygon", target.contains(target.centre))
    check("centroid distance-to-edge > 0", target.distanceToEdge(target.centre) > 0.0)
    val farPt = Pt(target.centre.x + 50_000, target.centre.y)
    check("far point is not contained", !target.contains(farPt))
    check("far point fails the distance clause", !target.withinDwellRange(farPt, oracle.dwellProximityMm))
    // Circles were invisible until Connect fixed GeometryConverter (2026-07-28); the edge now loads
    // 115 of 115, so this assertion is inverted from its original form rather than deleted — the flip is
    // the acceptance signal for that fix.
    check(
        "all 115 fixtures are now visible to the pipeline (GeometryConverter fix landed)",
        fixtures.fixtures.count { !it.visibleToImpressionPipeline } == 0,
        "still invisible: ${fixtures.fixtures.filter { !it.visibleToImpressionPipeline }.map { it.code }}",
    )
    val circles = fixtures.fixtures.filter { it.kind == Fixture.Kind.CIRCLE }
    check(
        "the 3 circle fixtures are present and carry a radius",
        circles.size == 3 && circles.all { (it.radiusMm ?: 0.0) > 0.0 },
        "circles=${circles.map { it.code to it.radiusMm }}",
    )
    check(
        "a circle is ray-hittable from outside",
        circles.first().let { c ->
            val p = Pt(c.centre.x, c.centre.y - (c.radiusMm!! + 600))
            fixtures.lookingAt(p, Pt(0.0, 1.0))?.code == c.code
        },
    )
    // Core gives circles CONTAINMENT-ONLY proximity (Geometry.Circle.edges() is a stub), so standing 0.6m
    // outside a promo island accumulates NO dwell — proven live 2026-07-28: that configuration emitted
    // lookingAtFixture but no dwellingNearbyFixture and no impression. Standing ON the footprint fires
    // (PI-01 → 7dc6fb79, 8400ms). If these flip, core shipped distanceToBoundary and the circle standoff
    // in BrowseEpisode can move back outside the radius.
    val circle = circles.first()
    val outsideCircle = Pt(circle.centre.x, circle.centre.y - (circle.radiusMm!! + 600))
    check(
        "circle: 0.6m OUTSIDE fails the distance clause (containment-only)",
        !circle.withinDwellRange(outsideCircle, oracle.dwellProximityMm),
    )
    check(
        "circle: ON the footprint satisfies it",
        circle.withinDwellRange(Pt(circle.centre.x + circle.radiusMm * 0.5, circle.centre.y), oracle.dwellProximityMm),
    )
    val circleEpisode = BrowseEpisode(fixtures, emitHz = 5.0).browse(circle.code, 8_400, 0L, Random(11))
    check(
        "a circle browse fires an impression on the requested circle",
        oracle.run(fixtures, circleEpisode).any { it.fixtureCode == circle.code },
        oracle.explainSilence(fixtures, circleEpisode).toString(),
    )

    // ── 2. the emit-rate floor — the case that matters ────────────────────────
    log.info("[2] emit-rate floor")
    val at5Hz = BrowseEpisode(fixtures, emitHz = 5.0).browse(target.code, 12_000, 0L, rng)
    val fired5 = oracle.run(fixtures, at5Hz)
    check("5 Hz / 12s browse FIRES an impression", fired5.isNotEmpty(), oracle.explainSilence(fixtures, at5Hz).toString())
    check(
        "it fires on the intended fixture",
        fired5.all { it.fixtureCode == target.code },
        "got ${fired5.map { it.fixtureCode }.distinct()}",
    )

    // 1 Hz exactly: gap == 1000ms, which is NOT > the 1000ms allowance, so clocks survive. The floor is
    // strict — anything slower resets. Verify both sides of the boundary.
    val at1Hz = BrowseEpisode(fixtures, emitHz = 1.001).browse(target.code, 12_000, 0L, rng)
    check("~1 Hz (999ms gap) still fires — boundary is strict, not defensive", oracle.run(fixtures, at1Hz).isNotEmpty())

    val slow = at5Hz.filterIndexed { i, _ -> i % 10 == 0 } // ~0.5 Hz → 2000ms gaps
    val firedSlow = oracle.run(fixtures, slow)
    check(
        "0.5 Hz browse fires NOTHING (both clocks reset every sample)",
        firedSlow.isEmpty(),
        "unexpectedly fired ${firedSlow.size}",
    )
    val why = oracle.explainSilence(fixtures, slow)
    check(
        "silence is explained as an emit-rate problem",
        why.any { it.contains("emit rate below the floor") },
        "reasons=$why",
    )

    // ── 3. duration threshold ─────────────────────────────────────────────────
    log.info("[3] duration threshold")
    val tooShort = BrowseEpisode(fixtures, emitHz = 5.0).browse(target.code, 4_000, 0L, rng)
    check("4s browse fires nothing (needs > 5000ms on BOTH clocks)", oracle.run(fixtures, tooShort).isEmpty())
    check("short-episode silence is explained", oracle.explainSilence(fixtures, tooShort).any { it.contains("too short") })
    val justOver = BrowseEpisode(fixtures, emitHz = 5.0).browse(target.code, 6_000, 0L, rng)
    check("6s browse fires", oracle.run(fixtures, justOver).isNotEmpty())

    // ── 4. the viewDirection gate ─────────────────────────────────────────────
    log.info("[4] viewDirection gate")
    val noView = at5Hz.map { it.copy(viewDir = null) }
    check("viewDirection=null fires NOTHING — the whole event is dropped", oracle.run(fixtures, noView).isEmpty())
    check("silence names the gate", oracle.explainSilence(fixtures, noView).any { it.contains("no viewDirection") })
    val tagged = at5Hz.map { it.copy(hasTag = true) }
    check("hasTag=true fires nothing (staff excluded upstream)", oracle.run(fixtures, tagged).isEmpty())

    // magnitude must be irrelevant — core builds an unbounded ray from the slope
    val scaled = at5Hz.map { s -> s.copy(viewDir = s.viewDir?.let { Pt(it.x * 137.0, it.y * 137.0) }) }
    check("view-vector magnitude is irrelevant (×137 identical)", oracle.run(fixtures, scaled).size == fired5.size)

    // ── 5. spatial clauses ────────────────────────────────────────────────────
    log.info("[5] spatial clauses")
    // Push the shopper 4m off the target's edge. NOTE: Denver's floor is dense — gondola rows sit ~1.4m
    // apart — so this lands them beside a NEIGHBOUR (GB-R5-U1 / GF-R4-U1). A neighbour firing is correct
    // behaviour, so the assertion isolates the TARGET rather than demanding global silence.
    val tooFar = at5Hz.map { s -> s.copy(p = Pt(s.p.x, s.p.y + 4_000)) }
    check(
        "the TARGET fails the distance clause when 4m off its edge",
        tooFar.none { target.withinDwellRange(it.p, oracle.dwellProximityMm) },
    )
    check(
        "target fires no impression from 4m away",
        oracle.run(fixtures, tooFar).none { it.fixtureCode == target.code },
        "target unexpectedly fired",
    )

    // Global silence IS required on genuinely open floor. Don't guess a coordinate — Denver packs 115
    // fixtures into 28.5×23m, so most of the floor is within impression range of something. Search for the
    // clearest point and report the coverage, which is a realism input in its own right: the fraction of
    // floor that is "attributable" bounds how much of a walk produces dwell signal.
    val clearance = openFloorProbe(fixtures, oracle.dwellProximityMm)
    log.info(
        "  · floor coverage: {}% of sampled points are within {}mm of a fixture edge; clearest point ({}, {}) at {}mm",
        clearance.pctWithinRange,
        oracle.dwellProximityMm.toInt(),
        clearance.best.x.toInt(),
        clearance.best.y.toInt(),
        clearance.bestClearanceMm.toInt(),
    )
    check(
        "a genuinely clear point exists on the floor",
        clearance.bestClearanceMm > oracle.dwellProximityMm,
        "densest-floor clearance only ${clearance.bestClearanceMm.toInt()}mm",
    )
    val openFloor = at5Hz.mapIndexed { i, s -> s.copy(p = Pt(clearance.best.x + (i % 3), clearance.best.y)) }
    val openFired = oracle.run(fixtures, openFloor)
    check("open floor fires nothing at all", openFired.isEmpty(), "fired ${openFired.map { it.fixtureCode }}")
    val lookingAway = at5Hz.map { s -> s.copy(viewDir = s.viewDir?.let { Pt(-it.x, -it.y) }) }
    val awayFired = oracle.run(fixtures, lookingAway)
    check(
        "standing at the fixture but facing away does not fire it",
        awayFired.none { it.fixtureCode == target.code },
        "fired ${awayFired.map { it.fixtureCode }}",
    )

    // ── 6. leave-and-return produces a SECOND impression ──────────────────────
    log.info("[6] leave-and-return")
    val first = BrowseEpisode(fixtures, emitHz = 5.0).browse(target.code, 8_000, 0L, rng)
    val second = BrowseEpisode(fixtures, emitHz = 5.0).browse(target.code, 8_000, 30_000L, rng)
    val both = oracle.run(fixtures, first + second)
    check("two separated visits produce two impressions", both.size == 2, "got ${both.size}")

    // ── 7. reported duration is sane ──────────────────────────────────────────
    log.info("[7] duration reporting")
    val d = fired5.first().durationMs
    check("duration is positive and under the episode length", d in 1..12_000, "duration=$d")
    // Validated against the live twin edge 2026-07-28 across 5 episodes: the edge reports the window at
    // cache EXPIRY (full episode span), never the window at creation. 12s episode → 12000ms.
    check("duration equals the full episode span, matching the live edge", d == 12_000L, "expected 12000, got $d")

    // ── 8. zone-affinity re-key portability (ruling 2026-07-28, decision 3) ───
    log.info("[8] zone-affinity re-key — portable across all 10 stores")
    val chainDir = Path.of(System.getenv("M8TRX_CHAIN_DIR") ?: "reference/data/chain", "stores")
    val storeDirs = java.nio.file.Files.list(chainDir).use { s -> s.sorted().toList() }
        .filter { java.nio.file.Files.exists(it.resolve("layout.json")) }
    check("all 10 stores present", storeDirs.size == 10, "found ${storeDirs.size}")

    val resolutions = storeDirs.map { ZoneRoleResolver.resolve(it.resolve("layout.json")) }
    val allUnmapped = resolutions.flatMap { it.unmapped }
    check(
        "every zone in every store resolves to a role — zero unmapped",
        allUnmapped.isEmpty(),
        "unmapped=${allUnmapped.take(8)}${if (allUnmapped.size > 8) " …+${allUnmapped.size - 8}" else ""}",
    )

    // The two codes the old §8 table named, which no longer exist anywhere — the reason for the re-key.
    val everyCode = resolutions.flatMap { r -> r.zones.map { it.zoneCode } }.toSet()
    check("Z-04 (old 'Main Sales Floor') is genuinely absent", "Z-04" !in everyCode)
    check("Z-10 (old 'Fitting Rooms') is genuinely absent", "Z-10" !in everyCode)

    // Roles that must exist in EVERY store for a journey to be constructible.
    val required = listOf(
        ZoneRole.ENTRANCE,
        ZoneRole.CHECKOUT,
        ZoneRole.DEPARTMENT_BAND,
        ZoneRole.FOOTWEAR_BENCH,
        ZoneRole.GAIT_ANALYSIS,
        ZoneRole.GPS_ACCESSORIES,
        ZoneRole.FITTING_ROOM,
    )
    for (role in required) {
        val missing = resolutions.filter { it.withRole(role).isEmpty() }.map { it.storeCode }
        check("$role present in all 10 stores", missing.isEmpty(), "missing in $missing")
    }

    val bandCounts = resolutions.associate { it.storeCode to it.withRole(ZoneRole.DEPARTMENT_BAND).size }
    check(
        "department bands are 2..7 per store (STATUS: min(7 universes, gondola rows))",
        bandCounts.values.all { it in 2..7 },
        "counts=$bandCounts",
    )
    log.info("  · department bands per store: {}", bandCounts.toSortedMap())
    log.info("  · sport universes across chain: {}", resolutions.flatMap { it.departments }.distinct().sorted())

    check(
        "checkout probability tracks the conversion rate, not a constant",
        ZoneAffinityModel.probability(ZoneRole.CHECKOUT, 0.22) == 0.22 &&
            ZoneAffinityModel.probability(ZoneRole.CHECKOUT, 0.30) == 0.30,
    )
    check(
        "back-of-house is not shopper-reachable",
        !ZoneRole.RECEIVING.shopperReachable && !ZoneRole.BACKROOM_RACK.shopperReachable,
    )
    log.info("  · ⚠ uncalibrated roles introduced by the re-key (NOT sourced): {}", ZoneAffinityModel.uncalibratedRoles)

    // ── 9. mother fixture-id sidecar + standoff targeting ────────────────────
    log.info("[9] fixture_ids sidecar + standoff targeting")
    val withIds = fixtures.fixtures.count { it.zoneId != null }
    check("fixture_ids.csv joined onto all 115 fixtures", withIds == 115, "got $withIds")
    check(
        "sidecar marks all 115 pipeline-ok after the GeometryConverter fix",
        fixtures.fixtures.count { !it.pipelineOk } == 0,
        "still not-ok: ${fixtures.fixtures.filter { !it.pipelineOk }.map { it.code }}",
    )
    check(
        "pipeline flag agrees with twin's own circle detection",
        fixtures.fixtures.all { it.pipelineOk == it.visibleToImpressionPipeline },
    )
    check(
        "GB-R3-U1 maps to the S13 compliance zone e82a21f3",
        fixtures.fixtures.first { it.code == "GB-R3-U1" }.zoneId?.startsWith("e82a21f3") == true,
    )

    // Paired gondolas ~1.4m apart are the case that broke targeting: the back unit's longest edge faces
    // its twin, so a naive standoff put the front unit in the ray. Verified live 2026-07-28 — GB-R3-U1
    // now lands on e82a21f3 (R3 Back) where it previously produced 998268a9 (R3 Front).
    val paired = fixtures.impressionVisible.filter { it.code.startsWith("GB-") || it.code.startsWith("GF-") }.take(24)
    val misTargeted = paired.filter { f ->
        val s = BrowseEpisode(fixtures, emitHz = 5.0).browse(f.code, 9_000, 0L, Random(7))
        oracle.run(fixtures, s).none { it.fixtureCode == f.code }
    }
    check(
        "standoff resolves to the REQUESTED fixture for paired gondolas",
        misTargeted.isEmpty(),
        "mis-targeted: ${misTargeted.map { it.code }}",
    )

    log.info("")
    log.info("peopleSelfTest — {} passed, {} failed", passed, failed)
    if (failed > 0) exitProcess(1)
}
