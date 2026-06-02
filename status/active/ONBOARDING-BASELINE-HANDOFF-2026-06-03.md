# HAND-OFF — Customer Onboarding Baseline (core + twin)

**Created:** 2026-06-03, end of Twin Session 4. **For:** next session (recommend **Coordinator track**).
**Mission:** baseline what it takes to onboard a real customer, across BOTH m8trx-core and the twin,
and design a real **onboarding surface + supporting APIs**. Clean up the UI holes/broken state found
while setting up the Denver store.

---

## Why this hand-off exists

Building the Denver twin store end-to-end (catalog → assortment → EPCs → fixtures → seed) exposed that
**onboarding a store is mostly manual or missing** in core, and that several UI surfaces are broken or
absent. Bob: "so many holes in the UI and so much is broke… trying to baseline what we need." This is a
cross-project architectural effort, not a quick fix — it gets its own session.

---

## State at hand-off

**Twin / Denver — DONE, ready to seed (gated):**
- Real US catalog re-base: 2,586 SKUs, 35,912 EPCs, 100% real images, validated EPC encoder.
- Rides the existing Manhattan `STORE-LAYOUT.md` fixtures as-is (140/149 used; GPS watch cases empty
  — no watches in US catalog). "Real Denver outdoor layout" deferred by decision.
- Files: `reference/data/analysis/denver-assortment.csv` + `denver-epcs.csv`, `scripts/build_denver.py`.
- **Not yet seeded to mother** (prod write gated; tenant/site/space naming undecided).

**Twin / Seoul — PARKED.** KR assortment built; images need live-KR (Algolia) re-base. See Session 4 notes.

**Full session detail:** `status/session-notes/2026-06-03-session-4-store-rebase-denver-real-catalog.md`.

---

## Onboarding gaps already surfaced (the spine of the plan)

From Session 4 notes — mapped to FR areas; **exact FR numbers still to be pulled from `9a`**:

**Day-1 trio (hard go-live blockers):**
1. **Catalog import** — bulk product ingest (name, price, EAN, category, **images**) from feed/API.
   Today: webhook stub only; we used direct Hasura. Candidate on-ramp: import a customer's existing
   e-com catalog (Shopify/feed) directly — proven viable by the Denver pull.
2. **EPC encoding config surface** — tenant constructs their RFID tag scheme (company prefix, filter,
   partition, serial). Today: nonexistent; we built it clean-room (`EPC-ENCODING-DECATHLON.md` = spec).
3. **Product imagery** — store + serve product images on inventory surfaces. Today: unknown/none.

**Behind those:**
4. Service/machine auth on inventory endpoints (`SERVICE-BEARER-INVENTORY`).
5. Item/EPC provisioning at fixture granularity (`inventoryReceive` is space-level; fixture placement seed-only).
6. Store layout / planogram authoring per store-type (we hand-built; no tool — Denver-on-Manhattan-layout mismatch is the worked example).
7. Commerce/sales ingest → `commerce_projection` writer (unfed).

---

## Brainstorm seed — "what it takes to onboard a real customer" (expand next session)

A first holistic pass (not just the data gaps above) — the full journey a new tenant walks:

- **Tenant + org/site/space setup** — the hierarchy wizard (perms-v3 provisioning exists; UI?).
- **User + role provisioning** — invite staff, assign roles (capabilities exist; onboarding UX?).
- **Store layout authoring** — define space dimensions, zones, fixtures; per store-type templates.
- **Catalog import** — products, prices, categories, **images**, attributes (Day-1 #1).
- **EPC / tag encoding setup** — the RFID scheme (Day-1 #2).
- **Initial stock provisioning** — the "RFID encoding walk" / receive + place at fixtures (gap #5).
- **Sensor / hardware registration + calibration** — Xovis cameras, RFID readers, EAS gates;
  SRF/ARLS calibration. (Onboarding the *physical* layer — likely the biggest hidden surface.)
- **Integration connection** — POS/sales feed, webhooks, API keys (gaps #4, #7).
- **Go-live checklist / progress tracking** — a guided wizard with per-step status (the So-Yeon-usable
  onboarding flow; mirrors the AR Bootstrap 4-gate idea on the Android side).

**Framing question for the plan:** is "Onboarding" a single guided surface/wizard that orchestrates
all of the above with progress state, or a set of independent admin surfaces? (Lean: a wizard shell
over independent, also-standalone surfaces.)

---

## Open questions to resolve in the planning session

- Which gaps are **MVP-blocking** vs post-MVP? (Day-1 trio is the candidate MVP set.)
- File as **TWIN-REQ briefs** back to core, or as core sprint tasks directly?
- Catalog-import on-ramp: generic feed/CSV, Shopify-style connector, or both?
- Where do **product images** live in the platform (storage + serving + which surfaces consume)?
- Store-layout authoring: build a tool, or template + import for MVP?
- Sensor onboarding: how much is in scope for customer self-serve vs ITX-assisted?

---

## Next-session setup checklist

1. Run as **Coordinator track** (cross-project) — read core `STATUS.md` + twin `STATUS.md`.
2. Pull the relevant FRs from `requirements/.../9a. Functional Requirements.md` (FR-PLAT, FR-INV,
   FR-COLLECT, FR-INTEG, FR-SPATIAL) and map each onboarding gap to a specific FR (or flag NEW).
3. Quick audit of core's existing onboarding/admin UI surfaces — what exists, what's broken, what's missing.
4. Consider a **working-draft** to iterate the plan with Bob.
5. Decide Denver seed (naming + go) — small, can fold in or keep separate.
