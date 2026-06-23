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
       "round_rack": "#be185d", "promo_island": "#fb923c",
       "receiving_dock": "#334155", "backroom_rack": "#475569"}
ZONE_FILL = {"try_on_zone": "#fef3c7", "entry_exit": "#dcfce7", "checkout": "#e2e8f0"}
# sport-universe department band tints (soft, distinct per universe) + BOH
DEPT_FILL = {"hike_camp": "#dcfce7", "running": "#dbeafe", "climb": "#ffedd5", "snow": "#cffafe",
             "cycling": "#f3e8ff", "water": "#e0f2fe", "general": "#f1f5f9", "boh": "#e2e8f0"}


def render(layout):
    """Render a store's SPACES (Pass 1 — each its own SRF frame) stacked vertically, labelled."""
    spaces = layout["spaces"]
    sc = 0.05
    pad, gap, labelh = 8, 22, 16
    Wpx = max(sp["footprint_mm"]["width"] for sp in spaces) * sc + 2 * pad
    Hpx = pad + sum(sp["footprint_mm"]["depth"] * sc + labelh + gap for sp in spaces) + 26
    s = [f'<svg viewBox="0 0 {round(Wpx,1)} {round(Hpx,1)}" xmlns="http://www.w3.org/2000/svg" font-family="sans-serif" role="img">']
    s.append(f'<title>{layout["name"]}</title>')
    y = pad
    for sp in spaces:
        fw = sp["footprint_mm"]["width"] * sc
        D_ = sp["footprint_mm"]["depth"]; fh = D_ * sc
        s.append(f'<text x="{pad}" y="{round(y+12,1)}" font-size="11" font-weight="600" fill="#0f172a">'
                 f'{sp["name"]} · {sp["space_type"]} · {sp["footprint_mm"]["width"]}×{D_}mm · {sp["counts"]["zones_total"]} zones</text>')
        y += labelh
        def px(x): return round(pad + x * sc, 1)
        def py(yy): return round(y + (D_ - yy) * sc, 1)
        s.append(f'<rect x="{pad}" y="{round(y,1)}" width="{round(fw,1)}" height="{round(fh,1)}" fill="#f8fafc" stroke="#1e293b" stroke-width="1.5"/>')
        for z in sp["zones"]:                              # area zones first
            if z["zone_type"] == "fixture":
                continue
            r = z["rect_mm"]; dept = z.get("department")
            fill = DEPT_FILL.get(dept, DEPT_FILL["boh"] if sp["space_type"] == "stockroom" else ZONE_FILL.get(z["zone_type"], "#ffffff"))
            s.append(f'<rect x="{px(r["x1"])}" y="{py(r["y2"])}" width="{round((r["x2"]-r["x1"])*sc,1)}" '
                     f'height="{round((r["y2"]-r["y1"])*sc,1)}" fill="{fill}" fill-opacity="0.85" stroke="#cbd5e1" stroke-width="1"/>')
            s.append(f'<text x="{round(px(r["x1"])+3,1)}" y="{round(py(r["y2"])+10,1)}" font-size="7.5" '
                     f'fill="#334155" font-weight="{"600" if dept else "400"}">{z["name"] if dept else z["code"]}</text>')
        for z in sp["zones"]:                              # fixtures on top
            if z["zone_type"] != "fixture":
                continue
            r = z["rect_mm"]; col = COL.get(z["fixture_category"], "#888")
            if z.get("geometry_type") == "circle":
                cx = pad + (r["x1"] + r["x2"]) / 2 * sc; cy = y + (D_ - (r["y1"] + r["y2"]) / 2) * sc
                rad = min(r["x2"] - r["x1"], r["y2"] - r["y1"]) / 2 * sc
                s.append(f'<circle cx="{round(cx,1)}" cy="{round(cy,1)}" r="{round(rad,1)}" fill="{col}" fill-opacity="0.8" stroke="#1e293b" stroke-width="0.4"/>')
            else:
                s.append(f'<rect x="{px(r["x1"])}" y="{py(r["y2"])}" width="{round((r["x2"]-r["x1"])*sc,1)}" '
                         f'height="{round((r["y2"]-r["y1"])*sc,1)}" fill="{col}" fill-opacity="0.72" stroke="#1e293b" stroke-width="0.4"/>')
        y += fh + gap
    c = layout["counts"]
    s.append(f'<text x="{pad}" y="{round(Hpx-14,1)}" font-size="9.5" fill="#0f172a">{layout["store_id"]} · {layout["tier"]} · '
             f'{c["spaces"]} spaces · {c["departments"]} depts · {c["zones_total"]} zones · 0 overlaps/space</text>')
    s.append(f'<text x="{pad}" y="{round(Hpx-3,1)}" font-size="8" fill="#64748b">spaces = independent SRF frames (Pass 1; site-assembly = Pass 2) · '
             f'tinted = depts · blue=gondolas · purple=walls · grey=BOH</text>')
    s.append('</svg>')
    return "\n".join(s)


def render_comparison(layouts):
    """All stores on ONE sheet at a COMMON scale — the SALES FLOOR space per store (the dominant
    area), so footprint sizes are comparable. (Back Room / Fitting are separate SRF frames — Pass 1.)"""
    sf = lambda d: d["spaces"][0]["footprint_mm"]     # spaces[0] = Sales Floor
    layouts = sorted(layouts, key=lambda d: -sf(d)["width"] * sf(d)["depth"])
    maxw = max(sf(d)["width"] for d in layouts)
    maxd = max(sf(d)["depth"] for d in layouts)
    sc = 165 / max(maxw, maxd)                      # common mm→px
    cw, ch, cols = maxw * sc + 24, maxd * sc + 40, 5
    W = cw * cols
    H = ch * ((len(layouts) + cols - 1) // cols)
    s = [f'<svg viewBox="0 0 {round(W,1)} {round(H,1)}" xmlns="http://www.w3.org/2000/svg" font-family="sans-serif" role="img">']
    s.append(f'<title>All 10 store Sales Floors at a common scale</title>')
    for i, d in enumerate(layouts):
        sp0 = d["spaces"][0]
        ox = (i % cols) * cw + 8
        oy = (i // cols) * ch + 8
        fw = sp0["footprint_mm"]["width"] * sc
        fh = sp0["footprint_mm"]["depth"] * sc
        baseY = oy + (maxd * sc - fh)               # bottom-align so sizes read from a common baseline
        s.append(f'<rect x="{round(ox,1)}" y="{round(baseY,1)}" width="{round(fw,1)}" height="{round(fh,1)}" '
                 f'fill="#eef2f7" stroke="#1e293b" stroke-width="1"/>')
        dep = sp0["footprint_mm"]["depth"]
        for z in sp0["zones"]:
            if z["zone_type"] != "fixture":
                continue
            r = z["rect_mm"]; col = COL.get(z["fixture_category"], "#888")
            if z.get("geometry_type") == "circle":
                cx = ox + (r["x1"] + r["x2"]) / 2 * sc
                cy = baseY + (dep - (r["y1"] + r["y2"]) / 2) * sc
                rad = min(r["x2"] - r["x1"], r["y2"] - r["y1"]) / 2 * sc
                s.append(f'<circle cx="{round(cx,1)}" cy="{round(cy,1)}" r="{round(rad,1)}" fill="{col}" fill-opacity="0.85"/>')
            else:
                s.append(f'<rect x="{round(ox + r["x1"]*sc,1)}" y="{round(baseY + (dep-r["y2"])*sc,1)}" '
                         f'width="{round((r["x2"]-r["x1"])*sc,1)}" height="{round((r["y2"]-r["y1"])*sc,1)}" '
                         f'fill="{col}" fill-opacity="0.8"/>')
        c = d["counts"]
        site = d["site_footprint_mm"]
        s.append(f'<text x="{round(ox,1)}" y="{round(oy + maxd*sc + 14,1)}" font-size="9.5" fill="#0f172a">'
                 f'{d["store_id"].replace("dec-","")} · {site["width"]*site["depth"]/1e6:.0f}m²</text>')
        s.append(f'<text x="{round(ox,1)}" y="{round(oy + maxd*sc + 26,1)}" font-size="8" fill="#64748b">'
                 f'{d["tier"]} · {c["spaces"]}sp · {c["gondola_rows"]}×{c["gondola_units"]} · {c["fixture_zones"]}fx</text>')
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
