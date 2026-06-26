# Track: Twin

**Last session:** Session 8 (2026-06-26) · ✅ **RE-RESEED v2 VERIFIED** on mother (twin cross-check byte-for-byte, zero drift) · static-seed gap audit · post-reseed login-500 triaged (audit-cascade pool starvation — core's) · **pivot to M8TRX Connect** for seeds + activity injection
**Last session notes:** [→](../session-notes/2026-06-26-session-8-reseed-verified-gap-audit-connect-pivot.md)

---

## Current State

**m8trx-twin:** main branch, Kotlin scaffold compiles, NATS smoke passed. No event emit yet (runtime not built).
**Chain dataset (Wave 1):** built + committed under `reference/data/chain/` — **14 sites** (10 retail + 4 office, all with lat/long), **251 users**, **2,586-SKU** catalog, **102,675 EPCs**, **10 UNIQUE per-store departmentalized layouts**, localized USD/EUR/KRW · EN/FR/KO. Deterministic builders (`build_layout`/`localize_names`/`build_chain`/`build_staff_roster`/`size_curve`/`sport_universe`/`render_floorplans`, sha256 seeds).
**Realism overhaul (Session 7, 2026-06-22, committed `17872e5` + `be0f712`):** (a) **sport-universe DEPARTMENTS** from `brand` — flagship 6–7 / large 4–5 / medium 2–3 (count = min(7 universes, gondola rows); Denver/SF 6, NYC/Paris 7) (`sport_universe.py`; `build_layout` emits department `region` bands replacing the single "Main Sales Floor"; `build_chain` places SKUs by department, absent universes → *General* in small stores); (b) **lean BACK-OF-HOUSE** — Stockroom (Z-05) = real `receiving_dock` + `backroom_rack`; 18% of each style staged; (c) **realistic SIZE CURVES** (`size_curve.py`) — per-style bell, color-aware; fixes the flat-depth "88-pair shoe" Bob caught; **277,515 → 102,675 EPCs** (old total was the bug; density knob `TIER_SCALE` ~2× = testing variety + realism); (d) **SITE GEO** — lat/long on 14 sites (was 0/14 on mother).
**★ Spatial hierarchy corrected → `site → spaces → zones` (Pass 1, committed `c480446`):** the single-space build was the error — a site has MANY spaces (ruling `m8trx-shared/reference/dev/SPATIAL-HIERARCHY.md`, grounded in `7a. Data Model`). Each store now = **3 spaces** (Sales Floor `sales_floor` / Back Room `stockroom` / Fitting Rooms `fitting_room`), each its own SRF; **departments are `region` zones *in* the Sales Floor** (not spaces). `layout.json`→`spaces[]`, manifest→`stores[].spaces[]`; `assortment`/`epcs` unchanged (fixture resolves via `spaces[].zones[]`). Pass-1 = structure (assembly cols `srf_to_site_transform`/`site_frame_anchor_space` DORMANT); **Pass 2 = site assembly (transforms + `space_connection`) PENDING.** `space_type` provisional pending Backend/Web ratification.
**Layout (per-store, parametric):** footprint/grid/aisles/specialty/departments/BOH seeded off store_id (flagship ~675m² → medium ~370m²), 0 overlaps + 0 OOB asserted; mother-canonical zone geometry. SVGs (departments tinted, BOH shaded) via `render_floorplans.py`. *(Earlier 2026-06-22 work also fixed the original 134-overlap bug — twin source, not core's seed.)*
**Catalog coding layer (CORE-REQ-001):** `assortment.csv` +`brand` +`classification_key` +`department`; `classification.csv` (5 roots + 90 leaves, `attributes_schema`, `lifecycle_type`) + `display_lookup.csv` (colour raw→canonical family ×3 locales, swatch). Decathlon normalisation model. **Absorbed in core**; applied to the demo tenant by the reseed.
**MK/Hansae coding profile (built 2026-06-21):** the numeric-code model — `reference/data/mk-trend/` (`display_lookup.csv` 344, `classification.csv` 10 div + 63 items, `assortment-sample.csv` 2,610 SKUs round-tripping 100%) from the real MK Trend spec (`reference/hansaemk/`, ex-Zenven). Same grain as Decathlon → proves attribute-coding is vertical-portable. Config/parser `scripts/mk_coding.py` + `build_mk_attributes.py`; writeup `reference/data/mk-trend/MK-CODING-PROFILE.md`.
**M8trxDemo on mother:** ✅ **RE-RESEED v2 (landed + verified 2026-06-26)** — `tenant_id ecfa6903-5c50-439f-8f80-185982de944e`; canonical `site → spaces → zones` live: **30 spaces** (10 stores × `sales_floor`/`stockroom`/`fitting_room`, own SRF each) · **929 zones** · **53 try-on** · **102,675 items** (84,266 floor / 18,409 BOH 17.9%, dual-written `thing_location`+`scan_event`) · catalog coding live (2,586 products / 95 classes / 2,586 images, USD) · geo + `site_category` on 14 sites · `space_type` Hasura-exposed · Hasura `is_consistent:true`. Pre-seed backup retained on mother.
**Reseed:** ✅ **DONE + VERIFIED 2026-06-26** (RE-RESEED v2; hand-off `DEPLOY-HANDOFF.md` §RESEED-2026-06-22 executed by core). Twin-side cross-check vs the committed dataset passed **byte-for-byte** (9/9 headline metrics — spaces, zones, try-on, items, floor/BOH split, products, classes, Denver per-space) → **zero drift, no dataset amendment needed.** Recorded core-side at m8trx-shared `693f706`; Hasura `is_consistent:true`.
**Static-seed audit (Session 8):** structural seed complete + verified; remaining gaps — (a) data present but unapplied: **staff/org model** unprovisioned (250-person roster/roles/reporting in `staff/`), **per-region currency** (mother USD-only — single shared US master), **localized names** (EN-only); (b) thin/absent substrate: **sensor/reader topology** (only 2 stubs, 0 in BOH/fitting), empty **try-on profiles** (`properties={}`), absent **LP/EAS substrate** (0 watch SKUs, no demo zone, no EAS tag); (c) blocked-on-core (images, service-bearer, commerce_projection). Full detail in session-notes.
**★ Integration pivot (Session 8):** future seeds **and** active interactions route through **M8TRX Connect** (public webhook/HMAC front door), with parallel ERP/external simulators injecting `ACTIVITY-PLAN` activities. Bob authoring the Connect **API doc** (shares next session). Supersedes direct-DB seeding as the path — also sidesteps the bulk-reseed audit cascade + the service-bearer-inventory blocker. Posture win (front door).
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
- **Auth 500 / Hikari pool starvation (NEW, Session 8)** — post-reseed, the bulk-mutation Hasura **audit-trigger cascade** exhausted m8trx-services' `HikariPool-1` (10/10 active, 30 waiting) → auth/exchange 500 (reqId `3cc2943b`). Mother DB healthy (74/200); likely amplifier = the 102,675-item dual-write (~205k rows). **Core's to fix** (pool headroom / async-batch audit-ingest / quiesce triggers during bulk load) — **OPEN, core investigating.** Connect-based incremental seeding avoids the bulk direct-DB writes that trigger it. (Confirmed NOT caused by the twin session's read-only audit.)

## Open Work (priority order)

> ✅ **DONE 2026-06-26 (gating item cleared):** RE-RESEED v2 landed on mother + twin-side verification passed **byte-for-byte** (zero drift, no amendment). Core recorded it at m8trx-shared `693f706`. Static-seed gap audit delivered (see Current State + session-notes).

1. **★ M8TRX Connect hookup (gated on Bob's Connect API doc)** — Connect is now the canonical path for **both** seeds/updates **and** active interactions. On pickup: ingest Bob's API document → design the connector contract for (a) future seeds and (b) activity injection; then **stand up the parallel ERP/external simulators** (mock POS/catalog/shipment/etc.) that inject `ACTIVITY-PLAN.md` activities through Connect. Subsumes the old "Connect simulator" item; routes through the public webhook/HMAC front door.
2. **Full activity — the "play"** (`ACTIVITY-PLAN.md`) — realized **via Connect + simulators** (#1): traffic → transactions → try-on → staff shifts/journeys → restock/stocktake (**BOH gives it a from-location**) → LP/EAS; item-movement is the connective tissue. The Kotlin Layer-0..3 generators feed the simulators. **Animate Wave 1 + light the analytics before expanding.**
3. **Close static-seed gaps via Connect** (Session 8 audit) — staff/org provisioning (candidate **TWIN-REQ-003**, hold for the API doc), per-region currency + localized names, sensor/reader topology (settle the zones-vs-readers event model), LP/EAS substrate (watch SKU source).
4. **Spatial Pass 2 — site assembly** (when calibration/placement data exists): fill `srf_to_site_transform` + designate `site_frame_anchor_space` + wire `space_connection` adjacency (FR-SPATIAL-26) for a unified site view + cross-space routing. Columns already in schema/emitted dormant → zero rework.
5. **Resolve image pipeline** with backend — link vs cached bytes (parallel).
6. **Wave 2 — 10 international stores** (`EXPANSION-PLAN.md`) — parametric per-store layout mechanism landed early; China←KR catalog, rest←US Shopify; onboard via Connect.

## Active Requirements Filed to Core

| Brief | Status | Blocks |
|-------|--------|--------|
| TWIN-REQ-001 `fitting_room` → `try_on_zone` | ✅ ABSORBED (mig 127) | — |
| TWIN-REQ-002 `commerce_projection` writer | 📨 FILED, AWAITING ABSORPTION (2026-06-11) | Scripts 1, 3, 5 |
| CORE-REQ-001 catalog attribute enrichment (**inverse, core→twin**) | ✅ ABSORBED 2026-06-21 (core merged-commit `eb39526`; mother loaded + verified) | — |
| CORE-REQ-002 `site_category` functional role (**inverse, core→twin**) | ✅ LIVE on mother (RE-RESEED v2, 2026-06-26; core mig 146) | — |
| `inventory:sell` capability split | PRE-EXISTING in CLEANUP-TASKS | Cashier persona |

## Key Docs
- Project context: `CLAUDE.md`
- Sister project contract: `~/IdeaProjects/m8trx-shared/twin/SISTER-PROJECT.md`
- Layer 4 schema: `reference/architecture/LAYER4-CONFIG-SCHEMA.md`
- Store layout: `reference/data/STORE-LAYOUT.md`
- API surface: `reference/integration/M8TRX-API-SURFACE.md`
