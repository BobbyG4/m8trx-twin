#!/usr/bin/env bash
# twin-digest.sh — ONE Slack line a day (08:00 Asia/Seoul) summarising the twin's last 24 h on the box:
# hourly passes (count, sent_ok total, per-step sent_ok, failures), the nightly people plane, and the HOLD state.
# Reads the logs the hourly/day scripts already write ($OPS/log/hourly-YYYY-MM-DD.log, day-YYYY-MM-DD.log, KST dates);
# no new ledger. Window: [yesterday 08:00, today 08:00) KST. Units: m8trx-twin-digest.{service,timer}.
# Source of record: m8trx-twin/ops/box/ since 2026-11-17 (was m8trx-shared/scripts/twin-ops/); deploy-local copy in /opt/m8trx/twin-ops. Bob's ruling 2026-10-17:
# "the twin hourly update is getting annoying now it's working — a daily summary at 8am instead".
# TWIN_NOTIFY_DRY=1 prints the line instead of posting. TWIN_DIGEST_END="YYYY-MM-DD HH:MM" overrides the window end (testing).
set -u
OPS="${TWIN_OPS_DIR:-/opt/m8trx/twin-ops}"
LOGDIR="$OPS/log"
END_KST="${TWIN_DIGEST_END:-$(TZ=Asia/Seoul date '+%F 08:00')}"
END_EPOCH=$(TZ=Asia/Seoul date -d "$END_KST" +%s)
START_EPOCH=$((END_EPOCH - 86400))
START_KST=$(TZ=Asia/Seoul date -d "@$START_EPOCH" '+%F %H:%M')
D_START=$(TZ=Asia/Seoul date -d "@$START_EPOCH" +%F)
D_END=$(TZ=Asia/Seoul date -d "@$END_EPOCH" +%F)

in_window() { # $1 = "YYYY-MM-DD HH:MM:SS" (KST) → 0 if START <= t < END
  local t; t=$(TZ=Asia/Seoul date -d "$1" +%s 2>/dev/null) || return 1
  [ "$t" -ge "$START_EPOCH" ] && [ "$t" -lt "$END_EPOCH" ]
}

PASSES=0 FAILED=0 SENT=0 WALLMAX=0
declare -A STEP_SENT
CUR_TS=""
for F in "$LOGDIR/hourly-$D_START.log" "$LOGDIR/hourly-$D_END.log"; do
  [ -r "$F" ] || continue
  while IFS= read -r LINE; do
    case "$LINE" in
      "["*" KST] "*) CUR_TS="${LINE:1:19}" ;;   # every script-stamped line refreshes the current pass's time
    esac
    [ -n "$CUR_TS" ] && in_window "$CUR_TS" || continue
    case "$LINE" in
      *"pass done - sent_ok total "*)
        PASSES=$((PASSES + 1))
        S=$(grep -o 'sent_ok total [0-9]*' <<<"$LINE" | awk '{print $3}'); E=$(grep -o 'errors [0-9]*' <<<"$LINE" | awk '{print $2}'); W=$(grep -o 'wall [0-9]*' <<<"$LINE" | awk '{print $2}')
        SENT=$((SENT + ${S:-0})); [ "${E:-0}" -gt 0 ] && FAILED=$((FAILED + 1)); [ "${W:-0}" -gt "$WALLMAX" ] && WALLMAX=$W ;;
      *"SIM-RUN "*"sent_ok="*)
        STEP=$(grep -o 'SIM-RUN [a-z]*' <<<"$LINE" | head -1 | awk '{print $2}'); OK=$(grep -o 'sent_ok=[0-9]*' <<<"$LINE" | head -1 | cut -d= -f2)
        [ -n "$STEP" ] && STEP_SENT[$STEP]=$(( ${STEP_SENT[$STEP]:-0} + ${OK:-0} )) ;;
    esac
  done <"$F"
done
STEPS=""
for K in stream alarm movement stock fixmoves tasks; do [ -n "${STEP_SENT[$K]:-}" ] && STEPS="${STEPS:+$STEPS, }$K ${STEP_SENT[$K]}"; done
for K in "${!STEP_SENT[@]}"; do case " stream alarm movement stock fixmoves tasks " in *" $K "*) ;; *) STEPS="${STEPS:+$STEPS, }$K ${STEP_SENT[$K]}";; esac; done

# people plane: the day script runs at 02:00 KST for yesterday's date → its log is day-<D_END>.log (written today) summarising D_START's day
DAY=""
for F in "$LOGDIR/day-$D_END.log" "$LOGDIR/day-$D_START.log"; do
  [ -r "$F" ] || continue
  RCLINE=$(grep -o 'done rc=[0-9]* wall [0-9]*s' "$F" | tail -1); [ -n "$RCLINE" ] || continue
  RC=$(awk '{print $2}' <<<"$RCLINE" | cut -d= -f2); W=$(awk '{print $4}' <<<"$RCLINE")
  SUM=$(grep -o 'SIM-RUN day .*' "$F" | tail -1 | cut -c13-160)
  if [ "$RC" = "0" ]; then DAY="people plane OK"; else DAY="people plane FAILED rc=$RC"; fi
  DAY="$DAY${SUM:+ ($SUM)} · $W"
  break
done
# live people plane (m8trx-twin-live, since 2026-10-14; the nightly day timer is disabled): service state + the store-days it
# completed whose plan date falls in the window (live.log's Java lines carry no date, so match on the plan date in the message).
if [ -z "$DAY" ]; then
  LSTATE=$(systemctl is-active m8trx-twin-live.service 2>/dev/null || true)
  LSITES=$(grep -s '^M8TRX_LIVE_SITES=' "$OPS/live.env" | cut -d= -f2)
  D_PREV=$(TZ=Asia/Seoul date -d "$D_START -1 day" +%F)
  DONE=$(grep -aso '[a-z-]* day [0-9-]* done → [0-9-]* ([0-9]* shoppers)' "$LOGDIR/live.log" |
    awk -v a="$D_PREV" -v b="$D_END" '$3 >= a && $3 <= b {printf "%s%s %s (%s shoppers)", (n++ ? ", " : ""), $1, $3, substr($7, 2)}')
  DAY="people plane: live ${LSTATE:-unknown} (${LSITES:-?}) · days done: ${DONE:-none in the window}"
fi
HOLD=""; [ -e "$OPS/HOLD" ] && HOLD=" · HOLD is SET (nothing publishes until resume)"
EXPECTED=24
MISSING=$((EXPECTED - PASSES)); [ "$MISSING" -lt 0 ] && MISSING=0
LINE="twin · daily · $START_KST → $END_KST KST · $PASSES/$EXPECTED hourly passes ($FAILED failed, $MISSING missing) · sent_ok $SENT${STEPS:+ ($STEPS)} · longest pass ${WALLMAX}s · $DAY$HOLD"
"$OPS/twin-notify" "$LINE"
echo "$LINE"
