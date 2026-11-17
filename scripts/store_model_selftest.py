#!/usr/bin/env python3
"""
Offline self-test for the Phase 1 store model (Denver): asserts the brief's section 2.6 list against the committed
stores/dec-us-denver/model/ files, rebuilds twice to prove byte-identical output, and checks that no file the live
box reads differs from origin/main. No network. Python 3 stdlib only.

    python3 scripts/store_model_selftest.py [--store dec-us-denver]
"""
import argparse
import csv
import glob
import json
import os
import subprocess
import sys
import tempfile
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_store_model as B  # noqa: E402

ROOT = B.ROOT
CHECKS = 0
FAILS = []


def ok(cond, msg):
    global CHECKS
    CHECKS += 1
    if not cond:
        FAILS.append(msg)
        print("  FAIL: " + msg)


def read(path):
    with open(path, encoding="utf-8", newline="") as f:
        return f.read()


def rows(path):
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def protected_files():
    pats = ["reference/data/chain/stores/*/assortment.csv", "reference/data/chain/stores/*/epcs.csv",
            "reference/data/chain/stores/*/layout.json", "reference/data/chain/stores/*/planogram.json",
            "reference/data/chain/stores/*/fixture_ids.csv", "reference/data/chain/keys-*.csv",
            "reference/data/store-operating-model.json"]
    out = []
    for p in pats:
        out += [os.path.relpath(x, ROOT) for x in glob.glob(os.path.join(ROOT, p))]
    return sorted(out)


def git(*args):
    return subprocess.run(["git", "-C", ROOT] + list(args), capture_output=True, text=True)


def all_slugs():
    return sorted(d for d in os.listdir(os.path.join(B.CHAIN, "stores")) if os.path.exists(os.path.join(B.CHAIN, "stores", d, "layout.json")))


def check_store(slug):
    n0, f0 = CHECKS, len(FAILS)
    sdir = os.path.join(B.CHAIN, "stores", slug)
    mdir = os.path.join(sdir, "model")
    layout = json.load(open(os.path.join(sdir, "layout.json"), encoding="utf-8"))
    assort = rows(os.path.join(sdir, "assortment.csv"))
    keys = {r["fixture_code"]: r for r in rows(B.KEYS_CSV) if r["site_slug"] == slug}
    fx = json.load(open(os.path.join(mdir, "fixtures.json"), encoding="utf-8"))
    pg = json.load(open(os.path.join(mdir, "planogram.json"), encoding="utf-8"))
    prof = json.load(open(os.path.join(mdir, "profile.json"), encoding="utf-8"))
    dem = rows(os.path.join(mdir, "demand.csv"))
    stock = rows(os.path.join(mdir, "stock-plan.csv"))
    lines = pg["lines"]
    fixtures = fx["fixtures"]

    # ---- fixtures.json covers every floor fixture zone; service fixtures are the only empties
    floor = {z["code"]: z for sp in layout["spaces"] if sp["space_type"] == "sales_floor" for z in sp["zones"] if z.get("zone_type") == "fixture"}
    by_fx = defaultdict(list)
    for l in lines:
        by_fx[l["fixture_code"]].append(l)
    for c, z in floor.items():
        ok(c in fixtures, "fixture %s missing from fixtures.json" % c)
        ok(fixtures[c]["zone_id"] == keys[c]["zone_id"] and fixtures[c]["zone_name"] == keys[c]["zone_name"], "%s zone id/name != keys" % c)
    empties = sorted(c for c in floor if c not in by_fx)
    service = sorted(c for c, z in floor.items() if z["fixture_category"] in ("footwear_bench", "gait_treadmill"))
    ok(empties == service, "empty floor fixtures %s != deliberate service fixtures %s" % (empties, service))
    for c in floor:
        if c in service:
            ok(fixtures[c].get("deliberate_empty") is True, "%s not flagged deliberate_empty" % c)
            continue
        cap = fixtures[c]["capacity_units"]
        req = sum(l["required_qty"] for l in by_fx[c])
        ok(len(by_fx[c]) >= 1, "%s has no planogram line" % c)
        ok(cap > 0 and req / float(cap) >= 0.70, "%s fill %.1f %% < 70 %%" % (c, 100.0 * req / max(cap, 1)))
    # ---- lines: capacity, facings, keying, contiguity
    lvl = {}
    for c, d in fixtures.items():
        for lv in d.get("levels", []):
            lvl[(c, lv["level"])] = lv
    seen = set()
    used = defaultdict(int)
    style_of = {r["ean"]: (r["handle"], r["color"]) for r in assort}
    style_eans = defaultdict(set)
    for e, s in style_of.items():
        style_eans[s].add(e)
    groups = defaultdict(list)
    for l in lines:
        key = (l["fixture_code"], l["level"], l["position_sequence"])
        ok(key not in seen, "duplicate fixture-level-position %s" % (key,))
        seen.add(key)
        lv = lvl.get((l["fixture_code"], l["level"]))
        ok(lv is not None, "line on unknown level %s" % (key,))
        if lv is None:
            continue
        ok(1 <= l["facings"] <= B.MAX_FACINGS_PER_EAN, "facings %d outside 1..%d at %s" % (l["facings"], B.MAX_FACINGS_PER_EAN, key))
        if "spill_from" in l:
            ok(l["spill_from"] == l["department"] and floor[l["fixture_code"]].get("department") in B.LEAN_DEPTS,
               "spill line wrongly tagged at %s" % (key,))
        ok(l["required_qty"] <= l["facings"] * lv["units_per_facing"], "line over capacity at %s" % (key,))
        ok(l["form"] == lv["form"] and l["display_level"] == lv["display_level"], "form/display mismatch at %s" % (key,))
        used[(l["fixture_code"], l["level"])] += l["facings"]
        ok(l["ean"] in style_of, "unknown ean %s" % l["ean"])
        groups[(style_of[l["ean"]], l["block"])].append(l)
        ok(l["zone_id"] == keys[l["fixture_code"]]["zone_id"] and l["zone_name"] == keys[l["fixture_code"]]["zone_name"],
           "line zone id/name != keys at %s" % (key,))
    for k, v in used.items():
        ok(v <= lvl[k]["facings"], "level %s holds %d facings > %d" % (k, v, lvl[k]["facings"]))
    for (style, block), ls in groups.items():
        ok(len({(l["fixture_code"], l["level"]) for l in ls}) == 1, "style %s block %d spans more than one level" % (style, block))
        pos = sorted(l["position_sequence"] for l in ls)
        ok(pos == list(range(pos[0], pos[0] + len(pos))), "style %s block %d not contiguous" % (style, block))
        ok({l["ean"] for l in ls} == style_eans[style], "style %s block %d is not the whole size run" % (style, block))
    # ---- EAS tagging in cases
    price = {r["ean"]: float(r["price_usd"]) for r in assort}
    cat = {r["ean"]: r["category"] for r in assort}
    for l in lines:
        if floor[l["fixture_code"]]["fixture_category"] == "gps_case":
            ok(B.eas_tagged(price[l["ean"]], cat[l["ean"]]), "untagged EAN %s in a GPS case" % l["ean"])
    # ---- demand
    ok(len(dem) == len(assort) and {r["ean"] for r in dem} == set(style_of), "demand.csv EAN set != assortment")
    ok(all(float(r["share"]) > 0 for r in dem), "a share <= 0")
    ok(abs(sum(float(r["share"]) for r in dem) - 1.0) <= 1e-9, "shares do not sum to 1")
    opm = json.load(open(B.OPMODEL, encoding="utf-8"))
    vis, conv, upt = opm["traffic"]["visitors_per_day"], opm["conversion"]["overall_rate"]["value"], opm["basket"]["units_per_txn"]["value"]
    area = prof["area_sqm"]
    mult = prof["location_factor"] * (area / float(next(r["area_sqm"] for r in rows(B.KEYS_CSV) if r["site_slug"] == B.REFERENCE_STORE)))
    wk = sum(float(r["units_per_day_weekday"]) for r in dem)
    we = sum(float(r["units_per_day_weekend"]) for r in dem)
    ok(abs(wk - vis["weekday"] * mult * conv * upt) < 0.05, "weekday total %.3f != visitors x conversion x upt" % wk)
    ok(abs(we - vis["weekend"] * mult * conv * upt) < 0.05, "weekend total %.3f != visitors x conversion x upt" % we)
    if slug == B.REFERENCE_STORE:
        ok(abs(wk - 411.4) < 0.05 and abs(we - 701.8) < 0.05, "Denver totals != today's 411.4 / 701.8")
        ok(prof["location_factor"] == 1.0 and all(h == "10:00-21:00" for h in prof["hours"].values()), "Denver profile != today's")
    sh = sorted((float(r["share"]) for r in dem), reverse=True)
    head = sum(sh[: len(sh) // 10])
    ok(0.40 <= head <= 0.55, "head (top 10 %% of EANs) carries %.1f %%, outside 40-55 %%" % (100 * head))
    # ---- stock plan vs planogram
    req = defaultdict(lambda: [0, 0])
    for l in lines:
        r = req[(l["ean"], l["fixture_code"])]
        r[0] += l["required_qty"]
        r[1] += 1
    flo = defaultdict(int)
    for r in stock:
        if r["kind"] == "floor":
            flo[(r["ean"], r["zone_code"])] += int(r["qty"])
            ok(r["zone_id"] == keys[r["zone_code"]]["zone_id"], "stock-plan zone_id != keys for %s" % r["zone_code"])
    for k, (q, n) in req.items():
        got = flo.get(k, 0)
        ok(max(0, q - 2 * n) <= got <= q + n, "floor stock %s %d outside [%d,%d]" % (k, got, max(0, q - 2 * n), q + n))
    ok(set(flo) <= set(req), "floor stock on a fixture the planogram does not list")
    back = defaultdict(int)
    for r in stock:
        if r["kind"] == "backroom":
            back[r["ean"]] += int(r["qty"])
            ok(r["zone_code"].startswith("BR-"), "backroom stock in %s" % r["zone_code"])
    onfloor = {l["ean"] for l in lines}
    pack = {r["ean"]: (B.CASE_PACK_BIKE if r["product_type"] in B.BIKE_TYPES else B.CASE_PACK) for r in assort}
    for e in onfloor:
        ok(back.get(e, 0) >= pack[e], "on-floor EAN %s has less than a case in the back room" % e)
    for e in set(style_of) - onfloor:
        ok(back.get(e, 0) >= pack[e], "off-floor EAN %s has no back-room stock" % e)
    rack_cap = defaultdict(int)
    for r in stock:
        if r["kind"] == "backroom":
            rack_cap[r["zone_code"]] += int(r["qty"])
    for rk, q in rack_cap.items():
        ok(q <= fixtures[rk]["capacity_units"], "back-room rack %s holds %d > capacity %d" % (rk, q, fixtures[rk]["capacity_units"]))
    # ---- determinism: two rebuilds, byte-identical to each other and to the committed files
    with tempfile.TemporaryDirectory() as t1, tempfile.TemporaryDirectory() as t2:
        o1, p1 = B.build(slug, t1)
        o2, _ = B.build(slug, t2)
        ok(o1 == o2, "two runs differ")
        for name, text in o1.items():
            ok(read(os.path.join(mdir, name)) == text, "committed %s is not what the builder produces" % name)
        ok(read(B.PROFILES) == B.jdump(p1), "committed store-profiles.json is not what the builder produces")
    print("  %-18s %d checks, %d failed" % (slug, CHECKS - n0, len(FAILS) - f0))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--store", default=None, help="one store (default: all ten)")
    args = ap.parse_args()
    slugs = [args.store] if args.store else all_slugs()
    if not args.store:
        ok(len(slugs) == 10, "expected ten stores, found %d" % len(slugs))
    for slug in slugs:
        check_store(slug)
    pj = json.load(open(B.PROFILES, encoding="utf-8"))
    for slug in slugs:
        prof = json.load(open(os.path.join(B.CHAIN, "stores", slug, "model", "profile.json"), encoding="utf-8"))
        ok(prof["store_id"] == slug and {k: v for k, v in prof.items() if k != "store_id"} == pj["stores"][slug], "profile.json != the store's block")
    ok(len(pj["stores"]) == 10, "store-profiles.json should carry all ten stores")
    for s_, p_ in pj["stores"].items():
        ok(p_["delivery_lead_min"] == (60 if p_["area_sqm"] >= 500 else 30), "delivery lead wrong for %s" % s_)
        ok(p_["provisional"] is True and p_["location_factor"] == 1.0, "profile %s not provisional/1.0" % s_)
    # ---- the nine neutral behavior files are what the generator writes
    ok(subprocess.run([sys.executable, os.path.join(ROOT, "scripts/build_store_behavior.py"), "--check"]).returncode == 0,
       "reference/data/behavior/<slug>.json is not what build_store_behavior.py writes")
    # ---- chain summary doc is what the builder writes from the committed model files
    slugs = all_slugs()
    ok(read(B.SUMMARY_MD) == B.chain_summary(slugs), "STORE-MODEL-SUMMARY.md is not what the builder produces")
    # ---- no live file changed vs origin/main
    base = git("rev-parse", "--verify", "-q", "origin/main")
    if base.returncode == 0:
        files = protected_files()
        d1 = git("diff", "--name-only", "origin/main", "HEAD", "--", *files).stdout.split()
        d2 = git("diff", "--name-only", "HEAD", "--", *files).stdout.split()
        ok(not d1 and not d2, "files the live box reads differ from origin/main: %s" % (d1 + d2))
    else:
        print("  note: origin/main not found, protected-file diff skipped")
    print("store-model self-test: %d checks, %d failed (%s)" % (CHECKS, len(FAILS), ", ".join(slugs)))
    if FAILS:
        sys.exit(1)


if __name__ == "__main__":
    main()
