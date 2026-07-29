# TWIN → COORDINATOR — Session 15 status

**Lane:** Twin · **Date:** 2026-07-28 (refreshed 15:5x, pre-full-day-run) · **Brief:** `BRIEF-TWIN-SPINE-2026-07-28.md`
**Branch:** `chore/spine-restart-hygiene` @ `87f2c5e` (13 commits, pushed, **no PR opened** — awaiting Bob)
**Verification:** ktlint · `compileKotlin` · `connectSelfTest` · `peopleSelfTest` (49) · `scenarioSelfTest` (23) — all green

> **Refreshed deliberately BEFORE the full-day run** so that if the run is interrupted (machine standby is a live risk — it drives from Bob's MacBook over ZeroTier), nothing is lost and a successor can finish the interpretation unaided. §2 is the run card.

---

## 1. Headline

**Core's fixture-impression pipeline is proven end to end, camera-free, at volume.** Twin publishes `objLocation` into the twin edge; `XovisImpressionEvaluator` runs real point-in-polygon, `ImpressionStateMachine` computes real dwell/look clocks, `ImpressionNatsSubscriber` writes `impression_event`.

- **DB-confirmed 5/5** on the single-episode battery (Bob ran the psql check twin cannot).
- **First circle impression ever** — the acceptance gate for Connect's `GeometryConverter` fix. DB-confirmed, 3 rows on Promo Island 1.
- **First volume run** — evening peak, 245,118 samples, 182 shoppers, **854 impressions**, 96 distinct fixtures, 182/182 shoppers producing at least one.
- **A full generated day reconciles to `STORE-OPERATING-MODEL` §1** on all four legs and is ready to drive.

---

## 2. ★ RUN CARD — full day (built, verified offline, NOT yet run)

`connectDayDrive` is built and green. The day is generated, reconciled and oracle-checked **offline first**; the driver refuses to publish a day that does not reconcile.

```bash
cd ~/IdeaProjects/m8trx-twin
set -a; . ./.env; set +a
export M8TRX_NATS_URL="nats://192.168.55.29:4223"     # 4223 = edge-twin-denver. NOT 4222 (office/real Xovis)
export M8TRX_SPACE_ID="e3c9a424-3ced-5288-9756-19935d39f88f"
export M8TRX_SITE_ID="84f2a1c1-fb0a-41b2-9e0d-c9102a22ca7e"
M8TRX_DAY_LIVE=true ./gradlew connectDayDrive --no-daemon
```

Omit `M8TRX_DAY_LIVE` for a dry run — it prints the full plan, the reconciliation table and the pacing check without publishing.

### Expected counts (current model)

| | |
|---|---|
| Shoppers | **790** |
| Samples | **1,100,584** |
| **Expected impressions** | **3,664** |
| Store time → wall time | 11h14m → **~44 min** at 18× |
| Visitors / transactions / revenue | 792 / 192 / $11,586.09 |

> ⚠ **3,664 supersedes the 2,045 figure** quoted in earlier correspondence. 2,045 was measured **before** the zone-vs-fixture dwell fix, when a shopper stood at ONE rack for a whole zone median. On the corrected model a shopper browses several racks, so impressions per visitor rose 2.35 → 4.33 while total samples FELL 4.3M → 1.1M. Do not publish 2,045.

### Useful variants

- `M8TRX_DAY_FROM_HOUR=17 M8TRX_DAY_TO_HOUR=19` — evening-peak slice (~19 min, 812 expected). Windows are applied in **store time** and keep sessions **whole**.
- `M8TRX_DAY_TAG=<prefix>` — tags every `objectId`, giving a clean population to verify against.
- `M8TRX_DAY_SEED` / `M8TRX_DAY_SCALE` / `M8TRX_DAY_COMPRESS` — deterministic; same seed reproduces the day exactly.

### Verifying afterwards

Twin verifies over **NATS** (public surface). The `impression_event` DB check is **direct psql, which twin's Off-Limits rule forbids** — it needs Bob or the coordinator. ⚠ The **22,944-row Hasura truncation ceiling** is untested: the evening slice's 854 rows was well under it, but 3,664 may reach it depending on the query shape.

---

## 3. ★ THE PACING INVARIANT — compress ARRIVALS, never EPISODES

**This is the single most expensive thing to get wrong, and the failure is silent.**

The impression rule has hard absolute timings: `millisTillImpression` 5000ms, both allowances 1000ms, a >1 Hz emit floor, a 10s cache. Compress episode durations and dwell drops under 5s → **zero impressions from a run that looks perfectly healthy for 45 minutes**.

The naive implementation — walk the merged timeline, compress anything that is not intra-episode — **is wrong, and wrong invisibly**. With ~790 *overlapping* shoppers, two consecutive samples on the merged timeline are almost always DIFFERENT people, so nearly every delta reads as inter-episode and gets divided. A shopper's 200ms spacing becomes ~11ms and their 8.4s dwell ~470ms.

**Correct rule, as implemented:**
- Each shopper's **arrival** is shifted by `offset / factor`.
- Within a shopper, deltas **≤ 1000ms** (inside an episode) replay **verbatim**.
- Everything larger — walks between racks, multi-minute zone dwell that emits no samples — is divided.

**Guarded, not trusted:** the driver asserts worst intra-episode spacing ≤ 1000ms and **aborts before publishing** if the rebase moved it. Verified 200ms across every change.

**Related, same class:** wire `ts` is anchored to the **planned** timeline, not `System.currentTimeMillis()`. Core computes its clocks from the envelope `ts`, so publisher jitter would otherwise leak into the rule — see §5.

---

## 4. Brief scope — status

| Item | State |
|---|---|
| **3.1** Turn the stream on | ✅ `connectChainActivity`, ~550 events, zero non-200s. Stopped on Bob's instruction. |
| **3.2** ★ People pipeline over NATS | ✅ **PROVEN** — `connectPeopleDrive`, 12+ live episodes, oracle validated, DB-confirmed. `crossing` descoped. |
| **3.3** People generator | ✅ **BUILT** — runtime skeleton (Q2/Q3/Q6), `OperatingModel`, `TrafficGenerator`, journeys, reconciliation gate, `connectDayDrive`. Full day reconciles. |
| **3.4** Hygiene | ✅ Done. **Secret rotation still owed — Bob's, not closeable from twin.** |

---

## 5. Findings the coordinator should carry

**★ Circle fixtures — acceptance proven, but the fix is only half-complete.** Connect's `GeometryConverter` fix landed and the edge now loads 115/115. Twin produced the first-ever circle impression (`PI-01` → `7dc6fb79`, 8400ms). **But `Geometry.Circle.edges()` is still a stub**, so circles get **containment-only** proximity — a shopper 0.6m from a promo island accumulates *no dwell*. Proven live: that configuration emitted `lookingAtFixture` but no `dwellingNearbyFixture` and no impression. Connect documents this as a KNOWN GAP; the real fix (`distanceToBoundary` on `Geometry`) touches the artifact shared with Android AR and is a decision, not a drive-by. Twin models it and stands ON the footprint. Regression asserts both directions.

**The silent-failure mode, twice confirmed.** At sub-1 Hz the edge still emits `lookingAtFixture` and `dwellingNearbyFixture` — transitions flow, everything looks healthy — while **no impression can ever form**. Worse than nothing happening.

**Publisher jitter leaks into the rule.** The evening slice produced 854 impressions against 812 predicted (+5.2%). The tell was internal: **854 impressions against only 790 `lookingAtFixture` transitions**, which is contradictory — a look-transition fires only when the *fixture changes*, so extra impressions without extra transitions means clocks reset mid-episode. Cause: `ts` stamped at publish time, and the run ran 18% over plan, stretching some intra-episode gaps past 1000ms. **Fixed** (§3); the full day should land on prediction.

**`viewDirection` off costs the dwell half too** — proven live (no `dwellingNearbyFixture` either), which settled the ruling.

**`crossing` is a dead end in v2** — `sliceId` is a v1 concept; only `onCrossing` implementor is `Journaler.kt`. Descoped. **Footfall is NOT blocked**: `person_session` is the visit record (ruled 2026-07-28) and the §1 identity closes on it.

**No front-door read for impressions** — `ImpressionEventController` is POST-only. Fourth sibling of the same gap (compliance `/state`, task read, space read, impression read) → **TWIN-REQ-004 as ONE read-surface brief** (ruled).

**FR-PLN-08's Notifications gate CLEARED** — rule engine live on master `f14482a`. The owed **directive→task smoke** is runnable with a backend watcher (task read still not `@ConnectExposed`).

---

## 6. Model corrections made this session

| Correction | Why it mattered |
|---|---|
| **Zone dwell ≠ fixture dwell** | §8's 6-min department-band median is ZONE dwell across several racks. Charging it to one fixture inflated the day to 4.3M samples / 31-min sessions. Now 30–90s per rack: 1.1M samples, impressions/visitor 2.35 → 4.33. |
| **Standoff side** | Paired gondolas sit ~1.4m apart, so a back unit's longest edge faces its twin — the ray hit the neighbour. `GB-R3-U1` landed on R3 *Front*. Now ray-validated. |
| **CSV quoting** | Assortment has quoted commas and escaped quotes; `split(",")` shifted `price_usd` → ATV $1,961 vs $58. |
| **Basket truncation** | `toInt()` truncated a 2.2 target to ~1.6 mean — systematic 27% unit under-count. |
| **Uniform SKU draw** | Gave every basket a shot at a $2,999 gravel bike. Prices are CORRECT (real catalog); §5's price-band weighting was what I'd skipped. |
| **Tolerances** | Flat ±10% is unachievable at small n (binomial sd 4.1 vs tolerance 2.2). Now `max(pct, 3σ)`, converging to ±10% as n grows. |

**Still flagged, not fixed:** §5's economics (ATV $58, avg line $26.40) were calibrated against the superseded 871-SKU running-store catalog, not this 2,586-SKU chain assortment — same staleness class as the §8 zone codes. Recalibration is a decision, not a drive-by.

---

## 7. Open

| # | Item | Owner |
|---|---|---|
| 1 | **Run the full day** (§2) — 44 min, standby risk | Bob |
| 2 | `impression_event` DB verification (twin cannot — Off-Limits) | Bob / coordinator |
| 3 | Secret rotation (mother Hasura admin; still in git history, instance-wide) | Bob |
| 4 | PR on `chore/spine-restart-hygiene` | Bob |
| 5 | TWIN-REQ-004 — one Connect read-surface brief | Twin, on Bob's go |
| 6 | `Circle.edges()` / `distanceToBoundary` — circle dwell parity | Connect |
| 7 | 22,944-row Hasura ceiling — untested at 3,664 | Coordinator |
| 8 | **Unreconciled:** 3 DB rows for Promo Island 1 where twin accounts for 1 | Twin |

---

## 8. Artifacts

`status/archive/sprint/TWIN-SPINE-GROUNDTRUTH-AND-RESTART-2026-07-28.md` — ground-truth note (accepted in full)
`src/main/kotlin/com/m8trx/twin/` — `runtime/` · `layer1/{FixtureGeometry,ImpressionOracle,BrowseEpisode,ZoneAffinity,PeopleDrive}` · `layer2/Journeys` · `layer3/{OperatingModel,TrafficGenerator,Reconciliation,ScenarioRun,DayDrive}`
`reference/data/chain/stores/dec-us-denver/fixture_ids.csv` — mother fixture map, re-keyed name → **code** (names are localized EN/FR/KO; a name join breaks at Lyon/Busan)
Gradle: `peopleSelfTest` (49, offline) · `scenarioSelfTest` (23, offline) · `connectPeopleDrive` · `connectDayDrive` — both dry-run by default, both guarded by the `edge-twin-denver` interlock
