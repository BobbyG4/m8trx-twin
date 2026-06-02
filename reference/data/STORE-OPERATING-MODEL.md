# Store Operating Model — Decathlon Manhattan (Flatiron)

**Status:** CALIBRATED v1 — 2026-06-02 (Session 4). Numbers filled from benchmark research
(`deep-research` workflow `wf_1d14474c-c86`) + targeted follow-up searches.

**Confidence tags** per parameter: **[S]** sourced (published figure), **[D]** derived (computed
from sourced inputs + the reconciliation identity), **[A]** assumed (reasoned default; no clean
public figure — tune freely). Bob's bar for this work: *realistic, not accurate.* These are
demo-calibration constants, not audited financials.

> **Verification caveat.** The research harness fetched 24 sources / 74 claims but its adversarial
> verifier failed mechanically (StructuredOutput bug → all votes `0-0 abstain`, not real
> refutations). So the **[S]** figures are sourced but **not independently cross-verified**, and
> several fitting-room uplift stats come from people-counting *vendor* blogs (marketing-grade —
> treated skeptically, conservative end taken). Good enough to make surfaces look real; do not cite
> externally without re-verifying.

**Purpose:** the single, citable source of realism for every Layer-3 generator. Traffic,
Transaction, Staff, and Stocktake generators all read these constants, so each persona is *sampled
from a distribution* instead of hand-numbered in isolation.

**Consumers:** `store-operating-model.json` (machine mirror) · `TrafficGenerator` ·
`TransactionGenerator` · `StaffShiftGenerator` · `StocktakeGenerator` · LP/shrink scenarios.
**Grounds against:** `STORE-LAYOUT.md` (600 sqm, 149 fixtures, 3 try-on zones) ·
`catalog/decathlon-manhattan-skus.csv` (≈871 SKUs / 15 categories, avg $25.36, 36 EAS items).

---

## 1. The Reconciliation Identity (the realism contract)

Every surface must tie out to these five numbers for a simulated day. A generator is *correct* only
if its emitted events reconcile:

```
visitors            = footfall(day_type, date)                         §3
transactions        = visitors × conversion_rate                       §4
revenue             = transactions × ATV                               §5
units_sold          = transactions × units_per_txn                     §5
revenue_by_category = revenue × category_revenue_mix[c]                 §5
shrink_units/day   ≈ (revenue × shrink_rate) / ATV                     §7
```

**Worked baseline (typical weekday):**
```
850 visitors × 22% conversion        = 187 transactions
187 transactions × $58 ATV           = $10,846 revenue
187 transactions × 2.2 units         = 411 units sold
avg line price = $10,846 / 411       = $26.4   ✓ (catalog avg $25.36)
```
**Annualized sanity:** blended ~1,020 visitors/day × 22% × $58 ≈ $13.0k/day → ≈ $4.4M/yr →
≈ $680/sqft (≈ $7,300/sqm). Healthy specialty-retail productivity, Decathlon-class. ✓

**Direction of authority:** footfall + funnel ratios are *inputs*; the orchestrator derives how many
of each persona to instantiate to hit these targets (± sampling noise). Personas never carry
hardcoded daily counts.

---

## 2. Scenarios as deltas (delta principle)

This document **is** the canonical Layer-4 baseline config — the "A Day in the Life" default fill.
Named scenarios are *deltas* over it, not separate parameter sets:

| Scenario | Delta over baseline |
|---|---|
| **A Day in the Life** | baseline as-is (weekday/weekend per `date`) |
| **Saturday Rush** | `footfall ×1.7`, weekend hourly curve, peak compression |
| **The Theft** | `+N` Shoplift personas in GPS/watch zone; shrink scenario active |
| **Fitting Room Conversion** | bias persona mix → TryOnAndPartialBuy; raise try-on rate |
| **Compliance Day** | staffing + restock emphasis; planogram-adherence events |

Adding a scenario is a config edit, not generator code — per the "Layer 4 config schema is the
contract" discipline in `CLAUDE.md`.

---

## 3. Traffic — footfall & arrival shape

| Parameter | Point | Range | Conf | Source / basis |
|---|---|---|---|---|
| Visitors/day (weekday) | **850** | 600–1,100 | [A] | small specialty 100–200/day floor; urban Flatiron destination scales up |
| Visitors/day (weekend day) | **1,450** | 1,000–1,800 | [A] | "busy store 1,000+ weekend" + 1.7× multiplier |
| Weekend/weekday multiplier | **1.7** | 1.5–2.0 | [S] | mall weekend-share ~65% of visits |
| Operating hours | 10:00–21:00 (11h) | — | [A] | store concept |
| Capture rate (pass→enter) | **35%** | 25–45% | [A] | urban high-street typical |
| Seasonality (peak/trough) | **±25%** | — | [S] | sporting goods seasonal (season-change peaks) |

**Hourly arrival curve** — fraction of daily footfall per open hour (each column sums to 1.00,
index 0 = 10:00–11:00). [S] shape: lunch + evening peaks, weekend midday-heavy.

| Hour | Weekday | Weekend |
|---|---|---|
| 10–11 | 0.05 | 0.06 |
| 11–12 | 0.07 | 0.09 |
| 12–13 (lunch) | 0.11 | 0.12 |
| 13–14 | 0.10 | 0.13 |
| 14–15 | 0.08 | 0.12 |
| 15–16 | 0.08 | 0.11 |
| 16–17 | 0.09 | 0.10 |
| 17–18 | 0.11 | 0.09 |
| 18–19 (evening peak) | 0.13 | 0.08 |
| 19–20 | 0.11 | 0.06 |
| 20–21 | 0.07 | 0.04 |

> TrafficGenerator samples each visitor's arrival from this curve (inhomogeneous Poisson),
> assigns a persona (§6), and runs the journey through §8 zone-affinity weights.

---

## 4. Conversion funnel

| Parameter | Point | Range | Conf | Source / basis |
|---|---|---|---|---|
| Overall conversion (visitor→buyer) | **22%** | 15–28% | [S] | specialty retail 10–20%, strong 25%+; Decathlon = destination |
| Browse-only share | **78%** | — | [D] | = 1 − conversion |
| Try-on engagement rate (% visitors) | **18%** | 12–25% | [A] | footwear+apparel try-heavy assortment |
| Try-on → buy uplift (× baseline) | **2.5×** | 2–3× | [S]* | vendor blogs claim 7–8×; conservative taken (*marketing-grade) |
| Fitting-room → register conversion | **67%** | 60–72% | [S]* | AlertTech/V-Count fitting-room benchmarks |
| Gait-analysis → shoe conversion | **55%** | 45–65% | [A] | staff-assisted, high-intent; > generic try-on |

---

## 5. Basket & category mix

| Parameter | Point | Range | Conf | Source / basis |
|---|---|---|---|---|
| Average transaction value (ATV) | **$58** | $45–$75 | [D] | UPT × avg line; reconciles to catalog $25.36 avg |
| Units per transaction | **2.2** | 1.8–2.6 | [A] | physical specialty (e-comm "12 units" rejected as junk) |
| Avg line price (check) | **$26.4** | — | [D] | ATV ÷ UPT ≈ catalog avg ✓ |

**Category revenue mix** (sums to 1.00). [D] from general sporting-goods benchmark (equipment 40 /
apparel 30 / footwear 20 / services 10) reweighted for this catalog's skew (footwear 136 SKUs,
apparel/running-app 133, fitness 118, hiking/swim/cycle 181, watches 36).

| Bucket | store_cat members | Revenue % | Unit % | Conf |
|---|---|---|---|---|
| Footwear | running_shoe, footwear | 0.25 | 0.18 | [D] |
| Apparel | apparel, running_app | 0.25 | 0.27 | [D] |
| Hardware / watches | watch_gps | 0.08 | 0.04 | [D] |
| Fitness / equipment | fitness | 0.18 | 0.15 | [D] |
| Outdoor (hike/swim/cycle) | hiking, swim, cycling | 0.15 | 0.16 | [D] |
| Team sport | team_sport | 0.05 | 0.07 | [D] |
| Accessories / other | accessories, eyewear, protection, bag_pack, other | 0.04 | 0.13 | [D] |

> Revenue vs unit mix differ (watches: few units, high $; accessories: many units, low $).
> TransactionGenerator uses unit mix to pick SKUs, revenue mix to validate, and per-category
> price-band weighting so most baskets are mid-price with occasional high-value watch.

---

## 6. Persona mix

Fraction of *sessions* (not transactions) by persona. Buying-persona shares sum to ≈ conversion (§4).

| Persona | Session share | Buys? | Conf | Notes |
|---|---|---|---|---|
| BrowseAndLeave | 0.78 | no | [D] | the (1 − conversion) bulk |
| ShopAndBuy | 0.14 | yes | [D] | direct purchase |
| TryOnAndPartialBuy | 0.08 | partial | [D] | enters try-on, buys subset |
| Shoplift | 0.003 (scenario-scaled) | no (theft) | [A] | §7; GPS/watch zone anchor |

> Staff personas (StaffRestock, StocktakeWalk) are not footfall — see §9.

---

## 7. Shrinkage (LP scenario calibration)

| Parameter | Point | Range | Conf | Source |
|---|---|---|---|---|
| Shrink rate (% of sales) | **1.6%** | 1.4–1.8% | [S] | NRF FY2022 (1.6%, up from 1.4%) |
| External theft (shoplifting) share | **37%** | — | [S] | NRF (theft ~65% combined) |
| Internal (employee) share | **29%** | — | [S] | NRF |
| Administrative / process share | **34%** | — | [D] | remainder |
| EAS-tagged scope | 36 items | — | — | catalog high-value flag |

> Daily shrink units ≈ (revenue × 1.6%) / ATV ≈ ($10,846 × 0.016)/$58 ≈ **3 units/day** baseline;
> "The Theft" scenario concentrates these as discrete events at the GPS/watch EAS zone.

---

## 8. Zone affinity / dwell

| Parameter | Point | Range | Conf | Source / basis |
|---|---|---|---|---|
| Total in-store dwell (median) | **18 min** | 10–35 | [S] | specialty-retail dwell benchmarks |
| Dwell — footwear/gait zone | 9 min | 5–15 | [A] | try-on heavy |
| Dwell — apparel/gondola | 6 min | 3–10 | [A] | |
| Dwell — watch/GPS display | 4 min | 2–7 | [A] | considered purchase |
| Dwell — checkout | 3 min | 1–6 | [A] | queue + transaction |
| Zones visited / session | 4 | 2–7 | [A] | |

**Zone-visit affinity** — P(session visits zone); drives `objLocation` paths through `STORE-LAYOUT`
zones. [A] from entrance proximity + category pull.

| Zone | P(visit) |
|---|---|
| Z-04 Main Sales Floor (gondolas) | 0.95 |
| Z-08 Footwear Bench | 0.35 |
| Z-09 Gait Analysis | 0.12 |
| Z-06 GPS & Accessories | 0.30 |
| Z-10 Fitting Rooms | 0.18 |
| Z-02 Checkout (buyers) | = conversion |

---

## 9. Staffing & replenishment

| Parameter | Point | Range | Conf | Source / basis |
|---|---|---|---|---|
| Associates on floor (peak) | **5** | 4–6 | [A] | ~1 per 120 sqm at peak |
| Associates on floor (off-peak) | **3** | 2–4 | [A] | |
| Density | ~1 / 120 sqm | 1/100–1/150 | [A] | specialty rule-of-thumb |
| Restock / replenishment | continuous + AM open-fill | — | [A] | fast-movers topped during day |
| Cycle count | weekly (rotating zones) | — | [A] | |
| Full stocktake | quarterly | — | [A] | |

---

## 10. Stocking depth (item/EPC seeding)

Drives `inventoryReceive` quantities — physical units (EPCs) on the floor per SKU, by category.
Fast-movers get more facings; watches few + EAS-locked.

| Parameter | Point | Range | Conf | Source / basis |
|---|---|---|---|---|
| On-floor units/SKU — footwear (per size) | 4 | 3–6 | [A] | |
| On-floor units/SKU — apparel (per size) | 6 | 4–8 | [A] | |
| On-floor units/SKU — watches (locked case) | 2 | 1–2 | [A] | high-value, secured |
| On-floor units/SKU — accessories | 8 | 6–12 | [A] | impulse depth |
| On-floor units/SKU — equipment/outdoor | 3 | 2–4 | [A] | bulky, low facing |
| On-floor vs backroom split | 65% / 35% | — | [A] | small-format urban = limited backroom |
| Annual inventory turns | **2.7×** | 2.0–3.5 | [S] | multi-source sporting-goods benchmark |
| Active SKUs (variant-level) | **~5,000** | 3,500–6,000 | [S] | 6–10 SKU/m² × 600 m² benchmark |
| Avg depth | **~5** pieces/SKU | 4–8 | [S] | sporting-goods depth benchmark |
| Total pieces / EPCs (four-wall) | **~25,000** | 20,000–40,000 | [S] | SKUs × depth; see INVENTORY-SEEDING-PIPELINE.md §4 |

> **Scale locked 2026-06-02** against the 6–10 SKU/m² + depth 4–8 industry benchmark (supersedes the
> earlier ~4,300 estimate, which conflated boutique scale). Full per-category SKU/depth allocation +
> planogram in `INVENTORY-SEEDING-PIPELINE.md` §4. Note `total pieces` is four-wall (floor + backroom);
> 65% sits on the floor.

---

## Sources

Fetched by the research workflow (quality grade in brackets). **Not independently cross-verified**
— see caveat at top.

- [NRF — National Retail Security Survey 2023 / FY2022 shrink](https://nrf.com/media-center/press-releases/shrink-accounted-over-112-billion-industry-losses-2022-according-nrf) **[primary]** — shrink 1.6% of sales; theft ~65% of shrink; 177 brands / $1.6T basis.
- [Decathlon Group 2024 performance](https://www.decathlon-united.media/pressfiles/decathlon-group-2024-performance) **[primary]** — €16.2B net sales, 1,817 stores (group context).
- [DICK'S Sporting Goods Q1 FY2026 8-K (SEC)](https://www.sec.gov/Archives/edgar/data/0001089063/000108906326000021/dks-2026502xex991earningsr.htm) **[primary]** — footwear/apparel/hardlines taxonomy; ticket + transaction growth.
- [Retailowner.com — Sporting Goods Stores Benchmarks (RMA)](https://retailowner.com/Benchmarks/Recreation-Leisure-Activities-Stores/Sporting-Goods-Stores) **[secondary]** — financial-ratio medians.
- [Retalon — Inventory turnover in retail](https://retalon.com/blog/inventory-turnover-ratio) **[blog]** — sporting goods ~2.7× annual turns.
- [Shopify — Foot traffic / basket size guides](https://www.shopify.com/blog/foot-traffic) **[blog]** — small specialty 100–200/day; busy store 1,000+/weekend.
- [getDor — Retail conversion-rate guide](https://www.getdor.com/blog/2026/04/28/what-is-a-retail-conversion-rate-the-complete-guide-for-store-owners/) **[blog]** — specialty 10–20%, strong 25%+.
- [StoreForce — Retail peak hours](https://storeforcesolutions.com/blog-type/blog-post/retail-peak-hours-critical-in-driving-store-performance/) **[blog]** — 50/20 rule, peak-hour share, weekday/weekend.
- [GrowthFactor — Mall foot-traffic guide](https://www.growthfactor.ai/resources/blog/mall-foot-traffic-data-guide) **[blog]** — weekend ~65% of visits; midday peak.
- [RetailTouchPoints — staffed fitting-room conversion](https://www.retailtouchpoints.com/features/executive-viewpoints/analytics-that-make-a-difference-data-shows-increased-conversion-rates-for-staffed-fitting-rooms) **[secondary]** — fitting-room conversion uplift (vendor study).
- [AlertTech](https://alerttech.net/retail-industry-benchmarks-for-fitting-rooms/) / [V-Count](https://v-count.com/why-benchmarking-fitting-room-data-is-important-for-retail-industry/) **[blog]** — fitting-room-to-register 67–72% (*marketing-grade, conservative end taken).
- [RetailSensing — dwell-time metric](https://www.retailsensing.com/people-counting/retail-dwell-time-metric/) **[blog]** — dwell↔sales correlation.
- [FinancialModelsLab — sports-equipment KPIs](https://financialmodelslab.com/blogs/kpi-metrics/sports-equipment-store) **[blog]** — category-mix benchmark (equipment 40 / apparel 30 / footwear 20 / services 10); *rejected its $8,775 AOV / 12-UPT as e-comm/B2B artifacts.*

### Rejected as implausible (recorded so they don't get re-pulled)
- "$8,775–$12,240 AOV" for sporting goods (FinancialModelsLab) — B2B/equipment SaaS modeling figure, not a physical-store basket.
- "12–16 units per transaction" — e-commerce cart metric, not in-store UPT.
- "7–8× / 67–72% fitting-room conversion" taken at face value — people-counting vendor marketing; used only directionally at the conservative end.
