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

## Live plane check

`twin-live-check.sh` (every 10 min, `m8trx-twin-live-check.timer`) tells Slack when the Denver live plane or the Busan floor feed
(one JVM, `m8trx-twin-live.service`) is not running when it should be: down 30 min with no HOLD; a HOLD still set after 6 h (repeated
every 6 h); live.log frozen 5 min; the floor feed stopped while the plane runs. One post per problem, one "recovered". Sends nothing to
the platform. `touch $OPS/live-off` silences it on purpose. Install: script to `/opt/m8trx/twin-ops/` (755), the service and timer to
`/etc/systemd/system/`, `sudo systemctl daemon-reload && sudo systemctl enable --now m8trx-twin-live-check.timer`.
`m8trx-twin-live.service` stays disabled (INSTALL.md: it must not come back by itself after a reboot); a reboot is caught by this check.
