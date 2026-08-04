# Project Status — M8TRX Twin

---

## ⚠ NEXT SESSION PRIORITIES (updated **2026-08-04** — **Session 18 CLOSED** · ★ **§A ALARM CHAIN DRIVEN: mechanism PROVEN, access path NOT** · scopes granted then **reverted by `mig-211`** · ~15 false doc claims corrected, incl. the hard rule pointing at **production NATS**)

> **PICK UP HERE (Session 19).** Read first: `status/session-notes/2026-08-01-session-18-alarm-chain-driven-and-doc-integrity.md` — the *don't-repeat* record is **§ FAILED APPROACHES, and four of the six errors were my own, all one shape: acting on state someone reported rather than state someone measured.**
>
> **★ VERIFY BEFORE YOU BUILD.** This session's opener was contradicted by its own docs within minutes (a completed action listed as next-up; ~730 lines of finished work uncommitted and unrecorded). **Establish state from git + a live probe, then read STATUS/TRACK for intent.** Verified at close 2026-08-04 09:40Z by twin's own probe: `main` `f6df80d` clean/pushed · key `twin-s280-lockdown` holds `inventory:read · vision_ai:view · task:read` Denver-scoped · **`alert:read` 403 · `integration:manage` 403.**
>
> **✅ BRANCH STATE — `main` = `496e78d`, no open PRs, working tree clean.** PR **[#15](https://github.com/BobbyG4/m8trx-twin/pull/15)** merged 2026-08-01 (`feat/connect-alarm-chain` — the §8.1 alarm build + the doc-integrity pass). Before it, PR [#14](https://github.com/BobbyG4/m8trx-twin/pull/14) = `5e4b81a`: `feat/connect-read-half` *already contained* S16's `fix/fixture-coverage-in-area-zone` as an ancestor — they were never two independent branches, and any note saying "PR S16's first" was wrong. One merge landed both. **Verified count (2026-08-01): 29 commits between `39c8238` and `5e4b81a` — 27 non-merge + 2 merges**, one linear chain with S16's `5b06290` (fixture coverage) at the base and S17's on top, plus the coordinator's S16 proxy-close doc `f92078a` arriving from `main`. *(This line said "26 commits"; TRACK said "7 + 7". Both were wrong.)* Green at merge: ktlint · `connectSelfTest` 11 · `peopleSelfTest` 50 · `scenarioSelfTest` **37**.
>
> **★ THE RESULT.** Post-#245 drive on the reproducing profile (seed 4242, hours 12-16, compress 18): **oracle 1512 · wire 1512 · rows 1512 · sessions 308 · fixtures 115/115 · loss 0.00%.** The external proof the impression-loss fix never had, closing the arc open since S15. It settled the S282-vs-twin contradiction as a **timeline, not a side**: 9.4% pre-fix → **20.3% at 03:03** → **total** during the `vision_ai` entitlement-gate outage → **0.00% post-#245**. *(The gate-shedding explanation for the 03:03 partial was offered as **labelled inference, not asserted** — it needs someone who can see the gate.)*
>
> **★ TWIN NOW HOLDS A STANDING ROLE** (Bob's ruling): **`./gradlew connectAcceptance` is the Connect ship gate** — the only automated coverage that surface has, because every CI security suite drives a human JWT and none drives a Connect key. Green before ship; extend it as the surface grows; **coverage gaps print alongside failures.** Detail + the non-vacuity rule: `status/tracks/TRACK-TWIN.md` § STANDING ROLE.
>
> **NEXT, ranked (S19):**
> **(1) ★ RE-GRANT `alert:read` + `alert:ingest` on `twin-s280-lockdown` by a SANCTIONED route.** The S285 grants were unapproved production writes and `mig-211` reverted them — correctly. Until a legitimate grant exists, twin's §A result stays *"proven mechanism, unreachable path"*. **Verify by watching `api_key.last_used_at` move on the row edited, never by re-reading `api_key.scopes`.** ⚠ `integration:manage` deliberately **NOT** requested — S280 removed it on purpose; DLQ/health stay dark and get *reported* dark.
> **(2) Fire `A3`** (built, unfired) — same `dedupe_key`, different bytes: the only real test of alert-level dedupe on the webhook plane. Needs (1) + a word on tenant noise, since every run leaves permanently `active` rows while `clear` returns `cleared:0`.
> **(3) Three findings need a core RULING, not twin work** — `cleared:0` on a live alarm (is clear for *conditions* only?) · zone unresolved (*an `eas_gate` is not a `zone`*) · `alert_source` registration having no self-serve path, which is what forced every lane onto twin's identity.
> **(4) ⛔ The cold peer test on the alarm surface is CLOSED TO THIS LANE** — needs a fresh session holding only the published Connect doc, plus the coordinator's signal.
> **(5) 6 alert rows left `active` in Denver**, unseeable and unclearable by twin. Core-side cleanup.
> **(6) ✅ DONE 2026-07-31 — both branches merged in one PR** (#14; see BRANCH STATE above). *(This item said "PR the two stacked branches — S16's first, then S17's" and was already false when written, four lines below the note explaining why.)* **Superseding (1) for S18: the §8.1 alarm chain is BUILT and ON `main`** (PR #15) — `AlarmDriver` + `connectAlarmDrive` + `ConnectClient.queryAlerts` + `WebhookDataType.ALARM`, offline-green (`connectSelfTest` 11 → **12**, `[PASS] §8.1 alarm envelope`), dry-run exercised end to end against Denver's real `CS-01` geometry and a rule-derived tagged EPC. ⚠ **Never fired live, and the verdict is built to print `SENT, UNPROVEN` when it is** — all three documented vendor diagnostics need scopes twin's key lacks. **Live fire is blocked on the admin action in (2), not on twin.**
> ### ★★ §A ALARM CHAIN — THE MECHANISM IS PROVEN; THE ACCESS PATH IS NOT. Keep these separate.
>
> **What twin measured itself, 2026-08-01, and stands behind** (own requests, own reads, no `psql`, no inherited claim):
> `/alerts/query` **200** site-confined to Denver · `POST /alerts` **200** `disposition=recorded` · byte-identical retry → **`deduped`, same `alertId`** · `A1`×2 → **exactly one row** · **6 alert rows** read back with **`native_level=critical` preserved on every one** (so §8.2's keep-the-proposal promise **holds**, and a theft alarm routing `info` is a **registration decision, not data loss**) · clear-refusal **400 by design** (*"a person SEEING an alarm is not the alarm being over"*) · the clear enum **named by the server's own 400** (`resolved`/`expired`/`auto_resolved`) — an API closing its own doc gap in one round trip.
>
> **⛔ THE CONDITION THAT MAKES THIS UNQUOTABLE AS "SHIPPED".** That run only happened because `alert:read` + `alert:ingest` were on twin's key **via unapproved production writes, since reverted** (core `mig-211`, *"revert every unapproved S285 production write"*). **Re-measured 2026-08-02 12:23Z: `alert:read` 403 again; twin's key is back to `inventory:read · vision_ai:view · task:read`.** So:
> - **PROVEN:** the alarm chain traverses and the §6.6/§8.2 contracts behave as measured.
> - **NOT PROVEN, and never was:** that a vendor can *reach* it. **The only time the chain has ever traversed, it did so on access that had to be undone.** That is **TWIN-REQ-005 demonstrated rather than argued** — and it is the stronger form of the brief.
> - **NOT verifiable by twin at all:** whether the sends dead-lettered. The §7 DLQ and `/integrations/{id}/health` need `integration:manage`, which S280 removed on purpose and which was correctly **not** reversed to unblock a test. Coordinator-side reads reported `integration_event processed×4 / failed×3` (the `03:16Z` casualties only) and `alert_event created×9` — **cite that as their measurement, never as twin's.**
>
> **Findings filed from behaviour, all still open:** (a) `clear` returns **`cleared:0` for a `dedupe_key` raised seconds earlier and still `active`** — documented as idempotent success, so a real failure is indistinguishable from a no-op (the ack echoes `conditionKey:null`; clear may be for *conditions*, not point events, in which case telling a vendor to clear by `dedupe_key` is the defect). (b) **`subject_ref` silently dropped** — the parser is `UUID.fromString(...).getOrNull()`, so the only identifier a gate vendor holds is discarded without refusal; **published core-side** (`55f83ca2`). (c) **zone unresolved 6/6, landing site-level** — *an `eas_gate` is not a `zone`*, so a vendor handed a gate code has nothing to name; **needs a ruling, not a patch.** (d) **`alert_source` had exactly one row (`twin-eas`)**, so every CI lane *had* to post under twin's identity — the collision was **forced by the missing registration path**, which makes it a §8.2 argument rather than hygiene.
>
> ⚠ **Twin-side bugs found in twin's own instrument, recorded so they are not re-learned:** rows are **camelCase under a snake envelope** (the doc-shaped DTO compiled, self-tested green, and was wrong — the fixture was snake too, so it *agreed with the bug*) · a **future-dated `occurred_at`** is invisible to its own read-back · a **hardcoded 1h lookback** hid 3.5h-old rows · **8s was too short to settle** (Bearer readable immediately, webhook ~50s) and briefly reported 4 rows where 6 existed · the **webhook dedupe assertion was vacuous** (a byte-identical repeat is collapsed by the content hash one layer *before* alert-level dedupe, so `A3` — same `dedupe_key`, different bytes — is the only real test on that plane, **built and not yet fired**).
>
> **(2) ★ Grant `alert:read` + `alert:ingest` + `integration:manage` on the key twin ACTUALLY PRESENTS — `twin-s280-lockdown`, NOT `twin-data-plane-bearer`.** Twin cannot self-serve it (SEC-1 subset guard + `CONNECT_NOT_EXPOSED`). Unblocks FR-INTEG-16, the §7 DLQ, `/alerts/query`, and the §6.6 Bearer arm.
> ⛔ **Attempted 2026-08-01 and INERT: the grant went to the wrong row and was reported complete.** Twin's `.env` holds **`twin-s280-lockdown`** (Denver site-bound; `inventory:read · vision_ai:view · task:read`), and re-measured at 08:07Z all three diagnostics still `403 PERMISSION_DENIED`. **Both twin keys carry `vision_ai:view` + `task:read`, so scope shape identifies a key's *vintage*, never its *row*** — the discriminator is `api_key.last_used_at` moving on the row you edited. Recorded as a recurring class in **TWIN-REQ-005 § Update 2026-08-01**. Verify the next grant with a request, never by re-reading `api_key.scopes`.
> **(3) LP chain end to end** once BW-TRIAD applies mig 205 and BW-CONNECT registers `twin-eas`: alarm → `alert` → routed to an LP role → visible on `/alerts` → dispositioned, **each hop verified**. ⚠ **Twin owes substrate first — see (6).**
> **(4) directive→task follow-up — a DECISION, not build work.** Twin can drive the input and read the output but **cannot cause the middle**: `/activate` and `/evaluate` are `CONNECT_NOT_EXPOSED`. Either expose a scoped activation or declare the loop deliberately human-gated. *("Needs a backend watcher" understates it — a watcher observes; what is missing is an actor.)*
> **(5) F4's end-divergence is NOT closed.** `firstDwell`→`firstLook` diverge on a persisted row (proven live, 1,200ms), but `lastDwell == lastLook` because the impression flushes at eviction before the disengage tail lands. **Core's `min()` still cannot be observed choosing between two arms** — needs an episode outliving its own flush.
> **(6) ✅ DONE 2026-07-31 — LP substrate BUILT (Bob's ruling).** The dead "W-series watches" anchor is replaced by a **derivable rule** (`layer2/EasTagging.kt`: `price >= $150 AND category != "outdoor"` → **271 SKUs**, zero bulky items) and **`Shoplift` is now real code**, not a design-table entry: browse a fixture holding tagged stock → conceal a unit → **skip checkout** → walk out on a track that genuinely crosses `CS-01` at `y=600`. **The alarm is a consequence of the track** — `EasGateCrossed` is published from the emitted samples and carries the unpaid EPCs, so no journey fires an alarm directly. **Proven firing, not just compiling:** a generated day produces 2 concealments and 2 gate alarms, and every concealment yields exactly one alarm. `scenarioSelfTest` 27 → **37**.
> **(7) Planogram grain** — `build_planogram.py` is `item_cd`-keyed in two places, so `5391035`'s target sums both sizes and takes whichever EAN was read last. Fix identified (`(fixture, ean)`), **not applied** — regenerates 10 documents and changes directive payloads. Needs a go.
> **(8) §5 economics recalibration** — ATV $58 / avg line $26.40 from the superseded 871-SKU catalog. Bob's decision.
>
> **⚠ STILL OWED (Bob, cannot close from twin):** rotate the mother Hasura admin secret — de-hardcoding did **not** remove it from git history.
>
> **★ TWIN-SIDE TRUTHS TO CITE, because nobody else can state them:**
> **Twin is 99.8% of the platform's entire impression record** (7,206 of the census's 7,222) — so `FR-INTEL-29 — BUILT, 7,222 rows` is true about the *pipeline* and misleading as evidence about *production*. Same class: **`scan_event.position` is twin's seed** (102,675 of 102,683), in **per-space unregistered mm frames, SRID 0, Z=0** — not calibration-wrong, but Sales Floor `(0,0)` and Back Room `(0,0)` are different physical points, so **cross-space reasoning over it is invalid** until FR-SPATIAL-26 Pass-2. Labelled at source in `DEPLOY-HANDOFF.md`; **do not delete or regenerate** (reseed parity is byte-for-byte verified).
>
> **⚠ THE PATTERN WORTH ONE DECISION:** **Connect lets an integrator write, and increasingly read, but not provision or transition.** directive→task needs an actor · FR-INTEG-16 needs a scope · alarms need three registrations (two with no self-serve path) · and `PATCH /connect/service-keys/{keyId}/scopes`, documented in §7 as *the* supported way to add scopes, is `CONNECT_NOT_EXPOSED` to **every** Connect key.
>
> **⚙ EMIT-CONTRACT CONSTANTS (unchanged, hard):** **emit >1 Hz** (5 Hz default) · within **1m of a fixture EDGE** · ray held on the **SAME** fixture · **both** clocks past 5000ms · ~10s `expireAfterWrite` lag · **circles are CONTAINMENT-ONLY** (`Circle.edges()` is a stub) · **`com.m8trx.geometry` is OFF-LIMITS** (shared with Android AR).
> **⚙ PACING RULE:** compress each shopper's **ARRIVAL**, never their internal timing; anchor wire `ts` to the **plan**, not the wall clock.
> **⛔ TWO EDGES SHARE .29:** `:4223` = `edge-twin-denver` (twin) · `:4222` = `edge-itx-office` (**PRODUCTION, real Xovis**).
> **⚙ Live-fire reminder:** gradle `JavaExec` does NOT auto-load `.env` → `set -a; . ./.env; set +a` then `./gradlew … --no-daemon`.

---

## ⚠⚠ S16 CLOSED BY PROXY (Coordinator, 2026-07-30 — the session died to provider API errors and could not be revived)

> **READ FIRST, S17:** [`session-notes/2026-07-30-session-16-PROXY-CLOSE-loss-investigation-acceptance.md`](session-notes/2026-07-30-session-16-PROXY-CLOSE-loss-investigation-acceptance.md) — S16's work (all artifact-cited), the Coordinator-run acceptance (**1,512 = 1,512 = 1,512 = 1,512**, investigation CLOSED-PROVEN core-side), and your ordered openers. **First act: countersign.** Your branch `fix/fixture-coverage-in-area-zone` (6 commits) was local-only and is now protectively pushed; disposal is yours.

> ### ↕ RECONCILED 2026-07-31 — this block and the S17 block above close the same investigation on different days. Both are honest; read them together.
>
> The Coordinator's acceptance above (1,512 four ways, **2026-07-30**) and twin's independent confirmation
> (**0.00% loss, 2026-07-31 08:24**) are **not a contradiction and neither supersedes the other** — they
> measured different builds. The full sequence, with the intermediate states that make it coherent, is the
> timeline table in
> [`session-notes/2026-07-31-session-17-…`](session-notes/2026-07-31-session-17-connect-read-half-ship-gate-zero-loss.md):
>
> | When | Measurement | Loss |
> |---|---|---|
> | 07-30 pre-fix | wire 1512 → rows 1370 | 9.4% |
> | **07-30 Coordinator acceptance** | **1512 four ways** | **0%** |
> | 07-31 03:03 (twin, independent) | wire 1512 → rows 1205 | **20.3%** |
> | 07-31 06:36–07:18 | wire 1512/317/317 → rows 0 | **total** — the `vision_ai` entitlement gate |
> | **07-31 08:24 post-#245 (twin)** | **wire 1512 → rows 1512** | **0.00%** |
>
> So "CLOSED-PROVEN core-side" on 07-30 was true when written and **did not stay true** — an entitlement gate
> keyed to an unsatisfiable slug regressed it, and #245 closed it again. **Neither record is wrong; a reader
> taking either one alone would be.** Kept side by side deliberately: deleting the 07-30 acceptance to make
> `main` tidy would erase a measurement someone honestly made.

---

## Session 15/16 priorities (historical)

## Session 14 priorities (historical — updated 2026-07-03 — Session 14 · ★ CORE-REQ-005 parts 1–3 DONE (`connectFullLoop` #9 · `connectStress` #10 · compliance read-back smoke → **TWIN-REQ-003 SATISFIED**) · ★ **Strand V site-scope hardening GREEN** (`connectSiteScopeAudit` #11 — the at-scale 151-table sweep caught **3 event/audit leaks** core's core-36/tail-35 mapping missed) · **seat/perms tenant-wide leak** surfaced (core's `seed_users.py` fix) · main `2131384`)

> **PICK UP HERE (Session 15):** **CORE-REQ-005 parts 1–3 DONE + merged** (PR #9 `connectFullLoop` · #10 `connectStress` · compliance read-back smoke GREEN). **TWIN-REQ-003 SATISFIED** (core PR #76 `925f9a4` — `POST /api/v2/compliance/state` live; the `/state` 403 wall closed, assert-split done). **★ Strand V site-scope hardening GREEN** (`connectSiteScopeAudit`, PR #11) — a site-scoped user proven confined across token/picker/read planes + writes; **the at-scale 151-table sweep caught 3 event/audit leaks** (`integration_event`/`item_custody_event`/`unified_audit_event`) that core's core-36/tail-35 mapping missed → core fixed → twin re-verified 0. **Seat/perms tenant-wide leak** surfaced provisioning demo logins (root `seed_users.py` grants `user_tenant_membership` per site-role — **core's fix**; twin roster clean). `main` `2131384` (PRs #9/#10/#11 merged). *(Part-2 live-stress ceiling: conc ≤12 clean / 24 → ~50% `502` = S8 event-loop starvation; fix `429`@nginx + offload/MVC, post-demo.)*
>
> **NEXT:**
> **(1) directive→task smoke** (CORE-REQ-005 part-3, still owed) — rule-engine **LIVE on master (`f14482a`)**. Drive a directive (`connectPlanogramDrive`, or `connectFullLoop` w/ `M8TRX_LOOP_PUBLISH_DIRECTIVE=true`) = INPUT; **backend verifies the auto-created tasks** = OUTPUT (task-read `403` to twin, JWT-only). **Run WITH backend watching.** Then the **Notification** smoke as it lands (GetNotifications/count + the notification RLS user-scope fix).
> **(2) #7 receive→relocate** — gated on backend's **FR-COLLECT-ID / Identifier Resolution Pipeline** (EPC→SKU decoder); re-fire when pinged deployed → the 2706524 target climbs to compliant. Deliver owed artifacts (EPC→EAN→SKU test-vector fixture, Hansae 2nd-scheme `reference/data/mk-trend/`, resolution-rate metric).
> **(3) The full "play" / activity runtime** (`ACTIVITY-PLAN.md` / `LIVE-OPERATIONS.md`) — drift + remediation proven; animate the 24/7 operation on the validated Connect sims + drivers.
> **(4) conc-1 latency baseline** — single sales at conc 1 to isolate per-txn cost vs contention (localizes the S8 event-loop-starvation root; ~5 requests, cheap).
> **↻ Site-scope acceptance gate:** re-run `connectSiteScopeAudit` after core re-seeds (the `seed_users.py` perms fix) or when strands change — it is the gate that caught what internal mapping missed. Needs `M8TRX_AUDIT_PASSWORD` (cohort pw, out-of-band).
> **⚙ Live-fire reminder:** gradle `JavaExec` does NOT auto-load `.env` (+ stale daemon env) → `set -a; . ./.env; set +a` then `./gradlew connect… --no-daemon` (else `M8TRX_TENANT_ID is not set`). **Bearer confirmed LIVE.** Creds in gitignored `.env`: `M8TRX_TWIN_BEARER` · `M8TRX_TWIN_WEBHOOK_KEY` · `M8TRX_TENANT_ID=ecfa6903-5c50-439f-8f80-185982de944e` · `M8TRX_CONNECT_INTEGRATION_SLUG=twin-pos`.
>
> ---
> **PICK UP HERE — Session 13 (historical):** **The live-compliance demo is PROVEN end-to-end.** Backend shipped the
> **compliance-EVALUATION engine** (services #69 — `POST /api/v2/compliance/directives/{id}/evaluate` + `GET …/state` +
> a **recompute-on-sale hook**); Bob authorized the one-time **re-point** of the 28 resolved targets → the correct zone
> **`e82a21f3`** (Gondola R3 Back U1 = code `GB-R3-U1`, where the stock actually is); operator `/evaluate` set the
> baseline **27/1/0**. Twin drove **12 real sales** (`connectSaleStream`) → compliance drifted **compliant →
> partially_compliant → non_compliant** (incl. the `req=1` one-event edge case) → **24/2/2**, 4 targets spanning the arc,
> **triple-verified** every beat (twin fire → twin self-verify SOLD via `items/details` → Backend live `/state`).
> **The S12 wrong-zone mapping is RESOLVED** (re-pointed to `e82a21f3`). Detail:
> `status/session-notes/2026-07-01-session-13-live-compliance-demo-remediation-prep.md`.
>
> **REMEDIATION PROVEN + drivers shipped (PR [#8](https://github.com/BobbyG4/m8trx-twin/pull/8) MERGED).** Backend shipped
> the movement ingester (services #71→#72); twin drove live relocations — **#6 fully recovered `2→3` compliant**, #2 `0→2`,
> **24/2/2 → 25/2/1**, the **first live runtime `thing_location` writes**. **Receive→relocate mechanics proven** (minted +
> received a 2706524 unit → relocated). Built `connectMovementDrive` + `connectReceiveDrive`. **2 core bugs caught:** #72
> (multi-site movement resolution — **FIXED**) + **FR-INTEG-04/FR-COLLECT-ID** (EPC-only receive → `product_id` NULL → #7
> couldn't climb; banked, Bob option 2). Handed core the **clean-room SGTIN-96 EPC↔EAN decoder** (relocated core-side to
> `m8trx-shared/reference/dev/EPC-EAN-DECODER-FR-COLLECT-ID.md`).
>
> **NEXT SESSION (14):** **(1) FR-COLLECT-07 close-out** — Backend's building the Identifier Resolution Pipeline
> (EPC→SKU/EAN decoder) so receive links product; **when Backend pings it deployed, re-fire #7** (`receive→relocate`) → it
> climbs to compliant (closes the receive-of-new-stock edge). When FR-COLLECT kicks off, deliver twin's owed artifacts:
> the **EPC→EAN→SKU test-vector fixture** (from the 169k validated tags), the **Hansae 2nd-scheme** template
> (`reference/data/mk-trend/`), and **resolution-rate health-metric** pairing. **(2) The full "play" / activity runtime**
> (`ACTIVITY-PLAN.md`) — drift + remediation now proven, so **animate the ongoing 24/7 operation** (traffic → transactions
> → try-on → staff/restock/stocktake → LP/EAS) on the validated Connect sims + the new movement/receive drivers.
> **(3)** Optional: a **marketing/investor visual** of the proven compliance lifecycle (drift → remediation). **Read-back
> TWIN-REQ = Backend's now** (folds into their Connect **read-surface** on the FR-INTEG rollout → a scoped `/state` read
> for twin self-verify; interim stays DB-reads-via-Backend — no twin filing needed). **Still gated on Backend (deferred):**
> FR-PLN-08 compliance-check task-gen needs the **Notifications spine** (Triad Slice-1).
>
> **Creds (gitignored `.env`, machine-local — re-supply on a fresh box):** `M8TRX_TWIN_BEARER` — ⚠ **this description is
> SUPERSEDED, corrected 2026-08-01.** It read *"m8trx_c3…, scopes `integration:manage`+`scan:submit`+`inventory:create`+`inventory:read`,
> **tenant-wide**"*. **Measured truth:** the key is **`twin-s280-lockdown`**, prefix `m8trx_da6…`, **SITE-scoped to Denver**
> (not tenant-wide), holding **`inventory:read` · `vision_ai:view` · `task:read`** and **NOT** `integration:manage`,
> `scan:submit`, `inventory:create`, `alert:read` or `alert:ingest`. The Denver binding is a feature — it is what makes
> `connectAcceptance`'s negative controls real. Do not confuse it with **`twin-data-plane-bearer`**, a different row twin
> does not hold; a 2026-08-01 grant went there by mistake (TWIN-REQ-005 § Update). ·
> `M8TRX_TWIN_WEBHOOK_KEY` (twin-pos X-API-Key) · `M8TRX_CONNECT_OUTBOUND_VERIFY_SECRET` (aacd…, the C3 HMAC, set
> core-side too). `M8TRX_TENANT_ID=ecfa6903-5c50-439f-8f80-185982de944e` · `M8TRX_CONNECT_INTEGRATION_SLUG=twin-pos` ·
> integration `5dfba5cd`. ConnectConfig reads `M8TRX_TWIN_BEARER` first (`M8TRX_TWIN_SERVICE_BEARER` is now a fallback alias).
> Keys-tab throwaway test keys were **revoked** (confirmed 401).
>
> **Connect harness — all 9 `connect*` drivers built + exercised** (`com.m8trx.twin.connect`, gradle `connect*` tasks, `connectSelfTest` 8 cases green):
> `connectMultiSiteSmoke` · `connectSaleStream` (+ sold-EPC persistence `.twin-state/`) · `connectChainActivity`
> (sale/restock/pricing/catalog × all 10 stores) · `connectSelfVerify` (items/details read-back, the closed loop) ·
> `connectScanSweep` (DRY-RUN default; `M8TRX_SCAN_LIVE=true` for live §6 scans — hold for BACKEND reader-topology) ·
> `connectOutboundReceiver` (§9 LAN receiver; PR #6) · **`connectPlanogramDrive`** (Mode-3 `directive_kind='planogram'`; PR #7, LIVE-PROVEN) · **`connectMovementDrive`** (Mode-3 `inventory_movement` relocation; PR #8, LIVE-PROVEN) · **`connectReceiveDrive`** (§6 Bearer item-receive; PR #8). **Comms:** `twin` seat on Slack `#m8trx-dev` (`@m8trx_twin`,
> dormant-wake Monitor) — coordinator seat retired, Bob drives Backend↔Twin directly. Helpers: `m8trx-shared/brainstorm/comms/slack-*.sh`.
>
> ⚠ **Dedup-replay gap (NEW finding, filed for Bob/core CLEANUP):** a *failed* outbound event's content-hash blocks a
> same-payload retry + escapes map-and-replay (not quarantined). The post-reseed auth-500 (Hikari) incident is still core's (see Blocked on core).

**Session 7 = reseed-dataset realism overhaul + spatial-hierarchy correction. Site→spaces→zones (Pass 1), sport-universe departments, lean back-of-house, realistic size curves, site geo + `site_category` (CORE-REQ-002); reseed hand-off rewritten. All committed + deterministic. **Session 7 closed 2026-06-24; RE-RESEED v2 landed + VERIFIED on mother 2026-06-26 (Session 8)** (core; recorded m8trx-shared `693f706`) — twin-side cross-check passed byte-for-byte (zero drift), no dataset amendment needed.**

- **★ SPATIAL HIERARCHY corrected → `site → spaces → zones` (Pass 1)** — the single-space build was the error; **a site has MANY spaces** (canonical ruling `m8trx-shared/reference/dev/SPATIAL-HIERARCHY.md`, grounded in `7a. Data Model`). Each store now = **3 spaces** — Sales Floor (`sales_floor`) / Back Room (`stockroom`) / Fitting Rooms (`fitting_room`) — each its own SRF frame; **departments are `region` zones *within* the Sales Floor** (NOT spaces). `layout.json`→`spaces[]`; manifest→`stores[].spaces[]`; `assortment`/`epcs` unchanged (fixture resolves via `spaces[].zones[]`). **Pass 1 = structure (assembly columns `srf_to_site_transform`/`site_frame_anchor_space` DORMANT); Pass 2 = site assembly (transforms + `space_connection`) — PENDING.** `space_type` provisional pending Backend/Web ratification. Commit `c480446`.
- **Sport-universe DEPARTMENTS** — floor carved into Decathlon "univers" bands from `brand` (CORE-REQ-001 payoff): flagship 6–7 / large 4–5 / medium 2–3 (count = min(7 universes, gondola rows); e.g. Denver/SF 6, NYC/Paris 7). `sport_universe.py` (brand→universe, e.g. Quechua/Forclaz→Hiking, Kiprun→Running, Simond→Climbing, Wedze→Snow, Van Rysel→Cycling); `build_layout.py` emits department `region` bands (replacing the single "Main Sales Floor"); `build_chain.py` places each SKU in its department (absent universes fold to *General* in small stores). Decided after research — Decathlon's real organizing unit is the sport universe w/ a "Sport Leader" each.
- **Lean BACK-OF-HOUSE** — Stockroom (Z-05) is now real: `receiving_dock` + `backroom_rack` fixtures; **18% of each style staged to the backroom** (a real from-location for restock/receiving/stocktake). Decathlon-honest (lean ~10–15% footprint; their stores minimise BOH).
- **Realistic SIZE CURVES** — `size_curve.py`: per-STYLE depth budget × size-curve allocation (bell over distinct sizes, split across colours, color-aware). **Fixes the flat-depth bug** Bob caught (the "89 of one size" — actually 88-pair styles). Footwear now ~40 pairs/style, ~5 facings/modal size, thin tails. **Inventory 277,515 → 102,675 EPCs** (the old number was the bug inflating depth; with 464 styles, realistic depth can't be 277k). Density knob `TIER_SCALE` in `build_chain.py` (~2×) — Bob chose higher for testing variety + realism.
- **SITE GEO** — lat/long on all 14 sites (geocoded to address) in `chain_config.py` → populates `site.latitude/longitude` (was **0/14 populated** on mother; geo map had nothing to plot). Reseed adds an `UPDATE site` on the 14 existing rows.
- **Reseed hand-off REWRITTEN** — `reference/data/chain/DEPLOY-HANDOFF.md` §RESEED-2026-06-22 is the authoritative instruction (in-place mechanism); `CHAIN-DATA-SPEC.md` + `IMPORT-MAPPING.md` synced.

**Commits:** twin `17872e5` (realism) · `be0f712` (hand-off+spec) · `bf1915c` (site_category CORE-REQ-002) · `c480446` (site→spaces→zones Pass 1) + spec/status sync. **Session 6 detail:** `status/session-notes/2026-06-22-session-6-catalog-coding-perstore-layouts.md`.

### What's LIVE on mother (✅ RE-RESEED v2, 2026-06-26 — canonical `site → spaces → zones` model)

| Asset | State |
|---|---|
| Tenant | **M8trxDemo** `ecfa6903-5c50-439f-8f80-185982de944e` (pre-seed backup retained on mother) |
| Sites | **14** — 10 retail (US×3 / FR×5 / KR×2) + 4 office · **lat/long + `site_category` live** (CORE-REQ-002, core mig 146) |
| Spaces | **30** — each retail store = **3** (`sales_floor` / `stockroom` / `fitting_room`), own SRF · **929 zones** · **53 try-on** · `space_type` Hasura-exposed · Pass-2 assembly cols dormant |
| Users | **251** — tenant-admin `zenvendemo@gmail.com`; 30 inactive |
| Catalog | **2,586 products** + 2,586 images · **coding layer live** (brand · classification 95 classes · department) · USD display |
| Inventory | **102,675 items** · realistic size curves · **84,266 floor / 18,409 BOH (17.9%)** · at department + BOH fixture-zones · dual-written (`thing_location` + `scan_event`) |

### Twin dataset (regenerated 2026-06-22, committed) — ✅ MATERIALIZED on mother by RE-RESEED v2 (byte-for-byte)

| Asset | State |
|---|---|
| Layouts | 10 per-store sites, each **3 spaces** (Sales Floor / Back Room / Fitting Rooms — own SRF each) · Sales Floor carries 2–7 `region` department bands + fixtures · Back Room = dock + racks · 0 overlaps/space · Pass-2 assembly dormant |
| Catalog | 2,586 products + **`brand`/`classification_key`/`department`** + `classification.csv` + `display_lookup.csv` |
| Inventory | **102,675 EPCs** · realistic size curves · ~18% (~18.4k) back-of-house · at department + BOH fixture-zones (EPC strings all CHANGED → full re-import, not re-locate) |
| Sites | 14 with **lat/long** (geocoded to address) |

> **Regenerate byte-identical:** `build_layout` → `localize_names` → `build_chain` → `build_staff_roster` → `render_floorplans`.

### Immediate next steps (ranked)

> ✅ **DONE 2026-06-26:** (1) RE-RESEED v2 landed + twin-verified byte-for-byte (zero drift; CORE-REQ-001 + CORE-REQ-002 now live end-to-end) and (2) static-seed gap audit delivered. Gating reseed item cleared.

1. **★ M8TRX Connect — finish P0 + start the runtime.** ✅ **S9:** all 5 P0 sims built + offline-verified; inbound webhook + Bearer plane **LIVE-validated** vs `twin-pos` (`sale_event` → PROCESSED; **self-verified SOLD** — 2 Denver EPCs; all 3 Bearer scopes). **Next:** (a) **re-supply the Bearer key** to `.env` (out-of-band; NOT in repo) — prereq for `/api/v2`; (b) **§9 outbound receiver loop** (last unexercised P0 sim — LAN-reachable `OutboundReceiver` + outbound channel via `Provisioner` + shared `hmac_secret` + dev→LAN egress + BACKEND test-trigger); (c) harness hardening (`items/details` in `ConnectClient`, `DeviceDriver` runner, configurable receiver bind); (d) start the `LIVE-OPERATIONS.md` runtime (per-site business-hours calendar + closed-loop inventory). Channel: `m8trx-shared/brainstorm/COMMS-CONNECT-TWIN-2026-06-27.md`. Guides: `reference/connect/{SIMULATOR-GUIDE,LIVE-OPERATIONS}.md`.
2. **Full activity — the "play"** (`ACTIVITY-PLAN.md`) — realized via Connect + simulators (#1): traffic → transactions → try-on → staff/restock/stocktake (**BOH gives it a from-location**) → LP/EAS; item-movement the connective tissue. Kotlin Layer-0..3 generators feed the simulators.
3. **Close static-seed gaps via Connect** (Session 8 audit) — staff/org provisioning (candidate **TWIN-REQ-003**, hold for the API doc) · per-region currency + localized names · sensor/reader topology (settle the zones-vs-readers event model) · LP/EAS substrate (watch SKU source).
4. **Spatial Pass 2 — site assembly** (when calibration/placement data exists) — fill `srf_to_site_transform` + designate `site_frame_anchor_space` + wire `space_connection` adjacency (FR-SPATIAL-26). Columns already emitted dormant → zero rework.
5. **Resolve the image pipeline** with backend — Shopify hot-link vs **cache bytes**. *(parallel; see Blocked on core)*
6. **Wave 2 — 10 international stores** (`EXPANSION-PLAN.md`) — parametric per-store layout + departments already land; China←KR catalog, rest←US Shopify; onboard via Connect.

**Deferred (Bob's call, noted):** rotate the mother Hasura admin secret + de-hardcode `scripts/seed_store.py:20` (committed prod secret). Optional fixture realism: end-caps. MK/Hansae EPC bit-encoding (only if an MK tenant seeded). **Backroom-as-separate-`space`** (true separate sensor domain) — needs core confirmation that >1 space/site is allowed; modelled as an area zone for now, NOT YET FILED.

### Blocked on core

- **Connect: provision + transition, not read (NEW, S17 — one decision, not three).** An integrator can write and increasingly read, but cannot **provision or transition**: `/activate` + `/evaluate` are `CONNECT_NOT_EXPOSED` (directive→task cannot be closed unaided) · `alert_source`/`alert_source_kind` registration has **no path for anyone short of psql** (§8.2's own measurement) · `POST /api/v2/compliance/fixture-codes` is not Connect-reachable, so §8.1's recommended `zone_ref` path is one a vendor cannot take · and **`PATCH /connect/service-keys/{keyId}/scopes`, documented in §7 as the supported way to add scopes, is `CONNECT_NOT_EXPOSED` to every Connect key** — with SEC-1's subset guard, no Connect key can ever obtain a new capability without an admin.
- **`alert:read` + `alert:ingest` held by no key twin can reach (S17, and WORSE after S18)** — both shipped, both Connect-reachable, neither callable. Twin's view: *"a new write ships with its read"* wants a second clause — **and with a route for a key to obtain the capability.**
  ★ **S18 escalates this from argument to demonstration.** The scopes *were* granted, twin drove the entire §A chain with them, and the grants were then **reverted as unapproved production writes** (`mig-211`). Re-probed 2026-08-02 and 2026-08-04: both `403`. **So the only time the alarm chain has ever traversed, it did so on access that had to be undone — and there is still no sanctioned route to it.** Recorded in **TWIN-REQ-005 § Update 2026-08-01** (pushed, `7e6cb156`), along with the sibling failure where a correct grant was applied to the wrong key row and reported complete.
- **Three alarm-surface findings awaiting a core RULING (NEW, S18)** — (a) `POST /alerts/clear` returns **`cleared:0` for a `dedupe_key` raised seconds earlier and still `active`**; documented as idempotent success, so real failure is indistinguishable from a no-op (the ack echoes `conditionKey:null` — clear may be for *conditions*, not point events, in which case telling a vendor to clear by `dedupe_key` is the defect). (b) **Zone unresolved on 6 of 6 rows**, landing site-level: *an `eas_gate` is not a `zone`*, so a vendor handed a gate code has nothing to name and every alarm loses its location. (c) **`alert_source` held exactly one row (`twin-eas`)**, which *forced* every CI lane to post under twin's identity — a missing registration path producing an attribution collision, not a hygiene issue. *(A fourth, `subject_ref` silently dropped by `UUID.fromString(…).getOrNull()`, was published core-side as `55f83ca2`.)*

- **Planogram Mode 3 demo tail (NEW, S12)** — directive→targets→**resolved** is LIVE-PROVEN, but **FR-PLN-08 compliance-check task-gen + push** ride the **Notifications spine** (Triad Slice-1), which Backend defers behind the 07-30 critical path. Also OPEN: the **wrong-zone fixture mapping** ((a)/(b) decision with Backend — `GB-R3-U1` mapped to `GF-R6-U1`'s zone) + **no `/api/v2` compliance read-back** for twin self-verify (**FILED as TWIN-REQ-003**, 2026-07-02 — folds into the Connect read-surface). ✅ Partial OI-2 close: `POST /api/v2/compliance/fixture-codes` now loads `fixture_code_mapping` (operator-side).
- **Auth 500 / Hikari pool starvation (NEW, Session 8)** — post-reseed, the bulk-mutation Hasura **audit-trigger cascade** exhausted m8trx-services' `HikariPool-1` (10/10 active, 30 waiting) → auth/exchange 500 (reqId `3cc2943b`). Mother DB healthy (74/200); likely amplifier = the 102,675-item dual-write (~205k rows). **Core's to fix** (pool headroom / async-batch audit-ingest / quiesce triggers during bulk load) — **OPEN, core investigating.** Connect-based incremental seeding avoids the bulk direct-DB writes that trigger it. *(Not caused by the twin session's read-only audit.)*
- **Image pipeline (NEW)** — `image` = Shopify CDN hot-link; backend hit an issue; likely need cached bytes in M8TRX's own asset store. Confirm what the seed did + whether core can store/serve cached assets. Part of `CATALOG-IMPORT-ONBOARDING`.
- **✅ Connect Bearer plane WORKS (S9)** — with an out-of-band service key, twin verified `inventory:read` + `scan:submit` + `integration:manage` on `/api/v2` (supersedes the old "service-bearer→inventory 401" — that was the wrong door). **Remaining gap (OI-1):** no **self-serve scoped-Bearer mint** — the integration API-Keys tab hardcodes `webhook:write`; scoped service keys are issued out-of-band (core KeyService re-mint). Connect doc §4 flags `/api/v2/connect/credentials` as `@MvpStub` (post-MVP). Tracked via channel OI-1.
- **Connect `lookup` transform ↔ `integration_lookup` table (OI-2, NEW)** — the `lookup` field-transform reads the `integration_lookup` table, NOT `value_lookups` JSON; no UI to load rows. Optional (EPC path needs no `site_id`). Channel OI-2.
- **Catalog import onboarding flow incl. images** — no tenant product-import path. `CATALOG-IMPORT-ONBOARDING`.
- **commerce_projection writer** — unfed; commerce dashboards blank on API path. **TWIN-REQ-002** (filed 2026-06-11).
- **No cold-start/manual location** — inventory location needs a scan/receive event (corrections §2). CLEANUP-TASKS.
- **No EAS-alarm subscriber** — LP/theft analytics don't surface. API-SURFACE gap.
- **MapCanvas rendering** — `zone_type` colors (fixed core Session 70/71 per log — re-verify against the new fixture-zones).

---

## Store Concept — ⚠ SUPERSEDED (locked Session 3, outgrown Session 6; kept as lineage)

> **This single-store concept is history.** The build is a **14-site chain** (10 retail + 4 office) and the drive target is **`dec-us-denver`** (Denver, CO — flagship, 600 m², 2,586 SKUs, 15,005 EPCs). NYC is one of the 10 stores, not *the* store. Kept because the concept lineage explains why the grammar looks the way it does — **not** as a description of anything current.

**Decathlon Manhattan** — Decathlon City format, NOT running specialty.
- Concept evolution: started as Bordeaux running specialty (160 sqm) → Florence CAD grammar showed the correct scale → 600 sqm Decathlon City format adopted
- Address: 620 6th Avenue, New York, NY 10011 (Flatiron District)
- Currency: USD
- SKU mix: running (primary) + fitness + hiking + swim + cycling + accessories ~~+ GPS watches~~
  ⚠ **"GPS watches" struck 2026-08-01 — the live catalog has ZERO watch SKUs** and `scenarioSelfTest` asserts their absence. This is the *same dead anchor* the LP line below was corrected for on 2026-07-31; the correction was applied there and missed here, in the same file.
- LP scenario anchor: **EAS-tagged premium concealable stock — `price_usd >= $150 AND category != "outdoor"`, 271 of 2,586 SKUs**, concentrated on `PE-02`/`PW-02` (Kiprun premium footwear). Gate is `CS-01` "Main Entrance Gate". Rule lives in `layer2/EasTagging.kt`; tag state is **twin-side, not the platform's**.
  ⚠ **Corrected 2026-07-31** — was *"W-series sports watches ($29.99–$89.99), EAS-tagged, 40 items"*. **Zero watch SKUs exist in the live catalog**; that anchor belonged to the superseded 920-SKU concept and Session 8 flagged it unclosed. Asserted absent by `scenarioSelfTest`.

---

## Project Artifacts

| Artifact | Path | Status |
|---|---|---|
| Kotlin project | `~/IdeaProjects/m8trx-twin/` | ✅ Compiles; ktlint clean; `connectSelfTest` **12** · `peopleSelfTest` 50 · `scenarioSelfTest` 37 |
| NATS emitter | `src/main/kotlin/com/m8trx/twin/layer0/NatsEmitter.kt` | ✅ Dual-publishes legacy + new pattern. **Publishes to `:4223` only** — `:4222` is the production office edge |
| REST emitter | `src/main/kotlin/com/m8trx/twin/layer0/RestEmitter.kt` | ⚠ **UNWIRED SCAFFOLD — zero callers anywhere in `src/`** (re-verified 2026-08-01). *(Was "✅ Written, untested (service bearer 401)" — the 401 reason has been dead since S9/S11 proved the Bearer plane; the real state is that nothing calls it. Live REST goes through `connect/ConnectClient`.)* |
| Store layout doc | `reference/data/STORE-LAYOUT.md` | ✅ Current — per-store parametric, redesigned 2026-06-22 (flagship ~600 m² → medium ~400 m²) |
| Floor plan SVGs | `reference/data/floor-plans/` | ✅ Generated, all 10 stores (`scripts/render_floorplans.py`) |
| ~~Snapshot JSON~~ | ~~`reference/data/snapshots/decathlon-running-small/day-start.json`~~ | ⛔ **FILE DOES NOT EXIST** (checked 2026-08-01). Listed here as "⚠ Outdated (300 sqm) — update to 600 sqm" for months; there is nothing to update. Day state is generated at runtime by `layer3/DayDrive.kt` |
| Seed script | `scripts/seed_store.py` | ⚠ **SUPERSEDED** — the 920-SKU single-store seeder. Chain seeding is `build_chain.py` → `DEPLOY-HANDOFF.md`; RE-RESEED v2 (2026-06-26) is what is live |
| Raw catalog | `reference/sample_stores/decathlon-korea-raw.csv` | ✅ 56,003 rows — the input to curation |
| ~~Curated catalog~~ | `reference/data/catalog/decathlon-korea-curated.csv` | ⚠ **SUPERSEDED, 920 SKUs** — the Manhattan-era cut. Live catalog is the **2,586-SKU** chain master (`reference/data/chain/`) |
| ~~Final SKU file~~ | `reference/data/catalog/decathlon-manhattan-skus.csv` | ⚠ **SUPERSEDED** — same 920-SKU concept; "ready" refers to a store that was never built. ★ **This file is the literal provenance of the dead watch anchor: exactly 40 rows in category `watch_gps`, $22.99–$89.99** (verified 2026-08-01). That is the "W-series sports watches, EAS-tagged, 40 items" line — real here, absent from every one of the 10 live stores |
| Florence CAD ref | `reference/sample_stores/deacthlon_florence/` | ✅ 4 files |
| API surface doc | `reference/integration/M8TRX-API-SURFACE.md` | ✅ 27 atoms mapped |

---

## Active Requirements Filed Back to Core

| Brief | Status | Blocks |
|---|---|---|
| TWIN-REQ-001 `fitting_room` → `try_on_zone` | ABSORBED 2026-05-09 | — |
| TWIN-REQ-002 `commerce_projection` writer | **FILED, AWAITING ABSORPTION** (2026-06-11) | Scripts 1, 3, 5 |
| **TWIN-REQ-005 — Connect capability acquisition** | 📨 **FILED 2026-07-31** (`m8trx-shared/twin/requirements/TWIN-REQ-005-connect-capability-acquisition.md`). No Connect key can obtain a capability it was not minted with, and §7's documented path (`PATCH …/service-keys/{keyId}/scopes`) is `CONNECT_NOT_EXPOSED` to every Connect key. Two individually-correct mechanisms (the admin-only door + SEC-1's subset guard) that together leave **no path at all**. Twin NOT blocked — twin asks a human, which is exactly what a real integrator cannot do. | `/alerts/query` uncallable by anyone; 2nd instance after `vision_ai:*` |
| **TWIN-REQ-004 Connect READ surface** | ✅ **SATISFIED — CLOSED 2026-07-31 by twin as external prover.** All four §6.5 reads callable; rule-2 confinement proven on all four with negative controls by slug AND UUID; omitted-site returns 1 of 14. All three §4 interim positions retired with evidence (fixture-map CSV → `/spatial/identity`; human `psql` → 3,119 rows reproduced in code; engineer-watched task smoke → `/tasks/query`). | — |
| **FR-INTEG-16 two-system reconciliation** — *feasibility asked of twin* | ✅ **ANSWERED 2026-07-31: YES.** Three of four steps live-proven; step 1 blocked only on `integration:manage` (a scope, restorable). `hmac_secret` at channel creation means the case never needs a key mint. | one scope grant |
| TWIN-REQ-003 compliance/directive read-back (Connect Mode-3) | ✅ **SATISFIED** (2026-07-02; core PR #76 `925f9a4` — `POST /api/v2/compliance/state` live; twin part-3 smoke GREEN 28/28 · 25/2/1 · 0.893) | — |
| ~~TWIN-REQ-004 (duplicate row)~~ | ⛔ **REMOVED 2026-08-01 — this was a second, contradictory TWIN-REQ-004 row in the same table.** It claimed *"ABSORBED core-side 2026-07-30 — sequenced behind the Traffic reference surface, **so not yet shipped**; interim positions remain the operating mode"* while the row above it recorded the requirement **shipped, proven and CLOSED** on 2026-07-31 with all three interim positions retired with evidence. The row above is correct; this one was 24 hours stale and read as authoritative. **One requirement, one row.** | — |
| CORE-REQ-001 catalog attribute enrichment (brand · classification · coded attrs) — **inverse, core→twin** | ✅ **ABSORBED** 2026-06-21 (core loaded + verified; merged-commit `eb39526`) | — |
| CORE-REQ-002 `site_category` (functional role `store/office/warehouse`) — **inverse, core→twin** | ✅ **LIVE on mother** (RE-RESEED v2, 2026-06-26; core mig 146) | — |
| CORE-REQ-003 build Connect simulators — **inverse, core→twin** | ✅ **DONE** (S11 — all 5 P0 sims live end-to-end) + **Planogram Mode 3 driver LIVE-PROVEN** (S12, PR #7 — directive→targets→resolved, triple-verified) | — |
| CORE-REQ-004 toolchain assessment — **inverse, core→twin** | ✅ **DONE** 2026-06-29 (GO; PR #1 merged — Gradle 9.6.1 · Kotlin 2.4.0 · jackson 2.21.4 P0 CVE · jnats 2.25.3 · coroutines 1.11.0 · logback 1.5.37); deliverable in `status/briefs/` | — |
| `inventory:sell` capability split | PRE-EXISTING in CLEANUP-TASKS | Cashier persona |

> TWIN-REQ-002 brief: `~/IdeaProjects/m8trx-shared/twin/requirements/TWIN-REQ-002-commerce-projection-writer.md` (filed by core 2026-06-11, formalizing the insight at CLAUDE.md §Insights). P1 — blocks the commerce story on the API path until core ships the writer (feed-raw-let-platform-derive per `twin/insights/IMPORT-CONTRACT.md` §2).

> **CORE-REQ-001 (delivered 2026-06-21):** catalog attribute coding for the Things/Discover surface. **Decathlon** profile — `reference/data/chain/{classification.csv, display_lookup.csv}` + `brand`/`classification_key` on assortment (normalisation model). **MK/Hansae** profile (second model, built) — `reference/data/mk-trend/` (numeric-code model) from the real MK Trend spec (`reference/hansaemk/`). Same coding grain across both → vertical-portable. Rationale: `reference/data/chain/CATALOG-CODING-MODEL.md`; MK writeup: `reference/data/mk-trend/MK-CODING-PROFILE.md`. **Applied to the demo tenant by RE-RESEED v2 (2026-06-26)** — coding layer now live on the Discover/Things surface.

---

## Deploy State — ⚠ SUPERSEDED (Session 3 snapshot; every line below is now false)

> Retained only as a marker of how far the build has moved. **Current deploy state is `### What's LIVE on mother` above and TRACK-TWIN § Current State.** Struck 2026-08-01 because a reader scrolling here found four confident, wrong facts.

- ~~m8trx-twin: uncommitted (coordinator handles commit)~~ → the repo is on `main` `5e4b81a`, PRs #1–#14 merged
- ~~M8trxDemo on mother: 160 zones + 920 products live~~ → **929 zones · 2,586 products · 102,675 items** across 14 sites (RE-RESEED v2)
- ~~NATS: smoke objLocation published successfully to .29~~ → 1.1M+ samples per full-day drive, to **`.29:4223`** specifically
- ~~Service API key: … auth works on NATS, fails on REST inventory endpoints~~ → **the REST/Bearer plane works** (core #51/#52, proven S9/S11); twin self-verifies SOLD through `items/details` and reads impressions through §6.5

---

## Open Decisions

- **Container deploy target** — decision deferred until first runnable scenario
- **Stack** ✅ LOCKED: Kotlin
- **Scenario clock** ✅ LOCKED: shared scheduler, `rate=0` step mode
- **Config canonical format** ✅ LOCKED: JSON
