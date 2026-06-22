# Deploy Handoff — Decathlon Chain → M8trxDemo

**For:** backend deploy session. **Date:** 2026-06-11. **Source:** Twin Session 5.
**One-line:** provision the M8trxDemo tenant as a 14-site Decathlon chain with 251 users + full
catalog/inventory, from the files in this folder.

---

## ✅ SEEDED — 2026-06-11 (core deploy session)

Provisioned into **M8trxDemo** (`tenant_id = ecfa6903-5c50-439f-8f80-185982de944e`). Verified at DB + RLS-path (zenven) + UI smoke.

| | landed |
|---|---|
| Sites | 14 (10 retail + 4 office) |
| Users | 251 (250 staff + zenven; 30 inactive=suspended) · zenven = sole tenant-admin |
| Catalog | 2,586 products + 2,586 images |
| Inventory | 277,515 EPCs → thing/item/item_identifier (1:1), `in_stock`, **site-level** |

**Roles:** HQ + regional office → `member`; store mgr/assistant → `site-manager`; floor → `staff`; nobody platform-admin.
**Method:** direct `psql` to `mother:5448` (REST/Hasura write paths were tenant/role-blocked — full notes in CC memory `project_m8trxdemo_seed`). Reversible via tenant-delete; pre-seed backup `mother:/tmp/m8trx_v2_2026-06-11_pre-chain-seed.dump`.

**Carried limitations:** currency displays **USD** (EUR/KRW preserved in `product.display_attributes.prices`); inventory is **site-level only** (no `thing_location`/fixture pins); commerce dashboards blank until `commerce_projection` writer ships (**TWIN-REQ-002**).

**⚠ LAYOUT SUPERSEDED — FULL RE-SEED NEEDED (2026-06-22):** the 2026-06-11 seed (10 spaces from a shared template) had **overlapping fixtures** and is replaced by **10 UNIQUE per-store layouts** (`stores/<id>/layout.json`, parametric, 0 overlaps asserted). Re-provision each store's own spaces/zones/fixtures + re-receive the **277,515 items** at the new fixture-zones via scan/receive (`thing_location` = fixture centroid). Old fixture codes/positions on mother no longer match — full re-provision, not a patch.

---

> Read **[CHAIN-DATA-SPEC.md](CHAIN-DATA-SPEC.md)** for the column-by-column data dictionary, and
> **[IMPORT-MAPPING.md](IMPORT-MAPPING.md)** for how each file maps onto the core import contract.
> All paths below are relative to `~/IdeaProjects/m8trx-twin/reference/data/chain/`.

---

## Totals

| | |
|---|---|
| Sites | **14** (10 retail + 4 office) |
| Users | **251** (250 staff + 1 tenant-admin) · 30 inactive |
| Catalog | 22,944 store-SKU rows (2,586 distinct master SKUs, real images) |
| Inventory | **277,515 EPCs** (scanner-decodable SGTIN-96) |
| Layout | each retail site's OWN unique floor · 11 area zones + N `zone_type='fixture'` (N ~42–78, varies by tier+seed) |
| Spread | 5 IANA timezones (UTC-8 → +9) · 3 currencies (USD/EUR/KRW) |

---

## Deploy order

### 1. Tenant
`M8trxDemo`. Tenant-admin login = **`zenvendemo@gmail.com`** (real Google mailbox → OAuth sign-in).

### 2. Sites (14) — `chain-manifest.json`
Provision sites first; the logical id → real `site_id` binding is the **config map** everything
else joins on.

- **`stores[]`** — 10 **retail** sites. Each: `id`, `address`, `timezone` (IANA), `currency`,
  `locale`, `tier`, `sqm`. These get space/zones/fixtures/inventory.
- **`office_sites[]`** — 4 **office** sites (HQ + US/FR/KR regional). `site_type=office`,
  `has_space=has_inventory=has_sensors=false` — **provision a site row only**, no space/zone/fixture.

### 2b. Spaces / zones / fixtures (retail only) — `stores/<id>/layout.json` (per-store, unique)
Each of the 10 retail sites instantiates **its own unique space** → 11 area zones (incl. 3
try-on, EAS entrance, checkout, stockroom) + **N `zone_type='fixture'` zones** (N varies per store,
~42–78, from that store's `layout.json`). **Fixtures are zones, not a separate table** (core's
`fixture` table is unused — corrections doc §1). Office sites
get no space. The `fixture` codes in `epcs.csv` resolve to the **fixture-zone of the same code**
`(store_id, code)` — so provision spaces **before** inventory (step 5), and place items at the
**fixture-zone** via scan/receive (step 5).

| Site | Type | City | TZ | Cur |
|---|---|---|---|---|
| dec-us-denver / -nyc / -sf | retail | Denver / New York / San Francisco | MT / ET / PT | USD |
| dec-fr-paris / -lyon / -lille / -marseille / -bordeaux | retail | (France) | Europe/Paris | EUR |
| dec-kr-seoul / -busan | retail | Seoul / Busan | Asia/Seoul | KRW |
| dec-hq-global | office | Villeneuve-d'Ascq, FR | Europe/Paris | — |
| dec-us-region | office | New York, NY | America/New_York | — |
| dec-fr-region | office | Paris, FR | Europe/Paris | — |
| dec-kr-region | office | Seoul, KR | Asia/Seoul | — |

### 3. Users (251) — `staff/users.csv`  ← the file under active test
Columns: `email, display_name, name_local, role, role_label, site, region, timezone, status,
status_reason, tenant_admin`.
- 250 staff are **site-bound** (`site` = a retail or office id); only the tenant-admin is blank/tenant-scoped.
- **30 inactive** accounts (varied `status_reason`: terminated / resigned / on_long_leave /
  deactivated / account_locked) — deliberately included to exercise inactive/orphaned states.
- `role` is a **descriptive key for you to map → perms-v3 capabilities** (twin emits no capabilities).
- Reporting hierarchy (optional) lives in `staff/roster.csv` (`manager_id`) + `staff/org-chart.json`.

### 4. Catalog — `stores/<id>/assortment.csv` (×10)
Per-store SKU list: `ean`, `item_cd` (sku), localized `name_local` + `price_local`/`currency`/`locale`,
`fixture` code, real Shopify `image` URL. Master FR/KO name map: `localization/name-localization.csv`.

### 5. Inventory — `stores/<id>/epcs.csv` (×10)
One row per physical unit: `epc` (SGTIN-96), `ean`, `item_cd`, `category`, `fixture`, `store_id`.
**Load as raw scan/receive → let the platform derive `thing_location`** (per import contract §2).
Direct-DB only for historical backfill, and only then owning FK pre-resolution + past timestamps +
projection backfill + no audit.

---

## Still on backend (blocks a *full* seed — see IMPORT-MAPPING.md)

| # | Gap | Affects | Tracked as |
|---|---|---|---|
| 1 | **User/role/org provisioning format** | step 3 (users) — the live one | request |
| 2 | Site + **space/zone/fixture** provisioning (incl. `site_type=office`) | steps 2 / 2b | request |
| 3 | Catalog import **with images** + per-region price/currency/locale | step 4 | `CATALOG-IMPORT-ONBOARDING` |
| 4 | Service bearer on inventory endpoints | step 5 (API receive/scan path) | `SERVICE-BEARER-INVENTORY` |
| 5 | `commerce_projection` writer | commerce dashboards | **TWIN-REQ-002** (filed) |

---

## Notes
- **Disposable + deterministic.** Regenerate byte-identical any time:
  `python3 scripts/build_layout.py && python3 scripts/localize_names.py && python3 scripts/build_chain.py && python3 scripts/build_staff_roster.py`.
- EPC scheme reference: `reference/data/EPC-ENCODING-DECATHLON.md` (validated against 169k real tags).
- Demo data — "realistic, not accurate": addresses/prices/headcounts/names are plausible, not audited.
- One open twin-side item at seed time: build the config-map binding logical ids
  (`dec-us-denver`, `GF-R1-U2`) → provisioned UUIDs once sites exist.
