# Session 1 — 2026-05-09 KST — Layer 4 schema lock + Trinity generator catalog

**Track:** Twin
**Source:** Extracted from `status/SESSION-LOG.md`

---

First post-bootstrap working session. Focus: lock the Layer 4 architectural commitments before any code.

## What shipped

**Layer 4 schema doc (`reference/architecture/LAYER4-CONFIG-SCHEMA.md`):**
- Q2 — Generator interface locked: `interface Generator { val id; fun start(ctx); fun stop(ctx) }`. `GeneratorContext` exposes `clock`, `scheduler`, `bus`, per-generator `rng`, `personas`, `journeys`, `emit`, `tenantSite`, `log`. Generators stateless except for subscription closures; no `tick()`; `stop()` semantics = report-and-drop.
- Q3 — Scheduler locked: shared scheduler owned by orchestrator; priority queue keyed by `(scenarioTime, insertionOrder)`; `ScheduledHandle` with `cancel()` + `rescheduleAt()`; three rate modes (`>0`, `0` step-mode, `+∞` regression); `events:` YAML pre-loaded ahead of generator `start()` so events win same-time ties; failure policy wraps every callback (skip-and-log default, halt dev).
- Q6 — EventBus locked: `subscribe(KClass<T>, handler)` + `publish(event)`; `DomainEvent` is open marker interface with `val at: Instant`; synchronous publish in publish-order; re-entrant publishes via queue-and-drain; no wildcards in v1.
- Generator catalog added — Trinity-organized (People / Things / Space / Cross-cutting) with v1 target list (~20 generator types).
- `meta.openingState` + `meta.capture` flags defined; snapshot path convention.

**Cross-session bookkeeping in m8trx-shared:**
- TWIN-REQ-001 frontmatter updated to `status: absorbed`, `merged-commit: 47b42f6`, `closed-date: 2026-05-09`.

## Decisions

- DomainEvent ≠ Layer 0 atom. Atoms go OUT to M8TRX (REST/NATS/webhook); DomainEvents stay IN simulator.
- Trinity coverage is the default for scenario authors.
- Snapshot is the unified seed for layout (Space) + inventory (Things).
- JSON canonical config — LLM-friendliness wins.
- Per-generator `rng` forked from `meta.seed` deterministically by `id` — regression test stability across config evolution.

## Carried forward

Step A (persona schema, journey contract, DomainEvent taxonomy), Step B (integration specs), Step C (content authoring), Step D (first code).
