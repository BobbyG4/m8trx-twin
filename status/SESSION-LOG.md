# Session Log — M8TRX Twin

> **Full detail:** per-session working notes in `status/session-notes/`.
> **Archive:** full pre-rebuild log at `status/archive/sprint/SESSION-LOG-FULL-ARCHIVE-2026-05-16.md`.

---

## Rolling Summary — Recent Sessions

**Session 10 (2026-06-29 · Twin) — Toolchain currency pass (CORE-REQ-004): Jackson CVE P0 + Gradle 9 / Kotlin 2.4 / ecosystem align — GO, merged**
Short, tightly-scoped. Core filed **CORE-REQ-004** (core→twin) asking twin to pull its generator toolchain
current — **build-once-while-greenfield**, mirroring the services SB4+Gradle9 pull-forward (S185). Twin is the
simplest of the three (no Spring Boot / Compose / AGP), so this was leaf-dep currency + Gradle/Kotlin, not a
framework migration. **Verdict GO** — every bump landed clean, committed per step, **PR #1 merged to main**:
**P0 jackson 2.21.3→2.21.4** (CVE-2026-54512/13/15; exposure confirmed low — no default-typing vector in `src/`),
jnats 2.20.6→**2.25.3** (NATS wire-align w/ edge+services), coroutines 1.9.0→**1.11.0**, Kotlin 2.3.20→**2.4.0** GA
(twin has no Compose lock → ahead of core's 2.3.21), Gradle 8.14.4→**9.6.1** (no deprecations, Gradle-10-ready),
logback 1.5.18→**1.5.37**, ktlint **held 12.2.0** (survives clean — no `.editorconfig` pass needed). One source
fold-in: `setSerializationInclusion`→`setDefaultPropertyInclusion`. Caught two stack-watch snapshot drifts via
Maven-Central cross-check (logback real latest .37 not .18; Kotlin `<release>` is a beta — took 2.4.0 GA).
**Verification ladder green through the offline rung** (`clean build` + `ktlintCheck` + `connectSelfTest`: HMAC,
DTO casing round-trips on Connect §6/§8/§9 shapes, OutboundReceiver loop). **Live-smoke rung GATED, not forced** —
dev Connect reachable + LAN NATS open, but Bearer/tenant/slug not in `.env` (no-creds-in-repo); folds into next
session's full realtime smoke. No new core API gaps. Commits `63cb26a`→`6308590`, merge `68b74e6`.

**Session 9 (2026-06-27 · Twin) — M8TRX Connect P0 harness BUILT + LIVE-VALIDATED (loop proven SOLD) + async channel**
Big session. Built the full **M8TRX Connect P0 simulator harness** (CORE-REQ-003) — new `com.m8trx.twin.connect`
package (18 files): foundation (config · HMAC · typed `ConnectResponse` where non-2xx is data · `ConnectClient`
Bearer gateway · `WebhookClient` · two-mapper camel/snake casing split · DTOs to the code-verified Connect §8
shapes) + all 5 P0 sims (api-key bootstrap · inbound push driver · data-plane device driver · outbound receiver ·
provisioner + SFTP CSV formatter); `./gradlew connectSelfTest` offline-green (HMAC · casing · receiver loop). Added
the missing **`.editorconfig`** (intellij_idea/150 — ktlint had never actually passed; reformatted 4 pre-existing
files). Then **validated live against the `twin-pos` integration**: inbound `sale_event` (EPC + SKU paths) →
PROCESSED, and — once a **Bearer service key** landed (the `twin-pos` webhook key is webhook-ONLY; 401s on all
`/api/v2`) — **self-verified the sales as `state=sold`** (2 Denver EPCs sold, control in_stock) plus all 3 Bearer
scopes (read/scan/manage). Full loop proven: twin → Connect webhook → core moves inventory → twin reads it back
SOLD. Captured **`LIVE-OPERATIONS.md`** (24/7 ongoing-operation design: per-site timezone calendar · daily
lifecycle · closed-loop inventory · gentle pacing). Stashed the **10 mother site UUIDs** (`site_ids.csv`, for the
§6 device driver). **Key friction:** first hand-rolled SQL key re-mint 401'd (hash-mismatch — manual run hashed a
different string than the token; BACKEND's in-Postgres `digest()` re-mint fixed it); no self-serve scoped-Bearer
mint (OI-1, core gap — out-of-band for now); the `lookup` transform reads the `integration_lookup` table, not
`value_lookups` (OI-2). Switched twin↔core coordination to an **async mailbox**
(`m8trx-shared/brainstorm/COMMS-CONNECT-TWIN-2026-06-27.md`, append-only, no creds in-file). **Session called for
time:** Bearer key NOT persisted (re-supply next session); the **§9 outbound loop is the last P0 sim** to exercise.
Commits twin `4741e7a`→`a746002`.

**Session 8 (2026-06-26 · Twin) — RE-RESEED v2 verified · static-seed gap audit · M8TRX Connect pivot**
Short session. Picked up the reseed that was in flight from Session 7: core completed **RE-RESEED v2**
and verified it on mother; I **cross-checked it against the committed dataset — 9/9 headline metrics
byte-for-byte, zero drift → no amendment needed** (30 spaces · 929 zones · 53 try-on · 102,675 items ·
84,266 floor / 18,409 BOH · 2,586 products · 95 classes), all read-only/local (posture held), and
flipped STATUS + TRACK from "reseed in flight" → "live + verified" (incl. rewriting "What's LIVE on
mother" off the stale 2026-06-11 seed; corrected the date 06-24 → 06-26 — it was still in flight at
session start). Ran a **static-seed completeness audit**: structure is done; gaps are (a) data we have
but the reseed didn't apply — **staff/org model** (250-person roster/roles/reporting unprovisioned),
**per-region currency** (mother is USD-only), **localized names**; (b) thin/absent substrate —
**sensor/reader topology** (only 2 stubs, none in BOH/fitting), empty **try-on profiles**, absent
**LP/EAS substrate** (0 watch SKUs, no demo zone, no EAS tag); (c) blocked-on-core (images,
service-bearer, commerce_projection). **Triaged a post-reseed login 500** — confirmed **NOT** caused by
the (read-only) audit; root cause = the reseed's bulk mutations firing a **Hasura audit-trigger
cascade** that exhausted the backend's Hikari pool (10/10 active, auth starved); flagged the
**102,675-item dual-write (~205k rows)** as the likely amplifier; core's to fix (OPEN). **Closed on a
strategic pivot:** Bob is standing up **M8TRX Connect** as the canonical path for future seeds **and**
active interactions, with **parallel ERP/external simulators** (on a server) injecting the planned
`ACTIVITY-PLAN` activities through Connect; Bob is authoring a Connect **API doc** to share next
session. Bob switching MacBook Pro → iMac for a few days (repair); status + memory pushed for continuity.

**Session 7 (2026-06-22 → closed 2026-06-24 · Twin) — RESEED IN FLIGHT (core mid-stream)**
Reseed-dataset realism overhaul. Carved the floor into **sport-universe departments** from `brand`
(flagship 6–7 / large 4–5 / medium 2–3 — capped by gondola rows, so Denver/SF 6, NYC/Paris 7; new `sport_universe.py`; Decathlon's real "univers" model,
decided after web research on departments + back-of-house), made the **Stockroom a real lean
back-of-house** (receiving dock + racks; 18% of each style staged → a from-location for restock),
and fixed Bob's **flat-depth "88-pair shoe"** with realistic **size curves** (new `size_curve.py`;
per-style bell, colour-aware) — dropping inventory **277,515 → 102,675 EPCs** (the old total was the
bug; density knob `TIER_SCALE` ~2× for testing variety + realism). Added **lat/long to all 14 sites**
(was 0/14 on mother → geo map had nothing to plot). Rewrote `DEPLOY-HANDOFF.md` as the authoritative
**reseed hand-off** (in-place: site-coord UPDATE · drop+recreate departmentalized spaces + BOH ·
catalog enrich · re-import 102.7k items — EPC strings changed, so re-import not re-locate); synced
CHAIN-DATA-SPEC + IMPORT-MAPPING; updated STATUS + TRACK. Committed `17872e5` (feat) + `be0f712`
(docs). Then delivered **CORE-REQ-002** (`site_category` functional role; inverse core→twin; `bf1915c`)
and — the big one — **corrected the spatial hierarchy to `site → spaces → zones`** (Pass 1, `c480446`):
the single-space build was the error; a site has MANY spaces, so each store is now **3 spaces**
(Sales Floor / Back Room / Fitting Rooms, each its own SRF), departments staying `region` zones *in*
the Sales Floor; assembly columns dormant (Pass 2 = site assembly, pending). `layout.json`→`spaces[]`,
manifest→`stores[].spaces[]`. Coordinator-adjudicated (`m8trx-shared/reference/dev/SPATIAL-HIERARCHY.md`);
root cause = inherited single-space assumption + the overloaded word "region" (table=site-group /
`zone_type`=area / geo). **Closed 2026-06-24; the reseed is now in flight on mother (core, mid-stream) — next session checks the result + amends the (regenerable) twin dataset if it surfaced issues.**

**Session 6 (2026-06-22 · Twin)**
Catalog-coding + store-layout overhaul. Delivered **CORE-REQ-001** (inverse core→twin) catalog
attribute coding — `brand` (←vendor), `classification.csv` (+per-class `attributes_schema`),
`display_lookup.csv` (colour normalisation ×3 locales) — and **core absorbed it** (mother loaded +
verified, brief closed; core built a multi-catalog SurfaceProfile arch from our input). Built the
**MK/Hansae numeric-code coding profile** (`reference/data/mk-trend/`) as the contrasting 2nd model
(vertical-portability proof) from the real MK Trend spec (`reference/hansaemk/`, ex-Zenven). Bumped
jackson 2.18.2→2.21.3 (HIGH CVEs). Then diagnosed + fixed the **134-overlap MapCanvas bug** — root
cause **twin's hand-authored coords, not core's seed** — by rewriting `build_layout.py` parametric →
**10 UNIQUE per-store floors** (seeded off store_id, 324–741 m², 0 overlaps asserted, layout-driven
planogram), scaled checkout lanes (4/3/2), added circular front-of-store feature displays, and made
twin emit **mother-canonical zone geometry** (circle = center `POINT Z` + `properties`; polygon =
`POLYGON Z` ring; SRID 0/mm/Z=0). Open: **full mother reseed** of per-store layouts + geometry +
re-receive 277k EPCs at new fixtures (mother still on the old overlapping layout). ⚠ committed prod
Hasura admin secret in `scripts/seed_store.py` (Bob to rotate later).

> _Sessions 1–5 (Layer-4 schema lock · persona/journey/DomainEvents · first code+NATS+store seed · live-catalog re-base/Denver + EPC-encoder validation · multi-store chain dataset Wave 1) trimmed from the rolling summary — see the Session Index below for rows + notes links._

---

## Session Index

| # | Date | Summary | Notes |
|---|------|---------|-------|
| 10 | 2026-06-29 | **Toolchain currency pass (CORE-REQ-004) — GO, merged (PR #1)** — jackson 2.21.4 (P0 CVE, low exposure) · jnats 2.25.3 · coroutines 1.11.0 · Kotlin 2.4.0 · Gradle 9.6.1 (no deprecations) · logback 1.5.37 · ktlint held 12.2.0; offline verify green; live-smoke gated on creds → next session | [→](session-notes/2026-06-29-session-10-toolchain-currency-core-req-004.md) |
| 9 | 2026-06-27 | **Connect P0 harness BUILT + LIVE-VALIDATED** — 5 sims (`com.m8trx.twin.connect`), offline self-tests green; live vs `twin-pos`: `sale_event` EPC+SKU → PROCESSED, **self-verified SOLD** via Bearer (2 Denver EPCs sold, control in_stock), all 3 scopes; `.editorconfig` added; `LIVE-OPERATIONS.md` (24/7 design); Bearer-key hash-mismatch fixed; async channel | [→](session-notes/2026-06-27-session-9-connect-p0-harness-live-validated.md) |
| 8 | 2026-06-26 | **RE-RESEED v2 verified** (twin cross-check, zero drift) · static-seed gap audit (staff/org · currency · sensors · LP/EAS) · post-reseed login-500 = audit-cascade pool starvation (not the audit) · **pivot to M8TRX Connect** for seeds + activity injection | [→](session-notes/2026-06-26-session-8-reseed-verified-gap-audit-connect-pivot.md) |
| 7 | 2026-06-22→24 | **CLOSED · reseed in flight** — `site→spaces→zones` spatial-hierarchy correction (3 spaces/site, Pass 1) + sport-universe departments + lean BOH + size curves (88-pair fix, 277k→102k EPCs) + site geo + `site_category` (CORE-REQ-002); reseed hand-off rewritten | [→](session-notes/2026-06-22-session-7-departments-boh-size-curves-geo.md) |
| 6 | 2026-06-22 | CORE-REQ-001 catalog coding delivered + **ABSORBED** by core; MK/Hansae 2nd coding profile built (portability proof); jackson 2.18→2.21 (CVEs); 134-overlap layout bug fixed → 10 unique per-store layouts; mother-canonical zone geometry (circle POINT Z + properties / polygon POLYGON Z) | [→](session-notes/2026-06-22-session-6-catalog-coding-perstore-layouts.md) |
| 5 | 2026-06-11 | Multi-store chain dataset (14 sites, 251 users, 277k EPCs) seeded; backend corrections digested into playbook; fixtures-as-zones applied; Phase-2 activity + Wave-2/3 roadmap | [→](session-notes/2026-06-11-session-5-chain-seed-corrections-playbook-roadmap.md) |
| 4 | 2026-06-03 | Re-base on live catalogs — Denver (US, real+images) built; Seoul parked; EPC encoder validated; onboarding hand-off | [→](session-notes/2026-06-03-session-4-store-rebase-denver-real-catalog.md) |
| 3 | 2026-05-11 | First code — Kotlin + NATS + store seeded + 920 SKUs | [→](session-notes/2026-05-11-session-3-first-code-nats-store-seeded.md) |
| 2 | 2026-05-10 | Persona + Journey + DomainEvent + Snapshot + persistence plan | [→](session-notes/2026-05-10-session-2-persona-journey-domainevents.md) |
| 1 | 2026-05-09 | Layer 4 schema lock + Trinity generator catalog | [→](session-notes/2026-05-09-session-1-layer4-schema-lock.md) |
