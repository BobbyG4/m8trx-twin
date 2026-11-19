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

# Denver reset night: LIVE run-card (`storeDayReset`, reset date 2026-11-19)

Run by hand on the box as `m8trx` from `/opt/m8trx/twin` (the chain paths are relative to it), on the merged branch. Nothing here is installed
as a service. One JVM does the whole night; the reset HOLD file stops it, the sent-ledger resumes it.

**Reset date 2026-11-19.** Denver's local midnight of 11-19 has already passed, so the old-directive end and the new plan are not held back
by the midnight gate: they go at the END of the run, after every receive and move. (A later date would make the run wait for that midnight.)

## 0. Box facts this card relies on

- **Keys:** `/opt/m8trx/twin/.env` holds the tenant id, the integration slug, the webhook key, the service bearer (receive), the read bearer
  and the ops password, as the `M8TRX_*` variables the code reads (`M8TRX_TENANT_ID`, `M8TRX_CONNECT_INTEGRATION_SLUG`, `M8TRX_TWIN_WEBHOOK_KEY`,
  `M8TRX_TWIN_SERVICE_BEARER`, `M8TRX_TWIN_BEARER`, `M8TRX_OPS_PASSWORD`). Load it with `set -a; . /opt/m8trx/twin/.env; set +a` and check
  with `env | grep -c '^M8TRX_'` (there is no `store-ops.env`).
- **HOLD is NOT the reset's file.** `/opt/m8trx/twin-ops/HOLD` is shared: the hourly pass exits on it and the live plane ENDS on it
  (restarting the live plane is Bob's word). The reset uses its own file, `M8TRX_RESET_HOLD_FILE`, default **`/opt/m8trx/twin-ops/reset-HOLD`**.
- **Two writers:** the box's hourly pass (`twin-hourly.sh`, deploy-local, not edited by this repo) runs `scripts/demo/day.sh` for ALL ten stores
  and does not read the owner file. It takes `flock -w 60 /opt/m8trx/twin-ops/lock`; when another holder has it, it logs
  "another twin run holds the lock - pass skipped" and exits 0. So the reset runs UNDER that lock: every hourly pass is skipped, for all stores,
  while it runs; the passes after it catch up yesterday and today.
- **Owner file:** `/opt/m8trx/twin-ops/store-ops-owner.csv` does not exist yet. The reset requires it to say `dec-us-denver,storeday`
  (the hourly pass ignores the file; the lock is what keeps it away).

## 1. Gates (the JVM refuses, exit 3, nothing sent, if one is not met)

| Gate | How to satisfy it |
|---|---|
| owner = `storeday` | `printf 'slug,owner\ndec-us-denver,storeday\n' > /opt/m8trx/twin-ops/store-ops-owner.csv` |
| receive probe passed | `install -D -m 644 ops/reset-probe-result.example.json /opt/m8trx/twin/.twin-state/reset/probe-result-dec-us-denver.json` (passed=true, `at` 2026-11-19T09:13:05Z, request id `rst-dec-us-denver-20261120-r-a39ac9b48b`, received 50 created 50 errors [], 50/50 sku + in_stock, server read 50/50 in the Back Room; every `checks` value must be true) |
| old directive | save the store's `/compliance/state` JSON, then `M8TRX_RESET_COMPLIANCE_JSON=<that file> java -cp "/opt/m8trx/twin-ops/store-ops-dist/*" com.m8trx.twin.storeops.ResetLiveMainKt` (or `./gradlew storeDayResetOldDirective`) writes `.twin-state/reset/old-directive-dec-us-denver.json` |
| spot count | WITH `M8TRX_OPS_PASSWORD` the tool always reads the platform (`StoreStock.fromPlatform`, Hasura as the store manager) for the 20 fixtures the report lists and STOPS before any send if more than 2% of the sampled units disagree. `M8TRX_RESET_SPOTCOUNT_ACK="<who counted, which fixtures, how many disagreed>"` is used ONLY if the read cannot run (no password, or the read errors); the log says which path was taken |
| keys | the `M8TRX_*` variables above |
| pace | a live run refuses `M8TRX_RESET_MOVE_CHUNK` over 25, `M8TRX_RESET_MOVE_EVERY_S` under 3.5, and receive requests over 50 tags |
| rack zone ids | resolved from `/spatial/identity` (zone code `BR-nn`, else name "Backroom Fixture n"); if a rack is not found the JVM refuses and names it: write `BR-nn,<zone id>` lines in `.twin-state/reset/backroom-zones-dec-us-denver.csv` (wins over the read) |

## 2. Plan first (dry run, sends nothing) and read the report

```
cd /opt/m8trx/twin
set -a; . /opt/m8trx/twin/.env; set +a
export M8TRX_STREAM_SOLD_LOG=/opt/m8trx/twin/.twin-state/sold-epcs.txt     # the state dir is its parent
export M8TRX_RESET_DATE=2026-11-19 M8TRX_RESET_OUT=/opt/m8trx/twin/out/reset
./gradlew storeOpsDist --offline        # once: the classpath copy used below
java -Xmx6g -cp "/opt/m8trx/twin-ops/store-ops-dist/*" com.m8trx.twin.storeops.StoreDayResetKt
# report: $M8TRX_RESET_OUT/dec-us-denver/reset-2026-11-19/report.md (read the LIVE ESTIMATE)
```

## 3. Start (live), under the hourly lock

Start it with `java -cp` (the primary path): gradle maps the exit codes 3 (refused) and 6 (HOLD) to 1, so under gradle they cannot be told apart.

```
cd /opt/m8trx/twin
set -a; . /opt/m8trx/twin/.env; set +a
export M8TRX_STREAM_SOLD_LOG=/opt/m8trx/twin/.twin-state/sold-epcs.txt
export M8TRX_RESET_DATE=2026-11-19 M8TRX_RESET_OUT=/opt/m8trx/twin/out/reset
export M8TRX_RESET_LIVE=true M8TRX_RESET_CONFIRM=dec-us-denver
export M8TRX_RESET_RECEIVE_LANES=1          # 1 (default) .. 4; tonight most likely 1 (a 50-tag receive holds one of the server's 4 event-loop threads ~50 s)
# export M8TRX_RESET_SPOTCOUNT_ACK="..."    # only for the fallback described in the gates table
nohup flock -w 600 /opt/m8trx/twin-ops/lock \
  java -Xmx6g -cp "/opt/m8trx/twin-ops/store-ops-dist/*" com.m8trx.twin.storeops.StoreDayResetKt \
  > /opt/m8trx/twin-ops/log/reset-live.out 2>&1 &
```

`flock -w 600` waits up to 10 minutes for a running hourly pass to finish, then holds the lock for the whole run (use `-n` instead to fail at once
if the lock is busy). While it is held every hourly pass logs "another twin run holds the lock - pass skipped". Keep requests at 50 tags.

What it does: gates, then plans and FREEZES the schedule (`live-started.json`, `schedule.jsonl` in the out dir), then runs the receive lane(s)
and the move lane together. A move batch waits until every EPC in it was counted by a receive answer (not in `errors[]`); moves of units
already on hand go at once. The directive steps (old end, then the new plan) go last, after every receive and move (the 11-19 midnight has passed).
Backoff: 429 (Retry-After; halt after 30 in a row), any 5xx, no answer; 401/403 pause 10 s once, then halt; webhook timeout 60 s; the receive
client timeout is 150 s and after a timeout or 504 the SAME request waits at least 60 s before it is re-sent (safe: the server finishes the
original). Health must answer a top-level `"status":"ok"` in under 1 s before each receive (move lane: at least every 30 s); 10 failures in a
row on one item halt. Per-tag receive `errors[]`: `internal_error` tags are re-sent later in a retry request of those tags only (id
`<original>-retryN`, tags rotated so each body differs) and the night goes on; STOP on more than 5 `internal_error` tags in a row or more than 10 in
one request, and on ANY `unknown_product`, `undecodable_tag`, `invalid_space`, `not_admitted` or other code, or if an answer does not match its
request (an error tag not in the request, or `received` not equal to tags sent minus distinct error tags). Only counted tags are accepted:
they unblock their moves and are booked in the ledger.

## 4. Watch, ramp, stop, resume

```
OUT=/opt/m8trx/twin/out/reset/dec-us-denver/reset-2026-11-19
tail -f $OUT/live-run.log          # events; one STATUS line a minute: receives in flight, tags received/created, move batches, health ms, ETA
cat $OUT/progress.json             # counts, rewritten every ~5 s
wc -l $OUT/sent-ledger.tsv         # accepted items
```

- **Ramp the receive lanes during the run (no restart):** `echo 2 > /opt/m8trx/twin-ops/reset-lanes`, later `echo 4 > ...` (re-read every
  second; clamped to 1..4; each change is logged; a garbled or half-written file keeps the last valid value; `rm` it to go back to the env value).
  Lowering the number starts no new request until the requests in flight are below it. Never more than 4 in flight.
- **Stop cleanly:** `touch /opt/m8trx/twin-ops/reset-HOLD` (NOT `HOLD`). Requests in flight finish, nothing new starts, exit 6. `rm` it to allow a restart.
- **Resume** (after HOLD, a halt, a crash or a reboot): **wait at least 60 s** first (a receive that was in flight is finished by the server
  anyway), remove `reset-HOLD`, then run step 3 again with the SAME env, `M8TRX_RESET_OUT` and date. The frozen `schedule.jsonl` is reused (the
  ledger changes as units are booked, so it is never re-planned), accepted ids in `sent-ledger.tsv` are skipped, the spot count is not repeated.
  To plan again from scratch use a new `M8TRX_RESET_OUT`.
- Exit codes (with `java -cp`): 0 done, 1 halted (read the last `HALT` line), 3 refused (nothing sent), 6 reset-HOLD.

## 5. After the run: hand Denver back to the hourly pass

When `$OUT/reset-complete.json` exists (the lock is released when the JVM exits):

```
printf 'slug,owner\ndec-us-denver,hourly\n' > /opt/m8trx/twin-ops/store-ops-owner.csv
```

The hourly pass needs no further step and does not read `$OUT/state/post-reset` (that snapshot is only for the store-day driver). The reset
appended, never rewrote, `.twin-state/receive/received-stock.csv` (`epc,ean,slug,fixture,date`, fixture = the BR rack the unit is booked to),
`receive/receive-reset-<yyyymmdd>.sent` and `moves/placements.csv` (`epc,fixture,date`, later line wins); `sold-epcs.txt` and
`missing-epcs.txt` are untouched. What each hourly step reads afterwards:

| hourly step (`day.sh`) | stock view | sees the reset? |
|---|---|---|
| `move` (restock, `ConnectMovementDrive`) | chain `epcs.csv` + `received-stock.csv`, positions from `placements.csv` | yes |
| `stock` (`StockDrive`) | live: the PLATFORM's thing_location (minus `missing-epcs.txt`); dry: `StoreStock.load` (epcs + received - sold, moved by placements) | yes (the platform is the truth) |
| `tasks` (`TaskDrive.soldDown`) | `placements.csv`, else the chain `epcs.csv` fixture | yes for the units it sees (it only looks at sold units) |
| `fixmoves` | fixture geometry only | no stock view |
| `stream` (sales, `DatedStream.allocate`) | chain `epcs.csv` ONLY, minus the sold log; back-of-house seed units first | NO: it ignores `received-stock.csv` and `placements.csv` |

So after the handback the hourly stream keeps selling only unsold chain-seed units (never a reset-received unit, and "wall or back room" is
judged from the seed location, not from where the reset put the unit), while `move`/`stock` see the reset's stock. An EAN whose seed units are
all sold is dropped or substituted by the stream even though the reset put new units of it on the shelf. Fixing that is a change to the sales
allocation (not part of this PR).
