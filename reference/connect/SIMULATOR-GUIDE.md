# M8TRX Connect Simulators — Build & Run Guide

**Status:** P0 simulators built + offline-verified 2026-06-27 (Session 9). Live runs gated on a scoped
service key + an inbound integration with an `hmac_secret` (provisioned out-of-band — CORE-REQ-003
§"What twin needs"). **Contract (authoritative):** `~/IdeaProjects/m8trx-shared/reference/connect/M8TRX-CONNECT-API.md`.

These are twin's permanent **M8TRX Connect integration harness** (`com.m8trx.twin.connect`). They (a) drive
+ receive live transactions to **stress-test core's build** (health / usage / DLQ surfaces) and (b) compose
into the **real-installation demo loop**: a fake POS pushes sales inbound → a handheld drives scans → a
stocktake completes → M8TRX pushes `stocktake_result` back out to twin's emulated ERP with a verified
signature + visible retry/DLQ.

**Posture:** twin CONSUMES the public Connect API; it never authors schema. We build against the §11 phase
markers, not ahead of them (`feedback_api_schema_backend_owned`).

---

## Two surfaces, two mappers, two HMAC secrets

| Plane | Base URL | Auth | Field case | Mapper |
|-------|----------|------|------------|--------|
| Bearer (data §6 + control §7) | `…/server/api/v2` | `Authorization: Bearer m8trx_<hex>` | camelCase | `ConnectMappers.camel` |
| Inbound webhook (§8) | `…/server/v1/webhook` | `X-API-Key` **or** HMAC | snake_case | `ConnectMappers.snake` |

- The control plane (§7) is **mixed-casing**: create/patch-integration bodies are snake_case (carried by
  explicit `@JsonProperty` on the DTOs) while channel-update / transforms / test / health / DLQ are camelCase
  — so the single camel mapper serves the whole Bearer plane.
- The §9 **outbound `stocktake_result`** we RECEIVE is camelCase (M8TRX-canonical), NOT snake — parsed with
  the camel mapper.
- **Two distinct HMAC secrets**: `M8TRX_CONNECT_INBOUND_HMAC_SECRET` signs our inbound pushes;
  `M8TRX_CONNECT_OUTBOUND_VERIFY_SECRET` verifies M8TRX's outbound signature. Never reuse one for both.

---

## The P0 simulators

| # | Simulator | Class | Connect § | Drives |
|---|-----------|-------|-----------|--------|
| 1 | API-key bootstrap | `setup/ApiKeyBootstrap` | §7 api-keys | mints the `m8trx_<hex>` Bearer (`mint` / `rotate` / `revoke` / `list` / `dryRun`) |
| 2 | Inbound push driver | `sim/InboundPushDriver` | §8 | POSTs `sale_event` / `product_catalog` / `shipment_manifest` / `pricing_update`, then polls the DLQ |
| 3 | Data-plane device driver | `sim/DeviceDriver` | §6 | `POST /scans` + `/inventory/items/receive` as a service principal |
| 4 | Outbound receiver | `sim/OutboundReceiver` | §9 | HTTP server that receives `stocktake_result`, verifies the signature, dedupes on `sessionId`, drives retry→poison→alert |
| 5a | Connection/channel provisioner | `setup/Provisioner` | §7 | creates integrations + channels (inbound webhook / outbound / SFTP), transforms, test, health, DLQ |
| 5b | SFTP drop (formatter) | `sim/SftpDropDriver` | §7 SFTP | builds the header-row CSV core's `SftpFileDropJob` ingests. **Transport (sshj) deferred** — see below |

**Foundation:** `ConnectConfig`, `Hmac`, `http/{ConnectHttp, ConnectError}` (typed `ConnectResponse` — non-2xx
is DATA, not an exception), `ConnectClient` (Bearer gateway), `WebhookClient` (webhook plane), `model/*` DTOs.

### SFTP transport — deferred (Bob's scope call, 2026-06-27)
The CSV **formatter** is built + tested now (the load-bearing contract: "a correct header-row CSV lands"). The
sshj **transport** (upload to the watched directory) is a fast-follow gated on a live SFTP endpoint + creds —
no new dependency until then. `SftpDropDriver.writeLocal(...)` materializes the exact bytes a real drop would
deliver, for inspection.

---

## Run

**Offline self-tests (now — zero core dependency):**
```
./gradlew connectSelfTest
```
Proves: HMAC sign↔verify; the two-mapper casing split + DTO round-trips; the full OutboundReceiver loop
(valid-accept / replay-dedupe / tamper-reject / missing-sig-reject / failMode→500); the dry-run request
shapes + SFTP CSV formatter. This is the CI-able gate.

**Live (when the scoped key + inbound integration land):**
1. Fill `.env` (see `.env.example`) — `M8TRX_TWIN_SERVICE_BEARER`, `M8TRX_TENANT_ID`,
   `M8TRX_CONNECT_INTEGRATION_SLUG`, the HMAC secrets.
2. Provision (or confirm) an inbound integration + channel with an `endpoint_config.hmac_secret`
   (`Provisioner.createInboundWebhook`) — or mint a key with `ApiKeyBootstrap.mint`.
3. Drive the loop: `DeviceDriver` (scans/receive) → `InboundPushDriver` (push + DLQ poll) → stand up
   `OutboundReceiver` and confirm a `stocktake_result` arrives with a verified signature.
4. The live device-driver run also confirms whether the §6 Bearer path closes the old
   service-bearer→inventory 401.

---

## Notes for maintainers
- **`run_id`**: the §6/§8 wire payloads have no `run_id` field. Traceability rides deterministic external ids
  (e.g. `InboundPushDriver.pushSaleBySku` → `external_sale_id = "$runId-sale-$seq"`), supporting
  reset-to-opening-state by run (ACTIVITY-PLAN). Don't add a `run_id` field to a wire DTO.
- **slug = create-response source of truth**: `Provisioner` returns the slug (= the integration name it sent);
  `InboundPushDriver` consumes the configured slug — never re-derive it in two places.
- **Doc-sync**: the older webhook payload shapes in `reference/integration/M8TRX-API-SURFACE.md` (atoms #22–#24)
  are superseded by the Connect contract — that doc now carries a correction note.
- These drivers are standalone (plain `run()` / `serve()` seams). When the Layer-1..4 orchestrator lands, each
  adapts to `Generator.start(ctx)` in one line — they do not implement `Generator` yet (it's paper-only today).
