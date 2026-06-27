# P2-E — Org Admin Dashboard: Requirements Exploration

Stream **P2-E** owns `/web/admin/`. Depends on **P2-A.5** (org endpoints), which is
merged to master. This doc covers the two exploration tasks **P2-E.1** (what a
technical lead needs to see + do) and **P2-E.2** (onboarding flow UX), and pins
the architecture decisions implementation will follow. Trust-first framing
throughout (§1, §8 of `00-SYSTEM-KNOWLEDGE.md`): show **aggregates and output**,
never surveillance.

---

## 0. What the backend actually gives us (grounding, from as-built P2-A code)

Everything below is read from the merged spine — these are the only shapes the
admin UI can consume today.

### Auth surface — `/api/v1/auth/**` (permitAll) + authenticated onboarding
| Method | Path | Body / params | Response |
|---|---|---|---|
| POST | `/auth/register-org` | `{orgName,email,password(≥10),displayName}` | `AuthResponse` |
| POST | `/auth/login` | `{email,password,orgSlug?}` | `AuthResponse` |
| POST | `/auth/refresh` | `{refreshToken}` | `TokenPair` |
| POST | `/auth/logout` | `{refreshToken}` | 204 |
| GET  | `/auth/invite/{token}` | — | `InvitePreview{orgName,email}` |
| POST | `/auth/invite/accept` | `{token,password(≥10),displayName?,email?}` | `AuthResponse` |
| POST | `/auth/password/forgot` | `{email}` | 202 |
| POST | `/auth/password/reset` | `{token,newPassword(≥10)}` | 204 |
| POST | `/org/invites` *(admin)* | `{email?,role?,teamId?,maxUses?,ttlHours?}` | `CreateInviteResponse{token,url,expiresAt}` |
| POST | `/me/device-codes` *(member)* | `?deviceLabel=` | `DeviceCodeResponse{code,expiresAt}` |

`AuthResponse = {accessToken, refreshToken, tokenType, expiresInSeconds,
member{id,email,displayName,role,status}, org{id,name,slug,privacyLevel}}`.
Access JWT is HS256, 60 min; refresh is opaque + rotating (reuse revokes the
family). Roles: `owner` / `admin` (both `isAdmin()`), `member`.

### Admin read surface — `/api/v1/org/**` (authenticated; `requireAdmin` server-side)
| Method | Path | Params | Response |
|---|---|---|---|
| GET | `/org/members` | `cursor?`, `limit=100` | `MembersResponse{items:MemberSummary[],nextCursor}` |
| GET | `/org/summary` | `range?`, `team?` | `OrgSummary` |

- `MemberSummary = {memberId,email,displayName,role,status,teams:string[]}`.
- `range` ∈ `today|24h|7d|30d|90d|week|month` (default `7d`); anything else → 400.
- `OrgSummary = {from,to,team,privacyLevel, orgTotalsByCategory:CategoryBucket[],
  orgByDay:DayBucket[], byMember:MemberRollup[]}`.
  - `CategoryBucket = {category,totalMs,eventCount}`.
  - `DayBucket = {date, byCategory:CategoryBucket[]}` → the **heatmap** source.
  - `MemberRollup = {memberId,displayName, byCategory:CategoryBucket[],
    tokens:TokenSummary}`.
  - `TokenSummary = {totalCostUsd, byModel:ModelBucket[]}`,
    `ModelBucket = {model,costUsd,tokensIn,tokensOut}` → the **token-spend** source.
- **Privacy enforcement is server-side on read.** Under `aggregate_only` the
  backend returns `byMember = []` (org totals + per-day only). Under
  `categories_only` / `full` it returns per-member rollups. The UI renders
  whatever it gets and must degrade gracefully — it never assumes `byMember` is
  populated.

### Per-member drilldown (P2-E.6)
There is **no `/org/members/{id}/summary`** endpoint. The per-member rollup is
the `byMember[]` slice already inside `/org/summary`. So the drilldown is built
from data we already hold — it is privacy-bounded **by construction** (absent
under `aggregate_only`, no titles/URLs ever, only category + token rollups). We
do **not** get per-event detail for a member, which is correct for trust-first.

---

## 1. P2-E.1 — What a technical lead needs to see + do (trust-first)

The buyer is a tech lead / founder running a 5–50 dev team. They need answers to
"is the team healthy and where is our AI spend going?", **not** "what is Alice
doing right now". The admin UI is framed around team health and cost, never
keystroke-level monitoring. Concretely:

**See (read):**
1. **Roster** — who's on the team, role, status (active/invited/suspended),
   team membership. Source: `/org/members`.
2. **Team category mix over time** — a day×category **heatmap** (where the team's
   hours go: deep_work / meetings / comms / …). Source: `orgByDay`.
3. **AI token spend** — total $ and a per-model breakdown; the wedge feature.
   Source: `orgTotalsByCategory` (time) + `byMember[].tokens` / aggregate tokens.
4. **Commit activity** — per the phase doc P2-E.5. **GAP:** `/org/summary` has no
   github/commit field, and P2-D (github) is a parallel, not-yet-built stream. See
   §5 NEEDS. Until then this panel renders an honest "GitHub not connected" state.
5. **Per-member drilldown** — category mix + token spend for one member, **only**
   when the org privacy level permits (`byMember` present). Never titles/URLs.

**Do (write):**
6. **Invite members** — generate a targeted or open invite link/code
   (`POST /org/invites`), copy it, see expiry.
7. **Set the org privacy level** — full / categories_only / aggregate_only. This
   is the trust contract with the team. **GAP:** no backend endpoint to *change*
   `orgs.privacy_level` exists yet (only read via `org.privacyLevel`). See §5.
8. **Manage own session** — login / logout, and an install page for the daemon.

**Trust-first UI rules (apply everywhere):**
- Lead with team aggregates; member detail is opt-in via explicit drilldown.
- Surface the **current privacy level** prominently as a banner — the admin (and
  by extension the team) always knows exactly what is and isn't visible.
- Never invent data the privacy level withholds: if `byMember` is empty, show
  "Per-member detail is hidden at this privacy level", not zeros.
- Words: "team health", "where time goes", "AI spend" — never "monitoring",
  "tracking employees", "productivity score".

---

## 2. P2-E.2 — Onboarding flow UX

Phase-2 exit criterion: an admin onboards 5–10 devs in **under 30 minutes** with
**zero manual DB work**. The flow:

```
Register org ──► Set privacy level ──► Invite members ──► Share install steps
 (founder)        (trust contract)      (link/email)        (daemon + exts)
     │                                       │                    │
     ▼                                       ▼                    ▼
 AuthResponse                         invite URL + code     member accepts invite,
 (admin seat)                                              installs daemon, enrolls
                                                            device → data appears
```

1. **Register** (`/register-org`): org name, admin email, password. Returns the
   admin's token pair + org. First screen after = the dashboard with an empty
   roster and a clear "Invite your team" call to action.
2. **Privacy first**: immediately prompt to confirm/choose the org privacy level
   so the trust contract is set *before* anyone's data flows. (Blocked on the
   set-privacy endpoint — §5; interim: show the default `categories_only` and the
   level chosen at register.)
3. **Invite**: admin creates invites. Two modes the backend supports — targeted
   (`email` set) or open shareable link (`maxUses`/`ttlHours`). UI shows the URL +
   a copy button + expiry.
4. **Member accepts** (`/auth/invite/{token}` preview → `/auth/invite/accept`):
   the invitee sets a password, lands authenticated.
5. **Install** (P2-E.7): a page with copy-paste daemon + extension install steps
   and the member's **device-enrollment code** (`/me/device-codes`) so the daemon
   self-enrolls (resolves the P1 member_id gap — see PROGRESS coordination block).
6. **Data appears**: once daemons sync (P2-B), the admin's heatmap/token panels
   populate. Until then every panel shows an honest empty state.

---

## 3. Architecture decision — BFF proxy with httpOnly cookie session

**Decision: the admin app is a Next.js App-Router app that talks to the backend
through its own server-side route handlers (a Backend-For-Frontend), exactly like
the P1-D dashboard proxies the daemon.** The browser only ever calls same-origin
`/api/*` Next routes; those routes attach the JWT and call the Spring backend.

Why BFF over direct browser→backend:
- **No CORS change needed.** The backend (`SecurityConfig`) configures **no CORS**.
  A direct browser SPA on `:3000` calling `:8080` would be blocked. BFF keeps the
  browser same-origin, so we need no change to the spine's security config.
- **Tokens never touch JS.** Access + refresh tokens live in **httpOnly, SameSite
  cookies** set by the Next server. This is the trust-first choice (no XSS token
  theft) and matches the product's identity.
- **Refresh is invisible.** The BFF transparently calls `/auth/refresh` on a 401
  and rotates the cookie — the React app never handles token lifecycle.
- **Consistency.** Mirrors the already-shipped dashboard proxy pattern (P1-D),
  same `force-dynamic` route handlers + RFC 7807 problem+json error passthrough.

`NEXT_PUBLIC_API_BASE` in the phase doc is replaced at runtime by a **server-side**
`CADENCE_API_BASE` (default `http://localhost:8080`) for the same reason P1-D
switched off `NEXT_PUBLIC_*`: the value is read on the server, not inlined into the
browser bundle. (Will note this env correction to the spine, as P1-D did.)

### App structure (self-contained Next app under `/web/admin/`)
The PROGRESS note says the shared `/web` shell refactor is "deferred to the P2 web
spine" — **there is no P2 web spine stream**. So, like P1-D, P2-E ships a
self-contained Next app rooted at `/web/admin/`, mirroring the dashboard's
toolchain (Next 14.2.35, React 18, Tailwind, shadcn-style primitives, no extra
deps). Shared-shell consolidation stays a later bet.

```
/web/admin/
  app/
    login/                  P2-E.3  login page
    invite/[token]/         P2-E.3  accept-invite page
    (dashboard)/            authenticated shell (privacy banner + nav)
      page.tsx              P2-E.5  team summary (heatmap + tokens + commits)
      roster/               P2-E.4  roster + invites + privacy control
      members/[id]/         P2-E.6  member drilldown (privacy-bounded)
      install/              P2-E.7  install instructions + device code
    api/
      auth/login|logout|refresh|accept   BFF auth → cookie
      org/members|summary|invites        BFF proxy (attaches JWT)
      me/device-codes                    BFF proxy
  lib/
    api/                    server-side backend client + cookie/session
    contract/               TS mirrors of Summaries.* / AuthDtos.* (snake_case)
    colors.ts               reuse the frozen category palette
  middleware.ts             redirect unauthenticated → /login
```

The category color palette (`cat.*`) is reused verbatim from the dashboard so the
admin heatmap and the personal donut read as one product.

---

## 4. Mapping to the P2-E task list
- **P2-E.3** auth pages → `login/`, `invite/[token]/`, BFF `api/auth/*` + cookies.
- **P2-E.4** roster + invites + privacy → `roster/`, `api/org/members`,
  `api/org/invites`; privacy **read** now, **write** blocked on §5 endpoint.
- **P2-E.5** team summary → heatmap from `orgByDay`, tokens from `byMember`/totals,
  commits = honest "not connected" until §5/P2-D land.
- **P2-E.6** member drilldown → render `byMember[id]`; absent under `aggregate_only`.
- **P2-E.7** install page → device-code mint + copy-paste daemon/ext steps.

## 5. Gaps / cross-stream NEEDS (to file in PROGRESS Coordination block)
1. **NEEDS P2-E → P2-A:** an admin endpoint to **set** `orgs.privacy_level`
   (e.g. `PATCH /api/v1/org/settings {privacyLevel}`), admin-guarded. Today the
   level is only readable (`org.privacyLevel`); P2-E.4 must be able to change it.
   Interim: privacy control is **read-only** in the UI until this lands.
2. **NEEDS P2-E → P2-A (+ depends P2-D):** commit activity in `/org/summary`
   (e.g. a `commits` block: per-member / per-day commit counts from `source:github`
   events). Not in `OrgSummary` today, and github ingestion (P2-D) is a parallel
   unbuilt stream. Interim: the commit panel renders a "GitHub not connected" state.
3. **NOTE to spine (env):** `NEXT_PUBLIC_API_BASE` → runtime server-side
   `CADENCE_API_BASE` (BFF reads it on the server; `NEXT_PUBLIC_*` is build-inlined
   and can't be set per deploy). Same correction P1-D made for the agent base.
```
