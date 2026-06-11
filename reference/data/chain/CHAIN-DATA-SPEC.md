# Decathlon Chain Dataset — Data Spec & Handoff

**Status:** v1, generated 2026-06-11 (Twin Session 5). Deterministic, regenerable.
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
python3 scripts/build_chain.py          # inventory: manifest + per-store assortment/EPCs
python3 scripts/build_staff_roster.py   # org/staff: roster + org-chart
```
Fully deterministic — regeneration is byte-identical (verified by md5). Per-store seed is a
**stable SHA-256** of the store id (not Python's per-process-salted `hash()`); roster `seed=42`.
Config lives in `scripts/chain_config.py` (stores, regions, tiers, org template, name pools) —
the single source of truth all builders read.

---

## The chain (10 stores, 1 HQ, 3 regional offices)

| Store ID | Region | City | Timezone | Cur | Tier | SKUs | EPCs |
|---|---|---|---|---|---|---|---|
| dec-us-denver | US | Denver, CO | America/Denver | USD | Flagship | 2,586 | 35,994 |
| dec-us-nyc | US | New York, NY | America/New_York | USD | Flagship | 2,586 | 33,951 |
| dec-us-sf | US | San Francisco, CA | America/Los_Angeles | USD | Flagship | 2,586 | 33,681 |
| dec-fr-paris | FR | Paris | Europe/Paris | EUR | Flagship | 2,586 | 35,805 |
| dec-fr-lyon | FR | Lyon | Europe/Paris | EUR | Large | 2,400 | 28,061 |
| dec-fr-lille | FR | Villeneuve-d'Ascq | Europe/Paris | EUR | Large | 2,400 | 27,781 |
| dec-fr-marseille | FR | Marseille | Europe/Paris | EUR | Medium | 1,800 | 18,033 |
| dec-fr-bordeaux | FR | Bordeaux | Europe/Paris | EUR | Medium | 1,800 | 18,061 |
| dec-kr-seoul | KR | Seoul | Asia/Seoul | KRW | Large | 2,400 | 28,118 |
| dec-kr-busan | KR | Busan | Asia/Seoul | KRW | Medium | 1,800 | 18,030 |

**Totals:** **14 sites = 10 retail + 4 office** · 22,944 store-SKU rows (2,586 distinct master
SKUs) · **277,515 EPCs** · 251 users (250 staff + tenant-admin). **5 distinct IANA timezones**
spanning UTC-8 → UTC+9.

Every site carries a **`site_type`**. **Office sites** (HQ + 3 regional) have a real address +
timezone but **no space, zones, fixtures, inventory, or sensors**
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
├── localization/
│   └── name-localization.csv        # ean -> name_en / name_fr / name_ko (master, 2,586 SKUs)
├── layout/
│   └── space-template.json          # shared 600 sqm space: 160 zones (11 area + 149 zone_type='fixture')
├── stores/<store-id>/
│   ├── assortment.csv               # product list for the store (localized name/price/currency/locale)
│   └── epcs.csv                     # one row per physical unit (RFID tag) — the inventory
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
| `timezone` | **IANA** zone (e.g. `Europe/Paris`) — use for all per-store local-time logic |
| `currency`, `locale` | ISO currency + BCP-47 locale for the store's market |
| `tier`, `tier_label` | Flagship / Large / Medium — drives SKU breadth + stock depth |
| `sqm` | selling-floor area (nominal — flagship 600 / large 520 / medium 420; all reuse the one 600 sqm fixture set) |
| `space` | the site's **one space**: `name`, `template` (→ `layout/space-template.json`), `sqm`, `zones_total` (160), `area_zones` (11), `fixture_zones` (149), `try_on_zones` (3) |
| `sku_count`, `epc_count` | actual rows in the store's two files |
| `epc_by_category` | piece counts per planogram bucket |
| `files` | relative paths to the store's assortment/EPC CSVs |

Each `office_sites[]` entry (HQ + 3 regional): `id`, `site_type="office"`, `office_role`
(`global_hq`/`regional_hq`), `region`, `country`, `city`, `address`, `timezone`,
`has_space=has_inventory=has_sensors=false`, `sku_count=epc_count=0`. Office sites have **no**
store dir, no `space/zone/fixture` rows — they provision as a **site row only** and exist to host
staff (HQ + regional org). They are valid `site` targets in `users.csv`.

## Schema — `layout/space-template.json` (shared by all 10 retail sites)

The one 600 sqm Decathlon-City floor, extracted from `STORE-LAYOUT.md`. **Every retail site
instantiates ONE space from this template** → its own `space_id` + 160 `zone_id`s. Office sites get
no space.

> **Fixtures are zones.** Per core's model (corrections doc §1), a fixture is a `zone` with
> `zone_type='fixture'` — **not** a row in the `fixture` table (unused). So this is **one unified
> `zones[]` of 160**: 11 area zones + 149 fixture-zones, all children of the space. The `fixture`
> codes in every `epcs.csv` resolve to the **fixture-zone of the same `code`**, keyed `(store_id, code)`.

- **`zones[]`** (160) — each: `code`, `name`, `zone_type`, `parent` (`"space"`), `area_sqm`, `rect_mm{x1,y1,x2,y2}`.
  - **Area zones** (11): `zone_type` ∈ `entry_exit` / `checkout` / `region` / `try_on_zone`;
    try-on zones add `try_on_profile` (`footwear_bench`/`equipment_test`/`apparel_room`); plus
    `customer_accessible`. Codes `Z-01`…`Z-11`.
  - **Fixture-zones** (149): `zone_type='fixture'`, plus `in_area_zone` (the area zone it sits in,
    e.g. `Z-04`) and `fixture_category` (`gondola_front`/`perimeter_west`/`gps_case`/…). Codes
    `GF-R1-U2`, `PW-01`, `GPS-01`… 140 are stocked; 9 (GPS cases, checkout counters, service
    counter) exist but are unstocked.
- **`counts`** — `zones_total` (160), `area_zones` (11), `fixture_zones` (149), `try_on_zones` (3),
  `crossing_slices`, `sensors`.
- **`crossing_slices[]`** (1) — `CS-01` main entrance EAS gate (traffic/EAS).
- **`sensors[]`** (5) — 3 Xovis 3D cameras + RFID overhead + EAS gate (planned placement).
- **Geometry:** millimeters, origin SW corner, footprint 24,000 × 25,000 mm, rectangles → `POLYGON Z` SRID 0.

---

## Schema — `stores/<id>/assortment.csv`

One row per **SKU carried by that store** (variant-level: a size/color is its own row).

| Column | Notes |
|---|---|
| `ean` | EAN-13 barcode (real Decathlon GS1-FR) — the product's stable identity |
| `item_cd` | Decathlon article/SKU code from the source catalog |
| `category` | planogram bucket: footwear / apparel / accessories / bag_pack / outdoor |
| `product_type` | finer source type (Shoes, Jacket, Backpack, …) |
| `name_en` | English product name (real, from the US catalog) |
| `name_local` | localized display name in the store's language: `name_en` (US), French (FR), Korean (KR). From `localization/name-localization.csv`. Machine gloss — see Localization |
| `size_us`, `color` | variant attributes |
| `price_usd` | base USD price (master) |
| `price_local` | price in the store's currency (`localize_price` in chain_config.py) |
| `currency`, `locale` | matches the store's region |
| `fixture` | planogram fixture code (e.g. `GF-R1-U2`, `PW-01`) per STORE-LAYOUT.md |
| `depth` | how many physical units of this SKU the store stocks (= count of its EPC rows) |
| `handle`, `image`, `n_images` | Shopify handle + **real product image URL** + image count |

> Same `ean` appears in multiple stores — that's the same *product* stocked in different stores.
> Distinct physical units live in `epcs.csv`.

## Schema — `stores/<id>/epcs.csv`

One row per **physical unit** (one RFID tag = one sellable item). This is the inventory volume.

| Column | Notes |
|---|---|
| `epc` | 24-hex **SGTIN-96** tag, scanner-decodable back to its EAN |
| `ean`, `item_cd`, `category`, `fixture` | denormalized from the assortment row |
| `store_id` | owning store |

**EPC encoding** — validated Decathlon SGTIN-96 (`filter=1`, `partition=6`, EAN-derived
company/itemref, sparse 38-bit serial). Every EPC across all 10 stores is **globally unique** and
**round-trips** to its EAN. Full spec + clean-room encoder: `reference/data/EPC-ENCODING-DECATHLON.md`.

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
2. **Per-store distinct layouts** — the structured space template (11 zones, 149 fixtures) now
   ships as `layout/space-template.json`, but all 10 retail sites **share** it (smaller tiers reuse
   the full 600 sqm fixture set; their `sqm` is nominal). Distinct per-store planograms/footprints
   are future work — not ingest-blocking.
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
  (~277k tags) and the EPC-encoding-config surface (ties to the EPC-encoding onboarding gap).

Once published, twin maps these files onto the schema and seeds M8trxDemo (gated prod write).
