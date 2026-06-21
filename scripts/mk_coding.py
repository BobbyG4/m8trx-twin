#!/usr/bin/env python3
"""
MK Trend / Hansae catalog coding profile — the NUMERIC-CODE model (CORE-REQ-001 seam, built).

The SECOND coding model the platform must handle (contrast: Decathlon's normalisation model in
catalog_coding.py). Source of truth: reference/hansaemk/ — the MK Trend "코드체계도" (code-system)
spec (`Hansae Tag Encoding.pdf`) + the operative code tables (`item_attrib_*.csv`). This was live
in the older Zenven product; the DDL has since changed, so this profile re-expresses the SAME codes
into the current twin coding grain (display_lookup / classification / attributes_schema).

STYLE/COLOR/SIZE — a 15-char positional code (PDF p2):

  pos  1   2-3   4     5-6   7-9     10        11-12   13-15
  seg  brand year  season item  serial  division  color   size
  e.g. T   04    1     DC    001   P         10      95
       TBJ 2004  SPRING DENIM-COAT  상품      OFF-WHITE  95

Unlike Decathlon (opaque article number + messy display colour), here colour/item/brand/season/
division ARE codes that decode to a display via per-attribute lookup tables — e.g. colour 06→NAVY/
네이비, item DC→DENIM COAT. That decode IS the coding. Same display_lookup grain as Decathlon;
only raw_value differs (a code vs a messy string). Locales: en + ko (Korean domestic brand — no FR).

Deterministic, offline, no DB writes.
"""
import csv, json, os
import catalog_coding as cc          # reuse canonical colour family + swatch (cross-catalog unify)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "reference/hansaemk")
LOCALES = ["en", "ko"]

# ── the 15-char STYLE/COLOR/SIZE layout: (segment, start, length); size is the remainder ──
STYLE_LAYOUT = [
    ("brand", 0, 1), ("year", 1, 2), ("season", 3, 1), ("item", 4, 2),
    ("serial", 6, 3), ("division", 9, 1), ("color", 10, 2), ("size", 12, 3),
]
# which segments are CODED (resolve to a display via a lookup table). serial/year are numeric
# but year is also coded (16->2016); serial is a free counter; size is a brand-specific display.
CODED_ATTRS = ["brand", "year", "season", "item", "division", "color"]


def _ko(d):
    return d.get("ko:") or d.get("ko") or ""      # source JSON uses the key "ko:" (sic)


def load_codes():
    """attr -> {code: {"en":..., "ko":...}}.

    colour/brand/season/division/year come from item_attrib_value.csv (verified aligned vs the
    PDF grid). The `item` rows in that file are a DEFECTIVE off-by-one import (DC→CULOTTES, …),
    so item is sourced from mktrend-items.csv instead — the clean, authoritative item table
    (11/11 vs PDF; cp949-encoded Korean). Keeps the profile faithful to the spec.
    """
    out = {}
    # 1) coded attrs from item_attrib_value.csv — EXCLUDING the defective `item` rows
    path = os.path.join(SRC, "item_attrib_value.csv")
    for r in csv.reader(open(path, encoding="utf-8-sig")):
        if len(r) < 3:
            continue
        code, attr = r[0].strip(), r[1].strip()
        if attr == "item":
            continue                              # defective here — loaded from mktrend below
        try:
            d = json.loads(r[2])
        except Exception:
            continue
        en = (d.get("en") or "").strip()
        if not en and not _ko(d):
            continue                              # skip blank/reserved codes (e.g. colour 17,18)
        out.setdefault(attr, {})[code] = {"en": en, "ko": _ko(d).strip() or en}
    # 2) item from the authoritative mktrend-items.csv (cp949)
    out["item"] = {}
    ipath = os.path.join(SRC, "mktrend-items.csv")
    for r in csv.DictReader(open(ipath, encoding="cp949")):
        code = (r.get("value") or "").strip()
        if not code:
            continue
        en = (r.get("en") or "").strip().replace("?", "-")     # cp949 stray char on a few rows
        ko = (r.get("ko") or "").strip()
        out["item"][code] = {"en": en, "ko": ko or en}
    return out


CODES = load_codes()


def _color_key(raw):
    """colour appears 2-digit in the style code ('06'); table is keyed unpadded ('6')."""
    raw = (raw or "").strip()
    return str(int(raw)) if raw.isdigit() else raw


def resolve(attr, raw, locale="en"):
    """code -> display string for a locale. Returns '' if not a coded attr / unknown code."""
    raw = (raw or "").strip()
    key = _color_key(raw) if attr == "color" else raw
    rec = CODES.get(attr, {}).get(key)
    return (rec.get(locale) or rec.get("en")) if rec else ""


def parse_style_code(code):
    """15-char STYLE/COLOR/SIZE -> {segment: raw_value}. Size is the remainder (1-3)."""
    seg = {}
    for name, start, length in STYLE_LAYOUT:
        seg[name] = (code[start:] if name == "size" else code[start:start + length]).strip()
    return seg


def make_style_code(brand, year, season, item, serial, division, color, size):
    """compose a canonical 15-char style code (colour zero-padded to 2; size left as-is, <=3)."""
    return f"{brand}{year:>2}{season}{item}{int(serial):03d}{division}{int(color):02d}{size}"


# ── item-code taxonomy: 63 item codes -> division (the classification parent) ──────────────
# rule by leading char + explicit exceptions (see PDF p4).
_DIV_BY_PREFIX = {"A": "accessory", "D": "denim", "K": "knit", "L": "leather",
                  "T": "cut_and_sew", "R": "rainwear", "C": "woven", "J": "woven",
                  "O": "woven", "P": "woven", "S": "woven", "V": "woven",
                  "U": "underwear", "F": "material"}
_DIV_EXC = {"AI": "underwear", "AJ": "accessory", "DW": "woven", "XX": "material"}
DIVISION_LABELS = {
    "accessory": ("Accessory", "액세서리"), "denim": ("Denim", "데님"),
    "woven": ("Woven", "우븐"), "knit": ("Knit", "니트"),
    "cut_and_sew": ("Cut & Sew", "컷앤소"), "leather": ("Leather", "가죽"),
    "rainwear": ("Rainwear", "레인웨어"), "underwear": ("Underwear", "이너웨어"),
    "material": ("Material", "원부자재"), "non_merchandise": ("Non-Merchandise", "비상품"),
}


def division_of(item_code):
    item_code = (item_code or "").strip().upper()
    if item_code in _DIV_EXC:
        return _DIV_EXC[item_code]
    if item_code[:1] in ("Y", "Z"):
        return "non_merchandise"
    return _DIV_BY_PREFIX.get(item_code[:1], "other")


def lifecycle_of(division):
    if division in ("material",):
        return "material"
    if division == "non_merchandise":
        return "non_merchandise"
    return "serialized"


# garment kind (drives the brand-specific size run) — by item-code semantics.
_BOTTOMS = {"DP", "DK", "DO", "PT", "SK", "TP", "TK", "CU", "RB"}
_SHOES = {"AS"}
_ACCESSORY_SIZED = {"AG", "AM", "AO", "AP", "AT", "AX"}    # gloves/muffler/socks/cap/belt: S-L,F


def garment_kind(item_code):
    item_code = (item_code or "").strip().upper()
    div = division_of(item_code)
    if item_code in _SHOES:
        return "shoes"
    if div in ("accessory",) and item_code not in _ACCESSORY_SIZED:
        return "free"                                       # bags/wallets/etc: one size (F)
    if item_code in _ACCESSORY_SIZED:
        return "accessory"
    if item_code in _BOTTOMS:
        return "bottom"
    return "top"


# brand-specific size runs (PDF p6), simplified to the demo-relevant set per garment kind.
SIZE_RUNS = {
    "T": {"top": ["85", "90", "95", "100", "105", "F"], "bottom": ["28", "30", "32", "34", "36", "F"],
          "shoes": ["240", "250", "260", "270", "280"], "accessory": ["S", "M", "L", "F"], "free": ["F"]},
    "O": {"top": ["85", "90", "95", "100", "105", "F"], "bottom": ["28", "30", "32", "34", "36", "F"],
          "shoes": ["240", "250", "260", "270", "280"], "accessory": ["S", "M", "L", "F"], "free": ["F"]},
    "B": {"top": ["XS", "S", "M", "L", "XL", "XXL", "F"], "bottom": ["28", "30", "32", "34", "36", "F"],
          "shoes": ["240", "250", "260", "270", "280"], "accessory": ["S", "M", "L", "F"], "free": ["F"]},
    "N": {"top": ["XS", "S", "M", "L", "XL", "XXL", "3XL", "F"], "bottom": ["71", "76", "81", "86", "91", "F"],
          "shoes": ["240", "250", "260", "270", "280", "290"], "accessory": ["S", "M", "L", "F"], "free": ["F"]},
    "L": {"top": ["80", "85", "90", "95", "100", "110"], "bottom": ["66", "70", "73", "76", "79"],
          "shoes": ["230", "240", "250", "260", "270"], "accessory": ["S", "M", "L", "F"], "free": ["F"]},
}


def size_run(brand, item_code):
    return SIZE_RUNS.get(brand, SIZE_RUNS["T"]).get(garment_kind(item_code), ["F"])


if __name__ == "__main__":
    # self-test: parse + resolve a few canonical codes
    print("code tables loaded:", {a: len(v) for a, v in CODES.items()})
    samples = ["T041DC001P1095", "B043JK018P8026", "N055AC001P8003XL", "L051TS009P0695"]
    for s in samples:
        seg = parse_style_code(s)
        disp = {k: resolve(k, v) for k, v in seg.items() if k in CODED_ATTRS}
        print(f"\n{s}")
        print(f"  segments: {seg}")
        print(f"  brand={disp['brand']} {disp['season']} {disp['item']} "
              f"colour={disp['color']} size={seg['size']} div={division_of(seg['item'])}")
    # round-trip
    rebuilt = make_style_code("T", "04", "1", "DC", 1, "P", 10, "95")
    print(f"\nround-trip make->parse: {rebuilt} -> {parse_style_code(rebuilt)}")
