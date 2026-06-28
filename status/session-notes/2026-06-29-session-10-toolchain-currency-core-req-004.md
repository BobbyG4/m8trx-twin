# Session 10 — 2026-06-29 · Twin

## Toolchain currency pass (CORE-REQ-004) — GO, full stack landed + merged

Short, tightly-scoped session. Core filed **CORE-REQ-004** (core→twin direction)
asking twin to run a toolchain assessment on the generator stack it maintains —
mirroring the services SB4+Gradle9 pull-forward (S185) and the android
assessment. Rationale: **build-once-while-greenfield** — the Connect simulators
(CORE-REQ-003) are still being built per phase, so bump the toolchain now while
the generators are young rather than migrating + double-validating later.

## What shipped

Branch `feature/toolchain-currency-core-req-004` → **PR #1 → merged to main.**
Each bump committed + pushed individually (durability lesson from core S185).

| Dep | was → now | commit |
|---|---|---|
| **jackson** | 2.21.3 → **2.21.4** (P0 CVE) | `63cb26a` |
| jnats / coroutines / logback | 2.20.6→**2.25.3** · 1.9.0→**1.11.0** · 1.5.18→**1.5.37** | `bdb23fe` |
| Kotlin | 2.3.20 → **2.4.0** | `599c368` |
| Gradle | 8.14.4 → **9.6.1** (+ Jackson deprecation fold-in) | `0dd6e16` |
| assessment deliverable | `status/briefs/TWIN-TOOLCHAIN-ASSESSMENT-CORE-REQ-004-2026-06-29.md` | `6308590` |

Merge commit `68b74e6`.

## What was attempted / verified

- **Baseline first** — green `build` + `connectSelfTest` before touching anything.
- **Verification per step** — `clean build` + `ktlintCheck` + `connectSelfTest`
  after each bump. All green throughout.
- **Resolved-version cross-check** — queried Maven Central for actual latest
  rather than trusting the stack-watch snapshot. Caught two snapshot drifts:
  logback's real latest is **1.5.37** (snapshot said 1.5.18); Kotlin's metadata
  `<release>` is **2.4.20-Beta1** (a beta) — took **2.4.0 GA** instead.
- **One source change** — the Gradle 9 + Kotlin 2.4 combo surfaced a deprecation
  warning on `ObjectMapper.setSerializationInclusion`; folded in the non-deprecated
  2.x equivalent `setDefaultPropertyInclusion` in `ConnectMappers`.

## Key discoveries

- **No new core API gaps.** This was leaf-dep currency + Gradle/Kotlin, not a
  framework migration (twin has no Spring Boot / Compose / AGP). Mechanical
  effort was ~30 min, not the briefed ~0.5 day.
- **Jackson CVE exposure was low and is now closed.** Grep of `src/` for the
  default-typing vector (`activateDefaultTyping` / `enableDefaultTyping` /
  `@JsonTypeInfo` / `PolymorphicTypeValidator`) → none. Twin's two ObjectMappers
  only set naming strategy + NON_NULL + lenient deserialization. Same clean
  result services passed.
- **ktlint 12.2.0 / ktlint-tool 1.5.0 survives Gradle 9 + Kotlin 2.4 clean** — no
  14.x upgrade or `.editorconfig` rule-promotion pass needed (the brief budgeted
  for one; not required).
- **No Gradle deprecation warnings** under 9.6.1 — no multi-string dep notation;
  Gradle-10-ready. (`gradlew.bat` was newly added by the wrapper regeneration —
  it was previously absent from the repo.)
- **Ecosystem wire-compat satisfied** — jackson 2.21.4 matches services' SB4
  jackson2-bridge pin; jnats 2.25.3 matches edge/services (twin was behind at
  2.20.6).

## Open gate — live smoke (carried to next session)

The brief's top verification rung — `connectLiveSmoke` (fire a real `sale_event`
at dev Connect) — was **gated, not forced**:
- Reachability OK: `dev.m8trx.com` responds; LAN NATS `192.168.55.29:4222` is
  **open from this host**.
- But `.env` has no Bearer/tenant/site/slug (S9 Bearer key deliberately not
  persisted per no-creds-in-repo protocol). Firing a live txn against dev is an
  outward action not taken unprompted.
- The **offline self-test already proves valid emission** (serialization +
  round-trip of every Connect payload shape across both ObjectMappers), so
  toolchain confidence doesn't depend on the live rung.

## Decisions

- Kotlin **2.4.0 GA** (not 2.4.20-Beta1; not held at services' 2.3.21 — twin has
  no Compose lock so it can move ahead).
- ktlint **held at 12.2.0** (survives clean; no reason to take on a 14.x
  `.editorconfig` pass this session).
- Live smoke **deferred** to next session, where it folds into the planned
  full realtime smoke (Bob: "fully smoke realtime next session").

## Branch / deploy state at close

- On **main**, clean working tree. PR #1 merged; feature branch can be deleted.
- Mother M8trxDemo unchanged this session (no seed/reseed work).
- Toolchain now current: Gradle 9.6.1 · Kotlin 2.4.0 · jackson 2.21.4 ·
  jnats 2.25.3 · coroutines 1.11.0 · logback 1.5.37 · ktlint 12.2.0 · JVM 21.
