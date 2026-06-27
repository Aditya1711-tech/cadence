# Phase 3 — AI Intelligence + Revenue

**Goal:** the insights are the reason teams stay, and the first paid invoice goes
out. Weekly narrative digest, pattern engine, natural-language query, billing,
and the agentic token-budget monitor.

**Wave structure:**
```
Wave 0 (spine, 1 session):  P3-A   insights foundation (pre-aggregation + digest pipeline + insights schema)
Wave 1 (parallel, 4 sessions):
  P3-B pattern engine | P3-C NL query | P3-D billing | P3-E budget alerts
```
P3-A is the spine because it defines how aggregated facts are shaped and stored
for the LLM to narrate; P3-B/C/E build on that aggregation layer, P3-D is
independent and can start anytime.

**Exit criteria (the milestone you defined: paying customers using it daily):**
- Weekly digest delivered to every active member; shareable.
- NL query answers real questions over the team's data in seconds.
- Stripe billing live: org can subscribe, seats metered, invoices issued.
- Budget monitor fires accurate token-spend alerts to Slack.
- 5 companies paying; weekly-active; churn < 20%/month.

---

## Stream P3-A — Insights foundation  (SPINE)

**Owns:** `/backend/insights/` (shared aggregation + digest), `insights` tables
**Check command:** `cd backend && ./gradlew build`
**Depends on:** Phase 2 complete (real data in the warehouse).

### Requirements exploration
- `P3-A.1` Define the **aggregated fact shape** the LLM narrates from — never
  feed raw events to the model. e.g. `{ peak_block, deep_work_h, meeting_h,
  token_cost_usd, commits, fragmentation_index, deltas_vs_4wk_avg }`. **Best-UX
  angle:** the digest must feel personal and accurate; numbers come from SQL, the
  model only writes prose.
- `P3-A.2` Decide delivery (in-app + email), cadence (weekly), and the
  "shareable card" format (the viral hook).

### Design / contract-in-code
- `P3-A.3` Migration for `insights` + `digests` tables and a pre-aggregation
  query layer (built on Phase 2 continuous aggregates). **Tick `P3-A.CONTRACT`
  when the aggregated-fact shape is merged.**
- `P3-A.4` `GET /api/v1/insights/weekly` returning the structured facts + the
  generated narrative.

### Implementation
- `P3-A.5` Digest job (Spring Batch, scheduled weekly) → compute facts →
  Anthropic call to narrate → store + email.
- `P3-A.6` Prompt engineering: facts-in → grounded plain-English story-out, with
  3 spotted insights (peak hours, token efficiency, meeting load).
- `P3-A.7` Shareable card render (server-side) for the "wrapped" moment.

### Variables to set
```
ANTHROPIC_API_KEY=                  # shared with P2-F
CADENCE_DIGEST_MODEL=claude-sonnet-4-6
CADENCE_DIGEST_CRON=0 0 23 * * SUN  # Sunday 11pm
EMAIL_FROM=insights@<yourdomain>
EMAIL_PROVIDER_API_KEY=             # e.g. SES creds or other
```

---

## Stream P3-B — Pattern engine

**Owns:** `/backend/insights/pattern/`
**Depends on:** `P3-A.CONTRACT` (aggregation layer).

### Requirements exploration
- `P3-B.1` Which patterns are genuinely useful with 2–4 weeks of data: peak
  productivity windows, flow-state predictors, meeting→output correlation,
  context-switch cost. **Best-UX angle:** surface 1–3 high-confidence findings,
  not a wall of stats.

### Implementation
- `P3-B.2` Time-series rollups + simple models (no heavy ML infra) over the
  continuous aggregates.
- `P3-B.3` Expose findings to the digest (P3-A) and admin UI (P2-E surface).
- `P3-B.4` Confidence thresholds so low-data members don't get noisy claims.

### Variables to set
```
CADENCE_PATTERN_MIN_DAYS=14         # min history before showing patterns
```

---

## Stream P3-C — Natural-language query

**Owns:** `/backend/insights/nlquery/` + `/web/insights/` (query UI)
**Depends on:** `P3-A.CONTRACT`, Phase 2 schema.

### Requirements exploration
- `P3-C.1` Safe text-to-SQL over TimescaleDB: a constrained schema view, allowed
  tables/columns, hard `org_id` scoping, read-only role, result-row caps.
  **Best-UX angle:** "how much did we code vs meet last sprint?" answered in ~1s
  with a chart; never lets a user read another org's data.

### Implementation
- `P3-C.2` Schema-aware prompt → SQL, executed via a read-only, org-scoped DB
  role. Reject anything not matching an allowlist.
- `P3-C.3` `POST /api/v1/query/nl` returns structured result + a short caption.
- `P3-C.4` Query UI with example prompts and result charts.

### Variables to set
```
CADENCE_NLQUERY_MODEL=claude-sonnet-4-6
CADENCE_NLQUERY_DB_ROLE=cadence_readonly   # role with SELECT-only, RLS enforced
CADENCE_NLQUERY_MAX_ROWS=5000
```

---

## Stream P3-D — Billing (Stripe)

**Owns:** `/backend/billing/`
**Depends on:** Phase 2 org model. Independent of P3-A — can start anytime.

### Requirements exploration
- `P3-D.1` Map the pricing model to Stripe: Team $12/seat (5 min), Growth
  $22/seat (10 min), annual default, 14-day no-card trial, token overage. **Best-
  UX angle:** org-level billing (admin pays for team), clean seat changes.

### Implementation
- `P3-D.2` Stripe products/prices; checkout + customer portal.
- `P3-D.3` `POST /api/v1/billing/webhook`: handle subscription lifecycle, seat
  count sync, trial end, payment failure → plan state on the org.
- `P3-D.4` Feature gating by plan (free/team/growth) across API + UI.
- `P3-D.5` Token-overage metering (counts above included allotment).

### Variables to set
```
STRIPE_SECRET_KEY=
STRIPE_WEBHOOK_SECRET=
STRIPE_PRICE_TEAM_ANNUAL=
STRIPE_PRICE_GROWTH_ANNUAL=
CADENCE_TOKEN_INCLUDED_PER_SEAT=500000
CADENCE_TOKEN_OVERAGE_PER_1K_USD=0.002
```

---

## Stream P3-E — Agentic token-budget monitor

**Owns:** `/backend/insights/budget/`
**Depends on:** `P3-A.CONTRACT`, P2-C token data.

### Requirements exploration
- `P3-E.1` Anomaly definition: rolling-average burn vs spike per member/org/day;
  thresholds; quiet hours; dedupe so it doesn't spam. **Best-UX angle:** alerts
  are rare, specific, and actionable ("Thursday session = 4.6× daily avg").

### Implementation
- `P3-E.2` Scheduled agent loop: read latest token rollups → compare to rolling
  average → on anomaly, generate a short natural-language alert (Haiku).
- `P3-E.3` Slack delivery (incoming webhook) + email fallback.
- `P3-E.4` Per-org config: thresholds, channels, mute windows.

### Variables to set
```
CADENCE_BUDGET_MODEL=claude-haiku-4-5
CADENCE_BUDGET_CHECK_CRON=0 0 * * * *   # hourly
SLACK_WEBHOOK_URL=                       # per-org, stored in DB; this is the dev default
```

---

## Phase 3 coordination notes
- The LLM only ever sees **pre-aggregated facts** (P3-A) or **schema metadata**
  (P3-C) — never raw event rows, never prompt/response content from token events.
- P3-A owns the insights migrations; others file NEEDS lines.
- Reuse the single `ANTHROPIC_API_KEY`; keep per-feature model choices in their
  own env vars so cost is tunable per feature.
