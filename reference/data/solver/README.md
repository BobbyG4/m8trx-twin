# Solver Test-Space Matrix

14 schema-true spaces for core's anchor-pattern solver (skeleton-first decoration: corridor
zigzag · junction star · plaza template · door-pair), covering environments the office cannot
physically stage.

**Brief:** `m8trx-shared/status/briefs/BRIEF-TWIN-SOLVER-MATRIX-GENERATOR-2026-08-05.md`
**Results append (manifest of record):** the `## RESULTS (2026-08-05)` section of that brief.

| file | what |
|---|---|
| `solver-matrix.json` | the deliverable — all 14 spaces, geometry in SRF **metres** |
| `svg/*.svg` | one rendering per space (eyeball check; not an input) |
| `../../../scripts/build_solver_matrix.py` | generator — deterministic off `sha256(space name)` |
| `../../../scripts/verify_solver_matrix.py` | independent verifier + SVG renderer |

```bash
python3 scripts/build_solver_matrix.py     # regenerate (byte-identical every run)
python3 scripts/verify_solver_matrix.py    # re-derive + check against the brief's §2/§3
```

## ⛔ Nothing here exists on mother

**No rows were created.** Twin has no sanctioned write path for spatial containers — measured
2026-08-05: every spatial create returns `403 CONNECT_NOT_EXPOSED`, and `/spatial/identity` is
read-only. Bob's ruling the same day: *"do NOT write to the DB at all"* — twin emits, **core
ingests into the quarantine site on its side.**

So `site.slug: "solver-test-facility"` is a **requested** identity, not an allocated one. There
are no `site_id` / `space_id` / `zone_id` values in this file because none have been minted.
Whoever ingests owns id allocation and should record the mapping back onto the brief.

## Reading the geometry

- **Units are metres**, floats, 3 dp. (Twin's other spatial artifact, `chain/stores/*/layout.json`,
  is **millimetres** — do not mix them.)
- **Frame:** per-space SRF, SW origin `(0,0)`, `+x` east, `+y` north, Z=0 implied. Spaces are
  **not** positioned relative to each other — site assembly (`srf_to_site_transform`) is Pass 2
  and dormant on mother, so each space is an independent frame.
- **Boundary:** `boundary.ring` — a closed CCW point list, first point repeated last. **May be
  non-convex** (`SOLVER-NONCONVEX-01` has one reflex vertex). Do not assume an AABB; `bbox_m` is
  provided as a convenience and is *not* the boundary.
- **Fixtures:** `center{x,y}` + `dims{w,d}` + `rotation_deg` (CCW about the centre), **plus** the
  derived closed corner `ring`. The two agree to 2e-3 m and the verifier asserts it — read
  whichever you prefer, but read only one.
- **Zones** are areas *inside* a space (`region`/`path`/`entry_exit`), never sub-spaces. In
  `multi_room`, the rooms are `region` zones.
- **`blocks_line_of_sight` + `height_m`** are on every fixture; racking (4.5 m) and partitions
  (2.6 m) block, benches and end-caps (0.5–1.2 m) do not.

## Doorways (§3.3 — which representation)

`openings[]`, as a **line segment** across a wall gap:

```json
{ "code":"DR-01", "kind":"doorway", "segment":[[2.45,2.2],[3.55,2.2]],
  "width_m":1.1, "connects":["Z-00","R-01"] }
```

This is twin's `crossing_slices` grammar (the EAS-gate form at `build_layout.py:378`) generalised
from axis-aligned `(y, x_start, x_end)` to a free segment. It is **not** `space_connection` —
that column exists in schema but is dormant and never populated on mother — and **not** a zone
row. Because rooms are zones within one space, `connects` names **zone codes**.

Each doorway pierces a real LOS-blocking `wall_partition` fixture pair, so the gap is geometric,
not merely declared: the verifier asserts every opening's midpoint is inside the boundary and
inside **no** fixture. 7 doorways across the two `multi_room` spaces.

## `space_type` — read the caveat before trusting it

`space_type` has no `CREATE TYPE`/CHECK in 7a; it is unratified free text
(`m8trx-shared/reference/dev/SPATIAL-HIERARCHY.md:48`). Every space carries `space_type_status`:

- `existing` — `sales_floor` (live on mother)
- `NEW-provisional` — `warehouse`, `showroom`, `concourse`, `back_office`, minted here for FR-37
  regime variety. **Ratifying or renaming these is BW's call**, not twin's; they are flagged so
  they are adopted deliberately rather than inherited by accident.

## No anchors

Deliberately absent, and the verifier fails the build if any appear. The solver **proposes**
anchors — that is the test.
