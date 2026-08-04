# Session 18 — the §A alarm chain driven from outside, and a doc-integrity pass that found the hard rule pointing at production

**Span:** 2026-08-01 → 2026-08-04 (work 08-01 15:00–18:00 KST · re-measure 08-02 12:23 · close 08-04 09:40)
**Branch/deploy at close:** `main` **`f6df80d`**, pushed, working tree clean, no open PRs. PR [#15](https://github.com/BobbyG4/m8trx-twin/pull/15) merged.
**Gates green at close:** `ktlintCheck` · `compileKotlin` · `connectSelfTest` **12** (was 11) · `peopleSelfTest` 50 · `scenarioSelfTest` 37.

---

## ★ THE ONE RESULT, AND THE CONDITION THAT MAKES IT QUOTABLE

**Twin drove the §A alarm chain end to end as a third-party EAS gate vendor. Nothing had ever traversed it.**

Measured from twin's own requests — no `psql`, no inherited claim:

| Step | Result |
|---|---|
| `POST /alerts/query` (`alert:read`) | **200**, site-confined to Denver (`84f2a1c1`) |
| `POST /alerts` (`alert:ingest`, §6.6) | **200** `disposition=recorded`, `alertId` returned |
| byte-identical retry | **`disposition=deduped`, SAME `alertId`** — alert-layer dedupe, proven |
| `A1` sent twice (webhook) | exactly **one** row |
| read-back | **6 rows**, `native_level=critical` **preserved on every one** |
| clear-refusal probe | **400 by design** — *"a person SEEING an alarm is not the alarm being over"* |
| clear enum | **named by the server's own 400**: `resolved` / `expired` / `auto_resolved` |

**⛔ THE CONDITION. The run only happened because `alert:read` + `alert:ingest` sat on twin's key by way of unapproved production writes, which were then reverted core-side (`mig-211`, *"revert every unapproved S285 production write"*).** Re-probed **2026-08-02 12:23Z and again 2026-08-04 09:40Z: `alert:read` 403, `integration:manage` 403**, key back to `inventory:read · vision_ai:view · task:read`. The revert is stable, not transient.

So the claim splits, and **the split is the finding**:

- **PROVEN** — the chain traverses; §6.6 and §8.2 behave as measured; the shapes are validated against real responses.
- **NOT PROVEN, and never was** — that a vendor can *reach* it. **The only time this chain has ever traversed, it ran on access that had to be undone.** That is **TWIN-REQ-005 demonstrated rather than argued**, and stronger than the brief as originally filed.
- **NOT twin's to assert at all** — whether the sends dead-lettered. DLQ + `/integrations/{id}/health` need `integration:manage`, which S280 removed on purpose and which was **correctly not reversed to unblock a test**. Coordinator-side reads reported `integration_event processed×4 / failed×3` (the 03:16Z casualties only) and `alert_event created×9`. **Cite as their measurement, never twin's.**

---

## What was attempted, in order

1. **Session-start orientation** → immediately contradicted by git: STATUS/TRACK both listed *"PR the two stacked branches"* as next action #1, already done. Found **~730 lines of finished alarm work uncommitted on `main`**, written 3h earlier by a prior sitting, recorded nowhere.
2. **Doc-integrity pass** (Bob: *"fix the twin doc claims first"*) — ~15 false claims across `CLAUDE.md`, `STATUS.md`, `TRACK-TWIN.md`.
3. **PR #15** — the alarm build + doc pass, merged.
4. **§6.6 Bearer arm built** when the coordinator reported `POST /alerts` deployed.
5. **Live drive**, twice, once the grants landed on the correct key row.
6. **Re-measure after the rollback** and re-scope every claim to match.

---

## ⚠ FAILED APPROACHES — the don't-repeat-this record

**Six errors this session. Four were mine, and three of those were the same shape: acting on state someone reported rather than state someone measured.**

1. **I overwrote an accurate finding with a denial.** Two files said twin had watched an alarm ack `200` then dead-letter. I "corrected" them to say twin had never sent one live, reasoning from *no run artifact survives in the repo*. **The sends were real** (03:16Z). `.env`-driven runs leave no artifact **by design**, and a 9h UTC/KST offset made `03:16Z` look unrelated to 12:1x local file mtimes. Restored with evidence. **Rule: absence of an artifact is not evidence of absence of a run. Label a claim unverified and go measure it — never invert it.**
2. **I asserted "it is the right key" and "that isn't a wrong-key problem."** Both wrong. My reasoning — that `vision_ai:view` + `task:read` are distinctive — identified the key's **vintage**, not its **row**; *both* twin keys carry that pair. `last_used_at` is what identifies a key. **Rule: identify a principal by what its traffic touches, never by its name or its scope shape.**
3. **`AlertRow` carried snake `@JsonProperty` names.** The server sends **camelCase rows under a snake envelope** — the identical split this repo already pins for §6.5 impression rows. `occurredAt`/`dedupeKey`/`zoneCode` bound to null, and I nearly reported "the server omits these" as a finding. **And the self-test fixture was snake too, so it PASSED and agreed with the bug.** Same failure mode S17 recorded for row clocks. **Rule: fixture the shape the server sends, never the shape the doc describes.**
4. **My webhook dedupe assertion was vacuous.** Reading the wire counts broke it: 6 webhook sends → **4** `integration_event` rows, because a byte-identical payload is collapsed by the **content hash at the integration layer** and never reaches the alert layer. So *"A1 twice → one row"* holds whether or not alert-level dedupe works. Only the Bearer arm's B2 ever tested it. `A3` (same `dedupe_key`, different bytes) added, with two `check()`s so a future edit breaks the run rather than silently returning to vacuous. **Built, not yet fired.**
5. **A2 was future-dated by 7s** purely to vary its `dedupe_key`. `alert.created_at` derives from vendor `occurred_at` and the query window is `[now-24h, now)`, so a future-dated alarm acks `200` and is invisible to its own read-back — presenting as *"A2 never landed"* rather than *"A2 is dated wrong."* Past-dated, sign documented as load-bearing.
6. **The pre-flight lookback was hardcoded to 1 hour**, so it could not see twin's own 3.5h-old alarms and would have reported an empty history. **Findings 5 and 6 are the two ends of the same window; either alone turns an empty result into a plausible negative.**

**Also mine, smaller:** an 8s settle wait that was too short (Bearer rows readable immediately, webhook rows ~50s), which briefly reported 4 rows where 6 existed — *a false negative manufactured by the client.* Now `M8TRX_ALARM_SETTLE_MS`, default 60s.

**Not mine, recorded because it cost a whole drive:** the coordinator wrote a correct grant to the **wrong key row** and reported it complete, then predicted the send *would* dead-letter and retracted it after deploy. I ran a real acceptance drive against a false premise and had to re-scope published claims twice.

---

## What shipped

| Commit | What |
|---|---|
| `e511b50` | §8.1 alarm ingest as an outside EAS vendor — `AlarmEnvelope` (union of two disagreeing specs), `AlarmDriver`, `connectAlarmDrive`, `ConnectClient.queryAlerts`, `WebhookDataType.ALARM` |
| `5a5143d` | **~15 false doc claims corrected** across `CLAUDE.md` / `STATUS.md` / `TRACK-TWIN.md` |
| `496e78d` | merge PR #15 |
| `dd7cb22` | branch-state sync (the doc pass named a branch the merge then deleted) |
| `c292ed4` | **§6.6 Bearer arm** — `AlertIngestAck` / `AlertClearRequest` / `AlertClearAck`, `ingestAlert`, `clearAlert`, raise→retry→clear→refusal; the inert-grant measurement |
| `05944da` | **alert rows are camelCase** — DTO + fixture corrected; retracted the false severity finding, kept the real ones |
| `d93659b` | **§A chain DRIVEN LIVE** — six steps measured, three findings |
| `7ed8f81` | **`A3`** — the vacuous webhook dedupe assertion made real |
| `f6df80d` | the split verdict recorded; three code claims the rollback falsified, corrected |

**m8trx-shared** — `7e6cb156` TWIN-REQ-005 § Update 2026-08-01 (pushed, verified surviving the rollback).

---

## Key discoveries

### Contract findings, all from behaviour, all open

1. **`clear` returns `cleared:0` for a `dedupe_key` raised seconds earlier and still `active`.** B2 had just proved the platform knows that key. `cleared:0` is documented as an idempotent **success**, so a real failure is indistinguishable from a no-op. The ack echoes `conditionKey:null` — clear may be built for **conditions**, not point events, in which case telling a vendor to clear by `dedupe_key` is the defect.
2. **`subject_ref` is silently dropped.** Parser is `runCatching { UUID.fromString(it) }.getOrNull()`. §8.1 documents `subject_id` (a UUID no vendor holds); the design doc says `subject_ref`. A vendor sending the only identifier it has gets `200`, sees its EPC echoed in `payload`, and has **no signal the subject binding never happened**. Published core-side (`55f83ca2`). **The last place in the alarm envelope that breaks §6.5 rule 1.**
3. **Zone unresolved on 6 of 6 rows, landing site-level.** Twin sent `zone_ref='CS-01'`, a real crossing-slice code from Denver's own `layout.json`. §8.1 permits the fallback — but **an `eas_gate` is not a `zone`**, so a vendor handed a gate code has nothing to name and every alarm loses its location. **Needs a ruling, not a patch.**
4. **`alert_source` held exactly one row (`twin-eas`).** So every CI lane and test *had* to post under twin's identity — the attribution collision was **forced by the missing registration path**, not chosen. That makes it a §8.2 argument, not hygiene: *"nobody can register a source"* reads as inconvenience; *"all producers share one identity"* reads as what it is.
5. **`source` does not identify the producer** — 4 of 6 rows were other lanes' tests. Rows minted by this driver are now identified by `dedupe_key` **composition** (`<gate>:<epc>:<millis>`).
6. **Two planes settle at different speeds** — Bearer rows readable immediately (synchronous ack), webhook rows absent at ~9s and present by ~50s. Worth stating in §8.2: *a vendor who reads back too early sees nothing and will blame the send.*

### ★ The doc-integrity pass — what it found

**The load-bearing one: `CLAUDE.md` listed `192.168.55.29:4222` as twin's permitted NATS surface.** That is `edge-itx-office` — **production, real Xovis hardware**. Every code path had `:4223` right and `DayDrive.kt:193` refuses to publish unless the broker's `server_name` matches, so nothing was ever misrouted — but **the HARD RULE section named the production edge for months.** The guard lives in code precisely because a doc cannot enforce anything.

Also struck: a dead **`GPS watches`** anchor that survived S17's own sweep *inside `STATUS.md`* — traced to `decathlon-manhattan-skus.csv`, which holds exactly **40 rows of category `watch_gps`, $22.99–$89.99**, so the *"W-series, EAS-tagged, 40 items"* line was **accurate about a file that stopped being the catalog in Session 6** · a **duplicate `TWIN-REQ-004` ledger row** contradicting its own neighbour · a snapshot artifact listed "⚠ Outdated" that **does not exist** · `reference/scenarios/` and `SKU-CURATION.md` referenced for months and **never existed** · `RestEmitter` blamed on a long-dead 401 when it has **zero callers** · `fitting_room → try_on_zone` marked "NOT YET FILED" three months after ABSORBED · **three different reference stores across two docs** (CLAUDE.md said Korea/1,500 sqm, STATUS said Manhattan, reality is a 14-site chain driven at Denver) · a Session-3 Deploy State whose four lines were all false · and a merge described as "26 commits"/"7 + 7" that is actually **29**.

**`M8TRX_TWIN_BEARER` was documented as tenant-wide with `integration:manage · scan:submit · inventory:create`.** It is **`twin-s280-lockdown`**, **site-scoped to Denver**, holding **none of those three**. That error contributed directly to a grant being aimed at the wrong row. The Denver binding is a **feature** — it is what makes `connectAcceptance`'s negative controls real.

**Method note:** corrections keep the old wording **struck rather than deleted**. A reader who acted on it deserves to see it was wrong, and deleting the record repeats the mistake STATUS itself documents about the two acceptance records.

---

## Decisions

- **Declined to fire unobservable alarms** (twice, before the grants landed). Three refusing diagnostics means adding alarms nobody can see — reproducing exactly the state in which the 03:16Z dead-letter hid for hours. Coordinator agreed both times.
- **Declined `integration:manage`.** TRACK said "restore"; that is **twin quoting its own convenience, not a security judgment**. S280 removed it deliberately and it is Bob's call, not something to widen to unblock a test. DLQ + health stay dark and are **reported dark**.
- **Recorded the `alert:ingest` canary as correct behaviour.** `ScopeClosureNoInflationTest` firing on tenant-admin 145→146 is the test doing its job; a key `scope_grants` write is a different plane from a permission-set grant.
- **Dropped an instance tally** rather than pick a number: code said `alert:read` was the "third SEC-3 instance", STATUS says second. Report the pattern, not the count.
- **`connectAcceptance` keeps alarm ingest in `NOT_COVERED`** — built and offline-gated, live chain ungated. **Offline conformance is not chain evidence.** Promote to `COVERED` only when a key holds `alert:read`.

---

## ⛔ Standing constraint reaffirmed

**This lane can never run the cold-onboarding peer test on the alarm surface.** Twin spent this session inside the envelope, the registration gates, the severity model and the §6.6 ack. Any "cold" run from here returns a **false pass**, which is worse than not running it. That gate needs a **fresh session holding only the published `M8TRX-CONNECT-API.md`**, and the coordinator's signal. Twin's remaining job on it is to hand it over and stay out of the run.

---

## Residue

**6 alert rows are `active` in Denver** that twin can no longer see (`alert:read` 403) and could not clear even with the scope (`cleared:0`). Real residue in the demo tenant; removal is core-side.
