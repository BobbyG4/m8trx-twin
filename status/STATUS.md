# Project Status — M8TRX Twin

---

## ⚠ NEXT SESSION PRIORITIES (updated 2026-06-22 — Session 6 close)

**Session 6 = catalog-coding + store-layout overhaul. All committed + deterministic; NOT yet reseeded to mother → a full reseed is the #1 next step.**

- **CORE-REQ-001 (inverse core→twin) DELIVERED** — catalog attribute coding: `brand` (←Shopify vendor), `classification.csv` (5 roots + 90 leaves + per-class `attributes_schema`), `display_lookup.csv` (colour raw→canonical family ×3 locales + swatch). Decathlon **normalisation** model. Brief → `delivered`.
- **MK/Hansae numeric-code coding profile BUILT** — `reference/data/mk-trend/` (the 2nd, contrasting coding model, from the real MK Trend spec in `reference/hansaemk/`). Proves attribute-coding is vertical-portable (same `display_lookup`/`classification` grain). Multi-catalog architecture input filed to core (`m8trx-shared/twin/insights/2026-06-22-multi-catalog-coding-architecture.md`).
- **Store layouts overhauled** — fixed the **134-overlap** bug (root cause: twin's hand-authored coords, NOT core's seed) AND went **per-store unique**: `build_layout.py` is now parametric → **10 distinct floors** seeded off `store_id` (324–741 m², grids 2×2–6×7, 0 overlaps + 0 OOB asserted), `build_chain.py` layout-driven planogram, checkout lanes scale by tier (4/3/2), circular front-of-store feature displays added. Floor-plan SVGs via `scripts/render_floorplans.py`.
- **Geometry now matches mother's live `zone` model** (verified vs real rows) — circle = center `POINT Z` + `properties{centerX,centerY,radiusX,radiusY,rotation}`; polygon = `POLYGON Z` ring; SRID 0, mm, Z=0.
- **jackson 2.18.2→2.21.3** (HIGH CVEs, 2026-06-22 tech-watch).

**Commits:** twin `f24c82c` (coding) · `345f9b8` (MK) · `7d1e4e8` (jackson) · `19ccedf` (per-store layouts) · `e64ac01` (geometry); shared `1477f90` `346c9f4` `3ea86ac`. **Session 5 detail:** `status/session-notes/2026-06-11-session-5-chain-seed-corrections-playbook-roadmap.md`.

### What's seeded in M8trxDemo (live on mother)

| Asset | State |
|---|---|
| Tenant | **M8trxDemo** `ecfa6903-5c50-439f-8f80-185982de944e` — chain seeded via direct psql (reversible via tenant-delete; pre-seed backup on mother) |
| Sites | **14** — 10 retail (US×3 / FR×5 / KR×2) + 4 office (HQ + 3 regional) |
| Users | **251** — tenant-admin `zenvendemo@gmail.com`; roles → core Profiles (member/site-manager/staff); 30 inactive |
| Catalog | **2,586 products** (tenant-scoped) + 2,586 images; **USD display** (EUR/KRW in `display_attributes.prices`) |
| Inventory | **277,515 EPCs** → item/identifier (1:1, `in_stock`) — **site-level only** (no fixture pins yet) |

### Pending follow-up deploy (data ready)
Provision each retail site's **own** spaces/zones/fixtures (`stores/<id>/layout.json` — **per-store unique floors**, 2026-06-22 redesign) + place items at fixture-zones via **scan/receive** (corrections §2). Closes the site-level limitation.

> **⚠ Layout redesigned 2026-06-22 — full reseed needed.** Two changes: (1) fixed the overlap bug — the originally-seeded layout had **134 overlapping fixture pairs** (hand-authored gondolas pitched 1800mm but 2400mm deep, into the specialty cluster); root cause was the **twin source, not core's seed**. (2) **`build_layout.py` is now parametric per-store** — each of the 10 stores has a UNIQUE floor (footprint/grid/aisles/specialty seeded off store_id; flagship ~600m²/5–6 rows → medium ~400m²/3–4 rows), 0 overlaps asserted, layout-driven planogram. All 10 regenerated (`stores/<id>/layout.json`). The on-mother layout is superseded → re-provision per-store + re-receive the 277k EPCs at the new fixtures. SVGs: `scripts/render_floorplans.py`.

### Immediate next steps (ranked)

1. **★ Draft + run the core RESEED hand-off** — the gating item. M8trxDemo on mother holds the OLD (overlapping, shared) layout + an unfed coding layer. Core needs to: tenant-delete / re-provision the **10 per-store layouts** (`stores/<id>/layout.json` — geometry now matches mother), load the **CORE-REQ-001** artifacts (`classification.csv`, `display_lookup.csv`, + `brand`/`classification_key` on `assortment.csv`), and **re-receive the 277,515 EPCs** at the new fixtures via scan/receive (corrections §2). Then verify on mother + flip CORE-REQ-001 brief → `absorbed`. Twin data is ready, deterministic, regenerable (`build_layout`→`build_chain`→`build_attributes`→`render_floorplans`). **Draft this hand-off first thing.**
2. **Verify circular fixtures render round** on MapCanvas post-reseed (geometry now matches mother — should be true circles, not bounding boxes).
3. **Resolve the image pipeline** with backend — Shopify hot-link vs **cache bytes**. *(parallel; see Blocked on core)*
4. **Full activity — the "play" (BEFORE Wave 2)** (`ACTIVITY-PLAN.md`) — runtime skeleton → TrafficGenerator → TransactionGenerator → try-on → staff shifts/journeys → restock/stocktake → LP/EAS, item-movement throughout. **Animate Wave 1 + light the analytics.**
5. **Wave 2 — 10 international stores** (`EXPANSION-PLAN.md`) — parametric per-store layout mechanism already landed; China←KR catalog, rest←US Shopify; UI-then-API onboarding.
6. **Connect simulator** — external vendor feeds (POS/catalog/shipment) via webhook/HMAC; pairs with Wave-2 API onboarding.

**Deferred (Bob's call, noted):** rotate the mother Hasura admin secret + de-hardcode it from `scripts/seed_store.py:20` (committed prod secret — Bob will rotate later). Optional fixture realism: end-caps (need reserved hall space). MK/Hansae EPC bit-encoding TBD (only if an MK tenant is seeded).

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
| CORE-REQ-001 catalog attribute enrichment (brand · classification · coded attrs) — **inverse, core→twin** | **DELIVERED 2026-06-21**, awaiting core re-seed → ABSORBED | Things/Discover surface (core) |
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
