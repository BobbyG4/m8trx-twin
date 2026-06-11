#!/usr/bin/env python3
"""
Extract the store layout from STORE-LAYOUT.md into a machine-readable space template the backend
can provision directly.

CORE-MODEL MATCH (per twin/insights/2026-06-11-seed-exercise-corrections.md §1): core renders
fixtures as `zone` rows with `zone_type='fixture'` (the `fixture` table is unused). So this emits a
SINGLE unified `zones[]` — 11 area/checkout/try_on zones + 149 fixture-zones = 160 zones, all
children of the one space. The `fixture` codes in epcs.csv resolve to the fixture-zone of the same
`code`, keyed (store_id, code). Geometry: mm, SW origin (0,0), rectangles → POLYGON Z, SRID 0.

All 10 retail sites share this ONE 600 sqm template (each instantiates its own space + 160 zones).
Office sites get no space.

In  : reference/data/STORE-LAYOUT.md
Out : reference/data/chain/layout/space-template.json
"""
import json, os, re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "reference/data/STORE-LAYOUT.md")
OUTDIR = os.path.join(ROOT, "reference/data/chain/layout")

# fixture code prefix -> (area zone it sits in, fixture_category)
PREFIX = {
    "GF": ("Z-04", "gondola_front"), "GB": ("Z-04", "gondola_back"),
    "PE": ("Z-04", "perimeter_east"), "PW": ("Z-04", "perimeter_west"),
    "GPS": ("Z-06", "gps_case"), "ACC": ("Z-07", "accessories_wall"),
    "FB": ("Z-08", "footwear_bench"), "GA": ("Z-09", "gait_treadmill"),
    "FR": ("Z-10", "fitting_stall"), "CO": ("Z-02", "checkout"),
}
TRY_ON = {"Z-08": "footwear_bench", "Z-09": "equipment_test", "Z-10": "apparel_room"}
NON_CUSTOMER = {"Z-03", "Z-05"}                       # non-retail strip + stockroom

ZONE_RE = re.compile(r"^\|\s*(Z-\d+)\s*\|\s*([^|]+?)\s*\|\s*([a-z_]+)\s*\|\s*"
                     r"(\d+)\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|")
FIX_RE = re.compile(r"^\|\s*([A-Z]{2,3}-[A-Z0-9-]+)\s*\|\s*([^|]+?)\s*\|\s*"
                    r"(\d+)\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|")


def area_sqm(x1, y1, x2, y2):
    return round((x2 - x1) * (y2 - y1) / 1_000_000, 1)


def main():
    os.makedirs(OUTDIR, exist_ok=True)
    text = open(SRC, encoding="utf-8").read()

    area_zones, fixture_zones = [], []
    for ln in text.splitlines():
        zm = ZONE_RE.match(ln)
        if zm:
            code, name, ztype, x1, y1, x2, y2 = zm.group(1), zm.group(2), zm.group(3), *map(int, zm.groups()[3:])
            z = {"code": code, "name": name, "zone_type": ztype, "parent": "space",
                 "area_sqm": area_sqm(x1, y1, x2, y2),
                 "customer_accessible": code not in NON_CUSTOMER,
                 "rect_mm": {"x1": x1, "y1": y1, "x2": x2, "y2": y2}}
            if code in TRY_ON:
                z["zone_type"] = "try_on_zone"
                z["try_on_profile"] = TRY_ON[code]
            area_zones.append(z)
            continue
        fm = FIX_RE.match(ln)
        if fm:
            code, name, x1, y1, x2, y2 = fm.group(1), fm.group(2), *map(int, fm.groups()[2:])
            prefix = code.split("-")[0]
            if prefix not in PREFIX:
                continue
            in_zone, cat = PREFIX[prefix]
            if code == "FR-SC":
                cat = "fitting_service"
            if code == "CO-IR":
                cat = "impulse_rack"
            # a fixture IS a zone (zone_type='fixture'), sibling of area zones under the space
            fixture_zones.append({
                "code": code, "name": name, "zone_type": "fixture", "parent": "space",
                "in_area_zone": in_zone, "fixture_category": cat,
                "area_sqm": area_sqm(x1, y1, x2, y2),
                "rect_mm": {"x1": x1, "y1": y1, "x2": x2, "y2": y2}})

    zones = area_zones + fixture_zones            # unified list — what core stores (160 zone rows)

    crossing_slices = [{"code": "CS-01", "name": "Main Entrance Gate", "zone_code": "Z-01",
                        "y": 600, "x_start": 8000, "x_end": 12000,
                        "direction": "left=exit, right=entry", "eas_gate": True}]
    sensors = [
        {"type": "xovis_3d", "x": 10000, "y": 8000, "coverage": "gondola rows 1-5"},
        {"type": "xovis_3d", "x": 10000, "y": 16000, "coverage": "gondola rows 6-8 + specialty"},
        {"type": "xovis_3d", "x": 20000, "y": 10000, "coverage": "service cluster + checkout"},
        {"type": "rfid_overhead", "zone_code": "Z-06", "coverage": "GPS display cases"},
        {"type": "eas_gate", "zone_code": "Z-01", "coverage": "main entrance (CS-01)"},
    ]

    template = {
        "name": "Decathlon City — 600 sqm (shared template)",
        "source": "reference/data/STORE-LAYOUT.md",
        "model_note": ("Fixtures are zones with zone_type='fixture' (core's `fixture` table is "
                       "unused). One space = these zones[]. Each retail site instantiates its own "
                       "space + 160 zones; epcs.csv `fixture` code -> the fixture-zone of that code "
                       "(key: store_id, code). Smaller-tier stores reuse the full set — manifest "
                       "sqm is nominal. Office sites have no space."),
        "sqm": 600,
        "footprint_mm": {"width": 24000, "depth": 25000, "origin": "SW corner (0,0)"},
        "coordinate_units": "millimeters",
        "geometry": "rectangles -> POLYGON Z, SRID 0",
        "counts": {"zones_total": len(zones), "area_zones": len(area_zones),
                   "fixture_zones": len(fixture_zones),
                   "try_on_zones": sum(1 for z in area_zones if z["zone_type"] == "try_on_zone"),
                   "crossing_slices": len(crossing_slices), "sensors": len(sensors)},
        "zones": zones,
        "crossing_slices": crossing_slices, "sensors": sensors,
    }

    # ── verify ──
    area_codes = {z["code"] for z in area_zones}
    orphan = [z["code"] for z in fixture_zones if z["in_area_zone"] not in area_codes]
    by_cat = {}
    for z in fixture_zones:
        by_cat[z["fixture_category"]] = by_cat.get(z["fixture_category"], 0) + 1
    assert not orphan, f"fixture-zones with unknown area zone: {orphan}"
    assert len(area_zones) == 11, f"expected 11 area zones, got {len(area_zones)}"
    assert len(fixture_zones) == 149, f"expected 149 fixture-zones, got {len(fixture_zones)}"
    assert len(zones) == 160, f"expected 160 zones total, got {len(zones)}"

    path = os.path.join(OUTDIR, "space-template.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(template, f, indent=2, ensure_ascii=False)

    print(f"zones: {len(zones)} total = {len(area_zones)} area "
          f"({sum(1 for z in area_zones if z['zone_type']=='try_on_zone')} try-on) "
          f"+ {len(fixture_zones)} fixture-zones")
    print(f"fixture-zones by category: {by_cat}")
    print(f"orphan fixture-zones (bad area zone): {len(orphan)}")
    print(f"wrote {path}")


if __name__ == "__main__":
    main()
