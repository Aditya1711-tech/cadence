# P3-C backend — as-built + verification

Implements the safety design in `P3-C.1-safe-text-to-sql.md`. Package
`com.cadence.insights.nlquery` (under `/backend/insights/nlquery/`).

## As-built map

| File | Role | Layer |
|---|---|---|
| `SqlAllowlist` | privacy-level-aware table/column allowlist (excludes `password_hash`, token hashes, `title`/`url`; `aggregate_only` drops `member_id` + per-member tables) | 5 |
| `SqlValidator` | JSqlParser-based fail-closed gate: denylist pre-filter → parse → single `PlainSelect` (no DML/CTE/UNION/subquery/`SELECT *`/`INTO`) → every table+column on the allowlist | 4·5·6 |
| `NlSqlPlanner` | LLM: schema-metadata → SQL (structured output) + caption from the capped result. Owns its own Anthropic client (no bean collision with P2-F) | — |
| `NlQueryExecutor` | runs the validated SQL on a **private** `cadence_readonly` Hikari datasource: read-only txn, `SET LOCAL statement_timeout`, `set_config('app.current_org',…)`, `SELECT * FROM (…) LIMIT max+1` | 1·2·3 |
| `NlQueryService` | admin gate → privacy lookup → allowlist → generate → validate → execute → caption | orchestration |
| `NlQueryController` | `POST /api/v1/query/nl` (gated) | — |
| `NlQueryConfig` / `NlQueryProperties` | `@ConditionalOnProperty(cadence.nlquery.enabled)`; **no `DataSource` bean** (would trip `DataSourceAutoConfiguration` and disable the primary) | gating |

## Key implementation decisions

- **JSqlParser 4.9** (post-refactor API: `SelectItem<?>.getExpression()`,
  `AllColumns` as `Expression`, `PlainSelect instanceof Select`). Column check is
  fail-closed: every referenced column name must be on some allowlisted table's
  column set OR a query-defined output alias; sensitive columns appear on no
  allowlist, so they're rejected regardless of alias resolution.
- **v1 grammar = a single flat `PlainSelect`** (joins allowed; no CTE/UNION/
  subquery). A tighter, fully-analyzable surface than the doc's "UNION/CTE
  allowed" sketch — chosen for a smaller, provable attack surface. Expanding it
  later needs proper derived-scope column tracking.
- **Separate readonly datasource owned by the executor**, not a Spring
  `DataSource` bean — keeps primary autoconfig intact while guaranteeing the
  text-to-SQL path never rides the owner/app connection.
- **Disabled by default** → the controller/service beans are absent, so the route
  is simply not mounted on a dev box (no owner-connection fallback ever).

## Verification (run here / deferred)

- ✅ **`SqlValidatorTest`** (38 cases) — the primary security proof: rejects
  writes/DDL/multi-statement/comments/`SELECT *`/subqueries/CTE/system catalogs/
  `password_hash`/`email`/`title`/`url`/token-hash tables/dangerous funcs; accepts
  legit aggregates; proves `aggregate_only` drops `member_id`/`members`. Pure
  logic, runs in `./gradlew build`.
- ✅ **`ReadonlyRoleDefinitionTest`** — asserts `deploy/initdb/01-readonly-role.sql`
  grants SELECT only, never INSERT/UPDATE/DELETE/BYPASSRLS/SUPERUSER/CREATE*.
- ✅ **`./gradlew build` GREEN** with `cadence.nlquery.enabled=false` (default) —
  no nlquery bean instantiates; the dev box (no API key / no readonly role) is
  untouched. `compileIntegrationTestJava` GREEN.
- ⛔ **`NlQueryReadonlyRoleIT`** (authored, `./gradlew integrationTest`, Docker) —
  recreates the role from the deploy script and proves: cross-org SELECT returns
  0 of the other org's rows (RLS); INSERT/UPDATE as `cadence_readonly` denied;
  row cap truncates. **Deferred — needs Docker; runs at deploy on a fresh volume.**
- ⛔ **Live LLM round-trip** — needs `ANTHROPIC_API_KEY` + the readonly datasource;
  exercised at deploy with `CADENCE_NLQUERY_ENABLED=true`.

**Handoff:** *P3-C live verification needs the `cadence_readonly` role, which
materializes on a fresh DB volume at deploy.* (Coordination block.)
