# Phase 2b notes: all ten stores, calendar overlays, misplacement realism (offline)

Code: `src/main/kotlin/com/m8trx/twin/storeday/` (`StoreDayCalendar.kt` is new). Same offline, record-only rules as 2a.

## 1. All ten stores
- `build_store_model.py --all` writes every store's `model/` and `reference/data/chain/STORE-MODEL-SUMMARY.md`. Smaller tiers needed: departments a store does not carry (no climb, snow, cycling in Bordeaux, Marseille, Busan), `water` (NYC, Paris) sized by its own demand like General, spillover from the most-pruned department into the lean departments' unused fixtures, and back-room racks that overflow their department's racks spilling to the least-loaded rack. Denver's files are byte-identical to #29 except one SUMMARY.md sentence.
- Behavior parameters: `scripts/build_store_behavior.py` writes nine NEUTRAL files (`reference/data/behavior/<slug>.json`): the six truths off (1.0), no hero styles, the store's timezone, seed = Denver's + serial index. Denver's tuned file is untouched.
- Hours and volume come from `profile.json` (`hoursOn`, `visitScale = location_factor x area / Denver area`). A store whose hours differ from the operating model's has the model's hourly shares laid over its hours and re-normalised.
- Calibration per store (`model/calibration.json`), fitted as in 2a but on a 56-day window (`M8TRX_STORE_DAY_CAL_DAYS=56`; Denver's committed file is the 2a 14-day fit). The trim stage keeps its best iterate and damps its step (exponent 0.8).
- Staff scale with the store: pre-open 3 and top-up 2 in Denver; fewer in smaller stores, never under two while the knob is two or more.
- EPC serial slices per store (EPCs are global and the same EAN sells everywhere): Denver keeps the 2a ranges; the other nine take initial `2e10 + i x 1e9` and deliveries `3e10 + i x 2e10`, `i` = 1..9 in slug order.
- Office sites are refused (no model directory, and `buildSim` rejects any slug containing `office`).
- Department-share checks compare against the demand of EANs a fixture holds. A small store cannot shelve its range (Bordeaux 735 of 1,800 EANs), and an off-floor EAN cannot sell.

## 2. Calendar overlays (`StoryCalendar` per store and date)
Expected visitors = weekday-or-weekend base x the behavior file's day-of-week factor (not on a holiday) x country seasonality x `visitMultiplier` (area, holiday, promo week, Korean pre-holiday surge, store story) x `location_factor`; the buy chance is multiplied by `conversionMultiplier` (promo, story). Closed holidays (French 1 Jan and 1 May, Seollal, Chuseok) are no store day: no delivery, staff or shoppers, an empty wire plan, summary `closed: true`. Open holidays take the weekend curve and the holiday's factor. `Knobs.calendar=false` (`M8TRX_STORE_DAY_CALENDAR=false`) reproduces the 2a day exactly (Denver: 13,174 units, 2,619 misplacements).

### Denver before / after (28 days from 2026-11-02, seed 20261104, misplacement 0.02 in both columns so only the calendar differs)
| | 2a (calendar off) | 2b (calendar on) |
|---|---:|---:|
| visitors | 28,405 | 29,984 |
| units sold | 13,174 | 14,449 |
| units by week | 3463 / 3518 / 3063 / 3130 | 3449 / 3671 / 3561 / 3768 |
| lost sales | 1.1 % | 1.3 % |
| department shares (climb, cycling, general, hike_camp, running, snow) | 2.7 / 0.9 / 15.3 / 36.0 / 17.4 / 27.7 % | 2.7 / 0.8 / 14.9 / 35.9 / 18.1 / 27.6 % |

The lift (+9.7 % units) is Denver's story (visits x1.03, conversion x1.06) and November's rise toward December (US seasonality 1.00 to 1.10) in place of the behavior model's synthetic six-week wave. The department mix does not move, and the fully stocked calibration is unaffected (it runs without overlays), so Denver's `calibration.json` is NOT re-fitted. Nothing was re-fit; the hold-out gate (`stocked ... within tolerance`) still passes at the same numbers.

## 3. Misplacement
Shoppers put back about ten units for every unit they buy (2a: 2,619 misplacements at 0.02 = about 131,000 put-backs against 13,174 units sold). The per-put-back probability that gives about 5 % of units sold is **0.005** (the new `Knobs.misplaceProb` default; still a knob, `M8TRX_STORE_DAY_MISPLACE`). Over the ten 28-day runs it lands at 4.3 to 6.8 % of units sold (the ratio of put-backs to sales differs a little by store).

## 4. Tolerances
The Poisson widening for tiny departments is three standard deviations (was two): with ten stores and ~45 department gates, two sigma fails by chance. A department under 2 % of units may also pass within 0.5 percentage points (Paris cycling is lumpy: one seed gave 1.1 % against 0.7 %, other seeds 0.8 to 0.9 %).

## 5. Not done / limits
- `person_samples_ref` and `samples.jsonl` stay opt-in; no non-Denver store has an edge.
- Per-store fixture-level error is as loose as Denver's (about 25 to 30 % on busy fixtures).
- The StoryCalendar holiday table is the 2026 one; 2027 dates get seasonality and story but no holidays.
