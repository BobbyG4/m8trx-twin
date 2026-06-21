# Catalog Attribute-Coding Model — Decathlon (+ MK/Hansae seam)

**Status:** v1, 2026-06-21 (Twin). Delivers CORE-REQ-001 §1–§3.
**Scope:** how brand, classification, and *coded attributes* are represented in the chain
source data, and how the same grain absorbs a second, structurally-different coding model
(MK/Hansae) without a schema change.

> **The one-line point.** A retail platform's attribute layer must survive *real catalog
> messiness*. Two suppliers code the same concept ("this jacket is navy, size M") in
> incompatible ways. The Things/Discover surface is only proven if it handles **both** off
> one model. This doc defines the two and shows `display_lookup` spans them.

---

## The two coding models

### Model A — DECATHLON (this demo catalog)

Verified empirically against the real sources we hold:

- **DC Korea catalogue** (`reference/sample_stores/decathlon-korea-raw.csv`, 55,866 rows)
- **`pantos` WMS dump** (`reference/data/dump-pantos-…sql`) — turns out to be *Decathlon's own*
  Korea warehouse DB (identical `EAN`/`ITEM_CD`/`ITEM_NM` schema), so it corroborates rather
  than adds a model.
- **US Shopify master** (`reference/data/us-catalog/detail/*.json`) — the actual demo source.

What the data shows:

| Aspect | Reality |
|---|---|
| Variant identity | opaque **7-digit article code** (`item_cd`, 95% of KR rows) + EAN-13 (GS1 `360`/`358`, French), **one per colour×size** |
| Colour / size | **display words**, not encoded into the number. KR embeds them as trailing name tokens ("…탱크톱 **민트 그린 M**"); US carries them as variant options ("**Smoked Black**", "**6.5**") |
| Colour cardinality (US demo) | **140 raw strings** for 2,586 SKUs — marketing names ("Deep Sea Turquoise", "Bronze Khaki Green"), plus noise ("Default Color", "No Dye", `\xa0` trailing spaces, Gray/Grey splits) |
| Real coded taxonomy | tags **do** carry coded pairs — `NATURE_ID→NATURE_VALUE` (`25126→Shoes`), `SPORT_ID→SPORT_VALUE` (authentically messy: `442→HIKING/backpacking/mountain trekking`) |

**So "coding" in the Decathlon model = NORMALISATION:** the article number is opaque; colour
is a messy display string that must be mapped to a canonical family + swatch. There is **no
numeric colour code to decode** — inventing one would test our own fiction (CORE-REQ-001 §3
explicit: "don't invent a code; authenticity is the whole point").

### Model B — MK / HANSAE (documented seam, NOT built here)

A structured **style code** whose colour and size **are positional segments**, decoded via a
per-catalog numeric lookup (e.g. colour `560 → Navy`, size `08 → M`). This is the textbook
raw-code→display case. The pattern was specced in the **main-project FRs (core-side)** and is
**segregated from this twin session** — not reproduced here. When an MK/Hansae catalog is
onboarded, its numeric codes drop straight into `display_lookup` (see below); only the *source*
of `raw_value` differs (a code segment vs a messy string).

---

## How `display_lookup` spans both (the portability claim)

`display_lookup(attribute_name, raw_value, display_value, locale, visual)` — the resolution map.
`raw_value` is **whatever is stored on the product**, regardless of how the catalog codes it:

| Model | `attribute_name` | `raw_value` (stored) | `display_value` | `visual` |
|---|---|---|---|---|
| Decathlon | `color` | `Smoked Black` / `민트 그린` | `Black` / `블랙` | `{"swatch":"#1a1a1a"}` |
| MK/Hansae | `color` | `560` | `Navy` | `{"swatch":"#16233f"}` |

Same table, same query path. Discover's "navy jackets" grounds on the display term and resolves
back to whichever raw form the catalog stored. **That is the vertical-portability proof.**

---

## Decisions (twin owns the realism — CORE-REQ-001 §3 open question)

1. **Coding grain = (a) chain-wide, per-tenant.** The demo chain is one tenant (M8trxDemo,
   14 sites) and real Decathlon runs **one corporate catalog**; codes are consistent across
   stores, locale variation rides the `locale` axis. Fits `display_lookup`'s per-tenant grain
   cleanly. (Option (b) per-store divergence would need `identifier_config.site_id` / a model
   extension — not warranted; would be filed as a follow-up TWIN-REQ if ever needed.)
2. **Colour is coded by normalisation** (messy raw → canonical family + swatch). 14 families,
   deterministic keyword classifier (`catalog_coding.py`), 0 fall-throughs over 140 raws.
3. **Size is NOT numerically coded** (authentic to Decathlon). Instead it is a **class-dependent
   display axis** declared in each class's `attributes_schema`:

   | Category | size axis | system |
   |---|---|---|
   | footwear | `size_us` | `footwear_us` |
   | apparel | `size` | `apparel_alpha` |
   | accessories | `size` | `accessory_mixed` |
   | bag_pack | `capacity` | `volume_liters` |
   | outdoor | `size` | `equipment_mixed` |

   This is *why* `attributes_schema` is per-class: the searchable size dimension means a different
   thing per vertical. (Cross-locale size *conversion* — US↔EU↔mondopoint — is a real Decathlon
   mapping available as future enrichment; not built.)
4. **Brand is a first-class facet, not coded** — Shopify `vendor` is already the clean canonical
   passion brand (Quechua, Kiprun, Forclaz…), identical in every locale; no lookup needed.

---

## What was delivered

| File | Content |
|---|---|
| `stores/*/assortment.csv` | +`brand` (←`vendor`), +`classification_key`; colour cleaned of nbsp noise |
| `classification.csv` | 5 category roots + 90 leaves; `lifecycle_type` (`serialized` / `display_model`); per-class `attributes_schema` |
| `display_lookup.csv` | `color` coding: 135 raw → 14 families × {en, fr-FR, ko-KR} = 405 rows, swatch in `visual` |

Builders (deterministic, idempotent, stable keys): `scripts/catalog_coding.py` (config),
`scripts/build_attributes.py` (emits the two coding files), `scripts/build_chain.py` (writes
the assortment columns). Re-seed is byte-identical.

---

## Wiring the MK/Hansae profile later (the seam)

When an MK/Hansae catalog is onboarded:
1. Parse the style code into its colour/size **code segments** (raw values).
2. Emit `display_lookup` rows with `raw_value=<code>`, `display_value=<word>`, per locale.
3. Mark the relevant `attributes_schema` axes `x-coded:true, x-lookup:<attr>` (as colour is here).
4. No change to the table, the loader, or the Discover grounding path.

**Needed from core (segregated now):** the actual MK/Hansae style-code grammar (segment offsets +
the colour/size code tables) from the main-project FRs. Hand it to a twin session to build the
profile against a real MK/Hansae sample.
