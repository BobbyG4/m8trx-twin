# Session 8 — 2026-06-26 — RE-RESEED v2 verified · static-seed gap audit · post-reseed pool-starvation triage · M8TRX Connect pivot

**Type:** Short session — verification + audit + incident triage, closed on a strategic pivot to **M8TRX Connect** as the integration path. Bob switching machines (MacBook Pro M4 → iMac) for a few days during repair.

## TL;DR
Picked up the reseed that was in flight at the end of Session 7. Core completed **RE-RESEED v2** and verified it on mother; I independently cross-checked it against the committed twin dataset (**9/9 headline metrics byte-for-byte, zero drift → no dataset amendment needed**) and synced the status docs from "reseed in flight" → "live + verified." Ran a **static-seed completeness audit** ("what's missing to continue core's work") and **triaged a post-reseed login 500** (confirmed NOT caused by my read-only audit; root cause = the reseed's bulk-mutation audit-trigger cascade starving the backend's Hikari pool). Session closed as Bob pivoted to standing up **M8TRX Connect** as the canonical path for future seeds **and** active interactions, with parallel ERP/external simulators to inject the planned activities. Bob is authoring a Connect **API document** to share next session.

## What shipped
- **Twin-side reseed verification** — cross-checked core's reported numbers vs the committed dataset (`chain-manifest.json`, `classification.csv`, per-store `layout.json`). All 9 match exactly: 30 spaces · 929 zones · 53 try-on · 102,675 items · 84,266 floor / 18,409 BOH (17.9%) · 2,586 products · 95 classes · Denver SF/BR/FT 129/9/3. **Read-only + local only — no mother / Hasura / NATS / API access (system-integrator posture held).**
- **Status docs synced** — `STATUS.md` + `TRACK-TWIN.md` flipped from "RESEED IN FLIGHT" → "RE-RESEED v2 COMPLETE + VERIFIED"; rewrote "What's LIVE on mother" (was the stale 2026-06-11 seed: 277,515 EPCs / USD / no coding / no coords) to the canonical 3-space live state; CORE-REQ-001 + CORE-REQ-002 marked applied/live; next-steps re-ranked. **Date corrected** 2026-06-24 → 2026-06-26 (the reseed was still in flight at this session's start, so it landed today).

## Key discoveries

### A. Static-seed gap audit — the "what's missing" deliverable
The **structural** seed (sites · spaces · zones · catalog structure · 102,675 placed items) is complete + verified. Remaining gaps are enrichment + activation substrate, in three buckets:

**In the dataset but the reseed didn't apply it** (deliberate / pending import path):
- **Staff / org model** — `staff/` carries 250 people (roles, home-store binding, full manager/reporting tree, 30 inactive w/ reasons) + `org-chart.json`. Reseed didn't touch users; the role→capability / site-binding / reporting / inactive model is unprovisioned. `IMPORT-MAPPING.md` marks this **❌ OPEN — needs a user/role/org provisioning contract** (candidate brief TWIN-REQ-003, **not yet filed** — see Decisions).
- **Per-region currency + pricing** — data carries `price_local`/`currency` (EUR 11,008 · KRW 4,209 · USD 7,758 rows); mother shows **USD only** (single shared US product master, "as decided"). Tracked under `CATALOG-IMPORT-ONBOARDING`.
- **Localized product names** — `name_local` (FR/KO machine gloss ~97%) not applied (shared master = English names). Same import path.

**Thin / absent in the dataset itself** (twin's to build):
- **Sensor / reader topology** — only **2 sensor stubs in the sales floor**; **0 in the stockroom** (the receiving dock!) and **0 in fitting rooms**. Reseed didn't seed sensors. Decision needed: do scan/objLocation events key on *zones* (sensors cosmetic) or *readers* (build the fleet)? Gates the play if reader-keyed.
- **Try-on behavioral profiles** — zones differentiated by *name* (Gait Analysis / Footwear Bench / Fitting Stall) but `properties={}` — no machine-readable profile (apparel/footwear/watch) that TWIN-REQ-001 was meant to enable.
- **LP / EAS substrate (absent)** — **0 watch SKUs** (US master has none), no GPS-watch demo zone in any layout, no EAS-tag attribute on any item. The whole LP/theft + EAS scenario (1 of 5 scripts) has no item, no zone, no tag. (Spec known-gap #3.)

**Blocked on core** (already tracked): images (hot-link vs cached bytes) · service-bearer on inventory endpoints · `commerce_projection` writer (TWIN-REQ-002).

> Note: with the Connect pivot, most bucket-A/B items become "seed through Connect" rather than direct-DB — the audit findings stand, the **delivery mechanism** changes.

### B. Post-reseed login 500 — Hikari pool starvation (NOT the audit)
Bob hit an auth/exchange **500 from m8trx-services (.28)** right after the reseed (requestId `3cc2943b`) and asked whether my audit caused it.
- **It did not.** The entire session was local-filesystem reads (git, `python3`/`grep`/`cut`/`awk` over repo files) + edits to two local markdown docs — **zero network calls**, no path to the backend's Hikari pool (that pool is only checked out by m8trx-services' own request handlers).
- **Root cause (Bob + core):** `HikariPool-1` exhausted (total=10, active=10, idle=0, waiting=30). Mother DB itself healthy (74/200). The reseed's **bulk mutations fired a Hasura audit-trigger cascade** → audit-ingest INSERTs are WAL-I/O-bound (IO/WALSync) → hold all 10 backend→DB connections → auth can't get one → 500. **Pool starvation, not a crash.**
- **Twin-side insight added:** the heaviest writer wasn't the spaces/zones (~959 rows) — it was the **102,675-item re-import, dual-written to `thing_location` + `scan_event` (~205k row writes)**. If the audit trigger fires per-row on item custody/location writes, that re-import is the likely amplifier. Worth core confirming the audit volume is item-write-dominated.
- **Ownership:** core's to fix (pool headroom / async-batched audit-ingest / quiesce audit triggers during a bulk reseed). Twin does **not** touch the backend or mother. **Status: OPEN with core.**
- **Mitigation via pivot:** Connect-based incremental seeding (next phase) avoids the bulk direct-DB writes that triggered the cascade.

## Decisions
1. **M8TRX Connect becomes the canonical integration path** for both future **seeds/updates** and **active interactions** (the "play"). Posture win — routes through the public webhook/HMAC front door instead of direct-DB; sidesteps the bulk-reseed audit cascade and (likely) the service-bearer-inventory blocker. Elevates the former open-work "Connect simulator" from item #5 to the **primary mechanism**.
2. **Parallel external simulators** on one of Bob's servers will mock the ERPs + other external connections (POS / catalog / shipment / etc.) and inject the planned twin activities (`ACTIVITY-PLAN.md`) through Connect.
3. **Reseed accepted as-is** — zero drift, no dataset amendment.
4. **User/role/org provisioning brief (TWIN-REQ-003) NOT filed yet** — candidate, held pending Bob's Connect API doc (Connect may reframe how provisioning works). Do not file unapproved mid-pivot.
5. **SEED-PLAYBOOK pool-starvation known-issue recorded here** rather than edited into the playbook this session — the Connect pivot is about to partly supersede the direct-DB reseed mechanism; revisit `SEED-PLAYBOOK.md` holistically next session once the Connect path is known.

## Queued for next session
1. **Receive Bob's M8TRX Connect API document** (gating input) → design the connector hookup for (a) future seeds and (b) activity injection.
2. **Stand up parallel ERP/external simulators** (mock POS/catalog/shipment/etc.) → inject `ACTIVITY-PLAN.md` activities through Connect.
3. **Re-map the static-seed gaps** (staff/org · per-region currency/names · sensor topology) onto the Connect seed path.
4. **Settle the sensor / event-origin model** — zones vs readers.
5. **Track the login-500 pool-starvation** resolution with core.

## Branch / deploy state at close
- **Branch:** `main`. Status-doc sync committed + pushed at close (commit ref in `SESSION-LOG.md`).
- **m8trx-twin code:** unchanged — Kotlin scaffold compiles, NATS smoke passed; no event emit yet (runtime not built). No code/runtime in flight to migrate.
- **M8trxDemo on mother:** **RE-RESEED v2 live + verified** — 14 sites (geo + `site_category`) · 30 spaces · 929 zones · 53 try-on · 102,675 items dual-written · 2,586 products + coding · `space_type` Hasura-exposed · Hasura `is_consistent:true`. Recorded core-side at m8trx-shared `693f706`. Pre-seed backup retained.
- **Open incident:** auth/exchange 500 (Hikari pool starvation from the reseed audit cascade) — **core investigating**; not a twin code issue.
- **Machine:** Bob moving **MacBook Pro M4 → iMac (secondary)** for a few days during repair. Twin runs as a parallel lane there; memory + status docs synced via this session's git push for continuity.
