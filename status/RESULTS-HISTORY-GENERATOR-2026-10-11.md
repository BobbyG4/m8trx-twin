# RESULTS — Twin history generator (BRIEF-TWIN-HISTORY-GENERATOR-2026-10-11)

**Lane:** Twin · **Branch:** `feat/history-generator-2026-10` (pushed, no PR, not merged) · **Generator commit that produced the files:** `e423873` (fix round 1 on top of `76b8308`; the files' MANIFEST `generator_git_sha` is `e42387326f05d6bea39a79ec291d7f78b340d2f9`) · **Seed:** 20261016 · **Date:** 2026-10-11.
**Posture:** offline only. No mother writes, no core repo touched, no psql, no Hasura admin, nothing published. Alarms are a file only (sending is a later round).

## 1. What was built

| Piece | Where |
|---|---|
| `./gradlew historyGenerate`: the eleven-file contract (§ 2). Env `M8TRX_HISTORY_SEED` (20261016) · `M8TRX_HISTORY_FROM` (2026-01-01) · `M8TRX_HISTORY_TO` (2026-10-04) · `M8TRX_HISTORY_OUT` (out/history-2026-10) · `M8TRX_HISTORY_STORES` · `M8TRX_HISTORY_THREADS` | `src/main/kotlin/com/m8trx/twin/history/HistoryGenerate.kt`, `HistoryGenerator.kt` |
| One store-day runs on the existing stack: `TrafficGenerator` arrivals off the model's hourly curves → `Journeys` (browse / buy / try-on / **Shoplift**) → 5 Hz dwell → `ImpressionOracle`. A streaming sink runs the oracle per shopper on eviction. That gives the same verdict as `ScenarioRun` while holding one session in memory. Sales are the journeys' `SaleCompleted` baskets (real SKUs, `price_usd`). Alarms are `EasGateCrossed` with `shouldAlarm` | `HistoryDayRunner.kt` |
| The year's story in one file: seasonality, promos, holidays, store stories, category seasonality and the slow category | `StoryCalendar.kt` |
| Compliance, tasks (derived from the day's real sales and alarms), alarms, stocktakes, KPI targets | `DerivedHistory.kt` |
| Keys come ONLY from the Coordinator's export, copied to `reference/data/chain/keys-2026-10-11.csv` | `ChainKeys.kt` |
| `./gradlew historySelfTest` (the § 3 self-tests plus the story beats) and `./gradlew offlineSelfTest` (the whole offline suite: scenario · people · connect · history) | `HistorySelfTest.kt`, `HistoryValidator.kt`, `StoryChecks.kt`, `build.gradle.kts` |
| `scripts/keys_check.py`: writes every key used to `<out>/keys_used.csv` and checks each one against the export | `scripts/keys_check.py` |
| `StoreCatalog` gets optional department acceptance weights. They are off by default (no extra RNG draws), so `scenarioSelfTest` still passes 37/37 | `layer2/Journeys.kt` |

## 2. Output

**Directory:** `/Users/bob/IdeaProjects/m8trx-twin/out/history-2026-10/`, **24 MB**. It is git-ignored (`out/`) and can be regenerated from seed + commit. The same directory also holds `keys_used.csv`, the keys_check dump. It is not part of the contract or the MANIFEST, and mig 259 does not read it, so leave it out of the copy to mother.
**Wall time:** **27m54s** for the full year (2,753 store-days, 14 threads, MacBook M4) on `e423873`. A first full run on the fix round's first commit (`2a2e601`) was discarded: it left an ATV drift of up to +5.1 % in a four-week window and one alarm (Lille) still stamped after close, both fixed in `e423873`.

```
file,rows,sha256,seed,generated_at,generator_git_sha
commerce_store_hourly.csv,30283,dbee4b5537966692a8f9acc0edf9ec10c9c54eccb58b56a7a84b4223ac90635a,20261016,2026-10-11T22:28:41+09:00,e42387326f05d6bea39a79ec291d7f78b340d2f9
commerce_department_daily.csv,13219,6391360c48244a9283b65ca540b558f2079452af83a00417224683ce5090dc82,20261016,2026-10-11T22:28:41+09:00,e42387326f05d6bea39a79ec291d7f78b340d2f9
traffic_store_hourly.csv,30283,7b32113b0f387c2d48635412e6b974291a9c0e3781a1106a9e6115df609e9425,20261016,2026-10-11T22:28:41+09:00,e42387326f05d6bea39a79ec291d7f78b340d2f9
traffic_department_daily.csv,18725,5ea3b907577156e595d96db16913dff31d91142136d3512c18e6ce716f0cc386,20261016,2026-10-11T22:28:41+09:00,e42387326f05d6bea39a79ec291d7f78b340d2f9
behavioral_fixture_daily.csv,191710,3b4ddf1d01a747e0667f567962b18326c46f78c4261d2b390cd87941f5f20e83,20261016,2026-10-11T22:28:41+09:00,e42387326f05d6bea39a79ec291d7f78b340d2f9
compliance_weekly.csv,390,74392d4f86d76f6b78dc3e4cec52c39a9428e3f858b09fb286a7ca526781bc3e,20261016,2026-10-11T22:28:41+09:00,e42387326f05d6bea39a79ec291d7f78b340d2f9
tasks.csv,13388,a40d72cb58f3391abc92334b58eac47a4234dd2269dcf1df64bd67fc5a032729,20261016,2026-10-11T22:28:41+09:00,e42387326f05d6bea39a79ec291d7f78b340d2f9
alarms.csv,1047,3f1288781eac44471e4ac40ee8eba113b759e773582ef0cb320b25ecdc3fa564,20261016,2026-10-11T22:28:41+09:00,e42387326f05d6bea39a79ec291d7f78b340d2f9
stocktakes.csv,90,1f1f6b84d67b7932c08758b64dbcb4e7d1f03d7b0b363de57d3146da45cb3b5d,20261016,2026-10-11T22:28:41+09:00,e42387326f05d6bea39a79ec291d7f78b340d2f9
kpi_targets.csv,30,2c13563e6858dbddd9e910e0d929e2232eadf6baaee63cdca01e6b2942d6746a,20261016,2026-10-11T22:28:41+09:00,e42387326f05d6bea39a79ec291d7f78b340d2f9
```

## 3. Per-store totals (Jan 1 → Oct 4; US stores end Oct 3, see § 5.1)

| store | days | visits | transactions | conversion | units | sales USD | ATV | compliance mean | Δ visits | Δ txn | Δ units | Δ sales | sales vs plan |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| dec-fr-bordeaux | 275 | 136,506 | 29,916 | 0.219 | 65,924 | 1,749,756 | 58.49 | 87.0 | -0.3% | -0.4% | -0.3% | +0.4% | -1.7% |
| dec-fr-lille | 275 | 199,331 | 41,726 | 0.209 | 91,679 | 2,416,628 | 57.92 | 87.1 | -0.3% | -0.2% | -0.3% | -0.3% | -11.5% |
| dec-fr-lyon | 275 | 192,572 | 43,810 | 0.227 | 96,467 | 2,574,705 | 58.77 | 91.2 | +0.4% | +0.1% | +0.1% | +1.4% | +3.3% |
| dec-fr-marseille | 275 | 122,505 | 23,610 | 0.193 | 51,856 | 1,357,854 | 57.51 | 74.3 | -0.2% | -0.1% | -0.3% | -1.0% | -23.4% |
| dec-fr-paris | 275 | 262,509 | 58,695 | 0.224 | 129,236 | 3,370,514 | 57.42 | 90.4 | +0.3% | +0.1% | +0.2% | -0.9% | +1.4% |
| dec-kr-busan | 275 | 119,297 | 26,757 | 0.224 | 59,009 | 1,542,348 | 57.64 | 89.8 | -0.2% | +0.1% | +0.3% | -0.5% | -3.3% |
| dec-kr-seoul | 275 | 226,057 | 50,636 | 0.224 | 111,393 | 2,960,411 | 58.46 | 93.2 | +0.1% | +0.3% | +0.3% | +1.1% | +1.2% |
| dec-us-denver | 276 | 305,193 | 73,126 | 0.240 | 161,190 | 4,205,624 | 57.51 | 92.0 | +0.3% | +1.3% | +1.5% | +0.4% | +9.4% |
| dec-us-nyc | 276 | 285,985 | 64,640 | 0.226 | 142,007 | 3,730,283 | 57.71 | 89.8 | +0.1% | +1.0% | +0.9% | +0.5% | +0.0% |
| dec-us-sf | 276 | 297,646 | 66,445 | 0.223 | 146,265 | 3,820,417 | 57.50 | 88.9 | -0.1% | -0.3% | -0.3% | -1.2% | -1.6% |
| **chain** | 2753 | 2,147,601 | 479,361 | 0.223 | 1,055,026 | 27,728,541 | | | | | | | |

Δ = generated vs the operating model's expectation for the generated days (model volumes × seasonality × story × weather; units at `units_per_txn` 2.2, sales at `atv_usd` $58). **"±5 % of the operating model" is measured against the STORIED plan, i.e. the Δ columns.** "Sales vs plan" is vs the KPI plan, which is the model WITHOUT the store's story, so it shows how far each story moves a store off the plan the dashboards compare against. Every store's Δ sales is inside ±1.4 %; the chain total is $27.73 M.

How the story beats read in the files (`historySelfTest` [3] asserts all of them):
- **Marseille:** −23.4 % vs plan for the year (the "−20 % class") and below plan every quarter. Compliance mean 74.3.
- **Lille:** −11.5 % for the year. Below plan in H1, above plan by September. Compliance went from 82 to 90 across the turnaround.
- **Denver:** the best US store, $4.21 M against SF $3.82 M and NYC $3.73 M, +9.4 % vs plan.
- **NYC and SF read at plan** (+0.0 % and −1.6 %). Marseille and Lille are the two below-plan stories; before this round four stores sat 8–23 % under plan.
- **Slow category:** Climbing & Mountaineering is the lowest department in absolute sales, chain-wide and in Denver (§ 5.12 for the Watersports caveat).
- **Seasonal categories:** snow sells in winter.
- **Promos:** three promo weeks (+35 % visits, +20 % conversion). Each is followed by a compliance dip and remediation tasks.
- **August:** the French five are down about 19 % against July.
- **Korea:** Seoul shows a pre-Chuseok surge. Chuseok and Seollal days are closed.
- **Year shape:** a January trough rising to a July peak.
- **KPI targets are flat per (site, metric)**: `kpi_target` holds one row per site and metric, so "vs plan" on a dashboard reads seasonal, red in the January trough and green in the summer peak. The demo script can say "vs plan reads seasonal" and show October, where the level and the target agree.

### 3.1 Department share of sales (Jan 1 → Oct 4)

Denver (reference store, six departments), $4.21 M:

| department | sales USD | share |
|---|---:|---:|
| Hiking, Trekking & Camping | 1,508,174 | 35.9 % |
| Running & Trail | 925,050 | 22.0 % |
| Cycling | 818,774 | 19.5 % |
| Snow & Ski | 382,694 | 9.1 % |
| General / Multi-sport | 376,582 | 9.0 % |
| Climbing & Mountaineering | 194,350 | 4.6 % |

Chain, $27.73 M:

| department | sales USD | share |
|---|---:|---:|
| Hiking, Trekking & Camping | 10,374,770 | 37.4 % |
| Running & Trail | 6,301,646 | 22.7 % |
| General / Multi-sport | 5,025,867 | 18.1 % |
| Cycling | 2,827,823 | 10.2 % |
| Snow & Ski | 1,887,572 | 6.8 % |
| Climbing & Mountaineering | 1,151,795 | 4.2 % |
| Watersports | 159,067 | 0.6 % |

Per store (% of the store's sales; – = the store carries no such department):

| store | Climbing | Cycling | General | Hiking & Camping | Running | Snow | Water |
|---|---:|---:|---:|---:|---:|---:|---:|
| dec-fr-bordeaux | – | – | 31.3 | 34.2 | 34.5 | – | – |
| dec-fr-lille | 5.1 | – | 29.2 | 35.3 | 30.3 | – | – |
| dec-fr-lyon | 4.4 | – | 23.8 | 37.3 | 25.0 | 9.5 | – |
| dec-fr-marseille | – | – | 31.9 | 34.5 | 33.6 | – | – |
| dec-fr-paris | 5.9 | 17.8 | 7.9 | 38.5 | 18.9 | 8.8 | 2.2 |
| dec-kr-busan | – | – | 49.8 | 50.2 | – | – | – |
| dec-kr-seoul | 4.4 | – | 23.0 | 38.0 | 24.9 | 9.8 | – |
| dec-us-denver | 4.6 | 19.5 | 9.0 | 35.9 | 22.0 | 9.1 | – |
| dec-us-nyc | 5.9 | 17.8 | 8.1 | 38.2 | 18.9 | 8.8 | 2.3 |
| dec-us-sf | 4.6 | 19.5 | 8.8 | 35.7 | 22.5 | 9.0 | – |

## 4. Self-test output (verbatim)

`./gradlew ktlintCheck --rerun-tasks` → exit 0 (run on `e423873`).
`./gradlew scenarioSelfTest peopleSelfTest connectSelfTest historySelfTest`:

```
c.m8trx.twin.layer3.ScenarioSelfTest - scenarioSelfTest — 37 passed, 0 failed
c.m8trx.twin.layer1.PeopleSelfTest - peopleSelfTest — 50 passed, 0 failed
c.m8trx.twin.connect.ConnectHarness - === ALL OFFLINE SELF-TESTS PASSED ===
BUILD SUCCESSFUL in 4m 48s
```

`python3 scripts/keys_check.py out/history-2026-10`:

```
keys_check: out/history-2026-10 against reference/data/chain/keys-2026-10-11.csv
  alarm_sku                 826 distinct    0 unresolved
  department_zone_name       68 distinct    0 unresolved
  fixture_code              696 distinct    0 unresolved
  site_slug                  10 distinct    0 unresolved
  task_zone_key             583 distinct    0 unresolved
  dump → out/history-2026-10/keys_used.csv
RESULT: ALL KEYS RESOLVE
```

<details><summary>historySelfTest, every line (311 checks)</summary>

```
[1] four weeks, all ten stores — 2026-09-07 .. 2026-10-04
22:57:40.065 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-fr-bordeaux sampler targets: units 2.160 · line price $23.37 (model 2.2 · $26.4; tagged SKUs 188)
22:57:40.066 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-fr-lille sampler targets: units 2.160 · line price $24.16 (model 2.2 · $26.4; tagged SKUs 238)
22:57:40.066 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-fr-lyon sampler targets: units 2.160 · line price $23.99 (model 2.2 · $26.4; tagged SKUs 263)
22:57:40.067 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-fr-marseille sampler targets: units 2.160 · line price $24.13 (model 2.2 · $26.4; tagged SKUs 155)
22:57:40.067 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-fr-paris sampler targets: units 2.160 · line price $23.09 (model 2.2 · $26.4; tagged SKUs 271)
22:57:40.067 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-kr-busan sampler targets: units 2.160 · line price $24.25 (model 2.2 · $26.4; tagged SKUs 162)
22:57:40.068 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-kr-seoul sampler targets: units 2.160 · line price $23.87 (model 2.2 · $26.4; tagged SKUs 263)
22:57:40.068 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-us-denver sampler targets: units 2.160 · line price $22.69 (model 2.2 · $26.4; tagged SKUs 271)
22:57:40.068 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-us-nyc sampler targets: units 2.160 · line price $23.09 (model 2.2 · $26.4; tagged SKUs 271)
22:57:40.068 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-us-sf sampler targets: units 2.160 · line price $22.69 (model 2.2 · $26.4; tagged SKUs 271)
22:57:40.071 [main] INFO  c.m.twin.history.HistoryGenerator - history: 275 store-days across 10 stores, 2026-09-07..2026-10-04 (cutoff 2026-10-05T00:00:00Z), seed 20261016, 14 threads
22:57:40.095 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 390 arrivals for 2026-09-17 (WEEKDAY, scale=0.42842498302901305) across 11 open hours
22:57:40.095 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 525 arrivals for 2026-09-15 (WEEKDAY, scale=0.5048947981919336) across 11 open hours
22:57:40.095 [pool-1-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 457 arrivals for 2026-09-18 (WEEKDAY, scale=0.495249748812122) across 11 open hours
22:57:40.096 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 439 arrivals for 2026-09-14 (WEEKDAY, scale=0.4973087739337185) across 11 open hours
22:57:40.096 [pool-1-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 581 arrivals for 2026-09-07 (WEEKDAY, scale=0.6179977337151101) across 11 open hours
22:57:40.099 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 543 arrivals for 2026-09-09 (WEEKDAY, scale=0.6472523457614162) across 11 open hours
22:57:40.100 [pool-1-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 764 arrivals for 2026-09-19 (WEEKEND, scale=0.515821744509292) across 11 open hours
22:57:40.101 [pool-1-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 580 arrivals for 2026-09-08 (WEEKDAY, scale=0.6497041939052506) across 11 open hours
22:57:40.101 [pool-1-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 480 arrivals for 2026-09-16 (WEEKDAY, scale=0.5281178592985158) across 11 open hours
22:57:40.101 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 758 arrivals for 2026-09-20 (WEEKEND, scale=0.49780232593241164) across 11 open hours
22:57:40.101 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 529 arrivals for 2026-09-10 (WEEKDAY, scale=0.5947407675049033) across 11 open hours
22:57:40.101 [pool-1-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 570 arrivals for 2026-09-11 (WEEKDAY, scale=0.6390321082494317) across 11 open hours
22:57:40.103 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 909 arrivals for 2026-09-12 (WEEKEND, scale=0.5847921316874779) across 11 open hours
22:57:40.105 [pool-1-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1078 arrivals for 2026-09-13 (WEEKEND, scale=0.6813134712577832) across 11 open hours
22:57:41.659 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 434 arrivals for 2026-09-21 (WEEKDAY, scale=0.48240505648368176) across 11 open hours
22:57:41.781 [pool-1-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 390 arrivals for 2026-09-22 (WEEKDAY, scale=0.4080519978058331) across 11 open hours
22:57:41.830 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 467 arrivals for 2026-09-23 (WEEKDAY, scale=0.5053667256926987) across 11 open hours
22:57:42.011 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 459 arrivals for 2026-09-24 (WEEKDAY, scale=0.449819003308032) across 11 open hours
22:57:42.038 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 445 arrivals for 2026-09-25 (WEEKDAY, scale=0.48421677093798304) across 11 open hours
22:57:42.052 [pool-1-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 718 arrivals for 2026-09-26 (WEEKEND, scale=0.47547163433201095) across 11 open hours
22:57:42.105 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 732 arrivals for 2026-09-27 (WEEKEND, scale=0.45861977425436895) across 11 open hours
22:57:42.126 [pool-1-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 432 arrivals for 2026-09-28 (WEEKDAY, scale=0.46325362132963627) across 11 open hours
22:57:42.164 [pool-1-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 442 arrivals for 2026-09-29 (WEEKDAY, scale=0.4867809615604018) across 11 open hours
22:57:42.244 [pool-1-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 394 arrivals for 2026-09-30 (WEEKDAY, scale=0.4687045819721649) across 11 open hours
22:57:42.878 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 404 arrivals for 2026-10-01 (WEEKDAY, scale=0.4398050524975635) across 11 open hours
22:57:42.949 [pool-1-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 433 arrivals for 2026-10-02 (WEEKDAY, scale=0.49272966452844635) across 11 open hours
22:57:43.118 [pool-1-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 796 arrivals for 2026-10-03 (WEEKEND, scale=0.5483461957400712) across 11 open hours
22:57:43.133 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 853 arrivals for 2026-10-04 (WEEKEND, scale=0.5215792007404756) across 11 open hours
22:57:43.381 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 964 arrivals for 2026-09-07 (WEEKDAY, scale=1.099228563273215) across 11 open hours
22:57:43.421 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 886 arrivals for 2026-09-08 (WEEKDAY, scale=1.0413136177803575) across 11 open hours
22:57:43.546 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 953 arrivals for 2026-09-09 (WEEKDAY, scale=1.0495678897712981) across 11 open hours
22:57:43.578 [pool-1-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 945 arrivals for 2026-09-10 (WEEKDAY, scale=1.0756485831744973) across 11 open hours
22:57:43.591 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 904 arrivals for 2026-09-11 (WEEKDAY, scale=1.0432036845280026) across 11 open hours
22:57:43.621 [pool-1-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1556 arrivals for 2026-09-12 (WEEKEND, scale=0.9818039611357444) across 11 open hours
22:57:43.689 [pool-1-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1668 arrivals for 2026-09-13 (WEEKEND, scale=1.0376904578331017) across 11 open hours
22:57:43.742 [pool-1-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 714 arrivals for 2026-09-14 (WEEKDAY, scale=0.7575931892107933) across 11 open hours
22:57:44.192 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 789 arrivals for 2026-09-15 (WEEKDAY, scale=0.8126041365909682) across 11 open hours
22:57:44.431 [pool-1-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 758 arrivals for 2026-09-16 (WEEKDAY, scale=0.8037698980623793) across 11 open hours
22:57:44.569 [pool-1-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 736 arrivals for 2026-09-17 (WEEKDAY, scale=0.8068591868730055) across 11 open hours
22:57:44.630 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 809 arrivals for 2026-09-18 (WEEKDAY, scale=0.8321573343959279) across 11 open hours
22:57:45.878 [pool-1-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1226 arrivals for 2026-09-19 (WEEKEND, scale=0.7457151227859116) across 11 open hours
22:57:46.055 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1117 arrivals for 2026-09-20 (WEEKEND, scale=0.7295914695007426) across 11 open hours
22:57:48.017 [pool-1-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 774 arrivals for 2026-09-21 (WEEKDAY, scale=0.8205792403827937) across 11 open hours
22:57:48.633 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 623 arrivals for 2026-09-22 (WEEKDAY, scale=0.714342949536872) across 11 open hours
22:57:48.695 [pool-1-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 629 arrivals for 2026-09-23 (WEEKDAY, scale=0.6827962348332133) across 11 open hours
22:57:48.714 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 634 arrivals for 2026-09-24 (WEEKDAY, scale=0.7004072973153078) across 11 open hours
22:57:48.742 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 738 arrivals for 2026-09-25 (WEEKDAY, scale=0.8092715980483258) across 11 open hours
22:57:48.750 [pool-1-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1199 arrivals for 2026-09-26 (WEEKEND, scale=0.7935988497285072) across 11 open hours
22:57:48.804 [pool-1-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1165 arrivals for 2026-09-27 (WEEKEND, scale=0.7338103677918685) across 11 open hours
22:57:48.838 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 661 arrivals for 2026-09-28 (WEEKDAY, scale=0.7483425207459482) across 11 open hours
22:57:49.006 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 671 arrivals for 2026-09-29 (WEEKDAY, scale=0.7982895879048645) across 11 open hours
22:57:49.823 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 653 arrivals for 2026-09-30 (WEEKDAY, scale=0.783484683288927) across 11 open hours
22:57:52.333 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 725 arrivals for 2026-10-01 (WEEKDAY, scale=0.788295434607849) across 11 open hours
22:57:52.367 [pool-1-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 638 arrivals for 2026-10-02 (WEEKDAY, scale=0.6999329256111798) across 11 open hours
22:57:52.439 [pool-1-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1082 arrivals for 2026-10-03 (WEEKEND, scale=0.7221408165966232) across 11 open hours
22:57:52.451 [pool-1-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1175 arrivals for 2026-10-04 (WEEKEND, scale=0.7911409739180916) across 11 open hours
22:57:52.583 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 729 arrivals for 2026-09-07 (WEEKDAY, scale=0.8308971040758354) across 11 open hours
22:57:52.588 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 860 arrivals for 2026-09-08 (WEEKDAY, scale=0.9473453898087175) across 11 open hours
22:57:52.750 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 838 arrivals for 2026-09-09 (WEEKDAY, scale=0.9674482678380809) across 11 open hours
22:57:53.007 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 728 arrivals for 2026-09-10 (WEEKDAY, scale=0.8413613079238468) across 11 open hours
22:57:53.011 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 736 arrivals for 2026-09-11 (WEEKDAY, scale=0.8644784987656079) across 11 open hours
22:57:53.024 [pool-1-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1253 arrivals for 2026-09-12 (WEEKEND, scale=0.8235348016436644) across 11 open hours
22:57:53.416 [pool-1-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1235 arrivals for 2026-09-13 (WEEKEND, scale=0.7865329342279351) across 11 open hours
22:57:53.674 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 593 arrivals for 2026-09-14 (WEEKDAY, scale=0.6682537722753316) across 11 open hours
22:57:55.953 [pool-1-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 631 arrivals for 2026-09-15 (WEEKDAY, scale=0.6554776324840096) across 11 open hours
22:57:56.000 [pool-1-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 610 arrivals for 2026-09-16 (WEEKDAY, scale=0.719565108367892) across 11 open hours
22:57:56.222 [pool-1-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 742 arrivals for 2026-09-17 (WEEKDAY, scale=0.7600021026339072) across 11 open hours
22:57:56.454 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 606 arrivals for 2026-09-18 (WEEKDAY, scale=0.6550065663013859) across 11 open hours
22:57:57.487 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 974 arrivals for 2026-09-19 (WEEKEND, scale=0.5805500232369337) across 11 open hours
22:57:57.601 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1169 arrivals for 2026-09-20 (WEEKEND, scale=0.7466691452031504) across 11 open hours
22:57:57.701 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 582 arrivals for 2026-09-21 (WEEKDAY, scale=0.7038441661198825) across 11 open hours
22:57:57.714 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 611 arrivals for 2026-09-22 (WEEKDAY, scale=0.6736301693067224) across 11 open hours
22:57:58.475 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 604 arrivals for 2026-09-23 (WEEKDAY, scale=0.6349586886957125) across 11 open hours
22:57:58.482 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 582 arrivals for 2026-09-24 (WEEKDAY, scale=0.6567365671727243) across 11 open hours
22:57:59.061 [pool-1-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 626 arrivals for 2026-09-25 (WEEKDAY, scale=0.6642936661722778) across 11 open hours
22:57:59.738 [pool-1-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1140 arrivals for 2026-09-26 (WEEKEND, scale=0.7080324615549678) across 11 open hours
22:58:00.265 [pool-1-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1040 arrivals for 2026-09-27 (WEEKEND, scale=0.6824517009359282) across 11 open hours
22:58:00.317 [pool-1-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 581 arrivals for 2026-09-28 (WEEKDAY, scale=0.6820215506721895) across 11 open hours
22:58:00.581 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 608 arrivals for 2026-09-29 (WEEKDAY, scale=0.6254529574540079) across 11 open hours
22:58:01.378 [pool-1-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 648 arrivals for 2026-09-30 (WEEKDAY, scale=0.6755831585243858) across 11 open hours
22:58:01.421 [pool-1-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 624 arrivals for 2026-10-01 (WEEKDAY, scale=0.7070239116029328) across 11 open hours
22:58:01.661 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 576 arrivals for 2026-10-02 (WEEKDAY, scale=0.6647479850151623) across 11 open hours
22:58:01.883 [pool-1-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1042 arrivals for 2026-10-03 (WEEKEND, scale=0.6670946374758883) across 11 open hours
22:58:01.920 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1042 arrivals for 2026-10-04 (WEEKEND, scale=0.7107734491378147) across 11 open hours
22:58:02.266 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 598 arrivals for 2026-09-07 (WEEKDAY, scale=0.6671361649210708) across 11 open hours
22:58:02.567 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 556 arrivals for 2026-09-08 (WEEKDAY, scale=0.6427377458492484) across 11 open hours
22:58:03.535 [pool-1-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 508 arrivals for 2026-09-09 (WEEKDAY, scale=0.5936717468408397) across 11 open hours
22:58:04.078 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 472 arrivals for 2026-09-10 (WEEKDAY, scale=0.574946197063973) across 11 open hours
22:58:04.284 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 561 arrivals for 2026-09-11 (WEEKDAY, scale=0.6009292642189895) across 11 open hours
22:58:04.421 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 867 arrivals for 2026-09-12 (WEEKEND, scale=0.58917834189127) across 11 open hours
22:58:04.667 [pool-1-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 978 arrivals for 2026-09-13 (WEEKEND, scale=0.6263277292193465) across 11 open hours
22:58:04.754 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 383 arrivals for 2026-09-14 (WEEKDAY, scale=0.4155533983707084) across 11 open hours
22:58:05.236 [pool-1-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 405 arrivals for 2026-09-15 (WEEKDAY, scale=0.44930469468377093) across 11 open hours
22:58:05.439 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 422 arrivals for 2026-09-16 (WEEKDAY, scale=0.4628618775161547) across 11 open hours
22:58:05.701 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 361 arrivals for 2026-09-17 (WEEKDAY, scale=0.39582042968584374) across 11 open hours
22:58:05.798 [pool-1-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 379 arrivals for 2026-09-18 (WEEKDAY, scale=0.4079471558036438) across 11 open hours
22:58:05.827 [pool-1-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 709 arrivals for 2026-09-19 (WEEKEND, scale=0.44294907345342377) across 11 open hours
22:58:05.894 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 571 arrivals for 2026-09-20 (WEEKEND, scale=0.38653445494100735) across 11 open hours
22:58:06.021 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 393 arrivals for 2026-09-21 (WEEKDAY, scale=0.42904701104547566) across 11 open hours
22:58:06.240 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 391 arrivals for 2026-09-22 (WEEKDAY, scale=0.44541862823251244) across 11 open hours
22:58:06.541 [pool-1-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 380 arrivals for 2026-09-23 (WEEKDAY, scale=0.43191051597926133) across 11 open hours
22:58:06.925 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 381 arrivals for 2026-09-24 (WEEKDAY, scale=0.4686058047559904) across 11 open hours
22:58:07.010 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 379 arrivals for 2026-09-25 (WEEKDAY, scale=0.43891292651520986) across 11 open hours
22:58:07.279 [pool-1-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 637 arrivals for 2026-09-26 (WEEKEND, scale=0.40052300718970507) across 11 open hours
22:58:07.306 [pool-1-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 607 arrivals for 2026-09-27 (WEEKEND, scale=0.406433074081677) across 11 open hours
22:58:07.369 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 389 arrivals for 2026-09-28 (WEEKDAY, scale=0.4388120746967707) across 11 open hours
22:58:07.401 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 417 arrivals for 2026-09-29 (WEEKDAY, scale=0.4297876017413979) across 11 open hours
22:58:07.594 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 387 arrivals for 2026-09-30 (WEEKDAY, scale=0.41632331703423375) across 11 open hours
22:58:07.819 [pool-1-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 395 arrivals for 2026-10-01 (WEEKDAY, scale=0.43612066457388865) across 11 open hours
22:58:07.825 [pool-1-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 362 arrivals for 2026-10-02 (WEEKDAY, scale=0.40960096317528216) across 11 open hours
22:58:07.899 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 651 arrivals for 2026-10-03 (WEEKEND, scale=0.40829881668428253) across 11 open hours
22:58:07.946 [pool-1-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 619 arrivals for 2026-10-04 (WEEKEND, scale=0.43627470844137434) across 11 open hours
22:58:08.246 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 978 arrivals for 2026-09-07 (WEEKDAY, scale=1.1463608446057982) across 11 open hours
22:58:08.317 [pool-1-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1114 arrivals for 2026-09-08 (WEEKDAY, scale=1.2363887990716913) across 11 open hours
22:58:08.318 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1218 arrivals for 2026-09-09 (WEEKDAY, scale=1.4120529671724833) across 11 open hours
22:58:08.706 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1067 arrivals for 2026-09-10 (WEEKDAY, scale=1.1949697222480067) across 11 open hours
22:58:08.899 [pool-1-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1122 arrivals for 2026-09-11 (WEEKDAY, scale=1.275431806006996) across 11 open hours
22:58:08.930 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1934 arrivals for 2026-09-12 (WEEKEND, scale=1.293751532922883) across 11 open hours
22:58:09.000 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1957 arrivals for 2026-09-13 (WEEKEND, scale=1.2247567190969142) across 11 open hours
22:58:09.001 [pool-1-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 804 arrivals for 2026-09-14 (WEEKDAY, scale=0.8641456246342061) across 11 open hours
22:58:09.154 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 841 arrivals for 2026-09-15 (WEEKDAY, scale=0.8743022118689087) across 11 open hours
22:58:09.190 [pool-1-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 784 arrivals for 2026-09-16 (WEEKDAY, scale=0.8421779761645186) across 11 open hours
22:58:09.398 [pool-1-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1033 arrivals for 2026-09-17 (WEEKDAY, scale=1.0487725880178747) across 11 open hours
22:58:09.402 [pool-1-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 715 arrivals for 2026-09-18 (WEEKDAY, scale=0.8449427841890307) across 11 open hours
22:58:09.995 [pool-1-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1336 arrivals for 2026-09-19 (WEEKEND, scale=0.8718526870153616) across 11 open hours
22:58:10.277 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1639 arrivals for 2026-09-20 (WEEKEND, scale=1.0718588082514142) across 11 open hours
22:58:18.376 [pool-1-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 727 arrivals for 2026-09-21 (WEEKDAY, scale=0.8152299638751187) across 11 open hours
22:58:18.694 [pool-1-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 828 arrivals for 2026-09-22 (WEEKDAY, scale=0.9449936201606656) across 11 open hours
22:58:19.186 [pool-1-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 814 arrivals for 2026-09-23 (WEEKDAY, scale=0.9450171728265645) across 11 open hours
22:58:19.697 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 845 arrivals for 2026-09-24 (WEEKDAY, scale=0.9520203718147684) across 11 open hours
22:58:19.715 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 819 arrivals for 2026-09-25 (WEEKDAY, scale=0.9223625715028696) across 11 open hours
22:58:21.880 [pool-1-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1319 arrivals for 2026-09-26 (WEEKEND, scale=0.8415508917022771) across 11 open hours
22:58:21.998 [pool-1-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1502 arrivals for 2026-09-27 (WEEKEND, scale=0.9332952363818894) across 11 open hours
22:58:22.240 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 800 arrivals for 2026-09-28 (WEEKDAY, scale=0.9665236508776702) across 11 open hours
22:58:23.029 [pool-1-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 879 arrivals for 2026-09-29 (WEEKDAY, scale=0.9820441864297768) across 11 open hours
22:58:23.223 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 783 arrivals for 2026-09-30 (WEEKDAY, scale=0.8733704276644756) across 11 open hours
22:58:26.934 [pool-1-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 773 arrivals for 2026-10-01 (WEEKDAY, scale=0.8394229655887286) across 11 open hours
22:58:27.615 [pool-1-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 789 arrivals for 2026-10-02 (WEEKDAY, scale=0.8698843333022063) across 11 open hours
22:58:29.371 [pool-1-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1380 arrivals for 2026-10-03 (WEEKEND, scale=0.9174826898688441) across 11 open hours
22:58:29.384 [pool-1-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1452 arrivals for 2026-10-04 (WEEKEND, scale=0.9627693114347398) across 11 open hours
22:58:29.822 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 513 arrivals for 2026-09-07 (WEEKDAY, scale=0.5282092124379024) across 11 open hours
22:58:30.141 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 499 arrivals for 2026-09-08 (WEEKDAY, scale=0.5728260202189824) across 11 open hours
22:58:31.204 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 459 arrivals for 2026-09-09 (WEEKDAY, scale=0.5301734565869383) across 11 open hours
22:58:31.264 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 437 arrivals for 2026-09-10 (WEEKDAY, scale=0.5272414149080845) across 11 open hours
22:58:31.470 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 530 arrivals for 2026-09-11 (WEEKDAY, scale=0.5488831961981906) across 11 open hours
22:58:32.169 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 689 arrivals for 2026-09-12 (WEEKEND, scale=0.4758450950449461) across 11 open hours
22:58:32.403 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 751 arrivals for 2026-09-13 (WEEKEND, scale=0.523699903316124) across 11 open hours
22:58:32.425 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 426 arrivals for 2026-09-14 (WEEKDAY, scale=0.4402140047898558) across 11 open hours
22:58:32.552 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 318 arrivals for 2026-09-15 (WEEKDAY, scale=0.3827259267621262) across 11 open hours
22:58:33.035 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 328 arrivals for 2026-09-16 (WEEKDAY, scale=0.35517693335435563) across 11 open hours
22:58:33.232 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 345 arrivals for 2026-09-17 (WEEKDAY, scale=0.3958423824070702) across 11 open hours
22:58:33.350 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 413 arrivals for 2026-09-18 (WEEKDAY, scale=0.41394191935138297) across 11 open hours
22:58:33.406 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 727 arrivals for 2026-09-19 (WEEKEND, scale=0.490060425653269) across 11 open hours
22:58:33.694 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 669 arrivals for 2026-09-20 (WEEKEND, scale=0.43637324318930687) across 11 open hours
22:58:33.958 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 379 arrivals for 2026-09-21 (WEEKDAY, scale=0.44247440032516006) across 11 open hours
22:58:33.965 [pool-1-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 330 arrivals for 2026-09-22 (WEEKDAY, scale=0.3746504978674616) across 11 open hours
22:58:34.057 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 399 arrivals for 2026-09-23 (WEEKDAY, scale=0.4382838725481145) across 11 open hours
22:58:34.154 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 396 arrivals for 2026-09-24 (WEEKEND, scale=0.266852968628028) across 11 open hours
22:58:34.423 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 512 arrivals for 2026-09-26 (WEEKEND, scale=0.3414733051141895) across 11 open hours
22:58:34.528 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 669 arrivals for 2026-09-27 (WEEKEND, scale=0.4368294668859842) across 11 open hours
22:58:34.950 [pool-1-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 341 arrivals for 2026-09-28 (WEEKDAY, scale=0.37207027841135265) across 11 open hours
22:58:35.077 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 337 arrivals for 2026-09-29 (WEEKDAY, scale=0.40030558561160823) across 11 open hours
22:58:35.197 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 371 arrivals for 2026-09-30 (WEEKDAY, scale=0.42887944450501386) across 11 open hours
22:58:35.400 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 353 arrivals for 2026-10-01 (WEEKDAY, scale=0.40089702560116086) across 11 open hours
22:58:35.513 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 348 arrivals for 2026-10-02 (WEEKDAY, scale=0.4085295263293165) across 11 open hours
22:58:35.570 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 588 arrivals for 2026-10-03 (WEEKEND, scale=0.38041257693681385) across 11 open hours
22:58:35.919 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 624 arrivals for 2026-10-04 (WEEKEND, scale=0.4101463091254617) across 11 open hours
22:58:35.928 [pool-1-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 998 arrivals for 2026-09-07 (WEEKDAY, scale=1.080870475324057) across 11 open hours
22:58:36.033 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 881 arrivals for 2026-09-08 (WEEKDAY, scale=1.0241342581389985) across 11 open hours
22:58:36.184 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1033 arrivals for 2026-09-09 (WEEKDAY, scale=1.1197214965133968) across 11 open hours
22:58:36.386 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 788 arrivals for 2026-09-10 (WEEKDAY, scale=0.8905159955133225) across 11 open hours
22:58:36.505 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1011 arrivals for 2026-09-11 (WEEKDAY, scale=1.0548775413725402) across 11 open hours
22:58:36.556 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1471 arrivals for 2026-09-12 (WEEKEND, scale=0.9736210346562587) across 11 open hours
22:58:36.668 [pool-1-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1507 arrivals for 2026-09-13 (WEEKEND, scale=1.0362497607711225) across 11 open hours
22:58:37.116 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 693 arrivals for 2026-09-14 (WEEKDAY, scale=0.806424442745644) across 11 open hours
22:58:37.831 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 701 arrivals for 2026-09-15 (WEEKDAY, scale=0.7711464621399993) across 11 open hours
22:58:37.981 [pool-1-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 615 arrivals for 2026-09-16 (WEEKDAY, scale=0.6971806330178205) across 11 open hours
22:58:38.485 [pool-1-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 669 arrivals for 2026-09-17 (WEEKDAY, scale=0.7538418517870752) across 11 open hours
22:58:40.708 [pool-1-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 724 arrivals for 2026-09-18 (WEEKDAY, scale=0.7720396528481965) across 11 open hours
22:58:43.227 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1330 arrivals for 2026-09-19 (WEEKEND, scale=0.8874465429211797) across 11 open hours
22:58:43.449 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1509 arrivals for 2026-09-20 (WEEKEND, scale=0.9860031944765344) across 11 open hours
22:58:43.506 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 739 arrivals for 2026-09-21 (WEEKDAY, scale=0.8347724889778564) across 11 open hours
22:58:43.557 [pool-1-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 732 arrivals for 2026-09-22 (WEEKDAY, scale=0.8534815819581705) across 11 open hours
22:58:43.675 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 826 arrivals for 2026-09-23 (WEEKDAY, scale=0.950366039256057) across 11 open hours
22:58:44.432 [pool-1-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 606 arrivals for 2026-09-24 (WEEKEND, scale=0.4144194811970339) across 11 open hours
22:58:44.537 [pool-1-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 800 arrivals for 2026-09-26 (WEEKEND, scale=0.5264280559854317) across 11 open hours
22:58:44.882 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1048 arrivals for 2026-09-27 (WEEKEND, scale=0.7180895956419141) across 11 open hours
22:58:45.124 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 638 arrivals for 2026-09-28 (WEEKDAY, scale=0.7274087788173654) across 11 open hours
22:58:46.653 [pool-1-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 700 arrivals for 2026-09-29 (WEEKDAY, scale=0.8183724502864207) across 11 open hours
22:58:46.932 [pool-1-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 771 arrivals for 2026-09-30 (WEEKDAY, scale=0.8142200405573082) across 11 open hours
22:58:47.686 [pool-1-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 608 arrivals for 2026-10-01 (WEEKDAY, scale=0.7498908698018256) across 11 open hours
22:58:49.039 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 680 arrivals for 2026-10-02 (WEEKDAY, scale=0.7958363359614471) across 11 open hours
22:58:49.463 [pool-1-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 895 arrivals for 2026-10-03 (WEEKEND, scale=0.6093325423066629) across 11 open hours
22:58:49.686 [pool-1-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1070 arrivals for 2026-10-04 (WEEKEND, scale=0.7282041946451586) across 11 open hours
22:58:49.873 [pool-1-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 2145 arrivals for 2026-09-07 (WEEKEND, scale=1.4092064510121405) across 11 open hours
22:58:50.171 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1095 arrivals for 2026-09-08 (WEEKDAY, scale=1.2723449270914513) across 11 open hours
22:58:50.791 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1220 arrivals for 2026-09-09 (WEEKDAY, scale=1.342321994008734) across 11 open hours
22:58:50.989 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1333 arrivals for 2026-09-10 (WEEKDAY, scale=1.4405364841622228) across 11 open hours
22:58:51.565 [pool-1-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1339 arrivals for 2026-09-11 (WEEKDAY, scale=1.52601551162263) across 11 open hours
22:58:52.783 [pool-1-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 2402 arrivals for 2026-09-12 (WEEKEND, scale=1.575359170180798) across 11 open hours
22:58:52.935 [pool-1-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 2347 arrivals for 2026-09-13 (WEEKEND, scale=1.521472875936131) across 11 open hours
22:58:53.979 [pool-1-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 896 arrivals for 2026-09-14 (WEEKDAY, scale=0.9676489125154625) across 11 open hours
22:58:54.324 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1012 arrivals for 2026-09-15 (WEEKDAY, scale=1.0538363912268052) across 11 open hours
22:58:55.088 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 979 arrivals for 2026-09-16 (WEEKDAY, scale=1.0773381659135084) across 11 open hours
22:58:55.234 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 966 arrivals for 2026-09-17 (WEEKDAY, scale=1.0705042682888783) across 11 open hours
22:58:56.922 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1049 arrivals for 2026-09-18 (WEEKDAY, scale=1.1026544229427186) across 11 open hours
22:58:57.451 [pool-1-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1343 arrivals for 2026-09-19 (WEEKEND, scale=0.9026618579927052) across 11 open hours
22:58:59.041 [pool-1-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1364 arrivals for 2026-09-20 (WEEKEND, scale=0.951457438248133) across 11 open hours
22:59:04.279 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 874 arrivals for 2026-09-21 (WEEKDAY, scale=0.9883297468017483) across 11 open hours
22:59:06.341 [pool-1-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 824 arrivals for 2026-09-22 (WEEKDAY, scale=0.9318576006270956) across 11 open hours
22:59:06.438 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 912 arrivals for 2026-09-23 (WEEKDAY, scale=1.0110041976832564) across 11 open hours
22:59:07.943 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 849 arrivals for 2026-09-24 (WEEKDAY, scale=0.9688555578012775) across 11 open hours
22:59:08.316 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 850 arrivals for 2026-09-25 (WEEKDAY, scale=1.0211400832623825) across 11 open hours
22:59:08.379 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1584 arrivals for 2026-09-26 (WEEKEND, scale=1.041503986488702) across 11 open hours
22:59:08.607 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1475 arrivals for 2026-09-27 (WEEKEND, scale=0.9967459530107129) across 11 open hours
22:59:09.145 [pool-1-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 839 arrivals for 2026-09-28 (WEEKDAY, scale=0.9662740079879092) across 11 open hours
22:59:11.378 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 862 arrivals for 2026-09-29 (WEEKDAY, scale=1.0529325776288154) across 11 open hours
22:59:16.138 [pool-1-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 920 arrivals for 2026-09-30 (WEEKDAY, scale=1.0593353484444945) across 11 open hours
22:59:16.561 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 935 arrivals for 2026-10-01 (WEEKDAY, scale=1.1000793320693003) across 11 open hours
22:59:17.436 [pool-1-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 949 arrivals for 2026-10-02 (WEEKDAY, scale=1.089403705801966) across 11 open hours
22:59:17.551 [pool-1-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1381 arrivals for 2026-10-03 (WEEKEND, scale=0.9661741183270061) across 11 open hours
22:59:17.910 [pool-1-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 2396 arrivals for 2026-09-07 (WEEKEND, scale=1.5629723508091027) across 11 open hours
22:59:18.989 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1165 arrivals for 2026-09-08 (WEEKDAY, scale=1.2905834894487858) across 11 open hours
22:59:19.150 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1102 arrivals for 2026-09-09 (WEEKDAY, scale=1.226058810944141) across 11 open hours
22:59:19.967 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1131 arrivals for 2026-09-10 (WEEKDAY, scale=1.2441105479480266) across 11 open hours
22:59:20.523 [pool-1-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1144 arrivals for 2026-09-11 (WEEKDAY, scale=1.30121907286133) across 11 open hours
22:59:23.173 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1880 arrivals for 2026-09-12 (WEEKEND, scale=1.2624934683380031) across 11 open hours
22:59:23.647 [pool-1-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1933 arrivals for 2026-09-13 (WEEKEND, scale=1.2664211266215226) across 11 open hours
22:59:23.763 [pool-1-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 930 arrivals for 2026-09-14 (WEEKDAY, scale=1.0416531186156073) across 11 open hours
22:59:28.605 [pool-1-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 740 arrivals for 2026-09-15 (WEEKDAY, scale=0.8717746665279559) across 11 open hours
22:59:29.104 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 761 arrivals for 2026-09-16 (WEEKDAY, scale=0.8721128660100185) across 11 open hours
22:59:29.517 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 795 arrivals for 2026-09-17 (WEEKDAY, scale=0.9160549394269241) across 11 open hours
22:59:29.856 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 926 arrivals for 2026-09-18 (WEEKDAY, scale=1.0149606874784716) across 11 open hours
22:59:29.889 [pool-1-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1475 arrivals for 2026-09-19 (WEEKEND, scale=0.9852870184176931) across 11 open hours
22:59:32.858 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1448 arrivals for 2026-09-20 (WEEKEND, scale=1.0020890689429538) across 11 open hours
22:59:33.458 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 921 arrivals for 2026-09-21 (WEEKDAY, scale=1.0054473594300977) across 11 open hours
22:59:34.027 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 866 arrivals for 2026-09-22 (WEEKDAY, scale=0.9520769874996025) across 11 open hours
22:59:34.766 [pool-1-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 877 arrivals for 2026-09-23 (WEEKDAY, scale=1.0419408133874741) across 11 open hours
22:59:35.254 [pool-1-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 944 arrivals for 2026-09-24 (WEEKDAY, scale=1.0769000000679958) across 11 open hours
22:59:36.305 [pool-1-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 743 arrivals for 2026-09-25 (WEEKDAY, scale=0.8545982880877141) across 11 open hours
22:59:38.357 [pool-1-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1500 arrivals for 2026-09-26 (WEEKEND, scale=1.0165287908876388) across 11 open hours
22:59:39.035 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1405 arrivals for 2026-09-27 (WEEKEND, scale=0.9302306451132621) across 11 open hours
22:59:39.796 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 847 arrivals for 2026-09-28 (WEEKDAY, scale=1.0144000338564703) across 11 open hours
22:59:42.067 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 822 arrivals for 2026-09-29 (WEEKDAY, scale=0.9030921558901854) across 11 open hours
22:59:44.959 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 801 arrivals for 2026-09-30 (WEEKDAY, scale=0.9161977911842863) across 11 open hours
22:59:45.189 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 818 arrivals for 2026-10-01 (WEEKDAY, scale=0.9306958162286557) across 11 open hours
22:59:45.492 [pool-1-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 819 arrivals for 2026-10-02 (WEEKDAY, scale=1.0105377399752995) across 11 open hours
22:59:46.093 [pool-1-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1367 arrivals for 2026-10-03 (WEEKEND, scale=0.9273793755510163) across 11 open hours
22:59:46.233 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1870 arrivals for 2026-09-07 (WEEKEND, scale=1.2431849495060585) across 11 open hours
22:59:46.804 [pool-1-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1045 arrivals for 2026-09-08 (WEEKDAY, scale=1.2448033561619116) across 11 open hours
22:59:47.338 [pool-1-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1149 arrivals for 2026-09-09 (WEEKDAY, scale=1.2946706264102021) across 11 open hours
22:59:47.389 [pool-1-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1371 arrivals for 2026-09-10 (WEEKDAY, scale=1.5205436265920744) across 11 open hours
22:59:48.365 [pool-1-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1199 arrivals for 2026-09-11 (WEEKDAY, scale=1.3063335025542846) across 11 open hours
22:59:50.864 [pool-1-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 2014 arrivals for 2026-09-12 (WEEKEND, scale=1.3773371697522985) across 11 open hours
22:59:51.144 [pool-1-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 2171 arrivals for 2026-09-13 (WEEKEND, scale=1.4481183873591752) across 11 open hours
22:59:52.472 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 898 arrivals for 2026-09-14 (WEEKDAY, scale=1.0141706524862943) across 11 open hours
22:59:54.833 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1045 arrivals for 2026-09-15 (WEEKDAY, scale=1.154104090262804) across 11 open hours
22:59:55.235 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 995 arrivals for 2026-09-16 (WEEKDAY, scale=1.0661344564686976) across 11 open hours
22:59:55.978 [pool-1-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 847 arrivals for 2026-09-17 (WEEKDAY, scale=0.9295097552227233) across 11 open hours
22:59:56.736 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 994 arrivals for 2026-09-18 (WEEKDAY, scale=1.097214025774705) across 11 open hours
22:59:57.263 [pool-1-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1466 arrivals for 2026-09-19 (WEEKEND, scale=0.995163157463325) across 11 open hours
22:59:57.666 [pool-1-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1586 arrivals for 2026-09-20 (WEEKEND, scale=1.053047297828483) across 11 open hours
22:59:59.650 [pool-1-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 901 arrivals for 2026-09-21 (WEEKDAY, scale=1.0353562492181498) across 11 open hours
23:00:01.038 [pool-1-thread-12] INFO  c.m.twin.history.HistoryGenerator -   … 250/275 store-days
23:00:01.038 [pool-1-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1080 arrivals for 2026-09-22 (WEEKDAY, scale=1.15733604299829) across 11 open hours
23:00:02.117 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 891 arrivals for 2026-09-23 (WEEKDAY, scale=0.9876350841773626) across 11 open hours
23:00:02.231 [pool-1-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 870 arrivals for 2026-09-24 (WEEKDAY, scale=1.085341354946549) across 11 open hours
23:00:03.188 [pool-1-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 915 arrivals for 2026-09-25 (WEEKDAY, scale=1.0029415535194264) across 11 open hours
23:00:05.011 [pool-1-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1496 arrivals for 2026-09-26 (WEEKEND, scale=0.9911239516789556) across 11 open hours
23:00:06.036 [pool-1-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1327 arrivals for 2026-09-27 (WEEKEND, scale=0.9337341483243844) across 11 open hours
23:00:06.466 [pool-1-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 895 arrivals for 2026-09-28 (WEEKDAY, scale=1.0315252754908635) across 11 open hours
23:00:06.867 [pool-1-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 812 arrivals for 2026-09-29 (WEEKDAY, scale=1.0023299381963235) across 11 open hours
23:00:08.044 [pool-1-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 811 arrivals for 2026-09-30 (WEEKDAY, scale=0.8795077207208007) across 11 open hours
23:00:09.716 [pool-1-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 825 arrivals for 2026-10-01 (WEEKDAY, scale=0.9750471752059179) across 11 open hours
23:00:11.629 [pool-1-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 899 arrivals for 2026-10-02 (WEEKDAY, scale=1.0428402093810956) across 11 open hours
23:00:12.110 [pool-1-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1599 arrivals for 2026-10-03 (WEEKEND, scale=1.0920260250872045) across 11 open hours
23:00:25.275 [pool-1-thread-3] INFO  c.m.twin.history.HistoryGenerator -   … 275/275 store-days
  generated 275 store-days in 3m25s

| store | days | visits | transactions | conversion | units | sales USD | ATV | compliance mean | Δ visits | Δ txn | Δ units | Δ sales | sales vs plan |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| dec-fr-bordeaux | 28 | 16,002 | 3,654 | 0.228 | 8,062 | 214,711 | 58.76 | 86.7 | +0.8% | +1.0% | +1.3% | +2.3% | +0.2% |
| dec-fr-lille | 28 | 25,392 | 6,374 | 0.251 | 14,022 | 372,655 | 58.46 | 91.3 | +0.4% | +2.1% | +2.1% | +2.9% | +13.4% |
| dec-fr-lyon | 28 | 22,010 | 5,247 | 0.238 | 11,590 | 307,983 | 58.70 | 89.0 | +0.2% | +0.9% | +1.3% | +2.1% | +2.7% |
| dec-fr-marseille | 28 | 14,158 | 2,869 | 0.203 | 6,263 | 167,347 | 58.33 | 74.9 | -1.1% | -0.1% | -0.9% | +0.4% | -21.5% |
| dec-fr-paris | 28 | 30,252 | 7,118 | 0.235 | 15,702 | 409,059 | 57.47 | 89.8 | -0.5% | +0.5% | +0.7% | -0.4% | +2.3% |
| dec-kr-busan | 27 | 12,751 | 2,973 | 0.233 | 6,557 | 173,042 | 58.20 | 88.4 | +0.8% | +0.8% | +1.1% | +1.2% | -2.6% |
| dec-kr-seoul | 27 | 24,043 | 5,605 | 0.233 | 12,325 | 330,350 | 58.94 | 92.3 | +0.2% | +0.1% | +0.1% | +1.7% | +0.9% |
| dec-us-denver | 27 | 32,744 | 8,324 | 0.254 | 18,369 | 481,807 | 57.88 | 89.5 | +0.7% | +2.6% | +3.0% | +2.4% | +12.1% |
| dec-us-nyc | 27 | 30,556 | 7,258 | 0.238 | 15,945 | 417,996 | 57.59 | 88.9 | +0.1% | +1.3% | +1.1% | +0.5% | +0.2% |
| dec-us-sf | 27 | 31,971 | 7,426 | 0.232 | 16,384 | 425,329 | 57.28 | 88.4 | +0.0% | -0.9% | -0.6% | -2.1% | -2.1% |
| **chain** | 275 | 239,879 | 56,848 | 0.237 | 125,219 | 3,300,279 | | | | | | | |
Δ = generated vs the operating model's expectation for the generated days (model volumes × seasonality × story × weather; units at `units_per_txn` 2.2, sales at `atv_usd` $58). Sales vs plan = vs the KPI plan (the model without the store's story).
  ✓ scheduler ran every store-day without errors
  ✓ the keys export agrees with every store's layout.json (codes, names, types)
  ✓ US stores end a day early (local Oct 4 closes after the 2026-10-05T00:00Z cut-off)
  ✓ Chuseok (Sep 25) is closed in Seoul and Busan, open elsewhere
  ✓ MANIFEST header is the contract's
  ✓ MANIFEST lists every data file once
  ✓ MANIFEST rows = file rows — commerce_store_hourly.csv
  ✓ MANIFEST sha256 matches — commerce_store_hourly.csv
  ✓ MANIFEST seed is the run's — commerce_store_hourly.csv
  ✓ MANIFEST rows = file rows — commerce_department_daily.csv
  ✓ MANIFEST sha256 matches — commerce_department_daily.csv
  ✓ MANIFEST seed is the run's — commerce_department_daily.csv
  ✓ MANIFEST rows = file rows — traffic_store_hourly.csv
  ✓ MANIFEST sha256 matches — traffic_store_hourly.csv
  ✓ MANIFEST seed is the run's — traffic_store_hourly.csv
  ✓ MANIFEST rows = file rows — traffic_department_daily.csv
  ✓ MANIFEST sha256 matches — traffic_department_daily.csv
  ✓ MANIFEST seed is the run's — traffic_department_daily.csv
  ✓ MANIFEST rows = file rows — behavioral_fixture_daily.csv
  ✓ MANIFEST sha256 matches — behavioral_fixture_daily.csv
  ✓ MANIFEST seed is the run's — behavioral_fixture_daily.csv
  ✓ MANIFEST rows = file rows — compliance_weekly.csv
  ✓ MANIFEST sha256 matches — compliance_weekly.csv
  ✓ MANIFEST seed is the run's — compliance_weekly.csv
  ✓ MANIFEST rows = file rows — tasks.csv
  ✓ MANIFEST sha256 matches — tasks.csv
  ✓ MANIFEST seed is the run's — tasks.csv
  ✓ MANIFEST rows = file rows — alarms.csv
  ✓ MANIFEST sha256 matches — alarms.csv
  ✓ MANIFEST seed is the run's — alarms.csv
  ✓ MANIFEST rows = file rows — stocktakes.csv
  ✓ MANIFEST sha256 matches — stocktakes.csv
  ✓ MANIFEST seed is the run's — stocktakes.csv
  ✓ MANIFEST rows = file rows — kpi_targets.csv
  ✓ MANIFEST sha256 matches — kpi_targets.csv
  ✓ MANIFEST seed is the run's — kpi_targets.csv
  ✓ header — commerce_store_hourly.csv
  ✓ header — commerce_department_daily.csv
  ✓ header — traffic_store_hourly.csv
  ✓ header — traffic_department_daily.csv
  ✓ header — behavioral_fixture_daily.csv
  ✓ header — compliance_weekly.csv
  ✓ header — tasks.csv
  ✓ header — alarms.csv
  ✓ header — stocktakes.csv
  ✓ header — kpi_targets.csv
  ✓ no hourly row outside operating hours (10:00–21:00 local, 1 h windows) — commerce_store_hourly.csv
  ✓ eleven hourly rows per open store-day — commerce_store_hourly.csv
  ✓ every window inside 2026-01-01 .. 2026-10-05T00:00:00Z — commerce_store_hourly.csv
  ✓ no duplicate (store, window) — commerce_store_hourly.csv
  ✓ no hourly row outside operating hours (10:00–21:00 local, 1 h windows) — traffic_store_hourly.csv
  ✓ eleven hourly rows per open store-day — traffic_store_hourly.csv
  ✓ every window inside 2026-01-01 .. 2026-10-05T00:00:00Z — traffic_store_hourly.csv
  ✓ no duplicate (store, window) — traffic_store_hourly.csv
  ✓ closed holidays carry no rows
  ✓ daily windows are the operating day [10:00, 21:00) local — commerce_department_daily.csv
  ✓ every window inside the backfill range — commerce_department_daily.csv
  ✓ daily windows are the operating day [10:00, 21:00) local — traffic_department_daily.csv
  ✓ every window inside the backfill range — traffic_department_daily.csv
  ✓ daily windows are the operating day [10:00, 21:00) local — behavioral_fixture_daily.csv
  ✓ every window inside the backfill range — behavioral_fixture_daily.csv
  ✓ every site slug resolves — commerce_store_hourly.csv
  ✓ every site slug resolves — commerce_department_daily.csv
  ✓ every site slug resolves — traffic_store_hourly.csv
  ✓ every site slug resolves — traffic_department_daily.csv
  ✓ every site slug resolves — behavioral_fixture_daily.csv
  ✓ every site slug resolves — compliance_weekly.csv
  ✓ every site slug resolves — tasks.csv
  ✓ every site slug resolves — alarms.csv
  ✓ every site slug resolves — stocktakes.csv
  ✓ every site slug resolves — kpi_targets.csv
  ✓ every department resolves to a REGION zone by name — commerce_department_daily.csv
  ✓ every department resolves to a REGION zone by name — traffic_department_daily.csv
  ✓ every fixture code resolves to a FIXTURE zone
  ✓ every task zone key resolves to exactly one zone (name or code)
  ✓ every alarm sku (EAN) is in the store's assortment
  ✓ conversion_rate ≤ 1 and = transactions ÷ visits in the same window
  ✓ avg_basket_value = value ÷ txn, units_per_transaction = units ÷ txn
  ✓ transit_count ≤ visit_count
  ✓ department sums = the store's hourly sums for the day (units, value, transactions)
  ✓ every store-day has department rows and hourly rows alike
  ✓ department visits ≤ the store's visits that day
  ✓ fixture impressions (distinct shoppers) ≤ the store's visits that day
  ✓ no negative value anywhere
  ✓ task.type ∈ the live CHECK list
  ✓ task.priority ∈ low/normal/high/urgent
  ✓ task.status ∈ the task_status enum
  ✓ kpi metric_type ∈ sales_value · visit_count · conversion_rate
  ✓ kpi: one row per store × metric
  ✓ stocktakes: mode general, scanned = expected − missing + ghost
  ✓ compliance: score 0..100, counts consistent (drift, remediated ≤ non_compliant ≤ targets)
  ✓ compliance weeks start on Mondays
  ✓ compliance scores the trailing partial week
  ✓ open work only in the store's last 3 days
  ✓ ≥ 85 % of past tasks completed within a day
  ✓ task created/started/completed timestamps inside operating hours
  ✓ task timestamps ordered created ≤ started ≤ completed
  ✓ 3+ tasks every store-day
  ✓ unresolved alarms only within the last 14 days
  ✓ alarms carry gate CS-01, an EPC and a severity
  ✓ alarm created_at falls inside operating hours (close − 1 s at the latest)
  ✓ 2–4 gate alarms per store-week (chain mean)
  ✓ dec-fr-bordeaux: visits within ±5.0% of the model
  ✓ dec-fr-bordeaux: transactions within ±5.0% of the model
  ✓ dec-fr-bordeaux: units within ±5.0% of the model
  ✓ dec-fr-bordeaux: sales within ±5.0% of the model (txn × atv_usd)
  ✓ dec-fr-lille: visits within ±5.0% of the model
  ✓ dec-fr-lille: transactions within ±5.0% of the model
  ✓ dec-fr-lille: units within ±5.0% of the model
  ✓ dec-fr-lille: sales within ±5.0% of the model (txn × atv_usd)
  ✓ dec-fr-lyon: visits within ±5.0% of the model
  ✓ dec-fr-lyon: transactions within ±5.0% of the model
  ✓ dec-fr-lyon: units within ±5.0% of the model
  ✓ dec-fr-lyon: sales within ±5.0% of the model (txn × atv_usd)
  ✓ dec-fr-marseille: visits within ±5.0% of the model
  ✓ dec-fr-marseille: transactions within ±5.0% of the model
  ✓ dec-fr-marseille: units within ±5.0% of the model
  ✓ dec-fr-marseille: sales within ±5.0% of the model (txn × atv_usd)
  ✓ dec-fr-paris: visits within ±5.0% of the model
  ✓ dec-fr-paris: transactions within ±5.0% of the model
  ✓ dec-fr-paris: units within ±5.0% of the model
  ✓ dec-fr-paris: sales within ±5.0% of the model (txn × atv_usd)
  ✓ dec-kr-busan: visits within ±5.0% of the model
  ✓ dec-kr-busan: transactions within ±5.0% of the model
  ✓ dec-kr-busan: units within ±5.0% of the model
  ✓ dec-kr-busan: sales within ±5.0% of the model (txn × atv_usd)
  ✓ dec-kr-seoul: visits within ±5.0% of the model
  ✓ dec-kr-seoul: transactions within ±5.0% of the model
  ✓ dec-kr-seoul: units within ±5.0% of the model
  ✓ dec-kr-seoul: sales within ±5.0% of the model (txn × atv_usd)
  ✓ dec-us-denver: visits within ±5.0% of the model
  ✓ dec-us-denver: transactions within ±5.0% of the model
  ✓ dec-us-denver: units within ±5.0% of the model
  ✓ dec-us-denver: sales within ±5.0% of the model (txn × atv_usd)
  ✓ dec-us-nyc: visits within ±5.0% of the model
  ✓ dec-us-nyc: transactions within ±5.0% of the model
  ✓ dec-us-nyc: units within ±5.0% of the model
  ✓ dec-us-nyc: sales within ±5.0% of the model (txn × atv_usd)
  ✓ dec-us-sf: visits within ±5.0% of the model
  ✓ dec-us-sf: transactions within ±5.0% of the model
  ✓ dec-us-sf: units within ±5.0% of the model
  ✓ dec-us-sf: sales within ±5.0% of the model (txn × atv_usd)
[2] determinism — same seed → byte-identical files
23:00:33.774 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-fr-marseille sampler targets: units 2.160 · line price $24.13 (model 2.2 · $26.4; tagged SKUs 155)
23:00:33.775 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-kr-busan sampler targets: units 2.160 · line price $24.25 (model 2.2 · $26.4; tagged SKUs 162)
23:00:33.775 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-us-denver sampler targets: units 2.160 · line price $22.69 (model 2.2 · $26.4; tagged SKUs 271)
23:00:33.775 [main] INFO  c.m.twin.history.HistoryGenerator - history: 20 store-days across 3 stores, 2026-06-01..2026-06-07 (cutoff 2026-06-08T00:00:00Z), seed 20261016, 14 threads
23:00:33.775 [pool-2-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 472 arrivals for 2026-06-02 (WEEKDAY, scale=0.47426985389327464) across 11 open hours
23:00:33.775 [pool-2-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 397 arrivals for 2026-06-03 (WEEKDAY, scale=0.4493526798354866) across 11 open hours
23:00:33.775 [pool-2-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 446 arrivals for 2026-06-01 (WEEKDAY, scale=0.49661832850089216) across 11 open hours
23:00:33.775 [pool-2-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 407 arrivals for 2026-06-04 (WEEKDAY, scale=0.4397894950932227) across 11 open hours
23:00:33.776 [pool-2-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 426 arrivals for 2026-06-05 (WEEKDAY, scale=0.46336544382291356) across 11 open hours
23:00:33.776 [pool-2-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 764 arrivals for 2026-06-06 (WEEKEND, scale=0.46836568041152776) across 11 open hours
23:00:33.776 [pool-2-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 720 arrivals for 2026-06-07 (WEEKEND, scale=0.45060136072034257) across 11 open hours
23:00:33.776 [pool-2-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 426 arrivals for 2026-06-01 (WEEKDAY, scale=0.4419792979750781) across 11 open hours
23:00:33.776 [pool-2-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 342 arrivals for 2026-06-04 (WEEKDAY, scale=0.39597315100198954) across 11 open hours
23:00:33.776 [pool-2-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 373 arrivals for 2026-06-02 (WEEKDAY, scale=0.4055135719704385) across 11 open hours
23:00:33.776 [pool-2-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 533 arrivals for 2026-06-03 (WEEKEND, scale=0.3471616940164708) across 11 open hours
23:00:33.776 [pool-2-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 376 arrivals for 2026-06-05 (WEEKDAY, scale=0.4047230469922516) across 11 open hours
23:00:33.784 [pool-2-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 662 arrivals for 2026-06-06 (WEEKEND, scale=0.4294001951144702) across 11 open hours
23:00:33.792 [pool-2-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 574 arrivals for 2026-06-07 (WEEKEND, scale=0.38780675471590714) across 11 open hours
23:00:34.654 [pool-2-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 916 arrivals for 2026-06-01 (WEEKDAY, scale=0.9876297549221617) across 11 open hours
23:00:34.809 [pool-2-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 949 arrivals for 2026-06-02 (WEEKDAY, scale=1.0148306389949815) across 11 open hours
23:00:34.866 [pool-2-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1036 arrivals for 2026-06-03 (WEEKDAY, scale=1.086717902683015) across 11 open hours
23:00:35.008 [pool-2-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 944 arrivals for 2026-06-04 (WEEKDAY, scale=1.025564217339752) across 11 open hours
23:00:35.125 [pool-2-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 839 arrivals for 2026-06-05 (WEEKDAY, scale=0.8932645698963438) across 11 open hours
23:00:35.158 [pool-2-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1596 arrivals for 2026-06-06 (WEEKEND, scale=0.9550114767213883) across 11 open hours
23:00:50.356 [pool-2-thread-5] INFO  c.m.twin.history.HistoryGenerator -   … 20/20 store-days
23:00:58.391 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-fr-marseille sampler targets: units 2.160 · line price $24.13 (model 2.2 · $26.4; tagged SKUs 155)
23:00:58.391 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-kr-busan sampler targets: units 2.160 · line price $24.25 (model 2.2 · $26.4; tagged SKUs 162)
23:00:58.391 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-us-denver sampler targets: units 2.160 · line price $22.69 (model 2.2 · $26.4; tagged SKUs 271)
23:00:58.391 [main] INFO  c.m.twin.history.HistoryGenerator - history: 20 store-days across 3 stores, 2026-06-01..2026-06-07 (cutoff 2026-06-08T00:00:00Z), seed 20261016, 14 threads
23:00:58.391 [pool-3-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 397 arrivals for 2026-06-03 (WEEKDAY, scale=0.4493526798354866) across 11 open hours
23:00:58.391 [pool-3-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 446 arrivals for 2026-06-01 (WEEKDAY, scale=0.49661832850089216) across 11 open hours
23:00:58.391 [pool-3-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 426 arrivals for 2026-06-05 (WEEKDAY, scale=0.46336544382291356) across 11 open hours
23:00:58.391 [pool-3-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 407 arrivals for 2026-06-04 (WEEKDAY, scale=0.4397894950932227) across 11 open hours
23:00:58.391 [pool-3-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 472 arrivals for 2026-06-02 (WEEKDAY, scale=0.47426985389327464) across 11 open hours
23:00:58.391 [pool-3-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 764 arrivals for 2026-06-06 (WEEKEND, scale=0.46836568041152776) across 11 open hours
23:00:58.392 [pool-3-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 720 arrivals for 2026-06-07 (WEEKEND, scale=0.45060136072034257) across 11 open hours
23:00:58.393 [pool-3-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 373 arrivals for 2026-06-02 (WEEKDAY, scale=0.4055135719704385) across 11 open hours
23:00:58.393 [pool-3-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 426 arrivals for 2026-06-01 (WEEKDAY, scale=0.4419792979750781) across 11 open hours
23:00:58.393 [pool-3-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 533 arrivals for 2026-06-03 (WEEKEND, scale=0.3471616940164708) across 11 open hours
23:00:58.393 [pool-3-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 662 arrivals for 2026-06-06 (WEEKEND, scale=0.4294001951144702) across 11 open hours
23:00:58.393 [pool-3-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 342 arrivals for 2026-06-04 (WEEKDAY, scale=0.39597315100198954) across 11 open hours
23:00:58.393 [pool-3-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 376 arrivals for 2026-06-05 (WEEKDAY, scale=0.4047230469922516) across 11 open hours
23:00:58.394 [pool-3-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 574 arrivals for 2026-06-07 (WEEKEND, scale=0.38780675471590714) across 11 open hours
23:00:59.274 [pool-3-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 916 arrivals for 2026-06-01 (WEEKDAY, scale=0.9876297549221617) across 11 open hours
23:00:59.391 [pool-3-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 949 arrivals for 2026-06-02 (WEEKDAY, scale=1.0148306389949815) across 11 open hours
23:00:59.406 [pool-3-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1036 arrivals for 2026-06-03 (WEEKDAY, scale=1.086717902683015) across 11 open hours
23:00:59.568 [pool-3-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 944 arrivals for 2026-06-04 (WEEKDAY, scale=1.025564217339752) across 11 open hours
23:00:59.663 [pool-3-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 839 arrivals for 2026-06-05 (WEEKDAY, scale=0.8932645698963438) across 11 open hours
23:00:59.748 [pool-3-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1596 arrivals for 2026-06-06 (WEEKEND, scale=0.9550114767213883) across 11 open hours
23:01:14.933 [pool-3-thread-3] INFO  c.m.twin.history.HistoryGenerator -   … 20/20 store-days
23:01:23.057 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-fr-marseille sampler targets: units 2.160 · line price $24.13 (model 2.2 · $26.4; tagged SKUs 155)
23:01:23.057 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-kr-busan sampler targets: units 2.160 · line price $24.25 (model 2.2 · $26.4; tagged SKUs 162)
23:01:23.057 [main] INFO  c.m.twin.history.HistoryGenerator -   dec-us-denver sampler targets: units 2.160 · line price $22.69 (model 2.2 · $26.4; tagged SKUs 271)
23:01:23.057 [main] INFO  c.m.twin.history.HistoryGenerator - history: 20 store-days across 3 stores, 2026-06-01..2026-06-07 (cutoff 2026-06-08T00:00:00Z), seed 20261017, 14 threads
23:01:23.058 [pool-4-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 403 arrivals for 2026-06-03 (WEEKDAY, scale=0.4263075019285454) across 11 open hours
23:01:23.058 [pool-4-thread-2] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 446 arrivals for 2026-06-02 (WEEKDAY, scale=0.4570727491503974) across 11 open hours
23:01:23.058 [pool-4-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 368 arrivals for 2026-06-05 (WEEKDAY, scale=0.40857596219925707) across 11 open hours
23:01:23.058 [pool-4-thread-4] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 463 arrivals for 2026-06-04 (WEEKDAY, scale=0.4713441098520698) across 11 open hours
23:01:23.058 [pool-4-thread-1] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 475 arrivals for 2026-06-01 (WEEKDAY, scale=0.47006573778777516) across 11 open hours
23:01:23.058 [pool-4-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 343 arrivals for 2026-06-01 (WEEKDAY, scale=0.36888966505839343) across 11 open hours
23:01:23.058 [pool-4-thread-7] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 676 arrivals for 2026-06-07 (WEEKEND, scale=0.4165762599657799) across 11 open hours
23:01:23.058 [pool-4-thread-6] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 606 arrivals for 2026-06-06 (WEEKEND, scale=0.4298358220398795) across 11 open hours
23:01:23.058 [pool-4-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 434 arrivals for 2026-06-02 (WEEKDAY, scale=0.45521213593255216) across 11 open hours
23:01:23.058 [pool-4-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 396 arrivals for 2026-06-04 (WEEKDAY, scale=0.4722450304518733) across 11 open hours
23:01:23.058 [pool-4-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 398 arrivals for 2026-06-05 (WEEKDAY, scale=0.45745531845608445) across 11 open hours
23:01:23.058 [pool-4-thread-13] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 625 arrivals for 2026-06-06 (WEEKEND, scale=0.41084852191452337) across 11 open hours
23:01:23.058 [pool-4-thread-10] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 518 arrivals for 2026-06-03 (WEEKEND, scale=0.32323562324486843) across 11 open hours
23:01:23.068 [pool-4-thread-14] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 722 arrivals for 2026-06-07 (WEEKEND, scale=0.4345768927352173) across 11 open hours
23:01:23.911 [pool-4-thread-8] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 987 arrivals for 2026-06-01 (WEEKDAY, scale=1.046819216936731) across 11 open hours
23:01:24.029 [pool-4-thread-12] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 975 arrivals for 2026-06-02 (WEEKDAY, scale=1.0804568970933934) across 11 open hours
23:01:24.115 [pool-4-thread-9] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 858 arrivals for 2026-06-03 (WEEKDAY, scale=0.9200830239606862) across 11 open hours
23:01:24.134 [pool-4-thread-11] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 888 arrivals for 2026-06-04 (WEEKDAY, scale=0.9543419520223091) across 11 open hours
23:01:24.225 [pool-4-thread-5] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 894 arrivals for 2026-06-05 (WEEKDAY, scale=1.007718972820961) across 11 open hours
23:01:24.421 [pool-4-thread-3] INFO  c.m.twin.history.HistoryDayRunner - traffic: scheduled 1617 arrivals for 2026-06-06 (WEEKEND, scale=1.0047508831529184) across 11 open hours
23:01:39.845 [pool-4-thread-3] INFO  c.m.twin.history.HistoryGenerator -   … 20/20 store-days
  ✓ byte-identical on re-run — MANIFEST.csv
  ✓ byte-identical on re-run — commerce_store_hourly.csv
  ✓ byte-identical on re-run — commerce_department_daily.csv
  ✓ byte-identical on re-run — traffic_store_hourly.csv
  ✓ byte-identical on re-run — traffic_department_daily.csv
  ✓ byte-identical on re-run — behavioral_fixture_daily.csv
  ✓ byte-identical on re-run — compliance_weekly.csv
  ✓ byte-identical on re-run — tasks.csv
  ✓ byte-identical on re-run — alarms.csv
  ✓ byte-identical on re-run — stocktakes.csv
  ✓ byte-identical on re-run — kpi_targets.csv
  ✓ a different seed yields a different history
[3] the generated year at out/history-2026-10
  ✓ MANIFEST header is the contract's
  ✓ MANIFEST lists every data file once
  ✓ MANIFEST rows = file rows — commerce_store_hourly.csv
  ✓ MANIFEST sha256 matches — commerce_store_hourly.csv
  ✓ MANIFEST seed is the run's — commerce_store_hourly.csv
  ✓ MANIFEST rows = file rows — commerce_department_daily.csv
  ✓ MANIFEST sha256 matches — commerce_department_daily.csv
  ✓ MANIFEST seed is the run's — commerce_department_daily.csv
  ✓ MANIFEST rows = file rows — traffic_store_hourly.csv
  ✓ MANIFEST sha256 matches — traffic_store_hourly.csv
  ✓ MANIFEST seed is the run's — traffic_store_hourly.csv
  ✓ MANIFEST rows = file rows — traffic_department_daily.csv
  ✓ MANIFEST sha256 matches — traffic_department_daily.csv
  ✓ MANIFEST seed is the run's — traffic_department_daily.csv
  ✓ MANIFEST rows = file rows — behavioral_fixture_daily.csv
  ✓ MANIFEST sha256 matches — behavioral_fixture_daily.csv
  ✓ MANIFEST seed is the run's — behavioral_fixture_daily.csv
  ✓ MANIFEST rows = file rows — compliance_weekly.csv
  ✓ MANIFEST sha256 matches — compliance_weekly.csv
  ✓ MANIFEST seed is the run's — compliance_weekly.csv
  ✓ MANIFEST rows = file rows — tasks.csv
  ✓ MANIFEST sha256 matches — tasks.csv
  ✓ MANIFEST seed is the run's — tasks.csv
  ✓ MANIFEST rows = file rows — alarms.csv
  ✓ MANIFEST sha256 matches — alarms.csv
  ✓ MANIFEST seed is the run's — alarms.csv
  ✓ MANIFEST rows = file rows — stocktakes.csv
  ✓ MANIFEST sha256 matches — stocktakes.csv
  ✓ MANIFEST seed is the run's — stocktakes.csv
  ✓ MANIFEST rows = file rows — kpi_targets.csv
  ✓ MANIFEST sha256 matches — kpi_targets.csv
  ✓ MANIFEST seed is the run's — kpi_targets.csv
  ✓ header — commerce_store_hourly.csv
  ✓ header — commerce_department_daily.csv
  ✓ header — traffic_store_hourly.csv
  ✓ header — traffic_department_daily.csv
  ✓ header — behavioral_fixture_daily.csv
  ✓ header — compliance_weekly.csv
  ✓ header — tasks.csv
  ✓ header — alarms.csv
  ✓ header — stocktakes.csv
  ✓ header — kpi_targets.csv
  ✓ no hourly row outside operating hours (10:00–21:00 local, 1 h windows) — commerce_store_hourly.csv
  ✓ eleven hourly rows per open store-day — commerce_store_hourly.csv
  ✓ every window inside 2026-01-01 .. 2026-10-05T00:00:00Z — commerce_store_hourly.csv
  ✓ no duplicate (store, window) — commerce_store_hourly.csv
  ✓ no hourly row outside operating hours (10:00–21:00 local, 1 h windows) — traffic_store_hourly.csv
  ✓ eleven hourly rows per open store-day — traffic_store_hourly.csv
  ✓ every window inside 2026-01-01 .. 2026-10-05T00:00:00Z — traffic_store_hourly.csv
  ✓ no duplicate (store, window) — traffic_store_hourly.csv
  ✓ closed holidays carry no rows
  ✓ daily windows are the operating day [10:00, 21:00) local — commerce_department_daily.csv
  ✓ every window inside the backfill range — commerce_department_daily.csv
  ✓ daily windows are the operating day [10:00, 21:00) local — traffic_department_daily.csv
  ✓ every window inside the backfill range — traffic_department_daily.csv
  ✓ daily windows are the operating day [10:00, 21:00) local — behavioral_fixture_daily.csv
  ✓ every window inside the backfill range — behavioral_fixture_daily.csv
  ✓ every site slug resolves — commerce_store_hourly.csv
  ✓ every site slug resolves — commerce_department_daily.csv
  ✓ every site slug resolves — traffic_store_hourly.csv
  ✓ every site slug resolves — traffic_department_daily.csv
  ✓ every site slug resolves — behavioral_fixture_daily.csv
  ✓ every site slug resolves — compliance_weekly.csv
  ✓ every site slug resolves — tasks.csv
  ✓ every site slug resolves — alarms.csv
  ✓ every site slug resolves — stocktakes.csv
  ✓ every site slug resolves — kpi_targets.csv
  ✓ every department resolves to a REGION zone by name — commerce_department_daily.csv
  ✓ every department resolves to a REGION zone by name — traffic_department_daily.csv
  ✓ every fixture code resolves to a FIXTURE zone
  ✓ every task zone key resolves to exactly one zone (name or code)
  ✓ every alarm sku (EAN) is in the store's assortment
  ✓ conversion_rate ≤ 1 and = transactions ÷ visits in the same window
  ✓ avg_basket_value = value ÷ txn, units_per_transaction = units ÷ txn
  ✓ transit_count ≤ visit_count
  ✓ department sums = the store's hourly sums for the day (units, value, transactions)
  ✓ every store-day has department rows and hourly rows alike
  ✓ department visits ≤ the store's visits that day
  ✓ fixture impressions (distinct shoppers) ≤ the store's visits that day
  ✓ no negative value anywhere
  ✓ task.type ∈ the live CHECK list
  ✓ task.priority ∈ low/normal/high/urgent
  ✓ task.status ∈ the task_status enum
  ✓ kpi metric_type ∈ sales_value · visit_count · conversion_rate
  ✓ kpi: one row per store × metric
  ✓ stocktakes: mode general, scanned = expected − missing + ghost
  ✓ compliance: score 0..100, counts consistent (drift, remediated ≤ non_compliant ≤ targets)
  ✓ compliance weeks start on Mondays
  ✓ compliance scores the trailing partial week
  ✓ open work only in the store's last 3 days
  ✓ ≥ 85 % of past tasks completed within a day
  ✓ task created/started/completed timestamps inside operating hours
  ✓ task timestamps ordered created ≤ started ≤ completed
  ✓ 3+ tasks every store-day
  ✓ unresolved alarms only within the last 14 days
  ✓ alarms carry gate CS-01, an EPC and a severity
  ✓ alarm created_at falls inside operating hours (close − 1 s at the latest)
  ✓ 2–4 gate alarms per store-week (chain mean)
  ✓ dec-fr-bordeaux: visits within ±5.0% of the model
  ✓ dec-fr-bordeaux: transactions within ±5.0% of the model
  ✓ dec-fr-bordeaux: units within ±5.0% of the model
  ✓ dec-fr-bordeaux: sales within ±5.0% of the model (txn × atv_usd)
  ✓ dec-fr-lille: visits within ±5.0% of the model
  ✓ dec-fr-lille: transactions within ±5.0% of the model
  ✓ dec-fr-lille: units within ±5.0% of the model
  ✓ dec-fr-lille: sales within ±5.0% of the model (txn × atv_usd)
  ✓ dec-fr-lyon: visits within ±5.0% of the model
  ✓ dec-fr-lyon: transactions within ±5.0% of the model
  ✓ dec-fr-lyon: units within ±5.0% of the model
  ✓ dec-fr-lyon: sales within ±5.0% of the model (txn × atv_usd)
  ✓ dec-fr-marseille: visits within ±5.0% of the model
  ✓ dec-fr-marseille: transactions within ±5.0% of the model
  ✓ dec-fr-marseille: units within ±5.0% of the model
  ✓ dec-fr-marseille: sales within ±5.0% of the model (txn × atv_usd)
  ✓ dec-fr-paris: visits within ±5.0% of the model
  ✓ dec-fr-paris: transactions within ±5.0% of the model
  ✓ dec-fr-paris: units within ±5.0% of the model
  ✓ dec-fr-paris: sales within ±5.0% of the model (txn × atv_usd)
  ✓ dec-kr-busan: visits within ±5.0% of the model
  ✓ dec-kr-busan: transactions within ±5.0% of the model
  ✓ dec-kr-busan: units within ±5.0% of the model
  ✓ dec-kr-busan: sales within ±5.0% of the model (txn × atv_usd)
  ✓ dec-kr-seoul: visits within ±5.0% of the model
  ✓ dec-kr-seoul: transactions within ±5.0% of the model
  ✓ dec-kr-seoul: units within ±5.0% of the model
  ✓ dec-kr-seoul: sales within ±5.0% of the model (txn × atv_usd)
  ✓ dec-us-denver: visits within ±5.0% of the model
  ✓ dec-us-denver: transactions within ±5.0% of the model
  ✓ dec-us-denver: units within ±5.0% of the model
  ✓ dec-us-denver: sales within ±5.0% of the model (txn × atv_usd)
  ✓ dec-us-nyc: visits within ±5.0% of the model
  ✓ dec-us-nyc: transactions within ±5.0% of the model
  ✓ dec-us-nyc: units within ±5.0% of the model
  ✓ dec-us-nyc: sales within ±5.0% of the model (txn × atv_usd)
  ✓ dec-us-sf: visits within ±5.0% of the model
  ✓ dec-us-sf: transactions within ±5.0% of the model
  ✓ dec-us-sf: units within ±5.0% of the model
  ✓ dec-us-sf: sales within ±5.0% of the model (txn × atv_usd)
  ✓ Marseille is below plan in every quarter (< −10 %)
  ✓ Lille turns around in Q3: H1 below plan, September above, ≥ 15 points of swing
  ✓ Denver has the highest sales in the US
  ✓ one slow category chain-wide: 'climb' has the lowest ABSOLUTE department sales
  ✓ Denver: Climbing & Mountaineering is the lowest department in absolute sales
  ✓ no department above 42 % of a store's sales (52 % in a two-department store)
  ✓ Denver: Hiking, Trekking & Camping is the largest department at 28–36 %
  ✓ snow sells in winter, not in July (Jan > 2.5 × Jul)
  ✓ promo 'Spring Sports Days' lifts chain visits ≥ 20 % over the weeks around it
  ✓ promo 'Summer Sports Week' lifts chain visits ≥ 20 % over the weeks around it
  ✓ promo 'Back-to-School Sports' lifts chain visits ≥ 20 % over the weeks around it
  ✓ the French five dip in August (≥ 15 % below July); the US does not
  ✓ a January trough and a July peak (Jul ≥ 1.3 × Jan)
  ✓ Seoul's pre-Chuseok surge (Sep 19–23 ≥ 10 % over Aug 29–Sep 2, same weekdays, no promo)
  ✓ Chuseok day (Sep 25) is closed in Korea
  ✓ Marseille's compliance averages 70–80 %
  ✓ every other store averages 85–95 %
  ✓ compliance dips the week after each promo (≥ 4 points)
  ✓ the history starts at the beginning of January
```

</details>

## 5. Where the brief could not be honored as written, and why

1. **US stores end on Oct 3, not Oct 4.** Mig 259 refuses any commerce window that ends after `2026-10-05` (00:00 UTC, assuming the session TimeZone is UTC). A US store's local Oct 4 closes at 03:00–04:00Z on Oct 5. So the whole US Oct-4 day is dropped. It is not clipped, because a half day would read as a sales dip. Europe and Korea run to Oct 4. The § 4 recent-window round should therefore start the US stores at local Oct 4.
2. **Chuseok dates.** The brief says "Chuseok (Oct 3–6, 2026)", but Chuseok 2026 is **Sep 24–26**. The day itself is Sep 25, and Oct 3 is Gaecheonjeol. The generator uses the real dates:
   - a five-day pre-holiday gift surge (Seoul +25 %, Busan +12 %)
   - Sep 24 and Sep 26 as holiday-shaped days, Sep 25 closed
   - Oct 3 as a holiday-shaped Saturday

   Seollal (Feb 16–18, Feb 17 closed) follows the same pattern. If the Coordinator wants Oct 3–6 anyway, it is one map in `StoryCalendar.kt`.
3. **MANIFEST has 10 rows, not 11.** There is one row per data file, because a file cannot carry its own sha256. `generated_at` is the generator commit's time rather than the wall clock, so the MANIFEST also reproduces byte for byte. Override it with `M8TRX_HISTORY_GENERATED_AT`.
4. **Row counts differ from the brief's estimates** because the brief's own rules produce different numbers:

   | File | Rows | Why it differs from the estimate |
   |---|---:|---|
   | `tasks.csv` | 13,388 | 3–6 per store-day × 2,753, plus an `investigate` per alarm and a `recovery` per recovered alarm. The "~8 k" estimate does not follow from 3–6 a day |
   | `commerce_department_daily.csv` | 13,219 | Only regions that carry merchandise: 2–7 per store, 48 in all, not ~11 per store |
   | `traffic_department_daily.csv` | 18,725 | Every region with browsable fixtures, including Accessories Wall and GPS & Accessories |
   | `compliance_weekly.csv` | 390 | Mondays from Jan 5 to the last week with at least Mon..Sat in range: 39 weeks for every store (the US stores' Oct 3 cut-off still scores the week of Sep 28) |
   | `alarms.csv` | 1,047 | 2.7 per store-week, inside the 2–4 target |
5. **Alarm `sku` is the EAN.** The keys export carries no skus. The EAN is the only per-variant-unique code in the assortment (2,586 distinct of 2,586; `item_cd` has duplicates), and it matches the brief's "2,586 distinct". Every alarm EAN is checked against the store's assortment, and the EPC is a real EPC of that EAN from the store's `epcs.csv`. Severity is `critical` at $300 or more, otherwise `warning`. Resolutions are `recovered`, `false_alarm` or `not_recovered`. Verify the severity and resolution vocabulary against the API before the sending round.
6. **Stocktakes start at 08:00 local, before opening,** because an RFID count walks an empty store. Every other timestamp falls inside 10:00–21:00 local.
7. **`kpi_targets.currency` is `USD` on all three metrics,** as the contract shows. It is meaningless on visit_count and conversion_rate, so drop it there if the column is nullable. The plan is the operating model at the store's area, with seasonality, holidays and promos but **without** the store's own story, at ATV $58, monthly.
8. **Calibration, not new models.**
   - (a) The basket sampler rounds `2.2 ± 0.8` to a mean of 2.25 units, and its nearest-priced draw over the chain catalog put ATV at $60–62 in the first trial. The history gives the SAME sampler calibrated targets: a unit target of 2.160 (exact closed form) and a per-store line-price target ($22.69–24.25 after the department mix below, fixed-seed Monte-Carlo), corrected each month for the season's department mix and interpolated by day, so the ATV stays level through the year. The year then ties out to $58 and 2.2.
   - (b) The model's `shoplift_baseline` of 0.003 would give about 20 alarms per Denver week. The history sets the shoplift share per store-day to give about 3 a week (the brief asks for 2–4).
9. **Attribution rules** (the code comments in `HistoryDayRunner` state these too):
   - A shopper's visit, transaction and session dwell land in the hour they ARRIVED. This guarantees conversion ≤ 1 in every hour.
   - Money is tallied in integer cents, so department sums equal store sums exactly.
   - Department `transaction_count` goes to the basket's primary (highest-value) department, so it also sums exactly.
   - Department traffic counts shoppers with an oracle impression at a fixture in that region. Its dwell is those impressions' seconds per visitor.
   - `transit_count` counts sessions under 5 minutes (6.6 % of visits).
   - `queue_length` uses Little's law: transactions per hour × mean checkout dwell.
   - `staff_movement_count` comes from the model's `staffing` block: associates by area / 120 m², 6 moves an hour each.
   - Fixture `engagement` counts units picked up there (bought plus concealed). The journeys emit no other pick-up event.
   - Every keyed fixture gets a row every open day, zeros included.
10. **Department mix (review F1, fixed).** Hiking, Trekking & Camping carried about 70–80 % of sales because §5's `outdoor` and `fitness_equipment` buckets both map to the `outdoor` category, and the nearest-priced SKU to a ~$26 draw there is a hike_camp one. The default sampler and the bucket map are untouched (`scenarioSelfTest` 37/37 depends on them). The history now steers the mix with department acceptance weights that are MEASURED per store: `StoreCatalog.calibrateDepartmentWeights` runs a damped fixed point over fixed-seed passes of the real accept/redraw process until each department's share of sales value lands on a target, jointly with the ATV price target. The targets (`StoryCalendar.departmentSalesTargets`) are relative weights hike 27, running 18, cycling 16, snow 11, climb 5, water 2, general 8 (or 18 where it carries 100+ SKUs), held to a 34 % cap per department (50/50 in a two-department store). The seasonal factors then multiply the base weights; Hiking & Camping's seasonal swing was softened from 0.70–1.40 to 0.85–1.20 so July does not push it past 40 %. Result: Denver 35.9 %, no other store above 38.5 % apart from Busan (two departments, 50/50), chain 37.4 % (Busan and the two-three-department French stores pull it up).
11. **Determinism.** The self-test asserts it per file: a re-run gives byte-identical sha256s, and a different seed gives different files. Across the earlier runs, stores the fixes did not touch produced identical totals.

## 6. TODO, not in this round
- **§ 4 recent window (Oct 4 → Oct 16):** `M8TRX_DAY_EPOCH_MODE=plan|wall` in `DayDrive`, `M8TRX_STREAM_DATE` in `SaleStream`/`ChainActivityStream`, and `M8TRX_ALARM_DATE` in `connectAlarmDrive` are deliberately not implemented. That is a separate round, after the core fixes. Per § 5.1, the US stores' recent window starts at local Oct 4.
- **Sending `alarms.csv`** through the API, with `created_at` set to the event time, is a later round.
- The brief's two Sonnet review rounds have not run.
- A fix between the two full runs: a DST bug put a Denver task at 21:08 local on Mar 8 (US DST day). Local times are now built from the local date-time instead of midnight + minutes. The French August anchor moved to the model's range floor (0.75), and the Chuseok-surge check now uses a baseline clear of the Sep promo week. All three are in `37b39e1`.

## 7. Hand-off
Copy `out/history-2026-10/` (the ten data files + MANIFEST.csv) to mother `/root/m8trx-backfill/2026-10/`. Re-run `scripts/keys_check.py` only if the keys export is refreshed. Deletions: none. Mother writes by twin: none.

## 8. Fix round 1 (review findings, `REVIEW-1-HISTORY-GENERATOR.md`) — what was done per finding

Each finding was re-derived against the code before any change. None was wrong; F3 was accepted by the Coordinator.

| finding | disposition |
|---|---|
| F1 Hiking ≈ 80 % of Denver | Confirmed (Denver 70.8 % over the year, from `Journeys.kt` `mappedUnitMix`). Fixed by measured department weights, § 5.10. Denver 35.9 %, chain 37.4 %, ATV level through the year, chain total $27.73 M (Δ sales within ±1.4 % per store). |
| F2 slow category invisible | Confirmed (`StoryChecks` asserted per-SKU only). Climbing is now the lowest in absolute sales; the self-test asserts it chain-wide and for Denver (§ 5.12). |
| F3 flat targets vs seasonality | Accepted as is; the script line is in § 3. |
| F4 four stores below plan | Confirmed (NYC 0.97×0.95, SF 0.94×0.98). NYC is now 1.00×1.00, SF 0.99×1.00. Marseille (−23.4 %) and Lille (−11.5 %, H1-low/H2-high) kept. ±5 % is measured against the storied plan (§ 3). |
| Low: alarms after close | Confirmed (Lille, Lyon, Paris, Busan, Seoul). `created_at` is clamped to close − 1 s on BOTH the open and the resolved branch (the first commit missed the resolved branch, caught by the new validator check), and `resolved_at` follows the clamped time. The validator now checks alarm times against operating hours. |
| Low: trailing partial compliance week | Confirmed. A week is scored when the store's last day is ≥ week_start + 5, so the US stores now carry the week of Sep 28 (390 rows, was 387); the validator asserts it. |
| Low: Marseille stocktake 3–4 % | Kept as the shrink story, deliberately: the nine monthly counts miss 3.07–3.99 %, above the brief's 1–3 %. |
| Info: CRLF | Left as is (the loader accepts it). |

### 5.12 Watersports caveat
"Lowest" is asserted among departments with a real assortment (more than 100 SKUs chain-wide). Watersports is a 7-SKU token department in Paris and NYC only ($159 k, 0.6 % of the chain), and is smaller than Climbing ($1.15 M). A chain-wide department chart will show Watersports at the bottom. Making Climbing lowest even there would need Watersports at roughly 7 % of two stores' sales out of 7 SKUs, which is not believable. Decision for the Coordinator: leave it, or drop the 7-SKU department from the department files.
