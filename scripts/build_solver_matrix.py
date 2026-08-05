#!/usr/bin/env python3
"""
Generate the SOLVER TEST-SPACE MATRIX — 14 schema-true spaces for core's anchor-pattern solver.

Brief: m8trx-shared/status/briefs/BRIEF-TWIN-SOLVER-MATRIX-GENERATOR-2026-08-05.md

Sibling to build_layout.py, NOT an extension of it. build_layout.generate() is one monolithic
Decathlon grammar (gondola hall + sport-universe departments + BOH + fitting rooms) and every
archetype here is something that grammar cannot say. What IS shared is the discipline:
deterministic off sha256(name), correct-by-construction placement, and every space asserted
overlap-free + in-bounds before it is written.

Three deliberate departures from build_layout.py, each forced by the brief:
  1. METRES, not millimetres (§3 "in SRF meters"). Emitted as floats, 3dp.
  2. ARBITRARY RINGS, not rectangles. build_layout's geometry_for() can only emit an AABB ring,
     so it cannot express the §2 non-convex L/U pathology. Boundaries here are point lists and
     containment is ray-cast, not bbox-compared.
  3. ROTATED fixtures. build_layout zeroes rotation for every rect (_rebase:128); showroom
     islands are meaningless axis-aligned, so overlap testing here is SAT over convex polygons.

NO WRITE PATH. This script emits a file and nothing else. Twin has no sanctioned route to create
a site/space/zone on mother — measured 2026-08-05, see the RESULTS append on the brief. Core
ingests solver-matrix.json into the quarantine site on its side (Bob's ruling, 2026-08-05).

Out: reference/data/solver/solver-matrix.json
"""
import hashlib
import json
import math
import os
import random

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "reference/data/solver")

SITE_NAME = "Solver Test Facility"
SITE_SLUG = "solver-test-facility"

# Provisional space_type tokens. `space_type` has NO CREATE TYPE / CHECK in 7a — it is an
# unratified free-text field (SPATIAL-HIERARCHY.md:48, "twin emits these provisionally").
# EXISTING = a token already live on mother. NEW = minted here for FR-37 regime variety and
# flagged as such in the manifest so BW ratifies deliberately rather than inheriting by accident.
SPACE_TYPES_EXISTING = {"sales_floor", "stockroom", "fitting_room"}


def seed_of(name):
    return int(hashlib.sha256(name.encode()).hexdigest(), 16) % (2**31)


def r3(v):
    # normalise -0.0 → 0.0 so rings compare byte-stably across runs
    return round(float(v) + 0.0, 3)


# ── geometry ────────────────────────────────────────────────────────────────────────────────

def ring_rect(x1, y1, x2, y2):
    """Closed CCW ring for an axis-aligned rect."""
    return [[r3(x1), r3(y1)], [r3(x2), r3(y1)], [r3(x2), r3(y2)], [r3(x1), r3(y2)], [r3(x1), r3(y1)]]


def ring_rot(cx, cy, w, d, deg):
    """Closed CCW ring for a rect of w×d centred (cx,cy), rotated `deg` CCW about its centre."""
    t = math.radians(deg)
    ct, st = math.cos(t), math.sin(t)
    hw, hd = w / 2.0, d / 2.0
    pts = []
    for dx, dy in ((-hw, -hd), (hw, -hd), (hw, hd), (-hw, hd)):
        pts.append([r3(cx + dx * ct - dy * st), r3(cy + dx * st + dy * ct)])
    return pts + [pts[0]]


def poly_area(ring):
    """Shoelace over a closed ring. Positive ⇒ CCW."""
    v = ring[:-1]
    s = sum(v[i][0] * v[(i + 1) % len(v)][1] - v[(i + 1) % len(v)][0] * v[i][1] for i in range(len(v)))
    return s / 2.0


def bbox_of(ring):
    xs = [p[0] for p in ring]
    ys = [p[1] for p in ring]
    return {"x1": r3(min(xs)), "y1": r3(min(ys)), "x2": r3(max(xs)), "y2": r3(max(ys))}


def is_convex(ring):
    v = ring[:-1]
    n = len(v)
    sign = 0
    for i in range(n):
        ax, ay = v[i]
        bx, by = v[(i + 1) % n]
        cx, cy = v[(i + 2) % n]
        cr = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
        if abs(cr) < 1e-9:
            continue
        s = 1 if cr > 0 else -1
        if sign and s != sign:
            return False
        sign = s
    return True


def point_in_ring(p, ring):
    """Ray-cast, boundary counts as inside (within 1e-9). Handles non-convex."""
    x, y = p
    v = ring[:-1]
    n = len(v)
    for i in range(n):
        ax, ay = v[i]
        bx, by = v[(i + 1) % n]
        # on-segment test
        cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
        if abs(cross) < 1e-9 and min(ax, bx) - 1e-9 <= x <= max(ax, bx) + 1e-9 \
                and min(ay, by) - 1e-9 <= y <= max(ay, by) + 1e-9:
            return True
    inside = False
    for i in range(n):
        ax, ay = v[i]
        bx, by = v[(i + 1) % n]
        if (ay > y) != (by > y):
            xin = (bx - ax) * (y - ay) / (by - ay) + ax
            if x < xin:
                inside = not inside
    return inside


def sat_overlap(ra, rb):
    """True if two CONVEX closed rings overlap with positive area (touching edges do NOT count).
    Separating Axis Theorem — needed because rotated islands make bbox comparison wrong in both
    directions (false overlaps on the diagonal, missed overlaps on the corner)."""
    for poly in (ra, rb):
        v = poly[:-1]
        n = len(v)
        for i in range(n):
            ax, ay = v[i]
            bx, by = v[(i + 1) % n]
            nx, ny = -(by - ay), (bx - ax)
            ln = math.hypot(nx, ny)
            if ln < 1e-12:
                continue
            nx, ny = nx / ln, ny / ln
            pa = [p[0] * nx + p[1] * ny for p in ra[:-1]]
            pb = [p[0] * nx + p[1] * ny for p in rb[:-1]]
            if min(pa) >= max(pb) - 1e-6 or min(pb) >= max(pa) - 1e-6:
                return False
    return True


# ── emitters ────────────────────────────────────────────────────────────────────────────────

def fx(code, name, cx, cy, w, d, cat, rot=0.0, blocks_los=True, height_m=1.8):
    """A fixture footprint: position + dimensions + rotation (brief §3.2), plus the derived
    corner ring so the harness never has to redo the trig."""
    ring = ring_rot(cx, cy, w, d, rot)
    return {"code": code, "name": name, "zone_type": "fixture", "fixture_category": cat,
            "geometry_type": "polygon",
            "center": {"x": r3(cx), "y": r3(cy)},
            "dims": {"w": r3(w), "d": r3(d)},
            "rotation_deg": r3(rot),
            "height_m": r3(height_m),
            "blocks_line_of_sight": blocks_los,
            "ring": ring,
            "area_sqm": r3(abs(poly_area(ring)))}


def zone(code, name, ring, ztype="region", accessible=True, **extra):
    z = {"code": code, "name": name, "zone_type": ztype, "geometry_type": "polygon",
         "ring": ring, "area_sqm": r3(abs(poly_area(ring))), "customer_accessible": accessible}
    z.update(extra)
    return z


def opening(code, name, p1, p2, connects, kind="doorway"):
    """A doorway/opening as a LINE SEGMENT across a wall gap.

    §3.3 asks which representation twin used, so it is explicit: this is NOT `space_connection`
    (dormant on mother, never populated) and NOT a zone row. It is twin's `crossing_slices`
    grammar — the same shape build_layout.py:378 uses for the EAS gate — generalised off the
    axis-aligned (y, x_start, x_end) form to a free segment. Rooms are `region` zones inside ONE
    space, so these connect zones, not spaces. The wall each doorway pierces is emitted as a
    real LOS-blocking fixture pair, so the gap is geometric, not just declared.
    """
    w = math.hypot(p2[0] - p1[0], p2[1] - p1[1])
    return {"code": code, "name": name, "kind": kind,
            "segment": [[r3(p1[0]), r3(p1[1])], [r3(p2[0]), r3(p2[1])]],
            "width_m": r3(w), "connects": connects}


def wall_with_gap(prefix, x1, y1, x2, y2, gap_at, gap_w, thickness=0.2):
    """A straight interior wall broken by one doorway gap. Returns (fixtures, opening_segment).
    Horizontal if y1==y2, vertical if x1==x2. `gap_at` is the centre of the gap along the run."""
    out = []
    if abs(y2 - y1) < 1e-9:                                    # horizontal wall
        a1, a2 = min(x1, x2), max(x1, x2)
        g1, g2 = gap_at - gap_w / 2.0, gap_at + gap_w / 2.0
        segs = [(a1, g1), (g2, a2)]
        for i, (s, e) in enumerate(segs, 1):
            if e - s <= 1e-6:
                continue
            out.append(fx(f"{prefix}-{i}", f"Partition {prefix} seg {i}",
                          (s + e) / 2.0, y1, e - s, thickness, "wall_partition", height_m=2.6))
        seg = ((g1, y1), (g2, y1))
    else:                                                      # vertical wall
        a1, a2 = min(y1, y2), max(y1, y2)
        g1, g2 = gap_at - gap_w / 2.0, gap_at + gap_w / 2.0
        segs = [(a1, g1), (g2, a2)]
        for i, (s, e) in enumerate(segs, 1):
            if e - s <= 1e-6:
                continue
            out.append(fx(f"{prefix}-{i}", f"Partition {prefix} seg {i}",
                          x1, (s + e) / 2.0, thickness, e - s, "wall_partition", height_m=2.6))
        seg = ((x1, g1), (x1, g2))
    return out, seg


# ── archetype builders ──────────────────────────────────────────────────────────────────────
# Each returns (boundary_ring, zones, fixtures, openings, notes). All work in the space's own
# SRF: SW origin (0,0), +x east, +y north, metres.

def build_grocery(rng, w, d, n_aisles):
    """Parallel aisle grid: double-sided gondola runs, end-cap cross-corridors, entrance plaza."""
    b = ring_rect(0, 0, w, d)
    plaza_d = 4.0                      # entrance plaza, south
    endcap = 2.6                       # cross-corridor at each end of the run
    run_y1, run_y2 = plaza_d, d - endcap
    run_len = run_y2 - run_y1
    gond_d = 1.2                       # double-sided gondola depth
    # solve aisle width from what is actually left over, then assert it landed in the §2 band
    aisle = (w - 2 * endcap - n_aisles * gond_d) / (n_aisles + 1)
    zones = [zone("Z-01", "Entrance Plaza", ring_rect(0, 0, w, plaza_d), "entry_exit"),
             zone("Z-02", "North Cross-Corridor", ring_rect(0, d - endcap, w, d), "path"),
             zone("Z-03", "Grocery Aisle Grid", ring_rect(0, plaza_d, w, d - endcap), "region")]
    fixtures = []
    units = max(3, int(run_len // 3.0))
    ulen = run_len / units
    for a in range(n_aisles):
        cx = endcap + aisle * (a + 1) + gond_d * a + gond_d / 2.0
        for u in range(units):
            cy = run_y1 + ulen * u + ulen / 2.0
            fixtures.append(fx(f"AI-{a + 1:02d}-U{u + 1}", f"Aisle {a + 1} Gondola U{u + 1}",
                               cx, cy, gond_d, ulen - 0.05, "gondola_run"))
    # end-caps: low promo units facing the cross-corridor
    for a in range(n_aisles):
        cx = endcap + aisle * (a + 1) + gond_d * a + gond_d / 2.0
        fixtures.append(fx(f"EC-{a + 1:02d}", f"End Cap {a + 1}", cx, run_y2 + 0.7,
                           gond_d, 1.0, "end_cap", height_m=1.2))
    # checkout bank along the plaza's east edge
    for i in range(3):
        fixtures.append(fx(f"CO-{i + 1:02d}", f"Checkout {i + 1}", w - 2.0, 0.9 + i * 1.2,
                           2.4, 0.7, "checkout_counter", height_m=1.0))
    return b, zones, fixtures, [], {"aisle_width_m": r3(aisle), "aisles": n_aisles,
                                    "run_length_m": r3(run_len)}


def build_factory(rng, w, d, n_rows):
    """Long LOS-blocking racking rows, marked walkways, wide staging/dock plaza."""
    b = ring_rect(0, 0, w, d)
    dock_d = 8.0                       # wide staging/dock plaza, south
    rack_d = 1.4
    aisle = (d - dock_d - n_rows * rack_d) / (n_rows + 1)
    zones = [zone("Z-01", "Dock & Staging Plaza", ring_rect(0, 0, w, dock_d), "region"),
             zone("Z-02", "Racking Hall", ring_rect(0, dock_d, w, d), "region")]
    fixtures = []
    run_x1, run_x2 = 1.5, w - 1.5
    bays = max(4, int((run_x2 - run_x1) // 2.7))
    blen = (run_x2 - run_x1) / bays
    for r in range(n_rows):
        cy = dock_d + aisle * (r + 1) + rack_d * r + rack_d / 2.0
        for bi in range(bays):
            cx = run_x1 + blen * bi + blen / 2.0
            fixtures.append(fx(f"RK-R{r + 1}-B{bi + 1:02d}", f"Racking Row {r + 1} Bay {bi + 1}",
                               cx, cy, blen - 0.08, rack_d, "pallet_rack", height_m=4.5))
    # marked walkways: painted paths, NOT obstacles — zone_type='path', no fixture
    for r in range(n_rows + 1):
        y = dock_d + aisle * r + rack_d * r + aisle / 2.0
        zones.append(zone(f"WK-{r + 1:02d}", f"Marked Walkway {r + 1}",
                          ring_rect(run_x1, y - 0.6, run_x2, y + 0.6), "path"))
    # dock doors + staging blocks in the plaza
    for i in range(4):
        fixtures.append(fx(f"DK-{i + 1:02d}", f"Dock Door {i + 1}", 4.0 + i * (w - 8.0) / 3.0, 0.4,
                           3.0, 0.4, "dock_door", height_m=4.0))
    for i in range(3):
        fixtures.append(fx(f"ST-{i + 1:02d}", f"Staging Block {i + 1}",
                           6.0 + i * 7.0, dock_d - 2.6, 4.0, 2.4, "staging_block", height_m=1.5))
    return b, zones, fixtures, [], {"aisle_width_m": r3(aisle), "racking_rows": n_rows,
                                    "bays_per_row": bays}


def build_showroom(rng, w, d, n_islands):
    """Irregular rotated islands, winding free path, deliberately NO straight aisle."""
    b = ring_rect(0, 0, w, d)
    zones = [zone("Z-01", "Showroom Floor", ring_rect(0, 0, w, d), "region"),
             zone("Z-02", "Entrance Vestibule", ring_rect(w / 2 - 2.5, 0, w / 2 + 2.5, 3.0), "entry_exit")]
    fixtures = []
    placed = []
    # rejection-sample rotated islands: random pose, keep only if it clears everything already
    # down by >=1.4m of walkable gap. Winding path is what's LEFT, never drawn.
    tries = 0
    i = 0
    while i < n_islands and tries < 60000:
        tries += 1
        iw = rng.uniform(1.6, 3.4)
        idp = rng.uniform(1.2, 2.6)
        cx = rng.uniform(2.2, w - 2.2)
        cy = rng.uniform(4.0, d - 2.2)
        rot = rng.choice([0, 15, 22.5, 30, 45, 60, 75])
        cand = fx(f"IS-{i + 1:02d}", f"Display Island {i + 1}", cx, cy, iw, idp, "display_island",
                  rot=rot, height_m=rng.choice([0.9, 1.2, 1.6]))
        pad = fx("_pad", "_pad", cx, cy, iw + 1.4, idp + 1.4, "_pad", rot=rot)
        if not all(p[0] >= 0 and p[1] >= 0 and p[0] <= w and p[1] <= d for p in pad["ring"]):
            continue
        if any(sat_overlap(pad["ring"], q["ring"]) for q in placed):
            continue
        fixtures.append(cand)
        placed.append(pad)
        i += 1
    return b, zones, fixtures, [], {"islands_placed": len(fixtures), "islands_requested": n_islands,
                                    "min_walkable_gap_m": 1.4, "straight_aisles": 0}


def build_plaza(rng, w, d):
    """>=60% open floor, sparse perimeter fixtures only."""
    b = ring_rect(0, 0, w, d)
    zones = [zone("Z-01", "Open Concourse", ring_rect(0, 0, w, d), "region"),
             zone("Z-02", "Entrance", ring_rect(w / 2 - 3.0, 0, w / 2 + 3.0, 2.5), "entry_exit")]
    fixtures = []
    n = 0
    for side in ("W", "E"):
        x = 0.6 if side == "W" else w - 0.6
        for k in range(3):
            n += 1
            fixtures.append(fx(f"PB-{n:02d}", f"Perimeter Bay {n}", x, 3.0 + k * (d - 6.0) / 2.0,
                               1.0, 2.2, "perimeter_bay"))
    fixtures.append(fx("KI-01", "Info Kiosk", w / 2.0, d - 3.0, 1.6, 1.6, "kiosk", rot=45, height_m=1.4))
    return b, zones, fixtures, [], {}


def build_multiroom(rng, w, d, n_rooms):
    """3–5 rooms off a corridor spine, connected by real doorways (door-pair sites)."""
    b = ring_rect(0, 0, w, d)
    corr_d = 2.2                       # corridor spine runs W→E along the south
    zones = [zone("Z-00", "Corridor Spine", ring_rect(0, 0, w, corr_d), "path")]
    fixtures = []
    openings = []
    rw = w / n_rooms
    for r in range(n_rooms):
        x1, x2 = r * rw, (r + 1) * rw
        zones.append(zone(f"R-{r + 1:02d}", f"Room {r + 1}",
                          ring_rect(x1 + 0.1, corr_d, x2 - 0.1, d), "region"))
        # wall between the corridor and this room, pierced by one doorway
        wf, seg = wall_with_gap(f"WS-{r + 1:02d}", x1, corr_d, x2, corr_d,
                                gap_at=x1 + rw * 0.5, gap_w=1.1)
        fixtures += wf
        openings.append(opening(f"DR-{r + 1:02d}", f"Doorway Corridor→Room {r + 1}",
                                seg[0], seg[1], ["Z-00", f"R-{r + 1:02d}"]))
        # dividing wall to the next room (solid, full depth) — makes each room a real enclosure.
        # Starts at corr_d+0.1 so it ABUTS the corridor wall's north face instead of crossing it:
        # a T-junction of two 0.2m walls is one wall meeting another, not two solids interpenetrating.
        if r < n_rooms - 1:
            dy1 = corr_d + 0.1
            fixtures.append(fx(f"WD-{r + 1:02d}", f"Room Divider {r + 1}/{r + 2}",
                               x2, dy1 + (d - dy1) / 2.0, 0.2, d - dy1,
                               "wall_partition", height_m=2.6))
        # one furnishing per room so it is not an empty box
        fixtures.append(fx(f"FN-{r + 1:02d}", f"Room {r + 1} Bench", (x1 + x2) / 2.0, d - 1.4,
                           min(2.4, rw - 1.2), 0.7, "bench", height_m=0.5))
    return b, zones, fixtures, openings, {"rooms": n_rooms, "doorway_width_m": 1.1}


# ── pathologies ─────────────────────────────────────────────────────────────────────────────

def build_path_narrow(rng):
    """(a) aisles BELOW spacing feasibility — 1.1 m, under the 1.5 m floor, deliberately."""
    w, d = 18.0, 14.0
    b = ring_rect(0, 0, w, d)
    gond_d, aisle, n = 1.2, 1.1, 7
    span = n * gond_d + (n + 1) * aisle
    x0 = (w - span) / 2.0
    zones = [zone("Z-01", "Sub-Feasible Aisle Grid", ring_rect(0, 0, w, d), "region")]
    fixtures = []
    for a in range(n):
        cx = x0 + aisle * (a + 1) + gond_d * a + gond_d / 2.0
        fixtures.append(fx(f"NA-{a + 1:02d}", f"Narrow Run {a + 1}", cx, d / 2.0,
                           gond_d, d - 3.0, "gondola_run"))
    return b, zones, fixtures, [], {"aisle_width_m": aisle, "below_feasibility_floor_m": 1.5,
                                    "intent": "solver must refuse or degrade, not silently emit"}


def build_path_deadend(rng):
    """(b) dead-end corridors — three stubs off a spine, each terminating in a wall."""
    w, d = 22.0, 16.0
    b = ring_rect(0, 0, w, d)
    corr = 2.0
    zones = [zone("Z-00", "Spine Corridor", ring_rect(0, 0, w, corr), "path")]
    fixtures = []
    stub_w = 2.0
    for i, sx in enumerate((4.0, 10.5, 17.0), start=1):
        depth = (10.0, 6.5, 12.0)[i - 1]
        zones.append(zone(f"DE-{i:02d}", f"Dead-End Stub {i}",
                          ring_rect(sx, corr, sx + stub_w, corr + depth), "path"))
        # two side walls + a terminating end wall = a true dead end
        fixtures.append(fx(f"DW-{i:02d}-L", f"Stub {i} West Wall", sx - 0.1, corr + depth / 2.0,
                           0.2, depth, "wall_partition", height_m=2.6))
        fixtures.append(fx(f"DW-{i:02d}-R", f"Stub {i} East Wall", sx + stub_w + 0.1,
                           corr + depth / 2.0, 0.2, depth, "wall_partition", height_m=2.6))
        fixtures.append(fx(f"DW-{i:02d}-E", f"Stub {i} End Wall", sx + stub_w / 2.0,
                           corr + depth + 0.1, stub_w + 0.2, 0.2, "wall_partition", height_m=2.6))
    return b, zones, fixtures, [], {"dead_ends": 3, "stub_width_m": stub_w,
                                    "intent": "no through-route; corridor solver must not loop"}


def build_path_bigplaza(rng):
    """(c) one oversized plaza ~30×30 m, essentially featureless."""
    w = d = 30.0
    b = ring_rect(0, 0, w, d)
    zones = [zone("Z-01", "Oversized Plaza", ring_rect(0, 0, w, d), "region")]
    fixtures = [fx("CL-01", "Central Column", w / 2.0, d / 2.0, 0.6, 0.6, "column", height_m=4.0),
                fx("BN-01", "Bench North", w / 2.0, d - 2.0, 3.0, 0.6, "bench", height_m=0.5),
                fx("BN-02", "Bench South", w / 2.0, 2.0, 3.0, 0.6, "bench", height_m=0.5)]
    return b, zones, fixtures, [], {"open_ratio": 0.995,
                                    "intent": "plaza template must scale, not tile to exhaustion"}


def build_path_nonconvex(rng):
    """(d) non-convex L-shaped boundary — the case build_layout.py structurally cannot emit."""
    # L: 24×18 with the NE 12×9 quadrant removed
    b = [[0.0, 0.0], [24.0, 0.0], [24.0, 9.0], [12.0, 9.0], [12.0, 18.0], [0.0, 18.0], [0.0, 0.0]]
    zones = [zone("Z-01", "L-Wing South", ring_rect(0, 0, 24, 9), "region"),
             zone("Z-02", "L-Wing North", ring_rect(0, 9, 12, 18), "region")]
    fixtures = []
    for i in range(4):                                        # south wing racks
        fixtures.append(fx(f"LS-{i + 1:02d}", f"South Wing Rack {i + 1}",
                           3.0 + i * 5.5, 4.5, 1.2, 5.0, "gondola_run"))
    for i in range(3):                                        # north wing racks
        fixtures.append(fx(f"LN-{i + 1:02d}", f"North Wing Rack {i + 1}",
                           2.5 + i * 3.6, 13.5, 1.2, 5.0, "gondola_run"))
    # A column ON the reflex corner: the concave vertex is the interesting part of this space,
    # so give the solver a real obstacle sitting in it rather than an empty notch.
    fixtures.append(fx("LC-01", "Inside-Corner Column", 12.8, 8.4, 0.6, 0.6,
                       "column", height_m=4.0))
    fixtures.append(fx("LI-01", "South Wing Island", 16.9, 7.6, 1.6, 1.6,
                       "display_island", rot=30, height_m=1.2))
    return b, zones, fixtures, [], {"reflex_vertices": 1,
                                    "intent": "concave corner at (12,9); no single AABB covers this"}


# ── assembly + validation ───────────────────────────────────────────────────────────────────

SPECS = [
    # Grocery widths are solved BACKWARDS from the §2 aisle band (1.8–2.5 m), not chosen: with
    # 8 runs of 1.2 m and 2.6 m end-caps, 28 m yielded 1.47 m aisles — inside the footprint band
    # but outside the aisle band. 32 m / 27 m put both aisles in spec and the area still in band.
    ("SOLVER-GROCERY-01",   "grocery_aisle_grid",  "sales_floor", lambda r: build_grocery(r, 32.0, 23.0, 8)),
    ("SOLVER-GROCERY-02",   "grocery_aisle_grid",  "sales_floor", lambda r: build_grocery(r, 27.0, 19.0, 6)),
    ("SOLVER-FACTORY-01",   "factory_racking",     "warehouse",   lambda r: build_factory(r, 50.0, 32.0, 7)),
    ("SOLVER-FACTORY-02",   "factory_racking",     "warehouse",   lambda r: build_factory(r, 40.0, 28.0, 5)),
    ("SOLVER-SHOWROOM-01",  "showroom_islands",    "showroom",    lambda r: build_showroom(r, 24.0, 20.0, 14)),
    ("SOLVER-SHOWROOM-02",  "showroom_islands",    "showroom",    lambda r: build_showroom(r, 20.0, 16.0, 10)),
    ("SOLVER-PLAZA-01",     "plaza_open",          "concourse",   lambda r: build_plaza(r, 20.0, 16.0)),
    ("SOLVER-PLAZA-02",     "plaza_open",          "concourse",   lambda r: build_plaza(r, 16.0, 14.0)),
    ("SOLVER-MULTIROOM-01", "multi_room",          "back_office", lambda r: build_multiroom(r, 20.0, 13.0, 4)),
    ("SOLVER-MULTIROOM-02", "multi_room",          "back_office", lambda r: build_multiroom(r, 16.0, 11.0, 3)),
    ("SOLVER-NARROW-01",    "pathology_narrow_aisle",  "sales_floor", build_path_narrow),
    ("SOLVER-DEADEND-01",   "pathology_dead_end",      "back_office", build_path_deadend),
    ("SOLVER-BIGPLAZA-01",  "pathology_oversized_plaza", "concourse", build_path_bigplaza),
    ("SOLVER-NONCONVEX-01", "pathology_non_convex",    "sales_floor", build_path_nonconvex),
]


def build_space(name, archetype, space_type, fn):
    rng = random.Random(seed_of(name))
    boundary, zones, fixtures, openings, notes = fn(rng)

    # ── correct-by-construction gates. A pathology is a deliberate GEOMETRY, never a broken
    # file: sub-feasible aisles still must not overlap, and a dead end still must be in bounds.
    problems = []
    for i in range(len(fixtures)):
        for j in range(i + 1, len(fixtures)):
            if sat_overlap(fixtures[i]["ring"], fixtures[j]["ring"]):
                problems.append(f"OVERLAP {fixtures[i]['code']}×{fixtures[j]['code']}")
    for f in fixtures:
        for p in f["ring"][:-1]:
            if not point_in_ring(p, boundary):
                problems.append(f"OUT-OF-BOUNDS {f['code']} at {p}")
                break
    for z in zones:
        for p in z["ring"][:-1]:
            if not point_in_ring(p, boundary):
                problems.append(f"ZONE-OUT-OF-BOUNDS {z['code']} at {p}")
                break
    if poly_area(boundary) <= 0:
        problems.append("BOUNDARY not CCW")
    if problems:
        raise AssertionError(f"{name}: " + "; ".join(problems[:6]))

    area = abs(poly_area(boundary))
    fixture_area = sum(f["area_sqm"] for f in fixtures)
    return {
        "name": name,
        "archetype": archetype,
        "space_type": space_type,
        "space_type_status": "existing" if space_type in SPACE_TYPES_EXISTING else "NEW-provisional",
        "seed": seed_of(name),
        "boundary": {"ring": boundary, "convex": is_convex(boundary),
                     "vertices": len(boundary) - 1, "bbox_m": bbox_of(boundary)},
        "area_sqm": r3(area),
        "open_floor_ratio": r3(1.0 - fixture_area / area),
        "counts": {"zones": len(zones), "fixtures": len(fixtures), "openings": len(openings)},
        "zones": zones,
        "fixtures": fixtures,
        "openings": openings,
        "archetype_notes": notes,
    }


def main():
    spaces = [build_space(n, a, st, fn) for n, a, st, fn in SPECS]
    doc = {
        "schema": "m8trx-twin/solver-matrix/v1",
        "generated_by": "scripts/build_solver_matrix.py",
        "brief": "m8trx-shared/status/briefs/BRIEF-TWIN-SOLVER-MATRIX-GENERATOR-2026-08-05.md",
        "purpose": "Schema-true test-space matrix for core's anchor-pattern solver. READ-ONLY input.",
        "coordinate_units": "meters",
        "coordinate_frame": ("per-space SRF; SW origin (0,0); +x east, +y north; Z=0 implied. "
                             "No site assembly — spaces are NOT positioned relative to each other "
                             "(srf_to_site_transform is Pass-2 and dormant on mother)."),
        "geometry_encoding": {
            "boundary": "closed CCW ring [[x,y],...]; first point repeated last; may be non-convex",
            "fixture": "center{x,y} + dims{w,d} + rotation_deg (CCW about center); `ring` is the derived closed corner ring",
            "zone": "closed CCW ring; zones are areas INSIDE the space, not sub-spaces",
            "opening": "line SEGMENT [[x1,y1],[x2,y2]] across a wall gap",
        },
        "doorway_representation": (
            "openings[] — a line segment, twin's `crossing_slices` grammar (build_layout.py:378, the "
            "EAS-gate form) generalised from axis-aligned (y,x_start,x_end) to a free segment. "
            "NOT `space_connection` (exists in schema, dormant/never populated on mother) and NOT a "
            "zone row. Rooms are `region` zones within ONE space, so an opening connects ZONE codes. "
            "Every doorway pierces a real LOS-blocking `wall_partition` fixture pair, so the gap is "
            "geometric, not merely declared."
        ),
        "space_type_caveat": (
            "`space_type` has no CREATE TYPE/CHECK in 7a — unratified free text "
            "(SPATIAL-HIERARCHY.md:48). `sales_floor`/`stockroom`/`fitting_room` are live on mother; "
            "`warehouse`/`showroom`/`concourse`/`back_office` are NEW tokens minted here for FR-37 "
            "regime variety and flagged per-space via space_type_status."
        ),
        "no_anchors": "Deliberately absent — the solver proposes anchors; that is the test (brief §3).",
        "site": {"name": SITE_NAME, "slug": SITE_SLUG, "quarantine": True,
                 "edge_attached": False, "sensors": [],
                 "note": "NOT created by twin — no sanctioned spatial write path exists. Core ingests."},
        "totals": {
            "spaces": len(spaces),
            "zones": sum(s["counts"]["zones"] for s in spaces),
            "fixtures": sum(s["counts"]["fixtures"] for s in spaces),
            "openings": sum(s["counts"]["openings"] for s in spaces),
            "floor_area_sqm": r3(sum(s["area_sqm"] for s in spaces)),
        },
        "spaces": spaces,
    }
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "solver-matrix.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=2, ensure_ascii=False)
        fh.write("\n")

    print(f"wrote {path}")
    print(f"{'space':22} {'archetype':26} {'m²':>8} {'zn':>3} {'fx':>4} {'op':>3} {'open%':>6}  notes")
    for s in spaces:
        n = s["archetype_notes"]
        extra = ", ".join(f"{k}={v}" for k, v in n.items() if k != "intent")
        print(f"{s['name']:22} {s['archetype']:26} {s['area_sqm']:8.1f} "
              f"{s['counts']['zones']:3d} {s['counts']['fixtures']:4d} {s['counts']['openings']:3d} "
              f"{s['open_floor_ratio'] * 100:5.1f}%  {extra}")
    t = doc["totals"]
    print(f"\nTOTAL {t['spaces']} spaces · {t['zones']} zones · {t['fixtures']} fixtures · "
          f"{t['openings']} openings · {t['floor_area_sqm']:.0f} m²")


if __name__ == "__main__":
    main()
