#!/usr/bin/env bash
# twin-live.sh — ExecStart of m8trx-twin-live.service: the twin's live people plane (LiveDrive, M8TRX_PEOPLE_MODE=live).
#
# Runs the JVM from $OPS/live-dist (a copy of the runtime classpath made by `live start` under the shared lock), NOT under
# gradle, so the service holds no gradle lock while it runs and the hourly pass's `./gradlew build` never rewrites a jar
# under it. Output is appended to $OPS/log/live.log. Slack (through twin-notify, the hourly pass's wrapper): one post when
# the plan is up (sites, speed, shift, scale, which stores publish), one when it stops (reason, published, wall) or fails.
#
# Refuses (exit 3, no restart — RestartPreventExitStatus) while HOLD is set or the day replay runs.
set -uo pipefail

OPS="${M8TRX_OPS_DIR:-/opt/m8trx/twin-ops}"
TWIN="${M8TRX_TWIN_DIR:-/opt/m8trx/twin}"
LOG="$OPS/log/live.log"
HOLD="${M8TRX_LIVE_HOLD_FILE:-$OPS/HOLD}"
NOTIFY="$OPS/twin-notify"
JAVA="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}/bin/java"
mkdir -p "$OPS/log"

notify() { [[ -x "$NOTIFY" ]] && "$NOTIFY" "$1" || echo "notify: $1"; }
stamp() { TZ=Asia/Seoul date '+%Y-%m-%d %H:%M:%S KST'; }

day_running() {
    systemctl is-active --quiet m8trx-twin-day.service 2>/dev/null && return 0
    # bracketed first letter so the pattern does not match this pgrep itself
    pgrep -f '[D]ayDriveKt' >/dev/null && return 0
    pgrep -f '[c]onnectDayDrive' >/dev/null && return 0
    return 1
}

if [[ -e "$HOLD" ]]; then
    notify "twin · live · REFUSED to start: HOLD is set ($HOLD) — resume first"
    exit 3
fi
if day_running; then
    notify "twin · live · REFUSED to start: the day replay is running (m8trx-twin-day) — the two would stack on one edge"
    exit 3
fi
if ! ls "$OPS"/live-dist/*.jar >/dev/null 2>&1; then
    notify "twin · live · REFUSED to start: no $OPS/live-dist — run '$OPS/live start' (it builds the classpath)"
    exit 3
fi

cd "$TWIN" || exit 3
OFFSET=$( [[ -f "$LOG" ]] && wc -c <"$LOG" | tr -d " " || echo 0)
echo "════ $(stamp) live start · $(git -C "$TWIN" rev-parse --short HEAD 2>/dev/null) · speed=${M8TRX_LIVE_SPEED:-1} sites=${M8TRX_LIVE_SITES:-all} shift=${M8TRX_LIVE_SHIFT:-open} dwell=${M8TRX_LIVE_DWELL:-scaled}" >>"$LOG"

M8TRX_PEOPLE_MODE=live M8TRX_PEOPLE_LIVE=true M8TRX_LIVE_HOLD_FILE="$HOLD" \
    "$JAVA" -Xmx6g -cp "$OPS/live-dist/*" com.m8trx.twin.layer1.PeopleDriveKt >>"$LOG" 2>&1 &
JPID=$!
# systemd stop (KillMode=mixed) signals this script only: hand the TERM to the JVM so its hook evicts every shopper
trap 'kill -TERM "$JPID" 2>/dev/null' TERM INT

# the start post waits for the plan line (generation takes seconds), so it names the stores that actually publish
for _ in $(seq 1 90); do
    PLAN=$(tail -c +"$((OFFSET + 1))" "$LOG" | grep -m1 -o 'SIM-RUN live plan.*' || true)
    [[ -n "$PLAN" ]] && break
    kill -0 "$JPID" 2>/dev/null || break
    sleep 2
done
SCALE="${M8TRX_LIVE_SCALE:-${M8TRX_DAY_SCALE:-weekday table x 0.48 (Sat 0.6)}}"
if [[ -n "${PLAN:-}" ]]; then
    notify "twin · live · STARTED $(stamp) · scale ${SCALE} · ${PLAN#SIM-RUN live plan } · stop: $OPS/live stop (or hold)"
fi

# the first wait collects the JVM's status even when it has already exited; a TERM interrupts a wait (status 143) while
# the JVM is still evicting, so wait again until it is gone
wait "$JPID"
RC=$?
while kill -0 "$JPID" 2>/dev/null; do
    wait "$JPID"
    RC=$?
done
STOP=$(tail -c +"$((OFFSET + 1))" "$LOG" | grep -o 'SIM-RUN live stop.*' | tail -1 || true)
echo "════ $(stamp) live exit rc=$RC" >>"$LOG"
if [[ $RC -eq 0 || $RC -eq 143 ]]; then
    notify "twin · live · STOPPED $(stamp) · ${STOP#SIM-RUN live stop }"
    exit 0
fi
ERR=$(tail -c +"$((OFFSET + 1))" "$LOG" | grep -E 'ERROR|REFUSING|Exception' | tail -1 | sed 's/^.* - //' | cut -c1-300)
case $RC in
3 | 5 | 6) notify "twin · live · REFUSED rc=$RC $(stamp) · ${ERR:-see $LOG}" ;;  # not restarted (RestartPreventExitStatus)
*) notify "twin · live · FAILED rc=$RC $(stamp) · ${ERR:-see $LOG} · systemd retries in 30 s (3 tries in 10 min)" ;;
esac
exit "$RC"
