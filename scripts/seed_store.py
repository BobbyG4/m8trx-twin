#!/usr/bin/env python3
"""
Seed M8trxDemo [Demo] Main Floor with the Decathlon Running store layout.
600 sqm (24m × 25m), E-W gondola orientation, 160 total zone rows.

Actions:
  1. Clear existing try_on_zones and zones for the space
  2. Update space boundary to 24m × 25m
  3. Insert 11 area/specialty zones
  4. Insert 3 try_on_zone rows (Footwear Bench, Gait Analysis, Fitting Rooms)
  5. Insert 149 fixture zones
  6. Verify counts

Auth: Hasura admin secret (bootstrap-only tool, not a runtime twin emitter).
"""

import json, sys, uuid, requests

HASURA_URL   = "https://mother.m8trx.com/v2/v1/graphql"
ADMIN_SECRET = "veryValerie"
SPACE_ID     = "a66450b6-8ab1-4ea4-806e-2d078296e260"
SITE_ID      = "d68a4884-0957-4b90-947e-997018778583"
TENANT_ID    = "14d052b0-5505-4f29-b238-35efd71fb4bb"

HEADERS = {
    "Content-Type": "application/json",
    "x-hasura-admin-secret": ADMIN_SECRET,
}

UUID_NS = uuid.UUID("a0000000-0000-0000-0000-000000000000")


def gql(query, variables=None):
    r = requests.post(HASURA_URL, headers=HEADERS,
                      json={"query": query, "variables": variables or {}})
    r.raise_for_status()
    body = r.json()
    if "errors" in body:
        raise RuntimeError(f"GraphQL error: {body['errors']}")
    return body["data"]


def poly3d(x1, y1, x2, y2):
    """GeoJSON Polygon with z=0 matching zone geometry format."""
    return {
        "type": "Polygon",
        "coordinates": [[
            [x1, y1, 0], [x2, y1, 0], [x2, y2, 0], [x1, y2, 0], [x1, y1, 0]
        ]]
    }


def uid(name):
    return str(uuid.uuid5(UUID_NS, name))


# ── Area / Region zones ───────────────────────────────────────────────────────
# (code, name, zone_type, x1, y1, x2, y2)
AREA_ZONES = [
    ("Z-01", "Entrance",              "entry_exit", 4000,  0,     16000, 3000),
    ("Z-02", "Checkout Area",         "checkout",   17000, 0,     24000, 4000),
    ("Z-03", "Non-Retail Left Strip", "region",     0,     3000,  3000,  22000),
    ("Z-04", "Main Sales Floor",      "region",     3000,  3000,  17000, 22000),
    ("Z-05", "Stockroom",             "region",     0,     22000, 24000, 25000),
    ("Z-06", "GPS And Accessories",   "region",     3000,  15000, 8000,  22000),
    ("Z-07", "Accessories Wall",      "region",     8000,  15000, 11000, 22000),
    ("Z-11", "Service Cluster",       "region",     17000, 4000,  24000, 15000),
]

# Try-on zones — each becomes a zone row (zone_type=region) + a try_on_zone row
# (code, name, kind, x1, y1, x2, y2)
TRY_ON_ZONES = [
    ("Z-08", "Footwear Bench", "footwear_bench", 14000, 15000, 17000, 18000),
    ("Z-09", "Gait Analysis",  "equipment_test", 11000, 15000, 14000, 19000),
    ("Z-10", "Fitting Rooms",  "apparel_room",   17000, 15000, 24000, 22000),
]

# ── Gondola fixtures ──────────────────────────────────────────────────────────
# 8 E-W rows × 7 units front + 7 units back = 112 gondola fixtures
# Front rows: y_bottom = row_y, y_top = row_y + 1200
# Back rows:  y_bottom = row_y - 1200, y_top = row_y
ROW_Y_POSITIONS = [4000, 5800, 7600, 9400, 11200, 13000, 14800, 16600]
UNIT_X_STARTS   = [3000, 5000, 7000, 9000, 11000, 13000, 15000]
UNIT_WIDTH       = 2000
GONDOLA_DEPTH    = 1200

GONDOLA_FIXTURES = []
for ri, row_y in enumerate(ROW_Y_POSITIONS, start=1):
    for ui, x1 in enumerate(UNIT_X_STARTS, start=1):
        x2 = x1 + UNIT_WIDTH
        # Front unit
        GONDOLA_FIXTURES.append((
            f"GF-R{ri}-U{ui}",
            f"Gondola R{ri} Front U{ui}",
            x1, row_y, x2, row_y + GONDOLA_DEPTH,
        ))
        # Back unit (back-to-back, facing opposite direction)
        y_back_bottom = row_y - GONDOLA_DEPTH
        y_back_top    = row_y
        # Clamp so back of row 1 doesn't go below the sales floor origin y=3000
        if y_back_bottom < 3000:
            y_back_bottom = 3000
        GONDOLA_FIXTURES.append((
            f"GB-R{ri}-U{ui}",
            f"Gondola R{ri} Back U{ui}",
            x1, y_back_bottom, x2, y_back_top,
        ))

# ── Perimeter shelving ────────────────────────────────────────────────────────
# East sales wall: x=16500–17000, 8 bays
EAST_WALL_BAYS = [
    (3000, 6000), (6000, 9000), (9000, 12000), (12000, 15000),
    (15000, 18000), (18000, 19000), (19000, 20000), (20000, 22000),
]
WEST_WALL_BAYS = EAST_WALL_BAYS  # same y ranges

PERIMETER_FIXTURES = []
for i, (y1, y2) in enumerate(EAST_WALL_BAYS, start=1):
    PERIMETER_FIXTURES.append((f"PE-{i:02d}", f"East Wall Bay {i}", 16500, y1, 17000, y2))
for i, (y1, y2) in enumerate(WEST_WALL_BAYS, start=1):
    PERIMETER_FIXTURES.append((f"PW-{i:02d}", f"West Wall Bay {i}", 3000, y1, 3500, y2))

# ── Specialty fixtures ────────────────────────────────────────────────────────
SPECIALTY_FIXTURES = [
    # GPS display cases (Z-06)
    ("GPS-01", "GPS Display Case 1",  3200,  15500, 5200,  17000),
    ("GPS-02", "GPS Display Case 2",  5500,  15500, 7500,  17000),
    ("GPS-03", "GPS Display Case 3",  3200,  17500, 5200,  19000),
    ("GPS-04", "GPS Display Case 4",  5500,  17500, 7500,  19000),
    ("GPS-05", "GPS Display Case 5",  3200,  19500, 5200,  21000),
    ("GPS-06", "GPS Display Case 6",  5500,  19500, 7500,  21000),
    # Accessories wall (Z-07)
    ("ACC-01", "Accessories Bay 1",   8200,  15500, 10500, 17500),
    ("ACC-02", "Accessories Bay 2",   8200,  17800, 10500, 19800),
    ("ACC-03", "Accessories Bay 3",   8200,  20000, 10500, 21500),
    ("ACC-04", "Accessories Impulse", 8200,  21500, 10500, 22000),
    # Footwear bench (Z-08)
    ("FB-01",  "Footwear Bench Seat", 14200, 15200, 16800, 17800),
    # Gait analysis treadmills (Z-09)
    ("GA-01",  "Treadmill 1",         11200, 15300, 12400, 18600),
    ("GA-02",  "Treadmill 2",         12700, 15300, 13800, 18600),
    # Fitting stalls + service counter (Z-10)
    ("FR-01",  "Fitting Stall 1",     17200, 15500, 19500, 17500),
    ("FR-02",  "Fitting Stall 2",     19700, 15500, 22000, 17500),
    ("FR-03",  "Fitting Stall 3",     17200, 18000, 19500, 20000),
    ("FR-04",  "Fitting Stall 4",     19700, 18000, 22000, 20000),
    ("FR-SC",  "Fitting Room Service Counter", 17200, 20500, 22000, 22000),
    # Checkout counters + impulse rack (Z-02)
    ("CO-01",  "Checkout Counter 1",  17500, 500,   20000, 2000),
    ("CO-02",  "Checkout Counter 2",  20500, 500,   23000, 2000),
    ("CO-IR",  "Impulse Rack",        17000, 2500,  24000, 3200),
]

# Combined fixture list: (code, name, x1, y1, x2, y2)
FIXTURE_ZONES = GONDOLA_FIXTURES + PERIMETER_FIXTURES + SPECIALTY_FIXTURES

WALL_ADJACENT_CODES = {f"PE-{i:02d}" for i in range(1, 9)} | {f"PW-{i:02d}" for i in range(1, 9)}


def step_clear():
    print("1. Clearing existing try_on_zones and zones for space…")
    d = gql("""
        mutation DeleteTryOnZones($spaceId: uuid!) {
            delete_try_on_zone(where: {space_id: {_eq: $spaceId}}) { affected_rows }
        }
    """, {"spaceId": SPACE_ID})
    print(f"   deleted {d['delete_try_on_zone']['affected_rows']} try_on_zone rows")

    d = gql("""
        mutation DeleteZones($spaceId: uuid!) {
            delete_zone(where: {space_id: {_eq: $spaceId}}) { affected_rows }
        }
    """, {"spaceId": SPACE_ID})
    print(f"   deleted {d['delete_zone']['affected_rows']} zone rows")


def step_update_space():
    print("2. Updating space boundary to 24m × 25m (600 sqm)…")
    geom = poly3d(0, 0, 24000, 25000)
    d = gql("""
        mutation UpdateSpace($id: uuid!, $boundary: geometry!, $area: numeric!) {
            update_space_by_pk(pk_columns: {id: $id}, _set: {boundary: $boundary, area_sqm: $area}) { id name }
        }
    """, {"id": SPACE_ID, "boundary": json.dumps(geom), "area": 600})
    print(f"   updated space: {d['update_space_by_pk']['name']}")


def step_insert_area_zones():
    print("3. Inserting area zones…")
    objects = []
    for code, name, ztype, x1, y1, x2, y2 in AREA_ZONES:
        objects.append({
            "id": uid(code),
            "space_id": SPACE_ID,
            "name": name,
            "zone_type": ztype,
            "geometry_type": "polygon",
            "geometry": json.dumps(poly3d(x1, y1, x2, y2)),
            "enabled": True,
            "end_of_row": False,
            "visionai_covered": code not in ("Z-05", "Z-03"),
            "wall_adjacent": code in ("Z-05",),
            "properties": {"layoutRef": code},
        })
    d = gql("""
        mutation InsertZones($objects: [zone_insert_input!]!) {
            insert_zone(objects: $objects) { affected_rows }
        }
    """, {"objects": objects})
    print(f"   inserted {d['insert_zone']['affected_rows']} area zones")


def step_insert_try_on_zones():
    print("4. Inserting try-on zones…")
    zone_objects = []
    trz_objects  = []
    for code, name, kind, x1, y1, x2, y2 in TRY_ON_ZONES:
        zid = uid(code)
        zone_objects.append({
            "id": zid,
            "space_id": SPACE_ID,
            "name": name,
            "zone_type": "region",
            "geometry_type": "polygon",
            "geometry": json.dumps(poly3d(x1, y1, x2, y2)),
            "enabled": True,
            "end_of_row": False,
            "visionai_covered": True,
            "wall_adjacent": False,
            "properties": {"layoutRef": code, "tryOnKind": kind},
        })
        trz_objects.append({
            "id": uid(f"TRZ-{code}"),
            "zone_id": zid,
            "space_id": SPACE_ID,
            "site_id": SITE_ID,
            "name": name,
            "kind": kind,
            "status": "active",
        })

    d = gql("""
        mutation InsertZones($objects: [zone_insert_input!]!) {
            insert_zone(objects: $objects) { affected_rows }
        }
    """, {"objects": zone_objects})
    print(f"   inserted {d['insert_zone']['affected_rows']} try-on zone rows")

    d = gql("""
        mutation InsertTryOnZones($objects: [try_on_zone_insert_input!]!) {
            insert_try_on_zone(objects: $objects) { affected_rows }
        }
    """, {"objects": trz_objects})
    print(f"   inserted {d['insert_try_on_zone']['affected_rows']} try_on_zone rows")


def step_insert_fixture_zones():
    print(f"5. Inserting {len(FIXTURE_ZONES)} fixture zones…")
    # Insert in batches of 50 to stay within Hasura variable size limits
    batch_size = 50
    total = 0
    for i in range(0, len(FIXTURE_ZONES), batch_size):
        batch = FIXTURE_ZONES[i:i + batch_size]
        objects = []
        for code, name, x1, y1, x2, y2 in batch:
            objects.append({
                "id": uid(code),
                "space_id": SPACE_ID,
                "name": name,
                "zone_type": "fixture",
                "geometry_type": "polygon",
                "geometry": json.dumps(poly3d(x1, y1, x2, y2)),
                "enabled": True,
                "end_of_row": False,
                "visionai_covered": False,
                "wall_adjacent": code in WALL_ADJACENT_CODES,
                "properties": {"layoutRef": code},
            })
        d = gql("""
            mutation InsertZones($objects: [zone_insert_input!]!) {
                insert_zone(objects: $objects) { affected_rows }
            }
        """, {"objects": objects})
        total += d["insert_zone"]["affected_rows"]
        print(f"   batch {i // batch_size + 1}: inserted {d['insert_zone']['affected_rows']} rows")
    print(f"   total fixture zones inserted: {total}")


def verify():
    print("6. Verifying…")
    d = gql("""
        query Verify($spaceId: uuid!) {
            zone_aggregate(where: {space_id: {_eq: $spaceId}}) { aggregate { count } }
            try_on_zone_aggregate(where: {space_id: {_eq: $spaceId}}) { aggregate { count } }
        }
    """, {"spaceId": SPACE_ID})
    nz  = d["zone_aggregate"]["aggregate"]["count"]
    ntz = d["try_on_zone_aggregate"]["aggregate"]["count"]
    expected_zones    = len(AREA_ZONES) + len(TRY_ON_ZONES) + len(FIXTURE_ZONES)
    expected_try_on   = len(TRY_ON_ZONES)
    print(f"   zones: {nz} (expected {expected_zones}), try_on_zones: {ntz} (expected {expected_try_on})")
    ok = True
    if nz != expected_zones:
        print(f"   WARNING: zone count mismatch (got {nz}, expected {expected_zones})")
        ok = False
    if ntz != expected_try_on:
        print(f"   WARNING: try_on_zone count mismatch (got {ntz}, expected {expected_try_on})")
        ok = False
    if ok:
        print("   counts match")
    return ok


if __name__ == "__main__":
    gondola_front = sum(1 for c, *_ in GONDOLA_FIXTURES if c.startswith("GF-"))
    gondola_back  = sum(1 for c, *_ in GONDOLA_FIXTURES if c.startswith("GB-"))
    print(f"Seeding M8trxDemo store layout → space {SPACE_ID}")
    print(f"Layout: 24m x 25m (600 sqm)")
    print(f"  Area zones:    {len(AREA_ZONES)}")
    print(f"  Try-on zones:  {len(TRY_ON_ZONES)}")
    print(f"  Fixtures:      {len(FIXTURE_ZONES)}")
    print(f"    Gondola front: {gondola_front}")
    print(f"    Gondola back:  {gondola_back}")
    print(f"    Perimeter:     {len(PERIMETER_FIXTURES)}")
    print(f"    Specialty:     {len(SPECIALTY_FIXTURES)}")
    print(f"  Total zone rows: {len(AREA_ZONES) + len(TRY_ON_ZONES) + len(FIXTURE_ZONES)}\n")

    step_clear()
    step_update_space()
    step_insert_area_zones()
    step_insert_try_on_zones()
    step_insert_fixture_zones()
    success = verify()
    print("\nDone." if success else "\nDone with warnings — check counts above.")
