#!/usr/bin/env bash
# twin-day.sh — the twin's nightly people-plane day (yesterday, Asia/Seoul date), LIVE, at the WEEKDAY scale only.
# Deploy-local (not in the repo). Units: m8trx-twin-day.{service,timer}. Hold switch: /opt/m8trx/twin-ops/hold.
# Rule: never run the day step while the office is using the demo.
set -u
OPS="${TWIN_OPS_DIR:-/opt/m8trx/twin-ops}"
TWIN="${TWIN_DIR:-/opt/m8trx/twin}"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
[ -e "$OPS/HOLD" ] && exit 0
mkdir -p "$OPS/log"
LOG="$OPS/log/day-$(TZ=Asia/Seoul date +%F).log"
log() { echo "[$(TZ=Asia/Seoul date '+%F %T %Z')] $*" >>"$LOG"; }
exec 9>"$OPS/lock"
flock -w 7200 9 || { log "lock not free after 2h - day skipped"; "$OPS/twin-notify" "twin · people plane · FAILED · lock busy for 2h, day not run"; exit 1; }

D="$(TZ=Asia/Seoul date -d yesterday +%F)"
# day.sh's weekday table: Mon .85, Tue .9, Fri 1.1, Wed/Thu 1.0 (default); weekend Sat 1.25, Sun 1.15. Fixed at the neutral weekday value 1.0.
export M8TRX_DAY_SCALE=1.0
"$OPS/twin-notify" "twin · people plane · $D · weekday scale ($M8TRX_DAY_SCALE) · publishing from the box (hold: $OPS/hold)"
T0=$SECONDS
cd "$TWIN" || exit 1
OUT="$(mktemp)"
log "day.sh --date $D --steps day --live-day (M8TRX_DAY_SCALE=$M8TRX_DAY_SCALE)"
scripts/demo/day.sh --date "$D" --steps day --live-day >"$OUT" 2>&1
RC=$?
cat "$OUT" >>"$LOG"
WALL=$((SECONDS - T0))
SUMMARY="$(grep -o 'SIM-RUN .*' "$OUT" | tail -1)"
if [ $RC -ne 0 ]; then
  ERR="$(grep -E 'ERROR|FAILED|refused' "$OUT" | tail -1 | cut -c1-240)"
  "$OPS/twin-notify" "twin · people plane · $D · FAILED rc=$RC (${WALL}s) · ${ERR:-see $LOG}"
else
  "$OPS/twin-notify" "twin · people plane · $D · ${SUMMARY:-no SIM-RUN line - see $LOG} · wall ${WALL}s"
fi
rm -f "$OUT"
log "done rc=$RC wall ${WALL}s"
exit $RC
