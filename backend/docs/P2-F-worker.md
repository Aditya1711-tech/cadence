# P2-F — Categorisation worker (as built)

**Package:** `com.cadence.worker` (single Spring Boot jar, §4/§9).
**Check:** `cd backend && ./gradlew build` (GREEN — 33 unit tests).
**Design:** [[exploration/P2-F.1-escalation-rules]], [[exploration/P2-F.2-prompt-design]].

## What it does

A background worker that fills in `events.category` for events the rule
classifier couldn't confidently classify, using the Anthropic API (Haiku).

Per-poll (`@Scheduled`, fixed delay): claim a batch of `categorize` jobs
(`FOR UPDATE SKIP LOCKED`) → fan out on a virtual-thread-per-task pool. Per job
(`JobProcessor`):

1. Load the event by `(event_id, ts_start)` under the job's org (RLS bound).
2. **Re-check**: only `null`/`other` escalate; a specific category → done, no LLM.
3. **Pattern cache** (`source|app|norm-title|url-host`, Redis): hit → reuse.
4. **Daily token cap** (per org, Redis): exhausted → defer the job (soft-degrade).
5. **LLM** (`AnthropicCategorizer`, structured output → fixed 8-enum) → write back
   `category`, populate cache, mark job `done`.

Best-effort: any error retries with exponential backoff, then `failed` past
`max-attempts`; the event simply stays `other`. The LLM call happens *between*
short DB transactions, so a pooled connection is never held across the network
round-trip.

## Files

| File | Role |
|---|---|
| `Category` | frozen 8-enum (constant names = wire/DB values); `needsLlm()` |
| `CategorizeProperties` | `cadence.categorize.*` binding |
| `EventSignals` / `CategoryPrompt` | LLM input + prompt (system + structured user msg) |
| `Categorizer` / `AnthropicCategorizer` | LLM call (structured output, defensive `other`) |
| `PatternCache` / `RedisPatternCache` | P2-F.4 cache + key normalisation |
| `DailyTokenCap` / `RedisDailyTokenCap` | P2-F.5 per-org/day budget |
| `JobStore` / `CategorizeJobStore` | claim + load + write-back + status (RLS-bound txns) |
| `JobProcessor` | per-job orchestration |
| `CategorizeWorker` | scheduled claim + fan-out |
| `WorkerConfig` | beans; gated on `enabled` + `@EnableScheduling` |

The whole stack is `@ConditionalOnProperty(cadence.categorize.enabled=true)`, so a
dev box without `ANTHROPIC_API_KEY`/Redis boots the rest of the backend untouched
(default `enabled=false`).

## DEPENDENCY — cross-org claim function (NEEDS P2-F → P2-A)

Claiming is **cross-org**, so it cannot run under the RLS-scoped app role with a
single org bound (RLS default-denies when `app.current_org` is unset). The worker
calls a `SECURITY DEFINER` function the spine must add to the migrations (P2-F does
not write migrations). Requested SQL (also reclaims stale `running` locks):

```sql
CREATE OR REPLACE FUNCTION claim_categorize_jobs(p_locked_by text, p_limit int)
RETURNS TABLE (id uuid, org_id uuid, payload jsonb, attempts int)
LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
  UPDATE job_queue j
     SET status = 'running', locked_by = p_locked_by, locked_at = now(),
         attempts = j.attempts + 1
   WHERE j.id IN (
     SELECT q.id FROM job_queue q
      WHERE q.kind = 'categorize'
        AND ( (q.status = 'pending' AND q.run_after <= now())
           OR (q.status = 'running' AND q.locked_at < now() - interval '5 minutes') )
      ORDER BY q.run_after
      FOR UPDATE SKIP LOCKED
      LIMIT p_limit )
  RETURNING j.id, j.org_id, j.payload, j.attempts;
$$;
REVOKE ALL ON FUNCTION claim_categorize_jobs(text,int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_categorize_jobs(text,int) TO cadence_app;
```

Owner = `cadence_owner` (runs Flyway), so the definer bypasses RLS (not FORCEd);
per-job reads/writes afterwards run as `cadence_app` with org bound. Until this
lands, the worker logs a claim warning and idles (non-blocking for build/tests).

A second NEEDS asks ingest to also enqueue when `category == 'other'` (the worker
re-checks regardless, so this is throughput-only). Both in PROGRESS Coordination.

## Running it

1. Apply the claim function (above).
2. `docker-compose` already ships Redis 7 (P2-A.9). Set on the backend service:
   `CADENCE_CATEGORIZE_ENABLED=true`, `ANTHROPIC_API_KEY=...`, and optionally
   `CADENCE_CATEGORIZE_DAILY_TOKEN_CAP`, `CADENCE_CATEGORIZE_MODEL`.
3. Metrics surface under `cadence.categorize.*` (jobs by result, cache hit/miss,
   llm.calls, llm.tokens) via the actuator MeterRegistry.

## Verification status

Unit-tested green (decision logic, cache key, prompt, enum). **Live e2e
(real Postgres job_queue + Redis + Anthropic API) is NOT run here** — this Windows
dev box has no Docker/Redis and no API key (same limit as P2-A.10). HANDOFF: on a
Docker host with `ANTHROPIC_API_KEY`, apply the claim function, enable the worker,
ingest null-category events, and assert `events.category` is filled.
