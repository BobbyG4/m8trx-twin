# Track: Twin

**Last session:** Session 5 (2026-06-11)
**Last session notes:** [→](../session-notes/2026-06-11-session-5-chain-seed-corrections-playbook-roadmap.md)

---

## Current State

**m8trx-twin:** main branch, Kotlin scaffold compiles, NATS smoke passed. No event emit yet (runtime not built).
**Chain dataset (Wave 1):** built + committed under `reference/data/chain/` — **14 sites** (10 retail + 4 office), **251 users**, **2,586-SKU** catalog, **277,515 EPCs**, one shared **160-zone** layout (11 area + 149 `zone_type='fixture'`), localized USD/EUR/KRW · EN/FR/KO. Deterministic builders (`build_layout`/`localize_names`/`build_chain`/`build_staff_roster`, sha256 seeds).
**M8trxDemo on mother:** **Wave 1 SEEDED** by backend — `tenant_id ecfa6903-5c50-439f-8f80-185982de944e`, via direct psql, **site-level** inventory, USD display, fixtures stored as zones. Reversible via tenant-delete; pre-seed backup on mother.
**Pending follow-up deploy:** the **corrected fixture-zone layout** + **scan/receive item placement** (so inventory shows at fixtures, not just site-level). Data ready (`layout/space-template.json` + `epcs.csv` fixture codes).
**EPC encoder:** VALIDATED (`EPC-ENCODING-DECATHLON.md`) — filter 1 / partition 6, round-trips real tags.

## Open hand-offs
- **`reference/data/chain/DEPLOY-HANDOFF.md`** — the seed handoff (Wave 1 done; follow-up = spaces/zones/fixtures + inventory placement).
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

1. **Resolve image pipeline with backend** — link vs cached bytes (parallel).
2. **Follow-up deploy** — provision the corrected **spaces/zones/fixtures** per retail site + place the 277k items at fixture-zones via scan/receive (closes the site-level limitation).
3. **Full activity — the "play" (BEFORE Wave 2)** (`ACTIVITY-PLAN.md`) — orchestrator runtime skeleton → TrafficGenerator (people on map, NATS) → TransactionGenerator → try-on → staff shifts/journeys → restock/stocktake → LP/EAS; item-movement is the connective tissue. **Animate Wave 1 + light the analytics before expanding.**
4. **Wave 2 — 10 international stores** (`EXPANSION-PLAN.md`) — *after* the baseline is alive; parametric `build_layout` + layout-driven `build_chain`; China←KR catalog, rest←US Shopify; onboard UI-first then API/Connect. Validates the playbook both sides.
5. **Connect simulator** — external vendor feeds (POS/catalog/shipment) via webhook/HMAC; pairs with Wave-2 API onboarding.

## Active Requirements Filed to Core

| Brief | Status | Blocks |
|-------|--------|--------|
| TWIN-REQ-001 `fitting_room` → `try_on_zone` | ✅ ABSORBED (mig 127) | — |
| TWIN-REQ-002 `commerce_projection` writer | 📨 FILED, AWAITING ABSORPTION (2026-06-11) | Scripts 1, 3, 5 |
| `inventory:sell` capability split | PRE-EXISTING in CLEANUP-TASKS | Cashier persona |

## Key Docs
- Project context: `CLAUDE.md`
- Sister project contract: `~/IdeaProjects/m8trx-shared/twin/SISTER-PROJECT.md`
- Layer 4 schema: `reference/architecture/LAYER4-CONFIG-SCHEMA.md`
- Store layout: `reference/data/STORE-LAYOUT.md`
- API surface: `reference/integration/M8TRX-API-SURFACE.md`
