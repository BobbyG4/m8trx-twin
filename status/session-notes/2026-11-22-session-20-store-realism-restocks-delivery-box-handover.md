# Session 20 — CORE-REQ-009 store realism: stock to plan, people history, live floor, daily delivery; lane handed to the GPU box

**Dates:** 2026-11-17 → 2026-11-22 (KST) · **Seat:** grove.bob@gmail.com (MacBook CLI) · **Plan:** `status/PLAN-STORE-REALISM-2026-11-17.md`
(running record § 12.16–12.17) · **Handover:** `status/active/twin/HANDOVER-BOX-2026-11-22.md` — **the box session `twin-box` owns the lane
from 2026-11-22 10:17 KST.** This Mac session starts nothing more.

The schedule was led by the Backend/Web lane (S39X, "Platform lead") on Bob's delegation; Bob's words are quoted where they decided a step.

## What shipped (PRs merged to main)

| PRs | What |
|---|---|
| #17, #27 | landed the box's #17/#18/#19 stack (simulated year, demo steps, live plane, pricing) on `main`; the box clone tracks `main` since |
| #28 | shopper agents for planogram draft scoring (TWIN-REQ-007 round 1) |
| #29–#36 | store model Phase 1 (Denver profile, kits, demand, planogram v2) and store-day Phases 2a–2e for all ten stores; D3 store profiles |
| #37, #42 | people by Connect REST for the nine non-Denver stores (offline, then live sender) |
| #38 | fixture moves weekly on the planogram Monday, small units only |
| #39–#41, #45 | live reset (rebuild): to_zone_id moves, existence pre-check, location post-check, back-room cover from each EAN's demand |
| #43, #54 | sales sell only what the store holds, and only from the sales floor |
| #44, #46–#51, #53 | reconcile sender (sales, moves, put-aways), 200 items/min, 10 s halt, missing-unit resends, eventKey log, stolen-unit counts |
| #52, #60–#62 | daily delivery; service key for the rack lookup; systemd timers ×8; France Mon–Sat |
| #55, #56, #58 | people backfill 10-05..11-20; batch cap 120/min; per-request BATCH-REQ timing log |
| #57, #59 | live floor people (walking model, xovis subjects only, hard `SubjectGuard`); stands never on thin ends or perimeter wall sides |

Docs: § 12.16–12.17 kept as a running record (≈30 commits `docs(status): …`), handover `449a35f`..`7afc3e2`.

## Outcomes (all read back by the Platform lead unless noted)

- **Ledger = platform** (§ 12.16, Bob: "100% accurate"): resent refused sales, moved theft to `stolen-epcs.txt`, two rounds of targeted
  counts. Remaining known differences: Busan's 2 stolen units on FB-01/GA-01 (no other stock there to count against), back-room units without
  a rack (Denver 15,037, Busan 2,042 + per-store after rebuilds) for the rack pass.
- **Rebuilds** (Bob: "I just want to make sure it's a rebuild … then we go to the daily normal delivery times"): Busan, Marseille,
  Bordeaux, Lille, Lyon, SF, Seoul done — every move key processed, post-check 0 disagreeing, plan swapped (new first, then the old end).
  **NYC (prepped) and Paris (not prepped) remain** — the box session runs them.
- **People history** 10-05..11-20 rolled up and read back equal: Seoul, Busan, Paris, Lille, Lyon, Bordeaux, Marseille. **NYC running,
  SF next** (box).
- **Busan live floor** from 2026-11-21 21:30 KST; stand fix #59 live after the 08:58 KST restart on 11-22 (Bob: "before Busan opens").
- **Daily delivery**: Busan pilot 11-22 08:17–08:21 KST (6 received into BR-04, 24 batches / 36 units booked, platform 8,708 = ledger
  8,706 + the 2 stolen). Timers installed for 8 stores at 08:00:30 local (Bob: "Install now"; France Mon–Sat, Bob 11-22).

## Failed approaches and mistakes — do not repeat

1. **Stock from one file.** Told Bob three stores had "0 units" from `epcs.csv` minus sold; the ledger also holds receives. Memory
   `feedback_stock_figures_check_platform`: seed + received − sold − missing, checked against the platform.
2. **Mac-side window controller.** The Mac's 1Password SSH agent locked overnight; the controller could not set HOLD at :57, SF ran through
   two sales passes (skipped, caught up at 01:00, nothing sent twice). Fix: `box-windows.sh` runs ON the box.
3. **Reported probe SEND times as answer times** (Lyon, SF). The probe's `at` is the send time.
4. **`reset-prep.sh` on an open store** sets the owner to `storeday` — NYC's prep did, while NYC was open. Prep only right before the run.
5. **`results.py` over the wrong column** of `move-answers.tsv` (extId \t eventKey \t receivedAt): build the keys file with awk on `$2`.
6. **Floor mode replaced Denver's generator** (PR #57 first pass) and an `exitProcess` in the floor thread could kill Denver — made the
   floor additive with a pre-start `validate()`.
7. **The twin edge (10-18 build) files any `area.*` space under Denver** — the floor path publishes only `m8trx.<t>.<site>.xovis.*`,
   enforced by `SubjectGuard`. Never another site on `area.*`.
8. **Daily delivery refused at 08:15 on 11-22**: the rack lookup read `/spatial/identity` with `M8TRX_TWIN_BEARER`, which is 403
   SITE_ACCESS_DENIED for Busan. The service key reads it (#60), as the reset already did.
9. **`pkill -f "sleep 30"` killed the lane's own ssh shell** (its command line contained the pattern). Nothing else was hit. Anchor
   patterns with `[x]` and never match your own command.
10. **Replaced the box's `~/.claude/CLAUDE.md` with the Mac's** — it dropped the box lane's own rules (ARLS, serve, worktrees). Restored
    from `CLAUDE.md.bak-20261122` within minutes and only appended a twin-lane section. The box file is a fork owned by the box lane.
11. **An apostrophe inside a single-quoted ssh command** ended the quote; the rest ran locally as harmless "not found" errors. Write
    remote text through a file + scp.
12. **Estimated the NYC backfill end as 10:30** from the cap; real pace was set by the platform's answer time (see below): about 16:00.

## Key discoveries (platform / integration)

- **People batches slow as a site's history grows**: NYC median answer 0.6 s → 3.7 s over 4,600 batches of 100, climbing across days; box
  idle, health 0.5 s. Over 3 s the runner backs off (waits as long again). Told the Platform lead (recorded on its side, not traced).
- **The platform's plan expiry job runs at :45**; **ending a plan does not close its restock tasks** (queued platform fix).
- **No bulk read of every unit's state and fixture per site** exists (the Platform lead's 11-21 comparison lists were DB reads). The nightly
  drift check needs one: the box session checks whether paging fixture reads with the service key covers it, else files a TWIN-REQ.
- `M8TRX_TWIN_BEARER` is not scoped to every store; `M8TRX_TWIN_SERVICE_BEARER` is.
- A late-history rollup needs Bob's platform login (queued platform fix: roll up without a login).

## Decisions (Bob's words)

- Backfill: "Go, at 120/min". Live restart for Busan: "Go, after Lyon"; for the stand fix: "Before Busan opens".
- Rebuilds then daily delivery: "I just want to make sure it's a rebuild … then we go to the daily normal delivery times for all these."
- Delivery timers: "Install now". France: no Sunday delivery ("yes on the french stores").
- Goal: finish this week; the Mac cannot stay on 24/7 → move the lane to the box: "Before NYC" (after the Platform lead agreed: before
  New York's restock or after its post-check, never during).
- Android/ARLS lane: the twin holds restocks while `/opt/m8trx/twin-ops/FLOOR-IN-USE` exists (Bob's office lock walks).

## State at close (2026-11-22 10:20 KST)

- `main` at the session-end commit (after `7afc3e2`); box clone `/opt/m8trx/twin` on `main`. Uncommitted on the box: `status/SIM-RUN-LOG.md`
  (box-side, not this session's).
- Box: live plane active (started 08:58:18 KST with #59); 13 twin timers (hourly, people ×4, delivery ×8); NYC people backfill running
  (batch 4,768 of 7,368); no HOLD / FLOOR / stop files; NYC owner `hourly`; no Paris line in `store-ops-owner.csv`.
- Box session `twin-box` in tmux `twin` (attached), took over 10:17 KST, starts NYC's restock from 11:05 KST.
- Box setup by this session: `gh` 2.105.0 at `~/.local/bin`; vault clone `/opt/m8trx/vault`; memory link
  `~/.claude/projects/-opt-m8trx-twin/memory` (twin memory files copied, **not pushed**); scripts in `$OPS/bin/` (probe-receive.sh,
  bf-run.sh, results.py); `box-windows.sh` honours `FLOOR-IN-USE` (backup `.bak-20261122`).
- Mac: no background jobs; the Platform lead stays on the Mac (closing down; notes via its vault handover § D).
