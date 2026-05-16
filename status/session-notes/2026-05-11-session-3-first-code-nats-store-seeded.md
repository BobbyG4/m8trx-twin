# Session 3 — 2026-05-11 KST — First code: Kotlin scaffold + NATS live + store seeded + 920-SKU catalog loaded

**Track:** Twin
**Source:** Extracted from `status/SESSION-LOG.md`

---

## What shipped

- **Kotlin project scaffold** — `build.gradle.kts`, `settings.gradle.kts`, `TwinConfig`, `AtomEmitters` interface, `NatsEmitter` (dual-publishes legacy + new pattern), `RestEmitter` (written, untested). Gradle 8.14.4, Kotlin 2.3.20, jnats 2.20.6, Jackson, coroutines.
- **NATS smoke** — `objLocation` published end-to-end to .29. CONNECTED in 140ms.
- **M8trxDemo tenant** — created via signup, service API key issued (`m8trx_6f…`, `principal_kind=service`). Tenant = Decathlon Manhattan, 620 6th Ave NYC, USD.
- **Store concept locked** — Bordeaux running specialty (160 sqm) → Decathlon City format (600 sqm). Grammar from Florence Decathlon CAD plan.
- **Store seeded** — 160 zones + 3 try_on_zones live on mother via `scripts/seed_store.py`. `reference/data/STORE-LAYOUT.md` + SVG floor plan generated.
- **SKU catalog** — 56,003-row Korea raw catalog curated to 920 SKUs, English names, USD pricing, 40 EAS-flagged items. Seeded to `product` table.
- **MapCanvas contract filed** — `m8trx-shared/status/cleanup/MAPCANVAS-ZONE-RENDERING-CONTRACT-2026-05-11.md`. Root: `zone_type` never reaches canvas. Contracted to web session.

## Key discoveries

- **MapCanvas all-zones-same-green** — VisionAI renders every zone type identically. Root at `VisionAIPage.tsx:57`. Contracted (now shipped in core Session 70/71).
- **Service bearer auth gap** — `InventoryActionController` JWT-only. Service bearer returns 401. Filed in `m8trx-shared/status/CLEANUP-TASKS.md`.
- **Catalog onboarding FR unbuilt** — no product catalog import flow. Filed in CLEANUP-TASKS.md.
- **Store concept scope creep** — running specialty can't fill 149 fixtures. Correct format = Decathlon City.

## Carried forward

- `inventoryReceive` + item/EPC seeding (blocks all RFID scenarios)
- Service bearer fix (backend session — `InventoryActionController`)
- TrafficGenerator — walking actor loop
- Persona definitions with English/American names
- Update `day-start.json` snapshot to 600 sqm (currently 300 sqm)
