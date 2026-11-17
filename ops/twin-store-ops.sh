#!/usr/bin/env bash
# twin-store-ops.sh — ExecStart of m8trx-twin-store-ops.service: the store-day ops driver (com.m8trx.twin.storeops.StoreOpsLiveKt).
# NOT INSTALLED ANYWHERE; drafted with the Phase 3 tooling. In that slice the JVM REFUSES a live run (exit 3), so installing this
# unit changes nothing until the live slice lands and Bob gives the go.
#
# Runs the JVM from $OPS/store-ops-dist (a copy of the runtime classpath made by `store-ops start` under the shared lock), NOT under
# gradle, so it holds no gradle lock while it runs. Output is appended to $OPS/log/store-ops.log. Slack through twin-notify, as the
# live plane: one post on start, one on stop, one on refusal or failure.
#
# Refuses (exit 3, no restart) while HOLD is set, when the owner file does not give the driver a store, or when
# M8TRX_STORE_OPS_CONFIRM is not the store's slug (the second key of a live run).
set -uo pipefail

OPS="${M8TRX_OPS_DIR:-/opt/m8trx/twin-ops}"
TWIN="${M8TRX_TWIN_DIR:-/opt/m8trx/twin}"
LOG="$OPS/log/store-ops.log"
HOLD="${M8TRX_LIVE_HOLD_FILE:-$OPS/HOLD}"
NOTIFY="$OPS/twin-notify"
JAVA="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}/bin/java"
mkdir -p "$OPS/log"

notify() { [[ -x "$NOTIFY" ]] && "$NOTIFY" "$1" || echo "notify: $1"; }
stamp() { TZ=Asia/Seoul date '+%Y-%m-%d %H:%M:%S KST'; }

if [[ -e "$HOLD" ]]; then
    notify "twin · store-ops · REFUSED to start: HOLD is set ($HOLD) — resume first"
    exit 3
fi
if ! ls "$OPS"/store-ops-dist/*.jar >/dev/null 2>&1; then
    notify "twin · store-ops · REFUSED to start: no $OPS/store-ops-dist — run '$OPS/store-ops start' (it builds the classpath)"
    exit 3
fi

cd "$TWIN" || exit 3
OFFSET=$( [[ -f "$LOG" ]] && wc -c <"$LOG" | tr -d " " || echo 0)
echo "════ $(stamp) store-ops start · $(git -C "$TWIN" rev-parse --short HEAD 2>/dev/null) · stores=${M8TRX_STORE_OPS_STORES:-dec-us-denver}" >>"$LOG"

M8TRX_STORE_OPS_LIVE="${M8TRX_STORE_OPS_LIVE:-false}" M8TRX_LIVE_HOLD_FILE="$HOLD" M8TRX_STORE_OPS_OWNER_FILE="${M8TRX_STORE_OPS_OWNER_FILE:-$OPS/store-ops-owner.csv}" \
    "$JAVA" -Xmx6g -cp "$OPS/store-ops-dist/*" com.m8trx.twin.storeops.StoreOpsLiveKt >>"$LOG" 2>&1 &
JPID=$!
trap 'kill -TERM "$JPID" 2>/dev/null' TERM INT
notify "twin · store-ops · STARTED $(stamp) · stores=${M8TRX_STORE_OPS_STORES:-dec-us-denver} · stop: $OPS/store-ops stop (or hold)"

wait "$JPID"
RC=$?
while kill -0 "$JPID" 2>/dev/null; do
    wait "$JPID"
    RC=$?
done
echo "════ $(stamp) store-ops exit rc=$RC" >>"$LOG"
if [[ $RC -eq 0 || $RC -eq 143 ]]; then
    notify "twin · store-ops · STOPPED $(stamp)"
    exit 0
fi
ERR=$(tail -c +"$((OFFSET + 1))" "$LOG" | grep -E 'ERROR|REFUSING|HALT|Exception' | tail -1 | sed 's/^.* - //' | cut -c1-300)
case $RC in
3 | 6) notify "twin · store-ops · REFUSED rc=$RC $(stamp) · ${ERR:-see $LOG}" ;;  # not restarted (RestartPreventExitStatus)
*) notify "twin · store-ops · FAILED rc=$RC $(stamp) · ${ERR:-see $LOG} · systemd retries in 30 s (3 tries in 10 min)" ;;
esac
exit "$RC"
