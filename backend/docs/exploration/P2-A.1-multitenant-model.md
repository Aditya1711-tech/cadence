# P2-A.1 — Multi-tenant data model + onboarding UX

Exploration deliverable for the Phase-2 spine. This freezes the **shape** of the
schema that `V1__init.sql` (P2-A.3) will encode and that five Wave-1 streams
depend on. Nothing here builds yet — it is the design contract to agree before
the migration is written.

Grounding (treated as frozen truth):
- Event Contract — `00-SYSTEM-KNOWLEDGE.md` §5
- REST conventions — §6
- DB conventions (tenancy, hypertable, job_queue) — §7
- Privacy model — §8
- Directory ownership — §9

---

## 1. Tenancy strategy

**Shared schema, single database, `org_id` discriminator + Postgres RLS.**

Rationale: deployment is a single EC2 box + single Postgres (§3, §4). We are not
multiplying schemas or databases per tenant — that would explode operational
surface for a 5–50-dev-org product. §7 already mandates the model: *"every row
that belongs to an org carries `org_id uuid not null`. Row-level security
enforced; queries always filter by `org_id`."*

How RLS is wired (detail in P2-A.2, summarised here so the model makes sense):
- A dedicated **non-owner** application DB role (RLS does **not** apply to the
  table owner / superuser / `BYPASSRLS` roles).
- Every org-scoped table has RLS enabled with a policy
  `USING (org_id = current_setting('app.current_org')::uuid)`.
- A per-request Spring filter runs
  `SELECT set_config('app.current_org', <jwt.org>, true)` (txn-local) before any
  query touches the DB. Same for `app.current_member`, `app.current_role`.
- Net effect: a query can physically only see its own org's rows even if app
  code forgets a `WHERE org_id =` clause. Defense in depth, not the only defense.

---

## 2. Entities & relationships

```
orgs 1───* members ───* team_members *─── teams
  │           │
  │           └──1 seats (one active seat per active member)
  │
  ├──* invites          (account onboarding + device enrollment origin)
  ├──* events           (TimescaleDB hypertable, partitioned on ts_start)
  └──* job_queue        (categorize/digest async work)

auth side-tables (P2-A.2): refresh_tokens, one_time_tokens
```

### orgs — the tenant
| column | type | notes |
|---|---|---|
| id | uuid PK | `gen_random_uuid()` |
| name | text not null | display name |
| slug | citext unique not null | used in login disambiguation + invite URLs |
| privacy_level | text not null default `categories_only` | enum: `full` \| `categories_only` \| `aggregate_only`; default from env `DEFAULT_ORG_PRIVACY` |
| created_at | timestamptz not null default now() | |

Privacy is a single column, not a table — the three §8 levels are a closed enum.
The user-controlled **local redaction regex list** lives client-side on the
daemon (P1-A.8), never server-side, so it needs no table here.

### members — a person in an org
| column | type | notes |
|---|---|---|
| id | uuid PK | **this is the `member_id` in the Event Contract** (see §3 below) |
| org_id | uuid not null → orgs | |
| email | citext not null | |
| password_hash | text null | null until invite accepted / password set |
| display_name | text null | |
| role | text not null default `member` | enum: `owner` \| `admin` \| `member` |
| status | text not null default `invited` | `invited` \| `active` \| `disabled` |
| github_login | text null | pre-provisioned for P2-D.4 (github→member map) |
| created_at | timestamptz not null default now() | |

Constraints: `unique (org_id, email)`. Exactly one `owner` per org (first
registrant). `github_login` is added now (nullable) so P2-D needs no migration.

### teams + team_members
| teams | type |
|---|---|
| id | uuid PK |
| org_id | uuid not null → orgs |
| name | text not null |
| created_at | timestamptz |
`unique (org_id, name)`.

| team_members | type |
|---|---|
| org_id | uuid not null → orgs (denormalized for RLS) |
| team_id | uuid not null → teams |
| member_id | uuid not null → members |
PK `(team_id, member_id)`.

**Decision — join table, not a single `members.team_id`.** A dev rotating teams
is realistic and `/org/summary?team=` rollups want clean membership history.
A join table avoids a painful column→table migration later (and I am the *only*
stream allowed to migrate). Cost is one extra JOIN in team rollups.

### seats — a paid license
| column | type | notes |
|---|---|---|
| id | uuid PK | |
| org_id | uuid not null → orgs | |
| member_id | uuid null → members | null = allocated-but-unassigned |
| status | text not null default `active` | `active` \| `revoked` |
| created_at | timestamptz | |

Partial unique `(member_id) where status='active'` → one active seat per member.
Active-seat **count drives billing (P3-D)**; modeling it now means P3-D adds no
migration. Pre-billing, `register-org`/invite-accept just allocate a seat.

### invites — onboarding origin
| column | type | notes |
|---|---|---|
| id | uuid PK | |
| org_id | uuid not null → orgs | |
| email | citext null | null = open/shareable link; set = targeted invite |
| token_hash | text not null unique | SHA-256 of the high-entropy token; **plaintext never stored** |
| role | text not null default `member` | role granted on accept |
| team_id | uuid null → teams | auto-add to team on accept |
| max_uses | int null | null = unlimited (shareable link); 1 = single-use |
| uses | int not null default 0 | |
| expires_at | timestamptz null | |
| created_by_member_id | uuid null → members | |
| created_at | timestamptz not null default now() | |

One table covers **both** flows: targeted email invite (`email` set,
`max_uses=1`) and a shareable Slack-able org link (`email` null,
`max_uses` null). The token in the URL is the secret; we store only its hash.

### events — the hypertable (mirrors Event Contract §5)
| column | type | source |
|---|---|---|
| event_id | uuid not null | collector-generated (idempotency key) |
| org_id | uuid not null | **stamped from JWT at ingest, never from client** |
| member_id | uuid not null | from JWT (must equal event's member; see §3) |
| schema_ver | int not null | contract |
| source | text not null | `os\|vscode\|chrome\|token\|github` |
| ts_start | timestamptz not null | **partition column** |
| ts_end | timestamptz not null | |
| duration_ms | bigint not null | |
| app | text null | nullable per privacy |
| title | text null | nullable / redacted per privacy |
| url | text null | nullable per privacy |
| project | text null | |
| category | text null | null pre-classify; worker fills it |
| is_idle | boolean not null | |
| meta | jsonb not null default `'{}'` | additive forever (§5) |
| created_at | timestamptz not null default now() | |

**Hypertable & idempotency — the contract-critical bit:**
- `create_hypertable('events','ts_start')`.
- TimescaleDB requires every unique index to **include the partition column**.
  So the idempotency key is `UNIQUE (event_id, ts_start)`, not `event_id` alone.
- This is still correct idempotency: `event_id` is a globally-unique uuid-v4 and
  a re-sent event always carries the *same* `ts_start` (the collector does not
  mutate it). Ingest does `ON CONFLICT (event_id, ts_start) DO NOTHING`.
- Read index: `(org_id, member_id, ts_start DESC)` for timeline/summary.

### job_queue — async work (§7) **+ org_id**
§7 gives the DDL without `org_id`, but §7 *also* says every org-scoped row
carries `org_id`. Categorisation/digest jobs are org-scoped (and P2-F.5 needs a
per-org daily token cap). So V1 adds `org_id uuid not null` to the §7 schema and
otherwise keeps it verbatim (kind/payload/status/attempts/run_after/locked_by/
locked_at/created_at, index on `(status, run_after)`). **This is a documented
extension of a frozen §7 contract → surfaced to the user before migrating.**
Workers still claim via `FOR UPDATE SKIP LOCKED`.

### Continuous aggregates (TimescaleDB CAGGs)
- **daily category rollup**: `(org_id, member_id, category, day)` →
  `sum(duration_ms)`, `count(*)`. Powers `/me/summary`, `/org/summary`, and the
  `aggregate_only` read path.
- **hourly category rollup**: same grain at hour resolution for the timeline UI.
- **daily token rollup**: `(org_id, member_id, meta->>'model', day)` →
  `sum((meta->>'cost_usd')::numeric)`, `sum((meta->>'tokens_in')::int)`,
  `sum((meta->>'tokens_out')::int)` over `source='token'`. Powers P2-C.5 and the
  admin token-spend panel. (CAGG on a jsonb expression — verify exact Timescale
  syntax at implementation; fallback is a plain rollup view refreshed by a job.)

---

## 3. Resolving the Phase-1 `member_id` gap

Phase-1 left an OPEN coordination item (P1-B and P1-C): collectors self-generate
a local `member_id` because no canonical identity existed. **Phase 2 resolves
it:** the canonical `member_id` IS `members.id`, assigned by the backend. The
daemon learns it during **device enrollment** (P2-B.5) and all collectors on
that machine then share it. Ingest **stamps `org_id` from the JWT** and
**requires the event's `member_id` to equal the authenticated member** (a member
may only upload their own events) — never trusting client-supplied tenancy. This
is the server-side guarantee §6 demands. (Note left for P2-B in Coordination.)

---

## 4. Privacy: store-at-level on ingest + enforce-on-read (§8, P2-A.7)

§8 says the policy is applied **server-side on ingest AND again on read**. Two
philosophies were considered:

- **(A) store raw, redact on read** — flexible (change level later, history
  adapts) but the cloud holds window titles even under `categories_only`, which
  contradicts the trust-first identity (§1: *"only what the user opts to share
  leaves the device"*).
- **(B) store-at-level on ingest** — the cloud never persists what the level
  forbids. Stronger guarantee, matches identity. Cost: lowering then raising the
  level cannot recover detail that was never stored.

**Recommendation: (B), with read-side enforcement as defense in depth.** The
daemon already redacts to the org level before sync (P2-B.3); the server
re-applies it on ingest (never trust the client) and again on read.

| org `privacy_level` | stored on ingest | `/me/*` returns | `/org/*` (admin) returns |
|---|---|---|---|
| `full` | all fields | all | all |
| `categories_only` | `app`, `title`, `url` → NULL; keep category/duration/project/source/is_idle | stored (no titles/urls/app) | same |
| `aggregate_only` | per-event rows kept (app/title/url/project NULL) **only** to feed CAGGs | **daily category totals only**, no per-event | daily category totals only |

The §8 table describes "what the org admin sees"; the member's rich per-event
view is the **local** dashboard (P1-D). The cloud serves what the level allowed
into storage.

---

## 5. Onboarding UX — "register → invite link → install → data appears"

Target (P2-A.1 best-UX angle): a technical lead onboards 5–10 devs in <30 min.

```
1. Admin self-registers
   POST /auth/register-org {org_name, email, password}
   → org (privacy = DEFAULT_ORG_PRIVACY), owner member (active), 1 seat, JWT pair.

2. Admin creates an invite
   POST /org/invites {email?, role?, team_id?, max_uses?}
   → { token, url: https://app/invite/<token>, expires_at }
   Smoothest path: one open, multi-use org link dropped in Slack.

3. Each dev accepts (web)
   GET  /auth/invite/<token>            → preview {org_name, email?}
   POST /auth/invite/accept {token, password, display_name}
   → member active, seat allocated, JWT pair.
   The accept page then shows the daemon install command embedding a one-time
   DEVICE ENROLLMENT CODE.

4. Dev installs the daemon, which enrolls the device
   POST /auth/device/enroll {code}
   → { member_id (=members.id), access_token, refresh_token }  (kept in keychain)
   Events start flowing; admin watches the roster light up.
```

This separates **web account** (password, for the dashboard) from **device
identity** (tokens for the daemon), while keeping it one continuous click-path.
A daemon-only fast path (enroll directly from the invite token, no web step) is
a viable alternative for headless installs — noted for P2-B to choose. The
endpoint shapes that P2-B/P2-E depend on are frozen in P2-A.2.

---

## 6. What this unblocks (Wave-1 dependencies)

- **P2-B** (sync): `member_id`=`members.id`, ingest auth = Bearer access token,
  device-enroll endpoint, idempotency `(event_id, ts_start)`.
- **P2-C** (token watcher): events `source='token'` + daily token CAGG.
- **P2-D** (github): `members.github_login`, events `source='github'`.
- **P2-E** (admin UI): orgs/members/teams/seats/invites + privacy_level + org
  rollup CAGGs.
- **P2-F** (worker): `job_queue` (+org_id), ingest enqueues categorize jobs.

---

## 7. Open decisions to confirm with the user before P2-A.3

1. **`job_queue.org_id` extension** to the frozen §7 DDL (justified by the
   tenancy rule + P2-F per-org cap). OK to encode?
2. **Privacy philosophy (B) store-at-level on ingest** vs (A) store-raw. (B)
   recommended.
3. **Teams as a join table** vs single `members.team_id`. Join table recommended.
4. **Login disambiguation** when one email exists in multiple orgs: require
   `org_slug` on `/auth/login` when ambiguous (detail in P2-A.2).
