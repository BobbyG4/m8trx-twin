#!/usr/bin/env python3
"""
Build the DECATHLON CHAIN staff/org roster — HQ + 3 regional offices + 10 stores.

~200 named people, locale-appropriate names (English / French / Korean hangul+romanized),
deterministic. Reporting lines resolved via manager_id so the tree is fully traversable.
~30 accounts are status=inactive (deliberately "broken" — terminated / on-leave / deactivated)
spread across HQ and stores, so every surface gets exercised against inactive/orphaned states.

Role labels are descriptive ONLY — backend maps them to perms-v3 capabilities. We emit no
capability strings and no DB-schema assumptions.

Config : scripts/chain_config.py
Out    : reference/data/chain/staff/roster.csv
         reference/data/chain/staff/org-chart.json
"""
import csv, json, os, sys, random, unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from chain_config import (STORES, REGIONS, HQ, OFFICE_SITES, OFFICE_BY_REGION, HQ_ROLES,
                          REGIONAL_ROLES, STORE_HEADCOUNT, DEPARTMENTS, ROLE_LABELS, NAME_POOLS,
                          INACTIVE_TARGET, INACTIVE_REASONS, TENANT)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "reference/data/chain/staff")
SEED = 42
EMAIL_DOMAIN = "decathlon-demo.com"

rng = random.Random(SEED)
_counter = [0]
_emails = {}


def next_id():
    _counter[0] += 1
    return f"EMP-{_counter[0]:05d}"


def slug(s):
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode("ascii")
    return "".join(c for c in s.lower() if c.isalnum())


def make_email(given_roman, family_roman):
    base = f"{slug(given_roman)}.{slug(family_roman)}"
    n = _emails.get(base, 0) + 1
    _emails[base] = n
    local = base if n == 1 else f"{base}{n}"
    return f"{local}@{EMAIL_DOMAIN}"


def pick_name(region):
    """Return (full_name, name_local, given_roman, family_roman) for the region's locale."""
    pool = NAME_POOLS[region]
    if region == "KR":
        gh, gr = rng.choice(pool["given"])
        fh, fr = rng.choice(pool["family"])
        return f"{fr} {gr}", f"{fh}{gh}", gr, fr        # family-first (Kim Min-jun / 김민준)
    g = rng.choice(pool["given"]); f = rng.choice(pool["family"])
    full = f"{g} {f}"
    return full, full, g, f


def hire_date():
    y = rng.randint(2015, 2025); m = rng.randint(1, 12); d = rng.randint(1, 28)
    return f"{y:04d}-{m:02d}-{d:02d}"


roster = []


def add(role_key, role_label, region, home, manager_id, tz, name_region=None):
    nr = name_region or region
    full, local, gr, fr = pick_name(nr)
    p = {
        "staff_id": next_id(), "full_name": full, "name_local": local,
        "role": role_key, "role_label": role_label, "region": region,
        "home_store_id": home, "manager_id": manager_id or "", "timezone": tz,
        "email": make_email(gr, fr), "status": "active", "status_reason": "",
        "hire_date": hire_date(),
    }
    roster.append(p)
    return p


# ── 1. Global HQ (France) ─────────────────────────────────────────────────────
ceo = add("ceo", "Chief Executive Officer", "FR", HQ["id"], None, HQ["tz"])
hq_by_role = {"ceo": ceo}
for key, label in HQ_ROLES:
    if key == "ceo":
        continue
    hq_by_role[key] = add(key, label, "FR", HQ["id"], ceo["staff_id"], HQ["tz"])
coo = hq_by_role["coo"]

# ── 2. Regional offices (US / FR / KR) ────────────────────────────────────────
regional = {}   # region -> {role_key: person}
for region in ["US", "FR", "KR"]:
    office = OFFICE_BY_REGION[region]
    tz = office["tz"]            # regional staff sit in their regional office's timezone
    home = office["id"]
    regional[region] = {}
    rd = add("regional_director", "Regional Director", region, home, coo["staff_id"], tz)
    regional[region]["regional_director"] = rd
    for key, label in REGIONAL_ROLES:
        if key == "regional_director":
            continue
        regional[region][key] = add(key, label, region, home, rd["staff_id"], tz)

# ── 3. Stores ─────────────────────────────────────────────────────────────────
for store in STORES:
    region, tz, home = store["region"], store["tz"], store["id"]
    hc = STORE_HEADCOUNT[store["tier"]]
    rd = regional[region]["regional_director"]
    rlp = regional[region]["regional_lp_manager"]

    sm = add("store_manager", ROLE_LABELS["store_manager"], region, home, rd["staff_id"], tz)
    assistants = [add("assistant_manager", ROLE_LABELS["assistant_manager"], region, home,
                      sm["staff_id"], tz) for _ in range(hc["assistant_manager"])]

    def an_mgr(i=[0]):
        a = assistants[i[0] % len(assistants)] if assistants else sm
        i[0] += 1
        return a["staff_id"]

    dept_leaders = []
    for d in range(hc["department_leader"]):
        dl = add("department_leader", f"{ROLE_LABELS['department_leader']} — {DEPARTMENTS[d % len(DEPARTMENTS)]}",
                 region, home, an_mgr(), tz)
        dl["department"] = DEPARTMENTS[d % len(DEPARTMENTS)]
        dept_leaders.append(dl)

    for i in range(hc["sales_associate"]):
        mgr = dept_leaders[i % len(dept_leaders)] if dept_leaders else sm
        a = add("sales_associate", ROLE_LABELS["sales_associate"], region, home, mgr["staff_id"], tz)
        a["department"] = mgr.get("department", "")
    for _ in range(hc["cashier"]):
        add("cashier", ROLE_LABELS["cashier"], region, home, an_mgr(), tz)
    for _ in range(hc["stock_logistics"]):
        add("stock_logistics", ROLE_LABELS["stock_logistics"], region, home, an_mgr(), tz)
    for _ in range(hc["store_lp"]):
        add("store_lp", ROLE_LABELS["store_lp"], region, home, rlp["staff_id"], tz)  # LP reports to regional LP

# ── 4. Deactivate ~30 accounts (deliberately broken) ──────────────────────────
# Keep the tree resolvable: CEO, C-suite, regional directors, and store managers stay
# active. Everyone else is eligible — including mid-level managers, so some reports get
# orphaned on purpose (a real inactive-account test surface).
PROTECTED = {"ceo", "cfo", "coo", "cio", "global_merch_director", "global_lp_director",
             "global_hr_director", "global_data_lead", "regional_director", "store_manager"}
eligible = [p for p in roster if p["role"] not in PROTECTED]
rng.shuffle(eligible)
for p in eligible[:INACTIVE_TARGET]:
    p["status"] = "inactive"
    p["status_reason"] = rng.choice(INACTIVE_REASONS)

# ── write roster.csv ──────────────────────────────────────────────────────────
os.makedirs(OUT, exist_ok=True)
COLS = ["staff_id", "full_name", "name_local", "role", "role_label", "department",
        "region", "home_store_id", "manager_id", "timezone", "email",
        "status", "status_reason", "hire_date"]
with open(os.path.join(OUT, "roster.csv"), "w", newline="", encoding="utf-8") as f:
    w = csv.DictWriter(f, fieldnames=COLS); w.writeheader()
    for p in roster:
        w.writerow({k: p.get(k, "") for k in COLS})

# ── write org-chart.json (nested tree from manager_id) ────────────────────────
by_id = {p["staff_id"]: p for p in roster}
children = {}
for p in roster:
    children.setdefault(p["manager_id"], []).append(p["staff_id"])


def node(sid):
    p = by_id[sid]
    return {"staff_id": sid, "name": p["full_name"], "role": p["role_label"],
            "home": p["home_store_id"], "status": p["status"],
            "reports": [node(c) for c in children.get(sid, [])]}


tree = node(ceo["staff_id"])
with open(os.path.join(OUT, "org-chart.json"), "w", encoding="utf-8") as f:
    json.dump(tree, f, indent=2, ensure_ascii=False)

# ── write users.csv (provisioning-ready, tenant scope) ────────────────────────
# Flat view for immediate tenant-level user provisioning into M8trxDemo. Store staff
# are assigned to their site; HQ/regional staff are tenant-scoped (site blank). The
# tenant-admin (real Google mailbox) is prepended and flagged.
ALL_SITE_IDS = {s["id"] for s in STORES} | {o["id"] for o in OFFICE_SITES}
USER_COLS = ["email", "display_name", "name_local", "role", "role_label",
             "site", "region", "timezone", "status", "status_reason", "tenant_admin"]


def site_of(p):
    # every staff member binds to a site now (retail store OR office site)
    return p["home_store_id"] if p["home_store_id"] in ALL_SITE_IDS else ""


users = [{
    "email": TENANT["admin_email"], "display_name": TENANT["admin_display_name"],
    "name_local": TENANT["admin_display_name"], "role": "tenant_admin",
    "role_label": "Tenant Administrator", "site": "", "region": "",
    "timezone": TENANT["admin_timezone"], "status": "active", "status_reason": "",
    "tenant_admin": "true",
}]
for p in roster:
    users.append({
        "email": p["email"], "display_name": p["full_name"], "name_local": p["name_local"],
        "role": p["role"], "role_label": p["role_label"], "site": site_of(p),
        "region": p["region"], "timezone": p["timezone"], "status": p["status"],
        "status_reason": p["status_reason"], "tenant_admin": "false",
    })
with open(os.path.join(OUT, "users.csv"), "w", newline="", encoding="utf-8") as f:
    w = csv.DictWriter(f, fieldnames=USER_COLS); w.writeheader(); w.writerows(users)

# ── verification ──────────────────────────────────────────────────────────────
ids = {p["staff_id"] for p in roster}
orphans = [p["staff_id"] for p in roster if p["manager_id"] and p["manager_id"] not in ids]
inactive = [p for p in roster if p["status"] == "inactive"]
from collections import Counter
print(f"total staff: {len(roster)}")
print("by role:", dict(Counter(p["role"] for p in roster)))
print("by region:", dict(Counter(p["region"] for p in roster)))
print(f"\ninactive accounts: {len(inactive)} (target {INACTIVE_TARGET})")
print("inactive reasons:", dict(Counter(p["status_reason"] for p in inactive)))
print("inactive spread (home):", dict(Counter(p["home_store_id"] for p in inactive)))
assert not orphans, f"DANGLING manager_id: {orphans}"
print("\nmanager_id integrity: OK (every manager resolves; tree rooted at CEO)")
# locale spot-check
for region in ["US", "FR", "KR"]:
    ex = next(p for p in roster if p["region"] == region and p["role"] == "sales_associate")
    print(f"  {region} sample: {ex['full_name']} / {ex['name_local']}  <{ex['email']}>")
site_assigned = sum(1 for u in users if u["site"])
tenant_scoped = sum(1 for u in users if not u["site"])
emails = [u["email"] for u in users]
assert len(emails) == len(set(emails)), "DUPLICATE user emails"
admins = [u["email"] for u in users if u["tenant_admin"] == "true"]
print(f"\nusers.csv: {len(users)} users  (site-assigned: {site_assigned}, tenant-scoped: {tenant_scoped})")
print(f"email uniqueness: OK ({len(set(emails))} distinct)")
print(f"tenant-admin: {admins}")
print(f"\nwrote {OUT}/roster.csv + org-chart.json + users.csv")
