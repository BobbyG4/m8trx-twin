# Session 16 — PROXY CLOSE (written by the Coordinator, 2026-07-30)

> ⚠ **This close was written BY THE COORDINATOR from S16's own artifacts** after the session died to repeated provider API errors and could not be revived. Every claim below cites an artifact S16 produced; nothing rests on Coordinator memory. **S17's first act: countersign or correct this note and the core workbench §0 verdict.** Boundary note: the Coordinator also read repo files and ran one drive (flagged inline) while this lane was dark — cross-lane crossings were announced in the core session transcript each time.

## What S16 did (all pushed: branch `fix/fixture-coverage-in-area-zone` @ `6839a4f` — six commits, harnesses green 50·27·connect; core workbench sections 2.1a–e)

1. **Provenance countersigned with a load-bearing correction** (workbench 2.1a): no twin-side NATS consumer has ever existed; S15's "observed 3,664" was an out-of-band count with no surviving artifact. Independence argument preserved via evening's `observed 854 vs predicted 812`. Verdict: *unsupported but not excluded* — later settled by the Coordinator's acceptance run (below).
2. **The Coordinator's phase-shape hypothesis falsified by construction** (2.1b): arrival-rate boundary at wall-m13.33 (lunch→trough), but persona per-arrival, field set invariant, zero circles in the run; emitted-per-minute series committed (`status/active/data/fullday-0728-emitted-per-minute.csv`). Zero-residual fit: `accepted = min(emitted, ~51/min)` from m14.
3. **The decisive drive** (`dbe004b`): defect REPRODUCED (1,512 predicted/wire vs 1,370 rows, −142/9.4%) **with full recovery once load fell** — unified both runs as one load-triggered transient. `Channel(100)` refuted with an artifact (wire == prediction).
4. **`impressionWatch` built** — the in-code, liveness-printing, deduped wire counter; the S16 standard: *no wire count asserted again without an artifact behind it.* Rule 2.1e filed: an absence reading counts only once the instrument has shown it can read presence.
5. **§B5 oracle-vs-actual: DONE** (`6839a4f`) — the oracle is exact; the model and the transport are now separable.
6. **The "edge is down" false alarm retracted** (2.1a-adjacent): the probe was `echo > /dev/tcp` under zsh — every port read "closed" including provably-open ones. Fourth broken-instrument claim of the day; codified into 2.1e.
7. **Fixture-coverage fix** (the branch's purpose): circles moved out of the department-less `Z-01` so they participate in attribution. **Branch is UNMERGED and undisposed — S17's call.**
8. Probing retired: `/spatial/identity` agrees with the brute-forced `fixture_ids.csv` 115/115 + 26 zones the sidecar never had. Grants live on the bearer (`vision_ai:view`, `task:read`).

## What the Coordinator executed while this lane was dark (flagged)

**The #215 acceptance drive, Pass-1 profile** (store 12–16 @18×, seed 4242, tag `accept215`, 2026-07-30 09:01–09:23 UTC), watcher liveness-proven first: **prediction 1,512 = wire 1,512 = subscriber received/recorded 1,512 = rows 1,512; sessions 308/308; every loss counter 0; `slowConsumerDetected` 0.** The wire CSV is promoted to `status/active/data/accept215-wire-counts.csv`. Mechanism (core workbench §0): client-side jnats discards under sustained load, fixed by svc #215 (async handoff + counted queue + ErrorListener + io.nats WARN→FILE_ERR).

## S17 openers, in order

1. **Countersign this note + core workbench §0** (`m8trx-shared/status/active/IMPRESSION-PERSISTENCE-LOSS-ANALYSIS-2026-07-30.md`).
2. **Dispose the branch** (`fix/fixture-coverage-in-area-zone`, 6 commits — merge call is twin's).
3. **Fullday-profile rerun** (expect 3,664/3,664) + **the throttled negative control** (Coordinator flips `m8trx.impression.post-workers=1` on your edge — signal in the workbench; counters must go NON-zero, in `err.log`).
4. **Confinement gate — UNBLOCKED:** `M8TRX_AUDIT_PASSWORD` is the uniform demo login, documented with env aliases in `m8trx-shared/working-doc-archive/M8DEMO-LOGINS.md` (Bob's turnkey ruling; a site-scoped audit key was also minted). No more out-of-band anything.
5. Standing items: §5 economics recalibration (Bob's call) · Hasura secret rotation (Bob's) · the site-scoped key rotates when the investigation's completeness items close (your own flag).
