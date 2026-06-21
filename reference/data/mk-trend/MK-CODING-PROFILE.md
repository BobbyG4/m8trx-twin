# MK Trend / Hansae Coding Profile — the numeric-code model (built)

**Status:** v1, 2026-06-21 (Twin). Delivers the **second** coding model named in CORE-REQ-001 —
the one previously documented only as a seam in `CATALOG-CODING-MODEL.md`. Now built against the
real MK Trend spec.

**Source of truth:** `reference/hansaemk/`
- `Hansae Tag Encoding.pdf` — MK Trend "코드체계도" (code-system) spec; what they work from.
- `item_attrib_name.csv` — the attribute schema (the code segments).
- `item_attrib_value.csv` — code→{en,ko} for colour / brand / season / division / year.
- `mktrend-items.csv` — the authoritative **item** code table (cp949).
- `Color(00~99).numbers` — the colour grid (same 100 codes as the CSV; reference only).

> This was operational in the older **Zenven** product with these tables; the DDL changed
> significantly since, so this profile re-expresses the SAME codes into the **current twin coding
> grain** (`display_lookup` / `classification` / `attributes_schema`) — identical to the Decathlon
> profile's output shape. That parity IS the deliverable: one platform layer, two coding models.

---

## The STYLE/COLOR/SIZE code — 15-char positional (PDF p2)

| Pos | 1 | 2–3 | 4 | 5–6 | 7–9 | 10 | 11–12 | 13–15 |
|---|---|---|---|---|---|---|---|---|
| Segment | brand | year | season | item | serial | division | **color** | **size** |
| Coded? | ✅ lookup | ✅ lookup | ✅ lookup | ✅ lookup | counter | ✅ lookup | ✅ lookup | brand-specific display |
| e.g. | `T` | `22` | `1` | `DC` | `001` | `P` | `06` | `95` |
| → | TBJ | 2022 | SPRING | DENIM COAT | #1 | 상품 | NAVY | 95 |

`STYLE NO` = positions 1–10 (brand+year+season+item+serial+division); `STYLE/COLOR/SIZE` adds
colour (2) + size (≤3). Contrast Decathlon: there the variant id is an **opaque** article number
and colour is a **messy display string**; here every classifying segment is a **code that decodes**.

### Code tables (resolve segment → display, en + ko)
| Attribute | Codes | Example |
|---|---|---|
| color | 97 | `06 → NAVY / 네이비`, `19 → BLACK / 블랙` (2-digit, read column-major in the PDF grid) |
| item | 63 | `DC → DENIM COAT`, `JK → WOVEN JACKET`, `AS → ACCESSORY SHOES` |
| brand | 5 | `T→TBJ, O→ANDEW, B→BUCKAROO, N→NBA, L→LPGA` |
| season | 5 | `1→SPRING … 5→RUNNING` |
| division | 2 | `P→상품 (merchandise), M→제품 (product)` |
| year | 7 | `16→2016 … 22→2022` |
| size | — | brand-specific runs (PDF p6): TBJ tops 85–105/F, BUCKAROO XS–XXL, NBA …/3XL, shoes 230–290 |

---

## Mapping to the twin coding grain (output)

Files under `reference/data/mk-trend/` — **same columns as the Decathlon profile**:

| File | Content |
|---|---|
| `display_lookup.csv` | 344 rows — colour/item/brand/season/division codes → display, ×{en,ko}; colour carries `visual={"swatch","family"}` resolved through the **shared** canonical family map (`catalog_coding.normalize_color`), so MK `06→NAVY` and Decathlon `Asphalt Blue` land on the **same** `Blue`/`#1f4e8c` |
| `classification.csv` | 10 division roots + 63 item leaves; `lifecycle_type`; `attributes_schema` declaring colour/brand/season/year as **coded** (`x-lookup`) — MK codes more axes than Decathlon, and the same schema shape expresses it |
| `assortment-sample.csv` | 2,610-SKU deterministic synthetic line (5 brands × 15 items × 6 colours × brand/item size runs); every row carries the 15-char `style_color_size` + decoded columns. Round-trips 2,610/2,610 (parse → re-resolve) |

Regenerate: `python3 scripts/build_mk_attributes.py` (config/parser in `scripts/mk_coding.py`).
Deterministic, idempotent, no DB writes.

---

## The portability claim, demonstrated

```
Decathlon  display_lookup:  color, "Asphalt Blue", Blue,  en, {"swatch":"#1f4e8c"}
MK Trend   display_lookup:  color, "06",           NAVY,  en, {"swatch":"#1f4e8c","family":"Blue"}
```
Same table, same query path, same canonical family. Decathlon codes colour by **normalisation**
(messy string → family); MK codes it by **decode** (numeric code → name). The Things/Discover
surface grounds on the display either way and resolves to the stored raw. Proven across both.

---

## Notes / boundaries

- **Authoritative item source.** `item_attrib_value.csv`'s `item` rows are a **defective off-by-one
  import** (`DC→CULOTTES`, `JK→WOVEN JUMPER`). `mktrend-items.csv` is correct (11/11 vs the PDF) and
  is used for `item`; the other attributes in `item_attrib_value.csv` are aligned (verified vs the
  PDF grid). This real data-quality wrinkle is itself the kind of mess the coding layer must absorb.
- **Size** is a brand-specific **display** axis (not a global code) — declared per class in
  `attributes_schema` (`x-system: mk_brand_size`), same treatment as Decathlon size.
- **Korean fallback.** Where the source lacks a `ko` value (e.g. season), display falls back to `en`
  — not fabricated. No `fr-FR` (MK is a Korean domestic brand).
- **EPC bit-encoding** (packing the code into an RFID tag) is **not** in the provided pages — they are
  the code *system*, not the tag bit-layout. Analogous to Decathlon's separate `EPC-ENCODING` doc;
  out of scope for the attribute-coding profile. `serial_no` is carried; full EPC encoding is TBD.
- **Tenant model.** MK is a **separate catalog/tenant** from the Decathlon chain (brands TBJ/ANDEW/
  BUCKAROO/NBA/LPGA). It exists to prove the second coding model, not to join the Decathlon chain.
