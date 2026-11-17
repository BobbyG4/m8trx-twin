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
