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
DEPTH = {"apparel": 16, "accessories": 18, "footwear": 10, "bag_pack": 8, "outdoor": 2}


def bucket(t):
    return None if t in DROP else TYPEMAP.get(t, "outdoor")


def GF(rows):
    return [f"GF-R{r}-U{u}" for r in rows for u in range(1, 8)]


def GB(rows):
    return [f"GB-R{r}-U{u}" for r in rows for u in range(1, 8)]


PLAN = {
    "apparel":     GF([1, 2, 3, 4]) + GB([1, 2, 3, 4]) + ["FR-01", "FR-02", "FR-03", "FR-04"],
    "accessories": ["ACC-01", "ACC-02", "ACC-03", "ACC-04", "CO-IR"] + GB([5]),
    "footwear":    [f"PW-0{i}" for i in range(1, 9)] + [f"PE-0{i}" for i in range(1, 6)] + ["FB-01", "GA-01", "GA-02"],
    "bag_pack":    ["PE-06", "PE-07", "PE-08"] + GF([5]),
    "outdoor":     GF([6, 7, 8]) + GB([6, 7, 8]),
}

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
ASSORT_COLS = ["ean", "item_cd", "brand", "category", "classification_key", "product_type",
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


def load_layout():
    """Shared space template (zones + fixtures) from build_layout.py, if present."""
    path = os.path.join(OUT, "layout", "space-template.json")
    if not os.path.exists(path):
        return None
    return json.load(open(path, encoding="utf-8"))


def build_store(store, master, global_used, global_epcs, name_map):
    region = REGIONS[store["region"]]
    # stable per-store seed (Python's hash() is salted per process — not reproducible)
    seed = int(hashlib.sha256(store["id"].encode()).hexdigest(), 16) % (2**31)
    rng = random.Random(seed)

    # SKU subset by tier (shared master, seeded subset for sub-flagship tiers)
    n = TIERS[store["tier"]]["sku_count"]
    if n is None or n >= len(master):
        subset = list(master)
    else:
        subset = rng.sample(master, n)
    subset = [dict(v) for v in subset]

    # depth allocation scaled to the store's target piece count
    target = store["target_pieces"]
    scale = target / sum(DEPTH[v["category"]] for v in subset)
    rr = defaultdict(int)
    rows, epcs = [], []
    for v in subset:
        depth = max(1, round(DEPTH[v["category"]] * scale * rng.uniform(0.6, 1.4)))
        if v["category"] in SHALLOW:
            depth = min(depth, rng.randint(1, 3))
        fx = PLAN[v["category"]]
        v["fixture"] = fx[rr[v["category"]] % len(fx)]
        rr[v["category"]] += 1
        v["depth"] = depth
        v["classification_key"] = cc.classification_key(v["category"], v["product_type"])  # §2 link
        # localization
        fr, ko = name_map.get(v["ean"], (v["name_en"], v["name_en"]))
        v["name_local"] = {"US": v["name_en"], "FR": fr, "KR": ko}[store["region"]]
        v["price_local"] = localize_price(v["price_usd"], store["region"])
        v["currency"] = region["currency"]
        v["locale"] = region["locale"]
        rows.append(v)
        for _ in range(depth):
            while True:
                s = rng.randint(1000, 9_999_999)
                if (v["ean"], s) not in global_used:
                    global_used.add((v["ean"], s)); break
            epc = ean_to_epc(v["ean"], s)
            global_epcs.add(epc)
            epcs.append({"epc": epc, "ean": v["ean"], "item_cd": v["item_cd"],
                         "category": v["category"], "fixture": v["fixture"], "store_id": store["id"]})

    sdir = os.path.join(OUT, "stores", store["id"])
    os.makedirs(sdir, exist_ok=True)
    with open(os.path.join(sdir, "assortment.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=ASSORT_COLS); w.writeheader()
        for v in rows:
            w.writerow({k: v.get(k, "") for k in ASSORT_COLS})
    with open(os.path.join(sdir, "epcs.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=EPC_COLS); w.writeheader(); w.writerows(epcs)

    return {"sku_count": len(rows), "epc_count": len(epcs),
            "by_category": dict(Counter(e["category"] for e in epcs)), "rows": rows, "epcs": epcs}


def main():
    os.makedirs(OUT, exist_ok=True)
    master = load_master()
    name_map = load_name_map()
    layout = load_layout()
    layout_fixtures = ({z["code"] for z in layout["zones"] if z.get("zone_type") == "fixture"}
                       if layout else None)
    print(f"master SKUs (encodable): {len(master)}")
    print(f"name localization map: {len(name_map)} SKUs "
          f"({'loaded' if name_map else 'MISSING — name_local falls back to English'})")
    print(f"layout template: {'loaded (%d zones = %d area + %d fixture-zones)' % (layout['counts']['zones_total'], layout['counts']['area_zones'], layout['counts']['fixture_zones']) if layout else 'MISSING — run build_layout.py'}")
    print("by category:", dict(Counter(v["category"] for v in master)))

    global_used, global_epcs, used_fixtures = set(), set(), set()
    manifest_stores, total_epcs, total_sku = [], 0, 0
    rt_checked = rt_ok = 0

    for store in STORES:
        res = build_store(store, master, global_used, global_epcs, name_map)
        total_epcs += res["epc_count"]; total_sku += res["sku_count"]
        used_fixtures.update(e["fixture"] for e in res["epcs"])
        # round-trip 1% sample
        sample = res["epcs"][:: max(1, len(res["epcs"]) // 100)]
        for e in sample:
            rt_checked += 1
            if epc_to_ean(e["epc"]) == e["ean"]:
                rt_ok += 1
        region = REGIONS[store["region"]]
        manifest_stores.append({
            "id": store["id"], "site_type": "retail", "region": store["region"],
            "country": region["country"], "city": store["city"], "state": store.get("state", ""),
            "address": store["address"], "timezone": store["tz"], "currency": region["currency"],
            "locale": region["locale"], "tier": store["tier"],
            "tier_label": TIERS[store["tier"]]["label"], "sqm": store["sqm"],
            "has_space": True, "has_inventory": True, "has_sensors": True,
            "space": {"name": "Main Floor", "template": "layout/space-template.json",
                      "sqm": store["sqm"],
                      "zones_total": layout["counts"]["zones_total"] if layout else None,
                      "area_zones": layout["counts"]["area_zones"] if layout else None,
                      "fixture_zones": layout["counts"]["fixture_zones"] if layout else None,
                      "try_on_zones": layout["counts"]["try_on_zones"] if layout else None},
            "sku_count": res["sku_count"], "epc_count": res["epc_count"],
            "epc_by_category": res["by_category"],
            "files": {"assortment": f"stores/{store['id']}/assortment.csv",
                      "epcs": f"stores/{store['id']}/epcs.csv"}})
        print(f"  {store['id']:<18} {store['tier']:<9} {res['sku_count']:>5} SKU  "
              f"{res['epc_count']:>6} EPC  {region['currency']}  {store['tz']}")

    office_sites = [{
        "id": o["id"], "site_type": "office", "office_role": o["office_role"],
        "region": o["region"], "country": o["country"], "city": o["city"],
        "address": o["address"], "timezone": o["tz"],
        "has_space": False, "has_inventory": False, "has_sensors": False,
        "sku_count": 0, "epc_count": 0,
    } for o in OFFICE_SITES]

    manifest = {
        "chain": "Decathlon (M8trxDemo)",
        "generated": "deterministic; master_seed=42, per-store seed=sha256(store_id)",
        "note": "Demo dataset. Shared US product master localized per region. Realistic, not accurate.",
        "hq": HQ,
        "layout_reference": "layout/space-template.json (shared 600 sqm space — 160 zones = 11 area + 149 zone_type='fixture'; source STORE-LAYOUT.md)",
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
    # every fixture an EPC sits on must exist in the shared layout template
    if layout_fixtures is not None:
        missing = used_fixtures - layout_fixtures
        assert not missing, f"EPCs reference fixtures absent from layout: {sorted(missing)}"
        print(f"fixture coverage: OK ({len(used_fixtures)}/{len(layout_fixtures)} layout fixtures stocked)")
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
        assert "space" not in o and o["epc_count"] == 0 and o["sku_count"] == 0, f"{o['id']} leaked retail layout"
        assert o["id"] not in retail_ids, f"{o['id']} collides with a retail site"
    assert all("space" in s for s in manifest_stores), "a retail site is missing its space"
    print(f"office sites: {len(office_sites)} site-row-only (no space/zone/fixture/inventory) — OK")
    print(f"\nwrote {OUT}/chain-manifest.json + regions.json + {len(STORES)} store dirs")


if __name__ == "__main__":
    main()
