# Project Status — M8TRX Twin

---

## ⚠ NEXT SESSION PRIORITIES (bootstrap, 2026-05-09)

Project just scaffolded. Foundational docs in place (`CLAUDE.md`); no code yet. The first sessions build the architectural substrate before any event flows.

### Recommended entry order

1. **Layer 4 config schema design** (~1-2 hr design session) — the architectural commitment Bob allocated explicitly. Strawman exists at `reference/architecture/LAYER4-CONFIG-SCHEMA.md`; finalize in a focused session. Output: that doc upgraded from strawman to committed contract, plus the LLM tool-surface mapping locked. **This unblocks everything below; do this first.**
2. **SKU curation spec** (~1 hr design) — define category filters, variant-expansion logic, and quantity-assignment heuristic against Decathlon Korea full catalog (~20k items). Open question: does Bob's Pontos-style data dump include demand/turnover signal? If yes, quantities reflect reality; if no, heuristic. Output: `reference/data/SKU-CURATION.md`.
3. **Store layout authoring** (~2-3 hr design + research) — gather Decathlon store-tour references (YouTube, public press kits, Google Maps interior), sketch a 30m×50m running specialty floor plan, define zone polygons + fixture-to-SKU bindings. Output: `reference/data/STORE-LAYOUT.md` with floor plan + zone seed structure. Decision required: SQL seed of zones vs. AR-walk-them-as-if (dogfoods fixture mode but slower).
4. **Tenant provisioning playbook** (~1 hr design) — how M8trxDemo gets created on mother (which API, which capability, what api_key gets provisioned for the Twin's service principal). The exercise itself is a real test of customer onboarding — capture friction. Output: `reference/ops/TENANT-PROVISIONING.md`.
5. **Project structure + Layer 0 atoms** (~half day code) — pick stack (open decision below), scaffold module layout, implement the ~6 Layer-0 emitters as the thinnest possible client of M8TRX's public APIs.

Steps 1-4 are pure design; step 5 is the first code commit.

---

## Active Requirements Filed Back to Core

Briefs live at `~/IdeaProjects/m8trx-shared/twin/requirements/TWIN-REQ-<NNN>-<short-slug>.md`. Format and lifecycle documented in `m8trx-shared/twin/SISTER-PROJECT.md`. Status enum: `NOT YET FILED` → `FILED, AWAITING ABSORPTION` → `ABSORBED` (or `REJECTED` / `SUPERSEDED`).

- **commerce_projection writer** — substrate (mig 112) exists; writer unfed. Blocks Scripts 1, 3, 5 commercial story. Status: NOT YET FILED (waiting for Bob's audit confirmation in core).
- **`fitting_room` → `try_on_zone` generalization** — current `fitting_room` table is apparel-specific; demo needs footwear bench + watch demo station as try-on zones. Status: **FILED, AWAITING ABSORPTION** (2026-05-09). Brief: `~/IdeaProjects/m8trx-shared/twin/requirements/TWIN-REQ-001-try-on-zone-generalization.md`. Execution contract: `~/IdeaProjects/m8trx-shared/status/cleanup/CONTRACT-FITTING-ROOM-RENAME-2026-05-09.md`. Twin's recommendation: Option E (atomic rename now, while window is open — 0 rows + pre-MVP + 24-hr-old code = uniquely cheap).
- **`inventory:sell` capability split** — currently piggybacks `inventory:transfer`. Surfaces when demo authors cashier persona. Status: PRE-EXISTING (already tracked in core's CLEANUP-TASKS.md; brief unnecessary).

---

## Deploy State

No artifacts yet. Repo bootstrap only.

---

## Open Decisions

- **Stack** — Kotlin (matches services/edge, JVM ecosystem familiar to the rest of m8trx-core), TypeScript/Node (matches web; NATS + Anthropic SDK first-class; lightweight), or Python (Anthropic SDK first-class; data-science tooling for SKU curation). Recommendation pending after Layer 4 schema design — the schema's shape may favor one. Likely TypeScript or Kotlin.
- **Container deploy target** — office .28 (alongside main-server), separate host, or cloud (Anthropic API needs outbound; LLM client doesn't run on LAN). Decision after stack pick.
- **Scenario clock** — wall-clock real-time vs. controllable rate (1×, 10×, 60×) vs. recorded-and-replayable. Recommendation: ship rate-controllable real-time first, layer scrubbing later. Lock into Layer 4 schema.
- **Config canonical format** — YAML (human-friendliest), JSON (LLM-friendliest), or builder DSL (type-safe-friendliest). Pick one as canonical, support transforms to/from the others.

---

## Scenario Script Library (planning)

Five scripts brainstormed; build order from highest-leverage substrate to highest-impact set piece:

1. **A Day in the Life** — long-form data substrate (open-to-close, populated). Build first; everything else extracts from it.
2. **Saturday Rush** — investor breadth-flex (~5 min compressed from 4 hr). All Layer 3 surfaces lit up simultaneously.
3. **The Theft** — LP-buyer demo (~3 min). Single journey ending in EAS alarm + RFID confirmation.
4. **Fitting Room Conversion** — ops/store-manager demo (~4 min). Three customers, three outcomes; surfaces "Items Tried But Not Bought" report.
5. **Compliance Day** — big-tenant compliance buyer (~3 min). Planogram drift detection via `zone_history` hypertable.

Scripts 1, 2 = unblocked once Layer 4 substrate runs. Scripts 3, 4 = layer over the substrate. Script 5 = independent of POS, blocked only on layout authoring.

---
