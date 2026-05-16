# M8TRX Twin — Project Context

Digital twin of a retail store, generating realistic event streams through M8TRX's public APIs. Same platform a customer would integrate against; same auth, same data flows, same surfaces. The output is real M8TRX tenant data, indistinguishable in shape from a live customer's data.

**Purpose:**
- **Dev fixture** — every backend and surface gets exercised against realistic data, not 100 boxes in the office
- **Investor surface** — investor demos run against the live platform on real data, not mocked screens
- **Customer demo** — sales engineers, channel partners, and customer middle-managers can run scripted or natural-language scenarios
- **Marketing source** — screenshots, GIFs, time-lapse heatmaps extracted from the "A Day in the Life" recording
- **Eventual product feature** — `M8TRX Twin` as a customer-self-serve scenario tool ("show me a busy Saturday in running shoes")

---

## Posture: System Integrator (HARD RULE)

**This project relates to m8trx-core the way a third-party customer integration would. We CONSUME public APIs; we do NOT modify core.**

### Permitted surfaces

- **REST** — m8trx-services public endpoints (`api.m8trx.com`, `dev.m8trx.com`, internal LAN equivalents)
- **GraphQL** — Hasura v2 (`mother.m8trx.com/v2/v1/graphql`)
- **Streaming** — NATS JetStream (`192.168.55.29:4222`) and NATS WebSocket (`192.168.55.29:8443`)
- **Webhook ingest** — `POST /v1/webhook/{tenantId}/{integrationKey}` (HMAC-signed)

### Authentication

- API key bearer (`Authorization: Bearer m8trx_…`) for service principals — same path edge subscribers walk
- JWT for user-class operations (login, capability-gated mutations)
- HMAC for webhook ingest

### Forbidden

- Direct DB access (no `docker exec … psql`, no SSH into mother to read or write tables)
- Reading or modifying core source code from this project's sessions
- Workarounds that bypass the public API surface
- Adding fields, columns, GraphQL ops, or controller endpoints to core "to make the demo work"
- Embedding credentials in committed code (use env vars + 1Password references)

### Requirements flow back through the front door

When the public API doesn't expose what we need:

1. **Stop.** Do not shim. Do not duplicate. Do not work around.
2. Write a brief at `~/IdeaProjects/m8trx-shared/twin/requirements/TWIN-REQ-<NNN>-<short-slug>.md` (format documented in `m8trx-shared/twin/SISTER-PROJECT.md`).
3. Reference the brief in `status/STATUS.md` under "Active Requirements Filed Back to Core" with status `FILED, AWAITING ABSORPTION`.
4. Core picks up the brief on its own schedule, creating a corresponding sprint task that references the brief by path.
5. When core ships, the brief gets a closing header (`absorbed-into-sprint`, `merged-commit`, `closed-date`); twin updates STATUS.md to `ABSORBED` and integrates against the new surface.

The brief lives in `twin/requirements/` for its entire lifecycle — never duplicated into sprint/. One artifact, two read points. The force-function only works *because* of this segregation. Patch a gap inside m8trx-twin and the gap stops being visible. Surface it; let core close it. Every gap closed this way is also a gap closed for real third-party integrators.

---

## Architecture: Layered Generators

Five layers. Each layer's interface is the contract for the layer above. Detailed schema in `reference/architecture/LAYER4-CONFIG-SCHEMA.md`.

| Layer | Role | Examples |
|-------|------|----------|
| **0 — Atoms** | 1:1 emitters against M8TRX public APIs | `emitObjLocation`, `emitObjEviction`, `emitRfidScan`, `emitSaleWebhook`, `emitFittingRoomEvent` |
| **1 — Behaviors** | Single-actor, time-extended actions | `walkPath`, `dwellAt`, `pickUpItem`, `tryOn`, `payAtRegister` |
| **2 — Journeys / Personas** | Full single-actor arcs with personality | `BrowseAndLeave`, `ShopAndBuy`, `TryOnAndPartialBuy`, `Shoplift`, `StaffRestock`, `StocktakeWalk` |
| **3 — Generators** | Population-level statistical producers | `TrafficGenerator`, `StaffShiftGenerator`, `TransactionGenerator`, `StocktakeGenerator` |
| **4 — Orchestration API** | Declarative scenario config; the consumer-facing surface | `ScenarioOrchestrator.run(config)` |

**Two clients of Layer 4 — both first-class:**

- **Client A — Scripted scenarios** (initial ship target). "Saturday Rush", "The Theft", "Fitting Room Conversion", "Compliance Day", "A Day in the Life" are configs fed to the orchestrator. Repeatable with seeds.
- **Client B — LLM authoring** (later, but design-committed now). Anthropic tool-use loop. Tools are config-builder primitives. Natural language in ("typical Tuesday afternoon with one suspicious customer in watches"), config out, orchestrator runs.

**Architectural discipline:** Layer 4's config schema is the API contract committed *before* code below it is written. Adding the LLM client later is a 1-2 day extension, not a refactor. The discipline costs ~10-15% extra design work up front; retrofitting it later costs 2-3 weeks.

---

## Reference Store

Decathlon Korea, mid-format running + fitness specialty. ~2,000-3,000 SKUs curated from the full catalog (~20k items).

- **Layout source** — authored from research (Decathlon store walkthroughs, public press kits, store concept renders); no real plan available. Detail in `reference/data/STORE-LAYOUT.md` (TBD).
- **Footprint** — ~30m × 50m, ~1,500 sqm
- **SKU curation** — filter Decathlon Korea catalog by category (Kalenji + Kiprun running, Domyos fitness, Geologic accessories, selected Quechua crossover); variant-expand by size/color; anchor to fixtures. Detail in `reference/data/SKU-CURATION.md` (TBD).
- **Try-on zones** — apparel fitting rooms (4-6 stalls), footwear bench area, GPS watch demo station. Three behavioral profiles, not one. Note: current core schema's `fitting_room` table is apparel-specific (filed as core requirement: generalize to `try_on_zone`).
- **EAS gates** — 1 main entrance, EAS-tagged premium SKUs (Garmin watches, Kiprun premium shoes) for the LP demo

---

## Tenant Model

The **M8trxDemo** tenant is the canonical instance. Lives on mother alongside real customer tenants. Provisioned via the same signup/onboarding flow a real customer walks (itself a useful test of customer onboarding friction — capture findings).

**Sharing mechanism:** M8trxDemo grants every customer tenant read-share into its demo site via `tenant_share_grant` (mig 104). When a new tenant signs up, the existing "stub demo site" seed gets replaced/augmented by a Reach-share into M8trxDemo. Customer logs into their account, sees the demo site running real data alongside their (empty) real site, clearly badged DEMO. Zero per-customer setup; one source of truth; M8TRX Reach dogfooded in production.

**Operator:** the platform-admin tenant operates M8trxDemo (cross-tenant read for share grants needs to live somewhere). This justifies keeping platform-admin's tenant binding rather than removing it.

**Channel partners:** the same Reach-share pattern extends to channel partner tenants, who then share onward to their prospects. Three M8TRX Reach hops in production.

---

## Insights Surfaced by Demo Build

Running list of schema/API improvements the demo work makes obvious. Each gets filed back to core as a requirement brief; track status here.

- **`fitting_room` → `try_on_zone` generalization** — current Layer-1 spatial primitive is apparel-specific; demo needs footwear bench + watch demo station rendered as try-on zones. Same behavioral analytics work across all kinds. Status: under discussion (Bob + Amy / call); NOT YET FILED.
- **`commerce_projection` writer** — substrate (mig 112) exists; writer unfed. Headline blocker for the demo's commercial story (3 of 5 scenario scripts depend on it). Pattern: Hasura event trigger on `item_custody_event` SOLD transitions → controller → projection hypertable. Precedent: audit-log capture chain (mig 101). Status: pending audit confirmation in core; NOT YET FILED.
- **`inventory:sell` capability split** — currently piggybacks `inventory:transfer`. Surfaces when scenarios author a "cashier" persona. Status: PRE-EXISTING (in core's CLEANUP-TASKS.md).
- **Per-vendor field mapping (Lightspeed Retail)** — optional; lets the demo claim "this is Decathlon's real Lightspeed feed" defensibly. Lower priority than commerce_projection. Status: NOT YET FILED.

Append new findings here. When a finding moves from *discovered* to *filed*, note the brief path inline.

---

## Off-Limits

CC sessions in this project must NEVER:

- Modify, delete, or copy from m8trx-services / m8trx-edge / m8trx-web / m8trx-android / m8trx-api / m8trx-shared repos
- Author SQL migrations targeting mother
- Issue Hasura admin requests (track table, modify permissions, set unauthorized_role behavior)
- Bypass authentication ("just for testing")
- Embed credentials in committed code
- Run destructive operations on production tenants other than M8trxDemo (and even then, only via the public delete-tenant flow)

If a session believes it must do any of the above, it stops and asks. The Destructive Changes Protocol from m8trx-shared applies in spirit: the rule wins.

---

## Module Relationships

| Repo | Relationship |
|------|--------------|
| **m8trx-services** | Platform we integrate against (REST, NATS publish, webhook ingest) |
| **m8trx-edge** | Platform we integrate against (NATS subjects for VisionAI events) |
| **m8trx-api** | Schema source — we consume types, never publish |
| **m8trx-shared** | Source of truth for core's roadmap; awareness only, never modify |
| **m8trx-web** | Consumer of the data we generate (we don't touch its source) |
| **m8trx-android** | Consumer of the data we generate (same) |

---

## Reference Documents

| Document | Location |
|----------|----------|
| **Project status** | `status/STATUS.md` — `## ⚠ NEXT SESSION PRIORITIES` (authoritative for what's next) |
| **Track state** | `status/tracks/TRACK-TWIN.md` — current branch/deploy state + open work + blocked items. Read at session-start. |
| **Session log** | `status/SESSION-LOG.md` — rolling summary + session index with links to session-notes |
| **Session notes** | `status/session-notes/` — detailed per-session working notes. Created every session close. |
| **Briefs** | `status/briefs/` (unfired) · `status/active/` (in-flight) · `status/archive/sprint/` (done) |
| **Sister project contract** | `~/IdeaProjects/m8trx-shared/twin/SISTER-PROJECT.md` — relationship rules, brief-filing protocol |
| Layer 4 config schema | `reference/architecture/LAYER4-CONFIG-SCHEMA.md` |
| Scenario library | `reference/scenarios/` (TBD per script) |
| SKU curation spec | `reference/data/SKU-CURATION.md` |
| Store layout reference | `reference/data/STORE-LAYOUT.md` |
| API surface | `reference/integration/M8TRX-API-SURFACE.md` |
| Tenant provisioning | `reference/ops/TENANT-PROVISIONING.md` (TBD) |

---

## Current Phase

**Active development.** Kotlin scaffold compiles, NATS live, Decathlon Manhattan store seeded (Session 3). Next: item/EPC seeding + TrafficGenerator. See `status/STATUS.md` and `status/tracks/TRACK-TWIN.md` for current priorities.
