# Project Status — M8TRX Twin

---

## ⚠ NEXT SESSION PRIORITIES (updated 2026-06-11 — Session 5 close)

**Session 5 built + seeded the multi-store chain dataset.** Wave 1 = **14 sites** (10 retail + 4 office), **251 users** (30 inactive), **2,586-SKU** catalog, **277,515 EPCs**, one shared **160-zone** layout, localized USD/EUR/KRW · EN/FR/KO — all under `reference/data/chain/`, deterministic. **Backend seeded it to M8trxDemo** (`tenant_id ecfa6903…`, site-level) and filed an **IMPORT-CONTRACT** + a **5-point corrections** doc + **TWIN-REQ-002**. Applied the biggest correction (**fixtures are `zone_type='fixture'` zones**) and captured everything in a **SEED-PLAYBOOK**, a Phase-2 **ACTIVITY-PLAN**, and an **EXPANSION-PLAN** (Wave 2 / Wave 3).

**Session 5 detail:** `status/session-notes/2026-06-11-session-5-chain-seed-corrections-playbook-roadmap.md`.

### What's seeded in M8trxDemo (live on mother)

| Asset | State |
|---|---|
| Tenant | **M8trxDemo** `ecfa6903-5c50-439f-8f80-185982de944e` — chain seeded via direct psql (reversible via tenant-delete; pre-seed backup on mother) |
| Sites | **14** — 10 retail (US×3 / FR×5 / KR×2) + 4 office (HQ + 3 regional) |
| Users | **251** — tenant-admin `zenvendemo@gmail.com`; roles → core Profiles (member/site-manager/staff); 30 inactive |
| Catalog | **2,586 products** (tenant-scoped) + 2,586 images; **USD display** (EUR/KRW in `display_attributes.prices`) |
| Inventory | **277,515 EPCs** → item/identifier (1:1, `in_stock`) — **site-level only** (no fixture pins yet) |

### Pending follow-up deploy (data ready)
Provision the **corrected spaces/zones/fixtures** per retail site (`layout/space-template.json`, 160 zones = 11 area + 149 `zone_type='fixture'`) + place items at fixture-zones via **scan/receive** (corrections §2). Closes the site-level limitation.

### Immediate next steps (ranked)

1. **Resolve the image pipeline with backend** — Shopify hot-link vs **cache bytes** into M8TRX's own asset store. *(parallel; see Blocked on core)*
2. **Follow-up deploy** — corrected fixture-zone layout + scan/receive item placement (completes Wave-1 inventory at fixtures).
3. **Full activity — the "play" (BEFORE Wave 2)** (`ACTIVITY-PLAN.md`) — runtime skeleton → TrafficGenerator (people on map) → TransactionGenerator → try-on → staff shifts/journeys → restock/stocktake → LP/EAS, with item-movement throughout. **Animate Wave 1 + light the analytics first.**
4. **Wave 2 — 10 international stores** (`reference/data/chain/EXPANSION-PLAN.md`) — *after* the baseline is alive; parametric `build_layout` + layout-driven `build_chain`; China←KR catalog, rest←US Shopify; UI-then-API onboarding; validates the playbook both sides.
5. **Connect simulator** — external vendor feeds (POS/catalog/shipment) via webhook/HMAC; pairs with Wave-2 API onboarding.

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
