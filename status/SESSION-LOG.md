# Session Log — M8TRX Twin

> Append-only history. Newest entry on top. One summarized entry per session (target 15-25 lines). For long-form detail, see commit messages.

---

## Session 1 — 2026-05-09 KST — Layer 4 schema lock + Trinity generator catalog

First post-bootstrap working session. Focus: lock the Layer 4 architectural commitments before any code.

**Shipped (Layer 4 schema doc — `reference/architecture/LAYER4-CONFIG-SCHEMA.md`):**

- Q2 — Generator interface locked: `interface Generator { val id; fun start(ctx); fun stop(ctx) }`. `GeneratorContext` exposes `clock`, `scheduler`, `bus`, per-generator `rng`, `personas`, `journeys`, `emit`, `tenantSite`, `log`. Generators stateless except for subscription closures; no `tick()`; `stop()` semantics = report-and-drop.
- Q3 — Scheduler locked: shared scheduler owned by orchestrator; priority queue keyed by `(scenarioTime, insertionOrder)`; `ScheduledHandle` with `cancel()` + `rescheduleAt()`; three rate modes (`>0`, `0` step-mode, `+∞` regression); `events:` YAML pre-loaded ahead of generator `start()` so events win same-time ties; failure policy wraps every callback (skip-and-log default, halt dev).
- Q6 — EventBus locked: `subscribe(KClass<T>, handler)` + `publish(event)`; `DomainEvent` is open marker interface with `val at: Instant`; synchronous publish in publish-order; re-entrant publishes via queue-and-drain (no recursion); no wildcards in v1; `bus.log` written alongside `atoms.log` when `meta.capture: true`.
- Generator catalog added — Trinity-organized (People / Things / Space / Cross-cutting) with v1 target list (~20 generator types). Strawman's `generators:` YAML reframed by Trinity dimension; obsolete `correlateWith` references removed.
- `meta.openingState` + `meta.capture` flags defined; snapshot path convention `ref/snapshots/<store-class>/<state>.json`; upstream design dependencies (STORE-LAYOUT.md + SKU-CURATION.md) called out.

**Shipped (cross-session bookkeeping in `m8trx-shared`):**

- TWIN-REQ-001 frontmatter updated to `status: absorbed`, `merged-commit: 47b42f6`, `closed-date: 2026-05-09`. Twin's STATUS.md "Active Requirements" reflects ABSORBED status. Closes the precedent for the brief lifecycle.

**Decisions:**

- DomainEvent ≠ Layer 0 atom. Atoms go OUT to M8TRX (REST/NATS/webhook); DomainEvents stay IN simulator for cross-generator correlation. Both can fire from the same callback.
- Trinity coverage is the default for scenario authors. People-only scenarios don't exercise M8TRX Fusion.
- Snapshot is the unified seed for layout (Space) + inventory (Things). Output of two upstream design docs (STORE-LAYOUT.md, SKU-CURATION.md). Multiple snapshots planned per store class — small running specialty first, larger formats later.
- JSON canonical config + human surface (Q1) — LLM-friendliness wins; human friendliness is a downstream tooling concern.
- Per-generator `rng` forked from `meta.seed` deterministically by `id` — adding a generator does NOT reshuffle existing generators' streams. Regression test stability across config evolution.

**Carried forward (next session — see STATUS.md `## ⚠ NEXT SESSION PRIORITIES`):**

- **Step A — quick locks (~30 min):** stack pick; persona schema; journey base contract; DomainEvent v1 taxonomy; snapshot file format; Layer 4 doc cleanup pass (drop strawman banner, recap locked Q1-Q7).
- **Step B — integration specs (~2-4 hr each, parallelizable):** Layer 0 AtomEmitter surface (`reference/integration/M8TRX-API-SURFACE.md`); tenant provisioning playbook (`reference/ops/TENANT-PROVISIONING.md`).
- **Step C — content authoring (own session):** STORE-LAYOUT.md (research-driven, small running specialty); SKU-CURATION.md (Decathlon Korea catalog → ~2-3k SKUs).
- **Step D — first code:** Layer 0 atoms + orchestrator skeleton + `TrafficGenerator` end-to-end.

---
