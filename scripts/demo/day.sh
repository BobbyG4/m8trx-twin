#!/usr/bin/env bash
# day.sh — the twin's daily drive for the demo sprint's recent window (BRIEF-TWIN-RECENT-WINDOW § 3).
#
# One calendar day, in order. EVERY step is a dry run unless its own live flag is given — the script is safe to
# run with no flags (that is how it is tested), and nothing reaches mother or the twin edge without an explicit
# --live-* on the command line.
#
#   1. stream  connectChainActivity, dated: all ten stores' sales at their own times (+ restock/pricing/catalog).
#              The run id (plan + sent ledger + sale ids) is per date: twin-day-<yyyymmdd>-<seed>, overriding any
#              M8TRX_RUN_ID in .env — a fixed id made every day after the first reuse the first day's plan (2026-10-12).
#   1b. receive connectReceiveDrive, dated: the unit-creating half of the restock loop. The step-1 manifest only records
#              EXPECTED quantities; this step reads the date's sales plan + sent ledger, reproduces each store's manifest
#              lines (units sold per item code, rounded up to cases of 6 - the stream's own function), mints new SGTIN-96
#              EPCs for them (serial block 1,000,000,000+, never colliding with the chain data) and receives them into the
#              store's STOCKROOM space (POST /inventory/items/receive, one request per store, <= 200 EPCs each), so the
#              move step has stock to refill the walls from. Ledger .twin-state/receive/ (idempotent per date; a re-run
#              receives nothing). In a dry run `move` counts the receive PLAN as already landed so the numbers chain.
#   2. day     connectDayDrive on Denver in PLAN epoch mode (wire ts = the plan's store-local hours), the whole
#              day compressed (~40 min at 18x) — or a slice at compress 1 with --slice (the live moving dots)
#   3. alarm   connectAlarmDrive, day mode: the day's gate alarms (+ the scripted Denver 14:07 on 2026-10-13);
#              zone_ref = M8TRX_ALARM_ZONE_REF (default Z-01, every store's Entrance zone code — core resolves it)
#   4. move    connectMovementDrive, N random moves per store from the stockroom onto the plan's walls, PLUS the
#              restock: after the day's sales, every (fixture, EAN) that sold down gets the same number of stockroom
#              units moved back onto it (one inventory_movement each), so compliance holds where the backfilled
#              history left it instead of draining with the live week. M8TRX_MOVEMENT_RESTOCK defaults to true here
#              (export it =false to switch off). Reads the step-1 sales plan + sent ledger for the date, so a date
#              whose sales were not sent restocks nothing; idempotent per date (.twin-state/moves/). Summary line adds
#              restock_moves= restock_units= shortfall_units= (stockroom had fewer units of the EAN than sold).
#   5. stock   stocktakes (SF / Seoul / Denver)            — NOT BUILT this round, reported SKIPPED
#   6. tryon   try-on-zone sessions with unreturned items  — NOT BUILT this round, reported SKIPPED
#   then, with --verify: connectAcceptance (the Connect ship gate, read-only) + impressionVerify for Denver.
#
# A run with any --live-* flag appends one line per step to status/SIM-RUN-LOG.md
# (date · seed · step · counts · wall · mode). A dry run prints the lines it would append and writes nothing.
#
# Safety rails that live in the drivers, not here (a script cannot enforce what the code does not):
#   - DayDrive refuses to publish unless the broker's server_name is `edge-twin-denver` (:4223). :4222 is the
#     PRODUCTION office edge with real Xovis hardware.
#   - Every dated driver holds back events stamped in the future unless --allow-future (reads are [now-24h, now)),
#     and a live re-run of the same date resumes from its ledger in .twin-state/ without re-selling a unit.
#
# Usage:
#   scripts/demo/day.sh [--date YYYY-MM-DD] [--seed N] [--steps stream,receive,day,alarm,move,stock,tryon]
#                       [--live-stream] [--live-receive] [--live-day] [--live-alarm] [--live-move] [--all-live]
#                       [--slice FROM-TO] [--compress X] [--moves N] [--alarm-script slug@HH:mm,...]
#                       [--allow-future] [--verify]
# Cron (unattended): run from the repo root; .env is sourced here (gradle's JavaExec does not read it).
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT="$(pwd)"

DATE="$(date +%F)"
SEED=20261016
STEPS="stream,receive,day,alarm,move,stock,tryon"
LIVE_STREAM=false LIVE_RECEIVE=false LIVE_DAY=false LIVE_ALARM=false LIVE_MOVE=false
SLICE="" COMPRESS="18.0" MOVES=3 ALLOW_FUTURE=false VERIFY=false
ALARM_SCRIPT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --date) DATE="$2"; shift 2 ;;
    --seed) SEED="$2"; shift 2 ;;
    --steps) STEPS="$2"; shift 2 ;;
    --live-stream) LIVE_STREAM=true; shift ;;
    --live-receive) LIVE_RECEIVE=true; shift ;;
    --live-day) LIVE_DAY=true; shift ;;
    --live-alarm) LIVE_ALARM=true; shift ;;
    --live-move) LIVE_MOVE=true; shift ;;
    --all-live) LIVE_STREAM=true; LIVE_RECEIVE=true; LIVE_DAY=true; LIVE_ALARM=true; LIVE_MOVE=true; shift ;;
    --slice) SLICE="$2"; shift 2 ;;
    --compress) COMPRESS="$2"; shift 2 ;;
    --moves) MOVES="$2"; shift 2 ;;
    --alarm-script) ALARM_SCRIPT="$2"; shift 2 ;;
    --allow-future) ALLOW_FUTURE=true; shift ;;
    --verify) VERIFY=true; shift ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "day.sh: unknown argument $1" >&2; exit 2 ;;
  esac
done

[[ "$DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || { echo "day.sh: --date must be YYYY-MM-DD" >&2; exit 2; }
# The demo script's Tuesday alarm: Denver at 14:07 store-local on 2026-10-13, unless overridden.
if [[ -z "$ALARM_SCRIPT" && "$DATE" == "2026-10-13" ]]; then ALARM_SCRIPT="dec-us-denver@14:07"; fi

if [[ -f .env ]]; then set -a; . ./.env; set +a; fi
# Denver's twin-edge identity (public values: the keys export + site_ids.csv). The tenant comes from .env.
export M8TRX_NATS_URL="${M8TRX_NATS_URL:-nats://192.168.55.29:4223}"
export M8TRX_SPACE_ID="${M8TRX_SPACE_ID:-e3c9a424-3ced-5288-9756-19935d39f88f}"
export M8TRX_SITE_ID="${M8TRX_SITE_ID:-84f2a1c1-fb0a-41b2-9e0d-c9102a22ca7e}"
export M8TRX_TENANT_ID="${M8TRX_TENANT_ID:-unset-dry-run}"

ANY_LIVE=false
for f in $LIVE_STREAM $LIVE_RECEIVE $LIVE_DAY $LIVE_ALARM $LIVE_MOVE; do [[ $f == true ]] && ANY_LIVE=true; done

RUN_DIR="out/day-runs/$DATE-$(date +%H%M%S)"
mkdir -p "$RUN_DIR"
LOG_LINES=()
FAILED=0

want() { [[ ",$STEPS," == *",$1,"* ]]; }
mode() { [[ $1 == true ]] && echo LIVE || echo DRY; }

# run_step <name> <live?> <gradle task> [VAR=value ...] — env is set only for that one gradle run.
run_step() {
  local name="$1" live="$2" task="$3"; shift 3
  local out="$RUN_DIR/$name.log" t0 rc=0
  t0=$(date +%s)
  echo "── [$name] $(mode "$live") ./gradlew $task $*"
  env "$@" ./gradlew "$task" -q --no-daemon >"$out" 2>&1 || rc=$?
  local wall=$(( $(date +%s) - t0 ))
  local summary
  summary="$(grep -o 'SIM-RUN .*' "$out" | tail -1 || true)"
  [[ -z "$summary" ]] && summary="(no SIM-RUN line — see $out)"
  grep -E "PLAN|REFUS|ERROR|refused|held|SIM-RUN" "$out" | grep -v DEBUG | cut -c1-240 | sed 's/^/   /' | tail -8 || true
  if [[ $rc -ne 0 ]]; then
    # gradle wraps the driver's exit code, so the future-stamp refusal is recognized by its own log line
    if [[ "$name" == day ]] && grep -q "plan-mode stamps run up to" "$out"; then
      echo "   [$name] HELD — the plan's stamps run into the future (pass --allow-future, or drive a past day/slice)"
      summary="HELD future-dated · $summary"
    else
      echo "   [$name] FAILED rc=$rc — see $out"; FAILED=$((FAILED + 1)); summary="FAILED rc=$rc · $summary"
    fi
  fi
  LOG_LINES+=("| $DATE | $SEED | $name | ${summary//|//} | ${wall}s | $(mode "$live") |")
}

skip_step() {
  echo "── [$1] SKIPPED — $2"
  LOG_LINES+=("| $DATE | $SEED | $1 | SKIPPED: $2 | 0s | - |")
}

echo "═══ day.sh  date=$DATE seed=$SEED steps=$STEPS live: stream=$LIVE_STREAM receive=$LIVE_RECEIVE day=$LIVE_DAY alarm=$LIVE_ALARM move=$LIVE_MOVE  logs → $RUN_DIR"

if want stream; then
  run_step stream "$LIVE_STREAM" connectChainActivity \
    M8TRX_RUN_ID="twin-day-${DATE//-/}-$SEED" \
    M8TRX_STREAM_DATE="$DATE" M8TRX_STREAM_SEED="$SEED" M8TRX_STREAM_LIVE="$LIVE_STREAM" M8TRX_STREAM_ALLOW_FUTURE="$ALLOW_FUTURE"
fi

if want receive; then
  run_step receive "$LIVE_RECEIVE" connectReceiveDrive \
    M8TRX_RECEIVE_DATE="$DATE" M8TRX_RECEIVE_SEED="$SEED" M8TRX_RECEIVE_LIVE="$LIVE_RECEIVE"
fi

if want day; then
  DAY_ENV=(M8TRX_DAY_DATE="$DATE" M8TRX_DAY_SEED="$SEED" M8TRX_DAY_EPOCH_MODE=plan M8TRX_DAY_LIVE="$LIVE_DAY" M8TRX_DAY_ALLOW_FUTURE="$ALLOW_FUTURE"
           M8TRX_DAY_TAG="day-$DATE" M8TRX_DAY_COMPRESS="$COMPRESS")
  if [[ -n "$SLICE" ]]; then
    DAY_ENV+=(M8TRX_DAY_FROM_HOUR="${SLICE%-*}" M8TRX_DAY_TO_HOUR="${SLICE#*-}" M8TRX_DAY_TAG="slice-$DATE-$SLICE")
  fi
  run_step day "$LIVE_DAY" connectDayDrive "${DAY_ENV[@]}"
fi

if want alarm; then
  run_step alarm "$LIVE_ALARM" connectAlarmDrive \
    M8TRX_ALARM_DATE="$DATE" M8TRX_ALARM_SEED="$SEED" M8TRX_ALARM_LIVE="$LIVE_ALARM" M8TRX_ALARM_ALLOW_FUTURE="$ALLOW_FUTURE" \
    M8TRX_ALARM_SCRIPT="$ALARM_SCRIPT" M8TRX_ALARM_ZONE_REF="${M8TRX_ALARM_ZONE_REF:-Z-01}"
fi

if want move; then
  # A dry `move` that follows a dry `receive` counts the receive plan as landed (nothing is written in a dry run, so
  # without this the restock would see the drained stockroom). Never with live moves: those EPCs would not exist yet.
  ASSUME_RECEIVED=false
  if want receive && [[ $LIVE_RECEIVE == false && $LIVE_MOVE == false ]]; then ASSUME_RECEIVED=true; fi
  run_step move "$LIVE_MOVE" connectMovementDrive \
    M8TRX_MOVEMENT_PER_STORE="$MOVES" M8TRX_MOVEMENT_DATE="$DATE" M8TRX_MOVEMENT_SEED="$SEED" M8TRX_MOVEMENT_LIVE="$LIVE_MOVE" \
    M8TRX_MOVEMENT_RESTOCK="${M8TRX_MOVEMENT_RESTOCK:-true}" M8TRX_MOVEMENT_ASSUME_RECEIVED="$ASSUME_RECEIVED"
fi

want stock && skip_step stock "stocktake sessions (connectScanSweep + sessions REST, deliberate shortfalls) not built this round"
want tryon && skip_step tryon "try-on-zone sessions (fittingRoom* atoms) not built this round; edge-side try-on config unproven"

if [[ $VERIFY == true ]]; then
  run_step acceptance false connectAcceptance
  run_step impressions false impressionVerify M8TRX_VERIFY_SITE_REF=dec-us-denver
fi

echo "═══ SIM-RUN-LOG lines ($([[ $ANY_LIVE == true ]] && echo "appended to status/SIM-RUN-LOG.md" || echo "DRY — not written"))"
printf '%s\n' "${LOG_LINES[@]}"
if [[ $ANY_LIVE == true ]]; then
  LOGF="$ROOT/status/SIM-RUN-LOG.md"
  if [[ ! -f "$LOGF" ]]; then
    printf '# SIM-RUN-LOG — the twin'"'"'s live days\n\nOne line per step per live run of `scripts/demo/day.sh`. Counts are the drivers'"'"' own SIM-RUN summaries.\n\n| date | seed | step | counts | wall | mode |\n|---|---|---|---|---|---|\n' >"$LOGF"
  fi
  printf '%s\n' "${LOG_LINES[@]}" >>"$LOGF"
fi

[[ $FAILED -eq 0 ]] || { echo "day.sh: $FAILED step(s) failed" >&2; exit 1; }
