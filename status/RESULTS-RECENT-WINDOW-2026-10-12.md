# RESULTS — Twin recent window, round 2 (BRIEF-TWIN-RECENT-WINDOW-AND-DAILY-DRIVE-2026-10-12)

**Lane:** Twin · **Branch:** `feat/recent-window-1012` off `feat/history-generator-2026-10` (draft PR #17), pushed, no PR · **Date:** 2026-10-12 (KST; stamps below are from the clock or the server's ack).
**Posture:** public APIs only. No psql, no Hasura admin, no core repo touched. The one live write this round is the nine planogram directives, sent through the webhook plane (`twin-pos`, `X-API-Key` = `M8TRX_TWIN_WEBHOOK_KEY`, the `twin-po-sim-2` key). That is the same contract Denver's plan used in S12. Everything else was dry-run only (`*_LIVE` unset). **Deletions: none.**

## 1. What was built

| § | Piece | Where | Default (= the behavior before this round) |
|---|---|---|---|
| 1.1 | `DayDrive` epoch mode, `M8TRX_DAY_EPOCH_MODE=plan\|wall`. In `plan` mode the wire `ts` is the sample's own planned `tsMs`. Pacing is unchanged: still wall-clock, still gap-only compression. The timeline builder moved into a pure `buildDayTimeline()` plus `wireTs()` so the self-test can reach it. Evictions in plan mode are stamped 1 s after the shopper's last planned sample. A **future-stamp guard** refuses a plan-mode run when any stamp would be published more than 5 min ahead of the clock, unless `M8TRX_DAY_ALLOW_FUTURE=true`. A compress-1 slice that starts in the past passes the guard (lead "none"), and a compressed run of today is held. Every run prints a `SIM-RUN day …` line. | `layer3/DayDrive.kt` | `wall` |
| 1.2 | **Dated sale stream**, `M8TRX_STREAM_DATE` + `M8TRX_STREAM_SEED`. One store-day comes from `RecentDay`, which reuses the history generator's own `plan()` + `HistoryDayRunner.run()`. The default seed is the history's (20261016), so the live days continue the backfilled year. Details: one sale event per till transaction (`epc` for 1 unit, `epc_list` otherwise). `occurred_at` is the shopper's exit time, clamped inside hours. Each EPC sells once. Back-of-house units go first. A plan is resumable: `.twin-state/stream/<runId>.plan.csv` + a `.sent` ledger. Future-dated events are held. Pacing is 120 ms (≤ 8/s, under the measured ≤ 12 ceiling), and 5 consecutive refusals stop the run. In chain mode each store-day also gets one restock (what sold, in cases of 6), 2 pricings and 1 catalog. **Opt-in live: `M8TRX_STREAM_LIVE=true`.** | `connect/DatedStream.kt`, `history/RecentDay.kt`, hooks in `SaleStream.kt` / `ChainActivityStream.kt` | dated mode off; undated drivers unchanged |
| 1.2 | `HistoryDayRunner.DayResult.txns`: each till transaction stamped at the shopper's `CustomerExited`, because the journey publishes `SaleCompleted` at its arrival instant. **History output is byte-identical**: a 7-day × 2-store `historyGenerate` on the base commit and on this branch gives the same sha256 for all ten data files; only MANIFEST's `generated_at`/`git_sha` differ. | `history/HistoryDayRunner.kt` | — |
| 1.3 | **Alarm day mode**, `M8TRX_ALARM_DATE`. The alarms are the day's own `EasGateCrossed`/`shouldAlarm` from the same `RecentDay` (same shoppers as the stream). `occurred_at` is the crossing time (→ `created_at` once core honors it). `zone_ref` is the store's gate code (`CS-01`, from its layout). The subject is a real floor EPC of the concealed EAN, never one sold or reserved. `M8TRX_ALARM_SCRIPT=dec-us-denver@14:07` adds a scripted alarm, and `day.sh` sets it automatically for 2026-10-13. Resumable with a ledger. A sent alarm's EPC goes to the sold-log, so a stolen unit is never sold later. | `connect/AlarmDay.kt`, hook in `ConnectAlarmDrive.kt` | acceptance chain unchanged |
| 2 | **Ten-store plans.** `M8TRX_PLANOGRAM_STORES=all\|csv`, `M8TRX_PLANOGRAM_GRAIN=curated` (28 lines), `M8TRX_PLANOGRAM_REF_DATE=yyyymmdd` → ref `PLN-<slug>-<yyyymmdd>`. The run refuses before sending anything if any two refs collide, stops at the first refused store, and reads back over `POST /compliance/state` after each accepted send. `PlanogramDirectiveDriver.curate()` is pure and deterministic. Walls are ordered department-interleaved and each takes one line in turn, so a store gets **one line per wall while walls last**. Bordeaux, Marseille (17 walls) and Busan (14) take a second line on some walls to reach 28. | `connect/ConnectPlanogramDrive.kt`, `connect/sim/PlanogramDirectiveDriver.kt` | Denver only, full grain, doc ref, dry-run |
| 3 | `scripts/demo/day.sh` runs the daily sequence. Each step has its own `--live-*` flag, and `--all-live` sets them all. A step can run alone (`--steps`). `--slice FROM-TO --compress 1` gives the live moving-dots window, and `--verify` runs `connectAcceptance` + `impressionVerify`. A live run appends one line per step to `status/SIM-RUN-LOG.md`. A dry run prints the lines it would append and writes nothing. The script sources `.env` and uses `--no-daemon`. | `scripts/demo/day.sh` | all steps dry |
| 4 | `connectMovementDrive` takes **N moves per store**: `M8TRX_MOVEMENT_PER_STORE=N`. Each move takes 2–6 back-of-house units of one SKU on the curated plan and moves them to the plan's wall for that SKU. Other walls of the full plan are used once the curated walls run out. Walls are distinct per store-day. The external id is `MV-<slug>-<yyyymmdd>-<k>`. The destination is the fixture code (default), or the zone UUID from the key export with `M8TRX_MOVEMENT_TARGET=zone`. | `connect/ConnectMovementDrive.kt` | single hand-staged move unchanged |

## 2. The plan send — per store (LIVE, 2026-10-12 05:26–05:27 KST)

`connectPlanogramDrive` with `M8TRX_PLANOGRAM_LIVE=true M8TRX_PLANOGRAM_GRAIN=curated M8TRX_PLANOGRAM_REF_DATE=20261012 M8TRX_PLANOGRAM_AUTH=api_key`. NYC went first, alone. It was accepted, so the other eight followed. Denver was not sent: it keeps its S12 directive, `a3b2bbde`.

| store | site_id | external ref | targets | walls | departments | ack | server `receivedAt` |
|---|---|---|---:|---:|---|---|---|
| dec-us-nyc | 62f95632-2937-42ef-b8a5-8308658c5f61 | `PLN-dec-us-nyc-20261012` | 28 | 28 | climb, cycling, general, hike_camp, running, snow, water | 200 accepted | 2026-10-11T20:26:28.092Z |
| dec-us-sf | 2e86875a-007b-41b7-b84e-b44233999255 | `PLN-dec-us-sf-20261012` | 28 | 28 | climb, cycling, general, hike_camp, running, snow | 200 accepted | 2026-10-11T20:26:59.433Z |
| dec-fr-paris | a6cc38a2-296d-4a4a-8ee3-236d3c44faf1 | `PLN-dec-fr-paris-20261012` | 28 | 28 | climb, cycling, general, hike_camp, running, snow, water | 200 accepted | 2026-10-11T20:27:01.702Z |
| dec-fr-lyon | 88456c3c-f18a-4c27-9ccb-03a30b22435c | `PLN-dec-fr-lyon-20261012` | 28 | 28 | climb, general, hike_camp, running, snow | 200 accepted | 2026-10-11T20:27:03.752Z |
| dec-fr-marseille | 44c835d1-716a-4e3b-bfe3-d6616cddbc4c | `PLN-dec-fr-marseille-20261012` | 28 | 17 | general, hike_camp, running | 200 accepted | 2026-10-11T20:27:05.846Z |
| dec-fr-bordeaux | 02e75ed0-39e2-4329-9961-d0aa49609618 | `PLN-dec-fr-bordeaux-20261012` | 28 | 17 | general, hike_camp, running | 200 accepted | 2026-10-11T20:27:07.906Z |
| dec-fr-lille | ac27b297-c2a2-4b0a-86ba-0ba9c45cede8 | `PLN-dec-fr-lille-20261012` | 28 | 28 | climb, general, hike_camp, running | 200 accepted | 2026-10-11T20:27:09.967Z |
| dec-kr-seoul | 7d03e0ce-7b4e-4659-9d49-4bdd2daf21c2 | `PLN-dec-kr-seoul-20261012` | 28 | 28 | climb, general, hike_camp, running, snow | 200 accepted | 2026-10-11T20:27:12.021Z |
| dec-kr-busan | cc759cc1-f8ad-4ed3-8b2f-a74c39a02fa7 | `PLN-dec-kr-busan-20261012` | 28 | 14 | general, hike_camp | 200 accepted | 2026-10-11T20:27:14.085Z |

**⚠ No directive ids: the ack does not return one, and twin cannot read one back.** Every ack is the bare webhook receipt, `{"accepted":true,"integrationId":"5dfba5cd-…","receivedAt":…}`, because ingest is async. The documented read-back, `POST /compliance/state {directive_ref}`, returned **`403 SITE_ACCESS_DENIED`** for all nine. Twin's Bearer (`twin-s280-lockdown`) is Denver-scoped. **Whether each directive landed is therefore UNPROVEN from twin's side.** Resolving ref → id, and checking that ingest did not dead-letter, needs the Coordinator. Twin did not run this query and cannot:
`SELECT id, external_directive_id, status FROM compliance_directive WHERE external_directive_id LIKE 'PLN-%-20261012';` (expect 9 rows, 28 `compliance_target` each). Then `/evaluate` per id with the tenant-admin JWT. `/evaluate` was not called from here.

**On the grain (Bob's ruling pending):** the brief describes Denver's plan as "one line per department wall", but Denver's S12 plan is 28 lines on ONE fixture (`GB-R3-U1`). I implemented the brief's description: 28 lines spread over the department walls. The choice is reversible. A re-send under the same ref rebuilds that directive's targets (`IntegrationIngesters.kt:583`, per the brief), so a different grain can replace these without creating new directives. Mig 259 § 3f needs at most 17 targets per store (Denver/NYC `non_compliant` max), and 28 covers every store.

**Fixture resolution:** every curated `raw_fixture_code` is in the key export. Whether each resolves to a zone depends on `fixture_code_mapping` for that store, which is outside twin's view. The resolved/unresolved count per store will appear in the Coordinator's `/evaluate`.

## 3. Self-test output

`./gradlew ktlintCheck --rerun-tasks` → exit 0.
`./gradlew scenarioSelfTest peopleSelfTest connectSelfTest historySelfTest` (new lines plus the four summaries, verbatim):

```
c.m8trx.twin.layer3.ScenarioSelfTest -   ✓ a plan-mode day's first wire ts equals the plan's first ts
c.m8trx.twin.layer3.ScenarioSelfTest -   ✓ wall mode (the default) still starts at the publish epoch
c.m8trx.twin.layer3.ScenarioSelfTest -   ✓ plan-mode stamps fall on the plan's date (Denver store hours, 10:00..21:00 local)
c.m8trx.twin.layer3.ScenarioSelfTest -   ✓ plan-mode intra-episode stamp spacing stays real time (<= 1000ms)
c.m8trx.twin.layer3.ScenarioSelfTest -   ✓ epoch mode parses wall|plan and defaults to wall
c.m8trx.twin.layer3.ScenarioSelfTest - scenarioSelfTest — 42 passed, 0 failed
c.m8trx.twin.layer1.PeopleSelfTest - peopleSelfTest — 50 passed, 0 failed
c.m8trx.twin.connect.ConnectHarness - [recent] ten-store plans: 10 distinct refs, 28 curated lines each, every department wall covered ✓
c.m8trx.twin.connect.ConnectHarness - [recent] dated sale: 205 txn / 466 units on 2026-10-06 (BOH 184, substituted 162), first occurred_at 2026-10-06T16:12:54.303Z ✓
c.m8trx.twin.connect.ConnectHarness - [recent] alarm day envelope: occurred_at = event time, zone_ref = gate ✓
c.m8trx.twin.connect.ConnectHarness - [recent] multi-store moves: 30 moves, distinct ids/walls/units ✓
c.m8trx.twin.connect.ConnectHarness - === ALL OFFLINE SELF-TESTS PASSED ===
c.m8trx.twin.history.HistorySelfTest - historySelfTest — 311 passed, 0 failed
BUILD SUCCESSFUL in 5m 39s
```

What the new connect checks assert:
- **Plans:** the ten refs are distinct and shaped `PLN-<slug>-20261012`, and a new plan date gives a new ref. Each store gets 28 lines, all on department walls, with distinct walls = min(28, walls in plan). Every department is covered, there are no duplicate (wall, SKU) targets, and the selection is deterministic.
- **Dated sale:** the serialized `sale_event` carries `"occurred_at":"<the transaction's own time>"`. All sales fall on the date inside 10:00–21:00 local and spread across ≥ 10 distinct hours. Each EPC sells once, no line is dropped, BOH units go first, ids are unique, the wire is snake_case, allocation is deterministic, and an excluded EPC never sells.
- **Alarm:** `occurred_at` is the event time, `zone_ref` is the gate, and the dedupe key is `gate:epc:millis`.
- **Moves:** 30 moves, distinct ids, walls and units, 2–6 units per move, every wall has a zone id, deterministic.

## 4. Dry runs

**`day.sh --date 2026-10-10` (all steps dry; a past Saturday, so nothing held), 4m25s:**

| step | result |
|---|---|
| stream | 2,421 sales + 40 ops across 10 stores; would take 4m55s at 120 ms |
| day | Denver plan mode: 2,294,611 samples · 1,430 shoppers · 7,396 expected impressions · stamps 16:00Z → 03:09Z (10:00–21:09 MDT) · ~46m56s wall at 18× |
| alarm | 2 alarms: Seoul 20:12 local, Denver 18:36 local |
| move | 30 moves, 74 units |
| stock / tryon | SKIPPED, not built this round |

Per store, stream on 2026-10-10:

| store | txn | units | from BOH | substituted lines | visits | units left → runway (days at this rate) |
|---|---:|---:|---:|---:|---:|---|
| dec-us-denver | 347 | 760 | 270 | 349 | 1,448 | 14,066 → 18.5 |
| dec-us-nyc | 306 | 686 | 252 | 305 | 1,387 | 13,913 → 20.3 |
| dec-us-sf | 351 | 770 | 290 | 357 | 1,444 | 13,768 → 17.9 |
| dec-fr-paris | 304 | 668 | 266 | 279 | 1,372 | 14,223 → 21.3 |
| dec-fr-lyon | 202 | 445 | 160 | 161 | 871 | 9,176 → 20.6 |
| dec-fr-marseille | 119 | 268 | 71 | 122 | 609 | 4,506 → 16.8 |
| dec-fr-bordeaux | 134 | 300 | 88 | 145 | 638 | 4,653 → 15.5 |
| dec-fr-lille | 267 | 599 | 193 | 273 | 1,108 | 9,159 → 15.3 |
| dec-kr-seoul | 258 | 561 | 172 | 208 | 1,103 | 8,918 → 15.9 |
| dec-kr-busan | 133 | 293 | 64 | 106 | 686 | 4,394 → 15.0 |

Runway is ≥ 15 days at a Saturday's rate for every store. The brief's budget is ≤ 12 days of sales.

- **Today (2026-10-12):** `day` is **HELD**, because the stamps would run up to 29h40m ahead of the clock. All 4 alarms are held as future.
- **A past slice:** `--date 2026-10-11 --slice 10-14 --compress 1` passes the guard with no stamp lead: 614 shoppers, 3,113 expected impressions, 4h11m wall.
- **2026-10-13:** carries the scripted Denver alarm at 14:07 local (`2026-10-13T20:07:00Z`, ★ scripted) plus 4 generated ones.

## 5. Findings / deviations

1. **Planogram directive ids are not visible to twin** (§ 2). The ack is a receipt and the read-back is site-scope denied. The Coordinator resolves the ids and checks ingest.
2. **Basket concentration → substitution.** The basket sampler picks SKUs near the target line price, and a store holds 1–10 units of each SKU. Exact-EAN allocation therefore **dropped 45 % of Denver's lines** on a Saturday. The fix: when an EAN is out, the line takes the nearest-priced in-stock EAN of the **same department**. Units per transaction and the department mix are kept. About half the lines substitute, and none drop. Revenue on the platform is priced from the substituted items, so it tracks the generated revenue only approximately.
3. **No event time on restock / pricing / catalog / movement** in the §8 contract. These four land at send time. Only `sale_event.occurred_at` and the alarm `occurred_at` carry the day's own hours.
4. **`epc_list` is unproven live from twin.** Every earlier live sale used single `epc`. The first live dated run should warm up with `M8TRX_STREAM_LIMIT=1` and check that the platform reads the multi-unit sale as ONE transaction.
5. **Two engines for one store-day.** Denver's people plane (`DayDrive` → `ScenarioRun`, the operating model without the story, seed 4242 unless `day.sh` passes one) and its sales (`RecentDay`, the history engine) are generated separately. On 2026-10-10 the volumes are close (1,430 shoppers vs 1,448 visits), but the shoppers are not the same people. Unifying them is a follow-up.
6. **When to run a date.** No stamp may be in the future, so a whole day across all ten stores is fully due only after the latest close: SF closes 21:00 PDT = 13:00 KST the next day. A run at 22:00 KST the same day sends Korea plus the part of France already past and holds the rest. A re-run the next afternoon resumes from the ledger.
7. **Alarm `zone_ref`.** `CS-01` resolves to a zone only where the gate is registered in `fixture_code_mapping`. A Connect key cannot register it. The one-row registration per store needs the Coordinator, or the alarms land site-level (§8.1).
8. **The run id must be per date (found on the first live chain, 2026-10-12, Coordinator).** `.env` carries `M8TRX_RUN_ID=twin-s11-multisite`, which overrides `DatedStream`'s per-date default; the Oct 5 drive used it, and Oct 6 then "reused the persisted plan" (same plan file, same sent ledger) and sent 0 of 1,378. `day.sh` now passes `M8TRX_RUN_ID=twin-day-<yyyymmdd>-<seed>` to the stream step; Oct 5's plan/ledger files were renamed to that scheme (its sale ids keep the old prefix, which is fine — ids only need to be unique).

## 6. What remains for the live days

- **Coordinator:** resolve the nine refs → ids, `/evaluate` each, report resolved/unresolved per store, and re-run mig 259's compliance part.
- **Core timestamp fixes deployed** (BRIEF-CORE-ROLLUP-AND-SCORECARD § 2.3) → then `day.sh --live-stream --live-alarm --live-move` (webhook plane, anywhere), then `--live-day` (LAN, `:4223`, edge interlock in code). For the moving dots, use a `--slice … --compress 1` whose start is in the past.
- **Gate registration:** ~~one `CS-01` row per store in `fixture_code_mapping` for the alarms (Coordinator)~~ **closed 2026-10-12 (Coordinator):** core resolves `zone_ref` by the zone's own code (`zone.properties.code`) before any mapping, every store's Entrance zone is `Z-01`, and the fixture-code save refuses non-fixture zones anyway (core cleanup F-6) — `AlarmDay` now sends `M8TRX_ALARM_ZONE_REF` (day.sh defaults it to `Z-01`), the layout's `CS-01` only when unset.
- **Stocktakes:** not built. They need `connectScanSweep` + the sessions REST with deliberate shortfalls, plus a grant: the key currently presents `twin-s280-lockdown` (read scopes only).
- **Try-on sessions:** not built. The edge-side try-on config is unproven.
- **Cron:** `day.sh` is cron-ready (sources `.env`, idempotent per date). The schedule and host are Bob's call (MacBook or box).

## Restock moves (2026-10-12)

Bob's ruling, "yes do the restock moves": after each day's sales, refill the walls that sold down, so the platform's compliance (re-evaluated when an item moves or sells) holds where the backfilled history left it. **Branch:** `feat/restock-moves-1012` off `feat/recent-window-1012`. **Deletions: none.** Dry runs only; nothing was sent.

**What it does.** `M8TRX_MOVEMENT_RESTOCK=true` on `connectMovementDrive` (default false; `day.sh`'s `move` step defaults it to true, `M8TRX_MOVEMENT_RESTOCK=false` switches it off). It reads the date's persisted sales plan (`.twin-state/stream/twin-day-<yyyymmdd>-<seed>.plan.csv`) and its `.sent` ledger. If the plan is absent it says so and does nothing. Per store, per (fixture, EAN), it counts the **sent** sales that left a wall, then sends ONE `inventory_movement` (same `MovementDriver` envelope as the random moves) carrying the same number of stockroom (`BR-*`) units of that EAN to that wall. Code: `connect/RestockMoves.kt` (pure `plan()`), wired in `ConnectMovementDrive.multiStore`.

- A unit that sold out of the stockroom leaves no hole on a wall and is skipped (counted as `stockroom` in the per-store line).
- Stockroom units picked are lowest-EPC first and never: in the sold log, reserved by ANY persisted sales plan, already moved (placements / ledger), or taken by this run's random moves. Fewer stockroom units than sold: move what exists, count the rest as `shortfall_units`. No EPC is invented.
- The random moves are unchanged (`M8TRX_MOVEMENT_PER_STORE` > 0); restock runs in addition, and works with PER_STORE unset or 0.
- External id `RS-<slug>-<yyyymmdd>-<fixture>-<ean>-<n>`, `n` = units of that (fixture, EAN) already restocked for the date. Ids are strictly increasing within a date, so a resumed batch never reuses one and a failed send retries the same id.
- Destination: fixture code (default) or the wall's zone UUID with `M8TRX_MOVEMENT_TARGET=zone`. All 371 Oct 6 moves had a zone id in the key export.
- Live sends are paced `M8TRX_MOVEMENT_GAP_MS` (default 120) apart, like the sale stream (a restock is hundreds of sends).

**Env:** `M8TRX_MOVEMENT_RESTOCK` · `M8TRX_MOVEMENT_DATE` · `M8TRX_MOVEMENT_SEED` (20261016) · `M8TRX_MOVEMENT_STORES` · `M8TRX_MOVEMENT_TARGET` · `M8TRX_MOVEMENT_GAP_MS` · `M8TRX_STREAM_SOLD_LOG` (state dir derives from it) · `M8TRX_MOVEMENT_LIVE`.

**Ledgers (`.twin-state/moves/`, gitignored, written only on an accepted live send):**
- `restock-<yyyymmdd>-<seed>.sent`: EPCs this date's restock moved. A re-run of the date owes only `sold − already restocked` per (fixture, EAN), so with no new sales it sends nothing (self-tested); sales sent later in the day produce only the new units.
- `placements.csv` (`epc,fixture,date`): where a moved unit now sits, for restock AND random moves. Restock reads it first when it resolves a sold EPC's fixture or a stockroom candidate, and the random planner now also excludes placed EPCs (previously a later day could re-pick a unit the day before had already moved).

**Output.** `PLAN: restock N moves / M units, shortfall K (stores 10)` (the line `day.sh` surfaces), one `restock <slug> sold: … wall + … stockroom · … moves / … units · shortfall …` line per store, and `SIM-RUN movement date= seed= moves= units= restock_moves= restock_units= shortfall_units= sent_ok= sent_err= wall=`.

**Self-test:** `connectSelfTest` → `[restock] picks the sold EANs from the stockroom, skips unsent/stockroom sales, idempotent on re-run, shortfall counted ✓` (synthetic plan + epcs.csv: exact EANs from the stockroom, an unsent sale and a stockroom sale not restocked, a placed EPC resolved first, ledger re-run = 0 moves, a later sale = one move with id suffix `-2`, shortfall when the stockroom lacks the EAN, state-file round trips). `compileKotlin`, `ktlintCheck`, `connectSelfTest` pass.

### Dry-run numbers for 2026-10-06 (`scripts/demo/day.sh --date 2026-10-06 --steps move`)

Oct 6 plan: 1,397 sales in `twin-day-20261006-20261016.plan.csv` (copied read-only from the main checkout with its `.sent` ledger and `sold-epcs.txt`). Two views, because the main checkout's sold log already holds Oct 7-12 (the Oct 7-12 sales had drained the stockroom by the time of this run):

| view | restock moves | restock units | shortfall units | sold from a wall |
|---|---:|---:|---:|---:|
| **As of the end of Oct 6** (sold log = sales up to Oct 6, only Oct 5-6 plans reserved; what the drive sees if it runs right after the day's stream) | **371** | **486** | **2,045** (81 % of owed) | 2,531 |
| Today's state (full sold log, as `day.sh` literally runs now) | 17 | 19 | 2,512 | 2,531 |

As-of view, per store (wall units sold → moves / units / shortfall): bordeaux 174 → 17/18/156 · lille 290 → 32/38/252 · lyon 230 → 37/44/186 · marseille 129 → 25/32/97 · paris 286 → 50/78/208 · busan 152 → 24/25/127 · seoul 316 → 43/56/260 · denver 380 → 54/74/306 · nyc 263 → 49/66/197 · sf 311 → 40/55/256. Per-move size: 286 of 371 moves carry 1 unit, 61 carry 2, 20 carry 3, 4 carry 4+. (Stockroom sales: 29-86 per store, skipped.)

The `day.sh` dry-run line as run in the worktree: `PLAN: restock 17 moves / 19 units, shortfall 2512 (stores 10)` (today's state view).

### Limitations

1. **The shortfall is structural, not a bug: ~80 % of what sells off a wall has no stockroom unit of the same EAN left.** The dated stream sells the stockroom (`BR-*`) first, the basket sampler concentrates on ~100 EANs per store, and a store holds ~6 units per EAN. On Denver, of 100 EANs that sold from a wall on Oct 6, 91 had stockroom units at the start but only 4 still had a free one afterwards. The restock refills only what the stockroom still holds, so on its own it slows the wall drain by about one fifth, it does not hold compliance flat. Closing the gap needs more stock of the sold EANs (a stockroom top-up with new EPCs, which is a data-generation change, or a stream that sells the floor before the stockroom), or Bob's call that a partial refill is enough for the demo. Not built, not decided here.
2. **Run it right after the day's stream.** Ordering matters: restock uses the sold log as the stockroom's state, so a late run (after later days sold) finds even less stock. `day.sh` runs `move` after `stream` for the same date, which is the right order.
3. **The dated sales stream does not read placements.** It allocates from the `epcs.csv` fixtures. A restocked unit still looks like stockroom stock to it, so a later day can sell it "from the stockroom". The platform sees the sale of an item that is physically on the wall (what actually happened), and the next restock resolves that EPC through placements and refills the wall it left. Sales are by EPC, so the demo is unaffected. Making `DatedStream.allocate` placement-aware would change the persisted-plan semantics of future dates and was left alone.
4. No event time on the movement contract: restock moves land at send time, as the random moves do.
5. Compliance behavior itself is unproven here: nothing was sent. Whether the platform keeps a target compliant from these moves is for the first live day to show (the Coordinator fires live).
6. Placements record a move only after an accepted send; a crash between the send and the ledger append can leave a sent batch unrecorded, and a re-run would re-send the same ids (the webhook is idempotent on `external_movement_id`).

## Receiving (2026-10-12)

The restock's measured gap (finding 8, "Restock moves" limitation 1): the stockroom was drained and the platform's `shipment_manifest` ingest records only EXPECTED quantities, it creates no units. `POST /api/v2/inventory/items/receive` does (core `InventoryActionService.receiveItems`, FR-INV-07 auto-creates unknown EPCs; the twin's data-plane bearer holds `inventory:create`). So the loop is now: manifest (expected) -> **receive** (new units land in the stockroom) -> restock moves (stockroom -> walls). **Branch:** `feat/restock-moves-1012`. **Deletions: none.** Dry runs and self-tests only; nothing was sent.

**What it does.** `connectReceiveDrive` with `M8TRX_RECEIVE_DATE` set (without it the old single-store EPC-file drive is unchanged). For the date's persisted plan (`.twin-state/stream/twin-day-<yyyymmdd>-<seed>.plan.csv` + `.sent`) and per store:
1. Reproduces the day's restock manifest lines with `DatedStream.manifestLines` (the stream's own function, extracted): units sold per item code, most-sold first, **rounded up to cases of 6**. Stockroom sales count too (the manifest never excluded them). Only SENT sales count. A style code carrying two sizes is split over its EANs: each EAN gets what it sold, the case-rounding surplus goes to the best seller, so the receipt totals exactly the manifest line.
2. Mints NEW EPCs for those EANs (`Sgtin96`, the chain's own Decathlon SGTIN-96 layout, verified against `epcs.csv` rows: re-encode = identity, decode = EAN). Serial from the **receiving block `[1,000,000,000 .. 11,000,000,000)`**; all chain serials lie in 1,000..9,999,999, so the block cannot collide with chain data by construction. The draw is `SHA-256(seed, slug, date, ean, index, attempt)` (replay-stable); a hit on any known EPC (the store's `epcs.csv`, the sold log, earlier receipts, this run) re-draws with the next attempt. Index `k` of an EAN is its k-th unit that date, so a re-run mints the same EPCs and later sales mint only the indices past the ledger.
3. Sends ONE `items/receive` per store (chunked at 200 EPCs): `{siteId, spaceId, items:[{epc}]}`. `siteId` from `site_ids.csv`; `spaceId` = the store's **stockroom space** (see limitations: not in the key export; read from `M8TRX_RECEIVE_SPACES` csv `slug,space_id` (default `reference/data/chain/stockroom_spaces.csv`), then `.twin-state/receive/stockroom-spaces.csv`, else looked up live once through `POST /spatial/identity` (`space_type` stockroom) and cached there). A refused lookup skips the store and counts an error; it never falls back to the sales floor. Only when the lookup succeeds and the site has no stockroom space does it use the sales-floor space, with a warning.

**Env:** `M8TRX_RECEIVE_DATE` · `M8TRX_RECEIVE_SEED` (20261016) · `M8TRX_RECEIVE_LIVE` (false) · `M8TRX_RECEIVE_STORES` (csv, all ten) · `M8TRX_RECEIVE_GAP_MS` (250) · `M8TRX_RECEIVE_SPACES` · `M8TRX_CHAIN_DIR` · `M8TRX_STREAM_SOLD_LOG` (state dir). `M8TRX_DAY_MANIFEST_LINES` (see decision below).

**Ledgers (`.twin-state/receive/`, gitignored, written only after an accepted send):**
- `receive-<yyyymmdd>-<seed>.sent`: EPCs received for that date. A re-run of the date owes `target - already received` per EAN, so with no new sales it receives nothing (self-tested, including `planAll` from disk).
- `received-stock.csv` (`epc,ean,slug,fixture,date`): every received unit, booked on the twin side to a `BR-*` fixture of its store (deterministic per EAN). `RestockMoves` reads it as stockroom stock through `ConnectMovementDrive` (lowest EPC first still applies; a placement overrides the fixture once a unit moves to a wall).
- A batch with per-EPC `errors` whose reason matches `exist|already|duplicate|in stock` counts as already received (a retry of an accepted batch); any other per-EPC error stays out of the ledger, is counted `epc_errors=` and fails the run. An HTTP refusal stops the run, a re-run resumes.

**Decision to confirm: the stream's manifest changed.** `plannedOps` used to send the day's top **5** item codes, counted from the planned basket lines. For "received equals promised" it now counts the **allocated** sales (the EPCs that actually sell, substitutions included) and sends every sold item code (`M8TRX_DAY_MANIFEST_LINES=5` restores the old cap). Manifest ids already sent (Oct 5-12) are skipped by the sent ledger, so those days keep their top-5 manifests on the platform and a receive for them exceeds what was promised. For the days not yet streamed the manifest is ~70-140 lines per store (one webhook), larger than anything sent so far.

**day.sh.** New step `receive` between `stream` and `day` (`--live-receive`, included in `--all-live`; default steps `stream,receive,day,alarm,move,stock,tryon`). SIM-RUN line: `SIM-RUN receive date= seed= stores= lines= units= sent_ok= sent_err= epc_errors= received= wall=` (`sent_ok/err` count requests). A dry `move` that follows a dry `receive` sets `M8TRX_MOVEMENT_ASSUME_RECEIVED=true`: the receive PLAN counts as landed so the dry numbers chain (refused with live moves). With `--live-move` but a dry receive, `move` sees only what real receives wrote.

**Self-test:** `connectSelfTest` -> `[receive] SGTIN-96 round-trip, manifest rule shared, minted EPCs unique/in-block/EAN-decodable, ledger resume, dry chain refills 11/11 (was 1/11)`. Covers: the encoder against real `epcs.csv` rows; the manifest rule (rounding, order, cap); a synthetic store-day (24 units = the manifest's cases, per-EAN split incl. a two-size style, all EPCs unique, in-block, decodable, not in `epcs.csv`, booked to a `BR-*` fixture, deterministic); nothing sold or sent -> nothing; ledger re-run = 0; a half-done ledger resumes with the same EPCs; 6 more sales mint only 6 new units; a taken EPC is re-drawn; state files round-trip; `planAll` from disk; the end-to-end dry chain (sell -> restock plan refills 1 of 11 -> after receive 11 of 11, shortfall 0). `compileKotlin`, `ktlintCheck`, `connectSelfTest` pass.

### Dry-run numbers for 2026-10-06 (`scripts/demo/day.sh --date 2026-10-06 --steps receive,move`, worktree state: Oct 6 plan + sent ledger, the main checkout's sold log)

`PLAN: receive 7374 units / 1103 manifest lines in 41 requests (stores 10)`; `PLAN: restock 963 moves / 2531 units, shortfall 0 (stores 10)`. Before receiving, the same state gave `restock 17 moves / 19 units, shortfall 2512` (Restock moves section).

| store | wall units sold | all units sold | manifest lines | units to receive | restock moves / units | shortfall |
|---|---:|---:|---:|---:|---:|---:|
| bordeaux | 174 | 203 | 117 | 720 | 108 / 174 | 0 |
| lille | 290 | 355 | 143 | 918 | 127 / 290 | 0 |
| lyon | 230 | 280 | 106 | 684 | 99 / 230 | 0 |
| marseille | 129 | 149 | 69 | 450 | 60 / 129 | 0 |
| paris | 286 | 343 | 110 | 768 | 93 / 286 | 0 |
| busan | 152 | 187 | 94 | 570 | 82 / 152 | 0 |
| seoul | 316 | 376 | 128 | 840 | 116 / 316 | 0 |
| denver | 380 | 466 | 122 | 906 | 100 / 380 | 0 |
| nyc | 263 | 317 | 103 | 726 | 84 / 263 | 0 |
| sf | 311 | 380 | 111 | 792 | 94 / 311 | 0 |

Receive = the manifest's cases exactly (every row is a multiple of 6). The 30 random moves of `--moves 3` are unchanged.

### Limitations

1. **The request carries a space, not a zone, and no shipment id.** `ItemReceiveRequest` is `{siteId, spaceId, items:[{epc}]}` (nothing else is documented in `M8TRX-API-SURFACE.md` #25 or `DataPlane.kt`). The unit lands in the stockroom SPACE; the `BR-*` fixture is the twin's own bookkeeping, and the platform does not know which back-room zone holds it. There is no `external_shipment_id` field, so the receipt cannot name the manifest it fulfils (FR-INV-05 reconciliation can only match by product and site, if at all); the manifest id lives in the twin's logs. I did not add unknown fields to the body. If the reconciliation matters for the demo it is a TWIN-REQ to core, not a shim.
2. **The stockroom space UUID is not known offline.** The key export (`keys-2026-10-11.csv`) holds only the SALES FLOOR space. The first live run resolves it per store through `spatial/identity` (needs `inventory:read` on the bearer; if the bearer lacks it the store is skipped with an error). To avoid that, put `slug,space_id` rows in `reference/data/chain/stockroom_spaces.csv`. In a dry run the space prints as a placeholder.
3. **Unproven on the platform:** whether it resolves the product for a minted EPC exactly as for the seeded chain units (same encoding, but the chain was bulk-seeded, not auto-created from an EPC); whether it dedupes on EPC on a retry (the API-surface note says an existing IN_STOCK EPC comes back in `errors`; the reason text is a guess in the `exist|already|duplicate|in stock` match); whether it caps a request below 200. Suggested canary before `--live-receive` on all ten: `M8TRX_RECEIVE_STORES=dec-us-denver M8TRX_RECEIVE_LIVE=true`, then read the stockroom count back and run `connectReadProbe`.
4. **Case rounding over-supplies.** Most items sell 1 unit and are received as 6, so Oct 6 receives 7,374 units against 2,531 wall units sold: ~4,800 surplus units stay in the stockroom (and Oct 6's 525 stockroom sales are replaced too). Every dated day adds its own; later days' restocks draw on the whole of `received-stock.csv`.
5. **The sales stream does not sell received units** (it allocates from `epcs.csv` only), and still does not read placements (Restock limitation 3). Received units therefore only ever move to walls; the walls hold more units than the chain data after a restock.
6. Receive lands at send time (no event time on the contract), like the moves.
7. As with the moves, a crash between an accepted send and the ledger append leaves a sent batch unrecorded; the re-run mints the same EPCs and the platform's reply to them (already in stock) is treated as received.
