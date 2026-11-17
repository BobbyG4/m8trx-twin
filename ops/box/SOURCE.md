# ops/box — the box's deploy-local twin ops files, as found on `m8trx-gpu` 2026-11-17

Byte copies of `/opt/m8trx/twin-ops/{twin-hourly.sh,twin-day.sh,twin-notify,hold,resume,README}` and
`/etc/systemd/system/m8trx-twin-{hourly,day}.{service,timer}`. They ran from the box only and were in no repo until now.
The live-plane files are in `ops/` (`live`, `twin-live.sh`, `m8trx-twin-live.service`, `INSTALL.md`); the digest files
are in `m8trx-shared/scripts/twin-ops/`.

The rule from here on: change these here first, then copy them to the box, never the other way round. `README` describes
the 2026-10-12 state; its "timers DISABLED" header is stale (hourly is enabled, day is disabled). See `status/STATUS.md`
§ WHAT ACTUALLY RUNS.
