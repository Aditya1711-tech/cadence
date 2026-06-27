# ENV-VARIABLES.md — consolidated reference

Every variable the system uses, by phase and by where it runs. When a stream
finishes, make sure the variables it introduced are reflected here, in
`deploy/.env(.example)`, and in `web/.env.local` as applicable.

Legend — **Where:** `agent` (local daemon), `backend` (Spring on box),
`web` (Next.js), `box` (host/cron/IAM).

---

## Phase 1 (local only)

| Variable | Where | Example | Notes |
|---|---|---|---|
| `CADENCE_AGENT_PORT` | agent, web | `8765` | Loopback port collectors POST to / dashboard reads. |
| `CADENCE_DB_PATH` | agent | `~/.cadence/local.db` | Encrypted SQLite path. |
| `CADENCE_KEYCHAIN_SERVICE` | agent | `cadence-local` | Keychain entry holding the DB key. |
| `NEXT_PUBLIC_CADENCE_AGENT_BASE` | web | `http://127.0.0.1:8765` | Dashboard → daemon. |
| `cadence.enabled` (VSCode) | vscode | `true` | Extension setting. |
| `cadence.agentPort` (VSCode/Chrome) | ext | `8765` | Must match agent port. |
| `cadence.redactPaths` (VSCode) | vscode | `true` | Redact file paths. |
| `cadence.urlPrivacy` (Chrome) | chrome | `domain_only` | `domain_only`/`full`. |

---

## Phase 2 (cloud + org)

| Variable | Where | Example | Notes |
|---|---|---|---|
| `DATABASE_URL` | backend | `postgres://cadence:pw@postgres:5432/cadence` | |
| `DATABASE_USER` | backend, box | `cadence` | |
| `DATABASE_PASSWORD` | backend, box | `<strong>` | Never commit. |
| `JWT_SIGNING_SECRET` | backend | `<32+ bytes>` | Rotate carefully. |
| `JWT_TTL_MINUTES` | backend | `60` | |
| `REDIS_URL` | backend | `redis://redis:6379` | |
| `SERVER_PORT` | backend | `8080` | |
| `DEFAULT_ORG_PRIVACY` | backend | `categories_only` | `full`/`categories_only`/`aggregate_only`. |
| `CADENCE_CLOUD_BASE` | agent | `https://api.<domain>` | Daemon sync target. |
| `CADENCE_SYNC_INTERVAL_SEC` | agent | `300` | |
| `CADENCE_TOKEN_SOURCES` | agent | `claude_code,codex,cursor` | Auto-detected; overridable. |
| `CADENCE_CLAUDE_CODE_LOG_DIR` | agent | _(unset)_ | Optional override. |
| `GITHUB_APP_ID` | backend | | |
| `GITHUB_APP_PRIVATE_KEY` | backend | `<base64 PEM>` | |
| `GITHUB_WEBHOOK_SECRET` | backend | | |
| `GITHUB_DEFAULT_MODE` | backend | `commit_messages_only` | Never default to code. |
| `NEXT_PUBLIC_API_BASE` | web | `https://api.<domain>` | |
| `ANTHROPIC_API_KEY` | backend | `sk-...` | Shared across AI features. |
| `CADENCE_CATEGORIZE_MODEL` | backend | `claude-haiku-4-5` | Cheap batch model. |
| `CADENCE_CATEGORIZE_DAILY_TOKEN_CAP` | backend | `2000000` | Per-org guardrail. |

---

## Phase 3 (AI intelligence + revenue)

| Variable | Where | Example | Notes |
|---|---|---|---|
| `CADENCE_DIGEST_MODEL` | backend | `claude-sonnet-4-6` | Narrative quality matters. |
| `CADENCE_DIGEST_CRON` | backend | `0 0 23 * * SUN` | Weekly. |
| `EMAIL_FROM` | backend | `insights@<domain>` | |
| `EMAIL_PROVIDER_API_KEY` | backend | | SES or other. |
| `CADENCE_PATTERN_MIN_DAYS` | backend | `14` | Min history for patterns. |
| `CADENCE_NLQUERY_MODEL` | backend | `claude-sonnet-4-6` | |
| `CADENCE_NLQUERY_DB_ROLE` | backend | `cadence_readonly` | SELECT-only, RLS on. |
| `CADENCE_NLQUERY_MAX_ROWS` | backend | `5000` | |
| `STRIPE_SECRET_KEY` | backend | `sk_live_...` | |
| `STRIPE_WEBHOOK_SECRET` | backend | `whsec_...` | |
| `STRIPE_PRICE_TEAM_ANNUAL` | backend | `price_...` | |
| `STRIPE_PRICE_GROWTH_ANNUAL` | backend | `price_...` | |
| `CADENCE_TOKEN_INCLUDED_PER_SEAT` | backend | `500000` | Included token allotment. |
| `CADENCE_TOKEN_OVERAGE_PER_1K_USD` | backend | `0.002` | Overage rate. |
| `CADENCE_BUDGET_MODEL` | backend | `claude-haiku-4-5` | Alert text. |
| `CADENCE_BUDGET_CHECK_CRON` | backend | `0 0 * * * *` | Hourly. |
| `SLACK_WEBHOOK_URL` | backend | | Dev default; per-org stored in DB. |

---

## Deploy / box-level (prod)

| Variable | Where | Notes |
|---|---|---|
| `AWS_S3_BUCKET` | backend, box | Backups + exports + cards. |
| `AWS_REGION` | backend, box | |
| (IAM instance role) | box | Grants S3 put/get on the bucket only — no static keys. |

---

## Hygiene
- `.env` files are `chmod 600`, never committed. Provide `deploy/.env.example`
  with empty values and commit that instead.
- Model names live in their own vars so cost is tunable per feature without code
  changes.
- When a phase adds a variable, update: this file, `deploy/.env.example`, and the
  relevant phase doc's "Variables to set" block — in the same commit.
