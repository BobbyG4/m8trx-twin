# Layer 4 — Scenario Configuration Schema

> **Status: STRAWMAN.** This is a starting design for a focused 1-2 hr session. Finalize before any code below Layer 4 is written.

The architectural commitment of m8trx-twin. The shape of a scenario config is the contract between authors (human or LLM) and the orchestrator. Lock it now; everything else is downstream.

---

## Goal

A declarative document that:

- A human author can write by hand to script a scenario (Client A — scripted scenarios, initial ship)
- An Anthropic tool-use loop can construct via primitive tool calls (Client B — LLM authoring, later)
- The orchestrator can validate, seed, and execute deterministically

Both clients produce the *same artifact*. The LLM client is a builder front-end over the same schema, not a parallel pipeline.

---

## Top-level shape

```yaml
scenario:
  meta: { … }          # name, description, time substrate, tenant binding
  population: { … }    # personas + journey templates (a shared library)
  generators: [ … ]    # population-level statistical producers
  events: [ … ]        # discrete time-stamped event injections
```

Four sections; each independent. Generators run continuously; events fire once at their `at` timestamp. Population is a shared library both reference.

---

## Section: meta

```yaml
meta:
  name: "Saturday Rush"
  description: "Investor breadth-flex; 4hr compressed to 5min wall"
  seed: 42                                       # deterministic randomness
  rate: 48.0                                     # scenario-time per wall-time multiplier
  startWallclock: "2026-05-09T10:00:00+09:00"    # what timestamps emit as
  duration: "4h"
  tenant: "M8trxDemo"
  site: "<uuid>"
  failurePolicy: skip-and-log                    # halt | skip-and-log | retry-3x
```

- `rate=1.0` is real-time (4 h scenario takes 4 h to play)
- `rate=48.0` compresses 4 h into 5 min wall
- `rate=0` allows step-by-step manual advancement (debugging)

`tenant` and `site` are the M8TRX targets all events get scoped to. `seed` drives all randomness in Layers 1-3 from a single PRNG; same seed + same config = byte-for-byte identical event stream.

---

## Section: population

```yaml
population:
  personas:
    - id: weekend_runner
      ageBand: "25-40"
      walkSpeedMps: 1.4
      dwellTendency: medium                # low | medium | high
      basketSizeDistUSD: { mean: 95, sigma: 30 }
      paymentMix: { card: 0.7, mobile: 0.2, cash: 0.1 }
    - id: family_browser
      walkSpeedMps: 1.0                     # slower with kids
      dwellTendency: high
      kidsInTow: true
    - id: serious_athlete
      walkSpeedMps: 1.5
      dwellTendency: low
      basketSizeDistUSD: { mean: 180, sigma: 80 }
    - id: shoplift_archetype
      walkSpeedMps: 1.3
      dwellTendency: low
      glanceFrequency: high                 # behavioral marker

  journeys:
    - id: browse_and_leave_running
      impl: BrowseAndLeave                  # Layer 2 class name
      params:
        interestZones: [running_shoes, gps_watches]
        dwellMinutesPerZone: { min: 2, max: 8 }
        exitProbAfterEachZone: 0.3
    - id: try_and_buy_apparel
      impl: TryOnAndBuy
      params:
        tryItemCount: { min: 2, max: 4 }
        keepItemRatio: { min: 0.3, max: 0.7 }
        fittingZones: [apparel_fitting_room]
    - id: shoplift_premium_watch
      impl: Shoplift
      params:
        target: { fixture: gps_watches_premium, skuPattern: "garmin-fr*" }
        exitGate: gate_main
```

Personas are reusable identity templates — they capture *who* and *how they act*. Journeys reference Layer 2 implementations by string name; params are forwarded to the journey constructor.

---

## Section: generators

```yaml
generators:
  - type: TrafficGenerator
    params:
      peakHour: "12:00"
      expectedTotalCustomers: 120
      peakShape: gaussian
      peakSigmaHours: 1.5
      journeyMix:
        browse_and_leave_running: 0.40
        try_and_buy_apparel: 0.30
        shop_and_buy: 0.25
        try_on_and_leave: 0.05
      personaMix:
        weekend_runner: 0.5
        family_browser: 0.3
        serious_athlete: 0.2
      entryGate: gate_main

  - type: StaffShiftGenerator
    params:
      roster: [staff_alice, staff_bob, staff_carol]
      schedule: weekend_pattern             # named pattern from staff library

  - type: TransactionGenerator
    params:
      correlateWith: TrafficGenerator       # output driven by, not parallel to
      conversionRate: 0.32                  # of customers who reach POS
      avgBasketUSD: 87
      paymentMix: { card: 0.7, mobile: 0.25, cash: 0.05 }   # default if persona doesn't override
      checkoutZone: cashier_main
```

Generators are population-level. They reference personas and journeys from `population`. The orchestrator instantiates them and runs them in parallel against the scenario clock.

`correlateWith` is a hint that one generator's output should drive another (transactions derive from traffic, not free-running).

---

## Section: events

```yaml
events:
  - at: "12:47:00"                          # scenario time, not wall time
    type: Shoplift
    params:
      target:
        fixture: gps_watches_premium
        skuPattern: "garmin-fr-945"
      persona: shoplift_archetype
      exitGate: gate_main
      eas: { tagAlive: true }               # gate fires on exit

  - at: "14:30:00"
    type: PlanogramDeviation
    params:
      fixture: running_shoes_men_endcap
      moveOffsetCm: { x: 25, y: 0 }
      reason: "associate restocked off-spec"

  - at: "08:30:00"
    type: StoreOpen
    params:
      unlockGates: [gate_main, gate_emergency]
      staffArrivalWindow: "08:00-08:30"
```

Events are discrete time-stamped injections. Useful for narrative beats that don't fit a statistical distribution — the LP demo moment, the compliance violation, a staff arrival, the daily store-open.

---

## Worked example: Saturday Rush

```yaml
scenario:
  meta:
    name: "Saturday Rush"
    seed: 1
    rate: 48.0
    startWallclock: "2026-05-09T10:00:00+09:00"
    duration: "4h"
    tenant: "M8trxDemo"
    site: "f3a8b2c1-…"

  population:
    personas: [weekend_runner, family_browser, serious_athlete, shoplift_archetype]
    journeys: [browse_and_leave_running, try_and_buy_apparel, shop_and_buy_athlete, shoplift_premium_watch]

  generators:
    - { type: TrafficGenerator, params: { peakHour: "12:00", expectedTotalCustomers: 120, … } }
    - { type: StaffShiftGenerator, params: { roster: […], schedule: weekend_pattern } }
    - { type: TransactionGenerator, params: { correlateWith: TrafficGenerator, conversionRate: 0.32, … } }

  events:
    - { at: "12:47:00", type: Shoplift, params: { target: {…}, persona: shoplift_archetype } }
```

Five-minute wall demo. Investor sees 4 h of believable Saturday traffic compressed, with one LP moment at minute ~3.5 of wall.

---

## LLM tool surface (Client B)

Each section maps to one or more Anthropic tool definitions. The LLM walks tool calls; the same calls accumulate the same config.

| Tool | Action |
|------|--------|
| `setMeta(name, duration, rate, seed, tenant, site)` | Initialize meta |
| `addPersona(id, …params)` | Register persona |
| `addJourneyTemplate(id, impl, …params)` | Register journey |
| `addGenerator(type, …params)` | Add population generator |
| `addEvent(at, type, …params)` | Inject discrete event |
| `validateScenario()` | Dry-run check; return errors |
| `runScenario()` | Submit to orchestrator |

The LLM doesn't need any platform knowledge. It only needs the tool surface and a prompt like:

> "You're building a M8TRX scenario for [audience] showing [intent]. Use the tools to compose a config; call `runScenario` when complete."

This makes Client B an *integration* against the same Layer 4 contract Client A uses, not a parallel system.

---

## Determinism

Same `seed` + same config = same emitted event stream, byte-for-byte. Critical for:

- **Regression testing** — nightly runs of "A Day in the Life" detect drift in either the simulator or the platform consuming its events
- **Marketing reproducibility** — the screenshot you took yesterday is the screenshot you take tomorrow
- **Investor confidence** — "let me run that exact moment again" works
- **Bug reproduction** — bugs surfaced in a specific scenario can be replayed on any machine

All randomness in Layer 1-3 is sourced from a single seeded PRNG fed by `meta.seed`. No `Random()` without a seed argument anywhere in the codebase — discipline enforced via lint.

---

## Validation

Before `runScenario()` proceeds:

- All persona/journey IDs in generators resolve to definitions in `population`
- All zone IDs in events resolve to seed data in M8trxDemo tenant (probe via Hasura query)
- All SKU patterns in events match at least one SKU in the curated catalog
- Total expected customers + staff doesn't exceed a sanity ceiling (avoid accidental DDoS of mother)
- Time arithmetic checks: `start + duration` doesn't overflow; events fall inside scenario window
- Tenant + site are reachable with current api_key

Validation runs in the orchestrator before any Layer 0 emit fires. Failed validation = no events sent.

---

## Open design questions (decide in focused session)

1. **Canonical config format** — YAML, JSON, or builder DSL (type-safe Kotlin/TypeScript)? YAML is human-friendliest, JSON is LLM-friendliest, builder DSL is correctness-friendliest. Pick one as canonical, support transforms to/from the others.
2. **Generator implementation contract** — what interface do `TrafficGenerator`, `StaffShiftGenerator` etc. implement? Probably `interface Generator { start(clock, ctx); tick(scenarioTime); stop() }` or similar. Lock the shape now; downstream depends on it.
3. **Clock / event-bus** abstraction — does the orchestrator own a virtual clock that ticks generators and events, or do generators schedule themselves on a shared scheduler? Affects rate-control mechanics, especially for `rate=0` (manual step) mode.
4. **Failure behavior** — `meta.failurePolicy` listed as `halt | skip-and-log | retry-3x`. Default? My read: `skip-and-log` for production demos (don't break the show), `halt` for dev (catch issues immediately). Should this be settable per-generator instead of scenario-wide?
5. **Live vs recorded mode** — is a "recording" of a scenario simply the seed+config (since deterministic), or do we capture the emitted event stream too? If just the seed, replays are perfect; storage is tiny. If we capture, scrubbing becomes possible (jump to minute 47 of a 4 h scenario without re-running everything).
6. **Cross-generator correlation** — `correlateWith` is named in the strawman but not specified. Is it a pub-sub of "events I emitted" the dependent generator subscribes to? Lock the protocol now if generators will commonly depend on each other (almost certainly yes — transactions on traffic, fitting-room events on traffic, restock events on inventory state).
7. **Multi-site scenarios** — a scenario binds to one site in this strawman. Do we ever need multi-site scenarios (e.g., chain-wide Black Friday)? If yes, `meta.site` becomes `meta.sites: []` and routing logic appears. Probably defer; first deliverable is single-site.

These are the questions to answer in the design session before any code below Layer 4 is written.

---

## Why this discipline matters

The 10-15% extra design effort in shaping this schema up front buys:

- **Free LLM authoring** — Client B is a 1-2 day add when Layer 4 is stable
- **Free reproducibility** — determinism falls out of the seed contract
- **Free regression testing** — nightly runs of fixed configs detect drift
- **Free marketing assets** — screenshots from a deterministic run never go stale
- **Free customer-self-serve** — when M8TRX Twin becomes a customer-facing product, the surface is already shaped right

Skipping this discipline and writing Saturday Rush imperatively means re-doing all of the above as a 2-3 week refactor when the LLM client lands. The cost is paid once, here, in this design session.
