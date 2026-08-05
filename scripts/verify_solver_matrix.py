#!/usr/bin/env python3
"""
Independently verify reference/data/solver/solver-matrix.json against the brief's §2/§3 contract.

Deliberately does NOT import build_solver_matrix.py. The generator asserting its own output is
worth little — every check here re-derives geometry from the emitted file, the way core's harness
will. In particular it re-computes each fixture ring from center+dims+rotation_deg and compares
it to the emitted `ring`, because a harness that trusts one and renders the other is the silent
failure this file exists to catch.

Also renders one SVG per space so the matrix can be eyeballed, and re-runs the generator to prove
byte-identical determinism.

Usage: python3 scripts/verify_solver_matrix.py
"""
import hashlib
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "reference/data/solver/solver-matrix.json")
SVG_DIR = os.path.join(ROOT, "reference/data/solver/svg")

# §2 contract: archetype → (count, area_min, area_max)
SPEC_BANDS = {
    "grocery_aisle_grid": (2, 400, 800),
    "factory_racking": (2, 1000, 2000),
    "showroom_islands": (2, 300, 600),
    "plaza_open": (2, 200, 400),
    "multi_room": (2, 150, 300),
}
PATHOLOGIES = {"pathology_narrow_aisle", "pathology_dead_end",
               "pathology_oversized_plaza", "pathology_non_convex"}

fails, warns = [], []


def bad(space, msg):
    fails.append(f"{space}: {msg}")


def warn(space, msg):
    warns.append(f"{space}: {msg}")


def area(ring):
    v = ring[:-1]
    return sum(v[i][0] * v[(i + 1) % len(v)][1] - v[(i + 1) % len(v)][0] * v[i][1]
               for i in range(len(v))) / 2.0


def point_in_ring(p, ring):
    x, y = p
    v = ring[:-1]
    n = len(v)
    for i in range(n):
        ax, ay = v[i]
        bx, by = v[(i + 1) % n]
        cr = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
        if abs(cr) < 1e-6 and min(ax, bx) - 1e-6 <= x <= max(ax, bx) + 1e-6 \
                and min(ay, by) - 1e-6 <= y <= max(ay, by) + 1e-6:
            return True
    inside = False
    for i in range(n):
        ax, ay = v[i]
        bx, by = v[(i + 1) % n]
        if (ay > y) != (by > y):
            if x < (bx - ax) * (y - ay) / (by - ay) + ax:
                inside = not inside
    return inside


def sat_overlap(ra, rb):
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


def check_space(s):
    n = s["name"]
    b = s["boundary"]["ring"]

    # ── rings well-formed
    for label, ring in ([("boundary", b)]
                        + [(f"zone {z['code']}", z["ring"]) for z in s["zones"]]
                        + [(f"fixture {f['code']}", f["ring"]) for f in s["fixtures"]]):
        if len(ring) < 4:
            bad(n, f"{label}: ring has {len(ring)} points, need >=4 closed")
        if ring[0] != ring[-1]:
            bad(n, f"{label}: ring not closed ({ring[0]} != {ring[-1]})")
        if area(ring) <= 0:
            bad(n, f"{label}: ring not CCW (signed area {area(ring):.4f})")

    # ── fixture ring must AGREE with center+dims+rotation (the harness may read either)
    for f in s["fixtures"]:
        cx, cy = f["center"]["x"], f["center"]["y"]
        w, d, deg = f["dims"]["w"], f["dims"]["d"], f["rotation_deg"]
        t = math.radians(deg)
        ct, st = math.cos(t), math.sin(t)
        exp = []
        for dx, dy in ((-w / 2, -d / 2), (w / 2, -d / 2), (w / 2, d / 2), (-w / 2, d / 2)):
            exp.append((cx + dx * ct - dy * st, cy + dx * st + dy * ct))
        for i, (ex, ey) in enumerate(exp):
            gx, gy = f["ring"][i]
            if abs(ex - gx) > 2e-3 or abs(ey - gy) > 2e-3:
                bad(n, f"fixture {f['code']}: ring[{i}] {[gx, gy]} != center+dims+rot {[round(ex, 3), round(ey, 3)]}")
                break
        if w <= 0 or d <= 0:
            bad(n, f"fixture {f['code']}: non-positive dims {w}×{d}")

    # ── containment + overlap, recomputed
    for f in s["fixtures"]:
        for p in f["ring"][:-1]:
            if not point_in_ring(p, b):
                bad(n, f"fixture {f['code']} vertex {p} outside boundary")
                break
    for z in s["zones"]:
        for p in z["ring"][:-1]:
            if not point_in_ring(p, b):
                bad(n, f"zone {z['code']} vertex {p} outside boundary")
                break
    fxs = s["fixtures"]
    for i in range(len(fxs)):
        for j in range(i + 1, len(fxs)):
            if sat_overlap(fxs[i]["ring"], fxs[j]["ring"]):
                bad(n, f"fixtures overlap: {fxs[i]['code']} × {fxs[j]['code']}")

    # ── doorways: a doorway must be a REAL gap — inside the space, clear of every fixture,
    # and wide enough to walk through. A declared opening sitting inside a wall is the defect.
    for o in s["openings"]:
        (x1, y1), (x2, y2) = o["segment"]
        mid = ((x1 + x2) / 2.0, (y1 + y2) / 2.0)
        if not point_in_ring(mid, b):
            bad(n, f"opening {o['code']}: midpoint {mid} outside boundary")
        for f in s["fixtures"]:
            if point_in_ring(mid, f["ring"]):
                bad(n, f"opening {o['code']} midpoint lies inside fixture {f['code']} — not a gap")
        if o["width_m"] < 0.8:
            bad(n, f"opening {o['code']}: {o['width_m']}m is not walkable")
        for ref in o["connects"]:
            if ref not in [z["code"] for z in s["zones"]]:
                bad(n, f"opening {o['code']}: connects unknown zone '{ref}'")

    # ── declared counts must match reality
    if s["counts"]["fixtures"] != len(s["fixtures"]):
        bad(n, "counts.fixtures disagrees with fixtures[]")
    if s["counts"]["zones"] != len(s["zones"]):
        bad(n, "counts.zones disagrees with zones[]")
    if s["counts"]["openings"] != len(s["openings"]):
        bad(n, "counts.openings disagrees with openings[]")
    if abs(abs(area(b)) - s["area_sqm"]) > 0.05:
        bad(n, f"area_sqm {s['area_sqm']} != boundary area {abs(area(b)):.2f}")

    # ── §3.4 space_type present and honestly labelled
    if not s.get("space_type"):
        bad(n, "space_type missing (§3.4)")
    if s["space_type_status"] not in ("existing", "NEW-provisional"):
        bad(n, "space_type_status not declared")

    # ── §3: no anchors anywhere — the solver proposes those
    blob = json.dumps(s).lower()
    if '"anchor' in blob or "anchor_id" in blob:
        bad(n, "contains an anchor — §3 forbids it")

    # ── archetype-specific §2 obligations
    a = s["archetype"]
    nt = s["archetype_notes"]
    if a == "grocery_aisle_grid":
        if not 1.8 <= nt["aisle_width_m"] <= 2.5:
            bad(n, f"aisle {nt['aisle_width_m']}m outside §2 band 1.8–2.5")
        if not 6 <= nt["aisles"] <= 10:
            bad(n, f"{nt['aisles']} aisles outside §2 band 6–10")
        if not any(z["zone_type"] == "entry_exit" for z in s["zones"]):
            bad(n, "§2 requires an entrance plaza")
        if not any(f["fixture_category"] == "end_cap" for f in s["fixtures"]):
            bad(n, "§2 requires end-caps")
    if a == "factory_racking":
        if not any(z["zone_type"] == "path" for z in s["zones"]):
            bad(n, "§2 requires marked walkways")
        if not any(f["blocks_line_of_sight"] and f["height_m"] >= 3.0 for f in s["fixtures"]):
            bad(n, "§2 requires LOS-blocking racking")
    if a == "showroom_islands":
        rots = {f["rotation_deg"] for f in s["fixtures"] if f["fixture_category"] == "display_island"}
        if rots <= {0.0}:
            bad(n, "§2 requires irregular (rotated) islands — all islands are axis-aligned")
    if a == "plaza_open" and s["open_floor_ratio"] < 0.60:
        bad(n, f"open floor {s['open_floor_ratio']:.0%} < §2 minimum 60%")
    if a == "multi_room":
        rooms = [z for z in s["zones"] if z["code"].startswith("R-")]
        if not 3 <= len(rooms) <= 5:
            bad(n, f"{len(rooms)} rooms outside §2 band 3–5")
        if len(s["openings"]) < len(rooms):
            bad(n, "every room needs a doorway (§2 door-pair sites)")
    if a == "pathology_narrow_aisle" and nt["aisle_width_m"] >= 1.5:
        bad(n, "pathology (a) must be BELOW 1.5m")
    if a == "pathology_oversized_plaza":
        bb = s["boundary"]["bbox_m"]
        if bb["x2"] - bb["x1"] < 28 or bb["y2"] - bb["y1"] < 28:
            bad(n, "pathology (c) must be ~30×30m")
    if a == "pathology_non_convex" and s["boundary"]["convex"]:
        bad(n, "pathology (d) must be non-convex")


def render_svg(s, path, px=1100):
    bb = s["boundary"]["bbox_m"]
    w, h = bb["x2"] - bb["x1"], bb["y2"] - bb["y1"]
    sc = px / max(w, h)
    pad = 24
    W, H = w * sc + 2 * pad, h * sc + 2 * pad

    def T(p):  # SRF (+y north) → SVG (+y down)
        return (pad + (p[0] - bb["x1"]) * sc, pad + (bb["y2"] - p[1]) * sc)

    def d(ring):
        return "M " + " L ".join(f"{T(p)[0]:.1f},{T(p)[1]:.1f}" for p in ring) + " Z"

    COL = {"wall_partition": "#4a4a4a", "pallet_rack": "#8a5a2b", "gondola_run": "#2f6f9f",
           "display_island": "#8e44ad", "end_cap": "#3fa7d6", "checkout_counter": "#c0392b",
           "column": "#222", "bench": "#7f8c8d", "kiosk": "#16a085", "dock_door": "#d35400",
           "staging_block": "#e08e0b", "perimeter_bay": "#2980b9"}
    ZCOL = {"path": "#fff2cc", "entry_exit": "#d9ead3", "region": "#f4f4f4"}
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W:.0f}" height="{H:.0f}" '
           f'viewBox="0 0 {W:.0f} {H:.0f}">',
           f'<rect width="{W:.0f}" height="{H:.0f}" fill="#fff"/>']
    for z in s["zones"]:
        out.append(f'<path d="{d(z["ring"])}" fill="{ZCOL.get(z["zone_type"], "#f4f4f4")}" '
                   f'stroke="#ccc" stroke-width="1"/>')
    out.append(f'<path d="{d(s["boundary"]["ring"])}" fill="none" stroke="#111" stroke-width="3"/>')
    for f in s["fixtures"]:
        c = COL.get(f["fixture_category"], "#999")
        out.append(f'<path d="{d(f["ring"])}" fill="{c}" fill-opacity="0.75" stroke="{c}" stroke-width="1"/>')
    for o in s["openings"]:
        (p1, p2) = o["segment"]
        a, b2 = T(p1), T(p2)
        out.append(f'<line x1="{a[0]:.1f}" y1="{a[1]:.1f}" x2="{b2[0]:.1f}" y2="{b2[1]:.1f}" '
                   f'stroke="#e11" stroke-width="5" stroke-linecap="round"/>')
    out.append(f'<text x="{pad}" y="{pad - 8}" font-family="monospace" font-size="15" fill="#111">'
               f'{s["name"]} · {s["archetype"]} · {s["area_sqm"]:.0f} m² · '
               f'{s["counts"]["fixtures"]} fixtures · {s["counts"]["openings"]} openings</text>')
    out.append("</svg>")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(out))


def main():
    with open(SRC, encoding="utf-8") as fh:
        raw = fh.read()
    doc = json.loads(raw)

    if doc["coordinate_units"] != "meters":
        fails.append("TOP: §3 requires SRF meters")
    for s in doc["spaces"]:
        if not s["name"].startswith("SOLVER-"):
            bad(s["name"], "§1.2 naming convention violated")
        check_space(s)

    # ── §2 matrix conformance
    by_arch = {}
    for s in doc["spaces"]:
        by_arch.setdefault(s["archetype"], []).append(s)
    for arch, (cnt, amin, amax) in SPEC_BANDS.items():
        got = by_arch.get(arch, [])
        if len(got) != cnt:
            fails.append(f"§2: {arch} expected {cnt} spaces, got {len(got)}")
        for s in got:
            if not amin <= s["area_sqm"] <= amax:
                bad(s["name"], f"area {s['area_sqm']} m² outside §2 band {amin}–{amax}")
    missing = PATHOLOGIES - set(by_arch)
    if missing:
        fails.append(f"§2: missing pathologies {sorted(missing)}")
    if len(doc["spaces"]) != 14:
        fails.append(f"§2: expected ~14 spaces, got {len(doc['spaces'])}")

    # ── §1.3 no edge attachment
    if doc["site"].get("edge_attached") is not False or doc["site"].get("sensors"):
        fails.append("§1.3: site must not be edge/sensor attached")

    # ── determinism: regenerate into a temp tree and byte-compare
    det = "SKIPPED"
    try:
        tmp = tempfile.mkdtemp()
        shutil.copy(SRC, os.path.join(tmp, "before.json"))
        subprocess.run([sys.executable, os.path.join(ROOT, "scripts/build_solver_matrix.py")],
                       check=True, capture_output=True)
        with open(SRC, encoding="utf-8") as fh:
            after = fh.read()
        det = "IDENTICAL" if after == raw else "DIVERGED"
        if det == "DIVERGED":
            fails.append("determinism: regeneration produced different bytes")
    except Exception as e:                                       # noqa: BLE001
        det = f"ERROR {e}"
        fails.append(f"determinism check failed: {e}")

    os.makedirs(SVG_DIR, exist_ok=True)
    for s in doc["spaces"]:
        render_svg(s, os.path.join(SVG_DIR, f"{s['name']}.svg"))

    t = doc["totals"]
    print(f"solver-matrix.json  sha256={hashlib.sha256(raw.encode()).hexdigest()[:16]}  "
          f"{len(raw):,} bytes")
    print(f"{t['spaces']} spaces · {t['zones']} zones · {t['fixtures']} fixtures · "
          f"{t['openings']} openings · {t['floor_area_sqm']:.0f} m²")
    print(f"determinism: {det}   SVGs: {SVG_DIR}")
    print(f"checks: {len(doc['spaces'])} spaces verified geometrically + against §2/§3")
    if warns:
        print("\nWARN")
        for w in warns:
            print("  ·", w)
    if fails:
        print(f"\n❌ {len(fails)} FAILURES")
        for f in fails:
            print("  ·", f)
        sys.exit(1)
    print("\n✅ ALL CHECKS PASS")


if __name__ == "__main__":
    main()
