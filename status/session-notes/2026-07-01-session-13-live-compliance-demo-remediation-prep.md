# Session 13 — 2026-07-01 (Twin) — Live-compliance demo PROVEN end-to-end + remediation-arc prep

**Branch/deploy at close-of-this-note:** `m8trx-twin` branch **`feature/connect-movement-emitter`** off main `4aa8abe` — the live-compliance demo itself ran on the webhook plane (scratch EPC files + gitignored `.twin-state`, zero repo changes); the S13 work is committed here: `6f55cb0` (feat: movement emitter scaffold) + a docs commit (this note + STATUS/TRACK/SESSION-LOG). Not pushed/PR'd yet (hold-fire until Backend's ingester lands, then adapt + live-prove → PR, per the planogram pattern). Comms: `twin` seat on Slack `#m8trx-dev`, S187 arc thread (`1782720036.220879`), dormant-wake Monitor armed. Session ONGOING (pushing on into the remediation arc with Backend).

---

## Headline

The **live-compliance demo is PROVEN end-to-end** — the payoff of the S12 planogram Mode-3 work. After Backend deployed the **compliance-EVALUATION engine** (services #69), the twin drove **12 real sales** through the Connect webhook at the corrected planogram fixture and **watched compliance drift `compliant → partially_compliant → non_compliant`**, triple-confirmed on every beat (twin fire → twin self-verify SOLD via `items/details` → Backend live `/state`). Summary walked **27/1/0 → 24/2/2** across **4 targets spanning the whole arc**, including a `req=1` compliant→non_compliant single-event edge case. Then, per Bob, we did **not** call it — we're pushing straight into the **remediation arc** (non→compliant when stock returns to the shelf), with Backend building the **Connect inbound movement/transfer ingester** and twin prepping the restock emitter to a **co-designed contract**.

> "IT MOVED. Your sale → recompute hook fired → compliant → partially_compliant, drift_detected event written." — Backend, live off `/state`

---

## What happened (chronological)

1. **Session-start miss + recovery.** Went heads-down on local analysis of the S12 wrong-zone mapping instead of checking comms. Bob stopped me ("are you reading the messages from Backend?"). I was not. Got on the `twin` seat and caught up.
2. **Comms plumbing fix.** The session-arg arc-root ts (`1782813719.506579`) is a **mid-thread marker** under the S187 thread — Slack *collapses sends* to the parent (so my reply reached Backend fine) but `conversations.replies` on it **reads empty**, so `slack-recv`/`slack-wake-check` were blind. Repointed `~/.config/m8trx/comms-arc-thread` → the real parent **`1782720036.220879`**, primed `.lastseen-twin`, armed the dormant-wake Monitor. (Lesson: the arc ts must be the THREAD PARENT for reads to work.)
3. **Found Backend's unread `→[twin]` handoff (07-01 15:19):** they built the **compliance-EVALUATION engine** — `POST /api/v2/compliance/directives/{id}/evaluate` (recompute resolved targets vs live inventory → `compliance_target_state` + `compliance_event`), `GET …/state` (the "watch it move" surface), a **recompute-on-sale hook**, and `product_id` resolution from `raw_item_identifier`. Branch `feat/compliance-evaluation-engine`, awaiting Bob's merge=deploy.
4. **Verified our side** while Bob digested: cross-checked the mapping (twin dataset agrees with Backend's re-point exactly), and **audited the S197 region→territory rename — twin CLEAN** (see Key discoveries).
5. **Bob merged → Backend deployed** (services #69, `0d0a709`, verified live on .28; `/evaluate` + `/state` mapped).
6. **Re-point (Bob-authorized prod write).** No REST path to re-point already-*resolved* targets → a raw prod UPDATE on mother (`compliance_target.zone_id` ×28 + the `GB-R3-U1` `fixture_code_mapping`) from the wrong `030dc90f` (Gondola R6 Front U1, empty of these SKUs) → correct **`e82a21f3`** (Gondola R3 Back U1). Backend's guard required Bob's in-conversation OK; Bob gave it. Operator `POST /evaluate` set the baseline: **27 compliant / 1 partial / 0 non-compliant**.
7. **The demo — 12 real sales, 3 beats** (`connectSaleStream`, webhook plane, X-API-Key, deterministic EPC files):

| Beat | Sales | Target | Movement | Result |
|---|---|---|---|---|
| 1 | 1 | #2 (SKU 2456187, BL100, req=10) | 10→9 | compliant → **partially_compliant** + `drift_detected`; summary 27/1/0 → 26/2/0 |
| 2 | 9 | #2 | 9→0 | partially_compliant → **non_compliant** + `non_compliant` event; 26/1/1 |
| Breadth | 2 | #6 (2456191, req=3) + #7 (2706524, req=1) | #6 3→2 · #7 1→0 | #6 **partially_compliant**; #7 **non_compliant** in ONE event (req=1 edge case); **24/2/2** |

   Every beat triple-confirmed: twin fired → **twin self-verified SOLD** via `items/details` (`inventory:read`, 12/12 `state=sold`, no psql) → **Backend read live `/state`** and narrated each flip + event.
8. **Bob: "not calling it — keep pushing."** Backend documents + builds the **Connect inbound movement/transfer ingester** (unblocks remediation). Twin prepares the restock emitter in parallel (this note + the scaffold agent).

---

## Key discoveries

- **★ The whole live-compliance thesis is proven on real data** — a real sale visibly moved compliance with real events, no mocks anywhere in the loop. This is the demo.
- **Read-back gap, now with hard evidence (→ file the TWIN-REQ).** Twin's Connect key gets **`403 CONNECT_NOT_EXPOSED`** on `GET /api/v2/compliance/directives/{id}/state`. Twin drove the entire demo **blind on the compliance side** — the loop only closed because Backend watched `/state` for us. This is exactly the S12 `TWIN-REQ-DRAFT-planogram-compliance-readback` gap; the 403 is the concrete justification to file it. (Bearer itself is LIVE — 403 is authz-not-authn.)
- **Event discipline (Backend's catch):** the 12 sales produced only **transition** events (`drift_detected` on entering partial, `non_compliant` on hitting 0). The 8 middle sales of beat 2 changed no status → no event. The event log captures transitions, not every scan — clean cardinality, good for the analytics story.
- **Fulfillment "why" (free from the evaluator):** #7 = **`unavailable_at_site`** (its only unit sold, nothing in back → **reorder**) vs #6/#2 = **`available`/`partially_available`** (shelf low, stock still in back → **restock-from-back**). Same non-compliant shelf, opposite action — all 3 fulfillment states demonstrated. This directly shapes the remediation arc.
- **The demo bay is coherent + real:** `GB-R3-U1` = "Gondola R3 Back U1", dept **snow** — all 28 targets are Wedze snow gear (ski base layers, kids' snowsuits, snow pants, beanies). A believable planogram slice: "the snow-gear bay drifting out of compliance as it sells through."
- **Mapping/count delta:** twin seed has **exactly `req`** EPCs per SKU at GB-R3-U1 (144 total = compliant-by-construction); mother holds **186** (+42 from S9/S11 activity + reseed). Drove sales off **Backend's live counts**, not the seed. Backend handed deterministic in_stock EPCs for the precise beats (esp. the mother-only ones our seed lacks).
- **S197 region→territory rename — twin audited CLEAN.** No live reference to any renamed table/col/cap/slug/JWT-claim (`region_site` / `user_region_membership` / `region:*` / `x-hasura-region` / `multi-region-enterprise` all absent in `src/`). All *live* `region` usage is the KEPT set: `zone_type='region'` (department bands) + geographic `region` (US/FR/KR currency/locale). Only the **un-provisioned** staff roster carries the org-hierarchy sense ("Regional Director/Manager" roles, `dec-*-region` office IDs) — cosmetic, align to "Territory Manager" when staff provisioning lands (TWIN-REQ-003, on hold). Non-blocking.

---

## Decisions made

- **Re-point path:** corrected the 28 targets to the real zone `e82a21f3` (not a re-fire) — a one-time Bob-authorized prod UPDATE, since there's no REST path to re-point resolved targets. Sidesteps the (a)/(b) vendor-code-vs-name question for this demo (Backend handled operator-side).
- **Drift-only demo (no build-up):** demo #1 = sell-down drift; remediation (build-up) deferred to the movement-ingester arc (now in progress).
- **Breadth beat:** per Bob, drifted 2 more targets for a richer "store going out of compliance" picture (one partial, one non_compliant via the req=1 edge case) — status variety across the bay.
- **Push on, don't call it:** into the remediation arc, co-designing the movement contract with Backend up front (not the planogram hold-for-as-built — Backend explicitly invited contract push-back now).

---

## State for next session

- **Live-compliance demo: DONE + triple-verified** (24/2/2, 4 targets spanning the arc). Bank it — marketing/investor-grade (a candidate for a captured visual).
- **Remediation arc IN PROGRESS:**
  - **Backend building:** Connect inbound **movement/transfer ingester** — `X-Data-Type: inventory_movement`, the missing runtime `thing_location` writer (FR-INTEG backlog S178). relocation = update `thing_location.zone_id` (+ history + 'moved' custody event), item stays `in_stock`. Unblocks non→compliant remediation.
  - **Contract co-designed** (Backend's proposed payload + twin's 3 notes: EPC-qty-implicitly-1, per-item-not-all-or-nothing, and the relocation(#2/#6)+receive(#7) division of labor). Backend finalizes in `M8TRX-CONNECT-API.md` as they build; will ping the as-built shape on deploy.
  - **Twin prepping:** a movement/relocation emitter scaffold (`MovementDriver` + DTOs + `connectMovementDrive` gradle task + `connectSelfTest` case), **dry-run default, hold live-fire** until Backend's ingester deploys. Source: relocate backroom/other-gondola EPCs of SKUs 2456187/2456191 → GB-R3-U1 (restock-from-back); #7 (2706524) needs the existing receive path (unavailable_at_site).
- **To file:** the compliance read-back TWIN-REQ (403 `CONNECT_NOT_EXPOSED` evidence) — draft at `status/briefs/TWIN-REQ-DRAFT-planogram-compliance-readback-2026-06-30.md`; strengthen with today's 403 and file per the B3 decision.
- **Creds:** `.env` intact (`M8TRX_TWIN_BEARER` + full set), Bearer LIVE. Demo EPC files in scratchpad; `.twin-state/sold-epcs.txt` has the 12 demo sales appended (gitignored).
- **Live-fire recipe (sales):** `set -a; source .env; set +a` then `M8TRX_STREAM_EPC_FILE=<file> M8TRX_STREAM_COUNT=<n> M8TRX_RUN_ID=<id> ./gradlew --no-daemon connectSaleStream`. Deterministic via a per-SKU EPC file; sold-log auto-excludes.

---

## Remediation arc + FR-COLLECT-ID — LIVE continuation (supersedes "IN PROGRESS" above)

The remediation arc ran to completion in-session (Backend built the ingester live, twin drove it):

- **Movement/transfer contract LOCKED** (co-designed up front, not hold-for-as-built): `X-Data-Type: inventory_movement`, `{external_movement_id?, site_id?, (to_zone_id|to_fixture_code), items:[{epc}], movement_type}` — as-built matched the lock exactly. Twin's emitter (`MovementDriver`/`connectMovementDrive`) needed zero rework; dry-run envelope verified against §8.
- **Remediation PROVEN via relocation** (Bob-authorized live fires): #2 climbed `0→2` (a BOH relocate) and **#6 fully recovered `2→3` COMPLIANT** (`compliant` event) — the **first live runtime `thing_location` writes**. Summary **24/2/2 → 25/2/1**. `sell→drift→restock→remediate` demonstrated end-to-end on real, product-linked inventory.
- **★ 7th core bug caught (#72, FIXED):** the first movement smoke got a **200 ack but `integration_event` FAILED** — on the 14-site M8trxDemo tenant, `to_fixture_code` can't resolve which store, and the deployed single-site fallback returned null. Twin's insistence on server-side readback caught it (the 200 hid it). Bob merged the **per-item-site fix (#72)** — resolve the code at the item's own site → `site_id` optional even multi-site; re-fire (no site_id) landed clean.
- **Receive→relocate MECHANICS proven, but #7 blocked by a distinct gap:** minted a fresh `2706524` EPC (`30396061C000080035A4E901`, round-trip + collision verified via the SGTIN-96 encoder) → **received `created=1`** (site-level) → **relocated** to e82a21f3 (custody `received`→`moved`, relocate upsert clean, recompute ran). **BUT #7 didn't climb** — `thing.product_id` is NULL. Root cause = the receive path is **EPC-only** (no product resolution) → **FR-INTEG-04 / the FR-COLLECT-ID Identifier Resolution Pipeline (Volare §9a, FR-COLLECT-07..10) isn't built.** The product-less receive is a *symptom*, not a movement bug. Bob's call: **bank it** (option 2 — mechanics proven; #7's compliance climb deferred to FR-COLLECT-ID, a ~30-line decoder port on core's side).
- **★ Handed core the fix reference:** twin owns the validated **clean-room Decathlon SGTIN-96 EPC↔EAN decoder** — shared + inline in comms; Backend relocated it to the core-visible `m8trx-shared/reference/dev/EPC-EAN-DECODER-FR-COLLECT-ID.md` (`9bd178d`) and points their FR-COLLECT-ID note at it (core sessions skip `twin/`). IP note flagged LOUD: **use twin's clean-room impl, cite "observed Decathlon SGTIN-96", NOT the proprietary pantos `EpcToEan.java`** (reference-only, never copy). Pipeline for FR-COLLECT-07 = `decode(EPC)→EAN-13→` core's existing `raw_item_identifier→product.barcode/gtin` lookup. Backend stashed it + pointed their FR-COLLECT-ID note at it.
- **Built `connectReceiveDrive`** (`ConnectReceiveDrive.kt` + gradle task over `DeviceDriver.receive`) — closes the old "DeviceDriver runner" harness TODO. Dry-run default, `M8TRX_RECEIVE_LIVE` gated.

**Two core findings this session (integrator-driven-testing thesis, again):** #72 multi-site movement resolution (**FIXED**) + FR-INTEG-04/FR-COLLECT-ID receive product-linkage (**surfaced + decoder handed off**, deferred to next round per Bob).

**Full compliance lifecycle now PROVEN on real data:** planogram directive → resolve → evaluate → **live drift** (sales, compliant→partial→non) → **remediation** (relocation restock-from-back #2/#6 climbing; receive→relocate mechanics for new stock). Marketing/investor-grade.

**Commits (branch `feature/connect-movement-emitter`, PR opened):** `6f55cb0` (feat: movement emitter scaffold) · `28c99fd` (feat: receive driver) · docs (this note + STATUS/TRACK/SESSION-LOG). The demo itself ran on the webhook plane + gitignored `.twin-state` (no repo change); EPC files in scratchpad.

**Live-fire recipes (movement / receive):**
- movement: `M8TRX_MOVEMENT_EPC_FILE=<file> M8TRX_MOVEMENT_TO_FIXTURE=GB-R3-U1 M8TRX_MOVEMENT_EXT_ID=<id> M8TRX_MOVEMENT_LIVE=true ./gradlew --no-daemon connectMovementDrive` (no `site_id` needed post-#72).
- receive: `M8TRX_RECEIVE_EPC_FILE=<file> M8TRX_RECEIVE_SITE_ID=84f2a1c1-… M8TRX_RECEIVE_SPACE_ID=e19e1502-… M8TRX_RECEIVE_LIVE=true ./gradlew --no-daemon connectReceiveDrive` (Denver site + Back Room space; receive doesn't persist spaceId).
