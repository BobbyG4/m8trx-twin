# Session 14 — 2026-07-02 · CORE-REQ-005 parts 1+2 (full-loop compose + at-scale stress); reproduced S8 (event-loop starvation) from the front door

**Arc:** Surface build — planogram/compliance + Triad (demo nucleus, target 2026-07-30). Comms arc `1782944142.731659`.
**Branch/deploy at close:** `main` — PRs **#9** + **#10** merged, clean. `m8trx-shared` — TWIN-REQ-003 pushed (`118e495`). Session-sync docs committed (push may be guard/Bash-gated at close — see §Close state).

---

## What shipped

### CORE-REQ-005 part 1 — compose the built drivers into ONE parameterized full-loop (PR #9, merged)
- **`sim/FullLoopDriver.kt`** — orchestrator. `plan()` pure/offline (reads site_ids/planogram/epcs/sold-log, no network) + phased `execute()`. Path-(b) loop: *(opt) directive → sale-drift → items/details assert SOLD → movement/scan remediate → items/details assert present → per-target compliance **expectation**.* Targets at **(fixture × SKU)** grain, mirroring `compliance_target`.
- **`ConnectFullLoop.kt`** + **`connectFullLoop`** gradle task — dry-run default, single `M8TRX_LOOP_LIVE` gate.
- **Assertion split (locked):** twin asserts INPUT over Connect (`items/details` = sold/moved); compliance OUTPUT is the reported *expectation* (the paired session's oracle) because `GET /state` → 403.
- Self-test `fullLoopPlan()`. Dry-run proven: Denver `GB-R3-U1` = **28 targets**, tally **19 compliant / 8 partial / 1 non_compliant** @ drift=1; under-stock + the `sku=2706524 req=1` **FR-COLLECT-ID (#7)** case surfaced as unremediable.

### CORE-REQ-005 part 2 — launch-quality stress harness (PR #10, merged; carried TWIN-REQ-003 twin-docs)
- **`sim/StressHarness.kt`** — `plan()` (pure) + concurrent `execute()` (kotlinx.coroutines + `Semaphore`). Fires a bounded-parallel `sale_event` storm across all retail stores over the **3 site-resolution arms** (`SaleArm`: NoScope EPC / store-code xref / site-scoped SKU); **edge probes** (same-`external_sale_id` dedup replay + unmapped-store quarantine); **breakage report** (per-arm ok/err, status + error-code histograms, 429 count, throughput) + sampled `items/details` SOLD-verify.
- **`ConnectStress.kt`** + **`connectStress`** task — dry-run default, `M8TRX_STRESS_LIVE` gate.
- Self-test `stressPlan()`. Dry-run: 10 stores → 504 sales planned, sends nothing.

### TWIN-REQ-003 filed (compliance/directive read-back over Connect)
- Brief: `m8trx-shared/twin/requirements/TWIN-REQ-003-connect-compliance-readback.md`, pushed to shared `main` (`118e495`). STATUS/TRACK requirement tables + blocked-lines updated; S12 draft annotated.

---

## Live stress run (clear-to-hammer, M8trxDemo, ~810 sales) — THE headline finding

Bounded ramp, webhook `sale_event`s across 10 retail stores, 3 arms:

| Wave (runId prefix) | Load | Result |
|---|---|---|
| `twin-stress-w1-1782951337` | 154 @ conc **6** | 154/154 `200`, ~9.1/s; SOLD-verify **40/40** post-delay |
| `twin-stress-w2-1782951454` | 254 @ **12** | 254/254 `200`, ~11.0/s — clean *above* S8 pool≈10 |
| `twin-stress-w3-1782951538` | 404 @ **24** | **205 `200` / 199 `502`** — all arms + the Bearer read; recovered instantly (3/3 `200` after) |

**Findings (handed to backend via Bob relay):**
1. **Safe concurrency ceiling ≤12; conc 24 → ~50% `502`** (upstream choke). Limit sits between.
2. **Throughput plateaus ~10–11/s** across 6→12→24 concurrency; per-request latency *rose* with concurrency (0.66s→1.09s W1→W2) → **saturated server-side resource / contention**, not client/network.
3. **No graceful backpressure** — the cliff is a `502`, never a `429`/Retry-After.
4. **received→SOLD is async** — immediate verify under-reports (5/20), post-delay 40/40.
5. **Reproduced S8 from the public API.** Backend's S8 deep-dive independently confirms: root = **event-loop starvation** (Netty event loop blocked by the synchronous audit-cascade/blocking work). Fix = **429 at nginx** (not an in-Netty WebFilter) + offload/MVC — **post-demo, NOT demo-blocking**. NOT a demo blocker: real traffic is hundreds/*day*; ~10/s is ~3 orders of headroom.

---

## Other discoveries / gotchas

- **Task-read `GET /api/v2/tasks` → `403 CONNECT_NOT_EXPOSED`** (JWT-only) — same wall as compliance `/state`. So the directive→task smoke = twin drives directive (INPUT) / backend session verifies auto-created tasks (OUTPUT). Sibling to TWIN-REQ-003 (folds into the same Connect read-surface). Bearer otherwise **confirmed LIVE** (`items/details` 200).
- **`.env` is NOT auto-loaded by gradle `JavaExec`, and the daemon caches a stale env** → live drives throw `M8TRX_TENANT_ID is not set`. **Fix for all live runs:** `set -a; . ./.env; set +a` then `./gradlew connect… --no-daemon`. (Dry-run + self-test need no creds.)
- **Comms arc churn:** the coordinator re-rooted the arc 3× before it stuck (`1782944142.731659`); presence posts mis-threaded because `slack-send` threads to the `comms-arc-thread` config value and it lagged. Landed clean after repointing config + advancing `.lastseen-twin`. Late-session: Bob switched to manual relay (all sessions low on context) + the **Bash auto-mode classifier went temporarily unavailable** (Anthropic-side) — blocked `curl`/git near close.

## Decisions
- **Stress ramp stopped at the breakage** (controlled, not infinite throttle) once the `502` ceiling was found.
- **Handed off** the directive→task smoke + conc-1 baseline to next session (close-time; the smoke needs backend watching, and backend was also closing).

---

## Open threads → next session
1. **directive→task smoke** — rule-engine is **LIVE on master (`f14482a`)**. Twin drives a directive (`connectPlanogramDrive`, or `connectFullLoop` with `M8TRX_LOOP_PUBLISH_DIRECTIVE=true`) = INPUT; backend session verifies the auto-created tasks = OUTPUT (task-read 403 to twin). **Run WITH backend watching.**
2. **conc-1 latency baseline** — fire single sales at concurrency 1 to isolate per-txn cost vs contention (cheap; localizes the event-loop-starvation root: ~1s solo = fat sync work; snappy solo but craters under load = pure contention).
3. **Part-3 synchronized surface smokes** as Phase-2 surfaces land: Task = LIVE (done). **Notification next** (backend building GetNotifications/count + a **notification RLS user-scope security fix** — priority). Drive a loop event → verify the in-app notification row/badge/deep-link.
4. **Live-fire reminder:** `set -a; . ./.env; set +a` + `--no-daemon`.
5. **Backend carry (awareness):** 4/5 Phase-1a services shipped (Task LIVE; rule-engine + recipient-resolution + NotificationService merged, master `f14482a`); OPEN = #5 business-hours scheduler (last) · notification frontend half + RLS fix (priority) · demo-artifact cleanup (disabled SMOKE rule `c05856af` + 2 proof tasks in M8trxDemo).

## Close state
- `main`: PRs #9 + #10 merged, working tree clean pre-handoff.
- `m8trx-shared`: TWIN-REQ-003 pushed (`118e495`).
- Session-sync docs (this file + STATUS/SESSION-LOG/TRACK): committed at close; **push may be gated** (direct-main-push guard + the temporary Bash-classifier outage) — if so, next session or Bob pushes.
