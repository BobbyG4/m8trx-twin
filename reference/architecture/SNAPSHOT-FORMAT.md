# Snapshot File Format — Scenario Opening State

**Status:** LOCKED 2026-05-10
**Path convention:** `reference/data/snapshots/<store-class>/<state>.json` (e.g. `decathlon-running-small/day-start.json`)
**Referenced from:** `LAYER4-CONFIG-SCHEMA.md` § "Section: meta" via `meta.openingState`

A snapshot is the **deterministic seed of tenant-side state** before a scenario starts. The orchestrator loads the snapshot at `t = startWallclock - ε`, pushes the rows into M8trxDemo via M8TRX's public APIs, and only then unblocks generator `start()` calls. Same snapshot byte-for-byte → same opening-state in mother → reproducible scenarios.

Every snapshot contains layout (Layer 1 spatial primitives) + inventory (Layer 1 things) + optional reach grants. It does NOT contain runtime data (sessions, scan events, transactions); those are produced by generators during the scenario.

---

## Top-level shape

```json
{
  "schemaVersion": "1",
  "storeClass": "decathlon-running-small",
  "state": "day-start",
  "createdAt": "2026-05-10T09:00:00+09:00",
  "metadata": {
    "footprintM2": 1500,
    "skuCount": 2347,
    "fixtureCount": 64,
    "zoneCount": 22,
    "vertical": "RETAIL",
    "type": "SPORTING_GOODS",
    "market": "KR"
  },
  "spaces":           [ /* Layer 1 — Space rows */ ],
  "zones":            [ /* Layer 1 — Zone rows */ ],
  "fixtures":         [ /* Layer 1 — Fixture rows */ ],
  "items":            [ /* Layer 1 — Item rows (catalog SKUs) */ ],
  "itemIdentifiers":  [ /* Layer 1 — Item Identifier rows (RFID EPCs / barcodes) */ ],
  "thingLocations":   [ /* Layer 1 — Thing Location rows (where each identifier currently sits) */ ],
  "tenantShareGrants": [ /* optional — pre-existing M8TRX Reach grants */ ]
}
```

Eight required sections + one optional. Each section is an array of typed rows mirroring the corresponding mother table (post-mig 127, post-mig 129).

---

## Section: spaces

Layer 1 spatial primitive — physical area within a site.

```json
{
  "id": "11111111-1111-1111-1111-111111111111",
  "siteId": "e60909b0-0aed-486f-b03e-2b7b26b58ba2",
  "name": "Main Floor",
  "polygon": {
    "type": "Polygon",
    "coordinates": [[ [0,0], [30,0], [30,50], [0,50], [0,0] ]]
  },
  "metadata": { "level": 1, "publicAccess": true }
}
```

`polygon` is GeoJSON (PostGIS-compatible). Coordinates in meters from the site origin.

---

## Section: zones

Sub-region inside a space (e.g. "Running Shoes" zone, "Fitting Rooms" zone). One row per Zone.

```json
{
  "id": "22222222-2222-2222-2222-222222222222",
  "spaceId": "11111111-1111-1111-1111-111111111111",
  "name": "Running Shoes",
  "zoneType": "merchandise",
  "polygon": { "type": "Polygon", "coordinates": [...] },
  "metadata": { "category": "footwear", "fixtureCount": 8 }
}
```

`zoneType` values per post-mig 127 enum: `merchandise` | `try_on` | `cashier` | `entrance` | `eas_gate` | `back_of_house` | `fitting_room_legacy` (kept for migration only — never authored fresh).

For `try_on` zones, an optional companion row in a `tryOnZones` section (post-mig 129) carries the typed sub-config:

```json
"tryOnZones": [
  {
    "id": "33333333-3333-3333-3333-333333333333",
    "zoneId": "22222222-...",
    "stallCount": 6,
    "kind": "apparel"      // apparel | footwear_bench | watch_demo | mixed
  }
]
```

---

## Section: fixtures

Physical merchandising unit inside a zone (wall, table, gondola, mannequin, demo bench).

```json
{
  "id": "44444444-4444-4444-4444-444444444444",
  "zoneId": "22222222-...",
  "name": "Wall A1 — Kalenji Run",
  "fixtureType": "wall",
  "geometry": {
    "type": "Polygon",
    "coordinates": [[ [3,3], [6,3], [6,3.5], [3,3.5], [3,3] ]]
  },
  "templateSize": { "widthM": 3.0, "depthM": 0.5, "heightM": 2.4 },
  "metadata": { "shelfCount": 6, "skuCapacity": 48 }
}
```

`fixtureType` values: `wall` | `table` | `gondola` | `circle` | `mannequin` | `demo_bench` | `cashier_counter` | `eas_gate` | `custom`.

---

## Section: items

Catalog SKU (the *kind* of thing — not the physical instance). One row per distinct SKU × variant.

```json
{
  "id": "55555555-5555-5555-5555-555555555555",
  "vendor": "decathlon-kr",
  "sku": "kalenji-run-100-black-m",
  "name": "Kalenji Run 100 — Black/M",
  "attrs": {
    "category": "running_shoe",
    "brand": "Kalenji",
    "size": "M",
    "color": "Black",
    "gender": "unisex"
  },
  "priceCents": 4900000,                  // 49,000 KRW
  "currency": "KRW",
  "easTagged": false
}
```

Twin's `sku_catalog` table (see `TWIN-DB-AND-GRAPH.md`) is the source-of-truth for these rows; snapshots embed a frozen copy at the time of authoring so replays are stable even if the catalog evolves.

---

## Section: itemIdentifiers

Physical instance — the actual RFID EPC or barcode on a single item. One identifier per individual object on the floor.

```json
{
  "id": "66666666-6666-6666-6666-666666666666",
  "itemId": "55555555-...",
  "rfidEpc": "E280689400000000A1B2C3D4",
  "barcode": "8809123456789",
  "metadata": { "encoded_at": "2026-04-15", "encoding_run": "decathlon-kr-001" }
}
```

For the Decathlon-running-small / day-start snapshot: ~20-30 identifiers per "fast-mover" SKU, ~5-10 per long-tail SKU, total ~12,000-15,000 identifier rows. JSON-on-disk is fine at this volume; if a snapshot exceeds ~50k identifiers, switch to JSONL streaming.

---

## Section: thingLocations

Where each identifier currently sits. One row per `itemIdentifierId`. Mother's `thing_location` table is the runtime view; snapshots seed it.

```json
{
  "itemIdentifierId": "66666666-...",
  "fixtureId": "44444444-...",
  "zoneId": "22222222-...",        // denormalized for fast filter; must match fixture's zone
  "spaceId": "11111111-...",       // denormalized; must match zone's space
  "lastSeen": "2026-05-09T22:00:00+09:00",
  "lastSeenSource": "rfid_overhead" // rfid_overhead | rfid_handheld | manual_pick | snapshot_seed
}
```

The orchestrator validates that every `itemIdentifierId` referenced here exists in `itemIdentifiers[]` and that `fixtureId` chains back to the same space. Validation failure aborts scenario load.

---

## Section: tenantShareGrants (optional)

Pre-existing M8TRX Reach grants the tenant has accepted. Most snapshots leave this empty; populate when a scenario depends on cross-tenant share state being present at start.

```json
{
  "id": "77777777-...",
  "ownerTenantId": "9039b6d4-3ff1-4541-8ea1-2b25eb6ed412",   // M8trxDemo
  "guestTenantId": "<other-tenant-uuid>",
  "scope": "site",
  "scopeRefId": "e60909b0-...",
  "grantedAt": "2026-04-01T00:00:00+09:00"
}
```

---

## Validation rules (orchestrator-enforced before scenario start)

1. **Schema version pin** — `schemaVersion` must equal the version the orchestrator was built against. Future migrations bump the version + ship a converter.
2. **FK chain integrity** — every `zone.spaceId` exists in `spaces[]`; every `fixture.zoneId` exists in `zones[]`; every `itemIdentifier.itemId` exists in `items[]`; every `thingLocation.itemIdentifierId` exists in `itemIdentifiers[]` and its `fixtureId/zoneId/spaceId` chain is internally consistent.
3. **Polygon geometry** — every polygon is a closed valid GeoJSON ring, area > 0, no self-intersections. PostGIS rejects malformed polygons at insert; pre-validate to fail fast.
4. **Fixture-in-zone containment** — every fixture's geometry must be ≥ 95% contained within its parent zone's polygon (5% tolerance for boundary cases).
5. **No runtime data** — snapshots must NOT contain `personSession`, `scanEvent`, `commerceProjection`, `behavioralProjection`, `auditLog` rows. These are runtime-only; presence in a snapshot rejects the load with a structured error.
6. **Vertical / type consistency** — `metadata.vertical` and `metadata.type` must match the scenario's `meta.vertical` / `meta.type`. Snapshots are vertical-typed and don't cross over.

Validation failures abort scenario load with structured error rows; partial loads are forbidden.

---

## Authoring workflow

```
1. STORE-LAYOUT.md (TBD)            → spaces[], zones[], fixtures[]
   Authored from research (Decathlon walkthroughs, public press kits).

2. SKU-CURATION.md (TBD)            → items[], itemIdentifiers[]
   Decathlon Korea catalog (~20k items) → curated to ~2-3k SKUs by category;
   variant-expanded (size × color); identifier counts per SKU set by depth-of-stock heuristic.

3. Anchor pass                      → thingLocations[]
   Each identifier assigned to a fixture per the curated SKU's anchor rule
   (e.g., "Kalenji Run 100 lives on Wall A1, all sizes").

4. Optional reach grants            → tenantShareGrants[]

5. Validation                       → run orchestrator's snapshot validator before commit
```

The first three steps are upstream design tasks (`reference/data/STORE-LAYOUT.md` + `reference/data/SKU-CURATION.md`, both Step C of twin's STATUS.md). Step 4 is per-scenario. Step 5 is mechanical.

---

## Multiple snapshots per store class

A given store class typically has multiple snapshots covering different opening states:

```
reference/data/snapshots/
└── decathlon-running-small/
    ├── day-start.json              # full inventory, ~9:55am pre-open
    ├── tuesday-afternoon.json      # half-stocked, mid-week stragglers
    ├── post-stocktake.json         # fresh after a reconciliation
    ├── compliance-deviation.json   # planogram-deviated — Script 5 entry state
    └── black-friday-eve.json       # extra inventory, long-tail thinned
```

Scenario configs reference one snapshot via `meta.openingState`. The same snapshot can seed multiple scenarios (e.g., `day-start.json` is the entry point for "Saturday Rush", "Day in the Life", "Fitting Room Conversion", and "The Theft").

---

## What this format does NOT cover

- **Camera / sensor configuration** — Xovis cameras, RFID readers, EAS gates are configured via M8TRX's existing onboarding flow (REST `/api/v2/sensors/...`); the snapshot points at sensor *coverage records* via fixture FKs but doesn't define the sensors themselves.
- **AR anchors** — populated by Android session calibration; snapshots presume calibration has already occurred. If a scenario needs to test calibration, it starts from a "no-AR-anchors" snapshot and includes a calibration walk in the journey.
- **Compliance target state** — `compliance_target_state` rows are scenario-specific (which planogram is in force right now); they're authored alongside the scenario, not the snapshot.
- **Audit log seed** — not seeded; audit_log starts empty per scenario and accumulates as generators run.

---

## Cross-references

| Doc | Purpose |
|---|---|
| `LAYER4-CONFIG-SCHEMA.md` § "Section: meta" | `meta.openingState` field that points to a snapshot |
| `PERSONA-SCHEMA.md` | Persona records — referenced at runtime by generators, not embedded in snapshots |
| `TWIN-DB-AND-GRAPH.md` § "Initial schema sketch" | `snapshot` table that catalogs available snapshots in twin's DB |
| `reference/data/STORE-LAYOUT.md` (TBD) | Authors `spaces[]`, `zones[]`, `fixtures[]` for the running-specialty store class |
| `reference/data/SKU-CURATION.md` (TBD) | Authors `items[]`, `itemIdentifiers[]` from Decathlon Korea catalog |
