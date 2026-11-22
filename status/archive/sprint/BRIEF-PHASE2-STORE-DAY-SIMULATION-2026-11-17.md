---
title: Phase 2 — one simulation per store-day (stock-aware shoppers + staff + deliveries → a wire plan and a truth ledger)
date: 2026-11-17
author: twin lane (Opus, design) — build by Sonnet agents, slice by slice
parent: status/PLAN-STORE-REALISM-2026-11-17.md § 5, § 5.1; depends on Phase 1 (BRIEF-PHASE1-DENVER-STORE-MODEL-2026-11-17)
scope: OFFLINE — produces files; the live driver that sends them is Phase 3
---

# Phase 2 — one simulation per store-day

## 0. The idea in one paragraph
For a store and a local date, one seeded run decides everything that happens in the store that day:
- before opening, the delivery arrives and staff put units on fixtures;
- in open hours, shoppers walk, look and dwell, pick up units that are physically on a fixture, try on, buy or put back;
- in the quiet hours, staff top up fixtures from the back room.

The run emits two files:
- a **wire plan**: every event the platform should receive, in order, with its due time;
- a **truth ledger**: what really happened, per fixture and hour.

Phase 3's live driver sends the wire plan at real time. The gap ledger compares the truth ledger with what the platform holds.
People and sales come from the same shoppers, so the two line up by construction.

## 1. Build on what exists (do not rewrite these)
- **`behavior/`** (landed in #28): `BehaviorModel.runDay`, `ShopperAgent` (visits, pickups, try-ons, `SoldLine(cell)`),
  `ShelfPlan`/`Cell`/`PlanTimeline`, `Presentation` (placement, level and pickup factors, the six truths), `FixtureLedger`, the 5 Hz
  position stream (`SampleSink`, `ImpressionOracle`), `EpcPool`.
- **The Phase 1 model** (`stores/<slug>/model/`): `profile.json`, `fixtures.json` (kits, levels, capacities), `demand.csv`,
  `planogram.json` v2, `stock-plan.csv`.
- **Calendar and volume:** `StoryCalendar`, `store-operating-model.json`.
- **EPC minting:** `connect/ReceiveStock.kt`, an SGTIN-96 serial block starting at 1,000,000,000.
- **Wire DTOs and drivers:** sale with `occurred_at`, `inventory_movement`, `items/receive`, `planogram_directive`.

## 2. Slices (each its own PR; merge before the next starts)

### 2a — StoreState and the stock-aware day (Denver)
- **`StoreState`.** Every unit: EPC, EAN, location (zone code + level, or back room), and state
  (`on_floor | backroom | sold | in_transit`). Loaded from a snapshot file.
  - Offline: the snapshot is built from `stock-plan.csv`, with EPCs minted in a dedicated offline serial range.
  - Live (Phase 3): it is built from the twin's ledgers.
  - It is saved at day end, and the next day starts from it.
- **Stock-aware shoppers.** A pickup takes a real unit from the cell's fixture.
  - An empty cell counts as a **stock-out**: the shopper may still look but cannot take, and the ledger records a lost sale.
  - Product attraction uses the Phase 1 `demand.csv` share in place of the behavior package's flat catalog weight. Calibrate
    so a fully stocked day's units sold match `demand.csv` within ±10% per department.
  - A put-back returns the unit to its fixture. With a small probability (knob, default 2%) it goes to a neighbouring fixture
    instead, a real misplacement that the platform's compliance should see.
- **The pre-open delivery,** at `open − delivery_lead_min` local time:
  - one `items/receive` per store, carrying new EPCs for yesterday's units sold plus the back-room shortfall against cover;
    cases of 6, at most 200 EPCs per request;
  - then staff **stocking moves**, back room → fixture, for every cell below its `required_qty`, spread over the lead window;
  - staff throughput is a knob (default 120 units per staff-hour; staff on shift from the profile and staffing model).
- **Quiet-hour top-ups.** In the store's two to three lowest hours of the hourly curve, staff walk the fixtures below a
  threshold (knob, default 60% of `required_qty`) and move units up from the back room. Each move is one
  `inventory_movement` per (fixture, EAN) batch.
- **Wire plan** (`out/day/<slug>/<date>/wire-plan.jsonl`). One JSON object per event, ordered by due time:
  `{due_utc, kind, payload, ids}`, where `kind` is `receive | move | sale | person_samples_ref`.
  - **Ordering rule** (Backend/Web A1): for every unit, its last move before its sale must be due before the sale, and no
    later move of that unit may come before it.
  - A sale carries `occurred_at` = the shopper's exit.
  - Receipts and moves carry no event time, so their `due_utc` **is** their time.
  - Position samples for the edge (Denver) go to a sidecar file (the existing sample-stream format), referenced by shopper id.
- **Truth ledger** (`out/day/<slug>/<date>/truth/`), at the grain of the plan's § 5.1 table:
  - `fixture_hour.csv`: impressions, dwell seconds, pickups, try-ons, units sold, value sold, stock-outs;
  - `fixture_product_day.csv`: units sold per EAN per fixture;
  - `onhand.csv`: per fixture × EAN at open and close, against plan;
  - `flows.csv`: zone → zone per hour;
  - `summary.json`: visitors, transactions, units, conversion, lost sales, deliveries, moves.
- **Self-test** (`storeDaySelfTest`, offline, wired into `offlineSelfTest`):
  - ordering rule holds for every unit;
  - every sold EPC was on that fixture at sale time;
  - no EPC sold twice;
  - units conserved: start + received = end + sold;
  - a 28-day simulated run keeps on-hand per store within a band (no drain): week 4 sales ≥ 80% of week 1;
  - weekday/weekend ratio as the model;
  - department shares within ±10% of demand;
  - determinism by seed.

### 2b — all ten stores
- Per-store behavior parameters: generate neutral ones from Denver's file. Denver's tuned "truths" stay Denver's.
- Per-store hours and volume from `profile.json`, and each store's Phase 1 model (run `build_store_model.py --all`).
- Self-test for all ten.

### 2c — weekly planogram issue and counts
- Each period is a `planogram_directive` with a new `external_directive_id`, `effective_date` = the period's first day, and
  `expiry_date` = the next period's first day. The stop date is deployed (Backend/Web A3); prove it on one fixture before the
  first live issue.
- The period's plan may change lines; changes knob default: a few lines per department. The day's staff then reset those
  fixtures.
- A weekly count per store over a rotating subset of fixtures, through the existing `StockDrive` path, so the platform's own
  compliance engine judges the floor.

### 2d — planted scenarios and the answer key
- A scenario layer edits the plan or demand for named fixtures in named stores:
  - Denver plus one of Paris/Seoul, never all stores;
  - held for 28 days or more;
  - other fixtures varied ±20%.
- It writes `out/scenarios/answer-key.json` in the shape the eval asks for:
  `scenario_id, store_id, planted_from, planted_to, entities{zone_ids, product_ids}, expected_recommendation`.
- The seven archetypes in the plan's § 5 table. `entrance_promo` waits for a promotion model.

## 3. Rules
- Offline only; no network.
- Do not change the files the live box reads (Phase 1 brief § 0).
- Kotlin, ktlint (max line length 150), Gradle Kotlin DSL.
- Deterministic by seed.
- Conventional commits, no attribution lines.
- Each slice is a draft PR with its self-test green and `offlineSelfTest` green.
