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
| existence pre-check (GATING) | the ledger EPCs of the 20 sampled fixtures (at most 2,000 EPCs) are read through the bearer `POST /inventory/items/details` in chunks of 200 (the read key `M8TRX_TWIN_BEARER`, inventory:read). MORE than 2% not `in_stock` with an `itemId` STOPS the run before any send. A location mismatch is NOT a gate: the October put-aways (the 3,092 units from 10-12) were booked in `placements.csv` but never sent, and the reset's moves place them |
| keys | the `M8TRX_*` variables above |
| pace | a live run refuses `M8TRX_RESET_MOVE_CHUNK` over 25, `M8TRX_RESET_MOVE_EVERY_S` under 3.5, and receive requests over 50 tags |
| zone ids (EVERY move is `to_zone_id`) | the server resolves a `to_fixture_code` only by an exact zone NAME (or a per-site mapping, Denver has one row), so a code-addressed move is refused `destination_unresolved` while the webhook still answers 200. So no move uses a fixture code. Floor fixtures: the zone ids in the schedule come from the keys export (the dry run uses them and says so); the live run reads `/spatial/identity` once and cross-checks every one by zone code, else name, and REFUSES naming the code if one is missing or the two ids differ. Back-room racks: resolved from the same read (zone code `BR-nn`, else name "Backroom Fixture n"); if a rack is not found the JVM refuses and names it: write `BR-nn,<zone id>` lines in `.twin-state/reset/backroom-zones-dec-us-denver.csv` (wins over the read). Move bodies carry no `occurred_at` |
| move-all | `M8TRX_RESET_MOVE_ALL` (default true): every on-hand unit, also the ones already in place, gets an explicit move to its own cell or rack |

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
export M8TRX_RESET_HOLD_DIRECTIVES=true       # 2026-11-19: BOTH plan items stay out (webhook body cap 256 KiB; a 6,365-target plan is 1.13 MB; form is Bob's choice)
export M8TRX_RESET_RECEIVE_LANES=1          # 1 (default) .. 4; tonight most likely 1 (a 50-tag receive holds one of the server's 4 event-loop threads ~50 s)
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

**Post-check (report only):** after every receive and move is accepted, the run waits 120 s after the last move (moves are processed after the
server answers them), then compares the platform's location of the same 20 fixtures with the ledger the reset just wrote
(`StoreStock.fromPlatform`, store-manager read, needs `M8TRX_OPS_PASSWORD`; skipped with a note without it). The per-fixture lines and the verdict
go to `live-run.log` and into `reset-complete.json` (`post_check`, `post_check_fixtures`; the pre-check verdict is `pre_check`). It never stops the run.

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
  ledger changes as units are booked, so it is never re-planned), accepted ids in `sent-ledger.tsv` are skipped, the existence pre-check is not repeated.
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

# People by REST, first live test: one store, one hour (`storeDayPeopleRestLive`)

Nine non-Denver stores only (Denver keeps its edge and is refused). One service key (`vision_ai:ingest`, site-scoped to the nine). Backfill is
refused, and so is a date before the store-local today. No secret is in the repo: the key is an env var.

Window rule: a session is in when its BEGIN is due in `[FROM, UNTIL)` store-local. A session that begins before FROM is skipped whole. A session
that begins in the window keeps its impressions and its close even after UNTIL (no session is left open), so the run ends when the last such
close is due. A line goes out only when it is due (due <= now); nothing is sent early.

```
cd ~/IdeaProjects/m8trx-twin     # on the box, the checkout of the merged branch
export M8TRX_PEOPLE_REST_STORES=dec-us-nyc        # subset of the nine
export M8TRX_PEOPLE_REST_FROM=10:00 M8TRX_PEOPLE_REST_UNTIL=11:00   # store-local; DATE defaults to the store-local today
export M8TRX_PEOPLE_REST_OUT=/opt/m8trx/twin-ops/people-rest         # run files: $OUT/live/<date>/
# 1. DRY (nothing sent, nothing written): prints the exact expected counts per store and kind
./gradlew storeDayPeopleRestLive
# 2. LIVE: needs all three
export M8TRX_PEOPLE_REST_LIVE=true M8TRX_PEOPLE_REST_CONFIRM=dec-us-nyc   # exactly the slugs of the run
export M8TRX_TWIN_PEOPLE_BEARER=...                                       # from 1Password, never committed
./gradlew storeDayPeopleRestLive
```

Optional: `M8TRX_PEOPLE_REST_CAP_PER_MIN` (300, max 600, one global cap for the key), `M8TRX_PEOPLE_REST_SEED`, `M8TRX_PEOPLE_REST_BASE_URL`
(default `https://dev.m8trx.com/server`), `M8TRX_OPS_DIR` (`/opt/m8trx/twin-ops`).

- **Check counts:** compare the printed table with `$OUT/live/<date>/run-complete.json` (`expected` vs `counts_this_run`, plus `sent-ledger.tsv`).
- **Watch:** `tail -f $OUT/live/<date>/live-run.log`; `cat $OUT/live/<date>/progress.json`; `wc -l $OUT/live/<date>/sent-ledger.tsv`.
- **Stop cleanly:** `touch /opt/m8trx/twin-ops/people-HOLD` (NOT `HOLD`, which stops the live plane). `rm` it to allow a restart. Exit 6.
- **Resume** (HOLD, halt, crash): same env, same `OUT` and date. The frozen `plan.jsonl` is reused (a different store/date/window/seed against it is
  refused), ids in `sent-ledger.tsv` are skipped. Exit codes: 0 done, 1 halted (read the last `end:` line), 3 refused, 6 HOLD.
- **Halts:** 402 `NOT_ENTITLED` at once; 401/403 retried once then halt; a 4xx refusal halts with the request still due; 429 waits at least a minute
  (or `Retry-After`); 5xx and no answer wait for the health gate (`GET /server/api/health`, 200 and `"status":"ok"`).

## People by REST: the nine stores daily (installed on the box 2026-11-20, plan § 12.15)

Deploy-local on the box (not in this repo): `/opt/m8trx/twin-ops/people-daily.sh <group> [FROM]` plus four systemd pairs,
`m8trx-twin-people-<group>.timer` → `m8trx-twin-people@<group>.service` (Type=oneshot, User=m8trx, RuntimeMaxSec=26h, Nice=10).

| group | stores | timer | cap/min |
|---|---|---|---|
| kr | dec-kr-seoul, dec-kr-busan | 00:02 Asia/Seoul | 80 |
| fr | dec-fr-bordeaux, -lille, -lyon, -marseille, -paris | 00:02 Europe/Paris | 120 |
| us-east | dec-us-nyc | 00:02 America/New_York | 50 |
| us-west | dec-us-sf | 00:02 America/Los_Angeles | 50 |

The script starts ONE run per store (own plan, ledger and halt), so a refusal at one store never stops the others. Per-store caps: seoul 50, busan 30; paris 40, lille 25, lyon 25, bordeaux 15, marseille 15; nyc 50; sf 50. A third argument limits a run to some stores (`people-daily.sh kr 12:20 dec-kr-seoul`).
Each run is the store-local day, window 00:00 to 23:59, sent as it happens (`storeDayPeopleRestLive`, key `M8TRX_TWIN_PEOPLE_BEARER`).
The caps add up to the key's twin cap of 300 a minute. Stores open 10:00 to 21:00, so a run ends around 22:00 local, before the next timer.
- Run files: `/opt/m8trx/twin-ops/people-rest/<store>/live/<date>/` (plan, sent-ledger, progress, run-complete).
- Logs: `/opt/m8trx/twin-ops/log/people-<store>-<yyyymmdd>.log`.
- Check the key's sites with `GET /server/api/v2/connect/whoami` (Bearer). 2026-11-20: Busan and Lille were missing, and their first begin answered 403.
- Stop all groups: `touch /opt/m8trx/twin-ops/people-HOLD` (never `$OPS/HOLD`, which stops the live plane).
- A halted run (exit 1) is not restarted. Rerun the same group the same day to resume from its sent-ledger.
- Backfill of past days is still refused by the sender. It is allowed by Bob's rule now that the live test has passed, but not built yet.

## Daily delivery (`./gradlew dailyDelivery`, drafted 2026-11-21; units drafted in `ops/`, installed only by an admin)

Every store-local morning before opening: order = (period plan floor + 7-day back-room cover) minus what the ledger holds, rounded to the nearest case of 6 (shortfall 1-2 orders nothing, 3-8 one case; bikes singles); receive into the stockroom (50 tags a request); put each received unit on its EAN's back-room rack by `inventory_movement` (`to_zone_id`); then top up the floor cells below plan from the back room, most short first. The gradle task defaults `M8TRX_RESET_BACKROOM_COVER_DAYS=7` and `M8TRX_RESET_BACKROOM_CASE_PACK=1`.

- **Dry (default):** `M8TRX_DELIVERY_SITES=<csv> M8TRX_DELIVERY_DATE=<yyyy-mm-dd> ./gradlew dailyDelivery` prints per store the order, units, requests, put-away and top-up batches, minutes, the window to start in and the first bodies; writes `out/delivery/<slug>/delivery-<date>/` and `out/delivery/summary.tsv`. The ledger comes from the folder of `M8TRX_STREAM_SOLD_LOG` (`sold-epcs.txt`, `receive/received-stock.csv`, `moves/placements.csv`, `stock/missing-epcs.txt`).
- **Live:** `M8TRX_DELIVERY_LIVE=true M8TRX_DELIVERY_CONFIRM=<the same sites csv>`. Refuses (exit 3) without the keys, for a store the owner file does not list `storeday`, after the store's opening on that date (`M8TRX_DELIVERY_ALLOW_OPEN=true` overrides), over 25 units a batch, or faster than one batch every 7.5 s. Stop file `$M8TRX_OPS_DIR/delivery-HOLD` (exit 6). The plan freezes on the first live send (`live-started.json`); a re-run resumes it.
- **Verification:** every move's `eventKey` goes to `answers.tsv` before the sent-ledger marks it; `GET .../results?idempotency_key=...` (40 keys a request, `X-API-Key`) confirms it; `placements.csv` gets a line only for a batch whose result says every unit moved. A refused unit, a `warning` or `failed` outcome, or a send answered after 10 s halts the store (exit 1). Receives book only the counted tags, as the reset does. Needs services #535 deployed.
- **Lock:** `ops/daily-delivery.sh <slug>` runs it under `flock /opt/m8trx/twin-ops/lock`, as the reset does (the hourly pass skips while it holds it). One store at a time, so 200 items a minute is the total. Pre-open starts: Korea 08:00 KST, France 08:00 Europe/Paris, US 08:00 local; a steady day is 10 to 35 minutes.

### Install the delivery timers (admin, sudo; nothing here installs itself)

Units: `ops/m8trx-twin-delivery@.service` (template, `%i` = store slug; runs `LIVE=1 OWN=1 ops/daily-delivery.sh %i`, `TimeoutStartSec=3h`) and one `ops/m8trx-twin-delivery-<slug>.timer` per store. Eight stores: dec-kr-busan, dec-kr-seoul (Asia/Seoul); dec-fr-bordeaux, dec-fr-lille, dec-fr-lyon, dec-fr-marseille (Europe/Paris); dec-us-denver (America/Denver); dec-us-sf (America/Los_Angeles). **dec-us-nyc (America/New_York) and dec-fr-paris are deliberately NOT included yet**: their rebuilds are pending. They get timers (same pattern) after the rebuilds.

```
cd /opt/m8trx/twin/ops
sudo cp m8trx-twin-delivery@.service m8trx-twin-delivery-*.timer /etc/systemd/system/
sudo systemctl daemon-reload
for s in dec-kr-busan dec-kr-seoul dec-fr-bordeaux dec-fr-lille dec-fr-lyon dec-fr-marseille dec-us-denver dec-us-sf; do
  sudo systemctl enable --now m8trx-twin-delivery-$s.timer
done
systemctl list-timers 'm8trx-twin-delivery-*'
```

- **`OWN=1`:** inside the shared lock the script flips the store's line in `$M8TRX_OPS_DIR/store-ops-owner.csv` `hourly` -> `storeday`, runs the task, and flips back to `hourly` on every exit (failure and TERM/INT/HUP included), logging both flips to `log/daily-delivery-<slug>.log`. It refuses (exit 3) if the file has no `<slug>,hourly` line at start (a reset owns the store, or it is already storeday): it never takes a store from a reset. No manual `sed` needed on the timer path.
- **Why 08:00:30:** every store's 08:00 local is on a UTC hour, when the hourly pass (`flock -n`, skips if held) starts. Thirty seconds later the hourly pass has the lock and the delivery's blocking `flock` waits for it (a pass runs 9-20 min), instead of the hourly pass being skipped. `Persistent=false`: a missed morning (box down) is not replayed later in the day.
- **Stop file:** `touch $M8TRX_OPS_DIR/delivery-HOLD` makes the next run refuse (exit 6); remove it to resume. A run already sending is not interrupted.
- **Disable:** one store `sudo systemctl disable --now m8trx-twin-delivery-<slug>.timer`; all `sudo systemctl disable --now 'm8trx-twin-delivery-*.timer'`. Stop a running delivery with `sudo systemctl stop m8trx-twin-delivery@<slug>.service` (the owner line is flipped back by the trap).
