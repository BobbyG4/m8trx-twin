# M8TRX Twin — Live Operations (24/7 ongoing store operation)

**Status:** DESIGN (captured 2026-06-27, Session 9). The forward architecture for running the twin as a
**continuous, always-on store operation** that emits a realistic event stream through M8TRX Connect during
each store's **local business hours** — the production realization of `ACTIVITY-PLAN.md`, driven by the
Connect simulators (`com.m8trx.twin.connect`, see `SIMULATOR-GUIDE.md`).

> **The shift in mental model:** a *scenario* has a start and an end (deterministic, `rate=0` step replays).
> An *ongoing operation* is a long-lived process that never stops but goes quiet when each store is locally
> closed. Build a **daemon**, not a scenario runner.

---

## 1. One always-on daemon, paced by a per-site local clock

A single long-running process (`StoreOperationsRunner`) drives the whole chain. Each site carries its **own
timezone + opening hours** (derivable from the site geo/country already seeded on all 14 sites). A real-time
tick loop (`rate=1` wall-clock mode — distinct from the locked `rate=0` step mode used for deterministic
scenario replays) asks each site every tick: *am I open right now in my local time, and what is my current
intensity?*

"24/7" then falls out for free — no store ever fakes after-hours traffic. With ~10–21 local hours across
US (×3) / FR (×5) / KR (×2), the chain spans enough timezones that **at least one store is always trading**:

```
UTC →   00  02  04  06  08  10  12  14  16  18  20  22
KR(+9)  ████──────────────████████████████──────────────   10–21 KST
FR(+1)  ──────────────████████████████████████──────────   10–21 CET
US-E    ████──────────────────────████████████████████──   10–21 ET
US-W    ██████──────────────────────────██████████████──   10–21 MT
        ↑ continuous global coverage, zero dead hours; each store respects LOCAL hours
```

When Seoul locks up, Paris is mid-afternoon and Denver is opening. The investor/marketing dashboard always
has a live store somewhere — while each store's local curve stays honest.

---

## 2. Daily lifecycle state machine (per site)

`CLOSED → PRE-OPEN → OPEN → CLOSING → POST-CLOSE → CLOSED`, transitions firing at the local-calendar
boundaries (with weekday/weekend hours + holiday overrides):

- **PRE-OPEN** — staff arrive (scans), receive the overnight delivery (`shipment_manifest` + `items/receive`),
  restock to facings.
- **OPEN** — the intensity-modulated "play": footfall → browse → try-on → buy → the occasional theft/EAS
  alarm. Bimodal demand curve (lunch + evening peaks); Saturday ≫ Tuesday.
- **CLOSING / POST-CLOSE** — last-customer wind-down, cash-up, periodic stocktake (which produces the
  `stocktake_result` the `OutboundReceiver` catches).

This is `ACTIVITY-PLAN.md`'s "play," clocked in real time — the Layer-3 generators (`TrafficGenerator`, etc.)
running continuously instead of in a batch window.

---

## 3. Intensity model (the demand curve)

`intensity(site, localDateTime) → events/hour`, shaped by: the open/closed gate, a time-of-day curve
(bimodal), a day-of-week multiplier, optional seasonality. Footfall derives transactions (conversion rate),
try-ons, restocks. Item movement is the connective tissue (per ACTIVITY-PLAN) — a sale/try-on/restock each
produces the RFID + custody events that tie traffic, staff, sales, and inventory analytics into one stream.

---

## 4. Closed-loop inventory conservation — the decision that makes or breaks 24/7

If customers buy 24/7, stock depletes to zero in days. The operation must **conserve inventory**:

- Keep a twin-side per-SKU on-hand projection (received − sold).
- Each **PRE-OPEN**, emit a **delivery** (`shipment_manifest` inbound + `inventory/items/receive`) that tops
  each SKU back toward its target depth (the size-curve facings from the seed). Real retail replenishment —
  keeps the store perpetually stocked and realistic.
- Keep the **weekly reset-to-opening-state** (the ACTIVITY-PLAN mechanism) as a **backstop** against drift,
  run in a global low-activity window.
- Stamp every emitted event with `run_id` / `business_date` so a reset can target a window cleanly. On the
  §8 webhook plane (no `run_id` field) this rides the deterministic external id —
  `InboundPushDriver.pushSaleBySku` already encodes it as `external_sale_id = "$runId-sale-$seq"`.

**Decision: closed-loop primary + periodic reset backstop.** A store that teleports back to full every night
reads as fake on a heatmap; replenishment-driven conservation is the realistic path.

---

## 5. Gentle pacing — this is what avoids the Hikari incident

A real store does low-hundreds of sales/day → a handful of events/minute at peak. Emit a **jittered trickle,
never bursts**, and honor `429 Retry-After` (a per-integration token bucket; `ConnectHttp` already surfaces
the header). The continuous incremental feed is the *opposite* of the bulk reseed that starved the backend
pool (the Session-8 auth-500) — so 24/7 operation is **safer** for core than seeding was, and it is precisely
the live-transaction generator the integration-health / DLQ cockpit is built to observe.

---

## 6. Operational shape

- **Resumable** — persist only per-site phase + the on-hand projection + last `business_date`. On restart,
  re-derive "where should each store be right now" from wall-clock + calendar (almost stateless) and
  reconcile inventory.
- **Observable** — heartbeat log + the `OutboundReceiver` counters; lights up core's integration-health
  dashboard directly. A 200 webhook ack is *received*, not processed — assert end-to-end by polling the DLQ.
- **Deploy** — one container, long-lived, restart-on-failure, env-driven (`.env`). One process can run the
  whole chain; split per-region only if load demands.
- **Reproducible-but-organic** — seed per `(site, business_date)` (`sha256(site_id + business_date)`): any
  given day is replayable for debugging, but days differ naturally.

---

## 7. How it maps to what exists

| Concern | Asset |
|---------|-------|
| Emission (sales / catalog / shipments / pricing) | `sim/InboundPushDriver` (§8 webhook) |
| Emission (scans / receive) | `sim/DeviceDriver` (§6 data plane — **needs a Bearer key**, §9 below) |
| Receive `stocktake_result` | `sim/OutboundReceiver` |
| One-time integration/channel setup | `setup/Provisioner` |
| The "play" / activities | `ACTIVITY-PLAN.md` (this doc is its 24/7 runtime) |
| Reset-to-opening-state | ACTIVITY-PLAN reset contract + `run_id` stamping |
| Statistical producers | Layer-3 generators (`TrafficGenerator`, etc.), run at `rate=1` |
| Scenario config contract | `LAYER4-CONFIG-SCHEMA.md` (the daemon is a Layer-4 client) |

The daemon IS the "parallel ERP/external simulator" layer the Session-8 pivot envisioned, on a server.

---

## 8. Credential note (live, confirmed Session 9)

Two surfaces, two credentials (validated against the live `twin-pos` integration):
- **Webhook plane (§8)** — `X-API-Key` (e.g. the `twin-pos` key). Webhook-ingest only; **does NOT** work as a
  Bearer on `/api/v2` (returns `401 INVALID_TOKEN`).
- **Bearer plane (§6 data + §7 control)** — needs a separate **service api-key** with `integration:manage`
  (+ `scan:submit`, `inventory:create`, `inventory:read` for the device driver). Required for the device
  driver, provisioner, health, and DLQ polling. **Outstanding — to be provisioned.**

---

## 9. Open decisions (ratify before building the runtime)

1. **Business-hours source** — hardcode Decathlon-style per-country hours, or a small per-site config file?
   (Recommend a `site_hours.csv` keyed by site, with country defaults + per-site overrides.)
2. **One daemon vs per-region processes** — start single-process; revisit only under load.
3. **Inventory projection store** — embedded (JSON/SQLite) for resumability; format TBD.
4. **Replenishment cadence** — nightly top-up to facings vs continuous trickle restock. (Recommend nightly
   pre-open top-up; simpler + matches real DC delivery rhythm.)
5. **VisionAI position streams** — keep on the legacy NATS path (heatmap/foot-traffic surfaces) alongside the
   Connect-routed commerce events, or defer until the commerce loop is solid.

---

## 10. Build sequence (when the live path is green)

1. `OpeningHours` + per-site business calendar (timezone-aware) + the `rate=1` real-time clock.
2. The daily lifecycle state machine + transition events.
3. The closed-loop inventory tracker (on-hand projection + pre-open replenishment).
4. The intensity model + wire the Layer-3 generators to it.
5. Pacing (token bucket + `Retry-After`) + resumable state + heartbeat.
6. Containerize + deploy on the server.

Doc-first while the live test path is shaken out — lock the **inventory-conservation** and **pacing**
decisions (§4, §5) before writing the runtime.
