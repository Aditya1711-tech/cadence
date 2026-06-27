# P1-D — Requirements Exploration (personal dashboard, local-only)

Stream: **P1-D** · Owns `/web/dashboard/` · Phase 1 (local, no cloud, all `127.0.0.1`).
Status: exploration only. **No implementation until `P1-A.CONTRACT` is ticked.**

This document covers tasks **P1-D.1** (what to show day-one) and **P1-D.2**
(the local read contract proposed to P1-A). It is the "report before coding"
deliverable required by the phase doc.

---

## P1-D.1 — What a developer actually wants to see on day one

### Design principle: glanceable in 5 seconds, zero setup friction

Phase 1 is local-only and there is no login. The dashboard opens straight to
**today**. Success = the developer understands "where did my day go?" and "am I
focused or fragmented?" without clicking anything. Everything else is secondary.

### Information hierarchy (top → bottom)

1. **Hero stat — focused time today.** One large number: total `deep_work`
   time, with active-vs-idle context (e.g. "4h 12m focused · 6h 40m tracked").
   This is the single thing a developer checks. It answers the day-one question
   alone.

2. **Daily timeline ribbon (P1-D.4).** A horizontal 24-hour (or working-hours,
   auto-cropped to first/last activity) bar split into hour/sub-hour blocks
   colored by category. Hovering a block shows app/project/duration. This is the
   "where did my day go" visual and is the centerpiece.

3. **Category breakdown donut (P1-D.5).** Share of time per category across the
   v1 enum: `deep_work`, `meetings`, `comms`, `research`, `code_review`,
   `ai_assisted`, `idle`, `other`. Durations + percentages. Fixed color legend
   shared with the ribbon so the two read as one picture.

4. **Top projects (P1-D.5).** Ranked list by tracked time, from `event.project`.
   Day-one this answers "what did I actually work on." Null/unknown project
   bucketed as "Unassigned" rather than hidden.

5. **Focus score (P1-D.6).** A single 0–100 (or 0–1) score plus a one-line
   plain-language read ("3 deep blocks, longest 90m, 11 context switches").
   See definition below.

6. **AI token spend (the wedge) — forward-looking panel.** Token-source events
   carry `meta.model`, `meta.tokens_in/out`, `meta.cost_usd`. This is the
   product's differentiator, so the dashboard reserves a first-class panel for
   "AI cost today" (cost, tokens, by-model). **Caveat:** the token watcher is
   Phase 2 (P2-C); in Phase 1 there is usually no token data, so this panel must
   degrade to a friendly "No AI usage tracked yet" empty state rather than
   render zeros as if they were real. Building the slot now keeps the contract
   stable for when token events arrive.

### Focus vs fragmentation — proposed definition (informs P1-D.6)

- **Uninterrupted deep block:** a contiguous run of `deep_work` (allowing small
  gaps, e.g. ≤ 2 min, to absorb micro-switches) lasting **≥ 25 min** (the phase
  doc's threshold).
- **Focus score:** `time_in_qualifying_deep_blocks / total_active_time`,
  expressed 0–100. High = long uninterrupted stretches; low = deep work exists
  but is shredded into sub-25-min slivers.
- **Fragmentation signals (shown alongside the score, not folded in):** number
  of context switches (category changes), count of deep blocks, longest block.
  These make the score legible instead of a black box.
- Idle time is excluded from the denominator so a lunch break doesn't tank the
  score.

### States the dashboard must handle gracefully (informs P1-D.7)

- **Daemon offline** — friendly "Cadence isn't running" with how-to-start, not a
  stack trace. This is the most common first-run state.
- **No data yet today** — "Nothing tracked yet today" with a hint that tracking
  is live, not an error.
- **Partial day** — ribbon crops to actual activity; never imply the empty hours
  are "missing data."
- **Loading** — skeleton, not a spinner blocking the whole view.

### Explicitly out of scope for day-one (keep it glanceable)

Multi-day trends, week/month rollups, comparisons, goals, team/anything cloud,
settings beyond day navigation. Phase 1 is "today, at a glance."

---

## P1-D.2 — Proposed local read contract (to be frozen by P1-A)

**Dependency:** P1-D consumes P1-A.5 (the local `127.0.0.1` read route). P1-A
owns and freezes this; P1-D only *consumes*. Below is the **proposal** P1-D
needs P1-A to confirm or amend. Coordinated via a NEEDS line in `PROGRESS.md`.

Anchors already fixed by `00-SYSTEM-KNOWLEDGE.md`:
- The **Event Contract** (§5) — the exact per-event shape.
- REST conventions (§6): JSON, RFC3339 UTC timestamps, RFC 7807 problem+json
  errors, cursor+limit pagination on list endpoints.
- P1-A "Variables to set" already sketches: `GET http://127.0.0.1:<port>/timeline?from&to`.

### Route 1 — `GET /timeline?from=<RFC3339>&to=<RFC3339>`

Returns the raw events overlapping the window, in **exact Event Contract shape**
(§5), so the dashboard can compute any view client-side.

```jsonc
// 200 OK
{
  "events": [ /* array of Event Contract objects, ordered by ts_start asc */ ],
  "next_cursor": null   // string when more pages exist; null when complete
}
```

- `from`/`to` are UTC RFC3339. The **client** converts the user's local-day
  boundaries to UTC before calling (storage never localizes — §5).
- Pagination: `&cursor=&limit=` (default 100 per §6). A full day is typically a
  few hundred events; the dashboard pages until `next_cursor` is null.

### Route 2 — `GET /summary?from=<RFC3339>&to=<RFC3339>` (requested)

Pre-aggregated rollups so the "glanceable in 5s" view doesn't depend on pulling
and crunching every raw event on the client. Mirrors the cloud `me/summary`
(§6) so the dashboard's data layer is forward-compatible with Phase 2.

```jsonc
// 200 OK
{
  "range": { "from": "2026-06-27T00:00:00Z", "to": "2026-06-27T23:59:59Z" },
  "total_ms":  28800000,
  "active_ms": 25200000,
  "idle_ms":   3600000,
  "by_category": [
    { "category": "deep_work", "ms": 14400000, "pct": 0.571 },
    { "category": "meetings",  "ms": 5400000,  "pct": 0.214 }
    // ... one row per v1 category enum value present
  ],
  "top_projects": [
    { "project": "cadence-api", "ms": 10800000, "pct": 0.43 },
    { "project": null,          "ms": 1800000,  "pct": 0.07 }  // -> "Unassigned"
  ],
  "focus": {
    "focus_score":        0.72,     // 0..1
    "deep_work_ms":       14400000,
    "longest_block_ms":   5400000,
    "qualifying_blocks":  3,         // deep blocks >= 25 min
    "context_switches":   11
  },
  "ai": {                            // null/empty in Phase 1 until P2-C lands
    "cost_usd":   0.0,
    "tokens_in":  0,
    "tokens_out": 0,
    "by_model":   []                 // [{ "model": "...", "cost_usd": 0.0 }]
  }
}
```

If P1-A prefers **not** to ship `/summary` in Phase 1, the dashboard can compute
all of the above from `/timeline` alone — `/summary` is a
performance/simplicity ask, not a hard blocker. Stating the preferred option:
**daemon-side `/summary` is preferred** (keeps the client thin and matches the
Phase 2 query API), but `/timeline`-only is an acceptable fallback.

### Open questions for P1-A (the actual decisions to freeze)

1. **`/timeline` payload:** full Event Contract objects, or a trimmed
   projection? P1-D requests **full objects** (needs `meta` for AI, `is_idle`,
   `category`, `project`, `app`, `ts_*`).
2. **`/summary`:** will P1-A provide it, or should P1-D compute from `/timeline`?
   (Preference: provide it.)
3. **CORS / origin:** the Next.js dev server (`localhost:3000`) calling
   `127.0.0.1:<port>` is **cross-origin**. Either the daemon sets
   `Access-Control-Allow-Origin` for the loopback dev origin, or P1-D proxies
   through a Next.js route handler (same-origin server→daemon). Needs a
   decision so the dashboard can actually read the route in the browser.
4. **Auth on the local route:** Phase 1 is loopback-only — confirm **no auth**
   (no bearer token) on `127.0.0.1`, unlike the cloud API (§6).
5. **Live refresh:** is polling (`/timeline` or `/summary` every ~30–60s)
   expected, or is there an SSE/websocket push? P1-D assumes **polling** for
   Phase 1 unless told otherwise.
6. **Errors:** confirm RFC 7807 problem+json (§6) on the local route too, so the
   offline/error states (P1-D.7) can parse a consistent shape.
7. **Port discovery:** `NEXT_PUBLIC_CADENCE_AGENT_BASE=http://127.0.0.1:<port>`
   is the P1-D env var; confirm `CADENCE_AGENT_PORT` is the single source of
   truth the dashboard reads.

### What P1-D will build once this is frozen (not started yet)

P1-D.3 Next.js data layer hitting these routes → P1-D.4 ribbon → P1-D.5
breakdown/projects → P1-D.6 focus score → P1-D.7 empty/offline states →
P1-D.8 verify with real local data. All gated on `P1-A.CONTRACT` + the answers
above.

---

## P1-D.2 — RESOLVED (frozen by P1-A.5, `agent/internal/api/server.go`)

P1-A shipped the loopback API. The dashboard is reconciled to the **actual**
contract below; the proposal above is kept for history. NEEDS line cleared.

| # | Open question | Frozen answer |
|---|---|---|
| 1 | `/timeline` payload | **Full Event Contract objects** — but a **bare JSON array**, not the `{events,next_cursor}` envelope I proposed. `getTimeline` parses the array directly. |
| 2 | `/summary` route | **Not shipped.** The dashboard computes every rollup from `/timeline` (lib/summary.ts) — exactly the documented fallback. No blocker. |
| 3 | CORS / origin | **Moot** — the dashboard reads the daemon **server-side** via its own `/api/timeline` route handler, so the browser is same-origin and the daemon never sees a cross-origin request. |
| 4 | Auth on local route | **No auth.** Daemon enforces loopback-only (`loopbackOnly` middleware rejects non-loopback peers); nothing else is needed in Phase 1. |
| 5 | Live refresh | **Polling.** No SSE/websocket; the client polls `/api/timeline`. Implemented in P1-D.7. |
| 6 | Errors | **RFC 7807 problem+json** confirmed on the daemon; the proxy mirrors it. |
| 7 | Port | `CADENCE_AGENT_PORT`, **default 47821**. Dashboard reads `NEXT_PUBLIC_CADENCE_AGENT_BASE` (e.g. `http://127.0.0.1:47821`). |

**Other shipped semantics the client honors:**
- `/timeline` filters by **`ts_start` in `[from, to)`** (not overlap); the mock
  client matches this so dev mirrors prod.
- `from`/`to` omitted → daemon defaults to the **last 24h**. The dashboard always
  sends explicit local-day boundaries (converted to UTC).
- There is **no pagination**; a day's events come back in one array.
- `POST /events` (collectors) accepts a single object or an array (max 1000),
  idempotent on `event_id` — not used by P1-D, noted for completeness.
