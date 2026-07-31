# Session 17 — 2026-07-31 · Connect READ half wired · the ship gate · zero-loss CONFIRMED

**Branch:** `feat/connect-read-half` (stacked on the unmerged `fix/fixture-coverage-in-area-zone`) · **12 commits this session** · `main` at `39c8238`
**Shared results file:** `m8trx-shared/status/active/RESULTS-TWIN-2026-07-31.md` (the coordinator-facing record; this file is twin's own)
**Mode:** coordinator-driven lane, ~10 cross-session briefs. Twin acted as **external prover** for the Connect surface all day.

> ⚠ **Session 16 (2026-07-30) has no notes file.** That session died before close and the coordinator proxied it, which is why its branch sat pushed with no PR. Its work is captured in TRACK/STATUS and in commits `5b06290`→`6839a4f` (fixture coverage 97→115, `lossAudit`, `impressionWatch`, `oracleDump`).

---

## ★ THE RESULT — post-#245 zero-loss, externally confirmed

The arc open since S15 is closed.

```
connectDayDrive · reproducing profile · seed 4242 · hours 12-16 · compress 18 · 08:24:19Z→08:46:35Z
469,665 samples · 308 shoppers · published 100%

  ORACLE prediction   1512   printed BEFORE a single publish
  WIRE                1512   core's OWN published output, counted in twin code, deduped by envelope id
  ROWS persisted      1512   read back over POST /visionai/impressions/query
  SESSIONS             308   = exactly the shoppers driven
  FIXTURES         115/115   fixture sets MATCH — attribution aligned, not merely the total

  LOSS: 0 — 0.00%
```

**The timeline that settles the contradiction.** S282 recorded *"acceptance EXACT, zero loss"*; twin measured 20.3%. Both were honestly reported and describe **different times**:

| When | Measurement | Loss |
|---|---|---|
| 07-30 pre-fix | wire 1512 → rows 1370 | 9.4% |
| 07-31 03:03 | wire 1512 → rows 1205 | **20.3%** |
| 07-31 06:36–07:18 | wire 1512/317/317 → rows 0 | **total** (the `vision_ai` entitlement gate) |
| **07-31 08:24 post-#245** | **wire 1512 → rows 1512** | **0.00%** |

**Hypothesis, explicitly not asserted:** the same unsatisfiable entitlement gate may also account for the 03:03 partial — degradation after a load plateau, recovering as load fell, is *consistent* with a gate shedding under contention. Not measured. Needs someone who can see the gate.

---

## What shipped

| Commit | What |
|---|---|
| `e6c01cb` | **§6.5 READ half wired** — `ReadPlane.kt` (4 DTOs), 4 `ConnectClient` methods, `connectReadProbe`, `impressionVerify` (window-walking, recursive slice-halving on `truncated`) |
| `b083cc3` | probe formatting fix + the live capability result |
| `359f0ca` | **row clocks are ISO strings, not epoch millis** + twin reproduced 3,119 in code |
| `b868193` | §6.5 confinement proven externally; **twin's key is Denver-scoped, not tenant-wide** |
| `bc669df` | **TWIN-REQ-004 CLOSED** |
| `e4252bf` | post-#215 drive artifacts (`status/active/data/post215-0731-*`) |
| `be66f52` | **catalog/pricing keyed on EAN + `gtin` emitted** — twin was *feeding* the both-rows bug |
| `55e4ce8` | **`connectAcceptance` — the Connect ship gate**, 15/15 |
| `8cbb1c8` | `scan_event.position` provenance labelled at source in `DEPLOY-HANDOFF.md` |
| `5078a0c` | **F4 — three-phase browse episode**; core's `min()` finally discriminates |
| `ef620ac` | episode-shape env toggle — **and it exonerated F4** |

| `cdcdf03` | **`Shoplift` journey + `EasTagging`** — the LP substrate, built (see § LATE ADDITION) |

**Green at close:** ktlint · `connectSelfTest` 11 · `peopleSelfTest` 50 · `scenarioSelfTest` **37**.

---

## Key discoveries

### Twin-side truths nobody else can state

- **`scan_event.position` is twin's seed** — 102,675 of 102,683 rows, caused by twin's own `DEPLOY-HANDOFF` instruction to write both `thing_location` and `scan_event`. The 8 unpopulated rows are the *real* writer's output (`ScanService.kt:95` sets none of those columns) — that fingerprint closed the inference. **Not calibration-wrong** (authored geometry, never through a camera transform, so S277's `scale: 0.452` doesn't apply) **but per-space unregistered mm frames, SRID 0, Z=0** — so positions in different spaces are not comparable at all. Labelled at source; do not delete or regenerate (reseed parity is byte-for-byte verified).
- **Twin is 99.8% of the platform's entire impression record** — 7,206 of the census's 7,222 (`07-28` 3,119 + `07-30` 2,882 + `07-31` 1,205). So `FR-INTEL-29 — BUILT, 7,222 rows` is true about the *pipeline* and misleading as evidence about *production*. Same class as `scan_event.position`: **two of the platform's largest spatial tables are almost entirely twin.**
- **`sku` is a supplier STYLE code, not a stock-keeping unit** — `5391035` is one shoe in two sizes at $80/$109, same colour, same handle, different EAN. Only 1 of 2,580 real style codes has multiple variants, but that is the **curation, not the model** — an index would have passed preflight by luck and failed later.

### Twin was causing a live bug

`ChainActivityStream.loadAssortment` deduped on `item_cd`, so `5391035`'s size-8 variant was **silently dropped at load** and twin emitted the style code — one pricing delivery writing one variant's price onto both product rows. Now keyed on EAN: **22,921 → 22,930** addressable units. Twin was not merely hitting the documented both-rows bug, it was **feeding** it.

### The Connect surface, driven from outside

- **§6.5 rule 2 holds on all four reads**, negative controls by slug **and** UUID, omitted-site returning **1 of 14**.
- **Twin's key is site-scoped to Denver**, not tenant-wide as this repo claimed for a month. That stale line nearly cost a verdict — the first read of `/compliance/state`'s 403 assumed tenant-wide and therefore a regression; only re-testing with the key's own site kept it honest.
- **Findings that landed in core the same day:** typed 403s (`SITE_ACCESS_DENIED` vs `PERMISSION_DENIED`), `/compliance/state` capability fix (which was masking a *second* defect — its omitted-`site_ref` path had no confinement at all, so fixing the visible one alone would have opened a cross-site read), `/compliance/state` accepting a slug, `product_catalog` re-keyed to `gtin`, and **the alarm envelope shipping with no coordinate field** because of the `scan_event.position` finding.
- **EAS peer test → verdict (b)**: payload authorable cold with no questions; onboarding needs a human, exactly as §8.2 now documents. **The one mismatch is the capability route** — §7 presents `PATCH /connect/service-keys/{keyId}/scopes` as the supported way to add scopes, but it and `GET /connect/service-keys` are `CONNECT_NOT_EXPOSED` to **every** Connect key. Combined with SEC-1's subset guard, no Connect key can ever obtain a new capability without an admin.

### The pattern worth one decision

Three separate findings, one shape: **Connect lets an integrator write, and increasingly read, but not provision or transition.**
- directive→task needs an operator to **act** mid-chain (`/activate` + `/evaluate` are `CONNECT_NOT_EXPOSED`)
- FR-INTEG-16 needs `integration:manage` restored
- alarms need three registrations, two with no self-serve path

---

## Failed approaches / don't-repeat

- **Wrong window twice.** Rows land at wall-clock, not the plan frame; my first read-back queried 07-28 and briefly looked like 100% loss. Later, a per-day loop built `to` as day+1 and produced the **invalid date `2026-07-32`**, whose error my parser turned into a `0` — I nearly filed "07-31: 0". Also: a whole-month query returns **5,000 `truncated:true`** — the cap, a page not a total.
- **Modelled the row clocks as `Long`.** §6.5 names the fields but not their type; the NATS event carries millis and the *request* accepts either, so `Long` compiles, passes a hand-written fixture, and throws `InvalidFormatException` on the first live call.
- **slf4j takes only `{}`.** `{:<34}` printed literally and shifted every argument right — the first `connectReadProbe` run put the endpoint name in the detail column and dropped the refusal message, the one thing that task exists to show.
- **Over-specified a fault window.** Bracketed the outage as "broke between 03:25Z and 06:36Z" — but that is simply when twin was not driving, and zero rows with zero denials is also what *no traffic* looks like. The three drives with confirmed wire output and zero rows were real evidence; the bracket was not, and it sent a lane hunting deploys. Mechanism wrong too: **ingest gate, not writer.**
- **A gate that cannot fail is decoration.** `connectAcceptance` was verified in *both* directions before being trusted — and the forced-failure run caught a misleading diagnostic in its own output (reporting `SITE_ACCESS_DENIED` as a missing scope).

---

## Decisions

- **TWIN-REQ-004 CLOSED** by twin as external prover. F6 (`/compliance/state` UUID-only) assessed **non-blocking**: the brief's complaint was an identifier *nothing on the surface returned*; F6's UUID **is** returned by `/spatial/identity` from a slug, proven front-door with no local files. Extra round trip ≠ wall. *(Core fixed it anyway the same day.)*
- **F4 shipped with its live verification recorded as OWED, then closed** — `firstDwell` → `firstLook` 1,200ms apart on a persisted row, `view 14.0s ≠ dwell 15.2s`, oracle predicted 15200ms and core stored 15.2s.
- **Did not regenerate planograms.** `build_planogram.py` is `item_cd`-keyed in two places so `5391035`'s target sums both sizes and takes whichever EAN was read last. Fix identified (`(fixture, ean)`), not applied — it regenerates 10 documents and changes directive payloads.
- **Did not build the EAS emulator.** Sending alarms twin cannot verify, from a source twin cannot register, would be theatre.

---

## ★ LATE ADDITION — the LP substrate, built (Bob's ruling)

Twin's own finding earlier in the session was *"envelope ready, LP story not ready"*. Bob ruled: **fix the anchor docs and build the journey.** Both landed in `cdcdf03`.

### The anchor pointed at stock that does not exist

`CLAUDE.md` and `STATUS.md` named *"W-series sports watches ($29.99–$89.99), EAS-tagged, 40 items"* as the LP anchor. **Zero watch SKUs exist in the live 2,586-SKU catalog** — that belonged to the superseded 920-SKU Manhattan concept. **Session 8's static-seed audit flagged exactly this and it went unclosed for two months.** Also corrected: CLAUDE.md's *"Garmin watches"*, and `store-operating-model.json`'s `eas_scope_skus: 36`.

Replaced with a **rule, not another literal** — a list rots the same way next time the catalog is rebuilt:

```
EAS-tagged  ⟺  price_usd >= 150  AND  category != "outdoor"
```

Both clauses earn their place: the floor selects genuinely premium stock; the exclusion is **concealability**, and in this catalog `outdoor` captures it exactly — verified by enumerating every bike/tent/trainer `product_type`, finding all of them there, and confirming the rule admits none. **271 of 2,586**, concentrated on `PE-02`/`PW-02` Kiprun premium footwear — which is what CLAUDE.md already meant by *"Kiprun premium shoes"*.

⚠ **Tag state is declared TWIN-SIDE, in the code header, prominently.** A real third-party EAS owns tag state and the platform never sees it — which is exactly what makes twin minting it *faithful emulation*, conditional on never implying M8TRX stores or could verify it. That distinction is the difference between a demo and a lie, so it lives in `EasTagging.kt`, not only in a results file.

### `Shoplift` is code now, and the alarm is earned

Browse two ordinary zones → dwell at a fixture that actually holds tagged stock (a real `BrowseEpisode`, so a real impression forms) → conceal a unit from that same fixture → **skip checkout** → walk out on an `objLocation` path that **genuinely crosses `CS-01` at `y=600`**.

**The alarm is a consequence of the track.** `EasGateCrossed` is published only *after* the walk-out samples are emitted, carrying the unpaid EPCs; a subscriber raises the alarm. No journey fires one directly — that would fill the LP tile with events having no shopper behind them, the same structural-zero-as-fact pattern this session spent all day removing elsewhere. The crossing is **checked, not asserted**: the walk fails loudly if the sign change did not happen.

**No `SaleCompleted`**, so these shoppers correctly never enter conversion or revenue — the §1 reconciliation identity stays honest and shrink appears as the gap it is.

**Proven firing, not merely compiling:** a generated day yields **2 concealments and 2 gate alarms**, every concealment producing exactly one alarm. Gates load from the store's own layout; a store declaring none emits no crossing, so **absence stays absence** rather than being papered over with an invented gate.

`scenarioSelfTest` **27 → 37** — gate geometry, the rule (incl. case-insensitivity and the road-bike case), the arc firing, alarm-per-concealment, and an assertion that **zero watch SKUs exist** so the dead anchor cannot be restored by accident.

### Don't-repeat, added to the record

**I nearly filed "twin has no EAS substrate."** A first pass scanned `zones` only, found nothing, and would have argued against building this. The substrate is real and lives in **`crossing_slices` and `sensors`** — which a zone scan never touches. Partial-listing errors of exactly this shape bit three people on 2026-07-31, the coordinator twice. **Look where the data is, not where you expect it.**

---

## Branch / deploy state at close

- **`feat/connect-read-half`**, 7 commits, **stacked on `fix/fixture-coverage-in-area-zone`** (S16's 7 commits, still **unmerged, no PR**). `main` untouched at `39c8238`.
- **Two unmerged branches stacked.** Recommend PR'ing S16's branch first, then this one.
- Live: ingest flowing post-#245, zero loss confirmed. `M8TRX_TWIN_BEARER` holds `inventory:read` + `vision_ai:view` + `task:read`; **lacks `integration:manage`** (lost since S11) and `alert:read`.
