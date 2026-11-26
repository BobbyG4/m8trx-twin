# Failure alert (Slack only)

A twin unit that ends failed posts one line to the uptime-watch Slack channel at once, not at the 08:00 digest.

| File | Installs to |
|---|---|
| `twin-alert.sh` | `/opt/m8trx/twin-ops/` (mode 755) |
| `m8trx-twin-alert@.service` | `/etc/systemd/system/` |
| `failure-alert.conf` | `/etc/systemd/system/<unit>.d/10-failure-alert.conf`, for `m8trx-twin-delivery@.service`, `m8trx-twin-people@{fr,kr,us-east,us-west}.service`, `m8trx-twin-hourly.service` |

Then `sudo systemctl daemon-reload`. The live unit is left out: `twin-live.sh` already posts its own FAILED line.
A people hold (exit 6, `SuccessExitStatus=6`) is not a failure and sends nothing. A delivery halt (exit 6) posts `HALTED (resumable)`.
Test: `systemd-run --unit=m8trx-twin-alerttest -p OnFailure=m8trx-twin-alert@m8trx-twin-alerttest.service /bin/false`.
