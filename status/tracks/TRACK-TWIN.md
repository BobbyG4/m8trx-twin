# Track: Twin

**Last session:** Session 7 (2026-06-22, **OPEN** — reseed pending, session held to amend during the seed)
**Last session notes:** [→](../session-notes/2026-06-22-session-7-departments-boh-size-curves-geo.md)

---

## Current State

**m8trx-twin:** main branch, Kotlin scaffold compiles, NATS smoke passed. No event emit yet (runtime not built).
**Chain dataset (Wave 1):** built + committed under `reference/data/chain/` — **14 sites** (10 retail + 4 office, all with lat/long), **251 users**, **2,586-SKU** catalog, **102,675 EPCs**, **10 UNIQUE per-store departmentalized layouts**, localized USD/EUR/KRW · EN/FR/KO. Deterministic builders (`build_layout`/`localize_names`/`build_chain`/`build_staff_roster`/`size_curve`/`sport_universe`/`render_floorplans`, sha256 seeds).
**Realism overhaul (Session 7, 2026-06-22, committed `17872e5` + `be0f712`):** (a) **sport-universe DEPARTMENTS** from `brand` — flagship 6–7 / large 4–5 / medium 2–3 (count = min(7 universes, gondola rows); Denver/SF 6, NYC/Paris 7) (`sport_universe.py`; `build_layout` emits department `region` bands replacing the single "Main Sales Floor"; `build_chain` places SKUs by department, absent universes → *General* in small stores); (b) **lean BACK-OF-HOUSE** — Stockroom (Z-05) = real `receiving_dock` + `backroom_rack`; 18% of each style staged; (c) **realistic SIZE CURVES** (`size_curve.py`) — per-style bell, color-aware; fixes the flat-depth "88-pair shoe" Bob caught; **277,515 → 102,675 EPCs** (old total was the bug; density knob `TIER_SCALE` ~2× = testing variety + realism); (d) **SITE GEO** — lat/long on 14 sites (was 0/14 on mother).
**★ Spatial hierarchy corrected → `site → spaces → zones` (Pass 1, committed `c480446`):** the single-space build was the error — a site has MANY spaces (ruling `m8trx-shared/reference/dev/SPATIAL-HIERARCHY.md`, grounded in `7a. Data Model`). Each store now = **3 spaces** (Sales Floor `sales_floor` / Back Room `stockroom` / Fitting Rooms `fitting_room`), each its own SRF; **departments are `region` zones *in* the Sales Floor** (not spaces). `layout.json`→`spaces[]`, manifest→`stores[].spaces[]`; `assortment`/`epcs` unchanged (fixture resolves via `spaces[].zones[]`). Pass-1 = structure (assembly cols `srf_to_site_transform`/`site_frame_anchor_space` DORMANT); **Pass 2 = site assembly (transforms + `space_connection`) PENDING.** `space_type` provisional pending Backend/Web ratification.
**Layout (per-store, parametric):** footprint/grid/aisles/specialty/departments/BOH seeded off store_id (flagship ~675m² → medium ~370m²), 0 overlaps + 0 OOB asserted; mother-canonical zone geometry. SVGs (departments tinted, BOH shaded) via `render_floorplans.py`. *(Earlier 2026-06-22 work also fixed the original 134-overlap bug — twin source, not core's seed.)*
**Catalog coding layer (CORE-REQ-001):** `assortment.csv` +`brand` +`classification_key` +`department`; `classification.csv` (5 roots + 90 leaves, `attributes_schema`, `lifecycle_type`) + `display_lookup.csv` (colour raw→canonical family ×3 locales, swatch). Decathlon normalisation model. **Absorbed in core**; applied to the demo tenant by the reseed.
**MK/Hansae coding profile (built 2026-06-21):** the numeric-code model — `reference/data/mk-trend/` (`display_lookup.csv` 344, `classification.csv` 10 div + 63 items, `assortment-sample.csv` 2,610 SKUs round-tripping 100%) from the real MK Trend spec (`reference/hansaemk/`, ex-Zenven). Same grain as Decathlon → proves attribute-coding is vertical-portable. Config/parser `scripts/mk_coding.py` + `build_mk_attributes.py`; writeup `reference/data/mk-trend/MK-CODING-PROFILE.md`.
**M8trxDemo on mother:** still the **2026-06-11 seed** — `tenant_id ecfa6903-5c50-439f-8f80-185982de944e`, 277,515 EPCs site-level, USD, **no coding layer, no site coords**. Reseed PENDING. Pre-seed backup on mother.
**Reseed:** dataset READY + hand-off written (`DEPLOY-HANDOFF.md` §RESEED-2026-06-22). In-place: UPDATE site lat/long + `site_category` (14); drop+recreate the **3 spaces** per store (Sales Floor/Back Room/Fitting Rooms — `spaces[]`, each own SRF, Pass-2 assembly dormant); enrich catalog with the coding layer; **re-import 102,675 items** at department/BOH fixture-zones (EPC strings changed → full re-import, not re-locate). **Session held OPEN to amend the dataset if the seed surfaces issues.**
**EPC encoder:** VALIDATED (`EPC-ENCODING-DECATHLON.md`) — filter 1 / partition 6, round-trips real tags.

## Open hand-offs
- **`reference/data/chain/DEPLOY-HANDOFF.md`** — **rewritten 2026-06-22** as the authoritative RESEED hand-off (§RESEED-2026-06-22: in-place mechanism, deploy order, acceptance checks). 2026-06-11 kept as history.
- **`reference/data/chain/EXPANSION-PLAN.md`** — Wave 2 (10 international stores, varied layouts) — folds in the onboarding-baseline thread (`status/active/ONBOARDING-BASELINE-HANDOFF-2026-06-03.md`).
- **`reference/data/chain/ACTIVITY-PLAN.md`** — Phase-2 dynamic layer + Connect simulator + reset-to-opening-state.
- **`reference/data/chain/SEED-PLAYBOOK.md`** — reusable recipe + the 5 backend corrections (read before re-seeding).

## Blocked on Core

- **Image pipeline (NEW, gating Wave-2 images)** — `assortment.csv` `image` = Shopify CDN hot-link; backend hit an issue; likely need to **cache bytes** into M8TRX's own asset store, not hot-link. Confirm what the seed did + whether core can store/serve cached assets. Part of `CATALOG-IMPORT-ONBOARDING`.
- **Service bearer auth** — `InventoryActionController` JWT-only; REST inventory 401. `SERVICE-BEARER-INVENTORY` (CLEANUP-TASKS).
- **Catalog onboarding flow incl. images** — no tenant product-import path. `CATALOG-IMPORT-ONBOARDING`.
- **commerce_projection writer** — unfed; commerce dashboards blank on API path. **TWIN-REQ-002** (filed).
- **No cold-start/manual location** — inventory location requires a scan/receive event (corrections §2). Core CLEANUP-TASKS.
- **No EAS-alarm subscriber** — LP/theft analytics don't surface (API-SURFACE gap).

## Open Work (priority order)

1. **★ RUN the reseed (gating)** — hand-off written (`DEPLOY-HANDOFF.md` §RESEED-2026-06-22). Backend deploy session executes in-place: site lat/long **+ `site_category`** UPDATE (CORE-REQ-002, core mig 146), per-store **3 spaces** (Sales Floor/Back Room/Fitting Rooms — `spaces[]`, own SRF each, Pass-2 assembly dormant) (drop+recreate), catalog enrich (coding layer), **re-import 102,675 items** at department/BOH fixture-zones via scan/receive (corrections §2; direct-DB BOTH `thing_location` + `scan_event` until service-bearer lands). **This session stays OPEN to amend the twin dataset if the seed surfaces issues.**
2. **Verify post-reseed** — geo map plots 14 sites · circular fixtures render round · size curves per-style (not flat) · departments + backroom stock visible.
3. **Resolve image pipeline** with backend — link vs cached bytes (parallel).
4. **Full activity — the "play" (BEFORE Wave 2)** (`ACTIVITY-PLAN.md`) — orchestrator runtime skeleton → TrafficGenerator (people on map, NATS) → TransactionGenerator → try-on → staff shifts/journeys → restock/stocktake → LP/EAS; item-movement is the connective tissue. **Animate Wave 1 + light the analytics before expanding.**
5. **Wave 2 — 10 international stores** (`EXPANSION-PLAN.md`) — parametric per-store layout mechanism landed early; China←KR catalog, rest←US Shopify; onboard UI-first then API/Connect.
6. **Connect simulator** — external vendor feeds (POS/catalog/shipment) via webhook/HMAC; pairs with Wave-2 API onboarding.
7. **Spatial Pass 2 — site assembly** (when calibration/placement data exists): fill `srf_to_site_transform` + designate `site_frame_anchor_space` + wire `space_connection` adjacency (FR-SPATIAL-26) for a unified site view + cross-space routing. Columns already in schema/emitted dormant → zero rework.

## Active Requirements Filed to Core

| Brief | Status | Blocks |
|-------|--------|--------|
| TWIN-REQ-001 `fitting_room` → `try_on_zone` | ✅ ABSORBED (mig 127) | — |
| TWIN-REQ-002 `commerce_projection` writer | 📨 FILED, AWAITING ABSORPTION (2026-06-11) | Scripts 1, 3, 5 |
| CORE-REQ-001 catalog attribute enrichment (**inverse, core→twin**) | ✅ ABSORBED 2026-06-21 (core merged-commit `eb39526`; mother loaded + verified) | — |
| CORE-REQ-002 `site_category` functional role (**inverse, core→twin**) | 📦 DELIVERED 2026-06-23 (manifest + spec; rides the reseed; core mig 146) | — |
| `inventory:sell` capability split | PRE-EXISTING in CLEANUP-TASKS | Cashier persona |

## Key Docs
- Project context: `CLAUDE.md`
- Sister project contract: `~/IdeaProjects/m8trx-shared/twin/SISTER-PROJECT.md`
- Layer 4 schema: `reference/architecture/LAYER4-CONFIG-SCHEMA.md`
- Store layout: `reference/data/STORE-LAYOUT.md`
- API surface: `reference/integration/M8TRX-API-SURFACE.md`
