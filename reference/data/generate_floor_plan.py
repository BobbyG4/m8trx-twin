#!/usr/bin/env python3
"""
Generate SVG floor plan for decathlon-running-medium from STORE-LAYOUT.md coordinates.
Store: 24m × 25m (24000mm × 25000mm), 600 sqm.
Output: 1080px wide, scale to fit.
"""

import uuid

# ── Canvas / scale ────────────────────────────────────────────────────────────
STORE_W_MM = 24000
STORE_H_MM = 25000
OUT_W_PX   = 1080
PAD        = 44
DRAW_W     = OUT_W_PX - PAD * 2          # usable drawing width in px
SCALE      = DRAW_W / STORE_W_MM         # mm → px
DRAW_H     = int(STORE_H_MM * SCALE)
OUT_H_PX   = DRAW_H + PAD * 2

def mm(v):   return v * SCALE
def rx(x):   return PAD + mm(x)
def ry(y):   return PAD + (DRAW_H - mm(y))   # flip Y (SVG Y increases downward)


# ── Zones (background fills) ──────────────────────────────────────────────────
# (label, fill_color, x1, y1, x2, y2)
ZONES = [
    ("Entrance",          "#a5d6a7", 4000,  0,     16000, 3000),
    ("Checkout",          "#80cbc4", 17000, 0,     24000, 4000),
    ("Non-Retail Strip",  "#bcaaa4", 0,     3000,  3000,  22000),
    ("Main Sales Floor",  "#e3f2fd", 3000,  3000,  17000, 22000),
    ("Stockroom",         "#d7ccc8", 0,     22000, 24000, 25000),
    ("GPS & Access.",     "#f3e5f5", 3000,  15000, 8000,  22000),
    ("Accessories",       "#ede7f6", 8000,  15000, 11000, 22000),
    ("Footwear Bench",    "#ffe0b2", 14000, 15000, 17000, 18000),
    ("Gait Analysis",     "#e8eaf6", 11000, 15000, 14000, 19000),
    ("Fitting Rooms",     "#fce4ec", 17000, 15000, 24000, 22000),
    ("Service Cluster",   "#f1f8e9", 17000, 4000,  24000, 15000),
]

# ── Gondola fixtures (generated same logic as seed_store.py) ─────────────────
ROW_Y_POSITIONS = [4000, 5800, 7600, 9400, 11200, 13000, 14800, 16600]
UNIT_X_STARTS   = [3000, 5000, 7000, 9000, 11000, 13000, 15000]
UNIT_WIDTH       = 2000
GONDOLA_DEPTH    = 1200

GONDOLA_FRONT = []
GONDOLA_BACK  = []
for ri, row_y in enumerate(ROW_Y_POSITIONS, start=1):
    for ui, x1 in enumerate(UNIT_X_STARTS, start=1):
        x2 = x1 + UNIT_WIDTH
        GONDOLA_FRONT.append((x1, row_y, x2, row_y + GONDOLA_DEPTH))
        y_back_bottom = max(row_y - GONDOLA_DEPTH, 3000)
        GONDOLA_BACK.append((x1, y_back_bottom, x2, row_y))

# ── Perimeter shelving ────────────────────────────────────────────────────────
WALL_BAYS_Y = [
    (3000, 6000), (6000, 9000), (9000, 12000), (12000, 15000),
    (15000, 18000), (18000, 19000), (19000, 20000), (20000, 22000),
]
EAST_WALLS = [(16500, y1, 17000, y2) for y1, y2 in WALL_BAYS_Y]
WEST_WALLS = [(3000,  y1, 3500,  y2) for y1, y2 in WALL_BAYS_Y]

# ── Specialty fixtures ────────────────────────────────────────────────────────
GPS_CASES = [
    (3200, 15500, 5200, 17000), (5500, 15500, 7500, 17000),
    (3200, 17500, 5200, 19000), (5500, 17500, 7500, 19000),
    (3200, 19500, 5200, 21000), (5500, 19500, 7500, 21000),
]
ACCESSORY_FIXTURES = [
    (8200, 15500, 10500, 17500), (8200, 17800, 10500, 19800),
    (8200, 20000, 10500, 21500), (8200, 21500, 10500, 22000),
]
TREADMILLS = [
    (11200, 15300, 12400, 18600),
    (12700, 15300, 13800, 18600),
]
FITTING_STALLS = [
    (17200, 15500, 19500, 17500), (19700, 15500, 22000, 17500),
    (17200, 18000, 19500, 20000), (19700, 18000, 22000, 20000),
    (17200, 20500, 22000, 22000),  # service counter
]
CHECKOUT_FIXTURES = [
    (17500, 500,  20000, 2000),
    (20500, 500,  23000, 2000),
    (17000, 2500, 24000, 3200),   # impulse rack
]

EAS_Y  = 600
EAS_X1 = 8000
EAS_X2 = 12000

# ── Build SVG ─────────────────────────────────────────────────────────────────
lines = []
lines.append(
    f'<svg xmlns="http://www.w3.org/2000/svg" '
    f'width="{OUT_W_PX}" height="{OUT_H_PX}" '
    f'font-family="Inter,Arial,sans-serif">'
)
# Dark background
lines.append(f'<rect width="{OUT_W_PX}" height="{OUT_H_PX}" fill="#111827"/>')
# Store boundary
lines.append(
    f'<rect x="{PAD}" y="{PAD}" width="{DRAW_W}" height="{DRAW_H}" '
    f'fill="#1e293b" stroke="#475569" stroke-width="2"/>'
)

# ── Zone fills ────────────────────────────────────────────────────────────────
for label, fill, x1, y1, x2, y2 in ZONES:
    sx, sy = rx(x1), ry(y2)
    sw, sh = mm(x2 - x1), mm(y2 - y1)
    lines.append(
        f'<rect x="{sx:.1f}" y="{sy:.1f}" width="{sw:.1f}" height="{sh:.1f}" '
        f'fill="{fill}" fill-opacity="0.14" stroke="{fill}" stroke-opacity="0.5" stroke-width="1"/>'
    )
    cx = sx + sw / 2
    cy = sy + sh / 2
    lines.append(
        f'<text x="{cx:.1f}" y="{cy:.1f}" text-anchor="middle" dominant-baseline="middle" '
        f'font-size="7" fill="{fill}" fill-opacity="0.85">{label}</text>'
    )

# ── Gondola front units (blue-grey) ───────────────────────────────────────────
for x1, y1, x2, y2 in GONDOLA_FRONT:
    sx, sy = rx(x1), ry(y2)
    sw, sh = mm(x2 - x1), mm(y2 - y1)
    lines.append(
        f'<rect x="{sx:.1f}" y="{sy:.1f}" width="{sw:.1f}" height="{sh:.1f}" '
        f'fill="#4fc3f7" fill-opacity="0.45" rx="1"/>'
    )

# ── Gondola back units (slightly darker) ─────────────────────────────────────
for x1, y1, x2, y2 in GONDOLA_BACK:
    sx, sy = rx(x1), ry(y2)
    sw, sh = mm(x2 - x1), mm(y2 - y1)
    lines.append(
        f'<rect x="{sx:.1f}" y="{sy:.1f}" width="{sw:.1f}" height="{sh:.1f}" '
        f'fill="#0288d1" fill-opacity="0.40" rx="1"/>'
    )

# ── Gondola aisle labels (one per row, centre) ────────────────────────────────
for ri, row_y in enumerate(ROW_Y_POSITIONS, start=1):
    cx = rx(10000)  # center of gondola run
    # label sits in the aisle gap between back of this row and front of previous
    cy = ry(row_y + GONDOLA_DEPTH / 2)
    lines.append(
        f'<text x="{cx:.1f}" y="{cy:.1f}" text-anchor="middle" dominant-baseline="middle" '
        f'font-size="5.5" fill="#90caf9" fill-opacity="0.7">R{ri}</text>'
    )

# ── Perimeter shelving (east wall — orange) ───────────────────────────────────
for x1, y1, x2, y2 in EAST_WALLS:
    sx, sy = rx(x1), ry(y2)
    sw, sh = mm(x2 - x1), mm(y2 - y1)
    lines.append(
        f'<rect x="{sx:.1f}" y="{sy:.1f}" width="{sw:.1f}" height="{sh:.1f}" '
        f'fill="#ff9800" fill-opacity="0.55" rx="1"/>'
    )

# ── Perimeter shelving (west wall — teal) ────────────────────────────────────
for x1, y1, x2, y2 in WEST_WALLS:
    sx, sy = rx(x1), ry(y2)
    sw, sh = mm(x2 - x1), mm(y2 - y1)
    lines.append(
        f'<rect x="{sx:.1f}" y="{sy:.1f}" width="{sw:.1f}" height="{sh:.1f}" '
        f'fill="#26c6da" fill-opacity="0.55" rx="1"/>'
    )

# ── GPS display cases (purple) ────────────────────────────────────────────────
for x1, y1, x2, y2 in GPS_CASES:
    sx, sy = rx(x1), ry(y2)
    sw, sh = mm(x2 - x1), mm(y2 - y1)
    lines.append(
        f'<rect x="{sx:.1f}" y="{sy:.1f}" width="{sw:.1f}" height="{sh:.1f}" '
        f'fill="#ab47bc" fill-opacity="0.65" rx="2"/>'
    )

# ── Accessories (lavender) ────────────────────────────────────────────────────
for x1, y1, x2, y2 in ACCESSORY_FIXTURES:
    sx, sy = rx(x1), ry(y2)
    sw, sh = mm(x2 - x1), mm(y2 - y1)
    lines.append(
        f'<rect x="{sx:.1f}" y="{sy:.1f}" width="{sw:.1f}" height="{sh:.1f}" '
        f'fill="#7e57c2" fill-opacity="0.55" rx="2"/>'
    )

# ── Treadmills (indigo) ───────────────────────────────────────────────────────
for x1, y1, x2, y2 in TREADMILLS:
    sx, sy = rx(x1), ry(y2)
    sw, sh = mm(x2 - x1), mm(y2 - y1)
    lines.append(
        f'<rect x="{sx:.1f}" y="{sy:.1f}" width="{sw:.1f}" height="{sh:.1f}" '
        f'fill="#5c6bc0" fill-opacity="0.7" rx="2"/>'
    )
    cx = sx + sw / 2
    cy = sy + sh / 2
    lines.append(
        f'<text x="{cx:.1f}" y="{cy:.1f}" text-anchor="middle" dominant-baseline="middle" '
        f'font-size="5" fill="#c5cae9">TRDML</text>'
    )

# ── Fitting stalls (pink) ─────────────────────────────────────────────────────
for x1, y1, x2, y2 in FITTING_STALLS:
    sx, sy = rx(x1), ry(y2)
    sw, sh = mm(x2 - x1), mm(y2 - y1)
    lines.append(
        f'<rect x="{sx:.1f}" y="{sy:.1f}" width="{sw:.1f}" height="{sh:.1f}" '
        f'fill="#ec407a" fill-opacity="0.55" rx="2"/>'
    )

# ── Checkout counters (green) ─────────────────────────────────────────────────
for x1, y1, x2, y2 in CHECKOUT_FIXTURES:
    sx, sy = rx(x1), ry(y2)
    sw, sh = mm(x2 - x1), mm(y2 - y1)
    lines.append(
        f'<rect x="{sx:.1f}" y="{sy:.1f}" width="{sw:.1f}" height="{sh:.1f}" '
        f'fill="#26a69a" fill-opacity="0.7" rx="2"/>'
    )

# ── EAS gate ──────────────────────────────────────────────────────────────────
lines.append(
    f'<line x1="{rx(EAS_X1):.1f}" y1="{ry(EAS_Y):.1f}" '
    f'x2="{rx(EAS_X2):.1f}" y2="{ry(EAS_Y):.1f}" '
    f'stroke="#ef5350" stroke-width="2.5" stroke-dasharray="5,3"/>'
)
lines.append(
    f'<text x="{rx((EAS_X1+EAS_X2)//2):.1f}" y="{ry(EAS_Y)-5:.1f}" '
    f'text-anchor="middle" font-size="7" fill="#ef5350" font-weight="bold">EAS GATE</text>'
)

# ── Legend ────────────────────────────────────────────────────────────────────
legend = [
    ("#4fc3f7", "Gondola front"),
    ("#0288d1", "Gondola back"),
    ("#ff9800", "E wall shelving"),
    ("#26c6da", "W wall shelving"),
    ("#ab47bc", "GPS cases"),
    ("#ec407a", "Fitting stalls"),
    ("#5c6bc0", "Treadmills"),
    ("#26a69a", "Checkout"),
]
lx = PAD + DRAW_W + 6
ly = PAD + 16
for fill, label in legend:
    lines.append(f'<rect x="{lx}" y="{ly-8}" width="10" height="8" fill="{fill}" fill-opacity="0.7" rx="1"/>')
    lines.append(f'<text x="{lx+13}" y="{ly}" font-size="7" fill="#9ca3af">{label}</text>')
    ly += 14

# ── Compass / scale bar ───────────────────────────────────────────────────────
lines.append(f'<text x="{PAD+6}" y="{PAD+14}" font-size="10" fill="#94a3b8" font-weight="bold">N↑</text>')
# Scale bar: 5m = 5000mm
bar_mm = 5000
bar_px = mm(bar_mm)
bx = PAD + 10
by = OUT_H_PX - 14
lines.append(f'<line x1="{bx}" y1="{by}" x2="{bx+bar_px:.1f}" y2="{by}" stroke="#64748b" stroke-width="2"/>')
lines.append(f'<line x1="{bx}" y1="{by-4}" x2="{bx}" y2="{by+4}" stroke="#64748b" stroke-width="1.5"/>')
lines.append(f'<line x1="{bx+bar_px:.1f}" y1="{by-4}" x2="{bx+bar_px:.1f}" y2="{by+4}" stroke="#64748b" stroke-width="1.5"/>')
lines.append(f'<text x="{bx+bar_px/2:.1f}" y="{by-6}" text-anchor="middle" font-size="7" fill="#64748b">5m</text>')

# ── Title ─────────────────────────────────────────────────────────────────────
lines.append(
    f'<text x="{OUT_W_PX//2}" y="{PAD-16}" '
    f'text-anchor="middle" font-size="12" fill="#e2e8f0" font-weight="bold">'
    f'Decathlon Running — 600 sqm (24m × 25m) — E-W Gondola Layout</text>'
)

# ── Counts annotation ─────────────────────────────────────────────────────────
gondola_total = len(GONDOLA_FRONT) + len(GONDOLA_BACK)
fixture_total = gondola_total + len(EAST_WALLS) + len(WEST_WALLS) + len(GPS_CASES) + \
                len(ACCESSORY_FIXTURES) + len(TREADMILLS) + len(FITTING_STALLS) + len(CHECKOUT_FIXTURES)
lines.append(
    f'<text x="{OUT_W_PX//2}" y="{OUT_H_PX - 8}" '
    f'text-anchor="middle" font-size="7" fill="#64748b">'
    f'{gondola_total} gondola units · {fixture_total} total fixture zones · '
    f'8 E-W rows × 7 units × 2 (front+back)</text>'
)

lines.append('</svg>')

OUT_PATH = "reference/data/floor-plans/decathlon-running-medium.svg"
with open(OUT_PATH, "w") as f:
    f.write("\n".join(lines))
print(f"Written: {OUT_PATH}  ({OUT_W_PX}×{OUT_H_PX}px)")
print(f"Gondola units: {gondola_total}  |  Total fixture zones: {fixture_total}")
