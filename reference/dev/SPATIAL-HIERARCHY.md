> **Mirror (snapshot 2026-06-23) of the CANONICAL ruling at**
> `~/IdeaProjects/m8trx-shared/reference/dev/SPATIAL-HIERARCHY.md`.
> Copied into the twin sandbox so twin sessions have it in-bounds. **Canonical source wins** — if they
> diverge, re-sync from m8trx-shared. (Coordinator-owned; do not edit semantics here.)

---

# Spatial Hierarchy — Canonical (settled 2026-06-23)

Authoritative reference for the M8TRX spatial model, to end the recurring site/space/zone/"region" confusion. Grounded in `7a. Data Model.md` (line refs below) + FR-SPATIAL. Companion to `SPATIAL-DATA-FLOW.md` (the write/read pipeline) and `SRF-STABILITY-ARCHITECTURE.md`.

## The hierarchy

```
organization
  └ region          grouping of SITES — management & reporting (NOT a spatial unit)
      └ site
          └ space   MANY per site — the functional areas (Sales Floor, Back Room, Fitting Rooms, Stockroom…)
              └ zone MANY per space — typed + nestable (areas, fixtures, shelves, paths…)
```

- **region → site:** `site.region_id` (nullable). `region` = *"logical grouping of sites for management & reporting"* (7a l.133–149); org-scoped; hierarchical (Country→District→City via `region.parent_id`); the scope axis for Regional Managers (FR-PLAT-11/15). **It is above `site`. It is not a spatial sub-unit of a site.**
- **site → space:** `space.site_id` is a **non-unique FK** (7a l.222). **A site has many spaces by design.**
- **space → zone:** `zone.space_id` (7a l.341); zones nest via `zone.parent_id` (l.342, "shelves in a fixture").

## "Space is many-per-site" was always the spec (not a new idea)

The schema was explicitly designed for N functional-area spaces per site, each with its own SRF, assembled onto one site floor plan — **"Session 14 — Spatial Hierarchy Design"**, encoded directly in the `space` table:
- `srf_to_site_transform` (7a l.~240) — *"user positions spaces on the floor plan; maps this space's SRF coordinates to the site-level coordinate system."*
- `site_frame_anchor_space` — *"TRUE for the space designated as site coordinate origin (its SRF = SiteRF, transform = identity)."*
- `space_connection` (l.609) — the per-site space **adjacency graph** (FR-SPATIAL-26: "Sales Floor → Back Room via west door"; multi-hop routing).
- `space.space_type` / anchor-density regime per space (FR-SPATIAL-37).

**Implication:** an implementation that assumes **one space per site** has silently dropped the entire site-assembly layer. Restoring many-spaces-per-site is **implementing the spec that was skipped — not a patch, not a new concept.** Each space calibrates its own SRF; one space is the site origin; the rest carry a rigid-body transform onto the site frame.

## "region" means THREE different things — do not conflate (this is the trap)

| Use | Where | What it is | Tier |
|---|---|---|---|
| **`region` table** | 7a l.136 | grouping of **sites** for mgmt/reporting + Regional-Manager scope | **above** site |
| **`zone_type = 'region'`** | 7a l.336 | a generic **shaped area-zone within a space** (e.g. "Menswear" on the Sales Floor) — the zone comment literally says *"shaped region within a space"* | **below** space |
| **geographic region** (2b) | `GeoRegions.kt` / Discover | country→continent comparison axis (`countryCodes`); M8 comparative tier | orthogonal (analytics) |

**There is NO "region" tier between `site` and `space`.** Inventing one is the error. Generic areas inside a space → `zone_type='region'`. Site groupings → the `region` table. Geo comparison → `GeoRegions`.

## What this means operationally
- **A site is a building/store.** Its **spaces** are its functional areas (many). A space owns its SRF + anchors. Spaces connect via `space_connection`.
- **A space's contents are zones**, typed: `region` (area), `fixture`, `shelf`, `bin`, `trigger`, `path`, `boundary`, `checkout`. Fixtures are zones (`zone_type='fixture'`); shelves/bins nest under a fixture via `parent_id`. (The M8trxDemo seed's "area-zones / try-on / fixture-zones" are all `zone` rows of different types under a space.)
- **Nothing in the model is missing.** The gap was build (space tier + site assembly never implemented) + the documentation hazard of the overloaded word "region" — which this doc settles.

---

## Twin application (M8trxDemo, Pass 1 — 2026-06-23)

Twin's `build_layout.py` now emits, per retail store, **3 spaces** under `layout.json` `spaces[]`:
- **Sales Floor** (`space_type=sales_floor`) — sport-universe **departments as `zone_type='region'`**, fixtures, `checkout`, entrance `entry_exit` + `eas_gate` crossing, footwear-bench + gait `try_on_zone`s.
- **Back Room** (`space_type=stockroom`) — `receiving_dock` + `backroom_rack` fixtures.
- **Fitting Rooms** (`space_type=fitting_room`) — stalls as `try_on_zone`s.

Each space carries its own SRF (SW-origin local frame). **Pass 1** = structure only; the assembly columns
(`srf_to_site_transform`, `site_frame_anchor_space`, `space_connection`) are emitted **null / dormant**.
**Pass 2** (pending) lights them for the unified site view + cross-space routing. `space_type` values are
**proposed-canonical, pending Backend/Web ratification** (BW owns the enum).
