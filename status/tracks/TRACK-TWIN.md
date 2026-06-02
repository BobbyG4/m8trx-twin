# Track: Twin

**Last session:** Session 4 (2026-06-03)
**Last session notes:** [→](../session-notes/2026-06-03-session-4-store-rebase-denver-real-catalog.md)

---

## Current State

**m8trx-twin:** main branch, Kotlin scaffold compiles, NATS smoke passed
**Store data:** re-based on **live regional catalogs** (two-store model):
- **Denver (US)** — built from live US Decathlon Shopify catalog: 2,586 SKUs / 35,912 EPCs / 100% real images. `reference/data/analysis/denver-{assortment,epcs}.csv` · `scripts/build_denver.py`. **NOT seeded to mother yet** (gated prod write).
- **Seoul (KR city)** — PARKED. KR-derived assortment built (`manhattan-assortment-final.csv`, 4,954 SKUs); images need live-KR (Algolia) re-base (only 6% free overlap with US).
**M8trxDemo on mother:** space renamed Manhattan→**Denver** (manual, threw failures first); 160 zones + 920 products live · **inventory items/EPCs still NOT seeded**
**EPC encoder:** VALIDATED clean-room (`reference/data/EPC-ENCODING-DECATHLON.md`) — filter 1 / partition 6, EAN-derived, round-trips real tags bit-for-bit.
**Service API key:** active (`m8trx_6f…`, `principal_kind=service`) — works on NATS, fails on REST inventory endpoints

## Open hand-off
**`status/active/ONBOARDING-BASELINE-HANDOFF-2026-06-03.md`** — next session = Coordinator-track plan for customer onboarding across core + twin (catalog import, EPC config, imagery + more).

## Blocked on Core

- **Service bearer auth** — `InventoryActionController` JWT-only; `POST /api/v2/inventory/items/receive` returns 401. Tracked in `m8trx-shared/status/CLEANUP-TASKS.md` as `SERVICE-BEARER-INVENTORY`.
- **Catalog onboarding flow** — no product import path for tenants. Tracked in CLEANUP-TASKS.md as `CATALOG-IMPORT-ONBOARDING`.

## Open Work (priority order)

1. **Onboarding-baseline plan (NEXT, Coordinator track)** — `status/active/ONBOARDING-BASELINE-HANDOFF-2026-06-03.md`. Plan customer onboarding across core + twin; baseline UI holes / broken state.
2. **Seed Denver to mother** — build `day-start.json` snapshot + `item_identifier`/`thing_location`/product-attrib (image) mutations from `denver-{assortment,epcs}.csv`; verify inventory-table schema first; gated prod write (~36k rows); decide tenant naming.
3. **TrafficGenerator** — Layer 3 walking-actor loop (sketch in `reference/architecture/TRAFFIC-GENERATOR-SKETCH.md`); needs orchestrator runtime skeleton first.
4. **Seoul (unpark)** — re-base off live KR catalog (Algolia) so products + images come together; reuse EPC encoder + Nov velocity.
5. **TransactionGenerator** — from Nov 160k real baskets (basket size ~2.3 UPT).

## Active Requirements Filed to Core

| Brief | Status | Blocks |
|-------|--------|--------|
| TWIN-REQ-001 `fitting_room` → `try_on_zone` | ✅ ABSORBED (mig 127) | — |
| `commerce_projection` writer | NOT YET FILED | Scripts 1, 3, 5 |
| `inventory:sell` capability split | PRE-EXISTING in CLEANUP-TASKS | Cashier persona |

## Key Docs
- Project context: `CLAUDE.md`
- Sister project contract: `~/IdeaProjects/m8trx-shared/twin/SISTER-PROJECT.md`
- Layer 4 schema: `reference/architecture/LAYER4-CONFIG-SCHEMA.md`
- Store layout: `reference/data/STORE-LAYOUT.md`
- API surface: `reference/integration/M8TRX-API-SURFACE.md`
