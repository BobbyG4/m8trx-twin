# Session 19 — 2026-08-05 — The solver test-space matrix, and the spatial write wall

**Brief:** `m8trx-shared/status/briefs/BRIEF-TWIN-SOLVER-MATRIX-GENERATOR-2026-08-05.md` (Bob-carried into a twin sitting).
**Outcome:** delivered as a **file**, not as rows. The brief's §1.5 rested on a twin write path that does not exist, and proving that is arguably the more valuable half of the session.
**Branch state at close:** `main` = `07f41e2` (unchanged, clean). **PR [#16](https://github.com/BobbyG4/m8trx-twin/pull/16) OPEN** — `feat/solver-test-space-matrix`, 1 commit `0fe2667`. Shared pushed at `206c921a`.

---

## 1. What was asked

A schema-true test-space matrix for core's anchor-pattern solver (skeleton-first decoration:
corridor zigzag · junction star · plaza template · door-pair): ~14 spaces across 6 archetypes,
real `site/space/zone/fixture` rows with SRF-metre geometry, inside a dedicated quarantine site
named `Solver Test Facility` in M8trxDemo, with a manifest so deletion later is one scripted pass.

## 2. ★ The finding: twin has no spatial write path, and this is the third instance of the class

**Measured live**, not read off a doc, against `dev.m8trx.com/server/api/v2` with twin's real
Bearer (`twin-s280-lockdown`, Denver-scoped):

| call | result |
|---|---|
| `POST /spatial/identity` | **200** — full Denver site→space→zone tree |
| `POST /sites` · `/spatial/sites` · `/spaces` · `/spatial/spaces` · `/zones` · `/spatial/zones` | **403 `CONNECT_NOT_EXPOSED`** |
| `POST /compliance/fixture-codes` | **403 `CONNECT_NOT_EXPOSED`** (known debt, §8.2 row 3) |

⚠ **The honesty caveat, and it matters.** `/spatial/site` — a path I invented, which almost
certainly does not exist — returns the **identical** `CONNECT_NOT_EXPOSED`. The gate fires **ahead
of routing**, so these 403s prove **unreachability, not absence**. This table is *not* an endpoint
inventory and must never be quoted as one. Recorded the same way in the shared append.

**Not a scope gap.** `CONNECT_NOT_EXPOSED` is the endpoint-level annotation, not the capability
check — a key minted with every scope in the system hits the same wall. Same door as TWIN-REQ-005.
Bob offered to mint a temp key mid-session; **declined on exactly this reasoning**, and the
generator was never the constraint anyway.

**How the existing spatial data got there:** not through any API. `DEPLOY-HANDOFF.md:21` —
*"direct `psql` to `mother:5448` (REST/Hasura write paths were tenant/role-blocked)"*, executed by
core. Off-Limits from a twin lane.

**Bob's ruling (2026-08-05):** *"do NOT write to the DB at all. Emit the full matrix as
solver-matrix.json … Core ingests it into the quarantine site on its side."* → **zero rows written,
edited or deleted, tenant-wide.**

**Filed as [TWIN-REQ-006](../../../m8trx-shared/twin/requirements/TWIN-REQ-006-connect-spatial-provisioning.md).**
Mid-session I told Bob I would flag rather than file, reasoning it was core's spatial lane. That was
the weaker call: CLAUDE.md's protocol triggers on *"the public API doesn't expose what we need"*,
and twin needed it and could not do it. Filed, and the shared append amended so the two do not
contradict each other.

## 3. What shipped

Commit `0fe2667` on `feat/solver-test-space-matrix` (**PR #16, open**):

| file | what |
|---|---|
| `scripts/build_solver_matrix.py` | the generator, deterministic off `sha256(space name)` |
| `scripts/verify_solver_matrix.py` | independent verifier + SVG renderer |
| `reference/data/solver/solver-matrix.json` | the deliverable, 388 KB, `sha256:7783087cde778367…` |
| `reference/data/solver/README.md` | ingest notes for whoever loads it |
| `reference/data/solver/svg/*.svg` | 14 renderings, eyeball aid, not an input |

**14 spaces · 49 zones · 374 fixtures · 7 doorways · 7,577 m²**, SRF metres. A full ingest creates
**1 site + 14 spaces + 423 zones** (fixtures *are* zones — `SEED-PLAYBOOK.md:37`; the `fixture`
table is unused).

Every §2 band met: grocery aisles **1.911 / 2.086 m** (band 1.8–2.5) at 8 / 6 aisles · factory
1600 / 1120 m², 7 / 5 racking rows at 4.5 m (LOS-blocking), marked walkways, 8 m dock plaza ·
showrooms 14 / 10 **rotated** islands with 1.4 m min gap and **zero straight aisles** · plazas
95% / 93% open · multi-room 4 / 3 rooms with 1.1 m doorways · and all four pathologies
(**1.1 m** aisles below the 1.5 m floor · 3 dead-end stubs · 30×30 m plaza · L boundary with one
reflex vertex at (12,9)).

## 4. Decisions made

1. **Sibling generator, not an extension of `build_layout.py`.** That file is one monolithic
   Decathlon grammar, and its `geometry_for()` emits an **AABB ring only** — the non-convex
   pathology is *structurally inexpressible* in it. Three forced departures: **metres** not
   millimetres (⚠ `chain/stores/*/layout.json` stays mm — do not mix), **arbitrary rings** not
   AABBs (containment becomes ray-cast), and **rotated fixtures** (`build_layout` zeroes rotation
   at `_rebase:128`), which forces overlap testing onto **SAT** over convex polygons.
2. **Doorways = `openings[]`, a line segment** — twin's `crossing_slices` grammar
   (`build_layout.py:378`, the EAS-gate form) generalised from axis-aligned `(y, x_start, x_end)`
   to a free segment. **Not** `space_connection` (in schema, dormant, never populated on mother),
   **not** a zone row. §3.3 asked which representation was used; this is the answer, recorded in
   the file itself, the README and the append.
3. **Rooms are `region` zones inside ONE space**, because `space_connection` is dormant. Noted as a
   limitation: if the solver's door-pair pattern is meant to fire *across* spaces, this matrix does
   not exercise that case, and the gap is core-side.
4. **Four new `space_type` tokens** — `warehouse` / `showroom` / `concourse` / `back_office` —
   minted for FR-37 regime variety. `space_type` has no `CREATE TYPE`/CHECK in 7a; it is unratified
   free text (`SPATIAL-HIERARCHY.md:48`). Each space carries `space_type_status` so **BW ratifies
   deliberately rather than inheriting by accident**. Flagged to Bob as easily reverted if twin
   should not coin vocabulary.
5. **No anchors**, and the verifier *fails the build* if any appear. The solver proposes them —
   that is the test.

## 5. Verification — and why it does not import the generator

`verify_solver_matrix.py` deliberately does **not** import `build_solver_matrix.py`. A generator
asserting its own output is worth little; every check re-derives from the emitted file, the way
core's harness will:

- fixture rings **recomputed** from `center + dims + rotation_deg` and compared to the emitted
  `ring` (agree to 2e-3 m) — a harness trusting one and rendering the other is the silent failure
  this exists to catch
- containment **ray-cast** against the real, possibly non-convex boundary
- fixture-pair overlap by **SAT** — bbox comparison is wrong in *both* directions once islands
  rotate (false positives on the diagonal, missed overlaps on the corner)
- every doorway midpoint proven inside the boundary and inside **no** fixture — a declared opening
  sitting inside a wall is the defect
- declared counts reconciled against the arrays; §2 bands and §3 obligations asserted per archetype
- **determinism proven by regeneration** — byte-identical

**14/14 pass.**

## 6. FAILED APPROACHES — the don't-repeat record

1. **Assuming `build_layout.py` was reusable.** Spent the first exploration pass on the premise
   that the chain generator could be parameterised into new archetypes. It cannot — `geometry_for()`
   is AABB-only and `assemble_spaces()` partitions by hardcoded code-string prefixes (`RCV-01`,
   `BR-`, `FR-`), plus `build_chain.py:399` asserts **exactly 3 spaces** per site. Anything
   non-retail is a sibling generator. Confirmed by an explorer sweep before writing a line.
2. **`qlmanage -t` to rasterise the SVGs for a visual check — hung, killed at the 2-minute
   timeout.** Do not reach for it again. The substitute that worked in seconds: an **ASCII
   occupancy raster** printed straight from the JSON (point-in-polygon per cell). It immediately
   confirmed the L-shape, dead-end stubs, doorway gaps and aisle grids.
   ⚠ **And it produced its own false alarm** — several 0.2 m walls appeared "missing" because the
   raster's 0.28 m sample centres fell either side of them. Verified by hand as a sampling
   artifact, not a geometry bug. A coarse raster under-draws thin fixtures; check the arithmetic
   before believing a gap.
3. **Two §2 deviations that the gates caught and I fixed rather than reported.** Grocery aisles
   first solved to **1.467 m** — inside §2's *area* band, outside its *aisle* band, because I chose
   the footprint and let the aisle fall out. Widths are now solved **backwards from the aisle
   band**. And showroom-01 placed **13 of 14** islands until the rejection sampler's try budget
   went 4,000 → 60,000. Both are recorded in the append; neither was allowed to ship as a
   "limitation".
4. **A real T-junction overlap in multi-room**, caught by the generator's own assert: room dividers
   spanning `[corr_d, d]` interpenetrated the corridor wall centred on `y = corr_d`. Fixed by
   starting dividers at `corr_d + 0.1` so two 0.2 m walls **abut** rather than cross. The gates
   earning their place on the first run.
5. **I nearly under-filed.** See §2 — flagging when the protocol said file.

## 7. Key discoveries

- **The Connect surface lets an integrator read the spatial tree and reference a `spaceId`, but not
  create the thing being referenced.** `M8TRX-CONNECT-API.md:312` calls `/spatial/identity` *"the
  endpoint that makes self-serve onboarding possible"* — it made the identifier **readable** without
  making the container **creatable**. Onboarding still terminates in a human on M8TRX's side.
- **`CONNECT_NOT_EXPOSED` is returned pre-routing**, so it cannot distinguish a closed endpoint from
  a non-existent one. Worth knowing for every future probe twin runs — a 403 here is not evidence
  about the shape of the API.
- **`space_type` is still unratified free text**, ~6 weeks after `SPATIAL-HIERARCHY.md:48` flagged
  it. Twin has now emitted four more provisional tokens into that vacuum, deliberately labelled.

## 8. Open at close

1. **PR #16 awaiting review/merge.** Nothing else uncommitted; `main` clean.
2. **Core owes the id map.** No `site_id`/`space_id`/`zone_id` exist in the file because none were
   minted. Whoever ingests allocates them and should record the mapping back onto the brief — that
   is the half of §1.4 twin cannot supply.
3. **TWIN-REQ-006 filed, awaiting absorption.**
4. Everything carried from S18 is untouched by this session — the `alert:read` re-grant, `A3`, and
   the three findings awaiting a core ruling all stand exactly where S18 left them.
