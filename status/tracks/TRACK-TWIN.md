# Track: Twin

**Last session:** Session 3 (2026-05-11)
**Last session notes:** [→](../session-notes/2026-05-11-session-3-first-code-nats-store-seeded.md)

---

## Current State

**m8trx-twin:** main branch, Kotlin scaffold compiles, NATS smoke passed
**M8trxDemo on mother:** 160 zones + 3 try_on_zones + 920 products live · Items/EPCs NOT seeded
**Service API key:** active (`m8trx_6f…`, `principal_kind=service`) — works on NATS, fails on REST inventory endpoints

## Blocked on Core

- **Service bearer auth** — `InventoryActionController` JWT-only; `POST /api/v2/inventory/items/receive` returns 401. Tracked in `m8trx-shared/status/CLEANUP-TASKS.md` as `SERVICE-BEARER-INVENTORY`.
- **Catalog onboarding flow** — no product import path for tenants. Tracked in CLEANUP-TASKS.md as `CATALOG-IMPORT-ONBOARDING`.

## Open Work (priority order)

1. **`inventoryReceive` + item/EPC seeding** — blocks all RFID scenarios. Use Hasura admin interim until service bearer fix lands.
2. **TrafficGenerator** — Layer 3 walking-actor loop: `objLocation` every 500ms + proper session lifecycle.
3. **`personSessionStart` smoke** — test REST atom end-to-end once service bearer fixed.
4. **Persona names** — English/American names for NYC demographic.
5. **Update `day-start.json`** — currently 300 sqm, needs 600 sqm.

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
