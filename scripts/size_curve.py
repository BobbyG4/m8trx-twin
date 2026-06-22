"""
size_curve.py — Realistic retail size-distribution generator for M8TRX Twin.

Public API
----------
normalize_size(raw, category)  → {"system", "ordinal", "canonical"}
allocate(variants, total_depth, category, rng) → {ean: int}
style_target(category, tier_scale, rng) → int

All randomness is supplied by the caller via an `rng` argument
(a random.Random instance or compatible).  This module never touches
random.random(), random.seed(), or datetime.
"""

from __future__ import annotations

import math
import re
from typing import Optional

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

ALPHA_ORDER = ["2XS", "XS", "S", "M", "L", "XL", "2XL", "3XL", "4XL", "5XL"]
ALPHA_ORDINAL = {s: float(i) for i, s in enumerate(ALPHA_ORDER)}

# Apparel curve weights keyed by alpha label (used in allocate)
ALPHA_WEIGHTS = {
    "2XS": 0.20,
    "XS":  0.45,
    "S":   1.00,
    "M":   1.60,
    "L":   1.55,
    "XL":  1.10,
    "2XL": 0.60,
    "3XL": 0.30,
    "4XL": 0.15,
    "5XL": 0.08,
}

# Base style depth by category (flagship tier_scale=1.0)
BASE_DEPTH = {
    "footwear":    22,
    "apparel":     26,
    "accessories":  9,
    "bag_pack":     7,
    "outdoor":      2,
}

# Regex atoms
_RE_EU       = re.compile(r"EU\s*(\d+(?:\.\d+)?)", re.IGNORECASE)
_RE_DUAL     = re.compile(r"W\s*(\d+(?:\.\d+)?)\s*/?\s*M\s*(\d+(?:\.\d+)?)", re.IGNORECASE)
_RE_RANGE    = re.compile(r"(\d+(?:\.\d+)?)\s*[-–]\s*(\d+(?:\.\d+)?)")
_RE_KIDS_C   = re.compile(r"^(\d+(?:\.\d+)?)C$", re.IGNORECASE)
_RE_PLAIN    = re.compile(r"^\d+(?:\.\d+)?$")
_RE_WAIST_L  = re.compile(r"W\s*(\d+)\s+L\s*(\d+)", re.IGNORECASE)    # "W33 L31"
_RE_WAIST_O  = re.compile(r'^W\s*(\d+)"?$', re.IGNORECASE)            # "W29\"" waist-only
_RE_KIDS_YR  = re.compile(r"(\d+)\s*[-–]\s*(\d+)\s*[Yy]")
_RE_SINGLE_YR= re.compile(r"^(\d+)\s+[Yy]ear", re.IGNORECASE)        # "10 Years"
_RE_MONTHS_YR= re.compile(r"(\d+)\s*[Mm]onths?.*?(\d+)\s*[Yy]ear", re.IGNORECASE)  # "24 Months-3 Years"
_RE_LEAD_N   = re.compile(r"^(\d+(?:\.\d+)?)")

# One-size synonyms
_ONE_SIZE_TOKENS = {"one size", "os", "one-size", "onesize", "1size", "universal",
                    "adult", "sm", "ns"}

# EU → US men's approximate offset
_EU_TO_US_MEN = -33.0   # EU 42 ≈ US 9  (42-33=9)


# ---------------------------------------------------------------------------
# normalize_size
# ---------------------------------------------------------------------------

def normalize_size(raw: str, category: str) -> dict:
    """Return {"system": str, "ordinal": float|None, "canonical": str}."""
    s = raw.strip()
    sl = s.lower().replace("’", "'")  # curly apostrophe normalisation

    # ---- one size ---------------------------------------------------------
    if not s or sl in _ONE_SIZE_TOKENS or sl.startswith("one "):
        return {"system": "one_size", "ordinal": 0.0, "canonical": "One Size"}

    # ---- dual W/M shoe  e.g. "W10/M9", "W5 / M3.5" ----------------------
    m = _RE_DUAL.search(s)
    if m and category in ("footwear", "accessories"):
        w_num = float(m.group(1))
        m_num = float(m.group(2))
        can = f"W{_fmt(w_num)}/M{_fmt(m_num)}"
        return {"system": "dual_shoe", "ordinal": m_num, "canonical": can}

    # ---- EU shoe ----------------------------------------------------------
    m = _RE_EU.search(s)
    if m:
        eu = float(m.group(1))
        us_approx = eu + _EU_TO_US_MEN
        return {"system": "eu_shoe", "ordinal": us_approx, "canonical": f"EU {int(eu)}"}

    # ---- alpha sizes (before range, because "S - M" contains letters) -----
    # Strip leading "US " or "US - " FIRST (before separator normalisation)
    alpha_norm = re.sub(r"^US\s*[-–]?\s*", "", s.upper().strip())
    # Normalise slash / hyphen variants: "S / M", "L - XL", "L/XL", "L-XL"
    alpha_norm = re.sub(r"\s*[/\-–]\s*", " / ", alpha_norm)
    alpha_norm = re.sub(r"\s+", " ", alpha_norm).strip()
    # strip trailing height annotations like "5'8\"-6'3\""
    alpha_norm = re.sub(r"\s+[\d'\"`].*$", "", alpha_norm).strip()
    # check each token
    tokens = [t.strip() for t in re.split(r"\s*/\s*|\s+-\s+", alpha_norm) if t.strip()]
    if all(t in ALPHA_ORDINAL for t in tokens) and tokens:
        ordinals = [ALPHA_ORDINAL[t] for t in tokens]
        mid_ord = sum(ordinals) / len(ordinals)
        # canonical: keep original-ish but uppercase
        can_tokens = " / ".join(tokens)
        if len(tokens) == 1:
            can_tokens = tokens[0]
        return {"system": "alpha", "ordinal": mid_ord, "canonical": can_tokens}

    # ---- "24 Months-3 Years / ..." (months before years) ------------------
    m = _RE_MONTHS_YR.search(s)
    if m:
        # treat months as fractional year
        months, years = int(m.group(1)), float(m.group(2))
        age_mid = (months / 12.0 + years) / 2.0
        return {"system": "kids_apparel", "ordinal": age_mid, "canonical": s}

    # ---- kids' apparel "7-8 Years / 48\"-51\"" ----------------------------
    m = _RE_KIDS_YR.search(s)
    if m:
        lo, hi = float(m.group(1)), float(m.group(2))
        return {"system": "kids_apparel", "ordinal": (lo + hi) / 2.0, "canonical": s}

    # ---- single-year kids "10 Years", "4 Years" ----------------------------
    m = _RE_SINGLE_YR.match(s)
    if m:
        age = float(m.group(1))
        return {"system": "kids_apparel", "ordinal": age, "canonical": s}

    # ---- waist/length "W33 L31" or "US W30 L31 / EU M" --------------------
    m = _RE_WAIST_L.search(s)
    if m:
        w, l = int(m.group(1)), int(m.group(2))
        return {"system": "waist_length", "ordinal": float(w), "canonical": f"W{w} L{l}"}

    # ---- waist-only "W29\"" ------------------------------------------------
    m = _RE_WAIST_O.match(s)
    if m:
        w = int(m.group(1))
        return {"system": "waist_length", "ordinal": float(w), "canonical": f"W{w}"}

    # ---- kids C shoe "10C", "10.5C" ----------------------------------------
    m = _RE_KIDS_C.match(s)
    if m:
        n = float(m.group(1))
        return {"system": "kids_shoe", "ordinal": n, "canonical": f"{_fmt(n)}C"}

    # ---- numeric range "10.5 - 11" ----------------------------------------
    m = _RE_RANGE.search(s)
    if m:
        lo, hi = float(m.group(1)), float(m.group(2))
        mid = (lo + hi) / 2.0
        # Heuristic: if hi < 20 it's probably a shoe size
        if hi < 20:
            return {"system": "us_shoe", "ordinal": mid, "canonical": s}
        # otherwise treat as some other numeric range
        return {"system": "other", "ordinal": mid, "canonical": s}

    # ---- plain numeric "9", "9.5" -----------------------------------------
    m = _RE_PLAIN.match(s)
    if m:
        n = float(m.group())
        if n <= 18 and category == "footwear":
            return {"system": "us_shoe", "ordinal": n, "canonical": f"US {_fmt(n)}"}
        if n <= 18 and category == "accessories":
            # small numeric accessories - treat as us_shoe context if tiny
            return {"system": "us_shoe", "ordinal": n, "canonical": f"US {_fmt(n)}"}
        # larger numbers (volume liters, years-age handled above) → other
        return {"system": "other", "ordinal": n, "canonical": s}

    # ---- leading numeric (e.g. "2XS / XS" already caught above,
    #      but "2XS" alone might not be) ------------------------------------
    sl2 = s.upper().strip()
    if sl2 in ALPHA_ORDINAL:
        return {"system": "alpha", "ordinal": ALPHA_ORDINAL[sl2], "canonical": sl2}

    # fallback
    return {"system": "other", "ordinal": None, "canonical": s}


def _fmt(n: float) -> str:
    """Format a float shoe size as '9' or '9.5'."""
    return str(int(n)) if n == int(n) else str(n)


# ---------------------------------------------------------------------------
# allocate
# ---------------------------------------------------------------------------

def allocate(variants: list[dict], total_depth: int, category: str, rng) -> dict[str, int]:
    """
    Distribute total_depth units across variants following a realistic size curve.
    Returns {ean: int}.  Sum == total_depth.  No negative counts.
    """
    if not variants or total_depth <= 0:
        return {v["ean"]: 0 for v in variants}

    # Resolve normalised info for every variant
    norms = []
    for v in variants:
        ninfo = normalize_size(v.get("size_us", ""), category)
        norms.append((v, ninfo))

    # Group colour variants of the SAME size together, so the curve runs over distinct SIZES
    # (a modal size gets real depth) and each size's units are then split across its colours —
    # instead of the curve diluting to ~1 per (size×colour) variant on low-volume styles.
    groups: dict = {}
    order: list = []
    for i, (v, ni) in enumerate(norms):
        key = (ni["system"], round(ni["ordinal"], 1)) if ni["ordinal"] is not None \
            else ("raw", (v.get("size_us", "") or "").strip().lower() or i)
        if key not in groups:
            groups[key] = []
            order.append(key)
        groups[key].append(i)

    reps = [norms[groups[k][0]] for k in order]          # one representative variant per size
    gweights = _compute_weights(reps, category, rng)      # size-level curve
    gtotals = _lrm(gweights, total_depth, rng)            # units per size

    counts = [0] * len(norms)
    for gi, k in enumerate(order):
        idxs = groups[k]
        sub = _lrm([1.0] * len(idxs), gtotals[gi], rng)   # split a size's units across its colours
        for j, idx in enumerate(idxs):
            counts[idx] = sub[j]

    return {v["ean"]: counts[i] for i, (v, _) in enumerate(norms)}


def _compute_weights(norms: list, category: str, rng) -> list[float]:
    """Return a parallel list of float weights (≥0) for each variant."""
    n = len(norms)
    if n == 0:
        return []

    # Detect predominant system
    sys_counts: dict[str, int] = {}
    for _, ni in norms:
        sys_counts[ni["system"]] = sys_counts.get(ni["system"], 0) + 1
    dominant = max(sys_counts, key=lambda k: sys_counts[k])

    # --- accessories / bag_pack / outdoor / one_size / unorderable → even ---
    if category in ("accessories", "bag_pack", "outdoor") or dominant in ("one_size", "other"):
        return [1.0] * n

    # --- footwear Gaussian --------------------------------------------------
    if category == "footwear" or dominant in ("us_shoe", "kids_shoe", "eu_shoe", "dual_shoe"):
        ordinals = [ni["ordinal"] for _, ni in norms if ni["ordinal"] is not None]
        if not ordinals:
            return [1.0] * n

        # Detect women's: if sizes cluster below 9, treat as women's (mode ≈ 8)
        min_o, max_o = min(ordinals), max(ordinals)
        mid_range = (min_o + max_o) / 2.0

        # Choose modal target based on size system
        if dominant == "kids_shoe":
            modal = mid_range  # centre of present kids range
        elif dominant == "eu_shoe":
            modal = 42 + _EU_TO_US_MEN  # EU 42 → US 9
        elif dominant == "dual_shoe":
            modal = 9.0  # men's equivalent
        else:
            # us_shoe: women's if upper bound ≤ 12 and mid < 8.5
            if max_o <= 12 and mid_range < 8.5:
                modal = 8.0   # women's modal
            else:
                modal = 9.5   # men's modal

        sigma = 1.8
        weights = []
        for _, ni in norms:
            if ni["ordinal"] is None:
                weights.append(0.5)
            else:
                d = (ni["ordinal"] - modal) / sigma
                w = math.exp(-0.5 * d * d)
                weights.append(w)
        return weights

    # --- apparel alpha ------------------------------------------------------
    if dominant == "alpha":
        weights = []
        for _, ni in norms:
            # Use the canonical label (first token if range)
            label = ni["canonical"].split(" / ")[0].upper().strip()
            # Map range midpoint label back to single
            if ni["ordinal"] is not None:
                # find closest alpha label
                label = min(ALPHA_ORDER, key=lambda a: abs(ALPHA_ORDINAL[a] - ni["ordinal"]))
            weights.append(ALPHA_WEIGHTS.get(label, 0.5))
        return weights

    # --- waist_length: mild bell over waist ---------------------------------
    if dominant == "waist_length":
        ordinals = [ni["ordinal"] for _, ni in norms if ni["ordinal"] is not None]
        if not ordinals:
            return [1.0] * n
        modal = sum(ordinals) / len(ordinals)
        sigma = max((max(ordinals) - min(ordinals)) / 3.0, 1.0)
        weights = []
        for _, ni in norms:
            if ni["ordinal"] is None:
                weights.append(0.5)
            else:
                d = (ni["ordinal"] - modal) / sigma
                weights.append(math.exp(-0.5 * d * d))
        return weights

    # --- kids_apparel: mild bell --------------------------------------------
    if dominant == "kids_apparel":
        ordinals = [ni["ordinal"] for _, ni in norms if ni["ordinal"] is not None]
        if not ordinals:
            return [1.0] * n
        modal = sum(ordinals) / len(ordinals)
        sigma = max((max(ordinals) - min(ordinals)) / 3.0, 0.5)
        weights = []
        for _, ni in norms:
            if ni["ordinal"] is None:
                weights.append(0.5)
            else:
                d = (ni["ordinal"] - modal) / sigma
                weights.append(math.exp(-0.5 * d * d))
        return weights

    # fallback: even
    return [1.0] * n


def _lrm(weights: list[float], total: int, rng) -> list[int]:
    """
    Largest-remainder method.  Converts weights to integers summing to total.
    Uses rng for deterministic tie-breaking.
    """
    n = len(weights)
    if n == 0:
        return []

    w_sum = sum(weights)
    if w_sum <= 0:
        # Even split
        base = [total // n] * n
        remainder = total - sum(base)
        for i in range(remainder):
            base[i] += 1
        return base

    # Exact quotas
    quotas = [w / w_sum * total for w in weights]
    floors = [int(math.floor(q)) for q in quotas]
    remainders = [(quotas[i] - floors[i], i) for i in range(n)]

    # Sort by remainder descending; use rng float for tie-breaking
    remainders.sort(key=lambda x: (x[0], rng.random()), reverse=True)

    deficit = total - sum(floors)
    for j in range(deficit):
        floors[remainders[j][1]] += 1

    return floors


# ---------------------------------------------------------------------------
# style_target
# ---------------------------------------------------------------------------

def style_target(category: str, tier_scale: float, rng) -> int:
    """
    Return realistic total stock depth for one style.
    tier_scale=1.0 → flagship store.
    """
    base = BASE_DEPTH.get(category, 9)
    pop = rng.uniform(0.55, 1.35)
    raw = base * tier_scale * pop

    if category == "outdoor":
        result = max(1, min(3, round(raw)))
    else:
        result = max(1, round(raw))

    return result


# ---------------------------------------------------------------------------
# Self-test
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import csv
    import random
    from collections import defaultdict

    CSV_PATH = (
        "/Users/bob/IdeaProjects/m8trx-twin/reference/data/chain/stores"
        "/dec-us-nyc/assortment.csv"
    )

    # -----------------------------------------------------------------------
    # Load assortment
    # -----------------------------------------------------------------------
    rows: list[dict] = []
    with open(CSV_PATH, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for r in reader:
            rows.append(r)

    # Group by category + handle
    by_cat_handle: dict[str, dict[str, list[dict]]] = defaultdict(lambda: defaultdict(list))
    for r in rows:
        by_cat_handle[r["category"]][r["handle"]].append(r)

    seed_rng = random.Random(0)

    # -----------------------------------------------------------------------
    # Print allocated size curves for sample styles
    # -----------------------------------------------------------------------
    def print_curve(handle: str, variants: list[dict], cat: str) -> None:
        total = style_target(cat, 1.0, seed_rng)
        alloc = allocate(variants, total, cat, seed_rng)
        # Sort by ordinal for display
        norms_map = {v["ean"]: normalize_size(v.get("size_us", ""), cat) for v in variants}
        sorted_v = sorted(
            variants,
            key=lambda v: (norms_map[v["ean"]]["ordinal"] or 999)
        )
        print(f"\n  Handle : {handle}")
        print(f"  Target : {total} units")
        line_parts = []
        for v in sorted_v:
            cnt = alloc[v["ean"]]
            can = norms_map[v["ean"]]["canonical"]
            line_parts.append(f"{can}={cnt}")
        print(f"  Curve  : {', '.join(line_parts)}")
        assert sum(alloc.values()) == total, (
            f"Sum mismatch: {sum(alloc.values())} != {total}"
        )
        # modal bucket ≥ any tail bucket
        nonzero = [c for c in alloc.values() if c > 0]
        if nonzero:
            modal_val = max(alloc.values())
            # tail defined as sizes >2 sigma from median ordinal
            ords = [norms_map[v["ean"]]["ordinal"] for v in sorted_v
                    if norms_map[v["ean"]]["ordinal"] is not None]
            if len(ords) >= 3:
                mid = sorted(ords)[len(ords) // 2]
                span = max(ords) - min(ords)
                sigma = span / 4.0 if span > 0 else 1.0
                tails = [alloc[v["ean"]] for v in sorted_v
                         if norms_map[v["ean"]]["ordinal"] is not None
                         and abs(norms_map[v["ean"]]["ordinal"] - mid) > 2.0 * sigma]
                if tails:
                    max_tail = max(tails)
                    assert modal_val >= max_tail, (
                        f"Modal {modal_val} < tail {max_tail} in {handle}"
                    )
        print(f"  OK (sum={total}, modal≥tail)")

    # Pick 4 footwear styles
    print("\n=== FOOTWEAR STYLES ===")
    fw_handles = list(by_cat_handle["footwear"].keys())
    for h in fw_handles[:4]:
        print_curve(h, by_cat_handle["footwear"][h], "footwear")

    # Pick 2 apparel styles
    print("\n=== APPAREL STYLES ===")
    ap_handles = list(by_cat_handle["apparel"].keys())
    for h in ap_handles[:2]:
        print_curve(h, by_cat_handle["apparel"][h], "apparel")

    # -----------------------------------------------------------------------
    # Coverage check
    # -----------------------------------------------------------------------
    def coverage(cat: str, target_pct: float) -> None:
        distinct = set()
        for r in rows:
            if r["category"] == cat:
                distinct.add(r["size_us"])
        resolved = 0
        other_labels = []
        for raw in distinct:
            ni = normalize_size(raw, cat)
            if ni["system"] != "other":
                resolved += 1
            else:
                other_labels.append(raw)
        pct = resolved / len(distinct) * 100 if distinct else 0.0
        status = "PASS" if pct >= target_pct else "FAIL"
        print(
            f"\n{cat.upper()} coverage: {resolved}/{len(distinct)} "
            f"= {pct:.1f}%  [target ≥{target_pct:.0f}%]  [{status}]"
        )
        if other_labels:
            print(f"  Unresolved: {sorted(other_labels)}")
        assert pct >= target_pct, (
            f"{cat} coverage {pct:.1f}% < target {target_pct}%"
        )

    print("\n=== COVERAGE ===")
    coverage("footwear", 85.0)
    coverage("apparel", 80.0)

    print("\nAll assertions passed.")
