# Operating Model Benchmarks — Raw Research Backup

**Run ID:** `wf_1d14474c-c86`
**Date:** 2026-06-02
**Workflow:** `deep-research` harness (fan-out web searches, adversarial verifier, claim synthesis)
**Purpose:** Calibrate the Decathlon Manhattan City-format store operating model with defensible, cited benchmarks.

## Verifier-Bug Caveat

The adversarial verifier failed mechanically on this run. Every verification vote returned `0-0 (3 abstain)` due to a StructuredOutput bug that prevented verifier agents from recording their judgments. The workflow's summary line ("All 25 claims refuted") is incorrect — these claims were never actually contested. The `refuted` array is a mislabeled list of the **25 extracted claims that were queued for verification but never voted on**. Do not treat any claim here as debunked.

The distilled numbers from this research already live in `reference/data/STORE-OPERATING-MODEL.md`. This file preserves the complete raw-claim backup — every claim's exact wording and source URL — in case the original workflow temp file is cleared.

---

## Research Question

Assemble a cited set of retail operating benchmarks to calibrate a synthetic "store operating model" for a Decathlon City-format urban sporting-goods store: ~600 sqm sales floor, Flatiron Manhattan, USD, ~900 SKUs spanning running/fitness/hiking/swim/cycling/team-sport/apparel/footwear/GPS-watches/accessories. I need defensible numbers (with sources and ranges) for:

1. Daily footfall/visitor count for a 600 sqm urban specialty sporting-goods store, weekday vs weekend multiplier, and an hourly arrival/traffic curve for a 10am–9pm operation including lunch and evening peaks.
2. Retail conversion rate for sporting-goods/specialty (browse→buy), and uplift from try-on / fitting-room / gait-analysis engagement.
3. Average transaction value (ATV/basket) and units-per-transaction for sporting goods, and a category sales-mix breakdown (what % of revenue/units by category: footwear, apparel, accessories, hardware/watches, etc.).
4. In-store dwell time (total and per-zone/department) for specialty retail.
5. Sales-floor stocking depth — typical units-on-floor / facings per SKU and on-floor vs backroom split for specialty sporting goods, plus inventory turns.
6. Retail shrinkage rate (% of sales) for sporting goods and the typical split between shoplifting / employee / admin.
7. Staffing — associates per shift / per sqm for specialty retail and restock/replenishment cadence.

Decathlon-specific public data (revenue per sqm, footfall, basket) preferred where available; otherwise NRF, sporting-goods retail industry benchmarks, and comparable specialty-retail studies. Output each parameter with a point estimate, a plausible range, and the source.

---

## Sources

| URL | Quality | Claims |
|-----|---------|--------|
| https://www.decathlon-united.media/pressfiles/decathlon-group-2024-performance | primary | 4 |
| https://www.sec.gov/Archives/edgar/data/0001089063/000108906326000021/dks-2026502xex991earningsr.htm | primary | 5 |
| https://nrf.com/media-center/press-releases/shrink-accounted-over-112-billion-industry-losses-2022-according-nrf | primary | 4 |
| https://ecdb.com/resources/sample-data/retailer/decathlon | secondary | 4 |
| https://www.retailtouchpoints.com/features/executive-viewpoints/analytics-that-make-a-difference-data-shows-increased-conversion-rates-for-staffed-fitting-rooms | secondary | 5 |
| https://retailowner.com/Benchmarks/Recreation-Leisure-Activities-Stores/Sporting-Goods-Stores | secondary | 2 |
| https://storeforcesolutions.com/blog-type/blog-post/retail-peak-hours-critical-in-driving-store-performance/ | blog | 5 |
| https://www.storetech.com/resources/using-people-counting-data-to-know-peak-shopping-hours | blog | 1 |
| https://www.growthfactor.ai/resources/blog/mall-foot-traffic-data-guide | blog | 5 |
| https://www.posnation.com/blog/peak-hour-retail-operations-how-to-manage-rush-periods | blog | 4 |
| https://www.sensormatic.com/shoppertrak-retail-traffic-insights/people-counting | blog | 2 |
| https://www.storetech.com/resources/key-trends-shaping-footfall-in-2025 | blog | 4 |
| https://financialmodelslab.com/blogs/kpi-metrics/sports-equipment-store | blog | 3 |
| https://www.getdor.com/blog/2026/04/28/what-is-a-retail-conversion-rate-the-complete-guide-for-store-owners/ | blog | 5 |
| https://alerttech.net/retail-industry-benchmarks-for-fitting-rooms/ | blog | 5 |
| https://v-count.com/why-benchmarking-fitting-room-data-is-important-for-retail-industry/ | blog | 5 |
| https://www.retailsensing.com/people-counting/retail-dwell-time-metric/ | blog | 3 |
| https://umbrex.com/resources/industry-analyses/how-to-analyze-a-retail-company/store-labor-productivity-analysis/ | blog | 4 |
| https://www.shopify.com/enterprise/blog/sales-per-square-foot | blog | 4 |
| https://www.ariadne.inc/resources/blogs/people-counting-for-department-stores/ | blog | 0 |
| https://www.xovis.com/insights/detail/measuring-in-store-dwell-times-use-case | unreliable | 0 |
| https://www.macrotrends.net/stocks/charts/BGFV/big-5-sporting-goods/inventory-turnover | unreliable | 0 |
| https://www.myshyft.com/blog/retail-staffing-metrics/ | unreliable | 0 |
| https://fred.stlouisfed.org/series/IPUHN45111L000000000 | unreliable | 0 |

---

## Extracted Claims (verbatim)

Claims are grouped by source. Wording is preserved exactly as extracted by the research harness. None of these claims were verified or refuted — see caveat above.

### https://nrf.com/media-center/press-releases/shrink-accounted-over-112-billion-industry-losses-2022-according-nrf

- Retail shrink represented 1.6% of total retail sales in FY2022, up from 1.4% in FY2021.
- Total retail shrink losses reached $112.1 billion in 2022, up from $93.9 billion in 2021 (a $18.2 billion increase).
- Internal and external theft together accounted for nearly two-thirds (65%) of retailers' shrink.
- The survey findings are based on 177 retail brands representing $1.6 trillion in 2022 retail sales across more than 97,000 U.S. locations.

### https://www.retailtouchpoints.com/features/executive-viewpoints/analytics-that-make-a-difference-data-shows-increased-conversion-rates-for-staffed-fitting-rooms

- Fitting-room visitors at the studied department-store chain were historically 25% more likely to convert than other shoppers, and each shopper entering the fitting-room area was worth an additional ~$6 in expected sales (total expected value per fitting-room shopper ~$18).
- Adding one staff member to the fitting room raised transactions per hour by 12.55% and sales per hour by $399.55, equal to a 15.98% increase in total store sales per hour.
- A 10-store pilot adding fitting-room staffing during peak hours produced a 2.1% uptick in conversion, leading the chain to roll out increased fitting-room labor chain-wide.
- Store sales volume has an inverted-U relationship with fitting-room traffic: in an unstaffed self-service model, sales rise then diminish and eventually turn negative as fitting-room congestion increases.
- Baseline per-visitor sales expectation at the chain was $12 for every person entering the store, with fitting-room entrants worth an incremental $6.

### https://storeforcesolutions.com/blog-type/blog-post/retail-peak-hours-critical-in-driving-store-performance/

- Retail stores see roughly 50% of their weekly traffic and sales concentrated in the busiest 20 hours of the week (the '50/20 rule'), holding across low- and high-volume stores.
- The share of weekly traffic occurring during peak hours rose from 46% (2019) to 51% (2020), and peak-hour share of weekly sales rose from 47% to 53%.
- Intraday traffic shifted toward afternoons in 2020: afternoons saw a 14% traffic increase while evenings saw a 16% decrease.

### https://www.growthfactor.ai/resources/blog/mall-foot-traffic-data-guide

- Mall peak shopping hours are 12-3 PM, accounting for ~35% of daily traffic, with elevated morning traffic 9am-noon post-pandemic.
- Weekend traffic accounts for ~65% of total mall visits, while weekday share of total traffic rose from ~60% to over 70% between 2019 and 2022.

### https://www.storetech.com/resources/key-trends-shaping-footfall-in-2025

- In a 10am-9pm style retail operation, footfall peaks at lunchtime, with the busiest hour being noon to 1pm; morning traffic only builds toward midday and tapers after 2pm.

### https://www.getdor.com/blog/2026/04/28/what-is-a-retail-conversion-rate-the-complete-guide-for-store-owners/

- Specialty retail stores (gifts, home décor) typically convert browsers to buyers at 10–20%, with strong performers at 25%+.

### https://alerttech.net/retail-industry-benchmarks-for-fitting-rooms/

- Shoppers who use fitting rooms are approximately 7 times more likely to make a purchase than those who only browse, establishing a strong try-on/engagement conversion uplift.
- About 67% of customers who visit a fitting room go on to make a purchase (fitting-room-to-register conversion).

### https://v-count.com/why-benchmarking-fitting-room-data-is-important-for-retail-industry/

- Shoppers who use fitting rooms are 8x more likely to buy, and those who receive great service in the fitting room are 4x as likely to purchase — quantifying try-on engagement conversion uplift.
- The average fitting-room-to-register conversion rate is 72%, and fitting rooms account for ~30% of overall conversion rate.

### https://www.retailsensing.com/people-counting/retail-dwell-time-metric/

- A 1% increase in retail dwell time correlates with a 1.3% increase in sales (cited from a Pathintelligence study).

### https://www.decathlon-united.media/pressfiles/decathlon-group-2024-performance

- Decathlon Group generated 16.2 billion euros in net sales in 2024, up +5.2% at constant exchange rate (+3.8% reported), across 1,817 stores — implying roughly 8.9 million euros average annual revenue per store at the group level.

### https://www.sec.gov/Archives/edgar/data/0001089063/000108906326000021/dks-2026502xex991earningsr.htm

- In its fiscal Q1 (13 weeks ended May 2, 2026), the core DICK'S Sporting Goods business delivered 6.0% comparable-sales growth driven by simultaneous growth in BOTH average ticket and transaction count, indicating positive momentum in basket value and footfall/conversion at a leading US sporting-goods retailer.
- DICK'S categorizes its sporting-goods sales mix into three broad merchandise groups — footwear, apparel, and hardlines — reflecting the standard category taxonomy for sporting-goods retail revenue breakdown.

### https://retailowner.com/Benchmarks/Recreation-Leisure-Activities-Stores/Sporting-Goods-Stores

- The sporting-goods benchmarks are sourced from the Risk Management Association (RMA) Annual Statement Studies Financial Ratio Benchmarks, 2021-2022, reported as median values.

---

## Stats

6 angles researched, 24 sources fetched, 74 claims extracted, 25 queued for verification, 0 confirmed, 25 mechanically killed (verifier bug — not genuine refutations).
