# Session 5 — Multi-store chain seed, backend corrections, playbook + Phase-2 roadmap

**Date:** 2026-06-11 · **Track:** Twin · **Branch:** main

---

## Context / pivot

Session 4 closed pointing at the **onboarding-baseline plan** as NEXT. Bob redirected at the top of
this session: **build the multi-store chain DATASET now** (populate M8trxDemo as a realistic chain so
every surface is exercised against chain-scale data), and defer the onboarding-surface design.
Division of labor locked: **twin assembles the data; backend defines the ingest schema + seeds.**

---

## What shipped (twin side) — the dataset + tooling under `reference/data/chain/`

**Wave-1 chain:** 14 sites (10 retail + 4 office), 251 users, 2,586-SKU catalog, **277,515 EPCs**,
one shared 160-zone layout, localized (USD/EUR/KRW · EN/FR/KO).
- Stores: 3 US (Denver/NYC/SF), 5 FR (Paris/Lyon/Lille/Marseille/Bordeaux), 2 KR (Seoul/Busan). 5 IANA tz.
- Office sites: HQ (Villeneuve-d'Ascq) + US/FR/KR regional — `site_type=office`, **no space** (asserted invariant; build fails if violated).
- Roster: HQ + regional + per-store, **30 inactive** (varied reasons), locale-appropriate names, tenant-admin = `zenvendemo@gmail.com`.

**Scripts (all deterministic):** `build_layout.py` · `localize_names.py` · `build_chain.py` ·
`build_staff_roster.py` · config in `chain_config.py`. Pipeline order:
`build_layout → localize_names → build_chain → build_staff_roster`. Per-store seed = `sha256(store_id)`.

**Docs:** `CHAIN-DATA-SPEC.md` (data dictionary) · `IMPORT-MAPPING.md` · `DEPLOY-HANDOFF.md` ·
`SEED-PLAYBOOK.md` · `ACTIVITY-PLAN.md` · `EXPANSION-PLAN.md`.

## What backend did (parallel, same day)
- **Seeded the chain to M8trxDemo** — `tenant_id = ecfa6903-5c50-439f-8f80-185982de944e`. Via direct
  `psql` (REST/Hasura write paths were tenant/role-blocked). **Site-level** inventory, USD display,
  fixtures redone as zones. Reversible via tenant-delete; pre-seed backup on `mother`.
- Filed in `m8trx-shared/twin/`: `insights/IMPORT-CONTRACT.md`,
  `insights/2026-06-11-seed-exercise-corrections.md` (commit `019f789`), and **TWIN-REQ-002**
  (`commerce_projection` writer).

## Key discoveries — backend corrections (the "don't repeat" record)
1. **Fixtures ARE zones (`zone_type='fixture'`), not a separate `fixtures[]`/table** — core's `fixture`
   table is unused (0 rows); importing there = **blank canvas**. Floor = 11 area + 149 fixture-zones =
   **160**. *Cost backend a rework — the biggest miss.*
2. **Inventory location reads from `scan_event.zone_id` at the fixture-zone**, not `thing_location`.
   Feed raw scan/receive; `thing_location`-only write → blank UI.
3. **Catalog is tenant-scoped & single-currency** — 22,944 store-SKU rows dedupe to 2,586 `product`
   rows; per-region prices preserved in `display_attributes.prices`, but display shows USD without core DDL.
4. **`site_type` is `managed|external`** (no `office`) → office = managed w/ no space. Roles → 6 core
   Profiles (member / site-manager / staff; nobody platform-admin).
5. Geometry (mm / SW-origin / SRID 0 / POLYGON Z) was **correct**.

## What was applied / built this session
- **Correction #1 applied** — rewrote `build_layout.py` to a unified `zones[]` of **160** (11 area +
  149 `zone_type='fixture'`, each with `in_area_zone` + `fixture_category`); `build_chain.py` derives
  the fixture set from fixture-zones; all docs updated. **Next layout import is canvas-ready, no fix-up.**
- **TWIN-REQ-002 protocol step 3** — added twin-side pointers (STATUS / TRACK / CLAUDE.md) `FILED, AWAITING ABSORPTION`.
- **SEED-PLAYBOOK.md** — digested the 5 corrections + import contract into a reusable recipe (pre-flight checklist, gotchas, core-gap tracker).
- **ACTIVITY-PLAN.md** (Phase 2) — the dynamic layer: customer/staff/item actors; in-day **item lifecycle** (pickup → wrong-rack / fitting-room / buy / abandon / theft); **staff journeys** (cashiering, engagement, **app-triggered fitting-room fetch**, restock, re-shelve, stocktake); activity→analytics map; generators/runtime; **reset-to-opening-state** to-do; **M8TRX Connect simulator**.
- **EXPANSION-PLAN.md** — **Wave 2** = 10 new international stores (DE/IT/ES/China×3/SG/IN/MX/BR), varied layouts; catalog strategy (China ← KR catalog, rest ← US Shopify; toward a full tenant catalog); **image-pipeline risk** (cache bytes vs hot-link); UI-then-API onboarding; **Wave 3** preview (online + DCs + 3PL).

## Bugs fixed (don't repeat)
- **Determinism bug:** per-store seed used Python `hash()` (salted per process) → EPC counts drifted run-to-run. Fixed → `sha256(store_id)`. Now byte-identical (md5-verified).
- **Email-dedup bug:** dedup checked the full email string against a base-keyed dict → silent duplicate emails in the roster. Fixed.
- Name localization is a **machine gloss** (~97% type coverage, ~3% residual English in compounds) — accepted at the "realistic, not accurate" bar.

## Decisions
- Wave 1: reuse US master + localize · shared assortment, varied stock · full named roster · real-anchored locations · office sites.
- Correction #1 applied; #2–5 captured in the playbook for the deploy side.
- Wave 2: **new stores** (not retrofit) · varied layouts (China differentiated) · China←KR, rest←US Shopify · Mexico ×1 · onboard **UI first, then API/Connect**.
- Reset: **logical reset to opening state** (epcs.csv = canonical opening state). **Not filing a formal req yet** — seed not 100% validated.
- Image pipeline: likely **cache bytes, not hot-link** — resolve with backend first.
- **Did NOT file new TWIN-REQ briefs** (reset / image / appliance / Connect) — data not validated; tracked as gaps instead.

## Open gaps surfaced (tracked; mostly not yet filed by deliberate choice)
- **Image pipeline** (hot-link vs cached bytes) — gates Wave-2 images. *Resolve with backend first.*
- Customer fitting-room request **"appliance" surface** — verify exists / else file.
- **Reset-to-opening-state** capability — agreed approach, deferred filing.
- **M8TRX Connect simulator** — needed component (external vendor feeds).
- Per-vendor field mapping (Lightspeed) — NOT YET FILED.
- Pre-existing: SERVICE-BEARER-INVENTORY · CATALOG-IMPORT-ONBOARDING (incl. images) · EAS-alarm subscriber · cold-start/manual location · commerce_projection (**TWIN-REQ-002**, filed).

## Branch / deploy state at close
- `main`; dataset + scripts + docs committed this session.
- **Wave 1 SEEDED** to M8trxDemo by backend (site-level inventory; fixtures-as-zones; USD display).
- Corrected **fixture-zone layout** + **scan/receive item placement** = the **pending follow-up deploy**.
- No twin code emits events yet (orchestrator runtime not built).
