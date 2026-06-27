# PROGRESS — living tracker

> **This file is the source of truth for state.** Update it on the go (see
> `02-PROGRESS-PROTOCOL.md`). States: `[ ]` todo · `[~]` doing · `[x]` done ·
> `[!]` blocked. Every `[x]` must be committed. Resuming sessions read this file
> and the Build Log only — never the whole codebase.

Last updated: 2026-06-27  ·  by stream: P1-A

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
```

---

## Phase 1 — Foundation

### P1-A — agent core / store / contract / classifier  (SPINE)
- [x] P1-A.1 explore active-window detection per OS
- [x] P1-A.2 explore idle detection approach
- [x] P1-A.3 Event Contract structs + JSON  ← ticks P1-A.CONTRACT
- [x] P1-A.4 encrypted SQLite store + APIs
- [x] P1-A.5 local 127.0.0.1 collector/read routes
- [x] P1-A.6 active-window + idle collector (Windows backend done; mac/linux scaffolded)
- [x] P1-A.7 rule-based classifier + default ruleset
- [x] P1-A.8 local redaction list (hashing)
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
- [ ] P1-D.1 explore day-one dashboard content
- [ ] P1-D.2 agree local read contract with P1-A
- [ ] P1-D.3 Next.js dashboard reading local route
- [ ] P1-D.4 daily timeline ribbon
- [ ] P1-D.5 category breakdown + top projects
- [ ] P1-D.6 focus score
- [ ] P1-D.7 empty/offline states
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
2026-06-27  P1-A.7  done   rule classifier (internal/classify): ordered regex ruleset app/title/url/source/is_idle -> category, first-match-wins; shipped default ruleset (editors->deep_work, meetings, comms, code_review, ai_assisted, research, idle); user-editable JSON via CADENCE_RULES_PATH (scaffolded on first run); wired into POST /events to fill null categories; build/vet/test green, cross-compiles mac/linux; commit 6bc5187
2026-06-27  P1-A.8  done   redaction list (internal/redact): user regex -> SHA-256 hash of matching title/url before store (stable token, groupable); user JSON via CADENCE_REDACT_PATH (scaffold empty), default off; refactored api.New to Options{Classifier,Redactor,Logger}; runs after classify so categories use real values; tests + ingest redaction test green; commit acd7225
2026-06-27  P1-A.6  done   OS collector (internal/collector): OS-agnostic segmentation loop (window/idle transitions, back-dated idle boundary, meeting idle-suppression), HTTP sink -> local /events; Windows backend (user32/kernel32, no CGO) runtime-tested (reads real fg window+idle); macOS/Linux backends scaffolded behind build tags (cross-compile-checked, NOT runtime-verified here - see memory); member-id persisted in keychain; collector started best-effort in main; loop unit tests + win integration test green; commit 8845c9a
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
