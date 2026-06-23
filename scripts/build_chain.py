#!/usr/bin/env python3
"""
Build the DECATHLON CHAIN inventory dataset — 10 stores across US / FR / KR.

Generalizes build_denver.py: one shared 2,586-SKU product master (real US Decathlon Shopify
catalog, real images/EANs) is re-used across every store; each store gets its own seeded
assortment subset + stock depth (by tier) and globally-unique, scanner-decodable EPCs
(validated Decathlon SGTIN-96 encoder, filter 1 / partition 6). Prices localized per region.

Source : reference/data/us-catalog/detail/*.json   (shared master)
Config : scripts/chain_config.py                    (stores, regions, tiers)
Out    : reference/data/chain/chain-manifest.json
         reference/data/chain/regions.json
         reference/data/chain/stores/<id>/assortment.csv
         reference/data/chain/stores/<id>/epcs.csv

Deterministic. No DB writes. "Realistic, not accurate."
"""
import json, glob, csv, random, os, sys, hashlib
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from chain_config import STORES, REGIONS, TIERS, HQ, OFFICE_SITES, localize_price
import catalog_coding as cc          # brand/classification/colour coding (CORE-REQ-001)
import size_curve                     # realistic per-style depth + size-curve allocation
import sport_universe as su           # brand → sport-universe department

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DET = os.path.join(ROOT, "reference/data/us-catalog/detail")
OUT = os.path.join(ROOT, "reference/data/chain")
MASTER_SEED = 42

# ── planogram buckets (lifted verbatim from build_denver.py) ──────────────────
TYPEMAP = {}
for t in ["Shoes", "Snow boots", "Cycling Shoes", "Sandals", "Snowshoe"]:
    TYPEMAP[t] = "footwear"
for t in ["Jacket", "Fleece", "T-shirt", "Trousers/pants", "Shorts", "Base layer", "Down jacket",
          "Long-sleeved t-shirt", "Leggings", "Short-sleeved jersey", "Ski pants", "Baselayer bottom",
          "Zip-Off Pants", "Long-sleeved jersey", "Pullover", "Parka", "Padded Jacket", "Overalls",
          "Windbreaker", "Ski suit", "Cycling shorts", "Cycling bib shorts", "3 in 1 jacket", "Softshell",
          "Midlayer", "Tank", "Sweatshirt", "Rain Jacket", "Rain Poncho", "Boxer shorts", "Boxers",
          "Sports bra", "Skort"]:
    TYPEMAP[t] = "apparel"
for t in ["Socks", "Gloves", "Beanie", "Cap", "Hat", "Glove liner", "Mittens", "Fingerless gloves", "Towel",
          "Headband", "Belt", "Neck warmer", "Flask", "Thermos", "Water bottle waist pack", "Bottle cap",
          "Bottle cage", "Chest strap", "Headlamp", "Lantern", "Water bag", "Water pouch", "Bladder",
          "Hiking pole", "Trail running poles", "Running vest"]:
    TYPEMAP[t] = "accessories"
for t in ["Backpack", "Travel bag", "Sport bag", "Belt bag", "Quiver", "Bag protector"]:
    TYPEMAP[t] = "bag_pack"
for t in ["Road bike", "Gravel bike", "Mountain bike", "Home trainer", "Tent", "tent", "Tent poles",
          "Mattress", "Inflatable mattress", "Sleeping bag", "Sleeping bag liner", "Pillow", "Foam mat",
          "Shelter", "Cookset", "Camping stove", "Camp bed", "Bed base", "Hammock", "Folding chair",
          "Foot pump", "Frame", "Seat", "Shower", "Pad"]:
    TYPEMAP[t] = "outdoor"
DROP = {"Gift Card", "Fastener", "Repair tape", "zip puller", "Zip Slider", "Clamping strap", "Bottle cap"}
SHALLOW = {"outdoor"}                         # bikes/tents/furniture -> display models, depth 1-3
BOH_FRACTION = 0.18                           # share of each style's stock staged in the backroom (Decathlon-lean)
# DENSITY KNOB — per-style depth × store-size scale. Tuned for testing variety/volume while keeping
# per-modal-size facings realistic (~4-8). Lean baseline 1.0/0.72/0.5 ≈ 52k EPCs; this ~2× lift
# (≈100k EPCs) gives fuller size-run coverage for variable inventory checks. Dial down for leaner.
TIER_SCALE = {"flagship": 2.0, "large": 1.4, "medium": 0.95}


def bucket(t):
    return None if t in DROP else TYPEMAP.get(t, "outdoor")


# LAYOUT-DRIVEN planogram: SKU category -> eligible fixture ROLES (priority order). Each store's
# planogram is built from whatever fixtures of these roles ITS OWN layout has (build_layout.py
# emits per-store layouts). No hardcoded fixture codes — adapts to any floor.
CATEGORY_ROLES = {
    "apparel":     ["gondola_front", "gondola_back", "fitting_stall", "round_rack"],
    "accessories": ["accessories_wall", "impulse_rack", "gondola_back", "promo_island"],
    "footwear":    ["perimeter_west", "perimeter_east", "footwear_bench", "gait_treadmill"],
    "bag_pack":    ["perimeter_east", "gondola_front"],
    "outdoor":     ["gondola_front", "gondola_back"],
}


BOH_ROLES = {"backroom_rack", "receiving_dock"}


def store_fixtures(layout):
    """(floor, boh_racks) for THIS store. floor = [(code, role, department)] (customer-facing
    stocking locations); boh_racks = [code] backroom racks. Receiving dock isn't a stock location."""
    floor, boh = [], []
    for sp in layout["spaces"]:                      # site → spaces → zones (Pass 1)
        for z in sp["zones"]:
            if z.get("zone_type") != "fixture":
                continue
            role = z["fixture_category"]
            if role == "backroom_rack":
                boh.append(z["code"])
            elif role not in BOH_ROLES:
                floor.append((z["code"], role, z.get("department", "general")))
    return sorted(floor), sorted(boh)

# ── validated Decathlon SGTIN-96 encoder (EPC-ENCODING-DECATHLON.md) ───────────
FILTER, PARTITION = 1, 6


def ean_to_epc(ean, serial, filt=FILTER, part=PARTITION):
    c = int(ean[0:6]); it = int(ean[6:12])
    return f"{int(f'{0x30:08b}{filt:03b}{part:03b}{c:020b}{0:04b}{it:020b}{serial:038b}', 2):024X}"


def epc_to_ean(epc):
    """Decode EPC -> EAN-13 (recompute check digit). Round-trip self-test only."""
    b = bin(int(epc, 16))[2:].zfill(96)
    company = str(int(b[14:34], 2))
    indicator = str(int(b[34:38], 2))
    itemref = str(int(b[38:58], 2)).zfill(6)
    s = (indicator + company + itemref).zfill(13)
    d = [int(ch) for ch in s]
    chk = (10 - (3 * (d[0] + d[2] + d[4] + d[6] + d[8] + d[10] + d[12]) +
                 (d[1] + d[3] + d[5] + d[7] + d[9] + d[11])) % 10) % 10
    return company + itemref + str(chk)


# ── load the shared master once ───────────────────────────────────────────────
def load_master():
    variants = []
    for f in glob.glob(os.path.join(DET, "*.json")):
        try:
            p = json.load(open(f))["product"]
        except Exception:
            continue
        cat = bucket(p.get("product_type", ""))
        if not cat:
            continue
        opts = [o["name"].lower() for o in p.get("options", [])]
        si = next((i for i, n in enumerate(opts) if "size" in n), None)
        ci = next((i for i, n in enumerate(opts) if "col" in n), None)
        imgs = [im["src"] for im in p.get("images", [])]
        for v in p["variants"]:
            ean = (v.get("barcode") or "").strip()
            if not (len(ean) == 13 and ean.isdigit()
                    and int(ean[:6]) <= 0xFFFFF and int(ean[6:12]) <= 0xFFFFF):
                continue
            size = (v.get(f"option{si+1}") if si is not None else "") or "OS"
            color = (v.get(f"option{ci+1}") if ci is not None else "") or ""
            img = ""
            if v.get("featured_image") and v["featured_image"].get("src"):
                img = v["featured_image"]["src"]
            elif imgs:
                img = imgs[0]
            variants.append({
                "ean": ean, "item_cd": (v.get("sku") or "").strip(), "category": cat,
                "brand": (p.get("vendor") or "").strip(),     # §1 authoritative brand
                "name_en": p["title"], "product_type": p.get("product_type", ""),
                "size_us": size, "color": cc.clean_value(color),   # nbsp/space noise removed
                "price_usd": v.get("price"),
                "handle": p["handle"], "image": img, "n_images": len(imgs)})
    # stable order so a store's seeded subset is reproducible
    variants.sort(key=lambda v: v["ean"])
    return variants


# ── build one store ───────────────────────────────────────────────────────────
ASSORT_COLS = ["ean", "item_cd", "brand", "category", "classification_key", "department", "product_type",
               "name_en", "name_local", "size_us", "color", "price_usd", "price_local",
               "currency", "locale", "fixture", "depth", "handle", "image", "n_images"]
EPC_COLS = ["epc", "ean", "item_cd", "category", "fixture", "store_id"]


def load_name_map():
    """ean -> (name_fr, name_ko) from the localization enrichment pass, if present."""
    path = os.path.join(OUT, "localization", "name-localization.csv")
    m = {}
    if os.path.exists(path):
        for r in csv.DictReader(open(path, encoding="utf-8")):
            m[r["ean"]] = (r["name_fr"], r["name_ko"])
    return m


def load_store_layout(store_id):
    """Per-store layout (zones + fixtures) from build_layout.py."""
    path = os.path.join(OUT, "stores", store_id, "layout.json")
    return json.load(open(path, encoding="utf-8")) if os.path.exists(path) else None


def build_store(store, styles_all, global_used, global_epcs, name_map):
    region = REGIONS[store["region"]]
    # stable per-store seed (Python's hash() is salted per process — not reproducible)
    seed = int(hashlib.sha256(store["id"].encode()).hexdigest(), 16) % (2**31)
    rng = random.Random(seed)

    layout = load_store_layout(store["id"])
    floor, boh_racks = store_fixtures(layout)    # customer-facing fixtures + backroom racks
    present = {d["key"] for d in layout.get("departments", [])}   # universes THIS store actually merchandises

    # ── SKU subset: sample whole STYLES so each carries its full size run (the curve needs it) ──
    handles = sorted(styles_all.keys())
    n = TIERS[store["tier"]]["sku_count"]
    master_len = sum(len(v) for v in styles_all.values())
    if n is None or n >= master_len:
        chosen = handles
    else:
        sh = handles[:]; rng.shuffle(sh)
        chosen, cnt = [], 0
        for h in sh:
            chosen.append(h); cnt += len(styles_all[h])
            if cnt >= n:
                break
    subset = {h: [dict(v) for v in styles_all[h]] for h in chosen}

    # ── per-STYLE depth budget: realistic ABSOLUTE units per style (a real size-curve run),
    # scaled only by tier. NOT scaled to a fixed piece target — doing that over-deepened every
    # style (it was the source of the 88-pair shoe). Total pieces now fall out of real depths. ──
    tier_scale = TIER_SCALE[store["tier"]]

    rr = defaultdict(int)                         # round-robin per (category, department) over floor
    boh_i = 0
    rows, epcs = [], []

    def pick_floor(cat, dept):
        """A floor fixture for this category+department. Priority: role-match in the right
        department → any fixture in the department → role-match anywhere → any floor fixture."""
        roles = CATEGORY_ROLES[cat]
        cands = ([c for c, r, d in floor if r in roles and d == dept]
                 or [c for c, r, d in floor if d == dept]
                 or [c for c, r, d in floor if r in roles]
                 or [c for c, _, _ in floor])
        k = (cat, dept)
        out = cands[rr[k] % len(cands)]; rr[k] += 1
        return out

    def emit(v, fixture_code):
        while True:
            s = rng.randint(1000, 9_999_999)
            if (v["ean"], s) not in global_used:
                global_used.add((v["ean"], s)); break
        epc = ean_to_epc(v["ean"], s)
        global_epcs.add(epc)
        epcs.append({"epc": epc, "ean": v["ean"], "item_cd": v["item_cd"],
                     "category": v["category"], "fixture": fixture_code, "store_id": store["id"]})

    for h, vs in subset.items():
        cat, brand = vs[0]["category"], vs[0]["brand"]
        dept = su.universe_for_brand(brand)
        if dept not in present:                  # small store lacks this universe → merchandise under General
            dept = "general"
        budget = size_curve.style_target(cat, tier_scale, rng)   # realistic total units for this style
        if cat in SHALLOW:
            budget = min(budget, 3)
        alloc = size_curve.allocate(vs, budget, cat, rng)     # {ean: unit count} — the size curve
        floor_fx = pick_floor(cat, dept)
        for v in vs:
            count = int(alloc.get(v["ean"], 0))
            v["depth"] = count
            v["fixture"] = floor_fx
            v["department"] = dept
            v["classification_key"] = cc.classification_key(cat, v["product_type"])   # §2 link
            fr, ko = name_map.get(v["ean"], (v["name_en"], v["name_en"]))
            v["name_local"] = {"US": v["name_en"], "FR": fr, "KR": ko}[store["region"]]
            v["price_local"] = localize_price(v["price_usd"], store["region"])
            v["currency"] = region["currency"]
            v["locale"] = region["locale"]
            rows.append(v)
            boh_n = int(round(count * BOH_FRACTION)) if boh_racks else 0   # stage some in the backroom
            for _ in range(count - boh_n):
                emit(v, floor_fx)
            for _ in range(boh_n):
                emit(v, boh_racks[boh_i % len(boh_racks)]); boh_i += 1

    sdir = os.path.join(OUT, "stores", store["id"])
    os.makedirs(sdir, exist_ok=True)
    with open(os.path.join(sdir, "assortment.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=ASSORT_COLS); w.writeheader()
        for v in rows:
            w.writerow({k: v.get(k, "") for k in ASSORT_COLS})
    with open(os.path.join(sdir, "epcs.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=EPC_COLS); w.writeheader(); w.writerows(epcs)

    boh_codes = set(boh_racks)
    return {"sku_count": len(rows), "epc_count": len(epcs),
            "by_category": dict(Counter(e["category"] for e in epcs)),
            "by_department": dict(Counter(v["department"] for v in rows if v["depth"] > 0)),
            "boh_epc": sum(1 for e in epcs if e["fixture"] in boh_codes),
            "rows": rows, "epcs": epcs, "layout": layout,
            "fixture_codes": {z["code"] for sp in layout["spaces"] for z in sp["zones"]
                              if z.get("zone_type") == "fixture"}}


def main():
    os.makedirs(OUT, exist_ok=True)
    master = load_master()
    styles_all = defaultdict(list)               # handle -> [size/colour variants] (style = planogram unit)
    for v in master:
        styles_all[v["handle"]].append(v)
    name_map = load_name_map()
    print(f"master SKUs (encodable): {len(master)}")
    print(f"name localization map: {len(name_map)} SKUs "
          f"({'loaded' if name_map else 'MISSING — name_local falls back to English'})")
    print("per-store layouts: stores/<id>/layout.json (run build_layout.py first)")
    print("by category:", dict(Counter(v["category"] for v in master)))

    global_used, global_epcs = set(), set()
    total_fixtures = total_stocked = 0
    fixture_missing = set()
    manifest_stores, total_epcs, total_sku = [], 0, 0
    rt_checked = rt_ok = 0

    print(f"styles (planogram units): {len(styles_all)}")
    for store in STORES:
        res = build_store(store, styles_all, global_used, global_epcs, name_map)
        total_epcs += res["epc_count"]; total_sku += res["sku_count"]
        store_used = {e["fixture"] for e in res["epcs"]}
        fixture_missing |= (store_used - res["fixture_codes"])      # EPCs must sit on this store's fixtures
        total_fixtures += len(res["fixture_codes"]); total_stocked += len(store_used & res["fixture_codes"])
        # round-trip 1% sample
        sample = res["epcs"][:: max(1, len(res["epcs"]) // 100)]
        for e in sample:
            rt_checked += 1
            if epc_to_ean(e["epc"]) == e["ean"]:
                rt_ok += 1
        region = REGIONS[store["region"]]
        manifest_stores.append({
            "id": store["id"], "site_type": "retail",
            "site_category": store.get("site_category", "store"),   # CORE-REQ-002 (→ site.site_category)
            "region": store["region"],
            "country": region["country"], "city": store["city"], "state": store.get("state", ""),
            "address": store["address"], "latitude": store["lat"], "longitude": store["lon"],
            "timezone": store["tz"], "currency": region["currency"],
            "locale": region["locale"], "tier": store["tier"],
            "tier_label": TIERS[store["tier"]]["label"], "sqm": store["sqm"],
            "has_space": True, "has_inventory": True, "has_sensors": True,
            "template": f"stores/{store['id']}/layout.json",
            "site_footprint_mm": res["layout"]["site_footprint_mm"],
            "space_counts": res["layout"]["counts"],         # aggregate: spaces, zones, departments, etc.
            "spaces": [{"code": sp["code"], "space_type": sp["space_type"], "name": sp["name"],
                        "footprint_mm": sp["footprint_mm"],
                        "site_frame_anchor_space": sp["site_frame_anchor_space"],   # Pass 2 (dormant)
                        "srf_to_site_transform": sp["srf_to_site_transform"],       # Pass 2 (dormant)
                        "counts": sp["counts"]} for sp in res["layout"]["spaces"]],
            "departments": res["layout"].get("departments", []),
            "sku_count": res["sku_count"], "epc_count": res["epc_count"],
            "epc_by_category": res["by_category"],
            "epc_by_department": res["by_department"], "boh_epc": res["boh_epc"],
            "files": {"assortment": f"stores/{store['id']}/assortment.csv",
                      "epcs": f"stores/{store['id']}/epcs.csv"}})
        print(f"  {store['id']:<18} {store['tier']:<9} {res['sku_count']:>5} SKU  "
              f"{res['epc_count']:>6} EPC ({res['boh_epc']:>5} BOH)  {len(res['by_department'])} depts  {region['currency']}  {store['tz']}")

    office_sites = [{
        "id": o["id"], "site_type": "office", "office_role": o["office_role"],
        "site_category": o.get("site_category", "office"),         # CORE-REQ-002 (→ site.site_category)
        "region": o["region"], "country": o["country"], "city": o["city"],
        "address": o["address"], "latitude": o["lat"], "longitude": o["lon"], "timezone": o["tz"],
        "has_space": False, "has_inventory": False, "has_sensors": False,
        "sku_count": 0, "epc_count": 0,
    } for o in OFFICE_SITES]

    manifest = {
        "chain": "Decathlon (M8trxDemo)",
        "generated": "deterministic; master_seed=42, per-store seed=sha256(store_id)",
        "note": "Demo dataset. Shared US product master localized per region. Realistic, not accurate.",
        "hq": HQ,
        "layout_reference": "per-store: stores/<id>/layout.json (UNIQUE floor per store — parametric build_layout.py, seed=sha256(store_id); 0 overlaps asserted; source STORE-LAYOUT.md)",
        "epc_encoding_reference": "reference/data/EPC-ENCODING-DECATHLON.md (SGTIN-96, filter 1 / partition 6)",
        "totals": {"sites_total": len(STORES) + len(OFFICE_SITES),
                   "retail_sites": len(STORES), "office_sites": len(OFFICE_SITES),
                   "skus_master": len(master), "sku_rows_all_stores": total_sku,
                   "epcs_all_stores": total_epcs},
        "stores": manifest_stores,
        "office_sites": office_sites,
    }
    with open(os.path.join(OUT, "chain-manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
    with open(os.path.join(OUT, "regions.json"), "w", encoding="utf-8") as f:
        json.dump(REGIONS, f, indent=2, ensure_ascii=False)

    # ── verification ──
    print("\n── verification ──")
    print(f"stores: {len(STORES)}  total SKU rows: {total_sku}  total EPCs: {total_epcs}")
    assert len(global_epcs) == total_epcs, \
        f"EPC COLLISION: {total_epcs - len(global_epcs)} duplicate EPCs across chain"
    print(f"EPC global uniqueness: OK ({len(global_epcs)} distinct)")
    print(f"EPC round-trip decode: {rt_ok}/{rt_checked} sampled tags decode to their EAN")
    assert rt_ok == rt_checked, "EPC round-trip FAILED"
    # every EPC must sit on a fixture that exists in ITS store's own layout
    assert not fixture_missing, f"EPCs reference fixtures absent from store layout: {sorted(fixture_missing)[:10]}"
    print(f"fixture coverage: OK ({total_stocked}/{total_fixtures} per-store fixtures stocked across {len(STORES)} layouts)")
    # tz validity
    try:
        import zoneinfo
        for s in STORES:
            zoneinfo.ZoneInfo(s["tz"])
        print(f"timezones: OK ({len(set(s['tz'] for s in STORES))} distinct IANA zones)")
    except Exception as e:
        print(f"timezone check skipped/failed: {e}")
    # INVARIANT: HQ + regional offices NEVER get a space/zone/fixture/inventory — site row only
    retail_ids = {s["id"] for s in manifest_stores}
    for o in office_sites:
        assert o["has_space"] is False and o["has_inventory"] is False, f"{o['id']} must not have space/inventory"
        assert "spaces" not in o and o["epc_count"] == 0 and o["sku_count"] == 0, f"{o['id']} leaked retail layout"
        assert o["id"] not in retail_ids, f"{o['id']} collides with a retail site"
    assert all(s.get("spaces") and len(s["spaces"]) == 3 for s in manifest_stores), "a retail site is missing its 3 spaces"
    print(f"office sites: {len(office_sites)} site-row-only (no space/zone/fixture/inventory) — OK")
    print(f"\nwrote {OUT}/chain-manifest.json + regions.json + {len(STORES)} store dirs")


if __name__ == "__main__":
    main()
