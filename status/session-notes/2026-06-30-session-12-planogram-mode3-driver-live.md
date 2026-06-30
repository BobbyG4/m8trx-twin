# Session 12 — 2026-06-30 (Twin) — Planogram-directive driver (Connect Mode 3) built + live-proven end-to-end

**Branch/deploy at close:** `m8trx-twin` main `ae5fcf2` (PR #7 merged, clean, no open PRs). All offline self-tests green (`connectSelfTest`, now 7 cases incl. the planogram directive). Comms: `twin` seat on Slack `#m8trx-dev` (drove Backend↔Twin direct, COORD coordinating the DB-migration arc).

---

## Headline

Picked up the **planogram-via-Connect** track (S193 coordinator designs). The twin is the **MVP external driver** that drives M8TRX's internal planogram through Connect (Mode 3, `directive_kind='planogram'`). In one continuous session: **pre-built the driver → Backend shipped the as-built ingest → adapted → fired live → server-verified end-to-end.** The twin drove a **real 28-placement planogram directive** into M8trxDemo, which **landed → was operator-mapped → resolved to a real Denver fixture zone**. COORD independently verified the whole loop on mother. **Twin's behavioral smoke caught a 6th real core bug** along the way.

> "The twin drove a real planogram directive → 28 resolved compliance targets at a real Denver fixture zone — that's the demo." — COORD independent verify

---

## What shipped (commit refs, all on main via PR #7)

| Commit | What |
|---|---|
| `4265cbe` | **T1 — planogram document generator** (`scripts/build_planogram.py`) → 10× `stores/<id>/planogram.json` (m8trx_standard). Deterministic (sha256 `source_digest`). `required_qty` = the **actual seeded floor EPC count** per fixture×SKU (BOH excluded) → **84,266 floor units = the reseed's floor split exactly** → compliant-by-construction. |
| `24f81ff` | **T2 — planogram-directive driver + DTOs + gradle task** (`connectPlanogramDrive`, dry-run default) + `connectSelfTest` case. *(Initial build to the §6.1 design sketch.)* |
| `f98a973` | **plan doc** — `status/active/PLANOGRAM-DIRECTIVE-DRIVER-PLAN-2026-06-30.md` (twin's role + T1–T6 build order + blockers). |
| `9642828` | **T3 + T4 docs** — `LIVE-OPERATIONS.md` §11 (the planogram lifecycle beat woven into the 24/7 daemon; §4 pre-open replenishment IS the remediation execution) + §12 (compliance read-back gap) + `status/briefs/TWIN-REQ-DRAFT-planogram-compliance-readback-2026-06-30.md`. |
| `a3b5264` | Merge PR #7. |
| `ae5fcf2` | **As-built rework + LIVE-PROVEN** — adapted the driver from §6.1 to Backend's as-built #64 ingest shape, fired the GB-R3-U1 smoke, server-verified. |

---

## What was attempted / the arc (chronological)

1. **Investigated the S193 designs** (`PLANOGRAM-RESOLVED-DESIGN`, `CONNECT-PLANOGRAM-MVP-SCOPE-CORRECTION`, the triad doc, `BUILD-SEQUENCE`). Key realization: **the twin's dataset already IS a planogram** — `assortment.csv` assigns each SKU to a `fixture` at a stocked depth; that's the `compliance_target` payload.
2. **Built T1–T4** (idle window while Backend was busy): document generator, driver scaffold, lifecycle-arc design, read-back gap analysis. Opened + merged **PR #7**.
3. **Backend, in-session, applied `mig 152a`** (directive-channel schema) + Triad substrate columns (`user_device_token`, `task.assigned_to_role`) + retention floors in a DDL window (COORD-coordinated, backup-gated). Twin held all live ops during the window (zero traffic to pause).
4. **Asked the right question before firing:** post-`152a`, was there an ingester routing `directive_kind`? Backend: schema-only, no route — *don't fire yet*. Pre-build instinct validated (firing would've fallen through the generic path = a misleading green).
5. **Backend shipped the as-built Mode-3 ingest (#64)** with a shape that **superseded the §6.1 sketch** (the S9 lesson again): `X-Data-Type: planogram_directive` on the existing twin-pos webhook, flat `{name, external_directive_id, effective_date, targets[]}`, per-target **real site_id UUID** (not site_ref), `raw_fixture_code` unresolved, `raw_item_identifier`=EAN.
6. **Adapted the driver** to the as-built contract (`ae5fcf2`), green through `connectSelfTest`, and **fired the GB-R3-U1 smoke live** (28 targets, X-API-Key auth) → **200 ack** → Backend server-verified **PROCESSED** (`compliance_directive a3b2bbde` + `_site` + 28 `_target`).
7. **Backend shipped the resolver (#65/#66).** An operator mapped `GB-R3-U1` → a Denver fixture zone via `POST /api/v2/compliance/fixture-codes`; `resolvePendingForTenant` auto-flipped all 28 targets `pending_zone_mapping`→`resolved`. **COORD independently verified on mother** (28 resolved / 0 pending, non-inheriting `fixture_code_mapping`, services `380934d`).

**No failed approaches to flag** — the pre-build-to-contract → adapt-to-as-built sequence worked cleanly. The one course-correction (the §6.1 shape ≠ as-built) was *expected* and absorbed by design.

---

## Key discoveries

- **6th core bug caught by twin's live exercise** — Backend's resolver queried `zone.site_id`, which doesn't exist (zone→site is via `space`). Surfaced by the live smoke, fixed + redeployed (#66). The integrator-driven-testing thesis keeps paying.
- **Wrong-zone mapping (OPEN, raised, not yet resolved):** the smoke's operator mapped `GB-R3-U1` → "Gondola R6 Front U1", but in twin's layout that name is a **different fixture** (code `GF-R6-U1`, dept cycling). `GB-R3-U1`'s real zone is "Gondola R3 Back U1" (dept snow). Fine for proving the *mechanism*; **wrong for a real planogram** (compliance would score a zone with no matching inventory). **This is load-bearing for the live-compliance demo** — driving sales on the GB-R3-U1 inventory wouldn't move compliance at the R6-Front zone. Two paths put to Backend (pending reply): **(a)** twin keeps sending vendor codes + supplies the authoritative code→zone map from layout.json (realistic, exercises the mapping path); **(b)** twin sends zone *names* as `raw_fixture_code` → auto-resolves via exact-name-match, no operator step (simpler, correct-by-construction). Twin's lean: (a) for realism + coverage; (b) is the fastest clean route to a live demo.
- **OI-2 partially closed:** there's now a real load path for `fixture_code_mapping` rows — `POST /api/v2/compliance/fixture-codes` (`fixture:create`, operator-side, not yet Connect-exposed).
- **Compliance read-back still absent** (T4) — `/api/v2` exposes the Mode-3 *inbound* half but no compliance/directive *read*; the twin can't self-verify a directive's effect over Connect (relies on Backend/COORD server-side). Drafted as a candidate TWIN-REQ (see below).

---

## Decisions made

- **Twin's planogram source = the seeded floor** (`required_qty` from `epcs.csv` floor count, not assortment depth) → compliant-by-construction at activation; activity drives drift. (Builder `build_planogram.py`.)
- **Driver built to the as-built #64 contract**, not the §6.1 sketch. `WebhookDataType.PLANOGRAM_DIRECTIVE`, flat `PlanogramDirective`/`DirectiveTarget`; `PlanogramDocument`/`Line` kept as twin's *source* format (driver maps doc→directive, resolving site UUID from `site_ids.csv`).
- **Bounded smoke first** (GB-R3-U1, 28 targets) before any full-Denver drive — `connectPlanogramDrive` defaults to Denver + dry-run to prevent an accidental 10-store / 2,504-target live fire.
- **Did NOT fire the full Denver directive or activate the directive** — held for Bob's call; the live-compliance demo is gated on fixing the wrong-zone mapping first.

---

## State for next session

- **Directive→targets→resolved is PROVEN + triple-verified** (twin fire + Backend readback + COORD independent). The Mode-3 *landing+resolution* arc is complete.
- **Backend has more work before this is demo-ready** (Bob's close-out note): the **next tail is FR-PLN-08 compliance-check task-gen on the resolved targets — needs the Notifications spine** (Triad Slice-1, Backend's deferred critical-path work).
- **Twin's immediate next options:** (1) resolve the (a)/(b) mapping path with Backend → correct the GB-R3-U1 mapping; (2) the **live-compliance demo** (re-fire with correct zone → activate `effective_date=today` → sale-stream tap → compliance moves) — self-contained, ~15 min, once the mapping's right; (3) scale to the full Denver planogram (2,504 targets); (4) generate the per-store code→zone map artifact (if path (a)).
- `connectPlanogramDrive` live-fire recipe: `set -a; source .env; set +a` then `M8TRX_PLANOGRAM_LIVE=true M8TRX_PLANOGRAM_FIXTURES=<codes> M8TRX_PLANOGRAM_AUTH=api_key ./gradlew --no-daemon connectPlanogramDrive`. Idempotent on `external_directive_id`.
