# Session 4 — Store data re-base: Denver on real US catalog (+ Seoul parked)

**Date:** 2026-06-03 (KST) · **Track:** Twin · **Status:** CLOSED — handed off to onboarding-baseline planning (see `status/active/ONBOARDING-BASELINE-HANDOFF-2026-06-03.md`)

---

## TL;DR

Set out to seed a realistic store. Discovered through the work that the realistic path is to
**re-base each store on a live regional Decathlon catalog**, not extrapolate from one Korea dump.
Landed a **two-store model**:

- **Denver (US)** — re-based on the **live US Decathlon Shopify catalog**. Real products, US
  names/prices/sizes, **real images**, real EANs → validated EPCs. **2,586 SKUs / 35,912 pieces,
  100% imaged. Built and ready to seed.**
- **Seoul (KR, city-format)** — PARKED. We have a Korea-derived assortment + real demand data, but
  images need a separate live-KR retrieval. Plan captured below.

The store is fully fictitious (note: Decathlon has **no physical US stores** — US is online-only —
so "Denver flagship stocking the US online range" is a clean, uncontradicted premise). Brand rename
away from "Decathlon" is a later pre-market task.

---

## What we built this session (artifacts)

**Calibration + method (reference/data/):**
- `STORE-OPERATING-MODEL.md` + `.json` — US-calibrated operating model (footfall, conversion, ATV,
  category mix, shrink, depth). Reconciliation identity. Scale locked: ~5k SKUs / 20–40k pieces.
- `research/OPERATING-MODEL-BENCHMARKS-2026-06-02.md` — cited benchmark backup (deep-research run;
  verifier bug noted).
- `EPC-ENCODING-DECATHLON.md` — **clean-room EPC encoder, VALIDATED** against 169k real tags
  (header 0x30, filter 1, partition 6, indicator 0; EAN-derived; 100% catalog round-trip; encode
  reproduces real tags 2000/2000 bit-for-bit). `EanToEpc` Kotlin included.
- `INVENTORY-SEEDING-PIPELINE.md` — data-source assessment + planogram + the 6-stage pipeline.

**Architecture (reference/architecture/):**
- `TRAFFIC-GENERATOR-SKETCH.md` — Layer-3 generator design consuming the operating model.

**Korea data analysis (reference/data/):**
- `dump-pantos-202504250747.sql` (April, slow-season) — used to **validate the EPC scheme**.
- `dump-pantos-202606021444.sql` (Nov, peak) — real demand: 160k orders, ~2.3 UPT, item velocity.
  Single node `WH 1010 / STRR BPA057`, B2C/e-com fulfillment (NOT a multi-store feed).
- `analysis/nov-item-velocity.csv` — extracted velocity master.

**US catalog (reference/data/us-catalog/):**
- `page*.json` (482 products) + `detail/*.json` (per-product, with EAN barcodes + images).
- Pulled live from `decathlon.com` Shopify (`products.json` + `/products/{handle}.json`).

**Denver outputs (reference/data/analysis/):**
- `denver-assortment.csv` — 2,586 real US SKUs (name, US size, color, price, category, fixture,
  **image URL**, handle).
- `denver-epcs.csv` — 35,912 EPCs across 140 fixtures.
- `scripts/build_denver.py` — deterministic rebuild (seed=42).

**Seoul outputs (superseded as primary, retained):**
- `analysis/manhattan-assortment-final.csv` (4,954 KR-derived SKUs) + `manhattan-epcs.csv` (39,720).
- `scripts/build_assortment.py`.

---

## Denver — current state + pending decisions

**Have:** real catalog ✅ · real images ✅ · real EANs→EPCs ✅ · fixture placement (planogram) ✅ ·
US sizes/prices/names native ✅. Category mix is real-catalog-driven (outdoor/hike/cycle + apparel +
backpacks + footwear; **no watches, no team sport, minimal swim** — that's the real US assortment).

**Pending decisions:**
1. **Tenant/store identity on mother.** Currently seeded space = `decathlon-manhattan`. Denver wants
   its own tenant/site/space (two stores = two tenants eventually). Rename vs new tenant — TBD.
2. **Seed execution** — ~36k-row production write to mother (item_identifier + thing_location +
   product attribs incl. image). GATED on Bob's go. Schema to be verified first; re-runnable
   clear/replace strategy (seed_store.py pattern).
3. **Depth balance** — apparel is ~68% of pieces (real catalog is apparel-heavy); flatten depth if
   the heatmap should read less apparel-dominated.
4. **LP scenario anchor** — US catalog has no watches (the planned EAS/locked-case anchor). Pick a
   new high-value anchor from the real range (e.g. carbon-plate shoes $350, premium bikes) or carry
   a few synthetic watch SKUs.

---

## Seoul — PARKED (what we have + plan)

**Have:** KR 56k catalog (2021), real Nov peak velocity, a KR-derived 4,954-SKU assortment +
EPCs, US-localized names/sizes (agent pass). EPC encoder works on KR EANs.

**Gap:** images. Only **6% (310/4,954)** overlap the US catalog for free reuse; **94% need live-KR
retrieval.** KR site is Algolia/JS (`prod_pim_v1_index`), not Shopify — harder. And the 2021 catalog
has discontinued items → permanent gaps if patched onto stale data.

**Plan when unparked:** mirror the Denver approach — **re-base Seoul off the LIVE KR catalog**
(decathlon.co.kr via its Algolia index) so products + images + current pricing come together, instead
of patching images onto the 2021 dump. Needs the public Algolia app-id + search key (DevTools grab or
browser automation). Korea EPC encoder + Nov velocity carry over unchanged.

---

## ⭐ Onboarding gaps surfaced — FR prioritization (DISCUSSION)

The twin's whole point is to surface what core needs to onboard a real customer. Building Denver
exercised the onboarding path end-to-end and exposed these — **mapped to FR areas; exact FR numbers
to confirm against `requirements/.../9a. Functional Requirements.md`.** Ordered by onboarding-blocking.

| # | Capability needed to onboard a store | Status today | FR area | Filed? |
|---|---|---|---|---|
| 1 | **Catalog import** — bulk product ingest (name, price, EAN, category, attrs, **images**) from a feed/API | `productCatalogWebhook` (#23) exists; no full API/UI; we used direct Hasura | FR-INTEG / FR-PLAT | `CATALOG-IMPORT-ONBOARDING` (cleanup) |
| 2 | **EPC encoding config** — tenant constructs their RFID scheme (company prefix, filter, partition, serial) | none — built clean-room in twin this session | FR-COLLECT (RFID) / FR-PLAT | **NEW — file** |
| 3 | **Service/machine auth on inventory endpoints** — API-key principals, not JWT-only | `inventoryReceive` (#25) is JWT-only → 401 | FR-PLAT / FR-INTEG | `SERVICE-BEARER-INVENTORY` |
| 4 | **Item/EPC provisioning at fixture granularity** — create tagged stock + place at fixtures | `inventoryReceive` is space-level only; fixture placement (`thing_location`) is seed/admin-only | FR-INV / FR-COLLECT | partial |
| 5 | **Product imagery** — store + serve product images on inventory surfaces | unknown/none in platform; Denver has real image URLs ready to use | FR-INV / FR-PLAT | **NEW — file** |
| 6 | **Store layout / planogram authoring** — define space/zones/fixtures + SKU→fixture | hand-built; no onboarding tool | FR-SPATIAL / FR-PLAT | TBD |
| 7 | **Commerce/sales ingest + projection** — POS sale → commerce_projection | `saleNative`/`saleWebhook` exist; `commerce_projection` writer unfed | FR-INTEG / FR-INV | filed (not built) |

**Discussion seeds:**
- #1 + #2 + #5 are the "**Day-1 onboarding trio**" — a customer can't go live without importing their
  catalog, configuring their tag encoding, and showing product images. The twin just proved all three
  are real and currently manual/missing. Strong candidates to prioritize into core's MVP onboarding.
- #2 (EPC config surface) is the cleanest new FR to write — we have the exact spec from
  `EPC-ENCODING-DECATHLON.md` (the user constructs filter/partition/serial; the rest derives from EAN).
- The Shopify-catalog discovery suggests an onboarding pattern: **import a customer's existing
  e-com catalog (Shopify/feed) directly** — names, prices, images, barcodes all in one pull. Worth
  considering as the canonical catalog-import on-ramp (FR-INTEG).

---

## Gotchas / failures observed (onboarding-UX signal)

- **Store rename Manhattan → Denver threw failures before succeeding** (Bob did it manually on mother).
  Store-identity editing is fragile — another admin/onboarding-UI gap data point for the planning session.
- **EPC scheme: never guess filter/partition** — defaults (filter 1 / part 5) were wrong; real tags
  showed partition 6. Always validate against real tags before generating.
- **Bulk Shopify `products.json` omits barcodes**; per-product `/products/{handle}.json` carries them.
  Rapid sequential curls (>~60) trip a "Verifying your connection" rate-limit → re-pull with delays + retry.
- **`deep-research` workflow verifier bug** — StructuredOutput failure marked all claims "refuted"
  (`0-0 abstain`); data was fine, just mislabeled. Don't trust its summary verdict.
- Denver rides the Manhattan layout: GPS watch cases sit empty (no US watches); large outdoor items
  (bikes/tents) shoehorned into gondola units — real Denver layout deferred by decision.

## Open decisions / next

1. **Denver seed** — naming + go (gated prod write).
2. **FR prioritization** — which onboarding gaps (esp. the Day-1 trio #1/#2/#5) get filed as
   TWIN-REQ briefs and pushed into core's MVP. ← **main discussion topic**.
3. **Seoul** — unpark when ready; re-base off live KR catalog.
4. **Brand rename** — pre-market, later.
