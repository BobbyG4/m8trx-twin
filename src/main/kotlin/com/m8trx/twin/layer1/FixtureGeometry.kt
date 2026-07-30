package com.m8trx.twin.layer1

import com.fasterxml.jackson.databind.JsonNode
import com.m8trx.twin.connect.model.ConnectMappers
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Twin-side fixture geometry, loaded from a store's `layout.json`.
 *
 * This is twin's OWN geometry code, used to PLAN walks — it is deliberately not a port of, and never a
 * patch to, `com.m8trx.geometry`. That artifact is shared with Android AR (`m8trx-geometry:0.9.0-SNAPSHOT`)
 * and is off-limits per Bob's standing instruction: if a geometry bug turns up, report it, don't fix it.
 *
 * All coordinates are millimetres in the space's SRF, matching `layout.json` (`coordinate_units: mm`,
 * origin SW corner). Denver's sales floor is 28,500 × 23,000 mm with 115 fixture zones.
 */
data class Pt(val x: Double, val y: Double)

/** A fixture zone as twin knows it locally — code + geometry, no mother UUID (those are unresolved; see the spine note). */
data class Fixture(
    val code: String,
    val name: String,
    val kind: Kind,
    /** Closed ring for [Kind.POLYGON] (first point repeated at the end, as emitted by `build_layout.py`). */
    val ring: List<Pt>,
    val centre: Pt,
    val radiusMm: Double?,
    val department: String?,
    /**
     * Mother's `zone.id`, when a `fixture_ids.csv` sidecar is present. Twin's `layout.json` carries geometry
     * but no platform identifiers (`zone_id: null`), so this is joined in from the map read off mother
     * 2026-07-28. Not required to DRIVE the pipeline — core resolves fixtures itself from x/y — but it is
     * what lets twin name a returned `fixtureId` instead of probing for it.
     */
    val zoneId: String? = null,
    /** False when core's evaluator silently skips this fixture (see [visibleToImpressionPipeline]). */
    val pipelineOk: Boolean = true,
) {
    enum class Kind { POLYGON, CIRCLE }

    /**
     * True when core's impression pipeline can see this fixture at all.
     *
     * **History (matters for reading older twin notes):** circles used to be invisible. `GeometryConverter`
     * handled only Polygon and returned a bare null with no logging, so Denver's 3 circle fixtures were
     * silently dropped and the evaluator loaded 112 of 115 — a shopper could browse one for a full minute
     * and produce nothing. Connect shipped the fix 2026-07-28 and the twin edge now loads **115 of 115**,
     * so circles are live and targetable.
     *
     * Driven by [pipelineOk] (from the `fixture_ids.csv` sidecar) so that if a geometry kind is ever dropped
     * again, the map is the single place that says so rather than a hard-coded kind check here.
     */
    val visibleToImpressionPipeline: Boolean get() = pipelineOk

    /** True when [p] lies inside the ring (ray-casting). Circles fall back to radius. */
    fun contains(p: Pt): Boolean = when (kind) {
        Kind.CIRCLE -> radiusMm != null && hypot(p.x - centre.x, p.y - centre.y) <= radiusMm
        Kind.POLYGON -> {
            var inside = false
            var j = ring.size - 1
            for (i in ring.indices) {
                val a = ring[i]
                val b = ring[j]
                if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
                j = i
            }
            inside
        }
    }

    /** Shortest distance from [p] to any edge of the ring, in mm. */
    fun distanceToEdge(p: Pt): Double {
        if (kind == Kind.CIRCLE) return abs(hypot(p.x - centre.x, p.y - centre.y) - (radiusMm ?: 0.0))
        var best = Double.MAX_VALUE
        for (i in 0 until ring.size - 1) best = min(best, distancePointToSegment(p, ring[i], ring[i + 1]))
        return best
    }

    /**
     * Core's DISTANCE clause, exactly: inside the polygon OR within [dwellProximityMm] of an edge.
     * Note the strict `<` — matching `ImpressionStateMachine`'s `distanceFromPoint(p) < dwellProximity`.
     */
    fun withinDwellRange(p: Pt, dwellProximityMm: Double): Boolean {
        if (!visibleToImpressionPipeline) return false
        // CIRCLES ARE CONTAINMENT-ONLY. Core's proximity clause is `contains(p) || edges().any { … }`, and
        // `Geometry.Circle.edges()` is a stub returning an empty sequence — so the dwellProximity band
        // contributes NOTHING for a circle. A shopper 0.5m from a promo island is "not nearby", where the
        // same shopper beside a polygon rack would be. Confirmed live 2026-07-28: a circle standoff produced
        // `lookingAtFixture` but no `dwellingNearbyFixture` and no impression. Core documents this as a
        // KNOWN GAP (the real fix is a `distanceToBoundary` on Geometry, which touches the artifact shared
        // with Android AR and is therefore a decision, not a drive-by).
        if (kind == Kind.CIRCLE) return contains(p)
        return contains(p) || distanceToEdge(p) < dwellProximityMm
    }

    /**
     * Distance along a ray from [origin] in direction [dir] at which it first crosses this fixture,
     * or null if it never does. Used to answer "is the target really the NEAREST fixture on this ray?" —
     * a shopper aiming past a near gondola at a far one is looking at the near one.
     *
     * Magnitude of [dir] is irrelevant (core builds an unbounded ray from the slope); only direction counts.
     */
    fun rayHitDistance(origin: Pt, dir: Pt): Double? {
        val len = hypot(dir.x, dir.y)
        if (len == 0.0) return null
        val d = Pt(dir.x / len, dir.y / len)
        if (contains(origin)) return 0.0

        if (kind == Kind.CIRCLE) {
            // Ray/circle: |origin + t·d − centre|² = r², solved for the nearest t ≥ 0.
            val r = radiusMm ?: return null
            val ox = origin.x - centre.x
            val oy = origin.y - centre.y
            val b = 2.0 * (ox * d.x + oy * d.y)
            val c = ox * ox + oy * oy - r * r
            val disc = b * b - 4.0 * c
            if (disc < 0.0) return null
            val sq = sqrt(disc)
            val t1 = (-b - sq) / 2.0
            val t2 = (-b + sq) / 2.0
            return listOf(t1, t2).filter { it >= 0.0 }.minOrNull()
        }

        var best: Double? = null
        for (i in 0 until ring.size - 1) {
            val t = raySegmentT(origin, d, ring[i], ring[i + 1]) ?: continue
            if (best == null || t < best) best = t
        }
        return best
    }
}

/** The fixtures of one space, plus the rule-relevant queries over the whole set. */
class FixtureSet(val storeCode: String, val spaceCode: String, val fixtures: List<Fixture>) {

    val impressionVisible: List<Fixture> get() = fixtures.filter { it.visibleToImpressionPipeline }

    /** Every fixture whose DISTANCE clause [p] satisfies — core's `nearbyFixtureIds`. */
    fun nearby(p: Pt, dwellProximityMm: Double): List<Fixture> = fixtures.filter { it.withinDwellRange(p, dwellProximityMm) }

    /**
     * The fixture an unbounded ray from [p] along [dir] first crosses — core's `EyeballFixtureResolver`
     * reduced to what matters for planning: nearest hit wins, occluders included.
     */
    fun lookingAt(p: Pt, dir: Pt): Fixture? = fixtures.mapNotNull { f -> f.rayHitDistance(p, dir)?.let { f to it } }.minByOrNull { it.second }?.first

    companion object {
        /**
         * Load the fixture zones of one space from a store's `layout.json`, joining `fixture_ids.csv`
         * (name → mother `zone_id` + pipeline flag) when it sits alongside. The sidecar is optional — twin
         * can drive the pipeline without it — so a missing file is not an error.
         */
        fun load(layoutJson: Path, spaceType: String = "sales_floor"): FixtureSet {
            val root = ConnectMappers.camel.readTree(Files.readAllBytes(layoutJson))
            val space = root.path("spaces").firstOrNull { it.path("space_type").asText() == spaceType }
                ?: error("no space of type '$spaceType' in $layoutJson")
            val ids = loadFixtureIds(layoutJson.resolveSibling("fixture_ids.csv"))
            val fixtures = space.path("zones")
                .filter { it.path("zone_type").asText() == "fixture" }
                .map { z ->
                    val f = parseFixture(z)
                    val row = ids[f.code]
                    if (row == null) f else f.copy(zoneId = row.first, pipelineOk = row.second)
                }
            return FixtureSet(root.path("store_id").asText(), space.path("code").asText(), fixtures)
        }

        /** code → (zone_id, pipelineOk). Empty when the sidecar is absent. */
        private fun loadFixtureIds(csv: Path): Map<String, Pair<String, Boolean>> {
            if (!Files.exists(csv)) return emptyMap()
            val lines = Files.readAllLines(csv)
            if (lines.size < 2) return emptyMap()
            val header = lines.first().split(",")
            val iCode = header.indexOf("code")
            val iZone = header.indexOf("zone_id")
            val iPipe = header.indexOf("pipeline")
            if (iCode < 0 || iZone < 0) return emptyMap()
            return lines.drop(1).mapNotNull { l ->
                val c = l.split(",")
                if (c.size <= maxOf(iCode, iZone)) return@mapNotNull null
                val ok = iPipe < 0 || c.getOrNull(iPipe)?.trim() == "ok"
                c[iCode] to (c[iZone] to ok)
            }.toMap()
        }

        private fun parseFixture(z: JsonNode): Fixture {
            val kind = if (z.path("geometry_type").asText() == "circle") Fixture.Kind.CIRCLE else Fixture.Kind.POLYGON
            val ring = if (kind == Fixture.Kind.POLYGON) parseRing(z.path("geometry").asText()) else emptyList()
            val centre = if (ring.isNotEmpty()) {
                Pt(ring.dropLast(1).sumOf { it.x } / (ring.size - 1), ring.dropLast(1).sumOf { it.y } / (ring.size - 1))
            } else {
                parseCircleCentre(z)
            }
            return Fixture(
                code = z.path("code").asText(),
                name = z.path("name").asText(),
                kind = kind,
                ring = ring,
                centre = centre,
                radiusMm = parseRadius(z),
                department = z.path("department").asText(null),
            )
        }

        /** `POLYGON Z ((x y z, …))` → ring. Z is always 0 in the twin dataset (SRID 0, mm, planar). */
        private fun parseRing(wkt: String): List<Pt> {
            val inner = wkt.substringAfter("((").substringBefore("))")
            return inner.split(",").map { pair ->
                val parts = pair.trim().split(Regex("\\s+"))
                Pt(parts[0].toDouble(), parts[1].toDouble())
            }
        }

        /**
         * Circle radius. The layout emits `properties.radiusX` / `radiusY` (an ellipse shape, always equal
         * in this dataset) — NOT `radius_mm`, which never exists. Reading the wrong key silently produced a
         * null radius, which made every circle un-hittable regardless of what core did.
         */
        private fun parseRadius(z: JsonNode): Double? {
            val p = z.path("properties")
            val rx = p.path("radiusX").asDouble(0.0)
            val ry = p.path("radiusY").asDouble(0.0)
            val r = if (rx > 0.0 && ry > 0.0) (rx + ry) / 2.0 else maxOf(rx, ry)
            return r.takeIf { it > 0.0 }
        }

        private fun parseCircleCentre(z: JsonNode): Pt {
            val g = z.path("geometry").asText()
            val inner = g.substringAfter("(").substringBefore(")").trim().split(Regex("\\s+"))
            return if (inner.size >= 2) Pt(inner[0].toDouble(), inner[1].toDouble()) else Pt(0.0, 0.0)
        }
    }
}

// ── local geometry helpers (twin's own; NOT com.m8trx.geometry) ─────────────────────────────────

internal fun distancePointToSegment(p: Pt, a: Pt, b: Pt): Double {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lenSq = dx * dx + dy * dy
    if (lenSq == 0.0) return hypot(p.x - a.x, p.y - a.y)
    var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq
    t = max(0.0, min(1.0, t))
    return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}

/** Ray/segment intersection; returns the ray parameter t >= 0 of the hit, or null. [d] must be unit-length. */
internal fun raySegmentT(o: Pt, d: Pt, a: Pt, b: Pt): Double? {
    val sx = b.x - a.x
    val sy = b.y - a.y
    val denom = d.x * sy - d.y * sx
    if (abs(denom) < 1e-9) return null
    val t = ((a.x - o.x) * sy - (a.y - o.y) * sx) / denom
    val u = ((a.x - o.x) * d.y - (a.y - o.y) * d.x) / denom
    return if (t >= 0.0 && u in 0.0..1.0) t else null
}

internal fun norm(p: Pt): Double = sqrt(p.x * p.x + p.y * p.y)
