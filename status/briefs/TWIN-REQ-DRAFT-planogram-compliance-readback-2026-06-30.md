# TWIN-REQ DRAFT — Public compliance/directive read-back for Connect Mode-3 integrators

**Status:** DRAFT (twin-side, unfired) — 2026-06-30, Session 12 (T4 finding).
**Proposed number:** TWIN-REQ-003 — *note:* `status/STATUS.md` tentatively earmarks "003" for staff/org
provisioning; Bob assigns the final number at filing.
**File WHEN:** the planogram-domain processing tail (**B3**) is scheduled — so the read-back ships **with** it.
Filing now would be premature: the surface it reads doesn't exist yet (correctly — B3 isn't built).
**Blocks:** the twin's planogram self-verify loop (`LIVE-OPERATIONS.md` §11 beat 6 / plan T6). Generalizes to
**any** external planogram tool (JDA, Quant) — this is a first-party integrator gap, not a twin convenience.

---

## Problem

Connect Mode 3 lets an external planogram tool POST a `directive_kind='planogram'` directive — the twin's
`sim/PlanogramDirectiveDriver` (built S12 to `PLANOGRAM-RESOLVED-DESIGN-2026-06-30` §6.1). It is
**inbound-only**: once the directive lands, the posting system has **no public `/api/v2` way to read back**
whether it landed/activated or what compliance resulted. The planogram design routes compliance status to the
**VM Web dashboard** (FR-PLN-10 "existing compliance dashboard"; FR-COMP-15 audit report "via the reporting
hierarchy") — a human surface, not an integration read.

## Why it's a real integrator gap

The inbound data plane already has its read counterpart: `POST /api/v2/inventory/items/details`
(`M8TRX-API-SURFACE.md` #29, `inventory:read`) was added so an integrator can confirm `state=sold` after firing
a sale — **Bearer-only, zero psql.** The twin used it to self-verify SOLD in S9/S11 (the `connectSelfVerify`
loop-closer). The planogram path has the inbound half but **not** the read half. An external system that drives
a directive should be able to confirm, over the same Connect/Bearer plane:

- did the directive **land + activate**? (directive status)
- what is the resulting **per-fixture / per-site compliance**? (`compliance_target_state` + the dual score)
- the **audit timeline** (FR-COMP-15), for a programmatic integrator report.

Without it the only confirmation path is logging into the Web cockpit — which breaks the
"external-system-drives-AND-verifies-through-the-front-door" pattern Mode 3 is meant to complete.

## Proposed surface (illustrative — core owns the shape)

A read-side Bearer atom mirroring #29, e.g.:
- `GET /api/v2/compliance/directives/{directive_ref}` → directive status + per-site activation state;
- `POST /api/v2/compliance/state` `{ site_ref, directive_ref }` →
  `[{ fixture_code, required_qty, observed_qty, compliance_status, fulfillment_status, last_confirmed_at }]`
  plus the two §5.4 scores (directive vs effective).
- Capability: a new `compliance:read`, or reuse `inventory:read`.

## Precedent

- `inventory/items/details` (#29) — the inbound→read-back closure for **inventory** state. Same pattern,
  compliance domain.
- TWIN-REQ-002 (`commerce_projection` writer) — same *shape* of gap: substrate exists, the read/derive surface
  for an external consumer is unfed.

## Fit criteria

- An external system that POSTed a planogram directive over Connect can, with only a service Bearer,
  (a) confirm the directive activated and (b) read the resulting compliance score + per-fixture state — **no
  Web login, no psql.**
- The twin's planned `connectPlanogramSelfVerify` closes the planogram lifecycle loop the way
  `connectSelfVerify` closes the inventory loop.

## Dependency

Reads the planogram-domain processing tail (**B3**, Backend Phase 2). File to land **with** that build.
