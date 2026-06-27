# P2-D.1 — GitHub integration: App vs OAuth vs PAT

Exploration deliverable for stream **P2-D** (GitHub integration). Decides how a
5–50-dev org connects its GitHub activity to Cadence, and freezes the
backend-side shape (webhook surface, installation→org mapping, github→member
mapping) before any code is written.

Grounding (frozen truth):
- Event Contract — `00-SYSTEM-KNOWLEDGE.md` §5 (`source:"github"`,
  `meta.commit_sha`, `meta.repo`).
- REST conventions — §6 (`/api/v1`, problem+json).
- DB conventions / tenancy — §7 (every org row carries `org_id`; RLS).
- Privacy — §8 (GitHub hard toggle `commit_messages_only` default vs `full_diff`
  opt-in; **never read code by default**). Toggle design is P2-D.2.
- Directory ownership — §9 (P2-D owns `/backend/github/` ⇒ package
  `com.cadence.github`; **only P2-A writes Flyway migrations** — schema changes
  are requested via NEEDS lines).
- Phase-2 doc P2-D **best-UX angle:** *an admin installs one GitHub App and the
  whole org's commit activity flows in.*
- Env vars already reserved for this stream: `GITHUB_APP_ID`,
  `GITHUB_APP_PRIVATE_KEY` (PEM, base64), `GITHUB_WEBHOOK_SECRET`,
  `GITHUB_DEFAULT_MODE=commit_messages_only`.

---

## 1. The three options

| Mechanism | How org connects | Identity | Webhooks | Permission granularity | Rate limit | Verdict |
|---|---|---|---|---|---|---|
| **PAT** (personal access token) | every dev mints a token, pastes it into Cadence | the user | per-user polling only (no org webhooks) | coarse (classic) / per-repo (fine) but user-wide | 5k/hr **per user** | ✗ |
| **OAuth App** | each dev clicks "Authorize", grants user-scoped token | the user | no first-class org push webhooks | user-scoped repo access | 5k/hr per user token | ✗ |
| **GitHub App** | **one admin installs the App on the GitHub org once** | the App + per-install token | **native push/PR/installation webhooks** | fine-grained, least-privilege, repo-selectable | 5k–15k/hr **per installation** | ✅ |

### Why PAT loses
Every developer would have to create and rotate a token — the opposite of the
"<30 min onboarding" goal (Phase-2 goal statement). PATs are broad (a classic PAT
grants everything the user can do), expire, and are painful to revoke centrally.
No org-wide push webhook. Disqualified for the activity-ingestion use case.

### Why OAuth App loses
OAuth is the right tool for *"log in with GitHub"* (acting **as a user**), not for
ingesting an org's commit stream. There is no clean org-level push webhook; you
would poll per-user repos with per-user tokens, multiplying rate-limit pressure
and requiring every dev to authorize. We may add OAuth later purely as a login
convenience for the admin UI (P2-E), but it is not the ingestion path.

### Why GitHub App wins (and matches the best-UX angle exactly)
- **One install, whole org.** An org admin installs the Cadence GitHub App on
  their GitHub organisation and selects "all repos" (or a subset). That single
  action wires push/PR webhooks for every selected repo — no per-dev setup.
- **Least privilege.** A GitHub App requests only the scopes it needs. For the
  default `commit_messages_only` mode the App needs **`metadata: read`** plus the
  `push` (and `pull_request`) webhook events — **zero code read access**. This is
  exactly what §8 demands ("never read code by default"). `contents: read` is
  added *only* for the opt-in `full_diff` mode (see P2-D.2).
- **Own identity + high limits.** The App authenticates as itself (App JWT,
  RS256) and mints short-lived **installation access tokens** for API calls; rate
  limits scale per installation, not per user.
- **Central lifecycle.** Install / uninstall / repo-selection changes all arrive
  as `installation` / `installation_repositories` webhooks, so Cadence always
  knows the current connection state.

**DECISION: GitHub App.** PAT and OAuth are rejected for ingestion.

---

## 2. Credentials & config (maps to the reserved env vars)

A GitHub App has three pieces of secret material, already reserved in the phase
doc. They bind to a `cadence.github.*` `@ConfigurationProperties` group
(`GithubProperties`), following the `JwtProperties` pattern in `security/`:

```yaml
cadence:
  github:
    app-id:        ${GITHUB_APP_ID:}
    private-key:   ${GITHUB_APP_PRIVATE_KEY:}      # PEM, base64-encoded
    webhook-secret: ${GITHUB_WEBHOOK_SECRET:}
    default-mode:  ${GITHUB_DEFAULT_MODE:commit_messages_only}
```

- **`webhook-secret`** — the only credential needed for the **default path**. The
  receiver verifies the `X-Hub-Signature-256` HMAC over the raw body with it.
- **`app-id` + `private-key`** — needed **only** to mint installation tokens for
  the `full_diff` enrichment API calls (P2-D.2). The default mode reads nothing
  from the API, so these can be empty in a commit-messages-only deployment.

No new build dependency is required for the default path: HMAC-SHA256 uses JDK
`javax.crypto.Mac` (the existing `auth/Tokens` helper already uses
`MessageDigest`/`HexFormat`). RS256 App-JWT signing for `full_diff` is available
via the already-present `jjwt` (0.12.6) once an RSA `PrivateKey` is loaded from
the PEM — still no new dependency.

---

## 3. The connection lifecycle (how install → events flows)

```
1. Admin (in Cadence admin UI, P2-E) clicks "Connect GitHub".
   → Cadence sends them to the App's install URL with state = <cadence org_id>
     (signed/opaque) so we can bind the resulting installation to the right org.

2. Admin installs the App on their GitHub org, picks repos.
   → GitHub POSTs an `installation` (action=created) webhook carrying
     installation.id, the GitHub account/org login, and the selected repos.
   → Cadence stores  installation_id ─→ org_id  (+ default mode).  [needs table]

3. A dev pushes commits / opens a PR on a selected repo.
   → GitHub POSTs `push` / `pull_request` webhooks (HMAC-signed).
   → Cadence verifies the signature, resolves installation_id → org_id, maps the
     commit author's github login → a Cadence member, and writes Event-Contract
     events (source:"github") under that org's RLS context.

4. Admin watches commit activity appear in the org dashboard (P2-E.5).
```

The `state`-carries-org_id linking in step 1 is a **P2-E (admin UI)** concern;
P2-D provides the backend endpoint that records the mapping and the webhook
receiver. For backend-only testing we can seed the mapping directly.

---

## 4. Installation → org mapping — the one schema gap (NEEDS P2-A)

`members.github_login` (already in `V1__init.sql`, "pre-provisioned for P2-D.4")
covers **github user → member**. But there is **no place to store
`installation_id → org_id`**, and we need it: a webhook arrives keyed only by
GitHub's `installation.id`, and we must resolve which Cadence org it belongs to
(and that org's privacy mode) before we can insert events under the right
`org_id`.

Per §9, **only P2-A writes migrations.** So P2-D files a NEEDS line requesting a
`V2` migration for a `github_installations` table. Proposed shape (org-scoped,
RLS like every other org table):

```sql
CREATE TABLE github_installations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          uuid NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    installation_id bigint NOT NULL UNIQUE,        -- GitHub's installation id
    account_login   text,                          -- the GitHub org/account name
    mode            text NOT NULL DEFAULT 'commit_messages_only'
                         CHECK (mode IN ('commit_messages_only','full_diff')),
    suspended_at    timestamptz,                   -- App suspended/uninstalled
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_github_inst_org ON github_installations(org_id);
-- RLS: enable + org_isolation policy on org_id (same DO-block pattern as V1).
```

Also a small, optional ergonomics request (nice-to-have, not blocking):
`CREATE UNIQUE INDEX uq_members_org_github ON members(org_id, github_login)
 WHERE github_login IS NOT NULL;` so one github login maps to at most one member
per org.

**Cross-org lookup note (defense-in-depth).** Resolving `installation_id → org_id`
happens *before* we know the org, so it cannot run under an org RLS context —
exactly like the public auth flows (`AuthService` resolves invite/login tokens
across orgs without binding). Today that works because the app connects as the
DB owner (RLS not FORCEd); the documented hardening (P2-A.8 follow-up: a
narrowly-scoped privileged datasource for cross-org doors) applies equally to the
webhook resolver. P2-D records this rather than inventing its own DB role.

Until the migration lands, P2-D codes against a small `GithubInstallationRepository`
abstraction so the webhook→event mapping, signature verification, and member
mapping are all implemented and unit-tested; the live end-to-end insert is gated
on the `V2` table (tracked as a NEEDS line + a blocked sub-task if needed).

---

## 5. Event mapping (what a webhook becomes) — detail in P2-D.3

GitHub webhooks are **point-in-time** signals, not time spans. They map to the
Event Contract as zero-duration events (`ts_end = ts_start`, `duration_ms = 0`),
so they never distort the time-by-category rollups (which sum `duration_ms`); the
admin "commit activity" panel counts them by `source='github'`.

| Webhook | Event(s) | source | ts_start | title (default mode) | project | category | meta |
|---|---|---|---|---|---|---|---|
| `push` | **one event per commit** | `github` | commit timestamp | commit message subject | repo name | `null`† | `commit_sha`, `repo`, `branch` |
| `pull_request` (opened/closed/...) | one event | `github` | PR action timestamp | PR title | repo name | `code_review` | `repo`, `pr_number`, `action` |

† **Category for commits:** left `null` but **not** enqueued for LLM
categorization. The ingest auto-enqueue path is for editor/browser events whose
category is genuinely unknown; a git commit is a deterministic git signal and
sending it to the worker would waste LLM budget (and the worker's prompt expects
app/title patterns, not commits). P2-D writes its own insert path and simply does
not enqueue. (Commits contribute 0 ms, so a null category does not affect time
rollups regardless.) PR review activity is deterministically `code_review`.

`member_id`/`org_id` are **not** taken from any client body (there is none —
GitHub is the caller): `org_id` from the installation mapping, `member_id` from
`members.github_login` lookup (P2-D.4). Idempotency reuses the existing
`(event_id, ts_start)` key — P2-D derives a **stable, deterministic `event_id`**
(uuid-v5 over `repo + commit_sha`, or `repo + pr_number + action`) so GitHub's
at-least-once webhook redelivery dedupes via `ON CONFLICT DO NOTHING`.

---

## 6. What this stream will build (backend, package `com.cadence.github`)

- `GithubProperties` (`@ConfigurationProperties("cadence.github")`).
- `GithubController` — `POST /api/v1/github/webhook` (registered `permitAll` in
  `SecurityConfig`; authenticated instead by HMAC signature). Optional
  `POST /api/v1/github/installations` (authenticated, admin) to let P2-E link an
  installation to the org — or this is folded into the `installation` webhook +
  install `state`.
- `GithubSignatureVerifier` — constant-time HMAC-SHA256 check of
  `X-Hub-Signature-256` over the raw request body.
- `GithubWebhookService` (`@Transactional`) — parse `push`/`pull_request`,
  resolve org + member, `tenancy.bind(orgId, memberId, role)`, insert events with
  the same `INSERT ... ON CONFLICT (event_id, ts_start) DO NOTHING` shape ingest
  uses. Honors the per-org `mode` (P2-D.2/.5).
- `GithubInstallationRepository`, `MemberLookup` (github_login → member).
- (P2-D.5 `full_diff` only) `GithubApiClient` — mint installation token (App JWT
  RS256 via jjwt) and fetch numeric diff **stats** (additions/deletions/files),
  never the patch/code.

---

## 7. Decisions (this exploration) — pending user confirmation

1. **GitHub App**, not OAuth/PAT (matches best-UX angle; least privilege).
2. **One new table `github_installations`** for `installation_id → org_id` + mode
   ⇒ **NEEDS line to P2-A** for a `V2` migration (P2-D does not write migrations).
3. **GitHub events are zero-duration** point-in-time signals; **not** sent to the
   LLM categorizer; commits `category=null`, PR activity `category=code_review`.
4. **Deterministic `event_id`** (uuid-v5 of repo+sha / repo+pr+action) for
   idempotent webhook redelivery via the existing `(event_id, ts_start)` key.
5. **Default mode needs only `metadata:read` + webhook secret** — no GitHub API
   calls, no App private key. `contents:read` + App-JWT come in *only* for the
   opt-in `full_diff` stats enrichment (P2-D.2).
