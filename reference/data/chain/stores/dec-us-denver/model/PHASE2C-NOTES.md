# Phase 2c notes: weekly planogram periods, resets and counts (offline)

Code: `src/main/kotlin/com/m8trx/twin/storeday/` (`StorePeriods.kt`, `PeriodTruth.kt` are new; `StoreDaySim.kt` carries the reset and the count). Same offline, record-only rules as 2a and 2b. `Knobs.periods=false` (`M8TRX_STORE_DAY_PERIODS=false`) reproduces the 2b day exactly (checked byte for byte on Denver's run summary).

## 1. Periods
- A period is Monday to Sunday, store-local. The `2026-11-02` week is the anchor and carries the Phase 1 plan unchanged; earlier dates use it too. Every later period is the previous one plus a seeded set of changes (`PeriodPlans`, seed = the store's behavior seed + store + period start), memoised, so any week can be generated on demand.
- Per department per week: **2 to 3 lines change facings**, at most **1 style swap** between neighbouring fixtures of the same department, and when the month changes the department the season favours most (StoryCalendar weights, at least +5 %) gets one more facing change and one **new style from the back of its range** in place of its slowest one.
- JUDGMENT, facings: the brief says 1 to 3 lines. Every level of every fixture is exactly full (0 slack on 475 of 523 Denver levels; the rest are empty), so a facing gained must be taken from another line of the same level: the smallest change is 2 lines. Defaults are therefore 2 to 3 (3 = one line gains two facings from two donors). `required_qty` scales with the facings (`round(req x new / old)`, at least 1).
- Period files: `model/periods/<yyyy-mm-dd>.json` holds the header (directive id, previous id, line count, total required, digest) and the list of changes; the plan itself regenerates from the seed. Only Denver's 4-week sample (2026-11-02 to 2026-11-23) is committed. A run writes its own periods under `out/day/<slug>/periods/<date>/` (`period.json`, `directive.json` = the exact payload, `compliance_truth.csv`); set `M8TRX_STORE_DAY_PERIODS_DIR` to also write the `<date>.json` files elsewhere (that is how the Denver sample was written). The self-test fails if the committed sample differs from what the seed generates.

## 2. The directive (Backend/Web A3, deployed 2026-11-17)
`PlanogramDirective` gained `expiryDate` (omitted when null, so every existing payload is unchanged). One directive per store and period: `external_directive_id` `PLNW-<slug>-<yyyymmdd>`, `effective_date` = the Monday, `expiry_date` = the next Monday, one target per plan line (Denver: 6,365) with `site_id` (`@site`, filled in by the live driver), `raw_fixture_code` = the **zone name** from the keys export (looked up by the line's zone id; the self-test checks every one is a keys name), `raw_item_identifier` = EAN, `required_quantity`, `position_sequence`, `facing_count`, `display_level` (the level number, 1 = top; the DTO's type). The old 28-line `curate()` slice is not used. The prefix is `PLNW-`, not the curated directives' `PLN-` (same slug and date shape): a re-send of one id replaces that directive's targets, so a period id must never be able to equal one the curated drive produces (the self-test asserts it).
Not proven live (Phase 3's job, on one fixture first): that a bare-day `effective_date` / `expiry_date` is read as the store's midnight on dev, and what the platform does with `display_level` as a number.

## 3. The reset
On a period's first open day (`StoreState.planPeriod` != the day's period; saved in the snapshot's `.meta`):
- the wire plan gets a `planogram` event (the directive payload) due 10 minutes before the first reset move or receipt;
- staff start `resetLeadMin` (180) before opening, i.e. 2 hours before the delivery lands. In that window: surplus of every slot whose requirement changed goes to a slot of the same EAN that now needs it (`reset-swap`, fixture to fixture) or to the back room (`reset-displace`, wire `to_zone_id: "@backroom_space"`, the receipt's placeholder), then slots that need more are filled from back-room stock the store already has (`reset-fill`). Throughput is the pre-open crew's (120 units per staff-hour);
- the delivery's order covers the NEW plan (a style new to the floor is ordered up to what the floor lacks, rounded up to a case); the ordinary pre-open stocking then fills what the truck brought;
- shoppers that day see the new plan (`ShelfPlan` per period).
Denver: 42 / 82 / 73 reset units in the three reset weeks. The self-test asserts every planned cell holds its EAN after the window or is a recorded stock-out (nothing anywhere), and that a dropped style is off its old fixture.

## 4. Weekly counts
Per store per week, 1/4 of the floor fixtures (`countShare`, a fixed seeded order; fixture i is in group `i mod 4`, week w counts group `w mod 4`), so every fixture is counted every 4 weeks, on a Tuesday to Thursday (next open day if closed), starting in the quietest hour after skipping the first two open hours (knob `countSkipOpenHours`) and leaving two hours before closing, fixtures read one after another (60 s + a quarter second a tag, 20 s walk between). Each fixture is read at its own moment: the units ON it then (a unit in a shopper's hand is not), through `StockCounts.truthPull` (a new builder reusing `Frame`/`Tag`/`FixturePull`, no missing or over injected; `StockDrive`'s send path is untouched). Wire events are kind `count`, payload `{step: open|pull|close, method, path, body}` with `StockDrive.openBody` / `pullBody` bodies (`@site` for the site; scope = the counted zone ids); pulls are due at their frame's end. Floor notes (observations) are not generated.
The first two open hours are skipped because the quietest hour is usually the opening hour, right after pre-open stocking, where the floor is at its best; counts see a floor shoppers have worked on.

## 5. Truth
`compliance_truth.csv` per period: `period_start, point, at_utc, fixture, ean, required_qty, on_hand, gap` (`gap = required - on_hand`, positive = short). Rows: every planned slot of each counted fixture (plus any other EAN read there) at the moment of the pull, and every slot at the period's end (the last open day's close). "Compliant" = on hand at least the requirement (strict); the report also gives lines at 60 % or more.
Truth of the wire: `onhand.csv` plan column now uses the period's requirement. Day `summary.json` gained `directive_issued`, `period_lines_changed`, `reset_units`, `counts`.

## 6. 28 days from 2026-11-02, all ten stores (default knobs)
See the PR body for the table; `out/day/CHAIN-RUN.md` regenerates it (`M8TRX_STORE_DAY_SITE=all ./gradlew storeDayRun`).

## 7. Not done / limits
- Not exercised against any server; the live issue, the A3 proof on one fixture and the live counts are Phase 3.
- Period plans do not feed `demand.csv` (a department's demand is the same whatever its facings); a new style takes its demand share from `demand.csv`.
- A swap moves two styles between fixtures of one department only; a style never changes department.
- Seasonal range changes bring in an off-floor style of the department (Denver has 89, mostly cycling); a store with none takes no range change.
