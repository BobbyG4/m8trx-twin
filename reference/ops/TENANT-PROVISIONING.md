# M8trxDemo Tenant — Provisioning Playbook

**Status:** DRAFT 2026-05-10 (initial integrator pass; verify against live mother before first run)
**Audience:** twin sessions provisioning M8trxDemo from scratch
**Companion docs:**
- [m8trx-shared/twin/SISTER-PROJECT.md](../../../m8trx-shared/twin/SISTER-PROJECT.md) — twin/core posture
- [m8trx-shared/reference/dev/AUTH-FLOW.md](../../../m8trx-shared/reference/dev/AUTH-FLOW.md) — auth wiring source of truth
- [m8trx-shared/reference/governance/PERMS-V3-PROVISIONING-FLOW.md](../../../m8trx-shared/reference/governance/PERMS-V3-PROVISIONING-FLOW.md) — Profile + PSet resolution

---

## Preamble

**M8trxDemo** is the canonical M8TRX demo tenant on mother. It runs the Decathlon Korea running specialty store fixture. Twin generates realistic event streams against it via Layer 0 atom emitters; every customer tenant subsequently sees this data through an M8TRX Reach grant (the "stub demo site" pattern in [m8trx-twin/CLAUDE.md § Tenant Model](../../CLAUDE.md)).

The provisioning flow walked here is **the same flow a real customer or channel-partner customer walks**. That makes the playbook dual-purpose: it is twin's bootstrap recipe AND a real customer-onboarding stress test of M8TRX's public surfaces. Friction discovered here is friction every paying customer would also hit. Capture it (see § "Onboarding friction surfaced") so it can flow back to core as TWIN-REQ briefs.

Twin operates M8trxDemo as **platform-admin** (per [SISTER-PROJECT.md § "Operator role for M8trxDemo tenant"](../../../m8trx-shared/twin/SISTER-PROJECT.md)). Platform-admin is required because M8trxDemo grants Reach shares to every customer tenant, and grant issuance is a tenant-level admin operation that cross-tenant operators sit above.

Servers we hit:
- **mother** (`mother.m8trx.com`, 159.223.43.77) — Hasura v2 + Postgres
- **m8trx-services** (LAN office .28, public via dev.m8trx.com) — REST `/api/v2/*`
- **m8trx-web** (dev.m8trx.com) — UI surface
- **mother :5448** is the Postgres port; twin does NOT connect to it directly (forbidden per CLAUDE.md). All access flows through the public REST + Hasura paths.

---

## Verification checklist (skim first; meet at end)

After running the full playbook, these artifacts must exist:

- [ ] `organization` row for "Intellithinx" (already exists; mig 075b)
- [ ] `tenant` row for "M8trxDemo" (slug `m8trx-demo`, vertical `retail`, tier `enterprise` for headroom, status `active`)
- [ ] `user` row for the platform-admin operator (`is_platform_admin = true`, `email_verified_at` set)
- [ ] `user_tenant_membership` row binding the operator to M8trxDemo with `profile_id = tenant-admin`
- [ ] ~20 `user_permission_set` rows applied via `applyDefaultPermissionSets()` (per [PERMS-V3-PROVISIONING-FLOW.md](../../../m8trx-shared/reference/governance/PERMS-V3-PROVISIONING-FLOW.md))
- [ ] At least one `site` row (Decathlon Seoul Gangnam Running) under M8trxDemo
- [ ] One `api_key` row with `principal_kind='service'`, `owning_tenant_id = M8trxDemo.id`, `scope_grants` populated for Layer 0 emit endpoints, raw bearer captured to twin's secrets store
- [ ] (Optional, deferred) `tenant_share_grant` row for read-share into customer tenants

---

## Step 1 — Establish the platform-admin operator

### Step name
Create the platform-admin user that twin authenticates as when issuing tenant-level admin operations against M8trxDemo.

### Customer-facing equivalent
None. Platform-admin status is a backend-only flag (`user.is_platform_admin = true`, mig 054). There is no customer-facing surface to grant it — by design (CLAUDE.md memory `project_platform_admin_role.md`: platform-admin is a true SaaS admin, NOT a super-tenant-admin).

### Surface (admin-only path — explicitly called out)
**SQL bootstrap** via mig 075b precedent (`status/migrations/075b_bootstrap_bob.sql`). For twin, the operator should be a **dedicated identity** — not a personal address — per [`feedback_external_signups_via_groups.md`](`reference/dev/AUTH-PROVIDERS-EXPANSION.md`).

**Recommended operator identity:**
- **Email:** `dev@m8trx.com` (Google Group; routes to bob.grove@intellithinx.com today, can re-route as the team grows)
- **Display name:** `M8TRX Twin Operator`
- **Credentials store:** 1Password "Company / M8TRX" vault entry `M8trxDemo Twin Operator`
- **OAuth provider:** Google (the group address is a Workspace identity)
- Add a row to [`reference/dev/OPERATIONAL-ACCOUNTS.md`](../../../m8trx-shared/reference/dev/OPERATIONAL-ACCOUNTS.md) per the AUTH-PROVIDERS-EXPANSION pattern

### Inputs
- Operator email (group address)
- Display name
- A known UUID (use `gen_random_uuid()` or pick a stable test UUID for twin convenience, e.g. `00000000-0000-0000-0000-000000000T01`)

### What gets created
- `user` row with `is_platform_admin = TRUE`, `enabled = TRUE`, `email_verified_at = NOW()`
- (No `user_tenant_membership` row at this step — platform-admin authority comes from the boolean column, not from a membership; resolver Step 1 short-circuits when `is_platform_admin = true`)
- (No `user_identity` row yet — added Step 2 when the operator first OAuths in)

### Verification
- `SELECT id, email, is_platform_admin FROM "user" WHERE email = 'dev@m8trx.com';` returns one row, `is_platform_admin = t`
- After Step 2 (login), `GET /api/v2/auth/me` returns `user.platformAdmin: true` in the JWT claim and resolver yields all 150+ caps at scope `all`

### Known friction / gotchas
- **No customer-facing path.** This is the single hard admin-only step in the playbook. Every other step uses a customer-walked surface. Bob-equivalent (mig 075b) pre-seeds via SQL run during DB initialization. For a fresh provisioning twin should:
  1. Open a PR/issue against m8trx-shared adding a 075b-style migration for the twin operator identity, OR
  2. Have Bob run the equivalent INSERT directly on mother (one-time, audited via `audit_log` if executed via Hasura console; bypasses audit if via psql — see CLAUDE.md "Audit capture convention").
- **No `/api/v2/admin/users/grant-platform-admin` endpoint exists.** This is a deliberate gap (platform-admin is rare; manual SQL is appropriate). Twin should NOT propose adding a self-serve UI for this — that would weaken the security boundary.
- **Email verified bypass.** Using `email_verified_at = NOW()` skips the email-verify flow because the operator is bootstrapped, not signed up. Production customers walk Flow 1 of AUTH-FLOW.md.

---

## Step 2 — Operator first login + session bootstrap

### Step name
Operator authenticates via Google OAuth, receives a JWT, twin captures the access + refresh tokens for subsequent API calls.

### Customer-facing surface
Web UI at `https://dev.m8trx.com/m8trx/login` → "Continue with Google" button. (Confirmed: route exists in `m8trx-web/src/auth/oauthProviders.ts`; `LoginPage` rendered by `m8trx-web/src/auth/LoginPage.tsx` per AUTH-FLOW.md Flow 3.) **`[needs verification]`** that the operator's group address resolves correctly through the Google OAuth → backend `/auth/exchange` linking — the abandoned-account-overwrite logic in AUTH-FLOW.md Flow 1 handles fresh-user case but the operator is pre-seeded with no `user_identity` row.

### Inputs
- Operator Google credentials (1Password)

### Backend flow (per AUTH-FLOW.md Flow 3)
```
POST /api/v2/auth/exchange
  body: { provider: "google", credentials: { type: "OAuthCode", code: <from-redirect>, redirectUri: ... } }
→ AuthUserService.findOrCreateFromProvider()
  → lookup user_identity by provider='google' + subject=<google sub>
  → not found, lookup by email → MATCH on dev@m8trx.com (Step 1 user)
  → INSERT user_identity (user_id=<step1-uuid>, provider='google', subject=<google sub>)
→ buildAuthResponse(user, headers)
← AuthResponse { status: "authenticated", accessToken, refreshToken, user, tenant: NULL, capabilities: [all 150+] }
```

### What gets created
- `user_identity` row linking the Google account to the Step 1 user
- `refresh_token` row (10-day lifetime per AUTH-FLOW.md "Token Architecture")
- JWT with `platformAdmin: true` claim

### Outputs twin needs to capture
- `accessToken` (JWT, 60-min lifetime — refresh via `POST /api/v2/auth/refresh` before expiry)
- `refreshToken` (UUID, 10-day lifetime)
- Operator `userId` (will be Step 1 UUID)

Twin stores these in its secrets store (see Step 4 for the durable `api_key` bearer that doesn't expire).

### Verification
- `GET /api/v2/auth/me` with `Authorization: Bearer <accessToken>` returns `status: authenticated`, `user.platformAdmin: true`, `tenant: null` (operator has no tenant binding by design)
- `SELECT * FROM user_identity WHERE user_id = <op-id>;` returns the Google identity row

### Known friction / gotchas
- **Tenant binding for platform-admin operator.** Per [SISTER-PROJECT.md § "Operator role"](../../../m8trx-shared/twin/SISTER-PROJECT.md), platform-admin retains a tenant binding (cross-tenant Reach grant issuance has to live somewhere). However, mig 075b's bootstrap pattern binds Bob to ITX Demo tenant via `user_tenant_membership` with `profile_id = tenant-admin`. **Question:** does the twin operator need the same? Twin's operator is platform-admin first and foremost — the Resolver Step 1 short-circuits on `is_platform_admin` and returns scope `all` regardless of membership. The membership is only needed if twin wants to ALSO use the operator as a tenant-admin of M8trxDemo (e.g., to render the demo tenant in the tenant switcher). Recommend: after Step 3 (tenant created), add a tenant-admin membership for the operator into M8trxDemo so the web UI can land on the demo tenant when the operator logs in. Same INSERT pattern as mig 075b lines 36-42.
- **No active tenant in JWT until membership exists.** Until the membership is created, `JWT.tid` is NULL and Hasura RLS will reject any `frontEnd`-role query that requires `X-Hasura-Tenant-Id`. Platform-admin operations that go through the `platform_admin` Hasura role bypass this.
- **OAuth-only branch on first password attempt.** If twin ever tries to `/auth/login` (email + password) for the operator, the backend returns 401 `OAUTH_ONLY` because no `provider='email'` identity row exists. AUTH-FLOW.md Flow 5 documents this. Twin should always use `/auth/exchange` with the Google OAuth code.

---

## Step 3 — Provision the M8trxDemo tenant

### Step name
Create the M8trxDemo tenant under the Intellithinx organization, with tenant-admin Profile applied to twin's operator (so the operator can also act as tenant-admin within M8trxDemo).

### Customer-facing equivalent
**Self-serve signup wizard** at `/signup` (TenantWizardPage in m8trx-web; AUTH-FLOW.md Flow 1, Step "TenantWizardPage"). A real customer hits `POST /api/v2/auth/signup` after registering a new email. **For twin, the operator already exists with `tenant_id = NULL`, so calling `/auth/signup` while logged in as the operator would attempt to provision a new tenant for that user — which is the right shape, except the operator is platform-admin and self-signup may not be the intended path for that role.**

**Recommended path: `POST /api/tenant/provision` (TenantProvisioningController)** — the channel-partner / platform-admin provisioning endpoint that takes a target email + tenant config and provisions on behalf of the caller. Cap-gated on `tenant:create` (which platform-admin holds at scope `all`). Records the operator as the provisioner in `audit_log`.

### Surface
```
POST /api/tenant/provision
Authorization: Bearer <operator-access-token>
Content-Type: application/json

{
  "templateId": "multi-brand-enterprise",
  "tenantName": "M8trxDemo",
  "ownerEmail": "dev@m8trx.com",
  "ownerName": "M8TRX Twin Operator",
  "vertical": "retail",
  "organizationName": "Intellithinx",
  "partnerId": null
}
```

(See `m8trx-services/main-server/src/main/kotlin/com/m8trx/server/auth/TenantProvisioningController.kt:48` for the canonical request shape.)

### Inputs
- `templateId` — `multi-brand-enterprise` (matches mig 075b precedent for ITX Demo; gives the demo headroom for multi-site, multi-zone authoring without setup_template friction)
- `tenantName` — `M8trxDemo`
- `ownerEmail` — twin operator address (`dev@m8trx.com`)
- `ownerName` — `M8TRX Twin Operator`
- `vertical` — `retail`
- `organizationName` — `Intellithinx` (resolves to existing org per mig 075b; otherwise creates a new org row)

### What gets created
- `tenant` row: `M8trxDemo`, slug `m8trx-demo` (auto-generated; if collision, suffix `-2`, `-3`...), tier `free`/status `trial` initially (see gotcha below for tier upgrade), `setup_template = multi-brand-enterprise`
- `user_tenant_membership` row binding the operator to M8trxDemo with `profile_id = tenant-admin` (the operator now has TWO authority paths: platform-admin via boolean, tenant-admin via membership)
- ~20 `user_permission_set` rows from `profile_default_permission_set` lookup (per PERMS-V3-PROVISIONING-FLOW.md)
- `subscription_event` row recording trial start
- `audit_log` row with `action = 'tenant_provisioned'`, `actor_user_id = <operator>`

### Verification
- `SELECT id, name, slug, vertical, subscription_tier, setup_template FROM tenant WHERE slug = 'm8trx-demo';` returns one row
- `SELECT count(*) FROM user_permission_set WHERE user_id = <op> AND tenant_id = <m8trxdemo>;` returns ~20
- Operator can fetch `GET /api/v2/auth/me` and `tenant.slug = 'm8trx-demo'` appears (assuming auto-select picks the new membership; otherwise call `POST /auth/select-tenant` — `[needs verification]`: confirm endpoint name)
- Audit row visible in `audit_log` table

### Known friction / gotchas
- **Tier defaults to `free`/`trial`.** TenantProvisioningController hardcodes `subscription_tier = 'free'` + `subscription_status = 'trial'` (line 100). For M8trxDemo we want `enterprise`/`active` (no trial expiry to surprise the demo). After provisioning, run a one-shot UPDATE: `UPDATE tenant SET subscription_tier = 'enterprise', subscription_status = 'active', trial_ends_at = NULL WHERE slug = 'm8trx-demo';`. **This is admin-only friction** — there is no customer-facing endpoint to switch to enterprise without going through Stripe billing flow. For twin (no real billing), bypass via SQL is the right call. (Add to friction list as TWIN-REQ candidate: "platform-admin tier override endpoint for demo / channel-partner-seeded tenants.")
- **`templateId` validation.** Backend accepts the string verbatim. Valid values per `setup_template` table (mig 087): `small-shop`, `multi-store-smb`, `multi-brand-enterprise`. Free-tier tenants are LOCKED to `small-shop` (PERMS-V3-PROVISIONING-FLOW.md § "Workspace-mode mapping"); since we upgrade to enterprise post-creation, the lock is a non-issue but worth knowing.
- **Operator email collision.** If `dev@m8trx.com` was used for any prior tenant signup that was abandoned (`tenant_id IS NULL`), the registration overwrite logic in AUTH-FLOW.md Flow 1 may fire. For TenantProvisioningController the path is different — it looks up the existing user by email (line 109-113) and reuses the row. Should be safe but verify the operator user ID matches Step 1's UUID after this call.
- **`partnerId` is optional.** ITX direct-provisioning (this case) sets `null`. For real channel-partner provisioning, this would be the partner tenant's UUID for attribution / billing-flow-back.
- **Subscription state.** `subscription_event` is recorded with `reason = 'channel_partner_provision'` regardless of caller (line 154). Audit will read "channel_partner_provision" even for ITX direct calls. Documented as benign in the controller's KDoc.

---

## Step 4 — Issue a service api_key for twin (M8TRX Connect / service principal)

### Step name
Mint a non-expiring `principal_kind='service'` api_key with `scope_grants` covering Layer 0 emit endpoints. Twin uses this bearer for all subsequent emit calls; no JWT refresh, no user-session lifecycle.

### Customer-facing equivalent
**None directly.** The customer-facing api_key surface is `POST /api/v2/integrations/{integrationId}/api-keys` (ApiKeyController), but it's gated to `principal_kind='integration'` — credentials tied to a specific Integration row that represents a customer-configured POS / EAS / inventory system. **For internal-platform service principals (twin, edge subscribers, scheduled jobs) there is no customer-facing endpoint.** Mig 124 (2026-05-09) added the `service` principal_kind specifically for this case.

The intended path per CLAUDE.md branded terms ("M8TRX Connect"): `POST /api/v2/connect/credentials` (ExternalPrincipalController), but it's an MVP stub returning 501 (`m8trx-services/main-server/src/main/kotlin/com/m8trx/server/connect/ExternalPrincipalController.kt`) — the route exists for surface-gating but business logic ships post-MVP.

### Surface (admin-only path — explicitly called out)
**Direct SQL INSERT into `api_key`** via Bob-run psql or a 075b-style migration. Pattern matches the edge-server provisioning recipe (mig 124 KDoc lines 8-12). Twin should NOT generate the raw key client-side; the convention is `m8trx_<32-char-base64url>` per ApiKeyService.

**One-shot script (Bob runs once):**
```sql
-- Compute raw key + hash off-DB; insert hash + prefix
-- raw_key = "m8trx_" || substring(encode(gen_random_bytes(24), 'base64'), 1, 32)
-- key_prefix = substring(raw_key, 1, 8)
-- key_hash = encode(sha256(raw_key::bytea), 'hex')

INSERT INTO api_key (
    id, tenant_id, owning_tenant_id, integration_id,
    name, key_prefix, key_hash,
    principal_kind, scope_grants,
    enabled, expires_at,
    created_at, created_by
) VALUES (
    gen_random_uuid(),
    '<m8trxdemo-tenant-uuid>',                -- tenant_id (back-compat: mirrors owning_tenant_id)
    '<m8trxdemo-tenant-uuid>',                -- owning_tenant_id (the tenant whose data this credential operates against)
    NULL,                                      -- integration_id NULL for service principals
    'M8TRX Twin — Layer 0 emitter',
    '<first-8-chars-of-raw-key>',
    '<sha256-hex-of-raw-key>',
    'service',                                 -- mig 124 value
    '{"capabilities": [
        {"resource": "scan", "action": "submit", "scope": "TENANT"},
        {"resource": "inventory", "action": "create", "scope": "TENANT"},
        {"resource": "inventory", "action": "update", "scope": "TENANT"},
        {"resource": "inventory", "action": "transfer", "scope": "TENANT"},
        {"resource": "vision_ai", "action": "configure", "scope": "TENANT"}
    ]}'::jsonb,
    true,
    NULL,                                      -- no expiry; rotate manually
    NOW(),
    '<operator-user-uuid>'
);
```

(Capability list is illustrative — replace with the exact set Layer 0 needs once `M8TRX-API-SURFACE.md` is authored. Reference: ApiKeyService.kt:262-267 for the `ServiceCapability` shape; CapabilityFilter.kt:211-218 for the match algorithm.)

### Inputs
- Twin operator's `userId` (Step 1) for `created_by`
- M8trxDemo `tenant.id` (Step 3)
- The exact resource/action/scope tuples Layer 0 will hit (cross-reference [`M8TRX-API-SURFACE.md`](../integration/M8TRX-API-SURFACE.md) — being authored in parallel)

### What gets created
- One `api_key` row with `principal_kind = 'service'`, `revoked_at = NULL`, `expires_at = NULL`
- The raw key string (`m8trx_<32 chars>`) — **NEVER stored in DB; capture immediately to twin's secrets store**

### Outputs twin needs to capture
- The raw bearer string (e.g. `m8trx_AbCdEf...`) — store in 1Password "M8TRX / Twin / Layer 0 service bearer"
- `api_key.id` (for rotation / revocation)
- The hash (DB-side; not needed by twin)

### Verification
- `SELECT id, name, principal_kind, owning_tenant_id, scope_grants FROM api_key WHERE name = 'M8TRX Twin — Layer 0 emitter';` returns one row
- Smoke test: `curl -H "Authorization: Bearer m8trx_..." https://dev.m8trx.com/api/v2/<some-emit-endpoint>` returns 200/201 (NOT 401/403)
- CapabilityFilter logs (LOGS.md → `/opt/m8trx/main/log/main-server/out.log`): grep for `Service api_key cap denied` — should be empty for the configured caps; appears only for caps NOT in `scope_grants.capabilities`

### Known friction / gotchas
- **No CRUD endpoint for service principals.** Per CLAUDE.md branded terms, M8TRX Connect was supposed to ship the customer-facing surface for `external_data_source` and `partner_api` kinds. The `service` kind (mig 124) is internal-only and has no UI / REST surface at all. Twin's bootstrap is via Bob-run SQL OR a 075b-style migration. **Friction candidate (P2):** "Need a `POST /api/v2/admin/api-keys` endpoint for platform-admin to mint service-kind keys without psql." Low priority — service keys are rare (one per edge box, one per twin instance) and rotation is infrequent.
- **`scope_grants.capabilities` array is unvalidated.** ApiKeyService.parseServiceCapabilities (line 349) tolerantly parses; an unknown `resource` string passes through and silently fails to match any annotation at request time. Typo-prone. **Friction candidate (P3):** "Validate `scope_grants.capabilities[].resource` against `capability.resource_type` at insert time." Low blast radius — wrong scope just causes 403s twin will see immediately.
- **Bearer prefix discipline.** The raw key MUST start with `m8trx_` for `verifyServiceBearer` to consider it (ApiKeyService.kt:276). If Bob hand-generates without the prefix, 401 across the board. Use the `m8trx_` || base64 pattern consistently.
- **`integration_id` nullability.** Existing `api_key` rows are `integration`-kind and tied to an integration. Service-kind rows have `integration_id = NULL`. Confirm the column allows NULL on the running DB (it should per mig 105 + mig 124, but the original mig 098 shape may have made it NOT NULL — `[needs verification]` against the mother schema).
- **Audit trail.** Service-bearer requests do NOT write `audit_log.actor_user_id` (no user). They write `actor_api_key_id` (per audit conventions; verify this column exists). For Trinity-tier tables, the Hasura event trigger captures the row-level mutation regardless. For platform-admin actions invoked via JWT (like Step 3), the actor user ID is the operator. Twin's emit-flow audit attribution is by api_key, not user.

---

## Step 5 — Provision the first site (Decathlon Seoul Gangnam Running)

### Step name
Create the first physical site under M8trxDemo. Layout authoring (zones, fixtures, AR anchors) is out of scope here — see [`reference/data/STORE-LAYOUT.md`](../data/STORE-LAYOUT.md) (TBD).

### Customer-facing surface
`POST /api/v2/sites` (SiteManagementController.createSite, line 91). Customer-facing UI: `m8trx-web` Sites page → "Create Site" button (route `/sites/new` `[needs verification]` — confirm in `m8trx-web/src/sites/`).

Cap-gated: `site:create`. Twin's operator holds it via tenant-admin Profile (Step 3 grants).

### Surface
```
POST /api/v2/sites
Authorization: Bearer <operator-access-token>     # JWT path (preferred, captured to audit_log with actor)
                                                   # Service api_key works too if site:create is in scope_grants
Content-Type: application/json

{
  "name": "Decathlon Seoul Gangnam Running",
  "slug": "seoul-gangnam-running",
  "address": "Seoul, Gangnam-gu (full address from research)",
  "latitude": 37.4979,
  "longitude": 127.0276,
  "timezone": "Asia/Seoul",
  "locale": "ko",
  "googlePlaceId": "<optional>",
  "formattedAddress": "<optional>"
}
```

(See `m8trx-services/main-server/src/main/kotlin/com/m8trx/server/site/SiteManagementController.kt:94-158` for the canonical request shape.)

### Inputs
- Site name, slug, address, lat/long, timezone, locale
- Operator JWT (or service api_key with `site:create` cap if doing it via twin's bearer)

### What gets created
- `site` row under M8trxDemo
- `audit_log` entry: `action = 'site_created'`, `entity_type = 'site'`, `entity_id = <new site id>`, `actor_user_id = <operator>` (or `actor_api_key_id` if service path)

### Verification
- `GET /api/v2/sites/{id}` with operator JWT returns the row
- `SELECT id, name, slug, tenant_id FROM site WHERE slug = 'seoul-gangnam-running';` matches
- Web UI: log in as operator → tenant switcher shows M8trxDemo → Sites page lists the new site

### Known friction / gotchas
- **Tier quota gate.** SiteManagementController.createSite (line 127) calls `entitlementService.assertWithinLimit(effectiveTenantId, "max_sites")` — throws HTTP 402 if M8trxDemo's tier quota is exceeded. Enterprise tier has high `max_sites` (per `tier_feature.quota_default`); should not bite for first site. If subsequent sites trigger 402, run `SELECT * FROM tier_feature WHERE feature_key = 'max_sites' AND tier = 'enterprise';` to check the quota and either bump the quota row or restructure (one site is sufficient for MVP demo).
- **Cross-tenant create.** If operator hits `/api/v2/sites` with a `tenantId` in the body that differs from JWT, the platform-admin-only branch (line 109) allows it and writes a `PlatformAdminAuditEvent` row. Twin doesn't need this — operator's JWT carries M8trxDemo as active tenant, body omits `tenantId`, default path applies.
- **Slug uniqueness.** Per-tenant unique constraint on `(tenant_id, slug)` (or similar) — `[needs verification]` of exact constraint name. Duplicate slug returns HTTP 409 with friendly message.
- **Site-scoped permissions.** Twin's operator currently has only `tenant`-scope grants (resolver Step 5 default). Site-scoped staff personas (Step 6 below, optional) would need explicit per-site `user_permission_set` rows or `user_role` assignments. Out of scope for the bootstrap playbook; relevant when twin starts simulating site-manager / staff personas.

---

## Step 6 (deferred) — M8TRX Reach grant to customer tenants

### Step name
Issue a `tenant_share_grant` from M8trxDemo to each customer tenant so they see the demo site read-only in their UI alongside their own data.

### Status
**Deferred.** TenantShareGrantController (`m8trx-services/main-server/src/main/kotlin/com/m8trx/server/sharing/TenantShareGrantController.kt`) is an MVP stub returning 501. The `tenant_share_grant` table exists (mig 104) and is schema-ready; the surface to issue grants through customer-facing flows ships post-MVP.

### Eventual customer-facing surface (post-MVP)
`POST /api/v2/reach/grants` — currently 501. Customer-facing UI TBD (per CLAUDE.md branded terms, the "M8TRX Reach brand-vendor UI" is post-MVP per OQ6 lock).

### Twin's interim approach
Either:
1. **Wait for the surface.** Twin can run the demo without Reach shares; new customer tenants simply don't see M8trxDemo data. Once Reach UI ships, file this as a TWIN-REQ to ensure first-use grant issuance is automatable.
2. **Direct INSERT into `tenant_share_grant`.** Bob-run SQL with grant fields per mig 104 schema (see `status/migrations/104_tenant_share_grant.sql:13-89` for the column shape). Twin would seed one grant per known customer tenant; on each new customer signup, append a row. Heavy maintenance — not recommended for sustainable use.

### Recommended grant shape (when surface lands)
```
grantor_tenant_id    = M8trxDemo.id
grantor_org_id       = NULL
grantee_tenant_id    = <customer tenant>.id
grant_kind           = 'data_share'
permission_level     = 'read'
scope_geometry       = '{"site_ids": ["<seoul-gangnam-site-id>"], "time_window_days": 365}'
feature_scope        = ARRAY['behavioral_trinity', 'commerce_projection', 'traffic_projection']
valid_to             = NULL                                         -- demo runs indefinitely
granted_by           = <operator-user-id>
contract_ref         = 'demo-tenant-default-share'
```

### Verification (when surface lands)
- Customer tenant logs in → site list includes M8trxDemo's Decathlon site, badged DEMO
- Reach grant visible in `tenant_share_grant` with active flags

### Known friction / gotchas
- **Whole step is deferred to post-MVP.** Track as a twin-side STATUS.md entry rather than a TWIN-REQ — core knows the gap, OQ6 lock is documented, no twin-specific need filed-back.
- **Customer-onboarding hook.** When the Reach UI lands, the question is: does Bob (platform-admin) have to manually grant on each new signup, or does a hook auto-issue the standard demo-share grant on tenant creation? Latter is cleaner; former matches AWS-style "explicit access". Recommendation: file TWIN-REQ proposing an `is_auto_demo_share = true` flag on M8trxDemo tenant + `SignupService.provision()` hook to insert the grant.

---

## Onboarding friction surfaced

Candidate TWIN-REQ briefs. Do NOT file; capture for next-session triage with Bob.

| # | Friction | Severity | Surface | TWIN-REQ candidate |
|---|----------|----------|---------|--------------------|
| F1 | No customer-facing path to grant `is_platform_admin = true` to a user. Pure SQL bootstrap (mig 075b precedent). Twin operator provisioning therefore requires Bob-run SQL or a per-environment 075-style migration. | Low (rare op) | DB-only | "Document the platform-admin bootstrap recipe as a first-class operations doc and codify the migration template." Low priority — pattern is well-precedented but undocumented as a template. |
| F2 | `TenantProvisioningController` hardcodes `subscription_tier = 'free'` + `subscription_status = 'trial'`. No tier override accepted in the request body. Twin needs `enterprise` for demo headroom → requires post-create SQL UPDATE to bump tier. | Medium | `POST /api/tenant/provision` | "Accept optional `subscriptionTier` and `subscriptionStatus` in `ProvisionTenantRequest`; require `tenant:configure` cap to set non-default values. Channel partners may want `connected` tier for paid customers; ITX wants `enterprise` for demo." Medium priority — bypasses Stripe billing flow which is fine for admin-driven provisioning paths. |
| F3 | No customer-facing CRUD for `principal_kind='service'` api_keys. M8TRX Connect (`/api/v2/connect/credentials`) is a 501 stub for `external_data_source`/`partner_api`; nothing handles `service`. Twin bootstraps via direct `api_key` INSERT. | Medium (rare op but un-auditable via UI) | none exists | "Add `POST /api/v2/admin/api-keys` for platform-admin to mint service-kind keys with scope_grants. Audit the create + raw-key-return path identically to `ApiKeyController.create`." Medium priority — one-time bootstrap for each edge box / twin instance, but a UI prevents psql drift. |
| F4 | `api_key.scope_grants.capabilities[].resource` and `.action` are not validated against the `capability` table at insert time. Typos silently produce 403s at request time, not insert time. | Low (debuggable) | `POST /api/v2/integrations/{id}/api-keys` and direct INSERT path | "Add a CHECK constraint or trigger validating `scope_grants.capabilities[*]` resource/action tuples against `capability.resource_type` + `capability.action`." Low priority — easily caught in testing, but would surface bad keys at provisioning rather than at first emit. |
| F5 | `subscription_event` records `reason = 'channel_partner_provision'` even for ITX direct-provisioning calls (TenantProvisioningController:154). Audit text is misleading for admin self-service. | Low (cosmetic) | `POST /api/tenant/provision` | "Pass through a `reason` field from the request body, defaulting to `tenant_provisioned` (not `channel_partner_provision`)." Low priority — cosmetic, but audit-readers will notice. |
| F6 | `M8TRX Reach` grant issuance is 501-stubbed. Demo data flow to customer tenants requires either waiting for the post-MVP UI or one-off SQL per customer signup. | High (blocks the "stub demo site" pattern in CLAUDE.md) | `POST /api/v2/reach/grants` | DO NOT file as TWIN-REQ — known core gap (OQ6 lock); core has the timeline. Track as twin STATUS.md "Active Requirements Filed Back to Core" with status `NOT YET FILED — pending OQ6 unlock`. |
| F7 | Auto-demo-share hook on customer tenant creation does not exist. Even when Reach UI lands, every new customer signup will need a manual grant (or Bob-run SQL). | Medium (post-MVP) | `SignupService.provision()` | "Add `is_default_demo_share_grantor: bool` flag to `tenant`. When true, `SignupService.provision()` auto-issues a `data_share` grant from that tenant to every new tenant. M8trxDemo would carry the flag." Medium priority — only relevant once F6 lands; bundle into the same TWIN-REQ. |
| F8 | No platform-admin-facing endpoint to list `is_platform_admin = true` users (or to demote them). Once seeded, the only way out is SQL. | Low (rare) | DB-only | Skip — security-by-friction; rare operations should require explicit DB ops with audit trail. Do NOT file. |
| F9 | Operator's tenant binding for M8trxDemo is implicit (Step 3 mints it as side effect of `/api/tenant/provision`); platform-admin alone (no tenant binding) cannot land in any tenant's UI scope on web. The tenant switcher only renders tenants the user has a `user_tenant_membership` row for. | Low (documented; matches mig 075b) | n/a | DO NOT file — this is the correct design per the operator-role doc. Recommend documenting it in this playbook (done — Step 2 gotcha). |

**Total: 7 candidate briefs (F1–F5, F7), 2 explicit non-files (F6, F8), 1 doc-clarification (F9 already in playbook).**

---

## Steps marked `[needs verification]`

- **Step 2:** Google OAuth flow with a pre-seeded user (no `user_identity` row yet) — confirm the link-by-email branch works as documented in AUTH-FLOW.md Flow 3.
- **Step 3:** Tenant auto-select after first provision — confirm the operator's next `GET /auth/me` actually surfaces M8trxDemo, or whether explicit `/auth/select-tenant` (or equivalent) call is required.
- **Step 4:** `api_key.integration_id` nullability — confirm the running mother schema accepts `NULL` (mig 105 + mig 124 imply yes; original mig 098 may have made it NOT NULL).
- **Step 5:** Web UI route for site creation (`/sites/new` vs other) — confirm path in `m8trx-web/src/sites/`.
- **Step 5:** Site slug per-tenant uniqueness constraint — exact constraint name and behavior on collision.
