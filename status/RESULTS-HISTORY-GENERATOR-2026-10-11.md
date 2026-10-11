# RESULTS — Twin history generator (BRIEF-TWIN-HISTORY-GENERATOR-2026-10-11)

**Lane:** Twin · **Branch:** `feat/history-generator-2026-10` (pushed, no PR, not merged) · **Generator commit that produced the files:** `37b39e1` (on `764cbbc`) · **Seed:** 20261016 · **Date:** 2026-10-11.
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
**Wall time:** **29m17s** for the full year (2,753 store-days, 14 threads, MacBook M4). The first run, on `764cbbc`, took 26m50s.

```
file,rows,sha256,seed,generated_at,generator_git_sha
commerce_store_hourly.csv,30283,8169c9c01bfcddc18486ec5123fdeb68bf5cb3318c4730f8dc91a73cd6f5a4de,20261016,2026-10-11T19:52:37+09:00,37b39e131402ef969ba6af5e5c6937c0ef9f4cd0
commerce_department_daily.csv,13219,9f9340e6d83ce7e204af01bd06d9bdffb748e777045334b65687534ebbdf6a75,20261016,2026-10-11T19:52:37+09:00,37b39e131402ef969ba6af5e5c6937c0ef9f4cd0
traffic_store_hourly.csv,30283,e699a7afe9d0de5a8eb580d5b35e69b4f7db90fa831604d232a9257a191cb320,20261016,2026-10-11T19:52:37+09:00,37b39e131402ef969ba6af5e5c6937c0ef9f4cd0
traffic_department_daily.csv,18725,d78c9afbcaea1c3f25125178e7a8da2fe25a909db6d6c7a41e74105cea11661f,20261016,2026-10-11T19:52:37+09:00,37b39e131402ef969ba6af5e5c6937c0ef9f4cd0
behavioral_fixture_daily.csv,191710,895afb5fe9d0e6c5b0cbfa13219186f87316e076c5ed1ca7409720a9f6b9a772,20261016,2026-10-11T19:52:37+09:00,37b39e131402ef969ba6af5e5c6937c0ef9f4cd0
compliance_weekly.csv,387,a822951f8a8de121631e5bdc161f4d3d78c016aadb0d5b74b953e7ea9ccafe47,20261016,2026-10-11T19:52:37+09:00,37b39e131402ef969ba6af5e5c6937c0ef9f4cd0
tasks.csv,13400,4da80114f77e9f64e88c39dde4a33d377cbc7f8fbe16aa6780deaf5443a364b0,20261016,2026-10-11T19:52:37+09:00,37b39e131402ef969ba6af5e5c6937c0ef9f4cd0
alarms.csv,1054,7648b6e696e8d3cd4b4234111eef3139e45d6be8061226153bf5473c41f360c6,20261016,2026-10-11T19:52:37+09:00,37b39e131402ef969ba6af5e5c6937c0ef9f4cd0
stocktakes.csv,90,1f1f6b84d67b7932c08758b64dbcb4e7d1f03d7b0b363de57d3146da45cb3b5d,20261016,2026-10-11T19:52:37+09:00,37b39e131402ef969ba6af5e5c6937c0ef9f4cd0
kpi_targets.csv,30,2c13563e6858dbddd9e910e0d929e2232eadf6baaee63cdca01e6b2942d6746a,20261016,2026-10-11T19:52:37+09:00,37b39e131402ef969ba6af5e5c6937c0ef9f4cd0
```

## 3. Per-store totals (Jan 1 → Oct 4; US stores end Oct 3, see § 5.1)

| store | days | visits | transactions | conversion | units | sales USD | ATV | compliance mean | Δ visits | Δ txn | Δ units | Δ sales | sales vs plan |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| dec-fr-bordeaux | 275 | 136,506 | 29,916 | 0.219 | 65,924 | 1,750,986 | 58.53 | 87.0 | -0.3% | -0.4% | -0.3% | +0.5% | -1.7% |
| dec-fr-lille | 275 | 199,331 | 41,726 | 0.209 | 91,679 | 2,383,715 | 57.13 | 87.1 | -0.3% | -0.2% | -0.3% | -1.7% | -12.7% |
| dec-fr-lyon | 275 | 192,572 | 43,810 | 0.227 | 96,467 | 2,504,544 | 57.17 | 91.2 | +0.4% | +0.1% | +0.1% | -1.4% | +0.5% |
| dec-fr-marseille | 275 | 122,505 | 23,610 | 0.193 | 51,856 | 1,369,680 | 58.01 | 74.3 | -0.2% | -0.1% | -0.3% | -0.1% | -22.7% |
| dec-fr-paris | 275 | 262,509 | 58,695 | 0.224 | 129,236 | 3,382,076 | 57.62 | 90.4 | +0.3% | +0.1% | +0.2% | -0.5% | +1.8% |
| dec-kr-busan | 275 | 119,297 | 26,757 | 0.224 | 59,009 | 1,559,232 | 58.27 | 89.8 | -0.2% | +0.1% | +0.3% | +0.6% | -2.3% |
| dec-kr-seoul | 275 | 226,057 | 50,636 | 0.224 | 111,393 | 2,893,762 | 57.15 | 93.2 | +0.1% | +0.3% | +0.3% | -1.2% | -1.1% |
| dec-us-denver | 276 | 305,193 | 73,126 | 0.240 | 161,190 | 4,189,564 | 57.29 | 92.0 | +0.3% | +1.3% | +1.5% | +0.0% | +9.0% |
| dec-us-nyc | 276 | 277,241 | 59,580 | 0.215 | 130,953 | 3,442,404 | 57.78 | 89.8 | +0.1% | +1.1% | +1.0% | +0.7% | -7.7% |
| dec-us-sf | 276 | 283,005 | 61,967 | 0.219 | 136,466 | 3,568,028 | 57.58 | 88.8 | +0.1% | -0.1% | +0.0% | -0.8% | -8.1% |
| **chain** | 2753 | 2,124,216 | 469,823 | 0.221 | 1,034,173 | 27,043,991 | | | | | | | |
Δ = generated vs the operating model's expectation for the generated days (model volumes × seasonality × story × weather; units at `units_per_txn` 2.2, sales at `atv_usd` $58). Sales vs plan = vs the KPI plan (the model without the store's story).

How the story beats read in the files (`historySelfTest` [3] asserts all of them):
- **Marseille:** −22.7 % vs plan for the year and below plan every quarter. Compliance mean 74.3.
- **Lille:** −12.7 % for the year. Below plan in H1, above plan by September. Compliance went from 82 to 90 across the turnaround.
- **Denver:** the best US store, $4.19 M against SF $3.57 M and NYC $3.44 M, +9 % vs plan.
- **Slow category:** Climbing & Mountaineering has the lowest sales per SKU chain-wide.
- **Seasonal categories:** snow sells in winter.
- **Promos:** three promo weeks (+35 % visits, +20 % conversion). Each is followed by a compliance dip and remediation tasks.
- **August:** the French five are down about 19 % against July.
- **Korea:** Seoul shows a pre-Chuseok surge. Chuseok and Seollal days are closed.
- **Year shape:** a January trough rising to a July peak.

## 4. Self-test output (verbatim)

`./gradlew ktlintCheck --rerun-tasks` → BUILD SUCCESSFUL.
`./gradlew scenarioSelfTest peopleSelfTest connectSelfTest historySelfTest`:

```
c.m8trx.twin.layer3.ScenarioSelfTest - scenarioSelfTest — 37 passed, 0 failed
c.m8trx.twin.layer1.PeopleSelfTest - peopleSelfTest — 50 passed, 0 failed
c.m8trx.twin.connect.ConnectHarness - === ALL OFFLINE SELF-TESTS PASSED ===
BUILD SUCCESSFUL in 4m 6s
```

`python3 scripts/keys_check.py out/history-2026-10`:

```
keys_check: out/history-2026-10 against reference/data/chain/keys-2026-10-11.csv
  alarm_sku                 834 distinct    0 unresolved
  department_zone_name       68 distinct    0 unresolved
  fixture_code              696 distinct    0 unresolved
  site_slug                  10 distinct    0 unresolved
  task_zone_key             583 distinct    0 unresolved
  dump → out/history-2026-10/keys_used.csv
RESULT: ALL KEYS RESOLVE
```

<details><summary>historySelfTest, every line (304 checks)</summary>

```
[1] four weeks, all ten stores — 2026-09-07 .. 2026-10-04
  generated 275 store-days in 3m01s

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
  ✓ open work only in the store's last 3 days
  ✓ ≥ 85 % of past tasks completed within a day
  ✓ task created/started/completed timestamps inside operating hours
  ✓ task timestamps ordered created ≤ started ≤ completed
  ✓ 3+ tasks every store-day
  ✓ unresolved alarms only within the last 14 days
  ✓ alarms carry gate CS-01, an EPC and a severity
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
  ✓ open work only in the store's last 3 days
  ✓ ≥ 85 % of past tasks completed within a day
  ✓ task created/started/completed timestamps inside operating hours
  ✓ task timestamps ordered created ≤ started ≤ completed
  ✓ 3+ tasks every store-day
  ✓ unresolved alarms only within the last 14 days
  ✓ alarms carry gate CS-01, an EPC and a severity
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
  ✓ one slow category chain-wide: 'climb' has the lowest sales per SKU carried
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

historySelfTest — 304 passed, 0 failed
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
   | `tasks.csv` | 13,400 | 3–6 per store-day × 2,753, plus an `investigate` per alarm and a `recovery` per recovered alarm. The "~8 k" estimate does not follow from 3–6 a day |
   | `commerce_department_daily.csv` | 13,219 | Only regions that carry merchandise: 2–7 per store, 48 in all, not ~11 per store |
   | `traffic_department_daily.csv` | 18,725 | Every region with browsable fixtures, including Accessories Wall and GPS & Accessories |
   | `compliance_weekly.csv` | 387 | Only ISO weeks wholly inside each store's range: 39 weeks, 38 for US stores |
   | `alarms.csv` | 1,054 | 2.7 per store-week, inside the 2–4 target |
5. **Alarm `sku` is the EAN.** The keys export carries no skus. The EAN is the only per-variant-unique code in the assortment (2,586 distinct of 2,586; `item_cd` has duplicates), and it matches the brief's "2,586 distinct". Every alarm EAN is checked against the store's assortment, and the EPC is a real EPC of that EAN from the store's `epcs.csv`. Severity is `critical` at $300 or more, otherwise `warning`. Resolutions are `recovered`, `false_alarm` or `not_recovered`. Verify the severity and resolution vocabulary against the API before the sending round.
6. **Stocktakes start at 08:00 local, before opening,** because an RFID count walks an empty store. Every other timestamp falls inside 10:00–21:00 local.
7. **`kpi_targets.currency` is `USD` on all three metrics,** as the contract shows. It is meaningless on visit_count and conversion_rate, so drop it there if the column is nullable. The plan is the operating model at the store's area, with seasonality, holidays and promos but **without** the store's own story, at ATV $58, monthly.
8. **Calibration, not new models.**
   - (a) The basket sampler rounds `2.2 ± 0.8` to a mean of 2.25 units, and its nearest-priced draw over the chain catalog put ATV at $60–62 in the first trial. The history gives the SAME sampler calibrated targets: a unit target of 2.160 (exact closed form) and a per-store line-price target ($25.43–25.93, fixed-seed Monte-Carlo). The year then ties out to $58 and 2.2.
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
10. **One planner-visible oddity that is outside this round:** Hiking, Trekking & Camping carries about 70 % of chain sales. 1,219 of the 2,586 SKUs are `hike_camp`, and the sampler's `outdoor` and `fitness_equipment` buckets both draw from it. That comes from the existing catalog and sampler. Recalibrating the §5 mix is the decision already flagged in `StoreCatalog`.
11. **Determinism.** The self-test asserts it per file: a re-run gives byte-identical sha256s, and a different seed gives different files. Across the two full-year runs, the KR and US stores, which the fix between the runs did not touch, produced identical totals.

## 6. TODO, not in this round
- **§ 4 recent window (Oct 4 → Oct 16):** `M8TRX_DAY_EPOCH_MODE=plan|wall` in `DayDrive`, `M8TRX_STREAM_DATE` in `SaleStream`/`ChainActivityStream`, and `M8TRX_ALARM_DATE` in `connectAlarmDrive` are deliberately not implemented. That is a separate round, after the core fixes. Per § 5.1, the US stores' recent window starts at local Oct 4.
- **Sending `alarms.csv`** through the API, with `created_at` set to the event time, is a later round.
- The brief's two Sonnet review rounds have not run.
- A fix between the two full runs: a DST bug put a Denver task at 21:08 local on Mar 8 (US DST day). Local times are now built from the local date-time instead of midnight + minutes. The French August anchor moved to the model's range floor (0.75), and the Chuseok-surge check now uses a baseline clear of the Sep promo week. All three are in `37b39e1`.

## 7. Hand-off
Copy `out/history-2026-10/` (the ten data files + MANIFEST.csv) to mother `/root/m8trx-backfill/2026-10/`. Re-run `scripts/keys_check.py` only if the keys export is refreshed. Deletions: none. Mother writes by twin: none.
