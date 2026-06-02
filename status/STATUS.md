# Project Status — M8TRX Twin

---

## ⚠ NEXT SESSION PRIORITIES (updated 2026-06-03 — Session 4 close)

**Session 4 re-based the store data on live regional catalogs (two-store model)** and closed with a hand-off to a cross-project onboarding plan. **Denver (US)** built from the live US Decathlon Shopify catalog — 2,586 real SKUs, 35,912 EPCs, 100% real images, native US names/prices/sizes. **Seoul (KR city) parked** (images need live-KR re-base). **EPC encoder validated** clean-room vs 169k real warehouse tags (filter 1 / partition 6). Denver **not yet seeded to mother** (gated).

**NEXT SESSION = Coordinator track:** `status/active/ONBOARDING-BASELINE-HANDOFF-2026-06-03.md` — baseline customer onboarding across core + twin (catalog import, EPC config, product imagery + the full tenant journey) and the UI cleanup. Build as a working-draft.

**Session 4 detail:** `status/session-notes/2026-06-03-session-4-store-rebase-denver-real-catalog.md`.

### What's seeded in M8trxDemo (live on mother)

| Asset | State |
|---|---|
| Tenant/Store | renamed Manhattan → **Denver** (manual edit; threw failures first — onboarding-UX gap noted) |
| Space | Main Floor, 24m × 25m (600 sqm), 160 zones (Manhattan layout, reused for Denver) |
| Products | 920 SKUs from Session 3 (Korea-derived) — to be replaced by the Denver US catalog |
| Items / EPCs | ❌ Still NOT seeded — Denver dataset built (`denver-{assortment,epcs}.csv`, 2,586 SKUs / 35,912 EPCs), seed gated |

### Built this session, ready to seed (not yet on mother)
- **Denver** — `reference/data/analysis/denver-assortment.csv` (real US SKUs + images) + `denver-epcs.csv` (35,912 EPCs) · `scripts/build_denver.py`
- **EPC encoder** — `reference/data/EPC-ENCODING-DECATHLON.md` (validated)
- **Operating model** — `reference/data/STORE-OPERATING-MODEL.md` + `.json`
- **Pipeline + planogram** — `reference/data/INVENTORY-SEEDING-PIPELINE.md`

### Immediate next steps (ranked)

1. **Onboarding-baseline plan (Coordinator track)** — `status/active/ONBOARDING-BASELINE-HANDOFF-2026-06-03.md`. Cross-project plan for customer onboarding + UI cleanup. Pull FRs from `9a`, audit core onboarding surfaces, build a working-draft.
2. **Seed Denver to mother** — `day-start.json` snapshot + `item_identifier`/`thing_location`/product-image mutations from the Denver CSVs; verify inventory schema first; gated ~36k-row prod write; decide tenant naming.
3. **TrafficGenerator** — Layer 3 loop (sketch ready); needs orchestrator runtime skeleton (`com.m8trx.twin.runtime`) first.
4. **Seoul (unpark)** — re-base off live KR catalog (Algolia) for products + images; reuse EPC encoder + Nov velocity.
5. **TransactionGenerator** — from Nov 160k real baskets.

### Blocked on core

- **Service bearer not wired to inventory endpoints** — `InventoryActionController`, and likely others, need `ApiKeyService` injected. Filed in `m8trx-shared/status/CLEANUP-TASKS.md`.
- **Catalog import onboarding flow** — no product catalog import UI or REST path exists for tenants. Twin works around via Hasura admin. Real customers need this as part of tenant setup. Filed in CLEANUP-TASKS.md as `CATALOG-IMPORT-ONBOARDING`.
- **MapCanvas rendering** — zones all render as the same green regardless of `zone_type`. Contract at `m8trx-shared/status/cleanup/MAPCANVAS-ZONE-RENDERING-CONTRACT-2026-05-11.md`. Canvas fix is a hard prereq for demo-quality VisionAI display.

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
| `commerce_projection` writer | NOT YET FILED | Scripts 1, 3, 5 |
| `inventory:sell` capability split | PRE-EXISTING in CLEANUP-TASKS | Cashier persona |

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
