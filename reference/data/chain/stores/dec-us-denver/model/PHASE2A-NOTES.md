# Phase 2a notes: the stock-aware Denver store-day (offline)

Code: `src/main/kotlin/com/m8trx/twin/storeday/`. Tasks: `storeDayRun`, `storeDayCalibrate`, `storeDaySelfTest` (wired into `offlineSelfTest`). Offline and record-only: nothing in the package opens a connection (the self-test scans for it). Output goes under `out/day/<slug>/` (git-ignored).

## What a day does
1. **Delivery** at open minus `delivery_lead_min` (10:00 - 60 min = 09:00 local): one or more `receive` wire events (at most 200 EPCs each, 2 s apart). Each EAN is ordered back to its stock-plan back-room cover after the floor's deficit is taken out of it, rounded to the nearest case (6; 1 for bike-priced lines, price >= $500). That is "yesterday's units sold plus the back-room shortfall" in one rule.
2. **Pre-open stocking**, 09:06 to 09:59: strays (misplaced put-backs) go home first, then every slot below its `required_qty`, emptiest first, fastest-selling first. Throughput = staff x 120 units/h; staff = the operating model's off-peak associates on the floor (3), so at most 318 units a day.
3. **Open hours:** the behavior model's own shopper agents (arrivals, stops, dwell, try-on, checkout) run against the real units on the fixtures. A pickup takes a unit of that (fixture, EAN); an empty slot is a stock-out (the shopper looks but takes nothing; if they would have bought, it is a lost sale). A sale is the specific EPC taken from that fixture. A put-back stays on its fixture, or with probability `misplace` (2 %) goes to one of the three nearest shelved fixtures (a `move` on the wire, due at the put-back; the unit is held in transit until then).
4. **Quiet-hour top-ups:** the 3 lowest hours of the day's hourly curve; at 5 minutes past the hour 2 staff walk the slots below 60 % of `required_qty` and move units up from the back room, one `move` per (fixture, EAN) batch, at most 45 minutes of throughput.
5. Strays are returned in the next morning's stocking pass.

## Wire plan (`out/day/<slug>/<date>/wire-plan.jsonl`)
`{due_utc, kind, payload, ids}`, ordered by `due_utc`, ties receive < move < sale < person_samples_ref. `receive` carries `siteId:"@site"`, `spaceId:"@backroom_space"` placeholders for Phase 3 to resolve. A sale's `occurred_at` is the shopper's exit; receipts and moves carry no event time, so `due_utc` is their time. **Ordering rule** (Backend/Web A1): a unit is only ever taken from a fixture it has already been moved to (staff moves are applied to the floor at their due time, shoppers see only what has landed by their arrival, and a put-back that moves a unit holds it in transit until the move is due), so each unit's last move is due before its sale and nothing moves it afterwards. The self-test replays the 28-day files and checks this.

## Truth ledger (`.../truth/`)
`fixture_hour.csv` (impressions, dwell s, pickups, put-backs, try-ons, units, value, stock-outs, lost sales, lost value), `fixture_product_day.csv`, `onhand.csv` (plan / open / close per fixture x EAN, strays included with plan 0), `flows.csv` (zone to zone per hour, `DOOR` at both ends), `summary.json`.

## Serial ranges
chain data 1,000..9,999,999; `ReceiveStock` block 1e9..1.1e10; **offline initial snapshot 2e10 + n (stock-plan row order)**; **store-day deliveries 3e10 upward** (counter carried in the snapshot `.meta`); the 38-bit serial field ends at 2.75e11.

## Calibration (`calibration.json`, `storeDayCalibrate`)
Left alone the behavior model sells by SPACE (each department has about 16 fixtures), not by demand. Two multipliers, applied only inside a store-day run through `FloorHooks`, fix that without touching Denver's tuned behavior file: a **stop scale per fixture** (a fixture's pull on shoppers; median pinned to 1) and a **buy scale per department**. Cells are picked by their `demand.csv` share (spread over a style's repeat blocks by facings) instead of the flat facings x price weight. Fitted on 14 fully stocked days, gated on a 28-day hold-out (`out/day/dec-us-denver/CALIBRATION.md` has both): every department within 10 % of `demand.csv` except tiny cycling (about 100 units in 28 days), which is held to two Poisson standard deviations.

Known limits: per-fixture sales follow demand only roughly (mean error about 29 % on fixtures with at least 5 target units in 14 days); per-EAN rank correlation 0.71. The 89 off-floor EANs (0.5 % of demand) cannot sell from a fixture. The StoryCalendar overlays (holidays, promo weeks, store story) are not applied in 2a: traffic is the behavior model's own day-of-week x weekly seasonality.

## Not done here
All ten stores (2b), weekly planogram issue and counts (2c), planted scenarios (2d). `person_samples_ref` events and `samples.jsonl` exist only with `M8TRX_STORE_DAY_SAMPLES=true` (about 39 MB of position samples for one weekday at 5 Hz; a month is about 1 GB).
