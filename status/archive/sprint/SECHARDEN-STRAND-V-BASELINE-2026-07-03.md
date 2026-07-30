# Strand V — Site-Scope Confinement: RED Baseline (twin harness)

**Filed 2026-07-03 (twin, Session 14). Extends CORE-REQ-005 / SECHARDEN.** Baseline artifact for the
site-scope hardening arc — recorded so we do NOT re-derive. Coordinator absorbs into the arc.

**Harness:** `./gradlew connectSiteScopeAudit` (`ConnectSiteScopeAudit` + `sim/SiteScopeAuditDriver` +
`UserAuthClient` + `HasuraClient`), branch `feature/connect-sitescope-audit`. Public-surface + user-JWT
**only** — psql cohort-shaping stays coordinator/core-side (twin HARD RULE). Re-runnable per strand.

## Re-run
```
set -a; . ./.env; set +a                 # M8TRX_CONNECT_API_BASE (else dev default)
M8TRX_AUDIT_PASSWORD=<cohort-pw> ./gradlew connectSiteScopeAudit --no-daemon
#  + M8TRX_AUDIT_SCALE=N        → N concurrent scoped login+read (resolver/RLS latency)
#  + M8TRX_AUDIT_WRITE_TARGETS  → set once core provisions throwaway victim/test-task targets
```
Emits a plane-by-plane RED/GREEN matrix + a markdown table (copy target). Cohort + cross-site targets are
the labeled M8trxDemo fixtures (andrew.wilson/Denver · anais.faure/Bordeaux · alice.roux/HQ).

## RED baseline — 2026-07-03 (today's state): **24 RED · 15 GREEN · 0 ERR · 4 GATED** (47 rows)

**Token plane** — the root. Both site-scoped users' JWTs carry `x-hasura-tenant-id` only, **no site claim**
(= Boundary-2 TokenProvider finding; site claims only emitted for ORG/TERRITORY today):

| user | caps | role | site_claim | verdict |
|---|---|---|---|---|
| andrew (Denver site-mgr) | 111 | frontEnd | **ABSENT** | 🔴 RED |
| anais (Bordeaux staff) | 13 | frontEnd | **ABSENT** | 🔴 RED |
| alice (HQ member) | 17 | frontEnd | ABSENT | ⚪ INFO (org/territory axis) |

**Store-picker plane** — `site` visible to each user:

| user | sites_visible | verdict |
|---|---|---|
| andrew (Denver) | **14 / 14** | 🔴 RED (want Denver-only) |
| anais (Bordeaux) | **14 / 14** | 🔴 RED (want Bordeaux-only) |
| alice (HQ) | 14 | ⚪ INFO |

**Read plane** — cross-site Hasura reads as a site-scoped user (want `[]`). **Uniform LEAK** for both
andrew and anais across every non-home site (Seoul / New York / Bordeaux); representative counts:

| table | Seoul | New York | Bordeaux | verdict |
|---|---|---|---|---|
| `space` | 3 | 3 | 3 | 🔴 RED |
| `item` | **9,507** | **14,642** | **4,996** | 🔴 RED |
| `zone` | 100 | 130 | 54 | 🔴 RED |
| `scan_event` | **9,507** | **14,642** | 4,993 | 🔴 RED (RFID stream — surveillance-grade) |
| `fixture` | 0 | 0 | 0 | ⚪ 0 rows — inconclusive (table sparse; `zone` covers the layer) |
| `stocktake_session` | 0 | 0 | 0 | ⚪ 0 rows — inconclusive (empty at these sites) |
| `reader` | 0 | 0 | 0 | ⚪ 0 rows — inconclusive (empty at these sites) |

> ⚠ **False-GREEN caveat:** a `0`-count read is only a *confinement* proof if the table is populated at
> that site. `fixture`/`stocktake_session`/`reader` are empty/sparse at these sites → their GREEN is
> "nothing to leak," NOT "confined." The **decisive RED signals** are `space` / `item` / `zone` /
> `scan_event` (thousands of rows, both users, all cross-sites).

**Allowed axis (must NOT over-deny)** — each user's HOME site returns rows: andrew→Denver `item` = 15,006;
anais→Bordeaux `item` = 4,996. ✅ Confinement must keep these GREEN-for-home when it lands.

**Write plane (confused-deputy)** — **GATED** (4 rows): `/rules/fire` · `/tenant-role` ·
`/permission-sets` · task-lifecycle. No cross-site write fires until core provisions the throwaway
victim-user + per-site test-task targets (twin pings coord when wiring this suite).

**Scale** — `M8TRX_AUDIT_SCALE=N` drives N concurrent scoped login+read (resolver + site-RLS cost under
load). Not yet run at scale; coordinator invokes when useful.

## Interpretation as strands land (per coord)
Partial-green per strand, not all-at-once:
- **store-picker** flips with **Strand 1** (site identity) + andrew's re-seed (tenant-membership drops).
- **cross-site reads** flip with **Strand 3** (site-RLS on the ~28 operational tables).
- **confused-deputy writes** flip with **Strand 2**.
- **A plane still RED after its strand = a real miss → flag it.**
