---
title: Twin toolchain currency assessment — response to CORE-REQ-004
date: 2026-06-29
responds-to: ~/IdeaProjects/m8trx-shared/twin/requirements/CORE-REQ-004-twin-toolchain-assessment.md
branch: feature/toolchain-currency-core-req-004
status: LANDED — full stack bumped + green; one verification rung (live smoke) gated on creds
mirrors: services feature/toolchain-sb4-gradle9 (S185) · android ANDROID-TOOLCHAIN-PULL-FORWARD-ASSESSMENT-2026-06-29
---

# Twin Toolchain Assessment — CORE-REQ-004

## Verdict: **GO — all bumps landed, build-once-while-greenfield**

Twin was the simplest of the three (no Spring Boot, no Compose/AGP/SceneView).
Every recommended bump applied cleanly with **zero source changes** beyond one
deprecated-API fold-in. Each step committed + pushed individually on
`feature/toolchain-currency-core-req-004`.

## Target version matrix (assessed against twin's own build, not the snapshot)

| Dep | was | now | decision | notes |
|---|---|---|---|---|
| **jackson** | 2.21.3 | **2.21.4** | ✅ DONE (P0) | the CVE. Resolves across databind/core/kotlin + bom. |
| jnats | 2.20.6 | **2.25.3** | ✅ DONE | NATS wire-align with edge/services. Latest. |
| kotlinx-coroutines | 1.9.0 | **1.11.0** | ✅ DONE | align with android. Latest. |
| Kotlin | 2.3.20 | **2.4.0** | ✅ DONE | GA (2.4.20-Beta1 is a beta — not taken). Ahead of services/android (2.3.21) — twin has no Compose lock. |
| Gradle | 8.14.4 | **9.6.1** | ✅ DONE | current. No deprecation warnings; Gradle-10-ready. |
| ktlint plugin | 12.2.0 | **12.2.0** (hold) | ✅ no change | survives Gradle 9 + Kotlin 2.4 clean — no 14.x / `.editorconfig` pass needed. |
| logback | 1.5.18 | **1.5.37** | ✅ DONE | latest 1.5.x (snapshot said "1.5.18"; real latest is .37). |
| jvm toolchain | 21 | **21** (hold) | ✅ no change | per brief. |

## P0 — Jackson CVE: CLEARED

- Bumped 2.21.3 → **2.21.4** (CVE-2026-54512/54513/54515; affected `≥2.19.0 <2.21.4`).
- **Exposure was low and is now closed.** Grep of `src/` for the default-typing
  vector (`activateDefaultTyping` / `enableDefaultTyping` / `@JsonTypeInfo` /
  `PolymorphicTypeValidator`) → **none** — same clean result services passed.
  Twin's two ObjectMappers (`ConnectMappers`) only set naming strategy +
  NON_NULL inclusion + lenient deserialization; no polymorphic typing on
  untrusted input.
- Committed isolated (`63cb26a`) so it can cherry-pick independent of the rest.

## Effort — mechanical vs validation

- **Mechanical: ~0.5 day → actual ~30 min.** All version edits live in
  `settings.gradle.kts` (inline `deps` catalog) + the Gradle wrapper. One
  source edit: `ObjectMapper.setSerializationInclusion` →
  `setDefaultPropertyInclusion` (the warning Gradle 9 + Kotlin 2.4 surfaced).
- **Validation: folded in per step** — `clean build` + `ktlintCheck` +
  `connectSelfTest` after each bump.
- **No gate found** in the mechanical path. The only gate is on the live rung
  (below), and it's an environment/creds gate, not a toolchain one.

## Verification ladder

| Rung | Result |
|---|---|
| compiles (Kotlin 2.4.0) | ✅ |
| `clean build` (Gradle 9.6.1) | ✅ green, no Gradle deprecations |
| `ktlintCheck` (12.2.0 / ktlint-tool 1.5.0) | ✅ clean on Gradle 9 + Kotlin 2.4 |
| `connectSelfTest` — generators emit valid payloads | ✅ HMAC round-trip · DTO casing round-trip on Connect webhook/bearer/outbound shapes (§6/§8/§9) · OutboundReceiver loop (accept/dedupe/tamper-reject/missing-sig/failMode) · SFTP CSV formatter |
| **live smoke — `connectLiveSmoke` against dev Connect** | ⏸ **GATED** (see below) |

The offline self-test exercises serialization/deserialization of every Connect
payload shape across both ObjectMappers — that's the proof the Jackson + the
codebase still round-trip correctly post-bump.

## Open gate — live smoke

`connectLiveSmoke` (fire one real `sale_event` at the dev Connect webhook) is the
brief's top rung. It is **not runnable in this checkout** and was **not forced**:

- Reachability is fine: `dev.m8trx.com` responds (TLS+routing OK) and LAN NATS
  `192.168.55.29:4222` is **open from this host**.
- But the required env is empty in `.env`: `M8TRX_TENANT_ID`, `M8TRX_SITE_ID`,
  `M8TRX_CONNECT_INTEGRATION_SLUG`, `M8TRX_TWIN_SERVICE_BEARER` are all blank
  (the **Bearer key was deliberately not persisted** at S9 close — protocol:
  no creds in repo). Firing a live transaction against the dev tenant is also
  an outward action not taken unprompted.
- **To close:** re-supply the S9 Bearer key + tenant/site/slug to `.env`, then
  `./gradlew connectLiveSmoke`. Expect the same S9 result (sale_event →
  PROCESSED → self-verified SOLD via Bearer). Toolchain confidence does not
  depend on it — the offline ladder already proves valid emission.

## Cross-project wire-compat — satisfied

- **jackson 2.21.4** matches services' SB4 jackson2-bridge pin.
- **jnats 2.25.3** matches edge/services — twin is no longer behind on the NATS
  wire version.

## Commits (all pushed to `origin/feature/toolchain-currency-core-req-004`)

1. `63cb26a` fix(deps): jackson 2.21.3 → 2.21.4 (P0 CVE)
2. `chore(deps)` jnats 2.25.3 · coroutines 1.11.0 · logback 1.5.37
3. `chore(deps)` Kotlin 2.3.20 → 2.4.0
4. `build` Gradle 8.14.4 → 9.6.1 + Jackson deprecation fold-in
