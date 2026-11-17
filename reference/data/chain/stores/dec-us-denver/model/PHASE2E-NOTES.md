# Phase 2e notes: dwell that differs by fixture kind and content (offline)

Code: `storeday/DwellModel.kt` (kinds, every knob, the stats), `storeday/DwellSelfTest.kt`, `storeday/DwellReport.kt` (`./gradlew storeDayDwellReport`), the `FloorHooks` seam in `behavior/ShopperAgent.kt`. Run: `./gradlew storeDayDwellReport` (env `M8TRX_STORE_DAY_SITE` comma list, `M8TRX_STORE_DAY_DWELL=off` for the 2d floor, `M8TRX_STORE_DAY_COMPARE=<dir of old calibration files>` for the before/after table). Offline.

## What was wrong
Dwell per visit was `uniform(25..80 s) x sqrt(content appeal)` for every fixture: a store mean of 53 s with no spread by kind, and a median session of 5 to 6 minutes against the operating model's 18. Total dwell per fixture was visits x 53 s.

## What it is now (store-day runs only)
The seam is three new default methods on `FloorHooks` (`dwellScale`, `cellsConsidered`, `tryOnScale`); only `StoreDaySim`'s hooks override them, so behavior runs (`behaviorSelfTest` 35/0), Denver's tuned file, and the history generator never see them.

    span = draw (behavior file, 25..80 s) x kind factor x depth factor x attraction factor x store dwell scale x (plant lever)

| Knob (`DwellKnobs`) | Value | Meaning |
|---|---|---|
| kind factor | footwear wall 2.0 · apparel rail/rounder 1.4 · folded apparel 0.9 · pegboard 0.45 · promo table/bin 0.5 · GPS case 2.4 · bike 3.0 · large items 1.3 | relative span; the level comes from the store scale |
| kind from | `model/fixtures.json`: category (GPS case, promo island, checkout, impulse rack, accessories wall), else the goods class holding most facings (shoe, hang, fold, peg, bike, bulk) | |
| depth | (distinct styles / store median)^0.3, clamped 0.6..1.7 | a wider range is browsed longer |
| attraction | (content appeal x impression truths)^0.5, floor 0.25 | replaces the old sqrt(content appeal) |
| interactions | cells considered = 4 x (span / 52.5 s)^0.35, at least 1 | pickups, put-backs and try-ons (a try-on is a share of pickups) all follow |
| try-on span | x1.8 (bench and fitting room) | the bench is the longest footwear stop |
| misplaced put-back | probability x0.52; never for items of 500 USD or more | see below |
| `Calibration.dwellScale` | per store, in `calibration.json` | fitted, see below |

The kind factors are my reading of the brief's words (long / medium-long / medium / short), not measurements; they are one table to change. "Bikes: long but rare" is long (3.0) here; their rarity is the fixture's own stop weight, which the calibration owns and this slice does not touch.

## Calibration to the operating model
`dwell.total_median_min` is 18 (range 10 to 35) and is a MEDIAN, so the store figure gated is the median session (door to door, 14 stocked days): the scale is fitted by secant steps until it is 18.0 (+/-10 % is the self-test band; every store lands within 1 %). The model's other figure, `zones_visited_per_session` 4, is not used to derive a per-stop target: shoppers stop at 4.5 browse fixtures here, and a per-stop target of 270 s (18 / 4) produced a 23 to 31 minute median session, so the session median is the honest anchor. The mean stop falls out at 170 to 210 s.

Informational check against `dwell.by_zone_min` (minutes in the zone per shopper who visited it, browse plus try-on): footwear 9.8 (model 9), GPS 3.6 (model 4), apparel 13.6 (model 6). Apparel is high because the figure sums every apparel fixture a shopper stops at (4 to 5 apparel stops are common) plus the fitting room; the operating model's 6 minutes reads as one zone. Not tuned further; flagged.

## Per-kind mean dwell per engaged browse visit (14 stocked days, 2026-09-07)
| kind | Denver mean s | Denver p10-p90 | Busan (small, 270 m2) mean s |
|---|---:|---|---:|
| footwear wall | 236 | 131-351 | 200 |
| apparel rail / rounder | 212 | 120-313 | 177 |
| folded apparel | 116 | 65-171 | 119 |
| pegboard accessories | 74 | 40-111 | 57 |
| promo table / bin | 76 | 45-113 | 41 |
| GPS case | 187 | 108-264 | 194 |
| bike | 193 | 111-273 | 178 |
| large items | 189 | 104-284 | 188 |
| footwear bench (try-on) | 378 | | 380 |
| store mean browse stop | 175 | | 171 |
| median session (min) | 18.0 | | 17.9 |

Before (2d floor, Denver): every kind 53 s, median session 5.4 min.

## Demand calibration, before and after
The dwell model adds handling (1.1 to 1.7 pickups per visit, was 1.1) so on the 2d calibration the stocked day over-sells by 15 to 37 %. Re-fitted per store with the same `storeDayCalibrate` (now warm-started from the committed file and fitting the dwell scale first and again after the stop-scale stage; a 56-day window for all ten, as 2b did for nine; Denver's 2a file was a 14-day fit and Denver failed its 28-day hold-out on a 14-day refit, hence 56). Hold-out window of the self-test (28 stocked days from 2027-01-04):

| store | variant | total error | worst department (error, tolerance) | fixture MAPE |
|---|---|---:|---|---:|
| Denver | A 2d calibration, 2d floor | -1.5 % | climb -9.1 % (15) | 32.6 % |
| Denver | B 2d calibration, dwell on | +26.0 % | hike_camp +39.1 % (10) | 39.8 % |
| Denver | C 2e calibration | -2.0 % | climb -11.8 % (15) | 28.5 % |
| Busan | A | -2.0 % | general -27.4 % (36) | 34.1 % |
| Busan | B | +31.7 % | hike_camp +32.7 % (10) | 44.1 % |
| Busan | C | -3.0 % | general -11.8 % (36) | 33.2 % |

(Paris, Bordeaux, NYC in the PR description; same shape: B is 15 to 37 % over, C within 1 to 4 %.) The 2a/2b tolerances are unchanged and `storeDaySelfTest` gates C on all ten.

One hand adjustment, Denver only: after the fit, `general`'s buy scale was multiplied by 1.05 (8.3214 to 8.7375). The stock-aware 28-day run sold 14.6 % of units in `general` against 16.3 % demanded (a 10 % tolerance): the fast pegboard lines run short in a long day and the stocked-day fit cannot see that loss. The stocked hold-out stays inside its band and every plant check is unchanged.

## Two side effects, both documented where they bite
- **Misplacements.** More put-backs per unit sold doubled the share of units sold from a wrong fixture (9.7 % against the 2a figure of about 5 %); the per-put-back chance is scaled by 0.52 to hold it, and bike-class items (500 USD or more) are never misplaced: a misplaced bike left PW-05 holding 46,600 USD (above its 32,000 plan) and broke the under_seen check. Both apply only with the dwell model on.
- **Plant config.** The recalibration moved every fixture's baseline, so the 2d planted levers were re-tuned (layout climb x6 with buy 0.2, look_not_buy control x2.5 / x2.6, Paris promote buy 5.0, Denver looked-at cell weight 10, Denver restock B). See `PHASE2D-NOTES.md` for what each lever does; the numbers are in `plant-config.json`.

## move_fast_seller, re-planted as the eval states it
The 3x seller now sits on a fixture of few visits and a long dwell per visit: Denver `GF-R2-U5` (swap with 3608409910639), Paris `PE-06` (swap with 3608392174957), each with a new lever `fixture_dwell_mul` 1.25 (the span only: a demo, seating, an associate; visits untouched). A fixture's natural dwell per visit is its kind, depth and content; the lever adds a margin over the top-decile line. `VERIFY.md` gains "its fixture is high-dwell: dwell per visit in the top decile of the merchandise fixtures (at least 20 visits)", alongside the low-visit check. The key text now reads "few visits, but long dwell per visit".

## Outputs that changed
Every store-day output (visits' spans, impressions' dwell, pickups, put-backs, try-ons, units, misplacements, wire plans) for all ten stores, because the calibration and the floor changed; the ten `calibration.json` files (new `dwell_scale`, new stop and buy scales); `plant-config.json` and `answer-key-example.json` (regenerated). Unchanged: every behavior run, `behaviorSelfTest`, Denver's behavior file, the operating model, the chain's committed layout/planogram/assortment/EPC files, the history generator.
