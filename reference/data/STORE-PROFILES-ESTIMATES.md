# Store profile estimates (D3)

**Status: estimates, set by the twin lane on Bob's instruction, 2026-11-17. Replace with real data when a customer supplies it.**
Source string in `reference/data/store-profiles.json`: "twin estimate, Bob 2026-11-17: best guess from normal retail practice per city and store size". `provisional` is now `false`; that means "decided", not "measured".

## The numbers

| store | city (tz) | hours (store-local) | h/week | area m2 | location factor | nominal volume vs Denver (location x area) | simulated units/week | sim vs Denver | delivery lead |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| dec-us-nyc | America/New_York | Mon-Sat 10:00-21:00, Sun 11:00-20:00 | 75 | 636 | 1.80 | 1.75x | 5906 | 1.65x | 60 min |
| dec-fr-paris | Europe/Paris | Mon-Sat 09:30-20:30, Sun 10:00-19:00 | 75 | 588 | 1.60 | 1.44x | 4969 | 1.39x | 60 min |
| dec-kr-seoul | Asia/Seoul | every day 10:30-22:00 | 80.5 | 493.5 | 1.50 | 1.13x | 3729 | 1.04x | 60 min |
| dec-us-sf | America/Los_Angeles | Mon-Sat 10:00-20:00, Sun 11:00-19:00 | 68 | 662.5 | 1.10 | 1.11x | 3876 | 1.09x | 60 min |
| dec-us-denver | America/Denver | Mon-Sat 10:00-21:00, Sun 11:00-18:00 | 73 | 655.5 | 1.00 | 1.00x | 3570 | 1.00x | 60 min |
| dec-fr-lyon | Europe/Paris | Mon-Sat 09:30-20:00, Sun closed | 63 | 441 | 1.10 | 0.74x | 1920 | 0.54x | 30 min |
| dec-fr-lille | Europe/Paris | Mon-Sat 09:30-20:00, Sun closed | 63 | 483 | 1.00 | 0.74x | 2189 | 0.61x | 60 min |
| dec-kr-busan | Asia/Seoul | every day 10:30-21:30 | 77 | 270 | 1.10 | 0.45x | 1049 | 0.29x | 30 min |
| dec-fr-bordeaux | Europe/Paris | Mon-Sat 09:30-20:00, Sun closed | 63 | 315 | 0.90 | 0.43x | 994 | 0.28x | 30 min |
| dec-fr-marseille | Europe/Paris | Mon-Sat 09:30-20:00, Sun closed | 63 | 313.5 | 0.85 | 0.41x | 836 | 0.23x | 30 min |

- **Hours** are store-local per weekday. "Sun closed" is a closed weekday: it behaves like a closed holiday (no visitors, no sales, no delivery, empty wire plan).
- **Location factor** is footfall per m2 against Denver (1.0). Nominal volume vs Denver = location factor x area / Denver's 655.5 m2 (this is what `demand.csv` and the visit scale use). "Simulated" is units sold per week in a 28-day run from 2026-11-02 (all ten stores, default knobs), which also reflects open days and hours (a Sunday-closed store sells six days; Denver's Sunday is a 7-hour day), the Saturday lift, stock-outs and the off-floor share of a small store's range. Simulated ordering: NYC > Paris > SF ~ Seoul > Denver > Lille > Lyon > Busan ~ Bordeaux > Marseille (Lyon and Lille are equal nominally, 0.74x; Lille simulates higher).
- **Delivery lead**: 60 min if area >= 450 m2, else 30 (rule in `chain_default.delivery_lead_rule`, threshold lowered from 500 to 450 in this change). Lille (483) and Seoul (493.5) move to 60; Lyon (441), Bordeaux, Marseille and Busan stay at 30. The delivery lands at that day's own opening minus the lead, so on a half-hour opening it lands on the half hour.
- **Saturday lift**: where Sunday is closed, Saturday visitors x 1.10 (`closed_sunday_saturday_lift` in the chain defaults; resolved per store as `saturday_lift`).

## Reasoning (normal retail practice, not data)

- **US.** NYC, Denver and SF are mall/street stores open seven days. NYC and Denver close at 21:00 (Denver's Sunday shortened to 11:00-18:00, a Denver norm); SF closes an hour earlier and keeps a short Sunday. NYC has the highest footfall per m2 of the chain (1.8: dense, tourist and commuter traffic), SF is above Denver (1.1), Denver is the reference (1.0).
- **France.** Sunday trading is restricted by law: large shops are normally closed on Sunday, so Lyon, Lille, Bordeaux and Marseille close Sunday (Saturday takes a 10 % lift because Sunday's shoppers move to it). Paris is the exception: it sits in a tourist zone where Sunday opening is allowed, so it opens 10:00-19:00 and runs the long 09:30-20:30 weekday. The other four open 09:30-20:00. Paris 1.6 (tourist and office footfall), Lyon 1.1, Lille 1.0, Bordeaux 0.9, Marseille 0.85 (the chain's Marseille story already has it below plan).
- **Korea.** Shopping hours run late and every day: Seoul 10:30-22:00 and Busan 10:30-21:30, no closed day. Seoul 1.5 (dense catchment), Busan 1.1.
- **Lead times** follow store size: a larger floor takes a bigger delivery, so more time to put it away before opening.

## What the code does with them

- The operating model's 11-hour weekday and weekend hourly curves are stretched or compressed onto each day's actual window, keeping the shape (morning ramp, midday and afternoon peaks, evening tail) and the day's total. An 11-hour window is the model's own curve unchanged. A window that is not a whole number of hours has a partial last bucket; arrivals, quiet-hour top-up walks (full buckets only) and the weekly count all work from that day's own opening.
- Daily visitor totals depend only on day type, the calendar and the store's volume factors, not on the window length (asserted in `storeProfilesSelfTest`).
- Staff scale up with footfall where the location factor is above 1 (factor^1.5): NYC 7 pre-open stockers, Paris 5, Seoul 4. Without it the three busiest stores' floors ran below the self-test's 85 % close band.

## Discontinuity

The January to October 2026 history (`historyGenerate`) used 10:00-21:00 and a location factor of 1.0 everywhere, seven days a week. From this change the store-day simulation uses the table above, so a series that spans the cut shows a step in opening hours, Sunday closures for four French stores and the volume ratios. The history itself is unchanged.
