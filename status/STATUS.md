# Project Status — M8TRX Twin

---

## ⚠ NEXT SESSION PRIORITIES (updated 2026-05-11 — Session 3 close)

**Session 3 was the first real-code session.** Kotlin project scaffolded, NATS wired end-to-end, store seeded (Decathlon Manhattan, 600 sqm, 160 zones), 920-SKU catalog curated from Korea data and loaded. Major side-effect: the twin exercise exposed serious gaps in the core MapCanvas rendering (all zones same color, no hierarchy, label collision at scale) — these are now contracted for a web session.

**Before next twin session:** check STATUS.md in m8trx-shared for MapCanvas contract delivery status. If canvas is still broken, twin data will still be invisible on VisionAI. Canvas fix is a hard prereq for meaningful demo.

### What's seeded in M8trxDemo (live on mother)

| Asset | State |
|---|---|
| Tenant | Decathlon Manhattan (`decathlon-manhattan`), 620 6th Ave NY 10011, USD |
| Site | Decathlon Manhattan - Flatiron |
| Space | Main Floor, 24m × 25m (600 sqm), boundary updated |
| Zones | 160 zones (8 area + 3 try_on + 149 fixture) — E-W gondola grammar from Florence CAD |
| Products | 920 SKUs, English names, USD pricing, 40 high-value EAS-flagged items |
| Items / EPCs | ❌ Not seeded — next session |

### Immediate next steps (ranked)

1. **`inventoryReceive` + item seeding** — create EPCs for each product on the floor. Needed before any RFID scenario runs. Use `POST /api/v2/inventory/items/receive` once service bearer auth is fixed, OR Hasura admin path as interim.
2. **Service bearer auth fix** (backend session) — `InventoryActionController` uses JWT-only `extractAuthContext`. Add `ApiKeyService` injection + switch to 3-arg overload. Filed in `m8trx-shared/status/CLEANUP-TASKS.md` as `SERVICE-BEARER-INVENTORY`.
3. **TrafficGenerator** — Layer 3 walking-actor loop: emit `objLocation` every 500ms along a random path through gondola zones + proper session lifecycle (`personSessionStart` → walk → `personSessionClose` → `objEviction`).
4. **`personSessionStart` smoke** — test the REST atom end-to-end against M8trxDemo once service bearer is fixed.
5. **Persona names** — English/American names for NYC demographic. Korean names don't translate well. Internationalize later.

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
