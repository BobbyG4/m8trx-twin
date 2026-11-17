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
