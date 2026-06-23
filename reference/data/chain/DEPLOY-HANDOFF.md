# Deploy Handoff — Decathlon Chain → M8trxDemo

**For:** backend deploy session. **Source:** Twin. **Latest:** RESEED 2026-06-22 (Session 7).
**One-line:** M8trxDemo is a 14-site Decathlon chain. It was first seeded 2026-06-11; this doc's
**RESEED** section is the authoritative current instruction (per-store departmentalized layouts,
realistic size-curve inventory, lean back-of-house, site coordinates).

---

## ✅ SEEDED — 2026-06-11 (history)

Provisioned into **M8trxDemo** (`tenant_id = ecfa6903-5c50-439f-8f80-185982de944e`). Verified at DB + RLS-path (zenven) + UI smoke.

| | landed |
|---|---|
| Sites | 14 (10 retail + 4 office) |
| Users | 251 (250 staff + zenven; 30 inactive=suspended) · zenven = sole tenant-admin |
| Catalog | 2,586 products + 2,586 images |
| Inventory | 277,515 EPCs → thing/item/item_identifier (1:1), `in_stock`, **site-level** |

**Method:** direct `psql` to `mother:5448` (REST/Hasura write paths were tenant/role-blocked — notes in CC memory `project_m8trxdemo_seed`). Reversible via tenant-delete; pre-seed backup `mother:/tmp/m8trx_v2_2026-06-11_pre-chain-seed.dump`.

**Superseded by the RESEED below.** The 2026-06-11 inventory (277,515 EPCs, flat per-size depth, site-level, shared overlapping layout) is fully replaced.

---

## ⚠ RESEED — 2026-06-22 (AUTHORITATIVE — execute this)

> Read **[CHAIN-DATA-SPEC.md](CHAIN-DATA-SPEC.md)** for the column-by-column dictionary, **[SEED-PLAYBOOK.md](SEED-PLAYBOOK.md)** for the 5 data-modeling corrections (read before importing), and **[IMPORT-MAPPING.md](IMPORT-MAPPING.md)** for the import-contract mapping. Paths are relative to `~/IdeaProjects/m8trx-twin/reference/data/chain/`.

### What changed since the live seed

| Layer | On mother now (2026-06-11) | This reseed | Action |
|---|---|---|---|
| **Tenant / Users** | M8trxDemo · 251 users | unchanged | **keep** |
| **Sites** | 14, no coordinates, no functional-role column | +`latitude`/`longitude` + **`site_category`** (`store`×10 / `office`×4 — CORE-REQ-002) on all 14 | **UPDATE 14 rows** |
| **Spaces / zones** | 10 shared-template, overlapping fixtures, single "Main Floor" | 10 **unique** floors, **sport-universe department bands**, real **back-of-house** (dock + racks), mother-canonical geometry | **drop + recreate** |
| **Catalog** | 2,586 products, USD, no coding | +`brand` +`classification_key` +`department` + `classification.csv` + `display_lookup.csv` (CORE-REQ-001) | **enrich** (not re-create) |
| **Inventory** | 277,515 EPCs, flat per-size depth, **site-level** | **102,675 EPCs**, realistic **size curves**, placed at **department + BOH fixture-zones**; EPC strings all changed | **re-import** |

### Reseed mechanism — in-place, no tenant-delete

Tenant, the 251 users, and the 14 site rows **stay**. Three operations:

1. **Sites** — `UPDATE site SET latitude=…, longitude=…, site_category=…` for all 14 (values in `chain-manifest.json`: `stores[]`→`store`, `office_sites[]`→`office`). **`site_category`** (CORE-REQ-002, core **mig 146** — apply with backup) sets the functional role authoritatively, so core stops inferring it from space-presence. Ownership `site_type` stays `managed` (untouched). Nothing else on the site row changes.
2. **Spaces** — **drop** each retail site's existing space (cascade its zones/fixtures/scan_events/thing_locations) and **recreate** from `stores/<id>/layout.json`. Office sites still get no space.
3. **Inventory** — the 277,515 old items no longer match (EPC strings changed with the new depths). **Delete the old items and re-import** the 102,675 from `stores/<id>/epcs.csv`, placed at their fixture-zone via scan/receive.

> Why re-import not re-locate: fixing the size-curve realism regenerated every serial, so EPC strings differ from 2026-06-11. The item set is genuinely new.

### Totals (this reseed)

| | |
|---|---|
| Sites | **14** (10 retail + 4 office) · all with lat/long |
| Users | **251** (unchanged) |
| Catalog | **2,586** products (tenant-scoped) + coding layer · USD display, EUR/KRW in `display_attributes.prices` |
| Inventory | **102,675 EPCs** · ~18.4k (18%) staged in back-of-house, ~84.3k on the floor |
| Per store | flagship ~14.6–15k · large ~9.5–9.8k · medium ~4.7–5.0k EPCs (exact in manifest) |
| Layout | per store: 10 base area zones + **2–7 department bands** + **N fixture-zones** + BOH (1 dock + 4–8 racks). 0 overlaps asserted. |
| Spread | 5 IANA timezones (UTC-8 → +9) · 3 currencies (USD/EUR/KRW) |

### Deploy order

**1. Tenant** — `M8trxDemo`, exists. Admin login `zenvendemo@gmail.com` (real Google mailbox → OAuth).

**2. Sites (14)** — `chain-manifest.json`. Rows exist; **UPDATE `latitude`/`longitude`** on all 14 from the manifest (`stores[]` + `office_sites[]`). The logical id → real `site_id` config-map is what everything else joins on.

**2b. Spaces / zones / fixtures (retail only)** — `stores/<id>/layout.json`. Drop + recreate per store. Each floor =
- **Area zones**: `entry_exit` (EAS entrance), `checkout`, 3 `try_on_zone`, `Stockroom` (BOH, `customer_accessible=false`), service/specialty `region`s, **+ 2–7 sport-universe `region` department bands** (e.g. "Hiking, Trekking & Camping", "Running & Trail", "Cycling" — `name`, `properties`, and a `department` key).
- **Fixture-zones** (`zone_type='fixture'`): gondolas, perimeter bays, specialty, checkout, circular feature displays, **+ BOH** `receiving_dock` + `backroom_rack`. **Fixtures ARE zones, not the `fixture` table** (corrections §1). Each floor fixture carries `in_area_zone` (its department band) + `department`.
- Geometry: **mother-canonical** — circle = center `POINT Z` + `properties{centerX,centerY,radiusX,radiusY,rotation}`; polygon = `POLYGON Z` ring; SRID 0, mm, Z=0.

**3. Users (251)** — `staff/users.csv`. **Already seeded; unchanged** — no action unless re-provisioning from scratch.

**4. Catalog** — `stores/<id>/assortment.csv` (×10). **Enrich** the existing 2,586 products:
- New columns per SKU: `brand` (authoritative, ← Shopify vendor), `classification_key`, `department`.
- Load the chain-level coding tables into the CORE-REQ-001 SurfaceProfile: **`classification.csv`** (5 roots + 90 leaves, `attributes_schema`, `lifecycle_type`) and **`display_lookup.csv`** (colour raw→canonical family ×3 locales + swatch).
- Per-region prices in `display_attributes.prices`; single-currency (USD) display until core adds per-region pricing (corrections §3). Images = real Shopify `image` URLs (image-pipeline gap below).

**5. Inventory** — `stores/<id>/epcs.csv` (×10). One row per unit: `epc` (SGTIN-96), `ean`, `item_cd`, `category`, `fixture`, `store_id`. The `fixture` code resolves to the **fixture-zone of the same `(store_id, code)`** — floor OR `backroom_rack`. **Place via raw scan/receive → let the platform derive `thing_location`** (corrections §2). This finally lifts inventory from site-level to fixture-level (incl. backroom stock).

### Item-placement path

Prefer the **API receive/scan** path (one step → `scan_event` + derived `thing_location` + audit). It is still blocked by the service-bearer gap (#4 below). Until that lands, use the **direct-DB escape hatch but write BOTH `thing_location` AND `scan_event` at the fixture-zone** — the 2026-06-11 round wrote `thing_location` only and the UI (which reads `scan_event`) stayed blank, forcing a fix-up.

### Acceptance checks (run post-reseed)

- [ ] 14 sites have non-null `latitude`/`longitude` (geo map plots all 14) **and non-null `site_category`** — the 10 retail = `store`, the 4 offices = `office`.
- [ ] Each retail space has its department bands + `Stockroom` with `receiving_dock`/`backroom_rack`; canvas renders fixtures (0 overlaps; circular fixtures render round, not bounding boxes).
- [ ] Every `epcs.csv` `fixture` resolves to a fixture-zone in that store's space (twin asserts 0 orphans pre-handoff).
- [ ] Item count on mother = **102,675**; ~18% sit at `backroom_rack` zones (back-of-house stock visible).
- [ ] `GetItemsAtLocation` returns a realistic **size curve** per shoe style (modal sizes deeper, tails thin) — not a flat pile of one size.
- [ ] `brand` / `classification_key` / `department` populated; Discover/Things surface shows coded attributes + colour families.

### Still on backend (gaps)

| # | Gap | Affects | Tracked as |
|---|---|---|---|
| 1 | User/role/org provisioning format | step 3 (only if re-provisioning) | request |
| 2 | Site + space/zone/fixture provisioning (incl. `managed`-no-space office) | steps 2 / 2b | request |
| 3 | Catalog import **with images** + per-region price/currency/locale | step 4 | `CATALOG-IMPORT-ONBOARDING` |
| 4 | Service bearer on inventory endpoints | step 5 (API receive/scan path) | `SERVICE-BEARER-INVENTORY` |
| 5 | `commerce_projection` writer | commerce dashboards | **TWIN-REQ-002** (filed) |

---

## Notes

- **Disposable + deterministic.** Regenerate byte-identical any time:
  `python3 scripts/build_layout.py && python3 scripts/localize_names.py && python3 scripts/build_chain.py && python3 scripts/build_staff_roster.py && python3 scripts/render_floorplans.py`.
- **Inventory density is a knob** — `TIER_SCALE` in `build_chain.py` (currently ~2×, ≈102k EPCs). Dial down for leaner; size curves hold at any scale.
- EPC scheme: `reference/data/EPC-ENCODING-DECATHLON.md` (validated against 169k real tags).
- Floor-plan SVGs (visual QA): `reference/data/floor-plans/<id>.svg` + `_comparison.svg` (departments tinted, BOH shaded).
- Demo data — "realistic, not accurate": addresses/coords/prices/headcounts/depths are plausible, not audited.
- One open twin-side item: build the config-map binding logical ids (`dec-us-denver`, `GF-R1-U2`) → provisioned UUIDs once sites/spaces exist.
