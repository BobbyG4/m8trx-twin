# Project Status — M8TRX Twin

---

## ⚠ NEXT SESSION PRIORITIES (updated 2026-07-01 — Session 13 · ★ LIVE-COMPLIANCE DEMO PROVEN end-to-end — 12 real sales drove compliance compliant→partial→non_compliant · 24/2/2 · triple-verified · remediation arc started with Backend)

> **PICK UP HERE (Session 13 ongoing / 14):** **The live-compliance demo is PROVEN end-to-end.** Backend shipped the
> **compliance-EVALUATION engine** (services #69 — `POST /api/v2/compliance/directives/{id}/evaluate` + `GET …/state` +
> a **recompute-on-sale hook**); Bob authorized the one-time **re-point** of the 28 resolved targets → the correct zone
> **`e82a21f3`** (Gondola R3 Back U1 = code `GB-R3-U1`, where the stock actually is); operator `/evaluate` set the
> baseline **27/1/0**. Twin drove **12 real sales** (`connectSaleStream`) → compliance drifted **compliant →
> partially_compliant → non_compliant** (incl. the `req=1` one-event edge case) → **24/2/2**, 4 targets spanning the arc,
> **triple-verified** every beat (twin fire → twin self-verify SOLD via `items/details` → Backend live `/state`).
> **The S12 wrong-zone mapping is RESOLVED** (re-pointed to `e82a21f3`). Detail:
> `status/session-notes/2026-07-01-session-13-live-compliance-demo-remediation-prep.md`.
>
> **NEXT — the REMEDIATION arc (in progress with Backend):** **(1)** Backend is building the **Connect inbound
> movement/transfer ingester** (`X-Data-Type: inventory_movement`, FR-INTEG S178 — the missing runtime `thing_location`
> writer) → unblocks **non→compliant remediation** (stock returns to shelf). **Contract co-designed** (Backend's proposed
> payload + twin's 3 notes: EPC-qty-implicitly-1 · per-item-not-all-or-nothing · relocation(#2/#6)+receive(#7) split).
> **(2)** Twin is scaffolding the **restock emitter** (`MovementDriver` + `connectMovementDrive`, dry-run default,
> **HOLD-FIRE** until Backend's ingester deploys) — relocate backroom/other-gondola EPCs of SKUs 2456187/2456191 →
> `GB-R3-U1` (restock-from-back for #2/#6); #7 (2706524) needs the existing **receive** path (unavailable_at_site).
> **(3)** Fire the **remediation demo** when the ingester lands → watch compliance climb **non→partial→compliant** +
> remediated events. **(4)** **File the compliance read-back TWIN-REQ** — twin's Connect key gets **`403
> CONNECT_NOT_EXPOSED`** on `GET /state` (drove the whole demo blind; loop closed only via Backend) — hard evidence now
> exists; draft `status/briefs/TWIN-REQ-DRAFT-planogram-compliance-readback-2026-06-30.md`. **(5)** Optional: capture a
> marketing/investor **visual** of the proven compliance arc. **Still gated on Backend (deferred):** FR-PLN-08
> compliance-check task-gen needs the **Notifications spine** (Triad Slice-1).
>
> **Creds (gitignored `.env`, machine-local — re-supply on a fresh box):** `M8TRX_TWIN_BEARER` (m8trx_c3…, the working
> service Bearer — scopes `integration:manage`+`scan:submit`+`inventory:create`+`inventory:read`, tenant-wide) ·
> `M8TRX_TWIN_WEBHOOK_KEY` (twin-pos X-API-Key) · `M8TRX_CONNECT_OUTBOUND_VERIFY_SECRET` (aacd…, the C3 HMAC, set
> core-side too). `M8TRX_TENANT_ID=ecfa6903-5c50-439f-8f80-185982de944e` · `M8TRX_CONNECT_INTEGRATION_SLUG=twin-pos` ·
> integration `5dfba5cd`. ConnectConfig reads `M8TRX_TWIN_BEARER` first (`M8TRX_TWIN_SERVICE_BEARER` is now a fallback alias).
> Keys-tab throwaway test keys were **revoked** (confirmed 401).
>
> **Connect harness — all 7 `connect*` drivers built + exercised** (`com.m8trx.twin.connect`, gradle `connect*` tasks, `connectSelfTest` 7 cases green):
> `connectMultiSiteSmoke` · `connectSaleStream` (+ sold-EPC persistence `.twin-state/`) · `connectChainActivity`
> (sale/restock/pricing/catalog × all 10 stores) · `connectSelfVerify` (items/details read-back, the closed loop) ·
> `connectScanSweep` (DRY-RUN default; `M8TRX_SCAN_LIVE=true` for live §6 scans — hold for BACKEND reader-topology) ·
> `connectOutboundReceiver` (§9 LAN receiver; PR #6) · **`connectPlanogramDrive`** (Mode-3 `directive_kind='planogram'`, dry-run default; PR #7, LIVE-PROVEN). **Comms:** `twin` seat on Slack `#m8trx-dev` (`@m8trx_twin`,
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

- **Planogram Mode 3 demo tail (NEW, S12)** — directive→targets→**resolved** is LIVE-PROVEN, but **FR-PLN-08 compliance-check task-gen + push** ride the **Notifications spine** (Triad Slice-1), which Backend defers behind the 07-30 critical path. Also OPEN: the **wrong-zone fixture mapping** ((a)/(b) decision with Backend — `GB-R3-U1` mapped to `GF-R6-U1`'s zone) + **no `/api/v2` compliance read-back** for twin self-verify (candidate TWIN-REQ, drafted `status/briefs/`). ✅ Partial OI-2 close: `POST /api/v2/compliance/fixture-codes` now loads `fixture_code_mapping` (operator-side).
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

## Store Concept (locked Session 3)

**Decathlon Manhattan** — Decathlon City format, NOT running specialty.
- Concept evolution: started as Bordeaux running specialty (160 sqm) → Florence CAD grammar showed the correct scale → 600 sqm Decathlon City format adopted
- Address: 620 6th Avenue, New York, NY 10011 (Flatiron District)
- Currency: USD
- SKU mix: running (primary) + fitness + hiking + swim + cycling + accessories + GPS watches
- LP scenario anchor: W-series sports watches ($29.99–$89.99), EAS-tagged, 40 items

---

## Project Artifacts

| Artifact | Path | Status |
|---|---|---|
| Kotlin project | `~/IdeaProjects/m8trx-twin/` | ✅ Compiles, NATS smoke passed |
| NATS emitter | `src/main/kotlin/com/m8trx/twin/layer0/NatsEmitter.kt` | ✅ Dual-publishes legacy + new pattern |
| REST emitter | `src/main/kotlin/com/m8trx/twin/layer0/RestEmitter.kt` | ✅ Written, untested (service bearer 401) |
| Store layout doc | `reference/data/STORE-LAYOUT.md` | ✅ 600 sqm, full fixture spec |
| Floor plan SVG | `reference/data/floor-plans/decathlon-running-medium.svg` | ✅ Generated |
| Snapshot JSON | `reference/data/snapshots/decathlon-running-small/day-start.json` | ⚠ Outdated (300 sqm) — update to 600 sqm |
| Seed script | `scripts/seed_store.py` | ✅ Live on mother |
| Raw catalog | `reference/sample_stores/decathlon-korea-raw.csv` | ✅ 56,003 rows |
| Curated catalog | `reference/data/catalog/decathlon-korea-curated.csv` | ✅ 920 SKUs, USD prices |
| Final SKU file | `reference/data/catalog/decathlon-manhattan-skus.csv` | ✅ English names, ready |
| Florence CAD ref | `reference/sample_stores/deacthlon_florence/` | ✅ 4 files |
| API surface doc | `reference/integration/M8TRX-API-SURFACE.md` | ✅ 27 atoms mapped |

---

## Active Requirements Filed Back to Core

| Brief | Status | Blocks |
|---|---|---|
| TWIN-REQ-001 `fitting_room` → `try_on_zone` | ABSORBED 2026-05-09 | — |
| TWIN-REQ-002 `commerce_projection` writer | **FILED, AWAITING ABSORPTION** (2026-06-11) | Scripts 1, 3, 5 |
| CORE-REQ-001 catalog attribute enrichment (brand · classification · coded attrs) — **inverse, core→twin** | ✅ **ABSORBED** 2026-06-21 (core loaded + verified; merged-commit `eb39526`) | — |
| CORE-REQ-002 `site_category` (functional role `store/office/warehouse`) — **inverse, core→twin** | ✅ **LIVE on mother** (RE-RESEED v2, 2026-06-26; core mig 146) | — |
| CORE-REQ-003 build Connect simulators — **inverse, core→twin** | ✅ **DONE** (S11 — all 5 P0 sims live end-to-end) + **Planogram Mode 3 driver LIVE-PROVEN** (S12, PR #7 — directive→targets→resolved, triple-verified) | — |
| CORE-REQ-004 toolchain assessment — **inverse, core→twin** | ✅ **DONE** 2026-06-29 (GO; PR #1 merged — Gradle 9.6.1 · Kotlin 2.4.0 · jackson 2.21.4 P0 CVE · jnats 2.25.3 · coroutines 1.11.0 · logback 1.5.37); deliverable in `status/briefs/` | — |
| `inventory:sell` capability split | PRE-EXISTING in CLEANUP-TASKS | Cashier persona |

> TWIN-REQ-002 brief: `~/IdeaProjects/m8trx-shared/twin/requirements/TWIN-REQ-002-commerce-projection-writer.md` (filed by core 2026-06-11, formalizing the insight at CLAUDE.md §Insights). P1 — blocks the commerce story on the API path until core ships the writer (feed-raw-let-platform-derive per `twin/insights/IMPORT-CONTRACT.md` §2).

> **CORE-REQ-001 (delivered 2026-06-21):** catalog attribute coding for the Things/Discover surface. **Decathlon** profile — `reference/data/chain/{classification.csv, display_lookup.csv}` + `brand`/`classification_key` on assortment (normalisation model). **MK/Hansae** profile (second model, built) — `reference/data/mk-trend/` (numeric-code model) from the real MK Trend spec (`reference/hansaemk/`). Same coding grain across both → vertical-portable. Rationale: `reference/data/chain/CATALOG-CODING-MODEL.md`; MK writeup: `reference/data/mk-trend/MK-CODING-PROFILE.md`. **Applied to the demo tenant by RE-RESEED v2 (2026-06-26)** — coding layer now live on the Discover/Things surface.

---

## Deploy State (Session 3)

- m8trx-twin: uncommitted (coordinator handles commit)
- M8trxDemo on mother: 160 zones + 920 products live
- NATS: smoke objLocation published successfully to .29
- Service API key: active, `principal_kind=service` — auth works on NATS, fails on REST inventory endpoints

---

## Open Decisions

- **Container deploy target** — decision deferred until first runnable scenario
- **Stack** ✅ LOCKED: Kotlin
- **Scenario clock** ✅ LOCKED: shared scheduler, `rate=0` step mode
- **Config canonical format** ✅ LOCKED: JSON
