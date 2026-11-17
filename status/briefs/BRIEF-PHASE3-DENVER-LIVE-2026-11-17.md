---
title: Phase 3 — Denver live on the new store model (the reset night, the live ops driver, the gap ledger)
date: 2026-11-17
author: twin lane (Opus, design)
parent: status/PLAN-STORE-REALISM-2026-11-17.md § 6; builds on Phase 2a/2b (package com.m8trx.twin.storeday)
status: DESIGN — nothing here runs without Bob's go; the reset night is scheduled with him and announced to the lanes
---

# Phase 3 — Denver live on the new store model

## 0. What changes on dev, in one table (why this needs Bob's go)

| Change | Size (Denver) | Surface | Risk / guard |
|---|---|---|---|
| **Reset night**: units put to the new planogram and back room | about 30,000 new units received (≈ 43k planned minus the unsold units already on the platform), plus about 6,400 movement batches | `items/receive` on the data-plane bearer, max 200 EPCs a request, limit 300/min; `inventory_movement` webhook | Bulk writes fire core's audit triggers. In Session 8 a bulk load exhausted the main server's DB pool. So it is **paced over hours** (e.g. 1 receive request / 10 s ⇒ ~25 min of receives, moves at ≤ 2/s), at night KST, never during a walk, announced to the Backend/Web and Android lanes first |
| **The first period planogram** for Denver | ~6,400 lines, `effective_date` = reset day, `expiry_date` = +7 days | `planogram_directive` webhook | Prove the stop date on one fixture first (B/W A3). The old 28-line directive stays; the stop date on the new plan does not end the old one, and this needs a decision (§ 4 Q1) |
| **The hourly pass stops sending Denver's sales and moves**; the live ops driver sends them | ~470 units/day, ~600 moves/day | same Connect paths as today | Two writers must never run together. Denver is removed from the hourly pass's store list in the same step that starts the new driver |
| **Denver's people come from the store-day shoppers** instead of `LiveDrive`'s own generator | same volume as today | twin edge `:4223` (unchanged) | `server_name` interlock unchanged; one process publishes |

## 1. The reset night (one-off, idempotent, resumable)
`storeDayReset` (new; dry-run by default; `M8TRX_RESET_LIVE=true` sends):
1. **Read the truth of today.**
   - **From the twin's ledgers** (`ops/StoreStock.load`): seed EPCs + received − sold − missing, located by placements.
   - **From the platform** (Connect reads `/spatial/identity` for zone ids): a spot count of 20 random fixtures to confirm the
     ledger agrees. If more than 2% disagree, stop.
2. **Map to the plan.**
   - Each unsold unit whose EAN has a plan cell goes there (a move).
   - A unit whose EAN is off plan or over plan goes to the back room (a move).
   - What the plan still lacks is **received** as new EPCs (receiving serial block) into the back room, then moved to its cells.
3. **Send.** Paced, health-checked (`GET …/actuator/health` must be `ok` before each batch; on 502 wait for ok; on 429 wait 60 s
   and honour `Retry-After`). Every batch has an idempotent external id, so a resumed run re-sends nothing that was accepted.
4. **Ledgers.** Write the post-reset `StoreState` snapshot. From this point it is the live state the store-day continues from.
5. **Push the period planogram** for the reset day (with its stop date).

## 2. The live ops driver (continuous, replaces the hourly pass for Denver)
A long-running service, `m8trx-twin-ops-live`, mirroring the live plane's operational shape: started by hand, HOLD-aware,
Slack on start/stop/failure, refusing to run alongside the hourly pass for the same store.
- At each store's local midnight it runs the store-day for the new date from the saved `StoreState` and writes the wire plan.
  The seed and the date are logged.
- Every few seconds it sends what is due, in plan order:
  - receipts and moves at their due time (they carry no event time);
  - sales at the shopper's exit, with `occurred_at`.
  - The per-unit ordering rule is asserted before sending.
- **Retry rules** (B/W A11):
  - Wait for health `ok` after a 502.
  - Wait 60 s after a 429.
  - Store-manager logins at least 7 s apart, with tokens cached for their hour.
  - A failure leaves the event due. Nothing is skipped silently.
- **People.** The same store-day's shopper position streams go to the twin edge for Denver. `LiveDrive` gains a source switch
  (its generator, or the store-day sidecar) so the plane keeps its caps, HOLD handling and eviction. Other stores: REST
  person-sessions later, on the nine-site key (plan § 8 A5).
- **Weekly:** every Monday before open it pushes the next period's planogram and runs the count steps (2c).

## 3. The gap ledger, first edition
After the first full live day, `storeDayGapCheck` (read-only) compares the truth ledger with what the platform shows, through
Connect reads where they exist (`/compliance/state`, tasks query, spatial identity), and lists what it could not read.
- Signals: sales per fixture, sales per item on a fixture, impressions, dwell, interactions, flows, on hand vs plan.
- Each is classified as **captured · captured wrong · not captured · no surface**.
- Output: `status/GAP-LEDGER.md` (dated editions). The first edition is sent to the core as soon as it exists (B/W § 11).

## 4. Open questions to settle before the reset night
1. **The old Denver directive** (28 targets, `api_push`, still active, no stop date). Two options:
   - Re-send it with the same `external_directive_id` and an `expiry_date` of the reset day; B/W A3 says a re-send keeps the
     stored stop, so a re-send *with* one may set it. This needs checking.
   - Leave it, since the new plan's later `effective_date` wins on the same (fixture, product), but its other lines stay
     required.
   Ask B/W which is right.
2. **The other nine stores' old directives**, the same question when they go live.
3. **Denver's entrance zone** was retyped `entry_exit` → `region` on 2026-11-11 (B/W A12). If visit counting depends on it,
   Denver's people numbers are already off. Bob to confirm whether to put it back.
4. **When**: a night with no walk, no planned deploy, announced to the lanes.
