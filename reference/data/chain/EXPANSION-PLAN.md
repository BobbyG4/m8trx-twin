# Wave-2 Expansion — varied-layout international stores

**Decision (Session 5):** leave **Wave 1** (the 10 shared-layout stores) **as-is**, and grow the
chain by **adding 10 new international stores with realistic, varied layouts**, onboarded the way a
real customer would. New stores (not retrofit) keep the seeded baseline clean and serve triple duty
(variety · playbook validation both sides · onboarding dogfood).

---

## Wave 2 — the 10 new stores

Different **store formats** applied per store (esp. across the 3 China stores). Big timezone +
currency spread — including India's **UTC+5:30** half-hour offset, a good edge-case test.

| # | Store id | Country | City | IANA TZ | Cur | Locale | Format |
|---|---|---|---|---|---|---|---|
| 1 | `dec-de-berlin` | Germany | Berlin | Europe/Berlin | EUR | de-DE | Standard |
| 2 | `dec-it-milan` | Italy | Milan | Europe/Rome | EUR | it-IT | City |
| 3 | `dec-es-madrid` | Spain | Madrid | Europe/Madrid | EUR | es-ES | Standard |
| 4 | `dec-cn-hk` | Hong Kong SAR | Hong Kong | Asia/Hong_Kong | HKD | zh-HK | **Mall-inline** |
| 5 | `dec-cn-beijing` | China | Beijing | Asia/Shanghai | CNY | zh-CN | **Standard** |
| 6 | `dec-cn-shanghai` | China | Shanghai | Asia/Shanghai | CNY | zh-CN | **Big-box** |
| 7 | `dec-sg-singapore` | Singapore | Singapore | Asia/Singapore | SGD | en-SG | Big-box (Lab flagship) |
| 8 | `dec-in-bangalore` | India | Bangalore | Asia/Kolkata | INR | en-IN | Big-box |
| 9 | `dec-mx-cdmx` | Mexico | Mexico City | America/Mexico_City | MXN | es-MX | Standard |
| 10 | `dec-br-saopaulo` | Brazil | São Paulo | America/Sao_Paulo | BRL | pt-BR | Standard |

Adds **9 IANA timezones** (Berlin/Rome/Madrid are distinct CET zones — good IANA test) and **7 new
currencies** (EUR, HKD, CNY, SGD, INR, MXN, BRL) on top of Wave 1's USD/KRW.

> *Confirmed: one Mexico store (10 total).*

---

## Why new stores (the triple win)
1. **Realistic variety** — stores differ in size, fixture count, and format → the chain looks real,
   and per-store traffic/sales density differ → richer analytics.
2. **Playbook validation, both sides** — Wave 2 is the **regression test** that the Session-5
   corrections "took": twin regenerates clean (`SEED-PLAYBOOK.md`) *and* backend imports with **zero
   rework**. A clean Wave-2 import proves both playbooks.
3. **Onboarding dogfood** — added through the **real onboarding flow**, capturing friction → revives
   `status/active/ONBOARDING-BASELINE-HANDOFF-2026-06-03.md`.

---

## Layout variation — Tier A (parametric) + light Tier B (archetypes)

Deterministic, visibly-distinct stores without a full CAD engine.

**Parametric (`build_layout.py` generates from footprint/format):** gondola rows = f(depth);
units/row = f(width); perimeter bays = f(wall); fitting stalls = f(format); gait/GPS specialty zones
only above a size threshold. Output = same `space-template.json` shape (unified `zones[]`,
`zone_type='fixture'`), different **counts + coordinates** per store.

**Format archetypes:**
| Format | Footprint | Used by |
|---|---|---|
| Mall-inline | narrow | HK (dense urban) — the format `STORE-LAYOUT.md` was originally authored as |
| City | ~600 sqm | Milan; the Wave-1 grammar |
| Standard | ~1,000–1,500 sqm | Berlin, Madrid, Beijing, CDMX, São Paulo |
| Big-box | ~2,000+ sqm | Shanghai, Singapore (Lab), Bangalore |

**The cascade fix (don't miss this):** varying layouts is **not** just a layout-file change.
`build_chain.py` uses one hardcoded `PLAN` today — make it **layout-driven**: read each store's own
fixture-zones (by `fixture_category`) and place inventory onto *that store's* fixtures. EPC counts
then scale with the store. This is the bulk of the work; the layout generator is the easy half.

---

## Catalog strategy — toward the full tenant catalog

Shift from the curated 2,586-SKU shared subset to a **growing tenant-level master** (per corrections
§3, `product` is tenant-scoped, one row/SKU — a big tenant catalog is exactly right; stores stock
**localized subsets** by format/region).

**Per-region source mapping:**
| Stores | Catalog source | Images |
|---|---|---|
| China ×3 (HK/BJ/SH) | **Korea catalog** (similar market — `manhattan-assortment-final.csv`, `name_kr`; 56k KR raw) | ⚠ same gap as Seoul — KR/CN images **parked** (need Algolia/CN feed) |
| DE, IT, ES, SG, IN, MX, BR | **US Shopify master** (products + real images), localized | from US Shopify (see image pipeline) |

- **Localization:** add the 7 new currencies + locales; names via `localize_names.py` extended to
  DE/IT/ES/ZH/PT (English fallback until translated).
- **Later (deferred):** move the catalog up to **organization** level and **split tenants & regions**.
  Keep one tenant building one comprehensive dataset first.

### ⚠ Image pipeline — open risk (resolve before relying on images)
Today `assortment.csv` `image` = a **Shopify CDN hot-link**. **The backend seed hit an issue here —
it is NOT yet confirmed the link approach works** (external URLs may not be reliably accessible /
served by the platform: expiry, rate-limits, blocking, fetch-at-render). Likely fix: **don't
hot-link — cache the bytes.**
- **Twin side:** a build step that **downloads** the Shopify images into a controlled local cache
  (dedup by image, manifest `ean → cached file`), so the deliverable ships the actual bytes.
- **Core side:** ingest the cached files into M8TRX's **own asset store** and serve from M8TRX URLs
  (part of `CATALOG-IMPORT-ONBOARDING`).
- **Action:** confirm with backend *what the seed actually did with the 2,586 image URLs* and whether
  core can store/serve cached assets — before scaling images across Wave 2.

---

## Onboarding — UI first, then API (Connect)

Going forward, each new store is onboarded in **two passes**, exercising both front doors:
1. **UI first** — the truest dogfood; capture every friction point in the real onboarding screens.
2. **API we simulate** — drive the **M8TRX Connect** path (the simulator in `ACTIVITY-PLAN.md`) to
   work through the **entire Connect chain** (integration provisioning → webhook/HMAC feeds →
   integration-health). This proves the same onboarding works machine-to-machine.

---

## Build order
1. **Resolve the image pipeline with backend** (link vs cached bytes) — gates how images scale.
2. Parametric `build_layout.py` (footprint/format → zones + fixture-zones).
3. Layout-driven `build_chain.py` (planogram derived from each store's fixtures).
4. Catalog: China ← KR catalog, rest ← US Shopify; new-region localization (currencies/locales/names);
   image caching step if confirmed needed.
5. Define Wave-2 set in `chain_config.py`; regenerate → run playbook pre-flight.
6. Onboard Wave 2: **UI pass** (capture friction) → **API/Connect pass** (full chain).
7. Confirm zero-rework import = playbook proven both sides.

---

## Wave 3 (later — preview)
Beyond physical stores: **online channel** (e-commerce) + **distribution centers**, including a
**3PL DC**. Extends the chain to supply-chain nodes (inbound shipments, DC→store transfers, online
orders/fulfillment) — new site types + integration flows. Plan when Wave 2 lands.

## Pointers
- Gap origin: `CHAIN-DATA-SPEC.md` Known-gap #2 · Generation rules: `SEED-PLAYBOOK.md`
- Onboarding thread: `status/active/ONBOARDING-BASELINE-HANDOFF-2026-06-03.md`
- Connect simulator + activity: `ACTIVITY-PLAN.md`
