package com.m8trx.twin.layer1

import kotlin.math.hypot
import kotlin.random.Random

/**
 * Layer 1 — the "browse a fixture" behaviour, emitted at Xovis fidelity.
 *
 * Produces the position/view-direction sample stream for one shopper standing at one fixture. This is the
 * unit the impression pipeline actually consumes: a walk path with one pin per waypoint produces nothing,
 * because both of core's clocks reset on any gap over 1000ms.
 *
 * Emit rate defaults to **5 Hz**, the bottom of the real Xovis range (`M8TRX-API-SURFACE.md:44` — 5–25 Hz
 * per tracked person) and comfortably clear of the 1 Hz floor. Do not lower it below 2 Hz without running
 * [ImpressionOracle.explainSilence] first.
 *
 * `viewDirection` is a PLAIN DIRECTION VECTOR, not a unit vector: core's resolver builds an unbounded ray
 * from the slope, so magnitude is discarded. Real Xovis output carries non-unit magnitudes (the recorded
 * sample in `reference/events/Event-JSON-Schema.md:62` is `[-5.1, 9.5]`, magnitude ~10.8), so we match that
 * rather than emitting a suspiciously tidy unit vector.
 */
class BrowseEpisode(private val fixtures: FixtureSet, private val emitHz: Double = 5.0, private val viewMagnitude: Double = 10.8) {
    init {
        require(emitHz > 1.0) {
            "emitHz must exceed 1.0 — at or below 1 Hz every inter-sample gap exceeds core's 1000ms " +
                "allowance, both clocks reset on every sample, and no impression can ever fire."
        }
    }

    /**
     * Stand [standoffMm] from the nearest edge of [fixtureCode], facing it, for [dwellMs].
     *
     * [standoffMm] must stay under the 1000mm `dwellProximity` or the distance clause never holds; the
     * default 600mm is a realistic browsing stand-off with margin. Jitter models the small postural drift
     * a real tracker sees — it must never push the shopper outside the proximity band, so it is bounded.
     */
    fun browse(
        fixtureCode: String,
        dwellMs: Long,
        startTsMs: Long,
        rng: Random,
        standoffMm: Double = 600.0,
        jitterMm: Double = 80.0,
    ): List<ImpressionOracle.Sample> {
        val f = fixtures.fixtures.firstOrNull { it.code == fixtureCode }
            ?: error("no fixture '$fixtureCode' in ${fixtures.storeCode}/${fixtures.spaceCode}")
        require(f.visibleToImpressionPipeline) {
            "fixture '$fixtureCode' is flagged not-visible to the impression pipeline in fixture_ids.csv, so " +
                "browsing it can never produce an impression. (Circles WERE invisible until Connect's " +
                "GeometryConverter fix on 2026-07-28; the edge now loads 115 of 115.)"
        }
        require(standoffMm < 1000.0) { "standoffMm=$standoffMm is outside core's 1000mm dwellProximity" }

        val stand = standoffPoint(f, standoffMm)
        val stepMs = (1000.0 / emitHz).toLong()
        val n = (dwellMs / stepMs).toInt().coerceAtLeast(1)

        return (0..n).map { i ->
            val p = Pt(
                stand.x + (rng.nextDouble() - 0.5) * 2 * jitterMm,
                stand.y + (rng.nextDouble() - 0.5) * 2 * jitterMm,
            )
            ImpressionOracle.Sample(
                tsMs = startTsMs + i * stepMs,
                p = p,
                viewDir = aimAt(p, f.centre, viewMagnitude, rng),
                hasTag = false,
            )
        }
    }

    /**
     * A standoff point [standoffMm] outside the fixture, on a side from which the shopper can actually SEE
     * it — validated by ray-cast, not assumed.
     *
     * Naively stepping out from the longest edge is wrong on a real floor. Denver's gondolas are paired
     * front/back ~1.4m apart, so the longest edge of `GB-R3-U1` (Gondola R3 **Back**) faces its twin
     * `GF-R3-U1`; a shopper standing there and looking at the back unit's centroid has the FRONT unit in
     * the way, and the impression lands on the neighbour. Observed live 2026-07-28 and confirmed against
     * the mother fixture map: aiming at `GB-R3-U1` produced impressions on `998268a9` (R3 Front), not
     * `e82a21f3` (R3 Back).
     *
     * So: generate a candidate outside each edge, keep only those whose view ray genuinely resolves to this
     * fixture, and among those prefer the widest edge (the browsable face). Falls back to the longest-edge
     * candidate when none validates, so behaviour degrades rather than throwing.
     */
    private fun standoffPoint(f: Fixture, standoffMm: Double): Pt {
        data class Candidate(val p: Pt, val edgeLen: Double)
        val candidates = mutableListOf<Candidate>()

        // CIRCLES: stand ON the footprint, not beside it.
        //
        // Circles have no ring, and — more importantly — core gives them CONTAINMENT-ONLY proximity:
        // `Geometry.Circle.edges()` is a stub, so the dwellProximity band contributes nothing. A shopper
        // outside the radius never accumulates dwell no matter how close they stand. Proven live
        // 2026-07-28: a radius+600mm standoff on PI-01 produced `lookingAtFixture` but no
        // `dwellingNearbyFixture` and no impression.
        //
        // Standing on the footprint is also the intended semantic — core's own test note reads "anyone
        // stepping onto the footprint counts". Placed at 0.5r so the jitter cannot push the shopper out.
        if (f.kind == Fixture.Kind.CIRCLE) {
            val r = f.radiusMm ?: return f.centre
            return Pt(f.centre.x + r * 0.5, f.centre.y)
        }

        for (i in 0 until f.ring.size - 1) {
            val a = f.ring[i]
            val b = f.ring[i + 1]
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len <= 0.0) continue
            val mid = Pt((a.x + b.x) / 2, (a.y + b.y) / 2)
            // Outward normal = away from the centroid through the edge midpoint.
            val dx = mid.x - f.centre.x
            val dy = mid.y - f.centre.y
            val d = hypot(dx, dy)
            if (d <= 0.0) continue
            candidates += Candidate(Pt(mid.x + dx / d * standoffMm, mid.y + dy / d * standoffMm), len)
        }
        if (candidates.isEmpty()) return Pt(f.centre.x, f.centre.y + standoffMm)

        val visible = candidates.filter { c ->
            val aim = Pt(f.centre.x - c.p.x, f.centre.y - c.p.y)
            fixtures.lookingAt(c.p, aim)?.code == f.code
        }
        return (visible.maxByOrNull { it.edgeLen } ?: candidates.maxByOrNull { it.edgeLen }!!).p
    }

    /** Direction vector from [from] toward [to], scaled to [magnitude] with a little angular noise. */
    private fun aimAt(from: Pt, to: Pt, magnitude: Double, rng: Random): Pt {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val d = hypot(dx, dy).takeIf { it > 0.0 } ?: return Pt(0.0, magnitude)
        val jitterRad = (rng.nextDouble() - 0.5) * 0.12 // ±~3.4°, well inside a fixture's angular width
        val cos = kotlin.math.cos(jitterRad)
        val sin = kotlin.math.sin(jitterRad)
        val ux = dx / d
        val uy = dy / d
        return Pt((ux * cos - uy * sin) * magnitude, (ux * sin + uy * cos) * magnitude)
    }
}
