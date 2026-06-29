# P3-A.1 — Aggregated-fact shape (the LLM narrates from THIS, never raw events)

**Status:** exploration → frozen as the P3-A contract (see P3-A.3 / `P3-A.CONTRACT`).
**Owns:** `/backend/insights/` aggregation layer + `insights` table.
**Grounding:** `00-SYSTEM-KNOWLEDGE.md` §5 (Event Contract), §6 (REST), §7 (DB
conventions, CAGGs), §8 (privacy). Phase doc `PHASE-3-ai-intelligence.md` P3-A.

---

## 0. The hard rule that drives this shape

The LLM **only ever sees pre-aggregated facts** — never raw event rows, never any
prompt/response content from token events. **Every number is produced by SQL; the
model only writes prose.** This document defines exactly the structured object the
narrator (P3-A.5/.6), the pattern engine (P3-B) and the shareable card (P3-A.7)
read. Freezing it is the spine deliverable that unblocks P3-B/C/E.

---

## 1. What the warehouse already gives us (no new collectors, no new CAGG)

From the Phase-2 as-built (audited 2026-06-28):

| Fact | Source | Grain |
|---|---|---|
| time-by-category (`deep_work_h`, `meeting_h`, …) | `events_daily_by_category` CAGG / raw `events` | member/org/day |
| token cost + in/out | `events_daily_tokens` CAGG / raw `events` (`source='token'`) | member/org/model/day |
| commits | raw `events` `source='github'` + `meta.commit_sha` (the `/org/summary` commit-facet code path) | member/org/day |
| peak block | `events_hourly_by_category` CAGG | member/org/hour |
| fragmentation | **derived in SQL from raw `events`** (see §3) | member/period |

There is **no per-team grain** in the warehouse; a team digest derives by joining
`team_members` at query time (readiness note, 2026-06-28). **No new continuous
aggregate is introduced** — keeping the shared-contract surface minimal (a CAGG
addition would be a coordination event per the kickoff). `commits` reads
zero/low on the dev box until live GitHub commits arrive at deploy; that is
expected, not a blocker (P2-D-finish closed the code path).

---

## 2. The two frozen fact shapes

One logical store (`insights` table, `grain` column) holds two sibling shapes.
Both are snake_case on the wire (global Jackson strategy) and carry the member's/
org's deltas vs a trailing **4 completed weeks** average.

### 2.1 `MemberWeekFacts` (grain = `member`)

```jsonc
{
  "org_id":       "uuid",
  "member_id":    "uuid",
  "display_name": "Octo Dev",
  "grain":        "member",
  "period": { "from":"2026-06-22T00:00:00Z", "to":"2026-06-29T00:00:00Z",
              "iso_week":"2026-W26" },

  // ── headline scalars (frozen must-haves) ───────────────────────────────
  "deep_work_h":         18.4,     // hours = sum(duration_ms)/3.6e6, 1 dp
  "meeting_h":            6.2,
  "token_cost_usd":       4.73,
  "commits":             27,
  "fragmentation_index": 38,       // 0..100, lower = more focused (see §3)

  // ── supporting breakdowns (grounding for prose + P3-B) ─────────────────
  "by_category_h": { "deep_work":18.4,"meetings":6.2,"comms":3.1,"research":2.0,
                     "code_review":1.5,"ai_assisted":4.0,"idle":0,"other":0.7 },
  "tokens_in": 412000, "tokens_out": 98000,
  "fragmentation": { "switches":71, "switches_per_focus_h":2.7, "mean_session_min":22 },
  "peak_block": { "dow":"Tue", "hour":10, "category":"deep_work", "total_ms":5400000 },

  // ── delta vs THIS member's trailing 4 completed weeks (null if <4wk) ────
  "deltas_vs_4wk_avg": {
    "deep_work_h": 3.1, "meeting_h": -1.4, "token_cost_usd": 0.9,
    "commits": 6, "fragmentation_index": -5
  },
  "history_weeks": 7,
  "low_confidence": false           // true when history_weeks < CADENCE_DIGEST_MIN_DAYS/7
}
```

### 2.2 `OrgWeekFacts` (grain = `org`) — admin digest, privacy-bounded

```jsonc
{
  "org_id":"uuid", "grain":"org",
  "period": { "from":"…","to":"…","iso_week":"2026-W26" },
  "active_members": 9,
  "deep_work_h": 142.0, "meeting_h": 58.1, "token_cost_usd": 41.2, "commits": 213,
  "fragmentation_index": 34,                    // focus-hour-weighted mean (§3.4)
  "by_category_h": { … }, "tokens_in":…, "tokens_out":…,
  "top_contributors": [                         // OMITTED under aggregate_only
    { "member_id":"uuid","display_name":"…","commits":41,"deep_work_h":22.0 }
  ],
  "peak_block": { "dow":"Wed","hour":11,"category":"deep_work","total_ms":… },
  "deltas_vs_4wk_avg": { "deep_work_h":…, "meeting_h":…, "token_cost_usd":…,
                         "commits":…, "fragmentation_index":… }
}
```

**Privacy (§8) is enforced on build**, mirroring `/org/summary`:
- `top_contributors` (per-member detail) is **omitted under `aggregate_only`**;
  org-level scalars/totals are returned at every level.
- Same redaction rule the admin read path already applies — the org digest never
  exposes anything `/org/summary` wouldn't at that privacy level.

---

## 3. `fragmentation_index` — computed in SQL, not stored

Fragmentation is **not** a stored fact. It is derived per member per week from raw
`events` with a window function. (Org-grain is the weighted mean of member values,
§3.4.)

### 3.1 Focus events
A **focus event** = `is_idle = false AND category IN
('deep_work','code_review','ai_assisted','research')` — the cognitively-demanding
categories (user decision 2026-06-28). Meetings/comms/idle/other are excluded.

### 3.2 The SQL

```sql
WITH focus AS (
  SELECT member_id, ts_start, ts_end, duration_ms, project,
         lag(project) OVER w AS prev_project,
         lag(ts_end)  OVER w AS prev_end
  FROM events
  WHERE org_id = ? AND ts_start >= ? AND ts_start < ?
    AND is_idle = false
    AND category IN ('deep_work','code_review','ai_assisted','research')
  WINDOW w AS (PARTITION BY member_id ORDER BY ts_start)
)
SELECT member_id,
       -- context switch: project changed AND still inside one session (no long gap)
       count(*) FILTER (
         WHERE prev_end IS NOT NULL
           AND project IS DISTINCT FROM prev_project
           AND ts_start - prev_end <= interval '30 minutes'
       )                                          AS switches,
       sum(duration_ms)                           AS focus_ms,
       -- session boundary: first focus event, or a >30min gap
       count(*) FILTER (
         WHERE prev_end IS NULL OR ts_start - prev_end > interval '30 minutes'
       )                                          AS sessions
FROM focus GROUP BY member_id;
```

### 3.3 Derivation
- `switches_per_focus_h = switches / (focus_ms / 3.6e6)`
- `mean_session_min     = (focus_ms / 60000) / sessions`
- **`fragmentation_index = round(100 * min(1, switches_per_focus_h / SATURATION))`**

`SATURATION = 4.0` (≥4 project switches per focused hour ⇒ maximally fragmented),
exposed as the tunable `CADENCE_FRAGMENTATION_SATURATION`.

**Why these rules:**
- A **>30-minute gap** between focus events is a *session boundary*, not a context
  switch — lunch/standup must not read as fragmentation. Same-project resume
  after a gap therefore also doesn't count.
- `project IS DISTINCT FROM` treats `NULL` project as its own bucket (so
  null→named is a real switch) and is null-safe.
- Raw `switches_per_focus_h` and `mean_session_min` ride along in
  `facts.fragmentation` so the narrator can ground a phrase
  ("you switched projects ~2.7×/focused-hour") without inventing a number.

### 3.4 Org-grain
`OrgWeekFacts.fragmentation_index` = **focus-hour-weighted mean** of member
indices: `sum(idx_m * focus_h_m) / sum(focus_h_m)`. A member with 20 focus-hours
weighs more than one with 2, so the org number reflects where the time actually
went.

---

## 4. `deltas_vs_4wk_avg`

For each scalar (`deep_work_h`, `meeting_h`, `token_cost_usd`, `commits`,
`fragmentation_index`): average over the **4 completed ISO weeks preceding** the
digest week (28-day window, current week excluded), then `delta = current − avg`.

- Computed from the daily CAGGs / raw events over the prior 28 days (no dependence
  on prior `insights` rows — the fact builder is self-contained and replayable).
- `history_weeks < 4` ⇒ `deltas_vs_4wk_avg = null` and `low_confidence = true`
  (the P3-B confidence hook; `CADENCE_DIGEST_MIN_DAYS` is the floor).

---

## 5. Where it's stored (P3-A.3 freezes this)

`insights` table — one row per `(org_id, member_id, iso_week, grain)`:
- denormalized scalar columns (`deep_work_h, meeting_h, token_cost_usd, commits,
  fragmentation_index`) for cheap indexing / P3-B queries,
- `facts jsonb` holding the full frozen object above,
- `grain text CHECK (grain IN ('member','org'))`, `member_id` **nullable** (null
  for `grain='org'`),
- RLS `org_isolation` (keyed on `app.current_org`), same as every org-scoped table.

P3-B reads `insights` (facts JSONB + scalars). The digest narrator (P3-A.5) reads
the same row, writes prose into `digests`. Neither ever touches raw events for
numbers — they read this contract.

---

## 6. What this deliberately is NOT

- Not a new CAGG (built from existing aggregates + raw events).
- Not per-team grain (derive via `team_members` join when a team digest is asked
  for; out of scope for the v1 member+org digest).
- Not raw-event exposure: the narrator gets this JSON and nothing else.
</content>
</invoke>
