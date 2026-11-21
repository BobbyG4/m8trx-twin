#!/usr/bin/env bash
# daily-delivery.sh — one store's pre-opening delivery (./gradlew dailyDelivery), run under the shared twin lock.
# NOT INSTALLED ANYWHERE: a drafted box script (see ops/INSTALL.md "Daily delivery"). Nothing here touches cron or systemd.
#
#   daily-delivery.sh <slug>            DRY (prints the plan, sends nothing)
#   LIVE=1 daily-delivery.sh <slug>     LIVE (needs the owner file to list <slug> as storeday, and the keys in the environment)
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
if [[ "${LIVE:-0}" == "1" ]]; then
  export M8TRX_DELIVERY_LIVE=true
  export M8TRX_DELIVERY_CONFIRM="$SLUG"
fi

cd "$TWIN" || exit 3
mkdir -p "$OPS/log"
# the same lock as the reset and the hourly pass: the hourly pass skips while this holds it
exec flock "$OPS/lock" ./gradlew dailyDelivery -q >>"$OPS/log/daily-delivery-$SLUG.log" 2>&1
