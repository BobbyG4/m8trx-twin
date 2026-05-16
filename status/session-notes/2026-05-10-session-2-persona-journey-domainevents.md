# Session 2 — 2026-05-10 KST — Persona + journey contract + DomainEvent taxonomy + snapshot format + persistence plan

**Track:** Twin
**Source:** Extracted from `status/SESSION-LOG.md`

---

Layer 4 architectural commitment now stable enough to write code against. Step A complete.

## What shipped

- **Stack locked** — Kotlin (matches services / edge / android)
- **Persona schema** — 3 sealed kinds (Shopper / Operator / Buyer), 9 shared fields incl. `market` + `vertical` + `type` axes, optional `PersonaBiography` bundle anchored to Volere 2d/2e — `reference/architecture/PERSONA-SCHEMA.md`
- **Layer 2 Journey contract** — `Journey { start(ctx, actor, params) }`, scheduler-driven, 6 v1 kinds, terminal-event convention — `LAYER4-CONFIG-SCHEMA.md` § "Layer 2 — Journey contract"
- **DomainEvent v1 taxonomy** — 15 typed events (customer lifecycle / engagement / commerce / operations / anomalies) — `LAYER4-CONFIG-SCHEMA.md` § "DomainEvent v1 taxonomy"
- **Snapshot file format** — 8-section JSON for Layer 1 opening-state seeds + FK-chain / polygon validation — `reference/architecture/SNAPSHOT-FORMAT.md`
- **Persistence + graph plan** — twin owns dedicated PG database on mother instance; no standalone Hasura; embedded `graphql-kotlin` when graph layer earns its keep; 4-stage progression — `reference/architecture/TWIN-DB-AND-GRAPH.md`
- **Twin insight filed** — `m8trx-shared/twin/insights/2026-05-10-vertical-portability-ddl.md`

## Decisions

- Two-layer industry model: `Vertical` (RETAIL | HEALTHCARE | …) + `VerticalType`. MVP RETAIL-only; structure ready for post-MVP expansion.
- Persona biography = separate optional bundle reused across kinds.
- Twin gets its own DB on mother PG instance as separate database. Stage 1 is in-memory + file capture.
- No standalone Hasura for twin — embedded `graphql-kotlin`.
- JSON canonical config + human surface.

## Deploy verification

m8trx-shared VERIFIED ✓. m8trx-twin SKIP (not in verify-deploy.sh allowlist).
