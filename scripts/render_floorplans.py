#!/usr/bin/env python3
"""
Render each store's layout.json to a scale SVG floor plan (visual QA + docs/marketing artifact).

In : reference/data/chain/stores/<id>/layout.json
Out: reference/data/floor-plans/<id>.svg
"""
import json, os, glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
STORES = os.path.join(ROOT, "reference/data/chain/stores")
OUT = os.path.join(ROOT, "reference/data/floor-plans")

COL = {"gondola_front": "#2563eb", "gondola_back": "#3b82f6", "perimeter_west": "#7c3aed",
       "perimeter_east": "#7c3aed", "gps_case": "#dc2626", "accessories_wall": "#f59e0b",
       "footwear_bench": "#10b981", "gait_treadmill": "#14b8a6", "fitting_stall": "#ec4899",
       "fitting_service": "#f472b6", "checkout": "#64748b", "impulse_rack": "#94a3b8",
       "round_rack": "#be185d", "promo_island": "#fb923c"}
ZONE_FILL = {"try_on_zone": "#fef3c7", "entry_exit": "#dcfce7", "checkout": "#e2e8f0"}


def render(layout):
    W = layout["footprint_mm"]["width"]
    D = layout["footprint_mm"]["depth"]
    sc = 0.05
    def fx(x): return round(x * sc, 1)
    def fy(y): return round((D - y) * sc, 1)          # flip y (store y-up → svg y-down)
    s = [f'<svg viewBox="0 0 {round(W*sc,1)} {round(D*sc+45,1)}" xmlns="http://www.w3.org/2000/svg" font-family="sans-serif" role="img">']
    s.append(f'<title>{layout["name"]} — {layout["counts"]["fixture_zones"]} fixtures, 0 overlaps</title>')
    s.append(f'<rect x="0" y="0" width="{round(W*sc,1)}" height="{round(D*sc,1)}" fill="#f8fafc" stroke="#1e293b" stroke-width="2"/>')
    for z in layout["zones"]:
        if z["zone_type"] == "fixture":
            continue
        r = z["rect_mm"]
        s.append(f'<rect x="{fx(r["x1"])}" y="{fy(r["y2"])}" width="{round((r["x2"]-r["x1"])*sc,1)}" '
                 f'height="{round((r["y2"]-r["y1"])*sc,1)}" fill="{ZONE_FILL.get(z["zone_type"],"#ffffff")}" '
                 f'fill-opacity="0.5" stroke="#cbd5e1" stroke-width="1"/>')
        s.append(f'<text x="{round(fx(r["x1"])+3,1)}" y="{round(fy(r["y2"])+11,1)}" font-size="8" fill="#475569">{z["code"]}</text>')
    for z in layout["zones"]:
        if z["zone_type"] != "fixture":
            continue
        r = z["rect_mm"]; col = COL.get(z["fixture_category"], "#888")
        if z.get("shape") == "circle":
            cx = (r["x1"] + r["x2"]) / 2 * sc
            cy = (D - (r["y1"] + r["y2"]) / 2) * sc
            rad = min(r["x2"] - r["x1"], r["y2"] - r["y1"]) / 2 * sc
            s.append(f'<circle cx="{round(cx,1)}" cy="{round(cy,1)}" r="{round(rad,1)}" '
                     f'fill="{col}" fill-opacity="0.8" stroke="#1e293b" stroke-width="0.4"/>')
        else:
            s.append(f'<rect x="{fx(r["x1"])}" y="{fy(r["y2"])}" width="{round((r["x2"]-r["x1"])*sc,1)}" '
                     f'height="{round((r["y2"]-r["y1"])*sc,1)}" fill="{col}" '
                     f'fill-opacity="0.72" stroke="#1e293b" stroke-width="0.4"/>')
    c = layout["counts"]
    s.append(f'<text x="6" y="{round(D*sc+16,1)}" font-size="11" fill="#0f172a">{layout["store_id"]} · {layout["tier"]} · '
             f'{W}×{D}mm · {c["gondola_rows"]}×{c["gondola_units"]} gondolas · {c["zones_total"]} zones · 0 overlaps</text>')
    s.append(f'<text x="6" y="{round(D*sc+32,1)}" font-size="9" fill="#64748b">blue=gondolas · purple=walls · '
             f'red=GPS · amber=accessories · teal/green=gait+bench · pink=fitting · grey=checkout · entrance=bottom</text>')
    s.append('</svg>')
    return "\n".join(s)


def render_comparison(layouts):
    """All stores on ONE sheet at a COMMON scale (so footprint sizes are comparable), 5×2 grid."""
    layouts = sorted(layouts, key=lambda d: -d["footprint_mm"]["width"] * d["footprint_mm"]["depth"])
    maxw = max(d["footprint_mm"]["width"] for d in layouts)
    maxd = max(d["footprint_mm"]["depth"] for d in layouts)
    sc = 165 / max(maxw, maxd)                      # common mm→px
    cw, ch, cols = maxw * sc + 24, maxd * sc + 40, 5
    W = cw * cols
    H = ch * ((len(layouts) + cols - 1) // cols)
    s = [f'<svg viewBox="0 0 {round(W,1)} {round(H,1)}" xmlns="http://www.w3.org/2000/svg" font-family="sans-serif" role="img">']
    s.append(f'<title>All 10 store footprints at a common scale</title>')
    for i, d in enumerate(layouts):
        ox = (i % cols) * cw + 8
        oy = (i // cols) * ch + 8
        fw = d["footprint_mm"]["width"] * sc
        fh = d["footprint_mm"]["depth"] * sc
        baseY = oy + (maxd * sc - fh)               # bottom-align so sizes read from a common baseline
        s.append(f'<rect x="{round(ox,1)}" y="{round(baseY,1)}" width="{round(fw,1)}" height="{round(fh,1)}" '
                 f'fill="#eef2f7" stroke="#1e293b" stroke-width="1"/>')
        dep = d["footprint_mm"]["depth"]
        for z in d["zones"]:
            if z["zone_type"] != "fixture":
                continue
            r = z["rect_mm"]; col = COL.get(z["fixture_category"], "#888")
            if z.get("shape") == "circle":
                cx = ox + (r["x1"] + r["x2"]) / 2 * sc
                cy = baseY + (dep - (r["y1"] + r["y2"]) / 2) * sc
                rad = min(r["x2"] - r["x1"], r["y2"] - r["y1"]) / 2 * sc
                s.append(f'<circle cx="{round(cx,1)}" cy="{round(cy,1)}" r="{round(rad,1)}" fill="{col}" fill-opacity="0.85"/>')
            else:
                s.append(f'<rect x="{round(ox + r["x1"]*sc,1)}" y="{round(baseY + (dep-r["y2"])*sc,1)}" '
                         f'width="{round((r["x2"]-r["x1"])*sc,1)}" height="{round((r["y2"]-r["y1"])*sc,1)}" '
                         f'fill="{col}" fill-opacity="0.8"/>')
        c = d["counts"]
        s.append(f'<text x="{round(ox,1)}" y="{round(oy + maxd*sc + 14,1)}" font-size="9.5" fill="#0f172a">'
                 f'{d["store_id"].replace("dec-","")} · {d["footprint_mm"]["width"]*d["footprint_mm"]["depth"]/1e6:.0f}m²</text>')
        s.append(f'<text x="{round(ox,1)}" y="{round(oy + maxd*sc + 26,1)}" font-size="8" fill="#64748b">'
                 f'{d["tier"]} · {c["gondola_rows"]}×{c["gondola_units"]} · {c["fixture_zones"]}fx</text>')
    s.append('</svg>')
    return "\n".join(s)


def main():
    os.makedirs(OUT, exist_ok=True)
    layouts = [json.load(open(p, encoding="utf-8")) for p in sorted(glob.glob(os.path.join(STORES, "*/layout.json")))]
    for layout in layouts:
        with open(os.path.join(OUT, f'{layout["store_id"]}.svg'), "w", encoding="utf-8") as f:
            f.write(render(layout))
    with open(os.path.join(OUT, "_comparison.svg"), "w", encoding="utf-8") as f:
        f.write(render_comparison(layouts))
    print(f"rendered {len(layouts)} floor plans + _comparison.svg (common scale) → {OUT}/")


if __name__ == "__main__":
    main()
