# Phase 2 — Cloud + Org Layer

**Goal:** an org admin can onboard a team of 5–10 developers in under 30 minutes;
data syncs from devices to the cloud under the privacy model; AI token spend and
git activity are tracked as first-class signals.

**Wave structure:**
```
Wave 0 (spine, 1 session):  P2-A   backend skeleton + auth + multi-tenant schema + ingest/query contracts
Wave 1 (parallel, 5 sessions):
  P2-B sync engine | P2-C token watcher | P2-D github | P2-E org admin UI | P2-F categorisation worker
```
Launch P2-A alone. When `P2-A.CONTRACT` is ticked, launch the five Wave-1
streams together.

**Exit criteria (gate to Phase 3):**
- A new org can self-register, invite members, set a privacy level.
- Daemon syncs events to the cloud; admin sees team rollups honoring privacy.
- Token spend per member/model/session visible. GitHub commit activity visible.
- 3 pilot companies onboarded with zero manual DB work.

---

## Stream P2-A — Backend skeleton, auth, schema, contracts  (SPINE)

**Owns:** `/backend/` (esp. `ingest/`, `query/`, `migrations/`), `/deploy/`
**Check command:** `cd backend && ./gradlew build`

### Requirements exploration
- `P2-A.1` Multi-tenant data model: orgs, members, teams, seats, invites,
  privacy policy per org. **Best-UX angle:** smoothest possible admin onboarding
  (register → invite link → members install daemon → data appears).
- `P2-A.2` Decide JWT issuance + refresh, password reset, invite-token flow.
  (Self-issued JWT; Cognito stays out to keep AWS minimal.)

### Design / contract-in-code
- `P2-A.3` Flyway `V1__init.sql`: orgs, members, teams, seats, invites,
  `events` hypertable (TimescaleDB), `job_queue` (per system knowledge §7),
  continuous aggregates for hourly/daily category rollups. **This is the schema
  contract.**
- `P2-A.4` Implement `POST /api/v1/ingest/events` (array, ≤1000, idempotent on
  `event_id`, applies privacy policy server-side). **Tick `P2-A.CONTRACT` when
  ingest + query shapes are merged to main.**
- `P2-A.5` Implement `/api/v1/me/timeline`, `/api/v1/me/summary`,
  `/api/v1/org/members`, `/api/v1/org/summary` (shapes per system knowledge §6).
- `P2-A.6` Auth endpoints: `register-org`, `login`, invite acceptance. Spring
  Security + virtual threads. Row-level security by `org_id`.

### Implementation
- `P2-A.7` Privacy enforcement layer (full / categories_only / aggregate_only)
  applied on both ingest and read.
- `P2-A.8` Health endpoint, structured logging, request tracing.
- `P2-A.9` `/deploy/docker-compose.yml` for local cloud (Postgres+TSDB, Redis,
  backend). Used by LOCAL-SETUP.md.

### Verification
- `P2-A.10` End-to-end: register org → login → POST events → read summary, with
  each privacy level producing the right redaction.

### Variables to set
```
# NOTE: Spring needs a JDBC URL scheme. Use jdbc:postgresql://... (not postgres://).
DATABASE_URL=jdbc:postgresql://localhost:5432/cadence
DATABASE_USER=cadence
DATABASE_PASSWORD=
JWT_SIGNING_SECRET=            # 32+ byte random; backend refuses to start if shorter
JWT_TTL_MINUTES=60
REDIS_URL=redis://localhost:6379
SERVER_PORT=8080
DEFAULT_ORG_PRIVACY=categories_only

# Added by P2-A (email + link building; see P2-A.2 exploration):
APP_PUBLIC_BASE_URL=http://localhost:3000   # base for invite/reset links
SMTP_HOST=                    # empty in dev -> reset/enroll links are logged
SMTP_PORT=587
SMTP_USERNAME=
SMTP_PASSWORD=
SMTP_FROM=no-reply@cadence.local
SMTP_STARTTLS=true
```

---

## Stream P2-B — Sync engine (daemon ↔ cloud)

**Owns:** `/agent/sync/` (new subpackage), client glue only
**Check command:** `cd agent && go build ./... && go test ./...`
**Depends on:** `P2-A.CONTRACT` (ingest shape + auth).

### Requirements exploration
- `P2-B.1` Sync strategy: batch interval, backoff, offline queueing, dedupe on
  `event_id`, clock-skew handling. **Best-UX angle:** invisible, never blocks the
  user, recovers cleanly from days offline.
- `P2-B.2` Device enrollment: how a daemon learns its `member_id` + token from
  an invite link without a painful login.

### Implementation
- `P2-B.3` Outbound sync loop: pull un-synced local events, apply privacy
  filter + redaction, POST in ≤1000 batches, mark synced on 2xx.
- `P2-B.4` Token storage in OS keychain; refresh flow.
- `P2-B.5` Enrollment: paste invite link / code → daemon registers device.
- `P2-B.6` Backoff + retry + offline durability tests.

### Variables to set
```
CADENCE_CLOUD_BASE=https://api.<yourdomain>   # or http://localhost:8080 in dev (default)
CADENCE_SYNC_INTERVAL_SEC=300
CADENCE_SYNC_DB_PATH=                          # as-built: sync sidecar DB; default
                                              # sibling of CADENCE_DB_PATH (cadence-sync.db)
# member token persisted in keychain after enrollment (not an env var)
```

---

## Stream P2-C — AI token watcher

**Owns:** `/agent/token/` (collector) + `/backend/token/` (ingest glue)
**Check command:** agent + backend check commands.
**Depends on:** `P2-A.CONTRACT`.

### Requirements exploration
- `P2-C.1` Where each tool writes usage locally: Claude Code session logs,
  Codex/Cursor logs/APIs. Document file locations + formats per OS. **Best-UX
  angle:** zero config — detect installed tools automatically.
- `P2-C.2` Confirm we only read counts/cost/model, never prompt/response text.

### Implementation
- `P2-C.3` Parsers for each supported tool → Event Contract events with
  `source:"token"`, `meta.model/tokens_in/tokens_out/cost_usd`.
- `P2-C.4` Tail logs incrementally (don't reparse); attribute to `project` when
  derivable from cwd.
- `P2-C.5` Backend: aggregate token cost per member/model/day for the dashboards.

### Variables to set
```
CADENCE_TOKEN_SOURCES=claude_code,codex,cursor   # auto-detected; cursor is
                                                 # recognized but server-side-only (not tailed)
CADENCE_CLAUDE_CODE_LOG_DIR=                      # optional override
# As-built additions (P2-C):
CADENCE_CODEX_LOG_DIR=                            # optional override
CADENCE_CODEX_DEFAULT_MODEL=gpt-5-codex           # model when a Codex line omits it
CADENCE_TOKEN_PRICING_PATH=                       # optional JSON per-model price overlay
CADENCE_TOKEN_STATE_DIR=                          # tail-cursor dir; default OS config dir
# No backend env vars: the P2-C.5 token endpoints reuse P2-A's datasource.
```

---

## Stream P2-D — GitHub integration

**Owns:** `/backend/github/`
**Check command:** backend check command.
**Depends on:** `P2-A.CONTRACT`.

### Requirements exploration
- `P2-D.1` GitHub App vs OAuth vs PAT for a 5–50 dev org. **Best-UX angle:** an
  admin installs one GitHub App and the whole org's commit activity flows in.
- `P2-D.2` Hard privacy toggle design: `commit_messages_only` (default) vs
  `full_diff` (opt-in). Never read code by default.

### Implementation
- `P2-D.3` GitHub App: webhook on push/PR; map to Event Contract events
  (`source:"github"`, `meta.commit_sha/repo`).
- `P2-D.4` Map GitHub login → Cadence member.
- `P2-D.5` Respect the toggle: store messages or messages+diff-stats only.

### Variables to set
```
GITHUB_APP_ID=
GITHUB_APP_PRIVATE_KEY=        # PEM, base64 in env
GITHUB_WEBHOOK_SECRET=
GITHUB_DEFAULT_MODE=commit_messages_only
```

---

## Stream P2-E — Org admin dashboard

**Owns:** `/web/admin/`
**Check command:** `cd web && npm ci && npm run lint && npm run build`
**Depends on:** `P2-A.5` (org endpoints).

### Requirements exploration
- `P2-E.1` What a technical lead needs to see + do: roster, invite, set privacy,
  team rollups (time by category, token spend, commit activity), per-member
  drilldown within privacy limits. **Best-UX angle:** trust-first framing — show
  aggregates and output, never surveillance.
- `P2-E.2` Onboarding flow UX: register org → invite → install instructions.

### Implementation
- `P2-E.3` Auth pages (login, accept invite) against P2-A.
- `P2-E.4` Roster + invite management + privacy-level control.
- `P2-E.5` Team summary: category heatmap, token-spend panel, commit activity.
- `P2-E.6` Member drilldown respecting org privacy level.
- `P2-E.7` Install instructions page (daemon + extensions) with the invite code.

### Variables to set
```
# As-built correction (mirrors P1-D): the admin app talks to the backend
# SERVER-SIDE via a BFF proxy, so it reads a RUNTIME var, not the build-inlined
# NEXT_PUBLIC_*. NEXT_PUBLIC_API_BASE is accepted only as a last-resort fallback.
CADENCE_API_BASE=http://localhost:8080          # admin BFF -> backend (runtime)
```

---

## Stream P2-F — Categorisation worker (LLM)

**Owns:** `/backend/worker/`
**Check command:** backend check command.
**Depends on:** `P2-A.3` (`job_queue`), `P2-A.4` (ingest enqueues jobs).

### Requirements exploration
- `P2-F.1` When to escalate from rule classifier to LLM (only low-confidence /
  ambiguous events). **Best-UX angle:** keep cost low — batch, cache by
  app+title pattern, use Haiku for cheap calls.
- `P2-F.2` Prompt design: structured input (app/title/url/project/role) → one of
  the fixed category enums; never free-text categories.

### Implementation
- `P2-F.3` Worker claims jobs via `FOR UPDATE SKIP LOCKED`, calls Anthropic API,
  writes back `category`. Virtual-thread pool.
- `P2-F.4` Pattern cache so repeat app/title combos never re-hit the LLM.
- `P2-F.5` Cost guardrails: per-org daily token cap on categorisation; metrics.

### Variables to set
```
# Worker is OFF unless explicitly enabled (so a box without a key/Redis runs the
# rest of the backend untouched). Set true on the deploy backend service.
CADENCE_CATEGORIZE_ENABLED=false
ANTHROPIC_API_KEY=                      # read from env by the Anthropic SDK
CADENCE_CATEGORIZE_MODEL=claude-haiku-4-5
CADENCE_CATEGORIZE_DAILY_TOKEN_CAP=0    # per org per day; 0 = unlimited
# Tuning (sensible defaults; rarely overridden):
CADENCE_CATEGORIZE_POLL_MS=2000
CADENCE_CATEGORIZE_BATCH=20             # keep <= datasource pool size (20)
CADENCE_CATEGORIZE_MAX_ATTEMPTS=5
CADENCE_CATEGORIZE_MAX_OUTPUT_TOKENS=256
CADENCE_CATEGORIZE_CACHE_TTL_DAYS=30
# Pattern cache + token cap use Redis (already in the stack):
REDIS_URL=redis://localhost:6379
```
> Requires a `claim_categorize_jobs` SECURITY DEFINER migration from P2-A
> (cross-org claim under RLS) — see backend/docs/P2-F-worker.md + PROGRESS NEEDS.

---

## Phase 2 coordination notes
- Only **P2-A** writes Flyway migrations. Other streams file NEEDS lines for
  schema changes; P2-A adds the migration.
- Ingest enqueues categorisation jobs; P2-F consumes. Agree the job payload
  shape in the Coordination block before P2-F implements `P2-F.3`.
