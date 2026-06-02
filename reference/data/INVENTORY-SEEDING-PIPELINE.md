# Inventory Seeding Pipeline — Decathlon Manhattan

**Status:** DRAFT 2026-06-02 (Session 4). The end-to-end plan for getting realistic stock onto the
store's 149 fixtures, plus the data-source assessment behind it. Companion: `EPC-ENCODING-DECATHLON.md`
(the validated tag encoder), `STORE-OPERATING-MODEL.md` (US calibration), `STORE-LAYOUT.md` (fixtures).

---

## 1. Data sources — what each is good for (and not)

Two real Decathlon Korea warehouse dumps + the Korea catalog + the US operating model. **None is a
full-line store**; each contributes a different slice of realism.

| Source | What it is | Good for | NOT good for |
|---|---|---|---|
| `dump-pantos-202504250747.sql` (April, 154M) | 90-day **slow-season** window, node BPA057 | **EPC scheme validation** (169k tags, 100% catalog match) | velocity (dead window) |
| `dump-pantos-202606021444.sql` (Nov, 263M) | Nov 28–Jan 27 **peak season**, same node BPA057 | real **apparel/accessory velocity**, **basket size (~2.3 UPT)**, **English product names**, 343k tags | full-category assortment; category mix; footfall timing |
| `sample_stores/decathlon-korea-raw.csv` (56k) | Full Decathlon Korea catalog | **article master** (real SKUs, EANs, full category breadth) | demand, pricing (KR), US sizes |
| `STORE-OPERATING-MODEL.md` | US-calibrated benchmarks | **category mix, sizes, footfall timing, volume scale** | specific real articles |

### Two traps discovered (do not repeat)

1. **BPA057 is an apparel + accessories e-commerce fulfillment node, not a full store.** Nov units:
   accessories 42% + apparel 40% = 82%; footwear ~0.4% (and those are `SHOE RACK`/`SHOE BAG`
   storage, misclassified), watch_gps 0, cycling 11 units, swim 215. So the warehouse velocity is
   only a real demand signal **within apparel/accessories** — it cannot rank footwear, watches,
   cycling, swim, team, or hardware, because the node barely ships them.
2. **The Nov timestamp curve is warehouse pick/pack, NOT retail footfall.** Mon 87,853 vs Sat 1 /
   Sun 0; 09:00 + 13:00 shift waves. That's a DC processing rhythm (Monday batch of weekend e-com).
   **Store footfall timing comes from the operating model**, never from this curve.

### What the warehouse legitimately contributes
- **EPC encoding** — validated bit-for-bit (filter=1, partition=6; see `EPC-ENCODING-DECATHLON.md`).
- **English article names** — the KR→US "cleanse" is largely free; names like `LS TS RUN WARM M BLACK`
  are already English with color+size embedded.
- **Basket size** — ~2.3 units/order, corroborates operating-model UPT 2.2.
- **Apparel/accessory velocity** — real relative ranking within those two categories.

---

## 2. Realism strategy — what drives what

```
WHICH real articles      ← 56k Korea catalog (full breadth) + Nov names (English, apparel/acc)
WHICH are tagged real     ← validated EPC encoder (EAN → SGTIN-96)
HOW MANY per category     ← US operating-model category mix (NOT Korea node)
WHAT SIZES                 ← synthesized US size curves (NOT Korea sizing)
HOW DEEP per SKU           ← operating-model §10 stocking depth
DEMAND SHAPE (apparel/acc) ← Nov velocity (peak, real) — weak prior elsewhere
BASKET / co-purchase       ← Nov orders (160k real baskets) → TransactionGenerator
FOOTFALL TIMING            ← operating-model retail curve (NOT warehouse curve)
```

Korea data supplies **validity + apparel/accessory shape**; the operating model supplies **US
magnitude, mix, sizes, and timing**. Neither overreaches.

---

## 3. Pipeline stages

| # | Stage | Mechanism | Status |
|---|---|---|---|
| ① | Cleanse → US names | Nov English names + agent translation of KR catalog names | **DONE** (`manhattan-assortment-final.csv`) |
| ② | Select assortment | catalog + US mix budget; apparel/acc velocity-ranked from Nov | **DONE** (4,954 SKUs, `build_assortment.py` seed=42) |
| ③ | EPC encode | `EanToEpc` (filter 1 / partition 6), seeded sparse serials | **DONE** (39,720 EPCs, `manhattan-epcs.csv`, 0 dupes) |
| ④ | Planogram (SKU→fixture) | §4 — real STORE-LAYOUT IDs, 146/149 fixtures used | **DONE** (review on inventory map) |
| ⑤ | Depth + US sizes | per-category depth → ~40k pieces; US size relabel + agent normalize | **DONE** |
| ⑥ | Seed | `item_identifier` + `thing_location` → `day-start.json` → push to mother | **PENDING — prod write, gated on Bob** |

**Outputs (Session 4):** `reference/data/analysis/manhattan-assortment-final.csv` (4,954 SKUs) ·
`manhattan-epcs.csv` (39,720 real EPCs) · `scripts/build_assortment.py` (deterministic rebuild).
Known cosmetic residuals: Decathlon name shorthand ("Ls Ts"), mixed shoe-size format — non-blocking.

---

## 4. Scale (LOCKED 2026-06-02) + Planogram draft — FOR REVIEW

**Scale, reconciled against industry benchmark (6–10 SKUs/m², depth 4–8) + Nov warehouse skew:**

- **Active SKUs (variant-level, = EAN): ~5,000** (within the 3,500–6,000 range for a 600 m² city format; ~8.3 SKUs/m²)
- **Avg depth: ~5 pieces/SKU** (category-varied; 4–8 benchmark)
- **Total pieces / EPCs / on-hand items: ~25,000** (four-wall, incl. backroom; within 20,000–40,000)
- **On-floor / backroom split: 65% / 35%** (small-format limited backroom)

> **Terminology (locked):** *style/model* → *SKU* (style+color+size = one EAN/barcode) → *unit/piece/EPC*
> (one physical RFID-tagged item; what an inventory count tallies). The planogram allocates **SKUs**;
> depth × SKUs = **pieces** (EPCs we seed). **SKU-count mix ≠ revenue mix** — apparel/socks/accessories
> dominate SKU count (size-multiply, space-efficient); footwear + watches punch above their SKU share
> in revenue. Planogram uses SKU-count mix; operating model uses revenue/unit mix.

Per benchmark, **~60–70% of SKUs are apparel + socks + accessories**; large equipment is display-model
only (depth 1). Running-forward urban Decathlon City, mapped to `STORE-LAYOUT.md` fixtures:

| Category | SKUs | Depth | Pieces | Fixtures (STORE-LAYOUT IDs) |
|---|---:|---:|---:|---|
| **Apparel** (apparel, running_app) | 1,900 (38%) | 5 | 9,500 | Gondola `R1–R5` (front+back) + Fitting Rooms `FR-01..04` |
| **Accessories** (socks, gloves, bottles, caps) | 1,250 (25%) | 8 | 10,000 | Accessories wall `ACC-01..04` + gondola `R1–R3` backs + checkout impulse `CO-IR` |
| **Footwear** (running_shoe, footwear) | 800 (16%) | 4 | 3,200 | West wall `PW-01..08` + East wall `PE-01..05` + Footwear Bench `FB-01` + Gait `GA-01/02` |
| **Outdoor** (hiking, swim, cycling) | 400 (8%) | 3 | 1,200 | Gondola `R7–R8` (rear) + East wall `PE-06..08`; large equipment = 1 display model |
| **Fitness / yoga** | 300 (6%) | 2.5 | 750 | Gondola `R6` front; equipment display-model only |
| **Watches / GPS** | 200 (4%) | 2.5 | 500 | GPS display cases `GPS-01..06` — **EAS-tagged, locked** (LP anchor) |
| **Team sport** | 150 (3%) | 2 | 300 | Gondola `R6` back + endcaps |
| **Total** | **5,000** | ~5 | **~25,450** | 149 fixtures (apparel+accessories = 63% of SKUs ✓) |

**Open knobs for Bob:**
- **Target pieces** — ~25k drafted (mid of 20–40k). Push toward 30–40k for a packed-store feel?
- **Category emphasis** — running-forward; re-weight if Manhattan should lean heavier fitness/gym.
- **US size curves** — footwear US men 7–13 (peak 10–10.5) / women 5.5–11 (peak 8–8.5); apparel
  S–XXL US-skewed. Surfaced explicitly at stage ⑤ before EPC generation.

---

## 5. Next

1. Bob reviews the §4 planogram + sets target SKU count + category emphasis.
2. Build stage ② selection (catalog + US mix; apparel/acc velocity from Nov) → assortment CSV.
3. Stage ⑤ US size-curve expansion (surface curves for sign-off).
4. Stage ③ + ⑥: generate EPCs + `thing_location` → `day-start.json` → seed mother.
5. Nov 160k orders → TransactionGenerator basket model (separate workstream).
