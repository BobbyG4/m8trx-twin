#!/usr/bin/env bash
# twin-live-check.sh — every 10 min (m8trx-twin-live-check.timer): tell Slack when the Denver live plane or the Busan floor feed
# (one JVM, m8trx-twin-live.service) is not running when it should be, so a plane stopped by a hold and never restarted cannot
# sit dead for days (Denver, 11-24 to 11-27). Posts through twin-notify; never fails. Sends nothing to the platform.
#   plane down, no HOLD  : alert after 30 min down (a restart in between is a normal `live speed N`)
#   HOLD set             : a reminder every 6 h while it stays set (the plane is down on purpose, but not forever)
#   JVM up, log frozen   : alert when live.log has not moved for 5 min
#   floor feed stopped   : alert when LiveFloorDrive logged a stop after the last start while the plane runs
# Silence on purpose: `touch $OPS/live-off` (a `live stop` you mean to keep); remove it to re-arm.
# One post per problem, repeated every 6 h while it lasts, one "recovered" when it clears. State: $OPS/live-check.state.
# TWIN_NOTIFY_DRY=1 prints instead of posting; --test posts one labelled line.
set -u
OPS="${M8TRX_OPS_DIR:-/opt/m8trx/twin-ops}"
UNIT=m8trx-twin-live.service
LOG="$OPS/log/live.log"
STATE="$OPS/live-check.state"
NOTIFY="$OPS/twin-notify"
NOW=$(date +%s)
REPEAT=$((6 * 3600))

notify() { "$NOTIFY" "$1"; }
[ "${1:-}" = "--test" ] && { notify "twin · live check · TEST line (the live plane check is installed; nothing is wrong)"; exit 0; }
[ -e "$OPS/live-off" ] && exit 0

active=0; systemctl is-active --quiet "$UNIT" 2>/dev/null && active=1
PROBLEM=""
if [ "$active" = 1 ]; then
    AGE=$(( NOW - $(stat -c %Y "$LOG" 2>/dev/null || echo 0) ))
    START_LN=$(grep -n '═══ LiveDrive ═══' "$LOG" 2>/dev/null | tail -1 | cut -d: -f1)
    if [ "$AGE" -gt 300 ]; then
        PROBLEM="frozen|the live plane is running but live.log has not moved for $((AGE / 60)) min"
    elif [ -n "${START_LN:-}" ] && tail -n +"$START_LN" "$LOG" | grep -q 'LiveFloorDrive stop reason='; then
        PROBLEM="floor|the Busan floor feed stopped while the live plane runs ($(tail -n +"$START_LN" "$LOG" | grep 'LiveFloorDrive stop reason=' | tail -1 | grep -o 'reason=[A-Za-z_]*'))"
    fi
    rm -f "$STATE.down"
else
    [ -e "$STATE.down" ] || echo "$NOW" >"$STATE.down"
    DOWN_FOR=$(( NOW - $(cat "$STATE.down") ))
    if [ -e "$OPS/HOLD" ]; then
        HELD=$(( (NOW - $(stat -c %Y "$OPS/HOLD")) / 3600 ))
        PROBLEM="hold|the live plane is down and HOLD has been set for ${HELD} h ($(head -c 80 "$OPS/HOLD" 2>/dev/null | tr '\n' ' ')) - release it and run '$OPS/live start' when the hold is over"
        [ "$HELD" -lt 6 ] && PROBLEM=""
    elif [ "$DOWN_FOR" -ge 1800 ]; then
        PROBLEM="down|the live plane (Denver) and the Busan floor feed have been down for $((DOWN_FOR / 60)) min and no HOLD is set - '$OPS/live start' to bring them back, or 'touch $OPS/live-off' to silence this on purpose"
    fi
fi

LAST_KEY=""; LAST_AT=0
[ -r "$STATE" ] && read -r LAST_KEY LAST_AT <"$STATE"
if [ -n "$PROBLEM" ]; then
    KEY="${PROBLEM%%|*}"; MSG="${PROBLEM#*|}"
    if [ "$KEY" != "$LAST_KEY" ] || [ $((NOW - ${LAST_AT:-0})) -ge "$REPEAT" ]; then
        notify "twin · live check · $MSG · $(TZ=Asia/Seoul date '+%F %H:%M KST')"
        echo "$KEY $NOW" >"$STATE"
    fi
elif [ -n "$LAST_KEY" ]; then
    notify "twin · live check · recovered: the live plane is running again · $(TZ=Asia/Seoul date '+%F %H:%M KST')"
    : >"$STATE"
fi
exit 0
