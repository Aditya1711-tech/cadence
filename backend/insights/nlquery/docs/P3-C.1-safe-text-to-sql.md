# P3-C.1 — Safe text-to-SQL: the security design (show this before implementing)

**Status:** exploration → the safety contract this stream is built to. Present to
the user for review BEFORE implementing P3-C.2/.3/.4 (kickoff: "Exploration
first — show the safety design before implementing").
**Owns:** `/backend/insights/nlquery/` (→ package `com.cadence.insights.nlquery`)
+ `/web/insights/` (query UI).
**Depends on:** `P3-A.CONTRACT` (frozen, rebased in) + Phase-2 schema (V1/V2/V3).
**Grounding:** `00-SYSTEM-KNOWLEDGE.md` §6 (REST `POST /query/nl`), §7 (DB
conventions, RLS, the `cadence_readonly` role), §8 (privacy levels). Phase doc
`PHASE-3-ai-intelligence.md` P3-C.

---

## 0. The one-paragraph threat model

A user types a natural-language question. An LLM turns it into SQL. **That SQL is
attacker-controlled** — prompt injection, a confused model, or a malicious
question can make the model emit anything. So the LLM's SQL is treated as
hostile input at every layer below. The job of this stream is to make it
**impossible** for that SQL to (a) mutate data, (b) read another org's rows, or
(c) read sensitive within-org columns, and **bounded** so it can never exhaust
the box. The LLM never sees raw event rows — it sees schema *metadata* to write
the SQL, and only the already-capped result set to write a caption.

```
question ──► LLM(schema metadata only) ──► candidate SQL
                                              │  ← treated as hostile
                          ┌───────────────────┴────────────────────┐
                          ▼  app-layer policy (fail-closed)         │
                 1. single read-only SELECT?                        │
                 2. every table/column on the allowlist?            │
                 3. no forbidden tokens / no multi-statement?       │
                          ▼  if rejected → 422, never executed      │
                 execute via cadence_readonly datasource            │
                          ▼  DB-enforced hard guarantees            │
                 4. SELECT-only role  (no write is grantable)       │
                 5. non-owner + RLS   (only app.current_org's rows) │
                 6. statement_timeout + row cap (LIMIT max+1)       │
                          ▼                                         │
                 capped rows ──► LLM writes a short caption ────────┘
                          ▼
                 { question, sql, columns, rows, row_count, truncated, caption }
```

## 1. Six layers of defense (independent; any one failing is backed by another)

| # | Layer | Stops | Enforced by | Trust |
|---|---|---|---|---|
| 1 | **SELECT-only role** `cadence_readonly` | INSERT/UPDATE/DELETE/DDL | Postgres grants (no write priv exists to use) | **DB-hard** |
| 2 | **Non-owner + RLS** | cross-org reads | `org_isolation` policy keyed on `app.current_org`; role lacks BYPASSRLS | **DB-hard** |
| 3 | **`statement_timeout` + row cap** | runaway / huge result | `SET LOCAL statement_timeout`; outer `LIMIT max+1` | **DB/driver-hard** |
| 4 | **Single read-only statement** | multi-statement, write-CTE, stacked queries | parse + fail-closed (§3) | app policy |
| 5 | **Table/column allowlist** | reading `password_hash`, token hashes, `title`/`url` | parse → walk tables+columns → reject off-list (§4) | app policy |
| 6 | **Forbidden-token pre-filter** | obvious injection / dangerous funcs | denylist scan before parse (§3) | app policy (belt) |

**The hard guarantees are 1–3 (database-enforced).** Layers 4–6 are policy: they
make the *common* case clean, give good error messages, and — crucially — block
reading sensitive columns *within your own org* (which RLS does **not** stop,
because they're your org's rows). Layers 4–6 fail **closed**: anything we cannot
fully parse and prove safe is rejected, never executed.

## 2. The role (defined by P3-A, Docker-deferred)

`deploy/initdb/01-readonly-role.sql` (P3-A, on main) creates `cadence_readonly`:
SELECT-only, non-owner, no BYPASSRLS, no CREATE, no sequence usage; RLS applies
because it is a non-owner. It is the **first** consumer that connects as a
non-owner role, so for the NL-query path RLS is *genuinely enforced* (the rest of
the app still connects as owner, where RLS is a backstop — §7).

> **Docker-deferred.** Init scripts run only on a fresh DB volume, so the role
> **physically materializes on a fresh DB volume at deploy** — it does not exist
> on an already-initialised dev DB. We therefore build and unit-test the query
> path against the role **definition** + a local fixture, and gate the live
> path. **P3-C live verification needs the `cadence_readonly` role, which
> materializes on a fresh DB volume at deploy.** (Handoff line, also in
> Coordination.)
>
> **We never route NL queries through the owner/app connection as a local
> shortcut — not even temporarily.** A separate datasource (layer 1/2) is the
> whole point; without it the security model is a no-op. If `cadence.nlquery`
> isn't configured with the readonly datasource, the endpoint returns 503 — it
> does not fall back to the owner connection.

## 3. Statement gate (layers 4 & 6) — fail closed

Before any execution the candidate SQL must pass, in order:

1. **Pre-filter (denylist, cheap):** reject if it contains `;` followed by more
   non-whitespace (multi-statement), SQL comments (`--`, `/* */`), or any
   forbidden token (case-insensitive, word-boundary): `insert update delete
   drop alter create grant revoke truncate copy call do merge vacuum analyze
   reindex cluster comment security label set reset begin commit rollback
   savepoint listen notify lock pg_sleep pg_read_file pg_ls_dir lo_import
   lo_export dblink nextval setval currval set_config pg_terminate_backend`.
   This is a coarse belt — the parser (below) is the real gate.
2. **Parse (JSqlParser):** parse to one statement. **Reject on any parse error**
   (fail-closed: if we can't understand it, we don't run it).
3. **Shape:** the statement must be a `Select` (a `PlainSelect`, or a
   `SetOperationList` — UNION/INTERSECT/EXCEPT — of plain selects; CTEs allowed
   only if every CTE body is itself a read-only select). Anything else
   (Insert/Update/Delete/Update/Merge/etc.) → reject.

JSqlParser is added as the parser (pure-Java, no service). It is the right tool
for "reject anything not matching the allowlist": a regex allowlist is not
trustworthy for this. The denylist pre-filter stays as defense-in-depth so a
parser-bypass still trips the coarse net.

## 4. The allowlist (layer 5) — privacy-level-aware

Only these tables/columns are exposed to the LLM **and** accepted by the
validator. Everything else (and every other table) is rejected.

**Always excluded — never queryable:** `members.password_hash`,
`members.email`, all of `refresh_tokens`, `one_time_tokens`, `invites`,
`seats`, `job_queue`, `digests`, plus `events.title` and `events.url` (the
free-text fields §8 redacts; v1 keeps them out of NL query entirely).

> Note: `cadence_readonly` is granted `SELECT ON ALL TABLES`, so the **app-layer
> allowlist is the only thing preventing the NL path from reading
> `password_hash` within the caller's own org** (RLS scopes to org, not to
> column). That makes layer 5 genuinely security-critical, not just UX. A
> belt-and-suspenders follow-up (NEEDS → spine) is to tighten the role's grants
> to only the allowlisted tables; until then the validator carries it.

**Allowlisted surface (v1):**

| Table / view | Columns exposed | Why |
|---|---|---|
| `events` | `member_id*`, `source`, `ts_start`, `ts_end`, `duration_ms`, `app`, `project`, `category`, `is_idle`, `meta` | time-by-category, app/project, idle; `meta` carries token `cost_usd/model/tokens_*` and github `commit_sha/repo` (no free text) |
| `events_daily_by_category` (CAGG) | `bucket`, `member_id*`, `category`, `total_ms`, `event_count` | fast daily time rollups |
| `events_hourly_by_category` (CAGG) | `bucket`, `member_id*`, `category`, `total_ms`, `event_count` | peak-hour questions |
| `events_daily_tokens` (CAGG) | `bucket`, `member_id*`, `model`, `cost_usd`, `tokens_in`, `tokens_out` | AI token spend |
| `members` | `id`, `display_name`, `role`, `status`, `github_login` | name commits/time to a person |
| `teams` | `id`, `name` | team filters |
| `team_members` | `team_id`, `member_id*` | team membership joins |
| `insights` | `member_id*`, `grain`, `iso_week`, scalars, `facts` | P3-A weekly facts (read-only) |

`org_id` is **not** in the column allowlist on purpose: callers do not filter by
org — RLS does that transparently, and exposing `org_id` only invites confusion.
(The validator still permits `org_id` implicitly? No — it is simply never needed;
RLS appends the org predicate.)

**Privacy-level binding (§8), computed per request from `orgs.privacy_level`:**
- `full` / `categories_only` → full allowlist above (admin already sees
  per-member category/token rollups via `/org/summary`).
- `aggregate_only` → columns marked `*` (`member_id`) and the tables
  `members` / `team_members` / per-member `insights` are **dropped from the
  allowlist for that request**, so only org-level aggregates are answerable.
  This makes "no per-person detail under aggregate_only" enforceable on
  free-form SQL — by construction, the column simply isn't selectable.

**Authorization:** `/query/nl` is **admin-only** (`requireAdmin`, same as
`/org/summary`) — it is a team-analytics surface. RLS is org-scoped, not
member-scoped, so a non-admin could otherwise read colleagues' category data;
gating on admin closes that. (A future self-serve member mode would need a
member-scoped predicate, out of v1 scope.)

## 5. Execution (layers 1–3)

A dedicated `nlqueryDataSource` (Hikari) connects as `CADENCE_NLQUERY_DB_ROLE`
(`cadence_readonly`) — **separate from the owner datasource**. Per request, in a
single `read-only` transaction on that datasource:

```sql
SET LOCAL statement_timeout = :timeoutMs;     -- runaway guard
SELECT set_config('app.current_org', :orgId, true);  -- RLS bind (same door as cadence_app)
-- validated SQL, wrapped to enforce the cap regardless of inner LIMIT/ORDER BY:
SELECT * FROM ( <validated sql> ) AS _nlq LIMIT :maxRowsPlusOne;
```

- **Row cap:** fetch `max+1`; if we got `max+1`, drop the extra and set
  `truncated=true`. Driver `setMaxRows` is set as a second guard.
- **Org bind:** `app.current_org` is transaction-local (`set_config(...,true)`),
  so it cannot leak across pooled connections. RLS then restricts every
  allowlisted table to that org.
- **Read-only transaction** + the SELECT-only role mean a write is impossible
  two ways over.

## 6. The LLM boundary (never sees raw rows for SQL generation)

- **SQL generation:** the model is given (a) the question, (b) the
  privacy-bounded schema *metadata* (table/column names + one-line semantics,
  built from §4), (c) hard rules (single SELECT, only these tables/columns,
  always aggregate, no DML). It returns SQL via structured output. It sees **no
  data**.
- **Validation** (§3/§4) runs on the returned SQL — the model is never trusted.
- **Caption:** after execution, the model gets the **column names + the capped
  rows** (≤ `CADENCE_NLQUERY_MAX_ROWS`) and writes one or two plain sentences.
  It never sees more than the capped result, and never raw `title`/`url`
  (excluded from the allowlist).
- Model = `CADENCE_NLQUERY_MODEL` (default `claude-sonnet-4-6`). Token events'
  prompt/response content is never in scope (only counts/cost/model exist in the
  warehouse — §8).

## 7. Endpoint contract — `POST /api/v1/query/nl`

Request `{ "question": "how much did we code vs meet last week?" }`

Response
```jsonc
{
  "question": "...",
  "sql":      "SELECT category, sum(duration_ms)/3600000.0 AS hours FROM events ...",
  "columns":  ["category", "hours"],
  "rows":     [["deep_work", 41.2], ["meetings", 12.0]],
  "row_count": 2,
  "truncated": false,
  "caption":  "Last week the team spent ~41h in deep work vs ~12h in meetings."
}
```

Errors (RFC 7807 problem+json, §6): `400` blank question · `403` not admin ·
`422 Unprocessable Entity` generated SQL failed the allowlist/validator (detail
names the violation, e.g. "table `refresh_tokens` is not queryable") · `503` NL
query not configured (no API key / no readonly datasource — **no owner-connection
fallback**).

## 8. Build-time gating (dev box stays green)

The whole stack is `@ConditionalOnProperty(cadence.nlquery.enabled=true)`,
default **false** — exactly like P2-F's worker. A dev box with no
`ANTHROPIC_API_KEY` and no readonly datasource boots the rest of the backend
untouched and `./gradlew build` stays green. The **validator/allowlist** logic
(the security-critical core) is plain code with no Spring/DB/LLM dependency, so
it is unit-tested unconditionally.

## 9. Verification strategy (role is Docker-deferred)

1. **Validator unit tests (primary security proof, run always):** reject DML,
   DDL, multi-statement, comment-smuggling, `password_hash`/`refresh_tokens`/
   `title`/`url` references, non-select shapes, parse failures; accept valid
   aggregate SELECTs; assert the aggregate_only allowlist drops `member_id`.
2. **Role-definition assertion test:** parse `deploy/initdb/01-readonly-role.sql`
   and assert it grants SELECT only (no INSERT/UPDATE/DELETE, no BYPASSRLS/
   CREATE) — pins the role contract this stream relies on.
3. **Integration test (authored, Docker + fresh-volume gated, HANDOFF):** mirror
   `E2EIngestQueryIT` — create the role, connect as `cadence_readonly`, prove
   (a) a cross-org `SELECT` returns zero rows, (b) an `INSERT`/`UPDATE` raises a
   privilege error, (c) the row cap truncates. Runs at deploy on a fresh volume.

**Handoff:** *P3-C live verification needs the `cadence_readonly` role, which
materializes on a fresh DB volume at deploy.*

## 10. Variables (P3-C "Variables to set" + additions for the spine to fold in)

```
CADENCE_NLQUERY_MODEL=claude-sonnet-4-6      # phase doc
CADENCE_NLQUERY_DB_ROLE=cadence_readonly     # phase doc (the readonly role)
CADENCE_NLQUERY_MAX_ROWS=5000                # phase doc (result-row cap)
# added by P3-C (gating + the readonly datasource; note to spine for ENV-VARIABLES.md):
CADENCE_NLQUERY_ENABLED=false                # off unless API key + readonly DS present
CADENCE_NLQUERY_DB_URL=<jdbc url>            # defaults to DATABASE_URL
CADENCE_NLQUERY_DB_PASSWORD=cadence_readonly # readonly role password (deploy/initdb default)
CADENCE_NLQUERY_TIMEOUT_MS=5000              # per-query statement_timeout
CADENCE_NLQUERY_MAX_OUTPUT_TOKENS=1024       # LLM SQL+caption budget
ANTHROPIC_API_KEY=                           # shared (P2-F / P3-A)
```

## 11. Deliberately NOT in v1
- No write path of any kind (read-only by construction).
- No `title`/`url` exposure (free-text sensitive fields kept out entirely).
- No self-serve member-scoped mode (admin-only team analytics for v1).
- No new tables/CAGGs/migrations (reads the existing warehouse + P3-A facts).
- No multi-statement, no stored-proc calls, no `meta` free-text beyond the
  token/commit numeric/id keys that already exist.
