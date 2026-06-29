# P3-E.1 — Anomaly definition + dedupe design (frozen-on-approval)

**Stream:** P3-E — Agentic token-budget monitor.
**Owns:** `/backend/insights/budget/` (docs here; Java goes in package
`com.cadence.insights.budget`).
**Depends on:** `P3-A.CONTRACT` (the token-cost fact, frozen) + P2-C token data.
**Grounding:** `00-SYSTEM-KNOWLEDGE.md` §5 (Event Contract), §6/§7 (REST/DB,
the `events_daily_tokens` CAGG), §8 (privacy); `PHASE-3` P3-E; P3-A.1 fact shape.

> **Best-UX bar (from the phase doc):** alerts are **rare, specific, and
> actionable** — e.g. *"Thursday's session burned $6.10 — 4.6× this member's
> usual day."* Never a wall of stats, never a 3 a.m. spam loop.

---

## 0. What the warehouse already gives us — the one fact we read

We build entirely on the **frozen token fact**, the `events_daily_tokens`
continuous aggregate (V1, `source='token'` only):

```
keys:     bucket (1 day), org_id, member_id, model
measures: cost_usd, tokens_in, tokens_out
```

No new collector, **no new CAGG** (a CAGG addition is a coordination event). The
budget monitor is a pure *reader* of this aggregate plus a tiny bit of new
config/ledger state it owns. Token events carry **no prompt/response content**
(§8) — only counts/cost — so there is nothing sensitive to leak into an alert.

**Daily burn** for a subject on day *D*:
`burn(subject, D) = Σ cost_usd over all (model) rows of events_daily_tokens
where bucket = D`, summed across that subject's members (org) or for the one
member (member grain).

> **Materialization lag (known, accepted):** the CAGG refresh policy has
> `end_offset => INTERVAL '1 hour'`, so *today's* bucket lags reality by up to
> ~1 h. For an **hourly** budget check that is fine — a real spike is still
> caught within the hour. We deliberately do **not** read raw `events` for a
> "live" figure (keeps us a pure fact-reader and avoids a second code path).

---

## 1. Anomaly definition

### 1.1 Subjects (per member AND per org)
Each run evaluates, for the **current UTC day**:
- every **member** with activity in the baseline window (catches one dev's
  runaway agent), and
- the **org** as a whole = Σ members (catches a team-wide spike no single
  member trips).

Both grains can fire independently. Org-grain uses the same math on the summed
series.

### 1.2 Baseline = rolling average over *active* days
For subject *s* on day *D*:
- **Baseline window** = the `baseline_days` (default **14**) completed days
  before *D*.
- Baseline mean **μ** = mean of `burn(s, d)` over the days in that window
  **that had spend > 0** ("active days"). Standard deviation **σ** is kept for
  context/phrasing.

> **Why active-days-only:** dev token spend is bursty and weekend-sparse. If we
> averaged in zero days, μ would collapse and every ordinary Monday would read
> as a "spike". Averaging over active days makes "4.6× a usual day" mean a usual
> *working* day — exactly the UX phrasing.

### 1.3 Minimum history (no noise for new/low-data subjects)
Emit nothing unless the subject has **≥ `min_history_days` (default 7)** active
days in the window. Mirrors P3-B's confidence floor — a 2-day-old member never
gets a "spike" alert.

### 1.4 Spike condition (must satisfy BOTH)
Let `today = burn(s, D)` and `ratio = today / μ`. Fire when:

1. `ratio ≥ spike_multiplier` (default **3.0×**), **and**
2. `today ≥ min_absolute_usd` (default **$10.00** — see provisional note).

> **Why the absolute floor:** without it, `$0.03` vs a `$0.005` baseline is
> "6×" and we'd spam over rounding noise. The floor guarantees an alert always
> corresponds to real money. (σ-based z-score was considered; multiplier + floor
> is simpler, matches the human phrasing, and is what we ship. σ rides along only
> to colour the prose.)
>
> **PROVISIONAL default (user decision 2026-06-29):** this team runs AI coding
> tools heavily, so a *normal* active day already clears $1 — a $1 floor would
> false-fire constantly. The floor's job is to ensure an alert only fires on a
> **genuinely unusual** day, so the starting default is **$10.00** (≈ a normal
> heavy active-dev day). It is a **config value** (`CADENCE_BUDGET_MIN_ABSOLUTE_USD`
> + per-org `budget_alert_config.min_absolute_usd`), never hardcoded, and is
> expected to be **retuned at/after deploy** once we have ≥ 2 weeks of live
> per-developer daily spend to calibrate against. The 3× ratio and `[3,5,10]`
> tiers stay as-is. See the Coordination note in `PROGRESS.md`.

### 1.5 Severity buckets (drives escalation + dedupe, §2)
`tiers = [3, 5, 10]` → `severity = highest tier ≤ ratio` (so 3.2× → tier 3,
8× → tier 5, 12× → tier 10). Tiers are config-overridable.

---

## 2. Dedupe — "never spams" (the core safety property)

Three independent guards, strongest first:

### 2.1 Per-(subject, day, severity) ledger — the hard idempotency key
A new owned table `budget_alerts` records every alert actually delivered:

```
UNIQUE (org_id, subject_type, subject_id, day, severity)
```

Before delivering, we attempt `INSERT … ON CONFLICT DO NOTHING`. **Deliver only
if a row was inserted** (`rowcount == 1`); otherwise this (subject, day, tier)
was already alerted — skip. This is the same idempotent pattern as event ingest
(§6), is atomic, and is correct across restarts **and** multiple backend
instances (no double-send).

**Escalation, not repetition:** because the key includes `severity`, an ongoing
spike that *worsens* (3× → later 8× same day) sends **one** new alert when it
crosses into tier 5 — but the hourly checks in between, still at ~3×, send
nothing. Max alerts per subject per day = number of tiers (3).

### 2.2 Quiet hours (defer, don't drop)
Per-org `quiet_hours_start`/`quiet_hours_end` (+ org `timezone`). If "now" is
inside quiet hours we **detect but do not deliver and do not write the ledger
row** — so the next non-quiet hourly check re-evaluates and, if the spike
persists, delivers then. Net effect = "hold until morning" with **zero extra
queue/state**. (A 2 a.m. spike that's gone by 8 a.m. correctly never pages
anyone.)

### 2.3 Mute window (explicit operator override)
Per-org `mute_until timestamptz`. While `now < mute_until`, the subject's org is
skipped entirely (detection included). For "we're running a big migration this
week, don't alert." Blanket, deliberate, time-boxed.

---

## 3. Per-org config (P3-E.4) — DB is source of truth, env is the dev default

A new owned table `budget_alert_config` (one row per org), all columns
defaulted so an org with **no row** still works off env/global defaults:

| column | meaning | default |
|---|---|---|
| `org_id` (PK) | tenant | — |
| `enabled` | master switch for this org | `true` |
| `spike_multiplier` | §1.4(1) | `3.0` |
| `min_absolute_usd` | §1.4(2) — provisional, retune post-deploy | `10.00` |
| `baseline_days` | §1.2 | `14` |
| `min_history_days` | §1.3 | `7` |
| `tiers` | §1.5 (int[]) | `{3,5,10}` |
| `channel` | `email` \| `slack` (resolved, §4) | `email` |
| `slack_webhook_url` | per-org Slack incoming webhook | `null` |
| `alert_email` | override recipient | `null` → org owners/admins |
| `quiet_hours_start`/`_end` | local hour 0–23, null = off | `null` |
| `timezone` | IANA tz for quiet hours | `UTC` |
| `mute_until` | §2.3 | `null` |

**Migration ownership:** migrations are **spine-only** (§7, §9). P3-E does **not**
write `backend/migrations/`. We file a **NEEDS P3-E → P3-A** line with the exact
DDL (below) and, until it lands, the code **degrades gracefully**: a missing
table is caught, logged once, and the run is skipped — identical to how the P2-F
worker tolerates its not-yet-installed claim function. The backend boots and
builds green without the migration.

Requested DDL (verbatim for the spine):

```sql
-- Vn: P3-E budget-alert config + dedupe ledger (requested via NEEDS P3-E->P3-A)
CREATE TABLE budget_alert_config (
    org_id            uuid PRIMARY KEY REFERENCES orgs(id) ON DELETE CASCADE,
    enabled           boolean     NOT NULL DEFAULT true,
    spike_multiplier  numeric     NOT NULL DEFAULT 3.0,
    min_absolute_usd  numeric     NOT NULL DEFAULT 1.00,
    baseline_days     int         NOT NULL DEFAULT 14,
    min_history_days  int         NOT NULL DEFAULT 7,
    tiers             int[]       NOT NULL DEFAULT '{3,5,10}',
    channel           text        NOT NULL DEFAULT 'email'
                                  CHECK (channel IN ('email','slack')),
    slack_webhook_url text,
    alert_email       text,
    quiet_hours_start int,
    quiet_hours_end   int,
    timezone          text        NOT NULL DEFAULT 'UTC',
    mute_until        timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE budget_alerts (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       uuid        NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    subject_type text        NOT NULL CHECK (subject_type IN ('member','org')),
    subject_id   uuid,                         -- null for org grain
    day          date        NOT NULL,
    severity     int         NOT NULL,         -- tier crossed (3/5/10/…)
    ratio        numeric     NOT NULL,
    today_usd    numeric     NOT NULL,
    baseline_usd numeric     NOT NULL,
    channel      text        NOT NULL,         -- channel actually used
    delivered    boolean     NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now()
);
-- one alert per (subject, day, severity) — the hard dedupe key (§2.1)
CREATE UNIQUE INDEX uq_budget_alerts_dedupe
    ON budget_alerts (org_id, subject_type, COALESCE(subject_id,'00000000-0000-0000-0000-000000000000'::uuid), day, severity);
-- RLS org_isolation (keyed on app.current_org), same as every org-scoped table.
ALTER TABLE budget_alert_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget_alerts        ENABLE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON budget_alert_config USING (org_id = current_setting('app.current_org', true)::uuid);
CREATE POLICY org_isolation ON budget_alerts        USING (org_id = current_setting('app.current_org', true)::uuid);
```

---

## 4. Delivery (P3-E.3) — email default, Slack on config presence

Both channels are built **now**; Slack is gated **purely on config presence** so
it flips on later with zero code change:

```
resolve_channel(org):
    url = config.slack_webhook_url ?? env SLACK_WEBHOOK_URL (local-test default)
    if config.channel == 'slack' AND url present  -> Slack(url)
    else                                           -> Email   (DEFAULT)
```

- **Email (default):** reuse `com.cadence.mail.Mailer` (SMTP when `SMTP_HOST`
  set, else `LogMailer` console fallback — exactly like P3-A/P2-A). Recipients =
  `config.alert_email` or the org's owner/admin members.
- **Slack:** POST `{"text": …}` to the org's incoming webhook via JDK 21
  `java.net.http.HttpClient` (**no new dependency**). Per the kickoff, the env
  `SLACK_WEBHOOK_URL` is **only a local-test default**; real per-org webhooks
  live in `budget_alert_config.slack_webhook_url`.
- A delivery failure on the chosen channel **falls back to email** and is logged;
  it never throws out of the loop.

---

## 5. The agent loop (P3-E.2) — schedule + LLM

- `@Scheduled(cron = ${cadence.budget.check-cron})` (default hourly,
  `0 0 * * * *`), gated `@ConditionalOnProperty cadence.budget.enabled=true`
  (so a dev box with no key/DB boots untouched — mirrors `WorkerConfig`).
- Per run: iterate orgs → load config → if muted/disabled skip → compute μ and
  today's burn per member + org → apply §1.4 → for each fired (subject, tier):
  quiet-hours check (§2.2) → ledger insert (§2.1) → on insert, **narrate** →
  dispatch (§4).
- **Narration (Haiku, `CADENCE_BUDGET_MODEL=claude-haiku-4-5`):** the model sees
  **only aggregated facts** (subject display name, `today_usd`, `μ`, `ratio`,
  top model by spend, day-of-week) — never raw events (§8). Prompt asks for a
  **1–3 sentence**, specific, actionable alert. Reuses the official Anthropic
  Java SDK exactly as `AnthropicCategorizer` does, and **never throws**: on any
  API/parse error it falls back to a deterministic templated string
  (`"<name> burned $X today — N× the usual $Y. Top model: <model>."`) so an
  alert still fires. LLM is optional polish, not a hard dependency.

---

## 6. Config / env (`cadence.budget.*`)

```
CADENCE_BUDGET_ENABLED=false                 # master gate (like categorize)
CADENCE_BUDGET_MODEL=claude-haiku-4-5
CADENCE_BUDGET_CHECK_CRON=0 0 * * * *        # hourly
CADENCE_BUDGET_SPIKE_MULTIPLIER=3.0          # global default; per-org overrides in DB
CADENCE_BUDGET_MIN_ABSOLUTE_USD=10.00        # PROVISIONAL — retune post-deploy (≥2wk data)
CADENCE_BUDGET_BASELINE_DAYS=14
CADENCE_BUDGET_MIN_HISTORY_DAYS=7
CADENCE_BUDGET_MAX_OUTPUT_TOKENS=200
SLACK_WEBHOOK_URL=                           # LOCAL-TEST default only; real = per-org DB
```

---

## 7. What this deliberately is NOT
- Not a new CAGG or collector (reads `events_daily_tokens` only).
- Not a raw-event reader (privacy + simplicity; CADENCE lag accepted).
- Not a budget *cap/enforcement* (that's billing/overage, P3-D) — this only
  **alerts** on anomalous burn.
- Not per-team grain (member + org only, matching the warehouse grain).
- Not a new HTTP dependency (JDK `HttpClient` for Slack; `Mailer` for email).

---

## 8. Verification plan (dev-box reality)
Per the standing dev-box limit (no Docker/Postgres here; DB e2e defers to the
Docker handoff, same as P2-A.10), P3-E ships **pure-logic unit tests** that need
no DB: the detector math (μ over active days, ratio, min-history gate, absolute
floor), severity-bucket selection, quiet-hours window logic, and channel
resolution. `cd backend && ./gradlew build` must be green. Live DB + real
Slack/SMTP wiring is the deploy handoff.
```
```
