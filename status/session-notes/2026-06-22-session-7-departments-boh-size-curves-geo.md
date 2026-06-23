# Session 7 — Reseed-dataset realism overhaul (departments · BOH · size curves · site geo)

**Date:** 2026-06-22 · **Status:** **OPEN** (reseed not yet run on mother; session held to amend the dataset during the seed)
**Commits:** `17872e5` (feat: realism overhaul) · `be0f712` (docs: reseed hand-off + spec sync)

---

## Goal

Started as "draft + run the core reseed" (Session 6's #1). While grounding it, Bob inspected the live
data and surfaced gaps that reshaped the reseed dataset before it ships. Net: the reseed is now a
**realistic** dataset, not just a re-provision of the old one.

## What we found + decided

### 1. Reseed mechanism (grounding)
- Confirmed the twin dataset was internally consistent: 0 orphan fixture codes across all 10 stores;
  all asserts (0 overlaps, EPC-unique, SGTIN-96 round-trip) green.
- Original finding: EPC strings were **identical** to mother's (only fixture assignment had changed),
  so the reseed could have been a cheap **re-locate**. **This changed once we fixed depth** (below) —
  new depths → new serials → new EPC strings → the reseed is now a **full item re-import**.
- Mechanism (unchanged): **in-place**, no tenant-delete. Tenant + 251 users + 14 site rows stay.

### 2. Sport-universe DEPARTMENTS (Bob's question → research → decided)
- Question: we modelled the floor as one big space; should we add departments? Does Decathlon do it?
- **Research (web, cited):** Decathlon's real organizing unit is the **"univers" (sport universe)**,
  each run by a **"Sport Leader."** City format ≈ 3 permanent + 1 seasonal; larger formats 7–9.
- **Decision:** YES. The catalog's brands map cleanly to universes (Quechua/Forclaz→Hiking, Kiprun→
  Running, Simond→Climbing, Wedze→Snow, Van Rysel/Riverside/Rockrider→Cycling, Itiwit→Water) — so
  `brand` (CORE-REQ-001) drives departmentalization for free. Catalog is mountain/outdoor-heavy.
- **Build:** `sport_universe.py` (brand→universe + tier scaling). `build_layout.py` carves the hall
  into department `region` bands (flagship 6–7 / large 4–5 / medium 2–3 — count = min(7 universes, gondola rows), so Denver/SF 6, NYC/Paris 7), replacing the single
  "Main Sales Floor"; fixtures carry `in_area_zone` = department + a `department` key. `build_chain.py`
  places each SKU in its department; universes a small store lacks fold into **General**. **Zero core
  change** (just more `region` zones + `in_area_zone` repointing).

### 3. Lean BACK-OF-HOUSE (Bob's question → research → decided)
- Research nuance: Decathlon **deliberately minimises backrooms** (stock on overhead floor racking;
  depth at the DC) — but every retail digital-twin/RTLS standard models BOH as a first-class zone.
- **Decision:** a **lean but real** BOH. Stockroom (Z-05) now holds a `receiving_dock` + `backroom_rack`
  fixtures; **18% of each style staged to the backroom** — a real from-location for restock/receiving/
  stocktake in the future "play." Decathlon-honest (lean ~10–15% footprint). Zero core change.
- **Deferred (core question):** backroom as a **separate `space`** per site (true separate sensor
  domain) needs core to confirm >1 space/site is allowed — our docs don't state it. Modelled as an
  area zone for now; NOT YET FILED.

### 4. Realistic SIZE CURVES (Bob's bug → fixed)
- Bob caught it inspecting mother: shoes were "89 of one size." Diagnosis: depth was allocated
  **per size-variant** with a flat `uniform(0.6–1.4)` multiplier → a 10-size shoe summed to **88 pairs**
  with a flat spread. Three defects: no per-style budget, flat (not bell) curve, messy size labels
  (US/EU/kids/dual/ranges/alpha/waist-length).
- **Build:** `size_curve.py` — `normalize_size` (parses all the messy systems), `allocate` (curve over
  **distinct sizes**, then split each size across its **colours** — so multi-colour low-volume styles
  still show a bell), `style_target` (realistic absolute per-style total). `build_chain` now budgets
  **per style** (not per size) × tier scale, then applies the curve.
- **Result:** per-style **bell** (e.g. footwear modal US 8–9.5 deepest, tails → 1/0). Footwear median
  ~40 pairs/style, ~5 facings/modal size. **No more 88-pair styles.**

### 5. Density (Bob's call)
- The realism fix dropped inventory **277,515 → 52,546** (lean). The 277k was the flat-depth bug
  inflating everything — with only 464 styles, realistic depth can't reach 277k (that needed ~597/style).
- Bob chose **higher** density for testing variety + full variable-checking. Bumped `TIER_SCALE` ~2×
  → **102,675 EPCs**, still realistic (footwear median 40/style, modal ~5/size). Knob lives in
  `build_chain.py` (one line) for future tuning.

### 6. SITE GEO (Bob's finding → fixed)
- `site` has `latitude`/`longitude` columns but **0/14 populated** on mother (only city/country) →
  geo map can't plot. Twin is the data source, so we ship the coordinates.
- **Build:** `lat`/`lon` on all 14 sites in `chain_config.py` (geocoded to each actual address),
  emitted in the manifest. Reseed adds an `UPDATE site SET latitude/longitude` on the 14 rows.

## Verification (all green, deterministic)
- 0 fixture overlaps + 0 OOB asserted per store (`build_layout`).
- 102,675 EPCs globally unique; SGTIN-96 round-trip clean (sampled); 0 orphan fixture codes.
- Byte-identical reruns (md5 stable). Floor plans re-rendered (departments tinted, BOH shaded).

## Artifacts
- **New:** `scripts/size_curve.py`, `scripts/sport_universe.py`
- **Modified:** `scripts/{chain_config,build_layout,build_chain,render_floorplans}.py`
- **Regenerated:** 10× `layout.json`/`assortment.csv`/`epcs.csv`, `chain-manifest.json`, 11 floor-plan SVGs
- **Docs:** `DEPLOY-HANDOFF.md` (rewritten = authoritative reseed hand-off), `CHAIN-DATA-SPEC.md`,
  `IMPORT-MAPPING.md` (synced)

## Open / next
1. **RUN the reseed** — backend executes `DEPLOY-HANDOFF.md` §RESEED-2026-06-22 (in-place). **Session
   stays OPEN** to amend the twin dataset if the seed surfaces issues.
2. Verify post-reseed: geo plots 14 · circular fixtures round · per-style size curves · departments +
   backroom stock visible.
3. Deferred: backroom-as-separate-space (core multi-space question); image pipeline; the "play."
