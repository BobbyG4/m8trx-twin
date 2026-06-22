# Chain Dataset ↔ Core Import Contract — Reconciliation

**Status:** v2, 2026-06-22 (Twin Session 7). Twin-side companion to `CHAIN-DATA-SPEC.md`.
**Maps:** `reference/data/chain/*` → core's **`~/IdeaProjects/m8trx-shared/twin/insights/IMPORT-CONTRACT.md`**
(backend-authored, code-derived, *not yet smoke-tested* — confirm payloads against the running API
before locking any serializer).

---

## Framing: two different grains

Our chain dataset is **static provisioning + opening-state** data (sites, catalog, opening
inventory, org/staff). The import contract is mostly about **runtime event-grain ingestion**
(scans, sales, person-sessions, fitting-room, EAS). They overlap in exactly two places:

1. **§1 config-FK anchor** — `site_id / space_id / zone_id / fixture_id / sensor_id` are acquired
   *once at provisioning* and held in a config map. Our dataset is the **input** to that provisioning.
2. **§2 grain rule** — opening inventory is expressed as **raw scan/receive events**, and the
   platform *derives* `thing_location`. We must **not** write Layer-2 projections (or `thing_location`)
   directly.

The contract confirms our keying choices and tells us how opening stock (and later events) should land.

---

## Conformance check (contract demands vs what we built)

| Contract rule | Our dataset | Verdict |
|---|---|---|
| Key on **stable business identifiers**, not surrogate UUIDs (§ core decision) | keys on `ean`, `item_cd`/sku, `epc`, `store_id`, fixture codes, `staff_id`/`email` — no invented UUIDs | ✅ aligned |
| Hold **config FKs** acquired at provisioning (§1) | we carry **logical** keys (`store_id=dec-us-denver`, `fixture=GF-R1-U2`) — real UUIDs don't exist until 10 sites are provisioned on M8trxDemo | ⚠ need a one-time **config-map** (logical → provisioned UUID) |
| **Feed raw, don't write projections** (§2) | dataset is catalog + items + org only — zero projections | ✅ aligned |
| **Partition timestamp mandatory** on DB path (§3) | opening-state has no event time yet; when rendered to receive/scan events we stamp `recorded_at` (PAST timestamps for historical backfill) | ✅ handled at render time |

We are well-positioned: the dataset is already business-keyed and projection-free, which is exactly
the envelope shape the contract recommends.

---

## Per-artifact mapping

| Chain artifact | What it is | Contract treatment | Import path | Status |
|---|---|---|---|---|
| `chain-manifest.json → stores[]` (10 retail) | site/space provisioning input | §1 config-FK anchor — provisioned via GraphQL/REST | provision sites → config map | ⚠ **PARTIAL** — provisioning *format* not in this contract (assumes existing onboarding) |
| `chain-manifest.json → office_sites[]` (4 office) | HQ + regional site rows, `site_type=office` | §1 anchor but **site_id only** — no `space_id/zone_id/fixture_id` (no space/inventory/sensors) | provision site row → config map | ⚠ **PARTIAL** — needs a `site_type=office` provisioning path |
| `stores/<id>/layout.json` (per-store UNIQUE floor: 12–17 area zones + N `zone_type='fixture'`, N ~39–115+; area zones include department bands `D-0N` + BOH `Z-05` with `backroom_rack`/`receiving_dock` fixtures) | space + zone provisioning (retail only) | §1 config FKs `space_id`, `zone_id` (fixtures ARE zones — no `fixture_id`) | instantiate each store's own space + its zones → config map | ✅ **COVERED (data)** — matches core's zone model; provisioning *format* still core's |
| `stores/*/assortment.csv` (SKUs) | catalog/product master (incl. `brand`, `classification_key`, `department`) | natural key `sku` → `item.id`; `brand`→`product.brand`; `classification_key`→`product.classification_id`; `department` — sport-universe band for planogram placement | `POST /api/v2/inventory/skus/bulk` (atom #26) + attribs/images | ⚠ **PARTIAL** — bulk SKU exists; **images** are the open `CATALOG-IMPORT-ONBOARDING` gap |
| `classification.csv` (5 roots + 90 leaves) | product taxonomy + `attributes_schema` | natural key `classification_key` → `product_classification` (`attributes_schema` JSON) | core loader (CORE-REQ-001) | 📦 **DELIVERED** (CORE-REQ-001 §2) — awaiting core re-seed |
| `display_lookup.csv` (405 rows) | raw→display attribute coding (colour) | `(attribute_name, raw_value, locale)` → `display_lookup` (`visual` JSON) | core loader (CORE-REQ-001) | 📦 **DELIVERED** (CORE-REQ-001 §3) — awaiting core re-seed |
| `stores/*/epcs.csv` (102,675 units; ~18% on `backroom_rack` fixtures in Z-05) | opening inventory | §2/§5 "inventory position **derived from scans**"; EPC→`item.id` via EpcResolver | raw `POST /api/v2/scans` (#14) or `inventoryReceive` (#25) → `thing_location` derived | ✅ **COVERED** mechanism — gated on `SERVICE-BEARER-INVENTORY` for the API path |
| `staff/roster.csv` + `org-chart.json` | user / org provisioning | **not addressed** (contract is operational-pulse scope) | — | ❌ **OPEN** — needs a user/role/org provisioning contract |

---

## What the contract settles for us

1. **Opening inventory loads as raw scan/receive events**, not direct `thing_location`/projection
   writes. Each `epcs.csv` row → a receive/scan keyed on `(epc, zone_id/fixture_id)`; the platform
   derives current location. (Direct-DB only for historical backfill — §4 escape hatch.)
2. **Do not write `commerce_projection` ourselves.** That's the §2 anti-pattern, and it's now filed
   as **TWIN-REQ-002** (`commerce_projection` writer, FILED 2026-06-11). Until core ships the writer,
   commerce dashboards stay blank on the API path — we accept that rather than desync the projection.
3. **Seeding-path rule:** live/interactive scenarios → **API path** (NATS lights the live surfaces).
   Historical pre-aging → **direct-DB backfill is defensible**, but then twin owns all four bypassed
   jobs: pre-resolve FKs, stamp **past** partition timestamps, backfill Layer-2 projections, no audit.

---

## Still open before a full chain seed (needs backend)

| # | Gap | Blocks | Tracked as |
|---|---|---|---|
| 1 | **Org/site/store + regional-node provisioning format** (how `dec-xx-region` + sites/spaces/zones/fixtures are created and yield the config map) | binding our logical keys to UUIDs | — (request; contract assumes it exists) |
| 2 | **Catalog import incl. images** + per-region price/currency/locale | product imagery on inventory surfaces | `CATALOG-IMPORT-ONBOARDING` |
| 3 | **User/role/org + staff provisioning** (the `roster.csv`) | seeding the 250-person org | — (not covered by event-grain contract) |
| 4 | **Service bearer on inventory endpoints** | the API receive/scan path for opening stock | `SERVICE-BEARER-INVENTORY` |
| 5 | **`commerce_projection` writer** | commerce story rendering | **TWIN-REQ-002** (FILED) |

---

## Build implied (at seed time, not now)

A single **business-key envelope** + a thin **resolver** with two renderers (API / DB), plus a
one-time **config-map** binding logical store/fixture keys → provisioned UUIDs. The resolver does
in-process what the API does server-side (EPC→item, dedup, status transitions). Per the contract's
own caveat, **verify every payload against the live API before locking serializers.**
