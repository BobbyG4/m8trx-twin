# Session Log — M8TRX Twin

> **Full detail:** per-session working notes in `status/session-notes/`.
> **Archive:** full pre-rebuild log at `status/archive/sprint/SESSION-LOG-FULL-ARCHIVE-2026-05-16.md`.

---

## Rolling Summary — Recent Sessions

**Session 6 (2026-06-22 · Twin)**
Catalog-coding + store-layout overhaul. Delivered **CORE-REQ-001** (inverse core→twin) catalog
attribute coding — `brand` (←vendor), `classification.csv` (+per-class `attributes_schema`),
`display_lookup.csv` (colour normalisation ×3 locales) — and **core absorbed it** (mother loaded +
verified, brief closed; core built a multi-catalog SurfaceProfile arch from our input). Built the
**MK/Hansae numeric-code coding profile** (`reference/data/mk-trend/`) as the contrasting 2nd model
(vertical-portability proof) from the real MK Trend spec (`reference/hansaemk/`, ex-Zenven). Bumped
jackson 2.18.2→2.21.3 (HIGH CVEs). Then diagnosed + fixed the **134-overlap MapCanvas bug** — root
cause **twin's hand-authored coords, not core's seed** — by rewriting `build_layout.py` parametric →
**10 UNIQUE per-store floors** (seeded off store_id, 324–741 m², 0 overlaps asserted, layout-driven
planogram), scaled checkout lanes (4/3/2), added circular front-of-store feature displays, and made
twin emit **mother-canonical zone geometry** (circle = center `POINT Z` + `properties`; polygon =
`POLYGON Z` ring; SRID 0/mm/Z=0). Open: **full mother reseed** of per-store layouts + geometry +
re-receive 277k EPCs at new fixtures (mother still on the old overlapping layout). ⚠ committed prod
Hasura admin secret in `scripts/seed_store.py` (Bob to rotate later).

**Session 5 (2026-06-11 · Twin)**
Pivoted from the onboarding-baseline plan to **building the multi-store chain dataset**. Shipped
**Wave 1** under `reference/data/chain/`: 14 sites (10 retail + 4 office), 251 users (30 inactive,
tenant-admin `zenvendemo@gmail.com`), 2,586-SKU catalog, **277,515 EPCs**, one shared 160-zone
layout, localized USD/EUR/KRW · EN/FR/KO. Deterministic builders (`build_layout` / `localize_names` /
`build_chain` / `build_staff_roster`, sha256 seeds). **Backend seeded it to M8trxDemo** (tenant
`ecfa6903…`, site-level) and filed **IMPORT-CONTRACT** + a **5-point corrections** doc + **TWIN-REQ-002**
(`commerce_projection` writer). Biggest correction: **fixtures are zones (`zone_type='fixture'`), not a
separate table** — applied it (unified 160-zone template, canvas-ready). Captured the lot in a reusable
**SEED-PLAYBOOK**, a Phase-2 **ACTIVITY-PLAN** (customers/staff/item-movement → analytics, Connect
simulator, reset-to-opening-state to-do), and an **EXPANSION-PLAN** (Wave 2 = 10 international stores,
varied layouts; Wave 3 = online + DCs + 3PL). Fixed a determinism bug (`hash()`→`sha256`) and an
email-dedup bug. Open: image pipeline (cache bytes vs hot-link) gates Wave-2 images.

**Session 4 (2026-06-03 · Twin)**
Store-data strategy re-based on **live regional catalogs**. Two-store model: **Denver (US)** built from the live US Decathlon Shopify catalog — 2,586 real SKUs, 35,912 EPCs, **100% real images**, real US names/prices/sizes; **Seoul (KR city) parked** (images need live-KR Algolia re-base; only 6% free overlap with US). **EPC encoder validated** clean-room against 169k real warehouse tags (filter 1 / partition 6; encode reproduces real tags bit-for-bit). US-calibrated operating model + benchmark research captured. Denver rides the Manhattan layout as-is (GPS watch cases empty — no US watches). Not yet seeded to mother (gated). Store renamed Manhattan→Denver on mother (manual; threw failures first). Closed with a hand-off: next session = **Coordinator-track onboarding-baseline plan** across core + twin.

**Session 3 (2026-05-11 · Twin)**
First code. Kotlin scaffolded, NATS smoke passed (140ms). Decathlon Manhattan store seeded: 160 zones + 3 try_on_zones + 920-SKU catalog on mother. Key gap found: service bearer auth fails on `InventoryActionController` (JWT-only). MapCanvas all-zones-same-green bug contracted to core web session (now fixed in core Session 70/71).

**Session 2 (2026-05-10 · Twin)**
Layer 4 Step A complete. Persona schema (3 kinds), Journey contract, DomainEvent taxonomy (15 events), Snapshot format, DB + graph plan (dedicated PG on mother, no standalone Hasura, embedded graphql-kotlin). Stack locked: Kotlin.

**Session 1 (2026-05-09 · Twin)**
Layer 4 architecture locked: Generator interface, Scheduler (3 rate modes), EventBus, Trinity generator catalog. TWIN-REQ-001 (`fitting_room` → `try_on_zone`) absorbed into core.

---

## Session Index

| # | Date | Summary | Notes |
|---|------|---------|-------|
| 6 | 2026-06-22 | CORE-REQ-001 catalog coding delivered + **ABSORBED** by core; MK/Hansae 2nd coding profile built (portability proof); jackson 2.18→2.21 (CVEs); 134-overlap layout bug fixed → 10 unique per-store layouts; mother-canonical zone geometry (circle POINT Z + properties / polygon POLYGON Z) | [→](session-notes/2026-06-22-session-6-catalog-coding-perstore-layouts.md) |
| 5 | 2026-06-11 | Multi-store chain dataset (14 sites, 251 users, 277k EPCs) seeded; backend corrections digested into playbook; fixtures-as-zones applied; Phase-2 activity + Wave-2/3 roadmap | [→](session-notes/2026-06-11-session-5-chain-seed-corrections-playbook-roadmap.md) |
| 4 | 2026-06-03 | Re-base on live catalogs — Denver (US, real+images) built; Seoul parked; EPC encoder validated; onboarding hand-off | [→](session-notes/2026-06-03-session-4-store-rebase-denver-real-catalog.md) |
| 3 | 2026-05-11 | First code — Kotlin + NATS + store seeded + 920 SKUs | [→](session-notes/2026-05-11-session-3-first-code-nats-store-seeded.md) |
| 2 | 2026-05-10 | Persona + Journey + DomainEvent + Snapshot + persistence plan | [→](session-notes/2026-05-10-session-2-persona-journey-domainevents.md) |
| 1 | 2026-05-09 | Layer 4 schema lock + Trinity generator catalog | [→](session-notes/2026-05-09-session-1-layer4-schema-lock.md) |
