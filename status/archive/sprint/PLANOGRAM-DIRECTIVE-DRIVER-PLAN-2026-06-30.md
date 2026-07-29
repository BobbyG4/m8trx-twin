# Twin-side Plan — Planogram-Directive Driver (Connect Mode 3) + Compliance-Lifecycle Activity

*2026-06-30 (Session 12, idle-window work while Backend is heads-down). Twin-side build plan for the
planogram track defined by the S193 coordinator designs. Grounds the twin's role (per
`BUILD-SEQUENCE-TRIAD-PLANOGRAM-CONNECT-2026-06-30.md` §4) in what the twin already has.*

> **Build status (2026-06-30):** **T1 + T2 DONE + verified green** — the planogram document generator
> (`scripts/build_planogram.py` → 10× `stores/<id>/planogram.json`, deterministic, 84,266 floor units =
> exact reseed match) and the Kotlin `PlanogramDirectiveDriver` + DTOs + `connectPlanogramDrive` gradle
> task (built to the §6.1 contract; `connectSelfTest` green incl. the new directive-casing case; dry-run
> drives all 10 stores clean). **T3 + T4 also DONE** — the planogram lifecycle beat is in `LIVE-OPERATIONS.md`
> §11, and the compliance read-back gap is confirmed + drafted (§12 + `status/briefs/TWIN-REQ-DRAFT-…`).
> **T5 + T6 gated on Backend** — and **B1 (`mig 152a`) is APPLYING on mother 2026-06-30** (this session's DDL
> window); the twin driver fires the moment COORD calls GREEN.

**Source designs (core, `m8trx-shared/status/active/`, read-only for twin):**
- `PLANOGRAM-RESOLVED-DESIGN-2026-06-30.md` — 3 ingestion modes; **§6 = Mode 3 (Connect inbound) = the twin's target**.
- `CONNECT-PLANOGRAM-MVP-SCOPE-CORRECTION-2026-06-30.md` — **the twin IS the MVP external driver**; `mig 152a`/fork #11 → MVP.
- `TASK-CALENDAR-NOTIFICATION-TRIAD-RESOLVED-DESIGN-2026-06-30.md` — the triad the directive lifecycle delegates to.
- `BUILD-SEQUENCE-TRIAD-PLANOGRAM-CONNECT-2026-06-30.md` — §4 track plan: Twin = live-validate + build the Mode-3 driver.

---

## 1. The twin's role in this track (verbatim from the build sequence)

> *"Twin … the MVP external driver; live exercise of every surface … **live-validate** each surface as it lands
> (Notifications push, the Connect directive channel) + **build the planogram-directive driver** for Mode 3 …
> drives Mode 3 once the channel exists."* — BUILD-SEQUENCE §4

Two deliverables:
1. **Build the planogram-directive driver** — a new `com.m8trx.twin.connect` driver that posts a
   `directive_kind='planogram'` directive over Connect's inbound-directive channel (Mode 3), acting as the
   external planogram tool. This is the canonical *external-system → Connect inbound → internal planogram* flow.
2. **Live-validate the lifecycle** — drive the activity (scans, sales) that takes a published directive through
   compliance → drift → remediation → completion, and **self-verify** the resulting compliance state through the
   public API (the S11 discipline: server-side verification, not 200-acks). Catches bugs behind the ack — the
   twin caught 5 core bugs in S11 this exact way.

---

## 2. Key realization — **the twin's dataset already IS a planogram**

A planogram directive is `SKU → fixture → required_quantity (+ facing/level/position)`. The twin already
computes and commits exactly that, per store, in `reference/data/chain/stores/<id>/`:

| Directive concept (`compliance_target`) | Twin dataset source | Example (Denver) |
|---|---|---|
| `zone_id` (the fixture) | `assortment.csv:fixture` = `layout.json` fixture-zone `code` | `GB-R3-U1` ("Gondola R6 Front U1") |
| `product_id` (the SKU) | `assortment.csv:ean` / `item_cd` | `3608449847032` / `2456185` |
| `required_quantity` | `assortment.csv:depth` (units of that SKU at that fixture) | `5` |
| `line_item_type='product_placement'` | every assortment row is a placement | — |
| `facing_count` / `display_level` / `position_sequence` | derivable (depth→facings heuristic) or default | — |
| per-fixture realized stock (for compliance scoring) | `epcs.csv` (EPC→fixture) — the seeded inventory | 102,675 EPCs |

**Why this matters:** the directive the twin posts via Connect **matches the inventory already seeded on mother**
(same fixtures, same depths). So at activation the store is **compliant by construction**, and the twin's
*activity* (sales depleting a fixture, scans showing a gap) is what drives it out of compliance — producing
genuine, explainable remediation tasks and drift alerts. This is the realism payoff: not a synthetic directive
against empty fixtures, but the real seeded layout asserted as the VM intent.

---

## 3. Mode 3 — the envelope the twin builds to (`PLANOGRAM-RESOLVED-DESIGN` §6.1)

```jsonc
{ "directive_kind": "planogram",            // fork #11 discriminator (planogram | compliance | fulfillment)
  "integration_id": "<twin Connect integration>",
  "source_format":  "m8trx_standard",       // → directive_format_profile.schema_fingerprint match
  "site_ref":       "dec-us-denver",         // Connect resolves → site_id via integration_site_xref (INHERITS)
  "effective_date": "2026-08-01T00:00:00",
  "payload": { /* the planogram document — rows of {fixture_code, sku, required_qty, facings, level} */ } }
```

**Boundary (hard):** Connect provides transport, HMAC auth, FR-COMP-05 key-failure logging, and
`site_ref → site_id` resolution (which **INHERITS**). The twin **consumes** that — it does **not** design or
touch the cockpit/channel config. The fixture-code sub-resolution (`fixture_code → zone_id`) is **NON-inheriting**
and per-site (core-side, R5); the twin simply sends the raw `fixture_code` (`GB-R3-U1`) and core resolves it
against `fixture_code_mapping`. Since the twin's fixture codes are also the live `zone.name`/`code` on mother,
they resolve by **exact-name match** (resolver step 1) — no mapping rows needed. **No comms tools, ever.**

**Wire/DTO pattern** mirrors the existing inbound envelopes (`WebhookPayloads.kt`): camelCase Kotlin props,
snake_case on the wire via the `snake` mapper, factory helpers, built to the code-verified contract.

---

## 4. Blockers — all Backend/Web (Phase 1/2), none twin-side

| # | Blocker | Owner / phase | Blocks (twin) | Status |
|---|---|---|---|---|
| B1 | **Inbound-directive channel** — `mig 152a` (family + `directive_kind` + direction flags) + fork #11 | Backend, **Phase 1 Lane B** | the twin can't POST `directive_kind='planogram'` until the channel accepts it | **APPLYING 2026-06-30** — in the `157/158/152a/156` DDL batch on mother; COORD to call GREEN. Twin driver ready to fire on GREEN. |
| B2 | **Triad Slice-1** — rule-engine + scheduler + recipient-resolution + `user_device_token` + `task.assigned_to_role` | Backend, **Phase 1 Lane A** (critical path) | a landed directive won't generate tasks / drift / completion-push until this exists | NOT BUILT |
| B3 | **Planogram core** — Modes 1+2 + lifecycle/scoring (the `compliance_directive` landing + processing tail) | Backend, **Phase 2** (after triad) | even a directive that lands won't become targets/scoring until the tail exists | NOT BUILT (depends on B2) |

The twin's *live* Mode-3 run is gated on **B1** (channel) for landing and **B2+B3** for the lifecycle to fire.
Mode 3 itself is flagged as the **natural slip tail** if days run short (BUILD-SEQUENCE §6) — so the twin should
**pre-build to the contract now** and be ready to fire the day the channel lands, exactly as S9 pre-built the
whole harness to the §8 shapes before the integration was live.

---

## 5. What the twin CAN build NOW (unblocked — pure twin-side)

### 5a. Planogram **document generator** (Python, chain-builder family) — *zero core dependency*
A deterministic builder (sibling to `build_chain.py`) that emits a per-store planogram document from the
committed `assortment.csv` + `layout.json`:
- aggregate assortment rows → `{fixture_code, sku, required_qty=depth, facings, display_level, department}`;
- emit **`m8trx_standard`** JSON (clean canonical form, first cut) and optionally a **JDA-shaped** variant later
  for "this is a real third-party planogram feed" realism;
- output to `reference/data/chain/stores/<id>/planogram.json` (regenerable, sha-seeded).
This is a transform on already-committed data — buildable and testable with no channel, no mother.

### 5b. Kotlin **`PlanogramDirectiveDriver`** scaffold — built to contract, offline-self-tested
- DTOs: `DirectiveEnvelope` + `PlanogramDocument`/`PlanogramLine` under `connect/model/` (camel↔snake round-trip);
- `sim/PlanogramDirectiveDriver.kt` mirroring `InboundPushDriver` — loads `planogram.json`, builds the §6.1
  envelope, signs + POSTs via `WebhookClient`/`ConnectClient`;
- gradle task `connectPlanogramDrive` (dry-run default, like `connectScanSweep`);
- **add a `connectSelfTest` case** — envelope casing round-trip + document load — so it's green offline before
  the channel exists (the S9 pattern).

### 5c. **Compliance-lifecycle activity arc** design (into `LIVE-OPERATIONS.md` / `ACTIVITY-PLAN.md`)
Sequence the "play" that demonstrates **and tests** planogram end-to-end (see §7).

### 5d. **Self-verify read-back** design (the loop-closer)
Mirror `connectSelfVerify` (which reads `items/details` for SOLD): determine the public-API path that reads
back `compliance_target_state` / dual-score / audit so the twin can confirm the directive landed and the store's
compliance moved. **This is also gap-discovery (§6).**

---

## 6. Open verification items / candidate front-door gaps

> Integrator posture: verify against the public API; if a needed surface isn't exposed, **file a TWIN-REQ**, don't shim.

1. **Compliance read-back on `/api/v2`** — does the public Bearer surface expose `compliance_target_state` /
   directive status / dual-score / the FR-COMP-15 audit report for an external integrator to self-verify? If
   **no**, that's a real integrator gap (an external planogram tool can't confirm its directive's effect) →
   candidate **TWIN-REQ**. *Verify before asserting — not yet checked.*
2. **Format-profile registration** — Mode 3 matches `source_format` against `directive_format_profile`
   (FR-COMP-01, registered by INSERT, no deploy). Does an **`m8trx_standard`** profile ship built-in, or must
   Backend register the twin's format `field_mapping`? Coordination point with Backend (one-line ask when B1 lands).
3. **`directive_kind` envelope final shape** — §6.1 is the design contract; confirm the *as-built* wire shape
   against the channel when B1 ships (S9 found camel/snake + one-of nuances by code-reading the live ingester).

---

## 7. The compliance-lifecycle "play" (demonstrates + tests planogram)

The arc that makes planogram visible on the surfaces and exercises every seam:

1. **Post directive** (twin, Mode 3) → `compliance_directive(source='api_push')` + targets land; `effective_date` set.
2. **Activation** (core scheduler Z-04, site-local 00:00) → directive `active`, compliance baseline = **compliant**
   (matches seeded EPCs). Twin self-verifies dual-score ≈ 100%.
3. **Drive non-compliance** (twin activity) → `connectSaleStream` depletes a directive-governed fixture below
   `required_quantity`; a `connectScanSweep` shows the gap. → inferred/`scan_confirmed` non-compliance.
4. **Remediation fires** (core triad) → rule-engine emits `replenishment_request` task (role-routed);
   drift job (Z-01) raises a drift alert by 09:00. Twin confirms the task/notification surfaced.
5. **Restock heals it** (twin activity) → restock/receive event refills the fixture → compliance recovers.
6. **Self-verify** (twin) → read dual-score back to ~100%; pull the FR-COMP-15 audit timeline. Loop closed.

This is the same closed-loop shape the twin already proved for SOLD (drive → core moves state → read it back).
It plugs into the `LIVE-OPERATIONS.md` runtime as a daily-lifecycle beat once B2/B3 land.

---

## 8. Proposed twin build order

| Step | Work | Depends on | Status |
|---|---|---|---|
| T1 | **Planogram document generator** (§5a) — `planogram.json` per store from committed data | nothing | ✅ **DONE** (`scripts/build_planogram.py`; 10 stores; 84,266 floor units = reseed match; deterministic) |
| T2 | **`PlanogramDirectiveDriver` + DTOs + offline self-test** (§5b) — built to §6.1 contract | T1 | ✅ **DONE** (`DirectivePayloads.kt` · `sim/PlanogramDirectiveDriver.kt` · `ConnectPlanogramDrive.kt` · `connectPlanogramDrive`; `connectSelfTest` green; dry-run drives 10/10) |
| T3 | **Lifecycle-arc design** into LIVE-OPERATIONS / ACTIVITY-PLAN (§7) | nothing | ✅ **DONE** (`LIVE-OPERATIONS.md` §11 — the directive beat woven into the daily lifecycle; §4 replenishment = the remediation execution) |
| T4 | **Self-verify read-back design** + API-surface check (§5d, §6.1) | `/api/v2` reachable | ✅ **DONE** — gap confirmed (no compliance read-back on `/api/v2`); draft `status/briefs/TWIN-REQ-DRAFT-planogram-compliance-readback-2026-06-30.md` |
| T5 | **Live Mode-3 drive** — fire the directive at the real channel; verify landing | **B1** (channel) | gated — when core ships `mig 152a` |
| T6 | **Live lifecycle validation** — run the §7 play end-to-end; catch bugs behind the ack | **B1+B2+B3** | gated — when triad + planogram core land |

**T1–T4 are the idle-window work** (no core dependency); T5–T6 are validate-after-land, the moment Backend ships.

---

*Twin planning only. Backend/Web owns all platform implementation (channel, triad, planogram core); the twin
drives + validates. Filed in twin `status/active/`. No core artifact written from this session.*
