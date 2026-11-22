# Twin on the GPU box: what runs, 2026-11-22 13:35 KST (read-only inventory)

Taken at 13:30 KST on the box. Nothing was stopped or changed. **Every "stop" or unit change below waits for Bob's yes**, and none is
done while New York's restock runs. Left out as asked: `m8trx-reloc.service`, `m8trx-count-bench`, `/opt/m8trx/lanes`, `/opt/m8trx/reloc`,
and the cron lines for the uptime watch, the lane orchestrator and the shadow-chain night (not the Twin's).
Note: `ps` on the box prints UTC; the table below is KST (UTC + 9).

## 1. Why the three people services show failed (the answer to item 4)

`m8trx-twin-people@kr`, `@us-east` and `@us-west` did not crash. The runner `PeopleRestLive` stops itself when `$OPS/people-HOLD` exists,
logs `people-rest live: HOLD`, and exits with code 6; gradle prints `BUILD FAILED`, the script returns 1, systemd marks the service failed.

- `@us-east`: started 11-21 14:02 KST (the 00:02 New York timer), stopped by the HOLD at 16:23:44 KST after 2 h 21 min.
- `@kr` and `@us-west`: started by their timers (kr 00:02 KST 11-22), stopped by the same HOLD at 03:34:51 KST 11-22 (both at the same second).
- The NYC run on day 11-21 was restarted at 16:47 KST (07:47 UTC; by hand, judging by the log: no timer fires then) and stopped by the same 03:34:51 HOLD (`BUILD FAILED in 10h 47m 16s`).
- `people-HOLD` is gone now (no such file). Who touched it, and when it was removed, is **not recorded** anywhere I could read (the journal shows only the exits).
- The hand runs below were started 12 minutes after that HOLD, at 03:47:32 KST, after it was removed.
- The `RuntimeMaxSec=26h` warning in the journal is noise (oneshot ignores it).

## 2. Inventory

| # | Item | Started by | Also started by a timer/service? | Ends | Kind | Systemd owns it | Proposed action |
|---|---|---|---|---|---|---|---|
| 1 | `m8trx-twin-hourly.timer` → `.service` | systemd, :00 UTC | is the timer | each pass ~8-9 min; takes `$OPS/lock` (`flock -n`, skips if held) | steady | yes | keep |
| 2 | `m8trx-twin-digest.timer` | systemd, 23:00 UTC | is the timer | each run | steady | yes | keep |
| 3 | `m8trx-twin-live.service` (pid 3367214/3367231, `twin-live.sh` → `PeopleDriveKt`, -Xmx6g, since 11-22 08:58 KST) | systemd | is the service | never (always on); Denver generator + Busan floor | steady | yes | keep |
| 4 | 8× `m8trx-twin-delivery-<slug>.timer` (Busan, Seoul, Bordeaux, Lille, Lyon, Marseille, Denver, SF) | systemd, 08:00:30 local | are the timers | each run | steady | yes | keep. `delivery-HOLD` (row 12) currently makes them exit 6 |
| 5 | `m8trx-twin-people-fr.timer` → `@fr.service` (pids 3342429…3342638, 1 JVM -Xmx6g = Paris; running since 11-22 08:02 KST) | timer 00:02 Paris | is the timer | ends when the five stores' plans are all sent; window closes 23:59 Paris | steady, healthy | yes | keep (Lille, Lyon, Bordeaux, Marseille already DONE for 11-22; Paris WAITING, 17,875 of 17,875 left at 13:30) |
| 6 | `m8trx-twin-people-kr/us-east/us-west.timer` | systemd | are the timers | each run | steady | yes | keep; each fires with no hand copy on the same day (see § 3) |
| 7 | `@kr.service`, `@us-east.service`, `@us-west.service` in state **failed** | the timers; killed by the HOLD (§ 1) | n/a | already ended | one-off failure, stale state | yes | `systemctl reset-failed` for the three (clears the red; needs sudo, Bob's yes) |
| 8 | **Hand-started `people-daily.sh kr`** (pid 3239208; children: gradle 3239223 → daemon 3239544 → **JVM 3240231 Seoul -Xmx6g**; gradle 3240275 → daemon 3240363 → **JVM 3240730 Busan -Xmx6g**), started 03:47:32 KST 11-22, parent pid 1, cgroup `session-11505.scope` | hand (Mac session over ssh, 192.168.55.210) | **same job as `@kr`** | ends by itself when day 11-22 KST is fully sent (all 10,485 Seoul and 4,569 Busan items), window closes 23:59 KST; at 13:30: Seoul 8,117 and Busan 3,438 left | one-off, mid-day, but it is *today's* Seoul and Busan feed | **no** | **let finish**, then the timer owns the next day. Do not stop it: stopping loses today's remaining visits unless the timer copy is started in its place (see § 4) |
| 9 | **Hand-started `people-daily.sh us-west`** (pid 3239211; gradle 3239219 → daemon 3239368 → **JVM 3239890 SF -Xmx6g**), 03:47:32 KST | hand (same) | same job as `@us-west` | day 11-21 Pacific, window closes 23:59 PT = 16:59 KST today; 28 items left at 13:30 | one-off | no | let finish (about 3 h at most) |
| 10 | `box-windows.sh reset-nyc-7d` controller (pid 3400389, since 11:05 KST), its forked copy (3423457), `flock` (3423459) and `StoreDayResetKt` JVM (3423615, -Xmx6g, window 3, from 13:08 KST) | hand (this session, `setsid nohup`) | no (hourly pass shares `$OPS/lock`) | controller ends at `reset-complete.json` (DONE); each window ends at :57 | one-off, several hours more | no (tmux scope) | **let finish**; it is the NYC restock itself |
| 11 | tmux session `twin` running this Claude session (pids 3394579/3394592) | hand | no | when the session ends | steady while the lane is attended | no | keep; this is the lane |
| 12 | `$OPS/delivery-HOLD` (11-22 11:04 KST) | hand (this session, step 1 of the NYC restock) | n/a | removed by me after NYC's post-check (handover § 6 step 8) | flag | n/a | keep until the post-check; then remove |
| 13 | `$OPS/lock` (empty file) | `flock` target for the hourly pass, deliveries, restocks | n/a | held only while a flock runs (now by row 10) | lock | n/a | keep (a lock file with no holder is normal) |
| 14 | `$OPS/people-backfill/backfill.lock` (11-21 13:32 KST, empty) | NYC/other backfill runs | n/a | no process holds it; the 9 backfills are all finished | stale lock file | n/a | harmless; can be deleted once Bob agrees |
| 15 | Finished backfill runners (`bf-run.sh`, `people-backfill-site.sh`) | hand | no | all ended: NYC 10:49, SF 12:40 KST | done | n/a | none left running; nothing to stop |
| 16 | 4 gradle daemons (pids 3239368, 3239544, 3240363, 3342490), ~480 MB each | gradle clients of rows 5, 8, 9 | n/a | exit with their client (idle timeout after) | part of those runs | cgroups as rows 5/8/9 | none of their own |
| 17 | Flag files `FLOOR-IN-USE`, `people-HOLD`, `reset-HOLD`, `HOLD`, `*.stop`, `backfill-HOLD` | n/a | n/a | none exist | | | none |
| 18 | cron (user m8trx): `uptime-watch` every 5 min, `shadow-chain` 02:30 KST | other lanes | n/a | steady | not the Twin's | | out of scope |
| 19 | Anything left from the Mac session | | | | | | only rows 8 and 9 (their hand runs). No other process, tmux, `at` job or user unit of the Twin's |

Memory at 13:30 KST: 7.7 GB used of 31 GB (19.6 GB cache), six -Xmx6g JVMs allowed but each ~0.5-2 GB resident.

## 3. Rows 8 and 9 against the next timer: what happens, and does the platform store a visit twice?

- **us-east (New York):** no hand copy alive. Timer fires 14:02 KST today (05:02 UTC) for New York day 11-22. No overlap. NYC's day 11-21 finished by itself (DONE at 12:41 KST, 25,792 of 25,792).
- **us-west (SF):** hand copy is on **day 11-21 Pacific** (ends by 16:59 KST today at the latest); the timer fires 17:02 KST for **day 11-22 Pacific**. Different days, so no overlap in data. Gap: if the hand copy were still alive at 17:02 both would run for 3 minutes, on different days.
- **kr (Seoul, Busan):** hand copy is on **day 11-22 KST** (window ends 23:59 KST); the timer fires **00:02 KST on 11-23** for day 11-23. Different days, no overlap.

Visit ids carry the day (`ppl-dec-us-nyc-20261120-0-b`), the plan is frozen per day (`FROZEN plan reused`) and each store has one sent-ledger. Two live copies on the **same** day would each read the ledger once at start and then send their whole "later" list, so the same visits would go out twice. Whether the platform then stores two rows or deduplicates by that id is **not determined** (nothing on the box proves it, and I did not test it). Today no such pair exists, so it does not arise.

Earlier suspicion (hand copy beside a timer copy for the same day) does not hold now: the timer copies were already dead (killed by the HOLD at 03:34:51) when the hand copies started at 03:47:32.

## 4. If Bob wants everything under systemd (proposal, nothing done)

Rows 8 and 9 are the only items outside systemd that are steady feeds. The clean order: wait until each ends by itself (SF about 16:59 KST today, Seoul/Busan 23:59 KST today); the timers then take over at 17:02 KST (SF) and 00:02 KST on 11-23 (Seoul/Busan). To move now instead would mean stopping the hand run and starting `systemctl start m8trx-twin-people@kr` (resumes from the sent-ledger; a few seconds' gap, no repeat of sent items). That is optional and needs Bob's yes plus sudo.

## 5. What runs on the box when everything is clean

- `m8trx-twin-hourly.timer` (:00 UTC), `m8trx-twin-digest.timer` (23:00 UTC)
- `m8trx-twin-live.service` (Denver generator and Busan floor)
- 8 delivery timers (10 once NYC and Paris are added)
- 4 people timers (kr, fr, us-east, us-west), each starting one runner per store at 00:02 local; no hand-started copy
- no `people-daily.sh`, backfill or restock process outside a unit, except while a restock is deliberately running (`box-windows.sh`)
- flags: none present except while a hold is wanted (`delivery-HOLD` only during a restock)
- the tmux `twin` session carrying this lane
