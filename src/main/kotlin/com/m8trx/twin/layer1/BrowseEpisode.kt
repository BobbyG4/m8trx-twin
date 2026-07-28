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
            "fixture '$fixtureCode' is a ${f.kind} — core's ImpressionStateMachine does not implement circle " +
                "proximity (it warns and skips), so browsing it can never produce an impression. Pick a polygon fixture."
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
     * A point [standoffMm] outside the fixture's nearest edge, on the side facing the aisle.
     * Approximated by stepping out from the centroid toward the closest edge midpoint and beyond.
     */
    private fun standoffPoint(f: Fixture, standoffMm: Double): Pt {
        // Step outward along the centroid → edge-midpoint direction for the longest edge (the browsable face).
        var bestMid = f.centre
        var bestLen = -1.0
        for (i in 0 until f.ring.size - 1) {
            val a = f.ring[i]
            val b = f.ring[i + 1]
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len > bestLen) {
                bestLen = len
                bestMid = Pt((a.x + b.x) / 2, (a.y + b.y) / 2)
            }
        }
        val dx = bestMid.x - f.centre.x
        val dy = bestMid.y - f.centre.y
        val d = hypot(dx, dy).takeIf { it > 0.0 } ?: return Pt(f.centre.x, f.centre.y + standoffMm)
        return Pt(bestMid.x + dx / d * standoffMm, bestMid.y + dy / d * standoffMm)
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
