#!/usr/bin/env bash
# twin-hourly.sh — the twin's hourly live pass (yesterday + today, Asia/Seoul dates): stream+alarm, then move.
# Deploy-local (not in the repo). Units: m8trx-twin-hourly.{service,timer}. Hold switch: /opt/m8trx/twin-ops/hold.
# Slack: FAILED passes post at once; successful passes post nothing (since 2026-10-17 the 08:00 KST digest sums them).
set -u
OPS="${TWIN_OPS_DIR:-/opt/m8trx/twin-ops}"
TWIN="${TWIN_DIR:-/opt/m8trx/twin}"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
[ -e "$OPS/HOLD" ] && exit 0
mkdir -p "$OPS/log"
LOG="$OPS/log/hourly-$(TZ=Asia/Seoul date +%F).log"
log() { echo "[$(TZ=Asia/Seoul date '+%F %T %Z')] $*" >>"$LOG"; }
exec 9>"$OPS/lock"
flock -w 60 9 || { log "another twin run holds the lock - pass skipped"; exit 0; }

T0=$SECONDS
ERRS=0 SENT=0 PARTS=() LASTERR=""
cd "$TWIN" || exit 1
for D in "$(TZ=Asia/Seoul date -d yesterday +%F)" "$(TZ=Asia/Seoul date +%F)"; do
  # 2026-10-13 23:0x KST (Coordinator, Bob: run all 10): the demo steps run daily too; a past date is HELD by the twin, never written
  for PASS in "stream,alarm|--live-stream --live-alarm|" "move|--live-move|--moves 0" "stock,tasks,fixmoves|--live-stock --live-tasks --live-fixmoves|"; do
    IFS='|' read -r STEPS LIVE EXTRA <<<"$PASS"
    OUT="$(mktemp)"
    log "day.sh --date $D --steps $STEPS $LIVE $EXTRA"
    # shellcheck disable=SC2086
    scripts/demo/day.sh --date "$D" --steps "$STEPS" $LIVE $EXTRA >"$OUT" 2>&1
    RC=$?
    cat "$OUT" >>"$LOG"
    [ $RC -ne 0 ] && { ERRS=$((ERRS + 1)); LASTERR="$(grep -E 'ERROR|FAILED|refused' "$OUT" | tail -1 | cut -c1-240)"; }
    while read -r LINE; do
      OK="$(grep -o 'sent_ok=[0-9]*' <<<"$LINE" | cut -d= -f2)"; ER="$(grep -o 'sent_err=[0-9]*' <<<"$LINE" | cut -d= -f2)"
      STEP="$(awk '{print $2}' <<<"$LINE")"
      SENT=$((SENT + ${OK:-0}))
      [ "${ER:-0}" -gt 0 ] && { ERRS=$((ERRS + 1)); LASTERR="$STEP sent_err=$ER on $D"; }
      [ "${OK:-0}" -gt 0 ] && PARTS+=("$D $STEP sent_ok=$OK")
    done < <(grep -o 'SIM-RUN [a-z]* .*' "$OUT")
    rm -f "$OUT"
  done
done
WALL=$((SECONDS - T0))
log "pass done - sent_ok total $SENT, errors $ERRS, wall ${WALL}s"
if [ "$ERRS" -gt 0 ]; then
  "$OPS/twin-notify" "twin · hourly · FAILED ($ERRS error(s), sent_ok=$SENT, ${WALL}s) · ${LASTERR:-see $LOG}"
fi
# 2026-10-17 (Bob): no Slack line per successful pass any more — the 08:00 KST digest (twin-digest.sh) sums the day from this log.
[ "$ERRS" -eq 0 ]
