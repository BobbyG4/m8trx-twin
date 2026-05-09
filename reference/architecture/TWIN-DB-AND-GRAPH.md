# Twin Persistence & Query Layer

**Status:** LOCKED 2026-05-10
**Decision:** Twin owns a dedicated PG database for internal state; **no** standalone Hasura/GraphQL. Graph layer, when needed, is embedded `graphql-kotlin` in the same JVM as the orchestrator.

Sibling docs: `LAYER4-CONFIG-SCHEMA.md`, `PERSONA-SCHEMA.md`.

---

## The decision in one paragraph

Twin needs its own persistence layer for scenarios, runs, persona seeds, SKU catalog, and LLM authoring sessions — none of this belongs in mother (M8TRX's DB). Twin gets a dedicated Postgres database, deployed as a *separate database* on the existing mother PG instance (cheap to ops; logically isolated). Twin does **not** stand up its own Hasura instance — query-layer needs at MVP are met by Spring Data JDBC repositories. When LLM Client B authoring sessions or a future twin UI start to need typed cross-cutting queries, **embedded `graphql-kotlin`** (Expedia's library) lands in the same JVM as the orchestrator. Twin remains deployable as a single Spring Boot jar throughout. When twin eventually graduates to the customer-self-serve `M8TRX Twin` product, persisted state folds into mother as a tenant-scoped domain — twin never owns long-term standalone infrastructure.

---

## Posture check — why this doesn't break the integrator rule

`m8trx-twin/CLAUDE.md` § "Forbidden" prohibits direct DB access to *mother* and prohibits modifying core. It does not prohibit twin from having its own persistence layer for its own internal state. Every system-integrator owns its own state — the rule is about not forking M8TRX's database and not patching gaps inside twin that should be filed back as requirements.

The twin DB:
- **Stores zero M8TRX-owned data.** No mirror of `item`, `tenant`, `space`, `person_session`, or anything else mother owns.
- **Stores only twin's internal bookkeeping** — scenario configs, run history, persona seeds, SKU catalog (which is the demo's *input*, not data M8TRX has produced), LLM session state.
- **Is invisible to M8TRX.** Mother does not know it exists; M8TRX consumers do not query it. It exists for the orchestrator (and, eventually, Client B + a possible twin UI).

If a future TWIN-REQ proposes putting some piece of twin state into mother (e.g., "saved scenarios should be a tenant-scoped entity in M8TRX so customers can edit them"), that's the *graduation* path — twin's DB shrinks as core grows to absorb the appropriate bits. Net direction: twin's DB stays small, eventually empties when M8TRX Twin ships as a product.

---

## Four-stage progression

| Stage | Trigger | Persistence | Query shape |
|---|---|---|---|
| **Stage 1 — first code (Step D)** | Layer 0 atoms + orchestrator + `TrafficGenerator` running first end-to-end scenario | In-memory; capture-to-file at `runs/<id>/{atoms,bus}.log` + `summary.json` | Direct collection access in-process; nothing exposed |
| **Stage 2 — first DB introduction** | Cross-run queries needed ("which Saturday Rush runs hit anomaly X"); SKU catalog stops fitting in JSON files; LLM authoring sessions need to span process restarts | Twin PG database (separate db on mother instance); Spring Data JDBC + Flyway | Repository methods only; no graph yet |
| **Stage 3 — graph layer earns keep** | LLM Client B needs typed introspectable tool surface, OR a twin UI is built | Same DB | **Embedded `graphql-kotlin`** in the orchestrator JVM; `/graphql` endpoint exposed in-process |
| **Stage 4 — twin graduates to product** | `M8TRX Twin` ships as a customer self-serve scenario tool | Mother absorbs the right tables as a tenant-scoped domain | Mother's existing Hasura adds the tracked tables; twin DB shrinks |

Stages 1 → 2 is the only required transition for MVP. Stages 3 and 4 are roadmap items, not commitments.

---

## Stack alignment

Same conventions as `m8trx-services` so future developers don't context-switch:

| Concern | Choice |
|---|---|
| RDBMS | Postgres 16 (vanilla; no TimescaleDB or PostGIS extensions needed at this layer) |
| Migration | Flyway, file convention `db/migration/V{NN}__{slug}.sql` + companion `V{NN}__{slug}_rollback.sql` (mirrors core's hard rule on rollback companions) |
| Migration transactionality | Flyway's outer transaction; **no inner `BEGIN`/`COMMIT`/`ROLLBACK` in migration files** (per core convention; documented incident on mig 040, 2026-04-26) |
| Connection pool | HikariCP (Spring Boot default) |
| Repository | Spring Data JDBC (preferred over JPA; matches M8TRX services style) |
| Stage 3 graph | `com.expediagroup:graphql-kotlin-spring-server` — schema derives from Kotlin types via reflection; same JVM, same jar |

Twin migrations are an **independent sequence** from mother's `status/migrations/`. They live at `m8trx-twin/src/main/resources/db/migration/`.

---

## Initial schema sketch (Stage 2)

Eight tables when twin's DB first lands. Refine when the orchestrator actually needs them.

```sql
-- V001__init.sql — twin core bookkeeping

CREATE TABLE scenario (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name            TEXT NOT NULL,
  version         INTEGER NOT NULL DEFAULT 1,
  config_json     JSONB NOT NULL,                              -- the Layer 4 ScenarioConfig
  tags            TEXT[] DEFAULT '{}',
  author          TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (name, version)
);

CREATE TABLE scenario_run (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  scenario_id     UUID NOT NULL REFERENCES scenario(id),
  started_at      TIMESTAMPTZ NOT NULL,
  ended_at        TIMESTAMPTZ,
  seed            BIGINT NOT NULL,
  status          TEXT NOT NULL CHECK (status IN ('running','completed','failed','cancelled')),
  outcome_summary JSONB,                                       -- atom counts, anomaly flags, generator health
  log_path        TEXT NOT NULL                                -- relative path to runs/<id>/ for capture artifacts
);
CREATE INDEX scenario_run_by_scenario ON scenario_run (scenario_id, started_at DESC);

CREATE TABLE persona_seed (
  id              TEXT PRIMARY KEY,                            -- e.g. "operator.kr.apparel.soyeon"
  kind            TEXT NOT NULL CHECK (kind IN ('shopper','operator','buyer')),
  market          TEXT NOT NULL,                               -- KR | US | EU
  vertical        TEXT NOT NULL,                               -- RETAIL | HEALTHCARE | …
  type            TEXT NOT NULL,                               -- APPAREL | SPORTING_GOODS | …
  blob_json       JSONB NOT NULL,                              -- full Persona instance (kotlinx.serialization output)
  source_ref      TEXT,                                        -- e.g. "m8trx-shared/.../2e. Personas.md#persona-8"
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX persona_seed_by_axis ON persona_seed (market, vertical, type, kind);

CREATE TABLE sku_catalog (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  vendor          TEXT NOT NULL,                               -- "decathlon-kr" | "lightspeed-retail"
  sku             TEXT NOT NULL,
  name            TEXT NOT NULL,
  attrs_json      JSONB NOT NULL,                              -- variant axes (size, color, …) and metadata
  fixture_anchor  TEXT,                                        -- which fixture in the demo store this SKU lives on
  price_cents     BIGINT,
  currency        TEXT,
  UNIQUE (vendor, sku)
);

CREATE TABLE snapshot (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  store_class     TEXT NOT NULL,                               -- e.g. "decathlon-running-small"
  state           TEXT NOT NULL,                               -- e.g. "day-start" | "tuesday-half-stocked"
  snapshot_json   JSONB NOT NULL,                              -- layout (Space/Zone/Fixture) + inventory (Item/Identifier/Location)
  source_ref      TEXT,                                        -- e.g. "reference/data/snapshots/decathlon-running-small/day-start.json"
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (store_class, state)
);

CREATE TABLE market_profile (
  market          TEXT PRIMARY KEY,                            -- KR | US | EU
  profile_json    JSONB NOT NULL,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE business_ops_profile (
  vertical        TEXT NOT NULL,
  type            TEXT NOT NULL,
  profile_json    JSONB NOT NULL,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (vertical, type)
);

CREATE TABLE llm_authoring_session (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_active_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  conversation    JSONB NOT NULL,                              -- Anthropic message history + tool-use turns
  resulting_scenario_id UUID REFERENCES scenario(id)
);
```

`run_atom_log` and `run_event_log` (per-atom / per-event hypertable-class data) are intentionally **not** in this initial schema. Reasoning:

- Capture lives on disk as append-only files (`runs/<id>/atoms.log`, `bus.log`) per Q5/Q6 lock — sequential reads + replay only.
- If "tail an active run" or "query atoms by type across runs" becomes a real need, *that's* the trigger to add a hypertable (with TimescaleDB extension turned on for twin's DB at that point).

Defer until the need is concrete; don't pre-materialize the volume.

---

## Deployment

### Initial — co-locate on mother PG instance

```
mother (159.223.43.77, PG 16)
├── m8trx (existing)               — M8TRX core data, TimescaleDB + PostGIS extensions
└── m8trx_twin (NEW)               — twin internal bookkeeping; vanilla PG, no extensions
```

Cheapest possible isolation:

- Single PG server to ops (no extra container, no extra backup config to design)
- Logical isolation via separate PG database (twin's tables are unreachable from M8TRX's connection)
- `m8trx_twin` user/role with grants only on the twin database; cannot read or write `m8trx`
- Connection string in twin Spring config: `jdbc:postgresql://mother.m8trx.com:5448/m8trx_twin?sslmode=verify-full`
- Backups: same backup job extends to `m8trx_twin` automatically (cheap)

### Provisioning steps (when Stage 2 lands)

1. SSH `mother`, create role + database (DDL captured as a one-shot ops script in twin repo at `ops/init-db.sh`)
2. Add Flyway dependency + `application-prod.yml` JDBC config + secret in 1Password
3. Land V001 init migration; let Flyway create schema on first boot
4. Provision an api_key on mother for twin's machine class (parallel to edge's pattern)

### Future considerations

- **Move to dedicated PG container on .28 or .29** if mother becomes a noisy neighbor (unlikely at twin's scale)
- **Move to managed cloud PG** (DO Managed DB / RDS) if/when twin graduates to customer-self-serve product
- **Enable TimescaleDB extension on `m8trx_twin`** if `run_atom_log` table becomes a real need

---

## Graph layer (Stage 3 — when it earns its keep)

Triggers:
- LLM Client B authoring session needs typed cross-cutting queries ("get me all KR apparel User Buyers with technologicalExp = JOURNEYMAN")
- A twin UI is built (scenario editor, run viewer, persona browser)

Implementation: **`com.expediagroup:graphql-kotlin-spring-server`**, embedded in the orchestrator JVM. Schema derives from Kotlin types via reflection — the same Persona / Scenario / Run types already defined become the graph automatically.

```kotlin
@GraphQLApi
class ScenarioQueries(private val repo: ScenarioRepository) {
  @GraphQLDescription("List scenario runs filtered by tag and outcome")
  fun runs(tag: String?, outcome: Outcome?): List<ScenarioRun> = repo.find(tag, outcome)
}
```

Endpoint at `/graphql` on the same Spring server that exposes any health/ops endpoints. No separate container, no Hasura, no metadata file to maintain.

**Why not Hasura?** Hasura earns its keep when there's real RLS / multi-consumer / multi-tenant complexity. Twin is single-tenant single-user with ~2 query consumers. Hasura's value is wasted at this scale; the ops weight (one more container, metadata management, permissions) is real.

**When twin graduates to product (Stage 4):** the right tables fold into mother as a tenant-scoped domain, and mother's *existing* Hasura tracks them. Twin's embedded graphql-kotlin gets retired or scoped down to the bits that don't migrate. At no point does twin need its own Hasura.

---

## What this does NOT cover

- **Mirroring mother data into twin** — never. Twin queries mother via M8TRX's public APIs (REST, GraphQL Hasura, NATS); it does not maintain a local copy.
- **Authoritative SKU catalog ownership** — open question: is the Decathlon-curated SKU list authoritative *in twin*, or is it fed into a customer's M8TRX `item` catalog and read back? Probable answer: SKUs originate as twin's curated data, get pushed into M8trxDemo via `/api/v2/.../items` on scenario init, and from then on mother is authoritative. Twin's `sku_catalog` is the *source* file, not the runtime cache.
- **Audit-log integration** — twin doesn't write to mother's `audit_log`. Twin's own internal mutations (scenario edits, run creations) are tracked in twin's own DB if needed; they're not interesting to M8TRX.
- **Multi-environment (dev/stage/prod)** — twin runs in one place at MVP. Multi-env is a Stage 4 concern.

---

## Cross-references

| Doc | Purpose |
|---|---|
| `LAYER4-CONFIG-SCHEMA.md` | Scenario config shape (consumed at orchestrator startup; persisted as JSON in twin's `scenario.config_json`) |
| `PERSONA-SCHEMA.md` | Persona type hierarchy (instances persisted in `persona_seed.blob_json`) |
| `m8trx-shared/CLAUDE.md` § "Migration file conventions" | Inherited convention: no inner `BEGIN`/`COMMIT`; companion `_rollback.sql` required |
| `m8trx-shared/twin/insights/2026-05-10-vertical-portability-ddl.md` | Related: when twin's `vertical` axis would force a corresponding column on mother's `tenant` |
