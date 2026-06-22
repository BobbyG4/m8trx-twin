#!/usr/bin/env python3
"""
Generate PER-STORE store layouts — 10 unique floors (parametric, deterministic, overlap-free).

Step 1 of the realism redesign (2026-06-22). Replaces the single shared 600 sqm template: each
retail store now gets its OWN floor, sized to its tier and varied off a stable sha256(store_id)
seed — different footprint, gondola grid (rows/units/aisle), specialty mix, perimeter, checkout
side. Macro grammar is shared (entrance south, checkout + service + fitting east band, specialty
cluster north, gondola hall center, stockroom rear, non-retail west); the QUANTITIES vary.

Geometry is correct-by-construction: every fixture is placed by an even-gap grid filler inside a
non-overlapping zone rect, and each store's layout is asserted to have 0 fixture overlaps and 0
out-of-bounds before writing. Fixtures ARE zones (zone_type='fixture'); mm, SW origin (0,0),
rect -> POLYGON Z SRID 0. Office sites get no layout.

Out: reference/data/chain/stores/<retail-id>/layout.json  (one per retail store)
Consumed by build_chain.py (layout-driven planogram) for EPC placement.
"""
import json, os, hashlib, random, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from chain_config import STORES
import sport_universe as su          # brand→sport-universe departments

# our store tiers (flagship/large/medium) → Decathlon format buckets the universe model speaks
TIER_BUCKET = {"flagship": "flagship", "large": "medium", "medium": "city"}
BOH_RACKS = {"flagship": 8, "large": 6, "medium": 4}   # backroom rack count by tier

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "reference/data/chain")

# ── per-tier base params (footprint + target gondola rows). sqm ≈ w*d/1e6 ──
# Tiers are spread WIDE so sizes read as clearly different (flagship ~675 / large ~485 /
# medium ~370 m²); large per-store jitter (varies width & depth independently → size + aspect).
TIER_LAYOUT = {
    "flagship": {"fp": (26000, 26000), "rows": 7},
    "large":    {"fp": (22000, 22000), "rows": 5},
    "medium":   {"fp": (19000, 19500), "rows": 4},
}
W_JITTER = {"flagship": [-2000, -1000, 0, 1500, 2500], "large": [-2000, -1000, 0, 1000, 1500],
            "medium": [-1500, -1000, 0, 1000, 1500]}
D_JITTER = {"flagship": [-2000, 0, 1500, 2500, 3500], "large": [-2000, -1000, 0, 1500, 2000],
            "medium": [-1500, -500, 0, 1000, 1500]}
GOND_DEPTH = 1200            # double-sided gondola: front 600 (south) + back 600 (north)
MIN_AISLE = 700
UNIT_W = 2000               # gondola unit width
WALL_D = 500                # perimeter wall depth
SOUTH = 3000                # entrance band depth
STOCK = 3000                # stockroom band depth (north)
WEST = 3000                 # non-retail west strip


def store_seed(sid):
    return int(hashlib.sha256(sid.encode()).hexdigest(), 16) % (2**31)


def asqm(x1, y1, x2, y2):
    return round((x2 - x1) * (y2 - y1) / 1_000_000, 1)


def geometry_for(x1, y1, x2, y2, shape="rect", rotation=0):
    """Mother-canonical zone geometry (verified against live `zone` rows, 2026-06-22):
      • polygon  → full closed `POLYGON Z` ring in `geometry`; `properties` = {}
      • circle/ellipse → center `POINT Z` in `geometry`; shape in `properties`
        (centerX/Y, radiusX/Y in mm, rotation in degrees). circle ⇔ radiusX==radiusY.
    SRID 0, millimetres, Z=0. The POINT center MUST equal properties.centerX/Y.
    Returns (geometry_type, geometry_wkt, properties, bbox)."""
    x1, y1, x2, y2 = int(x1), int(y1), int(x2), int(y2)
    if shape == "circle":
        cx, cy = round((x1 + x2) / 2), round((y1 + y2) / 2)
        r = round(min(x2 - x1, y2 - y1) / 2)                  # true circle inscribed in the footprint
        props = {"centerX": cx, "centerY": cy, "radiusX": r, "radiusY": r, "rotation": rotation}
        return "circle", f"POINT Z ({cx} {cy} 0)", props, (cx - r, cy - r, cx + r, cy + r)
    ring = f"({x1} {y1} 0, {x2} {y1} 0, {x2} {y2} 0, {x1} {y2} 0, {x1} {y1} 0)"
    return "polygon", f"POLYGON Z ({ring})", {}, (x1, y1, x2, y2)


def fixture(code, name, x1, y1, x2, y2, in_zone, cat, shape="rect", rotation=0):
    gtype, geom, props, (bx1, by1, bx2, by2) = geometry_for(x1, y1, x2, y2, shape, rotation)
    return {"code": code, "name": name, "zone_type": "fixture", "parent": "space",
            "in_area_zone": in_zone, "fixture_category": cat,
            "geometry_type": gtype, "geometry": geom, "properties": props,
            "area_sqm": asqm(bx1, by1, bx2, by2),
            "rect_mm": {"x1": bx1, "y1": by1, "x2": bx2, "y2": by2}}    # bbox (twin convenience)


def cells(x1, y1, x2, y2, ncols, nrows, cw, ch):
    """ncols×nrows cells of FIXED cw×ch, even gaps (incl. margins), inside the rect.
    Caller must ensure cw·ncols and ch·nrows fit (gaps go negative → overlap otherwise)."""
    gx = (x2 - x1 - ncols * cw) / (ncols + 1)
    gy = (y2 - y1 - nrows * ch) / (nrows + 1)
    out = []
    for r in range(nrows):
        for c in range(ncols):
            ax1 = x1 + gx * (c + 1) + cw * c
            ay1 = y1 + gy * (r + 1) + ch * r
            out.append((ax1, ay1, ax1 + cw, ay1 + ch, r, c))
    return out


def fill(x1, y1, x2, y2, ncols, nrows, gap=200):
    """ncols×nrows cells that FILL the rect (cell size derived from space). Always fits."""
    cw = (x2 - x1 - (ncols + 1) * gap) / ncols
    ch = (y2 - y1 - (nrows + 1) * gap) / nrows
    out = []
    for r in range(nrows):
        for c in range(ncols):
            ax1 = x1 + gap * (c + 1) + cw * c
            ay1 = y1 + gap * (r + 1) + ch * r
            out.append((ax1, ay1, ax1 + cw, ay1 + ch, r, c))
    return out


def _overlap(a, b):
    a, b = a["rect_mm"], b["rect_mm"]
    return (max(0, min(a["x2"], b["x2"]) - max(a["x1"], b["x1"]))
            * max(0, min(a["y2"], b["y2"]) - max(a["y1"], b["y1"])))


def generate(store):
    sid, tier = store["id"], store["tier"]
    rng = random.Random(store_seed(sid))
    base = TIER_LAYOUT[tier]
    W = base["fp"][0] + rng.choice(W_JITTER[tier])
    D = base["fp"][1] + rng.choice(D_JITTER[tier])
    EAST = rng.choice([6500, 7000, 7500])          # east service/fitting/checkout band
    spec_h = rng.choice([6000, 6500, 7000])        # specialty band height
    checkout_right = True                           # east band holds checkout (grammar)

    sales_x1, sales_x2 = WEST, W - EAST
    east_x1 = W - EAST
    spec_y2 = D - STOCK
    spec_y1 = spec_y2 - spec_h
    hall_y1, hall_y2 = SOUTH, spec_y1               # gondola hall (below specialty)

    # ── area zones ──
    ent_w = int((sales_x2 - sales_x1) * 0.6)
    ent_x1 = sales_x1 + ((sales_x2 - sales_x1) - ent_w) // 2
    co_y2 = 4000
    area = [
        ("Z-01", "Entrance", "entry_exit", ent_x1, 0, ent_x1 + ent_w, SOUTH),
        ("Z-02", "Checkout Area", "checkout", east_x1, 0, W, co_y2),
        ("Z-03", "Non-Retail Left Strip", "region", 0, SOUTH, WEST, D - STOCK),
        # Z-04 (was single "Main Sales Floor") is replaced below by sport-universe DEPARTMENT bands
        ("Z-05", "Stockroom", "region", 0, D - STOCK, W, D),
        ("Z-11", "Service Cluster", "region", east_x1, co_y2, W, spec_y1),
        ("Z-10", "Fitting Rooms", "region", east_x1, spec_y1, W, spec_y2),
    ]
    # specialty band split into 4 columns: GPS / accessories / gait / bench
    sw = sales_x2 - sales_x1
    cuts = [sales_x1, sales_x1 + int(sw * 0.34), sales_x1 + int(sw * 0.54),
            sales_x1 + int(sw * 0.77), sales_x2]
    area += [
        ("Z-06", "GPS & Accessories", "region", cuts[0], spec_y1, cuts[1], spec_y2),
        ("Z-07", "Accessories Wall", "region", cuts[1], spec_y1, cuts[2], spec_y2),
        ("Z-09", "Gait Analysis", "region", cuts[2], spec_y1, cuts[3], spec_y2),
        ("Z-08", "Footwear Bench", "region", cuts[3], spec_y1, cuts[4], spec_y2),
    ]
    TRY_ON = {"Z-08": "footwear_bench", "Z-09": "equipment_test", "Z-10": "apparel_room"}
    NON_CUST = {"Z-03", "Z-05"}
    area_zones = []
    for code, name, zt, x1, y1, x2, y2 in area:
        gtype, geom, props, (bx1, by1, bx2, by2) = geometry_for(x1, y1, x2, y2)
        z = {"code": code, "name": name, "zone_type": zt, "parent": "space",
             "geometry_type": gtype, "geometry": geom, "properties": props,
             "area_sqm": asqm(x1, y1, x2, y2), "customer_accessible": code not in NON_CUST,
             "rect_mm": {"x1": bx1, "y1": by1, "x2": bx2, "y2": by2}}
        if code in TRY_ON:
            z["zone_type"] = "try_on_zone"; z["try_on_profile"] = TRY_ON[code]
        area_zones.append(z)

    fx = []
    # ── gondolas: fit rows×units into the hall with guaranteed aisles ──
    gx1 = sales_x1 + WALL_D + 400          # inset past west wall + side aisle
    gx2 = sales_x2 - WALL_D - 400
    hall_h = hall_y2 - hall_y1
    # max count that fits with >= gap spacing:  n <= (span - gap) / (size + gap).
    # result is min(fit cap, desired) — the fit cap ALWAYS wins so it can't overflow.
    max_rows = int((hall_h - MIN_AISLE) / (GOND_DEPTH + MIN_AISLE))
    rows = min(max_rows, max(2, base["rows"] + rng.choice([-1, 0, 0])))
    max_units = int((gx2 - gx1 - 300) / (UNIT_W + 300))
    units = min(max_units, max(2, base["rows"] + rng.choice([-1, 0, 1])))
    gcells = cells(gx1, hall_y1, gx2, hall_y2, units, rows, UNIT_W, GOND_DEPTH)
    rows_y = {}                                            # gondola-row r -> (y_lo, y_hi)
    for x1, y1, x2, y2, r, c in gcells:
        rows_y[r] = (y1, y1 + GOND_DEPTH)

    # ── DEPARTMENTS: carve the hall into sport-universe bands (replaces single Z-04) ──
    # contiguous gondola-row blocks, south(front)→north, biggest universe first.
    dept_keys = su.universes_for_tier(TIER_BUCKET[tier], store_seed(sid))
    nb = min(len(dept_keys), rows)
    dept_keys = dept_keys[:nb]
    if "general" not in dept_keys:                         # keep the multi-sport catch-all
        dept_keys[-1] = "general"
    per = [rows // nb + (1 if i < rows % nb else 0) for i in range(nb)]
    row_dept = {}
    rstart = 0
    for di, cnt in enumerate(per):
        for r in range(rstart, rstart + cnt):
            row_dept[r] = di
        rstart += cnt
    dept_rows = {di: sorted(r for r, d in row_dept.items() if d == di) for di in range(nb)}
    bounds = [hall_y1]
    for di in range(nb - 1):
        last_r, next_r = dept_rows[di][-1], dept_rows[di + 1][0]
        bounds.append(round((rows_y[last_r][1] + rows_y[next_r][0]) / 2))
    bounds.append(hall_y2)
    dept_code = {di: f"D-{di + 1:02d}" for di in range(nb)}
    for di in range(nb):
        key = dept_keys[di]
        gtype, geom, props, (bx1, by1, bx2, by2) = geometry_for(sales_x1, bounds[di], sales_x2, bounds[di + 1])
        area_zones.append({"code": dept_code[di], "name": su.label_for(key), "zone_type": "region",
                           "parent": "space", "department": key,
                           "geometry_type": gtype, "geometry": geom, "properties": props,
                           "area_sqm": asqm(sales_x1, bounds[di], sales_x2, bounds[di + 1]),
                           "customer_accessible": True,
                           "rect_mm": {"x1": bx1, "y1": by1, "x2": bx2, "y2": by2}})

    def dept_by_y(yc):                                     # perimeter bays: band containing y-center
        for di in range(nb):
            if bounds[di] <= yc < bounds[di + 1]:
                return di
        return nb - 1

    # ── gondola fixtures, each tagged with its department ──
    for x1, y1, x2, y2, r, c in gcells:
        di = row_dept[r]; rr, uu = rows - r, c + 1         # R1 = south (front of store)
        gf = fixture(f"GF-R{rr}-U{uu}", f"Gondola R{rr} Front U{uu}", x1, y1, x2, y1 + 600, dept_code[di], "gondola_front")
        gb = fixture(f"GB-R{rr}-U{uu}", f"Gondola R{rr} Back U{uu}", x1, y1 + 600, x2, y2, dept_code[di], "gondola_back")
        gf["department"] = gb["department"] = dept_keys[di]
        fx += [gf, gb]
    # ── perimeter wall bays (one column each side) — tagged by the band they fall in ──
    pbays = max(3, rows)
    for side, label, role, sx1, sx2 in (("PW", "West", "perimeter_west", sales_x1, sales_x1 + WALL_D),
                                        ("PE", "East", "perimeter_east", sales_x2 - WALL_D, sales_x2)):
        for x1, y1, x2, y2, r, c in fill(sx1, hall_y1, sx2, hall_y2, 1, pbays):
            di = dept_by_y((y1 + y2) / 2)
            pf = fixture(f"{side}-{r+1:02d}", f"{label} Wall Bay {r+1}", x1, y1, x2, y2, dept_code[di], role)
            pf["department"] = dept_keys[di]
            fx.append(pf)
    # ── specialty fixtures (counts seeded; fill-placed so they always fit their zone) ──
    z = {a["code"]: a["rect_mm"] for a in area_zones}
    def rect(c): r = z[c]; return (r["x1"], r["y1"], r["x2"], r["y2"])
    n_gps = rng.choice([4, 6])
    for x1, y1, x2, y2, r, c in fill(*rect("Z-06"), 2, n_gps // 2):
        fx.append(fixture(f"GPS-{r*2+c+1:02d}", f"GPS Display Case {r*2+c+1}", x1, y1, x2, y2, "Z-06", "gps_case"))
    n_acc = rng.choice([3, 4])
    for x1, y1, x2, y2, r, c in fill(*rect("Z-07"), 1, n_acc):
        fx.append(fixture(f"ACC-{r+1:02d}", f"Accessories Bay {r+1}", x1, y1, x2, y2, "Z-07", "accessories_wall"))
    n_tm = rng.choice([1, 2])
    for x1, y1, x2, y2, r, c in fill(*rect("Z-09"), n_tm, 1):
        fx.append(fixture(f"GA-{c+1:02d}", f"Treadmill {c+1}", x1, y1, x2, y2, "Z-09", "gait_treadmill"))
    for x1, y1, x2, y2, r, c in fill(*rect("Z-08"), 1, 1):
        fx.append(fixture("FB-01", "Footwear Bench Seat", x1, y1, x2, y2, "Z-08", "footwear_bench"))
    n_fr = rng.choice([3, 4])
    fr_rows = (n_fr + 1) // 2
    for x1, y1, x2, y2, r, c in fill(*rect("Z-10"), 2, fr_rows):
        idx = r * 2 + c + 1
        if idx <= n_fr:
            fx.append(fixture(f"FR-{idx:02d}", f"Fitting Stall {idx}", x1, y1, x2, y2, "Z-10", "fitting_stall"))
    # checkout counters (top of Z-02) — lanes scale with tier/traffic — + impulse rack (bottom strip)
    n_co = {"flagship": 4, "large": 3, "medium": 2}[tier]
    cz = z["Z-02"]
    for x1, y1, x2, y2, r, c in fill(cz["x1"], cz["y1"] + 800, cz["x2"], cz["y2"], n_co, 1):
        fx.append(fixture(f"CO-{c+1:02d}", f"Checkout Counter {c+1}", x1, y1, x2, y2, "Z-02", "checkout"))
    fx.append(fixture("CO-IR", "Impulse Rack", cz["x1"] + 150, cz["y1"] + 150, cz["x2"] - 150, cz["y1"] + 650, "Z-02", "impulse_rack"))

    # front-of-store FEATURE displays in the entrance (Z-01) — circular promo islands + apparel
    # rounders (the "circular fixture" type, used sparingly as Decathlon does). shape=circle.
    n_feat = {"flagship": 3, "large": 2, "medium": 1}[tier]
    ez = z["Z-01"]
    for x1, y1, x2, y2, r, c in fill(ez["x1"] + 300, 900, ez["x2"] - 300, 2700, n_feat, 1):
        rack = c % 2 == 1
        fx.append(fixture(f"{'RR' if rack else 'PI'}-{c+1:02d}",
                          f"Apparel Round Rack {c+1}" if rack else f"Promo Island {c+1}",
                          x1, y1, x2, y2, "Z-01",
                          "round_rack" if rack else "promo_island", shape="circle"))

    # ── back-of-house: Stockroom (Z-05) made real — receiving dock + lean backroom racks ──
    # non-customer (Z-05 ∈ NON_CUST); gives restock / receiving / stocktake a real from-location.
    bz = z["Z-05"]
    dock_w = 2600
    fx.append(fixture("RCV-01", "Receiving Dock", bz["x1"] + 200, bz["y1"] + 200,
                      bz["x1"] + 200 + dock_w, bz["y2"] - 200, "Z-05", "receiving_dock"))
    n_rack = BOH_RACKS[tier]
    rcols = (n_rack + 1) // 2
    for x1, y1, x2, y2, r, c in fill(bz["x1"] + 200 + dock_w + 300, bz["y1"] + 200, bz["x2"] - 200, bz["y2"] - 200, rcols, 2):
        idx = r * rcols + c + 1
        if idx <= n_rack:
            br = fixture(f"BR-{idx:02d}", f"Backroom Rack {idx}", x1, y1, x2, y2, "Z-05", "backroom_rack")
            br["department"] = "boh"
            fx.append(br)

    zones = area_zones + fx
    # ── per-store verification ──
    overlaps = [(fx[i]["code"], fx[j]["code"]) for i in range(len(fx)) for j in range(i + 1, len(fx)) if _overlap(fx[i], fx[j]) > 0]
    assert not overlaps, f"{sid}: OVERLAPS {overlaps[:6]} ({len(overlaps)})"
    oob = [f["code"] for f in fx if not (0 <= f["rect_mm"]["x1"] and f["rect_mm"]["x2"] <= W and 0 <= f["rect_mm"]["y1"] and f["rect_mm"]["y2"] <= D)]
    assert not oob, f"{sid}: out-of-bounds {oob[:6]}"
    # circle center must agree between the geometry POINT and properties (live mother had 7/16 mismatched)
    for f in fx + area_zones:
        if f["geometry_type"] == "circle":
            p = f["properties"]
            assert f["geometry"] == f"POINT Z ({p['centerX']} {p['centerY']} 0)", f"{sid} {f['code']}: center mismatch"

    by_cat = {}
    for f in fx:
        by_cat[f["fixture_category"]] = by_cat.get(f["fixture_category"], 0) + 1
    template = {
        "store_id": sid, "tier": tier,
        "name": f"{sid} — {tier} floor ({asqm(0,0,W,D):.0f} m² footprint)",
        "source": "scripts/build_layout.py (parametric, per-store seed=sha256(store_id))",
        "footprint_mm": {"width": W, "depth": D, "origin": "SW corner (0,0)"},
        "coordinate_units": "millimeters", "geometry": "rectangles -> POLYGON Z, SRID 0",
        "counts": {"zones_total": len(zones), "area_zones": len(area_zones), "fixture_zones": len(fx),
                   "try_on_zones": sum(1 for a in area_zones if a["zone_type"] == "try_on_zone"),
                   "departments": nb, "backroom_racks": n_rack,
                   "gondola_rows": rows, "gondola_units": units},
        "departments": [{"code": dept_code[di], "key": dept_keys[di], "label": su.label_for(dept_keys[di])}
                        for di in range(nb)],
        "zones": zones,
        "crossing_slices": [{"code": "CS-01", "name": "Main Entrance Gate", "zone_code": "Z-01",
                             "y": 600, "x_start": ent_x1, "x_end": ent_x1 + ent_w, "eas_gate": True}],
        "sensors": [{"type": "rfid_overhead", "zone_code": "Z-06", "coverage": "GPS display cases"},
                    {"type": "eas_gate", "zone_code": "Z-01", "coverage": "main entrance"}],
    }
    return template, by_cat


def main():
    retail = [s for s in STORES]
    print(f"generating {len(retail)} per-store layouts...\n")
    tot_fx = 0
    for store in retail:
        tmpl, by_cat = generate(store)
        sdir = os.path.join(OUT, "stores", store["id"])
        os.makedirs(sdir, exist_ok=True)
        with open(os.path.join(sdir, "layout.json"), "w", encoding="utf-8") as f:
            json.dump(tmpl, f, indent=2, ensure_ascii=False)
        c = tmpl["counts"]
        tot_fx += c["fixture_zones"]
        print(f"  {store['id']:<18} {store['tier']:<9} {tmpl['footprint_mm']['width']}×{tmpl['footprint_mm']['depth']}mm  "
              f"{c['gondola_rows']}×{c['gondola_units']} gond  {c['departments']} depts  {c['backroom_racks']} racks  "
              f"{c['zones_total']:>3} zones ({c['fixture_zones']} fx)  0 overlaps")
    print(f"\nwrote {len(retail)} layouts ({tot_fx} fixture-zones total). All asserted overlap-free + in-bounds.")


if __name__ == "__main__":
    main()
