# M8TRX API Surface — Layer 0 AtomEmitter Map

**Status:** v1, drafted 2026-05-10. Authoritative for Layer 0 implementation.
**Sources:** m8trx-services controllers + edge NATS taxonomy as of commit cb4d096 (m8trx-shared HEAD). When core ships a new public surface, update this doc in the same session you wire the new emitter.

This doc is the **mapping table between twin's Layer 0 atom names and core's public M8TRX surfaces** (REST endpoints + NATS subjects + webhook ingest paths). Twin's `AtomEmitters` interface is the in-process facade; each method below corresponds to one atom emit and dispatches against exactly one of these surfaces.

**Relationship to other twin docs:**

- The **Generator catalog** in [LAYER4-CONFIG-SCHEMA.md](../architecture/LAYER4-CONFIG-SCHEMA.md) names *what* generators emit. This doc names *where* those emits land in core. One-to-many — a single generator can drive 3-4 atom kinds.
- The **DomainEvent v1 taxonomy** (also in LAYER4) is simulator-internal and does NOT cross the Layer 0 boundary. The mapping `DomainEvent → atoms emitted` is decided per-generator and per-journey.
- The **Persona schema** ([PERSONA-SCHEMA.md](../architecture/PERSONA-SCHEMA.md)) drives *who* and *how often*; it does not change the wire shapes here.

**Authoritative when:** the listed file path + class still exists on `m8trx-services:master`. **Hint only when:** a row is marked `[needs verification]` or the section is dated more than 30 days stale relative to a recent core PR touching the area.

**Posture (HARD):** twin is a customer-class consumer. If a row says `[needs verification]`, the next session opens that file and reads it — does not guess and patch in twin. If a needed atom has no row at all, it is filed as a TWIN-REQ brief (see § Gaps surfaced).

---

## Wire-protocol vocabulary (single-paragraph cheat sheet)

Three transports, in decreasing-fidelity order:

1. **NATS JetStream** (`192.168.55.29:4222`) — fire-and-forget pub/sub, persistent stream. Subject pattern is **dual-published** today (per `AreaEventPublisher.kt`):
   - Legacy: `area.<areaIdNoHyphens>.<eventType>` — still the only pattern Android subscribes on; will be retired Sweep B.
   - New: `m8trx.<tenantId>.<siteId>.<domain>.<eventType>` — engaged when env vars `M8TRX_TENANT_ID` + `M8TRX_SITE_ID` are set on the publisher. Twin SHOULD publish on the new pattern; subscribers (incl. main-server) consume both.
   - The envelope shape is `Event { type, ts, id, areaId, body }` where `body` is the typed event class. See `area/AreaEvent.kt` and `area/AreaEventPublisher.kt`.
   - The subject's `areaId` segment is the **space** UUID (a Sweep-A class-rename leftover; the wire literal stays `area.*` until Sweep B). When publishing, strip hyphens: `UUID.toString().replace("-", "")`.
2. **REST** — `dev.m8trx.com` → office .28 main-server (or LAN equivalent `192.168.55.28:9999`). Auth header `Authorization: Bearer <token>`. The bearer is one of: a JWT minted at `/api/v2/auth/login`, a Google-OAuth-exchanged JWT from `/api/v2/auth/exchange`, OR a service api_key (`m8trx_<hex>`) with `principal_kind='service'` and `scope_grants` covering the required `<resource>:<action>` capabilities.
3. **Webhook ingest** — `POST /v1/webhook/{tenantId}/{integrationKey}` for POS / catalog / shipment / pricing data. Auth via `X-API-Key` (per-integration api_key) OR `X-M8TRX-Signature` (HMAC-SHA-256 of raw body). Tenant + integration must exist and be enabled.

Twin's auth posture: **provision a single `principal_kind='service'` api_key on the M8trxDemo tenant with a broad `scope_grants` covering everything in the table below**. Use that bearer for both REST and (where needed) the webhook ingest path. Webhook auth secrets sit on `integration_channel.endpoint_config.hmac_secret` for HMAC; the simpler path is the per-integration api_key.

---

## Mapping table — atom emitters

Each row is one method on twin's `AtomEmitters` interface. Atom name uses lowerCamel matching the underlying NATS event-type literal where one exists; otherwise a verb-first name.

| # | Atom (Layer 0 method) | Transport | Endpoint / Subject | Payload (canonical fields) | Auth | Idempotency / dedup | Notes |
|---|---|---|---|---|---|---|---|
| 1 | `objLocation` | NATS | `area.<spaceIdNoHyphens>.objLocation` (legacy) + `m8trx.<t>.<s>.xovis.objLocation` (new) | `Event { type:"objLocation", ts:Long, id:String, areaId:String, body: ObjLocation }`. Body fields: `objectId, x, y, height?, isMale?, faceMask?, hasTag?, viewDirection?[2], layoutId` (per `area/AreaEvent.kt:210`) | NATS unauth at MVP (broker on .29 trusts LAN). Twin uses same URL; production reach via VPN | None at protocol — same `id` re-published is consumed twice. Twin should mint UUIDv4 per emit | This is the single highest-volume atom (Xovis emits 5-25 Hz per tracked person). `objectId` is twin-internal ID for an actor; emit one stream per actor for the duration they're in-store |
| 2 | `objEviction` | NATS | `area.<spaceIdNoHyphens>.objEviction` + new pattern | `Event { ... body: ObjEviction }`. Body: `objectId, layoutId` (`AreaEvent.kt:222`) | NATS unauth | Same as above | Fires when a tracked person is dropped from Xovis tracking. Twin emits when an actor ends a journey AND has crossed the exit gate. Pair with a final `crossing` |
| 3 | `crossing` | NATS | `area.<spaceIdNoHyphens>.crossing` + new pattern | Body: `Crossing { sliceId:UUID, objectId, leftToRight:Boolean, sourceEventId?, layoutId }` (`AreaEvent.kt:244`) | NATS unauth | Same | `sliceId` references a `crossing_slice` config row (entry/exit gate counter line). Twin needs the slice UUIDs from opening-state seed |
| 4 | `arDevicePosition` | NATS | `area.<spaceIdNoHyphens>.arDevicePosition` + new pattern | Body: `ArDevicePosition { deviceId, x, y, directionX?, directionY?, viewDirX?, viewDirY?, anchorsResolved, anchorsTotal, driftMm?, anchors? }` (`AreaEvent.kt:315`) | NATS unauth | Same | OPTIONAL for v1 — twin can simulate AR handhelds carried by operators in stocktake/restock journeys. Skip until a journey actually models handheld carriers |
| 5 | `personSessionStart` | REST | `POST /api/v2/visionai/person-sessions` | `{ trackingId:String, spaceId:UUID, siteId:UUID, entryTime?:ISO8601, classification?:String }` → 201 with `{ id, spaceId, siteId, trackingId, classification, entryTime, exitTime:null }` | Bearer (JWT or service api_key) — `inventory:create` capability | None — duplicate POSTs create duplicate sessions. Twin tracks (actor → personSessionId) in-memory | Path-agnostic; XovisIngress on .29 hits this same endpoint after deriving sessions from `objLocation` streams. Twin can either rely on XovisIngress to derive (just emit `objLocation`) OR call this directly for a deterministic ID. Recommend the latter for replay stability |
| 6 | `personSessionClose` | REST | `PATCH /api/v2/visionai/person-sessions/{id}` | `{ exitTime?:ISO8601 }` (defaults to NOW). Idempotent — already-closed returns 200 with existing record | Bearer — `inventory:update` | Idempotent on `id` | Pair with #5 |
| 7 | `tryOnZoneEntry` | NATS | `area.<spaceIdNoHyphens>.fittingRoomEntry` + new pattern (literal stays `fittingRoom*` per Sweep B note in `NatsSubjectTaxonomy.kt:46`) | Body: `FittingRoomEntry { objectId, roomId:UUID, prevEpcs:List<String>, layoutId }` (`AreaEvent.kt:249`) | NATS unauth | None | Edge `TryOnZoneNatsSubscriber` on .29 picks this up and POSTs to `/api/v2/fitting-rooms/{roomId}/sessions`. Twin can rely on that path OR hit REST directly (atom #11). Choose ONE path per scenario; double-emit creates two sessions |
| 8 | `tryOnZoneAddItem` | NATS | `area.<spaceIdNoHyphens>.fittingRoomAddItem` + new pattern | Body: `FittingRoomAddItem { roomId:UUID, epc:String, layoutId }` (`AreaEvent.kt:254`) | NATS unauth | None | Same NATS-vs-REST choice as #7. EPCs must already exist in `item_identifier` (seeded via opening state) |
| 9 | `tryOnZoneExit` | NATS | `area.<spaceIdNoHyphens>.fittingRoomExit` + new pattern | Body: `FittingRoomExit { objectId, roomId:UUID, layoutId }` (`AreaEvent.kt:252`) | NATS unauth | None | Edge subscriber's `onFittingRoomExit` POSTs to `/sessions/{sid}/close` with `itemsOut:null` so main-server derives kept/lost from scan-event window. To force a specific kept/lost split, call REST directly (atom #12) |
| 10 | `tryOnZoneSessionStart` (REST direct) | REST | `POST /api/v2/fitting-rooms/{roomId}/sessions` | `{ occupancySource:String, objectId?, gender?, friesDeviceId?:UUID, initialEpcs?:List<String> }` → 201 with `{ id, zoneId, ... }` | Bearer — `inventory:create` | None — same dedup posture as #5 | Direct alternative to atoms #7-#9 NATS path. Path is `/fitting-rooms/` for one release cycle even though the table renamed to `try_on_zone` (`TryOnZoneController.kt:31` note) |
| 11 | `tryOnZoneAddItem` (REST direct) | REST | `POST /api/v2/fitting-rooms/sessions/{id}/items` | `{ epc?:String, epcs?:List<String> }` (one of) → `{ accepted, itemResolved, itemsInCount }` | Bearer — `inventory:create` | None per call; main-server tracks unique EPCs per session | Up to 200 EPCs per call |
| 12 | `tryOnZoneSessionClose` (REST direct) | REST | `POST /api/v2/fitting-rooms/sessions/{id}/close` | `{ itemsOut?:List<String>, explicitExitTime?:ISO8601 }`. `itemsOut:null` → main-server derives from scan window | Bearer — `inventory:update` | None | Returns `{ session, itemsRecovered, itemsLost, ghostsObserved, financialValueLost }` — the LP shrinkage moment |
| 13 | `tryOnZoneSessionAbandon` | REST | `POST /api/v2/fitting-rooms/sessions/{id}/abandon` | `{ reason?:String }` | Bearer — `inventory:update` | None | Use when an actor leaves a try-on without exiting cleanly (Shoplift journey may use this if it walked the actor through a try-on first) |
| 14 | `rfidScanBatch` | REST | `POST /api/v2/scans` | `{ siteId:UUID, spaceId?:UUID, zoneId?:UUID, fixtureId?:UUID, readerId:String, readerType:String("RFID"\|"barcode"), reads: [{ identifier:String, rssi?:Short, readCount?:Int, timestamp?:ISO8601, antennaPort?:Int }] }` → 202 with `{ accepted, deduplicated, resolved, eventBatchId }` | Bearer — `scan:submit` | Server-side dedup by (identifier, reader, time-bucket); see `ScanService.ingestBatch` | Use for handheld scans (StaffRestock, StocktakeWalk) AND for fixed RFID readers (RestockGenerator backroom-dock arrival reads). Up to ~1000 reads per batch is safe |
| 15 | `rfidScanNats` | NATS | `scan.<deviceId>.raw` (per `NatsSubjectTaxonomy.scanSubject`) | Free-form JSON; `ScanEventEnricher` on .29 enriches and republishes as `area.<space>.scanEvent`. Body shape mirrors `AreaEvent.ScanEvent` | NATS unauth | None | Alternate path used by Android RFID adapters (Invengo, etc.). Twin should prefer atom #14 for determinism — REST has explicit dedup semantics |
| 16 | `stocktakeBegin` | REST | `POST /api/v2/stocktake/sessions` | `{ siteId:UUID, spaceId?:UUID, mode:String("general"\|"compliance"\|"inbound_receiving"), scopeZoneIds:List<UUID>, participants:List<UUID>, initiationPath:String("self"\|"task"\|"post_stocking"), taskId?:UUID, reconciliationSource:String("cloud"\|"edge"\|"direct_upload") }` → 201 with session row | Bearer — `inventory:create` (user JWT — controller pulls actorId from JWT) | None at receipt level | Twin's StocktakeGenerator opens one session per scenario walk |
| 17 | `stocktakeAddSegment` | REST | `POST /api/v2/stocktake/sessions/{id}/segments` | `{ zoneId:UUID, userId?:UUID, startedAt:ISO8601, completedAt?:ISO8601, coverage:String("complete"\|"partial"), arlsActive:Boolean, locationConfidence:String("ar_verified"\|"manual"\|"unknown"), readerPowerSetting?:Double, readerFrequencySetting?:Double, readerSerial?:String, readerType?:String, reads: [{...same as atom #14...}] }` → 201 | Bearer — `inventory:create` | None | One segment per zone per operator. Server reconciles reads against expected stock and emits discrepancies |
| 18 | `stocktakeAddObservation` | REST | `POST /api/v2/stocktake/sessions/{id}/observations` | `{ itemId?:UUID, zoneId?:UUID, observationType:String("expiry_approaching"\|"damaged_tag"\|"display_condition"\|"slow_moving"\|"pick_required"\|"custom"), severity:String("info"\|"warning"\|"critical"), details?:Object, createRemediationTask:Boolean }` → 201 | Bearer — `inventory:create` | None | OPTIONAL for v1 — drives FR-INV observation path. Twin can skip until a journey models observation flagging |
| 19 | `stocktakeEnd` | REST | `PATCH /api/v2/stocktake/sessions/{id}` | `{ status:"submitted"\|"abandoned"\|"interrupted_and_resumed", participants?:List<UUID>, scopeZoneIds?:List<UUID> }` | Bearer — `inventory:update` | None | Drives `StocktakeReconciled` DomainEvent on twin's bus once main-server completes reconciliation. Twin may need to poll `/sessions/{id}` or rely on a fixed delay; reconciliation status is async |
| 20 | `stocktakeResolveDiscrepancy` | REST | `PATCH /api/v2/stocktake/discrepancies/{id}` | `{ resolutionAction:String, resolved:Boolean }` | Bearer — `inventory:update` | None | OPTIONAL — only if AdjustmentGenerator is wired |
| 21 | `saleNative` | REST | `POST /api/v2/sales` | `{ siteId:UUID, externalSaleId:String, occurredAt?:ISO8601, lineItems:[{ sku?:String, epc?:String, epcList?:List<String>, quantity:Int }] }` → `{ tenantId, siteId, externalSaleId, lineItems:[{ transitionedItemIds, refusedItemIds, shortfall, attributionSource, attributionScores }], totals }` | Bearer — `inventory:transfer` (capability split to `inventory:sell` is queued in core CLEANUP-TASKS) | NOT idempotent at receipt level. Re-POSTing same `externalSaleId` re-attempts IN_STOCK→SOLD on already-SOLD items → `refusedItemIds`. Twin should mint deterministic `externalSaleId` from seed + actor + time bucket | M8TRX-native POS path. Up to 200 lineItems per receipt. Items must be in `IN_STOCK` state — receive them first via opening state OR atom #25 |
| 22 | `saleWebhook` | Webhook | `POST /v1/webhook/{tenantId}/{integrationKey}` with `X-Data-Type: sale_event` | Body shape per integration's field-mapping config. Default canonical: `{ external_sale_id:String, occurred_at:ISO8601, site_id:UUID, line_items:[{ sku?, epc?, epc_list?, quantity }] }`. `IntegrationIngesters.ingestSaleEvent` parses and runs same writer as #21 | `X-API-Key: m8trx_<hex>` (per-integration api_key) OR `X-M8TRX-Signature: <hex>` HMAC-SHA-256 | Receipt-level idempotency via `integration_event.external_event_id` | Use this when a scenario explicitly advertises "this is the customer's POS feed" — produces an `integration` + `integration_event` row that the integration health dashboard surfaces. Otherwise prefer #21 for simpler determinism |
| 23 | `productCatalogWebhook` | Webhook | `POST /v1/webhook/{tenantId}/{integrationKey}` with `X-Data-Type: product_catalog` | Per integration field-mapping. Canonical: `{ skus:[{ sku, name?, attribs?, ... }] }` per `IntegrationIngesters.ingestProductCatalog` | Same as #22 | Per `external_event_id` | Twin's opening state is normally seeded directly via `inventory:bulk` (atom #26) — use this only if a scenario explicitly demonstrates catalog-update propagation |
| 24 | `shipmentManifestWebhook` | Webhook | `POST /v1/webhook/{tenantId}/{integrationKey}` with `X-Data-Type: shipment_manifest` | Per integration mapping. Canonical: `{ manifest_id, expected_items:[{ epc?, sku?, quantity? }] }` | Same as #22 | Per `external_event_id` | Drives ShipmentArrivalGenerator. Pairs with atom #14 RFID reads at the receiving dock to close the loop |
| 25 | `inventoryReceive` | REST | `POST /api/v2/inventory/items/receive` | `{ siteId:UUID, spaceId:UUID, items:[{ epc:String }] }` → `{ received, created, errors:[{ epc, reason }] }` | Bearer — `inventory:create` | Per-EPC: existing IN_STOCK EPCs return in `errors` | Direct receive path — alternative to atom #24. Use for ShipmentArrivalGenerator when not exercising the integration substrate. EPCs must be in `expected` state OR be brand new |
| 26 | `inventoryBulkSku` | REST | `POST /api/v2/inventory/skus/bulk` | `{ skus:[{ sku:String, attribs?:Object, attribsType?:String }] }` → `{ inserted, skipped }` | Bearer — `inventory:create` | Per-sku: existing skipped | Used by twin's opening-state seeder, not a per-tick atom. Keep here for completeness |
| 27 | `easAlarm` | NATS | `area.<spaceIdNoHyphens>.alarmEvent` + new pattern (`m8trx.<t>.<s>.eas.alarmEvent`). Also reachable as `alarm.<gateId>.triggered` per `NatsSubjectTaxonomy.alarmSubject` (separate stream `M8TRX_ALARMS`) | Body: `AlarmEvent { gateId:String, alarmType:String, epcs:List<String>, layoutId }` (`AreaEvent.kt:202`) | NATS unauth | None | The LP shrinkage moment for the Shoplift journey. EPCs in body should be ones currently in IN_STOCK that an actor "carried" past the gate. No REST handler subscribes today — alarm consumer is the read-side analytics pipeline + future Sentinel module **[needs verification — no code-grepped subscriber found in main-server; check after Sentinel ships]** |
| 28 | `auditEventInternal` | REST (internal) | `POST /api/v2/internal/audit-events` | Hasura-style payload `{ id, table, event:{ op, data:{ old, new }, sessionVariables }, trigger, createdAt }` | `X-Hasura-Audit-Sig: <shared-secret>` header. Twin MUST NOT call this — endpoint is for Hasura event triggers only | dedup on `hasura_event_id` | Listed for completeness so a future twin-session does not mistakenly emit it. Audit rows for twin's actions are produced automatically when twin's mutations land via Hasura-tracked tables (via the trigger chain) or via REST controllers carrying `@Audited` |

**Atom count: 27 callable + 1 NOT-FOR-TWIN.** All of the v1 Generator catalog has at least one route.

---

## Sequencing notes (scenario authors, read this)

These are NOT enforced by core — twin's orchestrator must respect them. Wrong order produces `refusedItemIds`, 404s, or audit rows that look anomalous in the dashboard.

1. **Opening-state seed FIRST, atoms SECOND.** `meta.openingState` populates `space + zone + fixture + item + item_identifier + thing_location` rows directly via Hasura admin in the orchestrator's bootstrap step (NOT via Layer 0 atoms). Once seeded, Layer 0 emits operate on EPCs and IDs that already exist.
2. **`personSessionStart` (atom #5) before any try-on / scan attributed to that customer.** XovisIngress also derives a session from `objLocation` streams (atom #1) — pick ONE path per scenario for determinism. If twin emits #5 directly, also emit `objLocation` so VisionAI position pipelines have signal, but skip XovisIngress's auto-session-derivation by using a `trackingId` that doesn't match the Xovis tracking-id pattern.
3. **`tryOnZoneEntry` → `tryOnZoneAddItem`+ → `tryOnZoneExit`** must arrive in order on the same `roomId`. Out-of-order: edge subscriber opens a "defensive session" (`TryOnZoneNatsSubscriber.openSessionDefensively`) which works but produces a `occupancySource:'epc_reads'` instead of `'motion_sensor'` — a tell in the audit log.
4. **`saleNative` (#21) presumes items are IN_STOCK.** Sequence: `inventoryReceive` (or shipment webhook) → optional `rfidScanBatch` movements → `saleNative`. Refused items in the response are usually the sequencing tell.
5. **`saleNative` for an item that just left a try-on zone via `tryOnZoneSessionClose` itemsOut** — the close marks items as recovered to IN_STOCK; sale immediately afterward should succeed. If the close marks items as `lost`, sale will refuse them.
6. **`stocktakeAddSegment` reads should reference EPCs whose `thing_location` is in the segment's `zoneId`.** Reads of EPCs from elsewhere produce discrepancies — which is realistic and useful for the StocktakeGenerator's discrepancy demo.
7. **`easAlarm` (#27) without a preceding `tryOnZoneEntry`/`tryOnZoneExit` carrying the EPC** is fine — that's the Shoplift journey by design. Alarm consumer correlates against `item.state` AND ARLS proximity.
8. **`crossing` (#3) at exit slice + `objEviction` (#2)** should be the last two atoms in any customer actor's stream. Reverse order or omitting #2 leaves a "ghost" person session open in `person_session` past the actor's actual exit (recoverable by close-on-timeout but the audit row tells the truth: ghost).

---

## Auth provisioning — once per environment

Twin's orchestrator boots with **one** service api_key, not per-actor JWTs. Provisioning:

1. Authenticate as a `tenant-admin` on M8trxDemo (one-time, from the orchestrator's secrets store — 1Password ref).
2. `POST /api/v2/integration-keys` (or via web UI) creating an api_key with `principal_kind='service'`, `owning_tenant_id=<M8trxDemo>`, and `scope_grants` covering: `inventory:{create,read,update,transfer}`, `scan:submit`, `analytics:read`. Capability list grows as new atoms land.
3. Stash the returned `m8trx_<hex>` value in twin's secrets (env var `M8TRX_TWIN_SERVICE_BEARER`). Orchestrator passes it as `Authorization: Bearer <value>` for every REST atom.
4. For webhook ingest atoms (#22-#24), provision an `integration` row with a per-integration api_key OR HMAC secret; store separately as `M8TRX_TWIN_WEBHOOK_KEY_<vendor>`. Production integrations get one each; twin can collapse to a single "synthetic" integration for MVP scenarios.

---

## Gaps surfaced — candidate TWIN-REQ briefs

Each item below blocks at least one Layer 0 atom or one v1 generator. Format: candidate-brief one-liner + which generator(s) it blocks. Twin sessions DO NOT file these — flag for Bob.

1. **No `principal_kind='service'` provisioning UI / endpoint exposed at `/api/v2/connect/principals` for self-serve.** Twin's bootstrap currently has to round-trip through a tenant-admin user JWT to mint the bearer. Blocks: orchestrator boot ergonomics. Severity: P3 (workaround exists). Candidate slug: `connect-service-principal-self-serve`.
2. **No documented EAS alarm subscriber in main-server.** Atom #27 is publish-only; no row in the LP shrinkage analytics path correlates EAS alarms to items today. Blocks: Shoplift journey end-to-end (alarm fires but doesn't surface in dashboard). Severity: P1 if the LP demo is required at MVP. Candidate slug: `eas-alarm-subscriber-and-lp-correlation`.
3. **`stocktakeEnd` (atom #19) reconciliation is async with no completion event/webhook.** Twin must poll `/sessions/{id}` to know when reconciliation completes and discrepancies are ready. Blocks: deterministic StocktakeReconciled DomainEvent timing. Severity: P2 (poll works, but adds drift across replays). Candidate slug: `stocktake-reconciliation-completion-event`.
4. **No REST surface for `crossing` events.** Atom #3 is NATS-only. A scenario that wants a "deterministic gate count" cannot emit via REST. Blocks: high-precision entry/exit count for investor demo. Severity: P3 (NATS works; REST would just add a deterministic alternative). Candidate slug: `crossing-event-rest-ingest`.
5. **`fitting_room` → `try_on_zone` REST path rename pending.** Already filed informally in twin/CLAUDE.md insights; the path stays `/api/v2/fitting-rooms/...` for one release cycle even though the table is renamed. Twin must hardcode the legacy path. Blocks: clean atom naming. Severity: P3 (cosmetic). Candidate slug: `try-on-zone-rest-path-rename` — likely closes alongside Sweep B.
6. **`personSessionClose` (atom #6) is path-by-id only — no `(trackingId, spaceId)` lookup.** Twin must remember the personSessionId returned from #5 across the actor's lifetime. If twin crashes mid-actor, no recovery. Blocks: orchestrator restart resilience. Severity: P3 (acceptable; in-memory state). Candidate slug: `person-session-close-by-trackingid`.
7. **No `inventory:sell` capability (atom #21 piggybacks `inventory:transfer`).** Already a known core CLEANUP-TASKS item — listed here so twin's cashier-persona scope_grants list reflects the eventual rename. Severity: P3.

---

## Out of scope for this doc

- **Hasura GraphQL queries / subscriptions** — twin reads `mother.m8trx.com/v2/v1/graphql` for opening-state seeds and read-side validation, but does not "emit" GraphQL mutations as atoms. The pattern is REST-or-NATS for emit, GraphQL for read. If a future generator needs to mutate via GraphQL, add a row above with transport `GraphQL` and document the operation name + variables.
- **Auth flow itself** — twin uses the bearer it was issued; the OAuth/email-verify/token-exchange dance documented at [`reference/dev/AUTH-FLOW.md` in m8trx-shared](../../../m8trx-shared/reference/dev/AUTH-FLOW.md) is a one-time bootstrap.
- **Hardware adapter wire protocols** (Xovis protobuf, Invengo RFID frames) — twin does not speak these; it speaks the post-adapter NATS/REST shapes.
- **Edge reconfiguration** (`ConfigManager`, `XovisCalibrationService`) — twin does not reconfigure edge components; it operates against a pre-calibrated M8trxDemo deployment.

---

## Maintenance

When core ships a new public surface that twin needs:

1. Add a row to the table with the file path the shape was extracted from.
2. If unsure about a field, mark `[needs verification]` in the row and note which file you couldn't confidently read.
3. If the surface doesn't exist and twin needs it, file as a TWIN-REQ brief; add a one-liner under § Gaps surfaced linking to the brief.

When core RENAMES a surface that twin uses:

1. Update the row with the new path/subject.
2. Add a "renamed-from / renamed-on" inline note for one release cycle.
3. After the cycle, drop the note. Don't accumulate retired-name annotations.

This doc lives at `~/IdeaProjects/m8trx-twin/reference/integration/M8TRX-API-SURFACE.md`. Twin commits manage its lifecycle.
