#!/usr/bin/env python3
"""
Phase 1 store model (Denver cut) - the store as files: profile, fixture kits, demand, planogram v2, stock to plan.

    python3 scripts/build_store_model.py --store dec-us-denver [--out DIR]

Spec : status/briefs/BRIEF-PHASE1-DENVER-STORE-MODEL-2026-11-17.md
Reads: stores/<slug>/{layout.json,assortment.csv}, chain/keys-2026-10-11.csv, store-operating-model.json (read only)
Writes (and ONLY these): stores/<slug>/model/{profile.json,fixtures.json,demand.csv,planogram.json,stock-plan.csv,SUMMARY.md}
         and reference/data/store-profiles.json.
Nothing the live box reads is touched. Offline. Python 3 stdlib only. Deterministic: same inputs -> identical bytes.

Keyed by EAN (the unit of stock). A STYLE here is (handle, colour): one product in one colour, i.e. one size run.
(item_cd is per-EAN in the assortment - 2,581 distinct over 2,586 rows - so it cannot be the style key.)
"""
import argparse
import csv
import hashlib
import itertools
import json
import math
import os
import random
import sys
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import size_curve  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHAIN = os.path.join(ROOT, "reference/data/chain")
KEYS_CSV = os.path.join(CHAIN, "keys-2026-10-11.csv")
OPMODEL = os.path.join(ROOT, "reference/data/store-operating-model.json")
PROFILES = os.path.join(ROOT, "reference/data/store-profiles.json")
REFERENCE_STORE = "dec-us-denver"       # StoryCalendar's area-scale reference (HistoryGenerator.REFERENCE_STORE)

KIT_VERSION = "kit-v1"
FORMAT, VERSION = "m8trx_standard", "v2"

# ============================== KNOBS (every number the brief left to the builder) ==============================
# --- profile (D3 estimates, Bob 2026-11-17; per-store hours and location factors are in HOURS / LOCATION_FACTORS) ---
DEFAULT_LOCATION_FACTOR = 1.0           # the chain default (Denver) footfall per m2
LARGE_STORE_SQM = 450.0                 # delivery lead: 60 min at/above, 30 below (D3, Bob 2026-11-17; was 500)
LEAD_LARGE_MIN, LEAD_SMALL_MIN = 60, 30
# --- demand ---
DEMAND_DATE = (2026, 11, 17)            # the day the seasonal department weights are read for (StoryCalendar anchors)
ZIPF_EXP = 1.1                          # long-tail exponent within department x category (brief: ~1.1)
ZIPF_OFFSET = 40.0                      # rank offset k+offset: flattens the very top so the head is not over-concentrated
PRICE_ELASTICITY = -0.5                 # style weight ~ price^-0.5 (cheaper lines sell more units)
CAT_FULL_STYLES = 10                    # a category in a department carries its full unit-mix weight from 10 styles; fewer scales it down
SIZE_CURVE_TOTAL = 1000                 # units fed to size_curve.allocate to read the size curve as shares
SIZE_FLOOR = 0.5                        # added to every size allocation so no EAN has a zero share
# unit mix by assortment category (model basket.category_unit_mix: footwear .18, apparel .27, outdoor .16,
# accessories_other .13 split accessories/bag_pack); renormalised over the categories a department carries
CATEGORY_UNIT_WEIGHT = {"footwear": 0.18, "apparel": 0.27, "outdoor": 0.16, "accessories": 0.09, "bag_pack": 0.04}
# --- geometry / capacity (the knobs the brief asked to be constants) ---
FACE_HEIGHT = 60                        # height units in one gondola face (5 shelf levels x 12)
FACES_PER_DEPT = 14                     # gondola faces per department (7 front + 7 back)
# lc = level class: (form, facings per metre of level width, units per facing, height units)
LEVEL_CLASS = {
    "shoe": ("shelf", 6.0, 3, 12),      # brief says ~4/m; at 4/m a size run of 9+ (40 of 67 footwear styles) cannot sit on
                                        # one 2 m level at one facing per EAN, so shoe boxes are 6/m (16 cm end-on)
    "fold": ("shelf", 5.0, 6, 12),      # folded apparel; brief ~4/m, 5/m so 8-10 size runs still fit one level
    "hang": ("rail", 25.0, 2, 30),      # one hanger slot per 4 cm (25/m); 2 garments per slot-facing. Brief's literal
                                        # 10/facing would put 250 garments on a metre of rail, so it is 2 here
    "peg": ("pegboard", 8.0, 6, 10),    # 8 hooks/m, 6 units a hook
    "bulk": ("shelf", 2.0, 2, 12),      # bulky (outdoor kit, bags); lowest level of a face is `floor`
    "bike": ("floor", 5.0, 1, 60),      # floor level per face / bay; 5/m = bikes parked 3 across x 2 deep, so a frame-size run fits
}
TABLE_TOP = ("table_top", 4.0, 8, 12)   # promo island table
BIN = ("bin", 3.0, 20, 12)              # promo island / checkout bin
BACKROOM = ("shelf", 4.0, 24, 12)       # back-room rack: 4 carton positions a metre, 24 units each
LC_AFFINITY = ["bike", "bulk", "shoe", "fold", "hang", "peg"]    # bottom -> top order faces are dealt in
WALL_KIT = {                            # (levels, height each) of a wall bay by level class
    "shoe": (6, 10), "fold": (6, 10), "peg": (6, 10), "hang": (2, 30), "bike": (1, 60), "bulk": (5, 12),
}
WALL_LC_BY_DEPT = {"running": "shoe", "hike_camp": "shoe", "climb": "hang", "snow": "hang", "cycling": "bike", "general": "bulk",
                   "water": "bulk"}
MAX_FACINGS_PER_EAN = 6                # no EAN line gets more than 6 facings anywhere (also the deepening cap: 6x the minimum need)
DEEPEN_CAP = MAX_FACINGS_PER_EAN
FILL_TARGET = 0.88                      # required_qty = facings x capacity x 0.88 (every fixture >= 70 %)
LOOKAHEAD = 40                          # styles scanned for one that still fits the open width of a level
GPS_CASE_LEVELS = 3
IMPULSE_LEVELS = 4                      # pegboard rows on the impulse rack
IMPULSE_MAX_PRICE = 15.0                # checkout/impulse: low-price accessories
ROUND_RACK_LEVELS = 1
ISLAND_LEVELS = [TABLE_TOP, BIN]        # table_top + bin
BR_LEVELS = 6                           # back-room rack: shelf x 6
# --- stock to plan ---
DEVIATION_EXACT, DEVIATION_SHORT = 0.92, 0.06     # 92 % exact, 6 % short by 1-2, remaining 2 % over by 1
SPILL_FROM = "hike_camp"                # the department with the largest off-floor demand (chosen per store in build); it spills onto the lean departments' unused fixtures
COVER_DAYS = 7                          # back-room cover
OFFFLOOR_EXTRA_DAYS = 7                 # an off-floor EAN has no floor stock: its back room carries the floor's days too
CASE_PACK = 6                           # back room is rounded up to cases of 6 ...
CASE_PACK_BIKE = 1                      # ... except bikes, which ship one to a box
SEED_SALT = "store-model-v1"

HANGING_TYPES = {"Jacket", "Down jacket", "Parka", "Padded Jacket", "Rain Jacket", "Rain Poncho", "Windbreaker", "Ski suit",
                 "Softshell", "3 in 1 jacket", "Overalls", "Ski pants", "Trousers/pants", "Zip-Off Pants", "Fleece"}
BIKE_TYPES = {"Road bike", "Gravel bike", "Mountain bike"}

# StoryCalendar (history/StoryCalendar.kt) departmentAnchors and SALES_WEIGHT, mirrored
SEASON_ANCHORS = {
    "snow": [1.80, 1.60, 1.00, 0.50, 0.30, 0.30, 0.30, 0.30, 0.50, 0.90, 1.40, 1.80],
    "cycling": [0.60, 0.70, 1.00, 1.30, 1.40, 1.40, 1.30, 1.10, 1.00, 0.80, 0.70, 0.70],
    "hike_camp": [0.85, 0.90, 0.97, 1.05, 1.12, 1.18, 1.20, 1.12, 1.00, 0.95, 0.90, 0.88],
    "running": [1.20, 1.05, 1.15, 1.15, 1.10, 1.00, 0.90, 0.95, 1.15, 1.10, 1.00, 1.00],
    "water": [0.60, 0.60, 0.70, 0.90, 1.20, 1.60, 1.70, 1.60, 1.00, 0.70, 0.60, 0.60],
    "climb": [0.66, 0.65, 0.64, 0.63, 0.62, 0.61, 0.60, 0.60, 0.59, 0.58, 0.58, 0.58],
}
SALES_WEIGHT = {"hike_camp": 27.0, "running": 18.0, "cycling": 16.0, "snow": 11.0, "climb": 5.0, "water": 2.0}
SALES_CAP = 0.34
ALL_DEPTS = ["hike_camp", "running", "climb", "snow", "cycling", "water", "general"]
DEPT_ORDER = list(ALL_DEPTS)            # narrowed in place to the departments a store carries at the start of build()
LEAN_DEPTS = ("general", "water")       # departments sized by their own demand; the fixtures they do not need are spillover room


# ===================================================== helpers =====================================================
def sha(b):
    return hashlib.sha256(b).hexdigest()


def seed_of(*parts):
    return int(hashlib.sha256(":".join(str(p) for p in parts).encode()).hexdigest(), 16) % (2 ** 31)


def jdump(o):
    return json.dumps(o, indent=2, ensure_ascii=False, sort_keys=False) + "\n"


def read_csv(path):
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def csv_text(cols, rows):
    import io
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\n")
    w.writerow(cols)
    for r in rows:
        w.writerow(r)
    return buf.getvalue()


def month_interp(anchors, y, m, d):
    """StoryCalendar.interpolateMonthly: anchor i sits on the 15th of month i+1."""
    import datetime
    date = datetime.date(y, m, d)
    mid = datetime.date(y, m, 15)

    def add_month(dt, k):
        mm = dt.month - 1 + k
        return datetime.date(dt.year + mm // 12, mm % 12 + 1, 15)
    a, b = (add_month(mid, -1), mid) if date < mid else (mid, add_month(mid, 1))
    t = (date - a).days / (b - a).days
    return anchors[a.month - 1] + (anchors[b.month - 1] - anchors[a.month - 1]) * t


def season_weight(dept):
    a = SEASON_ANCHORS.get(dept)
    return month_interp(a, *DEMAND_DATE) if a else 1.0


def dept_sales_targets(counts):
    """StoryCalendar.departmentSalesTargets."""
    def gw(n):
        return 8.0 if n < 100 else 18.0
    raw = {d: (gw(n) if d == "general" else SALES_WEIGHT.get(d, 5.0)) for d, n in counts.items()}
    cap = 1.0 if len(raw) == 1 else (0.5 if len(raw) == 2 else SALES_CAP)
    share, free, budget = {}, set(raw), 1.0
    while free:
        tot = sum(raw[d] for d in free)
        over = [d for d in free if raw[d] / tot * budget > cap + 1e-9]
        if not over:
            for d in free:
                share[d] = raw[d] / tot * budget
            break
        for d in over:
            share[d] = cap
        budget -= cap * len(over)
        free -= set(over)
    return share


def size_ord(r):
    n = size_curve.normalize_size(r["size_us"], r["category"])
    return (n["ordinal"] if n["ordinal"] is not None else 1e9, r["size_us"], r["ean"])


def lc_of(r):
    c = r["category"]
    if c == "footwear":
        return "shoe"
    if c == "apparel":
        return "hang" if r["product_type"] in HANGING_TYPES else "fold"
    if c == "accessories":
        return "peg"
    if c == "outdoor":
        return "bike" if r["product_type"] in BIKE_TYPES else "bulk"
    return "bulk"       # bag_pack


def eas_tagged(price, category):
    """layer2/EasTagging.kt: price >= 150 and category != outdoor."""
    return price >= 150.0 and category.lower() != "outdoor"


def display_of(center_frac):
    if center_frac >= 0.85:
        return "top"
    if center_frac >= 0.45:
        return "eye"
    if center_frac >= 0.20:
        return "reach"
    return "stoop"


# ===================================================== profile =====================================================
def load_keys():
    keys = defaultdict(dict)            # slug -> fixture_code -> row
    meta = {}
    for r in read_csv(KEYS_CSV):
        keys[r["site_slug"]][r["fixture_code"]] = r
        meta[r["site_slug"]] = {"timezone": r["timezone"], "area_sqm": float(r["area_sqm"])}
    return keys, meta


DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
PROFILE_SOURCE = "twin estimate, Bob 2026-11-17: best guess from normal retail practice per city and store size"
CLOSED_SUNDAY_SATURDAY_LIFT = 1.10      # Saturday visitors x this where the store is closed on Sunday


def _week(mon_sat, sun):
    return {d: (mon_sat if d != "sun" else sun) for d in DAYS}


# D3 estimates (Bob 2026-11-17), store-local hours per weekday ("closed" = shut that weekday) and footfall per m2 against Denver
HOURS = {
    "dec-us-nyc": _week("10:00-21:00", "11:00-20:00"),
    "dec-us-denver": _week("10:00-21:00", "11:00-18:00"),
    "dec-us-sf": _week("10:00-20:00", "11:00-19:00"),
    "dec-fr-paris": _week("09:30-20:30", "10:00-19:00"),
    "dec-fr-lyon": _week("09:30-20:00", "closed"),
    "dec-fr-lille": _week("09:30-20:00", "closed"),
    "dec-fr-bordeaux": _week("09:30-20:00", "closed"),
    "dec-fr-marseille": _week("09:30-20:00", "closed"),
    "dec-kr-seoul": _week("10:30-22:00", "10:30-22:00"),
    "dec-kr-busan": _week("10:30-21:30", "10:30-21:30"),
}
LOCATION_FACTORS = {
    "dec-us-nyc": 1.8, "dec-fr-paris": 1.6, "dec-kr-seoul": 1.5, "dec-us-sf": 1.1, "dec-us-denver": 1.0, "dec-fr-lyon": 1.1,
    "dec-fr-lille": 1.0, "dec-kr-busan": 1.1, "dec-fr-bordeaux": 0.9, "dec-fr-marseille": 0.85,
}


def build_profiles(meta):
    default = {
        "hours": dict(HOURS["dec-us-denver"]),
        "location_factor": DEFAULT_LOCATION_FACTOR,
        "delivery_lead_rule": {"area_sqm_at_least": LARGE_STORE_SQM, "lead_min": LEAD_LARGE_MIN, "else_lead_min": LEAD_SMALL_MIN},
        "closed_sunday_saturday_lift": CLOSED_SUNDAY_SATURDAY_LIFT,
        "provisional": False,
        "source": PROFILE_SOURCE,
    }
    stores = {}
    for slug in sorted(meta):
        m = meta[slug]
        hours = dict(HOURS[slug])
        stores[slug] = {
            "timezone": m["timezone"],
            "area_sqm": m["area_sqm"],
            "hours": hours,
            "location_factor": LOCATION_FACTORS[slug],
            "delivery_lead_min": LEAD_LARGE_MIN if m["area_sqm"] >= LARGE_STORE_SQM else LEAD_SMALL_MIN,
            "saturday_lift": CLOSED_SUNDAY_SATURDAY_LIFT if hours["sun"] == "closed" else 1.0,
            "provisional": False,
            "source": PROFILE_SOURCE,
        }
    return {"schema": "store-profiles-v1", "provisional": False, "chain_default": default, "stores": stores}


# ===================================================== demand ======================================================
class Style:
    __slots__ = ("id", "dept", "cat", "lc", "eans", "demand", "price", "tagged", "run")

    def __init__(self, sid, dept, cat, lc, eans):
        self.id, self.dept, self.cat, self.lc, self.eans = sid, dept, cat, lc, eans
        self.demand = 0.0
        self.price = min(e["price"] for e in eans)
        self.tagged = all(eas_tagged(e["price"], cat) for e in eans)
        self.run = len(eans)


def build_demand(slug, rows, profile, opm):
    seed = seed_of(SEED_SALT, slug)
    styles_by_id = defaultdict(list)
    for r in rows:
        styles_by_id[(r["handle"], r["color"])].append(r)
    styles = []
    for (h, col), rs in sorted(styles_by_id.items()):
        rs = sorted(rs, key=size_ord)
        eans = [{"ean": r["ean"], "item_cd": r["item_cd"], "price": float(r["price_usd"]), "size": r["size_us"], "row": r} for r in rs]
        r0 = rs[0]
        styles.append(Style(f"{h}|{col}", r0["department"], r0["category"], lc_of(r0), eans))

    # EAN weight within department: category share x zipf(style) x price^-0.5 (normalised in the group) x size share
    by_group = defaultdict(list)
    for s in styles:
        by_group[(s.dept, s.cat)].append(s)
    cats_in_dept = defaultdict(set)
    for (d, c) in by_group:
        cats_in_dept[d].add(c)
    w = {}
    for (d, c), grp in sorted(by_group.items()):
        rng = random.Random(seed_of(seed, d, c))
        grp = sorted(grp, key=lambda s: s.id)
        rng.shuffle(grp)
        raw = []
        for k, s in enumerate(grp, start=1):
            mean_p = sum(e["price"] for e in s.eans) / len(s.eans)
            raw.append((k + ZIPF_OFFSET) ** (-ZIPF_EXP) * mean_p ** PRICE_ELASTICITY)
        tot = sum(raw)
        def cw(x):
            return CATEGORY_UNIT_WEIGHT[x] * min(1.0, len(by_group[(d, x)]) / CAT_FULL_STYLES)
        cat_share = cw(c) / sum(cw(x) for x in cats_in_dept[d])
        for s, x in zip(grp, raw):
            srng = random.Random(seed_of(seed, "size", s.id))
            alloc = size_curve.allocate([{"ean": e["ean"], "size_us": e["size"]} for e in s.eans], SIZE_CURVE_TOTAL, c, srng)
            sz = {e["ean"]: alloc.get(e["ean"], 0) + SIZE_FLOOR for e in s.eans}
            zs = sum(sz.values())
            for e in s.eans:
                w[e["ean"]] = cat_share * x / tot * sz[e["ean"]] / zs
    # department: value share target x seasonal weight -> units via demand-weighted mean price
    counts = Counter(s.dept for s in styles for _ in s.eans)
    targets = dept_sales_targets(counts)
    seas = {d: season_weight(d) for d in counts}
    val = {d: targets[d] * seas[d] for d in counts}
    vt = sum(val.values())
    val = {d: v / vt for d, v in val.items()}
    pbar = {}
    for d in counts:
        es = [e for s in styles if s.dept == d for e in s.eans]
        sw = sum(w[e["ean"]] for e in es)
        pbar[d] = sum(w[e["ean"]] * e["price"] for e in es) / sw
    units_raw = {d: val[d] / pbar[d] for d in counts}
    ut = sum(units_raw.values())
    dept_units = {d: u / ut for d, u in units_raw.items()}        # share of store units

    # totals: visitors x conversion x units per transaction (arithmetic documented in SUMMARY.md)
    vis = opm["traffic"]["visitors_per_day"]
    conv = opm["conversion"]["overall_rate"]["value"]
    upt = opm["basket"]["units_per_txn"]["value"]
    area_scale = profile["area_sqm"] / profile["_denver_area"]
    mult = profile["location_factor"] * area_scale
    tot_wk = vis["weekday"] * mult * conv * upt
    tot_we = vis["weekend"] * mult * conv * upt             # per weekend day, before the Saturday lift
    sat_lift = profile.get("saturday_lift", 1.0)
    # the week's units by the store's own hours: a closed weekday sells nothing; Saturday carries the lift where Sunday is closed
    day_units = {d: (0.0 if profile["hours"][d] == "closed" else (tot_wk if i < 5 else tot_we * (sat_lift if d == "sat" else 1.0)))
                 for i, d in enumerate(DAYS)}
    weekly = sum(day_units.values())
    arithmetic = {"visitors_weekday": vis["weekday"], "visitors_weekend": vis["weekend"], "location_factor": profile["location_factor"],
                  "area_scale": area_scale, "conversion": conv, "units_per_txn": upt,
                  "units_weekday": tot_wk, "units_weekend": tot_we, "saturday_lift": sat_lift, "units_week": weekly}
    ean_share = {}
    dsum = defaultdict(float)
    for s in styles:
        for e in s.eans:
            dsum[s.dept] += w[e["ean"]]
    for s in styles:
        for e in s.eans:
            ean_share[e["ean"]] = dept_units[s.dept] * w[e["ean"]] / dsum[s.dept]
        s.demand = sum(ean_share[e["ean"]] for e in s.eans)
    return styles, ean_share, arithmetic, {"dept_units": dept_units, "targets": targets, "season": seas, "value": val, "pbar": pbar}


def demand_csv(styles, share, arith):
    rows = []
    for s in sorted(styles, key=lambda s: s.id):
        for e in s.eans:
            r = e["row"]
            sh = share[e["ean"]]
            rows.append([r["ean"], r["item_cd"], s.dept, s.cat, r["price_usd"],
                         "%.6f" % (sh * arith["units_weekday"]), "%.6f" % (sh * arith["units_weekend"]), "%.15g" % sh])
    rows.sort(key=lambda r: r[0])
    return csv_text(["ean", "item_cd", "department", "category", "price_usd", "units_per_day_weekday", "units_per_day_weekend", "share"], rows)


# ===================================================== fixtures / kits =============================================
class Level:
    def __init__(self, fixture, idx, form, lc, width, fpm, cap, display, h):
        self.fixture, self.idx, self.form, self.lc = fixture, idx, form, lc
        self.width = width
        self.fpm, self.cap = fpm, cap
        self.facings = max(1, int(width / 1000.0 * fpm + 1e-9))
        self.display, self.h = display, h
        self.blocks = []        # [(style, role, block_no)]
        self.spill = False      # a hike_camp spillover level on a General fixture
        self.reserved = 0       # facings already claimed by the level's own department (spillover uses the rest)

    def doc(self):
        return {"level": self.idx, "form": self.form, "goods": self.lc, "width_mm": self.width, "facings": self.facings,
                "units_per_facing": self.cap, "capacity_units": self.facings * self.cap, "display_level": self.display}


def levels_from_stack(fx, width, stack, base=0):
    """stack: bottom-up [(lc, form, fpm, cap, h)] -> top-down Level list with display derived from height."""
    total = base + sum(x[4] for x in stack)
    out, cum = [], base
    for lc, form, fpm, cap, h in stack:
        center = (cum + h / 2.0) / float(max(total, FACE_HEIGHT))
        disp = "stoop" if form in ("floor", "bin") else display_of(center)
        out.append((center, Level(fx, 0, form, lc, width, fpm, cap, disp, h)))
        cum += h
    out.sort(key=lambda t: -t[0])
    levels = [lv for _, lv in out]
    for i, lv in enumerate(levels, start=1):
        lv.idx = i
    return levels


def lc_row(lc):
    form, fpm, cap, h = LEVEL_CLASS[lc]
    return (lc, form, fpm, cap, h)


def width_of(rect, cat):
    dx, dy = rect["x2"] - rect["x1"], rect["y2"] - rect["y1"]
    return max(dx, dy)


# ---------------------------------------------------- placement ----------------------------------------------------
def pref(level):
    order = ["stoop", "reach", "eye", "top"] if level.lc in ("bulk", "bike") else ["eye", "reach", "top", "stoop"]
    return order.index(level.display) if level.display in order else 9


def place_levels(levels, pending, role, counter, allow_repeat=True, pool_for_repeat=None, deep=1):
    """Fill `levels` (already in fixture order) consecutively from `pending` (demand-desc list of Style).
    Returns styles left over. A level holds whole style runs; the head lands on the eye levels of each fixture."""
    by_fx = defaultdict(list)
    order = []
    for lv in levels:
        if lv.fixture not in by_fx:
            order.append(lv.fixture)
        by_fx[lv.fixture].append(lv)
    placed = []
    for fx in order:
        for lv in sorted(by_fx[fx], key=lambda l: (pref(l), l.idx)):
            left = lv.facings - lv.reserved
            while True:
                pick = None
                for i in range(min(LOOKAHEAD, len(pending))):
                    if pending[i].run <= left:
                        pick = i
                        break
                if pick is None:
                    break
                s = pending.pop(pick)
                counter[s.id] += 1
                lv.blocks.append((s, role, counter[s.id]))
                placed.append(s)
                cost = min(s.run * deep, left)
                left -= cost
                if deep > 1:
                    lv.reserved += cost
    if allow_repeat:
        pool = list(pool_for_repeat if pool_for_repeat is not None else placed)
        if pool:
            cyc = itertools.cycle(pool)
            for fx in order:
                for lv in sorted(by_fx[fx], key=lambda l: (pref(l), l.idx)):
                    if lv.blocks:
                        continue
                    left = lv.facings
                    tried = 0
                    while left > 0 and tried < len(pool) + 1:
                        s = next(cyc)
                        tried += 1
                        if s.run <= left:
                            counter[s.id] += 1
                            lv.blocks.append((s, "repeat", counter[s.id]))
                            left -= s.run
                            tried = 0
                            if left <= 0:
                                break
    return pending


# ---------------------------------------------------- department quotas / dealing -------------------------------------
def need_levels(styles, lc, deep=1):
    form, fpm, cap, h = LEVEL_CLASS[lc]
    std = int(2.0 * fpm + 1e-9)
    bins = []
    for s in sorted([s for s in styles if s.run <= std], key=lambda s: -s.run):
        sz = min(s.run * deep, std)
        for i, b in enumerate(bins):
            if b >= sz:
                bins[i] -= sz
                break
        else:
            bins.append(std - sz)
    return len(bins)


def trial_levels(styles, lc, width=2000):
    """(levels, deep) for a department that must stay shallow: the largest per-EAN facing allowance deep <= MAX_FACINGS_PER_EAN for
    which the fewest standard levels hold every style AND each level carries enough EANs (at <= MAX facings each) to fill itself."""
    form, fpm, cap, h = LEVEL_CLASS[lc]
    fit = sorted((s for s in styles if s.run <= int(width / 1000.0 * fpm + 1e-9)), key=lambda s: (-s.demand, s.id))
    best = None
    for deep in range(MAX_FACINGS_PER_EAN, 0, -1):
        for n in range(1, len(fit) + 2):
            lvs = [Level("T", i, form, lc, width, fpm, cap, "eye", h) for i in range(1, n + 1)]
            if not place_levels(lvs, list(fit), "dept", Counter(), allow_repeat=False, deep=deep):
                break
        if all(sum(b[0].run for b in lv.blocks) * MAX_FACINGS_PER_EAN >= lv.facings for lv in lvs):
            return n, deep
        best = best or (n, deep)
    return best


def lc_quotas(dept_styles, total_h):
    """Height units per level class: demand-proportional, capped at DEEPEN_CAP x need, surplus re-spread (water filling)."""
    by_lc = defaultdict(list)
    for s in dept_styles:
        by_lc[s.lc].append(s)
    dem = {lc: sum(s.demand for s in ss) for lc, ss in by_lc.items()}
    cap_h = {lc: need_levels(ss, lc) * LEVEL_CLASS[lc][3] * DEEPEN_CAP for lc, ss in by_lc.items()}
    alloc, active, remaining = {}, set(by_lc), float(total_h)
    while active:
        td = sum(dem[l] for l in active)
        over = [l for l in active if remaining * dem[l] / td > cap_h[l] + 1e-9]
        if not over:
            for l in active:
                alloc[l] = remaining * dem[l] / td
            remaining = 0.0
            break
        for l in over:
            alloc[l] = cap_h[l]
            remaining -= cap_h[l]
            active.discard(l)
    if remaining > 1e-9:                # every class is capped: deepen all of them in proportion to demand
        td = sum(dem.values())
        for l in alloc:
            alloc[l] += remaining * dem[l] / td
    return alloc, by_lc


def deal_faces(faces, quota, demand_rank, left_init=None, exact=False):
    """faces: sorted fixture codes. Returns {code: [(lc, form, fpm, cap, h)] bottom-up} (new rows only).
    left_init: height already free per face (default a whole face). exact: stop when the quotas are spent."""
    rem = dict(quota)
    out = {}
    for fx in faces:
        left, stack = (left_init or {}).get(fx, FACE_HEIGHT), []
        while left >= 10:
            cands = [lc for lc in LC_AFFINITY if lc in rem and rem[lc] >= LEVEL_CLASS[lc][3] / 2.0 and LEVEL_CLASS[lc][3] <= left]
            if not cands and not exact:
                cands = [lc for lc in demand_rank if LEVEL_CLASS[lc][3] <= left]
            if not cands:
                break
            lc = cands[0]
            stack.append(lc_row(lc))
            rem[lc] -= LEVEL_CLASS[lc][3]
            left -= LEVEL_CLASS[lc][3]
        out[fx] = stack
    return out


def spread(spare, ws):
    """Spare facings over EANs in proportion to ws, each EAN capped at MAX_FACINGS_PER_EAN - 1 extra (unplaceable spare stays empty)."""
    n = len(ws)
    add = [0] * n
    cap = MAX_FACINGS_PER_EAN - 1
    active = set(range(n))
    while spare - sum(add) > 0 and active:
        rem = spare - sum(add)
        tw = sum(ws[i] for i in active)
        q = {i: rem * ws[i] / tw for i in active}
        g = {i: int(q[i]) for i in active}
        left = rem - sum(g.values())
        for i in sorted(active, key=lambda i: (-(q[i] - g[i]), i))[:left]:
            g[i] += 1
        before = sum(add)
        for i in active:
            add[i] += min(g[i], cap - add[i])
        active = {i for i in active if add[i] < cap}
        if sum(add) == before:
            break
    return add


def wall_stack(lc):
    n, h = WALL_KIT[lc]
    form, fpm, cap, _ = LEVEL_CLASS[lc]
    return [(lc, form, fpm, cap, h)] * n


# ===================================================== main build ==================================================
def build(slug, out_dir):
    sdir = os.path.join(CHAIN, "stores", slug)
    layout = json.load(open(os.path.join(sdir, "layout.json"), encoding="utf-8"))
    rows = read_csv(os.path.join(sdir, "assortment.csv"))
    opm = json.load(open(OPMODEL, encoding="utf-8"))
    keys, kmeta = load_keys()
    profiles = build_profiles(kmeta)
    DEPT_ORDER[:] = [d for d in ALL_DEPTS if any(r["department"] == d for r in rows)]
    prof = dict(profiles["stores"][slug])
    prof["_denver_area"] = profiles["stores"][REFERENCE_STORE]["area_sqm"]
    styles, share, arith, dinfo = build_demand(slug, rows, prof, opm)
    prof.pop("_denver_area")
    kz = keys[slug]

    # ---- zones
    zones = {}
    for sp in layout["spaces"]:
        for z in sp["zones"]:
            zones[z["code"]] = dict(z, space=sp["space_type"], space_code=sp["code"])
    fixtures = {c: z for c, z in zones.items() if z.get("zone_type") == "fixture"}
    floor_fx = {c: z for c, z in fixtures.items() if z["space"] == "sales_floor"}

    kit = {}                      # fixture code -> list[Level]
    counter = Counter()           # style id -> blocks placed so far
    deliberate_empty = {c: z for c, z in floor_fx.items() if z["fixture_category"] in ("footwear_bench", "gait_treadmill")}
    off_floor = []                # (style, reason)

    # ---- gps cases: exclusive, EAS-tagged premium footwear, price descending
    cases = sorted([c for c, z in floor_fx.items() if z["fixture_category"] == "gps_case"])
    case_levels = []
    for c in cases:
        w = width_of(zones[c]["rect_mm"], "gps_case")
        stack = [("shoe",) + LEVEL_CLASS["shoe"] for _ in range(GPS_CASE_LEVELS)]
        stack = [(lc, f, fpm, cap, 20) for (lc, f, fpm, cap, h) in stack]
        lvs = levels_from_stack(c, w, stack)
        for lv in lvs:
            lv.display = "eye"
        kit[c] = lvs
        case_levels += lvs
    case_pool = sorted([s for s in styles if s.tagged and s.lc == "shoe"], key=lambda s: (-s.price, -s.demand, s.id))
    leftover = place_levels(case_levels, list(case_pool), "case", counter, allow_repeat=True)
    cased = {s.id for lv in case_levels for (s, _, _) in lv.blocks}

    # ---- departments: dealing, placement
    dept_info = {}
    gen_partial = defaultdict(list)
    gen_used = {}                 # General face -> height its own styles use
    gen_walls = []
    gen_deep = {}
    for dept in [d for d in DEPT_ORDER if d in LEAN_DEPTS] + [d for d in DEPT_ORDER if d not in LEAN_DEPTS]:
        faces = sorted(c for c, z in floor_fx.items() if z["fixture_category"] in ("gondola_front", "gondola_back") and z.get("department") == dept)
        walls = sorted(c for c, z in floor_fx.items() if z["fixture_category"] in ("perimeter_west", "perimeter_east") and z.get("department") == dept)
        dstyles = [s for s in styles if s.dept == dept and s.id not in cased]
        by_lc = defaultdict(list)
        for s in dstyles:
            by_lc[s.lc].append(s)
        dem = {lc: sum(s.demand for s in ss) for lc, ss in by_lc.items()}
        if dept in LEAN_DEPTS:
            # sized by its own demand: each EAN at most MAX_FACINGS_PER_EAN facings; the rest of the region is spillover space
            gen_walls = gen_walls + walls
            walls, wall_lc, alloc = [], None, {}
            gen_deep.update({(dept, lc): trial_levels(ss, lc) for lc, ss in by_lc.items()})
            gq = {lc: float(gen_deep[(dept, lc)][0] * LEVEL_CLASS[lc][3]) for lc in by_lc}
            dealt = deal_faces(faces, gq, sorted(by_lc, key=lambda l: -dem[l]), exact=True)
        else:
            wall_lc = WALL_LC_BY_DEPT[dept]
            if wall_lc not in by_lc:
                wall_lc = max(dem, key=dem.get)
            wall_h = sum(WALL_KIT[wall_lc][0] * WALL_KIT[wall_lc][1] for _ in walls)
            total_h = len(faces) * FACE_HEIGHT + wall_h
            alloc, _ = lc_quotas(dstyles, total_h)
            gq = {lc: max(0.0, alloc.get(lc, 0.0) - (wall_h if lc == wall_lc else 0.0)) for lc in by_lc}
            # every class that has styles gets at least one level of its own
            for lc in by_lc:
                if gq[lc] < LEVEL_CLASS[lc][3] and not (lc == wall_lc and wall_h >= FACE_HEIGHT):
                    gq[lc] = float(LEVEL_CLASS[lc][3])
            gsum = sum(gq.values()) or 1.0
            gq = {lc: v * len(faces) * FACE_HEIGHT / gsum for lc, v in gq.items()}
            dealt = deal_faces(faces, gq, sorted(by_lc, key=lambda l: -dem[l]))
        lvs_by_lc = defaultdict(list)
        for fx in faces:
            if not dealt[fx]:
                continue
            if dept in LEAN_DEPTS:
                gen_used[fx] = sum(x[4] for x in dealt[fx])
            lvs = levels_from_stack(fx, width_of(zones[fx]["rect_mm"], zones[fx]["fixture_category"]), dealt[fx])
            # lowest bulk level of a face is the floor
            if lvs and lvs[-1].lc == "bulk":
                lvs[-1].form = "floor"
                lvs[-1].display = "stoop"
            kit[fx] = lvs
            for lv in lvs:
                lvs_by_lc[lv.lc].append(lv)
        for fx in walls:
            lvs = levels_from_stack(fx, width_of(zones[fx]["rect_mm"], "wall"), wall_stack(wall_lc))
            kit[fx] = lvs
            for lv in lvs:
                lvs_by_lc[lv.lc].append(lv)
        fill_by_dept = {}
        for lc, lvs in sorted(lvs_by_lc.items()):
            ss = sorted(by_lc.get(lc, []), key=lambda s: (-s.demand, s.id))
            maxw = max(lv.facings for lv in lvs)
            fit = [s for s in ss if s.run <= maxw]
            for s in ss:
                if s.run > maxw:
                    off_floor.append((s, "run of %d wider than any %s level (%d facings)" % (s.run, lc, maxw)))
            left = place_levels(lvs, list(fit), "dept", counter, allow_repeat=(dept not in LEAN_DEPTS), pool_for_repeat=None,
                                 deep=gen_deep[(dept, lc)][1] if dept in LEAN_DEPTS else 1)
            if dept in LEAN_DEPTS:       # what General's styles claim at their facing cap; the rest of the level is spillover room
                for lv in lvs:
                    if lv.reserved < lv.facings:
                        gen_partial[lc].append(lv)
            for s in left:
                off_floor.append((s, "no room at its demand rank"))
        for lc in by_lc:
            if lc not in lvs_by_lc:
                for s in by_lc[lc]:
                    off_floor.append((s, "department has no %s level" % lc))
        dept_info[dept] = {"faces": faces, "walls": walls, "wall_lc": wall_lc, "quota": alloc}

    # ---- spillover: the General fixtures General does not need hold hike_camp's best off-floor styles (whole size runs)
    global SPILL_FROM
    pruned = Counter()
    for s, r in off_floor:
        if r.startswith("no room"):
            pruned[s.dept] += s.run
    if pruned:
        SPILL_FROM = max(sorted(pruned), key=lambda d: pruned[d])
    hc_before = sum(s.run for s, _ in off_floor if s.dept == SPILL_FROM)
    spill_pool = sorted([s for s, r in off_floor if s.dept == SPILL_FROM and r.startswith("no room")], key=lambda s: (-s.demand, s.id))
    gen_faces = sorted(f for d in LEAN_DEPTS if d in dept_info for f in dept_info[d]["faces"])
    left_h = {fx: FACE_HEIGHT - gen_used.get(fx, 0) for fx in gen_faces}
    if spill_pool:
        sdem = defaultdict(float)
        for s in spill_pool:
            sdem[s.lc] += s.demand
        wall_lc = WALL_LC_BY_DEPT[SPILL_FROM] if WALL_LC_BY_DEPT[SPILL_FROM] in sdem else max(sdem, key=sdem.get)
        wall_h = sum(WALL_KIT[wall_lc][0] * WALL_KIT[wall_lc][1] for _ in gen_walls)
        total_h = sum(left_h.values()) + wall_h
        alloc, _ = lc_quotas(spill_pool, total_h)
        gq = {lc: max(0.0, alloc.get(lc, 0.0) - (wall_h if lc == wall_lc else 0.0)) for lc in sdem}
        gsum = sum(gq.values()) or 1.0
        gq = {lc: v * sum(left_h.values()) / gsum for lc, v in gq.items()}
        dealt = deal_faces(gen_faces, gq, sorted(sdem, key=lambda l: -sdem[l]), left_init=left_h)
        spill_by_lc = defaultdict(list)
        for fx in gen_faces:
            if not dealt[fx]:
                continue
            lvs = levels_from_stack(fx, width_of(zones[fx]["rect_mm"], zones[fx]["fixture_category"]), dealt[fx], base=gen_used.get(fx, 0))
            for lv in lvs:
                lv.spill = True
                spill_by_lc[lv.lc].append(lv)
            kit[fx] = lvs + kit.get(fx, [])
        for fx in gen_walls:
            lvs = levels_from_stack(fx, width_of(zones[fx]["rect_mm"], "wall"), wall_stack(wall_lc))
            for lv in lvs:
                lv.spill = True
                spill_by_lc[lv.lc].append(lv)
            kit[fx] = lvs
        for fx in gen_faces + gen_walls:
            for i, lv in enumerate(kit.get(fx, []), start=1):
                lv.idx = i
        placed_spill = set()
        for lc in sorted(set(spill_by_lc) | set(gen_partial)):
            lvs = sorted(spill_by_lc.get(lc, []) + gen_partial.get(lc, []), key=lambda l: l.fixture)
            maxw = max(lv.facings - lv.reserved for lv in lvs)
            pend = [s for s in spill_pool if s.lc == lc and s.run <= maxw]
            left = place_levels(lvs, list(pend), "spill", counter, allow_repeat=True)
            placed_spill |= {s.id for s in pend} - {s.id for s in left}
        off_floor = [(s, r) for s, r in off_floor if s.id not in placed_spill]
    hc_after = sum(s.run for s, _ in off_floor if s.dept == SPILL_FROM)
    spill_info = {"before": hc_before, "after": hc_after, "general_fixtures_used": sorted(gen_used), "gen_faces": gen_faces, "gen_walls": gen_walls}

    # ---- specialty duplicates (feature blocks): islands, round rack, accessories walls, impulse, checkout
    on_floor_ids = {s.id for s in styles} - {s.id for s, _ in off_floor}
    avail = [s for s in styles if s.id in on_floor_ids]
    by_demand = sorted(avail, key=lambda s: (-s.demand, s.id))
    feature_dept = max((d for d in DEPT_ORDER if d not in LEAN_DEPTS), key=lambda d: season_weight(d))

    def mk_levels(fx, specs, cat):
        w = width_of(zones[fx]["rect_mm"], cat)
        stack = [(lc, f, fpm, cap, h) for (lc, f, fpm, cap, h) in specs]
        lvs = levels_from_stack(fx, w, stack)
        for lv in lvs:
            if lv.form in ("table_top",):
                lv.display = "reach"
        kit[fx] = lvs
        return lvs

    islands = sorted(c for c, z in floor_fx.items() if z["fixture_category"] == "promo_island")
    for i, fx in enumerate(islands):
        lt = mk_levels(fx, [("table",) + TABLE_TOP, ("bin",) + BIN], "promo_island")
        table = [lv for lv in lt if lv.form == "table_top"]
        binl = [lv for lv in lt if lv.form == "bin"]
        if i == 0:
            tp = [s for s in by_demand if s.dept == feature_dept and s.lc in ("fold", "shoe")]
            bp = [s for s in by_demand if s.dept == feature_dept and s.lc == "peg"]
        else:
            tp = [s for s in by_demand if s.dept != feature_dept and s.lc in ("fold", "shoe")]
            bp = [s for s in by_demand if s.dept != feature_dept and s.lc == "peg"]
        place_levels(table, list(tp), "feature", counter, True, tp[:12])
        place_levels(binl, list(bp), "feature", counter, True, bp[:12])
    rr = sorted(c for c, z in floor_fx.items() if z["fixture_category"] == "round_rack")
    for fx in rr:
        lt = mk_levels(fx, [lc_row("hang")] * ROUND_RACK_LEVELS, "round_rack")
        hp = [s for s in by_demand if s.lc == "hang"]
        place_levels(lt, list(hp), "feature", counter, True, hp[:6])
    accw = sorted(c for c, z in floor_fx.items() if z["fixture_category"] == "accessories_wall")
    acc_levels = []
    for fx in accw:
        acc_levels += mk_levels(fx, [lc_row("peg")] * 6, "accessories_wall")
    pp = [s for s in by_demand if s.lc == "peg"]
    place_levels(acc_levels, list(pp), "feature", counter, True, pp[:20])
    imp = sorted(c for c, z in floor_fx.items() if z["fixture_category"] == "impulse_rack")
    chk = sorted(c for c, z in floor_fx.items() if z["fixture_category"] == "checkout")
    small = [s for s in by_demand if s.lc == "peg" and s.price <= IMPULSE_MAX_PRICE]
    imp_levels = []
    for fx in imp:
        imp_levels += mk_levels(fx, [lc_row("peg")] * IMPULSE_LEVELS, "impulse_rack")
    place_levels(imp_levels, list(small), "feature", counter, True, small[:20])
    chk_levels = []
    for fx in chk:
        chk_levels += mk_levels(fx, [("bin",) + BIN], "checkout")
    place_levels(chk_levels, list(small), "feature", counter, True, small[:20])
    # back room racks
    brs = sorted(c for c, z in fixtures.items() if z["fixture_category"] == "backroom_rack")
    for fx in brs:
        w = width_of(zones[fx]["rect_mm"], "backroom_rack")
        kit[fx] = levels_from_stack(fx, w, [("store",) + BACKROOM for _ in range(BR_LEVELS)])
        for lv in kit[fx]:
            lv.display = "reach"

    # ---- facings and lines
    all_levels = [lv for fx in sorted(kit) for lv in kit[fx] if fx not in brs]
    fac = {}
    for lv in all_levels:
        if not lv.blocks:
            continue
        eans = [e for (s, _, _) in lv.blocks for e in s.eans]
        used = len(eans)
        spare = lv.facings - used
        ws = [share[e["ean"]] for e in eans]
        tw = sum(ws)
        add = spread(spare, ws)
        k = 0
        for bi, (s, role, bno) in enumerate(lv.blocks):
            for e in s.eans:
                fac[(id(lv), bi, e["ean"])] = 1 + add[k]
                k += 1
    lines = []
    for fx in sorted(kit):
        if fx in brs:
            continue
        z = zones[fx]
        kr = kz.get(fx, {})
        for lv in sorted(kit[fx], key=lambda l: l.idx):
            pos = 0
            for bi, (s, role, bno) in enumerate(lv.blocks):
                for e in s.eans:
                    pos += 1
                    f = fac[(id(lv), bi, e["ean"])]
                    req = max(1, min(f * lv.cap, int(round(f * lv.cap * FILL_TARGET))))
                    lines.append({
                        "fixture_code": fx, "zone_id": kr.get("zone_id", ""), "zone_name": kr.get("zone_name", z["name"]),
                        "level": lv.idx, "form": lv.form, "position_sequence": pos, "ean": e["ean"], "sku": e["item_cd"],
                        "facings": f, "required_qty": req, "display_level": lv.display,
                        "department": s.dept, "block": bno, "role": role,
                    })
                    if lv.spill or role == "spill":
                        lines[-1]["spill_from"] = s.dept
    lines.sort(key=lambda l: (l["fixture_code"], l["level"], l["position_sequence"]))
    digest = sha(json.dumps(lines, sort_keys=True, ensure_ascii=False).encode("utf-8"))
    placed_eans = {l["ean"] for l in lines}
    off_ids = {s.id for s, _ in off_floor}
    n_off = sum(s.run for s in styles if s.id in off_ids)
    doc = {
        "format": FORMAT, "version": VERSION, "kit_version": KIT_VERSION,
        "directive_ref": "PLN-%s-v2-%s" % (slug, digest[:12]), "store_id": slug, "site_ref": slug, "source_digest": digest,
        "capacity_per_facing": {k: v[2] for k, v in LEVEL_CLASS.items()},
        "notes": "product_placement only; keyed by EAN; `block` 1 = the style's first placement, >1 = repeat or feature block; "
                 "levels are numbered top-down (1 = top)",
        "counts": {"lines": len(lines), "fixtures": len({l["fixture_code"] for l in lines}), "eans": len(placed_eans),
                   "skus": len({l["sku"] for l in lines}), "total_required": sum(l["required_qty"] for l in lines),
                   "off_floor_eans": n_off},
        "lines": lines,
    }

    # ---- fixtures.json
    fx_doc = {}
    for c in sorted(zones):
        z = zones[c]
        kr = kz.get(c, {})
        d = {"zone_id": kr.get("zone_id", ""), "zone_name": kr.get("zone_name", z["name"]), "space": z["space"],
             "zone_type": z.get("zone_type"), "fixture_category": z.get("fixture_category", ""), "department": z.get("department", "")}
        if z.get("zone_type") == "fixture":
            if c in kit:
                d["levels"] = [lv.doc() for lv in sorted(kit[c], key=lambda l: l.idx)]
                d["capacity_units"] = sum(lv.facings * lv.cap for lv in kit[c])
                d["role"] = "backroom" if c in brs else "stock"
            else:
                d["levels"] = []
                d["capacity_units"] = 0
                d["role"] = "service" if c in deliberate_empty else "staging"
                d["deliberate_empty"] = True
                d["reason"] = {"footwear_bench": "service fixture: try-on seating, no stock by design",
                               "gait_treadmill": "service fixture: gait analysis, no stock by design",
                               "receiving_dock": "staging: no planned stock"}.get(z["fixture_category"], "no stock by design")
        else:
            d["role"] = "area" if z.get("zone_type") in ("region", "entry_exit", "checkout") else z.get("zone_type", "")
        fx_doc[c] = d
    fixtures_json = {"store_id": slug, "kit_version": KIT_VERSION,
                     "constants": {"level_class": {k: {"form": v[0], "facings_per_m": v[1], "units_per_facing": v[2], "height_units": v[3]}
                                                   for k, v in LEVEL_CLASS.items()},
                                   "table_top": TABLE_TOP[1:3], "bin": BIN[1:3], "backroom": BACKROOM[1:3], "face_height_units": FACE_HEIGHT,
                                   "level_numbering": "top-down within a fixture (1 = top)"},
                     "fixtures": fx_doc}

    # ---- stock plan
    srng = random.Random(seed_of(SEED_SALT, slug, "deviation"))
    agg = defaultdict(int)
    for l in lines:
        q = l["required_qty"]
        u = srng.random()
        if u < DEVIATION_EXACT:
            pass
        elif u < DEVIATION_EXACT + DEVIATION_SHORT:
            q = max(0, q - srng.randint(1, 2))
        else:
            q += 1
        agg[(l["ean"], l["fixture_code"])] += q
    stock = [[ean, fx, kz.get(fx, {}).get("zone_id", ""), q, "floor"] for (ean, fx), q in agg.items() if q > 0]
    dept_br = defaultdict(int)
    br_rows = []
    pack_of = lambda s: CASE_PACK_BIKE if s.lc == "bike" else CASE_PACK
    for s in sorted(styles, key=lambda s: s.id):
        off = s.id in off_ids
        for e in s.eans:
            d = share[e["ean"]] * arith["units_week"] / 7.0
            n = math.ceil(d * (COVER_DAYS + (OFFFLOOR_EXTRA_DAYS if off else 0)))
            p = pack_of(s)
            q = max(1, math.ceil(n / p)) * p
            br_rows.append((s, e["ean"], q))
            dept_br[s.dept] += q
    # racks per department: proportional to back-room units, at least one each (largest remainder, trimmed from the biggest)
    nr = len(brs)
    tot = sum(dept_br.values())
    quota = {d: nr * dept_br[d] / tot for d in DEPT_ORDER}
    n_of = {d: max(1, int(quota[d])) for d in DEPT_ORDER}
    while sum(n_of.values()) < nr:
        n_of[max(DEPT_ORDER, key=lambda d: (quota[d] - n_of[d], d))] += 1
    while sum(n_of.values()) > nr:
        n_of[max((d for d in DEPT_ORDER if n_of[d] > 1), key=lambda d: (n_of[d] - quota[d], d))] -= 1
    racks_of, i0 = {}, 0
    for d in sorted(DEPT_ORDER, key=lambda d: (-dept_br[d], d)):
        racks_of[d] = brs[i0:i0 + n_of[d]]
        i0 += n_of[d]
    rack_cap = lambda r: sum(lv.facings * lv.cap for lv in kit[r])
    sid_rack = {}
    load = Counter()
    style_units = defaultdict(int)
    for s, ean, q in br_rows:
        style_units[s.id] += q
    for s in sorted(styles, key=lambda s: (-style_units[s.id], s.id)):
        rk = min(racks_of[s.dept], key=lambda r: (load[r], r))     # least-loaded rack of the department
        if load[rk] + style_units[s.id] > rack_cap(rk):             # department's racks are full: least-loaded rack with room
            fits = [r for r in brs if load[r] + style_units[s.id] <= rack_cap(r)]
            rk = min(fits or brs, key=lambda r: (load[r] / float(rack_cap(r)), r))
        sid_rack[s.id] = rk
        load[rk] += style_units[s.id]
    rack_units = Counter()
    for s, ean, q in br_rows:
        rk = sid_rack[s.id]
        stock.append([ean, rk, kz.get(rk, {}).get("zone_id", ""), q, "backroom"])
        rack_units[rk] += q
    stock.sort(key=lambda r: (r[4], r[1], r[0]))
    stock_csv = csv_text(["ean", "zone_code", "zone_id", "qty", "kind"], stock)

    outs = {
        "profile.json": jdump(dict({"store_id": slug}, **prof)),
        "fixtures.json": jdump(fixtures_json),
        "demand.csv": demand_csv(styles, share, arith),
        "planogram.json": jdump(doc),
        "stock-plan.csv": stock_csv,
    }
    outs["SUMMARY.md"] = summary(slug, styles, share, arith, dinfo, lines, kit, brs, off_floor, dept_info, feature_dept,
                                 rack_units, racks_of, spill_info, deliberate_empty, fixtures, floor_fx, outs, zones, fx_doc, cased, stock)
    os.makedirs(out_dir, exist_ok=True)
    for name, text in outs.items():
        with open(os.path.join(out_dir, name), "w", encoding="utf-8", newline="") as f:
            f.write(text)
    return outs, profiles


# ===================================================== summary =====================================================
def summary(slug, styles, share, arith, dinfo, lines, kit, brs, off_floor, dept_info, feature_dept, rack_units, racks_of,
            spill_info, deliberate_empty, fixtures, floor_fx, outs, zones, fx_doc, cased, stock):
    L = []
    w = L.append
    sty = {s.id: s for s in styles}
    owned = defaultdict(list)       # department -> levels it owns (spillover levels belong to the spilling department)
    for d in DEPT_ORDER:
        for c in dept_info[d]["faces"] + dept_info[d]["walls"] + (spill_info["gen_walls"] if d == "general" else []):
            for lv in kit.get(c, []):
                owned[SPILL_FROM if lv.spill else d].append(lv)
    ean_style = {e["ean"]: s for s in styles for e in s.eans}
    off_ids = {s.id: r for s, r in off_floor}
    on_floor_ean = {l["ean"] for l in lines}
    dem_dept = defaultdict(float)
    for s in styles:
        dem_dept[s.dept] += s.demand
    w("# %s - store model, Phase 1 (generated)\n" % slug)
    w("Generated by `scripts/build_store_model.py`. Do not edit by hand. Offline; seeded; byte-identical on rerun.\n")
    w("## Headline\n")
    n_ean = sum(len(s.eans) for s in styles)
    n_off = sum(s.run for s in styles if s.id in off_ids)
    fl_units = sum(l["required_qty"] for l in lines)
    cap_units = sum(fx_doc[c]["capacity_units"] for c in kit if c not in brs)
    stocked = {l["fixture_code"] for l in lines}
    empty_floor = sorted(c for c in floor_fx if c not in stocked)
    w("- EANs: %d, on the floor: %d, off the floor (back room only): %d (%d styles)" % (n_ean, len(on_floor_ean), n_off, len(off_ids)))
    w("- Planogram lines: %d over %d fixtures; floor units at plan: %d of %d capacity (%.1f %%)" %
      (len(lines), len(stocked), fl_units, cap_units, 100.0 * fl_units / cap_units))
    w("- Empty floor fixtures: %d - %s (all deliberate service fixtures)" % (len(empty_floor), ", ".join(empty_floor) or "none"))
    w("- Backroom: %d units over %d racks" % (sum(rack_units.values()), len(brs)))
    srt = sorted(share.values(), reverse=True)
    head = sum(srt[: max(1, len(srt) // 10)]) / sum(srt)
    ng = sorted((share[e["ean"]] for s in styles if s.dept != "general" for e in s.eans), reverse=True)
    head_ng = sum(ng[: max(1, len(ng) // 10)]) / sum(ng)
    w("- Demand head: the top 10 %% of EANs (%d) carry %.1f %% of units (%.1f %% excluding the %d-EAN general department, "
      "which alone carries %.1f %% of units)\n" % (len(srt) // 10, 100 * head, 100 * head_ng, sum(1 for s in styles if s.dept == "general" for _ in s.eans), 100 * dem_dept["general"]))

    w("## Demand arithmetic\n")
    w("units/day = visitors/day x location_factor x area_scale x conversion x units per transaction (model values, neutral:")
    w("before seasonality, store story, holiday and promo overlays, which Phase 2 applies per date).\n")
    a = arith
    w("- weekday: %d x %.2f x %.4f x %.2f x %.1f = **%.3f units/day**" % (a["visitors_weekday"], a["location_factor"], a["area_scale"],
                                                                         a["conversion"], a["units_per_txn"], a["units_weekday"]))
    w("- weekend: %d x %.2f x %.4f x %.2f x %.1f = **%.3f units/day**" % (a["visitors_weekend"], a["location_factor"], a["area_scale"],
                                                                         a["conversion"], a["units_per_txn"], a["units_weekend"]))
    w("- area_scale = area_sqm / Denver area_sqm (StoryCalendar.areaScale, reference store dec-us-denver): 1.0 for Denver.")
    w("- Department split: value share = StoryCalendar.departmentSalesTargets x seasonal department weight read at %04d-%02d-%02d, "
      "renormalised; units = value / demand-weighted mean price, renormalised to the total above." % DEMAND_DATE)
    w("- Within a department: category unit mix, then Zipf (exponent %.2f, rank offset %.1f) over styles x price^%.1f, then the size curve "
      "(`size_curve.allocate`, floored so no EAN is zero).\n" % (ZIPF_EXP, ZIPF_OFFSET, PRICE_ELASTICITY))
    w("| department | value share | seasonal weight | mean price | units share | units/day weekday |")
    w("|---|---:|---:|---:|---:|---:|")
    for d in DEPT_ORDER:
        w("| %s | %.1f %% | %.3f | $%.2f | %.1f %% | %.1f |" % (d, 100 * dinfo["value"][d], dinfo["season"][d], dinfo["pbar"][d],
                                                          100 * dinfo["dept_units"][d], dinfo["dept_units"][d] * a["units_weekday"]))
    w("\nFeature island department (highest seasonal weight): **%s**.\n" % feature_dept)

    gl = defaultdict(set)
    for l in lines:
        if zones[l["fixture_code"]].get("department") == "general" or l["fixture_code"] in spill_info["gen_walls"]:
            gl[l["fixture_code"]].add("spill" if l.get("spill_from") else "general")
    n_g = sum(1 for v in gl.values() if v == {"general"})
    n_s = sum(1 for v in gl.values() if v == {"spill"})
    n_b = sum(1 for v in gl.values() if len(v) == 2)
    gen_units = sum(l["required_qty"] for l in lines if l["department"] == "general" and l["role"] in ("dept", "repeat")
                    and zones[l["fixture_code"]].get("department") == "general")
    sp_units = sum(l["required_qty"] for l in lines if l.get("spill_from"))
    w("## General's corner and the spillover\n")
    n_gen_eans = sum(len(s.eans) for s in styles if s.dept == "general")
    n_gen_fx = len(spill_info["gen_faces"]) + len(spill_info["gen_walls"])
    w("General (%d EANs) is sized by its own demand: at most %d facings per EAN and no repeat blocks, so it needs only part of its %d fixtures. "
      "The rest of the General region is spillover space for %s, the department with the largest off-floor demand; its best off-floor "
      "styles (whole size runs, same rules) go there, tagged `spill_from: %s` in planogram.json with their department kept as %s.\n" %
      (n_gen_eans, MAX_FACINGS_PER_EAN, n_gen_fx, SPILL_FROM, SPILL_FROM, SPILL_FROM))
    w("- General fixtures (%d in the region): %d used by General alone, %d shared (General levels plus spillover levels), %d spillover only"
      % (n_gen_fx, n_g, n_b, n_s))
    w("- General floor units in its own rows: %d; spillover floor units: %d" % (gen_units, sp_units))
    w("- %s off-floor EANs: %d before spillover, %d after" % (SPILL_FROM, spill_info["before"], spill_info["after"]))
    w("- Cycling's off-floor bike frame sizes are deliberately left as they are: a store displays one frame size per model and keeps "
      "the rest in the back room. Cycling is excluded from spillover.\n")
    w("## Per department - space vs demand\n")
    w("Department rows are fixed geometry (rule 1): every department holds %d gondola faces + %d wall bays, so space across departments is " %
      (len(dept_info[DEPT_ORDER[0]]["faces"]), len(dept_info[DEPT_ORDER[0]]["walls"])) +
      "fixed by the floor plan and cannot follow demand; space follows demand *inside* each department (next table).\n")
    w("| department | fixtures | facings | floor units | EANs | on floor | off floor | space share (height units) | demand share |")
    w("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    hts = {d: sum(lv.h for lv in owned[d]) for d in DEPT_ORDER}
    hts_tot = float(sum(hts.values()))
    fac_dept = defaultdict(int)
    units_dept = defaultdict(int)
    fx_dept = defaultdict(set)
    ean_dept_floor = defaultdict(set)
    for l in lines:
        if l["role"] in ("dept", "repeat") and zones[l["fixture_code"]].get("department"):
            fac_dept[l["department"]] += l["facings"]
            units_dept[l["department"]] += l["required_qty"]
            fx_dept[l["department"]].add(l["fixture_code"])
            ean_dept_floor[l["department"]].add(l["ean"])
    totf = sum(fac_dept.values())
    for d in DEPT_ORDER:
        ne = sum(len(s.eans) for s in styles if s.dept == d)
        no = sum(s.run for s in styles if s.dept == d and s.id in off_ids)
        w("| %s | %d | %d | %d | %d | %d | %d | %.1f %% | %.1f %% |" % (
            d, len(fx_dept[d]), fac_dept[d], units_dept[d], ne, len(ean_dept_floor[d]), no, 100.0 * hts[d] / hts_tot,
            100.0 * dem_dept[d]))
    w("\n(EANs on floor in a department row can exceed the count above by the styles that sit in a GPS case; see below.)\n")

    w("## Inside each department - level class: space vs demand\n")
    w("| department | class | levels | facings | space share (height units) | demand share | EANs | off floor |")
    w("|---|---|---:|---:|---:|---:|---:|---:|")
    for d in DEPT_ORDER:
        fcl = defaultdict(int)
        lcn = defaultdict(int)
        hcl = defaultdict(int)
        for lv in owned[d]:
            fcl[lv.lc] += lv.facings
            lcn[lv.lc] += 1
            hcl[lv.lc] += lv.h
        dd = defaultdict(float)
        en = Counter()
        eo = Counter()
        for s in styles:
            if s.dept == d and s.id not in cased:
                dd[s.lc] += s.demand
                en[s.lc] += s.run
                if s.id in off_ids:
                    eo[s.lc] += s.run
        tf = sum(hcl.values()) or 1
        td = sum(dd.values()) or 1
        for lc in LC_AFFINITY:
            if lc in en:
                w("| %s | %s | %d | %d | %.1f %% | %.1f %% | %d | %d |" % (d, lc, lcn[lc], fcl[lc], 100.0 * hcl[lc] / tf,
                                                                       100.0 * dd[lc] / td, en[lc], eo[lc]))
    w("")

    w("## Per fixture category\n")
    w("| category | fixtures | levels | facings | floor units | capacity | fill |")
    w("|---|---:|---:|---:|---:|---:|---:|")
    cat_fx = defaultdict(list)
    for c, z in sorted(floor_fx.items()):
        cat_fx[z["fixture_category"]].append(c)
    lines_by_fx = defaultdict(list)
    for l in lines:
        lines_by_fx[l["fixture_code"]].append(l)
    for cat in sorted(cat_fx):
        fxs = cat_fx[cat]
        if all(c in deliberate_empty for c in fxs):
            w("| %s | %d | 0 | 0 | 0 | 0 | deliberate empty |" % (cat, len(fxs)))
            continue
        nl = sum(len(kit[c]) for c in fxs)
        nf = sum(lv.facings for c in fxs for lv in kit[c])
        u = sum(l["required_qty"] for c in fxs for l in lines_by_fx[c])
        cp = sum(fx_doc[c]["capacity_units"] for c in fxs)
        w("| %s | %d | %d | %d | %d | %d | %.1f %% |" % (cat, len(fxs), nl, nf, u, cp, 100.0 * u / cp))
    mn = min((sum(l["required_qty"] for l in lines_by_fx[c]) / float(fx_doc[c]["capacity_units"]), c) for c in stocked)
    w("\nLowest fixture fill: %.1f %% (%s). Fill = required_qty / (facings x units per facing) summed over every level of the fixture.\n" % (
        100 * mn[0], mn[1]))

    w("## Deliberate empties\n")
    for c in sorted(deliberate_empty):
        w("- %s (%s): %s" % (c, zones[c]["name"], fx_doc[c]["reason"]))
    w("- RCV-01 (Receiving Dock, back-room space): staging, no planned stock\n")

    w("## GPS cases (exclusive, EAS-tagged by `price >= 150 and category != outdoor`)\n")
    case_styles = [s for s in styles if s.id in cased]
    w("%d footwear styles (%d EANs) sit in the 6 cases, highest price first; they are not repeated in the department rows.\n" %
      (len(case_styles), sum(s.run for s in case_styles)))

    w("## Off the floor (%d EANs, %d styles) - lowest demand first within each department\n" % (n_off, len(off_ids)))
    byd = defaultdict(list)
    for s, r in off_floor:
        byd[s.dept].append((s, r))
    reasons = Counter(r.split(" of ")[0] if r.startswith("run of") else r for _, r in off_floor)
    w("Reasons: " + "; ".join("%s: %d styles" % (k, v) for k, v in sorted(reasons.items())) + "\n")
    for d in DEPT_ORDER:
        lst = sorted(byd[d], key=lambda t: (t[0].demand, t[0].id))
        w("### %s - %d styles, %d EANs\n" % (d, len(lst), sum(s.run for s, _ in lst)))
        for s, r in lst:
            w("- `%s` (%s, %s, %d sizes, $%.2f, %.3f units/day): %s" % (s.id, s.cat, s.lc, s.run, s.price, s.demand * a["units_weekday"], r))
        w("")

    w("## Back room\n")
    w("| rack | units | capacity | department |")
    w("|---|---:|---:|---|")
    dep_of = {r: d for d, rs in racks_of.items() for r in rs}
    for rk in brs:
        w("| %s | %d | %d | %s |" % (rk, rack_units[rk], fx_doc[rk]["capacity_units"], dep_of.get(rk, "")))
    w("\nBack room per EAN = ceil(demand/day x %d) rounded up to cases of %d (bikes: %d); off-floor EANs carry %d extra days. "
      "Racks hold units, not EPCs: Phase 2/3 mint EPCs. Back-room rack zones are not in the keys export, so their `zone_id` is empty.\n" % (
          COVER_DAYS, CASE_PACK, CASE_PACK_BIKE, OFFFLOOR_EXTRA_DAYS))

    w("## Knobs used\n")
    w("| knob | value |")
    w("|---|---|")
    for k, v in [("MAX_FACINGS_PER_EAN", MAX_FACINGS_PER_EAN), ("SPILL_FROM", SPILL_FROM), ("DEMAND_DATE", DEMAND_DATE), ("ZIPF_EXP", ZIPF_EXP), ("ZIPF_OFFSET", ZIPF_OFFSET), ("PRICE_ELASTICITY", PRICE_ELASTICITY),
                 ("CATEGORY_UNIT_WEIGHT", CATEGORY_UNIT_WEIGHT), ("FILL_TARGET", FILL_TARGET), ("DEEPEN_CAP", DEEPEN_CAP),
                 ("DEVIATION (exact/short/over)", "%.2f / %.2f / %.2f" % (DEVIATION_EXACT, DEVIATION_SHORT, 1 - DEVIATION_EXACT - DEVIATION_SHORT)),
                 ("COVER_DAYS / OFFFLOOR_EXTRA_DAYS", "%d / %d" % (COVER_DAYS, OFFFLOOR_EXTRA_DAYS)),
                 ("CASE_PACK (bike)", "%d (%d)" % (CASE_PACK, CASE_PACK_BIKE)), ("IMPULSE_MAX_PRICE", IMPULSE_MAX_PRICE)]:
        w("| %s | %s |" % (k, v))
    w("\nLevel classes (form, facings/m, units/facing, height units): " + "; ".join("%s=%s" % (k, v) for k, v in LEVEL_CLASS.items()) + ".\n")
    w("## Where the brief's numbers and the data disagreed\n")
    w("- Shoe boxes at the brief's ~4 facings/m cannot hold a 9+ size run on one level at one facing per EAN (40 of 67 footwear styles): 6/m used.")
    w("- Folded apparel 5/m (brief ~4/m) for the same reason (24 apparel styles have 8-15 sizes).")
    w("- Hanging: brief's 10 per facing at 25 slots/m is 250 garments a metre of rail; 2 garments per 4 cm slot-facing used.")
    w("- Bikes: 5 facings/m (3 across x 2 deep in a floor bay) so a frame-size run fits one level; 1.5/m left the two cycling wall bays empty.")
    w("- Total floor capacity (~%d units) is about twice the 15,005 EPCs Denver holds today; the plan is a target, Phase 3 plans the transition." % sum(
        fx_doc[c]["capacity_units"] for c in kit if c not in brs))
    w("- Department space cannot follow demand across departments (rule 1 fixes 16 fixtures each); it does inside each department.")
    w("- General (%d EANs) cannot use %d fixtures at <= %d facings per EAN: it takes what it needs and the rest spills over from %s (above)." % (n_gen_eans, n_gen_fx, MAX_FACINGS_PER_EAN, SPILL_FROM))
    w("- Feature fixtures (islands, round rack, accessories walls, impulse, checkout) repeat styles already placed in a department (block > 1).")
    w("- Runs wider than any level of their class go off the floor (listed above).\n")
    w("## Output digests (sha256)\n")
    for name in sorted(outs):
        if name != "SUMMARY.md":
            w("- %s `%s`" % (name, sha(outs[name].encode("utf-8"))[:16]))
    return "\n".join(L) + "\n"


SUMMARY_MD = os.path.join(CHAIN, "STORE-MODEL-SUMMARY.md")


def chain_summary(slugs):
    """One row per store, read back from the committed model files (so it describes exactly what is on disk)."""
    L = ["# Store model - chain summary (generated)", "",
         "Generated by `scripts/build_store_model.py --all` from each store's `model/` files. Do not edit by hand. "
         "Per-store detail: `stores/<slug>/model/SUMMARY.md`.", "",
         "| store | m2 | tz | EANs | on floor | off floor | floor fixtures | lines | floor units at plan | capacity | fill | min fixture fill "
         "| back room units (racks) | demand wk / we units per day | spill dept | spill units | deliberate empties |",
         "|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---:|---|"]
    for slug in slugs:
        mdir = os.path.join(CHAIN, "stores", slug, "model")
        prof = json.load(open(os.path.join(mdir, "profile.json"), encoding="utf-8"))
        fx = json.load(open(os.path.join(mdir, "fixtures.json"), encoding="utf-8"))["fixtures"]
        pg = json.load(open(os.path.join(mdir, "planogram.json"), encoding="utf-8"))
        dem = read_csv(os.path.join(mdir, "demand.csv"))
        stock = read_csv(os.path.join(mdir, "stock-plan.csv"))
        lines = pg["lines"]
        stocked = {c: v for c, v in fx.items() if v.get("zone_type") == "fixture" and v.get("role") == "stock"}
        req = defaultdict(int)
        for l in lines:
            req[l["fixture_code"]] += l["required_qty"]
        cap = sum(v["capacity_units"] for v in stocked.values())
        fills = [req[c] / float(v["capacity_units"]) for c, v in stocked.items() if v["capacity_units"] > 0]
        on_floor = len({l["ean"] for l in lines})
        spill = [l for l in lines if l.get("spill_from")]
        spill_dept = ",".join(sorted({l["spill_from"] for l in spill})) or "-"
        empties = ",".join(sorted(c for c, v in fx.items() if v.get("deliberate_empty") and v.get("space") == "sales_floor")) or "-"
        back = sum(int(r["qty"]) for r in stock if r["kind"] == "backroom")
        racks = len({r["zone_code"] for r in stock if r["kind"] == "backroom"})
        wk = sum(float(r["units_per_day_weekday"]) for r in dem)
        we = sum(float(r["units_per_day_weekend"]) for r in dem)
        tot = sum(req.values())
        L.append("| %s | %.0f | %s | %d | %d | %d | %d | %d | %d | %d | %.1f %% | %.1f %% | %d (%d) | %.1f / %.1f | %s | %d | %s |" % (
            slug, prof["area_sqm"], prof["timezone"], len(dem), on_floor, len(dem) - on_floor, len(stocked), len(lines), tot, cap,
            100.0 * tot / cap, 100.0 * min(fills), back, racks, wk, we, spill_dept, sum(l["required_qty"] for l in spill), empties))
    L += ["", "Rules held for every store (asserted by `store_model_selftest.py`): no empty floor fixture except the deliberate service "
          "fixtures; every stocked fixture at least 70 %% filled; size runs contiguous on one level; at most %d facings per EAN; "
          "General (and water where a store has it) sized by its own demand, the rest of its region taken by spillover from the "
          "most-pruned department; bike frame sizes may stay in the back room; back-room racks within capacity." % MAX_FACINGS_PER_EAN, ""]
    return "\n".join(L) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--store", default=REFERENCE_STORE)
    ap.add_argument("--out", default=None, help="output dir (default stores/<slug>/model); profiles go beside it only for the default")
    ap.add_argument("--no-profiles", action="store_true")
    ap.add_argument("--all", action="store_true", help="build the model for every chain store (default output dirs)")
    args = ap.parse_args()
    if args.all:
        slugs = sorted(d for d in os.listdir(os.path.join(CHAIN, "stores")) if os.path.exists(os.path.join(CHAIN, "stores", d, "layout.json")))
        for slug in slugs:
            outs, profiles = build(slug, os.path.join(CHAIN, "stores", slug, "model"))
            print("%-18s %s" % (slug, " ".join(sha(outs[n].encode("utf-8"))[:8] for n in sorted(outs))))
        if not args.no_profiles:
            with open(PROFILES, "w", encoding="utf-8", newline="") as f:
                f.write(jdump(profiles))
            with open(SUMMARY_MD, "w", encoding="utf-8", newline="") as f:
                f.write(chain_summary(slugs))
        return
    out = args.out or os.path.join(CHAIN, "stores", args.store, "model")
    outs, profiles = build(args.store, out)
    ptxt = jdump(profiles)
    if args.out is None and not args.no_profiles:
        with open(PROFILES, "w", encoding="utf-8", newline="") as f:
            f.write(ptxt)
    for name in sorted(outs):
        print("%-16s %s" % (name, sha(outs[name].encode("utf-8"))))
    print("%-16s %s" % ("store-profiles", sha(ptxt.encode("utf-8"))))


if __name__ == "__main__":
    main()
