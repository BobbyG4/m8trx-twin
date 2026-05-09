# Session Log — M8TRX Twin

> Append-only history. Newest entry on top. One summarized entry per session (target 15-25 lines). For long-form detail, see commit messages.

---

## Session 2 — 2026-05-10 KST — Step A complete: persona + journey contract + DomainEvent taxonomy + snapshot format + persistence plan

Layer 4 architectural commitment now stable enough to write code against. Step B (integration specs) is the next remaining doc work; Step C (content authoring) and Step D (first code) can run in parallel after that.

**Shipped:**
- Stack locked Kotlin (matches services / edge / android) — `STATUS.md` § Open Decisions
- Persona schema — 3 sealed kinds (Shopper / Operator / Buyer), 9 shared fields incl. `market` + `vertical` + `type` axes, optional `PersonaBiography` bundle anchored to Volere 2d/2e — `reference/architecture/PERSONA-SCHEMA.md`
- Layer 2 Journey contract — `Journey { start(ctx, actor, params) }`, scheduler-driven, 6 v1 kinds, terminal-event convention — `LAYER4-CONFIG-SCHEMA.md` § "Layer 2 — Journey contract"
- DomainEvent v1 taxonomy — 15 typed events (customer lifecycle / engagement / commerce / operations / anomalies) — `LAYER4-CONFIG-SCHEMA.md` § "DomainEvent v1 taxonomy"
- Snapshot file format — 8-section JSON for Layer 1 opening-state seeds + FK-chain / polygon validation — `reference/architecture/SNAPSHOT-FORMAT.md`
- Persistence + graph plan — twin owns dedicated PG database (separate db on mother instance); no standalone Hasura; embedded `graphql-kotlin` when graph layer earns its keep; 4-stage progression — `reference/architecture/TWIN-DB-AND-GRAPH.md`
- Layer 4 doc cleanup — STRAWMAN banner dropped, Q1–Q7 recap landed, sibling-doc cross-refs added
- Twin insight filed in m8trx-shared — `twin/insights/2026-05-10-vertical-portability-ddl.md` (core DDL question: typed `tenant.vertical` column likely worth preparing soon)

**Decisions:**
- Two-layer industry model: top-level `Vertical` (RETAIL | HEALTHCARE | MANUFACTURING | LOGISTICS | HOSPITALITY) + `VerticalType` (RetailType.APPAREL | SPORTING_GOODS | …). MVP is RETAIL-only; structure ready for post-MVP industry expansion without retrofit.
- Persona biography is a separate optional bundle reused across kinds — same human appearing as `OperatorPersona` and `BuyerPersona` shares biography by reference.
- Twin gets its own DB instance for internal state (scenarios / runs / persona seeds / SKU catalog / LLM sessions). Co-located on mother PG instance as a separate database (cheapest isolation). Introduces in Stage 2 when cross-run queries become real need; Stage 1 first-code is in-memory + file capture.
- No standalone Hasura for twin — embedded `graphql-kotlin` keeps twin deployable as a single Spring Boot jar. When twin graduates to product, persisted state folds into mother as a tenant-scoped domain.
- Volere 2e named personas anchored as canonical via `agentPrompt` field on `BuyerPersona` (verbatim from 2e); not pre-extracted to seed files yet — defer until first scenario mechanically loads a named persona.

**Carried forward:**
- Step B integration specs (parallelizable, ~2–4 hr each): Layer 0 AtomEmitter surface (`reference/integration/M8TRX-API-SURFACE.md`); Tenant provisioning playbook (`reference/ops/TENANT-PROVISIONING.md`)
- Step C content authoring (own session, half-day each): `STORE-LAYOUT.md`, `SKU-CURATION.md`
- Step D first code: Layer 0 atoms + orchestrator + `TrafficGenerator` end-to-end against M8trxDemo (in-memory + file capture per `TWIN-DB-AND-GRAPH.md` Stage 1)

**Deploy Verification (run at session close):**

```
=== m8trx-twin ===
SKIP — twin is a fixture project, not in the verify-deploy.sh repo allowlist.
       HEAD=dec0745 on origin/main (Session 2 close commit). Working tree clean.

=== m8trx-shared (twin/insights/ only — surgical commit; other work in flight not touched) ===
PASS @ working-tree: m8trx-shared: clean
PASS @ push: m8trx-shared: HEAD=6a547f819d on origin/main (commit ts=1778368473)
SKIP @ ci: m8trx-shared: no GH Actions runs visible
INFO @ shared-artifact: m8trx-shared: docs vault. Commit + push IS the deploy.
SKIP @ shared-live: m8trx-shared: no runtime deploy
VERIFIED ✓
```

Lane: Twin (sister project, system-integrator posture). Other repos (`m8trx-services`, `-web`, `-android`, `-api`, `-edge`) NOT TOUCHED this session — twin lane only.

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
