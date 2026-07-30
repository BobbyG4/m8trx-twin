# Session 15 — 2026-07-28 — Twin spine restart: the people pipeline, proven end to end at full-day volume

**Branch:** `chore/spine-restart-hygiene` (15 commits, pushed, **no PR opened** — awaiting Bob)
**Brief:** `m8trx-shared/status/briefs/BRIEF-TWIN-SPINE-2026-07-28.md` (paired with `BRIEF-CONNECT-FINISH`, `BRIEF-TRIAD-FINISH`)
**Verification at close:** ktlint · `compileKotlin` · `connectSelfTest` · `peopleSelfTest` (49) · `scenarioSelfTest` (23) — all green

---

## Headline

**Core's fixture-impression pipeline ran end to end for the first time, camera-free, and then at full-day volume with the oracle exact.**

```
FULL DAY (live, twin edge, tag fullday-0728)
published    1,100,584 samples · 790 shoppers · 44m20s
observed     fixtureImpression  3,664   (oracle predicted 3,664)  ← 0.0% deviation
             lookingAtFixture 3,578 · dwellingNearbyFixture 8,658 · evictions 790
coverage     97 distinct fixtures · 790/790 shoppers produced impressions
durations    min 28,200ms · median 60,000ms · max 89,800ms
```

Also: **the first circle-fixture impression ever recorded** (`PI-01` → `7dc6fb79`, 8400ms), which is the acceptance gate for Connect's `GeometryConverter` fix. DB-confirmed by Bob.

---

## 1. Ground-truth pass — the session's highest-leverage 40 minutes

Bob's instruction was *"if something is not correct then let's resolve first… we have to go by real code here so we don't waste time."* Verified both paired briefs against source before building. **Two errors found, both of which would have burned both parallel sessions.**

1. **`viewDirection` is MANDATORY** (`XovisImpressionEvaluator.kt:311`: `if ((e.hasTag != true) && e.viewDirection != null)`). Twin declared it `Array<Double>? = null` and never set it, so **every people-event twin published would have been silently discarded** — no exception, nothing above debug. `impression_event` stays at 0 and each session blames the other.
2. **Connect outbound already exists** — `OutboundWebhookDispatcher` + `OutboundRetryJob`, with backoff/poison/HMAC, called from `StocktakeService:529` and `IntegrationController:179`. **Twin exercised this loop live in S11** (happy path + retry/heal). The Connect brief said it did not exist; that session would have rebuilt working infrastructure.

Also surfaced: the **edge-server instance was unowned across all three briefs** (→ ruled to the Connect session, delivered same day).

**Both corrections accepted into `m8trx-shared` through `9f8f68e0`.** Note recorded at `status/archive/sprint/TWIN-SPINE-GROUNDTRUTH-AND-RESTART-2026-07-28.md`.

> **Don't repeat:** the brief's framing "twin already publishes the exact AreaEvent shapes this pipeline consumes" was **shape-true but behaviour-false**. Field-for-field the DTOs matched; an *optional-in-the-type* field was *mandatory-in-the-gate*. Matching a wire shape is not the same as satisfying a consumer.

---

## 2. What shipped

| Commit | What |
|---|---|
| `c82cb8f` | Ground-truth note + 3.4 hygiene (5 stale HOLD FIRE sites, not 2; `seed_store.py` de-hardcoded + SUPERSEDED) |
| `4cd32a6` | Addendum — `crossing` is a dead end in v2, descoped from 3.2 |
| `3e0a8f8` | `peopleSelfTest` — impression-rule conformance harness (`FixtureGeometry` · `ImpressionOracle` · `BrowseEpisode`) |
| `9c12c34` | Zone-affinity re-key off structure, portable across all 10 stores |
| `ffcdcd2` | STATUS + TRACK sync; cleared the stale FR-PLN-08 blocker |
| `5644950` | `connectPeopleDrive` — drive the real pipeline over NATS, two edge interlocks |
| `b6f34ab` | Oracle reports the window at expiry (validated live 7/7) |
| `8740f3f` | Mother fixture map joined; standoff-side targeting bug fixed |
| `ceadbc0` | **Circle fixtures live — first circle impression** + the containment-only gap behind it |
| `368613a` | `connectDayDrive` — full generated day, gap-only compression |
| `2299e11` | Zone-vs-fixture dwell + store-time slicing + idle compression |
| `87f2c5e` | Anchor wire timestamps to the plan, not the wall clock |
| `3589952` | Coordinator status refreshed pre-run |

New packages: `runtime/` (Q2/Q3/Q6 contract) · `layer1/` · `layer2/Journeys` · `layer3/{OperatingModel,TrafficGenerator,Reconciliation,ScenarioRun,DayDrive}`
New gradle tasks: `peopleSelfTest` · `scenarioSelfTest` · `connectPeopleDrive` · `connectDayDrive`

---

## 3. ★ Failed approaches — the "don't repeat this" record

**Every bug this session was silent-failure class.** Nothing threw; runs looked healthy; the only signals were internal inconsistencies. That pattern is the lesson.

### 3.1 Pacing — compress ARRIVALS, never episodes (nearly shipped, caught pre-fire)

Bob's rule was "compress the gaps, never the episodes". I implemented it as *walk the merged timeline, compress any delta that isn't intra-episode*. **Wrong, and invisibly so:** with ~790 *overlapping* shoppers, two consecutive samples on the merged timeline are almost always DIFFERENT people, so nearly every delta reads inter-episode and gets divided. A shopper's 200ms spacing → ~11ms, their 8.4s dwell → ~470ms, under `millisTillImpression`. **Zero impressions from a 26-minute run that looks perfectly healthy.**

Correct formulation (Bob adopted it as the rule): **shift each shopper's ARRIVAL by `offset/factor`; within a shopper, deltas ≤1000ms replay verbatim and anything larger is divided.** Guarded by an invariant that aborts before publishing if intra-episode spacing moved — verified 200ms across every subsequent change.

### 3.2 Wall-clock timestamps leak publisher jitter into the rule

Evening slice produced **854 impressions vs 812 predicted (+5.2%)**. The tell was internal and arithmetically impossible: **854 impressions against only 790 `lookingAtFixture` transitions**. Cause: `ts` stamped `System.currentTimeMillis()` at publish; the run ran 18% over plan; stretched intra-episode gaps past 1000ms split episodes. Fixed by anchoring `ts` to the planned timeline → full day landed **exact**.

> Core computes both dwell clocks from the envelope `ts`. Anything that lets real-world timing leak into `ts` leaks into the rule.

### 3.3 Circle standoff — the fix was only half-landed

Connect fixed `GeometryConverter` (edge loads 115/115). First circle attempt stood the shopper at radius+600mm, mirroring polygons → `lookingAtFixture` fired but **no `dwellingNearbyFixture`, no impression**. Core documents why: **`Geometry.Circle.edges()` is a stub**, so circles get **containment-only** proximity — the `dwellProximity` band contributes nothing. Must stand ON the footprint. My oracle predicted 1 where reality was 0 — reported before fixing.

### 3.4 Standoff side — only live geometry surfaces this

`standoffPoint` stepped out from the **longest** edge. Denver's gondolas are paired front/back ~1.4m apart, so a back unit's longest edge faces its twin: the shopper stood *between* them and the ray hit the front unit. `GB-R3-U1` produced `998268a9` (R3 **Front**) instead of `e82a21f3` (R3 Back). Now ray-validated per candidate side.

### 3.5 My own test assumptions were wrong twice

Asserted "4m off the edge fires nothing" — wrong, gondola rows are ~1.4m apart so that lands at a neighbour, and the neighbour firing is *correct*. Then "mid-aisle fires nothing" — also wrong, `GA-01` is there. Both were sloppy tests, not code faults. Fix: compute a genuinely clear point rather than guessing coordinates. **Byproduct finding: 74% of Denver's sales floor lies within 1m of a fixture edge** (clearest point only 6.4m out) — dwell is far less discriminating than §8 assumes.

### 3.6 Data-shape bugs the reconciliation gate caught

- **CSV `split(",")`** — assortment has quoted commas AND escaped quotes (`"Camp Bed Air 79"" Inflatable"`); every column after the first quoted name shifted, pulling a non-price field into `price_usd` → ATV $1,961 vs $58.
- **`toInt()` truncates** — 2.2 target with ±0.8 spread collapsed to ~1.6 mean, a systematic 27% unit under-count.
- **Uniform SKU draw** — gave every basket a shot at a $2,999 Riverside gravel bike. **Prices are correct** (real catalog, median $55, max $11,999); §5's price-band weighting was what I'd skipped.
- **`radius_mm` never exists** — the layout emits `properties.radiusX`/`radiusY`. Every circle had a null radius twin-side regardless of core.
- **`_note` string inside numeric mix maps** broke Jackson before any filter could run.
- **Slicing after compression returns 0 rows** — windows must be applied in store time, before the rebase.

### 3.7 A concern I raised and then withdrew

Mid-run I flagged impressions creeping above look-transitions as possible residual splitting. **Withdrawn — I should have reasoned it through before flagging.** `lookingAtFixture` fires only when the fixture *changes*; journeys draw fixtures via `pool.random()`, so a shopper can draw the same rack twice — no transition, but clocks reset, so a second impression. Impressions exceeding transitions is **expected** from repeat visits. The exact 3,664 match proves no splitting occurred.

---

## 4. Key discoveries about core

| Finding | Status |
|---|---|
| **`viewDirection` mandatory; absent = whole event dropped**, costing the dwell half too though the distance clause needs no vector | Ruled: evaluator will pass through, leaving only LOOK unresolved |
| **>1 Hz emit floor is absolute** — below it both clocks reset per sample and `millisTillImpression` is unreachable however long the shopper stands | Load-bearing constraint, now encoded + tested |
| **Sub-1Hz still emits `lookingAtFixture`/`dwellingNearbyFixture`** — transitions flow, pipeline looks healthy, zero impressions form. Worse than nothing happening | Documented |
| **`Circle.edges()` is a stub** → containment-only proximity. A shopper 0.6m from a promo island accumulates no dwell | Connect's KNOWN GAP; real fix is `distanceToBoundary` on `Geometry`, which touches the Android-AR-shared artifact |
| **`crossing` is a dead end in v2** — `sliceId` is a v1 `arealayoutslice` concept; only `onCrossing` implementor is `Journaler.kt`; `crossing_line` shows the never-written fingerprint | Descoped. Footfall NOT blocked — `person_session` is the visit record |
| **`ImpressionEventController` is POST-only** — no front-door impression read | 4th sibling → TWIN-REQ-004 as ONE read-surface brief (ruled) |
| **Zone/space UUIDs are UUIDv5** (deterministic) but the recipe isn't derivable twin-side (130 patterns × 8 namespaces, no match) | Moot — mother fixture map delivered |
| **FR-PLN-08's Notifications gate CLEARED** — rule engine live on master `f14482a` | directive→task smoke unblocked, needs backend watcher |
| **Impression cache lag measured** — 10.7s / 12.7s after `lastDwell`, consistent with 10s `expireAfterWrite` + async jitter | — |

---

## 5. Decisions made

- **Declined a tenant-wide reader password** for space-UUID resolution. Wrong plane (human login bootstrapping a machine integration), a standing tenant-wide secret three weeks after hardening tenant-wide over-reach, and it would have hidden the gap. Bob delivered the fixture map instead.
- **Re-keyed the fixture map from name → code before committing.** Names are localized EN/FR/KO; a name join breaks at Lyon/Busan.
- **Reordered the run plan** (dwell fix before the slice) because step 1's premise — "~10 min, fits one observation window" — was unachievable at 38 min under the old model, and window width wasn't the lever; session length was.
- **Reconciliation tolerances → `max(pct, 3σ)`.** Flat ±10% is unachievable at small n (binomial sd 4.1 vs tolerance 2.2), so a correct generator failed ~half the time. Widening the percentage would have been tolerance-hacking; this converges to ±10% as n grows and passes at both 12% and 100% scale.
- **Uncalibrated values quarantined.** §8 had no equivalent for ENTRANCE/ACCESSORIES_WALL/SERVICE_CLUSTER; `ZoneAffinityModel.uncalibratedRoles` surfaces them and the self-test prints them, so invented numbers can't acquire the authority of sourced ones.
- **`scheduleEvery` deliberately unimplemented** despite being in the locked Q3 spec — no generator needs a cadence, and an unused primitive with drift semantics is design debt.
- **Did NOT run the `psql` verification.** Direct DB access is on twin's Off-Limits list; asked instead. Bob ran it and confirmed 5/5.
- **`com.m8trx.geometry` never touched** — shared with Android AR. Twin wrote its own local planning geometry.

---

## 6. ★ GAPS AT CLOSE — carry these forward

### 6.1 18 of 115 fixtures never browsed — including all three circles

The full day covered **97 distinct fixtures**. The ~18 missed are those outside department bands: `dwellAtZone` only browses fixtures via `fixturesByDept`, keyed on the `department` field, and the three circles (`PI-01`, `PI-03`, `RR-02`) sit in `Z-01` (entrance) with no department. **So the circles did not participate in the day despite being individually proven.** Anyone reading fixture coverage as a heatmap would see three permanently cold promo islands and conclude the platform was dropping them — the same wrong conclusion the converter bug used to cause, now from a twin-side modelling limit.
**Fix:** let journeys browse non-department fixtures (entrance promo islands, accessories wall, GPS cases) via `in_area_zone`, not just department bands.

### 6.2 `impression_event` DB verification for the full day — OWED
Twin verifies over NATS only (direct psql is Off-Limits). Prefix `fullday-0728`, expect **3,664 rows**. Needs Bob or the coordinator.

### 6.3 22,944-row Hasura truncation ceiling — UNTESTED
The evening slice's 854 rows was comfortably under. 3,664 is the first run that could reach it, depending on query shape.

### 6.4 Three DB rows for Promo Island 1 where twin accounts for one — UNRECONCILED
Bob's psql showed 3 rows (8.40/8.53/8.51s); twin can account for one (attempt 2; attempt 1 produced none). Either twin's accounting is short or something multiplied. Small, but unexplained row counts matter in a reconciliation-driven project.

### 6.5 §5 economics are calibrated against the superseded catalog
ATV $58 / avg line $26.40 come from the old 871-SKU running-store catalog, not the live 2,586-SKU chain assortment (median $55, max $11,999). Same staleness class as the §8 zone codes Bob already ruled on. The sampler makes the generator obey the model *as written*; recalibrating §5 is a separate decision.

### 6.6 Publishing throughput, not the schedule, sets wall time
1.1M samples took 44m20s against a 44m20s plan — but the evening slice ran 18% over. Throughput is ~415 samples/s. A larger day or a lower compression factor will bind on the publisher, not the plan.

---

## 7. Branch / deploy state at close

- **`chore/spine-restart-hygiene`** @ `3589952` + this session's docs — 15 commits, pushed, **no PR** (awaiting Bob)
- **`main`** untouched at `b0390fc`; never pushed to
- Twin edge `edge-twin-denver` (`192.168.55.29:4223`) live, API key loaded, full write mode
- Nothing in flight; both background drives and observers stopped
- `M8trxDemo` on mother carries: the day's `impression_event` rows (`fullday-0728`), evening-slice rows (`evening-peak`), single-episode rows, plus ~550 `sale_event`-driven SOLD items from the 3.1 chain-activity stream
- `.twin-state/sold-epcs.txt` at 550 lines

---

## 8. Interlocks worth preserving

Two edges share host `.29`. Every live driver asserts the NATS **`server_name` is `edge-twin-denver`** before emitting a single event, and denylists the office space. `:4222` is `edge-itx-office` — production, real Xovis hardware. A port check alone is too weak; ports get fat-fingered and forwarded, server identity does not. Also removed `TwinConfig`'s `natsUrl` default, which pointed at `:4222` — a default aimed at production is a landmine.
