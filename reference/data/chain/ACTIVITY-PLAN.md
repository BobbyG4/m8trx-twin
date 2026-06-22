# Phase 2 — Store Activity Plan ("A Day in the Life")

**What this is:** the plan for the *dynamic* layer — the event stream that makes the seeded chain
**live** and, crucially, **produces the events every analytics surface reads**. Phase 1 built the
**set** (sites · per-store departmentalized spaces + lean BOH · catalog + coding · 102,675 opening-state items · 250 users).
Phase 2 is the **play**.

**Design principle — model backwards from the analytics.** Every behavior below exists because a
dashboard needs the events it emits. If a behavior lights up no surface, we don't model it.

**Sequencing — this comes BEFORE Wave 2.** Animate the Wave-1 baseline chain (the whole table
below) and light the analytics *first*; only then expand to the Wave-2 international stores
(`EXPANSION-PLAN.md`). Depth before breadth.

**Builds on (don't duplicate):** Layer 0–4 architecture (`reference/architecture/`), the
`PERSONA-SCHEMA`, the DomainEvent taxonomy, `STORE-OPERATING-MODEL.md` (calibration), the 27-atom
`M8TRX-API-SURFACE.md`, and the corrected import model (`SEED-PLAYBOOK.md` rules — esp. §2: location
is read from `scan_event` at the **fixture-zone**).

---

## Three actor classes

| Actor | What it is | Provisioned? | Driven by |
|---|---|---|---|
| **Customers** | synthetic shoppers, spawned per arrival | ❌ no — created at runtime (`personSessionStart`) | TrafficGenerator + a persona/journey |
| **Staff** | the 250 real users, scheduled into shifts | ✅ yes (Phase 1) | StaffShiftGenerator + staff journeys |
| **Items** | the 102,675 tags — they **react** | ✅ yes (opening stock) | customer & staff handling (each touch = a location event) |

**Items are the connective tissue.** A customer or staff member acting on an item produces an RFID
location change — `scan_event.zone_id` at a **fixture-zone**. Item movement is what ties traffic,
staff, sales, and inventory analytics into one coherent stream.

---

## The item lifecycle in a day (linked to traffic) — the realism core

Within a session, each handled item walks a micro-journey. **Every arrow is a Layer-1 behavior that
emits an RFID/location event** (the tag read at its new zone):

```
pick up from fixture F  →  tag travels WITH the actor (carried)  →  one of:

  ├─ put back, SAME fixture F            → correct re-shelve            (no analytics flag)
  ├─ put back, WRONG fixture F'          → MISPLACEMENT                 → planogram-compliance / stock-out-of-place
  ├─ carry to fitting room (try_on_zone) → try on → one of:
  │     ├─ keep → carry to checkout      → SOLD
  │     ├─ leave it in the FR            → ABANDONED                    → staff must recover (FR recovery loop)
  │     └─ carry back to floor           → (right or WRONG fixture)
  ├─ carry to checkout                   → SOLD (leaves store)          → sales / commerce
  └─ carry past EAS without paying       → THEFT                        → EAS alarm + shrink
```

The *realism details Bob called out* — "looks and puts back on the wrong rack", "takes to fitting
room", "leaves it behind" — are precisely the states that make inventory analytics non-trivial
(perfect put-backs produce no insight). Each customer session samples **N item interactions** from
the dwell / zone-affinity model.

---

## Customer journeys (Layer 2) — with item interactions baked in

Existing personas, extended so each carries the item micro-journeys above:

| Persona | Share | Item behavior |
|---|---|---|
| **BrowseAndLeave** | 78% | handles a few items, puts back (some on the wrong rack), no buy |
| **ShopAndBuy** | 14% | picks, carries to checkout → SOLD |
| **TryOnAndPartialBuy** | 8% | fitting room, keeps a subset, abandons/misplaces the rest |
| **Shoplift** | 0.3% (scenario) | conceals item, exits past EAS → alarm + shrink |

---

## Staff activity (Layer 2/3) — the depth that sells the analytics

The 250 users scheduled by **StaffShiftGenerator** (5 on-floor at peak / 3 off-peak, per operating
model §9). Staff don't just exist — they **act**, and their actions move items and engage customers:

| Staff journey | What happens | Emits | Lights up |
|---|---|---|---|
| **Cashiering** | process baskets at CO-01/02; queue forms/clears | `saleNative` (→ `item_custody_event` SOLD) | revenue, basket, queue/throughput |
| **Customer engagement** | approach & assist; gait-analysis at Z-09 | staff↔customer co-location | staff-assisted conversion, service rate |
| **Fitting-room fetch-on-request** ⭐ | customer in FR requests another size **via the M8TRX appliance/app** → nearest free staff assigned → walks to fixture → picks up → carries to FR → hands off | app request event + staff path + item move floor→FR | **service responsiveness**, FR conversion |
| **Restock / replenishment** | fixture stock low → bring units from backroom (Z-05) → fixture | item move backroom→floor (`scan_event`) | replenishment, on-shelf availability |
| **Re-shelve misplaced** | find wrong-rack items → return to planogram home | item move → correct fixture | misplacement **recovery time** |
| **Stocktake** | periodic RFID walk of zones | `stocktake*` reads | inventory accuracy, discrepancies |

⭐ **The fetch-on-request loop is the showcase interaction** — it's the one that demonstrates the
M8TRX app + staff + RFID + analytics working as one system. It needs a **customer-request surface**
(the "appliance"): *verify it exists in the public API; if not, file a TWIN-REQ — do not shim.*

---

## Activity → analytics map (the whole point)

| Analytics surface | Fed by |
|---|---|
| Footfall, heatmaps, dwell, zone affinity | customer `objLocation` streams + `crossing` |
| Conversion funnel (visit → try-on → buy) | sessions + try-on + sales |
| Product interaction ("most-handled SKUs", pick-to-buy) | item pick-up/put-down events |
| **Planogram compliance / stock-out-of-place** | misplacement (wrong-rack put-backs) |
| Fitting-room conversion + abandoned items + **fetch response time** | try-on sessions + the app-request loop |
| Revenue, basket, units/txn, **sales density per fixture** | sales → `commerce_projection` (blocked on TWIN-REQ-002) |
| Staff-assisted conversion, service rate | staff↔customer engagement |
| Shrink, theft, EAS alarms | Shoplift + `easAlarm` |
| Inventory accuracy, replenishment, availability | stocktake + restock item moves |

---

## Generators (Layer 3) + runtime — build status

| Generator | Status | Calibration source |
|---|---|---|
| TrafficGenerator | ❌ sketch only | operating model: 850/day, hourly curves, persona mix, dwell |
| TransactionGenerator | ❌ not built | Nov 160k baskets · $58 ATV · 2.2 UPT |
| StaffShiftGenerator | ❌ not built | staffing §9 (5 peak / 3 off) |
| StocktakeGenerator / RestockGenerator | ❌ not built | cadence (weekly cycle, AM open-fill) |

**Prerequisites (cross-cutting):**
- **Orchestrator runtime skeleton** (`com.m8trx.twin.runtime`) — all Layer-3 generators need it.
- **Emit path** — `NatsEmitter` ✅ (objLocation/crossing/EAS fire today); `RestEmitter` ❌ gated on
  **service bearer** (sales/sessions/scans).
- **Sensor/reader entities** — events need a `sensor_id`/`reader_id`; we have the 5 placements in the
  layout but they aren't provisioned devices yet.
- **Time/calendar** — which day(s), per-timezone opening hours, and **historical pre-aging** so
  trends/heatmaps have depth (one live day ≠ weeks of history).

---

## M8TRX Connect — external-integration simulator (needed, later phase)

Everything above emits the store's **own** signals (its sensors, its internal POS). **M8TRX Connect**
is the *integration* surface — where a customer's **external** systems (POS, ERP, e-commerce, 3PL)
feed M8TRX. To demo Connect + the integration-health dashboards, we need a simulator that **acts as
those external systems**, sending realistic **vendor-shaped** payloads into the public ingest:

- `POST /v1/webhook/{tenantId}/{integrationKey}` — `X-API-Key` or HMAC-signed (atoms #22 `saleWebhook`,
  #23 `productCatalogWebhook`, #24 `shipmentManifestWebhook`).
- **Per-vendor field mappings** (Lightspeed Retail, Shopify, generic feed) so the demo can defensibly
  claim *"this is Decathlon's real Lightspeed POS / Shopify catalog feed."*
- Per-integration provisioning (an `integration` row + api_key/HMAC secret) → lights the
  **integration-health dashboard** (`integration_event` rows, connector status, field-mapping view).

It's the **Connect-path counterpart** to the in-store generators: the same sale can arrive as
`saleNative` (#21, internal) *or* `saleWebhook` (#22, "via the customer's POS through Connect"). For
the Connect demo we drive the webhook path; likewise catalog updates (#23) and inbound shipments
(#24, paired with dock RFID reads).

Status: **not built** — distinct component from the in-store generators. Ties to the **"Per-vendor
field mapping (Lightspeed Retail)"** insight (twin `CLAUDE.md` §Insights — NOT YET FILED).

---

## Core blockers that gate the analytics (file/track, don't shim)

| Blocker | Gates | Tracked as |
|---|---|---|
| Service bearer on inventory/REST endpoints | the entire REST emit path | `SERVICE-BEARER-INVENTORY` |
| `commerce_projection` writer | all commerce dashboards (sales density, conversion, basket) | **TWIN-REQ-002** (filed) |
| No EAS alarm subscriber in core | the LP/theft analytics | API-SURFACE gap (P1 if LP demo required) |
| Cold-start / manual location (no-scan) | opening-stock placement nuance | core CLEANUP-TASKS |
| **Customer fitting-room request surface ("appliance")** | the staff fetch-on-request showcase | ⚠ **verify exists / else NEW TWIN-REQ** |

---

## Build order (shortest path to "looks alive", then to "full analytics")

1. **Opening-stock placement** — scan/receive the 102.7k items to their department/BOH fixture-zones → inventory surfaces light up. *(data ready; the reseed)*
2. **Runtime skeleton + emit path** — unblock REST (service bearer).
3. **TrafficGenerator + customer journeys w/ item micro-movement** → people on the map + RFID movement. *Biggest visual win; NATS-only, no core blocker.*
4. **TransactionGenerator** → sales. *(commerce dashboards gated on TWIN-REQ-002)*
5. **StaffShiftGenerator + staff journeys** — cashiering, engagement, **fitting-room fetch-on-request**.
6. **Stocktake / Restock / LP-EAS** scenario layers.
7. **M8TRX Connect simulator** — external vendor feeds (POS / catalog / shipment) via webhook/HMAC, per-vendor field mappings → Connect + integration-health surfaces.
8. **Historical pre-aging** → trend & heatmap depth.

---

## Reset / lifecycle (TO DO — not yet filed)

Running activity drifts the tenant (item state + the event hypertables). Reset must be
**tenant-scoped** — M8trxDemo shares `mother` with real customer tenants, so **no whole-DB restore,
no twin direct-DB**. Only the state+event layer needs resetting; the static layer
(sites/spaces/zones/catalog/users) never drifts.

**Agreed approach to build — "logical reset to opening state":** tenant-scoped truncate of the
activity (event hypertables + Layer-2 projections + audit, ideally by `run_id`/time window) +
**re-assert every item to IN_STOCK at its opening fixture-zone from `epcs.csv`**. Keeps the static
layer (no full re-onboard) → seconds-to-minutes, repeatable, and doubles as a Twin product feature
(reset-and-re-run a scenario). `epcs.csv` *is* the canonical opening state.

**Why not filed yet:** the corrected seed (fixture-zones + scan-event placement) isn't 100%
validated — don't file a TWIN-REQ against a moving target. Revisit once the seed is confirmed clean.

**Checklist:**
- [ ] **File + build** the tenant *reset-to-opening-state* capability (core; logical reset, by run-tag) — once seed validated
- [ ] Backend takes a **golden opening-state snapshot** right after the corrected seed (known-good restore point)
- [ ] Confirm **delete-tenant → re-seed** works end-to-end (the safe fallback; deterministic re-build)
- [ ] Orchestrator stamps a **`run_id`** on every emitted event (enables per-scenario, not all-or-nothing, reset)

---

## Pointers
- Calibration: `reference/data/STORE-OPERATING-MODEL.md` · Atoms: `reference/integration/M8TRX-API-SURFACE.md`
- Personas/journeys: `reference/architecture/PERSONA-SCHEMA.md` · Layer-4 schema: `reference/architecture/LAYER4-CONFIG-SCHEMA.md`
- Seed exercise (the set): `SEED-PLAYBOOK.md` · `CHAIN-DATA-SPEC.md` · `DEPLOY-HANDOFF.md`
