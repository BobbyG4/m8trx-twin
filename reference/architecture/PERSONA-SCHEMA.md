# Persona Schema — Layer 2 Actor Templates

**Status:** LOCKED 2026-05-10
**Stack:** Kotlin (canonical data classes; JSON wire format derived via `kotlinx.serialization`)
**Sibling:** `LAYER4-CONFIG-SCHEMA.md` (this schema replaces the strawman `population.personas` block in §"Section: population")

Anchors back to the source-of-truth Volere stakeholder docs in `m8trx-shared`:

- **`requirements/A. Project Drivers/2. Stakeholders/2d. Hands-on Users of the Product.md`** — abstract user *categories* (C-level → Store Staff → ITX Admin → End Customer). Defines the role/capability surface a persona may bind to.
- **`requirements/A. Project Drivers/2. Stakeholders/2e. Personas.md`** — eight named, biographically-developed personas (Park Ji-Yeon, Cho Sung-Min, Lee Hyejin, Kim Dohyun, Jung Min-Seo, Oh Ji-Soo, Yoon Tae-Yang, Park So-Yeon) plus US and EU market adaptation guides. Each named persona ships with an `Agent Prompt` block designed for LLM simulation — twin's `BuyerPersona` carries this verbatim.

Twin does **not** duplicate persona content from 2e. The schema gives the *shape*; named instances are extracted on-demand from the 2e markdown (or, when scenarios start needing them mechanically, into typed seed files at `reference/data/personas/seed/`). One artifact, two read points.

---

## Three persona kinds

```kotlin
sealed interface Persona {
  val id: String                          // stable id; format "<kind>.<market>.<type>.<slug>" e.g. "operator.kr.apparel.soyeon"
  val displayName: String                 // human-readable label; for named personas this is the realName
  val market: Market                      // KR | US | EU — drives MarketProfile defaults + persona-pool filter
  val vertical: Vertical                  // RETAIL only at MVP; HEALTHCARE / MANUFACTURING / … post-MVP
  val type: VerticalType                  // sub-classification within the vertical; RetailType for MVP
  val locale: String                      // BCP-47; defaults to MarketProfile.defaultLocale unless overridden
  val subjectMatterExp: ExperienceLevel   // Volere 2d — novice | journeyman | master
  val technologicalExp: ExperienceLevel   // Volere 2d — novice | journeyman | master
  val biography: PersonaBiography?        // null for statistical shoppers; populated from Volere 2e for named personas
}

enum class ExperienceLevel { NOVICE, JOURNEYMAN, MASTER }
enum class Market { KR, US, EU }

enum class Vertical {
  RETAIL,           // MVP — only vertical covered today; Volere 2d/2e + Decathlon demo all live here
  HEALTHCARE,       // post-MVP — different personas, HIPAA reshapes the architecture, not just labels
  MANUFACTURING,    // post-MVP — work-in-progress tracking, tool location, line monitoring
  LOGISTICS,        // post-MVP — DC / 3PL operations, yard management
  HOSPITALITY,      // post-MVP — hotel guest flow, F&B venue ops
  GENERAL,          // vertical-agnostic fallback (rare; e.g. a security guard archetype reusable across all)
}

sealed interface VerticalType {
  val vertical: Vertical
  val name: String
}

enum class RetailType : VerticalType {
  APPAREL,                // Volere 2e baseline (Hansung Fashion Group); MVP primary
  SPORTING_GOODS,         // Decathlon demo store (running + fitness specialty); in twin scope on day one
  GROCERY,                // post-MVP
  DIY_HARDWARE,
  ELECTRONICS,
  LUXURY,
  DEPARTMENT_STORE,
  GENERAL_RETAIL;         // cross-type fallback within retail

  override val vertical: Vertical = Vertical.RETAIL
}

// Future-shape sketches (NOT implemented at MVP — listed for clarity only):
//   enum class HealthcareType    : VerticalType { HOSPITAL, CLINIC, PHARMACY, … }
//   enum class ManufacturingType : VerticalType { DISCRETE, PROCESS, ASSEMBLY, … }
//   enum class LogisticsType     : VerticalType { DC_3PL, YARD, COLD_CHAIN, … }
```

9 shared fields. Three concrete kinds extend with kind-specific surface.

> **Why a two-layer (`vertical` + `type`) model?** They handle different magnitudes of difference:
>
> - **Cross-vertical differences are architectural.** Healthcare's HIPAA is not a regulatory overlay — it changes how VisionAI tracks people, what data leaves the edge, what an `item` even is. Manufacturing's `item` is a work-in-progress unit on a line, not a SKU on a fixture. These are different products built on the same M8TRX substrate.
> - **Cross-type differences within a vertical are operational.** Apparel vs sporting goods vs grocery share the same architecture — same Trinity, same Layer 1/2/3 split, same RLS shape. They differ in attribute schemas, KPI flavoring, and behavioral profiles.
>
> MVP is `vertical = RETAIL` for everything. Post-MVP verticals plug in as new `VerticalType` enums under `Vertical.HEALTHCARE` (etc.) without disturbing the persona core.

> **Why both `Market` and (`vertical`, `type`)?** Three orthogonal axes. Decathlon Korea is `(KR, RETAIL, SPORTING_GOODS)`; a US apparel scenario is `(US, RETAIL, APPAREL)`; a future Korean hospital scenario is `(KR, HEALTHCARE, HOSPITAL)`. The Volere 2e named personas are all `(KR, RETAIL, APPAREL)` because that's where the Volere doc anchored — the schema does not bake that assumption.

### `ShopperPersona` — statistical population

Drives in-store crowd. Twin invents these archetypes; Volere 2d only sketches the End Customer category in the Phase-2 section, which is a thin specification at this layer (a tracked subject, not a UI user). Not biographically named — `biography` is typically `null`. ~120-300 instances per scenario, generated by `TrafficGenerator`.

```kotlin
data class ShopperPersona(
  override val id: String, override val displayName: String,
  override val market: Market, override val locale: String,
  override val subjectMatterExp: ExperienceLevel,
  override val technologicalExp: ExperienceLevel,
  override val biography: PersonaBiography? = null,

  val walkSpeedMps: Double,                             // 0.6–1.8 typical
  val dwellTendency: DwellTendency,                     // LOW | MEDIUM | HIGH
  val basketSizeDist: AmountDist?,                      // null = browser, no purchase intent
  val paymentMix: Map<PaymentKind, Double>?,            // sums to 1.0 if present; null = use MarketProfile default
  val riskProfile: RiskProfile = RiskProfile.NORMAL,    // NORMAL | SHOPLIFT | RETURN_FRAUD
  val accompaniment: Accompaniment? = null,             // ALONE | PARTNER | KIDS_IN_TOW | GROUP
) : Persona

enum class DwellTendency { LOW, MEDIUM, HIGH }
enum class PaymentKind { CARD, MOBILE, CASH, BNPL }
enum class RiskProfile { NORMAL, SHOPLIFT, RETURN_FRAUD }
enum class Accompaniment { ALONE, PARTNER, KIDS_IN_TOW, GROUP }
data class AmountDist(val mean: Double, val sigma: Double, val currency: String)  // currency defaults from MarketProfile
```

7 + 6 = 13 fields. `paymentMix` and `currency` default from `MarketProfile` so most shopper instances stay terse.

### `OperatorPersona` — drives in-store ops journeys

Acts on M8TRX. Bound to a M8TRX role + capability set. Volere 2d categories that map here: Store Staff, Store Manager, Visual Merchandiser, Merchandise Planner, Regional/District Sales Manager, Brand Manager, Designer, Online Operations, Logistics/Distribution, Store Designer, Lease-Hold Management, ITX System Admin. Volere 2e named instances: Hyejin (Store Manager), Dohyun (Brand Manager), Min-Seo (Merchandise Planner), Ji-Soo (VM), Tae-Yang (Regional Sales), So-Yeon (Store Staff).

```kotlin
data class OperatorPersona(
  override val id: String, override val displayName: String,
  override val market: Market, override val locale: String,
  override val subjectMatterExp: ExperienceLevel,
  override val technologicalExp: ExperienceLevel,
  override val biography: PersonaBiography? = null,

  val m8trxRole: M8trxRole,                             // platformAdmin | tenantAdmin | siteManager | staff | member
  val capabilityOverrides: Set<String> = emptySet(),    // for tests; default = role's resolved caps
  val primaryInterface: Interface,                      // WEB_DESKTOP | WEB_MOBILE | HANDHELD_ANDROID
  val workingHours: TimeWindow? = null,                 // shift definition; null for HQ-class roles
) : Persona

enum class M8trxRole { PLATFORM_ADMIN, TENANT_ADMIN, SITE_MANAGER, STAFF, MEMBER }
enum class Interface { WEB_DESKTOP, WEB_MOBILE, HANDHELD_ANDROID }
data class TimeWindow(val startHour: Int, val endHour: Int, val daysOfWeek: Set<DayOfWeek>)
```

7 + 4 = 11 fields. `m8trxRole` aligns to the locked role taxonomy in `m8trx-shared/CLAUDE.md` § Permissions. `capabilityOverrides` exists for negative-path tests (e.g., simulate a staff member with one capability revoked); leave empty for production-fidelity scenarios.

### `BuyerPersona` — drives sales/eval scenarios

Used by Client B (LLM authoring). Each Volere 2e named buyer ships with an `Agent Prompt` block — twin loads it verbatim into a Claude session and the persona becomes a conversational simulator (objection-raising, demo-evaluating, marketing-message-vetting). Volere 2e named instances: Ji-Yeon (Economic Buyer), Sung-Min (Technical Buyer), Hyejin/Min-Seo/Ji-Soo/Tae-Yang/So-Yeon (User Buyers / Frontline), Dohyun (Coach).

```kotlin
data class BuyerPersona(
  override val id: String, override val displayName: String,
  override val market: Market, override val locale: String,
  override val subjectMatterExp: ExperienceLevel,
  override val technologicalExp: ExperienceLevel,
  override val biography: PersonaBiography? = null,

  val buyingTeamRole: BuyingTeamRole,                   // ECONOMIC | TECHNICAL | USER | COACH (Strategic Selling)
  val keyObjection: String,
  val winningMessage: String,
  val agentPrompt: String,                              // ready-to-load Claude system prompt; verbatim from Volere 2e
) : Persona

enum class BuyingTeamRole { ECONOMIC, TECHNICAL, USER, COACH }
```

7 + 4 = 11 fields. The Strategic Selling four-role classification is a fixed taxonomy from Volere 2e § "Personas" intro; not extensible.

### Same-person dual-hat handling

Hyejin, Min-Seo, Ji-Soo, Tae-Yang, So-Yeon each appear in 2e as **User Buyers** (eval-stage) and operate the platform as **Operators** (production-stage). Schema convention: **two separate persona records sharing a biography reference**.

```
operator.kr.soyeon  → OperatorPersona, biography = personaBio.kr.soyeon
buyer.kr.soyeon     → BuyerPersona,    biography = personaBio.kr.soyeon  (same blob, by reference)
```

Cleaner than a discriminated-by-context dispatch. Scenarios that need both contexts (e.g., a sales-eval scenario that includes a real-floor-walk demo) load both records.

---

## `PersonaBiography` — Volere 2e biographical surface

All optional. Populated from 2e for named personas; left null for statistical shoppers.

```kotlin
data class PersonaBiography(
  val realName: String? = null,                         // e.g. "Park So-Yeon (박소연)"
  val age: Int? = null,
  val title: String? = null,
  val location: String? = null,                         // e.g. "Mapo-gu, Seoul"
  val family: String? = null,
  val education: String? = null,
  val hobbies: String? = null,
  val dailyLife: String? = null,                        // multi-paragraph narrative from 2e § Daily Life
  val character: String? = null,                        // 2e § Character
  val attitudeToTech: String? = null,                   // 2e § Attitude to Technology
  val painPoints: List<String> = emptyList(),           // 2e § Pain Points (bullets)
  val whatMoves: List<String> = emptyList(),            // 2e § What Moves Her/Him (bullets)
  val whatBreaks: List<String> = emptyList(),           // 2e § What She/He Doesn't Want to Hear / What Will Break the System
  val designNote: String? = null,                       // 2e § Critical Design Note (e.g. So-Yeon's "data source" note)
)
```

13 fields, all optional. Mirrors 2e's narrative subsections so extraction is mechanical.

---

## `MarketProfile` and `BusinessOpsProfile` — environment-level defaults

Two orthogonal profile types supply defaults so persona records stay terse:

- **`MarketProfile`** keyed on `Market` — regulatory regime, currency, locale, payment-mix priors, decision-making style. Captures *where* the scenario runs.
- **`BusinessOpsProfile`** keyed on `VerticalType` — operating-hour convention, return policy, peak-day pattern, try-on-zone presence, EAS posture, basket-class distribution. Captures *what kind of store* (or hospital, factory, DC) the scenario runs in.

Volere 2e dedicates separate adaptation guides for KR (default), US (line 780), and EU (line 952) covering market-level differences. Type-level differences are not yet documented in Volere — they emerge as twin scenarios push beyond apparel (Decathlon sporting goods is already non-apparel within the same `RETAIL` vertical).

```kotlin
data class MarketProfile(
  val market: Market,
  val regulatoryRegime: RegulatoryRegime,               // PIPA | CCPA | GDPR
  val defaultLocale: String,                            // BCP-47
  val currency: String,                                 // ISO 4217
  val paymentMixPrior: Map<PaymentKind, Double>,        // statistical default for unspecified shoppers
  val retailFormatHint: RetailFormat,                   // MID_TIER_SPECIALTY | MASS_MARKET | LUXURY | DEPARTMENT
  val decisionStyle: DecisionStyle,                     // CONSENSUS | INDIVIDUAL | HIERARCHICAL
  val complianceOverlay: List<String> = emptyList(),    // free-form notes; e.g. ["EU works council consultation required for VisionAI"]
)

enum class RegulatoryRegime { PIPA, CCPA, GDPR }
enum class RetailFormat { MID_TIER_SPECIALTY, MASS_MARKET, LUXURY, DEPARTMENT }
enum class DecisionStyle { CONSENSUS, INDIVIDUAL, HIERARCHICAL }
```

The orchestrator looks up the active `MarketProfile` from `scenario.meta.market` and supplies defaults to any persona that left `locale`, `paymentMix`, or `currency` unspecified. Built-in profiles ship for `KR`, `US`, `EU`; custom markets can be defined in scenario configs.

### Built-in market profiles (reference)

```kotlin
val MARKET_KR = MarketProfile(
  market = Market.KR, regulatoryRegime = PIPA,
  defaultLocale = "ko-KR", currency = "KRW",
  paymentMixPrior = mapOf(CARD to 0.65, MOBILE to 0.30, CASH to 0.05),
  retailFormatHint = MID_TIER_SPECIALTY, decisionStyle = CONSENSUS,
)
val MARKET_US = MarketProfile(
  market = Market.US, regulatoryRegime = CCPA,
  defaultLocale = "en-US", currency = "USD",
  paymentMixPrior = mapOf(CARD to 0.55, MOBILE to 0.20, CASH to 0.15, BNPL to 0.10),
  retailFormatHint = MID_TIER_SPECIALTY, decisionStyle = INDIVIDUAL,
)
val MARKET_EU = MarketProfile(
  market = Market.EU, regulatoryRegime = GDPR,
  defaultLocale = "en-GB", currency = "EUR",
  paymentMixPrior = mapOf(CARD to 0.55, MOBILE to 0.25, CASH to 0.20),
  retailFormatHint = MID_TIER_SPECIALTY, decisionStyle = HIERARCHICAL,
  complianceOverlay = listOf("EU works council consultation required for VisionAI deployment"),
)
```

Numbers are sketched from Volere 2e's market-adaptation tables; refine as concrete data lands.

### `BusinessOpsProfile` — vertical-level defaults

```kotlin
data class BusinessOpsProfile(
  val type: VerticalType,
  val operatingHours: OperatingHours,                   // typical open/close cadence; e.g. "10:00–22:00 daily" for KR mid-tier specialty
  val peakDayPattern: PeakDayPattern,                   // WEEKEND_HEAVY | EVENING_HEAVY | LUNCH_HEAVY | EVEN
  val returnPolicyDays: Int,                            // 14 (KR apparel norm) | 30 (US apparel norm) | 90 (Decathlon)
  val tryOnZonesPresent: Boolean,                       // apparel/sporting yes; grocery/electronics no
  val easPosture: EasPosture,                           // ALL_PREMIUM | NONE | TAGGED_HIGH_VALUE_ONLY | RFID_CASE_LEVEL
  val basketClassDistribution: Map<BasketClass, Double>,// SINGLE_ITEM | MULTI_ITEM | LARGE_BASKET | EXPLORATORY (no-purchase)
  val typeNotes: List<String> = emptyList(),            // free-form; e.g. ["food safety reporting required", "appointment booking common in luxury"]
)

enum class PeakDayPattern { WEEKEND_HEAVY, EVENING_HEAVY, LUNCH_HEAVY, EVEN }
enum class EasPosture { ALL_PREMIUM, NONE, TAGGED_HIGH_VALUE_ONLY, RFID_CASE_LEVEL }
enum class BasketClass { SINGLE_ITEM, MULTI_ITEM, LARGE_BASKET, EXPLORATORY }
data class OperatingHours(val openHour: Int, val closeHour: Int, val daysOfWeek: Set<DayOfWeek>)
```

### Built-in business-ops profiles (reference)

```kotlin
val OPS_RETAIL_APPAREL = BusinessOpsProfile(
  type = RetailType.APPAREL,
  operatingHours = OperatingHours(10, 22, allDays),
  peakDayPattern = WEEKEND_HEAVY,
  returnPolicyDays = 14,
  tryOnZonesPresent = true,
  easPosture = TAGGED_HIGH_VALUE_ONLY,
  basketClassDistribution = mapOf(SINGLE_ITEM to 0.4, MULTI_ITEM to 0.4, EXPLORATORY to 0.2),
)
val OPS_RETAIL_SPORTING_GOODS = BusinessOpsProfile(
  type = RetailType.SPORTING_GOODS,
  operatingHours = OperatingHours(10, 22, allDays),
  peakDayPattern = WEEKEND_HEAVY,
  returnPolicyDays = 90,                                // Decathlon norm
  tryOnZonesPresent = true,                             // apparel try-on + footwear bench + GPS-watch demo
  easPosture = TAGGED_HIGH_VALUE_ONLY,
  basketClassDistribution = mapOf(SINGLE_ITEM to 0.35, MULTI_ITEM to 0.45, EXPLORATORY to 0.20),
)
// RetailType.GROCERY, DIY_HARDWARE, ELECTRONICS, LUXURY, DEPARTMENT_STORE — defer until first scenario hits the type.
// HealthcareType.*, ManufacturingType.*, etc. — defer until post-MVP vertical expansion.
```

Refine numbers as concrete type-level research lands.

---

## Worked example — Park So-Yeon as `OperatorPersona`

Volere 2e § Persona 8 instantiates against this schema as:

```kotlin
val operatorSoYeon = OperatorPersona(
  id = "operator.kr.apparel.soyeon",
  displayName = "Park So-Yeon",
  market = Market.KR,
  vertical = Vertical.RETAIL,                           // top-level industry vertical
  type = RetailType.APPAREL,                            // Volere 2e baseline (Hansung Fashion Group)
  locale = "ko-KR",
  subjectMatterExp = ExperienceLevel.NOVICE,            // 8 months on the job, 2 stocktakes done
  technologicalExp = ExperienceLevel.JOURNEYMAN,        // fluent in Instagram/KakaoTalk/Coupang/Naver Map
  m8trxRole = M8trxRole.STAFF,
  primaryInterface = Interface.HANDHELD_ANDROID,
  workingHours = TimeWindow(
    startHour = 13, endHour = 21,
    daysOfWeek = setOf(WEDNESDAY, FRIDAY, SATURDAY, SUNDAY),
  ),
  biography = PersonaBiography(
    realName = "Park So-Yeon (박소연)", age = 22,
    title = "Sales Associate (Part-Time), TURO Hongdae flagship",
    location = "Mapo-gu, Seoul — shares a flat with two university friends near Sinchon",
    family = "Originally from Daejeon. Calls her parents weekly. Older brother works in IT.",
    education = "Currently completing a BA in Consumer Studies, Ewha Womans University",
    hobbies = "Fashion content on YouTube and Instagram; thrift shops on days off; noraebang Friday nights.",
    dailyLife = /* full §Daily Life narrative from 2e */ "...",
    character = /* §Character */ "...",
    attitudeToTech = /* §Technology */ "...",
    painPoints = listOf(
      "Stocktake is tedious and long — hard to maintain focus across a 4–6 hour count",
      "RFID workflow has failure modes she doesn't fully understand",
      "When customers ask where an item is, she often doesn't know precisely",
      "Task lists arrive by paper or KakaoTalk — hard to track across a shift",
    ),
    whatMoves = listOf(
      "App as simple as Instagram — zero training required",
      "Stocktake step-by-step and zone-gated — cannot accidentally sync the wrong data",
      "Find-an-item flow faster than current process — AR-guided, under 10 seconds",
    ),
    whatBreaks = listOf(
      "Complex interface that requires thinking about the software rather than the task",
      "Onboarding that takes more than one shift",
      "Error states she cannot understand or resolve herself",
    ),
    designNote = /* the §Critical Design Note about So-Yeon as a data source */ "...",
  ),
)
```

The companion `BuyerPersona` for the same human (used in eval-stage scenarios) shares `biography` by reference and adds the `agentPrompt` from 2e § Persona 8 § Agent Prompt verbatim:

```kotlin
val buyerSoYeon = BuyerPersona(
  id = "buyer.kr.apparel.soyeon",
  displayName = "Park So-Yeon",
  market = Market.KR,
  vertical = Vertical.RETAIL,
  type = RetailType.APPAREL,
  locale = "ko-KR",
  subjectMatterExp = ExperienceLevel.NOVICE,
  technologicalExp = ExperienceLevel.JOURNEYMAN,
  buyingTeamRole = BuyingTeamRole.USER,
  keyObjection = "If I make a mistake with this, will I get in trouble? Will I know immediately when something is wrong?",
  winningMessage = "App as simple as Instagram, errors caught and explained at the moment they happen, stocktake step-by-step and zone-gated.",
  agentPrompt = """
    PERSONA: Park So-Yeon — Sales Associate (Part-Time), TURO Hongdae flagship
    You are Park So-Yeon, 22, part-time sales associate ...
    [verbatim from Volere 2e § Persona 8 § Agent Prompt]
  """.trimIndent(),
  biography = operatorSoYeon.biography,   // same blob, shared by reference
)
```

---

## Wire format (JSON, derived)

`kotlinx.serialization`'s sealed-class polymorphism produces a `type` discriminator automatically. The same Park So-Yeon `OperatorPersona` serializes as:

```json
{
  "type": "operator",
  "id": "operator.kr.apparel.soyeon",
  "displayName": "Park So-Yeon",
  "market": "KR",
  "vertical": "RETAIL",
  "type": "APPAREL",
  "locale": "ko-KR",
  "subjectMatterExp": "NOVICE",
  "technologicalExp": "JOURNEYMAN",
  "m8trxRole": "STAFF",
  "primaryInterface": "HANDHELD_ANDROID",
  "workingHours": { "startHour": 13, "endHour": 21, "daysOfWeek": ["WEDNESDAY","FRIDAY","SATURDAY","SUNDAY"] },
  "biography": { "realName": "Park So-Yeon (박소연)", "age": 22, "...": "..." }
}
```

JSON Schema for the full hierarchy is generated from the `kotlinx.serialization` descriptors at build time — not hand-maintained. The Kotlin data classes are the canonical contract.

---

## Validation rules (orchestrator-enforced)

1. **`paymentMix` sums to 1.0 ± 0.001** when present (else null and use `MarketProfile.paymentMixPrior`).
2. **`capabilityOverrides`** must be a subset of the platform's resolved capability set for `m8trxRole` — typo-class capabilities reject at config-load.
3. **`agentPrompt` non-empty** for every `BuyerPersona` (the eval scenarios are useless without it; load failure is a fast-fail).
4. **`market` consistency** — every persona's `market` must match `scenario.meta.market`, OR the persona is from a `crossMarketReferenced` allowlist (rare; e.g., a US scenario showing a Korean compliance officer visiting).
5. **`vertical` consistency** — every persona's `vertical` must match `scenario.meta.vertical`, OR be `Vertical.GENERAL` (cross-vertical archetype reusable everywhere; e.g., a generic security-guard archetype).
6. **`type` consistency** — every persona's `type.vertical` must equal `persona.vertical` (sealed-class structure already enforces this at compile time in Kotlin; runtime validator re-asserts after deserialization). Persona's `type` must match `scenario.meta.type`, OR be the vertical's GENERAL fallback (`RetailType.GENERAL_RETAIL` for retail).
7. **Same-person dual-hat** — when `operator.<m>.<t>.<slug>` and `buyer.<m>.<t>.<slug>` both exist with shared biography, `displayName` + `age` + `realName` must match across the two records (else it's not actually the same person; rename one).

Validation failures abort scenario load with structured error rows; partial loads are forbidden.

---

## What this schema does NOT cover

- **Per-persona behavior policy** (e.g., "this shopper, when seeing a discount tag, has 30% probability of adding to basket") — that's a Layer 2 *Journey* concern. Personas describe *who*; journeys describe *what they do*.
- **End-customer mobile-app personas** (Volere 2d Phase-2 § "End Customer / Shopper") — these would be a fourth persona kind once the customer-facing app is real. Not modeled now; add `CustomerPersona` later as a sealed-class extension.
- **Channel-partner sales personas** (re-share scenarios via M8TRX Reach) — not modeled at this layer; their behavior is captured by the existing `BuyerPersona` kind with a partner-flagged biography.

---

## Cross-references

| Document | Where |
|---|---|
| Volere 2d Hands-on Users | `m8trx-shared/requirements/A. Project Drivers/2. Stakeholders/2d. Hands-on Users of the Product.md` |
| Volere 2e Personas | `m8trx-shared/requirements/A. Project Drivers/2. Stakeholders/2e. Personas.md` |
| Layer 4 schema (orchestrator surface) | `LAYER4-CONFIG-SCHEMA.md` (sibling) |
| Capability + role taxonomy | `m8trx-shared/reference/governance/CAPABILITIES.md` |
| Strategic Selling buying-team roles | Volere 2e § "Personas" intro |
