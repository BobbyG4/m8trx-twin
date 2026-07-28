# TWIN → COORDINATOR — Session 15 status

**Lane:** Twin · **Date:** 2026-07-28 · **Brief:** `BRIEF-TWIN-SPINE-2026-07-28.md`
**Branch:** `chore/spine-restart-hygiene` @ `b6f34ab` (8 commits, pushed, **no PR opened** — awaiting Bob's go)
**Verification:** ktlint + `compileKotlin` + `connectSelfTest` + `peopleSelfTest` (39/39) all green

---

## 1. Headline

**Core's fixture-impression pipeline ran end to end for the first time, with no camera and no lab.** Twin published `objLocation` into the new twin edge; `XovisImpressionEvaluator` ran real point-in-polygon against live fixture geometry, `ImpressionStateMachine` computed real dwell/look clocks, and `FixtureImpression` published on cache expiry. Verified by direct NATS observation across 9 episodes.

**Twin's `ImpressionOracle` is validated against the live edge 7/7** — including both negative controls — so the emit contract can now be iterated locally without the edge.

---

## 2. Brief scope — status

| Item | State |
|---|---|
| **3.1** Turn the stream on | ✅ **DONE** — `connectChainActivity`, ~550 events, zero non-200s. **Stopped on Bob's instruction** pending other fixes; restart is one command. |
| **3.2** ★ Drive the people pipeline over NATS | ✅ **PROVEN** — `connectPeopleDrive` shipped; 9 live episodes; oracle validated. `crossing` **descoped** (§4). |
| **3.3** Build the people generator | 🟡 **FOUNDATION ONLY** — geometry, oracle, browse-episode, zone-affinity re-key all shipped and tested. **`TrafficGenerator`, the runtime skeleton, and journeys are NOT built** — and are partly gated (§4 footfall). |
| **3.4** Hygiene | ✅ **DONE** — 5 stale HOLD FIRE sites (brief said 2); `seed_store.py` de-hardcoded + marked SUPERSEDED. **Secret rotation still owed — Bob's, not closeable from twin.** |

---

## 3. Ground-truth corrections (accepted into m8trx-shared through `9f8f68e0`)

Two brief errors caught before either session built on them:

1. **`viewDirection` is mandatory** (`XovisImpressionEvaluator.kt:311`). Twin never emitted it, so every people-event would have been silently dropped — `impression_event` stays 0 and each session blames the other. The real killer is the **>1 Hz emit floor**: below it both clocks reset per sample and `millisTillImpression` is unreachable however long the shopper stands there.
2. **Connect outbound already exists** — `OutboundWebhookDispatcher` + `OutboundRetryJob`, live-proven by twin in S11 (happy path + retry/heal). The Connect brief stated it did not, which would have had that session rebuild working infrastructure.

Also surfaced: the **edge-server instance was unowned across all three briefs** (→ ruled to the Connect session, now delivered and live).

---

## 4. Findings the coordinator should carry

**`crossing` is a dead end in v2 — descoped from 3.2.** `sliceId` is a v1 concept (`arealayoutslice`, archived schema); v2 has `crossing_line`/`crossing_line_event`/`zone_crossing` and **zero `.sql` migrations mention "slice"**. The only `onCrossing` implementor is `Journaler.kt`. `crossing_line` shows the same never-written fingerprint as `impression_event` did — cascade-delete and retention only, no writer.
> **Consequence:** twin can drive **fixture dwell** and **transactions**, but **not footfall**. The `STORE-OPERATING-MODEL.md` §1 reconciliation identity (`visitors → transactions → revenue`) cannot close platform-side until something consumes crossings. **This gates 3.3's realism gate. Needs a ruling.**

**The silent-failure mode is worse than "nothing happens".** At 0.5 Hz the edge still emits `lookingAtFixture` and `dwellingNearbyFixture` — transitions flow normally and the pipeline looks healthy — while **no impression can ever form**. Anyone debugging would see traffic and conclude it was fine.

**`viewDirection` off costs the dwell half too.** It produced no `lookingAtFixture` and no `dwellingNearbyFixture` either, though the distance clause needs no vector. The gate discards the event before it gets there. **This is the design question Bob + Connect still owe a ruling on** — now demonstrated rather than argued.

**No front-door read for impressions.** `ImpressionEventController` is **POST-only**. That is now the *fourth* sibling of the same gap — compliance `/state` (closed as TWIN-REQ-003), task read, space read, impression read. **Recommend TWIN-REQ-004 be scoped as one Connect read-surface brief, not four one-offs.**

**Zone/space UUIDs are v5 (deterministic).** Twin could not derive the recipe (130 name patterns × 8 namespaces, no match), but **differential probing recovers the mappings through the public surface alone** — no hand-off, no reader password. Confirmed: `GF-R6-U1=030dc90f`, `PW-01=feb54fac`, `GF-R5-U1=970e95e8`. ~20s/fixture. **Twin is therefore not blocked**, though core sharing the derivation recipe remains strictly better and would cover all 929 zones and every future store.

**★ FR-PLN-08's Notifications-spine gate has CLEARED.** Rule engine live on master `f14482a`. The owed **directive→task smoke** is unblocked and runnable **with a backend watcher** (task read is still not `@ConnectExposed` — verified by enumerating every annotation site).

---

## 5. Proven live (9 episodes, twin edge `edge-twin-denver`)

- Impression fires; **duration equals full episode span exactly** — 12000/8000/15000/9000/7000/11400/6600ms
- **Targeting is real** — distinct fixtures return distinct zone UUIDs, reproducibly
- **Multi-fixture journey** — one `objectId` across two fixtures → two impressions
- **Both negative controls silent**, proven inside a window where a positive fired (so silence ≠ dead subscriber)
- **Ray occlusion validated on a non-obvious case** — aiming at `GB-R5-U1` correctly predicted to land on `GF-R5-U1` (paired gondolas ~1.4m apart); the edge agreed, and explicitly targeting `GF-R5-U1` returned the same UUID
- **Cache lag measured** — 10.7s / 12.7s after `lastDwell`, consistent with 10s `expireAfterWrite` + async jitter
- **74% of Denver's sales floor lies within 1m of a fixture edge** — dwell is far less discriminating than §8 assumes; a realism input for journey tuning

---

## 6. Not verified

**No `impression_event` row has been confirmed.** All evidence above is NATS-observed. The DB check is direct psql, which twin's `CLAUDE.md` Off-Limits rule forbids, so it was not run. `objectId`s available for a sharp write-path check: `twin-r2-A-noviewdir` and `-B-slow` should have written **nothing**; `-C-journey` **two** rows; `-D-oraclefix` and `-E-occlusion` **one each**.

---

## 7. Rulings owed

| # | Ruling | Owner | Blocks |
|---|---|---|---|
| 1 | **Footfall** — does anything consume `crossing`? | Bob + Connect | 3.3's reconciliation gate |
| 2 | **`viewDirection` off drops dwell too** — accept, or split the gate? | Bob + Connect | test-matrix design |
| 3 | **TWIN-REQ-004 scope** — one read-surface brief vs four one-offs | Bob | filed, not yet written |
| 4 | **Space/zone UUID route** — derivation recipe vs probing (twin unblocked either way) | Core | speed only |
| 5 | **Secret rotation** (mother Hasura admin) — de-hardcoding did not remove it from git history; instance-wide | Bob | security hygiene |
| 6 | **PR** on `chore/spine-restart-hygiene` | Bob | merge |

---

## 8. Cross-lane

- **Connect session:** delivered the twin edge and `ImpressionNatsSubscriber`; join point is proven from twin's side. Outstanding for them: the `viewDirection` gate ruling, and a read-surface for impressions.
- **Triad session:** Phase-1a confirmed closed and on master. Its rule engine cleared twin's FR-PLN-08 gate. Its §2.3 notification scope-path trap is the **same defect class** as the seat/perms leak twin surfaced in S14 — `connectSiteScopeAudit` is a site-scope gate, and a **user-scope arm is filed-not-built** per Bob's ruling.
- **Standing gate:** re-run `connectSiteScopeAudit` after any core re-seed. Needs `M8TRX_AUDIT_PASSWORD`.

---

## 9. Artifacts

`status/active/TWIN-SPINE-GROUNDTRUTH-AND-RESTART-2026-07-28.md` — full ground-truth note (accepted in full)
`src/main/kotlin/com/m8trx/twin/layer1/` — `FixtureGeometry` · `ImpressionOracle` · `BrowseEpisode` · `ZoneAffinity` · `PeopleDrive` · `PeopleSelfTest`
Gradle: `./gradlew peopleSelfTest` (offline, 39 cases) · `./gradlew connectPeopleDrive` (dry-run default; two interlocks guard the production office edge)
