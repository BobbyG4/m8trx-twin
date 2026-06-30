# Session 11 — 2026-06-29 → 06-30 · M8TRX Connect LIVE-validation marathon (all 5 P0 sims exercised; 5 core bugs caught)

**One line:** Drove the full Connect surface live against dev — inbound (sales + catalog/pricing/shipment) · Bearer self-verify (closed loop) · outbound `stocktake_result` (C3) — coordinating with BACKEND over the Slack `#m8trx-dev` channel; the dogfood run surfaced **5 real core bugs**, each fixed/filed by core the same session. **CORE-REQ-003 (build Connect simulators) is now exercised end-to-end.**

**Machine/comms:** Worked solo as the **`twin`** seat on Slack v2 (`@m8trx_twin`, dormant-wake Monitor). Coordinator seat went away mid-session — Bob now drives Backend↔Twin directly. Comms helpers: `m8trx-shared/brainstorm/comms/slack-{send,recv,wake-check}.sh`.

---

## What shipped (all merged to main except #6)

| PR | Commit(s) | What |
|----|-----------|------|
| **#2** (merged `f3aa447`) | `c2cafcf` + `3278c25` | Multi-site sale smoke (`SaleEvent` store_id path + `MultiSiteSmoke`) · live `SaleStream` tap + sold-EPC persistence (`.twin-state/`) · `ChainActivityStream` (sale/restock/pricing/catalog × 10 stores) |
| **#3** (merged `35b6793`) | `86364bf` | Self-verify read-side: `ItemDetailsRequest`/`ItemDetail` + `ConnectClient.itemDetails` + `ConnectSelfVerify` (closed loop, `inventory:read`) |
| **#4** (merged `aced567`) | `daf6ea3` | `ScanStream` — §6 RFID scan-sweep (dry-run default; `M8TRX_SCAN_LIVE=true` to POST /scans) |
| **#5** (merged `0cb4d3b`) | `749fc98` | API-surface doc: Bearer plane live + `inventory/items/details` atom |
| **#6 — OPEN** | `020679a` | **LAN-bind outbound receiver runner** (`ConnectOutboundReceiver` + configurable `bindHost`; the §9 C3 receiver). **Merge to land it on main.** |

New gradle tasks: `connectMultiSiteSmoke` · `connectSaleStream` · `connectChainActivity` · `connectSelfVerify` · `connectScanSweep` · `connectOutboundReceiver` (all `verification` group, env-driven). `connectSelfTest` still green throughout.

---

## The arc (what was attempted, in order)

1. **Multi-site behavioral smoke (S188 canary)** — fired a normal EPC sale (→ PROCESS→SOLD via the NoScope→item-band path, services PR#49) + an unknown `store_id=SMOKE-9999` (→ QUARANTINE + unmapped `integration_site_xref`). BACKEND verified both server-side. Canary CLOSED.
2. **Live sale-stream tap** — 121 real Denver floor sales (warm-up + pm 40 + pm2 80), all 200, sold-EPC persistence so runs are safely repeatable. **Self-verified SOLD** once the Bearer landed (20/20 read back `sold` via `connectSelfVerify`).
3. **Chain-activity generator** — 1-of-each validate across Seoul/Busan/NYC (genuinely multi-site).
4. **Bearer wall down** (core services #51/#52) — self-serve scoped service-Bearer now 200s on `/api/v2` (was 401). Closed C1/OI-1. Verified the cockpit **Keys tab** mint (#53/web#31) consumer-side.
5. **C3 outbound loop** — stood up the LAN receiver (`192.168.55.210:8088/hooks/m8trx`), **provisioned the outbound channel myself via REST** (BACKEND is prod-DB-guarded), fired `test-outbound` → M8TRX signed → receiver verified HMAC + accepted + 200 → BACKEND verified the `outbound/processed` row. Then the **light retry pass**: `failMode=500` → retry scheduled → flipped to 200 → retry healed (`attempt_count=2, processed`). Poison@6 cited from core S178. **C3 CLOSED both ends.**

---

## Key discoveries — 5 core bugs the twin surfaced (acks ≠ processed)

1. **Cross-site read leak** (data-isolation) — a Denver-`site_scope` Bearer read the *entire tenant's* inventory via `items/details` (any EPC/site/state). Core fixed → cross-site reads now redacted (sensitive fields null); verified clean (no existence oracle; `attribsType` is a static default, not a leak).
2. **3 inbound ingesters broken** — `product_catalog`/`pricing_update`/`shipment_manifest` were written against a non-existent schema (`external_sku`, `product.metadata`, `shipment.external_id`, status `'manifested'`) → every one acked 200 but would have FAILED. Core fixed (services PR#56). Re-fired AS-IS → all PROCESSED.
3. **Pricing `price_source` CHECK reject** — `price_source='integration'` rejected by `product_price_source_check`. Core fixed → `erp_feed` (services PR#57).
4. **`integration_event.site_id = NULL` on NoScope EPC sales** — events smeared across all sites in the cockpit (custody was correct). Core fixed + backfilled the 121 (services PR#50).
5. **Dedup-shadows-failed-retry gap** — a *failed* outbound event's content-hash blocks a same-payload retry, and (not quarantined) escapes map-and-replay. **Filed for Bob / core CLEANUP.**

**Lesson re-confirmed:** a 200 webhook ack means *received*, not *processed* — insist on server-side verification (psql or twin's own `items/details`). That discipline caught every one of the above.

---

## Decisions
- **Webhook-plane = no Bearer** for data generation (sales/catalog/pricing/shipment). The Bearer is only needed for self-verify, scans (`scan:submit`), receive (`inventory:create`), and C3 channel-mgmt (`integration:manage`).
- **Env var standardized to `M8TRX_TWIN_BEARER`** (ConnectConfig falls back to the old `M8TRX_TWIN_SERVICE_BEARER`).
- **Drivers dry-run-safe by default** where they mutate the shared platform (`ScanStream` needs `M8TRX_SCAN_LIVE=true`; the receiver's live LAN bind + the channel-provisioning REST both required explicit Bob OK — the harness guard fired correctly on each).
- **Outbound channel provisioned by twin via REST**, not by BACKEND (prod-DB guard). Integration-management endpoints (`DELETE /integrations/{id}`, key-mgmt) are **admin-JWT-only** (`CONNECT_NOT_EXPOSED` to Connect keys) — by design (external keys don't manage themselves/keys).

---

## Branch/deploy state at close
- **main** at `0cb4d3b` (PRs #2–#5 merged). **PR #6 open** (`feature/connect-outbound-receiver` `020679a`) — merge to land the outbound receiver runner.
- M8trxDemo on mother: unchanged structurally; **~162 items moved in_stock→SOLD this session** (121 Denver stream + warm-ups + smoke + the chain-validate sale), prices/products/shipment touched by the re-fires (`TWIN-CAT-1`, `TWIN-SHIP-1`).
- Creds live in gitignored `.env` (machine-local): `M8TRX_TWIN_BEARER` (m8trx_c3…, working) · `M8TRX_TWIN_WEBHOOK_KEY` · `M8TRX_CONNECT_OUTBOUND_VERIFY_SECRET` (aacd…). Throwaway Keys-tab test keys were **revoked** (confirmed 401).
- Receiver process **stopped** at close.

## Loose ends for next session
- **Merge PR #6** (outbound receiver runner) → main.
- **`twin-outbound` test integration (`fcffa62d`) lingers on M8trxDemo** — twin can't delete it (`CONNECT_NOT_EXPOSED`, admin-only). Harmless (points at a now-stopped receiver). Ask BACKEND/admin to delete, or repurpose for the LIVE-OPERATIONS runtime.
- **LIVE-OPERATIONS runtime** (`reference/connect/LIVE-OPERATIONS.md`) — the per-site business-hours calendar + closed-loop daily lifecycle, now that every primitive is live-proven. Compose: sales-deplete + restock/receive-replenish + scans + self-verify.
- Optional: wire `connectSelfVerify` into the generators for self-validating runs; flip `ScanStream` to live (`M8TRX_SCAN_LIVE`) with BACKEND watching reader-topology.
