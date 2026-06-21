#!/usr/bin/env python3
"""
Catalog ATTRIBUTE-CODING config for the Decathlon chain dataset (CORE-REQ-001).

Single source of truth for the *coding layer* the Things/Discover surface needs:
brand, a product classification tree (with per-class attributes_schema), and the
raw->display coding map for attributes (display_lookup).

────────────────────────────────────────────────────────────────────────────────
TWO CODING MODELS (see CATALOG-CODING-MODEL.md). The platform must handle both:

  • DECATHLON model (THIS demo catalog): the variant identity is an opaque 7-digit
    article code (item_cd) + EAN-13, one per colour×size. Colour/size are NOT coded
    into the number — they are *display words* carried alongside (high-cardinality,
    messy: "Smoked Black", "Deep Sea Turquoise", "Default Color", nbsp noise,
    Gray/Grey splits). So Decathlon "coding" = NORMALISATION: messy raw display
    string -> canonical family + swatch. That is what build_attributes.py emits here.

  • MK / HANSAE model (documented seam, not built here): a structured style code
    whose colour/size ARE positional segments decoded via a numeric lookup
    (colour 560 -> Navy). The SAME display_lookup grain handles it (raw="560",
    display="Navy"); only the source of raw_value differs. Pattern lives in the
    main-project FRs (core-side); wire it when an MK/Hansae catalog is onboarded.

We do NOT invent numeric colour codes for Decathlon data — authenticity is the point
(CORE-REQ-001 §3). Colour is coded by normalisation; size is a class-dependent DISPLAY
axis (footwear=US shoe, apparel=alpha, bags=litres, tents=persons) declared per class
in attributes_schema, not forced into a fake code.

Deterministic, offline, no network, no DB writes.
"""
import re

# ── canonical colour families: family -> (swatch hex, FR display, KO display) ──
COLOR_FAMILIES = {
    "Black":     ("#1a1a1a", "Noir",        "블랙"),
    "Gray":      ("#808080", "Gris",        "그레이"),
    "White":     ("#f5f5f5", "Blanc",       "화이트"),
    "Blue":      ("#1f4e8c", "Bleu",        "블루"),
    "Turquoise": ("#10b3b3", "Turquoise",   "청록"),
    "Green":     ("#3f7d3a", "Vert",        "그린"),
    "Yellow":    ("#f2c200", "Jaune",       "옐로우"),
    "Orange":    ("#e8730f", "Orange",      "오렌지"),
    "Red":       ("#c0392b", "Rouge",       "레드"),
    "Pink":      ("#e84d8a", "Rose",        "핑크"),
    "Purple":    ("#7d3ca8", "Violet",      "퍼플"),
    "Brown":     ("#6b4423", "Marron",      "브라운"),
    "Beige":     ("#d9c6a5", "Beige",       "베이지"),
    "Unspecified": ("#cccccc", "Indéfini",  "미지정"),
}

# words that, on their own, are pure base colours (two of them => two-tone name,
# in which the FIRST is the dominant garment colour). Everything else is a modifier
# (Steel, Olive, Dark…) and the RIGHTMOST mapped colour word is the base.
PURE_WORDS = {"black", "white", "blue", "green", "red", "pink",
              "orange", "yellow", "purple", "gray", "grey", "brown"}

# colour word -> family. Only CONFIDENT words are mapped; ambiguous modifiers
# (abyss, sweet, golden, dark, light, deep…) are deliberately left unmapped so the
# reliable base word in the same name wins (e.g. "Abyss Blue"->Blue, "Abyss Gray"->Gray).
WORD_FAMILY = {
    "black": "Black",
    "white": "White", "snowy": "White", "magnolia": "White",
    "gray": "Gray", "grey": "Gray", "graphite": "Gray", "charcoal": "Gray",
    "carbon": "Gray", "storm": "Gray", "pewter": "Gray", "silver": "Gray",
    "ash": "Gray", "zinc": "Gray", "granite": "Gray", "pebble": "Gray",
    "slate": "Gray", "steel": "Gray", "shale": "Gray",
    "blue": "Blue", "navy": "Blue", "indigo": "Blue", "petrol": "Blue",
    "royal": "Blue", "ice": "Blue", "prussian": "Blue", "pacific": "Blue",
    "galaxy": "Blue", "eclipse": "Blue", "midnight": "Blue", "inkpot": "Blue",
    "turquoise": "Turquoise", "teal": "Turquoise", "verdigris": "Turquoise",
    "green": "Green", "olive": "Green", "khaki": "Green", "sage": "Green",
    "laurel": "Green", "cypress": "Green", "mint": "Green", "lime": "Green",
    "alpine": "Green", "acid": "Green", "caribbean": "Green", "jade": "Green",
    "yellow": "Yellow", "sun": "Yellow",
    "orange": "Orange", "cayenne": "Orange", "ochre": "Orange",
    "red": "Red", "crimson": "Red", "brick": "Red", "bordeaux": "Red",
    "burgundy": "Red", "beetroot": "Red", "vermilion": "Red",
    "pink": "Pink", "fuchsia": "Pink", "fushia": "Pink", "fuschia": "Pink",
    "strawberry": "Pink", "magenta": "Pink",
    "purple": "Purple", "mauve": "Purple", "lilac": "Purple", "violet": "Purple",
    "brown": "Brown", "sepia": "Brown", "chocolate": "Brown", "truffle": "Brown",
    "mahogany": "Brown", "cacao": "Brown", "chestnut": "Brown", "cinnamon": "Brown",
    "cedar": "Brown", "bronze": "Brown", "hazelnut": "Brown", "cappucino": "Brown",
    "beige": "Beige", "sand": "Beige", "linen": "Beige", "putty": "Beige",
    "cream": "Beige", "ivory": "Beige", "tan": "Beige",
    # general colour words added for the MK Trend vocabulary (no Decathlon collision)
    "stone": "Gray", "oatmeal": "Beige", "camel": "Beige", "hunter": "Green",
    "mustard": "Yellow", "gold": "Yellow", "wine": "Red",
}

# explicit overrides for tokens the heuristic can't get right / non-colours.
COLOR_OVERRIDE = {
    "default color": "Unspecified", "unspecified": "Unspecified", "no dye": "Unspecified",
    "": "Unspecified", "team edition legacy": "Unspecified", "multicolor": "Unspecified",
    "yellow green": "Green", "blue gray": "Gray", "dark green gray": "Gray",
    "pearl beige": "Beige", "lunar beige": "Beige", "frozen cedar": "Brown",
    "sun gold": "Yellow", "yellow ochre": "Yellow", "golden olive green": "Green",
}


def _clean(raw):
    return re.sub(r"\s+", " ", (raw or "").replace("\xa0", " ")).strip()


# public alias: strip nbsp / collapse whitespace on a stored display value. Removes
# pure noise (trailing nbsp, double spaces) while preserving real marketing variation
# ("Smoked Black" stays "Smoked Black"). Used to clean the colour column at source.
def clean_value(raw):
    return _clean(raw)


def normalize_color(raw):
    """messy raw colour string -> (family, swatch_hex). Deterministic."""
    c = _clean(raw)
    key = c.lower()
    if key in COLOR_OVERRIDE:
        fam = COLOR_OVERRIDE[key]
        return fam, COLOR_FAMILIES[fam][0]
    words = re.split(r"[ /\-]+", key)
    mapped = [(i, WORD_FAMILY[w]) for i, w in enumerate(words) if w in WORD_FAMILY]
    if not mapped:
        return "Unspecified", COLOR_FAMILIES["Unspecified"][0]
    pure = [(i, f) for i, f in mapped if words[i] in PURE_WORDS]
    fam = pure[0][1] if len(pure) >= 2 else mapped[-1][1]
    return fam, COLOR_FAMILIES[fam][0]


# ── classification taxonomy ───────────────────────────────────────────────────
# Roots = the 5 coarse planogram categories already in the data. Leaves = the real
# product_type (finer). classification_key is a STABLE slug so re-seed is idempotent.
CATEGORY_LABELS = {
    "apparel": "Apparel", "footwear": "Footwear", "accessories": "Accessories",
    "bag_pack": "Bags & Packs", "outdoor": "Outdoor & Equipment",
}

# product_types that are floor DISPLAY units (sold to order, not RFID-tagged off the
# rack) -> lifecycle_type 'display_model'. Everything else -> 'serialized'.
DISPLAY_TYPES = {
    "road bike", "gravel bike", "mountain bike", "home trainer", "tent",
    "tent poles", "frame", "inflatable mattress", "mattress", "camp bed",
    "bed base", "folding chair", "shelter", "hammock",
}

# size-axis semantics per category — what the searchable size dimension MEANS.
# This is why attributes_schema is per-class: the size system differs by vertical.
SIZE_SYSTEM = {
    "footwear": ("size_us", "footwear_us"),      # US shoe sizes (6.5, 9.5…)
    "apparel": ("size", "apparel_alpha"),        # XS–5XL (+ waist/length, height)
    "accessories": ("size", "accessory_mixed"),  # alpha / OS / age
    "bag_pack": ("capacity", "volume_liters"),   # 20 L, 50+10 L
    "outdoor": ("size", "equipment_mixed"),      # persons (tents), OS, dimensions
}


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", (s or "").strip().lower()).strip("-") or "misc"


def classification_key(category, product_type):
    """STABLE leaf key: '<category>.<product_type-slug>'. Idempotent across re-seeds."""
    return f"{category}.{slug(product_type)}"


def lifecycle_type(product_type):
    return "display_model" if _clean(product_type).lower() in DISPLAY_TYPES else "serialized"


def attributes_schema(category):
    """JSON-Schema dict declaring the searchable, coded axes for a class.

    color is coded (resolves via the 'color' display_lookup); brand is a facet;
    size is a class-dependent DISPLAY axis (system noted, not numerically coded).
    """
    size_attr, size_system = SIZE_SYSTEM.get(category, ("size", "generic"))
    props = {
        "color": {"type": "string", "x-coded": True, "x-lookup": "color",
                  "x-facet": True},
        "brand": {"type": "string", "x-coded": False, "x-facet": True},
        size_attr: {"type": "string", "x-coded": False, "x-system": size_system,
                    "x-facet": True},
    }
    return {"type": "object", "properties": props, "required": ["color", "brand"]}


if __name__ == "__main__":
    # standalone review: print the full raw-colour -> family mapping
    import csv, os, collections
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    src = os.path.join(root, "reference/data/chain/stores/dec-us-denver/assortment.csv")
    cnt = collections.Counter()
    for r in csv.DictReader(open(src, encoding="utf-8")):
        cnt[r["color"]] += 1
    by_fam = collections.defaultdict(list)
    for raw, n in cnt.items():
        fam, sw = normalize_color(raw)
        by_fam[fam].append((raw, n))
    total = sum(cnt.values())
    print(f"{len(cnt)} raw colour strings -> {len(by_fam)} families "
          f"({total} SKU rows)\n")
    for fam in sorted(by_fam, key=lambda f: -sum(n for _, n in by_fam[f])):
        rows = sorted(by_fam[fam], key=lambda x: -x[1])
        sku = sum(n for _, n in rows)
        print(f"■ {fam:11} {COLOR_FAMILIES[fam][0]}  ({sku} SKUs, {len(rows)} raw)")
        print("   " + ", ".join(f"{raw!r}×{n}" for raw, n in rows[:12])
              + (" …" if len(rows) > 12 else ""))
    unident = sum(n for raw, n in cnt.items()
                  if normalize_color(raw)[0] == "Unspecified"
                  and _clean(raw).lower() not in COLOR_OVERRIDE)
    print(f"\nfell through to Unspecified (no override, no colour word): {unident} SKUs")
