---
title: Plan — a twin store the M8 assistant can be graded on
date: 2026-11-17
author: m8trx-twin lane, Session 20 (gmail seat)
answers: m8trx-shared/twin/requirements/CORE-REQ-009-data-fit-to-evaluate-m8-on.md (asks A–D)
status: PLAN — for Bob's review; nothing built, nothing on the box / .29 / dev changed
reads-with: status/STATUS.md § WHAT ACTUALLY RUNS (2026-11-17) — the step-0 survey this plan stands on
---

# Plan — a twin store the M8 assistant can be graded on

## 0. For the reader on the other seat

This is the twin lane's answer to CORE-REQ-009, written after a read-only survey of what actually runs (STATUS § WHAT ACTUALLY
RUNS) and a long conversation with Bob on 2026-11-17. Bob relays it; questions back to the twin go into this file's § 9 or a
reply brief in `m8trx-shared/twin/requirements/`. **§ 8 lists what the twin needs from the Backend/Web lane — please answer those
before the twin builds the parts that depend on them.**

CORE-REQ-009 asked for four things (people in every store, a fixture on every sale, sales that do not decay, planted scenarios).
Working through them with Bob showed they are symptoms of one problem: **the twin's store is not a store.** The floor is a
parametric grid, the planogram is derived from wherever stock happened to land, stock is a one-shot pool that drains, people and
sales are drawn independently, and only Denver has people at all. Patching A–D one by one would leave every advice answer
ungradable for the same reason. So the plan rebuilds the store model in the order a real store has it —
**floor plan → planogram → stock to plan → shoppers and staff acting on that floor → weekly plan issue and compliance** — with
one simulation per store-day producing people, sales, stock and compliance together.

## 1. What Bob ruled or described today (2026-11-17) — design inputs, not proposals

1. **Purpose.** The M8 assistant must answer real questions about store activity, so the store is modelled as accurately as
   possible in layout and planogram. Planograms are the new core feature since the twin lane last worked.
2. **People on all stores** is the plan. People and sales must line up in time, per store.
3. **Store hours and day shape.** Sales and traffic follow each store's hours, peak at the usual times, and keep the
   weekday/weekend shape the model already has.
4. **Volume by store, not only by size.** "New York's going to be busier than … Seoul or whatever."
5. **Stock is EPC-level.** New EPCs are issued for new stock; nothing is recycled. Seasonal assortment flow is **later**.
6. **Deliveries come in before opening** — about an hour before at big stores, half an hour at smaller ones (Bob's observation of
   Korean stores). Staff stock the racks in that window. Cross-store transfers are occasional and item-specific (later).
7. **Back-room → floor restock happens through the day, in the quiet hours**, to keep the fixture matching its planogram.
8. **Denver's floor is unrealistic**: many fixtures with no stock, fixtures all alike, stock "a few items, really uneven".
9. **Planograms are issued weekly, for compliance**, and compliance is measured against them — simulate that live.
10. **Backdating is wanted, even by a core migration — but only after the data is tested live and shown working.**
11. **`sale_line.zone_id`:** do not assume the twin's EPC-only sale is right. The fixture on a sale may be tied to the planogram
    and spatial model deliberately — **ask the Backend/Web lane first** (§ 8 Q1).
12. **Stale PRs get settled before anything is built on top** (§ 3).
13. **Fixture-level capture is critical:** which fixtures sell most, which items on them sell most, and impressions, dwell and
    interactions at each fixture. Whatever the platform does not capture is **marked** — that is the purpose of the twin (§ 5.1).

## 2. Design principles

- **One truth per store-day.** A single seeded simulation per store per day decides who enters, where they walk and dwell, what
  they pick up from which fixture, what they buy, what staff restock and when. Every wire stream (people events, sales, moves,
  receipts, counts, directives) is a projection of that one truth. This is what makes "a week with more visitors has more
  sales" and "the fixture a sale came from is where the shopper dwelt" true by construction instead of by tuning.
- **The planogram is an input, not a by-product.** Today `build_planogram.py` counts where stock landed and calls it the plan
  (compliant by construction). Reverse it: design the plan, seed stock to it, let reality drift from it, measure.
- **Public surfaces only** (the twin's hard rule). Where the surface is missing, file a TWIN-REQ — never shim.
- **Test live, then backfill.** Every new piece runs live on a small scope first, measured; history comes after (Bob, § 1.10).
- **The box changes only on Bob's word.** Build and prove offline and on the Mac first; deploy is a separate, named step.
- **Deterministic by seed**, so a scenario and its answer key reproduce.

## 3. Phase 0 — settle the base (before any new code)

The box runs PR #19's branch (`80f810b`), which is not `main`. New work must start from a `main` that equals what runs, with the
dependency bumps (#26) in it. Proposed landing, each step on Bob's word.

### 3.1 Trial merge (2026-11-17, local scratch worktree, nothing pushed)

`origin/main` merged into `80f810b`:
- **4 text conflicts** (`ChainActivityStream.kt`, `ConnectHarness.kt`, `PricingGuardSelfTest.kt`, `WebhookPayloads.kt`). All are the
  duplicated pricing work. `main`'s `56e87bb` and `a8c3b52` are the same two features as the stack's `fea8570` and `3bcd7ab`
  (`git range-diff` shows the same code). The stack adds price memory, local-currency sales and the EUR base on top.
  **Resolution: take the stack side in all four.** `main` contributes no line the stack lacks.
- **Build and ktlint green** on gradle 9.8.2 / kotlin 2.4.21. `liveSelfTest` and `demoStepsSelfTest` pass.
- **`offlineSelfTest` red: `historySelfTest` 153/1.** The failing check is "the keys export agrees with every store's layout.json".
  `main`'s #20 ("Fixture, not Rack", `533419b`) renamed zones in the generated layouts, but the stack's
  `reference/data/chain/keys-2026-10-11.csv` still has the old names (17 "Rack" rows). This is a semantic conflict, not a text
  one. **Fix:** re-export the keys through the public read (`/spatial/identity`). Whether mother's zone names took the rename
  decides which side is right, so check it; don't hand-edit the CSV.
- **#19 deletes nothing** (no deleted or renamed files against `main`).
- **#21:** its six non-bump commits cherry-pick onto `main` cleanly. Against the landed stack it has two union-style conflicts
  (`build.gradle.kts` task registrations, `Journeys.kt`). Drop its bump commit `6a0e7ae`, which repeats #26.

### 3.2 Landing recipe

1. **One consolidated PR, "land what the box runs":** branch from `80f810b`, merge `main` (resolution above), fix the keys export,
   `offlineSelfTest` green. Use a merge commit, not a squash, so the per-bump and pricing history survives.
   **Close #17 and #18 as superseded** by it (their commits are inside it). Landing them one by one gains nothing: the
   recent-window branch has no PR, and the pricing PRs were merged into #19's branch, so the final tree is the same.
2. **#21 after that:** rebase onto the new `main` without `6a0e7ae`, union-resolve the two files. It is the base for Phase 2
   (§ 5), so it lands before Phase 2 starts.
3. **Cleanup candidates** (inferred; Bob ticks):
   - `stockDepthReport`, a one-off for the Oct 5–16 top-up.
   - `FixtureRevert` (`6439647`), a one-shot revert that has already run.
   - Five stale local worktrees `m8trx-twin-wt-*`.
   - Leave `historyGenerate` in: Phase 4 reuses it.
4. **Risk:** landing moves the box's toolchain (gradle 9.7.1 → 9.8.2, kotlin 2.4.10 → 2.4.21, jnats 2.26.2 → 2.26.4). The bumps
   are verified offline only, so the box rebuild is its own step, watched through one hourly pass.

Also in Phase 0:
- **Commit the box-only ops scripts** (`twin-hourly.sh`, `twin-day.sh`, `twin-notify`, `hold`, `resume`, the unit files) under
  `ops/` in this repo, so the repo describes what runs. (Bob's open question — this repo or the shared vault — decides where;
  default here, since `ops/` already holds the live-plane files.)
- **Rebuild the box from merged `main`** (pull, `live start` rebuilds `live-dist`) — Bob's go; the live-plane code is unchanged, so
  the rebuild only brings the bumps and pricing work.
- **Restart Denver's live plane with `shift=open`** — removes the 11h16m plan-clock lag so Denver's people peaks land on Denver's
  real hours, in step with its sales (Bob's go; a one-line change on the box).
- **Fix the digest's stale "people plane" check** (it reads the dead nightly's log).

## 4. Phase 1 — the store model (offline; files and generators, nothing sent)

### 4.1 Store profile (per store)
Extend `store-operating-model.json` from one chain-wide block to a chain default plus per-store overrides:

| Field | Today | Proposed |
|---|---|---|
| opening hours | 10:00–21:00 everywhere | per store / per weekday (country and city practice; Bob to confirm or supply real hours) |
| daily visitors | 850 / 1,450 × area ÷ Denver's area × hand story | × a **location factor** (urban-core flagship ≫ regional): NYC, Paris, Seoul high; Busan, Bordeaux, Marseille lower. Bob to confirm targets (e.g. NYC ≈ 1.5× Denver) |
| delivery window | — | lead before open: **60 min ≥ ~500 m², 30 min below** (Bob's cut-off to confirm) |
| conversion, basket | chain-wide + story | unchanged for now |

`StoryCalendar` (seasonality, holidays, promo weeks, the slow "climb" department, the Lille turnaround) is kept.

### 4.2 Floor plan (per store)
Replace the parametric gondola grid (`build_layout.py:186-403`; reuse its geometry helpers and asserts) with a plan built
department by department from realistic Decathlon fixture kinds: footwear walls, apparel rails and round racks, gondola bays
with levels, an entrance/promo table zone, a bike and large-item area, checkout impulse, back room by receiving dock. Fixtures
carry **bays, levels and a capacity per level**, matching core's fixture model (`zone_template` with a `form` per level — rail,
shelf, pegboard, table_top, bin; faces; mig 292/303). Stores differ in shape, not only in size.

⚠ **Platform constraint.** Connect cannot create, retype or resize zones (TWIN-REQ-006, filed 2026-08-05, still open). The 30
spaces / 929 zones on mother were loaded by core. Two paths — **Bob to choose** (§ 9 D2):
- **(a) Keep today's zones on mother, make them realistic inside.** Same fixture rectangles, but the twin's planogram gives each a
  real role, levels and capacities (planogram lines carry `display_level`, `position_sequence`, `facing_count`). Fast; no core
  work; the floor's *shape* stays the grid.
- **(b) New floor plans, loaded by core.** The twin emits layouts as files (precedent: the S19 solver matrix), core loads them
  (TWIN-REQ-006 or a one-off load). Realistic shape; waits on core.
Recommended: **(a) now to get data flowing, (b) filed in parallel**, so the twin is not blocked and the floor improves when core
lands it.

### 4.3 Planogram as input
A generator that, per store, assigns products to fixture levels with facings and a required quantity per (fixture, product):
departments placed by adjacency, complete size runs on one fixture, high-velocity lines at eye level, a feature table at the
entrance, deliberate small deviations available as a knob. Kept in the existing `m8trx_standard` document format and sent with
the existing `PlanogramDirective` DTOs / driver (drop the 28-line `curate()` demo slice — send the full plan).
Known bug to fix on the way: `build_planogram.py` keys on `item_cd`, merging sizes (STATUS item 7 on the #19 branch).

### 4.4 Stock seeded to the planogram
Floor units = the plan's required quantity per (fixture, product) ± a small deliberate deviation; back room = N days of cover
per product; **no empty floor fixture except on purpose**. Reuse the SGTIN-96 encoder and the receiving serial block
(1,000,000,000+, never colliding with seed serials 1,000–9,999,999). Every unit keeps one EPC for life; sold EPCs never return.

## 5. Phase 2 — one simulation per store-day (offline first)

Base it on the behavior package from PR #21 (`ShopperAgent`, `Presentation`, `ShelfPlan`, `FixtureLedger` — the only code that
already links dwell at a fixture to a sale from that fixture) merged with the history runner (`HistoryDayRunner` / `RecentDay`,
which already produces a store-day's volume, calendar and story). Generalise it from Denver to all ten stores.

A store-day, in order:
1. **Pre-open (T−60 / T−30 min):** the day's delivery is received into the back room (new EPCs, sized from what sold since the last
   delivery, rounded to cases); staff move units onto fixtures that are below plan, spread across the window.
2. **Open hours:** shoppers arrive on the store's hourly curve (weekday or weekend) at the store's volume; each walks the floor,
   sees fixtures (impressions), dwells, picks up **a unit actually on that fixture**, may try on, buys or puts back. A sale is
   the specific EPC taken from that fixture — so the platform's custody location at the sale is the fixture the shopper stood at.
   Selling from the back room becomes the rare case it is in a real store.
3. **Quiet hours:** staff top up fixtures that fell below plan from the back room, placed in the troughs of the hourly curve.
4. **Close:** the day's ledger (per fixture: impressions, dwell, pickups, units sold, on hand vs plan) is the truth the checks
   and the answer key read.
5. **Weekly:** a new planogram is issued (a new directive with the next effective date, before Monday's open) and a count runs,
   so compliance is measured by the platform's own engine against the plan in force.

Sales decay (ask C) disappears by construction: demand follows the model, and replenishment keeps stock in a band.

### 5.1 What must be captured — the fixture truth and the gap ledger (Bob, 2026-11-17: "critical")

The store questions that matter are fixture questions: **which fixtures sell the most, which items on those fixtures sell the
most, and what attracts the flow — impressions and dwell, then interactions with the items.** That is the whole purpose of the
twin: it knows the truth, so it can show what the platform captures and **mark what it does not**.

Every store-day, the simulation writes its own **truth ledger** (a file, never sent to the product) at this grain:

| Signal | Twin truth grain | Where the platform should hold it | Known state today |
|---|---|---|---|
| Units and value sold per fixture | fixture × hour | `sale_line.zone_id` → `commerce_projection` zone rows | 6.8% of lines on the right fixture (§ 8 Q1) |
| Units sold per item **on** a fixture | fixture × product × day | `sale_line` (`zone_id`, `product_id`) | same dependency |
| Impressions per fixture | fixture × hour | `impression_event` → `behavioral_projection` (impression) | Denver only; day-part windows, no hour grain |
| Dwell per fixture | fixture × hour, per shopper | `person_session` / `behavioral_projection` (dwell) | Denver only |
| Interactions: pickup, put-back, try-on | fixture × product × hour | engagement metric; `try_on_zone_session`; mirror sessions | engagement never written by the rollup; try-on API stamps now(); mirror table empty — **candidate gaps** |
| Flow between zones | zone → zone per hour | `path_analysis` | empty — **candidate gap** |
| On hand vs plan per fixture | fixture × product, at open and close | `inventory_zone_product_count`, `compliance_target_state` | quantity-only compliance |

**The gap ledger.** After each live day, a check compares the truth ledger with what the platform holds, through the same read
surfaces a customer would use (Connect reads; the eval's read-only grid where the twin has no read surface). Each signal is
classified **captured · captured wrong · not captured · no surface to send it**. Every *not captured* or *no surface* row is
**marked**: it goes into a gap ledger in this repo and, when confirmed, into a TWIN-REQ brief through the front door. It is never
worked around in the twin. The ledger's first edition comes from the first Denver live day of Phase 3.

**Planted scenarios (ask D)** are a layer on top: a scenario edits the plan or the parameters for named fixtures in named stores
(Denver plus one of Paris/Seoul, never all), holds ≥ 28 days, and writes the answer key file the eval reads
(`twin_scenario_key`-shaped JSON: scenario id, store, from/to, entity ids, expected recommendation). The eight eval archetypes
map onto planogram and parameter edits:

| Eval archetype | What the scenario plants |
|---|---|
| promote | a fast seller in a quiet zone vs one in a busy zone; a low-cover product |
| layout | a strong department in the least-visited region, slow sellers in the busiest |
| move_fast_seller | a 3× velocity product on a high-dwell low-visit fixture |
| under_seen | the highest on-hand value fixture in the bottom impressions decile |
| restock_first | fast/low-cover vs fast/healthy vs slow/nearly-out |
| quiet_stock | five products, high on hand, zero sales for 28 days |
| look_not_buy | two high-attention zero-sale fixtures (one price, one stock-out) + a control |
| entrance_promo | needs a promotion model — **later** (no product table holds promotions) |

## 6. Phase 3 — live, small first, then widen (each step on Bob's go)

1. **Denver live on the new model**, replacing the hourly pass's stream/move/stock steps with the store-day simulation's wire
   output (sales with `occurred_at`; moves and receipts sent at their real local time, since those payloads carry no event time;
   people through the existing twin edge).
2. **Measure** with the eval's own grid (`scripts/m8-eval/grid.py` on `feat/m8-eval-loop`, read-only SQL) and the CORE-REQ-009
   done-when checks, plus the twin's own oracle (every planned event = one on the wire = one row), and **publish the first
   gap ledger** (§ 5.1): per fixture signal, captured or marked.
3. **Sales-only stores next** (all nine have sales through Connect today): new model, replenishment, planogram issue.
4. **People for the other nine stores** — blocked on the receiving side (§ 8 Q5). The twin's code already plans all ten stores;
   it needs a path per store.
5. **Load**: Denver alone peaks ≈ 4,400 events/min; ten stores is several times that. The live plane's caps (500 ev/s, 40
   arrivals/min) are checked per store vs total before widening; the main-server's ingest ceiling is a core question (§ 8 Q7).
   Precedent: a Saturday-scale Denver replay starved the main-server for two hours on 2026-10-12.

## 7. Phase 4 — backfill, only after Phase 3 is measured green

What can be backdated (recorded in core's docs, **not yet measured end to end**):

| Data | Path | Status |
|---|---|---|
| Sales | Connect `sale_event` `occurred_at` | accepted up to 400 days back (`MAX_BACKDATE`, B-9); never sent-and-read-back |
| People sessions | edge envelope `ts` (core PR #299) or cloud REST `person-sessions` (needs a granted `vision_ai:ingest` key) | built; deployment on the twin edge unverified |
| Receipts, moves, directives, counts | — | stamped at send time; not backdatable through Connect |
| Engagement, queue, staff series | — | the rollup never produces them; only a load or a new producer |

Steps: (1) the re-measure TWIN-REQ-007 § 10 already asks for — one backdated sale and one backdated people session, read back the
stored times; (2) generate the history from the **same** store-day simulation, offline, as files (precedent: `historyGenerate`,
PR #17 → mig 259); (3) a TWIN-REQ asking core for the load — a migration if that is the path, on Bob's word. Constraints to design
around: Timescale compression after 90 days, `delete_guard` on custody and compliance events, mig 259's `materialized` tag
(the rollup skips loaded days).

## 8. Questions for the Backend/Web lane (answer before the twin builds the dependent part)

1. **`sale_line.zone_id`** — what is it for (planogram compliance, fixture conversion, spatial), who stamps it (resolved from
   `thing_location` at ingest per B-9, or a fixture the sale event can carry), and should the twin send the fixture on the sale,
   in which field? Bob asked that nothing be assumed here. *(Blocks: ask B's design.)*
2. **Is the timestamp fix (services PR #299: custody `occurredAt`, edge envelope `ts`) deployed — on the main server and on the
   twin edge image (`m8trx-edge-server:dev` built 2026-10-18)?** *(Blocks: Phase 4.)*
3. **Weekly planogram issue through Connect** — for an `api_push` directive (`planogram_directive` webhook), does a newer
   `effective_date` supersede the previous directive on the same fixtures, or must the twin cancel or expire it? Is there a
   documented payload (the Connect doc lists the data type but no payload)? *(Blocks: § 5 step 5.)*
4. **Receiving** — `POST /inventory/items/receive` needs `inventory:create`; the twin's data-plane key is read-only. What is the
   sanctioned grant route (TWIN-REQ-005)? *(Blocks: replenishment — the core of ask C.)*
5. **People for nine more stores** — one twin edge per store, one edge serving several sites, or the cloud REST route with
   site-scoped `vision_ai:ingest` keys? The twin does not touch `.29`. *(Blocks: ask A beyond Denver.)*
6. **Floor plans** — will core load new twin layouts (TWIN-REQ-006, or a one-off load like the original 929 zones)? Which fixture
   templates (`zone_template`) exist or should exist for Decathlon? *(Decides § 4.2 path (b).)*
7. **Ingest capacity** — what people-event rate can the main server take, so ten stores can be paced safely?
8. **Engagement, queue length, staff movement** — the rollup never writes them. A core producer, or loaded series? *(Ask A's
   "also asked".)*

## 9. Decisions for Bob

- **D1** Phase 0 landing (§ 3) — approve the recipe, then each merge and the box rebuild.
- **D2** Floor plan path — (a) realistic inside today's zones now, (b) core-loaded new layouts later; recommended both, (a) first.
- **D3** Store profile numbers — location factors (NYC vs Denver vs Busan…), real hours per city, the 60/30-minute cut-off.
- **D4** Planogram issue day/time (e.g. Monday before open) and how much deliberate non-compliance the floor should carry.
- **D5** Order of stores after Denver.
- **D6** Where the twin's records live — this repo (current) or the shared vault; this plan stays here until you say otherwise.

## 10. Sizing (rough)

Phase 0: a day (conflict resolution + tests) plus Bob's reviews. Phase 1: 3–5 days. Phase 2: about a week (the behavior package
does the hardest part). Phase 3: Denver live in days after Phase 2, widening paced by measurements and § 8 answers. Phase 4:
after Phase 3 is green; mostly generation (reuse) plus core's load.
