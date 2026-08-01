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
- **Streaming** — NATS JetStream (`192.168.55.29:`**`4223`**) and NATS WebSocket (`192.168.55.29:8443`)
  ⛔ **TWO EDGES SHARE `.29` — twin publishes to `:4223` ONLY.** `:4223` = `edge-twin-denver` (twin's). `:4222` = `edge-itx-office`, **production, real Xovis hardware** — publishing there injects synthetic people into a live office deployment. This line named `:4222` until 2026-08-01, i.e. the hard-rule doc pointed at production; every code path had it right (`TwinConfig.kt:16`, `PeopleDrive.kt:32`, `DayDrive.kt:39`) and `DayDrive.kt:193` refuses to publish unless the broker's `server_name` matches the twin edge. The guard is in code because a doc cannot enforce anything.
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

**A 14-site Decathlon chain, not one store** — 10 retail + 4 office under the single M8trxDemo tenant. ⚠ **This section described a single ~1,500 sqm Decathlon Korea running-specialty store until 2026-08-01; that concept was superseded by the Wave-1 chain build in Session 6 (2026-06-22) and the description simply never followed.** The single-store concepts it went through (Bordeaux 160 sqm → Decathlon City 600 sqm → Manhattan) are history; STATUS's `## Store Concept (locked Session 3)` records that lineage.

- **Reference store / drive target** — **`dec-us-denver`** (Denver, CO, 1515 16th Street Mall), tier `flagship`, **600 m²**. Every people-drive, impression run, planogram directive, and alarm this project has fired went at Denver. The other 9 retail stores are real and seeded but rarely driven.
- **Layout source** — generated, not authored: `scripts/build_layout.py` derives each store's footprint, gondola grid, aisle widths, and fixture counts deterministically from `sha256(store_id)` → `reference/data/chain/stores/<slug>/layout.json`, one unique floor per retail store. `reference/data/STORE-LAYOUT.md` documents the shared grammar and is **current** (redesigned 2026-06-22); the generator is authoritative for coordinates.
- **Spatial model** — each retail store = **3 spaces** (`sales_floor` / `stockroom` / `fitting_room`), each its own SRF frame; departments are `region` zones *inside* the sales floor. 30 spaces / 929 zones chain-wide.
- **Catalog** — **2,586-SKU master** (Decathlon Korea raw catalog → coded + USD-priced), 22,975 SKU rows across the 10 stores, **102,675 EPCs** (~18% staged back-of-house). Curation and placement live in `scripts/build_chain.py` + `reference/data/chain/CHAIN-DATA-SPEC.md`. ⚠ **`reference/data/SKU-CURATION.md` was referenced by this doc for months and has never existed** — the curation that actually shipped is the code above.
- **Try-on zones** — apparel fitting rooms, footwear bench area, and the GPS demo cases (`Z-06`, 6 fixtures in Denver). Three behavioral profiles, not one. ⚠ The GPS cases are real **fixtures** and are browsed by generated shoppers, but **no watch SKU exists to demo** (see the EAS note below) — the fixture is real, the merchandise is not. Note: core's `fitting_room` table was apparel-specific; generalized to `try_on_zone` via TWIN-REQ-001 (ABSORBED 2026-05-09, mig 127).
- **EAS gates** — 1 main entrance (`CS-01` "Main Entrance Gate", present in all 10 stores as an `eas_gate` crossing slice with real SRF geometry). **EAS-tagged stock is defined by a RULE, not a list** — `price_usd >= $150 AND category != "outdoor"`, i.e. premium *and* concealable, since every bulky line (road bikes, tents, trainers) lives in `outdoor`. Yields **271 of Denver's 2,586 SKUs**, concentrated on `PE-02`/`PW-02` Kiprun premium footwear. Rule + rationale: `layer2/EasTagging.kt`.
  ⚠ **Tag state is TWIN-SIDE. The platform does not know it and never sees it** — a real third-party EAS owns tag state. That makes twin minting it faithful emulation, *provided* it is never implied to be something M8TRX stores or could verify.
  ⚠ **Corrected 2026-07-31:** this line previously named *"Garmin watches"* and STATUS named *"W-series sports watches ($29.99–$89.99), EAS-tagged, 40 items"*. **The live 2,586-SKU chain catalog has ZERO watch SKUs** — that anchor belonged to the superseded 920-SKU Manhattan concept. Session 8's static-seed audit flagged it and it went unclosed for two months. `scenarioSelfTest` now asserts the absence so it cannot be restored by accident.

---

## Tenant Model

The **M8trxDemo** tenant is the canonical instance. Lives on mother alongside real customer tenants. Provisioned via the same signup/onboarding flow a real customer walks (itself a useful test of customer onboarding friction — capture findings).

**Sharing mechanism:** M8trxDemo grants every customer tenant read-share into its demo site via `tenant_share_grant` (mig 104). When a new tenant signs up, the existing "stub demo site" seed gets replaced/augmented by a Reach-share into M8trxDemo. Customer logs into their account, sees the demo site running real data alongside their (empty) real site, clearly badged DEMO. Zero per-customer setup; one source of truth; M8TRX Reach dogfooded in production.

**Operator:** the platform-admin tenant operates M8trxDemo (cross-tenant read for share grants needs to live somewhere). This justifies keeping platform-admin's tenant binding rather than removing it.

**Channel partners:** the same Reach-share pattern extends to channel partner tenants, who then share onward to their prospects. Three M8TRX Reach hops in production.

---

## Insights Surfaced by Demo Build

Running list of schema/API improvements the demo work makes obvious. Each gets filed back to core as a requirement brief; track status here.

- **`fitting_room` → `try_on_zone` generalization** — Layer-1 spatial primitive was apparel-specific; the demo needs the footwear bench and GPS demo cases rendered as try-on zones too. Status: ✅ **ABSORBED 2026-05-09 as TWIN-REQ-001** (core mig 127); 53 try-on zones live on mother. *(This line read "under discussion; NOT YET FILED" until 2026-08-01 — stale by ~3 months, and contradicted by STATUS's own ledger.)*
- **`commerce_projection` writer** — substrate (mig 112) exists; writer unfed. Headline blocker for the demo's commercial story (3 of 5 scenario scripts depend on it). Pattern: Hasura event trigger on `item_custody_event` SOLD transitions → controller → projection hypertable. Precedent: audit-log capture chain (mig 101). Status: **FILED 2026-06-11 as TWIN-REQ-002** (`~/IdeaProjects/m8trx-shared/twin/requirements/TWIN-REQ-002-commerce-projection-writer.md`), AWAITING ABSORPTION.
- **`inventory:sell` capability split** — currently piggybacks `inventory:transfer`. Surfaces when scenarios author a "cashier" persona. Status: PRE-EXISTING (in core's CLEANUP-TASKS.md).
- **Per-vendor field mapping (Lightspeed Retail)** — optional; lets the demo claim "this is Decathlon's real Lightspeed feed" defensibly. Lower priority than commerce_projection. Status: NOT YET FILED.
- **A Connect integrator cannot acquire a capability it was not minted with** — the admin-only key surface and SEC-1's subset guard are each correct alone and together leave no path; §7's documented route (`PATCH /connect/service-keys/{keyId}/scopes`) is `CONNECT_NOT_EXPOSED` to every Connect key. Status: **FILED 2026-07-31 as TWIN-REQ-005**. Twin is not blocked — twin asks a human, which is exactly what a real integrator cannot do.
- **Connect READ surface** (task · space/zone · impression read) — Status: ✅ **SATISFIED, closed 2026-07-31 as TWIN-REQ-004**, with twin as the external prover. **`commerce_projection` read-back** (TWIN-REQ-003) likewise SATISFIED 2026-07-02.

Append new findings here. When a finding moves from *discovered* to *filed*, note the brief path inline. **The ledger of record is STATUS.md § Active Requirements Filed Back to Core** — when these two disagree, STATUS wins and this list is the one to fix.

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
| ~~Scenario library~~ | ~~`reference/scenarios/`~~ — **does not exist.** Scenarios live in code: `layer2/Journeys.kt` (incl. `Shoplift`), `layer3/{OperatingModel,TrafficGenerator,ScenarioRun,DayDrive}.kt`, asserted by `scenarioSelfTest` |
| ~~SKU curation spec~~ | ~~`reference/data/SKU-CURATION.md`~~ — **never existed.** See `scripts/build_chain.py` + `reference/data/chain/CHAIN-DATA-SPEC.md` |
| Store layout reference | `reference/data/STORE-LAYOUT.md` — current (redesigned 2026-06-22, per-store parametric) |
| Chain dataset spec | `reference/data/chain/CHAIN-DATA-SPEC.md` · reseed hand-off `DEPLOY-HANDOFF.md` |
| API surface | `reference/integration/M8TRX-API-SURFACE.md` |
| Connect drive run-card | `status/tracks/TRACK-TWIN.md` § Drive run-card — env + the four-number protocol for any live drive |
| Tenant provisioning | `reference/ops/TENANT-PROVISIONING.md` — exists, but **DRAFT 2026-05-10 and never verified against live mother** (predates the chain build and RE-RESEED v2) |

---

## Current Phase

**Active development, Session 18 (2026-08-01).** ⚠ *This section described Session 3 — "Kotlin scaffold compiles, NATS live, Manhattan store seeded; next item/EPC seeding + TrafficGenerator" — until 2026-08-01. All of that shipped long ago (chain seeded S6, RE-RESEED v2 verified S8, `TrafficGenerator` built S15).*

Where the project actually is:

- **Data** — 14-site chain live on M8trxDemo, verified byte-for-byte against the committed dataset (RE-RESEED v2, 2026-06-26).
- **Connect** — the entire surface is live-exercised from outside: inbound webhooks (6 data-types, 7th in flight), Bearer data plane, §6.5 read half, §9 outbound, planogram/movement/receive drivers. 13+ `connect*` gradle drivers.
- **People pipeline** — proven camera-free at full-day volume, and as of 2026-07-31 with **zero persistence loss** (oracle 1512 = wire 1512 = rows 1512).
- **★ Standing role** — **`./gradlew connectAcceptance` is the Connect ship gate**, because no CI suite anywhere drives a Connect key against a Connect endpoint. Green before ship; extend it as the surface grows; declared coverage gaps print alongside failures. Detail + the non-vacuity rule: `status/tracks/TRACK-TWIN.md` § STANDING ROLE. A second standing gate — the **cold-onboarding peer test** — must be run by a *fresh* session holding only the published Connect doc, never by a lane that helped design the surface.

Authoritative for what's next: `status/STATUS.md` § ⚠ NEXT SESSION PRIORITIES, then `status/tracks/TRACK-TWIN.md`.
