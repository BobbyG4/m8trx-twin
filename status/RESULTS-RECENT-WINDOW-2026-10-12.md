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

## 6. What remains for the live days

- **Coordinator:** resolve the nine refs → ids, `/evaluate` each, report resolved/unresolved per store, and re-run mig 259's compliance part.
- **Core timestamp fixes deployed** (BRIEF-CORE-ROLLUP-AND-SCORECARD § 2.3) → then `day.sh --live-stream --live-alarm --live-move` (webhook plane, anywhere), then `--live-day` (LAN, `:4223`, edge interlock in code). For the moving dots, use a `--slice … --compress 1` whose start is in the past.
- **Gate registration:** one `CS-01` row per store in `fixture_code_mapping` for the alarms (Coordinator).
- **Stocktakes:** not built. They need `connectScanSweep` + the sessions REST with deliberate shortfalls, plus a grant: the key currently presents `twin-s280-lockdown` (read scopes only).
- **Try-on sessions:** not built. The edge-side try-on config is unproven.
- **Cron:** `day.sh` is cron-ready (sources `.env`, idempotent per date). The schedule and host are Bob's call (MacBook or box).
