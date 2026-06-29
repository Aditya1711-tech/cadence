# P3-B.1 — Pattern engine exploration (which patterns, what math, how gated)

**Status:** exploration. Show findings BEFORE implementing P3-B.2–.4 (kickoff).
**Owns:** `/backend/insights/pattern/` only.
**Depends on:** `P3-A.CONTRACT` (ticked, commit `f8adcb9`) — `MemberWeekFacts` /
`OrgWeekFacts` shape (`backend/insights/docs/P3-A.1-aggregated-fact-shape.md`),
the V3 `insights`/`digests` tables, and the existing V1 continuous aggregates.
**Grounding:** `00-SYSTEM-KNOWLEDGE.md` §5 (Event Contract), §6 (REST), §7
(DB/CAGGs), §8 (privacy); `PHASE-3-ai-intelligence.md` P3-B.

---

## 0. The hard rule that drives this engine

Same rule as P3-A: **the LLM never sees raw events.** P3-B produces **structured
findings — every number comes from SQL**; the narrator (P3-A) turns findings into
prose and the admin view (P2-E) renders them. A "finding" is a small typed object
(`kind`, `title`, `detail`, `confidence`, supporting numbers), never an event row.

Two more rules the kickoff pins:
- **Build on the frozen facts + existing CAGGs.** Read raw `events` **only** where
  a pattern genuinely needs grain the facts/CAGGs don't provide (exactly one place
  below: per-day context-switch detection — §3.3).
- **Gate by `CADENCE_PATTERN_MIN_DAYS` (default 14).** A member below the floor
  gets **zero** findings — no noisy claims. This box likely has < 14 days of
  history, so findings are empty locally; that is **expected**. The logic is
  proven with a seeded fixture that has enough history (§6).

---

## 1. Which patterns are genuinely useful with 2–4 weeks of data

The phase doc (P3-B.1) lists candidates: peak productivity windows, flow-state
predictors, meeting→output correlation, context-switch cost. With only 2–4 weeks
of data, "high-confidence" rules out anything needing a trained model or a long
baseline. The kickoff freezes the three that are both **useful** and
**defensible from a few weeks of aggregates**:

| # | Finding | One-line value | Grain needed | Source |
|---|---|---|---|---|
| 1 | **Peak productivity window** | "Your most focused work lands Tue ~10:00." | hour × day-of-week | `events_hourly_by_category` CAGG |
| 2 | **Meeting→output correlation** | "Heavy-meeting days cut your deep work ~30%." | per-day | `events_daily_by_category` CAGG |
| 3 | **Context-switch cost** | "High-switching days cost ~2 focused hours." | per-day switches | raw `events` (fragmentation, §3.3) |

**Deliberately deferred — flow-state predictor.** Predicting flow needs a
labelled/longer baseline and a real model; it is *not* high-confidence at 2–4
weeks. Out of scope for v1; revisit when members carry months of history.

Each finding is defined below as: the question it answers, the data source, the
**simple model** (no ML infra — plain SQL rollups + arithmetic in Java), the
**confidence gate**, and **why it can't just read the weekly facts**.

### 1.1 Finding 1 — Peak productivity window
- **Answers:** "You do your most focused work on **Tue around 10:00**."
- **Focus set** (identical to P3-A §3.1, do not reinvent): `is_idle = false AND
  category IN ('deep_work','code_review','ai_assisted','research')`.
- **Source:** `events_hourly_by_category` CAGG. Sum `total_ms` of focus rows into
  a **7×24 grid** keyed `(dow, hour)` over the window (UTC buckets — same
  storage-UTC/UI-localizes rule as everywhere else).
- **Model:** find the peak `(dow, hour)` cell, then widen to the **contiguous
  ±1h window** around it for a human-friendly "morning/afternoon" phrase. Score =
  peak-cell focus share vs the uniform expectation (a flat week ⇒ no peak).
- **Why not `facts.peak_block`:** `MemberWeekFacts.peak_block` is a *single bucket
  for one week*. A stable *window* finding needs the multi-week hour×dow
  distribution — grain the weekly scalar doesn't carry. The hourly CAGG already
  holds it pre-aggregated; **no raw events**.
- **Gate:** emit only if (a) ≥ MIN_DAYS history, (b) the peak cell's share exceeds
  the uniform baseline by a margin (concentration threshold), and (c) a minimum
  total focus volume backs it.

### 1.2 Finding 2 — Meeting→output correlation
- **Answers:** "On heavy-meeting days your deep-work output drops ~**N%**" — or
  nothing, if there's no real effect.
- **Source:** `events_daily_by_category` CAGG → per active day: `meeting_h`
  (category `meetings`) and `deep_work_h` (output proxy). **Output proxy =
  `deep_work_h`** because it's dense and present locally; **commits** (raw
  `source='github'`) ride along as a *secondary* signal once GitHub is live, but
  are sparse/zero on the dev box, so they don't gate the finding (see §8 Q2).
- **Model:** Pearson **r** between daily `meeting_h` and daily `deep_work_h` across
  active days, plus a robust **high-vs-low split**: mean output on above-median
  meeting days vs below-median, reported as a % delta (the human number).
- **Why not facts:** weekly scalars give one `meeting_h`/`deep_work_h` per week — a
  correlation needs the **daily pairs**. That's exactly the daily CAGG; **no raw
  events**.
- **Gate:** ≥ MIN_DAYS paired days, **enough variance in `meeting_h`** (a member
  who never meets has no correlation to claim), and |r| past a threshold at
  sufficient n. Weak/ambiguous ⇒ omit.

### 1.3 Finding 3 — Context-switch cost
- **Answers:** "Your high-switching days cost ~**N** focused hours vs your focused
  days."
- **Source (the one raw-events read):** the **fragmentation derivation from P3-A
  §3.2** — `lag(project)`/`lag(ts_end)` over per-member focus sessions, a
  **project switch** = project changed AND gap ≤ 30 min (a >30-min gap is a
  *session boundary*, not a switch). The weekly facts carry only a single
  `fragmentation_index` *per week*; this finding needs **per-day** switch counts,
  which neither the facts nor any CAGG hold → **genuinely needs raw grain.** It
  reuses P3-A's exact rules (focus set, 30-min boundary, `project IS DISTINCT
  FROM`) so the finding is consistent with the `fragmentation_index` the digest
  already shows.
- **Model:** per day compute `switches_per_focus_h` and `deep_work_h`; compare
  **low-fragmentation vs high-fragmentation days** (split at the member's median)
  → output delta in focused hours; corroborate with Pearson r.
- **Gate:** ≥ MIN_DAYS, both day-groups non-empty, effect past a threshold.

---

## 2. Org grain & privacy (§8)

Org findings aggregate member signals (focus-hour-weighted peak window; team-wide
meeting→output). They are **aggregate by construction and name no member**, so
they're safe at every privacy level — no `aggregate_only` branch needed. Rule of
thumb kept simple: **a finding never contains a member name or per-person
callout**, mirroring the `top_contributors`-omitted discipline without needing the
branch. (If a future finding *did* single out a member, it'd be omitted under
`aggregate_only` exactly like `top_contributors`.)

---

## 3. Confidence model (P3-B.4)

Two gates, both must pass; mirrors P3-A's `low_confidence`/`history_weeks<4`:

1. **Hard history gate.** Distinct active days in the window <
   `CADENCE_PATTERN_MIN_DAYS` ⇒ return an **empty findings list** (the member is
   "low-data"). This is the "no noisy claims" rule, applied once for the whole
   engine.
2. **Per-finding evidence gate.** Each finding has its own significance test (min
   n, min effect size / |r|, min variance, min volume). A finding is emitted only
   if it clears the hard gate **and** its own evidence bar.

We surface **only `confidence = HIGH`** (medium/low are dropped, not shown). Then
**rank by effect strength and cap to ≤ 3** (kickoff: "1–3 high-confidence findings
only"). Each emitted finding carries its supporting numbers so the narrator can
ground a phrase without inventing one (same contract as `facts.fragmentation`).

Thresholds are tunables under `cadence.pattern.*` (defaults chosen conservatively;
listed in §7) so the bar can be tightened without a code change.

---

## 4. Exposure (P3-B.3) — additive, documented, same discipline as P2-D's commits

P3-B owns only `/backend/insights/pattern/`, so it **cannot** edit P3-A's digest
builder or P2-E's web app. The exposure is therefore **additive** on three fronts:

1. **A `PatternService` bean + `Finding`/`PatternFindings` DTOs** in
   `com.cadence.insights.pattern`. `forMember(orgId, memberId, window)` and
   `forOrg(orgId, window)` return findings, every query **`org_id`-filtered** (the
   real tenant guard; RLS is the backstop — same note as `TokenQueryService`).
2. **An additive `patterns: Finding[]` field on the frozen facts.** The narrator
   reads `facts.patterns` to write prose; the admin view renders the list.
   Existing readers that ignore it are unaffected — identical additive discipline
   to P2-D's `/org/summary.commits` facet. Documented in `00-SYSTEM-KNOWLEDGE.md`
   §6 as a P3-B extension.
3. **An additive read route** `GET /api/v1/insights/patterns?range` (member) /
   `?scope=org` (admin) in my package, so **P2-E can consume findings now**
   without waiting for P3-A's `/insights/weekly`. (See §8 Q1 — recommended but
   flagged for sign-off.)

Cross-stream wiring goes through **NEEDS lines**, never edits to their files:
- `NEEDS P3-B → P3-A`: in the digest fact-builder/narrator, call `PatternService`
  and attach `facts.patterns` (so the weekly story cites the findings).
- `NEEDS P3-B → P2-E`: render `patterns[]` in the admin overview (a "What we
  noticed" card), reading either `/insights/patterns` or `facts.patterns`.

---

## 5. What this deliberately is NOT
- **No new CAGG** (reuse V1 `events_hourly_by_category` + `events_daily_by_category`
  + the raw fragmentation query). A CAGG addition is a coordination event — avoided.
- **No heavy ML / Spark / model infra** — plain SQL rollups + correlation/threshold
  arithmetic in Java (kickoff: "simple models").
- **No flow-state predictor** (deferred — not high-confidence at 2–4 weeks).
- **No raw-event exposure to the LLM** — findings are numbers; the model writes prose.
- **No per-member callouts in findings** (privacy-safe at every level).

---

## 6. Testing strategy (this box has no Docker — same limit as P2-A.10)

**Separate analysis from data access** so the math is unit-testable without a DB:
- **Analysis = pure functions** over already-rolled-up series (a 7×24 focus grid;
  a list of per-day `{meeting_h, deep_work_h, switches, focus_ms}` rows). These get
  **unit tests with a seeded fixture** that has > MIN_DAYS of history, asserting:
  (a) each finding's math (known peak cell; known correlation/effect); (b) the hard
  gate drops a < MIN_DAYS fixture to **empty**; (c) a deliberately weak signal is
  **not** surfaced; (d) ranking caps at 3. Runs in `./gradlew build`, no Docker.
- **Data access = JDBC** (CAGG/raw → series). Gets an **integrationTest**
  (Testcontainers `timescaledb`, mirroring `E2EIngestQueryIT`) that seeds enough
  history and asserts findings appear — **deferred to a Docker host** (same dev-box
  limit recorded for P2-A.10 / P2-D).

---

## 7. Variables to set (P3-B)

```
CADENCE_PATTERN_MIN_DAYS=14     # hard history floor; < this ⇒ no findings (phase doc)
# evidence thresholds (conservative defaults; tunable, no code change):
cadence.pattern.peak-concentration=1.5   # peak cell ≥ 1.5× uniform share
cadence.pattern.min-correlation=0.4      # |r| floor for findings 2 & 3
cadence.pattern.min-effect=0.15          # ≥15% output delta on the high-vs-low split
```
Wired as `cadence.pattern.*` via a `@ConfigurationProperties` record (the
`GithubProperties`/`CategorizeProperties` pattern), added to `application.yml`.

---

## 8. Open decisions for the operator (flagged BEFORE implementing)

1. **Exposure route.** Add the additive `GET /api/v1/insights/patterns` now
   (recommended — lets P2-E integrate independently and is testable now), **or**
   ship only the `PatternService` bean + `facts.patterns` field and let P3-A
   surface findings through `/insights/weekly`? *Recommend: add the route, documented additive.*
2. **Output signal for findings 2 & 3.** Use `deep_work_h` as the output proxy
   (dense, present locally), with **commits** as a secondary signal once GitHub is
   live (sparse/zero now)? *Recommend: yes — deep_work primary, commits later.*
3. **Base/merge.** P3-B is rebased onto the **P3-A spine branch**
   (`worktree-stream+p3-a-insights`, commit `f8adcb9`) because `P3-A.CONTRACT` is
   ticked there but **not yet merged to `master`** (the kickoff's "on main /
   origin/main" doesn't match the repo — main is `master`). Confirm this base, or
   should P3-A merge to `master` first and P3-B rebase onto that?
```
