# Layer 4 — Scenario Configuration Schema

**Status:** LOCKED 2026-05-10 (Step A complete). Q1–Q7 locked Sessions 1–2; Layer 2 Journey contract + DomainEvent v1 taxonomy + Persona schema + persistence-layer plan locked Session 2. Code below Layer 4 may proceed.

The architectural commitment of m8trx-twin. The shape of a scenario config is the contract between authors (human or LLM) and the orchestrator. Locked here; everything else is downstream.

**Sibling docs:**

- `PERSONA-SCHEMA.md` — Layer 2 actor templates (Shopper / Operator / Buyer kinds; Volere 2d/2e anchored)
- `TWIN-DB-AND-GRAPH.md` — persistence layer (twin's own PG database; no standalone Hasura)
- `SNAPSHOT-FORMAT.md` — `meta.openingState` JSON shape (one section per Layer 1 table)

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
  openingState: ref/snapshots/decathlon-running-small/day-start.json   # tenant-state seed (inventory, fixtures, zones)
  capture: true                                  # write atoms.log + bus.log (see Runtime model § Q6)
```

- `rate=1.0` is real-time (4 h scenario takes 4 h to play)
- `rate=48.0` compresses 4 h into 5 min wall
- `rate=0` allows step-by-step manual advancement (debugging)
- `openingState` points at a snapshot of `item_identifier` + `thing_location` + `space` + `zone` + `fixture` rows that the orchestrator seeds the tenant from before `t = startWallclock`. Things generators presume there is stock to move — opening state is where it comes from. Snapshot-based seeding mirrors real customer onboarding (CSV import + initial RFID encoding walk). Replays use the same snapshot byte-for-byte for determinism.
  - **Two upstream design tasks produce the snapshot:**
    - **Store layout authoring** (`reference/data/STORE-LAYOUT.md`, TBD) — defines `space` + `zone` + `fixture` rows. Authored from research (Decathlon store walkthroughs, public press kits, store concept renders) at as-realistic-as-possible fidelity for the target footprint. Small running specialty (~30 m × 50 m) first; larger store classes later.
    - **SKU curation** (`reference/data/SKU-CURATION.md`, TBD) — defines `item_identifier` + `thing_location` rows. Authored from the Decathlon Korea catalog (~20k items), curated down to ~2-3k SKUs by category, variant-expanded by size/color, anchored to fixtures from the layout.
  - **Multiple snapshots planned**, keyed by store class. Path convention: `ref/snapshots/<store-class>/<state>.json` (e.g., `decathlon-running-small/day-start.json`, `decathlon-flagship-large/day-start.json`).
- `capture: true` enables writing both `atoms.log` (Layer 0 emissions) and `bus.log` (DomainEvents). See § Runtime model (Q6).

`tenant` and `site` are the M8TRX targets all events get scoped to. `seed` drives all randomness in Layers 1-3 from a single PRNG; same seed + same config = byte-for-byte identical event stream.

---

## Section: population

```yaml
population:
  personas:                        # see PERSONA-SCHEMA.md for the full field surface
    - id: shopper.kr.sportinggoods.weekend_runner
      type: shopper
      market: KR
      vertical: RETAIL
      type: SPORTING_GOODS
      walkSpeedMps: 1.4
      dwellTendency: medium
      basketSizeDist: { mean: 95, sigma: 30, currency: KRW }
      paymentMix: { CARD: 0.65, MOBILE: 0.30, CASH: 0.05 }

    - id: operator.kr.sportinggoods.staff_associate     # populated from Volere 2e via twin's persona seed loader
      type: operator
      market: KR
      vertical: RETAIL
      type: SPORTING_GOODS
      m8trxRole: STAFF
      primaryInterface: HANDHELD_ANDROID
      # subjectMatterExp / technologicalExp / biography pulled from PersonaLibrary at orchestrator boot

  journeys:
    - id: browse_and_leave_running
      impl: BrowseAndLeave                # Layer 2 class name; see § "Layer 2 — Journey contract"
      params:
        interestZones: [running_shoes, gps_watches]
        dwellMinutesPerZone: { min: 2, max: 8 }
        exitProbAfterEachZone: 0.3
    - id: try_and_buy_apparel
      impl: TryOnAndBuy
      params:
        tryItemCount: { min: 2, max: 4 }
        keepItemRatio: { min: 0.3, max: 0.7 }
        tryOnZones: [running_apparel_try_on]
    - id: shoplift_premium_watch
      impl: Shoplift
      params:
        target: { fixture: gps_watches_premium, skuPattern: "garmin-fr*" }
        exitGate: gate_main
```

Personas are reusable identity templates — they capture *who* and *how they act* (full schema at `PERSONA-SCHEMA.md`). Journeys reference Layer 2 `Journey` implementations by `impl` name; `params` is forwarded to `Journey.start(ctx, actor, params)` as untyped `JsonObject` and parsed inside the concrete journey.

---

## Section: generators

```yaml
generators:
  # People — who is in the store
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

  # Things — stock movement
  - type: ShipmentArrivalGenerator
    params:
      arrivalTime: "07:00"
      skuMix: weekly_replenishment
      itemCount: 800
      receivingZone: backroom_dock

  - type: RestockGenerator
    params:
      triggerLowStockBelow: 0.30            # restock fixture when stock drops below 30% of capacity
      staffPool: [staff_alice, staff_bob]
      backroomZone: backroom_main

  - type: ReturnsGenerator
    params:
      rateOfRecentSales: 0.05               # 5% of completed sales return within scenario window
      returnDelayHoursDist: { min: 4, max: 72 }

  - type: ShrinkageGenerator
    params:
      dailyTarget: 4
      profile: high_value_focused           # premium SKUs preferred
      modes: [theft, damage, miscount]

  # Space — layout state
  - type: PlanogramDriftGenerator
    params:
      fixturesPerDay: 2
      maxOffsetCm: 30
      driftMode: associate_restock_off_spec

  # Cross-cutting (Trinity intersections)
  - type: TransactionGenerator
    params:
      conversionRate: 0.32                  # subscribes to CustomerReachedZone(checkout) — see Runtime model § Q6
      avgBasketUSD: 87
      paymentMix: { card: 0.7, mobile: 0.25, cash: 0.05 }   # default if persona doesn't override
      checkoutZone: cashier_main
```

Generators are population-level and organized by **M8TRX Trinity** dimension: People (who), Things (what), Space (where), plus Cross-cutting intersections. The orchestrator instantiates them and runs them in parallel against the scenario clock. Full catalog: § Generator catalog (v1 target) below.

Cross-generator dependencies are NOT declared in YAML. They are wired via explicit `bus.subscribe(...)` calls in generator code — see § Runtime model (Q6) for the EventBus contract. The old `correlateWith` YAML hint is obsolete.

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

## Generator catalog (v1 target)

The full set of generator types Twin v1 targets, organized by **M8TRX Trinity** dimension. Without coverage in all three dimensions a scenario only exercises a slice of the platform — the demo value of "A Day in the Life" requires People + Things + Space streams running concurrently and correlating through the bus.

### People — *who is in the store*

| Generator | What it drives |
|---|---|
| `TrafficGenerator` | Customer arrivals, persona/journey assignment, zone walks |
| `StaffShiftGenerator` | Staff arrivals, breaks, end-of-shift; staff persona profiles distinct from customers |

### Things — *stock movement*

Each generator drives `item_custody_event` transitions, RFID scans, and POS-or-non-POS movement. Drives the inventory + LP demos.

| Generator | What it drives |
|---|---|
| `ShipmentArrivalGenerator` | Morning trucks. Creates new `item_identifier` rows, EPC bindings, places stock in receiving zone. Triggers RFID scan-in events |
| `RestockGenerator` | Staff moves items from backroom → shop-floor fixtures. Driven by low-stock detection or staff schedule. Generates RFID hand-held reader scans + `thing_location` updates |
| `ReturnsGenerator` | Customer returns sold items. Negates a sale, creates custody event back to inventory, routes to backroom-hold zone. Subscribes to `SaleCompleted` (delayed by hours/days) |
| `TransferGenerator` | Inter-zone or inter-store moves (rare; useful for chain demos later) |
| `ShrinkageGenerator` | Items disappear without a sale. Theft (visible journey), damage (silent), miscount (silent). The ghost the LP shrinkage dashboard chases |
| `MarkdownGenerator` | Price changes on existing stock. Drives the conversion-on-markdown analytics |
| `AdjustmentGenerator` | Manual stocktake reconciliation corrections (rare; subscribes to `StocktakeReconciled`) |
| `StocktakeGenerator` | Periodic count walks. Both the staff walk AND the discrepancies it generates |

### Space — *layout / fixture state*

Drives the compliance demo. Without Space generators, `zone_history` and the planogram-drift narrative are dead.

| Generator | What it drives |
|---|---|
| `PlanogramDriftGenerator` | Ambient deviation — fixtures drift from spec position over days as associates restock off-spec. Generates `space_history` rows |
| `FixtureRelocationGenerator` | Major repositions — end-cap rotations, seasonal layouts. Discrete enough that it can also live in `events:` |
| `ZoneReconfigurationGenerator` | Boundary changes — fitting room converted to try-on bench, sub-zone carved out. Maps to `zone_history` |
| `SignageChangeGenerator` | Promotional signage moves; tied to commerce campaigns. Bridges Space + Things |

### Cross-cutting (Trinity intersections)

| Generator | What it drives | Crosses |
|---|---|---|
| `TransactionGenerator` | POS sales | People × Things |
| `ItemDisplacementGenerator` | Customer picks up an item, walks N zones, abandons it on the wrong shelf. Generates `thing_location` movement with no sale. Source of "items in wrong location" report | People × Things × Space |
| `EasGenerator` | Gate alarms on EAS-tagged items | People × Things × Space |
| `TryOnZoneGenerator` | Customer enters try-on zone with items, returns subset. Apparel rooms / footwear bench / GPS watch demo, kind-discriminated per mig 127 | People × Things × Space |
| `ComplianceDriftGenerator` | Slow drift across all three dimensions surfaced by FR-INTEL-23 / FR-COMP-16 demos | All three |

### Coverage discipline

Scenario authors should treat Trinity coverage as the default. A scenario with only People generators emits no inventory or layout signal — M8TRX Fusion has nothing to fuse. The "A Day in the Life" reference scenario is the integration test: it runs every generator in this catalog at scenario-realistic cadence.

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
    # People
    - { type: TrafficGenerator, params: { peakHour: "12:00", expectedTotalCustomers: 120, … } }
    - { type: StaffShiftGenerator, params: { roster: […], schedule: weekend_pattern } }
    # Things
    - { type: ShipmentArrivalGenerator, params: { arrivalTime: "07:00", itemCount: 800, … } }
    - { type: RestockGenerator, params: { triggerLowStockBelow: 0.30, … } }
    - { type: ShrinkageGenerator, params: { dailyTarget: 4, profile: high_value_focused, … } }
    # Space
    - { type: PlanogramDriftGenerator, params: { fixturesPerDay: 2, maxOffsetCm: 30 } }
    # Cross-cutting
    - { type: TransactionGenerator, params: { conversionRate: 0.32, … } }

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

## Runtime model

> Locked decisions emerging from Q2/Q3/Q6 of the open design questions below. As each question locks, its design lands here.

### Q2 — Generator interface (LOCKED 2026-05-09)

Two methods. Generators are stateless except for what they capture in subscription closures.

```kotlin
interface Generator {
  val id: String
  fun start(ctx: GeneratorContext)   // called once at scenario start
  fun stop(ctx: GeneratorContext)    // called once at scenario end (cleanup, summary)
}
```

Everything a generator needs to act lives on `GeneratorContext`:

```kotlin
class GeneratorContext {
  val clock: Clock                  // read-only view of scenario time
  val scheduler: Scheduler          // schedule callbacks at scenario time
  val bus: EventBus                 // publish + subscribe to typed domain events
  val rng: Random                   // per-generator stream, forked from meta.seed by id
  val personas: PersonaLibrary
  val journeys: JourneyLibrary
  val emit: AtomEmitters            // Layer 0 atoms (REST / NATS / webhook)
  val tenantSite: TenantBinding     // M8trxDemo + site UUID
  val log: Logger                   // structured per-generator logger
}
```

**Key properties:**

- **No `tick()` method.** Generators don't get woken periodically. They register subscriptions in `start()`; the scheduler and bus drive everything else.
- **Stateless except for subscription closures.** Anything a generator needs to remember between firings is captured in the closure passed to `bus.subscribe` or `scheduler.scheduleAt`. No instance fields holding mutable state — this is what makes replay-from-seed deterministic.
- **Per-generator `rng` stream.** Forked from `meta.seed` deterministically by `id`. Adding a new generator does NOT reshuffle existing generators' random streams; regression tests stay stable across config evolution.
- **No direct generator-to-generator calls.** All cross-generator interaction routes through `bus`. (Replaces the YAML `correlateWith` hint — pending Q6 lock.)
- **`stop()` semantics — report-and-drop.** Anything still in the scheduler queue at scenario end is counted/typed in the capture log and dropped. `stop()` does NOT block to drain the queue — a misbehaving scheduled callback chain could otherwise prevent the scenario from ever closing. Use `stop()` for summary log lines and per-generator capture-file close, not for finishing work in flight.

### Q3 — Scheduler (LOCKED 2026-05-09)

Shared scheduler owned by the orchestrator; generators submit callbacks. Generators do NOT have internal clocks or loops.

```kotlin
interface Scheduler {
  fun scheduleAt(time: ScenarioTime, callback: () -> Unit): ScheduledHandle
  fun scheduleAfter(delay: Duration, callback: () -> Unit): ScheduledHandle
  fun scheduleEvery(period: Duration, callback: () -> Unit, until: ScenarioTime? = null): ScheduledHandle
}

interface ScheduledHandle {
  fun cancel()
  fun rescheduleAt(time: ScenarioTime)
  val isPending: Boolean
}
```

Internally a priority queue keyed by `(scenarioTime, insertionOrder)`. `insertionOrder` is a global monotonic counter incremented on every schedule call — it is the determinism tiebreaker for two callbacks scheduled at the same scenario time.

**`ScenarioTime` type:** `Instant` (absolute, e.g., `2026-05-09T12:47:00+09:00`). Matches the YAML format; the orchestrator's `Clock` returns `Instant`; `events.at` parses to `Instant` against `meta.startWallclock`. `Duration` is computed from `Instant − startWallclock` when needed. One canonical type, one direction of conversion.

**Rate modes:**

| `meta.rate` | Behavior |
|---|---|
| `> 0` (e.g., `48.0`) | Advance clock to next scheduled time; sleep wall-time `= (nextTime − clock.now()) / rate`; fire callback |
| `0` | Pause. Orchestrator exposes `advanceOne()` and `advanceUntil(time)` for manual step debugging |
| `+∞` | Fire next callback immediately, zero wall sleep. Used for regression tests, capture-replay, warm-up phases |

**`events:` YAML pre-loading.** The orchestrator parses the `events` array and pre-loads each entry onto the scheduler **before any generator's `start()` is called**. Events get `insertionOrder` 0..N−1, ahead of any generator-scheduled callbacks. **Events win ties** — a discrete event at `12:47:00` always fires before a generator-scheduled callback also at `12:47:00`. This is intentional: narrative beats the YAML author wrote should be the salient thing at that moment.

**Generator `start()` order.** `start()` is called in the YAML order generators appear in `generators:`. Authors often want a specific generator to register subscriptions before another publishes; YAML order gives them control without a separate dependency-declaration mechanism.

**Cancellation and rescheduling.** All three schedule methods return a `ScheduledHandle` supporting `.cancel()` and `.rescheduleAt(time)`. Useful for "leave at 15:00" cancelled because the customer got arrested at 14:50, or rescheduled because they stopped to chat with staff. Without handles, generators would have to write defensive "is this still valid?" checks inside every callback.

**`scheduleEvery` is a primitive**, not sugar over recursive `scheduleAfter`. Having it first-class lets the scheduler track cadence drift and gives a single `cancel()` for the whole repeating chain.

**Failure policy integration (links to Q4).** The scheduler wraps every callback in a try/catch. `meta.failurePolicy` is read at init:

- `skip-and-log` (prod default) → log structured record (callback id, generator id, scenarioTime, exception, stacktrace), increment per-generator error counter, continue.
- `halt` (dev default) → log, then propagate the exception to abort the scenario.

The wrapping is uniform; only the post-log behavior varies.

**Worked example — `TrafficGenerator` scheduling customer entries:**

```kotlin
class TrafficGenerator(private val params: Params) : Generator {
  override val id = "traffic"

  override fun start(ctx: GeneratorContext) {
    val entryTimes = sampleGaussian(
      n = params.expectedTotalCustomers,
      peak = params.peakHour,
      sigma = params.peakSigmaHours,
      rng = ctx.rng,
    )
    entryTimes.forEachIndexed { i, time ->
      ctx.scheduler.scheduleAt(time) {
        val persona = ctx.rng.weightedPick(params.personaMix)
        val journey = ctx.rng.weightedPick(params.journeyMix)
        ctx.bus.publish(CustomerEntered("cust-$i", persona, journey, ctx.clock.now()))
      }
    }
  }

  override fun stop(ctx: GeneratorContext) {}
}
```

`start()` pre-computes all 120 entry times using `ctx.rng` (deterministic), schedules them, returns. The orchestrator drains the queue. Same seed → same customers → same arrival times → same downstream cascade.

### Q6 — EventBus (LOCKED 2026-05-09)

Cross-generator correlation primitive. Replaces the YAML `correlateWith` string hint with explicit subscriptions in code.

```kotlin
interface EventBus {
  fun <T : DomainEvent> subscribe(type: KClass<T>, handler: (T) -> Unit)
  fun <T : DomainEvent> publish(event: T)
}

interface DomainEvent {
  val at: Instant
}
```

Concrete events are data classes in twin's domain package, not the bus's:

```kotlin
data class CustomerEntered(
  override val at: Instant,
  val customerId: String,
  val personaId: String,
  val journeyId: String,
) : DomainEvent

data class CustomerReachedZone(
  override val at: Instant,
  val customerId: String,
  val zoneId: String,
) : DomainEvent

data class SaleCompleted(
  override val at: Instant,
  val customerId: String,
  val basketUSD: Double,
  val payment: PaymentMethod,
) : DomainEvent
```

**Critical distinction — DomainEvent is NOT a Layer 0 atom.** Atoms go *out* to M8TRX (REST/NATS/webhook). DomainEvents stay *inside* the simulator and are the correlation substrate. A `SaleCompleted` is **not** what M8TRX sees — M8TRX sees a webhook POST. The DomainEvent is what other generators hear *about* the sale.

| Generator action | Goes via |
|---|---|
| Tell M8TRX a customer entered the store | `ctx.emit.objLocation(...)` (Layer 0 atom over NATS) |
| Tell other generators the same customer just entered | `ctx.bus.publish(CustomerEntered(...))` (DomainEvent) |

Both can fire from the same callback. Atom is the M8TRX contract; DomainEvent is the simulator's internal correlation.

**Determinism:**

- Handlers fire synchronously, in publish order, in a single thread. No async fan-out, no thread pools, no coroutine scopes.
- Multiple subscribers to the same event type fire in **subscription order** — i.e., YAML-generator-order (since `start()` is YAML-ordered, Q3). Authors control handler precedence by ordering their generators in YAML.
- **Re-entrant publishes are supported via queue-and-drain.** The topmost `publish` call manages a FIFO worklist; each nested `publish` from inside a handler appends to the worklist; the topmost call drains until empty before returning. No deep stack frames, no recursion bombs, deterministic order.

**`DomainEvent` is an open marker interface, not `sealed`.** Sealed would give compile-time exhaustiveness but forces every event type to be declared in one file — generators in separate modules couldn't add types without central edit. Open marker scales better for the layered architecture.

**No wildcards / pattern subscriptions in v1.** Subscribers register for one concrete type at a time; want three event types, write three subscriptions. Add later if a real case surfaces.

**Validation at start.** After every generator's `start()` runs, the orchestrator walks the publish/subscribe graph:

- Subscriber listens for a type no one publishes → very likely a config bug; that handler will never fire.
- `failurePolicy = halt` (dev default) → fail.
- `failurePolicy = skip-and-log` (prod default) → warn-only.

Inverse case (publisher fires events with no subscribers) is fine — generators may publish "for the record" even when nobody currently cares.

**Capture (links to Q5 — recording mode):**

| Capture stream | Used for |
|---|---|
| `atoms.log` | Layer 0 emissions keyed by scenarioTime — the canonical M8TRX-facing record. Drives Layer-0-replay scrubbing |
| `bus.log` | DomainEvents keyed by scenarioTime — diagnostic artifact answering "which generator told which generator about which event" post-run |

`bus.log` is not needed for replay-from-seed (that re-derives everything from `meta.seed`); it is a forensic artifact. Both streams are gated by `meta.capture: true`.

**Worked example — `TransactionGenerator` subscribes to `TrafficGenerator`:**

```kotlin
class TransactionGenerator(private val params: Params) : Generator {
  override val id = "transactions"

  override fun start(ctx: GeneratorContext) {
    ctx.bus.subscribe(CustomerReachedZone::class) { evt ->
      if (evt.zoneId != params.checkoutZone) return@subscribe
      val persona = ctx.personas[evt.personaId] ?: return@subscribe
      if (ctx.rng.nextDouble() > params.conversionRate) return@subscribe

      val basket = sampleBasket(ctx.rng, persona.basketSizeDistUSD ?: params.avgBasketUSD)
      val payment = ctx.rng.weightedPick(persona.paymentMix ?: params.paymentMix)

      // 45-second checkout latency
      ctx.scheduler.scheduleAfter(Duration.ofSeconds(45)) {
        val saleTime = ctx.clock.now()
        ctx.emit.saleWebhook(SaleEvent(evt.customerId, basket, payment, saleTime))
        ctx.bus.publish(SaleCompleted(saleTime, evt.customerId, basket, payment))
      }
    }
  }

  override fun stop(ctx: GeneratorContext) {}
}
```

`start()` registers a single subscription. The handler fires every time any generator publishes `CustomerReachedZone`. The 45-sec checkout latency is `scheduler.scheduleAfter` — the handler returns immediately. The eventual sale fires both an **atom emit** (out to M8TRX) and a **bus publish** (in for downstream generators).

---

### Layer 2 — Journey contract (LOCKED 2026-05-10)

Layer 2 holds **Journeys** — single-actor arcs with personality (e.g. `BrowseAndLeave`, `ShopAndBuy`, `TryOnAndBuy`, `Shoplift`, `StaffRestock`, `StocktakeWalk`). A journey is **assigned** to an `Actor` (a customer or operator instance) by a Layer 3 generator; the journey then schedules its actor's behavior over scenario time.

```kotlin
interface Journey {
  val id: String                    // matches the YAML config `impl` field, e.g. "BrowseAndLeave"

  /**
   * Invoked once when this journey is assigned to an actor.
   * Schedule first action via ctx.scheduler; subsequent actions chain off scheduled callbacks.
   * Return after scheduling — NOT after completion.
   * Termination is implicit: no further callbacks scheduled and (typically) a terminal
   * DomainEvent published (CustomerExited, RestockCompleted, etc.).
   */
  fun start(ctx: JourneyContext, actor: Actor, params: JsonObject)
}

data class JourneyContext(
  val clock: Clock,                 // scenario-time read-only view
  val scheduler: Scheduler,         // schedule callbacks at scenario time (same instance generators use)
  val bus: EventBus,                // publish + subscribe to typed DomainEvents
  val emit: AtomEmitters,           // Layer 0 atoms (REST / NATS / webhook)
  val personas: PersonaLibrary,
  val rng: Random,                  // forked deterministically from "<journeyId>:<actorId>" — replays stable
  val log: Logger,
)

sealed interface Actor {
  val id: String                    // stable id assigned by the spawning generator
  val persona: Persona              // the persona this actor instantiates (see PERSONA-SCHEMA.md)
}

data class CustomerActor(
  override val id: String,
  override val persona: Persona,
) : Actor

data class OperatorActor(
  override val id: String,
  override val persona: Persona,
  val handheldDeviceId: String? = null,    // set when the operator carries an Android handheld
) : Actor
```

**Why `start()` only — no `tick()` or `stop()`:** journeys are scheduler-driven, identical to generators. The handler chain self-terminates when the actor's last scheduled callback fires; no explicit cleanup hook is needed. Same model as Q2 generators.

**Why `params: JsonObject`:** journey params come from Layer 4 YAML/JSON config (`population.journeys[].params`), where the LLM authoring client (Client B) writes them as untyped JSON. Concrete journey implementations parse `params` into typed structures inside `start()`. This keeps the journey base contract stable across new journey kinds without recompiling the orchestrator.

**Termination convention:** a journey publishes a terminal DomainEvent on its actor's last action so downstream generators can correlate (e.g., `TransactionGenerator` subscribes to `CustomerExited` to clean up basket state). Required terminal events per journey kind:

| Journey kind | Terminal event |
|---|---|
| Customer browse/shop/try-on/shoplift | `CustomerExited` |
| Operator restock / receiving | `RestockCompleted` or `ShipmentArrived` |
| Operator stocktake | `StocktakeReconciled` |

Concrete journeys ship under `com.m8trx.twin.journeys.*` (one class per kind):

```
BrowseAndLeave        — enter, walk N zones, dwell, exit (no purchase)
ShopAndBuy            — enter, browse, pick items, pay at register, exit
TryOnAndBuy           — enter, pick items, fitting room, partial keep, pay, exit
Shoplift              — enter, target SKU, exit without paying (EAS may trigger)
StaffRestock          — operator restocks fixture from receiving area
StocktakeWalk         — operator walks zones with handheld, scanning RFID
```

These are v1 targets; new journey kinds drop in by implementing `Journey` and registering with `JourneyLibrary` at orchestrator boot. Adding a journey does NOT require schema changes — only a Layer 4 config entry under `population.journeys[]` referencing the new `impl` name.

---

### DomainEvent v1 taxonomy (LOCKED 2026-05-10)

Concrete `DomainEvent` types for v1. **DomainEvents are simulator-internal** — they enable cross-generator and cross-journey correlation via the EventBus (Q6). They do NOT cross the Layer 0 boundary; outbound atoms to M8TRX are a separate emission step.

15 events covering customer lifecycle, engagement, commerce, operations, and anomalies. Each carries `at: Instant` (Q6 marker requirement) plus typed payload fields.

```kotlin
sealed interface DomainEvent { val at: Instant }

// — Customer lifecycle —
data class CustomerEntered      (override val at: Instant, val customerId: String, val personaId: String, val gateZoneId: UUID) : DomainEvent
data class CustomerEnteredZone  (override val at: Instant, val customerId: String, val zoneId: UUID, val prevZoneId: UUID? = null) : DomainEvent
data class CustomerExited       (override val at: Instant, val customerId: String, val gateZoneId: UUID, val reason: ExitReason) : DomainEvent
enum class ExitReason { LEFT_STORE, TIMEOUT, EAS_TRIGGERED }

// — Engagement —
data class ItemPickedUp         (override val at: Instant, val customerId: String, val itemIdentifierId: UUID, val fixtureId: UUID) : DomainEvent
data class FittingRoomEntered   (override val at: Instant, val customerId: String, val tryOnZoneId: UUID, val itemsBrought: List<UUID>) : DomainEvent
data class FittingRoomExited    (override val at: Instant, val customerId: String, val tryOnZoneId: UUID, val itemsKept: List<UUID>, val itemsLeft: List<UUID>) : DomainEvent

// — Commerce —
data class SaleCompleted        (override val at: Instant, val customerId: String, val itemIdentifiers: List<UUID>, val totalCents: Long, val payment: PaymentKind) : DomainEvent
data class SaleAbandoned        (override val at: Instant, val customerId: String, val reason: AbandonReason) : DomainEvent
enum class AbandonReason { BASKET_LEFT, PAYMENT_FAILED, LINE_TOO_LONG }
data class ItemReturned         (override val at: Instant, val customerId: String, val itemIdentifierId: UUID, val reason: String?) : DomainEvent

// — Operations —
data class ShipmentArrived      (override val at: Instant, val manifestId: UUID, val expectedItemIdentifiers: List<UUID>) : DomainEvent
data class RestockCompleted     (override val at: Instant, val operatorId: String, val fixtureId: UUID, val itemsAdded: List<UUID>) : DomainEvent
data class StocktakeReconciled  (override val at: Instant, val operatorId: String, val zoneId: UUID, val discrepancyCount: Int) : DomainEvent

// — Anomalies —
data class ItemDisplaced              (override val at: Instant, val itemIdentifierId: UUID, val expectedFixtureId: UUID, val actualFixtureId: UUID, val detectedBy: DisplaceDetector) : DomainEvent
enum class DisplaceDetector { GENERATOR, OBSERVATION }
data class EasAlarmTriggered          (override val at: Instant, val gateZoneId: UUID, val itemIdentifierIds: List<UUID>, val falsePositive: Boolean) : DomainEvent
data class PlanogramDeviationDetected (override val at: Instant, val fixtureId: UUID, val deviationKind: DeviationKind) : DomainEvent
enum class DeviationKind { WRONG_SKU, WRONG_QTY, EMPTY }
```

**Notable v1 omissions** (deferred to v2 to keep the bus surface small):

- `CustomerExitedZone` — derivable from successive `CustomerEnteredZone` events; not needed at this fidelity
- `ItemPutDown` — pickup-without-purchase is captured by absence of `SaleCompleted` referencing the item; explicit put-down only matters for engagement-pattern analytics (post-MVP)
- `RestockBegan` — `RestockCompleted` is sufficient for v1; begin-event matters only if downstream generators need to react during the restock
- `CustomerDwellStarted` / `CustomerDwellEnded` — dwell is an observation derived from `CustomerEnteredZone` timestamps; first-class events would inflate the bus

**Subscription discipline:** generators subscribe by event class via `bus.subscribe(SaleCompleted::class) { … }`. No wildcards in v1 (per Q6 lock). When a v2 event is added, existing subscribers are unaffected.

---

## Locked design decisions (Q1–Q7)

All Q1–Q7 from the original strawman are now locked. Recap with land-points:

| Q | Decision | Land-point |
|---|---|---|
| Q1 | **Canonical config format = JSON** (LLM-friendliest); human surface authored on top via templates / DSL | This doc § Top-level shape |
| Q2 | **Generator interface = `Generator { start(ctx); stop(ctx) }`**; stateless except subscription closures; no `tick()` | § Runtime model § Q2 |
| Q3 | **Shared scheduler owned by orchestrator**; priority queue keyed by `(scenarioTime, insertionOrder)`; rate modes >0 / 0 / +∞; events pre-loaded ahead of generator `start()` | § Runtime model § Q3 |
| Q4 | **Failure policy default = `skip-and-log`** for production demos, `halt` for dev; settable per-scenario via `meta.failurePolicy`; per-generator override possible but not required at v1 | § Section: meta |
| Q5 | **Capture-and-replay enabled by default for production demos.** When `meta.capture: true`, orchestrator writes `runs/<id>/atoms.log` + `bus.log`; replay reads these byte-for-byte | § Section: meta |
| Q6 | **EventBus = `subscribe(KClass<T>, handler)` + `publish(event)`**; synchronous in-publish-order; re-entrant via queue-and-drain; no wildcards in v1; bus.log written when capture is on | § Runtime model § Q6 |
| Q7 | **Multi-site scenarios deferred** — `meta.site` stays singular at v1; multi-site routing logic added when first chain-wide scenario is filed | § Section: meta |

Plus three Step A locks added 2026-05-10:

| Lock | Decision |
|---|---|
| Stack | **Kotlin** (matches m8trx-services / -edge / -android). See `status/STATUS.md` § Open Decisions |
| Persona schema | **3 sealed kinds (Shopper / Operator / Buyer); 9 shared fields incl. `vertical` + `type`; PersonaBiography optional sub-record.** Full surface at `PERSONA-SCHEMA.md` |
| Layer 2 Journey contract | `interface Journey { fun start(ctx, actor, params) }` — scheduler-driven, scheduler chains, terminal events. § Layer 2 above |
| DomainEvent v1 taxonomy | 15 typed events covering customer lifecycle / engagement / commerce / operations / anomalies. § DomainEvent v1 above |
| Persistence + graph layer | Twin owns dedicated PG database (separate db on mother instance); no standalone Hasura; embedded `graphql-kotlin` when graph layer earns its keep. Full plan at `TWIN-DB-AND-GRAPH.md` |

---

## Open design questions

All Q1–Q7 closed. See § "Locked design decisions (Q1–Q7)" above for the recap and land-points.

Future questions surface as scenarios push the schema. Append here as `Q8`, `Q9`, … with the same `LOCKED YYYY-MM-DD` pattern when answered.

---

## Why this discipline matters

The 10-15% extra design effort in shaping this schema up front buys:

- **Free LLM authoring** — Client B is a 1-2 day add when Layer 4 is stable
- **Free reproducibility** — determinism falls out of the seed contract
- **Free regression testing** — nightly runs of fixed configs detect drift
- **Free marketing assets** — screenshots from a deterministic run never go stale
- **Free customer-self-serve** — when M8TRX Twin becomes a customer-facing product, the surface is already shaped right

Skipping this discipline and writing Saturday Rush imperatively means re-doing all of the above as a 2-3 week refactor when the LLM client lands. The cost is paid once, here, in this design session.
