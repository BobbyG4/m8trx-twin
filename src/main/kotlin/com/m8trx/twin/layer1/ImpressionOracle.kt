package com.m8trx.twin.layer1

/**
 * Twin-side ORACLE for core's fixture-impression rule.
 *
 * This predicts, offline, whether a planned `objLocation` stream would actually produce impressions. It
 * exists because the failure mode is silent: below the emit-rate floor, or with the ray off the fixture,
 * or beyond `dwellProximity`, core drops the events and `impression_event` simply stays empty — no error,
 * nothing above debug. A generator can look entirely correct and produce zero impressions forever.
 *
 * Same discipline as `connectFullLoop`'s per-target compliance expectation: twin computes what it expects,
 * then the platform is asked whether it agrees. **This oracle is not authoritative** — core's
 * `ImpressionStateMachine` is. When they disagree, core wins and the disagreement is the finding.
 *
 * The rule, as ruled 2026-07-28 (constants are core's defaults, overridable per deployment):
 *  - DISTANCE  inside the polygon OR within [dwellProximityMm] of an edge (default 1000mm = 1m)
 *  - DWELL     firstDwell→lastDwell while DISTANCE holds; RESETS if the gap exceeds [goAwayAllowanceMs]
 *  - VIEW      unbounded ray picks the NEAREST fixture it crosses; firstLook→lastLook;
 *              RESETS if the gap exceeds [lookAwayAllowanceMs]
 *  - IMPRESSION the SAME fixture satisfies BOTH clocks past [millisTillImpression]
 *  - A reset also nulls the impression id, so leave-and-return produces a NEW impression.
 *
 * ⇒ HARD FLOOR: emit strictly faster than 1 Hz. At or below it, every gap exceeds the 1000ms allowance,
 *   both clocks reset to the current sample on every event, the elapsed window stays 0, and
 *   [millisTillImpression] is mathematically unreachable however long the shopper stands there.
 */
class ImpressionOracle(
    val dwellProximityMm: Double = 1000.0,
    val goAwayAllowanceMs: Long = 1000L,
    val lookAwayAllowanceMs: Long = 1000L,
    val millisTillImpression: Long = 5000L,
) {
    /** One emitted position sample — the twin-side shape of an `objLocation` body plus its timestamp. */
    data class Sample(val tsMs: Long, val p: Pt, val viewDir: Pt?, val hasTag: Boolean = false)

    /** A predicted impression: which fixture, and the four clock marks core would stamp. */
    data class Predicted(val fixtureCode: String, val firstDwellMs: Long, val lastDwellMs: Long, val firstLookMs: Long, val lastLookMs: Long) {
        /** Core's `FixtureImpression.duration` — `min(lastDwell - firstDwell, lastLook - firstDwell)`. */
        val durationMs: Long get() = minOf(lastDwellMs - firstDwellMs, lastLookMs - firstDwellMs)
    }

    private data class Clocks(
        var firstDwell: Long? = null,
        var lastDwell: Long? = null,
        var firstLook: Long? = null,
        var lastLook: Long? = null,
        var fired: Boolean = false,
    )

    /**
     * Run [samples] through the rule and return every impression core would create.
     *
     * Mirrors `XovisImpressionEvaluator.onObjectLocation`: a sample with `hasTag == true`, or with no
     * view direction, is dropped ENTIRELY — which also costs the dwell half, even though the distance
     * clause needs no vector. That asymmetry is deliberate upstream and is the reason the
     * view-direction-off configuration must stay observable.
     */
    fun run(fixtures: FixtureSet, samples: List<Sample>): List<Predicted> {
        val out = mutableListOf<Predicted>()
        val state = mutableMapOf<String, Clocks>()

        /**
         * Emit a fired impression with its FINAL window.
         *
         * Validated against the live twin edge 2026-07-28 (5 episodes: 12000/8000/15000/9000/7000ms — the
         * reported duration equalled the full episode span every time). Core `create()`s the impression the
         * moment both clocks cross [millisTillImpression], then `update()`s it on every subsequent sample,
         * and each `cache.put` resets `expireAfterWrite` — so what finally publishes carries the window at
         * EXPIRY, not at creation. An earlier version of this oracle froze at creation and under-reported
         * (predicted 5200ms against an actual 15000ms). Flushed on reset (leave-and-return is a new
         * impression) and again at end of stream.
         */
        fun flush(code: String, c: Clocks) {
            if (!c.fired) return
            out += Predicted(code, c.firstDwell!!, c.lastDwell!!, c.firstLook!!, c.lastLook!!)
            c.fired = false
        }

        for (s in samples) {
            // The gate at XovisImpressionEvaluator.kt:311 — both halves lost when viewDirection is absent.
            if (s.hasTag || s.viewDir == null) continue

            val lookingAt = fixtures.lookingAt(s.p, s.viewDir)
            if (lookingAt != null && lookingAt.visibleToImpressionPipeline) {
                val c = state.getOrPut(lookingAt.code) { Clocks() }
                if (c.lastLook == null || s.tsMs - c.lastLook!! > lookAwayAllowanceMs) {
                    flush(lookingAt.code, c) // the previous impression ended when the look lapsed
                    c.firstLook = s.tsMs
                }
                c.lastLook = s.tsMs
            }

            for (f in fixtures.nearby(s.p, dwellProximityMm)) {
                val c = state.getOrPut(f.code) { Clocks() }
                if (c.lastDwell == null || s.tsMs - c.lastDwell!! > goAwayAllowanceMs) {
                    flush(f.code, c)
                    c.firstDwell = s.tsMs
                }
                c.lastDwell = s.tsMs

                if (lookingAt?.code != f.code) continue
                val fd = c.firstDwell ?: continue
                val fl = c.firstLook ?: continue
                val ld = c.lastDwell ?: continue
                val ll = c.lastLook ?: continue
                // Strict `>`, matching core's `lastLook.minusMillis(m).isAfter(firstLook)`.
                // Once fired we keep extending lastDwell/lastLook above; the final window is emitted by flush().
                if (!c.fired && (ll - fl) > millisTillImpression && (ld - fd) > millisTillImpression) {
                    c.fired = true
                }
            }
        }
        state.forEach { (code, c) -> flush(code, c) }
        return out
    }

    /**
     * Why a stream would produce nothing — the diagnostic that turns a silent zero into a sentence.
     * Returns an empty list when [samples] would fire at least one impression.
     */
    fun explainSilence(fixtures: FixtureSet, samples: List<Sample>): List<String> {
        if (run(fixtures, samples).isNotEmpty()) return emptyList()
        val reasons = mutableListOf<String>()

        if (samples.isEmpty()) return listOf("no samples emitted")
        val usable = samples.filter { !it.hasTag && it.viewDir != null }
        if (usable.isEmpty()) {
            reasons += "every sample was dropped at the gate: " +
                "${samples.count { it.viewDir == null }} with no viewDirection, ${samples.count { it.hasTag }} with hasTag=true"
            return reasons
        }

        val gaps = usable.zipWithNext { a, b -> b.tsMs - a.tsMs }
        val worst = gaps.maxOrNull() ?: 0L
        if (worst > minOf(goAwayAllowanceMs, lookAwayAllowanceMs)) {
            val hz = if (worst > 0) 1000.0 / worst else 0.0
            reasons += "emit rate below the floor: largest inter-sample gap ${worst}ms (~%.2f Hz) exceeds the ".format(hz) +
                "${minOf(goAwayAllowanceMs, lookAwayAllowanceMs)}ms allowance — both clocks reset on every sample, " +
                "so the elapsed window never grows. Emit faster than 1 Hz (Xovis runs 5–25 Hz)."
        }

        val everNear = usable.any { fixtures.nearby(it.p, dwellProximityMm).isNotEmpty() }
        if (!everNear) {
            reasons += "no sample ever came within ${dwellProximityMm.toInt()}mm of a polygon fixture edge " +
                "(distance clause never satisfied)"
        }
        val everLooking = usable.any { s -> s.viewDir?.let { fixtures.lookingAt(s.p, it) } != null }
        if (!everLooking) reasons += "the view ray never crossed any fixture (look clause never started)"

        val bothOnSame = usable.any { s ->
            val la = s.viewDir?.let { fixtures.lookingAt(s.p, it) }
            la != null && fixtures.nearby(s.p, dwellProximityMm).any { it.code == la.code }
        }
        if (everNear && everLooking && !bothOnSame) {
            reasons += "distance and view were never satisfied on the SAME fixture at the same time " +
                "(shopper stood at one fixture while looking at another)"
        }

        // Report the two clocks SEPARATELY, not the total stream span.
        //
        // This used to measure `usable.last().tsMs - usable.first().tsMs` and call it "the episode". That
        // was only ever right because every episode twin generated held both clauses over the identical
        // window — so stream span, look span and dwell span were the same number and the shortcut could not
        // be caught. The F4 three-phase episode (approach / engage / disengage) separates them, and the
        // shortcut immediately reported a 4s look as "long enough" because the surrounding proximity
        // brought the stream to 6.4s. The rule gates on each clock, so the diagnostic must too.
        val lookTs = usable.filter { s -> s.viewDir?.let { fixtures.lookingAt(s.p, it) } != null }.map { it.tsMs }
        val nearTs = usable.filter { s -> fixtures.nearby(s.p, dwellProximityMm).isNotEmpty() }.map { it.tsMs }
        val lookSpan = if (lookTs.isEmpty()) 0L else lookTs.max() - lookTs.min()
        val dwellSpan = if (nearTs.isEmpty()) 0L else nearTs.max() - nearTs.min()
        if (lookSpan <= millisTillImpression || dwellSpan <= millisTillImpression) {
            val which = when {
                lookSpan <= millisTillImpression && dwellSpan <= millisTillImpression -> "both clocks"
                lookSpan <= millisTillImpression -> "the LOOK clock"
                else -> "the DWELL clock"
            }
            reasons += "episode too short on $which: look=${lookSpan}ms dwell=${dwellSpan}ms, each needs " +
                "strictly more than ${millisTillImpression}ms (plus ~10s cache lag before it reaches the subscriber)"
        }

        if (reasons.isEmpty()) reasons += "no single cause identified — inspect the sample stream directly"
        return reasons
    }
}
