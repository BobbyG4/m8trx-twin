package com.m8trx.twin.livefloor

import com.m8trx.twin.connect.model.ConnectMappers
import com.m8trx.twin.layer1.Fixture
import com.m8trx.twin.layer1.FixtureSet
import com.m8trx.twin.layer1.Pt
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.hypot

/**
 * One store's sales floor as the walking model sees it, read (never changed) from `reference/data/chain/stores/<slug>/layout.json`:
 * the floor rectangle, the fixtures with their footprints, the entrance gate (`CS-01`), the checkout area and the non-retail strip
 * (the nearest the sales-floor frame has to a fitting-room door: the fitting room is its own space with its own SRF).
 */
class StoreFloor(
    val slug: String,
    val widthMm: Double,
    val depthMm: Double,
    val fixtures: List<Fixture>,
    val category: Map<String, String>,
    val gateX0: Double,
    val gateX1: Double,
    val gateY: Double,
    val checkout: Rect?,
    val strip: Rect?,
    val spaceCode: String,
) {
    data class Rect(val x0: Double, val y0: Double, val x1: Double, val y1: Double)

    /** An in-memory "what if" on one rectangular fixture: each edge of its footprint moved by a delta in mm (`x0`/`y0` the low edges, `x1`/`y1` the high). */
    data class FixtureEdit(val code: String, val dx0: Double = 0.0, val dy0: Double = 0.0, val dx1: Double = 0.0, val dy1: Double = 0.0)

    /** `PE-last` -> the highest-numbered `PE-nn` of this store; any other code is itself. */
    fun resolve(code: String): String {
        if (!code.endsWith("-last")) return code
        val prefix = code.removeSuffix("-last")
        return fixtures.map { it.code }.filter { it.startsWith("$prefix-") && it.drop(prefix.length + 1).all(Char::isDigit) }.maxOrNull()
            ?: error("$slug: no fixture matches $code")
    }

    /** A copy of this floor with [edits] applied to the footprints (layout.json is never touched). */
    fun withEdits(edits: List<FixtureEdit>): StoreFloor {
        val byCode = edits.associateBy { resolve(it.code) }
        val fx = fixtures.map { f ->
            val e = byCode[f.code] ?: return@map f
            require(f.kind == Fixture.Kind.POLYGON) { "$slug ${f.code}: only rectangular fixtures can be edited" }
            val x0 = f.ring.minOf { it.x } + e.dx0
            val y0 = f.ring.minOf { it.y } + e.dy0
            val x1 = f.ring.maxOf { it.x } + e.dx1
            val y1 = f.ring.maxOf { it.y } + e.dy1
            require(x1 > x0 && y1 > y0) { "$slug ${f.code}: the edit leaves no footprint" }
            f.copy(ring = listOf(Pt(x0, y0), Pt(x1, y0), Pt(x1, y1), Pt(x0, y1), Pt(x0, y0)), centre = Pt((x0 + x1) / 2, (y0 + y1) / 2))
        }
        return StoreFloor(slug, widthMm, depthMm, fx, category, gateX0, gateX1, gateY, checkout, strip, spaceCode)
    }

    companion object {
        /** `CO-01@x0=+600;PE-last@y1=-600` -> edits (keys `x0 y0 x1 y1` move one edge, `mx` / `my` move the whole fixture). */
        fun parseEdits(spec: String): List<FixtureEdit> = spec.split(";").map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
            val (code, kv) = part.split("@", limit = 2)
            val m = kv.split(",").associate { p -> p.substringBefore("=").trim() to p.substringAfter("=").trim().toDouble() }
            FixtureEdit(
                code.trim(),
                (m["x0"] ?: 0.0) + (m["mx"] ?: 0.0),
                (m["y0"] ?: 0.0) + (m["my"] ?: 0.0),
                (m["x1"] ?: 0.0) + (m["mx"] ?: 0.0),
                (m["y1"] ?: 0.0) + (m["my"] ?: 0.0),
            )
        }

        fun load(slug: String, chainDir: Path): StoreFloor {
            val layout = chainDir.resolve("stores").resolve(slug).resolve("layout.json")
            val root = ConnectMappers.camel.readTree(Files.readAllBytes(layout))
            val space = root.path("spaces").first { it.path("space_type").asText() == "sales_floor" }
            val set = FixtureSet.load(layout)
            val cats = space.path("zones").filter { it.path("zone_type").asText() == "fixture" }
                .associate { it.path("code").asText() to it.path("fixture_category").asText() }
            fun rect(z: com.fasterxml.jackson.databind.JsonNode): Rect = z.path("rect_mm").let {
                Rect(it.path("x1").asDouble(), it.path("y1").asDouble(), it.path("x2").asDouble(), it.path("y2").asDouble())
            }
            val zones = space.path("zones")
            val slice = space.path("crossing_slices").first { it.path("code").asText() == "CS-01" }
            val strip = zones.firstOrNull { it.path("code").asText() == "Z-03" }?.let(::rect)
            val checkout = zones.firstOrNull { it.path("zone_type").asText() == "checkout" }?.let(::rect)
            return StoreFloor(
                slug,
                space.path("footprint_mm").path("width").asDouble(),
                space.path("footprint_mm").path("depth").asDouble(),
                set.fixtures,
                cats,
                slice.path("x_start").asDouble(),
                slice.path("x_end").asDouble(),
                slice.path("y").asDouble(),
                checkout,
                strip,
                space.path("code").asText(),
            )
        }
    }
}

/** A place a person stands to look at a fixture: [x], [y] is the stand (0.25 m off the face), [ax], [ay] the point they walk in from. */
class Stand(
    val fixture: String,
    val x: Double,
    val y: Double,
    val ax: Double,
    val ay: Double,
    /** Unit direction the person faces (toward the fixture face). */
    val fx: Double,
    val fy: Double,
    /** True for the one stand of a fixture with no legal one: its most open face, neighbours as close as 0.10 m. */
    val relaxed: Boolean = false,
) {
    /** Unit tangent along the face. */
    val tx: Double get() = -fy
    val ty: Double get() = fx
}

/** A polyline with cumulative arc length. */
class Polyline(val xs: DoubleArray, val ys: DoubleArray) {
    val cum: DoubleArray = DoubleArray(xs.size).also { c -> for (i in 1 until xs.size) c[i] = c[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]) }
    val length: Double get() = cum.last()
    private var hint = 0

    /** Point at arc length [s] (clamped). Sequential access is O(1). */
    fun at(s: Double): Pt {
        val d = s.coerceIn(0.0, length)
        if (hint >= xs.size - 1 || cum[hint] > d) hint = 0
        while (hint < xs.size - 2 && cum[hint + 1] < d) hint++
        val seg = cum[hint + 1] - cum[hint]
        val f = if (seg <= 0.0) 0.0 else (d - cum[hint]) / seg
        return Pt(xs[hint] + (xs[hint + 1] - xs[hint]) * f, ys[hint] + (ys[hint + 1] - ys[hint]) * f)
    }
}
