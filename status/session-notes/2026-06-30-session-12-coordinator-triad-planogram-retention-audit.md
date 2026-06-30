# Session 12 — Coordinator/Planning — Triad + Planogram designs · retention audit · Mode-3-MVP correction

**2026-06-30 · Coordinator track (NOT twin implementation).** Bob redirected from the S11 "PICK UP HERE" Connect-runtime
continuation to a **coordinator/planning** session: design the coming FRs (Task/Calendar/Notification triad +
Planogram) and assess the decisions to open the Connect path. **All deliverables authored into CORE
(`m8trx-shared/status/active/`), Bob-directed** — twin code untouched (main `f25cbcc`).

> Posture note: this is the [[feedback_coordinator_sessions_write_to_core]] pattern — a twin-repo session that
> legitimately authors core artifacts because Bob explicitly framed it coordinator-track and pointed at the files.
> Default twin-segregation overridden by explicit direction. No core source/migrations/`9a` edited; FR changes flagged.

## What shipped (all commits in `m8trx-shared`, not twin)

| Doc (`m8trx-shared/status/active/`) | Commit | What |
|---|---|---|
| `TASK-CALENDAR-NOTIFICATION-TRIAD-RESOLVED-DESIGN-2026-06-30.md` | `c725660` | The triad, full-to-FRs over existing schema + a Connect-decoupled Slice-1 |
| `PLANOGRAM-RESOLVED-DESIGN-2026-06-30.md` | `c725660`→`3dd114d`→`7395d44` | 3 ingestion modes + authoring; F7 hardened; Mode 3 → MVP; F9/F10 resolved |
| `HISTORICAL-DATA-RETENTION-AUDIT-2026-06-30.md` | `7e0eb2f` | Model-wide retention/durability audit + Pass-1/Pass-2 remediation |
| `CONNECT-PLANOGRAM-MVP-SCOPE-CORRECTION-2026-06-30.md` | `6ea2dcf` | `mig 152a` → MVP; fork #10 superseded |
| `PROPOSED-9A-9B-FR-EDITS-2026-06-30.md` | `4712539` | FR-text edit checklist for Bob to apply |

Method: multi-agent UltraCode workflows — a **6-agent grounding sweep** → an **11-agent design** (foundations → 4 parallel
domain architects → assemble/verify/finalize per doc) → two **adversarial blind-spot sweeps** (deviation-granularity;
model-wide retention).

## Key discoveries & decisions

- **The schema is ~80% present (build-without-spec).** Triad design = spec-over-existing-schema + **4 gaps**
  (`user_device_token`, `task.assigned_to_role`, `business_hours.v1` schema, `recurring_rule.v1` RRULE) + **3 service
  layers** (rule-engine, recipient-resolution, business-hours scheduler). Planogram adds zero net-new tables of its own.
- **F7 RESOLVED (Bob-ratified):** per-fixture deviation scope via a `compliance_deviation_target` junction (per-target,
  rolls up via `zone.id`) + a **live/replay two-layer model** (replay anchors on durable `zone.id` via `zone_history`) +
  6 hardening items. → [[reference_f7_deviation_per_fixture_junction]].
- **Historical retention is prose-not-DDL** — 3/38 hypertables have a policy; **zero continuous aggregates**. A/B
  before-after + as-of reconstruction = **BROKEN**; fixture/item = AT-RISK. Cheap-insurance Pass-1 (floors +
  CASCADE→RESTRICT + append-only) is the high-value fix. → [[project_core_retention_is_prose_not_ddl]].
- **★ Mode-3-MVP correction (Bob-directed):** planogram-via-Connect is **MVP**, not post-MVP — the twin is the MVP
  external driver; `mig 152a` (family/`directive_kind`) pulled into MVP; fork #10 superseded. → [[project_mode3_planogram_connect_is_mvp]].
- **F10:** FR-COMP-05 = ✅ MVP, owned by the **live Connect ingest/auth boundary** (`audit_log` key-id-not-value +
  per-key rate-limit + 3-failure alert, live-smoked S178 + iterated) — NOT planogram, NOT a re-tag. **F9:** add
  `compliance_target_state.last_confirmed_at TIMESTAMPTZ` (staleness as-of).

## Failed approaches / don't-repeat

- **First design workflow's 4 domain agents all crashed simultaneously** on a transient API "Connection closed
  mid-response" (`server_error`) at the same instant — a correlated infra blip during the large doc-generation step,
  NOT a workflow flaw. **Fix: resume from runId** (`Workflow({scriptPath, resumeFromRunId})`) — foundations returned
  cached, the domain agents re-ran live, succeeded. Resume cleanly recovers a correlated-transient failure.

## Open / handoff (none are twin-implementation — they sit with Bob / backend)

1. **Backend Connect track (Bob carries, option A):** pull `mig 152a`/fork #11 into MVP; mark fork #10 superseded;
   update v2 design §9 + core `STATUS.md`. Spec in the correction brief.
2. **`9a`/`9b`/`6c` FR edits:** the checklist (`4712539`) — start with the two MVP re-tags (FR-PLN-14, FR-COMP-05).
3. **One ratification (gates triad Slice-1 sizing):** FR-TRAFFIC-31 — `gender_match` routing **in** Slice-1 vs downgrade.
4. **One verify:** confirm the FR-COMP-05 3-failure alert is wired on the **inbound auth-failure-per-key** path.
5. **Retention Pass-1 migration** (core, high value before data accumulates).

## Branch / deploy state at close

- **m8trx-twin:** main `f25cbcc` — **UNCHANGED** (no twin code/seed this session; status docs + memory only).
- **m8trx-shared:** main advanced `082bf16`→`4712539` (the 5 coordinator artifacts) + the new `.claude-memory/m8trx-twin/` memory home.
- No new TWIN-REQ filed (the gaps surfaced are core-internal schema, captured in the core design docs, not public-API-surface requests).
