---
title: Phase 1, Denver cut — the store model as files (profile, fixtures, demand, planogram, stock to plan)
date: 2026-11-17
author: twin lane (Opus, design) — build by a Sonnet agent
parent: status/PLAN-STORE-REALISM-2026-11-17.md § 4 (path (a): realistic inside today's zones)
scope: OFFLINE ONLY — files and generators; nothing is sent to any server; nothing the live box reads is changed
---

# Phase 1 (Denver) — the store model as files

## 0. Hard rules
- **Do not modify** any file the live box reads today: `reference/data/chain/stores/*/{assortment.csv,epcs.csv,layout.json,
  planogram.json,fixture_ids.csv}`, `reference/data/chain/keys-*.csv`, `reference/data/store-operating-model.json`. The box pulls
  `main`, and those files drive its hourly pass. **All new output goes under `reference/data/chain/stores/<slug>/model/`** plus one
  new chain file `reference/data/store-profiles.json`.
- Offline: no network, no gradle task that talks to a server. Deterministic by seed: the same inputs give byte-identical outputs.
- Key everything by **EAN** (the unit of stock); `item_cd` is the style. The old `build_planogram.py` merged sizes by keying on
  `item_cd` (STATUS item 7) — do not repeat it.
- Python 3 stdlib only for the builder (house style: `scripts/build_*.py`). ktlint/Kotlin only if a Kotlin piece is needed.

## 1. Why (what is wrong today, measured)
Denver's floor: 115 fixture zones. Each of the 6 departments gets **exactly 14 gondola faces + 2 wall bays** regardless of size
(hiking & camping carries 1,219 EANs, general 17). Stock was placed one-fixture-per-style, round-robin (`build_chain.py:217-227`), so
fixtures hold 8 to 713 units and 23 never got any. The planogram is derived from wherever stock landed. The demand model picks the
nearest-priced SKU to a log-normal price draw (`StoreCatalog.sampleBasket`), which hammers a few price points and never sells the rest.

## 2. What to build — `scripts/build_store_model.py --store dec-us-denver` (and `--all` later)

Reads: `layout.json`, `assortment.csv` (columns: ean,item_cd,brand,category,classification_key,department,product_type,name_en,
size_us,color,price_usd,…,fixture,depth,handle), `store-operating-model.json`, `reference/data/store-profiles.json`, keys export
(zone ids/names). Writes `stores/<slug>/model/{profile.json, fixtures.json, demand.csv, planogram.json, stock-plan.csv, SUMMARY.md}`.

### 2.1 Store profile — `reference/data/store-profiles.json` (new)
Chain default + one block per store (all ten present). Fields: `timezone` (from keys), `area_sqm` (keys), `hours` per weekday
(`mon`…`sun`: `"10:00-21:00"` or `null` = closed), `location_factor` (footfall per m² relative to Denver = 1.0),
`delivery_lead_min` (60 if area ≥ 500 m² else 30), `provisional: true` and a `source` note. **All values are provisional pending
Bob's D3** — use today's 10:00–21:00 everywhere and `location_factor` 1.0 everywhere; Denver's profile must reproduce today's
volumes exactly. The builder copies the store's block into `model/profile.json`.

### 2.2 Fixture kit — `model/fixtures.json`
Every zone of the store, keyed by zone **code** (and carrying the platform `zone_id` and `zone_name` from the keys export), with a
**kit** derived from `fixture_category` and the department's product mix. Mirror core's fixture model (faces → bays → levels, each
level with a `form`: `shelf | pegboard | rail | table_top | bin | floor`). Kits:

| fixture_category | kit |
|---|---|
| `gondola_front`/`gondola_back` (2,000 × 600 mm face) | 2 bays × 5 levels. Form per face from what the department puts there: footwear → `shelf`; folded apparel → `shelf`; small accessories → `pegboard` (6 rows); hanging apparel → `rail` (2 levels); bulky (`outdoor`, `bag_pack`) → bottom level `floor`/`shelf`. A face may mix forms by level (pegboard top, shelves below). |
| `perimeter_west`/`perimeter_east` (wall bay) | footwear wall (6 shelves) for running and hike_camp; hanging wall (`rail` ×2) for apparel-led departments; bike/large-item `floor` bay for cycling |
| `accessories_wall` | `pegboard` 6 rows × 2 bays |
| `gps_case` | lockable case, 3 `shelf` levels — **EAS-tagged premium stock** by the existing rule (`layer2/EasTagging.kt`: price ≥ $150 and category ≠ outdoor) |
| `promo_island` (2) | `table_top` + `bin`: entrance feature (seasonal department of the month — snow in Nov–Feb by `StoryCalendar` department weights) and one promotion table |
| `round_rack` | apparel rounder, 1 `rail` (≈ 60 hangers) |
| `impulse_rack`, `checkout` | `pegboard`/`bin`, low-price accessories (≤ $15) |
| `footwear_bench`, `gait_treadmill` | **service fixtures: no stock, by design** (listed as deliberate empties) |
| `backroom_rack` (8) | back stock, `shelf` × 6, high capacity, by department |
| `receiving_dock` | staging, no planned stock |

Capacity per facing by product type (units the facing holds when full): footwear shelf 3 (boxes), folded apparel 6, hanging 10,
pegboard hook 6, bulky 2, bike 1 (`floor`), table_top 8, bin 20. Facings per level by width (≈ 4 per metre for footwear/folded,
8 per metre for pegboard, 1 hanger slot per 4 cm on rails). State the numbers as constants at the top of the script with a comment;
they are the knobs.

### 2.3 Demand — `model/demand.csv` (the single source of "how fast does each EAN sell")
Columns: `ean,item_cd,department,category,price_usd,units_per_day_weekday,units_per_day_weekend,share`. Built, not inherited:
- **Total units/day** for the store = visitors/day (operating model × profile `location_factor` × area scale, weekday and weekend)
  × conversion × units per transaction — reproduce today's Denver totals (check against `StoryCalendar.expectedVisitors` logic and
  `store-operating-model.json`; document the arithmetic in SUMMARY.md).
- **Split** by department (the StoryCalendar seasonal department weights for the month, incl. the slow `climb`), then by style with a
  seeded long-tail (Zipf-like, exponent ≈ 1.1 within department × category), then by size with the existing size curve
  (`scripts/size_curve.py`), with a mild price elasticity (cheaper lines sell more units; e.g. weight ∝ price^-0.5).
- Every EAN gets a share > 0 (no product that can never sell), and the head (top 10% of EANs) carries roughly 40–50% of units.
Phase 2's shoppers will draw from this file, so it must be self-consistent and documented.

### 2.4 Planogram — `model/planogram.json` (m8trx_standard **v2**)
One line per (fixture, level, position, EAN): `fixture_code`, `zone_id`, `zone_name`, `level`, `form`, `position_sequence`, `ean`,
`sku` (= item_cd), `facings`, `required_qty`, `display_level` (`eye` | `reach` | `stoop` | `top`). Header as v1 (`directive_ref`,
`source_digest`, counts) plus `kit_version`. Rules, in order:
1. **Departments keep their rows** (the department regions on mother are fixed geometry); specialty fixtures (cases, islands, walls,
   rounder, impulse) are assigned by the table above.
2. **Space follows demand inside a department:** facings ∝ demand share, with a floor of one facing per EAN of a style that is
   on the floor; a style's whole size run sits together, contiguous, on one level of one fixture.
3. **Eye level for the head:** the top-demand styles of each fixture take the eye levels (levels 2–3 of 5); bulky and heavy at the
   bottom; small and light at the top.
4. **Capacity is respected** (`required_qty` ≤ facings × capacity per facing). If a department's assortment does not fit its space,
   the lowest-demand styles go **off the floor** (back-room only); count them and list them in SUMMARY.md. If it has room to spare,
   deepen facings of its head. General (17 EANs) will be deep; hiking & camping will prune.
5. **No empty floor fixture** except the deliberate service fixtures; every stocked fixture ≥ 70% filled at plan.
6. Deterministic, `source_digest` over canonical lines.

### 2.5 Stock to plan — `model/stock-plan.csv`
Columns: `ean,zone_code,zone_id,qty,kind` with `kind ∈ {floor, backroom}`. Floor = `required_qty` per line, then a **deliberate
deviation knob** (default: 92% of lines exact; 6% short by 1–2 units; 2% over by 1; seeded) so the store opens slightly
non-compliant, as real stores do. Back room = `ceil(demand/day × COVER_DAYS)` per EAN (default 7), rounded up to cases of 6,
spread over the back-room racks by department, **plus** the whole demand of off-floor styles. No EPCs here: Phase 2/3 mint them
(the receiving serial block, `connect/ReceiveStock.kt`) and decide which existing platform units are reused.

### 2.6 SUMMARY.md and the self-test
`model/SUMMARY.md`: per department — fixtures, facings, floor units, EANs on floor / off floor, share of space vs share of demand;
per fixture category — fill %, levels; the demand arithmetic; the knobs used. `scripts/store_model_selftest.py` asserts, for Denver:
- every floor fixture zone is in fixtures.json; every non-service floor fixture has ≥ 1 planogram line and fill ≥ 70%;
- no line exceeds capacity; facings ≥ 1; every on-floor style's sizes are contiguous on one level of one fixture;
- every EAN appears in demand.csv with share > 0; shares sum to 1 ± 1e-9; weekday/weekend totals match the documented arithmetic;
- planogram keyed by EAN (no two lines on one fixture-level-position); `zone_id`/`zone_name` match the keys export;
- stock-plan floor totals = planogram required ± the deviation knob's bounds; back room ≥ 1 case for every on-floor EAN;
- determinism: two runs → identical bytes (digest);
- the old files listed in § 0 are byte-identical to `origin/main` (git diff check).
Wire it as a gradle `Exec` task `storeModelSelfTest` (python3) and add it to `offlineSelfTest`.

## 3. Done when
`./gradlew build -x test ktlintCheck offlineSelfTest` green (incl. `storeModelSelfTest`); Denver's `model/` committed with SUMMARY.md
showing: 0 unintended empty floor fixtures; per-department space roughly ∝ demand; the off-floor list; the knobs. A draft PR to
`main` titled `feat(store-model): Phase 1 Denver — profile, fixture kits, demand, planogram v2, stock to plan (offline)`.

## 4. Not in this phase
Sending anything; EPC minting; the transition from what the platform holds today to the plan (Phase 3); the shopper simulation
(Phase 2); new floor geometry (path (b), core); the other nine stores (same script with `--all` once Denver is reviewed).
