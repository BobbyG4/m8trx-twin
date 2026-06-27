# Session 9 — 2026-06-27 — M8TRX Connect P0 harness BUILT + LIVE-VALIDATED (loop proven SOLD)

**Branch:** main · clean · 5 commits (`4741e7a` → `a746002`). Build + ktlint + `connectSelfTest` green.
**Headline:** Built the full M8TRX Connect P0 simulator harness (CORE-REQ-003) and validated it **live end-to-end** against the `twin-pos` integration — twin pushed sales through Connect's webhook front door, core moved real inventory, and twin **self-verified the items as `state=sold`** via the Bearer plane. Then switched twin↔core coordination to an async mailbox channel.

---

## What shipped (commit refs)

| Commit | What |
|--------|------|
| `4741e7a` | **chore(lint):** add `.editorconfig` (`intellij_idea`, max_line_length=150). Repo had NONE → ktlint was silently defaulting to `ktlint_official` and never actually passing; reformatted 4 pre-existing files into compliance (cosmetic). |
| `344edd9` | **feat(connect):** M8TRX Connect P0 simulator harness — `com.m8trx.twin.connect` (18 files). Foundation + all 5 P0 sims. `./gradlew connectSelfTest` (offline, zero-core). |
| `94bea24` | **feat(connect):** `ConnectLiveSmoke` runner + `connectLiveSmoke` gradle task; made `sale_event.siteId` **optional** (§8: EPC path attributes a specific item, needs no site). |
| `a94ef24` | **docs(twin):** `reference/connect/LIVE-OPERATIONS.md` (24/7 ongoing-operation design) + STATUS/TRACK. |
| `a746002` | **chore(chain):** `reference/data/chain/site_ids.csv` — store_code → mother site UUID (10 stores), from the Connect channel. |

**Package shape** (`com.m8trx.twin.connect`): `ConnectConfig` · `Hmac` · `http/{ConnectError, ConnectHttp}` (typed `ConnectResponse`, non-2xx is DATA) · `ConnectClient` (Bearer gateway) · `WebhookClient` (webhook plane) · `model/{ConnectMappers, bearer/*, webhook/*, outbound/*}` (two `ObjectMapper`s for the camel/snake casing split) · `setup/{ApiKeyBootstrap, Provisioner}` · `sim/{OutboundReceiver, DeviceDriver, InboundPushDriver, SftpDropDriver}` · `ConnectHarness` (offline self-tests) · `ConnectLiveSmoke` (live runner).

---

## Live validation (against `twin-pos`)

- **Tenant:** M8trxDemo `ecfa6903-5c50-439f-8f80-185982de944e` · **Integration:** `twin-pos`, id `5dfba5cd-fd74-4fb8-9c73-2a495419f863`.
- **Two surfaces, two keys, two doors** (confirmed live):
  - Webhook plane `…/server/v1/webhook/{tenant}/twin-pos` — `X-API-Key` (the `twin-pos` key). 200 OK.
  - Bearer plane `…/server/api/v2` — a **separate** Bearer service key (issued out-of-band). The `twin-pos` key is webhook-ONLY (401 on all `/api/v2`).
- **Inbound `sale_event`:** EPC path (no `site_id`) and SKU path both → 200 ack + Event Log `PROCESSED`.
- **SOLD self-verified** (`POST /api/v2/inventory/items/details`, Bearer): EPC `…21C51C` + `…5756B1` → `state=sold`; control `…48EFE7` → `in_stock`. **PROCESSED genuinely = item-sold for the EPC path.**
- **All 3 Bearer scopes live:** `inventory:read` (verify), `scan:submit` (`POST /scans` → 202, batch `7626e298…`), `integration:manage` (`GET /health` → 200, `yellow`, deadLetterDepth=0).

---

## Failed approaches / don't-repeat record

1. **ktlintFormat oscillated** (function-signature rule flipping wrap↔unwrap) under default `ktlint_official` — root cause = **no `.editorconfig`**. Fix: add the documented `intellij_idea`/150 config (`4741e7a`). Don't fight the formatter; set the code style.
2. **`twin-pos` webhook key 401s on the entire `/api/v2`** under BOTH `Bearer` and `X-API-Key` (still 200 on `/v1/webhook`). It cannot bootstrap a Bearer key — the mint endpoint itself needs an `integration:manage` Bearer (**chicken-and-egg**). The first Bearer key must come out-of-band.
3. **Hand-rolled SQL key re-mint 401'd** (`INVALID_TOKEN`) — `key_hash ≠ sha256(exact token presented)`. The manual run hashed a different string (shell-newline / 64-hex pasted vs the recipe's 48-hex `openssl rand -hex 24`). **Fix (BACKEND):** hash the token **inside Postgres** (`encode(digest(TOKEN,'sha256'),'hex')`, `key_prefix=left(TOKEN,8)`) so no shell ambiguity — that token authenticated immediately. Scheme is plain lowercase-hex sha256, prefix = first 8 chars, **no salt, no key cache**.
4. **`PROCESSED/SUCCESS` in the Event Log ≠ item-sold** — a shortfall (0 items) also reads SUCCESS. Only `inventory:read` (items/details `state`) confirms a real sale.
5. **`value_lookups` JSON is the WRONG mechanism** for site_id translation — the `lookup` transform reads the **`integration_lookup` table**, not the `value_lookups` block. (No UI to load those rows yet.)
6. **Concurrent Edit-tool writes to the shared channel file lost the race repeatedly** — COORD locked the protocol: **append-only** (`cat >>` / `printf >>`), never the read-modify-write Edit tool; **no credentials in the file ever**.

---

## Key discoveries / core gaps

- **OI-1 (core gap): no self-serve scoped-service-key mint.** The integration "API Keys" tab hardcodes `webhook:write`, so those keys 401 on `/api/v2`. There's no UI/endpoint for a tenant to mint a scoped **Bearer** service key — it must be issued out-of-band (Bob runs a KeyService re-mint). Connect doc §4 already flags `/api/v2/connect/credentials` (external-principal) as an `@MvpStub` (post-MVP). **Tracked core-side via channel OI-1 + the §4 stub** — not separately TWIN-REQ-filed (already core-owned).
- **OI-2: `lookup` transform ↔ `integration_lookup` table** (not `value_lookups`); no UI to load rows. EPC path needs no `site_id`, so this is optional vendor-realism.
- **§6 device driver DOES need real site UUIDs** (`ScanBatch.siteId` / `ItemReceiveRequest.siteId` are data-plane, NOT webhook-translated). The 10 mother site UUIDs are now stashed at `reference/data/chain/site_ids.csv`.
- **EPC path needs no `site_id`** (attributes a specific item) → it is the primary commerce path for the 24/7 operation. SKU path needs `site_id`.

## Decisions

- SFTP **sshj transport deferred**; CSV formatter only (Bob's scope call).
- **Closed-loop inventory conservation + gentle/jittered pacing (429-aware)** for the 24/7 op (`LIVE-OPERATIONS.md` §4/§5) — the two decisions to ratify before writing the runtime.
- **EPC path = primary** for live commerce; SKU+`integration_lookup` = optional vendor-realism.
- twin↔core coordination moved to the **async mailbox** `~/IdeaProjects/m8trx-shared/brainstorm/COMMS-CONNECT-TWIN-2026-06-27.md` (append-only; no creds in-file).

---

## ⚠ CRITICAL for next session — the Bearer key is NOT in the repo

The working Bearer service key is **issued out-of-band and intentionally NOT persisted anywhere in the repo** (protocol: no creds in files; it was never written to `.env`). **Next session must get it re-supplied** (Bob → `.env` as `M8TRX_TWIN_SERVICE_BEARER`, or 1Password) before ANY `/api/v2` work. The `twin-pos` webhook key (`M8TRX_TWIN_WEBHOOK_KEY`) is the other door.

**Env to run live** (`ConnectConfig.fromEnv`): `M8TRX_TENANT_ID=ecfa6903-5c50-439f-8f80-185982de944e` · `M8TRX_CONNECT_INTEGRATION_SLUG=twin-pos` · `M8TRX_TWIN_WEBHOOK_KEY=<twin-pos key>` · `M8TRX_TWIN_SERVICE_BEARER=<out-of-band Bearer>`. Integration id for health/DLQ = `5dfba5cd-fd74-4fb8-9c73-2a495419f863`. Denver site UUID = `84f2a1c1-fb0a-41b2-9e0d-c9102a22ca7e`.

## Queued for next session (ranked)

1. **§9 outbound receiver loop — the last unexercised P0 sim.** Stand up `OutboundReceiver` on this box's `192.168.55.x` LAN IP → provision an outbound `stocktake_result` channel via `Provisioner` → agree a shared `hmac_secret` with BACKEND → confirm dev can egress to a `192.168.55.x` host (else tunnel) → BACKEND triggers a test `stocktake_result`. Verify sig+dedupe+2xx, then flip to non-2xx to exercise retry→poison→DLQ. **BACKEND is ready to wire the trigger** (channel msg-02) — coordinate the secret + egress.
2. **Harness hardening (solo, no blocker):** add an `items/details` lookup to `ConnectClient` (SOLD-verify through twin code, not curl); a small `DeviceDriver` runner; make `OutboundReceiver`'s bind address configurable (it binds `127.0.0.1` today).
3. **Start the `LIVE-OPERATIONS.md` runtime:** business-hours calendar (per-site timezone) + daily lifecycle state machine + closed-loop inventory tracker, all on the validated Connect sims.
4. **Channel:** check `brainstorm/COMMS-CONNECT-TWIN-2026-06-27.md` at session-start (append-only; OPEN ITEMS C1✅ C2✅ resolved, C3 = outbound loop, OI-1/OI-2 core-side).

## Deploy state at close

- **twin:** main, clean. `4741e7a`..`a746002`. `./gradlew build connectSelfTest` green.
- **M8trxDemo:** `twin-pos` integration live. 2 Denver items SOLD (`…21C51C`, `…5756B1`), 1 control verified in_stock; a scan batch + a handful of test `sale_event`s in the event log (2 are FAILED test artifacts — the no-`site_id` SKU sale + a `TWIN-AUTHPROBE-001` webhook-liveness probe; ignore).
- **Channel:** `COMMS-CONNECT-TWIN-2026-06-27.md` at `c500985` (TWIN msg-04 posted).
