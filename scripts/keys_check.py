#!/usr/bin/env python3
"""keys_check.py — dump every key a generated history directory uses and compare it against the
Coordinator's read-only key export (BRIEF-TWIN-HISTORY-GENERATOR-2026-10-11 § 2).

Migration 259 refuses the WHOLE load if a single key fails to resolve, so this runs before hand-over:

    python3 scripts/keys_check.py [out/history-2026-10] [reference/data/chain/keys-2026-10-11.csv]

Checks (offline, read-only):
  * site slugs                      — every file, against the export's site_slug
  * department zone names           — commerce/traffic department files, against REGION zones of that store
  * fixture codes                   — behavioral file, against FIXTURE zones' code of that store
  * task zone keys                  — must match exactly ONE zone of the store by name or code (mig 259
                                      LEFT JOINs name OR code; two matches would duplicate the task row)
  * alarm skus (EANs)               — against the store's own assortment.csv
Writes the dump to <out>/keys_used.csv (not part of the MANIFEST) and exits 1 on any unresolved key.
"""
import csv
import sys
from collections import defaultdict
from pathlib import Path

out = Path(sys.argv[1] if len(sys.argv) > 1 else "out/history-2026-10")
keys_path = Path(sys.argv[2] if len(sys.argv) > 2 else "reference/data/chain/keys-2026-10-11.csv")


def rows(name):
    with open(out / name, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


export = list(csv.DictReader(open(keys_path, newline="", encoding="utf-8")))
slugs = {r["site_slug"] for r in export}
regions = defaultdict(set)
fixtures = defaultdict(set)
zone_keys = defaultdict(list)
for r in export:
    if r["zone_type"] == "region":
        regions[r["site_slug"]].add(r["zone_name"])
    if r["zone_type"] == "fixture":
        fixtures[r["site_slug"]].add(r["fixture_code"])
    zone_keys[r["site_slug"]].append((r["zone_name"], r["fixture_code"]))

used = []  # (kind, site_slug, key, resolved)
bad = 0


def note(kind, slug, key, ok):
    global bad
    used.append((kind, slug, key, "ok" if ok else "UNRESOLVED"))
    if not ok:
        bad += 1


files = [
    "commerce_store_hourly.csv", "commerce_department_daily.csv", "traffic_store_hourly.csv", "traffic_department_daily.csv",
    "behavioral_fixture_daily.csv", "compliance_weekly.csv", "tasks.csv", "alarms.csv", "stocktakes.csv", "kpi_targets.csv",
]
seen = set()
for f in files:
    for r in rows(f):
        s = r["site_slug"]
        if ("site", s) not in seen:
            seen.add(("site", s))
            note("site_slug", s, s, s in slugs)

for f in ["commerce_department_daily.csv", "traffic_department_daily.csv"]:
    for r in rows(f):
        k = ("dept", r["site_slug"], r["zone_name"])
        if k not in seen:
            seen.add(k)
            note("department_zone_name", r["site_slug"], r["zone_name"], r["zone_name"] in regions[r["site_slug"]])

for r in rows("behavioral_fixture_daily.csv"):
    k = ("fix", r["site_slug"], r["fixture_code"])
    if k not in seen:
        seen.add(k)
        note("fixture_code", r["site_slug"], r["fixture_code"], r["fixture_code"] in fixtures[r["site_slug"]])

for r in rows("tasks.csv"):
    key = r["zone_name_or_code"]
    if not key:
        continue
    k = ("task", r["site_slug"], key)
    if k not in seen:
        seen.add(k)
        matches = sum(1 for (name, code) in zone_keys[r["site_slug"]] if key in (name, code))
        note("task_zone_key", r["site_slug"], key, matches == 1)

assortments = {}
for r in rows("alarms.csv"):
    s = r["site_slug"]
    if s not in assortments:
        with open(Path("reference/data/chain/stores") / s / "assortment.csv", newline="", encoding="utf-8") as f:
            assortments[s] = {a["ean"] for a in csv.DictReader(f)}
    k = ("sku", s, r["sku"])
    if k not in seen:
        seen.add(k)
        note("alarm_sku", s, r["sku"], r["sku"] in assortments[s])

with open(out / "keys_used.csv", "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["kind", "site_slug", "key", "resolved"])
    w.writerows(sorted(used))

by_kind = defaultdict(lambda: [0, 0])
for kind, _, _, ok in used:
    by_kind[kind][0] += 1
    by_kind[kind][1] += ok != "ok"
print(f"keys_check: {out} against {keys_path}")
for kind, (n, b) in sorted(by_kind.items()):
    print(f"  {kind:22s} {n:6d} distinct  {b:3d} unresolved")
print(f"  dump → {out / 'keys_used.csv'}")
print("RESULT:", "ALL KEYS RESOLVE" if bad == 0 else f"{bad} UNRESOLVED KEY(S) — mig 259 would refuse the load")
sys.exit(1 if bad else 0)
