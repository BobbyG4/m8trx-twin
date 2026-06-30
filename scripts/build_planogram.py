#!/usr/bin/env python3
"""
Build per-store PLANOGRAM DIRECTIVE documents (m8trx_standard format) from the committed
chain dataset — the SKU→fixture→required_quantity intent the twin posts to M8TRX Connect as
the external planogram tool (Mode 3, directive_kind='planogram').

A planogram directive IS what the seeded dataset already encodes: each SKU sits on a `fixture`
at a stocked quantity. This builder lifts that into the canonical m8trx_standard document — one
placement line per (floor-fixture, SKU) — enriched with the fixture's human name + category from
layout.json. required_qty is taken from the ACTUAL seeded floor count (epcs.csv, BOH excluded),
not the assortment depth (which is floor+BOH); so the directive matches what is physically on the
floor and the store is COMPLIANT BY CONSTRUCTION at activation. Twin ACTIVITY (sales depleting a
fixture, a scan showing a gap) is then what drives it out of compliance — real, explainable
remediation, not a synthetic directive against empty fixtures.

Maps to core compliance_target (PLANOGRAM-RESOLVED-DESIGN-2026-06-30 §1):
    fixture_code      → zone_id            (resolved per-site, NON-inheriting; exact-name match on mother)
    sku (item_cd)     → product_id
    required_qty      → required_quantity  (= seeded floor EPC count for that SKU×fixture)
    facings           → facing_count       (DERIVED — no real facing data; see UNITS_PER_FACING)
    position_sequence → position_sequence  (stable per-fixture ordering)
    line_item_type    → 'product_placement'

Source : reference/data/chain/stores/<id>/{assortment.csv, epcs.csv, layout.json}
Out    : reference/data/chain/stores/<id>/planogram.json

Deterministic — no wall-clock in the document (effective_date is set by the driver at send time);
a source_digest over the canonical lines proves byte-identical regeneration. No DB writes.
"""
import json, csv, os, glob, hashlib
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STORES_DIR = os.path.join(ROOT, "reference/data/chain/stores")

FORMAT = "m8trx_standard"
VERSION = "v1"
LINE_ITEM_TYPE = "product_placement"
BOH_ROLES = {"backroom_rack", "receiving_dock"}   # not customer-facing → not a planogram placement
UNITS_PER_FACING = 4                              # depth→facings estimate (no real facing data)


def facings_for(qty):
    """Estimate visible facings from on-floor quantity. No real facing data — documented heuristic."""
    return max(1, round(qty / UNITS_PER_FACING))


def fixture_meta(layout):
    """code -> {fixture_name, fixture_category, department, space_type} for every fixture zone."""
    meta = {}
    for sp in layout["spaces"]:
        for z in sp["zones"]:
            if z.get("zone_type") != "fixture":
                continue
            meta[z["code"]] = {
                "fixture_name": z.get("name", z["code"]),
                "fixture_category": z.get("fixture_category", ""),
                "department": z.get("department", ""),
                "space_type": sp.get("space_type", ""),
            }
    return meta


def assortment_meta(sdir):
    """item_cd -> {ean, name, department} for SKU enrichment."""
    amap = {}
    with open(os.path.join(sdir, "assortment.csv"), encoding="utf-8") as f:
        for r in csv.DictReader(f):
            amap[r["item_cd"]] = {"ean": r["ean"], "name": r["name_en"], "department": r.get("department", "")}
    return amap


def build_store(store_id):
    sdir = os.path.join(STORES_DIR, store_id)
    layout = json.load(open(os.path.join(sdir, "layout.json"), encoding="utf-8"))
    fmeta = fixture_meta(layout)
    amap = assortment_meta(sdir)
    floor_fixtures = {code for code, m in fmeta.items() if m["fixture_category"] not in BOH_ROLES}

    # ground truth: count seeded EPCs per (floor fixture, SKU) — this IS the on-floor required_qty
    agg = defaultdict(int)
    with open(os.path.join(sdir, "epcs.csv"), encoding="utf-8") as f:
        for e in csv.DictReader(f):
            if e["fixture"] in floor_fixtures:
                agg[(e["fixture"], e["item_cd"])] += 1

    placements = []
    for (fx, item_cd), qty in agg.items():
        fm = fmeta.get(fx, {})
        am = amap.get(item_cd, {})
        placements.append({
            "fixture_code": fx,
            "fixture_name": fm.get("fixture_name", fx),
            "space_type": fm.get("space_type", "sales_floor"),
            "department": am.get("department") or fm.get("department", ""),
            "sku": item_cd,
            "ean": am.get("ean", ""),
            "name": am.get("name", ""),
            "line_item_type": LINE_ITEM_TYPE,
            "required_qty": qty,
            "facings": facings_for(qty),
        })

    # deterministic order; assign a stable position_sequence within each fixture
    placements.sort(key=lambda p: (p["fixture_code"], p["department"], p["sku"]))
    seq = defaultdict(int)
    for p in placements:
        seq[p["fixture_code"]] += 1
        p["position_sequence"] = seq[p["fixture_code"]]

    digest = hashlib.sha256(
        json.dumps(placements, sort_keys=True, ensure_ascii=False).encode("utf-8")
    ).hexdigest()
    fixtures = sorted({p["fixture_code"] for p in placements})

    doc = {
        "format": FORMAT,
        "version": VERSION,
        "directive_ref": f"PLN-{store_id}-{digest[:12]}",
        "store_id": store_id,
        "site_ref": store_id,   # Connect resolves site_ref → site_id via integration_site_xref (INHERITS)
        "source_digest": digest,
        "units_per_facing": UNITS_PER_FACING,
        "notes": "product_placement only; fixture_relocation lines added when SRF calibration data exists",
        "counts": {
            "lines": len(placements),
            "fixtures": len(fixtures),
            "skus": len({p["sku"] for p in placements}),
            "total_required": sum(p["required_qty"] for p in placements),
        },
        "lines": placements,
    }
    with open(os.path.join(sdir, "planogram.json"), "w", encoding="utf-8") as f:
        json.dump(doc, f, indent=2, ensure_ascii=False)

    missing = sorted(set(fixtures) - set(fmeta))   # every placed fixture must exist in the layout
    return doc, missing


def main():
    store_dirs = sorted(
        os.path.dirname(p) for p in glob.glob(os.path.join(STORES_DIR, "*", "assortment.csv"))
    )
    print(f"planogram builder — {len(store_dirs)} stores\n")
    grand_lines = grand_required = 0
    for sdir in store_dirs:
        store_id = os.path.basename(sdir)
        if not (os.path.exists(os.path.join(sdir, "epcs.csv")) and os.path.exists(os.path.join(sdir, "layout.json"))):
            print(f"  {store_id:<18} SKIP (missing epcs.csv or layout.json)")
            continue
        doc, missing = build_store(store_id)
        c = doc["counts"]
        grand_lines += c["lines"]; grand_required += c["total_required"]
        assert not missing, f"{store_id}: placements reference fixtures absent from layout: {missing[:10]}"
        print(f"  {store_id:<18} {c['lines']:>5} lines  {c['fixtures']:>3} fixtures  "
              f"{c['skus']:>5} SKUs  {c['total_required']:>6} units  digest {doc['source_digest'][:12]}")

    print("\n── verification ──")
    print(f"total placement lines: {grand_lines}   total on-floor units governed: {grand_required}")
    print(f"wrote {len(store_dirs)} stores/<id>/planogram.json (m8trx_standard {VERSION})")


if __name__ == "__main__":
    main()
