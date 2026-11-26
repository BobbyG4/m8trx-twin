#!/usr/bin/env bash
# twin-alert.sh <unit> — ExecStart of m8trx-twin-alert@.service: ONE Slack line the moment a twin unit ends failed, so a failure
# is not found at the 08:00 digest. Wired by OnFailure= drop-ins (failure-alert.conf) on the delivery, people and hourly units.
# Posts through twin-notify (uptime-watch channel); never fails. TWIN_NOTIFY_DRY=1 prints the line instead (used for testing).
# A hold is not a failure: people units end exit 6 on a hold and carry SuccessExitStatus=6, so they never reach here.
set -u
OPS="${M8TRX_OPS_DIR:-/opt/m8trx/twin-ops}"
UNIT="${1:-}"
[ -n "$UNIT" ] || exit 0
show() { systemctl show "$UNIT" -p "$1" --value 2>/dev/null; }
RESULT="$(show Result)"
CODE="$(show ExecMainStatus)"
LAST="$(journalctl -u "$UNIT" -n 60 -o cat --no-pager 2>/dev/null | grep -iE 'halt|error|fail|refus|exception' | grep -v DEBUG | tail -1 | cut -c1-220)"
[ -n "$LAST" ] || LAST="$(journalctl -u "$UNIT" -n 1 -o cat --no-pager 2>/dev/null | cut -c1-220)"
KIND="FAILED"
NOTE=""
if [ "$CODE" = "6" ]; then   # a delivery halts with exit 6: resumable, no auto-resume
    KIND="HALTED (resumable)"
    [ -e "$OPS/delivery-HOLD" ] && NOTE=" · delivery-HOLD is set"
fi
"$OPS/twin-notify" "twin · $KIND · $UNIT · result=${RESULT:-?} exit=${CODE:-?} · $(TZ=Asia/Seoul date '+%F %H:%M KST')$NOTE · ${LAST:-no log line}"
exit 0
