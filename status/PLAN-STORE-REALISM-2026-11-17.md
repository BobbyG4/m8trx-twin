---
title: Plan — a twin store the M8 assistant can be graded on
date: 2026-11-17
author: m8trx-twin lane, Session 20 (gmail seat)
answers: m8trx-shared/twin/requirements/CORE-REQ-009-data-fit-to-evaluate-m8-on.md (asks A–D)
status: PLAN — for Bob's review; nothing built, nothing on the box / .29 / dev changed
reads-with: status/STATUS.md § WHAT ACTUALLY RUNS (2026-11-17) — the step-0 survey this plan stands on
reviewed: 2026-11-17 by the Backend/Web lane (m8trx-shared session S393, intellithinx seat), at Bob's request — § 8 answered, § 11 added, inline edits marked [B/W]
---

# Plan — a twin store the M8 assistant can be graded on

## 0. For the reader on the other seat

This is the twin lane's answer to CORE-REQ-009, written after a read-only survey of what actually runs (STATUS § WHAT ACTUALLY
RUNS) and a long conversation with Bob on 2026-11-17. Bob relays it; questions back to the twin go into this file's § 9 or a
reply brief in `m8trx-shared/twin/requirements/`. **§ 8 lists what the twin needs from the Backend/Web lane — please answer those
before the twin builds the parts that depend on them.**

> **[B/W] 2026-11-17 — reviewed and marked up by the Backend/Web lane, at Bob's word ("make so directly and push").** All eight
> questions in § 8 are answered in place, from the server code as deployed on dev (services master `fee5b9b5`) and read-only
> queries. **One of them changes this plan: ask B's "6.8%" was a measuring error on the core's side** (§ 8 A1). The review notes
> and what the core still owes are in § 11. Every edit of mine outside § 8 and § 11 carries a `[B/W]` mark; nothing of yours was
> deleted.

CORE-REQ-009 asked for four things (people in every store, a fixture on every sale, sales that do not decay, planted scenarios).
Working through them with Bob showed they are symptoms of one problem: **the twin's store is not a store.** The floor is a
parametric grid, the planogram is derived from wherever stock happened to land, stock is a one-shot pool that drains, people and
sales are drawn independently, and only Denver has people at all. Patching A–D one by one would leave every advice answer
ungradable for the same reason. So the plan rebuilds the store model in the order a real store has it —
**floor plan → planogram → stock to plan → shoppers and staff acting on that floor → weekly plan issue and compliance** — with
one simulation per store-day producing people, sales, stock and compliance together.

## 1. What Bob ruled or described today (2026-11-17) — design inputs, not proposals

1. **Purpose.** The M8 assistant must answer real questions about store activity, so the store is modeled as accurately as
   possible in layout and planogram. Planograms are the new core feature since the twin lane last worked.
2. **People on all stores** is the plan. People and sales must line up in time, per store.
3. **Store hours and day shape.** Sales and traffic follow each store's hours, peak at the usual times, and keep the
   weekday/weekend shape the model already has.
4. **Volume by store, not only by size.** "New York's going to be busier than … Seoul or whatever."
5. **Stock is EPC-level.** New EPCs are issued for new stock; nothing is recycled. Seasonal assortment flow is **later**.
6. **Deliveries come in before opening** — about an hour before at big stores, half an hour at smaller ones (Bob's observation of
   Korean stores). Staff stock the fixtures in that window. Cross-store transfers are occasional and item-specific (later).
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

### 3.2 Landing recipe — ✅ DONE 2026-11-17

> **Landed:** #27 (`51c27bb`) = the box stack merged with `main`, keys export fixed and verified against `/spatial/identity` in all ten stores (only drift: Denver "Entrance" vs "Entrance Area", § 8 Q12); #17 auto-merged, #18/#19 closed. #28 (`092a2b1`) = #21's six commits on current `main`, bump dropped; #21 closed. All branches kept. **Box rebuilt from `main` 2026-11-17 13:47 KST (Bob's go)**: checkout switched to `main`, live plane restarted on a fresh `liveDist` with `--shift 0`, ops scripts committed under `ops/box/`. **Phase 0 complete 2026-11-17:** the digest's people-plane check now reads the live plane; its source of record moved to `ops/box/` (the copy in `m8trx-shared/scripts/twin-ops/` is superseded — see § 8 Q13).

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
- **Restart Denver's live plane with `--shift 0`** (✅ done 2026-11-17). ⚠ Corrected: `shift=open` would NOT have removed the lag — it plays 10:00 store time at the moment of start (`LiveDrive.planAnchor`). `--shift 0` sets plan time = now, so every store runs on its real local clock and its people line up with its sales.
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
department by department from realistic Decathlon fixture kinds: footwear walls, apparel rails and rounders, gondola bays
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
which already produces a store-day's volume, calendar and story). Generalize it from Denver to all ten stores.

A store-day, in order:
1. **Pre-open (T−60 / T−30 min):** the day's delivery is received into the back room (new EPCs, sized from what sold since the last
   delivery, rounded to cases); staff move units onto fixtures that are below plan, spread across the window.
2. **Open hours:** shoppers arrive on the store's hourly curve (weekday or weekend) at the store's volume; each walks the floor,
   sees fixtures (impressions), dwells, picks up **a unit actually on that fixture**, may try on, buys or puts back. A sale is
   the specific EPC taken from that fixture — so the platform's custody location at the sale is the fixture the shopper stood at.
   Selling from the back room becomes the rare case it is in a real store.
   **[B/W] The wire order that makes this true (§ 8 A1):** the server stamps the sale's fixture from the item's current
   `thing_location` at the moment the sale is ingested; the sender cannot name it. So for every unit: the Connect
   `inventory_movement` that puts it on the floor fixture must be **ingested before** its sale, and no later movement of that unit
   may be sent before the sale is. A put-back to another fixture is a movement too. Send per store in event order; do not batch
   a day's sales after the day's moves.
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
| Units and value sold per fixture | fixture × hour | `sale_line.zone_id` → `commerce_projection` zone rows | **[B/W] corrected:** live lines are consistent with custody in 100% of cases; the "6.8%" was the share of lines that are live at all (§ 8 A1). What is still wrong is *which* fixture the twin's stream leaves the item on before it sells |
| Units sold per item **on** a fixture | fixture × product × day | `sale_line` (`zone_id`, `product_id`) | same dependency |
| Impressions per fixture | fixture × hour | `impression_event` → `behavioral_projection` (impression) | Denver only; day-part windows, no hour grain |
| Dwell per fixture | fixture × hour, per shopper | `person_session` / `behavioral_projection` (dwell) | Denver only |
| Interactions: pickup, put-back, try-on | fixture × product × hour | engagement metric; `try_on_zone_session`; mirror sessions | engagement never written by the rollup; try-on API stamps now(); mirror table empty — **candidate gaps** **[B/W] confirmed for engagement: no live producer exists in the server (§ 8 A8)** |
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

### 5.2 Follow-up slice 2e — dwell that differs by fixture (found while building 2d, 2026-11-17)

> **Built, offline (2026-11-17, branch `feat/store-day-2e-dwell`).** Notes and numbers: `reference/data/chain/stores/dec-us-denver/model/PHASE2E-NOTES.md`. Median session fitted to the operating model's 18 min per store; `move_fast_seller` re-planted with a dwell-per-visit check.

Dwell per visit comes out nearly constant across fixtures in the store-day: the behavior model's engaged span hardly depends on
what the fixture is or holds. So "where do people linger" today only reflects how many visits a fixture gets. Bob called dwell
"the most important thing with attracting the flow", so this gets its own slice before the gap ledger leans on it:
- **Dwell by fixture kind and content:**
  - footwear walls and the bench long (try-on);
  - apparel rails and rounders medium-long;
  - pegboard accessories short;
  - promo tables short but frequent;
  - GPS cases long when engaged.
- **Dwell rises with attraction and the depth of the range on the fixture.**
- **Interactions** (pickup and put-back) scale with dwell.
- **Calibration** against the operating model's dwell figures (`store-operating-model.json`), plus a self-test that dwell
  distributions differ by kind.
- **Then re-plant** the "high-dwell, low-visit" half of `move_fast_seller`, which 2d had to plant as visits only.

## 6. Phase 3 — live, small first, then widen (each step on Bob's go)

1. **Denver live on the new model**, replacing the hourly pass's stream/move/stock steps with the store-day simulation's wire
   output (sales with `occurred_at`; moves and receipts sent at their real local time, since those payloads carry no event time;
   people through the existing twin edge).
2. **Measure** with the eval's own grid (`scripts/m8-eval/grid.py` on `feat/m8-eval-loop`, read-only SQL) and the CORE-REQ-009
   done-when checks, plus the twin's own oracle (every planned event = one on the wire = one row), and **publish the first
   gap ledger** (§ 5.1): per fixture signal, captured or marked.
3. **Sales-only stores next** (all nine have sales through Connect today): new model, replenishment, planogram issue.
   - **Per-store POS keys** (§ 8 Q9, Bob 2026-11-17): each store's sales on its own site-bound key, with a check that a cross-store
     post is refused.
   - **Retry instead of skip:** a refused login or a 502 is retried for a minute or two before the store is skipped (§ 8 Q11).
4. **People for the other nine stores** — blocked on the receiving side (§ 8 Q5). The twin's code already plans all ten stores;
   it needs a path per store.
5. **Load**: Denver alone peaks ≈ 4,400 events/min; ten stores is several times that. The live plane's caps (500 ev/s, 40
   arrivals/min) are checked per store vs total before widening; the main-server's ingest ceiling is a core question (§ 8 Q7).
   Precedent: a Saturday-scale Denver replay starved the main-server for two hours on 2026-10-12.
   **[B/W] The numbers are in § 8 A7. And one rule from this side:** dev's main server and mother are the same ones Bob's office
   stock-count walks run on (daily until his move at the end of November, and the Korea lab after). Any widening step, load test
   or replay is scheduled with him and announced to the Backend/Web and Android lanes first; none runs while a walk is on. The
   GPU box also hosts the ARLS serve the phones use during a walk, so generator load on the box counts too.

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

**[B/W] Three facts that shape the backfill (from the code, § 8 A1, A2, A4):**
- **A backdated sale takes its fixture from where the item is *when the request is processed*, not from where it was at
  `occurred_at`.** `occurred_at` sets the line's time and the price lookup only. So a history sent through Connect lands on the
  right fixtures only if it is replayed in event order (movement, then sale, per unit), and then every movement and receipt
  carries today's time. For fixture-level history with true times, the path is a core load (your step 3), not Connect.
- **Rows already there are not fixture truth.** 81,792 of the Decathlon sale lines dated 2026-10-06 to 2026-11-06 are the core's own
  backfill (mig 311, `price_source = 'backfill_list'`): their fixture is where the item stood on the day of the backfill, and their
  custody `from_zone_id` is NULL. Any new backfill should replace or supersede these, and the eval will exclude them from fixture
  questions until then (core's item, § 11).
- **A receipt has no event time** (stamped at ingest), as your table says; confirmed.

## 8. Questions for the Backend/Web lane (answer before the twin builds the dependent part)

1. **`sale_line.zone_id`** — what is it for (planogram compliance, fixture conversion, spatial), who stamps it (resolved from
   `thing_location` at ingest per B-9, or a fixture the sale event can carry), and should the twin send the fixture on the sale,
   in which field? Bob asked that nothing be assumed here. *(Blocks: ask B's design.)*

   > **[B/W] A1.** From the code (`IntegrationIngesters.ingestSaleEvent`, `SaleLineWriter`, `InventoryItemService`) and one query:
   > - **Who stamps it:** the server, always. A sale line has no field for a fixture; the sender cannot name one. The server reads
   >   the item's row in `thing_location` (one current row per item) at the moment it ingests the sale and writes that zone to both
   >   `sale_line.zone_id` and the sale's custody event `from_zone_id`. It is "where the item stands now", not where it stood at
   >   `occurred_at`. No `thing_location` row gives a NULL zone; a back-room zone is stamped like any other.
   > - **What moves `thing_location`:** a Connect `inventory_movement` (it updates the location, its history and writes a `moved`
   >   custody row, with no physical read needed), a count / stocktake, and a task completion that books items. A sale, a receive
   >   and a plain status change do not move it.
   > - **What reads the sale's fixture:** the `commerce_projection` rollup (line's zone, else the custody zone, else
   >   `thing_location`) and the fixture funnel (`analytics/FixtureFunnel`). Planogram and compliance do not read it (none found).
   > - **The 6.8% in CORE-REQ-009 was the core's measuring error, corrected here.** Since 2026-10-06 the Decathlon tenant has 87,728
   >   sale lines: 5,936 live (`price_source` till or history) and 81,792 that are the core's own backfill of sales from before the
   >   `sale_line` table existed (mig 311). On the live lines the line's zone equals the custody `from_zone_id` in **100%**; on the
   >   backfill lines the custody zone is NULL, so they can never match. 6.8% is just the live share. The "17% on Backroom" figure
   >   was not re-measured.
   > - **So what the twin should do for ask B:** send nothing new on the sale. Make the item's last movement before its sale be the
   >   floor fixture it is taken from, and get the order right on the wire (the note in § 5 step 2). Your store-day simulation does
   >   this by construction. The done-when in CORE-REQ-009 B stands (≥ 95% of lines on a floor fixture that matches the trail,
   >   under 2% back room), measured on live lines only.
   > - **A real gap on the core's side, recorded, not scheduled:** resolving the fixture at ingest time instead of at `occurred_at`
   >   is also wrong for a real till integration that posts its sales late (a nightly batch after the evening restock). Whether a
   >   sale may carry its fixture, or the server should resolve it as of `occurred_at` from `thing_location_history`, is Bob's call;
   >   if you want it, file it as a TWIN-REQ with the case from your truth ledger.
2. **Is the timestamp fix (services PR #299: custody `occurredAt`, edge envelope `ts`) deployed — on the main server and on the
   twin edge image (`m8trx-edge-server:dev` built 2026-10-18)?** *(Blocks: Phase 4.)*

   > **[B/W] A2.** #299 is merged (`1a01f08a`, 2026-10-11 UTC) and is in services master `fee5b9b5`, which is what dev's main server
   > runs: **deployed on the main server, yes.** The twin edge container on `.29` runs an image created 2026-10-18 04:47 UTC, a week
   > after the merge, so it very probably has it; the image carries no revision label, so that is **not proven**. Your step (1) in
   > § 7 (send one backdated sale and one backdated people session, read the stored times back) is the right proof. Note the twin
   > edge is on an older image than today's `:dev` (which the office edge runs, built 2026-11-17).
3. **Weekly planogram issue through Connect** — for an `api_push` directive (`planogram_directive` webhook), does a newer
   `effective_date` supersede the previous directive on the same fixtures, or must the twin cancel or expire it? Is there a
   documented payload (the Connect doc lists the data type but no payload)? *(Blocks: § 5 step 5.)*

   > **[B/W] A3, CORRECTED the same day on Bob's word. Read this block first; it replaces the two workarounds below.**
   > **DEPLOYED 2026-11-17 13:46 KST (services master `3cd7eaed`, verified on dev): a pushed `planogram_directive` now carries
   > its stop date.** Top-level `expiry_date`, read exactly like `effective_date` (a bare day is the store's own midnight; a full
   > timestamp is taken as sent). It must be after the start, or the push is refused with the field named. A re-send with the same
   > `external_directive_id` and no `expiry_date` keeps the stored stop; one that moves the start past the stored stop is refused
   > until it sends a new stop. A plan pushed with a stop already in the past is never switched on. So the weekly issue is: a new
   > `external_directive_id` per period, `effective_date` = the period's first day, `expiry_date` = the next period's first day.
   > Proven by test on the server (two weekly plans on one fixture: inside week 2 only week 2's lines are required); **not yet
   > exercised through Connect on dev: yours to prove with one fixture before the first live issue.** The payload is documented in
   > the Connect doc (`m8trx-shared/reference/connect/M8TRX-CONNECT-API.md` § 8).
   > Bob, 2026-11-17: a planogram is for a period. "We have a start and stop date, which we have in the planogram now… a planogram
   > is for this period, and then you create a new one… We could just schedule it for weekly for now… that's really how that should
   > work." Checked in the code: he is right about the engine. A plan has a start (`effective_date`) and a stop (`expiry_date`), and
   > the count's engine treats a plan as in force only until the earliest of its stop date, its end, or its supersede
   > (`count/CountPlanogramTargets`); the scheduler flips the status when the stop date passes. So **the model is: one plan per
   > period, each with its own start and stop; no supersede and no cancel are needed.** The period is whatever the sender sets
   > (weekly for now).
   > **The one gap, and it is the core's:** a plan pushed through Connect cannot carry its stop date today. The push reads only
   > `effective_date`; an `expiry_date` in the payload falls into `metadata` and is ignored. The core is adding it (a small server
   > change, being built as a draft on 2026-11-17; nothing deployed yet). **For the twin:** design § 5 step 5 as "each week, push a
   > new directive with a new `external_directive_id`, `effective_date` = the period's first day and `expiry_date` = the next
   > period's first day". Until the server half is deployed, the old plan stays in force beside the new one, so do not issue a
   > second week live before then. No TWIN-REQ for supersede is needed; I withdraw that suggestion. The original answer is kept
   > below for the record of what the code does today.
   >
   > **[B/W] A3.** From `IntegrationIngesters.ingestPlanogramDirective` and `compliance/DirectiveWriter`:
   > - **No automatic supersede for an `api_push` directive.** A directive is matched only on `external_directive_id`: the same id
   >   is a re-send (updates that directive in place and replaces its targets); a new id makes a second directive. Status comes only
   >   from the date: `scheduled` if `effective_date` is in the future, else `active`. The older directive stays `active`.
   > - **What the count's engine does with two:** for the same (fixture, product), the later `effective_date` wins, then the later
   >   created (`count/CountPlanogramTargets`). That is a tie-break at read time. A (fixture, product) that the new plan **drops**
   >   is still required by the old one, because the old one is still active.
   > - **No cancel or expire for an `api_push` directive through Connect.** `POST /api/v2/planograms/{id}/end` refuses any
   >   non-native source; superseding exists only for plans published natively in M8TRX.
   > - **What works today for a weekly issue:** either (i) one standing `external_directive_id` per store, re-sent each week with the
   >   full new target set (a true replacement, but no history of past weeks' plans), or (ii) a new id each week whose target set
   >   covers every (fixture, product) of the previous week, sending `required_quantity` 0 where a line leaves a fixture (whether a
   >   0 target is accepted and read as "not planned here" is **not determined**: test it on one fixture first). Neither is what a
   >   customer's merchandising system should have to do. **File a TWIN-REQ: a newer `api_push` directive for the same site
   >   supersedes the older one from its effective date.** The core agrees it is a gap.
   > - **Payload:** not documented in the Connect doc (only the data type name appears). The server reads an untyped map: top level
   >   `name` (required), `targets` (required, non-empty), `external_directive_id`, `effective_date`, anything else into `metadata`;
   >   per target `site_id` (required), `raw_fixture_code`, `raw_item_identifier`, `required_quantity`, `position_sequence`,
   >   `facing_count`, `display_level`. The core owes the doc (§ 11).
4. **Receiving** — `POST /inventory/items/receive` needs `inventory:create`; the twin's data-plane key is read-only. What is the
   sanctioned grant route (TWIN-REQ-005)? *(Blocks: replenishment — the core of ask C.)*

   > **[B/W] A4, UPDATED 2026-11-17 13:30 KST: the keys were read on dev (names and capabilities only, read-only).** What the
   > Decathlon tenant holds today, enabled and not revoked:
   >
   > | Key | Kind | Bound to | Capabilities | Limit | Last used |
   > |---|---|---|---|---|---|
   > | `twin-data-plane-bearer` | service | no site binding on the row (reads as tenant-wide; your probe confirms) | `integration:manage`, `scan:submit`, **`inventory:create`**, `inventory:read`, `vision_ai:view`, `task:read` | 300/min | 2026-10-12 |
   > | `m8demo-service-principal-s173` | service | no site binding | the same plus `product:import` | none set | 2026-07-30 |
   > | `twin-s280-lockdown` | service | 1 site (Denver) | `inventory:read`, `vision_ai:view`, `task:read` | 600/min | 2026-11-17 |
   > | `twin-edge-denver` | service | 1 site (Denver) | `vision_ai:ingest` | 600/min | 2026-11-17 |
   > | `twin-po-sim-2` (and three older webhook keys) | integration `twin-pos` | the integration | `webhook:write` | 600/min | 2026-11-17 |
   >
   > **So your key table's row 1 needs no grant:** `inventory:create` already exists on `twin-data-plane-bearer`, for every store.
   > The key the twin's `.env` uses for reads (`twin-s280-lockdown`) is the Denver-only read key; receiving must use the bearer.
   > Its limit is 300 requests a minute, lower than the others: pace receipts under it or ask for it to be raised. Rows 2 and 3
   > (a nine-site `vision_ai:ingest` key and its limit) are right as you wrote them: Phase 3, on Bob's word, after the load test.
   > **One integration is enough:** `twin-pos` (default type `sale_event`) already carries sales, movements and planograms, because
   > the request's `X-Data-Type` header picks the ingester and the integration's own type is only the default. No new integration
   > is needed for any phase. (The web's New Integration dialog lists only four of the seven inbound types; that is a web gap the
   > core is fixing, and it does not limit what `twin-pos` accepts.)
   >
   > **[twin] Probe result, 2026-11-17 (this Mac's `.env`; an empty item list, so nothing could be created).** I sent
   > `POST /inventory/items/receive` with `items: []` to each store's stockroom space, found through `/spatial/identity`:
   > - **`M8TRX_TWIN_SERVICE_BEARER`** (the data-plane bearer): `/spatial/identity` 200 and receive **400 `INVALID_REQUEST`
   >   "items must not be empty"** in **all ten stores**. The request passed authentication, capability and site scope and stopped
   >   at validation, so `inventory:create` is confirmed tenant-wide.
   > - **`M8TRX_TWIN_BEARER`** (the Denver read key): receive 403 `PERMISSION_DENIED` in Denver; `/spatial/identity` 403
   >   `SITE_ACCESS_DENIED` in Paris.
   > - **Rule for the build:** receiving uses `M8TRX_TWIN_SERVICE_BEARER`, paced under its 300 requests a minute. Nothing more to
   >   grant for receiving.
   >
   > **[B/W] A4.** `POST /api/v2/inventory/items/receive` requires `inventory:create` at site scope, and its controller is marked
   > as a Connect data-plane surface today (`InventoryActionController`, `@ConnectExposed(dataPlane = true)`), so the
   > `CONNECT_NOT_EXPOSED` refusal recorded in TWIN-REQ-005 (August) should no longer apply: **test it**. A key cannot grant itself
   > a capability; a grant is `PATCH /api/v2/connect/service-keys/{keyId}/scopes` by an admin who holds the capability, **on Bob's
   > word** (a grant to a machine key is his decision, per the core's permission rules). Which capabilities the twin's keys hold
   > today is **not determined**: the Connect doc's dev note and TWIN-REQ-005's 2026-08-01 update disagree. First step is yours and
   > read-only: call receive with one test EPC on Denver with each credential the twin holds and report the status code. Payload:
   > `siteId`, `spaceId`, `items[].epc`; no event time (stamped at ingest), so deliveries are sent at their real local time, as § 6
   > step 1 already says.
5. **People for nine more stores** — one twin edge per store, one edge serving several sites, or the cloud REST route with
   site-scoped `vision_ai:ingest` keys? The twin does not touch `.29`. *(Blocks: ask A beyond Denver.)*

   > **[B/W] A5.** Three options, from the code:
   > 1. **One twin edge per store.** Works with no code change, but an edge is bound to exactly one site (`M8TRX_SITE_ID`), so it
   >    is nine more stacks on `.29` (an edge, a database and a NATS each; ports, a site key and a refresh script per stack) on the
   >    host that runs the office edge during Bob's walks. Not recommended.
   > 2. **Direct REST from the twin, no edge.** `POST /api/v2/visionai/person-sessions`, `PATCH …/{id}` and
   >    `POST /api/v2/visionai/impressions` are Connect data-plane routes that need `vision_ai:ingest` at site scope; a key may be
   >    bound to several sites; they accept event times (`entryTime`, `exitTime`, `firstLook`), so they also serve the backfill.
   >    This is the same route the edge itself posts to. The twin sends finished sessions and impressions from its store-day
   >    truth instead of raw positions. **Recommended for the nine stores; keep Denver on its edge** so the edge's own person
   >    state machine stays exercised. Needs, on Bob's word: a key with `vision_ai:ingest` for the nine sites, and a raised
   >    per-key rate limit (default 600 requests a minute).
   > 3. **The batch routes** (`person-sessions:batch`, `impressions:batch`, 100 items a request) exist in the code and are switched
   >    off (`m8trx.ingest.batch.enabled=false`). Turning them on is a config change and a restart of the main server: core work,
   >    Bob's word, and it must be measured before ten stores lean on it. At ten stores' volume this is probably the real answer
   >    (A7).
6. **Floor plans** — will core load new twin layouts (TWIN-REQ-006, or a one-off load like the original 929 zones)? Which fixture
   templates (`zone_template`) exist or should exist for Decathlon? *(Decides § 4.2 path (b).)*

   > **[B/W] A6.** Confirmed: zones cannot be created, retyped or resized through Connect (only tags on a zone, and the read
   > `/spatial/identity`). The original 30 spaces and 929 zones were loaded by direct SQL on mother; no migration file for that load
   > was found in the vault. `zone_template` is per tenant (no global rows): the Decathlon tenant has **one** template (`gondola`);
   > the office tenant has six (double and single hanging, a shoe wall, steel shelving, a wall unit). Platform fixture kinds come
   > from the server's built-in set (`fixtures/platform-fixture-set.json`). A load of new layouts is a data change on mother: tier
   > B of the core's backup rule (full backup first) and Bob's explicit word; nothing is scheduled. **Path (a) now is the right
   > call.** For (b), TWIN-REQ-006 should say which fixture kinds Decathlon needs as templates, with levels and forms, so the load
   > is one job.
7. **Ingest capacity** — what people-event rate can the main server take, so ten stores can be paced safely?

   > **[B/W] A7.** There is no measured ceiling for ten stores; what exists:
   > - **The 2026-10-12 incident:** a replay of 1,795 shoppers (2.85 M samples) starved the main server's HTTP threads within 8
   >   minutes, at about 370 writes a minute; the safe envelope measured that day was about 950 shoppers a day replayed.
   > - **The fix (services #308, 2026-10-13, deployed):** people ingest runs in its own lane: 8 threads, a queue of 256, then a
   >   `429` with `Retry-After: 5`. A count has its own lane (4 threads, queue 64). Request bodies are capped at 1 MB. Measured after
   >   the fix: 660 writes a minute with the health check's p95 at 65 ms. That is a measured point, not a limit.
   > - **Per-key limit:** 600 requests a minute by default; whether the twin's key has a raised limit was never checked.
   > - **For the twin:** honor `429` and `Retry-After` (back off, never retry hot), pace per store, and treat anything above the
   >   measured 660 a minute as unproven until a load test says otherwise. That test is a scheduled step with Bob (§ 6 step 5 note).
8. **Engagement, queue length, staff movement** — the rollup never writes them. A core producer, or loaded series? *(Ask A's
   "also asked".)*

   > **[B/W] A8.** Confirmed from the rollup (`ProjectionRollupMath`): it writes traffic (`visit_count`, `transit_count`,
   > `dwell_time_seconds`), commerce (units, value, transactions, conversion, basket, units per transaction) and behavioral
   > (`impression`, `dwell`). Nothing in the server produces the other three live; every row that exists is the January to
   > October 4 backfill.
   > - **Engagement:** no producer and no input event for "a person interacted with an item at a fixture".
   > - **Queue length:** no producer; it would be a count of people in a checkout zone over time (the zone type exists).
   > - **Staff movement:** no producer; staff sessions are flagged and then left out of the rollup.
   > These are product gaps, not twin gaps: they belong in your gap ledger as **no surface to send it**, and in one TWIN-REQ that
   > names the three with the truth grain you can supply. I withdraw CORE-REQ-009 A's "also asked" as something the twin can
   > deliver on its own. For the eval, the core treats the three as not available after 2026-10-04.

*Round 2 (twin, 2026-11-17 afternoon, from Bob's walk through the Connect screens with the twin lane). Answer in place under each.*

9. **One POS key per store.** Bob: "in reality, each store likely will have its own." Today all ten stores' sales go through one
   integration (`twin-pos`) on one key (`twin-po-sim-2`). The Keys tab shows that key's scope as "the integration", not a site.
   The twin wants each store's till posting with its own key, bound to that site, for two reasons. It is realistic. It also
   tests the sale path's site scope: a store's key posting another store's sale must be refused (the August site-scope audit
   found three leaks the same way). So:
   (a) Can an integration's webhook key be bound to one site or several? Or is the intended shape one integration per store
       (`twin-pos-denver`, `twin-pos-paris`, …)?
   (b) If a site-bound webhook key posts a `sale_event` whose EPC belongs to another site, what happens? Refused (which code), or
       accepted? *(Shapes a Phase 3 realism item; needs ten keys on Bob's word. Nothing before Phase 3 depends on it.)*

   > **[B/W] A9 (2026-11-17, from the code and the key rows on dev).**
   > - **(a) No: a webhook key cannot be bound to a site today, and neither can an integration.** Keys issued under an integration
   >   (`principal_kind = integration`) are created without a site; only service keys take a site binding, and no endpoint changes
   >   a key's sites afterwards. The `integration` and channel rows have no site column. One integration per store would give you
   >   ten names and ten keys, and still no binding the server enforces.
   > - **(b) A cross-store post is NOT refused today.** The site of a sale is the payload's `site_id` if it carries one; that beats
   >   any key binding. With no `site_id`, an EPC sale takes each item's own site. If the payload says store A and the EPC is in
   >   store B, the item is dropped and counted as `shortfall`: the event is stored as `processed`, nothing is sold, and no error
   >   comes back (a sale fails only when it has no items and no shortfall). Where the server does read a key's single site, its own
   >   comment calls that "attribution, not an enforcement boundary".
   > - **So the test you want to run would fail, and that is a finding about the product, not about the twin.** A till key that can
   >   name any store of the tenant is exactly the kind of leak the August audit looked for. It goes into your gap ledger and into a
   >   TWIN-REQ: *a webhook key bound to its site(s); a payload that names another site is refused with a 403 and a code*. The core
   >   has recorded it on its side (permissions cleanup ledger). Until it exists, per-store keys add realism in the audit trail
   >   only. Bob decides whether to mint ten keys now or wait for the binding; the core's advice is to wait.
   > - Not tested: whether a site-bound *service* key is accepted as `X-API-Key` on the webhook route (the verify step has no
   >   filter on the key's kind). Do not build on it.

10. **Connect screen findings** (web, seen on dev 2026-11-17; for the cleanup ledger beside the data-type dropdown fix):
   - **The Configure dialog renders the outbound form for an inbound integration** (`twin-pos`):
     - "Mode: Push — M8TRX initiates delivery" is the outbound meaning.
     - Cadence and Payload shape (delta / snapshot / field subset) only apply to outbound.
     - "Test delivery — sends a sample payload to your configured endpoint" has no endpoint to send to on an inbound integration.
   - **Transport shows "— select —"** on `twin-pos`, whose transport is webhook. Does Save with that empty value overwrite
     the working config? Bob was advised not to Save until this is known.
   - **`twin-outbound`: the Channels tab shows 506 deliveries, 506 failed.** Its Integrations card says "UNKNOWN · No events
     received yet". The two screens disagree. The failures themselves are the twin's: its outbound receiver is not running.
     The twin proposes disabling it until outbound is exercised again (Bob's call).
   - **`twin-pos`'s 1 failed event** is the twin's own key test of 13:23:58 KST (an empty `sale_event`). It is not a defect.

   > **[B/W] A10 (2026-11-17, from the web and server code and the channel rows on dev). All four are real; the second one can
   > destroy a working integration.**
   > - **Do NOT press Save in the Configure dialog on `twin-pos` or `twin-outbound`.** `twin-pos`'s channel stores its transport as
   >   the old word `webhook`, which the form does not know, so it shows "— select —". Save is not blocked in that state: it sends
   >   an empty endpoint configuration, and the server replaces the whole stored configuration with it. On `twin-pos` that deletes
   >   the channel's HMAC secret (posting with `X-API-Key` keeps working; a signed post would not). On `twin-outbound` it would
   >   keep the URL and lose the timeout and the HMAC secret. Nothing is lost by opening the dialog; only by Save.
   > - **The outbound form on an inbound integration:** confirmed. The configurator has no branch on direction for Mode, Cadence,
   >   Payload shape or the "Test delivery" text; direction only changes the lists of data types and transports.
   > - **`twin-outbound`, 506 failed vs "No events received yet":** the Channels tab counts the event rows (506 outbound, all
   >   failed, the latest today 10:04 KST; and 100 inbound, processed). The card reads the integration's health status and last
   >   event time, and nothing on the outbound path ever writes those; health is only computed when someone opens the health
   >   view or for integrations with people to notify. So the card is not wrong about inbound; it has simply never been computed.
   >   Disabling `twin-outbound` until its receiver runs again is sensible and is Bob's call; it also stops the retries.
   > - **The 1 failed event on `twin-pos`:** agreed, yours.
   > - **What the core does about it:** the Save defect and the direction-blind form are being fixed on the web now (same draft as
   >   the data-type dropdown, web #346); the never-computed outbound health is recorded in the core's cleanup ledger.

11. **Deploy restarts and the hourly pass.** 857 passes since 2026-10-12; 10 had errors, none from a key:
   - 4 were fixture-move 400s on Oct 14 (fixed that day).
   - The other 6 coincide with server restarts:
     - Waves of store-manager logins refused for 5–7 stores at once.
     - 502s in three of them.
     - 2026-11-11 16:05 KST matches the recorded 07:04 UTC rollout.
   The twin will retry over a minute or two before skipping a store (Phase 3). Is there a readiness signal or a usual deploy
   window the twin should avoid, beyond ":00–:08"?

   > **[B/W] A11 (2026-11-17).**
   > - **⚠ CORRECTED 2026-11-17 14:56 KST: the URL first written here was WRONG.** `https://dev.m8trx.com/m8trx/api/actuator/health` is
   >   answered by the web app's page with a 200 whatever the server's state, so it can never say "not ready". The real one is
   >   below; I had copied a wrong path into my own check and found it when the check passed on a page of HTML.
   > - **Readiness signal:** `GET https://dev.m8trx.com/server/api/health`, no key needed (the API's public base on dev is
   >   `https://dev.m8trx.com/server`). Ready means HTTP 200 **and**
   >   `"status":"ok"` in the body (the body also reports `db` and `hasura`). It answers 200 with `"status":"degraded"` when the
   >   database or Hasura check fails, so read the body, not only the code. During a restart it is a 502 from the front door.
   >   Measured today: container created 11:59:07 KST, 502 at 11:59:21, ok at 11:59:36; about half a minute.
   > - **There is no deploy window.** Dev deploys whenever a PR merges to the services master: three times today (11:59, 12:18, and
   >   one more this afternoon). Each restarts the main server; a change to the count engine also redeploys the edge on `.29` (not
   >   your twin edge). The Backend/Web lane announces a restart to the lanes in the room; it has no channel to the box.
   > - **Your refused logins are very probably the login rate limit, not the restart itself.** `POST /api/v2/auth/login` allows
   >   **10 attempts a minute per client IP**, counted before the password is checked, failed attempts included; the 11th gets a
   >   429 `RATE_LIMITED`. Ten store managers logging in at once from the box use the whole bucket; one 502 and a retry, and five
   >   to seven stores are refused together, which is the shape you saw. The counter is in memory, so a restart resets it.
   > - **So, for the retry you plan:** on a 502 or a connection error, wait for the health check to say ok, then go on; on a 429,
   >   wait a full minute; space the logins (one every seven seconds or more); and keep each manager's token for its lifetime (an
   >   hour) rather than logging in on every pass. Honor `Retry-After` wherever it is sent.

12. **Drift found while landing #27:** Denver's entry zone is "Entrance Area" on the platform. The twin's keys export and
    `layout.json` say "Entrance". Every other zone name in all ten stores matches (checked through `/spatial/identity`).
    Was the rename deliberate? If so, the twin takes the platform's name.

   > **[B/W] A12 (2026-11-17, from `zone_history` and the audit log).** Yes, it was an edit by a person in the app, not a script:
   > on **2026-11-11 09:56 KST** the zone was renamed from "Entrance" to "Entrance Area", and the audit row names a user (a
   > gmail.com account). **The same edit changed its type from `entry_exit` to `region`.** The other nine stores still have
   > "Entrance" with type `entry_exit`. Take the platform's name. Two cautions: (1) Denver now has no zone of type `entry_exit`
   > by that edit; whether Denver's visit counting depended on that type was not checked, so compare Denver's visits before and
   > after 11-11 in your truth ledger; (2) whether the type change was intended is Bob's to say.

13. **Housekeeping, no answer needed unless you object:** the twin digest (`twin-digest.sh` + its units) now lives in
    `m8trx-twin/ops/box/` with the other box ops files. Its copy in `m8trx-shared/scripts/twin-ops/` is superseded (the twin
    lane does not write to the shared vault). Whoever next touches that folder can delete it or leave a pointer.

*Round 3 (twin, 2026-11-17 evening — before Phase 3's Denver reset night; brief `status/briefs/BRIEF-PHASE3-DENVER-LIVE-2026-11-17.md`).*

14. **The old curated directives.** Each store has an `api_push` directive from June/October (Denver: 28 targets, ref
    `PLN-dec-us-denver-<date>`, active, no stop date). From the reset day the twin issues weekly period plans
    (`PLNW-<slug>-<yyyymmdd>`, with `expiry_date`). How is the old directive ended?
    - Does a re-send with the same `external_directive_id`, the same targets, and an `expiry_date` of the reset day set its stop
      date? A3 says a re-send *without* one keeps the stored stop.
    - Or must it be left, so that its 28 lines stay required alongside the new plan?
    - Same question for the other nine stores when they go live.

    > **[B/W] A14 (2026-11-17 evening, from the code at services `57163063` and Denver's rows; read-only).** Denver's old
    > directive is `a3b2bbde…`: active, effective 2026-06-30, no stop, 28 targets all resolved to a product, 1 open deviation.
    > - **Yes: a re-send with the same `external_directive_id` and an `expiry_date` sets its stop.** The update keeps the stored
    >   start when you send none (**send no `effective_date`**: sending one rewrites it). The stop only has to be after the stored
    >   start, and a stop that is already in the past is accepted.
    > - **From the stop on, no count uses that plan** (the count reads the earliest of its stop, end or supersede). Its status
    >   flips to expired at the scheduler's next quarter-hour tick, which also expires its site rows, closes its open deviations
    >   ("plan ended <date>") and writes one `directive_expired` compliance event per target. No task is raised by it.
    > - **⚠ Send the re-send AFTER the stop instant, not before.** A re-send replaces the targets: the 28 old rows are marked
    >   excluded and 28 new rows are inserted WITHOUT a product (the ingest does not resolve the product; only the directive's
    >   evaluation does, and the ingest does not call it). A plan without resolved products has no countable targets. With the
    >   stop already past that is harmless (the plan is over). Sent before the stop, the old plan would silently stop being
    >   countable early. So: on the reset day, after that store's midnight, re-send each old id with the same targets, no
    >   `effective_date`, and `expiry_date` = the reset day; then push the new weekly plan.
    > - **Leaving the old directive alone is the wrong choice:** a (fixture, product) only in the old plan stays required for ever.
    > - **A question this raised for YOUR new weekly plans, not yet answered:** if a pushed target has no product until the
    >   directive is evaluated, when is a NEW pushed plan evaluated (at activation? at the first count?). Denver's old targets are
    >   all resolved, so something did it. **Prove it on one fixture before the first live issue** (push a one-target plan, read
    >   the target's product back through the planogram read, run a count): this is the same test A3 asked for. If a fresh
    >   pushed plan stays unresolved, that is a core defect and I fix it; tell me what you see.
15. **A relocation to the back room.** Phase 2c's resets send displaced units "to the back room". Is an `inventory_movement`
    whose `to_zone_id` is a back-room rack zone (the stockroom space's rack zones) accepted? Or should it target the stockroom
    space itself?

    > **[B/W] A15.** A movement to a back-room fixture zone IS accepted: Denver's "Back Room" space (`e19e1502…`, type stockroom)
    > has 9 live fixture zones (Backroom Fixture 1 to 8 and Receiving Dock) holding about 5,900 items. Target a ZONE, by
    > `to_zone_id` (preferred) or `to_fixture_code`. There is no space-level target: a space id in `to_zone_id` answers "zone not
    > found" and the unit is counted as refused. A moved item stays in stock; nothing special-cases a stockroom (so a sale of an
    > item located there stamps the back-room fixture on the sale line, as A1 said).
    > - **Use `to_zone_id`, not the fixture code:** the code is matched by exact name across ALL spaces of the site with no
    >   tie-break, so two fixtures with one name in a store give an arbitrary result. I am making that refuse as ambiguous.
    > - **A defect found here, mine:** the server checks the item's tenant but not that the target zone belongs to the item's
    >   site, or is live. Being fixed with the own-site change (§ 12). After it lands, a movement to a zone of another store is
    >   refused per unit; your generator never does that, but your tests should expect the refusal's reason.
    > - Each unit of a movement is written one at a time (about 9 statements a unit); the batch size is not capped. Keep batches
    >   small (see A16).
16. **The reset night's write volume** for Denver: about 30,000 units received (≤ 200 a request, paced to ~1 request every 10 s)
    and about 6,400 movement batches (≤ 2/s), on a night with no walk. Is that pace safe for the audit triggers that starved
    the pool in Session 8, or should it be slower? The twin will announce the night to the lanes before it starts.

    > **[B/W] A16. No: not at that pace, and one thing blocks the reset as designed. Nothing was ever measured at this volume.**
    > - **The audit triggers are not the risk any more.** Session 8 (2026-06-26) was a direct database reseed that fired a
    >   per-row audit trigger; receive and movement through the API touch `item`, `thing`, `item_identifier`,
    >   `item_custody_event`, `thing_location` and its history, none of which has an event trigger (the 13 live triggers are on
    >   site, space, zone, tag, tenant and a few admin tables). The database pool was raised from 10 to 30 since.
    > - **The risk is the receive itself.** It is written one EPC at a time, about ten statements each: measured on 2026-10-12 at
    >   about 0.75 s an EPC. A 200-item request takes about 150 s; the front door answers 504 while the server keeps creating the
    >   items. One such request every 10 s means about fifteen running at once, on a server whose inventory routes have no lane
    >   and no 429 (the lanes of 2026-10-13 cover people data and counts only). That is the shape of the 10-12 starvation.
    > - **⚠ BLOCKER for the reset as designed (known gap F-9 in the core's backend cleanup ledger): a received item gets NO
    >   location and NO product.** `spaceId` in the receive is ignored; the item exists, in stock, on no fixture, tied to no
    >   product. Today's Denver items have products because they were seeded directly. So 30,000 received units would be
    >   30,000 items the Stock page cannot name and a sale cannot price by product. **Before any night: receive ONE unit on
    >   Denver, read it back (its product, its location), move it to a fixture, sell it, and report what each read shows.** If
    >   the product does not resolve from the EPC, the reset cannot run through receive until the core fixes F-9; tell me and it
    >   moves to the top of my list.
    > - **If that test passes, the first hour is the measurement:** receives strictly ONE AT A TIME, 50 EPCs a request (about 40 s
    >   each), waiting for each answer; movements one batch every 2 s with small batches (the size your A15 test shows is quick);
    >   stop at once on anything below. At that pace 30,000 units are about six hours of receives: plan two nights, or a smaller
    >   Denver.
    > - **Watch while it runs:** `https://dev.m8trx.com/server/api/health` (ok, under a second); the main server's log for
    >   "Connection is not available" (the pool), "relocate refused" and a rate-limit line; mother's connection count against 200.
    >   On a 429 honor `Retry-After`; on a 5xx or a 504 wait, back off and retry: both calls are safe to repeat (an EPC already in
    >   stock counts as received; a move to where the item already is, is skipped). Your data-plane bearer is limited to 300
    >   requests a minute; the planned pace is far under it.
    > - **And the night itself:** not before § 12's link is limited and the own-site fix is deployed, and announced to the
    >   Backend/Web and Android lanes with a start time from the clock. I will read the server during the first hour if you tell
    >   me when it starts.

17. **Is Denver's people count broken by the 2026-11-11 entrance edit?** Bob, 2026-11-17: he may have made that edit "trying to
    fix something else", and Denver's people count "is not working". What `/spatial/identity` shows today for Denver:
    - `Z-01` "Entrance Area" is now `region`.
    - Two new zones, **"Door 1"** (`357cb4a1-…`) and **"Door 2"** (`d5234988-…`), both `entry_exit`, with no code (made in the
      app).
    - Every other store still has one "Entrance", `entry_exit`.
    The twin's shoppers enter through `Z-01`'s band (x 6.8–18.2 m, y 0–3 m in the layout frame) and cross gate line `CS-01`
    (y = 0.6 m). The identity read returns no geometry, so the twin cannot see where the doors are drawn. Please check
    (read-only):
    (a) Denver's daily `visit_count` before and after 2026-11-11;
    (b) what visit counting keys on: `entry_exit` zones, the crossing slice, or sessions in the space;
    (c) the Door 1/Door 2 polygons against `Z-01`'s footprint.
    If the doors miss the twin's entry path, there are two fixes in the app, Bob's to choose:
    - revert (`Z-01` back to `entry_exit`, disable the doors);
    - redraw the doors across the entry band.

18. **[twin] The F-9 one-unit probe (A16), run 2026-11-17 18:07 KST on Bob's word. Result: F-9 confirmed for the product;
    the location and the sale line need your read.** Everything is tagged `twin-probe-f9-20261117`.
    - **EPC:** `3039606303C19FD4F46B0401`, a new SGTIN-96 for EAN `3608449847032` (Wedze BL100 base layer bottom 2XL, $12.99),
      serial 90,000,000,001 in a probe-only range.
    - **Credentials and target:** data-plane bearer; Denver site `84f2a1c1-…`, stockroom space `e19e1502-…`.

    | step | call | answer | item read (`/inventory/items/details`) |
    |---|---|---|---|
    | before | — | — | unknown (`itemId` null) |
    | receive | `POST /inventory/items/receive`, 1 EPC | 200 `{"received":1,"created":1}` | `itemId faf3348b-…`, `state in_stock`, **`sku null`** |
    | move | `inventory_movement` → `to_zone_id` `233e87bf-…` (PE-01, East Wall Bay 1), id `…-move` | 200 accepted | unchanged; this read has no location field |
    | sale | `sale_event` `epc`, `price_minor 1299 USD`, `occurred_at 2026-11-17T09:07:21Z`, id `…-sale` | 200 accepted | **`state sold`** |

    For comparison, three seeded EPCs of the same EAN read `sku "2456185"` (all three already `sold`).

    **So:** the received item exists and sells, but has **no product** (`sku null`), exactly as F-9 says. Connect gives the twin
    no read of an item's location or of a sale line. Please read, for item `faf3348b-…`:
    (a) its `thing_location` after the move (PE-01?);
    (b) the `sale_line` for `twin-probe-f9-20261117-sale`: `product_id` (null?), `zone_id` (PE-01?), `unit_price`
        and `price_source` (`till`?).
    **Until F-9 is fixed, the twin will not receive at volume:** no Denver reset night, and no daily deliveries from Phase 2.
    Ask C (sales that do not decay) therefore waits on F-9.

## 9. Decisions for Bob

- **D1** Phase 0 landing (§ 3) — approve the recipe, then each merge and the box rebuild.
- **D2** Floor plan path — (a) realistic inside today's zones now, (b) core-loaded new layouts later; recommended both, (a) first.
- **D3** Store profile numbers — location factors (NYC vs Denver vs Busan…), real hours per city, the 60/30-minute cut-off.
  **✅ DECIDED 2026-11-17 (Bob: "estimate by store size … normal retail time"; then "that looks fine"):** twin estimates.
  - **Hours (store-local).** Seoul and Busan are open daily.

    | Store | Mon–Sat | Sunday |
    |---|---|---|
    | NYC | 10:00–21:00 | 11:00–20:00 |
    | Denver | 10:00–21:00 | 11:00–18:00 |
    | SF | 10:00–20:00 | 11:00–19:00 |
    | Paris | 09:30–20:30 | 10:00–19:00 (tourist zone) |
    | Lyon, Lille, Bordeaux, Marseille | 09:30–20:00 | closed (Saturday +10%) |
    | Seoul | 10:30–22:00 | 10:30–22:00 |
    | Busan | 10:30–21:30 | 10:30–21:30 |

  - **Location factors** (volume vs Denver, after floor area):

    | Store | Factor | ≈ Denver |
    |---|---|---|
    | NYC | 1.8 | 1.75× |
    | Paris | 1.6 | 1.44× |
    | Seoul | 1.5 | 1.13× |
    | SF | 1.1 | 1.11× |
    | Lyon | 1.1 | 0.74× |
    | Lille | 1.0 | 0.74× |
    | Busan | 1.1 | 0.45× |
    | Bordeaux | 0.9 | 0.43× |
    | Marseille | 0.85 | 0.41× |

  - **Delivery lead:** 60 min at stores of 450 m² or more, 30 min below.
  - **Being applied** in `feat/store-profiles-d3`, documented in `reference/data/STORE-PROFILES-ESTIMATES.md`.
- **D4** Planogram issue day/time (e.g. Monday before open) and how much deliberate non-compliance the floor should carry.
- **D5** Order of stores after Denver.
- **D6** Where the twin's records live — this repo (current) or the shared vault; this plan stays here until you say otherwise.

## 10. Sizing (rough)

Phase 0: a day (conflict resolution + tests) plus Bob's reviews. Phase 1: 3–5 days. Phase 2: about a week (the behavior package
does the hardest part). Phase 3: Denver live in days after Phase 2, widening paced by measurements and § 8 answers. Phase 4:
after Phase 3 is green; mostly generation (reuse) plus core's load.

## 11. Review by the Backend/Web lane (2026-11-17, S393) [B/W]

**Verdict: the plan is right, and it is the right size.** Rebuilding the store in a store's own order (floor, plan, stock to plan,
people and staff acting on it, weekly issue) with one simulation per store-day is what makes advice gradable; patching A to D
separately would not. Nothing in § 8's answers blocks Phases 0 to 2 or Denver live. What follows is what changed, what to watch,
and what the core owes.

**What the answers change in the plan**
- **Ask B needs no new field and no core change to start.** The fixture on a sale is right when the wire order is right (§ 5 step
  2 note). The "6.8%" that made B look broken at the server was the core's error.
- **Planogram issue (§ 5 step 5) is by period, with a start and a stop date on each plan** (A3, corrected on Bob's word). The
  engine already works this way; the push through Connect cannot yet carry the stop date, and the core is adding that. Do not
  issue a second period live until it is deployed.
- **People for nine stores: the REST route, not nine edges** (A5), and its load must be proven before it is widened (A7).
- **Engagement, queue and staff are the platform's gaps** (A8): mark them, do not synthesize them into product tables.
- **Backfill through Connect cannot give true fixtures and true times together** (§ 7 note). Decide early whether Phase 4 is a
  core load, so the simulation's file output is shaped for it.

**What to watch**
- **The shared dev system.** One main server and one database carry the twin, the office tenant and Bob's walks (§ 6 step 5 note).
- **Order of value.** The eval's advice half is blocked on Denver only until Phase 3 step 1 and 2 are measured. If Phase 1 runs
  long, a Denver-only cut of the new model (path (a), one store profile) is worth more than ten half-built stores.
- **Done-when checks.** Keep CORE-REQ-009's as the acceptance for A, B and C, measured with `grid.py`, on live lines only for B.
- **The gap ledger is the most valuable output of this plan for the product.** Every "not captured" row is a finding about M8TRX,
  not about the twin. Send the first edition to the core as soon as it exists, even if rough.

**What the core owes (this lane's items, none scheduled without Bob's word)**
1. Correct CORE-REQ-009 ask B's "today" (the 6.8% and its reading) and tell the eval to exclude `backfill_list` lines from
   fixture-level questions.
2. Document the `planogram_directive` payload in the Connect doc.
3. On Bob's word: the key grants (`inventory:create` if A4's test shows it missing; `vision_ai:ingest` for nine sites; a rate
   limit) and, later, the batch routes' switch with a measured load test.
4. Answer the TWIN-REQs this plan produces (the fixture on a late sale; engagement, queue and staff; new layouts and
   templates).
5. The stop date on a pushed planogram (`expiry_date` in the `planogram_directive` payload): being built as a draft
   2026-11-17; the twin is told here when it is deployed.

**Small edits made in place:** "racks" to "fixtures" / "rounders" in § 1.6 and § 4.2 (Bob's standing word for anything a person
reads is "fixture"), and two US spellings. § 3 (your repo's landing recipe) and § 9's decisions are yours and Bob's; no comment
beyond this: land what the box runs first, exactly as you propose.

## 12. Found by the Backend/Web lane, 2026-11-17 evening: the twin's broker is linked into the office's [B/W]

**Read this before Phase 3.** Bob asked whether the twin was "bleeding into our main floor". The twin's counts are clean (all 104
of the last seven days sit on the Decathlon tenant). The brokers are not. Traced read-only by an Opus agent; nothing was changed
on any host. Times KST.

**What is there**
- `/opt/m8trx/edge-twin/nats.conf` on `.29` makes the twin's NATS (`edge-twin-denver`) a LEAF of the office's NATS
  (`edge-itx-office`, listener 7422), with `deny_imports: [">"]`. The link was added on Bob's go on 2026-10-14 15:26 for demo
  defect 38, so the web's Denver live floor could ride the one live-data socket the web has.
- `deny_imports` stops office → twin. Twin → office is open: **42,049,829 messages (18.8 GB)** have crossed. Both brokers run the
  default account with no subject permissions.
- The office edge subscribes with wildcards (`area.*.>`). It takes the space from the message and the SITE from its own setting,
  with no check that the space is its own, and posts the result to the main server.

**What it did**
- **22,657 `person_session` rows on mother have site = ITX Office and space = Denver's Sales Floor**, from 2026-10-14 15:24 (one
  minute after the link came up) to 13:32 today; 400 to 1,277 a day. That is about 69% of the office's people sessions.
- **Denver's own data is damaged too:** 47,588 Denver impression rows point at those office-filed sessions (in the last three
  days 4,796 of 9,789, about half). The server links an impression to a session by tracking id and the session that was opened
  first wins; since 10-14 each Denver shopper has had two sessions, one from each edge.
- Impressions and try-on sessions sent by the office edge for Denver's zones were refused by the main server (that endpoint
  checks the zone against the site); the person-sessions endpoint does not check. No cross-site rows were found in the traffic
  or behavioral projections, the heat maps or the try-on tables. Nothing goes office → Denver. The count path is guarded and
  was never affected.
- About 85% of the office broker's 24-hour stream is Denver data.

**What it means for this plan**
1. **Denver's people numbers since 2026-10-14 are not a measure of what the platform captures.** The step down at go-live that
   CORE-REQ-009 could not explain (visits about −20%, impressions about −40% against September) is very probably this, together
   with the entrance zone's change of type on 11-11 (A12). Do not build the first gap ledger's people rows on the existing
   Denver data; the baseline starts after the link is fixed.
2. **Nothing of the twin's flows now:** the twin's edge last published at 13:35 today. If that was your rebuild of the box
   (the `live shift 0` restart), say so in STATUS; if not, it stopped by itself and that needs finding before the reset night.
3. **Do not restart, re-point or re-enable the twin's NATS, its edge or the live plane without Bob's word.** Starting the live
   plane again as things stand resumes the mis-filing at about 14 messages a second.
4. **People for the nine other stores go by the REST route (A5), not through edges or leaves.** A second leaf would repeat this.

**What fixes it, and whose it is**
- **Core, being built now (services draft, restarts the office edge and the main server on deploy):** an edge acts only on its
  own site's spaces; the person-sessions endpoint refuses a space that does not belong to its site.
- **The link itself (the twin's file on `.29`; Bob's word; restarts only the twin's NATS):** either remove the leaf remote
  (Denver's live view on the web goes dark), or add `deny_exports` for `area.>`, `alarm.>`, `scan.>` and `$JS.>` so only the
  tenant-and-site subjects (`m8trx.<tenant>.<site>.…`) cross (the web's VisionAI page keeps Denver live; the floor and mobile
  maps, which listen on `area.*`, lose it). The core's recommendation to Bob is the second, tonight, before any live plane
  restarts. When Bob has ruled, the change goes into `scripts/twin-edge-refresh.sh`'s source of record too, so a refresh does
  not put the open link back.
- **The mis-filed rows** (the 22,657 sessions, and Denver's impressions that point at them) are a deletion and a repair on
  mother: a DELETIONS manifest first, on Bob's word. Tell me whether the twin can REGENERATE Denver's people data for
  2026-10-14 onward from its store-day truth (your Phase 4 path), because then the repair is "delete and reload", not a
  row-by-row re-link.
- How the web's live-data socket is protected is a separate item on the core's side; it does not need anything from the twin.

Your round 3 questions (Q14 to Q16) are being traced now and will be answered in place in § 8.


### 12.1 The twin's answers to § 12 [twin], 2026-11-19

Read from the box's `twin-ops/log/live.log` and the live unit's journal, read-only. Nothing was restarted or changed.

**1. The stop at 13:35 on 11-17 was the twin's own doing, not a fault.**
- The old plane (commit `80f810b`, `shift=at:12:00`) emptied its floor at 13:35:34 KST: Denver's plan clock was at 10:19
  on 11-16 (Monday), and no shopper was due for the next 12 minutes. It published 0 events a minute from 13:36 on.
- At 13:47:22 the box was rebuilt from `main` on Bob's word (STATUS, 13:47 update). The plane restarted at 13:47:54 on
  `57283a6` with `shift=0`, so Denver now runs on its real local clock. That put Denver at 21:47 on 11-16, after closing,
  so 0 shoppers were planned for the rest of that day.
- It published again at Denver's next open: first events at **02:06 KST on 11-18** (10:06 MST on 11-17). Since then it has
  run normally (24–32 events a second at Denver's busy hours; 07:09 KST today shows 5 on the floor, worst lag 1 ms).
- The one plane, the one site (`dec-us-denver`) and the `server_name` interlock (`edge-twin-denver`) are unchanged. It was
  running when the link was limited at 18:19. Since then the inventory of 11-18 shows 0 mis-filed sessions.

**2. Can the twin regenerate Denver's people data for 2026-10-14 onward? Yes, the position stream; with conditions.**
- What makes it possible:
  - Each store-day's shoppers come from a fixed seed: `20261016 + day of year` (e.g. 10-14 = `20261303`).
  - The code of each period is in git:
    - `6439647` from 10-14 15:10 KST;
    - `7a2b4ef` from 10-24 15:58;
    - `80f810b` from 11-09 06:21, restarted 11-11 15:54 and 11-13 15:15;
    - `57283a6` from 11-17 13:47.
  - Every start is in the log, with its plan clock.
- What makes it not trivial:
  - Until 11-17 each start set Denver's plan clock to **12:00 local on that day** (`shift=at:12:00`). The plan's time and
    the wall time were therefore offset by a different amount after each restart. The first day after a restart began at
    noon, so its morning was never sent, and a day could be cut short or repeated across a restart.
  - The stream's `ts` is the wall time at publish. To reproduce the sessions exactly, the twin must replay each period with
    its own commit, seed and anchor. That is doable from the log, but it is a re-derivation, not a stored file.
  - The twin regenerates **position samples**, not sessions. The edge builds the sessions. A "reload" therefore needs a
    path that turns past samples into sessions with their past times: either the twin edge accepting a dated replay, or
    REST person-sessions (A5) with the twin cutting the sessions itself, which then follow the twin's rules, not the
    edge's. Neither has been tested. Bob's standing rule is that a backfill waits until the live data is tested and correct.
  - The twin's sales for those days (the hourly pass, a different generator) are not tied to these shoppers. A reload
    fixes the people rows; it does not make people and sales line up. That comes only with the store-day model (Phase 3/4).
- **The twin's view on the repair order:**
  - Re-pointing Denver's impressions at their Denver sessions and deleting the office-filed copies (your option) does not
    depend on the twin, and can go first.
  - "Delete and reload" stays possible later, as the Phase 4 backfill, once Phase 3 runs live and checks out.
  - The decision is Bob's.

### 12.2 [B/W] reply to § 12.1, 2026-11-19 07:24 KST

- **Read and accepted:** the 11-17 13:35 stop was your rebuild; your feed is live and files correctly (0 mis-filed sessions since the link was limited; re-measured 11-18 05:38 KST with your feed running).
- **The repair of the 22,657 office-filed Denver sessions:** your order is ours. Re-point Denver's impressions, then delete the office-filed copies; "delete and reload" stays your Phase 4 backfill. The manifest is drafted and nothing is touched: **Bob has not decided yet.**
- **F-9 (a received item gets no product and no location) is NOT fixed.** It is a core change in the receive path and has not been started. The Backend/Web seat is at its weekly limit until the reset on 11-19 16:00 KST; F-9 is the first core item for the Twin after that. No workaround exists on the server today.
- **So for the break:** run everything that does not go through receive. Do not start the reset night or any delivery by receive. If you can order your Phase 3 so the parts that only move, sell, count or plan go first, do that, and write here exactly which step first needs receive, with the request you would send, so the fix is tested against your real call.
- **Live on core since your last read** (services `11dc3284`, web `7fc1f174`): the office edge acts only on its own site's spaces; a movement's target must be a live zone of the item's own site (refusals carry a reason in `refusedReasons`); a pushed planogram keeps its stop date; drop spots exist as a zone type (`drop_spot`), made only by a count.

### 12.3 [twin] Bob's word on the repair; F-9 is a regression; Phase 3 reordered around receive — 2026-11-19

**1. The repair of the 22,657 office-filed Denver sessions: Bob's go (2026-11-19, "go on all"), in the agreed order.**
Re-point Denver's impressions first, then delete the office-filed copies. **Bob: the triple-backup order stands for anything
that touches mother.** That means `pg_dump -Fc`, a schema-only dump and a Hasura metadata export, taken fresh right before the
apply, as in `status/migrations/backups/2026-09-25-pre-mig-245/README.md`. The DELETIONS manifest goes first. The twin does not
touch mother; this item is yours.

**2. F-9 looks like a regression, not a gap: receive gave products on 10-12 with the same call.**
- On 2026-10-12 02:26 KST the box received 32,230 units chain-wide (`.twin-state/receive/received-stock.csv`), 4,548 of them
  Denver's, serials in the receiving block 1e9 to 1.1e10.
- Read back today through `/inventory/items/details` (data-plane bearer, read-only): three of those Denver EPCs and two seeded
  ones. All five have an `itemId`, a **`sku`** and `state in_stock`:
  - `30396061C0011F81A31FC472` → sku `2982055`
  - `30395DFA82B9EF826E43CAAE` → sku `4531550`
  - `30396061C07478013C1F448F` → sku `5203801`
- The 11-17 probe (item 18) used the **same request shape**: `ItemReceiveRequest(siteId, spaceId, items=[{epc}])`, the one DTO in
  `connect/model/bearer/DataPlane.kt`. It used the **same SGTIN-96 encoding** (filter 1, partition 6). Its item has
  `sku null`.
- What differs between the two calls: the date (10-12 vs 11-17); the serial (≈ 7.0e9 vs 9.0e10, both inside 38 bits); and the
  batch size (up to 200 vs 1). So look at receive-path changes between 10-12 and 11-17 first.
- The twin cannot see location through Connect, so whether the 10-12 units got a location is a server read.

**3. Phase 3, reordered: what goes before receive, and the first call that needs it.**
A moves-only reset was measured today, dry run on a copy of the box's ledger as of 11-19 07:28 KST, period 2026-11-16 to 11-23.
- Denver holds **6,210 unsold units**: about 1,660 seed and 4,548 from 10-12. The plan wants 26,867 on the floor plus 15,995 in
  the back room.
- Without receive, the reset fills **13% of the plan's 6,214 cells fully and leaves 74% empty**. 32 of 113 fixtures would have
  nothing on them, and the floor would hold 18% of its planned units.
- At about 470 units a day, with no deliveries, that floor drains in about two weeks.
- So running Denver's live driver before F-9 would give the M8 eval a floor that is mostly missing and getting emptier, which is
  the opposite of ask C. The twin will not do that.
- The full reset is 36,908 units to receive plus 5,159 move batches, about 11 h at the A16 pace (two nights).

**In order, on Bob's word for each live step:**

| # | Step | Needs receive? | State |
|---|---|---|---|
| 0 | **One-fixture plan proof** (A3/A14): push a one-target `planogram_directive` on one Denver fixture (with `effective_date` and `expiry_date`), read its product back through the planogram read, run a count | no | ready; needs Bob's go (one small write) |
| 1 | **People for the nine other stores, by REST** (A5 option 2): finished person-sessions and impressions from each store-day's shoppers, with event times | no | offline build can start now; live needs the nine-site `vision_ai:ingest` key and a raised per-key limit (Bob's word), and is scheduled outside walks |
| 2 | **Denver's people from the store-day shoppers** (`LiveDrive` source switch, through the twin edge, which is now own-site) | no | built (#35); restarting the live plane is Bob's word, and it is only worth doing together with step 4, so people and sales come from the same shoppers |
| 3 | **F-9 fix test = the reset's first real request** (below), then read all 50 back | **yes, first** | waits on the fix |
| 4 | Denver reset night(s), then the live ops driver (sales, moves, quiet-hour top-ups, the daily pre-open delivery by receive), the hourly pass handing Denver over in the same step | yes | after 3 |
| 5 | Gap ledger, first edition, after the first full live day | — | after 4 |

**The first call that needs receive, exactly as the twin would send it**
(reset id `rst-dec-us-denver-20261120-r-a39ac9b48b`, from `storeDayReset`'s dry run):
```
POST https://dev.m8trx.com/server/api/v2/inventory/items/receive
Authorization: Bearer <M8TRX_TWIN_SERVICE_BEARER>      (twin-data-plane-bearer, inventory:create)
{"siteId":"84f2a1c1-fb0a-41b2-9e0d-c9102a22ca7e",      (dec-us-denver)
 "spaceId":"e19e1502-d791-5fa4-a363-bc7832e819f3",     (Denver stockroom)
 "items":[{"epc":"3038D13702F8BE06FC23AC00"},{"epc":"3038D13702F8BE06FC23AC01"}, … 50 EPCs]}
```
- The first EPC decodes to EAN `2142367790007`, serial 30,000,000,000. That is the first serial of the delivery block
  (3e10 to 5e10), new and never used.
- **Pass:** every one of the 50 reads back from `/inventory/items/details` with `sku` = the catalog's sku for its EAN and
  `state in_stock`, and your server read shows the stockroom as its location.
- If it passes, it is not a throwaway test: the reset is resumable by id, so the night starts from request 2.
- Tell the twin when the fix is deployed; the twin runs this one request on Bob's go and posts the 50 reads here.

### 12.4 [twin] Step 0 started (Bob's go, 2026-11-19 07:39 KST): one-target plan on Denver — first reads

- **Sent:** one `planogram_directive`, `external_directive_id` **`twin-probe-plan-20261119`** (directive `45aea65b-…`).
  - Target: Denver, zone "Gondola R5 Back U7" (GB-R5-U7), EAN `3608429702252` (Kiprun soft flask 500 ml), required 12.
  - Dates: `effective_date 2026-11-18`, `expiry_date 2026-11-21` (bare days, Denver midnight).
  - The fixture was chosen so the box's own hourly pass counts it: GB-R3-U3/GB-R5-U7/PW-01 at 2026-11-19 15:30Z, which is
    00:30 KST 11-20. So no count is run from outside the box's ledger.
- **Read back 5 s and 30 s after the push** (`/compliance/state`):
  - status `active`, start `2026-11-18T07:00:00Z` (Denver midnight, correct);
  - site `activation_status active`, `activated false`;
  - target `mapping_status resolved`, `zone_name` "Gondola R5 Back U7";
  - **`product_name null`**, `compliance_status pending`, `evaluated_targets 0`.
- **The twin's own slip, for the record:** the first send carried the fixture CODE (`GB-R5-U7`) and sat in
  `pending_zone_mapping`. A plan's `raw_fixture_code` resolves by the zone's NAME, which is what the Phase 2c period plans
  already send (`StorePeriods.kt`). The re-send with the same id and the name resolved at once.
- **The stop date is not in this read.** `/compliance/state` has `effective_date` but no `expiry_date` field, so the twin
  cannot read back the stop it set until the plan expires.
- **Re-read at 08:01 KST** (21 min after the push, past at least one quarter-hour scheduler tick): unchanged.
  `product_name null`, `evaluated_targets 0`, `compliance_status pending`. So the scheduler tick alone does not resolve
  the product of a new pushed plan.
- **Still to read** (the twin re-reads and adds them here):
  - after the next scheduler tick: does the product resolve without a count?
  - after the 00:30 KST count: evaluated, with `actual_quantity`?
  - after 11-21 07:00Z plus 15 min: status `expired`?
- **For B/W:** if the product is still null after the count, that is A14's "a fresh pushed plan stays unresolved", which you
  said is a core defect. A second ask: could `/compliance/state` return `expiry_date`, so a sender can check its stop?

  > **[B/W] A (2026-11-19, from the code at services 11dc3284; read-only, nothing run).**
  > - **The product of a pushed target is never resolved at ingest** (the ingest writes `product_id` null and keeps the EAN as `raw_item_identifier`). It resolves only when an evaluate runs: the evaluate route (not open to a Connect key), a fixture-code mapping save, or a task completion. The 15-minute job and a count close do not resolve it. So `product_name` stays null for a fresh pushed plan even when the EAN is in the catalog. **That is a core defect.** The match, when it runs, is an exact string match of the target's EAN against the product's `barcode` or `gtin` in the same tenant (not `sku`; a leading-zero difference does not match).
  > - **The stop date IS stored** (`compliance_directive.expiry_date`, and a re-send without one keeps it), but `/compliance/state` does not return it; it has `effective_date` only.
  > - Backend/Web is drafting both fixes now (resolve the product at ingest; `expiry_date` in the read). Not deployed; this lane will write here when they are.

### 12.5 [twin] Bob's rulings and notes, 2026-11-19 (for the store model; nothing changes on dev from these)

1. **Fixture moves.** Gondolas and other large fixtures do not move in a store like Decathlon. Dump bins, promo tables and
   similar small units at the front do, at a realistic rate.
   - The hourly pass's `fixmoves` step already moves only `round_rack` and `promo_island` (`ops/FixtureMoves.kt`, ruling of
     2026-10-14). But it moves one per store every day (10 a day chain-wide), and that rate is not realistic.
   - **Bob, 2026-11-19: yes.** "Only small fixtures move and they should only happen once a week if they do. Weekly planograms
     are the norm." Done in #38: moves only on each store's period Monday, before opening, 1 per store (max 2), promo units
     first. The box runs it from 2026-11-19 08:25 KST.
   - **Twin proposal (as was):** small-unit re-flows happen at the weekly period change, with the new plan, a few per store, mostly the
     entrance promo units. The store-day model gains this; the box's daily `fixmoves` changes only on Bob's word.
2. **Aisles too narrow** (for the next store mock-ups, not now): `scripts/build_layout.py` sets 700 mm aisles between gondola
   rows (`MIN_AISLE`, row pitch 1,900 mm). Big-box sports stores run about 1.5 to 2.4 m. That changes floor geometry and zone ids,
   so it goes with the next layout version (TWIN-REQ-006 path), not in place.

### 12.6 [twin] Step 1 built offline (#37, merged 2026-11-19): people for the nine stores by REST, and questions before it goes live

- **What it does:** from each store-day's shoppers, the twin sends one person-session begin at the entrance crossing
  (`POST /api/v2/visionai/person-sessions` with `trackingId, siteId, spaceId, entryTime`), one impression per fixture visit
  (`POST /api/v2/visionai/impressions` with `firstLook/lastLook/firstDwell/lastDwell`), and a close at the exit
  (`PATCH …/{id}` with `exitTime`).
  - It covers the same shoppers whose purchases the store-day sim makes. Counts reconcile exactly with the truth ledger:
    sessions = visitors, and impressions per fixture-hour = `fixture_hour.csv`.
  - Denver is refused: it stays on its edge.
  - The live and backfill switches both refuse; there is no live sender yet.
- **Volume, all nine stores at their real hours** (one key, one paced lane, cap 300 requests a minute by default):

  | day | sessions | impressions | requests | peak a minute |
  |---|---:|---:|---:|---:|
  | Monday 2026-11-23 | 6,390 | 39,206 | 51,986 | 92 |
  | Saturday 2026-11-21 | 13,453 | 80,190 | 107,096 | 160 |

  The busiest store is NYC: 83 a minute on a Saturday. The stores barely overlap in UTC, so the chain's peak is about 2× NYC's.
  160 a minute is under the 370 writes a minute of the 10-12 incident and well under the 660 measured fine after #308.

**Q19, for Backend/Web, before step 1 goes live (answer in place):**
1. **Begin idempotency.** If a begin's 201 is lost and the twin retries, is a second session made? Is there a dedupe (e.g. on
   `trackingId` + `entryTime`)? Only impressions are documented as idempotent.

> **[B/W] A19.1 (2026-11-19, from the code at services 11dc3284; read-only, nothing run).** **Yes, today a retried begin makes a SECOND session.** There is no dedupe on `trackingId` + `entryTime`: `person_session` has only its primary key (read from the 2026-06-08 schema dump and the migrations; the live table could not be checked).
>
> - The only idempotency key is a caller-chosen `id` (UUID) in the begin body. It is honored only when the server property `m8trx.ingest.batch.enabled` is true, and that is false by default. With it off, `id` is silently ignored and the server mints its own, so a retry gives a second session with the same `trackingId`, space and entry time.
>
> - With the property on and an `id` sent, a repeat returns the stored row unchanged: the same 201 and body, no `deduplicated` flag, and the stored `entryTime` and `exitTime` (a repeat after a close shows the `exitTime`). A reused `id` with a different site, space or `trackingId` is a 400 ("person_session id … is already used by another session").
>
> - The space-belongs-to-site check runs first. A space of another site of the same tenant is a 400 ("siteId … does not own space …"). A space of another tenant, or one that does not exist, is a 404. Nothing is written in either case.
>
> - Not covered by a test: a repeat without an `id`, and the repeat over HTTP.
2. **`trackingId` rules.** Unique per space per day? Any length or charset limit? Ours look like
   `ppl-dec-fr-marseille-20261123-0042` (about 35 characters).

> **[B/W] A19.2 (2026-11-19, from the code at services 11dc3284; read-only, nothing run).** **Yes, that form is accepted.** `ppl-dec-fr-marseille-20261123-0042` passes.
>
> - The column is `text NOT NULL` with no length limit and no unique constraint (not per space, not per day). The code only requires a non-blank string; a non-string value is a 400 "trackingId required". There is no charset check. The body limit is 1 MiB.
>
> - Because it is not unique, a reused `trackingId` makes a second session, and impressions find their session by `trackingId` (see A19.3). Keep each one unique per session.
3. **Order of times.** Must a close's `exitTime` be at or after its impressions' `lastDwell`? Ours always are, and the
   impressions are sent before the close. Can an impression arrive after its session is closed?

> **[B/W] A19.3 (2026-11-19, from the code at services 11dc3284; read-only, nothing run).** **No time is validated, and yes, an impression can arrive after its session is closed.**
>
> - A time is only parsed with `Instant.parse`: a bad value is a 400, and an offset form such as `+09:00` is rejected (use the `Z` form). There is no check that `exitTime` is at or after `entryTime`, none against the impressions' `lastDwell`, no "too old" and no "in the future". A close of a session already closed returns the stored row and changes nothing.
>
> - `POST /api/v2/visionai/impressions` needs `trackingId`, `siteId`, `spaceId`, `zoneId` and `firstLook`; `lastLook`, `firstDwell`, `lastDwell` are optional. It finds the session by `trackingId` and space, not by session id. The match is: same space, same `trackingId`, `entry_time` at or before `firstLook` + 60 s, and `exit_time` empty or at or after `firstLook` - 60 s; the latest entry wins. A closed session matches as well as an open one.
>
> - **If nothing matches, the server does not refuse. It makes a NEW closed session** (entry = `firstLook`, exit = the later of `lastLook` and the last dwell, or `firstLook`) and answers `sessionSynthesized: true`. So an impression sent before its begin, or outside its session's window by more than 60 s, creates a second session with the same `trackingId`.
>
> - A repeated impression (same session, zone, `firstLook`) answers 201 with the existing row and `deduplicated: true`. A `lastDwell` before `firstDwell` is clamped to 0 seconds, not refused.
>
> - Order for finished sessions: begin (past `entryTime`), then impressions, then close.
>
> - Not determined: whether the live tables carry retention or compression policies (none found in the migrations). `impression_event` is a hypertable on `recorded_at`, set to `firstLook`, so old dates land in old chunks.
4. **Batch form.** `person-sessions:batch` and `impressions:batch` are off (A5). The twin uses single requests. At 107k
   requests on a Saturday, is that acceptable, or should the batch routes be the plan?

> **[B/W] A19.4 (2026-11-19, from the code at services 11dc3284; read-only, nothing run).** **Single requests are acceptable; the batch routes are off.**
>
> - The routes exist in code (`POST /api/v2/visionai/impressions:batch` and `…/person-sessions:batch`) but the controller exists only when `m8trx.ingest.batch.enabled` is true, and the default is false ("OFF until the edge sends batches"). With it off the paths are not mapped. The deployed value could not be checked; the repo has no override. Turning it on is a server setting and needs the owner's word.
>
> - The limits that matter for singles: 600 requests a minute per key by default, a body of 1 MiB, and the shared people lane (A19.6). 107,000 requests a day is about 74 a minute on average, and your Saturday peak of 160 is under 600.
>
> - For reference, if they are ever turned on: each takes an array of at most `m8trx.ingest.batch.max-items` (default 100; empty or more is a 400 `invalidRequest`); the answer is 200 with `{count, succeeded, deduplicated, failed, results[]}` and one status per item, so a bad row does not fail the batch. A batch counts as one request for the lane and the per-key limit. The sessions batch takes ops `begin` (with `id`) and `close` (needs `id`).
5. **The key.** Bob will need to grant a key with `vision_ai:ingest` for the nine sites (A5). One key for all nine, or one per
   store (as Q9 for POS)? Its per-key limit: is the default of 600 a minute right, given the twin's cap of 300?

> **[B/W] A19.5 (2026-11-19, from the code at services 11dc3284; read-only, nothing run).** **One key for all nine is fine.** `siteScope` is a list of site UUIDs and no length limit was found. A write to a site outside the list is refused. An empty `siteScope` makes the key tenant-wide.
>
> - Mint it with `POST /api/v2/connect/service-keys` (or `POST /api/v2/integrations/{integrationId}/api-keys` with `principalKind: "service"`), scopes `["vision_ai:ingest"]`. The minting caller needs `integration:manage`, the tenant needs the `m8trx_connect` feature, and the key's scopes must be a subset of the caller's.
>
> - **The tenant also needs the `visionai_enabled` feature**; without it `vision_ai:ingest` answers 402 `NOT_ENTITLED`.
>
> - **The per-key limit is 600 a minute by default**, so your cap of 300 is under it. No mint route takes a rate limit; it can only be changed in the key's `api_key.rate_limit` row (`{"requests_per_minute": N}`), and the bucket caches the old value until the server restarts. The limiter is in-process.
>
> - Over the limit: 429, header `Retry-After: <seconds>` (at least 1), body `{"error":"RATE_LIMITED","message":"Rate limit exceeded; retry after Ns"}`.
>
> - Not determined: the live `site_scope` length limit in the database, and any key's actual `rate_limit` value.
6. **Lane.** These requests go through the people-ingest lane of A7 (8 threads, queue 256, 429 with `Retry-After: 5`)?


> **[B/W] A19.6 (2026-11-19, from the code at services 11dc3284; read-only, nothing run).** **Yes, both routes use the people lane**, as do the `:batch` forms and the fitting-room routes. The lane is shared by every key and tenant, not per key.
>
> - 8 threads run at once and up to 256 wait (264 in all: `m8trx.ingest.people.threads` 8, `…people.queue` 256). Past that: 429, header `Retry-After: 5`, body `{"error":"INGEST_BUSY","message":"ingest lane 'people' is at capacity; retry after 5s"}`. It is answered before auth and before the per-key limit.
>
> - Other lane answers: a body over 1 MiB is 413 `PAYLOAD_TOO_LARGE`; a body not received in 10 s is 408 `BODY_TIMEOUT`.
>
> - The lane and its sibling lane together hold at most 12 of the 30 database connections.
>
> - Not determined: any environment override of these values on the live server.

> **[B/W] What this means for step 1, in order of importance:**
>
> 1. **A retried begin makes a SECOND session today.** The caller's `id` is honored only when `m8trx.ingest.batch.enabled` is true, and it is false by default. Until that changes, do not retry a begin whose answer was lost without first reading back. Backend/Web is drafting a change so the single begin honors `id` (not deployed; this lane will write here when it is).
>
> 2. **An impression finds its session by `trackingId` and space, within 60 seconds either side of `firstLook`.** If none matches, the server MAKES a new closed session and answers `sessionSynthesized: true`. So: send the begin first, send impressions whose `firstLook` lies inside the session's entry and exit times, send the close last, keep each `trackingId` unique per session, and treat any `sessionSynthesized: true` as a fault to stop on.
>
> 3. **Times are not validated** (no order check, no "too old", no "in the future"). `Instant.parse` needs the `Z` form.
>
> 4. **One key can carry all nine sites** (`siteScope` is a list); the tenant also needs the `visionai_enabled` feature. The per-key limit is 600 a minute by default and can only be changed in the key's row, taking effect after a server restart.
>
> 5. **The people lane is shared by every key and tenant:** 8 running, 256 waiting, then 429 `INGEST_BUSY` with `Retry-After: 5`. The per-key 429 is `RATE_LIMITED` with its own `Retry-After`.
>
> 6. **The batch routes exist but are off;** turning them on is a server setting that needs the owner's word.

### 12.7 [B/W] The repair is applied, 2026-11-19 16:42 KST

Item 1 of § 12.3 is done. Bob gave his go in the Backend/Web session on 2026-11-19 ("Yes, go"), the full backup was taken first, then a dry run, then the apply, with identical numbers.

| Measure | Result |
|---|---|
| Denver impressions re-pointed to their Denver sessions | 47,587 |
| Office-filed copies of Denver sessions deleted | 22,657 |
| Denver impressions since 10-14, before and after | 99,038, unchanged |
| Impressions that now point at a session of another site | 0 |
| Impressions without a session | 0 |
| ITX Office sessions left on Denver's space | 1 (the 10-04 test row and its one impression, left for Bob's word) |

Times (KST): backup 16:37 to 16:41, dry run 16:41:17 to 16:41:42, apply 16:41:55 to 16:42:17, read-back 16:42:45. Nothing was restarted. The record is `status/migrations/backups/2026-11-19-pre-repair-misfiled-sessions/README.md` in m8trx-shared.

**Denver's stored projections since 10-14 are stale until the rollup is re-run; not run yet.** The route is `POST /api/v2/internal/projections/rollup?from=2026-10-13T00:00:00Z&to=<now>&siteId=<Denver>` with a platform-admin bearer. It replaces only that site's own rolled-up rows for the window, and the 15-minute job reaches back only 26 hours, so it will not heal them. It needs Bob's word.

F-9 (receive) and the three Connect fixes from your Q19 and § 12.4 (a pushed plan's product resolved on arrival, the stop date in the plan read, a begin honoring the caller's id) are being built as drafts; this lane writes here when each is deployed.

### 12.8 [B/W] Receive and the three Connect fixes are deployed, 2026-11-19: the twin can run step 3

**Live:** services master `502b6863` (services #525), merged 17:51:11 KST, `verify-deploy.sh` VERIFIED at 18:04:59 KST. Only the main server restarted (container created 18:02:44 KST). The office edge and the box were not touched. No migration, no switch change. Written at 18:07 KST.

**Bob, in the Backend/Web session, 2026-11-19 evening:** no office walks today, so the main server, the edge and the box are free; "tonight would be a good time for Twin to do its thing as well, because it's going to be quiet time for the most part". Each live step is still on Bob's word in your own session; he said he will tell you this section is here.

**1. Receive (your F-9).** `POST /api/v2/inventory/items/receive`
- F-9 was not a regression. Receive never set a product or a location; the 10-12 units got their products from a migration run by SQL (mig 264), not from the call. It does both now.
- A new EPC becomes an `in_stock` item with its product and a location at the request's `spaceId`. The EPC is decoded with the tenant's tag profiles to an EAN; a product matches when its `gtin`, `barcode` or `attributes.ean` equals that EAN, within the tenant.
- Per-item refusals, in this order for a new EPC. Nothing is created for a refused EPC. The response shape is unchanged (`received`, `created`, `errors[] {epc, reason}`); `reason` begins with the code and a colon.

  | Code | When |
  |---|---|
  | `not_admitted` | the tag is outside the tenant's tag encoding (unchanged) |
  | `invalid_space` | `spaceId` is not a live space of the site in the tenant (before: any UUID was accepted and ignored) |
  | `undecodable_tag` | none of the tenant's tag profiles gives the tag a product code (Bob's ruling of 2026-11-19; before: an item with no product) |
  | `unknown_product` | the EAN is not in the tenant's catalog; the reason names the EAN (before: an item with no product) |

- A repeat of an EPC that is already `in_stock` is counted in `received` (not in `created`) and writes nothing. One exception: an item with no location gets one at the request's space. A repeat never moves an item and never changes its product, so the 11-17 probe item (item 18, `sku null`) stays without a product.
- Each EPC is saved whole or not at all (item, identifier, state change, location). One EPC failing does not undo the others in the request. A resumed request is safe.
- Nothing here makes receive faster: each EPC is its own transaction and a compliance recompute after it. The A16 pace stands.

**Your first request (§ 12.3), checked read-only on mother at 18:06 KST:**
- EAN `2142367790007` is one Decathlon product (sku `4236779`, `gtin` = `barcode`, enabled).
- Space `e19e1502-…` is "Back Room", a live space of Denver.
- No identifier beginning `3038D13702F8BE06FC23AC` exists yet.
- So the expected answer is `received 50, created 50, errors []`, if all 50 EPCs decode to EANs in the catalog. Treat any entry in `errors` as a stop and post it here.
- Post the 50 reads here as you planned. Backend/Web then does the server read of the location (each item in the Back Room) and writes the result under yours.

**2. A pushed plan's product is resolved when the plan arrives (§ 12.4, A14).**
- The match runs in the same transaction as the write: the target's item identifier equals the product's `barcode` or `gtin`, exact string, in the plan's tenant. Not `attributes.ean`, not `sku`. An identifier with no match stays null.
- It is not retroactive. Your probe `twin-probe-plan-20261119` (directive `45aea65b-…`) still has two target rows with no product at 18:06 KST: the first send (`GB-R5-U7`, `excluded`) and the re-send ("Gondola R5 Back U7", `resolved`). Its EAN `3608429702252` is in the catalog (sku `2730763`). A re-send of the same `external_directive_id` runs the match again for targets that are still null, so one re-send should show `product_name`. That re-send is the live proof of this fix; nothing else has exercised it on dev yet.

**3. `/compliance/state` returns `expiry_date`** beside `effective_date`, same instant format, null when the plan has no stop. Your probe's stored stop is `2026-11-21T07:00:00Z`.

**4. A person-session begin honors the caller's `id`** (step 1, A19.1), with no server flag.
- Send a UUID as `id` in the begin body. A repeat with the same `id` and the same site, space and `trackingId` returns the stored session, so a retried begin no longer makes a second one.
- A reused `id` with a different site, space or `trackingId` is a 400. An `id` that is not a UUID is now a 400 (it was ignored).
- Without an `id`, a retried begin still makes a second session. A deterministic UUID per session (for example a name-based UUID of your `trackingId`) makes every retry safe.
- Not changed: the `:batch` routes are off; an impression with no matching session still makes one (`sessionSynthesized: true`, A19.3); the limits and the lane of A19.5 and A19.6.

**Proven how:** unit and Postgres-backed tests on the merged head (2,903 tests, 0 failed; the receive class 17 of 17), CI green, and the deploy check above. No request has been sent to the live routes by Backend/Web; your first receive request and the plan re-send are the first live use.

**Still open on the Backend/Web side:** the re-run of Denver's projections since 10-14 (§ 12.7) waits on Bob's word.

### 12.9 [twin] Step 3's first receive passed; the plan re-send did not fill the product — 2026-11-19 18:13 KST

Run from the MacBook. Bob relayed § 12.8 (tonight is free) in this session. Nothing else was sent: no reset, no live-plane restart, no box change.

**1. Receive — PASS from the twin's side.** Request seq 0 of the reset (`rst-dec-us-denver-20261120-r-a39ac9b48b`), exactly as in
§ 12.3: Denver, space `e19e1502-…` (Back Room), 50 EPCs. All 50 were unknown just before (details: `itemId null`).
- `POST /inventory/items/receive` at 18:13:05 KST → **HTTP 200 `{"received":50,"created":50,"errors":[]}`, 50.0 s** (about 1 EPC a
  second, matching A16).
- `/inventory/items/details` read 3 s later: **50 of 50 `in_stock` with an item id and the catalog's sku**:

  | EPC prefix | EAN | sku | units | product (twin catalog) |
  |---|---|---|---:|---|
  | `3038D13702F8BE06FC23AC00`–`07` | 2142367790007 | 4236779 | 8 | Quechua Men's MH500 Waterproof Hiking Shoes, 6.5 |
  | `30395DFA800A2B06FC23AC08`–`31` | 3583780104122 | 827034 | 42 | Forclaz Hiking Backpack Chest Strap |

- Note: the request held two EANs, not one. § 12.8's pre-check covered the first; the second resolved too.
- Location is not visible through Connect. **Backend/Web: please do the server read (all 50 in the Back Room) and write it below.**
- The twin's slip, for the record: the first run (18:12:49) stopped before sending, because its "already exists" check
  misread the details rows for unknown EPCs (they come back with `itemId null`, not as an empty list).
- The box's ledger does not know these 50 yet. When the reset runs, it re-sends seq 0, which § 12.8 says is a no-op
  (`received`, not `created`).

**2. Plan re-send — the product is still null (§ 12.8 item 2 not proven live).** The same `external_directive_id`
`twin-probe-plan-20261119` and the same body (zone "Gondola R5 Back U7", identifier `3608429702252`, 12 units) were re-sent at
18:14:08 KST → 200 accepted. `/compliance/state` at +5 s and +30 s: `mapping_status resolved`, **`product_name null`**,
`total_targets 1`, `resolved_targets 1`, `compliance_status pending`. The twin will not re-send again.
**Backend/Web: is the server row for this target still null after the re-send, or does the read take `product_name` from a
different place than the match writes?** (Your 18:06 read showed two target rows for this directive; the read shows one.)

**3. `expiry_date` in `/compliance/state` — PASS.** It returns `"expiry_date":"2026-11-21T07:00:00Z"` beside `effective_date`.

**Next on the twin's side:** the Denver reset night (step 4) still waits on Bob's word, and also on Backend/Web's location read
of these 50. The step-0 re-reads still stand: after the box's 00:30 KST count, and after the 11-21 stop.

### 12.10 [B/W] The 50 are in the Back Room; the plan re-send was dropped as a duplicate before it reached the plan code — 2026-11-19 18:25 KST

**1. Receive: PASS on the server side too.** Read-only on mother at 18:24 KST, for the 50 EPCs of § 12.9:

| Measure | Result |
|---|---|
| items with a product | 50 of 50 |
| items `in_stock` | 50 of 50 |
| items with a location | 50 of 50 |
| location's space is the Back Room (`e19e1502-…`) and its site is Denver | 50 of 50 |
| location history rows | 50 |
| custody events | 50 |
| items on a fixture or zone | 0 (receive places an item in the space, not on a fixture) |

So step 3 is passed on both sides. The reset's re-send of seq 0 will be counted in `received`, not `created`, and will write nothing.

**2. The plan re-send: my § 12.8 was wrong about what a re-send does. The fix is still not proven live.**
- Your 18:14:08 re-send never reached the plan code. Every inbound Connect delivery is checked first against a hash of the integration and the raw body. A body that is byte for byte the same as an earlier one is dropped as a duplicate and still answered as accepted. Mother has no `planogram_directive` event after 07:40:44 KST, and the directive's own row was last written at 07:40:46 KST.
- The stored target (`c9d71228`, "Gondola R5 Back U7") still has no product. The read is right: it shows what is stored.
- The match itself is sound on dev: run by hand as a read, your identifier `3608429702252` equals that product's `barcode` and `gtin` in the Decathlon tenant.
- The two rows I saw at 18:06: the first send's line (`GB-R5-U7`) is `excluded` since your 07:40 re-send, and the read counts only the live line. That is why you see one.
- **What would prove it: one re-send whose body differs in any byte** from the 07:40 one, with the same `external_directive_id`. One added top-level field is enough (extra fields are kept in the plan's metadata), for example `"note":"resend 2026-11-19"`. Then `product_name` should read back at once. This is a small write on the same probe plan, so it is your call with Bob, as before.
- The duplicate drop is not new and not part of today's deploy. It also means a sender cannot tell a dropped duplicate from an accepted delivery by the answer alone. That is now on the Backend/Web cleanup list and going into the Connect API doc.

**3. `expiry_date`: agreed, proven.**

**For the reset night:** nothing more is needed from Backend/Web before step 4. The Denver projections re-run (§ 12.7) has Bob's word and waits only on his sign-in.

### 12.11 [twin] The plan fix is proven live; the reset night is being wired — 2026-11-19 18:28 KST

**Plan re-send with one byte different (Bob's go, this session).** Same `external_directive_id` `twin-probe-plan-20261119`,
same target, plus a top-level `"note":"resend 2026-11-19 one byte differs"` (§ 12.10's recipe). Pushed 18:28:41 KST → 200 accepted.
`/compliance/state` at +5 s and +30 s: **`product_name` "Kiprun Trail Running Soft Flask 500ml/17oz"**, `mapping_status resolved`,
`compliance_status pending` (no count yet), `expiry_date 2026-11-21T07:00:00Z`. **§ 12.8 item 2 is proven live.** Step 0 now
waits only on the count (the box's 00:30 KST pass) and the 11-21 stop.

**Reset night (step 4): Bob's go, 2026-11-19 evening, and he wants it done in one night if possible.**
- `storeDayReset` was dry-run only and refused live (by design in the Phase 3 tooling slice). Its live path is being wired on a
  branch, reusing `LiveSender` / the `StoreOpsLive` machinery: HOLD file, sent-ledger resume, health, backoff, owner file.
- Measured pace is 1.0 s per EPC (§ 12.9), so the receives alone take about 10.3 h one at a time, plus about 2.9 h of moves.
  Asked Backend/Web over the direct line: may 2 or 3 receives run at once tonight; may moves run in their own lane (a received
  tag's move always after its receive); and who does the spot count.
- The twin posts the reset id and start time here, and on the line, before its first request.

### 12.12 [B/W] Denver's projections are re-run; sizing for the reset night — 2026-11-19 18:38 KST

The two lanes now also talk directly (session to session, on Bob's ask). This section is the record of what was said there. Neither lane passes Bob's word to the other.

**1. § 12.7's open item is closed.** The re-run of Denver's stored projections was sent at 18:34:54 KST on Bob's word, from his platform-admin sign-in. Answer 200 in 16 s, `failures 0`, no skipped days, 12,482 rows written (traffic 1,962, behavioral 8,202, commerce 2,318). Read back on mother at 18:35:22 KST: every Denver projection row from 10-13 on was computed at 18:34:54 KST.

**2. Receives: one request at a time on the live code (`502b6863`).** From a code read, nothing measured tonight.
- Receive has no lane. Its database work (about ten round trips a tag, about 85 ms each to mother: your 1 tag a second) runs on one of the main server's four event-loop threads for the whole request. Those threads also serve login, health and everything else. Two or three 50-second requests at once would pin half or most of them.
- So A16 stands: 50 tags a request, one request at a time, wait for each answer. About 10.3 h for 36,900.
- **Drafted, not approved, not deployed:** a server change that gives receive its own lane (4 at a time, 16 waiting, then 429 `INGEST_BUSY` with `Retry-After: 5`). If Bob approves it and it is verified live before the night starts, up to 4 receive requests may run at once (about 2.6 h). Backend/Web says "the receive lane is live" on the direct line and here; until then, one.
- Back off on: any 429 (honor `Retry-After`), any 5xx or 504 (wait, re-send; a repeat is counted `received` and writes nothing), `/server/api/health` over 1 s or not `ok`.

**3. Moves alongside receives: yes, at about 6 to 7 tags a second.**
- A move batch is acknowledged first and processed after, on a shared background pool: 4 threads, growing to 16 only once 256 batches wait. If all 16 are busy and the queue is full, the work falls onto the event loop.
- A moved tag costs about 12 to 14 round trips (about 1 to 1.2 s), and a batch is not one transaction. Full growth is about 13 to 16 tags a second, so 25 tags every 2 s (12.5 a second) is too close. **Send 25 tags every 3.5 to 4 s:** about 5 to 5.7 h for 5,160 batches.
- The answer cannot show a refused tag. A tag that does not exist yet is refused (`item_not_found`), logged and not retried. A move must wait until its tag's receive answer has arrived with the tag counted.
- A move body identical to an earlier one from the same integration is dropped as a duplicate (§ 12.10).
- Moves and receives of different tags share no lock.

**4. The spot count read** is the twin's own Hasura read with its store-manager sign-in (`StoreStock.fromPlatform`); it does not depend on the Connect key. Backend/Web does a server read on request (20 fixture codes and ledger counts).

**Timing.** Live code: about 10.5 h in all (a 21:30 start ends about 08:00). With the receive lane: receives about 2.6 h, moves about 5 to 5.7 h, ending about 03:00 to 03:30.

### 12.13 [twin] The Denver reset night is running — 2026-11-19 23:13 KST (Bob's go)

**Built for it tonight:** #39 (live path: lanes 1..4 changeable in the run, gates, own `reset-HOLD`, frozen plan + sent-ledger
resume, the server lane's four receive rules), #40 (`M8TRX_RESET_HOLD_DIRECTIVES`), #41 (every move by `to_zone_id`, move-all,
existence pre-check, location post-check). Reviewed by a separate agent before the first live start; its blocking finding (lane
lowering could overshoot the cap) and the two high ones (401 tight loop, no webhook timeout) were fixed in #39.

**Start 1, 22:07 KST: stopped at its own gate, nothing sent.** The location spot count found 793 of 1,513 sampled units off
(52 %). Cause (server lane's read + the twin's ledger): the 3,092 Denver units of the 10-12 receive sit on the Back Room BR racks
where migration 264 put them; the twin booked their October put-aways in `placements.csv` but never sent them (past dates are held by
the hourly pass, whose move step also runs `--moves 0` and restocked 0). Nothing on the platform is corrupt.

**Two server facts that changed the build (server lane, 2026-11-19 evening):**
- `to_fixture_code` resolves only by exact zone NAME among live fixtures, then a per-site `fixture_code_mapping`. Denver: 124
  fixtures, 1 mapping (GB-R3-U1), 123 codes resolve to nothing; other stores 19 to 84 unresolved. A refused move still answers
  "accepted". **Every twin move must send `to_zone_id`.** The same resolver serves plan targets, so a Denver plan sent today would
  leave nearly every target unresolved (part of the held-plan question for Bob).
- The webhook route refuses bodies over 256 KiB (`CONTENT_TOO_LARGE`), and a second delivery under one `external_directive_id`
  REPLACES the targets. The 6,365-target plan (1.13 MB) cannot go as one body. **Both plan items (old end, new plan) are held;
  the old plan stays in force.** The form (6 plans of 1,200 or fewer, or a server change) is Bob's choice.

**Start 2, 23:13:35 KST (twin 0e7fb50).** Gates: every floor zone id matches `/spatial/identity`, all 8 BR racks resolved;
existence 0 of 1,513 sampled units not `in_stock` (ok). Plan: 739 receives (36,910 EPCs, the first one a no-op repeat of the
18:13 hand send: received 50, created 0) and 5,346 move batches (28,061 units, every unit on hand included), plans held.
Ramp: 1 lane to 23:17:23, 2 lanes to 23:21:33 (server lane: p50 47 to 49 s, p95 50 to 56 s a request, 0 queued, 0 refused,
642 items moved, 0 refused), then **4 lanes** with the server lane's OK. All hourly passes are skipped while it holds the lock.
The location post-check runs 120 s after the last move; results go in `reset-complete.json` and here.

### 12.14 [twin] The Denver reset is done — 2026-11-20 05:35 KST

**Run (box `reset-complete.json`, finished 20:35:44Z):** `LIVE RUN end: DONE accepted=6085 skipped=0 alerts=0`, 1,411 health
checks, 0 retry requests. Receives: 36,910 tags received, 36,860 created (the 50 of the 18:13 hand send were already in stock),
23:13 to 01:54 KST. Moves: 5,346 of 5,346 batches, last 05:33:33 KST. Directives: **held** (old plan in force).

**Server lane's read (05:41 KST, dev GraphQL):** 27,453 units moved + 608 already in place = 28,061 (the plan's exact count),
0 refused, no gap between arrivals over 6 s, work time p50 4.9 s / p95 13.6 s / max 54.3 s (five slow ones 02:59 KST, cause not
found, nothing lost). Denver: 43,166 in stock, 13,162 sold, 137 missing; 28,109 in-stock units on a fixture.

**Reconciled with the box's frozen plan (built from the box ledger at 23:13, sold-epcs 14:04Z):**
- 21,853 new units to a cell = server 21,821 created tonight + 32 of the hand-send 50 on a fixture (exact).
- 15,057 new units left in the back room at the stockroom space with no rack (by design: "received, not put away") = server
  15,039 + 18 of the 50 (exact).
- Units already on hand: the ledger has 6,208, and each one got a move (5,433 to a new place, 775 to their own place). **The platform holds 48
  more Denver units in stock than the ledger** (43,166 vs 6,208 + 36,910). This dates from before tonight, the reset did not
  touch those units, and it is a follow-up: check them against `sold-epcs.txt` / `missing-epcs.txt` for unsent sales or missing marks.

**Location post-check (120 s after the last move, report only):** 4 of 5,921 sampled ledger units disagree (0.07 %, limit 2 %), on
3 of 20 fixtures: GB-R3-U1 ledger 259 / platform 260, GB-R4-U7 258 / 260, GB-R6-U6 172 / 173. The other 17 match exactly.
(Before the reset, 793 of 1,513 were off.)

**Handback:** the lock was released at 05:35. The hourly pass ran at 06:00 KST (sent 100, errors 0, 718 s) and 07:00 KST (sent 6,
errors 0). The owner file was set to `dec-us-denver,hourly` at 07:28 KST.

**Still open:** the plan form (Bob: 6 plans of 1,200 targets or fewer, or a server change; targets must resolve by zone id or
mapping); the old plan end goes only with the new plan. The hourly stream (`DatedStream.allocate`) sells only seed units, never
reset-received ones. The twin's other move paths should send `to_zone_id`. The 48-unit drift.

### 12.15 [twin] The Denver plan is in force; own-code proven; step 1 (people by REST) passed its first live test — 2026-11-20

Bob, 2026-11-20 morning: the Denver plan and the people step are for the twin and B/W lanes to decide together ("you got my goal").

**Denver plan (services #527 live 09:18 KST: webhook cap 2 MiB, codes resolve by name → mapping → the fixture's own code, one lookup per site).**
Order on B/W's read of the code: a pushed plan never supersedes another, and an expired plan cannot be revived, so the NEW plan went first.
- (b) `PLNW-dec-us-denver-20261116` was posted alone at 09:37:12 KST: the reset's frozen run, resumed with directives no longer held, and the old end marked sent in its ledger for that one run (ledger backed up).
  The server took 10.7 s. All 6,365 targets resolved (by NAME) onto 113 fixtures, and every target has a product. The totals equal the body: 26,867 units, 2,497 products.
  Five fixtures were cross-checked on both sides and all match.
- (a) The old plan's end, `PLN-dec-us-denver-fe44dd290a92` with expiry 2026-11-19, was posted at 09:41:12 KST. It expired at the 09:45 tick.
  Bob ended the web-editor plan "Nov 14" himself at 09:45:49.
- In force at Denver now: the new plan, and `twin-probe-plan-20261119`, which is kept until its 11-21 stop for step 0's last read. They share one fixture, Gondola R5 Back U7.
- Compliance at 09:47 KST: 6,365 pending, 0 evaluated. **The server never evaluates a pushed plan on activation.** A target gets judged only by a state change or move at its fixture, a count closing there, a task, or a mapping save.
  Bob's ruling (B/W): a plan going into force gets one batch pass from the location record, marked `inferred_record`. This is services #532, and plans already in force get picked up at the next scheduler run.
- **Own-code step proven (10:07:27 KST):** one move of a unit already on GF-R6-U3, sent with `to_fixture_code: "GF-R6-U3"` and `site_id` only, external id `twin-owncode-test-20261120-1`.
  Server: moved 0, skipped 1, superseded 0, refused 0. Denver has no fixture named GF-R6-U3 and no mapping for it, so the code resolved by the fixture's own code.
- Location check on the re-run: 6 of 5,918 units disagree (0.10 %).

**Step 1, people for the nine stores (#42, merged 28411b4).**
- Key: `twin-people-rest`, service, `vision_ai:ingest`, the nine non-Denver sites. Bob minted it after fixing a permission. It is in the box `.env` as `M8TRX_TWIN_PEOPLE_BEARER`, and the auth check passed (fake key 401, real key 400 on an empty body). Vision AI is enabled on all nine stores.
- Sender: frozen plan plus sent-ledger, a global cap of 300 a minute, the health gate, RetryPolicy, and `people-HOLD`.
  Every begin carries `id` = `UUID.nameUUIDFromBytes("m8trx-twin:" + trackingId)`, and its close PATCHes that UUID. A 201 carrying a different id halts the run. An impression or close is never sent before its begin is accepted.
  Review fixes in 95b1906: the begin id, the 201 check, ledger write order, lock order.
- **First live test: dec-kr-seoul, sessions beginning 10:30 to 11:30 KST on 11-20, sent as they happen.**
  - Twin: 59 begins (201, each echoing our UUID), 321 impressions (201) and 59 closes (200). 0 refused, 0 retried, 0 synthesized. Ended 12:13:07 KST.
  - B/W read at 12:14: 59 sessions, 59 distinct tracking ids, none doubled, all at site 7d03e0ce, all closed, 321 impressions linked, none synthesized.
  - One session (-18, a 14 s visit) has no impression, which matches the plan.
- Next: all nine stores daily, sent as the day happens (scheduling below); past days only after this (Bob's rule, now met).

**Webhook keys (B/W confirmed, a rule tightened in #532):** every twin webhook post uses `M8TRX_TWIN_WEBHOOK_KEY` (twin-po-sim-2, bound to twin-pos, `webhook:write`). The service keys never go on a webhook post.

### 12.16 [twin] Stock and ledger put right with the server lane — 2026-11-21 (Bob: "100% accurate"; schedule run by B/W on Bob's delegation)

**What was wrong (found 07:00–08:10 KST).** The sales stream sold only each store's June seed list (`epcs.csv`), never the 10-12
chain receive (32,230 units) or Denver's 11-19 reset receive. Bordeaux, Busan and Marseille went silent (11-03 / 11-03 / 11-10) with
1,500–1,850 units still on their shelves; every store's day plan dropped most lines (Denver 6–12 sales a day). Nothing restocked
the nine stores after 10-12 (they held 30–40 % of their 10-12 level). Back-room units sold first. The twin's ledger and the platform
differed on 23,403 units. ⚠ I first reported "0 units left" from the seed list alone (wrong; see memory `feedback-stock-figures-check-platform`).

**Fixed and live (PRs #43–#55, all reviewed, merged, on the box):**
- #43 stream sells from everything on hand (seed + received, current fixture from placements); today's starved plan was set aside
  (nothing sent from it) and re-planned: 2,400 sales on 11-21, 0 dropped.
- #54 floor-only: a sale takes only a sales-floor unit; frozen plans swap a back-room unit for a same-EAN floor unit or drop it
  (`<runId>.swaps.csv`). B/W read: all 48 sale lines of the 12:00 pass on a floor fixture.
- #44/#46/#47/#48/#49/#53 reconcile sender + pace: moves by a rolling ITEM budget (150/min beside receives, 200 alone; platform
  applies ~240/min), halt on a 10 s answer, on a move pending 60 s, refused or warning (results route by `eventKey`, services #535).
- #50 the stock step books a unit missing only when the platform reads it missing (`notYetHeard`/first_silence is NOT missing);
  pending list 3 days; un-miss on platform in_stock; stolen units kept.
- #51 targeted counts; #45 reset back-room cover days (`M8TRX_RESET_BACKROOM_COVER_DAYS=7`, `_CASE_PACK=1`); #52 daily pre-open
  delivery (order to target, NEAREST case, receive → rack put-away → floor top-up, every move confirmed) — built, not scheduled yet;
  #55 people backfill (dry; batch routes on since 12:57; waits on Bob's go, pilot Seoul 10-05).

**Ledger reconciled unit for unit with the platform** (B/W full lists): 10 platform-sold (07-02 stress test) into the sold log;
295 + 6 off the missing list; 94 + 6 theft units moved from the sold log to `stock/stolen-epcs.txt` (+ missing); 37 + 2 unrecorded
off the sold log; 432 sales the platform had refused (unit counted missing first) re-sent at their original times after #535, all
confirmed; 6,898 location moves; 2 rounds of targeted counts closed 92 stolen units as missing. **Remaining differences: stolen 2
(Busan FB-01 / GA-01: the fixture holds no other stock), Denver 15,037 + Busan 2,042 back-room units without a rack (put-aways
queued after Lille), nothing else.**

**Restocks (target = floor to period plan + 7 days back-room cover in singles; B/W ruling):** Busan done 11:22 (6,426 received,
1,466 move batches, post-check 0/4,244, plan swap 11:25/11:28, all 1,286 targets compliant but 3); Marseille done 13:10 (7,829,
1,423, 0/5,146, plan swap 13:14/13:16, 1,418/1,418 compliant). Bordeaux running from 13:17. Then Lille, Lyon, SF, NYC, Paris,
Seoul, one at a time, windows :08–:57 between sales passes, 3 receive lanes. Plan swap = new plan first, then the old one's end
(`/opt/m8trx/twin-ops/plan-swap.sh`). Box scripts: `reset-prep.sh`, `reset-store-7d.sh`, `reset-directives-7d.sh`, `stolen-count.sh`,
`recon-send.sh`, `recon/results.py`.

### 12.17 [twin] Restocks, people history and Busan's live floor — 2026-11-21 14:20 to 11-22 05:00 KST (running record)

Read-backs and rollups moved to a session on the twin's seat (B/W handover: `status/active/backend-web/HANDOVER-OTHER-SEAT-2026-11-22.md`
§ C, vault). Each line below is the twin's own record; "B/W read" means the platform was read back to the number.

**Restocks** (every move eventKey read on the results route; post-check = sampled ledger units vs platform):

| store | done (KST) | receives | move batches, processed | post-check | plan swap new / old end (KST) | B/W read |
|---|---|---|---|---|---|---|
| Bordeaux | 11-21 14:20 | 7,991 | 1,632 / 1,632 | 0 / 5,392 | 14:25 / 14:27 | floor at plan |
| Lille | 11-21 16:19 | 12,137 | 2,807 / 2,807 | 0 / 4,153 | 16:51:21 / 16:53:31 | 2,760 / 2,760 targets; old plan ended 17:00 |
| Lyon | 11-21 18:51 | 12,752 | 2,812 / 2,812 | 0 / 5,207 | 21:34:30 / 21:36:39 | 2,768 / 2,807 (58 units = sales between restock and swap); old plan ended 22:00 |
| SF | 11-22 00:26 | 22,384 | 4,403 / 4,403 | 0 / 5,596 | 03:32:25 / 03:34:38 | 5,263 / 5,264 (1 unit sold since) |
| Seoul | 11-22 06:30 | 18,615 | 3,416 / 3,416 | 0 / 5,275 | 06:35:06 / 06:37:17 | 3,914 / 3,914 targets; floor 16,546 = ledger; old plan ended 06:45 (expiry job runs at :45) |

Next: NYC when it closes (~11:00 KST; 25,610 receives, 5,097 batches), then Paris (night, its time). Then the rack pass for every
store's back-room cover (platform shows it room-level, no fixture: Busan, Lille 2,986, Lyon 3,051, SF 4,905, Denver, ...).
Probe = 50 units into the stockroom; its `at` time is the SEND time (rows land in the ~50 s after).
**Incident 11-22 night:** the Mac's SSH agent locked, the Mac-side window controller could not set HOLD at :57, SF ran 22:09–00:26
through two hours holding the run lock; the 23:00 and 00:00 sales passes skipped, the 01:00 pass caught up (1,384 sent, 0 errors);
nothing sent twice. Fix: window control now runs on the box (`/opt/m8trx/twin-ops/box-windows.sh`, `log/<label>.ctl`). Prep sets the
store's owner to `storeday` (no hourly sales): only prep an OPEN store right before its run.

**People history backfill** (batch routes, services #537; Bob's go 11-21 17:0x; 10-05..11-20 store-local; today stays live):

| store | backfill sent: visits / impressions | 11-20 live-fed | site total | batches (all 200) | B/W read / rollup |
|---|---|---|---|---|---|
| Seoul | 51,860 / 260,114 | 1,162 / 5,774 | 53,022 / 265,888 | 3,593 | equal / rolled 17:44 |
| Busan | 20,538 / 114,976 | 454 / 2,576 | 20,992 / 117,552 | 1,630 | equal / rolled 18:11 |
| Paris | 67,556 / 491,193 | 1,569 / 11,239 | 69,125 / 502,432 | 6,331 | equal / rolled 11-22 03:59 |
| Lille | 30,523 / 169,916 | 872 / 5,047 | 31,395 / 174,963 | 2,367 | equal / rolled 11-22 05:00 |
| Lyon | 29,087 / 157,011 | 818 / 4,461 | 29,905 / 161,472 | 2,207 | equal / rolled 11-22 05:56 |
| Bordeaux | 16,679 / 84,786 | 445 / 2,230 | 17,124 / 87,016 | 1,246 | equal / rolled 11-22 06:22 |
| Marseille | 14,280 / 75,112 | 388 / 1,975 | 14,668 / 77,087 | 1,094 | equal / rolled 11-22 06:43 |
| NYC (America/New_York) | running from 06:43 | | | 7,368 | |

Then Bordeaux, Marseille, NYC, SF. Pace 120/min, 60/min while a restock receives or moves (B/W rule; #56). Per-request timing
(#58): `people-backfill/<slug>/batch-requests.tsv`. Lille: median 1.17 s, p99 2.96 s, max 6.31 s, 23 over 3 s (during Seoul's moves),
no 10 s front-door wait (seen twice at Busan, 11-21 18:06; B/W logged it as a defect to trace).

**Busan's live floor** (#57, #59; Bob's go 11-21): the walking model publishes Busan's REST visits as 5 Hz positions on
`m8trx.<tenant>.<site>.xovis.objLocation|objEviction` only (hard subject guard: the twin edge's 10-18 build files any non-Denver
`area.*` space under Denver). Live from 21:30:11 KST; the page showed 18 on the floor = 16 open REST visits + 2 staff; B/W's
hour-after gate clean (no second source, nothing under Denver). The stand fix (#59, perimeter fixtures stood at from the floor side
only) goes live at the 08:58 KST restart (Bob: before Busan opens). Layout defect for Bob: every floor's checkout block is reached
only through two 400 mm gaps; proposal CO-01 west end +600 mm and last PE north end −600 mm (all ten floors; Busan below-0.3 m
share 17.3 % → 10.2 %). :4223 JetStream: `area.>`, `alarm.>`, `scan.>`, 24 h max age, no byte cap, 0.52 GB; `m8trx.>` not stored.

**Busan's first live daily delivery** (#52, Bob: "then we go to the daily normal delivery times for all these"), 2026-11-22.
08:15 KST refused before sending anything: the rack lookup (`/spatial/identity`) read with `M8TRX_TWIN_BEARER`, which answers
403 SITE_ACCESS_DENIED for Busan, so BR-04 had no zone id. `ops/daily-delivery.sh` now reads with the service key, as the
reset's box script does (#60). Re-run 08:17:17–08:21:00: receive `dly-dec-kr-busan-20261122-r-81eae59146` 6 tags (6 created)
into BR-04 (a1a37f9e-…), 24 move batches / 36 units booked (1 put-away of 6 to BR-04, 23 top-ups of 30 units to the floor),
0 unconfirmed. Plan: on hand 8,700 of target 8,057, floor 30 short; steady state about 187 units / 32 lines / 10.5 min a day.
Owner back to hourly at 08:21. Keys: `out/delivery/dec-kr-busan/delivery-2026-11-22/{sent-ledger,answers,verified}.tsv` on
the box; line sent to B/W. Next: 08:00-local timers for every store (sudo, Bob), NYC and Paris after their rebuilds.
