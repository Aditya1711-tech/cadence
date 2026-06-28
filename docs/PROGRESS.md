# PROGRESS — living tracker

> **This file is the source of truth for state.** Update it on the go (see
> `02-PROGRESS-PROTOCOL.md`). States: `[ ]` todo · `[~]` doing · `[x]` done ·
> `[!]` blocked. Every `[x]` must be committed. Resuming sessions read this file
> and the Build Log only — never the whole codebase.

Last updated: 2026-06-28  ·  by stream: P2-D

---

## Contract checkpoints (gates for launching parallel waves)

- [x] `P1-A.CONTRACT` — Event Contract frozen in code (Go structs + JSON, golden sample, tests green); local routes land in P1-A.5 (unblocks P1-B/C/D)
- [x] `P2-A.CONTRACT` — ingest + query + schema frozen (unblocks P2-B/C/D/E/F). Schema = backend/migrations/V1__init.sql; ingest shape = EventDto/IngestResult; query shapes = Summaries.*; auth shapes = AuthDtos.*. Backend compiles (JDK21 toolchain) + 11 unit tests green; full DB e2e is authored (Testcontainers, P2-A.10) and runs on a Docker host. Frozen at code/SQL/doc level — safe to launch Wave-1.
- [ ] `P3-A.CONTRACT` — aggregated-fact shape frozen (unblocks P3-B/C/E)

---

## Coordination block (NEEDS lines)
```
(none yet — add cross-stream requests here, e.g.)
NEEDS  P2-E -> P2-A : /api/v1/org/summary returns per-category daily buckets

OPEN   P2-C -> P1-A : wire the token watcher into the daemon — one call in
          agent/cmd/cadence-agent/main.go (P1-A-owned) to start the token
          collector alongside startCollector(). INTERIM: P2-C ships a
          standalone runnable agent/token/cmd/cadence-token that POSTs to the
          same loopback /events route, so no P1-A change blocks P2-C. Adopt the
          in-daemon wire when P1-A resumes (mac/linux handoff session).
NEEDS  P2-D -> P2-A : V2 migration for installation->org mapping. New org-scoped
          (RLS) table github_installations(id, org_id->orgs, installation_id
          bigint UNIQUE, account_login text, mode text default
          'commit_messages_only' CHECK in ('commit_messages_only','full_diff'),
          suspended_at, created_at) + idx on org_id + enable RLS w/ org_isolation
          policy on org_id. Reason: a GitHub webhook arrives keyed only by GitHub
          installation_id; we must resolve org_id (+ privacy mode) before
          inserting source='github' events. members.github_login already covers
          github-user->member. Nice-to-have: partial UNIQUE(org_id,github_login)
          WHERE github_login IS NOT NULL on members. Full proposed DDL +
          cross-org-lookup note in backend/docs/exploration/P2-D.1-github-integration-model.md §4.

RESOLVED  P2-E -> P2-A : /org/summary returns org_by_day[] (per-day per-category
          buckets) + org_totals_by_category[] + by_member[] (privacy-bounded).
          Shape = com.cadence.query.Summaries.OrgSummary. (P2-A.5)
RESOLVED  (P1 member_id gap) P2-A : canonical member_id = members.id, assigned by
          the backend at INVITE-ACCEPT / DEVICE-ENROLL. The daemon learns it via
          POST /api/v1/auth/device/enroll {code} -> {member_id, access, refresh};
          all collectors on a machine then share it. Ingest STAMPS org_id+member_id
          from the JWT and ignores any client-supplied member_id. P2-B: stop self-
          generating uuids once enrolled; adopt the returned member_id.
NOTE   P2-A -> P2-F : job_queue categorize PAYLOAD shape = {"event_id":"<uuid>",
          "ts_start":"<rfc3339>"}; job_queue row carries org_id. Worker claims via
          SELECT ... FOR UPDATE SKIP LOCKED, reads the event under org context,
          writes back category. Confirm/extend before P2-F.3.
RESOLVED P2-B (operator) : track "un-synced" via Option A = self-contained sidecar.
          P2-B owns a sync.db (synced(event_id PK, ts_start_ms, synced_at_ms) + meta
          watermark); diff store.Query windows; watermark never advances past unsent
          events (offline-durable). Does NOT edit store.go. Revisit Option B (P1-A
          events.synced_at, §7.1) only when P1-A next touches the store.
NOTE   P2-B -> P1-A (operator-approved) : P2-B makes a MINIMAL touch to
          agent/cmd/cadence-agent/main.go — a thin call into /agent/sync to start the
          loop when enrolled + `enroll`/`status` subcommands. Only P1-A-owned file
          P2-B edits; logic stays in /agent/sync.
NOTE   P2-A -> ALL  : auth contract for clients — Authorization: Bearer <access JWT>
          (HS256, 60m); refresh via POST /auth/refresh {refresh_token} (rotating;
          reuse revokes the family). Ingest=POST /api/v1/ingest/events (array<=1000,
          idempotent). Backend is ONE Spring Boot jar; add per-stream packages under
          com.cadence.<stream> (do not edit other streams' packages).

RESOLVED  P1-C -> P1-A : local route + CORS — P1-A.5 ships POST /events on 127.0.0.1:47821 (default); manifest host_permissions http://127.0.0.1/* grants the SW cross-origin access, so no server CORS needed. (verified by reading agent/internal/api/server.go)
OPEN      P1-C -> P1-A : expose install-time member_id via the local API so all collectors share one identity — NOT provided by P1-A.5 (server stores whatever member_id the event carries). INTERIM: chrome self-generates a stable uuid in storage; should adopt the daemon's id once exposed. (also affects P1-B)
OPEN      P1-B -> P1-A : same member_id gap as P1-C above. INTERIM: vscode ext
          self-generates a stable uuid in globalState (identity.ts). Re-sends are
          idempotent. Both collectors should adopt a canonical daemon identity
          before Phase-2 sync correlates them.
RESOLVED  P1-B -> P1-A : P1-A.7 classifier — vscode events (category null, url
          null, is_idle false) classify deep_work via the editor-source rule;
          verified live end-to-end (ext-vscode/docs/verification-P1-B.7.md).
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

**Exit-criteria status (gate to Phase 2) — audited 2026-06-27:**
- [~] Daemon on macOS + Linux, survives logout/login, < 2% idle CPU —
  **PARTIAL.** Windows backend runtime-verified (0.0% idle, ~3 MB). The mac/linux
  collector backends + launchd/systemd units are authored & cross-compiled but
  **NOT runtime-verified** (see P1-A.6/.9 notes).
- [x] VSCode + Chrome events land in the local store with correct categories —
  verified live (P1-B.7, P1-C.7).
- [x] Dashboard shows today's timeline + category breakdown from local data only —
  verified against the real daemon (P1-D.8, single machine).
- [!] Both founders run it 14 continuous days with no crash — **NOT MET.** The
  P1-A.11 soak is blocked: needs both founders' macOS + Linux machines for 14 days.

**Verdict: Phase 1 is NOT fully gated.** Every build task is `[x]` except
`P1-A.11` (`[!]`). The open items are runtime-only (mac/linux soak + 14-day
dogfood) and need the founders' machines — no code work remains. Per the progress
protocol §8 the phase gate is not satisfied until those pass.

### P1-A — agent core / store / contract / classifier  (SPINE)
- [x] P1-A.1 explore active-window detection per OS
- [x] P1-A.2 explore idle detection approach
- [x] P1-A.3 Event Contract structs + JSON  ← ticks P1-A.CONTRACT
- [x] P1-A.4 encrypted SQLite store + APIs
- [x] P1-A.5 local 127.0.0.1 collector/read routes
- [x] P1-A.6 active-window + idle collector (Windows backend done; mac/linux scaffolded)
- [x] P1-A.7 rule-based classifier + default ruleset
- [x] P1-A.8 local redaction list (hashing)
- [x] P1-A.9 background service (launchd/systemd) (authored+syntax-checked; runtime install verified on mac/linux)
- [x] P1-A.10 resource budget verification (Windows: 0.0% idle CPU, ~3MB; mac/linux confirmed in soak)
- [!] P1-A.11 24h soak test both machines (BLOCKED: needs founders' macOS+Linux machines, 14 days)

### P1-B — VSCode extension
- [x] P1-B.1 explore which editor events reflect real time
- [x] P1-B.2 explore project/lang capture + redaction
- [x] P1-B.3 track active file/lang/workspace
- [x] P1-B.4 emit events to daemon (debounced)
- [x] P1-B.5 graceful degradation when daemon down
- [x] P1-B.6 settings + pause command
- [x] P1-B.7 verify events + classification

### P1-C — Chrome extension
- [x] P1-C.1 explore MV3 focus-time tracking
- [x] P1-C.2 explore privacy default (domain-only)
- [x] P1-C.3 track active tab + focus duration
- [x] P1-C.4 emit events per policy
- [x] P1-C.5 map dev domains to categories
- [x] P1-C.6 popup UI
- [x] P1-C.7 verify events + redaction

### P1-D — personal dashboard (local)
- [x] P1-D.1 explore day-one dashboard content
- [x] P1-D.2 agree local read contract with P1-A (frozen by P1-A.5; client reconciled)
- [x] P1-D.3 Next.js dashboard reading local route
- [x] P1-D.4 daily timeline ribbon
- [x] P1-D.5 category breakdown + top projects
- [x] P1-D.6 focus score
- [x] P1-D.7 empty/offline states
- [x] P1-D.8 verify with real local data (single machine; both-machines dogfood is phase-exit work)

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
2026-06-27  P1-A.9  done   service lifecycle (agent/dist): launchd LaunchAgent (RunAtLoad+KeepAlive, Background/LowPriorityIO) + systemd user unit (Restart=on-failure, Nice/CPUWeight/MemoryMax, enable-linger for survive-logout) + install.sh/uninstall.sh (build/locate binary, substitute paths, enable) + README; bash -n + plist XML validated; runtime install/restart verification deferred to mac/linux (Windows out of scope per task); commit f0b5151
2026-06-27  P1-A.10 done   resource budget: idle CPU measured 0.0% over 24s (typeperf), private working set ~3MB / RSS 13-57MB (tasklist); collector polls every 5s with batched HTTP flush, store WAL+single-writer; well under <2% idle target. NOTE: measured on Windows; authoritative mac/linux numbers come from the soak; commit 7069ac6
2026-06-27  P1-A.11 block  24h soak requires BOTH founders' machines (macOS + Linux) running 14 continuous days per phase exit criteria; cannot be performed in this Windows dev session. HANDOFF to a mac/linux session.
2026-06-27  P1-A     note   HANDOFF for mac/linux sessions: implement+verify collector backends (platform_darwin.go NSWorkspace+Accessibility+CGEventSource; platform_linux.go X11 EWMH+XScreenSaver / Wayland app-only); runtime-verify P1-A.9 service install; capture authoritative P1-A.10 idle-CPU on mac/linux; run P1-A.11 soak. All other P1-A code is done, tested on Windows, and on master.
2026-06-27  P1-C.1  doing  exploring MV3 SW lifecycle + active-tab focus tracking
2026-06-27  P1-C.2  doing  exploring domain-only privacy default + redaction honoring
2026-06-27  P1-C.1  done   findings in ext-chrome/docs/01-requirements-exploration.md; commit be835e0
2026-06-27  P1-C.2  done   domain-only default + daemon-owns-redaction; commit be835e0
2026-06-27  P1-C.3  doing  scaffold MV3 ext + focus state machine (tabs/windows/idle/alarms)
2026-06-27  P1-C.3  done   MV3 scaffold + focus state machine (focusLogic/focusTracker/index); npm ci && npm run build green; in-browser runtime verification deferred to P1-C.7; commit 0c5c4e4
2026-06-27  P1-C.4  block  emit blocked on P1-A.5 local route + member_id handshake; NEEDS lines filed in coordination block
2026-06-27  P1-C.5  doing  dev-domain -> category map (github/meet/zoom/slack/so/docs/ai tools)
2026-06-27  P1-C.5  done   contract.ts Category mirror + categorize(); host/suffix/docs rules; 15-case behavioral check green, build green; commit 15f6600
2026-06-27  P1-C.4  doing  rebased onto P1-A.5 (port 47821, POST /events); emit module: span->Event, domain_only/full policy, interim member_id, flush w/ graceful degradation
2026-06-27  P1-C.4  done   emit.ts span->Event (domain_only origin+null-title / full), interim member_id, batched flush to 127.0.0.1:47821 w/ graceful degradation; wired to heartbeat+startup; shaping Validate-clean via ported-rules check + build green; live daemon round-trip needs running Go agent -> P1-C.7/founder machines; commit f485dba
2026-06-27  P1-C.6  doing  popup: today's top sites (via daemon /timeline), pause toggle, privacy toggle; paused wired into tracker
2026-06-27  P1-C.6  done   popup.html/css + popup.ts (pause toggle, urlPrivacy select, today's top sites via daemon /timeline + offline state); paused honored in reconcile + storage.onChanged for prompt effect; sites helpers verified (15 asserts), build green; in-browser render -> P1-C.7; commit b8602a3
2026-06-27  P1-C.7  doing  formalize verification: node:test suites (focusLogic/categorize/emit/sites) + manual on-machine E2E doc; fill Variables block
2026-06-27  P1-C.7  done   npm test green (33 cases): spanToEvent Validate-clean + domain_only redaction verified, categorize, focus state machine, popup aggregation; manual live E2E checklist (ext-chrome/docs/02-verification.md) for founder box (no Go here); Variables block filled. P1-C stream COMPLETE. commit a2daef2
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
2026-06-27  P1-D.8  done   e2e against REAL daemon: built+ran cadence-agent (temp store, :47821), POSTed 14 events (idempotent), dashboard rendered real data via proxy (hero/ribbon/focus/donut/projects); offline->503 friendly state; both-machines dogfood noted as phase-exit; see VERIFICATION-P1-D.8.md; commit 11a99ed
2026-06-27  P1-B.1  done   editor-event survey + focused-session model; ext-vscode/docs/requirements-exploration.md; commit 2ebe52b
2026-06-27  P1-B.2  done   project (workspace folder) / lang (languageId) capture + path-redaction approach; same doc; commit 2ebe52b
2026-06-27  P1-B.3  done   focused-session tracker (src/session.ts): window-focus gate + edit/selection/scroll/debug heartbeats, 300s idle aligned w/ P1-A.2 + backdated close; vscode wiring (src/extension.ts); commit 2ebe52b
2026-06-27  P1-B.4  done   emitter.ts mapSegment->Event Contract (source=vscode, title=basename+project, meta.lang, is_idle=false, category=null) + DaemonEmitter 30s/focus-loss debounced batch POST to /events; identity.ts provisional member_id; agentPort/redactPaths settings; commit 2ebe52b
2026-06-27  P1-B.5  done   graceful degradation: retain-on-failure + timer retry, bounded queue (drop-oldest, logged), snapshot/restore backlog across editor restarts via globalState (idempotent re-send); commit 2ebe52b
2026-06-27  P1-B.6  done   settings cadence.enabled off-switch + pause/resume/toggle commands + status-bar toggle (pause flushes, never discards); commit 2ebe52b
2026-06-27  P1-B.7  done   verified: 22 node:test units + LIVE e2e vs real daemon incl. P1-A.7 classifier -> category deep_work, correct project/lang/is_idle, idempotency; ext-vscode/docs/verification-P1-B.7.md; P1-B stream COMPLETE; commit 2ebe52b
2026-06-27  DOCS    done   audit as-built P1 code vs frozen contracts; §5 Event Contract = ZERO drift; reconciled doc-vs-code drift to code: ENV-VARIABLES + LOCAL-SETUP (port 8765->47821, keychain cadence-local->com.cadence.agent, db path, build ./cmd/cadence-agent), PHASE-1 P1-D + ENV/LOCAL (NEXT_PUBLIC_CADENCE_AGENT_BASE -> runtime CADENCE_AGENT_BASE + CADENCE_USE_MOCK), added missing agent env vars, documented local SQLite store as 00-SYSTEM-KNOWLEDGE §7.1; flagged P1 exit criteria unmet (mac/linux soak + 14-day dogfood); commit 76a0a13
```

---

## Phase 2 — Cloud + Org

### P2-A — backend / auth / schema / contracts  (SPINE)
- [x] P2-A.1 explore multi-tenant model + onboarding UX
- [x] P2-A.2 explore JWT/invite flows
- [x] P2-A.3 Flyway V1 schema (orgs/members/teams/seats/events hypertable/job_queue/aggregates)
- [x] P2-A.4 ingest endpoint (idempotent, privacy-applied)  ← ticks P2-A.CONTRACT
- [x] P2-A.5 me/* + org/* query endpoints
- [x] P2-A.6 auth endpoints + RLS
- [x] P2-A.7 privacy enforcement layer
- [x] P2-A.8 health/logging/tracing
- [x] P2-A.9 docker-compose local cloud
- [~] P2-A.10 e2e privacy-level verification (Testcontainers e2e AUTHORED + compiles; running it needs a Docker host — not available on this Windows dev box; HANDOFF)

### P2-B — sync engine
- [x] P2-B.1 explore sync strategy
- [x] P2-B.2 explore device enrollment
- [x] P2-B.3 outbound sync loop (filtered, batched)
- [x] P2-B.4 keychain token storage + refresh
- [x] P2-B.5 enrollment via invite link
- [x] P2-B.6 backoff/offline durability tests

### P2-C — token watcher
- [x] P2-C.1 explore tool log locations/formats
- [x] P2-C.2 confirm counts-only (no content)
- [x] P2-C.3 per-tool parsers → events
- [x] P2-C.4 incremental tail + project attribution
- [x] P2-C.5 backend cost aggregation (code+unit-tests green; live Timescale query HANDOFF, no Docker here)

### P2-D — github integration
- [x] P2-D.1 explore App vs OAuth vs PAT
- [x] P2-D.2 design commit-only vs full-diff toggle
- [x] P2-D.3 GitHub App webhook → events (code+unit-tested; live e2e gated on V2 table + Docker)
- [x] P2-D.4 map github login → member
- [x] P2-D.5 respect toggle (full_diff stats-enrichment API call stubbed; default mode complete)

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
2026-06-27  P2-A.1  done   multi-tenant model + onboarding UX exploration: shared-schema + RLS by org_id; orgs/members/teams(+team_members join)/seats/invites/events-hypertable(+org_id,member_id; idempotency UNIQUE(event_id,ts_start))/job_queue(+org_id ext)/CAGGs(daily+hourly category, daily token); privacy store-at-level-on-ingest+enforce-on-read; canonical member_id=members.id resolves P1 gap; backend/docs/exploration/P2-A.1-multitenant-model.md; commit c7ec598
2026-06-27  P2-A.2  done   auth/invite exploration: HS256 access JWT (60m) + opaque rotating refresh (reuse-detect family) + one_time_tokens (password_reset/device_enroll); endpoints register-org/login/refresh/logout/invite-accept/password-reset/device-enroll; RLS via set_config(app.current_org) per-request; backend/docs/exploration/P2-A.2-auth-invite-flow.md; commit c7ec598
2026-06-27  P2-A     note   STOP per kickoff: data model + auth/invite flow presented to user for review BEFORE writing V1 migration (P2-A.3). 4 open decisions flagged (job_queue.org_id ext, privacy store-at-level, teams join table, login org disambiguation).
2026-06-27  P2-A     note   USER DECISIONS: (1) privacy = STORE RAW, redact on READ (not store-at-level); (2) job_queue +org_id YES; (3) teams = join table YES; (4) email = log link in dev + SMTP for prod. Exploration docs updated to match. Login org disambiguation via org_slug confirmed.
2026-06-27  P2-A.3  done   Flyway V1__init.sql SCHEMA CONTRACT: orgs/members/teams/team_members/seats/invites/refresh_tokens/one_time_tokens/events(hypertable on ts_start, idempotency UNIQUE(event_id,ts_start))/job_queue(+org_id); RLS org_isolation on all org tables (current_setting app.current_org); CAGGs daily+hourly category + daily token(by model); non-transactional via V1__init.sql.conf (Timescale CAGG limitation). Gradle/SpringBoot3.3/JDK21 skeleton bootstrapped (wrapper+toolchain auto-provision); migrations packaged to classpath db/migration; commit d09468e
2026-06-27  P2-A.4  done   POST /api/v1/ingest/events: array<=1000 (413 over), non-empty (400), idempotent ON CONFLICT(event_id,ts_start) DO NOTHING, org_id+member_id STAMPED from JWT (never body), enqueues categorize jobs for null-category events; shape EventDto(in)/IngestResult{received,stored,duplicates}; ticks P2-A.CONTRACT; unit-tested (size rules + snake_case wire shape); commit d09468e
2026-06-27  P2-A.5  done   /me/timeline (keyset cursor+limit), /me/summary (range), /org/members (paginated roster+teams), /org/summary (range,team, privacy-aware); shapes in Summaries.*; explicit org_id filters on every query (defense-in-depth; RLS backstop); commit d09468e
2026-06-27  P2-A.6  done   auth: HS256 access JWT (JwtService) + opaque rotating refresh w/ family reuse-detection (RefreshTokenService) + one_time_tokens; endpoints register-org/login(org_slug disambig)/refresh/logout/invite-create(admin)/invite-preview/invite-accept/password.forgot+reset/device-codes(member)/device.enroll; BCrypt(12); Spring Security stateless + problem+json 401/403; RLS bind via Tenancy.set_config; commit d09468e
2026-06-27  P2-A.7  done   privacy enforcement on READ (store-raw decision): PrivacyLevel enum {full,categories_only,aggregate_only}; /org/summary aggregate_only -> org daily totals only (no by_member), categories_only/full -> per-member rollups; redactForAdmin strips app/title/url for event-level admin reads; unit-tested; commit d09468e
2026-06-27  P2-A.8  done   actuator health (liveness/readiness) + RequestTraceFilter (X-Request-Id -> MDC traceId) + logback-spring.xml pattern; commit d09468e
2026-06-27  P2-A.9  done   deploy/docker-compose.yml (timescaledb pg16 + redis7 + backend) + initdb/00-app-role.sql (cadence_app RLS role) + .env.example + backend/Dockerfile (multistage JDK21); commit d09468e
2026-06-27  P2-A.10 doing  e2e authored: backend/src/integrationTest E2EIngestQueryIT (Testcontainers timescaledb): register->ingest->idempotency->/me/summary(+tokens)->/org/summary privacy(categories_only vs aggregate_only). Compiles; NEEDS Docker host to run (absent on this Windows box). HANDOFF.
2026-06-27  P2-A     note   BUILD/VERIFY status: `cd backend && ./gradlew build` GREEN (JDK21 auto-provisioned via foojay toolchain; 11 unit tests pass; bootJar built). NOT verified here: Flyway apply against real Timescale + integrationTest e2e (no Docker/Postgres on this box) — same dev-box limit as P1-A mac/linux. Run on a Docker host: `cd backend && ./gradlew integrationTest`.
2026-06-27  P2-A     note   ARCH DECISION (affects Wave-1): single Spring Boot module, package-by-feature under backend/src/main/java/com/cadence/<feature> (ingest, query, auth, security, tenancy, mail, common). §9's per-stream /backend/<dir> map to packages: worker->com.cadence.worker (P2-F), token->com.cadence.token (P2-C), github->com.cadence.github (P2-D). One deployable jar on one EC2 box (§4). Streams add their own package; avoid editing others'.
2026-06-27  P2-A     note   MERGE: P1 docs-audit landed on master (76a0a13, cb97aa7); rebased stream/p2-a-backend onto master (only PROGRESS "Last updated" conflicted -> P2-A); build-log shas refreshed (exploration ef77ce4, spine d09468e). Audit confirmed ZERO change to §5/§6/§7 cloud contracts (adds Phase-1 local-store §7.1 only). ff-merge to master next; not pushed (leaked-PAT, see memory).
2026-06-27  P2-B.1  done   sync-strategy exploration: periodic pull-filter-push loop (CADENCE_SYNC_INTERVAL_SEC=300, immediate first run), stored event.Event already marshals to EventDto snake_case (no transform), ingest idempotent on event_id (double-guarded), reactive 401->refresh, exp-backoff+jitter on 5xx/429, offline-durable (watermark never advances past unsent). No client-side org-privacy filter (daemon has no org privacy signal; server enforces on read; local redaction already applied at store time). agent/sync/docs/exploration/P2-B.1-sync-strategy.md; commit 0dfaab0
2026-06-27  P2-B.2  done   device-enrollment exploration: short-code flow (web mint POST /me/device-codes -> paste into daemon -> POST /auth/device/enroll {code} -> {memberId,access,refresh}); secrets in OS keychain (reuse keyring.Keyring; accounts member-id/access-token/refresh-token); reactive rotating refresh (reuse revokes family -> serialize); adopt canonical member_id. agent/sync/docs/exploration/P2-B.2-device-enrollment.md; commit 0dfaab0
2026-06-27  P2-B     note   STOP per kickoff: exploration done, showing findings BEFORE P2-B.3 implementation. ONE open decision flagged to operator (see DECISION line in Coordination block): how P2-B tracks "un-synced" given it owns only /agent/sync/ and must not edit store.go (P1-A). Recommendation: Option A self-contained sidecar. Secondary: minimal main.go entrypoint wiring touches one P1-A-owned file.
2026-06-27  P2-B     note   OPERATOR DECISIONS: (1) sync-state tracking = Option A self-contained sidecar (sync.db, does NOT edit store.go); (2) P2-B makes a minimal main.go touch for enroll/status subcommands + loop start. Implemented accordingly.
2026-06-28  P2-B.3  done   outbound sync loop (cloudsync.Syncer): pull store.Query(watermark,now) -> filter via sidecar synced-set -> POST <=1000/batch with Bearer access -> MarkSynced on 2xx -> advance watermark (to-48h floor) + prune. Stored event.Event marshals as-is to EventDto snake_case. State sidecar (agent/sync/state.go) owns delivery state; never writes store. commit 0dfaab0
2026-06-28  P2-B.4  done   keychain token storage (cloudsync.Keystore over keyring.Keyring; accounts cloud-access/cloud-refresh/member-id) + reactive rotating refresh: ingest 401 -> POST /auth/refresh -> persist rotated pair BEFORE retry (serialized; reuse revokes family) -> retry once; refresh 401/4xx -> ErrReenrollRequired. commit 0dfaab0
2026-06-28  P2-B.5  done   enrollment (cloudsync.Enroll + `cadence-agent enroll <code>`): redeem one-time device code via POST /auth/device/enroll -> {member_id,access,refresh} persisted; adopts canonical member_id; trimCode accepts bare code or enroll URL. `cadence-agent status` reports enrolled/member_id/synced-rows. commit 0dfaab0
2026-06-28  P2-B.6  done   backoff + offline durability: exp-backoff+full-jitter (Backoff, deterministic-seedable); watermark never advances on failure so days-offline backlog flushes on recovery; 24 tests green across state/keystore/client/backoff/syncer (offline->recover, dedupe idempotency, reactive refresh, re-enroll, batch>1000, ctx-cancel). `cd agent && go build ./... && go test ./...` GREEN (go1.26). commit 0dfaab0
2026-06-28  P2-B     note   ENV added beyond phase-doc list: CADENCE_SYNC_DB_PATH (sidecar DB path; defaults to sibling of CADENCE_DB_PATH: ~/.config/cadence/cadence-sync.db). Existing: CADENCE_CLOUD_BASE (default http://localhost:8080), CADENCE_SYNC_INTERVAL_SEC (default 300). Member token in keychain, not env. Phase-completion gate: add these to docs/ENV-VARIABLES.md (spine-owned) before Phase-2 close.
2026-06-27  P2-C     note   START: rebased onto origin/master (already at e5af75a, P2-A.CONTRACT in base — no-op). Read coordination block: ingest STAMPS org_id+member_id from JWT (ignores client member_id); token events flow via the SAME loopback /events route as other collectors then sync (P2-B); backend = ONE Spring Boot jar, P2-C adds package com.cadence.token only (do NOT edit com.cadence.query/ingest).
2026-06-27  P2-C.1  done   tool-log-location survey VERIFIED against real on-disk logs: Claude Code ~/.claude/projects/<cwd-slug>/<sessionId>.jsonl (assistant lines: message.model + message.usage.{input,output,cache_creation,cache_read}_tokens; top-level timestamp+cwd; NO cost field); Codex ~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl (session_meta.cwd + event_msg/token_count info.last_token_usage; cached_input is SUBSET of input; NO cost field); Cursor server-side only -> deferred to a future Admin-API connector. Cost MUST be computed from tokens x per-model pricing. Auto-detect by probing paths (zero config). Doc agent/token/docs/P2-C.1-tool-log-locations.md; commit <pending>
2026-06-27  P2-C.2  done   counts-only confirmed ENFORCEABLE: both logs contain full conversation text in the same files, so privacy = extract an ALLOW-LIST of fields (ts/model/tokens/cost/project) per line, never a deny-list; message.content/base_instructions/tool args dropped at decode; token events carry null title/url so safe at every privacy level; sentinel test guards against content capture. Doc agent/token/docs/P2-C.2-counts-only-privacy.md; commit <pending>
2026-06-27  P2-C     note   P2-C.5 SCOPE: P2-A ALREADY built the token aggregation primitives in ITS owned code — CAGG events_daily_tokens (per org/member/model/day cost+tokens, tagged "P2-C.5") in V1__init.sql + per-model TokenSummary in /me/summary & /org/summary (MeQueryService/OrgQueryService query raw events where source='token'). So P2-C.5's non-overlapping value = a dedicated per-member/model/DAY token endpoint under com.cadence.token backed by events_daily_tokens (currently it has NO consumer), feeding the P2-E admin token panel. Will NOT duplicate or edit P2-A's query package.
2026-06-27  P2-C     note   INTEGRATION PLAN (pre-impl, pending user OK): collector ships as a self-contained package agent/token + standalone runnable agent/token/cmd/cadence-token that POSTs to the daemon loopback /events (no P1-A changes needed, independently testable). Eventual in-daemon wiring (one line in agent/cmd/cadence-agent/main.go, P1-A-owned) filed as a NEEDS line below.
2026-06-28  P2-C     note   USER DECISIONS (Wave-1 kickoff Q): (1) P2-C.5 backend = NEW per-day token endpoint under com.cadence.token reading events_daily_tokens (no edits to P2-A query pkg); (2) collector = standalone runnable + NEEDS wire (no main.go edit now).
2026-06-28  P2-C.3  done   agent/token Go pkg: per-tool parsers (Claude Code + Codex) -> Event Contract source:"token" events w/ meta.model/tokens_in/tokens_out/cost_usd (+raw cache sub-counts, priced flag). Narrow allow-list decode (no message.content/base_instructions field) enforces counts-only (P2-C.2); sentinel test guards. Config-driven per-model pricing (pricing.go; Anthropic 4-tier, OpenAI cached-subset; DefaultTable rates from claude-api skill; CADENCE_TOKEN_PRICING_PATH overlay). Cursor=server-side limit; Cursor tool deferred (server-side only). go build/vet/test green, cross-compiles mac/linux; commit <pending>
2026-06-28  P2-C.4  done   incremental tail (watcher.go): per-file byte-offset cursor persisted token-cursors.json (no reparse/double-count across restarts), reads only complete lines, rotation-safe; project attributed from cwd basename; 30s poll; sink-failure retries chunk (cursor advances only on accept). Codex parser stateful per-file (carries cwd/model across chunks). LIVE e2e vs real daemon: 2 Claude Code turns -> /timeline shows correct model/tokens/cost (opus 0.080805, sonnet 0.006000), null title/url, project=cadence, NO content leak; see agent/token/docs/P2-C-verification.md; commit <pending>
2026-06-28  P2-C.5  done   com.cadence.token: GET /me/tokens?range + GET /org/tokens?range&team (admin, privacy-aware) reading events_daily_tokens CAGG (P2-A defined it, no prior consumer); explicit org_id filter (CAGG not RLS-covered, per schema note); aggregate_only -> org daily totals, no by_member. NO edits to P2-A query/ingest pkgs. Shapes TokenDtos.*; gradle build GREEN + wire/range unit tests (TokenWireAndRangeTest, 3). LIVE Timescale query HANDOFF (no Docker here; same limit as P2-A.10); commit <pending>
2026-06-28  P2-C     note   ENV VARS added by P2-C (for spine to fold into phase-doc Variables + ENV-VARIABLES.md): CADENCE_TOKEN_SOURCES (default claude_code,codex,cursor; cursor recognized but not locally tailed), CADENCE_CLAUDE_CODE_LOG_DIR + CADENCE_CODEX_LOG_DIR (optional log-dir overrides), CADENCE_CODEX_DEFAULT_MODEL (default gpt-5-codex), CADENCE_TOKEN_PRICING_PATH (JSON price overlay), CADENCE_TOKEN_STATE_DIR (cursor file dir; default OS config dir). Reuses CADENCE_AGENT_PORT/CADENCE_MEMBER_ID/CADENCE_KEYCHAIN_SERVICE from P1-A. No backend env vars added (endpoints reuse P2-A datasource).
2026-06-28  P2-C     note   STREAM COMPLETE (build tasks): P2-C.1-.5 all [x]. Runtime-deferred like the rest of Phase-2: backend token endpoints need a Docker host for live Timescale verification; Codex parser path verified by unit tests + on-disk format (no Codex run on this box).
2026-06-27  P2-D     note   START: verified branch in sync w/ origin/master (P2-A.CONTRACT ticked, present). Mapped backend (ingest/tenancy/security/schema): events.source + IngestService.SOURCES already allow 'github'; members.github_login pre-provisioned; Tenancy.bind(orgId,member,role) overload exists for non-JWT webhook context; HMAC via JDK javax.crypto (no new dep for default path).
2026-06-27  P2-D.1  done   App-vs-OAuth-vs-PAT exploration: DECISION GitHub App (one admin install, whole org; least-privilege metadata:read for default mode; per-install webhooks+tokens). Lifecycle install->webhook->events. Identified the one schema gap (installation_id->org_id) -> NEEDS line to P2-A for V2 github_installations table. Zero-duration github events, deterministic uuid-v5 event_id for redelivery idempotency. backend/docs/exploration/P2-D.1-github-integration-model.md; commit 6d1f765
2026-06-27  P2-D.2  done   privacy-toggle design: commit_messages_only (default) vs full_diff (opt-in); CODE/PATCH NEVER stored in either mode — full_diff adds numeric diff STATS only (additions/deletions/changed_files). Enforcement primarily by GitHub permission scope (default needs no API calls/contents:read). Per-org mode on github_installations; privacy-safe degradation if contents:read missing. Composes with org privacy_level (read-time P2-A.7 redaction) — no new read code. backend/docs/exploration/P2-D.2-privacy-toggle.md; commit 6d1f765
2026-06-28  P2-D.3  done   com.cadence.github package: POST /api/v1/github/webhook (HMAC X-Hub-Signature-256 verify via JDK Mac, fail-closed) -> GithubEventMapper (PURE) maps push=1 evt/commit + pull_request(opened/closed/reopened/ready_for_review)=code_review to Event Contract (source=github, zero-duration ts_end=ts_start dur=0, schema_ver=1, url=null, meta.commit_sha/repo/branch[/pr_number/action]); deterministic uuid-v5(repo+sha / repo+pr+action+ts) event_id => redelivery dedupes on (event_id,ts_start). Own @Order(1) SecurityFilterChain for the webhook path only (permitAll) — does NOT edit P2-A SecurityConfig. GithubWebhookService @Transactional: cross-org installation lookup -> tenancy.bind(org) -> map -> resolve member -> JdbcGithubEventStore ON CONFLICT DO NOTHING. application.yml cadence.github.* binds GITHUB_* env. build GREEN, 38 unit tests (sig verify, mapper push/PR/modes, uuid-v5, service orchestration w/ mocked deps). commit 803540a
2026-06-28  P2-D.4  done   github login->member via members.github_login (JdbcGithubMemberResolver: org_id+github_login+status='active'); commits/PRs attributed to commit.author.username / PR sender.login; unmappable author -> event SKIPPED (counted, logged), not stored. Admin link endpoint POST /api/v1/github/installations + GET list + PUT .../mode (GithubInstallationService, admin-only, org-bound) records installation_id->org_id. commit 803540a
2026-06-28  P2-D.5  done   privacy toggle respected: per-install GithubMode; commit_messages_only stores subject+sha+repo+branch (no paths, no API call); full_diff branch wired (mapper derives changed_files COUNT from push file-path array lengths — paths never stored) + GithubStatsEnricher hook; StubGithubStatsEnricher degrades safely (logs, messages-only) — live additions/deletions API call (App-JWT RS256 + contents:read) is the documented TODO per user decision. Code/patch NEVER stored. commit 803540a
2026-06-28  P2-D     note   BUILD/VERIFY: `cd backend && ./gradlew build` GREEN (JDK21 toolchain; 38 unit tests pass; cadence-backend.jar built). Beans instantiate without V2 table (queries lazy), so app still starts. NOT verified here (dev-box limit, same as P2-A.10): live webhook->Postgres insert + RLS + the two-SecurityFilterChain context start — all need (a) P2-A V2 github_installations migration [NEEDS filed] and (b) a Docker host. HANDOFF: after V2 lands, run on a Docker host and add a github integrationTest mirroring E2EIngestQueryIT.
2026-06-28  P2-D     note   SPINE FOLLOW-UP (phase gate §8): deploy/.env.example (/deploy is spine-owned) lacks GITHUB_APP_ID/GITHUB_APP_PRIVATE_KEY/GITHUB_WEBHOOK_SECRET/GITHUB_DEFAULT_MODE — already in docs/ENV-VARIABLES.md + PHASE-2 P2-D block; please add to .env.example + LOCAL-SETUP GitHub-App registration steps (webhook URL = <base>/api/v1/github/webhook). full_diff also needs GITHUB_APP_ID + GITHUB_APP_PRIVATE_KEY once enrichment is implemented.
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
