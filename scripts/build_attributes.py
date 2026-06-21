#!/usr/bin/env python3
"""
Emit the catalog CODING LAYER for the chain dataset (CORE-REQ-001 §2 + §3):

  reference/data/chain/classification.csv   product taxonomy + per-class attributes_schema
  reference/data/chain/display_lookup.csv   raw->display attribute coding (colour), localized

Reads the canonical flagship assortment (dec-us-denver = full 2,586-SKU master) for the
distinct (category, product_type) classes and the distinct raw colour strings, and the
coding rules from catalog_coding.py. Deterministic, offline, no DB writes.

brand (§1) and classification_key (§2 link) are written onto each store's assortment.csv
by build_chain.py — run build_chain.py after this if the taxonomy keys change.

Coding model: DECATHLON (normalisation — messy raw colour -> canonical family + swatch).
See catalog_coding.py header + CATALOG-CODING-MODEL.md for the MK/Hansae numeric-code seam.
"""
import csv, json, os, collections
import catalog_coding as cc

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHAIN = os.path.join(ROOT, "reference/data/chain")
SRC = os.path.join(CHAIN, "stores/dec-us-denver/assortment.csv")          # full master
LOCALES = ["en", "fr-FR", "ko-KR"]


def load_master_rows():
    return list(csv.DictReader(open(SRC, encoding="utf-8")))


def build_classification(rows):
    """5 category roots + one leaf per (category, product_type). Stable slug keys."""
    # group product_type variants under (category, slug) so case-dupes (Tent/tent) merge
    leaves = collections.defaultdict(collections.Counter)   # (cat, key) -> {orig_name: n}
    cats = collections.OrderedDict()
    for r in rows:
        cat, pt = r["category"], (r["product_type"] or "").strip()
        cats.setdefault(cat, 0)
        cats[cat] += 1
        key = cc.classification_key(cat, pt)
        leaves[(cat, key)][pt or "Misc"] += 1

    out = []
    # roots
    for cat in cats:
        out.append({
            "classification_key": cat, "parent_key": "",
            "name": cc.CATEGORY_LABELS.get(cat, cat.title()),
            "lifecycle_type": "",
            "attributes_schema": json.dumps(cc.attributes_schema(cat),
                                            ensure_ascii=False, separators=(",", ":")),
        })
    # leaves
    for (cat, key), names in sorted(leaves.items()):
        name = names.most_common(1)[0][0]
        # representative product_type for lifecycle (all variants share the slug)
        out.append({
            "classification_key": key, "parent_key": cat,
            "name": name,
            "lifecycle_type": cc.lifecycle_type(name),
            "attributes_schema": json.dumps(cc.attributes_schema(cat),
                                            ensure_ascii=False, separators=(",", ":")),
        })
    return out, cats


def build_display_lookup(rows):
    """One (raw colour, locale) row -> canonical family display + swatch.

    raw_value = cleaned stored colour string (nbsp/space noise removed, marketing
    variation kept). display_value = canonical family, localized per locale. The same
    grain accepts MK/Hansae numeric raws (raw='560' -> 'Navy') with no schema change.
    """
    raws = sorted({cc.clean_value(r["color"]) for r in rows})
    out = []
    for raw in raws:
        fam, swatch = cc.normalize_color(raw)
        sw_hex, fr, ko = cc.COLOR_FAMILIES[fam]
        disp = {"en": fam, "fr-FR": fr, "ko-KR": ko}
        for loc in LOCALES:
            out.append({
                "attribute_name": "color", "raw_value": raw,
                "display_value": disp[loc], "locale": loc,
                "visual": json.dumps({"swatch": sw_hex}, separators=(",", ":")),
            })
    return out, raws


def main():
    rows = load_master_rows()

    classification, cats = build_classification(rows)
    with open(os.path.join(CHAIN, "classification.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["classification_key", "parent_key", "name",
                                          "lifecycle_type", "attributes_schema"])
        w.writeheader(); w.writerows(classification)

    lookup, raws = build_display_lookup(rows)
    with open(os.path.join(CHAIN, "display_lookup.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["attribute_name", "raw_value", "display_value",
                                          "locale", "visual"])
        w.writeheader(); w.writerows(lookup)

    # ── report ──
    roots = [c for c in classification if not c["parent_key"]]
    leaves = [c for c in classification if c["parent_key"]]
    disp_models = [c for c in leaves if c["lifecycle_type"] == "display_model"]
    fams = collections.Counter(cc.normalize_color(r)[0] for r in raws)
    print(f"classification.csv : {len(roots)} roots + {len(leaves)} leaves "
          f"({len(disp_models)} display_model classes)")
    for cat, n in cats.items():
        nleaf = sum(1 for c in leaves if c["parent_key"] == cat)
        print(f"   {cat:<12} {nleaf:>3} leaves  ({n} SKU rows)")
    print(f"display_lookup.csv : {len(raws)} raw colours -> {len(fams)} families "
          f"x {len(LOCALES)} locales = {len(lookup)} rows")
    print(f"   families: {dict(fams.most_common())}")
    print(f"\nwrote classification.csv + display_lookup.csv under {CHAIN}")


if __name__ == "__main__":
    main()
