# Chain Seed Playbook — M8trxDemo multi-store exercise

**What this is:** the repeatable recipe + hard-won corrections for standing up M8trxDemo as a
multi-store retail chain (sites · spaces · zones · fixtures · catalog · inventory · users/org).
Read the **"Data-modeling rules"** section *before* generating — each rule is a mistake that cost a
rework round the first time (Session 5, 2026-06-11).

**Sources digested into this playbook:**
- Backend corrections: `~/IdeaProjects/m8trx-shared/twin/insights/2026-06-11-seed-exercise-corrections.md` (commit `019f789`)
- Import contract: `~/IdeaProjects/m8trx-shared/twin/insights/IMPORT-CONTRACT.md`
- This exercise's artifacts: `DEPLOY-HANDOFF.md` · `CHAIN-DATA-SPEC.md` · `IMPORT-MAPPING.md` (same folder)

---

## The exercise at a glance

Produce a deterministic, disposable dataset under `reference/data/chain/`, hand it to backend, they
seed M8trxDemo (reversible via tenant-delete). Last run: **14 sites** (10 retail + 4 office) · **251
users** (250 staff + tenant-admin, 30 inactive) · **2,586 products** · **277,515 EPCs** · 11 zones +
149 fixtures shared layout · 5 timezones · 3 currencies.

### Pipeline (deterministic — byte-identical every run)
```bash
python3 scripts/build_layout.py        # space template: zones + fixtures (from STORE-LAYOUT.md)
python3 scripts/localize_names.py      # EN->FR/KO product-name map
python3 scripts/build_chain.py         # manifest + per-store assortment/EPCs (reads the two above)
python3 scripts/build_staff_roster.py  # roster + org-chart + users.csv
```
Config is one file: `scripts/chain_config.py` (stores, regions, tiers, office sites, org template,
name pools, TENANT). Per-store seed = `sha256(store_id)` (NOT Python `hash()` — salted per process).

---

## Data-modeling rules (corrected — match core's model, not our intuition)

### 1. Fixtures ARE zones (`zone_type='fixture'`) — not a separate table  ⚠ biggest one
Core's space canvas renders fixtures as **`zone` rows with `zone_type='fixture'`**, children of the
space alongside area/checkout/try_on zones. **The `fixture` table is unused (0 rows in every working
space).** A floor = area-zones + fixture-zones (e.g. **11 + 149 = 160 zones**). Importing fixtures
into the `fixture` table → **canvas renders nothing** (had to be redone last time).
- **Generate accordingly:** present the 149 fixtures as `zone_type='fixture'` zones — either one
  unified `zones[]` carrying `zone_type`, or keep `fixtures[]` but state at the top that *each becomes
  a `zone_type='fixture'` zone at import* (parent = its `zone_code`).
- Geometry was correct, keep it: **mm, SW origin (0,0), rectangles → `POLYGON Z` SRID 0.**
- ✅ **DONE** (Session 5, 2026-06-11): `build_layout.py` now emits a unified `zones[]` of **160** =
  11 area + 149 `zone_type='fixture'` (each with `in_area_zone` + `fixture_category`).
  `space-template.json` is canvas-ready — no fix-up next import. The `epcs.csv` `fixture` code →
  the fixture-zone of the same `code`.

### 2. Inventory location is read from `scan_event`, at the fixture-zone — feed RAW
Core's inventory surface (`GetItemsAtLocation`) derives location from **`scan_event.zone_id`**, NOT
`thing_location`. Stock only "appears" once a **scan/receive event** exists, placed at the
**fixture-zone** (not the area zone). Each `epcs.csv` row already carries its `fixture` code → resolve
to the fixture-zone → emit a receive/scan there (populates `scan_event`; `thing_location` derives).
Writing `thing_location` alone leaves the UI blank.
- This is the IMPORT-CONTRACT §2 rule ("feed raw, let platform derive") — it was right; honor it.
- Core gap (filed in CLEANUP-TASKS): **no cold-start / manual-stocktake path** to set location
  without a scan. Until it lands, placement = scan/receive events.

### 3. Catalog is tenant-scoped & single-currency
`product` is **tenant-scoped, one row per SKU** → our 22,944 store-SKU rows **dedupe to 2,586 distinct
products**. Per-store stocking is expressed by **inventory** (items at sites/fixtures), *not* duplicate
product rows. Per-region currency (EUR/KRW) **cannot display** on a shared tenant catalog without core
DDL — keep per-region prices in `product.display_attributes.prices`, but expect **single-currency
(USD) display** until core adds per-region pricing.

### 4. Sites & roles map to fixed core enums
- **Site type:** core `site.site_type` is **`managed | external`** — there is **no `office`**. An
  office = a **`managed` site with no space**. Our `has_space/has_inventory/has_sensors` flags have no
  core columns; they're conveyed simply by **not creating a space**. (HQ + regional offices: managed
  site, no space, no inventory — exactly our office-site invariant.)
- **Roles → 6 core Profiles:** HQ + regional-office → `member`; `store_manager`/`assistant_manager`
  → `site-manager`; floor (associate/cashier/dept_leader/stock/LP) → `staff`; **nobody
  platform-admin**. We emit role *keys*; core maps. (Job-specific PSets exist, unused.)

### 5. Geometry — already correct
mm units · SW origin · SRID 0 · rectangles → `POLYGON Z`. No change needed.

---

## Import principles (from IMPORT-CONTRACT.md)
- **Key on stable business identifiers**, not surrogate UUIDs (ean/sku/epc/store_id/fixture_code/email).
  We already do this. ✓
- **Feed RAW observations; let the platform DERIVE.** Never write Layer-2 projections
  (`commerce_/behavioral_/traffic_projection`) — they desync from raw events. Commerce dashboards stay
  blank until core ships the writer (**TWIN-REQ-002**, filed). Don't plug it from twin.
- **Config-FK anchor:** `site_id / space_id / zone_id / fixture_id` are acquired **once at
  provisioning**; everything else joins by natural key. Build a config-map (logical id → UUID) at seed.
- **Path choice:** live/interactive demos → **API path** (NATS lights live surfaces). Historical
  pre-aging → direct-DB backfill is the escape hatch, and then twin owns FK pre-resolution + **past**
  partition timestamps + projection backfill + no audit.

---

## Deploy path (recommended)
**Prefer the API receive/scan path** for inventory — one step populates `scan_event` + derives
`thing_location` + audits. The direct-DB shortcut (used last round for bulk speed) **missed
`scan_event`** — the layer the UI reads — forcing a fix-up. If direct-DB is used for scale, write
**BOTH `thing_location` AND `scan_event` at the fixture-zone.**

---

## Pre-flight checklist (run before handing off)
- [ ] Fixtures expressed as `zone_type='fixture'` zones (rule 1) — area + fixture zone count stated (e.g. 160).
- [ ] Inventory delivered as scan/receive events at the **fixture-zone**, or epcs carry fixture codes + note "emit scan to place" (rule 2).
- [ ] Catalog deduped to **one product row per SKU** (tenant-scoped); per-region prices in `display_attributes.prices`; single-currency display expected (rule 3).
- [ ] Office sites = managed-with-no-space; **invariant asserted** (build fails if an office gets a space/inventory) (rule 4).
- [ ] Role keys present for core→Profile mapping; nobody platform-admin (rule 4).
- [ ] Determinism: per-store seed = `sha256(id)`; re-run twice → identical md5.
- [ ] Tenant-admin = the real OAuth mailbox (`zenvendemo@gmail.com`); all other emails unique.
- [ ] No Layer-2 projections in the dataset.

## Gotchas that cost rework last time
1. Split `zones[]`/`fixtures[]` → fixtures must be zones. **(rule 1 — the expensive one)**
2. `thing_location`-only write → UI blank; needs `scan_event`. **(rule 2)**
3. Assumed `site_type='office'` → only `managed|external` exist. **(rule 4)**
4. Expected per-region currency display → single-currency without core DDL. **(rule 3)**
5. `hash(store_id)` seed → non-deterministic across runs; use `sha256`. (fixed in `build_chain.py`)
6. Email dedup keyed on wrong field → silent duplicate emails. (fixed in `build_staff_roster.py`)

---

## Open core gaps (carry forward / track)
| Gap | Tracked as |
|---|---|
| `commerce_projection` writer (don't write projections from twin) | **TWIN-REQ-002** (filed) |
| Catalog import incl. images + per-region pricing/currency display | `CATALOG-IMPORT-ONBOARDING` |
| Service bearer on inventory endpoints (for API receive/scan path) | `SERVICE-BEARER-INVENTORY` |
| Cold-start/manual-stocktake location (set location without a scan) | core CLEANUP-TASKS |
| User/role/org + site/space provisioning *format* (we have the data) | request |

---

## Pointers
- **Next chapter (the dynamic layer):** `ACTIVITY-PLAN.md` — customers/staff/item movement + analytics
- Repro / surface findings: `m8trx-shared/status/active/m8demo-seed/M8TRXDEMO-SEED-SURFACE-FINDINGS-2026-06-11.md`
- Last seed result: `DEPLOY-HANDOFF.md` § "SEEDED — 2026-06-11" (tenant_id, method, limitations)
- Data dictionary: `CHAIN-DATA-SPEC.md` · Contract mapping: `IMPORT-MAPPING.md`
