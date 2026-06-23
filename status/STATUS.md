# Project Status — M8TRX Twin

---

## ⚠ NEXT SESSION PRIORITIES (updated 2026-06-24 — Session 7 **CLOSED**; ★ RESEED IN FLIGHT, core reseeding mid-stream)

> **PICK UP HERE:** Core is **reseeding M8trxDemo right now (mid-stream)** against the committed twin
> dataset (spaces[] · 102,675 EPCs · coding · geo · site_category). **First action next session:** check
> the reseed result on mother, and **amend the twin dataset if it surfaced issues** (fully regenerable:
> `build_layout → localize_names → build_chain → build_staff_roster → render_floorplans`). Then run the
> post-reseed verification checklist (geo plots 14 · spaces render · size curves per-style · departments
> + backroom stock · coding on Discover). Format is **spaces[]** (see DEPLOY-HANDOFF §RESEED). Spatial
> **Pass 2** (site assembly: transforms + space_connection) is the next build once calibration data exists.

**Session 7 = reseed-dataset realism overhaul + spatial-hierarchy correction. Site→spaces→zones (Pass 1), sport-universe departments, lean back-of-house, realistic size curves, site geo + `site_category` (CORE-REQ-002); reseed hand-off rewritten. All committed + deterministic. **Session closed 2026-06-24; the reseed is now IN FLIGHT on mother (core, mid-stream)** against this dataset — next session checks the result + amends the (regenerable) twin dataset if it surfaced issues.**

- **★ SPATIAL HIERARCHY corrected → `site → spaces → zones` (Pass 1)** — the single-space build was the error; **a site has MANY spaces** (canonical ruling `m8trx-shared/reference/dev/SPATIAL-HIERARCHY.md`, grounded in `7a. Data Model`). Each store now = **3 spaces** — Sales Floor (`sales_floor`) / Back Room (`stockroom`) / Fitting Rooms (`fitting_room`) — each its own SRF frame; **departments are `region` zones *within* the Sales Floor** (NOT spaces). `layout.json`→`spaces[]`; manifest→`stores[].spaces[]`; `assortment`/`epcs` unchanged (fixture resolves via `spaces[].zones[]`). **Pass 1 = structure (assembly columns `srf_to_site_transform`/`site_frame_anchor_space` DORMANT); Pass 2 = site assembly (transforms + `space_connection`) — PENDING.** `space_type` provisional pending Backend/Web ratification. Commit `c480446`.
- **Sport-universe DEPARTMENTS** — floor carved into Decathlon "univers" bands from `brand` (CORE-REQ-001 payoff): flagship 6–7 / large 4–5 / medium 2–3 (count = min(7 universes, gondola rows); e.g. Denver/SF 6, NYC/Paris 7). `sport_universe.py` (brand→universe, e.g. Quechua/Forclaz→Hiking, Kiprun→Running, Simond→Climbing, Wedze→Snow, Van Rysel→Cycling); `build_layout.py` emits department `region` bands (replacing the single "Main Sales Floor"); `build_chain.py` places each SKU in its department (absent universes fold to *General* in small stores). Decided after research — Decathlon's real organizing unit is the sport universe w/ a "Sport Leader" each.
- **Lean BACK-OF-HOUSE** — Stockroom (Z-05) is now real: `receiving_dock` + `backroom_rack` fixtures; **18% of each style staged to the backroom** (a real from-location for restock/receiving/stocktake). Decathlon-honest (lean ~10–15% footprint; their stores minimise BOH).
- **Realistic SIZE CURVES** — `size_curve.py`: per-STYLE depth budget × size-curve allocation (bell over distinct sizes, split across colours, color-aware). **Fixes the flat-depth bug** Bob caught (the "89 of one size" — actually 88-pair styles). Footwear now ~40 pairs/style, ~5 facings/modal size, thin tails. **Inventory 277,515 → 102,675 EPCs** (the old number was the bug inflating depth; with 464 styles, realistic depth can't be 277k). Density knob `TIER_SCALE` in `build_chain.py` (~2×) — Bob chose higher for testing variety + realism.
- **SITE GEO** — lat/long on all 14 sites (geocoded to address) in `chain_config.py` → populates `site.latitude/longitude` (was **0/14 populated** on mother; geo map had nothing to plot). Reseed adds an `UPDATE site` on the 14 existing rows.
- **Reseed hand-off REWRITTEN** — `reference/data/chain/DEPLOY-HANDOFF.md` §RESEED-2026-06-22 is the authoritative instruction (in-place mechanism); `CHAIN-DATA-SPEC.md` + `IMPORT-MAPPING.md` synced.

**Commits:** twin `17872e5` (realism) · `be0f712` (hand-off+spec) · `bf1915c` (site_category CORE-REQ-002) · `c480446` (site→spaces→zones Pass 1) + spec/status sync. **Session 6 detail:** `status/session-notes/2026-06-22-session-6-catalog-coding-perstore-layouts.md`.

### What's LIVE on mother (still the 2026-06-11 seed — reseed PENDING)

| Asset | State |
|---|---|
| Tenant | **M8trxDemo** `ecfa6903-5c50-439f-8f80-185982de944e` (pre-seed backup on mother) |
| Sites | **14** — 10 retail (US×3 / FR×5 / KR×2) + 4 office · **no coordinates** |
| Users | **251** — tenant-admin `zenvendemo@gmail.com`; 30 inactive |
| Catalog | **2,586 products** + images · **USD display** · **no coding layer applied** |
| Inventory | **277,515 EPCs**, flat per-size depth, **site-level only** |

### Twin dataset READY for reseed (regenerated 2026-06-22, committed)

| Asset | State |
|---|---|
| Layouts | 10 per-store sites, each **3 spaces** (Sales Floor / Back Room / Fitting Rooms — own SRF each) · Sales Floor carries 2–7 `region` department bands + fixtures · Back Room = dock + racks · 0 overlaps/space · Pass-2 assembly dormant |
| Catalog | 2,586 products + **`brand`/`classification_key`/`department`** + `classification.csv` + `display_lookup.csv` |
| Inventory | **102,675 EPCs** · realistic size curves · ~18% (~18.4k) back-of-house · at department + BOH fixture-zones (EPC strings all CHANGED → full re-import, not re-locate) |
| Sites | 14 with **lat/long** (geocoded to address) |

> **Regenerate byte-identical:** `build_layout` → `localize_names` → `build_chain` → `build_staff_roster` → `render_floorplans`.

### Immediate next steps (ranked)

1. **★ Reseed IN FLIGHT — monitor + amend** (core reseeding mid-stream; hand-off: `DEPLOY-HANDOFF.md` §RESEED-2026-06-22). In-place: UPDATE site lat/long **+ `site_category`** (14 rows; CORE-REQ-002, core mig 146); drop+recreate the per-store **3 spaces** (Sales Floor / Back Room / Fitting Rooms — `spaces[]`, each own SRF, Pass-2 assembly dormant); enrich catalog with the coding layer; **re-import the 102,675 items** (EPC strings changed → full re-import) at department/BOH fixture-zones via scan/receive (corrections §2; service-bearer still blocks the API path → direct-DB writing BOTH `thing_location` + `scan_event`). Then verify on mother (coding live on the Discover/Things surface; geo map populated) — this **applies the already-absorbed CORE-REQ-001 to the demo tenant** end-to-end. **Session 7 closed 2026-06-24; next session confirms the reseed landed clean and amends the (regenerable) twin dataset if it surfaced issues.**
2. **Verify post-reseed** — geo map plots 14 sites; circular fixtures render round (not bounding boxes); size curves show per-style (not a flat pile); departments + backroom stock visible.
3. **Resolve the image pipeline** with backend — Shopify hot-link vs **cache bytes**. *(parallel; see Blocked on core)*
4. **Full activity — the "play" (BEFORE Wave 2)** (`ACTIVITY-PLAN.md`) — runtime skeleton → TrafficGenerator → TransactionGenerator → try-on → staff shifts/journeys → restock/stocktake (**BOH now gives it a from-location**) → LP/EAS, item-movement throughout.
5. **Wave 2 — 10 international stores** (`EXPANSION-PLAN.md`) — parametric per-store layout + departments already land; China←KR catalog, rest←US Shopify; UI-then-API onboarding.
6. **Connect simulator** — external vendor feeds (POS/catalog/shipment) via webhook/HMAC.

**Deferred (Bob's call, noted):** rotate the mother Hasura admin secret + de-hardcode `scripts/seed_store.py:20` (committed prod secret). Optional fixture realism: end-caps. MK/Hansae EPC bit-encoding (only if an MK tenant seeded). **Backroom-as-separate-`space`** (true separate sensor domain) — needs core confirmation that >1 space/site is allowed; modelled as an area zone for now, NOT YET FILED.

### Blocked on core

- **Image pipeline (NEW)** — `image` = Shopify CDN hot-link; backend hit an issue; likely need cached bytes in M8TRX's own asset store. Confirm what the seed did + whether core can store/serve cached assets. Part of `CATALOG-IMPORT-ONBOARDING`.
- **Service bearer not wired to inventory endpoints** — REST inventory 401; `ApiKeyService` injection. `SERVICE-BEARER-INVENTORY` (CLEANUP-TASKS).
- **Catalog import onboarding flow incl. images** — no tenant product-import path. `CATALOG-IMPORT-ONBOARDING`.
- **commerce_projection writer** — unfed; commerce dashboards blank on API path. **TWIN-REQ-002** (filed 2026-06-11).
- **No cold-start/manual location** — inventory location needs a scan/receive event (corrections §2). CLEANUP-TASKS.
- **No EAS-alarm subscriber** — LP/theft analytics don't surface. API-SURFACE gap.
- **MapCanvas rendering** — `zone_type` colors (fixed core Session 70/71 per log — re-verify against the new fixture-zones).

---

## Store Concept (locked Session 3)

**Decathlon Manhattan** — Decathlon City format, NOT running specialty.
- Concept evolution: started as Bordeaux running specialty (160 sqm) → Florence CAD grammar showed the correct scale → 600 sqm Decathlon City format adopted
- Address: 620 6th Avenue, New York, NY 10011 (Flatiron District)
- Currency: USD
- SKU mix: running (primary) + fitness + hiking + swim + cycling + accessories + GPS watches
- LP scenario anchor: W-series sports watches ($29.99–$89.99), EAS-tagged, 40 items

---

## Project Artifacts

| Artifact | Path | Status |
|---|---|---|
| Kotlin project | `~/IdeaProjects/m8trx-twin/` | ✅ Compiles, NATS smoke passed |
| NATS emitter | `src/main/kotlin/com/m8trx/twin/layer0/NatsEmitter.kt` | ✅ Dual-publishes legacy + new pattern |
| REST emitter | `src/main/kotlin/com/m8trx/twin/layer0/RestEmitter.kt` | ✅ Written, untested (service bearer 401) |
| Store layout doc | `reference/data/STORE-LAYOUT.md` | ✅ 600 sqm, full fixture spec |
| Floor plan SVG | `reference/data/floor-plans/decathlon-running-medium.svg` | ✅ Generated |
| Snapshot JSON | `reference/data/snapshots/decathlon-running-small/day-start.json` | ⚠ Outdated (300 sqm) — update to 600 sqm |
| Seed script | `scripts/seed_store.py` | ✅ Live on mother |
| Raw catalog | `reference/sample_stores/decathlon-korea-raw.csv` | ✅ 56,003 rows |
| Curated catalog | `reference/data/catalog/decathlon-korea-curated.csv` | ✅ 920 SKUs, USD prices |
| Final SKU file | `reference/data/catalog/decathlon-manhattan-skus.csv` | ✅ English names, ready |
| Florence CAD ref | `reference/sample_stores/deacthlon_florence/` | ✅ 4 files |
| API surface doc | `reference/integration/M8TRX-API-SURFACE.md` | ✅ 27 atoms mapped |

---

## Active Requirements Filed Back to Core

| Brief | Status | Blocks |
|---|---|---|
| TWIN-REQ-001 `fitting_room` → `try_on_zone` | ABSORBED 2026-05-09 | — |
| TWIN-REQ-002 `commerce_projection` writer | **FILED, AWAITING ABSORPTION** (2026-06-11) | Scripts 1, 3, 5 |
| CORE-REQ-001 catalog attribute enrichment (brand · classification · coded attrs) — **inverse, core→twin** | ✅ **ABSORBED** 2026-06-21 (core loaded + verified; merged-commit `eb39526`) | — |
| CORE-REQ-002 `site_category` (functional role `store/office/warehouse`) — **inverse, core→twin** | 📦 **DELIVERED** 2026-06-23 (manifest + spec; rides the reseed; core mig 146) | — |
| `inventory:sell` capability split | PRE-EXISTING in CLEANUP-TASKS | Cashier persona |

> TWIN-REQ-002 brief: `~/IdeaProjects/m8trx-shared/twin/requirements/TWIN-REQ-002-commerce-projection-writer.md` (filed by core 2026-06-11, formalizing the insight at CLAUDE.md §Insights). P1 — blocks the commerce story on the API path until core ships the writer (feed-raw-let-platform-derive per `twin/insights/IMPORT-CONTRACT.md` §2).

> **CORE-REQ-001 (delivered 2026-06-21):** catalog attribute coding for the Things/Discover surface. **Decathlon** profile — `reference/data/chain/{classification.csv, display_lookup.csv}` + `brand`/`classification_key` on assortment (normalisation model). **MK/Hansae** profile (second model, built) — `reference/data/mk-trend/` (numeric-code model) from the real MK Trend spec (`reference/hansaemk/`). Same coding grain across both → vertical-portable. Rationale: `reference/data/chain/CATALOG-CODING-MODEL.md`; MK writeup: `reference/data/mk-trend/MK-CODING-PROFILE.md`. Awaiting core re-seed → ABSORBED.

---

## Deploy State (Session 3)

- m8trx-twin: uncommitted (coordinator handles commit)
- M8trxDemo on mother: 160 zones + 920 products live
- NATS: smoke objLocation published successfully to .29
- Service API key: active, `principal_kind=service` — auth works on NATS, fails on REST inventory endpoints

---

## Open Decisions

- **Container deploy target** — decision deferred until first runnable scenario
- **Stack** ✅ LOCKED: Kotlin
- **Scenario clock** ✅ LOCKED: shared scheduler, `rate=0` step mode
- **Config canonical format** ✅ LOCKED: JSON
