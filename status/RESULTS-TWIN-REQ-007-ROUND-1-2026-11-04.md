# RESULTS — TWIN-REQ-007 round 1 (2026-11-04, iMac, unattended)

Brief: `~/IdeaProjects/m8trx-shared/twin/requirements/TWIN-REQ-007-behavior-simulation-for-draft-scoring.md`. Branch `claude/behavior-simulation-req-007-837f10`, DRAFT PR (link in the PR list; not merged). Inventory (step 0): [`INVENTORY-TWIN-REQ-007-2026-11-04.md`](INVENTORY-TWIN-REQ-007-2026-11-04.md).

**Deletions: none. Deployed: no.** Nothing was written to mother by psql, nothing was published to `:4222`, and no backfill ran.

## 1. Bumps (stack-watch 2026-11-02) — all applied, build green

| Component | From | To | Note |
|---|---|---|---|
| Gradle wrapper | 9.7.1 | **9.8.2** | `distributionSha256Sum=21bd5da2…e389607` pinned in `gradle-wrapper.properties`, equal to `services.gradle.org/…/gradle-9.8.2-bin.zip.sha256`. Verified by deleting the cached distribution and re-downloading under the pin. Wrapper jar SHA-256 equals the published `gradle-9.8.2-wrapper.jar.sha256`. KGP 2.4.10 builds clean on 9.8.2 despite its stated 9.7.0 ceiling, so there was no need to stop lower. |
| jackson-databind/core | 2.22.2 | **2.22.4** | clears the four High advisories carried since 2026-10-05 |
| jnats | 2.26.2 | **2.26.4** | also lifts `bcprov-lts8on` to **2.73.12.1** (the BouncyCastle Notable) |
| logback | 1.6.3 | **1.6.6** | |

`./gradlew build` (incl. ktlint) green; `connectSelfTest` all pass, `peopleSelfTest` 50/0, `scenarioSelfTest` 37/0. Commit `6a0e7ae`.

## 2. Inventory headline

On `main`, the people plane is real, deterministic and proven, but nothing responds to what is on a fixture. The sales plane is a separate engine with `occurred_at = now`. Of the ten requirements: **1 met, 4 partly met, 5 absent**. Two months of twin work sit on unmerged PRs #17–#19 (one stacked chain; #19 subsumes it) and none of it is in STATUS. Full table with file:line evidence: the inventory file. Commit `bd1b225`.

## 3. What was built (round 1)

New package `src/main/kotlin/com/m8trx/twin/behavior/`, beside the existing `Journeys` (untouched apart from a `handle` field on `StoreCatalog.Sku`).

| Requirement | Built | Where |
|---|---|---|
| 8 shopper agents | One shopper, one causal chain. The shopper arrives, walks through the door, passes the entrance table, chooses stops **from what is on each fixture**, looks and dwells (real 5 Hz episodes), picks cells up, tries on (footwear at the bench `FB-01`, apparel in the fitting room), buys from what is in hand, queues at a counter, maybe adds an impulse item, and leaves. Each decision draws from its own random stream (common random numbers), so a change to one fixture changes only the decisions that read it. | `ShopperAgent.kt` |
| 2 content-driven impressions/dwell/engagement | Stop chance = zone role × **content appeal** (empty 0.15, hero up to 1.6) × impression truths × mission category share. Dwell scales with appeal. Engagement = pickups. | `ShopperAgent.kt`, `Presentation.kt` |
| 3 sales per cell | Every sale line is a handled cell, carrying its fixture and level. Buy chance = persona base × size run × complement-beside-hero × willingness to pay × try-on uplift. | `ShopperAgent.kt` |
| 10 truths | All six, each multiplying exactly one measure: entrance table (impressions; pickup), back corner, eye/floor level, broken size run, color story (coherent/clash), complement beside hero. | `Presentation.kt` |
| 6 parameter file | `reference/data/behavior/dec-us-denver.json` (every number has a `_doc`), plus the presenter prose `reference/data/behavior/README.md`. | |
| 7 determinism + oracle totals | Same seed → same shoppers, visits, sales and samples (digest-checked). The ledger dumps per-fixture expected totals: stops, impressions, look time, platform duration, engagements, try-ons, units, revenue, dwelled, dwelled-and-bought-here. | `BehaviorModel.kt` (`FixtureLedger`) |
| 1 traffic | Operating-model hourly curve × **day-of-week factor** × **6-week seasonality wave**. Door walk in and out (the entrance is a place on the wire); buyers queue at a counter (an impression on the counter, so checkout is a place too). | `BehaviorModel.kt`, `ShopperAgent.kt` |
| 5 plan-change response | `PlanTimeline`: a shopper reads the plan on the floor when they enter. Scripted reset: hero Kiprun shoes go from the back corner `GB-R1-U7` to the entrance table `PI-01`, with running socks beside them. Control fixture `GB-R6-U3`. | `ShelfPlan.kt` |
| 9 history (record mode) | `behaviorHistory`: 56–84 days ending yesterday, reset inside the window, refused beyond 84 days. Writes `days.csv`, `fixture_daily.csv` and `plan-changes.csv`. **Sends nothing**; live is refused with the reason. | `BehaviorHistory.kt` |
| 9 re-measure | `behaviorBackdateProbe`: one backdated episode to the twin edge (plus one backdated `sale_event` when keyed). | `BackdateProbe.kt` |
| drive | `behaviorDrive`: record mode by default (oracle files); live sends positions to the twin edge (interlocked) and baskets to Connect at each shopper's exit, when keyed. | `BehaviorDrive.kt` |

Twin's own calls, stated so they can be overruled: **shelf level is derived** (the plan has no level field; lines fill levels 1 top to 4 floor by facings in `position_sequence` order; `display_level` wins when present). **Back corner is derived** (farthest 20% of browsable fixtures from the door and more than 4 m off the door's centre line; 18 fixtures in Denver). **Color families** come from catalog color names.

### Calibration (seeded week 2026-10-05, published plan)

Behavior numbers (persona buy rates, pickup, willingness to pay, impulse) were tuned so the generated store still meets the operating model with the truths ON. Every day of the seeded week reconciles under `layer3/Reconciliation.kt`'s existing tolerances (visitors within 3√N, transactions/units/revenue within max(10%, 3σ)). That holds for both the published plan and the "before" plan the history starts from.

| Run | Conversion | Units/txn | ATV | Days outside tolerance |
|---|---|---|---|---|
| Operating model | 0.22 | 2.2 | $58 | — |
| Self-test week, published plan | per-day −8.6%…+8.4% vs model | | | **0 / 7** |
| History, 42 days before the reset | 0.215 | 2.17 | $57.1 | **1 / 42** (gate ≤ 10%) |
| History, 42 days after the reset | 0.227 | 2.27 | $56.4 | 0 / 42 (reported, not gated: the lift is the plan's effect) |

The 84-day record run (`behaviorHistory`, 2026-08-12 → 2026-11-03, reset 2026-09-23) shows the before/after the planogram lane needs, per day:

| Fixture | Role | Impressions/day before → after | Engagements/day | Units/day |
|---|---|---|---|---|
| `PI-01` | entrance table, receives the heroes | 24.5 → **255.5** | 0 → 372.8 | 0 → 43.0 |
| `GB-R1-U7` | back corner, loses the heroes | 32.5 → **3.2** | 34.5 → 0 | 0.6 → 0 |
| `GB-R6-U3` | untouched control | 38.6 → **38.1** | 37.5 → 34.5 | 6.6 → 4.8 |

The control's impressions are flat. Its units dip (6.6 → 4.8 a day). Two causes are plausible and not yet separated: different days carry different shoppers, and the heroes at the entrance pull some mission shoppers' budgets there. A same-days A/B (both plans over the same 42 days) would separate them; that is a round-2 check.

## 4. Proof: `./gradlew behaviorSelfTest`

**35 passed, 0 failed** at `98bbdd1` and again after the round-2 fixes, in about 20 s. Offline; nothing is published. The existing suites are unchanged: `peopleSelfTest` 50/0, `scenarioSelfTest` 37/0, `connectSelfTest` all pass, and `./gradlew build` with ktlint is green.

| Check (brief's "done when") | Result |
|---|---|
| Req 1: every day of a seeded week reconciles; weekend > weekday, Friday > Tuesday; weekday peak 18h, Saturday 13h; seasonality varies | ✓ (Tue 785, Fri 1012, Sat 1464 visitors) |
| Req 7: same seed → same shoppers/visits/sales (digest) and same 238,077 samples; seed+1 differs | ✓ |
| Req 8: oracle impressions computed from the emitted samples = the agents' claimed impressions, **per shopper** | ✓ 763 = 763 over 183 shoppers, 0 mismatches |
| Req 8: no sale line without an impression at that fixture in that visit, except gift grabs and checkout impulse | ✓ 0 orphans |
| Req 8: "of shoppers who dwelled at F, share who bought from F" recomputed from oracle impressions = ledger, every fixture | ✓ (e.g. `PI-01` 5/30 = 0.17 in the 14:00 hour) |
| Req 10: each truth OFF / default / DOUBLED | ✓ all six (below) |
| Req 3: two stylings differing only in size-run completeness | ✓ `mens-kiprun-run-100-warm-running-pants` on `GF-R5-U4`: complete 0.145 → 6/12 middle sizes removed 0.057 (rule 0.50, measured 0.39); same broken run with the truth off 0.130 |
| Req 5: scripted reset over 14 days | ✓ `PI-01` 156 → 1,648 impressions, 0 → 287 units; `GB-R1-U7` 227 → 15; control `GB-R6-U3` 270 → 261 (drift 3.3%); every shopper read the plan active at entry |
| Req 5: reset at 15:00 within a day | ✓ `PI-01` impressions/shopper 0.000 before, 0.245 after |
| Req 9: 84 days back accepted, 85 refused, reset outside the window refused | ✓ |

| Truth | Measure | Default ratio (stated) | Doubled ratio | Effect ×(doubled ÷ default) |
|---|---|---|---|---|
| entrance table | impressions | 1.807 (1.8) | 2.698 | 2.10 |
| entrance table | pickups per impression | 1.410 (1.4) | 1.799 | 1.95 |
| back corner | impressions | 0.485 (0.5) | 0.000 | 1.94 |
| eye level | eye-level pickups | 1.293 (1.3) | 1.582 | 1.99 |
| floor level | floor pickups | 0.702 (0.7) | 0.405 | 2.00 |
| color story coherent | impressions of coherent faces | 1.244 (1.25) | 1.472 | 1.93 |
| color story clash | impressions of clashing faces | 0.846 (0.85) | 0.702 | 1.94 |
| complement beside hero | complement conversion | 1.231 (1.2) | 1.441 | 1.91 |

OFF is the baseline each ratio is taken against, so an OFF truth has ratio 1.0 by construction.

### Reviews (Sonnet, two rounds, read-only)

- **Round 1** (on `081965b`): no critical findings. Fixed in `98bbdd1`:
  - The probability cap was damping the doubled truths for the strongest shoppers. Headroom is now validated in `BehaviorParams.validate`.
  - Decisions now use per-decision random streams (common random numbers). The control drift fell from about 7% to 3%.
  - Color story was looser than its doc. It now needs 3+ cells of the family, with unnamed colors counted in the denominator.
  - Shelf level was by line count; it is now by facing midpoint.
  - The back-corner quantile included non-browsable fixtures.
  - Duration validation was added for queue and try-on spans.
  - The ledger now carries a `platform_duration_s` column beside the engaged look time.
  - Episode phases are explicit, so they are immune to `DayDrive`'s env A/B switch.
  - The compression-window doc was honest only for a fast run. It now states the wall-clock margin.
- **Round 2** (on `98bbdd1`): **no live-safety finding.** The reviewer confirmed:
  - the drive and probe publish nothing unless their `*_LIVE` flag is set;
  - the `server_name` interlock and office refusal both run before the first connection that publishes;
  - credentials come from env only;
  - every object id and sale id carries the run tag.

  Fixed afterwards:
  - An eviction could precede the same shopper's last sample; the comparison is now strict.
  - Sold EPCs are now recorded per sale, not at the end, and the probe records its EPC.
  - Live runs require an explicit tag.
  - The probe's days-back value is bounded to 1..84.
  - The gift persona's fixture draw now uses per-fixture streams.
  - The watcher's prefix match now requires the tag separator and counts events with no object id.
  - A short-basket warning was added.
  - Doc drift was corrected.

## 5. Live smoke (one simulated hour, twin tenant, twin edge only)

**What ran.** `behaviorDrive` live, 2026-11-03 11:45:33Z → 11:59:12Z (13m39s wall). Denver, simulated Tuesday 2026-11-03 14:00–15:00, seed 20261104, published plan, compress 18×, tag `req007-smoke-1103-h14`. The env pointed explicitly at `nats://192.168.55.29:4223`, space `e3c9a424-…`, site `84f2a1c1-…` (Denver) and tenant `ecfa6903-…` (M8trxDemo). `.env` was NOT sourced (see § 9).

**What it did.**
- Interlock passed: `server_name = edge-twin-denver`.
- **Published 82,829 position samples for 66 shoppers**, each evicted at its own exit. Exit 0, no errors.
- The oracle predicted **284 impressions** before anything was sent and refused to drive unless that equalled the agents' claims (it did).
- 13 buyers, 22 units, $445.94 are planned in `build/behavior/drive-req007-smoke-1103-h14/sales.csv`, each with an EPC. **Sales were NOT sent**: no Connect webhook key in the environment.

**What the wire showed, and why it is not yet a clean number.** The in-code wire counter (`impressionWatch`, deduped) counted **342 `fixtureImpression`** on the twin edge across the window, against 284 + 1 (the backdate probe) = 285 of ours. The counter was unfiltered, and **another publisher was live on the same edge**. A 90-second prefix-filtered sample afterwards attributed every foreign impression to objects named `live-dec-us-denver-…-cust-2026-11-03` (PR #19's live people-plane service), at roughly 12 impressions/min. So the 342 mixes both, and our share cannot be separated after the fact. **Not claimed as oracle = wire.** `impressionWatch` now takes `M8TRX_WATCH_PREFIX` (committed), so the next smoke counts only its own objects. The persisted-rows half (`impressionVerify` over the tag) needs the Bearer.

**Code version caveat.** The smoke ran the drive and model as of `081965b` plus the uncommitted drive. The model, the pacing and the guards are the same as at `98bbdd1`. Review-round-1 fixes (random streams, color/level rules, recalibration) landed after it and change which shoppers do what, but not how they are sent.

**Re-run, attributed (one command each, ~14 min):**

```bash
M8TRX_NATS_URL=nats://192.168.55.29:4223 M8TRX_NATS_EXPECT_SERVER=edge-twin-denver M8TRX_WATCH_PREFIX=req007-smoke2 M8TRX_WATCH_TAG=req007-smoke2 ./gradlew impressionWatch
```

```bash
M8TRX_NATS_URL=nats://192.168.55.29:4223 M8TRX_SPACE_ID=e3c9a424-3ced-5288-9756-19935d39f88f M8TRX_SITE_ID=84f2a1c1-fb0a-41b2-9e0d-c9102a22ca7e M8TRX_TENANT_ID=ecfa6903-5c50-439f-8f80-185982de944e M8TRX_BEHAVIOR_DATE=2026-11-03 M8TRX_BEHAVIOR_FROM_HOUR=14 M8TRX_BEHAVIOR_TAG=req007-smoke2 M8TRX_BEHAVIOR_LIVE=true ./gradlew behaviorDrive
```

Add the Connect webhook vars (`M8TRX_CONNECT_INTEGRATION_SLUG`, `M8TRX_TWIN_WEBHOOK_KEY`) to send the sales in the same run.

## 6. Requirement 9 re-measure (past timestamps)

**People plane, measured live 2026-11-03 20:44 KST.** One shopper (`req007-backdate-1793706248`), one fixture (`GB-R6-U3`), an 8 s look. Every sample `ts` was set 30 days back (2026-10-04T11:44:08.656Z) with spacing intact, published to `:4223` after the `server_name = edge-twin-denver` interlock. The edge emitted 1 `fixtureImpression`, 3 `lookingAtFixture` and 32 `dwellingNearbyFixture`. The impression body carried **`firstDwell` 1791114248656 (= the first `ts` sent, to the millisecond)**, `firstLook` +1.2 s, `lastLook`/`lastDwell` +9.2 s, `duration` 9200. The edge's own envelope `ts` was wall clock (1793706269094).

So **the edge's impression rule runs on payload time and accepts a 30-day-old `ts`**. The persisted rows are a different question: whether `impression_event.recorded_at/first_look` keep the payload time, and whether `person_session.entry_time` does (the 2026-10-11 dig says the person state machine stamps wall clock). Both are **UNMEASURED**, because reading them needs the Bearer (`impressionVerify`), which this session does not hold.

**Sales plane: UNMEASURED.** No Connect webhook key in this session's environment. The exact bytes are recorded by the probe's dry run.

**TWIN-REQ-008 is therefore not filed yet.** The brief says to file it "stating exactly which paths ignore the time", and no path has yet been shown to ignore it. Next action: re-run `behaviorBackdateProbe` with the webhook key and Bearer, then read back the three stored times.

## 7. What waits on Bob

1. **Credentials for the sales half** (Connect webhook key + integration slug, and the Bearer for read-back). These would let the twin send the smoke's sales and measure the stored `occurred_at`. The main checkout's `.env` has `M8TRX_TENANT_ID`, `M8TRX_TWIN_WEBHOOK_KEY` and `M8TRX_TWIN_SERVICE_BEARER` **empty**, and the session was not permitted to browse 1Password for them.
2. **Go for the backfill.** It waits on (1) and on the re-measure's answer. Pace: ~40 min of wall time per Denver day through the edge, ~56 h for 84 days, inside the 6-day compression margin.
3. **A ruling on PR #17–#19** (merge, rebase or retire). They overlap this work: a dated sale stream and a plan-epoch `DayDrive`. This branch does not depend on them.
4. **Office.** Not built. The twin dataset has no office layout, and the office's only edge is production hardware. Requirement 11 says office behavior goes through the cloud REST route only, and that route has zero callers (`RestEmitter`) and no key grant. It needs the office spatial tree (readable via §6.5.1 with the Bearer) and a decision on the REST people route.

## 8. Core asks (none filed yet; each needs a measurement or a ruling first)

| Ask | Status | Why not filed |
|---|---|---|
| Past-timestamp honoring on the persisted people rows, sales, movement and plan `effective_date` (candidate TWIN-REQ-008) | edge rule **honors** payload time (measured); rows and sales unmeasured | the brief's own condition: file once a path is shown to ignore the time |
| **Floor engagement producer.** A shopper handling an item on the floor has no public path into `behavioral_projection.engagement`. Only try-on sessions (`fittingRoom*` on NATS → `try_on_zone_session`) have one. | candidate TWIN-REQ-009 | the twin has not yet driven try-on sessions (it needs try-on zone UUIDs from the Bearer read) to see whether they feed `engagement` |
| **Floor moves for a backdated reset.** A sale by EPC attributes the fixture where the platform has the unit. Moving the hero units to `PI-01` for a past reset needs `inventory_movement` with an event time, and the PR-branch docs say movement lands at send time. | candidate, same brief as 008 | unmeasured on this branch |
| **EPC supply for history.** 84 days ≈ 39k units sold at Denver vs the floor's EPC count. A backfill needs receiving (a dated receive) or the SKU sale path, which does not carry the fixture. | design question for core/Bob | not a platform gap until the receive path's time behavior is known |

## 9. Findings worth knowing

- **The main checkout's `.env` sets `M8TRX_NATS_URL=nats://192.168.55.29:4222`, the production office edge.** Every drive's `server_name` interlock refuses it, and the run-card overrides it, but sourcing `.env` alone points a drive at real hardware. Suggest changing it to `:4223`. Left untouched here: it is not in the repo, and it is Bob's file.
- The planned-sales file showed 0 lines without an EPC for the smoke hour. Whether those EPCs are still unsold on mother is unknown: PR #19's runs sold 1,300–2,450 Denver units a day in October, and their sold log is not on this machine.
