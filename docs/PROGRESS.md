# PROGRESS — living tracker

> **This file is the source of truth for state.** Update it on the go (see
> `02-PROGRESS-PROTOCOL.md`). States: `[ ]` todo · `[~]` doing · `[x]` done ·
> `[!]` blocked. Every `[x]` must be committed. Resuming sessions read this file
> and the Build Log only — never the whole codebase.

Last updated: 2026-06-27  ·  by stream: P1-D

---

## Contract checkpoints (gates for launching parallel waves)

- [x] `P1-A.CONTRACT` — Event Contract frozen in code (Go structs + JSON, golden sample, tests green); local routes land in P1-A.5 (unblocks P1-B/C/D)
- [ ] `P2-A.CONTRACT` — ingest + query + schema frozen (unblocks P2-B/C/D/E/F)
- [ ] `P3-A.CONTRACT` — aggregated-fact shape frozen (unblocks P3-B/C/E)

---

## Coordination block (NEEDS lines)
```
(none yet — add cross-stream requests here, e.g.)
NEEDS  P2-E -> P2-A : /api/v1/org/summary returns per-category daily buckets

RESOLVED  P1-D -> P1-A : local read contract frozen by P1-A.5 (commit 3986143).
       GET /timeline returns a bare event array (no envelope/pagination),
       problem+json errors, loopback-only (no auth), port 47821. Dashboard
       reads server-side via its own /api/timeline proxy (no CORS needed) and
       computes rollups from /timeline (no /summary shipped). Client
       reconciled. Resolution table in web/dashboard/docs/REQUIREMENTS-P1-D.md.

NOTE   P1-D : Phase-1 dashboard is a self-contained Next.js app rooted at
       /web/dashboard/ (no web-spine stream exists in Phase 1). The shared
       /web shell refactor for /web/admin (P2-E) + /web/insights (P3) is
       deferred to the P2 web spine. Actual check cmd: cd web/dashboard &&
       npm ci && npm run lint && npm run build.

NOTE   P1-D : env var correction — the phase-doc P1-D var
       NEXT_PUBLIC_CADENCE_AGENT_BASE is build-time-inlined by Next, so it
       can't be set per machine. The dashboard reads the daemon SERVER-SIDE
       (proxy), so it uses the RUNTIME var CADENCE_AGENT_BASE (default
       http://127.0.0.1:47821) + CADENCE_USE_MOCK. See web/dashboard/.env.example.
       Spine: please update the P1-D "Variables to set" block in
       docs/PHASE-1-foundation.md accordingly.
```

---

## Phase 1 — Foundation

### P1-A — agent core / store / contract / classifier  (SPINE)
- [x] P1-A.1 explore active-window detection per OS
- [x] P1-A.2 explore idle detection approach
- [x] P1-A.3 Event Contract structs + JSON  ← ticks P1-A.CONTRACT
- [x] P1-A.4 encrypted SQLite store + APIs
- [x] P1-A.5 local 127.0.0.1 collector/read routes
- [ ] P1-A.6 active-window + idle collector
- [ ] P1-A.7 rule-based classifier + default ruleset
- [ ] P1-A.8 local redaction list (hashing)
- [ ] P1-A.9 background service (launchd/systemd)
- [ ] P1-A.10 resource budget verification
- [ ] P1-A.11 24h soak test both machines

### P1-B — VSCode extension
- [ ] P1-B.1 explore which editor events reflect real time
- [ ] P1-B.2 explore project/lang capture + redaction
- [ ] P1-B.3 track active file/lang/workspace
- [ ] P1-B.4 emit events to daemon (debounced)
- [ ] P1-B.5 graceful degradation when daemon down
- [ ] P1-B.6 settings + pause command
- [ ] P1-B.7 verify events + classification

### P1-C — Chrome extension
- [ ] P1-C.1 explore MV3 focus-time tracking
- [ ] P1-C.2 explore privacy default (domain-only)
- [ ] P1-C.3 track active tab + focus duration
- [ ] P1-C.4 emit events per policy
- [ ] P1-C.5 map dev domains to categories
- [ ] P1-C.6 popup UI
- [ ] P1-C.7 verify events + redaction

### P1-D — personal dashboard (local)
- [x] P1-D.1 explore day-one dashboard content
- [x] P1-D.2 agree local read contract with P1-A (frozen by P1-A.5; client reconciled)
- [x] P1-D.3 Next.js dashboard reading local route
- [x] P1-D.4 daily timeline ribbon
- [x] P1-D.5 category breakdown + top projects
- [x] P1-D.6 focus score
- [x] P1-D.7 empty/offline states
- [ ] P1-D.8 verify with real local data

**Build Log — Phase 1**
```
(append newest at bottom: date  task-id  state  note; commit <sha>)
2026-06-27  P1-A.1  done   active-window detection survey (mac/linux/win); app-name-first, title opt-in via Accessibility (never Screen Recording); see agent/docs/exploration/P1-A.1; commit 490a7d2
2026-06-27  P1-A.2  done   idle detection survey; 5s poll of OS idle counter, 300s threshold, meeting-aware suppression, backdated idle start; see agent/docs/exploration/P1-A.2; commit 490a7d2
2026-06-27  P1-A.3  done   Event Contract Go structs+JSON (agent/internal/event), golden sample, validation, uuid-v4; go build/vet/test green; ticks P1-A.CONTRACT; commit dc270d3
2026-06-27  P1-A.CONTRACT  done  event shape frozen on master (fd490f2); P1-B/C/D unblocked on contract; routes pending P1-A.5
2026-06-27  P1-A.4  doing  encrypted sqlite store; app-level AES-256-GCM on title/url/meta, key in OS keychain (decided: modernc.org/sqlite has no native encryption)
2026-06-27  P1-A.4  done   store(modernc sqlite,WAL)+crypto(AES-256-GCM)+keyring(zalando/OS+memory fake); Append idempotent, Query[from,to), encrypted-at-rest test; build/vet/test green; commit 056c458
2026-06-27  P1-A.5  done   loopback API (internal/api) POST /events (single|array,max1000,idempotent), GET /timeline?from&to, GET /healthz; loopback-only guard; problem+json errors; cmd/cadence-agent wires keyring->store->server; live smoke verified (curl) + handler tests; routes documented in Variables block; commit 3986143
2026-06-27  P1-D.1  doing  exploring day-one dashboard content
2026-06-27  P1-D.2  doing  drafting proposed local read contract for P1-A
2026-06-27  P1-D.1  done   day-one content + focus-score def + states; see REQUIREMENTS-P1-D.md; commit cc31479
2026-06-27  P1-D.2  block  read contract proposed; awaiting P1-A to freeze (NEEDS filed); blocks P1-D.3+
2026-06-27  P1-D.3  doing  scaffold self-contained Next.js app under web/dashboard
2026-06-27  P1-D.3  done   Next.js app + TS Event Contract mirror + agent client (http/mock) + /api/timeline proxy (RFC7807) + summary engine; reads via mock, lint+build green, smoke-tested; commit 7ac6b4a
2026-06-27  P1-D.4  doing  timeline ribbon layout + component
2026-06-27  P1-D.4  done   cropped hourly ribbon, category-colored blocks, native tooltips, hour ticks; shared color palette; lint+build green, smoke-verified 14 blocks/8 colors; commit 7897fbb
2026-06-27  P1-D.5  doing  category donut + top projects
2026-06-27  P1-D.5  done   SVG category donut + legend (%s sum 100) + ranked project bars (null->Unassigned); lint+build green, smoke-verified 7 arcs; commit 38958f2
2026-06-27  P1-D.2  done   read contract frozen by P1-A.5; reconciled HttpAgentClient to bare array (no envelope/pagination), proxy returns array, mock matches ts_start-in-range; NEEDS resolved; resolution table in REQUIREMENTS-P1-D.md; lint+build green; commit 7e2a349
2026-06-27  P1-D.6  doing  focus score card
2026-06-27  P1-D.6  done   FocusCard: 0-100 score + band (Focused/Mixed/Fragmented) + plain-language read (deep blocks/longest/switches); lint+build green, smoke-verified; commit 7275b56
2026-06-27  P1-D.7  doing  friendly offline/empty/error/loading states + live polling
2026-06-27  P1-D.7  done   StatePanel (offline w/ start cmd+retry, empty, error, loading skeleton) + LiveDay client polls /api/timeline every 60s; fixed env: runtime CADENCE_AGENT_BASE/USE_MOCK (NEXT_PUBLIC is build-inlined), default port 47821, .env.example; smoke-verified offline(503)/mock; lint+build green; commit cb00cf4
```

---

## Phase 2 — Cloud + Org

### P2-A — backend / auth / schema / contracts  (SPINE)
- [ ] P2-A.1 explore multi-tenant model + onboarding UX
- [ ] P2-A.2 explore JWT/invite flows
- [ ] P2-A.3 Flyway V1 schema (orgs/members/teams/seats/events hypertable/job_queue/aggregates)
- [ ] P2-A.4 ingest endpoint (idempotent, privacy-applied)  ← ticks P2-A.CONTRACT
- [ ] P2-A.5 me/* + org/* query endpoints
- [ ] P2-A.6 auth endpoints + RLS
- [ ] P2-A.7 privacy enforcement layer
- [ ] P2-A.8 health/logging/tracing
- [ ] P2-A.9 docker-compose local cloud
- [ ] P2-A.10 e2e privacy-level verification

### P2-B — sync engine
- [ ] P2-B.1 explore sync strategy
- [ ] P2-B.2 explore device enrollment
- [ ] P2-B.3 outbound sync loop (filtered, batched)
- [ ] P2-B.4 keychain token storage + refresh
- [ ] P2-B.5 enrollment via invite link
- [ ] P2-B.6 backoff/offline durability tests

### P2-C — token watcher
- [ ] P2-C.1 explore tool log locations/formats
- [ ] P2-C.2 confirm counts-only (no content)
- [ ] P2-C.3 per-tool parsers → events
- [ ] P2-C.4 incremental tail + project attribution
- [ ] P2-C.5 backend cost aggregation

### P2-D — github integration
- [ ] P2-D.1 explore App vs OAuth vs PAT
- [ ] P2-D.2 design commit-only vs full-diff toggle
- [ ] P2-D.3 GitHub App webhook → events
- [ ] P2-D.4 map github login → member
- [ ] P2-D.5 respect toggle

### P2-E — org admin dashboard
- [ ] P2-E.1 explore admin needs (trust-first)
- [ ] P2-E.2 onboarding flow UX
- [ ] P2-E.3 auth pages
- [ ] P2-E.4 roster + invites + privacy control
- [ ] P2-E.5 team summary (heatmap/tokens/commits)
- [ ] P2-E.6 member drilldown (privacy-bounded)
- [ ] P2-E.7 install instructions page

### P2-F — categorisation worker
- [ ] P2-F.1 explore escalation rules
- [ ] P2-F.2 prompt design (fixed enum out)
- [ ] P2-F.3 worker claims jobs + LLM call + write-back
- [ ] P2-F.4 pattern cache
- [ ] P2-F.5 cost guardrails + metrics

**Build Log — Phase 2**
```
(append newest at bottom)
```

---

## Phase 3 — AI Intelligence + Revenue

### P3-A — insights foundation  (SPINE)
- [ ] P3-A.1 explore aggregated-fact shape
- [ ] P3-A.2 explore delivery + shareable card
- [ ] P3-A.3 insights/digests migration + aggregation layer  ← ticks P3-A.CONTRACT
- [ ] P3-A.4 weekly insights endpoint
- [ ] P3-A.5 digest job (compute → narrate → store/email)
- [ ] P3-A.6 prompt engineering (grounded narrative)
- [ ] P3-A.7 shareable card render

### P3-B — pattern engine
- [ ] P3-B.1 explore useful patterns
- [ ] P3-B.2 time-series rollups + simple models
- [ ] P3-B.3 expose to digest + admin
- [ ] P3-B.4 confidence thresholds

### P3-C — NL query
- [ ] P3-C.1 explore safe text-to-SQL constraints
- [ ] P3-C.2 schema-aware prompt → SQL (read-only, scoped)
- [ ] P3-C.3 nl query endpoint
- [ ] P3-C.4 query UI + charts

### P3-D — billing
- [ ] P3-D.1 explore pricing → Stripe mapping
- [ ] P3-D.2 products/prices + checkout + portal
- [ ] P3-D.3 webhook lifecycle handling
- [ ] P3-D.4 feature gating by plan
- [ ] P3-D.5 token-overage metering

### P3-E — budget alerts
- [ ] P3-E.1 explore anomaly definition
- [ ] P3-E.2 agent loop (compare → narrate)
- [ ] P3-E.3 Slack + email delivery
- [ ] P3-E.4 per-org config

**Build Log — Phase 3**
```
(append newest at bottom)
```
