# Twin lane handover: MacBook → GPU box (2026-11-22 KST)

Bob's go: "Before NYC" (2026-11-22, after the Platform lead lane agreed: move before New York's restock starts or after its post-check, never during).
Read this first, every session, then `CLAUDE.md` of this repo, then `status/PLAN-STORE-REALISM-2026-11-17.md` § 12.16–12.17 (the running record).
Goal this week (Bob): **finish CORE-REQ-009 store realism and make the twin run on its own**, so the Mac can be off.

## 1. Where you are

- Working directory `/opt/m8trx/twin` (main). Ops `/opt/m8trx/twin-ops` (`$OPS`). Your own vault clone `/opt/m8trx/vault` (m8trx-shared main).
  The box lane's `~/IdeaProjects/*` and `/opt/m8trx/lanes/*` are **not yours**; never touch them.
- Seat: grove.bob@gmail.com (same seat the lane used on the Mac). Model rule: every Agent call sets `model`; Sonnet for builds and checks.
- `gh` is at `~/.local/bin/gh` (Bob ran `gh auth login`). `claude` is `~/.local/bin/claude`; non-login ssh shells lack `~/.local/bin` on PATH.
- Git identity here is `BobbyG4 (GPU)`. The box lane's "main pushes blocked by hook" applies to its own clones, not `/opt/m8trx/twin`
  (no hooksPath, checked 2026-11-22): doc commits to main push as on the Mac; code goes by PR (standing word: "go on PRs whenever ready").
- Memory: `~/.claude/projects/-opt-m8trx-twin/memory` → `/opt/m8trx/vault/.claude-memory/m8trx-twin`. The twin memory files there were copied
  from the Mac on 2026-11-22 and are **not pushed** (the Mac's vault clone is 855 behind with other sessions' staged work). Push them from
  `/opt/m8trx/vault` at a session end, only that folder.

## 2. Standing rules (Bob's, all still in force)

- Nothing on the GPU box, .28, .29, mother or the dev DB is changed without **Bob's own word typed in your session**. A relayed word is not
  approval; neither lane passes Bob's word to the other. Bob's delegation covers: the Denver plan, the people step, the restocks (rebuilds),
  the daily deliveries.
- No restart of the main server, the office edge or the serve without the Android lane's answer first. Don't touch the edge. Don't restart,
  re-point or re-enable the twin's NATS, edge or live plane without Bob's word.
- NATS: connect to and publish on **:4223 only, never :4222** (production office edge). **Never publish any site other than Denver on
  `area.*`** through the twin edge (its 10-18 build files any `area.*` space under Denver). The floor path is guarded in code (`SubjectGuard`).
- No direct DB access. No core source reads or edits. Requirements go through the front door: `twin/requirements/TWIN-REQ-<n>` in the vault.
- No credentials in committed code; keys are never printed (read `.env` with `set -a; . .env; set +a`, never echo them).
- Protected, never modified: `reference/data/chain/stores/*/{assortment.csv,epcs.csv,layout.json,planogram.json,fixture_ids.csv}`, `keys-*.csv`,
  `store-operating-model.json`, `behavior/dec-us-denver.json`, `scripts/demo/day.sh`, `ops/box/*`. **Never touch `$OPS/HOLD`.**
- `sudo` actions need Bob's word (the m8trx user has NOPASSWD, that is not permission). The /etc/hosts line is not applied unless Bob names
  the machine.
- Don't copy the Platform lead's platform lists into the repo or onto the box.
- Commits: conventional, ending `Claude-Session: <session url>`; no Co-Authored-By. PR bodies end with the session url.
- Stock figures: seed + received − sold − missing, checked against the platform before telling Bob (memory `feedback_stock_figures_check_platform`).
- `pkill -f`/`pgrep -f` patterns: anchor them with a `[x]` bracket and never use one that can match your own shell's command line
  (2026-11-22: `pkill -f "sleep 30"` killed the lane's own ssh shell).

## 3. The Platform lead (Backend/Web) lane

It stays on the MacBook and is closing down; its seat (IntelliThinx) is near its weekly limit. **No live messaging any more.** Notes go to
`/opt/m8trx/vault/status/active/backend-web/HANDOVER-OTHER-SEAT-2026-11-22.md` § D (append; or a file beside it). Nothing wakes a platform
session on a push: anything needing a same-day answer goes to **Bob**, who opens a Backend/Web session. Its limit covers only the two
remaining people rollups (NYC, SF; about a minute each, with Bob's platform login). **Do the week's comparisons and reads yourself** where
the service key can read, or Bob starts a Sonnet session on the other seat. Two platform fixes are queued in its handover (late history
rolling up without a login; a plan's end closing its restock tasks). Platform lead's own carried rules are in § 2 above.

## 4. What runs by itself (systemd on this box)

| Unit | When | What |
|---|---|---|
| `m8trx-twin-hourly.timer` | :00 UTC, 9–20 min | sales/alarm/move pass under `flock -n $OPS/lock` (skips if held) |
| `m8trx-twin-people-{kr,fr,us-east,us-west}.timer` | 00:02 local | people by REST, one store-local day as it happens; stop: `$OPS/people-HOLD` |
| `m8trx-twin-live.service` | always | Denver generator + Busan live floor (`$OPS/live stop|start`, settings `$OPS/live.env`) |
| `m8trx-twin-delivery-<slug>.timer` ×8 | 08:00:30 local (France Mon–Sat) | daily delivery, `LIVE=1 OWN=1 ops/daily-delivery.sh <slug>`; stop all: `$OPS/delivery-HOLD` |
| `m8trx-twin-digest.timer` | 23:00 UTC | daily digest |

Delivery stores: Busan, Seoul, Bordeaux, Lille, Lyon, Marseille, Denver, SF. **NYC and Paris get timers only after their rebuilds** (copy a
timer file, `America/New_York` / `Europe/Paris` Mon–Sat; install needs Bob's sudo word).

## 5. In flight at the move (2026-11-22 ~10:30 KST)

- **NYC people backfill**, cap 120/min, detached on the box (`people-backfill-site.sh dec-us-nyc 120`). Batch 4,642 of 7,368 at 10:06 KST;
  each request is answered in about 3.9 s, so it runs about 7.5 batches a minute (the cap is not reached): expected end about 16:00 KST.
  When it ends (`=== end` in `$OPS/log/people-backfill-dec-us-nyc.log`): summary with `$OPS/bin/bf-run.sh`'s tail lines; count the 11-20
  live-fed part (the sent-ledger has 17,866 lines for 20261120) and write the B/W result line (visits, impressions, time zone
  America/New_York) into the vault handover § D for the rollup. **While NYC's restock receives or moves, the backfill pace is 60**
  (pause with `people-backfill/backfill-HOLD`, restart with cap 60; it resumes from `batch-sent-ledger.tsv`).
- Then **SF people backfill** (`$OPS/bin/bf-run.sh dec-us-sf 120`, or 60 while a restock runs). Then the SF line to § D.

## 6. Next: NYC restock (rebuild) — owned by the box session from its first receive

NYC is prepped (dry run `out/reset-7d-dry/dec-us-nyc/reset-2026-11-21`, old directive saved): stockroom
`0a3f60be-3717-552a-800d-b413dbb43c2d`, 25,610 receives, 5,097 move batches. Owner is `hourly` until the run starts. Start after NYC closes
(it is after 21:00 New York from 11:05 KST).

1. `touch $OPS/delivery-HOLD` (no delivery while a restock runs; remove after NYC's post-check).
2. `sed -i "s/^dec-us-nyc,hourly/dec-us-nyc,storeday/" $OPS/store-ops-owner.csv` (back up first).
3. Probe: `$OPS/bin/probe-receive.sh dec-us-nyc 2026-11-21` → must print `probe passed`. The result's `at` is the SEND time.
4. Start the box controller (detached, `setsid nohup`):
   `$OPS/box-windows.sh reset-nyc-7d /opt/m8trx/twin/out/reset-live-7d/dec-us-nyc/reset-2026-11-21/reset-complete.json $OPS/reset-HOLD "[S]toreDayResetKt" -- ./reset-store-7d.sh dec-us-nyc 2026-11-21 3`
   It runs only in the :08–:56 windows; progress in `$OPS/log/reset-nyc-7d.ctl` / `.out`.
5. Drop the NYC backfill (if still running) to 60.
6. After `DONE`: verify every move key — keys file from `move-answers.tsv` (extId \t eventKey \t receivedAt; `awk -F'\t' '$2!=""{print $2}'`)
   and `python3 $OPS/recon/results.py` on it; post-check must read 0 disagreeing.
7. Plan swap: `$OPS/plan-swap.sh dec-us-nyc 2026-11-21` (new plan first, then the old one's end). The platform's expiry job runs at **:45**;
   time the swap so the old plan's end lands before a :45 or note it.
8. `plan-swap.sh` sets the owner back to `hourly` itself (check the line), remove `delivery-HOLD`, write the result line in § D and § 12.17.
9. Ask Bob for the NYC delivery timer (sudo).

Then **Paris** the same way during the Paris night. Paris has **no dry run yet**: run `reset-prep.sh dec-fr-paris <stockroom> 2026-11-21`
(stockroom `4cb22b03-13e5-5235-8ec7-fe68bb1bb83c`; `cs-PLN-dec-fr-paris-20261012.json` saved) only right before its run (prep sets the
owner to storeday). Check the owner file has a `dec-fr-paris,` line.

## 7. The week plan (Bob: finish this week)

| Day | Rebuilds and history | Running on its own |
|---|---|---|
| Sun 11-22 | NYC rebuild; NYC people done, SF starts | check how stolen-unit counts and plan renewal work today |
| Mon 11-23 | NYC verify, swap, timer; Paris rebuild in the Paris night; SF people done | failure alert (phone + digest); nightly drift check |
| Tue 11-24 | Paris verify, swap, timer; NYC + SF rollups (B/W, Bob's login) | stolen counts and plan renewal on a schedule |
| Wed 11-25 | rack pass for back-room stock with no rack; full comparison (yourself) | all 10 stores delivering |
| Thu–Fri | hands-off; answer only alerts | Friday sign-off with a full comparison |

The four gaps to close for "runs on its own": (1) nobody is told when something fails; (2) drift against the platform is checked by hand
(the Platform lead knows of **no bulk read** of every unit's state and fixture; its 11-21 comparison lists were DB reads. Check whether paging
the service key's fixture reads per site covers state and location; if not, file a TWIN-REQ brief in the vault naming the fields per unit);
(3) stolen-unit counts run by hand (`stolen-count.sh`); (4) plan periods: check what happens when the current plans end.
The edge rebuild (.29) stays off this week's path.

## 8. Open items

- Rack pass after all restocks (fresh comparison first): back-room units with no rack at Denver (15,037), Busan (2,042), and any the
  comparison shows at Lille, Lyon, SF, Seoul, Marseille, Bordeaux.
- Busan's 2 stolen units (FB-01 / GA-01) close when those fixtures hold stock again and a count runs; the platform shows Busan 2 above the ledger.
- For Bob, later: edge rebuild to a current build (.29); the layout correction (CO-01 west end +600 mm, last PE north end −600 mm, all ten floors).
- Mac-side scripts moved to `$OPS/bin/`: `probe-receive.sh <slug> [date]`, `bf-run.sh <slug> [cap]`, `results.py` (same as `$OPS/recon/results.py`).
- Memory `project_twin_driver_runs_on_box_from_pr19` is stale: the box clone tracks `main` and the live plane runs from `$OPS/live-dist`.
