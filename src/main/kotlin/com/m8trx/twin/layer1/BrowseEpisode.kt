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
     * Stand [standoffMm] from the nearest edge of [fixtureCode] for [approachMs] + [dwellMs] +
     * [disengageMs], **facing it only for the middle span**.
     *
     * [standoffMm] must stay under the 1000mm `dwellProximity` or the distance clause never holds; the
     * default 600mm is a realistic browsing stand-off with margin. Jitter models the small postural drift
     * a real tracker sees — it must never push the shopper outside the proximity band, so it is bounded.
     *
     * ## Why the episode has three phases (F4, 2026-07-31)
     *
     * Until now every sample was emitted at the standoff **facing the fixture**, so the proximity clause and
     * the look clause went true on the same sample and false on the same sample. Every persisted row came
     * back with `firstDwell == firstLook` and `lastDwell == lastLook` **to the millisecond** — measured over
     * 3,119 rows, 100% of them.
     *
     * That is not how a shopper behaves, and it had a consequence beyond realism: core's duration rule is
     * `min(lastDwell - firstDwell, lastLook - firstDwell)`, and against coincident clocks **both arms are
     * identical, so the `min` never discriminates and its selection has never been exercised by anything.**
     * Any analytic separating "looked at" from "lingered near" was reading the same number twice.
     *
     * So: **approach** (nearby, looking elsewhere) → **engage** (nearby, looking) → **disengage** (nearby,
     * looking away again). Position is the same standoff throughout, so `dwellProximity` holds across the
     * whole episode and only the look clause changes. That yields `firstDwell < firstLook` and
     * `lastDwell > lastLook`, so the `min` resolves to `lastLook - firstDwell` and the rule's real branch
     * is finally under test.
     *
     * [disengageMs] deliberately exceeds `lookAwayAllowanceMs` (1000ms) — at or under it the look clock is
     * merely paused, not stopped, and the clocks would re-converge. The **engaged** span is left equal to
     * [dwellMs] rather than carved out of it, so impression *counts* are unchanged and this alters the shape
     * of an episode without silently changing what a day produces.
     *
     * Pass `approachMs = 0, disengageMs = 0` for the old coincident-clock shape (offline conformance cases
     * that assert against a single span still do this deliberately).
     */
    fun browse(
        fixtureCode: String,
        dwellMs: Long,
        startTsMs: Long,
        rng: Random,
        standoffMm: Double = 600.0,
        jitterMm: Double = 80.0,
        // Env-overridable so the three-phase shape can be switched OFF live without a code edit. That is
        // not a convenience: when a drive stops persisting, "did MY change do this or did a deploy?" is the
        // first question, and answering it needs an A/B on the same build in the same hour.
        approachMs: Long = System.getenv("M8TRX_EPISODE_APPROACH_MS")?.toLongOrNull() ?: 1_200,
        disengageMs: Long = System.getenv("M8TRX_EPISODE_DISENGAGE_MS")?.toLongOrNull() ?: 1_200,
    ): List<ImpressionOracle.Sample> {
        val f = fixtures.fixtures.firstOrNull { it.code == fixtureCode }
            ?: error("no fixture '$fixtureCode' in ${fixtures.storeCode}/${fixtures.spaceCode}")
        require(f.visibleToImpressionPipeline) {
            "fixture '$fixtureCode' is flagged not-visible to the impression pipeline in fixture_ids.csv, so " +
                "browsing it can never produce an impression. (Circles WERE invisible until Connect's " +
                "GeometryConverter fix on 2026-07-28; the edge now loads 115 of 115.)"
        }
        require(standoffMm < 1000.0) { "standoffMm=$standoffMm is outside core's 1000mm dwellProximity" }

        val stand = standCache.computeIfAbsent(f.code to standoffMm) { standoffPoint(f, standoffMm) }
        // Jitter must never push the shopper across either surface — into the fixture, or into the
        // neighbour behind them. In a tight aisle the standoff shrinks, so the jitter has to shrink with it.
        val effJitter = minOf(jitterMm, stand.standoffMm * 0.4)
        val stepMs = (1000.0 / emitHz).toLong()
        val nApproach = (approachMs / stepMs).toInt().coerceAtLeast(0)
        val nEngaged = (dwellMs / stepMs).toInt().coerceAtLeast(1)
        val nDisengage = (disengageMs / stepMs).toInt().coerceAtLeast(0)

        // Position is the SAME standoff throughout — the shopper is inside `dwellProximity` for the whole
        // episode. Only where they are LOOKING changes. That is what makes the two clocks diverge.
        fun sampleAt(i: Int, looking: Boolean): ImpressionOracle.Sample {
            val p = Pt(
                stand.p.x + (rng.nextDouble() - 0.5) * 2 * effJitter,
                stand.p.y + (rng.nextDouble() - 0.5) * 2 * effJitter,
            )
            // Looking away = aimed at the mirror point through the shopper, i.e. 180° off the fixture.
            // Anywhere well off-axis would do; the antipode is unambiguous and needs no extra geometry.
            val target = if (looking) f.centre else Pt(2 * p.x - f.centre.x, 2 * p.y - f.centre.y)
            return ImpressionOracle.Sample(
                tsMs = startTsMs + i * stepMs,
                p = p,
                viewDir = aimAt(p, target, viewMagnitude, rng),
                hasTag = false,
            )
        }

        val out = ArrayList<ImpressionOracle.Sample>(nApproach + nEngaged + nDisengage + 2)
        var i = 0
        repeat(nApproach) { out += sampleAt(i++, looking = false) }
        repeat(nEngaged + 1) { out += sampleAt(i++, looking = true) }
        repeat(nDisengage) { out += sampleAt(i++, looking = false) }
        return out
    }

    /** Where the shopper stands, and how much postural drift that position can absorb. */
    private data class Stand(val p: Pt, val standoffMm: Double)

    /**
     * `(fixture, requested standoff)` → chosen stand. The computation is deterministic and independent of
     * the shopper, but [clearanceAlong] marches the whole fixture list per edge, and a generated day calls
     * [browse] ~10k times over the same 115 fixtures. Cached, a full day's standoff solving happens 115
     * times instead of ~10,000. Concurrent because the publishing drivers are coroutine-based.
     */
    private val standCache = java.util.concurrent.ConcurrentHashMap<Pair<String, Double>, Stand>()

    /**
     * How far one can step outward along [dir] from [from] before entering another fixture, capped at [cap].
     *
     * Marched rather than solved: the answer only needs to be good to a few centimetres, and a march reuses
     * the same `contains` the rule itself uses instead of introducing a second, subtly different geometry.
     */
    private fun clearanceAlong(from: Pt, dir: Pt, self: Fixture, cap: Double): Double {
        var d = STEP_MM
        while (d <= cap) {
            val p = Pt(from.x + dir.x * d, from.y + dir.y * d)
            if (fixtures.fixtures.any { it.code != self.code && it.contains(p) }) return d - STEP_MM
            d += STEP_MM
        }
        return cap
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
     *
     * **The standoff is fitted to each edge's CLEARANCE, not fixed at [standoffMm].** A fixed 600mm assumes
     * every fixture has 600mm of walkable floor outside it, and two of Denver's do not: `ACC-02` and `GPS-04`
     * are boxed in on all four sides with 200–400mm gaps (`ACC-01`/`ACC-03` at 200mm, `GPS-04`/`GA-01` at
     * 400mm), so every 600mm candidate lands *inside* a neighbour, nothing validates, and the fallback aims
     * the shopper at the neighbour. Both fixtures were therefore uncoverable — the same
     * lands-on-the-wrong-fixture class as the paired-gondola bug, from the opposite direction.
     *
     * So each edge gets `min(standoffMm, clearance / 2)` — centred in whatever gap exists, and **identical to
     * the old behaviour wherever there is room** (clearance is capped at `2 × standoffMm`, so an open edge
     * yields exactly [standoffMm]). Edges with less than [MIN_CLEARANCE_MM] of gap are dropped rather than
     * squeezed: that is the paired-gondola case, where the neighbour is flush against the face and no
     * standing position outside it exists at all.
     */
    private fun standoffPoint(f: Fixture, standoffMm: Double): Stand {
        data class Candidate(val p: Pt, val edgeLen: Double, val standoff: Double)
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
            val r = f.radiusMm ?: return Stand(f.centre, standoffMm)
            return Stand(Pt(f.centre.x + r * 0.5, f.centre.y), r * 0.5)
        }

        val fallbacks = mutableListOf<Candidate>()
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
            val n = Pt(dx / d, dy / d)
            fallbacks += Candidate(Pt(mid.x + n.x * standoffMm, mid.y + n.y * standoffMm), len, standoffMm)

            // Cap the march at 2x the requested standoff so an open edge resolves to exactly standoffMm.
            val clearance = clearanceAlong(mid, n, f, cap = standoffMm * 2)
            if (clearance < MIN_CLEARANCE_MM) continue
            val off = minOf(standoffMm, clearance / 2)
            candidates += Candidate(Pt(mid.x + n.x * off, mid.y + n.y * off), len, off)
        }
        if (candidates.isEmpty() && fallbacks.isEmpty()) return Stand(Pt(f.centre.x, f.centre.y + standoffMm), standoffMm)

        val visible = candidates.filter { c ->
            val aim = Pt(f.centre.x - c.p.x, f.centre.y - c.p.y)
            fixtures.lookingAt(c.p, aim)?.code == f.code
        }
        val best = visible.maxByOrNull { it.edgeLen }
            ?: candidates.maxByOrNull { it.edgeLen }
            ?: fallbacks.maxByOrNull { it.edgeLen }!!
        return Stand(best.p, best.standoff)
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

    private companion object {
        /** March resolution for [clearanceAlong] — centimetre-ish is plenty to place a standing shopper. */
        const val STEP_MM = 25.0

        /**
         * Below this much gap an edge is not a browsable face at all. Denver's paired gondolas are flush
         * (`GF-R6-U1` ends at y=4428, `GB-R6-U1` starts there), so the back unit's front face has zero
         * clearance and there is no position outside it to stand in — the aisle-facing edge is the answer,
         * and squeezing a shopper into a nonexistent gap would only re-create the wrong-fixture bug.
         */
        const val MIN_CLEARANCE_MM = 150.0
    }
}
