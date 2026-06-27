# P2-A.2 — JWT issuance + refresh, password reset, invite-token, device enrollment

Exploration deliverable for the Phase-2 spine. Pairs with
`P2-A.1-multitenant-model.md`. Freezes the auth/token contract that P2-B (daemon
enrollment + sync auth) and P2-E (web auth pages) depend on.

Grounding: §3 (self-issued JWT, virtual threads, Spring Security — not WebFlux),
§6 (REST conventions, `Authorization: Bearer`), §7 (RLS by `org_id`). Env vars
from the phase doc: `JWT_SIGNING_SECRET` (32+ bytes), `JWT_TTL_MINUTES=60`.

---

## 1. Token model

Two token types — short-lived stateless access + long-lived revocable refresh.
The daemon runs for days/offline, so we cannot rely on a 60-minute token alone,
and we must be able to revoke (logout, disable member, rotate secret).

### Access token — JWT, HS256
- Signed with `JWT_SIGNING_SECRET` (HS256; symmetric is fine — single backend,
  no third-party verifier. Upgrade to RS256 only if a verifier outside the
  backend ever needs it).
- TTL = `JWT_TTL_MINUTES` (60).
- Claims:
  ```jsonc
  {
    "sub":  "<member_id>",     // = members.id = Event Contract member_id
    "org":  "<org_id>",        // drives RLS app.current_org
    "role": "owner|admin|member",
    "typ":  "access",
    "iat":  <epoch>, "exp": <epoch>, "jti": "<uuid>"
  }
  ```
- Sent as `Authorization: Bearer <jwt>` on every API including `/ingest/events`.
- Stateless: validated by signature + exp; no DB hit on the hot ingest path.

### Refresh token — opaque, rotating, revocable
- 32 bytes random (base64url). **Only its SHA-256 hash is stored.**
- TTL long (default 60 days; constant in code, revisit if it needs an env var).
- **Rotation with reuse detection (token family):** each refresh consumes the
  old token and issues a new one in the same `family_id`. If an already-consumed
  token is presented again → treat as theft, revoke the whole family.
- Why opaque, not a JWT: must be individually revocable (logout, member
  disabled, secret rotation) — a stateless JWT can't be.

#### `refresh_tokens` table
| column | type | notes |
|---|---|---|
| id | uuid PK | |
| member_id | uuid not null → members | |
| org_id | uuid not null → orgs | for RLS |
| token_hash | text not null unique | SHA-256 of the opaque token |
| family_id | uuid not null | rotation lineage |
| device_label | text null | e.g. "macbook-pro daemon" |
| expires_at | timestamptz not null | |
| revoked_at | timestamptz null | |
| replaced_by | uuid null → refresh_tokens | set on rotation |
| created_at | timestamptz not null default now() | |

### Short-lived single-use codes — one table for three jobs
Password-reset tokens, device-enrollment codes, and (future) email-verification
are all "high-entropy, single-use, short-TTL, hashed" → one table avoids sprawl.

#### `one_time_tokens` table
| column | type | notes |
|---|---|---|
| id | uuid PK | |
| org_id | uuid null → orgs | null allowed for pre-org flows |
| member_id | uuid null → members | |
| kind | text not null | `password_reset` \| `device_enroll` |
| token_hash | text not null unique | SHA-256 |
| expires_at | timestamptz not null | reset ~30 min; enroll ~15 min |
| used_at | timestamptz null | single-use guard |
| meta | jsonb not null default `'{}'` | |
| created_at | timestamptz not null default now() | |

> Invites are **not** in `one_time_tokens` — they are a first-class onboarding
> object (`invites` table in P2-A.1: roles, teams, multi-use, audit).

---

## 2. Password handling
- **BCrypt** via Spring Security `DelegatingPasswordEncoder` (strength 12).
  Argon2 is a later upgrade; BCrypt is the low-friction v1 default.
- Minimum policy: ≥10 chars (enforced app-side). `password_hash` nullable on
  `members` until set (invited members have none yet).

---

## 3. Endpoints (auth surface)

§6 freezes `POST /auth/login` and `POST /auth/register-org`. The rest are the
additional auth/onboarding routes this stream owns; all under `/api/v1`.

| method | path | auth | purpose |
|---|---|---|---|
| POST | `/auth/register-org` | public | create org + owner + seat → token pair |
| POST | `/auth/login` | public | email+password (+org_slug if ambiguous) → token pair |
| POST | `/auth/refresh` | refresh token | rotate → new token pair |
| POST | `/auth/logout` | refresh token | revoke presented refresh (family) |
| GET  | `/auth/invite/{token}` | public | preview invite {org_name, email?} |
| POST | `/auth/invite/accept` | public | {token,password,display_name} → activate → token pair |
| POST | `/auth/password/forgot` | public | {email} → issue reset code (emailed) |
| POST | `/auth/password/reset` | public | {token,new_password} → set password |
| POST | `/auth/device/enroll` | enroll code | {code} → {member_id, token pair} |
| POST | `/org/invites` | admin | create invite → {token, url, expires_at} |
| POST | `/me/device-codes` | member | mint a device-enroll code for the daemon |

Errors are RFC 7807 problem+json (§6). Auth failures are deliberately vague
(`invalid credentials`) to avoid user enumeration.

### Login disambiguation
`unique (org_id, email)` lets the same email exist in two orgs. `/auth/login`:
if the email resolves to exactly one member → proceed; if multiple → 409 with a
problem doc listing org slugs, client retries with `org_slug`. Keeps the common
single-org case frictionless.

---

## 4. Flows

### register-org (bootstrap a tenant)
```
POST /auth/register-org {org_name, email, password, display_name?}
 → INSERT org (privacy_level = DEFAULT_ORG_PRIVACY, slug from name+suffix)
 → INSERT member (role=owner, status=active, password_hash=bcrypt)
 → INSERT seat (member_id, active)
 → issue access JWT + refresh token
 → 201 { access_token, refresh_token, member, org }
```

### invite create → accept (web account)
```
admin: POST /org/invites {email?, role?, team_id?, max_uses?}
 → token = 32B random; store SHA-256(token); return plaintext ONCE in url.

dev:   GET  /auth/invite/{token}  → 200 {org_name, email?} | 410 if expired/used-up
dev:   POST /auth/invite/accept {token, password, display_name}
 → validate hash, expiry, uses < max_uses (or unlimited)
 → upsert member: if email-targeted & exists invited → activate; else create
   (status=active, role=invite.role); allocate seat; add to invite.team_id
 → invites.uses += 1
 → issue token pair → 201 { access_token, refresh_token, member, org }
```

### device enrollment (daemon identity, no painful login)
```
dev (logged into web): POST /me/device-codes {device_label?}
 → one_time_tokens(kind=device_enroll, member_id, org_id, 15-min TTL)
 → return short code (shown on the post-accept install page)

daemon: POST /auth/device/enroll {code}
 → validate one_time_tokens(device_enroll), mark used_at
 → issue token pair (refresh device_label set)
 → 200 { member_id, access_token, refresh_token }
 → daemon stores tokens in OS keychain (P2-B.4); member_id now canonical for
   ALL collectors on that machine (resolves the P1 member_id gap).
```
Alternative headless fast-path (daemon enrolls straight from the invite token,
skipping the web step) is left for **P2-B** to decide; the contract supports both.

### refresh / logout
```
POST /auth/refresh {refresh_token}
 → lookup SHA-256(token); if revoked/already-replaced → revoke FAMILY, 401
 → else mark replaced_by, issue new pair (same family) → 200

POST /auth/logout {refresh_token} → revoke that token (and family) → 204
```

### password reset
```
POST /auth/password/forgot {email}
 → if member exists: one_time_tokens(kind=password_reset, 30-min TTL); email link
 → ALWAYS 202 (never reveal whether the email exists)

POST /auth/password/reset {token, new_password}
 → validate+mark used; set password_hash; revoke all refresh tokens for member
 → 204
```
Email delivery: no SES wired in Phase 2 spine yet. Dev mode logs the link / lets
the dev fetch it; SES is a later wiring (note for P2-A.8 / deploy). Flagging so
P2-E's "forgot password" page degrades gracefully in local dev.

---

## 5. RLS ↔ request lifecycle (how auth enforces tenancy)

```
request → Spring Security filter validates Bearer JWT (signature + exp)
        → build principal {member_id, org_id, role}
        → open txn; SELECT set_config('app.current_org',    org_id,   true)
                    SELECT set_config('app.current_member', member_id,true)
                    SELECT set_config('app.current_role',   role,     true)
        → controller/service runs queries under RLS (txn-local settings)
        → commit; settings cleared with the txn
```
- App connects as a **non-owner** role so RLS actually applies.
- Public endpoints (register/login/invite/reset) run outside the org context
  (no `app.current_org`) and use targeted, parameterized queries — they touch
  rows across orgs by design (e.g. resolve an invite token), so they must not be
  under the org RLS policy. They get their own narrowly-scoped DB access.
- Virtual threads (§3) make the blocking JDBC + per-request txn model cheap; no
  WebFlux.

---

## 6. Security checklist (carried into implementation / P2-A.8)
- Store only **hashes** of refresh tokens, invite tokens, reset/enroll codes.
- High entropy (32 bytes) for every token/code.
- `JWT_SIGNING_SECRET` ≥32 bytes from env; reject startup if shorter.
- Rate-limit auth endpoints (login, forgot, enroll) — wire in P2-A.8.
- Generic error messages; no user/email enumeration.
- Refresh rotation with family-reuse detection (theft response).
- HS256 now; RS256 only if an external verifier appears.

---

## 7. Tables this stream adds beyond P2-A.1's core set
`refresh_tokens`, `one_time_tokens` — both org-scoped (RLS), both store only
hashes. Encoded in `V1__init.sql` (P2-A.3) alongside the core model.

---

## 8. Open decisions to confirm before P2-A.3
1. **Refresh-token TTL** as a code constant (60d) vs a new env var. Constant
   recommended for v1 (fewer knobs).
2. **Device-enroll path**: ship the web→code→daemon path now; leave the
   headless invite-token fast-path to P2-B. Confirm.
3. **Email in dev**: log reset/enroll links (no SES in the spine). Confirm this
   is acceptable for the Phase-2 local-cloud milestone.
