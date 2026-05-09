# Project Status — M8TRX Twin

---

## ⚠ NEXT SESSION PRIORITIES (updated 2026-05-10 — Session 2 close)

**Session 2 closed all of Step A.** Stack locked Kotlin; persona schema (3 sealed kinds, Volere 2d/2e anchored, market+vertical+type axes) committed at `reference/architecture/PERSONA-SCHEMA.md`; Layer 2 Journey contract + DomainEvent v1 taxonomy (15 events) added as locked sections in LAYER4-CONFIG-SCHEMA.md; snapshot file format committed at `reference/architecture/SNAPSHOT-FORMAT.md`; Layer 4 doc cleanup pass done (STRAWMAN banner dropped, Q1–Q7 recap landed). Twin persistence + graph plan locked at `reference/architecture/TWIN-DB-AND-GRAPH.md` (dedicated PG database on mother instance; no standalone Hasura; embedded `graphql-kotlin` when graph layer earns its keep).

**Architecture-side commitments are now stable enough to write code against.** Step B (integration specs) is the next remaining doc work; Step C (content authoring) and Step D (first code) can run in parallel after that.

### Recommended entry order — next session

**Step A — quick locks (~30 min, doc-only):** ✅ COMPLETE 2026-05-10

| | Item | Land-point |
|---|---|---|
| ✅ | Stack = Kotlin | STATUS.md § Open Decisions |
| ✅ | Persona schema | `PERSONA-SCHEMA.md` |
| ✅ | Journey base contract | `LAYER4-CONFIG-SCHEMA.md` § Layer 2 |
| ✅ | DomainEvent v1 taxonomy | `LAYER4-CONFIG-SCHEMA.md` § DomainEvent v1 |
| ✅ | Snapshot file format | `SNAPSHOT-FORMAT.md` |
| ✅ | Layer 4 doc cleanup (banner removed, Q1–Q7 recap) | `LAYER4-CONFIG-SCHEMA.md` |
| ✅ | Persistence + graph plan (bonus) | `TWIN-DB-AND-GRAPH.md` |

**Step B — integration specs (~2–4 hr each, can run in parallel):**

1. **Layer 0 AtomEmitter surface** — new doc `reference/integration/M8TRX-API-SURFACE.md`. ~10–15 method table mapping each emit call to M8TRX endpoint/subject + payload. Covers `objLocation`, `objEviction`, `rfidScan`, `saleWebhook`, `tryOnZoneEvent`, `custodyEvent`, `stocktakeBegin`, etc. **Hard blocker for all Layer 0 code.**
2. **Tenant provisioning playbook** — new doc `reference/ops/TENANT-PROVISIONING.md`. Walk M8trxDemo creation via the same signup flow a real customer would; capture friction findings as we go (this is itself a real onboarding test). Output: signup steps, capability grants, `api_key` issuance with `principal_kind=service`. **Hard blocker for any code talking to mother.**

**Step C — content authoring (own session, half-day each):**

3. **Store layout** — `reference/data/STORE-LAYOUT.md`. Research-driven (Decathlon walkthroughs, public press kits, store concept renders) for ~30m × 50m running specialty footprint. Defines `space` + `zone` + `fixture` rows of the snapshot.
4. **SKU curation** — `reference/data/SKU-CURATION.md`. Decathlon Korea catalog (~20k items) → ~2–3k curated SKUs by category, variant-expanded by size/color, anchored to fixtures.

These two upstream-feed the snapshot artifact at `reference/data/snapshots/decathlon-running-small/day-start.json`.

**Step D — first code:** Layer 0 atoms + orchestrator skeleton + `TrafficGenerator` end-to-end against M8trxDemo on mother. Initial deploy is in-memory + file capture (per `TWIN-DB-AND-GRAPH.md` Stage 1); twin's PG database introduces in Stage 2 once cross-run queries become a real need.

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

- **Stack** — ✅ LOCKED 2026-05-10: **Kotlin**. Follows the rest of the M8TRX ecosystem (services / edge / android are all Kotlin; only web is TS). Reuses JVM tooling, Apollo + NATS clients, and m8trx-api codegen the same way services/android do. Anthropic SDK has a maintained Kotlin/JVM path; no SDK gap forces the issue.
- **Container deploy target** — office .28 (alongside main-server), separate host, or cloud (Anthropic API needs outbound; LLM client doesn't run on LAN). Decision after first runnable scenario.
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
