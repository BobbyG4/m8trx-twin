# Decathlon Chain Dataset — Data Spec & Handoff

**Status:** v2, generated 2026-06-22 (Twin Session 7). Deterministic, regenerable.
**Purpose:** populate the **M8trxDemo** tenant as a realistic multi-store retail chain so every
m8trx surface (web / backend / edge) is exercised against chain-scale data — real timezones,
currencies, org hierarchy, staff, inventory, and location — instead of one store or office boxes.

**This is the cross-team handoff artifact.** Twin assembled the *data*; **backend/web own the
*ingest schema*** (org/site/user-role tables, catalog-import + EPC-config format) and publish the
requirements. Twin maps these files onto whatever schema they specify. We deliberately did **not**
reverse-engineer core's tables, invent capability strings, or write to mother.

> **Realistic, not accurate.** Demo-calibration data. Addresses/prices/headcounts are plausible,
> not audited. The dataset is disposable — regenerate any time with the two builders.

---

## How to regenerate

```bash
python3 scripts/localize_names.py       # EN->FR/KO product-name map (enrichment, run first)
python3 scripts/build_chain.py          # inventory: manifest + per-store assortment/EPCs (+brand/classification_key)
python3 scripts/build_attributes.py     # coding layer: classification.csv + display_lookup.csv (reads flagship assortment)
python3 scripts/build_staff_roster.py   # org/staff: roster + org-chart
```
Fully deterministic — regeneration is byte-identical (verified by md5). Per-store seed is a
**stable SHA-256** of the store id (not Python's per-process-salted `hash()`); roster `seed=42`.
Config lives in `scripts/chain_config.py` (stores, regions, tiers, org template, name pools) and
`scripts/catalog_coding.py` (brand/classification/colour coding — CORE-REQ-001) — the single
source of truth all builders read.

---

## The chain (10 stores, 1 HQ, 3 regional offices)

| Store ID | Region | City | Timezone | Cur | Tier | SKUs | EPCs |
|---|---|---|---|---|---|---|---|
| dec-us-denver | US | Denver, CO | America/Denver | USD | Flagship | 2,586 | 15,005 |
| dec-us-nyc | US | New York, NY | America/New_York | USD | Flagship | 2,586 | 14,642 |
| dec-us-sf | US | San Francisco, CA | America/Los_Angeles | USD | Flagship | 2,586 | 14,580 |
| dec-fr-paris | FR | Paris | Europe/Paris | EUR | Flagship | 2,586 | 14,943 |
| dec-fr-lyon | FR | Lyon | Europe/Paris | EUR | Large | 2,408 | 9,669 |
| dec-fr-lille | FR | Villeneuve-d'Ascq | Europe/Paris | EUR | Large | 2,410 | 9,797 |
| dec-fr-marseille | FR | Marseille | Europe/Paris | EUR | Medium | 1,804 | 4,816 |
| dec-fr-bordeaux | FR | Bordeaux | Europe/Paris | EUR | Medium | 1,800 | 4,993 |
| dec-kr-seoul | KR | Seoul | Asia/Seoul | KRW | Large | 2,409 | 9,507 |
| dec-kr-busan | KR | Busan | Asia/Seoul | KRW | Medium | 1,800 | 4,723 |

**Totals:** **14 sites = 10 retail + 4 office** · 22,975 store-SKU rows (2,586 distinct master
SKUs) · **102,675 EPCs** (~18.4k back-of-house, ~84.3k on floor) · 251 users (250 staff +
tenant-admin). **5 distinct IANA timezones** spanning UTC-8 → UTC+9.

Every site carries a **`site_type`** and **`latitude`/`longitude`** (WGS84 decimal degrees,
geocoded to the address). **Office sites** (HQ + 3 regional) have a real address + timezone but
**no space, zones, fixtures, inventory, or sensors**
(`has_space=has_inventory=has_sensors=false`) — staff bind to them exactly like a retail site:

| Office site | Role | City | Timezone | Staff |
|---|---|---|---|---|
| `dec-hq-global` | Global HQ | Villeneuve-d'Ascq, FR | Europe/Paris | 8 |
| `dec-us-region` | US regional | New York, NY | America/New_York | 4 |
| `dec-fr-region` | FR regional | Paris, FR | Europe/Paris | 4 |
| `dec-kr-region` | KR regional | Seoul, KR | Asia/Seoul | 4 |

---

## Files

```
reference/data/chain/
├── chain-manifest.json              # site directory: 10 retail stores[] + 4 office_sites[], site_type, tz, currency, counts
├── regions.json                     # per-region currency/locale/price-localization rules
├── classification.csv               # product taxonomy: 5 roots + 90 leaves, lifecycle_type, attributes_schema (CORE-REQ-001 §2)
├── display_lookup.csv               # raw->display attribute coding (colour), localized en/fr-FR/ko-KR (CORE-REQ-001 §3)
├── CATALOG-CODING-MODEL.md          # the coding model (Decathlon normalisation + MK/Hansae seam) — read this for §1–§3
├── localization/
│   └── name-localization.csv        # ean -> name_en / name_fr / name_ko (master, 2,586 SKUs)
├── stores/<store-id>/
│   ├── assortment.csv               # product list for the store (localized name/price/currency/locale)
│   ├── epcs.csv                     # one row per physical unit (RFID tag) — the inventory
│   └── layout.json                  # this store's UNIQUE floor (parametric, 0-overlap asserted; build_layout.py)
├── staff/
│   ├── roster.csv                   # 250 people, flat, with manager_id reporting links
│   ├── org-chart.json               # same people as a nested tree rooted at the CEO
│   └── users.csv                    # 251 provisioning-ready users (250 staff + tenant-admin)
└── CHAIN-DATA-SPEC.md               # this file
```

---

## Schema — `chain-manifest.json`

Top level: `chain`, `generated`, `note`, `hq{}`, `layout_reference`, `epc_encoding_reference`,
`totals{}`, `stores[]` (10 retail), `office_sites[]` (4 office). Each `stores[]` entry:

| Field | Meaning |
|---|---|
| `id` | stable site key, used as `store_id` FK in `epcs.csv` and `site` in users/roster |
| `site_type` | `retail` for all `stores[]` entries |
| `has_space` / `has_inventory` / `has_sensors` | all `true` for retail |
| `region` | `US` / `FR` / `KR` |
| `country`, `city`, `state`, `address` | location (real-anchored) |
| `latitude`, `longitude` | WGS84 decimal degrees, geocoded to address — present on all retail and office sites |
| `timezone` | **IANA** zone (e.g. `Europe/Paris`) — use for all per-store local-time logic |
| `currency`, `locale` | ISO currency + BCP-47 locale for the store's market |
| `tier`, `tier_label` | Flagship / Large / Medium — drives SKU breadth + stock depth |
| `sqm` | selling-floor area (nominal — flagship 600 / large 520 / medium 420) |
| `space` | the site's **one space**: `name`, `template` (→ `stores/<id>/layout.json`), `sqm`, `footprint_mm`, `zones_total`, `area_zones`, `fixture_zones`, `try_on_zones` (3), `departments`, `backroom_racks`, `gondola_grid` — **counts vary per store** (flagship ~120–143 zones / medium ~51–56) |
| `departments[]` | top-level list of `{code, key, label}` sport-universe bands for this store (flagship 6–7 / large 4–5 / medium 2–3) |
| `sku_count`, `epc_count` | actual rows in the store's two files |
| `epc_by_category` | piece counts per planogram bucket |
| `epc_by_department` | piece counts per sport-universe department key |
| `boh_epc` | back-of-house EPC count (~18% of store total, staged on `backroom_rack` fixtures in Z-05) |
| `files` | relative paths to the store's assortment/EPC CSVs |

Each `office_sites[]` entry (HQ + 3 regional): `id`, `site_type="office"`, `office_role`
(`global_hq`/`regional_hq`), `region`, `country`, `city`, `address`, `latitude`, `longitude`,
`timezone`, `has_space=has_inventory=has_sensors=false`, `sku_count=epc_count=0`. Office sites
have **no** store dir, no `space/zone/fixture` rows — they provision as a **site row only** and
exist to host staff (HQ + regional org). They are valid `site` targets in `users.csv`.

## Schema — `stores/<id>/layout.json` (one UNIQUE floor per retail site)

Each retail store has its **own** Decathlon-City floor, generated parametrically by
`build_layout.py` from `sha256(store_id)` (footprint, gondola grid, aisles, specialty mix, checkout
side all vary by tier+seed; 0 overlaps + 0 out-of-bounds asserted). Office sites get no layout.
`STORE-LAYOUT.md` documents the shared grammar; `scripts/render_floorplans.py` renders each to SVG.

> **Fixtures are zones.** Per core's model (corrections doc §1), a fixture is a `zone` with
> `zone_type='fixture'` — **not** a row in the `fixture` table (unused). So each store's `zones[]` is
> a unified list: 11 area zones + N fixture-zones, all children of the store's one space. The
> `fixture` codes in that store's `epcs.csv` resolve to the **fixture-zone of the same `code`**, keyed
> `(store_id, code)`. Codes (`GF-R1-U2`, `PW-01`, `GPS-01`…) repeat across stores but geometry differs.

- **`departments[]`** (top-level, mirrors `chain-manifest.json`): `[{code, key, label}]` — the
  sport-universe bands for this store (flagship 6–7 / large 4–5 / medium 2–3).
- **`zones[]`** — each: `code`, `name`, `zone_type`, `parent` (`"space"`), `area_sqm`, plus the
  **mother-canonical geometry** (SRID 0, mm, Z=0): `geometry_type` (`polygon`|`circle`), `geometry`
  (WKT — `POLYGON Z` ring for polygons, center-only `POINT Z` for circles), `properties` (`{}` for
  polygons; `{centerX,centerY,radiusX,radiusY,rotation}` for circles/ellipses). `rect_mm{x1,y1,x2,y2}`
  is a twin-side bounding-box convenience. (Round racks + promo islands use `circle`; see STORE-LAYOUT.md § Geometry format.)
  - **Area zones** (12–17, varies by store): `zone_type` ∈ `entry_exit` / `checkout` / `region` / `try_on_zone`;
    try-on zones add `try_on_profile` (`footwear_bench`/`equipment_test`/`apparel_room`); plus
    `customer_accessible`. Non-department area zones use `Z-0N` codes; department band zones use `D-0N` codes.
    - **Department bands** (`zone_type='region'`, codes `D-01`…`D-0N`): the main selling-floor bands,
      each with a `department` key (e.g. `"hike_camp"`) and a name (e.g. `"Hiking, Trekking & Camping"`).
      Replaces the old single "Main Sales Floor" region.
    - **Stockroom (Z-05)**: `zone_type='region'`, `customer_accessible=false` — the back-of-house holding
      area. Contains `receiving_dock` (code `RCV-01`) and `backroom_rack` (codes `BR-01`…`BR-0N`) fixtures.
  - **Fixture-zones** (varies, ~39 medium → ~115+ flagship): `zone_type='fixture'`, plus `in_area_zone`
    (points at their department band `D-0N` for floor fixtures, `Z-05` for BOH fixtures) and
    `fixture_category` (`gondola_front`/`perimeter_west`/`gps_case`/`backroom_rack`/`receiving_dock`/…).
    Floor fixtures carry a `department` field (sport-universe key). GPS cases + checkout/service
    counters exist but are unstocked (no watch SKUs; non-merchandise).
- **`counts`** — `zones_total`, `area_zones`, `fixture_zones`, `try_on_zones` (3), `departments`,
  `backroom_racks`, `gondola_rows`, `gondola_units`. **`footprint_mm`** — this store's `{width, depth}`.
- **`crossing_slices[]`** (1) — `CS-01` main entrance EAS gate (traffic/EAS).
- **`sensors[]`** (5) — 3 Xovis 3D cameras + RFID overhead + EAS gate (planned placement).
- **Geometry:** millimeters, origin SW corner, footprint 24,000 × 25,000 mm, rectangles → `POLYGON Z` SRID 0.

---

## Schema — `stores/<id>/assortment.csv`

One row per **SKU carried by that store** (variant-level: a size/color is its own row).

Full column order: `ean`, `item_cd`, `brand`, `category`, `classification_key`, `department`,
`product_type`, `name_en`, `name_local`, `size_us`, `color`, `price_usd`, `price_local`,
`currency`, `locale`, `fixture`, `depth`, `handle`, `image`, `n_images`.

| Column | Notes |
|---|---|
| `ean` | EAN-13 barcode (real Decathlon GS1-FR) — the product's stable identity |
| `item_cd` | Decathlon article/SKU code from the source catalog |
| `brand` | Decathlon passion brand (Quechua, Kiprun, Forclaz, …) — from Shopify `vendor`, authoritative. **CORE-REQ-001 §1** |
| `category` | planogram bucket: footwear / apparel / accessories / bag_pack / outdoor |
| `classification_key` | leaf class key → `classification.csv` (`<category>.<product_type-slug>`, stable). **CORE-REQ-001 §2** |
| `department` | sport-universe key the style belongs to: `hike_camp` / `running` / `climb` / `snow` / `cycling` / `water` / `general`. Matches the store's department bands. |
| `product_type` | finer source type (Shoes, Jacket, Backpack, …) |
| `name_en` | English product name (real, from the US catalog) |
| `name_local` | localized display name in the store's language: `name_en` (US), French (FR), Korean (KR). From `localization/name-localization.csv`. Machine gloss — see Localization |
| `size_us`, `color` | variant attributes. `color` is cleaned of nbsp noise + coded to a canonical family via `display_lookup.csv`; `size_us` is a class-dependent display axis (see `attributes_schema`) |
| `price_usd` | base USD price (master) |
| `price_local` | price in the store's currency (`localize_price` in chain_config.py) |
| `currency`, `locale` | matches the store's region |
| `fixture` | planogram fixture code (e.g. `GF-R1-U2`, `PW-01`) per STORE-LAYOUT.md — always a customer-facing floor fixture |
| `depth` | units of this style (across all sizes/colours) stocked at this fixture — a **realistic per-style size-curve allocation**: units are distributed as a bell over the style's distinct sizes (modal sizes deeper, thin tail sizes may be 0), then split across colours. NOT a flat per-size count. Total units = count of EPC rows for this style. |
| `handle`, `image`, `n_images` | Shopify handle + **real product image URL** + image count |

> Same `ean` appears in multiple stores — that's the same *product* stocked in different stores.
> Distinct physical units live in `epcs.csv`.

## Schema — `stores/<id>/epcs.csv`

One row per **physical unit** (one RFID tag = one sellable item). This is the inventory volume.

| Column | Notes |
|---|---|
| `epc` | 24-hex **SGTIN-96** tag, scanner-decodable back to its EAN |
| `ean`, `item_cd`, `category` | denormalized from the assortment row |
| `fixture` | the zone code where this unit is physically located — either a customer-facing floor fixture (e.g. `GF-R1-U2`, `PW-01`) **or** a `backroom_rack` (e.g. `BR-01`…`BR-08`). ~18% of every style's units are staged in back-of-house on backroom racks; the rest are on the sales floor. |
| `store_id` | owning store |

**EPC encoding** — validated Decathlon SGTIN-96 (`filter=1`, `partition=6`, EAN-derived
company/itemref, sparse 38-bit serial). Every EPC across all 10 stores is **globally unique** and
**round-trips** to its EAN. Full spec + clean-room encoder: `reference/data/EPC-ENCODING-DECATHLON.md`.

---

## Schema — the catalog coding layer (`classification.csv` + `display_lookup.csv`)

Delivers **CORE-REQ-001** — brand, classification + `attributes_schema`, and coded attributes.
Full rationale (Decathlon normalisation model + the MK/Hansae numeric-code seam) and the grain
decision live in **`CATALOG-CODING-MODEL.md`**. Both files are **tenant-scoped** (one corporate
coding scheme chain-wide; locale variation rides the `locale` axis). Stable keys → idempotent re-seed.

### `classification.csv` — product taxonomy
One row per class: **5 category roots** (`parent_key=""`) + **90 leaves** (one per real
`product_type`). `assortment.csv.classification_key` FKs the leaf.

| Column | Notes |
|---|---|
| `classification_key` | stable slug: roots = `apparel`/`footwear`/…; leaves = `<category>.<product_type-slug>` (e.g. `footwear.shoes`) |
| `parent_key` | root key for leaves; empty for roots |
| `name` | human label (`Footwear`, `Shoes`) |
| `lifecycle_type` | `serialized` (RFID-tagged sellable unit) or `display_model` (floor demo unit, sold to order — bikes/tents/furniture; 14 classes). Empty on roots |
| `attributes_schema` | JSON Schema of the class's searchable axes — `color` (`x-coded:true, x-lookup:color`), `brand` (facet), and the **class-dependent size axis** (footwear→`size_us`/`footwear_us`; apparel→`size`/`apparel_alpha`; bag_pack→`capacity`/`volume_liters`; …). This is what makes attributes vertical-portable |

### `display_lookup.csv` — raw→display attribute coding
The resolution map. Currently codes **`color`**: 135 raw strings × {`en`, `fr-FR`, `ko-KR`} = **405 rows**.

| Column | Notes |
|---|---|
| `attribute_name` | the coded attribute (`color`) |
| `raw_value` | the **stored** value on the product (Decathlon: messy display string `Smoked Black`; MK/Hansae seam: a numeric code `560`) |
| `display_value` | canonical family, localized (`Black` / `Noir` / `블랙`) |
| `locale` | `en` / `fr-FR` / `ko-KR` |
| `visual` | JSON, e.g. `{"swatch":"#1a1a1a"}` |

> **Authenticity (CORE-REQ-001 §3):** Decathlon does **not** numerically code colour, so we don't
> invent codes — colour is coded by *normalisation* (raw display → canonical family). Size is **not**
> coded either; it's a class-dependent display axis declared in `attributes_schema`. The numeric-code
> flavour (MK/Hansae) drops into the same `display_lookup` grain when such a catalog is onboarded.

---

## Schema — `staff/roster.csv` (250 people)

| Column | Notes |
|---|---|
| `staff_id` | `EMP-NNNNN`, stable key |
| `full_name` | romanized display name (KR is family-first, e.g. `Kim Min-jun`) |
| `name_local` | native script — identical for US/FR; **hangul** for KR (e.g. `김민준`) |
| `role` | machine role key (see taxonomy) |
| `role_label` | human label (Dept Leaders include their department) |
| `department` | for department_leader / sales_associate; else blank |
| `region` | US / FR / KR |
| `home_store_id` | store id, regional-office id (`dec-xx-region`), or HQ id (`dec-hq-global`) |
| `manager_id` | `staff_id` of this person's manager (blank only for the CEO) — **fully resolvable** |
| `timezone` | IANA zone of their home location |
| `email` | `given.family@decathlon-demo.com` (deduped) |
| `status` | `active` / `inactive` |
| `status_reason` | for inactive: terminated / resigned / on_long_leave / deactivated / account_locked |
| `hire_date` | `YYYY-MM-DD` |

`org-chart.json` is the same roster as a nested tree (`reports[]`) rooted at the CEO — every one of
the 250 is reachable.

### Role taxonomy (for backend to map → perms-v3 capabilities)

These are **descriptive labels only.** Twin emits no capability strings; backend decides the
role→capability mapping during the user/role schema design.

- **HQ (home `dec-hq-global`):** `ceo`, `cfo`, `coo`, `cio`, `global_merch_director`,
  `global_lp_director`, `global_hr_director`, `global_data_lead`
- **Regional (home `dec-xx-region`):** `regional_director`, `regional_ops_manager`,
  `regional_merch_manager`, `regional_lp_manager`
- **Store:** `store_manager`, `assistant_manager`, `department_leader`, `sales_associate`,
  `cashier`, `stock_logistics`, `store_lp`

Reporting: associates → dept leaders → assistant/store managers → regional director → COO → CEO.
Store LP reports to the **regional** LP manager (intentional cross-store line).

### Inactive accounts (the "broken" set)

**30 of 250 accounts are `status=inactive`**, deliberately spread across HQ-region and every store,
with varied `status_reason`. This exercises every surface against deactivated/terminated accounts.
CEO, C-suite, regional directors, and store managers are kept active so the tree always resolves;
some **mid-level managers are inactive on purpose**, so a few reports are *orphaned under an inactive
manager* — a real-world state worth testing (a deactivated manager whose reports are still active).

## Schema — `staff/users.csv` (provisioning-ready, tenant scope)

**This is the file to provision users from RIGHT NOW.** A flat, denormalized view of the roster
for **tenant-level** user provisioning into M8trxDemo — no reporting tree, no perms hierarchy.

| Column | Notes |
|---|---|
| `email` | login identity — unique. 250 staff use fake `given.family@decathlon-demo.com`; the tenant-admin uses a **real Google mailbox** (see below) |
| `display_name` | romanized full name |
| `name_local` | native script (hangul for KR) |
| `role`, `role_label` | role key + human label (backend maps role → capabilities) |
| `site` | assigned site id — a **retail** store (`dec-us-denver`) for store staff, or an **office** site (`dec-hq-global`, `dec-us-region`…) for HQ/regional staff. **All 250 staff are site-bound.** Blank only for the tenant-admin |
| `region` | US / FR / KR (blank for the tenant-admin) |
| `timezone` | IANA zone |
| `status` | `active` (221) / `inactive` (30) |
| `status_reason` | for inactive accounts |
| `tenant_admin` | `true` for exactly one row, else `false` |

**Tenant-admin:** `zenvendemo@gmail.com` (`tenant_admin=true`, active, tenant-scoped) — the real
login that signs into M8trxDemo via Google OAuth. The other 250 are fake-email accounts.

**Totals:** 251 users · **250 site-assigned** (10 retail + 4 office sites) · **1 tenant-scoped**
(the tenant-admin only) · 30 inactive. Set `scripts/chain_config.py → TENANT` to change the admin
mailbox/name.

---

## Localization (`regions.json`)

| Region | Currency | Locale | Price factor (demo) | Rounding |
|---|---|---|---|---|
| US | USD | en-US | ×1.00 | $0.01 |
| FR | EUR | fr-FR | ×0.95 | €0.01 |
| KR | KRW | ko-KR | ×1350 | ₩100 |

Factors are demo constants, **not live FX**.

**Product names** are localized EN→FR and EN→KO by `scripts/localize_names.py` — a deterministic
phrase-map glosser that **preserves brand + model tokens** (Quechua, Kiprun, MH500, NH100…) and
translates gender / product-type / descriptor terms. Output lives in
`localization/name-localization.csv` and is wired into each store's `name_local`.
Coverage: ~97% of names carry a translated type; ~3% retain a stray English noun in compound
phrasings. This is a **machine gloss for demo display**, not certified i18n (word order and
adjective agreement are approximate). Example, one SKU across regions:
- US `Wedze Men's 100 Mid-Length Warm Ski Jacket`
- FR `Wedze 100 Veste Mi-long Chaud de Ski Homme`
- KR `Wedze 100 남성용 미들렝스 보온 스키 재킷`

---

## Known gaps / queued enrichment (not blockers for ingest)

1. **FR/KO product-name polish** — names are machine-glossed (~97% type coverage). A future pass
   could fix adjective agreement/word order and clear the ~3% residual English nouns, or harvest
   authentic FR/KO names from the live decathlon.fr / decathlon.co.kr catalogs. Not ingest-blocking.
2. **Per-store distinct layouts** — ✅ **DONE (2026-06-22).** Each retail store now has its own
   parametric floor (`stores/<id>/layout.json`): footprint, gondola grid, aisles, specialty mix +
   checkout side vary by tier+seed (flagship ~600 m²/5–6 rows → medium ~400 m²/3–4 rows), 0 overlaps
   asserted, layout-driven planogram in `build_chain.py`. Render: `scripts/render_floorplans.py`.
3. **Watches/GPS** — the US master has no sports-watch SKUs, so watch fixtures are unstocked
   (same limitation noted for Denver in Session 4). The LP/EAS demo anchor needs a watch SKU source.

---

## What backend/web need to decide (the ingest requirements to publish)

- **Org/site model:** how HQ → regional → store hierarchy and `dec-xx-region` org nodes map to
  core's site/org tables; whether regional offices are sites, org units, or neither.
- **User/role model:** role-key → perms-v3 capability mapping; how `manager_id` reporting lines and
  `status=inactive` are represented; how `home_store_id` binds a user to a site.
- **Catalog import:** the format/endpoint for bulk product ingest incl. **images** and per-region
  price/currency/locale (ties to the `CATALOG-IMPORT-ONBOARDING` core gap).
- **EPC/item provisioning:** bulk item + `item_identifier` + `thing_location` ingest at chain scale
  (~102k tags, ~18% on backroom racks) and the EPC-encoding-config surface (ties to the EPC-encoding onboarding gap).

Once published, twin maps these files onto the schema and seeds M8trxDemo (gated prod write).
