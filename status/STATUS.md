# Project Status — M8TRX Twin

---

## ⚠ NEXT SESSION PRIORITIES (updated 2026-05-09 night — Session 1 close)

**Session 1 was a focused Layer 4 schema lock-in.** Q2 (Generator interface), Q3 (Scheduler), Q6 (EventBus) all locked, Trinity-organized generator catalog added, `meta.openingState` + `meta.capture` flags defined, snapshot architecture specified, TWIN-REQ-001 bookkeeping closed. Layer 4 schema is substantially committed — the strawman banner can come off after a small cleanup pass. **The runtime model (orchestrator + scheduler + bus) is locked; generators reference personas/journeys/atoms via `GeneratorContext`; cross-generator correlation goes through the bus, not YAML.**

### Recommended entry order — next session

**Step A — quick locks (~30 min, doc-only):**

1. **Stack pick** — Kotlin / TypeScript / Python. Schema is data-only and doesn't strongly favor one; pick on operational fit (iteration speed, deploy target, existing M8TRX integration tooling). Single-sentence decision.
2. **Persona schema** — required vs optional fields. ~10 fields. JSON Schema canonical + human surface.
3. **Journey base contract** — Layer 2 abstraction; `interface Journey { fun execute(ctx, customerId, params) }` or similar. Needed before `BrowseAndLeave`, `TryOnAndBuy`, `Shoplift` are written.
4. **DomainEvent v1 taxonomy** — concrete event types (~15 events: `CustomerEntered`, `CustomerReachedZone`, `CustomerExited`, `SaleCompleted`, `RestockBegan`, `RestockCompleted`, `ShipmentArrived`, `ItemDisplaced`, `EasAlarmTriggered`, `PlanogramDeviationDetected`, `StocktakeReconciled`, etc.).
5. **Snapshot file format** — JSON shape, one section per relevant table (`item_identifier`, `thing_location`, `space`, `zone`, `fixture`, optionally `tenant_share_grant`).
6. **Layer 4 doc cleanup** — drop the STRAWMAN banner, refactor "Open design questions" into a "Locked design decisions" recap (Q1-Q7 all ✅).

**Step B — integration specs (~2-4 hr each, can run in parallel):**

7. **Layer 0 AtomEmitter surface** — new doc `reference/integration/M8TRX-API-SURFACE.md`. ~10-15 method table mapping each emit call to M8TRX endpoint/subject + payload. Covers `objLocation`, `objEviction`, `rfidScan`, `saleWebhook`, `tryOnZoneEvent`, `custodyEvent`, `stocktakeBegin`, etc. **Hard blocker for all Layer 0 code.**
8. **Tenant provisioning playbook** — new doc `reference/ops/TENANT-PROVISIONING.md`. Walk M8trxDemo creation via the same signup flow a real customer would; capture friction findings as we go (this is itself a real onboarding test). Output: signup steps, capability grants, `api_key` issuance with `principal_kind=service`. **Hard blocker for any code talking to mother.**

**Step C — content authoring (own session, half-day each):**

9. **Store layout** — `reference/data/STORE-LAYOUT.md`. Research-driven (Decathlon walkthroughs, public press kits, store concept renders) for ~30m × 50m running specialty footprint. Defines `space` + `zone` + `fixture` rows.
10. **SKU curation** — `reference/data/SKU-CURATION.md`. Decathlon Korea catalog (~20k items) → ~2-3k curated SKUs by category, variant-expanded by size/color, anchored to fixtures.

These two upstream-feed the snapshot artifact at `ref/snapshots/decathlon-running-small/day-start.json`.

**Step D — first code:** Layer 0 atoms + orchestrator skeleton + `TrafficGenerator` end-to-end against M8trxDemo on mother.

---

## Active Requirements Filed Back to Core

Briefs live at `~/IdeaProjects/m8trx-shared/twin/requirements/TWIN-REQ-<NNN>-<short-slug>.md`. Format and lifecycle documented in `m8trx-shared/twin/SISTER-PROJECT.md`. Status enum: `NOT YET FILED` → `FILED, AWAITING ABSORPTION` → `ABSORBED` (or `REJECTED` / `SUPERSEDED`).

- **commerce_projection writer** — substrate (mig 112) exists; writer unfed. Blocks Scripts 1, 3, 5 commercial story. Status: NOT YET FILED (waiting for Bob's audit confirmation in core).
- **`fitting_room` → `try_on_zone` generalization** — Status: **ABSORBED 2026-05-09**. Brief: `~/IdeaProjects/m8trx-shared/twin/requirements/TWIN-REQ-001-try-on-zone-generalization.md` (frontmatter updated). Merged-commit `47b42f6` (m8trx-services); mig 127 in m8trx-shared; full 4-repo rename across services / web / android / api closed in core Session 61.
- **`inventory:sell` capability split** — currently piggybacks `inventory:transfer`. Surfaces when demo authors cashier persona. Status: PRE-EXISTING (already tracked in core's CLEANUP-TASKS.md; brief unnecessary).

---

## Deploy State

No artifacts yet. Repo bootstrap + Layer 4 schema doc only.

- m8trx-twin HEAD: pending Session 1 close commit (Layer 4 lock + generator catalog + openingState)

---

## Open Decisions

- **Stack** — Kotlin (matches services/edge, JVM ecosystem familiar to the rest of m8trx-core), TypeScript/Node (matches web; NATS + Anthropic SDK first-class; lightweight), or Python (Anthropic SDK first-class; data-science tooling for SKU curation). Schema is now committed and doesn't strongly favor one. Decision: Step A item 1 next session.
- **Container deploy target** — office .28 (alongside main-server), separate host, or cloud (Anthropic API needs outbound; LLM client doesn't run on LAN). Decision after stack pick.
- **Scenario clock** — ✅ LOCKED Session 1: shared scheduler owned by orchestrator; generators submit callbacks (Q3). `rate=0` step mode supported via orchestrator-exposed `advanceOne()` / `advanceUntil()`. Capture-and-replay locked in Q5.
- **Config canonical format** — ✅ LOCKED Session 1: JSON canonical (LLM-friendliest) + human surface on top (Q1).

---

## Scenario Script Library (planning)

Five scripts brainstormed; build order from highest-leverage substrate to highest-impact set piece:

1. **A Day in the Life** — long-form data substrate (open-to-close, populated). Build first; everything else extracts from it. **Coverage discipline: must run every generator in the catalog at scenario-realistic cadence — this is the integration test for Trinity coverage.**
2. **Saturday Rush** — investor breadth-flex (~5 min compressed from 4 hr). All Layer 3 surfaces lit up simultaneously.
3. **The Theft** — LP-buyer demo (~3 min). Single journey ending in EAS alarm + RFID confirmation.
4. **Fitting Room Conversion** — ops/store-manager demo (~4 min). Three customers, three outcomes; surfaces "Items Tried But Not Bought" report.
5. **Compliance Day** — big-tenant compliance buyer (~3 min). Planogram drift detection via `zone_history` hypertable.

Scripts 1, 2 = unblocked once Layer 4 substrate runs. Scripts 3, 4 = layer over the substrate. Script 5 = independent of POS, blocked only on layout authoring.

---
