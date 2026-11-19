# ops/ — the box's live people plane (install note)

The box's other twin ops files (`twin-hourly.sh`, `twin-day.sh`, `twin-notify`, `hold`, `resume`, `README`) live only in
`/opt/m8trx/twin-ops/` on the GPU box (192.168.55.31); they were never committed. This folder holds the live plane's three
files so they are reviewed and versioned with the code they run:

| File | Installs to | What it is |
|---|---|---|
| `m8trx-twin-live.service` | `/etc/systemd/system/` | the unit (not a timer — started and stopped by hand) |
| `twin-live.sh` | `/opt/m8trx/twin-ops/` | its ExecStart: refuses on HOLD or a running day replay, runs the JVM from `live-dist/`, posts start / stop / failure to Slack through `twin-notify`, logs to `log/live.log` |
| `live` | `/opt/m8trx/twin-ops/` | the operator's command: `live start [--sites …] [--speed 1\|2\|5\|10] [--shift open\|at:HH:mm\|<h>] [--scale ≤0.6] [--dwell scaled\|real]`, `live stop`, `live speed N`, `live status` |

## Install (on the box, as m8trx, after the clone is on this branch)

```
cd /opt/m8trx/twin
install -m 755 ops/twin-live.sh ops/live /opt/m8trx/twin-ops/
sudo install -m 644 ops/m8trx-twin-live.service /etc/systemd/system/
sudo systemctl daemon-reload
systemd-analyze verify /etc/systemd/system/m8trx-twin-live.service   # prints nothing when clean
ls /opt/m8trx/twin-ops/            # confirm the HOLD file's name is HOLD (the JVM polls $OPS/HOLD; else set M8TRX_LIVE_HOLD_FILE in live.env)
```

Do NOT `enable` the unit: it must not come back by itself after a reboot during office hours.

## Two one-line edits to the box-only scripts (recommended)

1. **The hourly pass must wait, not skip.** `live start` holds the shared lock only for one `./gradlew liveDist`
   (~30-40 s); the service itself is a plain JVM and holds nothing. Today `twin-hourly.sh` takes the lock with `flock -n`,
   which SKIPS a pass that collides. Change it to wait at most a minute:
   `grep -n 'flock' /opt/m8trx/twin-ops/twin-hourly.sh` then replace its `flock -n` with `flock -w 60`.
2. **The day replay must not start while the live plane runs** (both publish to the one twin edge). Near the top of
   `twin-day.sh`, after its HOLD check:
   `systemctl is-active --quiet m8trx-twin-live.service && { "$OPS/twin-notify" "twin · people plane · SKIPPED: the live plane is running"; exit 0; }`
   (`live start` and `twin-live.sh` already refuse in the other direction.)

`hold` needs no edit: the JVM polls the HOLD file every 5 s, evicts every shopper on the floor and exits 0 (no restart).

## Files the live plane writes

- `/opt/m8trx/twin-ops/live.env` — the settings `live` writes (sites, speed, shift, scale, dwell, continue); no secrets.
- `/opt/m8trx/twin-ops/live-dist/` — the runtime classpath copy (`./gradlew liveDist`).
- `/opt/m8trx/twin-ops/live-state.properties` — the plan clock per store, saved every 30 s and at stop (`live speed N` resumes it).
- `/opt/m8trx/twin-ops/log/live.log` — the run log: a `════` line per start/exit, the plan, a `LIVE minute:` rate line a minute,
  the `SIM-RUN live stop` line.

## Slack (through twin-notify → the uptime-watch channel)

- `twin · live · STARTED <KST> · scale … · sites=N publishing=… not_published=… speed=Nx dwell=… shift=… keep=… · stop: …/live stop (or hold)`
- `twin · live · STOPPED <KST> · reason=stopped|HOLD published=… evicted_at_stop=… wall=… plan_clocks=…`
- `twin · live · REFUSED …` (HOLD, the day replay, a bad setting, no store with an edge — never restarted)
- `twin · live · FAILED rc=N … · systemd retries in 30 s (3 tries in 10 min)`

---

# store-ops driver (Phase 3 tooling, 2026-11-17) — NOT INSTALLED, drafted only

Drafted with the Phase 3 tooling slice (`status/briefs/BRIEF-PHASE3-DENVER-LIVE-2026-11-17.md`). **Nothing below has been run on the
box and nothing is installed by this PR.** In that slice the JVM refuses a live run (exit 3), so even an installed unit sends nothing
until the live slice lands and Bob gives the go for the reset night.

| File | Installs to | What it is |
|---|---|---|
| `m8trx-twin-store-ops.service` | `/etc/systemd/system/` | the unit (not a timer; started and stopped by hand) |
| `twin-store-ops.sh` | `/opt/m8trx/twin-ops/` | its ExecStart: refuses on HOLD, runs the JVM from `store-ops-dist/`, Slack through `twin-notify`, logs to `log/store-ops.log` |
| `store-ops` | `/opt/m8trx/twin-ops/` | the operator command: `store-ops start [--stores csv]`, `stop`, `status`, `dry-run` |
| `store-ops-owner.example.csv` | `/opt/m8trx/twin-ops/store-ops-owner.csv` (copy, then edit) | who writes each store: `storeday` or `hourly` |

## Install (on the box, as m8trx, after the clone is on the merged branch; only when Bob says go)

```
cd /opt/m8trx/twin
install -m 755 ops/twin-store-ops.sh ops/store-ops /opt/m8trx/twin-ops/
install -m 644 ops/store-ops-owner.example.csv /opt/m8trx/twin-ops/store-ops-owner.csv   # then edit: Denver = storeday, the rest hourly
sudo install -m 644 ops/m8trx-twin-store-ops.service /etc/systemd/system/
sudo systemctl daemon-reload
systemd-analyze verify /etc/systemd/system/m8trx-twin-store-ops.service   # prints nothing when clean
```

Do NOT `enable` the unit. The driver reads the same HOLD file as the live people plane (`$OPS/HOLD`), so `hold` stops it too.

## The one owner file (two writers must never run for one store)

`/opt/m8trx/twin-ops/store-ops-owner.csv` has `slug,owner` lines. The driver serves only stores listed `storeday`; an unlisted store
belongs to the hourly pass. **The hourly pass has to read the same file.** `twin-hourly.sh` is box-only and this repo does not touch
it; the edit that makes it honour the file is, where it builds its store list:

```
M8TRX_OPS_STORES="$(awk -F, '!/^#/ && $2 ~ /hourly/ {print $1}' "$OPS/store-ops-owner.csv" | paste -sd, -)"   # hourly's stores
```

(and when a store is not in the file at all it must still be served by the hourly pass: add the stores the file does not list.)
Flip Denver in ONE step: stop the hourly pass for it (edit the file to `storeday`), then `store-ops start`. The driver refuses to start
for a store the file still gives to `hourly`, and `store-ops start` re-checks before it builds anything.

## Files it writes

- `/opt/m8trx/twin-ops/store-ops.env`: stores (and later knobs); no secrets.
- `/opt/m8trx/twin-ops/store-ops-dist/`: the runtime classpath copy (`./gradlew storeOpsDist`).
- `<out>/<slug>/state/day-start.{csv,meta}`: the store state at the start of the day being served; a restart re-simulates that day
  from it (same seed, same date, same ids) and the sent-ledger skips what the platform already accepted.
- `/opt/m8trx/twin-ops/log/store-ops.log`: the run log.

## Dry runs (safe anywhere, no network, no credentials)

`./gradlew storeDayReset` (the Denver reset schedule and report under `out/reset/`), `./gradlew storeOpsLiveDryRun` (one simulated
day, what it would send), `./gradlew storeDayGapCheck` (the gap ledger's planned checks), `./gradlew storeOpsSelfTest`.
For a real reset night the reset needs the box's `.twin-state` (`sold-epcs.txt`, `receive/received-stock.csv`, `moves/placements.csv`,
`stock/missing-epcs.txt`) copied right before the run.

---

# Denver reset night: LIVE run-card (`storeDayReset`, built 2026-11-19)

Run by hand on the box as `m8trx`, from `/opt/m8trx/twin` on the merged branch. Nothing here is installed as a service. The JVM does
the whole night in one process; the HOLD file stops it, the sent-ledger resumes it.

## 0. Before you start (all must be true; the JVM refuses, exit 3, if a gate is not met)

| Gate | How to satisfy it |
|---|---|
| owner = `storeday` | `/opt/m8trx/twin-ops/store-ops-owner.csv` has `dec-us-denver,storeday` AND the hourly pass has actually stopped writing Denver (see "The one owner file" above) |
| receive probe passed | `install -D -m 644 ops/reset-probe-result.example.json /opt/m8trx/twin/.twin-state/reset/probe-result-dec-us-denver.json` (content: passed=true, `at` 2026-11-19T09:13:05Z, request id `rst-dec-us-denver-20261120-r-a39ac9b48b`, received 50 created 50 errors [], 50/50 sku + in_stock, server read 50/50 in the Back Room; every `checks` value must be true) |
| old directive | save the store's `/compliance/state` JSON (`cs-PLN-dec-us-denver-....json`), then `M8TRX_RESET_COMPLIANCE_JSON=<that file> M8TRX_STREAM_SOLD_LOG=/opt/m8trx/twin/.twin-state/sold-epcs.txt ./gradlew storeDayResetOldDirective` writes `.twin-state/reset/old-directive-dec-us-denver.json` (id = `directive_ref`, targets with the zone NAME as `raw_fixture_code`) |
| spot count | the tool reads the platform itself (`StoreStock.fromPlatform`, Hasura as the store manager; needs `M8TRX_OPS_PASSWORD`) for the same 20 fixtures the report lists, and STOPS before any send if more than 2% of the sampled units disagree. Fallback if the read cannot run: count by hand and set `M8TRX_RESET_SPOTCOUNT_ACK="<who, which fixtures, how many disagreed>"` (recorded in report.md and live-run.log) |
| keys | `M8TRX_TWIN_SERVICE_BEARER` (receive, inventory:create), `M8TRX_TWIN_BEARER` (read key: the one `/spatial/identity` read for the stockroom rack zone ids), `M8TRX_TWIN_WEBHOOK_KEY` + `M8TRX_TENANT_ID` + `M8TRX_CONNECT_INTEGRATION_SLUG` (moves and directives), all from the env file, never typed into a repo file |
| rack zone ids | resolved from `/spatial/identity` (zone code `BR-nn`, else name "Backroom Fixture n"). If a rack is not found the JVM refuses and names it; write `BR-nn,<zone id>` lines in `.twin-state/reset/backroom-zones-dec-us-denver.csv` (wins over the read) |

## 1. Plan first (dry run, sends nothing), read the report

```
cd /opt/m8trx/twin
export M8TRX_STREAM_SOLD_LOG=/opt/m8trx/twin/.twin-state/sold-epcs.txt   # the state dir is its parent
export M8TRX_RESET_DATE=2026-11-20 M8TRX_RESET_OUT=/opt/m8trx/twin/out/reset
./gradlew storeDayReset --offline        # report: <out>/dec-us-denver/reset-2026-11-20/report.md, read the LIVE ESTIMATE and gates
```

## 2. Start (live)

```
export M8TRX_RESET_LIVE=true M8TRX_RESET_CONFIRM=dec-us-denver
export M8TRX_RESET_RECEIVE_LANES=1          # 1 (default) .. 4. Tonight most likely 1: a 50-tag receive holds one of the server's 4 event-loop threads ~50 s
export M8TRX_RESET_MOVE_EVERY_S=3.75        # default: 25 tags every 3.75 s
export M8TRX_RESET_SPOTCOUNT_ACK=...        # only when you count by hand instead of the platform read
set -a; . /opt/m8trx/twin-ops/store-ops.env; set +a    # the keys, M8TRX_OPS_PASSWORD
nohup ./gradlew storeDayReset --offline > /opt/m8trx/twin-ops/log/reset-live.out 2>&1 &
```
(or the store-ops-dist classpath: `java -Xmx6g -cp "/opt/m8trx/twin-ops/store-ops-dist/*" com.m8trx.twin.storeops.StoreDayResetKt`).
Keep the requests at 50 tags (`M8TRX_RESET_RECEIVE_CHUNK` above 50 is refused: a 200-tag request got a 504).

What it does: gates, then plans and FREEZES the schedule (`<out>/.../live-started.json`, `schedule.jsonl`), then runs receive lane(s) and the
move lane together. A move batch waits until the receive of every EPC in it was answered with no `errors[]` entry; moves of units already on
hand go at once. Directives (old directive end, then the new plan) go last, after every receive and move, and not before the store's
local midnight of the reset date (Denver 2026-11-20 00:00 = 2026-11-20 07:00 UTC = 16:00 KST); the run waits for it, HOLD honoured.
Backoff: 429 (Retry-After), any 5xx, no answer; health GET must answer `"status":"ok"` in under 1 s before each receive (move lane: at
least every 30 s); 10 failures in a row on one item halt. Receive client timeout is 150 s (above the front door's 120 s, so a 504 is the
front door's); after a timeout or 504 the SAME request waits at least 60 s before it is re-sent (safe: the server finishes the original).
Receive `errors[]` per tag: `internal_error` (DB pool briefly short) tags are re-sent later in a retry request of those tags only
(id `<original>-retryN`, different body) and the night goes on; STOP on more than 5 `internal_error` tags in a row or more than 10 in one
request, and on ANY `unknown_product`, `undecodable_tag`, `invalid_space`, `not_admitted` or other code (entries printed in the log). Only
tags counted (not in `errors[]`) are accepted: they unblock their moves and are booked in the ledger. Requests are at most 50 tags (more is refused).

Change the receive lanes DURING the run (the ramp 2 to 4), no restart: `echo 2 > /opt/m8trx/twin-ops/reset-lanes`, later `echo 4 > ...`
(re-read every second; overrides `M8TRX_RESET_RECEIVE_LANES` while the file exists; clamped to 1..4; each change is logged as
`receive lanes changed: a -> b`). `rm` the file to go back to the env value. Never more than 4 requests in flight.

Once a minute the log carries one `STATUS` line: receives in flight (and the lane count), tags received / created of the total, retry
requests, move batches sent, the last health latency in ms, and the ETA (from the rates so far).

## 3. Watch

```
tail -f <out>/dec-us-denver/reset-2026-11-20/live-run.log          # every event, HALT reasons, refused receive entries
cat <out>/dec-us-denver/reset-2026-11-20/progress.json             # receive/move/directive counts, rewritten every ~5 s
wc -l <out>/dec-us-denver/reset-2026-11-20/sent-ledger.tsv         # accepted items (one line each)
```

## 4. Stop and resume

- Stop cleanly: `touch /opt/m8trx/twin-ops/HOLD` (the file the live plane and store-ops use). Requests in flight finish, nothing new starts, exit 6.
  `rm` the file to allow a restart.
- Resume (after HOLD, a halt, a crash or a reboot): remove HOLD, set the SAME env (same `M8TRX_RESET_OUT` and date), run step 2 again. The frozen
  `schedule.jsonl` is reused (the ledger changes as units are booked, so it is never re-planned), accepted ids in `sent-ledger.tsv` are skipped,
  the spot count is not repeated. To plan again from scratch, use a new `M8TRX_RESET_OUT`.
- Exit codes: 0 done, 1 halted (read the last `HALT` line), 3 refused (nothing sent), 6 HOLD.

## 5. After a finished run (`reset-complete.json` exists)

The box ledger was appended as each item was accepted, in the other steps' formats, never rewritten: `.twin-state/receive/received-stock.csv`
(`epc,ean,slug,fixture,date`, fixture = the BR rack the unit is booked to), `.twin-state/receive/receive-reset-<yyyymmdd>.sent`,
`.twin-state/moves/placements.csv` (`epc,fixture,date`, later line wins). So `StoreStock.load` (the hourly pass, store-day sim) now sees the
new units and every moved unit at its new fixture; `sold-epcs.txt` and `missing-epcs.txt` are untouched. The store-day sim continues from
`<out>/dec-us-denver/reset-2026-11-20/state/post-reset` (`M8TRX_STORE_OPS_STATE=<that path without extension>`; it records the plan period so the
sim does not re-issue it). Hand Denver to the store-day driver only after reading the first store-day against the platform.
