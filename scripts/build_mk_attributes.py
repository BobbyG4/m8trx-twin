#!/usr/bin/env python3
"""
Emit the MK Trend / Hansae coding profile into the SAME twin grain as the Decathlon profile
(CORE-REQ-001 — the numeric-code model, built). Proves attribute-coding is vertical-portable:
one display_lookup / classification / attributes_schema shape holds two structurally-different
catalogs (Decathlon normalisation model + MK numeric-code model).

Out (reference/data/mk-trend/):
  display_lookup.csv     code->display for the CODED attrs (colour, item, brand, season, division),
                         ×{en,ko}, swatch+family in visual for colour. SAME columns as Decathlon.
  classification.csv     division roots -> item leaves, lifecycle_type, attributes_schema. SAME cols.
  assortment-sample.csv  deterministic synthetic MK line: 15-char style codes decoded end-to-end.

Source of truth: reference/hansaemk/ via mk_coding.py. Deterministic, offline, no DB writes.
"""
import csv, json, os, collections
import mk_coding as mk
import catalog_coding as cc          # shared canonical colour family + swatch

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "reference/data/mk-trend")
LOOKUP_ATTRS = ["color", "item", "brand", "season", "division"]   # the coded (resolvable) attrs


def color_visual(en_name):
    """best-effort cross-catalog swatch: map MK colour name -> shared canonical family + hex."""
    fam, swatch = cc.normalize_color(en_name)
    return {"swatch": swatch, "family": fam}


def build_display_lookup():
    rows = []
    for attr in LOOKUP_ATTRS:
        for code, disp in sorted(mk.CODES.get(attr, {}).items(),
                                 key=lambda kv: (len(kv[0]), kv[0])):
            raw = f"{int(code):02d}" if attr == "color" and code.isdigit() else code
            visual = json.dumps(color_visual(disp["en"]), separators=(",", ":")) if attr == "color" else ""
            for loc in mk.LOCALES:
                rows.append({"attribute_name": attr, "raw_value": raw,
                             "display_value": disp.get(loc) or disp["en"],
                             "locale": loc, "visual": visual})
    return rows


def attributes_schema_mk():
    """MK declares MORE coded axes than Decathlon — colour, brand, season all resolve via lookup."""
    return {"type": "object", "properties": {
        "color":  {"type": "string", "x-coded": True, "x-lookup": "color", "x-facet": True},
        "brand":  {"type": "string", "x-coded": True, "x-lookup": "brand", "x-facet": True},
        "season": {"type": "string", "x-coded": True, "x-lookup": "season", "x-facet": True},
        "size":   {"type": "string", "x-coded": False, "x-system": "mk_brand_size", "x-facet": True},
        "year":   {"type": "string", "x-coded": True, "x-lookup": "year", "x-facet": True},
    }, "required": ["color", "brand"]}


def build_classification():
    schema = json.dumps(attributes_schema_mk(), ensure_ascii=False, separators=(",", ":"))
    # group items by division
    by_div = collections.defaultdict(list)
    for code, disp in mk.CODES["item"].items():
        by_div[mk.division_of(code)].append((code, disp["en"]))
    out = []
    for div in sorted(by_div):
        en, ko = mk.DIVISION_LABELS.get(div, (div.title(), div))
        out.append({"classification_key": div, "parent_key": "", "name": en,
                    "lifecycle_type": "", "attributes_schema": schema})
    for div in sorted(by_div):
        for code, name in sorted(by_div[div]):
            out.append({"classification_key": f"{div}.{code.lower()}", "parent_key": div,
                        "name": name, "lifecycle_type": mk.lifecycle_of(div),
                        "attributes_schema": schema})
    return out, by_div


# representative saleable line for the sample (spans every wearable division)
SAMPLE_ITEMS = ["DC", "DJ", "DP", "JK", "SH", "PT", "OP", "KC", "KT", "TS", "TP",
                "AB", "AP", "AS", "LR"]
SAMPLE_COLORS = [0, 6, 19, 12, 31, 51]      # WHITE, NAVY, BLACK, GREY, PINK, GREEN
SAMPLE_YEAR, SAMPLE_SEASON, SAMPLE_DIV = "22", "1", "P"


def build_sample():
    cols = ["style_color_size", "style_no", "brand", "brand_name", "year", "season", "season_name",
            "item", "item_name", "classification_key", "serial", "division",
            "color", "color_name", "color_ko", "family", "swatch", "size"]
    rows = []
    for brand in ["T", "O", "B", "N", "L"]:
        serial = 0
        for item in SAMPLE_ITEMS:
            div = mk.division_of(item)
            sizes = mk.size_run(brand, item)
            for ci, color in enumerate(SAMPLE_COLORS):
                serial += 1
                cd = mk.CODES["color"].get(str(color), {"en": "", "ko": ""})
                vis = color_visual(cd["en"])
                for size in sizes:
                    scs = mk.make_style_code(brand, SAMPLE_YEAR, SAMPLE_SEASON, item,
                                             serial, SAMPLE_DIV, color, size)
                    rows.append({
                        "style_color_size": scs, "style_no": scs[:10],
                        "brand": brand, "brand_name": mk.resolve("brand", brand),
                        "year": SAMPLE_YEAR, "season": SAMPLE_SEASON,
                        "season_name": mk.resolve("season", SAMPLE_SEASON),
                        "item": item, "item_name": mk.resolve("item", item),
                        "classification_key": f"{div}.{item.lower()}", "serial": f"{serial:03d}",
                        "division": SAMPLE_DIV, "color": f"{color:02d}",
                        "color_name": cd["en"], "color_ko": cd["ko"],
                        "family": vis["family"], "swatch": vis["swatch"], "size": size})
    return cols, rows


def main():
    os.makedirs(OUT, exist_ok=True)

    lookup = build_display_lookup()
    with open(os.path.join(OUT, "display_lookup.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["attribute_name", "raw_value", "display_value", "locale", "visual"])
        w.writeheader(); w.writerows(lookup)

    classification, by_div = build_classification()
    with open(os.path.join(OUT, "classification.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["classification_key", "parent_key", "name", "lifecycle_type", "attributes_schema"])
        w.writeheader(); w.writerows(classification)

    scols, sample = build_sample()
    with open(os.path.join(OUT, "assortment-sample.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=scols); w.writeheader(); w.writerows(sample)

    # ── round-trip verification: every sample style code re-parses + re-resolves ──
    rt_ok = 0
    for r in sample:
        seg = mk.parse_style_code(r["style_color_size"])
        if (seg["brand"] == r["brand"] and seg["item"] == r["item"]
                and f"{int(seg['color']):02d}" == r["color"] and seg["size"] == r["size"]
                and mk.resolve("item", seg["item"]) == r["item_name"]):
            rt_ok += 1
    # colour family coverage (best-effort cross-catalog swatch)
    fams = collections.Counter(color_visual(d["en"])["family"] for d in mk.CODES["color"].values())
    unmapped = fams.get("Unspecified", 0)

    print(f"display_lookup.csv : {len(lookup)} rows  "
          f"({', '.join(f'{a}={len(mk.CODES.get(a, {}))}' for a in LOOKUP_ATTRS)}) × {len(mk.LOCALES)} locales")
    roots = [c for c in classification if not c['parent_key']]
    print(f"classification.csv : {len(roots)} division roots + {len(classification)-len(roots)} item leaves")
    for div in sorted(by_div):
        print(f"   {div:<16} {len(by_div[div])} items")
    print(f"assortment-sample.csv : {len(sample)} SKUs (5 brands × {len(SAMPLE_ITEMS)} items × "
          f"{len(SAMPLE_COLORS)} colours × brand/item size runs)")
    print(f"round-trip (parse+resolve): {rt_ok}/{len(sample)} {'OK' if rt_ok==len(sample) else 'FAIL'}")
    print(f"colour→family swatch: {len(mk.CODES['color'])-unmapped}/{len(mk.CODES['color'])} mapped "
          f"({unmapped} Unspecified)")
    print(f"\nwrote display_lookup.csv + classification.csv + assortment-sample.csv under {OUT}")


if __name__ == "__main__":
    main()
