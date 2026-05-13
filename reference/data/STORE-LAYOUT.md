# Store Layout — Decathlon Running Specialty (Medium Format)

**Status:** LOCKED 2026-05-10
**Floor plan SVG:** `reference/data/floor-plans/decathlon-running-medium.svg`
**Reference:** `reference/sample_stores/deacthlon_florence/ter_decath_04.jpg` (CAD plan, 3,600 sqm big-box — spatial grammar extracted and scaled)

---

## Concept

Decathlon Running specialty store, Korea mall inline unit. Scaled to 600 sqm for demo fixture density.
Spatial grammar derived from the Florence Decathlon CAD plan (parallel E-W gondola runs, service cluster
top-right, checkout bottom-right, entrance bottom-center). Gondola orientation is E-W (horizontal rows),
matching the Florence reference plan.

**Vertical:** RETAIL / SPORTING_GOODS / RUNNING_SPECIALTY
**Market:** KR
**Format:** Inline mall unit, single level

---

## Footprint

```
24,000 mm wide (E-W) × 25,000 mm deep (N-S)
Total: 600 sqm
Origin (0, 0): SW corner (bottom-left when viewed from above)
X: increases eastward →
Y: increases northward ↑
Entrance: south wall (bottom), center
Checkout: bottom-right
Stockroom: rear strip (north)
```

---

## Zone Summary

| Code | Name | Type | x1 | y1 | x2 | y2 |
|------|------|------|----|----|----|-----|
| Z-01 | Entrance | entry_exit | 4000 | 0 | 16000 | 3000 |
| Z-02 | Checkout Area | checkout | 17000 | 0 | 24000 | 4000 |
| Z-03 | Non-Retail Left Strip | region | 0 | 3000 | 3000 | 22000 |
| Z-04 | Main Sales Floor | region | 3000 | 3000 | 17000 | 22000 |
| Z-05 | Stockroom | region | 0 | 22000 | 24000 | 25000 |
| Z-06 | GPS & Accessories | region | 3000 | 15000 | 8000 | 22000 |
| Z-07 | Accessories Wall | region | 8000 | 15000 | 11000 | 22000 |
| Z-08 | Footwear Bench | region | 14000 | 15000 | 17000 | 18000 |
| Z-09 | Gait Analysis | region | 11000 | 15000 | 14000 | 19000 |
| Z-10 | Fitting Rooms | region | 17000 | 15000 | 24000 | 22000 |
| Z-11 | Service Cluster | region | 17000 | 4000 | 24000 | 15000 |

---

## Zones — Detail

All polygons are rectangular (x1,y1) → (x2,y2) in mm.

### Z-01 — Entrance / Decompression
```
type:        entry_exit
rect:        (4000,0) → (16000,3000)
area:        ~36 sqm
description: EAS gate at center (x=8000–12000, y=600). Decompression zone.
```

### Z-02 — Checkout Area
```
type:        checkout
rect:        (17000,0) → (24000,4000)
area:        ~28 sqm
description: 2 checkout counters + impulse rack. Mirrors Florence RETROCASSE bottom-right.
```

### Z-03 — Non-Retail Left Strip
```
type:        region
rect:        (0,3000) → (3000,22000)
area:        ~57 sqm
description: Staff receiving, utilities, non-customer area. Left perimeter.
```

### Z-04 — Main Sales Floor
```
type:        region
rect:        (3000,3000) → (17000,22000)
area:        ~266 sqm
description: Primary gondola area. 8 E-W gondola rows, each with 7 front + 7 back units.
             Perimeter shelving on east and west sales walls.
```

### Z-05 — Stockroom
```
type:        region
rect:        (0,22000) → (24000,25000)
area:        ~72 sqm
description: Rear strip. Receiving, stock, staff break. Not customer-accessible.
```

### Z-06 — GPS & Accessories
```
type:        region
rect:        (3000,15000) → (8000,22000)
area:        ~35 sqm
description: GPS watches (Garmin, Suunto, Polar, Kiprun GPS), HRM, lights. Display cases + wall.
             Primary LP target (high-value EAS-tagged items).
```

### Z-07 — Accessories Wall
```
type:        region
rect:        (8000,15000) → (11000,22000)
area:        ~21 sqm
description: Wall-mounted accessories: belts, lights, HRM straps, socks, hydration.
```

### Z-08 — Footwear Bench (try_on_zone: footwear_bench)
```
type:        try_on_zone / footwear_bench
rect:        (14000,15000) → (17000,18000)
area:        ~9 sqm
description: Open seating bench for shoe try-on. Customers sit, try multiple sizes.
```

### Z-09 — Gait Analysis (try_on_zone: equipment_test)
```
type:        try_on_zone / equipment_test
rect:        (11000,15000) → (14000,19000)
area:        ~12 sqm
description: 2 treadmills for complimentary gait analysis. Staff-assisted.
             Drives shoe conversion; key scenario anchor.
```

### Z-10 — Fitting Rooms (try_on_zone: apparel_room)
```
type:        try_on_zone / apparel_room
rect:        (17000,15000) → (24000,22000)
area:        ~49 sqm
description: 4 fitting stalls + service counter. Mirrors Florence service cluster top-right.
```

### Z-11 — Service Cluster (east upper)
```
type:        region
rect:        (17000,4000) → (24000,15000)
area:        ~77 sqm
description: Staff service, returns desk, waiting area (east upper corridor).
```

---

## Fixtures

All fixtures are rectangular zones (zone_type = fixture). Coordinates in mm.

### Gondola Rows — Front Units (E-W orientation, 2000mm wide × 1200mm deep)

Gondola rows are stacked N-S at 1800mm spacing. Each row has 7 front units facing south.
Row y positions (south face): 4000, 5800, 7600, 9400, 11200, 13000, 14800, 16600.
Unit depth: 1200mm south (y_top = row_y + 1200).

| ID | Name | x1 | y1 | x2 | y2 |
|----|------|----|----|----|-----|
| GF-R1-U1 | Gondola R1 Front U1 | 3000 | 4000 | 5000 | 5200 |
| GF-R1-U2 | Gondola R1 Front U2 | 5000 | 4000 | 7000 | 5200 |
| GF-R1-U3 | Gondola R1 Front U3 | 7000 | 4000 | 9000 | 5200 |
| GF-R1-U4 | Gondola R1 Front U4 | 9000 | 4000 | 11000 | 5200 |
| GF-R1-U5 | Gondola R1 Front U5 | 11000 | 4000 | 13000 | 5200 |
| GF-R1-U6 | Gondola R1 Front U6 | 13000 | 4000 | 15000 | 5200 |
| GF-R1-U7 | Gondola R1 Front U7 | 15000 | 4000 | 17000 | 5200 |
| GF-R2-U1 | Gondola R2 Front U1 | 3000 | 5800 | 5000 | 7000 |
| GF-R2-U2 | Gondola R2 Front U2 | 5000 | 5800 | 7000 | 7000 |
| GF-R2-U3 | Gondola R2 Front U3 | 7000 | 5800 | 9000 | 7000 |
| GF-R2-U4 | Gondola R2 Front U4 | 9000 | 5800 | 11000 | 7000 |
| GF-R2-U5 | Gondola R2 Front U5 | 11000 | 5800 | 13000 | 7000 |
| GF-R2-U6 | Gondola R2 Front U6 | 13000 | 5800 | 15000 | 7000 |
| GF-R2-U7 | Gondola R2 Front U7 | 15000 | 5800 | 17000 | 7000 |
| GF-R3-U1 | Gondola R3 Front U1 | 3000 | 7600 | 5000 | 8800 |
| GF-R3-U2 | Gondola R3 Front U2 | 5000 | 7600 | 7000 | 8800 |
| GF-R3-U3 | Gondola R3 Front U3 | 7000 | 7600 | 9000 | 8800 |
| GF-R3-U4 | Gondola R3 Front U4 | 9000 | 7600 | 11000 | 8800 |
| GF-R3-U5 | Gondola R3 Front U5 | 11000 | 7600 | 13000 | 8800 |
| GF-R3-U6 | Gondola R3 Front U6 | 13000 | 7600 | 15000 | 8800 |
| GF-R3-U7 | Gondola R3 Front U7 | 15000 | 7600 | 17000 | 8800 |
| GF-R4-U1 | Gondola R4 Front U1 | 3000 | 9400 | 5000 | 10600 |
| GF-R4-U2 | Gondola R4 Front U2 | 5000 | 9400 | 7000 | 10600 |
| GF-R4-U3 | Gondola R4 Front U3 | 7000 | 9400 | 9000 | 10600 |
| GF-R4-U4 | Gondola R4 Front U4 | 9000 | 9400 | 11000 | 10600 |
| GF-R4-U5 | Gondola R4 Front U5 | 11000 | 9400 | 13000 | 10600 |
| GF-R4-U6 | Gondola R4 Front U6 | 13000 | 9400 | 15000 | 10600 |
| GF-R4-U7 | Gondola R4 Front U7 | 15000 | 9400 | 17000 | 10600 |
| GF-R5-U1 | Gondola R5 Front U1 | 3000 | 11200 | 5000 | 12400 |
| GF-R5-U2 | Gondola R5 Front U2 | 5000 | 11200 | 7000 | 12400 |
| GF-R5-U3 | Gondola R5 Front U3 | 7000 | 11200 | 9000 | 12400 |
| GF-R5-U4 | Gondola R5 Front U4 | 9000 | 11200 | 11000 | 12400 |
| GF-R5-U5 | Gondola R5 Front U5 | 11000 | 11200 | 13000 | 12400 |
| GF-R5-U6 | Gondola R5 Front U6 | 13000 | 11200 | 15000 | 12400 |
| GF-R5-U7 | Gondola R5 Front U7 | 15000 | 11200 | 17000 | 12400 |
| GF-R6-U1 | Gondola R6 Front U1 | 3000 | 13000 | 5000 | 14200 |
| GF-R6-U2 | Gondola R6 Front U2 | 5000 | 13000 | 7000 | 14200 |
| GF-R6-U3 | Gondola R6 Front U3 | 7000 | 13000 | 9000 | 14200 |
| GF-R6-U4 | Gondola R6 Front U4 | 9000 | 13000 | 11000 | 14200 |
| GF-R6-U5 | Gondola R6 Front U5 | 11000 | 13000 | 13000 | 14200 |
| GF-R6-U6 | Gondola R6 Front U6 | 13000 | 13000 | 15000 | 14200 |
| GF-R6-U7 | Gondola R6 Front U7 | 15000 | 13000 | 17000 | 14200 |
| GF-R7-U1 | Gondola R7 Front U1 | 3000 | 14800 | 5000 | 16000 |
| GF-R7-U2 | Gondola R7 Front U2 | 5000 | 14800 | 7000 | 16000 |
| GF-R7-U3 | Gondola R7 Front U3 | 7000 | 14800 | 9000 | 16000 |
| GF-R7-U4 | Gondola R7 Front U4 | 9000 | 14800 | 11000 | 16000 |
| GF-R7-U5 | Gondola R7 Front U5 | 11000 | 14800 | 13000 | 16000 |
| GF-R7-U6 | Gondola R7 Front U6 | 13000 | 14800 | 15000 | 16000 |
| GF-R7-U7 | Gondola R7 Front U7 | 15000 | 14800 | 17000 | 16000 |
| GF-R8-U1 | Gondola R8 Front U1 | 3000 | 16600 | 5000 | 17800 |
| GF-R8-U2 | Gondola R8 Front U2 | 5000 | 16600 | 7000 | 17800 |
| GF-R8-U3 | Gondola R8 Front U3 | 7000 | 16600 | 9000 | 17800 |
| GF-R8-U4 | Gondola R8 Front U4 | 9000 | 16600 | 11000 | 17800 |
| GF-R8-U5 | Gondola R8 Front U5 | 11000 | 16600 | 13000 | 17800 |
| GF-R8-U6 | Gondola R8 Front U6 | 13000 | 16600 | 15000 | 17800 |
| GF-R8-U7 | Gondola R8 Front U7 | 15000 | 16600 | 17000 | 17800 |

### Gondola Rows — Back Units (back-to-back pair, facing north, 1200mm deep)

Each front unit has a back unit at y_bottom = row_y - 1200 (except where row_y - 1200 < 3000).

| ID | Name | x1 | y1 | x2 | y2 |
|----|------|----|----|----|-----|
| GB-R1-U1 | Gondola R1 Back U1 | 3000 | 2800 | 5000 | 4000 |
| GB-R1-U2 | Gondola R1 Back U2 | 5000 | 2800 | 7000 | 4000 |
| GB-R1-U3 | Gondola R1 Back U3 | 7000 | 2800 | 9000 | 4000 |
| GB-R1-U4 | Gondola R1 Back U4 | 9000 | 2800 | 11000 | 4000 |
| GB-R1-U5 | Gondola R1 Back U5 | 11000 | 2800 | 13000 | 4000 |
| GB-R1-U6 | Gondola R1 Back U6 | 13000 | 2800 | 15000 | 4000 |
| GB-R1-U7 | Gondola R1 Back U7 | 15000 | 2800 | 17000 | 4000 |
| GB-R2-U1 | Gondola R2 Back U1 | 3000 | 4600 | 5000 | 5800 |
| GB-R2-U2 | Gondola R2 Back U2 | 5000 | 4600 | 7000 | 5800 |
| GB-R2-U3 | Gondola R2 Back U3 | 7000 | 4600 | 9000 | 5800 |
| GB-R2-U4 | Gondola R2 Back U4 | 9000 | 4600 | 11000 | 5800 |
| GB-R2-U5 | Gondola R2 Back U5 | 11000 | 4600 | 13000 | 5800 |
| GB-R2-U6 | Gondola R2 Back U6 | 13000 | 4600 | 15000 | 5800 |
| GB-R2-U7 | Gondola R2 Back U7 | 15000 | 4600 | 17000 | 5800 |
| GB-R3-U1 | Gondola R3 Back U1 | 3000 | 6400 | 5000 | 7600 |
| GB-R3-U2 | Gondola R3 Back U2 | 5000 | 6400 | 7000 | 7600 |
| GB-R3-U3 | Gondola R3 Back U3 | 7000 | 6400 | 9000 | 7600 |
| GB-R3-U4 | Gondola R3 Back U4 | 9000 | 6400 | 11000 | 7600 |
| GB-R3-U5 | Gondola R3 Back U5 | 11000 | 6400 | 13000 | 7600 |
| GB-R3-U6 | Gondola R3 Back U6 | 13000 | 6400 | 15000 | 7600 |
| GB-R3-U7 | Gondola R3 Back U7 | 15000 | 6400 | 17000 | 7600 |
| GB-R4-U1 | Gondola R4 Back U1 | 3000 | 8200 | 5000 | 9400 |
| GB-R4-U2 | Gondola R4 Back U2 | 5000 | 8200 | 7000 | 9400 |
| GB-R4-U3 | Gondola R4 Back U3 | 7000 | 8200 | 9000 | 9400 |
| GB-R4-U4 | Gondola R4 Back U4 | 9000 | 8200 | 11000 | 9400 |
| GB-R4-U5 | Gondola R4 Back U5 | 11000 | 8200 | 13000 | 9400 |
| GB-R4-U6 | Gondola R4 Back U6 | 13000 | 8200 | 15000 | 9400 |
| GB-R4-U7 | Gondola R4 Back U7 | 15000 | 8200 | 17000 | 9400 |
| GB-R5-U1 | Gondola R5 Back U1 | 3000 | 10000 | 5000 | 11200 |
| GB-R5-U2 | Gondola R5 Back U2 | 5000 | 10000 | 7000 | 11200 |
| GB-R5-U3 | Gondola R5 Back U3 | 7000 | 10000 | 9000 | 11200 |
| GB-R5-U4 | Gondola R5 Back U4 | 9000 | 10000 | 11000 | 11200 |
| GB-R5-U5 | Gondola R5 Back U5 | 11000 | 10000 | 13000 | 11200 |
| GB-R5-U6 | Gondola R5 Back U6 | 13000 | 10000 | 15000 | 11200 |
| GB-R5-U7 | Gondola R5 Back U7 | 15000 | 10000 | 17000 | 11200 |
| GB-R6-U1 | Gondola R6 Back U1 | 3000 | 11800 | 5000 | 13000 |
| GB-R6-U2 | Gondola R6 Back U2 | 5000 | 11800 | 7000 | 13000 |
| GB-R6-U3 | Gondola R6 Back U3 | 7000 | 11800 | 9000 | 13000 |
| GB-R6-U4 | Gondola R6 Back U4 | 9000 | 11800 | 11000 | 13000 |
| GB-R6-U5 | Gondola R6 Back U5 | 11000 | 11800 | 13000 | 13000 |
| GB-R6-U6 | Gondola R6 Back U6 | 13000 | 11800 | 15000 | 13000 |
| GB-R6-U7 | Gondola R6 Back U7 | 15000 | 11800 | 17000 | 13000 |
| GB-R7-U1 | Gondola R7 Back U1 | 3000 | 13600 | 5000 | 14800 |
| GB-R7-U2 | Gondola R7 Back U2 | 5000 | 13600 | 7000 | 14800 |
| GB-R7-U3 | Gondola R7 Back U3 | 7000 | 13600 | 9000 | 14800 |
| GB-R7-U4 | Gondola R7 Back U4 | 9000 | 13600 | 11000 | 14800 |
| GB-R7-U5 | Gondola R7 Back U5 | 11000 | 13600 | 13000 | 14800 |
| GB-R7-U6 | Gondola R7 Back U6 | 13000 | 13600 | 15000 | 14800 |
| GB-R7-U7 | Gondola R7 Back U7 | 15000 | 13600 | 17000 | 14800 |
| GB-R8-U1 | Gondola R8 Back U1 | 3000 | 15400 | 5000 | 16600 |
| GB-R8-U2 | Gondola R8 Back U2 | 5000 | 15400 | 7000 | 16600 |
| GB-R8-U3 | Gondola R8 Back U3 | 7000 | 15400 | 9000 | 16600 |
| GB-R8-U4 | Gondola R8 Back U4 | 9000 | 15400 | 11000 | 16600 |
| GB-R8-U5 | Gondola R8 Back U5 | 11000 | 15400 | 13000 | 16600 |
| GB-R8-U6 | Gondola R8 Back U6 | 13000 | 15400 | 15000 | 16600 |
| GB-R8-U7 | Gondola R8 Back U7 | 15000 | 15400 | 17000 | 16600 |

### Perimeter Shelving — East Sales Wall (x=16500–17000, 8 bays of 3000mm)

| ID | Name | x1 | y1 | x2 | y2 |
|----|------|----|----|----|-----|
| PE-01 | East Wall Bay 1 | 16500 | 3000 | 17000 | 6000 |
| PE-02 | East Wall Bay 2 | 16500 | 6000 | 17000 | 9000 |
| PE-03 | East Wall Bay 3 | 16500 | 9000 | 17000 | 12000 |
| PE-04 | East Wall Bay 4 | 16500 | 12000 | 17000 | 15000 |
| PE-05 | East Wall Bay 5 | 16500 | 15000 | 17000 | 18000 |
| PE-06 | East Wall Bay 6 | 16500 | 18000 | 17000 | 19000 |
| PE-07 | East Wall Bay 7 | 16500 | 19000 | 17000 | 20000 |
| PE-08 | East Wall Bay 8 | 16500 | 20000 | 17000 | 22000 |

### Perimeter Shelving — West Sales Wall (x=3000–3500, 8 bays of 3000mm)

| ID | Name | x1 | y1 | x2 | y2 |
|----|------|----|----|----|-----|
| PW-01 | West Wall Bay 1 | 3000 | 3000 | 3500 | 6000 |
| PW-02 | West Wall Bay 2 | 3000 | 6000 | 3500 | 9000 |
| PW-03 | West Wall Bay 3 | 3000 | 9000 | 3500 | 12000 |
| PW-04 | West Wall Bay 4 | 3000 | 12000 | 3500 | 15000 |
| PW-05 | West Wall Bay 5 | 3000 | 15000 | 3500 | 18000 |
| PW-06 | West Wall Bay 6 | 3000 | 18000 | 3500 | 19000 |
| PW-07 | West Wall Bay 7 | 3000 | 19000 | 3500 | 20000 |
| PW-08 | West Wall Bay 8 | 3000 | 20000 | 3500 | 22000 |

### GPS Display Cases (Z-06)

| ID | Name | x1 | y1 | x2 | y2 |
|----|------|----|----|----|-----|
| GPS-01 | GPS Display Case 1 | 3200 | 15500 | 5200 | 17000 |
| GPS-02 | GPS Display Case 2 | 5500 | 15500 | 7500 | 17000 |
| GPS-03 | GPS Display Case 3 | 3200 | 17500 | 5200 | 19000 |
| GPS-04 | GPS Display Case 4 | 5500 | 17500 | 7500 | 19000 |
| GPS-05 | GPS Display Case 5 | 3200 | 19500 | 5200 | 21000 |
| GPS-06 | GPS Display Case 6 | 5500 | 19500 | 7500 | 21000 |

### Accessories Wall Fixtures (Z-07)

| ID | Name | x1 | y1 | x2 | y2 |
|----|------|----|----|----|-----|
| ACC-01 | Accessories Bay 1 | 8200 | 15500 | 10500 | 17500 |
| ACC-02 | Accessories Bay 2 | 8200 | 17800 | 10500 | 19800 |
| ACC-03 | Accessories Bay 3 | 8200 | 20000 | 10500 | 21500 |
| ACC-04 | Accessories Impulse | 8200 | 21500 | 10500 | 22000 |

### Specialty — Footwear Bench (Z-08)

| ID | Name | x1 | y1 | x2 | y2 |
|----|------|----|----|----|-----|
| FB-01 | Footwear Bench Seat | 14200 | 15200 | 16800 | 17800 |

### Specialty — Gait Analysis Treadmills (Z-09)

| ID | Name | x1 | y1 | x2 | y2 |
|----|------|----|----|----|-----|
| GA-01 | Treadmill 1 | 11200 | 15300 | 12400 | 18600 |
| GA-02 | Treadmill 2 | 12700 | 15300 | 13800 | 18600 |

### Specialty — Fitting Stalls (Z-10)

| ID | Name | x1 | y1 | x2 | y2 |
|----|------|----|----|----|-----|
| FR-01 | Fitting Stall 1 | 17200 | 15500 | 19500 | 17500 |
| FR-02 | Fitting Stall 2 | 19700 | 15500 | 22000 | 17500 |
| FR-03 | Fitting Stall 3 | 17200 | 18000 | 19500 | 20000 |
| FR-04 | Fitting Stall 4 | 19700 | 18000 | 22000 | 20000 |
| FR-SC | Fitting Room Service Counter | 17200 | 20500 | 22000 | 22000 |

### Checkout Fixtures (Z-02)

| ID | Name | x1 | y1 | x2 | y2 |
|----|------|----|----|----|-----|
| CO-01 | Checkout Counter 1 | 17500 | 500 | 20000 | 2000 |
| CO-02 | Checkout Counter 2 | 20500 | 500 | 23000 | 2000 |
| CO-IR | Impulse Rack | 17000 | 2500 | 24000 | 3200 |

---

## Fixture Count Summary

| Category | Count |
|----------|-------|
| Gondola Front (8 rows × 7 units) | 56 |
| Gondola Back (8 rows × 7 units) | 56 |
| East Wall Perimeter Bays | 8 |
| West Wall Perimeter Bays | 8 |
| GPS Display Cases | 6 |
| Accessories Wall | 4 |
| Footwear Bench | 1 |
| Gait Analysis Treadmills | 2 |
| Fitting Stalls + Service Counter | 5 |
| Checkout Counters + Impulse | 3 |
| **Total fixture zones** | **149** |

**Total zone rows:** 11 area/try-on zones + 149 fixture zones = **160**
**Total try_on_zone rows:** 3 (Footwear Bench, Gait Analysis, Fitting Rooms)

---

## Crossing Slices (EAS / Traffic gates)

| ID | Name | y | x_start | x_end | Direction |
|----|------|---|---------|-------|-----------|
| CS-01 | Main Entrance Gate | 600 | 8000 | 12000 | left=exit, right=entry |

---

## Sensors (planned placement)

| Type | Location | Coverage |
|------|----------|----------|
| Xovis 3D camera | Ceiling center-south (10000, 8000) | ~14m radius — covers gondola rows 1-5 |
| Xovis 3D camera | Ceiling center-north (10000, 16000) | ~14m radius — covers gondola rows 6-8 + specialty |
| Xovis 3D camera | Ceiling east (20000, 10000) | ~10m radius — covers service cluster + checkout |
| RFID overhead | Z-06 ceiling | GPS display cases GPS-01..GPS-06 |
| EAS gate | Z-01 CS-01 | Main entrance |

---

## Coordinate Diagram (ASCII, not to scale)

```
0              12000        17000      24000
│                │            │           │
25000 ├──────────────────────────────────────┤ N (rear)
      │           STOCKROOM (Z-05)           │
22000 ├──────┬─────────┬──────┬──────────────┤
      │ NON- │ GPS &   │ ACCS │FITTING ROOMS │
      │ RTAIL│ ACCESS  │ WALL │   (Z-10)     │
      │      │ (Z-06)  │(Z-07)│              │
      │(Z-03)├─────────┴──────┤(17000,22000) │
      │      │  GAIT | FTWEAR │ SERVICE (Z11)│
15000 │      │  (Z-09)|(Z-08) │              │
      │      ├────────────────┤              │
      │      │  MAIN SALES FLOOR (Z-04)      │
      │      │  8 × E-W GONDOLA ROWS         │
      │      │  (gondola runs x:3000-17000)  │
 3000 ├──────┴────────────────┴──────────────┤
      │  ENTRANCE (Z-01)      │ CHECKOUT Z-02│
 0    └──────────────────────────────────────┘ S (front)
```

---

## Design Notes

- **LP scenario anchor:** GPS watches in Z-06 (GPS-01..GPS-06) are EAS-tagged high-value items. Shoplift path exits past CS-01 without purchase — alarm fires.
- **Conversion scenario anchor:** Gait analysis in Z-09 drives shoe conversion via gondola rows.
- **Try-on conversion anchor:** Actor enters Z-10 with items → exits with fewer → `tryOnZoneSessionClose` produces `itemsLost` analytics.
- **Gondola orientation:** E-W (horizontal runs x=3000 to x=17000), rows stacked N-S. Matches Florence reference plan.
- **Coordinate origin:** M8TRX SRF origin (0,0) maps to SW corner. Entrance at south wall.
