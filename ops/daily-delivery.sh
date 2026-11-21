#!/usr/bin/env bash
# daily-delivery.sh — one store's pre-opening delivery (./gradlew dailyDelivery), run under the shared twin lock.
# The systemd units that run it are drafted in ops/ (m8trx-twin-delivery@.service + one timer per store) and are installed only by an
# admin, per ops/INSTALL.md "Daily delivery". This script itself never touches cron or systemd.
#
#   daily-delivery.sh <slug>            DRY (prints the plan, sends nothing)
#   LIVE=1 daily-delivery.sh <slug>     LIVE (needs the owner file to list <slug> as storeday, and the keys in the environment)
#   LIVE=1 OWN=1 daily-delivery.sh <slug>  LIVE, and the script itself flips <slug> hourly -> storeday inside the lock and ALWAYS back to
#                                       hourly afterwards. Refuses (exit 3) unless the owner file lists <slug>,hourly at start.
#
# The store-local date is taken in the store's own zone. Start it by the pre-open time of the store:
#   Korea   (dec-kr-seoul, dec-kr-busan)                      08:00 KST
#   France  (dec-fr-bordeaux/lille/lyon/marseille/paris)       08:00 Europe/Paris
#   US      (dec-us-denver, dec-us-nyc, dec-us-sf)             08:00 local
# A steady-state day is 10 to 35 minutes per store (dry report: "steady state"); a first heal of a not-yet-reset store is many hours and is
# run on its own nights (M8TRX_DELIVERY_ALLOW_OPEN=true only when the people on the floor are expected).
set -uo pipefail

SLUG="${1:?usage: daily-delivery.sh <store slug>}"
OPS="${M8TRX_OPS_DIR:-/opt/m8trx/twin-ops}"
TWIN="${M8TRX_TWIN_DIR:-/opt/m8trx/twin}"
case "$SLUG" in
  dec-kr-*) TZ_STORE=Asia/Seoul ;;
  dec-fr-*) TZ_STORE=Europe/Paris ;;
  dec-us-denver) TZ_STORE=America/Denver ;;
  dec-us-nyc) TZ_STORE=America/New_York ;;
  dec-us-sf) TZ_STORE=America/Los_Angeles ;;
  *) echo "unknown store $SLUG" >&2; exit 3 ;;
esac
DATE="$(TZ=$TZ_STORE date +%F)"

export M8TRX_DELIVERY_SITES="$SLUG"
export M8TRX_DELIVERY_DATE="$DATE"
export M8TRX_RESET_BACKROOM_COVER_DAYS="${M8TRX_RESET_BACKROOM_COVER_DAYS:-7}"
export M8TRX_RESET_BACKROOM_CASE_PACK="${M8TRX_RESET_BACKROOM_CASE_PACK:-1}"
# The rack lookup (/spatial/identity) reads with M8TRX_TWIN_BEARER, and that key is not scoped to every store (Busan answered 403
# SITE_ACCESS_DENIED on 2026-11-22), no BR-nn zone resolves, and the run refuses. Read with the service key, as the reset's box script does.
if [[ -n "${M8TRX_TWIN_SERVICE_BEARER:-}" ]]; then
  export M8TRX_TWIN_BEARER="$M8TRX_TWIN_SERVICE_BEARER"
fi
if [[ "${LIVE:-0}" == "1" ]]; then
  export M8TRX_DELIVERY_LIVE=true
  export M8TRX_DELIVERY_CONFIRM="$SLUG"
fi

cd "$TWIN" || exit 3
mkdir -p "$OPS/log"
# the same lock as the reset and the hourly pass: the hourly pass skips while this holds it
LOG="$OPS/log/daily-delivery-$SLUG.log"
if [[ "${OWN:-0}" != "1" || "${LIVE:-0}" != "1" ]]; then
  exec flock "$OPS/lock" ./gradlew dailyDelivery -q >>"$LOG" 2>&1
fi

# OWN=1 (LIVE only): take the owner flip inside the same lock. Never takes a store from a reset (storeday at start -> refuse).
OWNER="$OPS/store-ops-owner.csv"
log() { echo "[$(date -u +%FT%TZ)] daily-delivery OWN $SLUG: $*" >>"$LOG"; }
set_owner() { # <from> <to>: rewrite only this slug's line, via temp file + mv
  sed "s/^$SLUG,$1\$/$SLUG,$2/" "$OWNER" >"$OWNER.tmp.$$" && cat "$OWNER.tmp.$$" >"$OWNER"; local r=$?
  rm -f "$OWNER.tmp.$$"; return $r
}
exec 9>"$OPS/lock"
flock 9
if ! grep -qx "$SLUG,hourly" "$OWNER" 2>/dev/null; then
  log "REFUSED: $OWNER has no '$SLUG,hourly' line (a reset owns it, or it is missing); not flipping"
  echo "OWN refused: $OWNER has no '$SLUG,hourly' line" >&2
  exit 3
fi
FLIPPED=0
restore() {
  local rc=$?
  trap - EXIT
  if [[ "$FLIPPED" == "1" ]]; then
    if set_owner storeday hourly; then log "flipped storeday -> hourly (exit $rc)"; else log "FAILED to flip back to hourly; fix $OWNER by hand"; fi
  fi
  exit "$rc"
}
trap restore EXIT
trap 'exit 143' TERM
trap 'exit 130' INT HUP
set_owner hourly storeday || { log "could not flip to storeday"; exit 3; }
FLIPPED=1
log "flipped hourly -> storeday"
./gradlew dailyDelivery -q >>"$LOG" 2>&1 9>&-
exit $?
