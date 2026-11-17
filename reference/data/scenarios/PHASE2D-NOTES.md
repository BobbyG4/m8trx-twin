# Phase 2d notes: planted scenarios and the eval answer key (offline)

Code: `src/main/kotlin/com/m8trx/twin/storeday/Plant*.kt`. Config: `reference/data/scenarios/plant-config.json`. Example key from the deterministic Denver + Paris 28-day run: `answer-key-example.json` (the self-test regenerates it and fails if the committed copy differs).

Run: `./gradlew storeDayPlantRun` (writes `out/scenarios/<run>/answer-key.json`, `VERIFY.md`, per-store ledger CSVs) and `./gradlew storeDayPlantSelfTest`. Both offline, record only, no socket. Env: `M8TRX_PLANT_CONFIG`, `M8TRX_PLANT_STORES` (comma list), `M8TRX_PLANT_RUN`, `M8TRX_PLANT_OUT`, plus `M8TRX_STORE_DAY_FROM/DAYS`.

## What is planted, and how (never by faking an output)
A scenario pulls only levers the simulation already has. Each store in the config lists its scenarios; the levers merge per store and a clash between two scenarios is refused.

| Lever | Mechanism |
|---|---|
| `fixture_mul` | the fixture's stop weight (shoppers stopping there: impressions, dwell). The lever the calibration already uses |
| `fixture_buy_mul`, `buy_mul` | the shopper's chance to buy what is in hand, per fixture or per EAN |
| `cell_mul` | how readily a cell is picked among those a shopper looks at closely (the demand weight) |
| `swaps` | the period plan: a product takes another fixture's line, the displaced product takes its first line |
| `required_abs`, `required_ean_mul`, `required_fixture_mul` | the plan's required quantity per line. Used only to lower stock (never above the Phase 1 line capacity; the self-test asserts every edited line is at or below its unedited line) |
| `backroom_abs`, `initial_units` | the back-room cover the order tops up to; the units the store starts with |

Plan edits are a pure function of the unedited weekly plan (`PlantPlanEdit`); the weekly changes of 2c skip every planted fixture and product, so a facing change or style swap cannot undo the pattern. With no plant the 2c draws are unchanged: Denver and Paris 28-day unplanted runs are byte-identical to the pre-2d output (checked file by file, wire plans included).

Planted from `run.from` (a Monday, store-local), to the last simulated day. Denver plus Paris, a config (`stores`), never all ten. Entities are the same kind in both stores but chosen per store (own layout).

| Archetype | Plant (Denver; Paris is the same recipe on its own fixtures) |
|---|---|
| promote | a fast seller moved (swap) to a quiet fixture and boosted to the sell-through of a natural fast seller on the busiest fixture; two products with 6 to 20 times the pickups and a 0.01 buy factor; one fast product held at a 2-unit floor and no back room (cover under 5 days) |
| layout | the general department's fixtures at 0.3 of their visits (least visited region, its products still the strongest sellers); the climbing department's fixtures at 3.5 of theirs with a 0.4 buy factor (most visited region, slow sellers) |
| move_fast_seller | a climbing product swapped onto a low-visit cycling fixture, 3x the best of its department; the key names a top-decile fixture and its slow seller as the target |
| under_seen | the fixture whose planned (full) contents are worth most (Denver GPS-06, Paris GPS-01: premium GPS cases) with its visits at 0.1: the largest on-hand value in the store at full capacity, bottom decile of impressions and dwell. No over-stocking |
| restock_first | A fast with 3 units on the floor, no back room and 4x demand (cover under 2 days), B fast with its normal stock (cover over 20 days), C slow and held at 2 units |
| quiet_stock | five products at their normal full shelf (90 % or more of their lines' required quantity on the floor), buy factor 0, attraction x0.001, on one below-median fixture, in a category that sells elsewhere |
| look_not_buy | two top-3 fixtures: a premium one (buy factor 0.02) and one with required quantity 0 (nothing on the shelf); a control at the same visit level with normal sales |

`VERIFY.md` shows each claim with the numbers. All 90 checks pass (45 per store, including the capacity check).

## Not planted
- `entrance_promo`: needs a promotion model (no product table, fixture form or tier says what is on promotion where or when). The brief and the eval both defer it.
- Also from the eval's "worth planting" list, not in scope here: a zone whose traffic rose week on week, a weekday pattern, one anomaly per store.

## Judgment calls and limits (read before relying on the key)
- **Window.** Each pattern holds from the first simulated day to the last (28 days here). The brief's "up to today" is Phase 3's: for a live run the config's `run.from` is set so the window ends on today's local date (R-E). `planted_from/to` are store-local dates.
- **Dwell per visit is nearly constant** across fixtures in the behavior model (25 to 80 s scaled by the square root of content appeal), so "high dwell, low visits" cannot be planted as such. The move_fast_seller key rests on visits and sales; the dwell columns are in the ledger.
- **Look-but-not-buy at the stock-out fixture.** With nothing on the shelf a shopper cannot pick anything up, so the fixture shows top-3 impressions and stock-out events, not pickups. The ledger's "engagement" there is stock-outs; the price fixture shows ordinary pickups (a pickup per impression) with near-zero sales. Gift shoppers still buy a little anywhere, so "near-zero" is under 15 % of the control's units per impression, not exactly zero (the price fixture sold 48 to 51 units against the control's 450).
- **Stock-out wording.** "Empty" is 2 units or fewer at the close of the day: a shopper's put-back can land a stray unit on a bare fixture. The stock-out fixture is bare on at least 90 % of days.
- **Capacity.** Planted stock never exceeds the Phase 1 capacity (a line's required quantity is its facings x units per facing, and every level is full). No lever raises a line above its unedited quantity; a swap keeps the slot's quantity. The ledger checks at every close that no planted slot holds more than its planned quantity, except a unit a shopper put back on a neighbouring slot the same day (the 2a misplacement). To make that hold, a planted store's staff take any surplus on a planted slot back to the rack before opening (move reason `capacity-return`), a quiet-hour top-up never fills a planted slot past its quantity, and the starting snapshot is capped at the edited plan. The unplanted baseline does overfill about 2 % of slot-closes (the Phase 1 starting position and stray returns); that is left alone, because it must not change.
- **Cover days** is store-wide on-hand (floor and back room) at close over the daily demand (units sold plus lost to an empty facing). Deliveries come in cases of 6 whenever a floor slot needs a unit, so A reaches under 2 days of cover through demand (4x) with a 3-unit floor, within capacity.
- **Thresholds are the eval's words, made numeric** (fast = at or above the store's 95th percentile of product velocity; top decile; bottom decile; 3x any other product of the department; zero sales for quiet stock; top 3 impressions among the merchandise fixtures, i.e. shelved and not a checkout counter). They are in `PlantVerify.kt`.
- Impressions and dwell here are the twin's truth ledger. What the platform holds (Denver only today, day-part windows) is the gap ledger's job (Phase 3), not this slice's.
- Planted products live on Gondola Back fixtures and wall bays because those hold a product once; a Gondola Front product is repeated on up to 26 fixtures, which would blur "the fixture it sits on".
