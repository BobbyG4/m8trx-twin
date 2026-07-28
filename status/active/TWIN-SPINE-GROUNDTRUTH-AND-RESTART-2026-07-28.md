# TWIN — Ground-truth & restart note

**Filed:** 2026-07-28 (Twin session, `main` `b0390fc`) · **Status:** ACTIVE — read before firing `BRIEF-TWIN-SPINE-2026-07-28.md`
**Trigger:** Bob — *"if something is not correct then let's resolve first… we have to go by real code here so we don't waste time."*
**Method:** every claim below was checked against source in `m8trx-services` / `m8trx-api` / `m8trx-twin`, not against ledgers or briefs. Core source was read **read-only, for verification only**, on Bob's explicit instruction — a deliberate, logged exception to the twin segregation rule in `CLAUDE.md` § Posture. No core file was modified.

---

## 0. TL;DR

Three coordinator briefs were filed 2026-07-28 to restart parallel work after a permissions detour. Ground-truthing them found **one blocker that would have silently burned both the Twin and Connect sessions**, and **one factual error** that would have had the Connect session rebuild working infrastructure.

| # | Finding | Severity | Owner |
|---|---|---|---|
| 1 | `viewDirection` is **mandatory** at the impression gate; twin never emits it → every twin people-event is silently discarded | **BLOCKER** | Twin (small fix) |
| 2 | Brief omits the middle link — `XovisImpressionEvaluator` must be running *and* per-space configured; twin does not control it | **BLOCKER** | Core/edge config |
| 3 | Connect brief §1.3/§2.3 "Connect is inbound-only, no outbound trigger exists" is **false** — outbound shipped and twin proved it live in S11 | **HIGH** | Connect session |
| 4 | Impression needs a *sustained dense stream* (>5s look **and** dwell) + ~10s cache lag — not a walk path with pins | Design constraint | Twin |
| 5 | Twin's dataset carries **no mother UUIDs** (`zone_id: null`); `.env` missing `M8TRX_SITE_ID`/`M8TRX_SPACE_ID`/`M8TRX_NATS_URL` | Prerequisite | Twin |
| 6 | `STORE-OPERATING-MODEL.md` is calibrated to the **superseded** 600 sqm single store, not the seeded 10-store chain | Medium | Twin |
| 7 | `Geometry.Circle` proximity **not implemented** — circular fixtures silently skipped | Low | Core (known) |

Everything else in the three briefs that I checked held up.

---

## 1. Where twin left off — Session 14 (2026-07-02 → 07-03)

`main` `b0390fc`, clean tree. Last substantive work 2026-07-03; only a Jackson CVE bump (`ce403ef`, 2.21.4 → 2.21.5) since. Twin has been **parked for ~3.5 weeks** — a good restart position, and the reason the coordinator picked it up.

### 1.1 What shipped in S14

**CORE-REQ-005 parts 1–3 — DONE.**

- **Part 1 — `connectFullLoop` (PR #9).** Composed the built P0 drivers into one parameterized path-(b) loop: *(opt) directive → sale-drift → `items/details` assert SOLD → movement/scan remediate → assert present → per-target compliance expectation*. Grain is (fixture × SKU). Dry-run proven on Denver `GB-R3-U1`, 28 targets → 19/8/1, which surfaced both an under-stock case and the #7 / FR-COLLECT-ID case.
- **Part 2 — `connectStress` (PR #10).** Launch-quality concurrency harness: coroutines + Semaphore, multi-arm sale storm across three site-resolution arms (NoScope / store-xref / site-scoped), dedup + unmapped probes, breakage report, sampled SOLD-verify.
- **Part 3 — compliance read-back smoke.** Core shipped `POST /api/v2/compliance/state` (services PR #76, `925f9a4`); twin fired it live → `200` · 28/28 · **25/2/1** · 0.893.

**TWIN-REQ-003 — SATISFIED.** The `/state` `403 CONNECT_NOT_EXPOSED` wall that made twin drive the whole S13 compliance demo blind is closed. Brief at `m8trx-shared/twin/requirements/TWIN-REQ-003-connect-compliance-readback.md`.

**Live-stress result — the ceiling was found.** ~810 sales across 3 waves: concurrency **6 and 12 clean** (100% `200`, ~9–11/s, SOLD-verify 40/40 post-delay); concurrency **24 → ~50% `502`** across all arms *and* the Bearer read, with instant recovery. No `429` backpressure anywhere. This **reproduced the S8 event-loop starvation defect from the public API** — an external, at-scale confirmation of something previously only seen internally. Backend's independent deep-dive agrees. Fix is `429`@nginx + offload/MVC, explicitly **post-demo, not demo-blocking**.

> **Still true today.** Both the Twin and Connect briefs repeat the ≤12 concurrency ceiling. Honour it — anything above is re-finding a known core bug, not testing a surface.

### 1.2 The permissions detour — what sidetracked us

This is the "stumbling point" that consumed the back half of S14 and is why the tracks went quiet.

While provisioning demo logins, twin surfaced a **seat/permissions tenant-wide leak**: all 250 `@decathlon-demo.com` users resolved tenant-wide rather than to their site. Root cause was core's `seed_users.py` inserting a `user_tenant_membership` row per site-role. Twin's own roster was verified clean — this was a core seed bug, not a twin dataset bug.

That finding escalated into **Strand V / SECHARDEN site-scope hardening**, and twin built `connectSiteScopeAudit` (PR #11, `e390e0f`) as the external acceptance gate: a site-scoped user probed across token / store-picker / cross-site-read planes plus gated writes, at scale.

- Baseline **24 RED** → post-hardening (core S207) **0 RED**.
- The broad **151-table sweep caught 3 event/audit leaks that core's own core-36 / tail-35 mapping missed** — `integration_event`, `item_custody_event`, `unified_audit_event` → core fixed → twin re-verified 0.
- Write plane confined: cross-site confused-deputy all `403`; own-site `403` is the correct capability limit.

**Worth being clear about the cost/benefit.** The detour was expensive — it ate the session and stalled the demo-nucleus tracks for weeks. But it is also the single highest-value thing twin has produced: an *external, at-scale* gate caught what core's internal mapping did not. That is the force-function working exactly as `CLAUDE.md` § Requirements-flow describes. It was not wasted time; it was unscheduled time.

> **↻ Standing gate.** Re-run `connectSiteScopeAudit` after core re-seeds (the `seed_users.py` fix) or whenever strands change. Needs `M8TRX_AUDIT_PASSWORD` (cohort password, out-of-band).

### 1.3 What S14 left owed — the tracks we were on

These are the open threads the restart has to reconcile with, from `STATUS.md` § NEXT SESSION PRIORITIES:

1. **directive→task smoke** (CORE-REQ-005 part-3, still owed). Drive a directive as INPUT, backend verifies the auto-created tasks as OUTPUT. **See §4 — this gate has now cleared.**
2. **#7 receive→relocate re-fire** — gated on core's FR-COLLECT-ID / Identifier Resolution Pipeline (EPC→SKU decoder). Twin owes three artifacts when it kicks off: the EPC→EAN→SKU test-vector fixture (from the 169k validated tags), the Hansae 2nd-scheme template (`reference/data/mk-trend/`), and a resolution-rate health metric.
3. **The full "play" / activity runtime** (`ACTIVITY-PLAN.md` / `LIVE-OPERATIONS.md`) — drift and remediation are proven; what's missing is animating the ongoing 24/7 operation. **This is what the new twin brief calls the "spine."**
4. **conc-1 latency baseline** — ~5 requests, cheap, isolates per-txn cost from contention.

### 1.4 Harness inventory (all live-proven)

12 `connect*` drivers in `com.m8trx.twin.connect`, `connectSelfTest` green:
`connectMultiSiteSmoke` · `connectSaleStream` · `connectChainActivity` · `connectSelfVerify` · `connectScanSweep` · `connectOutboundReceiver` · `connectPlanogramDrive` · `connectMovementDrive` · `connectReceiveDrive` · `connectFullLoop` · `connectStress` · `connectSiteScopeAudit`.

Seeded chain on mother (`M8trxDemo`, tenant `ecfa6903-5c50-439f-8f80-185982de944e`): **14 sites · 30 spaces · 929 zones · 2,586 products · 102,675 items** (84,266 floor / 18,409 BOH).

---

## 2. The restart — three parallel briefs, 2026-07-28

The coordinator filed three briefs off `PLAN-CONVERGENCE-TO-SHIP-2026-07-28.md` §2.1:

| Brief | Lane | Live branch | State |
|---|---|---|---|
| `BRIEF-TWIN-SPINE-2026-07-28.md` | Twin | — | this session |
| `BRIEF-CONNECT-FINISH-2026-07-28.md` | Backend/Web | `feat/connect-finish-s272` | **in flight** — 3 untracked files, 0 commits vs master |
| `BRIEF-TRIAD-FINISH-2026-07-28.md` | Backend/Web | `fix/notification-scope-default` | **in flight** |

The twin brief's ★ item (3.2) and the Connect brief's ★ item (2.1) are **the same join point**, and both say *agree the event shape before either side builds*. That window is genuinely still open — the Connect session has written files but committed nothing.

---

## 3. Ground-truth findings

### 3.1 ★ BLOCKER — `viewDirection` is mandatory, and twin never sends it

`m8trx-services/edge-xovis-impression/.../XovisImpressionEvaluator.kt:309-311`:

```kotlin
override fun onObjectLocation(eventId: String, ts: Long, e: AreaEvent.ObjLocation) = runBlocking {
    if ((e.hasTag != true) && e.viewDirection != null) {
        impressionStateMachine.next(..., e.viewDirection!![0], e.viewDirection!![1])
    }
}
```

Two gates. `hasTag != true` (tagged objects = staff, excluded — twin sends `null`, so this passes). And **`viewDirection != null`**, which is not optional.

Twin's side:
- `domain/AreaEvent.kt:23` — `viewDirection: Array<Double>? = null`
- `Main.kt:18-26` — the one-and-only smoke does not set it
- `TRAFFIC-GENERATOR-SKETCH.md` §3 `spawnCustomer` — does not set it

**Consequence:** every `objLocation` twin publishes today is dropped at line 311. No exception, no warning, nothing above `debug`. `impression_event` stays at 0 rows and *both sessions conclude the other side is broken.* This is precisely the failure mode the brief pair was written to avoid.

The brief's claim that twin publishes "the exact `AreaEvent` shapes core's `ImpressionStateMachine` consumes" is **shape-true but behaviour-false**: the wire shapes match field-for-field (verified below), but an optional-in-the-type field is mandatory-in-the-gate.

**Wire-shape verification** (twin `domain/AreaEvent.kt` vs core `area/AreaEvent.kt`):

| Body | Core fields | Twin | Verdict |
|---|---|---|---|
| `ObjLocation` | objectId, x, y, height?, isMale?, faceMask?, hasTag?, viewDirection?, layoutId | identical (+ `type` literal) | ✅ match |
| `ObjEviction` | objectId, layoutId | identical (+ `type`) | ✅ match |
| `Crossing` | sliceId: UUID, objectId, leftToRight, sourceEventId?, layoutId | identical (+ `type`) | ✅ match |

Envelope also matches: core `AreaEvent.subscribeNats` (`:13-27`) reads `type` / `ts` / `id` / `areaId` / `body` and filters on hyphen-stripped `areaId`; twin's `NatsEmitter` sets `areaId = config.spaceIdNoHyphens` and dual-publishes legacy + modern. Core subscribes **legacy only** (`area.<id>.>`, `:172`) — twin's dual-publish covers it.

**Fix (twin-side, small):** emit `viewDirection` as a unit vector aimed at the fixture the shopper is browsing. This is a design constraint on the people generator, not a patch — worth landing before 3.3 is written rather than after.

### 3.2 ★ BLOCKER — the brief omits the middle link

Twin brief 3.2 reads as `NatsEmitter → ImpressionStateMachine`. The real chain is:

```
twin objLocation (NATS: area.<spaceIdNoHyphens>.objLocation)
  → XovisImpressionEvaluator          [edge-server JVM, implements AreaEvent.Listener]
  → ImpressionStateMachine            [point-in-polygon at ingest]
  → AreaEvent.FixtureImpression       [cache 10s → published ON EXPIRY]
  → ImpressionNatsSubscriber          [NEW — being written now, untracked]
  → POST /api/v2/visionai/impressions
  → ImpressionEventService → impression_event row
```

`XovisImpressionEvaluator` does **nothing** until `onConfigChange` supplies a `spaceId` plus four tuning constants (`goAwayAllowanceMillis`, `lookAwayAllowanceMillis`, `dwellProximity`, `millisTillImpression` — `:263-267`). It then loads fixtures via `GetSpaceExpandedQuery` filtered to `zone_type == "fixture"` with convertible geometry (`:145-160`).

**That is edge-side config twin does not control.** The brief's "no cameras and no lab" is true about cameras — but it still requires an **edge-server instance running and configured against a twin space on M8trxDemo**. Nobody owns that in either brief. It is the single largest unstated dependency in the pair.

`ImpressionNatsSubscriber` additionally needs, edge-side:
- `M8TRX_SITE_ID` set to a real UUID — else every impression is skipped (`:139-147`)
- `M8TRX_EDGE_CONNECT_API_KEY` — else writes go unauthenticated and `CapabilityFilter` rejects them (`:79-85`)

### 3.3 Impression requires a sustained dense stream

`ImpressionStateMachine.kt:94-101` — an impression is created only when `lookingAt != null` **and**:

```kotlin
i.lastLook!!.minusMillis(millisTillImpression).isAfter(i.firstLook) &&
i.lastDwell!!.minusMillis(millisTillImpression).isAfter(i.firstDwell)
```

Both look **and** dwell must exceed `millisTillImpression` (default **5000ms**, `:267`). So twin must emit a **multi-sample position stream** — Xovis-like cadence, view ray held on one fixture for >5s — not a sparse walk path with one pin per waypoint. Then `FixtureImpression` sits in a `expireAfterWrite(10, SECONDS)` cache (`:114`) and publishes **on expiry**, so expect **~10s additional lag** before it reaches the subscriber. Budget for that when reading results; a run that looks like it produced nothing may simply not have waited.

This materially shapes 3.3: the people generator's emit cadence is a correctness requirement, not a realism knob.

### 3.4 Circle geometry is silently skipped

`ImpressionStateMachine.kt:74-76` logs a warning and returns `false` for `Geometry.Circle`. Twin's circular front-of-store feature displays would be invisible to the pipeline. Denver's sales floor has **115 `POLYGON Z` fixtures**, which are fine — so target those. Not worth filing; just don't build a demo beat on a circular fixture.

### 3.5 ★ The Connect brief has a factual error — outbound already exists

`BRIEF-CONNECT-FINISH` §1.2 states *"Connect is inbound-only. No outbound trigger exists"* and §2.3 scopes outbound triggers as greenfield. Real code:

- `main-server/.../integration/OutboundWebhookDispatcher.kt` — outbound field-map → JSON → optional HMAC (`X-M8TRX-Signature`) → POST to `endpoint_config.url` → one `integration_event(direction='outbound')`. Increment 2 adds `next_attempt_at` exponential backoff, canonical-event stash for replay, `severity='poison'` escalation after MAX_ATTEMPTS, tenant-IT alerting.
- `main-server/.../integration/OutboundRetryJob.kt` — the redelivery job, re-resolves channel config live so an admin fixing a bad URL heals in-flight deliveries.
- Called from `StocktakeService.kt:529` and `IntegrationController.kt:179` (the test-trigger endpoint).

**And twin exercised this loop end-to-end in Session 11** — LAN receiver at `192.168.55.210:8088`, HMAC-verified accept, then the retry→heal path (failMode=500 → scheduled → flip to 200 → healed, `attempt_count=2`). `connectOutboundReceiver` (PR #6) is a standing driver for it.

**Correct framing for §2.3:** *add two dataTypes (`inventory_state_change`, `alarm`) to an existing, reliability-hardened dispatcher.* Not "Connect has no outbound path." If the Connect session takes §1 at face value it will rebuild working infrastructure and twin will have a second receiver to reconcile.

### 3.6 No mother UUIDs in the twin dataset

`reference/data/chain/stores/*/layout.json` carries `zone_id: null` on every zone — the dataset has **mm geometry but no mother identifiers**. And `.env` has no `M8TRX_SITE_ID`, `M8TRX_SPACE_ID`, or `M8TRX_NATS_URL`, so `TwinConfig.fromEnv()` (`TwinConfig.kt:14-21`) hard-fails on the NATS path.

Needed before a single people event can publish:
- **space UUID** → `objLocation.layoutId` + the NATS subject + envelope `areaId`
- **crossing-slice UUID** → `Crossing.sliceId` is a non-null `UUID`; twin only knows the code `CS-01`
- (fixture zone UUIDs are resolved core-side by point-in-polygon — twin does *not* need them)

All obtainable over public read surfaces (`HasuraClient.kt` exists, or the Bearer plane). **This is prerequisite work not costed in the brief.**

The dataset *is* well-suited otherwise: every fixture carries `rect_mm` **and** `POLYGON Z` in the space frame, so twin can compute genuinely in-polygon walk coordinates locally and the point-in-polygon at core's ingest will actually hit fixtures instead of empty floor.

### 3.7 The operating model is calibrated to a store that no longer exists

`reference/data/STORE-OPERATING-MODEL.md` is CALIBRATED v1 (2026-06-02) against the **superseded 600 sqm / 871-SKU / 149-fixture single Manhattan store**. Live Denver is 28.5m × 23m, 115 fixtures, six `D-01…D-06` sport-universe department bands, fitting rooms as their own space.

- **Transfers cleanly:** the funnel — 22% conversion, $58 ATV, 2.2 UPT, hourly arrival curve, persona mix, 1.6% shrink, dwell medians.
- **Does not transfer:** §8 zone-affinity is keyed to literal codes. `Z-01` Entrance, `Z-02` Checkout, `Z-06` GPS, `Z-08` Footwear Bench, `Z-09` Gait Analysis all still exist; **`Z-04` "Main Sales Floor" and `Z-10` "Fitting Rooms" are gone.**

**Recommended fix:** re-key affinity off `zone_type` + `department` rather than literal Z-codes. Same effort, and it then works across all 10 stores unchanged instead of one.

### 3.8 ★ ADDENDUM (post-ruling) — `crossing` is a dead end; descope it from 3.2

Found while doing the §3.6 UUID-resolution prerequisite. Twin brief 3.2 names **three** shapes twin
publishes — `objLocation`, `objEviction`, `crossing`. The third does not survive contact with v2.

**`sliceId` is a v1 concept with no v2 table.** Core's `AreaEvent.Crossing` still declares
`sliceId: UUID`, but `slices` exists only in the **archived** v1 data schema
(`m8trx-api/graphql/_archived/data/…`, type `arealayoutslice`). In v2 the tables are `crossing_line`,
`crossing_line_event`, `zone_crossing` — and **zero `.sql` migrations across `m8trx-services` mention
"slice" at all**. There is no resolution path from twin's `CS-01` to a v2 `sliceId`.

**And nothing consumes the event.** The only implementor of `onCrossing` is
`area/journal/Journaler.kt:44` — a journal writer. No traffic or footfall derivation subscribes.

**`crossing_line` shows the never-written signature.** It appears in exactly three places:
`SiteScopeCoverageGuardTest` (the table inventory), `TenantService` (cascade delete), and
`HypertableRetentionJob` (retention list). That is the *same* fingerprint the Connect brief used to
establish `impression_event` had never been written — cascade-delete and retention only, no writer.

**Consequences:**
1. **Descope `crossing` from 3.2.** Building it means inventing a `sliceId` against a table that
   doesn't exist, for an event whose only consumer is a log. Pure waste. `objLocation` +
   `objEviction` are the live pair.
2. **The crossing-slice UUID resolution in §3.6 is moot** — drop it. Space UUIDs are still required.
3. **★ Twin cannot drive footfall.** Door-count/entry-crossing is the natural source of *visitors*,
   and the entire `STORE-OPERATING-MODEL.md` §1 reconciliation identity is anchored on it
   (`visitors → transactions → revenue`). With no crossing consumer, twin can drive **fixture
   dwell/impressions** and **transactions** (already live via `sale_event`), but **not the visitor
   count that the funnel divides into.** 3.3's reconciliation gate (§4 of `TRAFFIC-GENERATOR-SKETCH.md`)
   cannot close on the platform side until something consumes crossings. This should be a ruling, not
   an assumption — flagged for Bob + Connect.

### 3.9 What checked out

- Twin is parked and clean at `b0390fc`, last substantive work 07-03 — ✅
- All six Connect canonical types live-proven via `WebhookClient.push()` — ✅
- `connectChainActivity` is a paced multi-store stream (60 events / 300s default, weights 70/12/10/8, single-threaded — safely under the conc-12 ceiling) — ✅
- `connectStress` is a working concurrency harness — ✅
- Seeded chain figures (14/30/929/2,586/102,675) — ✅
- `impression_event` has never been written — ✅ confirmed by `ImpressionEventService.kt:22`, *"0 rows at 2026-07-28"*
- The people layer does not exist in `src/` — ✅ no `layer1`/`layer2`/`layer3`/`runtime` packages
- Stale `HOLD FIRE` comments — ✅ but at **5 sites, not 2**: `ConnectMovementDrive.kt:17`, `ConnectPlanogramDrive.kt:15`, `sim/MovementDriver.kt:20,48`, `sim/PlanogramDirectiveDriver.kt:67`
- `scripts/seed_store.py:20` committed admin secret — ✅ and **worse than stated**: `SPACE_ID`/`SITE_ID`/`TENANT_ID` (`:21-23`) are hardcoded to the **pre-chain tenant** `14d052b0…`, not `ecfa6903…`. The file is stale as well as leaky.

---

## 4. The Triad path — and twin's join to it

Bob is right that this is one of the places twin left off.

**Triad Phase-1a is CLOSED and on master.** `f14482a` (services PR #75, `feat/triad-notifications`) is confirmed present on `master`. Per `BRIEF-TRIAD-FINISH` §1: the Z-05 scheduler, FCM/APNs delivery worker, completion/escalation notify, `/tasks` (My Tasks, claimable Team Queue, detail modal, `task:read` + site gate), the 409 claim-race resync, and **the rule engine and recipient resolution (S201, `f14482a`)** all shipped. `GetNotifications.graphql` and `GetUnreadNotificationCount.graphql` both exist in `m8trx-api/graphql/v2/queries/`.

### ★ This clears a standing twin blocker

`TRACK-TWIN.md` and `STATUS.md` both list **FR-PLN-08 compliance-check task-gen as "gated on the Notifications spine (Triad Slice-1), deferred behind the 07-30 critical path."**

**That gate has cleared.** The rule engine is live on master. So twin's owed **directive→task smoke** (CORE-REQ-005 part-3, `STATUS.md` NEXT item 1) is no longer blocked on Triad — it is runnable as soon as a backend watcher is available.

### The one remaining constraint on that smoke

`TaskController.kt` carries **no `@ConnectExposed` annotation**. Confirmed by enumerating every `@ConnectExposed` site in `main-server`: `TryOnZoneController`, `IntegrationController`, `ComplianceController`, `ScanController` — **not** `TaskController`. `CapabilityFilter.kt:140-143` default-denies service principals to anything not `@ConnectExposed`.

So twin's S14 finding stands: **task read is JWT-only, `403 CONNECT_NOT_EXPOSED` to twin's Connect key.** Twin can drive the INPUT (fire a directive) but cannot self-verify the OUTPUT (the auto-created tasks). The smoke must be **run with backend watching**, exactly as `STATUS.md` NEXT item 1 says. This is the same shape as TWIN-REQ-003 before core closed it — if it recurs often enough, it is a candidate TWIN-REQ for a scoped task read on the Connect read-surface.

### Triad ↔ Twin coupling for the notification smoke

Triad §2.1 (the notification bell) and §2.3 (the notification scope-path trap — `scope-path-spec.json` defaults notification scope to `tenant_direct`, correct user-scope arrives only via `bespokeSelect` override) are the surfaces twin's `STATUS.md` refers to as *"the Notification smoke as it lands."* Once the bell ships, twin drives a directive → rule engine creates tasks → notifications fire → the bell shows a real unread count. **That is the full Triad↔Twin loop and it is now within reach.**

Worth flagging to Bob: §2.3's scope-path trap is a **cross-user leak class**, and it is the same *category* of defect as the seat/permissions leak twin surfaced in S14. `connectSiteScopeAudit` is a site-scope gate, not a user-scope gate — but it is the natural place to extend if we want an external gate on this one too. **Not filed; flagging as an option.**

---

## 5. Consolidated blocker board

| Blocker | Owner | State |
|---|---|---|
| `viewDirection` mandatory at impression gate | **Twin** | Fix is small; land before 3.3 |
| edge-server instance configured for a twin space | **Core / edge** | **Unowned in all three briefs** — needs a decision |
| space + crossing-slice UUID resolution | **Twin** | Prerequisite; public read surfaces suffice |
| Outbound already exists — Connect brief §1.3/§2.3 wrong | **Connect session** | Relay before they build |
| FR-COLLECT-ID / FR-INTEG-04 (EPC-only receive → `product_id` NULL) | Core | Open; twin re-fires #7 when pinged deployed |
| Task read not `@ConnectExposed` | Core | By design today; run smoke with backend watching |
| S8 event-loop starvation (conc 24 → ~50% `502`) | Core | Open, post-demo. **Keep concurrency ≤12** |
| Auth 500 / Hikari pool starvation | Core | Open |
| `commerce_projection` writer (TWIN-REQ-002) | Core | FILED 2026-06-11, awaiting absorption |
| Image pipeline · no cold-start location · no EAS-alarm subscriber · dedup-shadows-failed-retry | Core | Open, unchanged |
| ~~FR-PLN-08 gated on Notifications spine~~ | — | ✅ **CLEARED** — rule engine live on master `f14482a` |

---

## 6. Recommended sequence

Revised from the brief's order, given the findings:

1. **3.1 — turn the stream on.** Free, no new code, no dependencies. `connectChainActivity` against the seeded chain, concurrency ≤12. Core gets live-moving data under the surfaces it is building, starting now.
2. **Relay the two corrections** — outbound already exists (Connect session); `viewDirection` is the gate (both sessions). Cheapest possible intervention, highest value, and the §5 shape window is still open.
3. **3.4 — hygiene.** Unblocked and cheap: 5 stale `HOLD FIRE` sites, and `seed_store.py` (secret **and** stale tenant UUIDs).
4. **3.2 prerequisite — UUID resolution.** Resolve space + crossing-slice UUIDs over the public read surface, stash alongside `site_ids.csv`. Needed by everything downstream.
5. **3.2 — the join point,** *once someone owns the edge-server config.* Build the AreaEvent people driver with `viewDirection` and a dense emit cadence. Target `impression_event`'s first row ever.
6. **3.3 — the people generator,** with §3.3's cadence requirement as a hard constraint and §3.7's re-keying folded in.
7. **Directive→task smoke** (owed from S14) — now unblocked, needs a backend watcher.

Items 1–4 are entirely twin-side and can start immediately. Item 5 has an unowned dependency.

---

## 7. Open decisions for Bob

1. **Who owns the edge-server instance** configured against a twin space on M8trxDemo? Without it, the join point's success criterion — `impression_event`'s first row from a twin-driven run — is unreachable, and neither the Twin nor the Connect brief assigns it.
2. **Do the corrections get written into `m8trx-shared/status/active/`** for the coordinator and the two live sessions, or does this note stay in the twin lane with Bob relaying? Writing to `m8trx-shared/status/` is core territory and needs an explicit instruction.
3. **Does the stale operating-model calibration reset 3.3's scope?** Re-keying is cheap, but it means the "already designed and calibrated, not built" framing in the brief understates the work.
4. **Should `connectSiteScopeAudit` grow a user-scope arm** to gate Triad §2.3's notification scope trap externally — the same way it gated site scope?

---

## 8. Provenance

Verified against, read-only: `m8trx-services` (`area/AreaEvent.kt`, `impression/ImpressionStateMachine.kt`, `edge-xovis-impression/XovisImpressionEvaluator.kt`, `edge-server/visionai/ImpressionNatsSubscriber.kt`, `main-server/visionai/ImpressionEventService.kt`, `main-server/integration/{OutboundWebhookDispatcher,OutboundRetryJob,IntegrationController,IntegrationIngesters}.kt`, `main-server/auth/{CapabilityFilter,ConnectExposed}.kt`, `main-server/task/TaskController.kt`, git branch/commit state); `m8trx-api/graphql/v2/queries/`; `m8trx-twin` (`domain/AreaEvent.kt`, `layer0/NatsEmitter.kt`, `Main.kt`, `TwinConfig.kt`, `connect/ChainActivityStream.kt`, `build.gradle.kts`, `reference/data/chain/stores/*/layout.json`, `reference/data/STORE-OPERATING-MODEL.md`, `reference/architecture/{PERSONA-SCHEMA,TRAFFIC-GENERATOR-SKETCH}.md`, `scripts/seed_store.py`, `.env` key names only).

No core file modified. No credential values read or recorded.
