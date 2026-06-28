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
| `CADENCE_AGENT_PORT` | agent, web | `47821` | Loopback port collectors POST to / dashboard reads. Agent default. |
| `CADENCE_DB_PATH` | agent | `~/.config/cadence/cadence.db` | Encrypted SQLite path. Unset → `<os.UserConfigDir>/cadence/cadence.db`. |
| `CADENCE_KEYCHAIN_SERVICE` | agent | `com.cadence.agent` | Keychain service; accounts `store-key` (DB key) + `member-id` (identity). |
| `CADENCE_MEMBER_ID` | agent | _(unset)_ | Optional; unset → a uuid is generated and persisted in the keychain. |
| `CADENCE_RULES_PATH` | agent | _(unset)_ | Optional JSON classifier ruleset; scaffolded with the default on first run. Unset → built-in default. |
| `CADENCE_REDACT_PATH` | agent | _(unset)_ | Optional JSON `{"patterns":[…]}`; matching titles/urls hashed before store. Unset → off. |
| `CADENCE_AGENT_BASE` | web | `http://127.0.0.1:47821` | Dashboard → daemon. Server-side **runtime** var (read by the proxy). |
| `CADENCE_USE_MOCK` | web | `0` | `1`/`true` renders fixtures instead of the live daemon. |
| `cadence.enabled` (VSCode) | vscode | `true` | Extension setting. |
| `cadence.agentPort` (VSCode/Chrome) | ext | `47821` | Must match `CADENCE_AGENT_PORT`. |
| `cadence.redactPaths` (VSCode) | vscode | `true` | Redact file paths (basename + project only). |
| `cadence.idleThresholdSec` (VSCode) | vscode | `300` | Focus-session idle cutoff; matches the OS collector. |
| `cadence.urlPrivacy` (Chrome) | chrome | `domain_only` | `domain_only`/`full`. |

> The dashboard reads the daemon **server-side** at runtime, so it uses
> `CADENCE_AGENT_BASE` — **not** `NEXT_PUBLIC_*`, which Next inlines at build time
> and so cannot be set per machine. `NEXT_PUBLIC_CADENCE_AGENT_BASE` is accepted
> only as a last-resort fallback.

---

## Phase 2 (cloud + org)

| Variable | Where | Example | Notes |
|---|---|---|---|
| `DATABASE_URL` | backend | `jdbc:postgresql://localhost:5432/cadence` | **JDBC scheme** — Spring rejects `postgres://`. |
| `DATABASE_USER` | backend, box | `cadence` | App connects as the **owner** today (RLS is a backstop; see §7). |
| `DATABASE_PASSWORD` | backend, box | `<strong>` | Never commit. Dev compose default `cadence`. |
| `JWT_SIGNING_SECRET` | backend | `<32+ bytes>` | Backend refuses to start if shorter. Rotate carefully. |
| `JWT_TTL_MINUTES` | backend | `60` | Access-token TTL. |
| `REDIS_URL` | backend | `redis://redis:6379` | P2-F pattern cache + daily token cap. |
| `SERVER_PORT` | backend | `8080` | |
| `DEFAULT_ORG_PRIVACY` | backend | `categories_only` | `full`/`categories_only`/`aggregate_only`. Org default at register; **no API setter yet** (§6 gap). |
| `APP_PUBLIC_BASE_URL` | backend | `http://localhost:3000` | Base for invite/reset links in emails. |
| `SMTP_HOST` | backend | _(empty in dev)_ | Empty → reset/enroll links are logged, not emailed. |
| `SMTP_PORT` | backend | `587` | |
| `SMTP_USERNAME` | backend | | |
| `SMTP_PASSWORD` | backend | | |
| `SMTP_FROM` | backend | `no-reply@cadence.local` | |
| `SMTP_STARTTLS` | backend | `true` | |
| `CADENCE_CLOUD_BASE` | agent | `http://localhost:8080` | Daemon sync target (default localhost:8080). |
| `CADENCE_SYNC_INTERVAL_SEC` | agent | `300` | |
| `CADENCE_SYNC_DB_PATH` | agent | _(unset)_ | Sync sidecar DB; default sibling of `CADENCE_DB_PATH` (`cadence-sync.db`). |
| `CADENCE_TOKEN_SOURCES` | agent | `claude_code,codex,cursor` | Auto-detected; `cursor` recognized but server-side-only (not tailed). |
| `CADENCE_CLAUDE_CODE_LOG_DIR` | agent | _(unset)_ | Optional override. |
| `CADENCE_CODEX_LOG_DIR` | agent | _(unset)_ | Optional override. |
| `CADENCE_CODEX_DEFAULT_MODEL` | agent | `gpt-5-codex` | Model when a Codex log line omits it. |
| `CADENCE_TOKEN_PRICING_PATH` | agent | _(unset)_ | Optional JSON per-model price overlay (cost is computed from tokens). |
| `CADENCE_TOKEN_STATE_DIR` | agent | _(unset)_ | Tail-cursor dir; default OS config dir. |
| `GITHUB_APP_ID` | backend | | Required for `full_diff` enrichment. |
| `GITHUB_APP_PRIVATE_KEY` | backend | `<base64 PEM>` | |
| `GITHUB_WEBHOOK_SECRET` | backend | | HMAC verify of `/github/webhook`. |
| `GITHUB_DEFAULT_MODE` | backend | `commit_messages_only` | Never default to code. |
| `CADENCE_API_BASE` | web | `http://localhost:8080` | **Admin BFF → backend, runtime** (mirrors the P1-D fix). `NEXT_PUBLIC_API_BASE` is a build-inlined last-resort fallback only. |
| `ANTHROPIC_API_KEY` | backend | `sk-...` | Read by the Anthropic SDK; shared across AI features. |
| `CADENCE_CATEGORIZE_ENABLED` | backend | `false` | Whole worker stack is off unless `true` (needs key + Redis). |
| `CADENCE_CATEGORIZE_MODEL` | backend | `claude-haiku-4-5` | Cheap batch model. |
| `CADENCE_CATEGORIZE_DAILY_TOKEN_CAP` | backend | `0` | Per-org/day; `0` = unlimited. |
| `CADENCE_CATEGORIZE_POLL_MS` | backend | `2000` | Claim poll interval. |
| `CADENCE_CATEGORIZE_BATCH` | backend | `20` | Keep ≤ datasource pool size (20). |
| `CADENCE_CATEGORIZE_MAX_ATTEMPTS` | backend | `5` | Then mark job `failed`. |
| `CADENCE_CATEGORIZE_MAX_OUTPUT_TOKENS` | backend | `256` | |
| `CADENCE_CATEGORIZE_CACHE_TTL_DAYS` | backend | `30` | Pattern-cache TTL. |

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
