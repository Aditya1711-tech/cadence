# PROGRESS — living tracker

> **This file is the source of truth for state.** Update it on the go (see
> `02-PROGRESS-PROTOCOL.md`). States: `[ ]` todo · `[~]` doing · `[x]` done ·
> `[!]` blocked. Every `[x]` must be committed. Resuming sessions read this file
> and the Build Log only — never the whole codebase.

Last updated: 2026-06-29  ·  by stream: P3-C (NL query — stream complete; merged to master)

---

## Contract checkpoints (gates for launching parallel waves)

- [x] `P1-A.CONTRACT` — Event Contract frozen in code (Go structs + JSON, golden sample, tests green); local routes land in P1-A.5 (unblocks P1-B/C/D)
- [x] `P2-A.CONTRACT` — ingest + query + schema frozen (unblocks P2-B/C/D/E/F). Schema = backend/migrations/V1__init.sql; ingest shape = EventDto/IngestResult; query shapes = Summaries.*; auth shapes = AuthDtos.*. Backend compiles (JDK21 toolchain) + 11 unit tests green; full DB e2e is authored (Testcontainers, P2-A.10) and runs on a Docker host. Frozen at code/SQL/doc level — safe to launch Wave-1.
- [x] `P3-A.CONTRACT` — aggregated-fact shape frozen (unblocks P3-B/C/E). Shape = MemberWeekFacts/OrgWeekFacts (backend/insights/docs/P3-A.1) stored in insights/digests (V3__insights_digests.sql); cadence_readonly role created (deploy/initdb/01-readonly-role.sql) for P3-C. Documented additive in 00-SYSTEM-KNOWLEDGE.md §6/§7. Safe to launch P3-B/C/E.

---

## Coordination block (NEEDS lines)
```
(none yet — add cross-stream requests here, e.g.)
NEEDS  P2-E -> P2-A : /api/v1/org/summary returns per-category daily buckets

RESOLVED  P3-A -> P3-C : cadence_readonly DB role created in
          deploy/initdb/01-readonly-role.sql — SELECT-only, non-owner,
          RLS-enforced, org-scoped (the role CADENCE_NLQUERY_DB_ROLE already
          referenced but nothing created). P3-C connects a separate datasource as
          cadence_readonly and sets app.current_org per request (same door as
          cadence_app); RLS is the hard backstop behind the text-to-SQL
          allowlist. Fresh-init only (initdb) — drop/recreate the dev volume to
          pick it up; same limit as cadence_app.
HANDOFF  P3-C live verification needs the cadence_readonly role, which
          materializes on fresh DB volume at deploy. The NL-query path connects a
          SEPARATE datasource as cadence_readonly (never the owner/app
          connection) and is gated CADENCE_NLQUERY_ENABLED=false by default, so
          build+unit tests are green without it. At deploy on a fresh volume: set
          CADENCE_NLQUERY_ENABLED=true + ANTHROPIC_API_KEY + the readonly
          datasource (CADENCE_NLQUERY_DB_URL/USER/PASSWORD), then run the authored
          Docker IT (cross-org SELECT returns 0 rows; INSERT/UPDATE denied; row
          cap truncates) — mirrors E2EIngestQueryIT. Belt-and-suspenders follow-up
          (optional NEEDS -> spine): tighten cadence_readonly's SELECT grant from
          ALL TABLES to just the NL allowlist so password_hash/token-hashes are
          ungrantable at the DB layer too (today the app-layer allowlist carries
          that within-org guard).
DECISION  P3-C (operator-approved) : (1) SQL validation = JSqlParser fail-closed
          structural allowlist (parse, single SELECT, walk tables+columns, reject
          off-allowlist/parse-error) + token denylist as a coarse belt — regex
          alone NOT acceptable for the gate; (2) NL query is ADMIN-ONLY for v1
          (RLS is org- not member-scoped). Member-scoped self-serve (a hard
          member_id predicate so a member can query only their OWN data) is a
          deliberate LATER addition, not v1.
NOTE   P3-A -> P3-B/C/E : aggregated-fact contract FROZEN (V3 insights/digests +
          MemberWeekFacts/OrgWeekFacts, grain column). P3-B reads the `insights`
          table (facts jsonb + denormalized scalars: deep_work_h, meeting_h,
          token_cost_usd, commits, fragmentation_index; one row per
          org/member/iso_week/grain). Facts are built from the EXISTING CAGGs +
          raw events + the /org/summary commit-facet code path — NO new CAGG, NO
          new commits source. fragmentation_index is SQL-derived (project context
          switches per focused hour over deep_work+code_review+ai_assisted+
          research; >30min gap = session boundary; SATURATION=4.0). P3-E reads
          the same token rollups (events_daily_tokens). Org grain is
          privacy-bounded (top_contributors omitted under aggregate_only). Shape
          ref: 00-SYSTEM-KNOWLEDGE.md §6 + backend/insights/docs/
          P3-A.1-aggregated-fact-shape.md, P3-A.2-delivery-and-card.md.
NEEDS  P3-B -> P3-A : attach pattern findings to the digest. Call
          com.cadence.insights.pattern.PatternService.forMember(p,range) /
          forOrg(p,range) in the fact-builder/narrator and set the ADDITIVE
          `patterns` array on MemberWeekFacts/OrgWeekFacts (shape doc'd §6 +
          backend/insights/pattern/docs/P3-B.1). NOTE on integration: P3-B does
          NOT read the `insights` table (contra the P3-A NOTE above) — it computes
          findings from the SAME sources the facts use (events_hourly/daily_by_
          category CAGGs + the P3-A §3.2 raw fragmentation), so there is NO ordering
          dependency on P3-A.5 populating `insights`. P3-A just grafts the returned
          List<Finding> onto facts.patterns; existing readers ignoring it are
          unaffected (additive, same as the commits facet).
NEEDS  P3-B -> P2-E : render pattern findings in the admin overview (a "What we
          noticed" card). Read GET /api/v1/insights/patterns?scope=org&range (admin;
          BFF proxy like the other /api/org/* routes) OR facts.patterns once P3-A
          wires it. Payload = {grain,history_days,low_confidence,findings[]} with
          findings[{kind,title,detail,confidence,strength,evidence}] (§6). Show
          nothing when low_confidence=true (honest empty, like the commit panel).

OPEN   P2-C -> P1-A : wire the token watcher into the daemon — one call in
          agent/cmd/cadence-agent/main.go (P1-A-owned) to start the token
          collector alongside startCollector(). INTERIM: P2-C ships a
          standalone runnable agent/token/cmd/cadence-token that POSTs to the
          same loopback /events route, so no P1-A change blocks P2-C. Adopt the
          in-daemon wire when P1-A resumes (mac/linux handoff session).
RESOLVED  P2-D -> P2-A : V2 migration for installation->org mapping. Written as
          backend/migrations/V2__github_installations.sql (spine GitHub follow-up):
          github_installations(id, org_id->orgs CASCADE, installation_id bigint
          UNIQUE, account_login, mode default 'commit_messages_only' CHECK
          in ('commit_messages_only','full_diff'), suspended_at, created_at) +
          idx_github_inst_org + RLS enable w/ org_isolation policy on org_id
          (not FORCEd; cross-org webhook lookup rides the owner connection, same
          door as auth). Plus the nice-to-have partial UNIQUE(org_id,github_login)
          WHERE github_login IS NOT NULL on members, and idx_events_org_source_ts
          to back the /org/summary commit facet. Runs transactionally (no CAGGs;
          no .conf). APPLY + live e2e gated on a Docker host (see HANDOFF).
HANDOFF  P2-D live GitHub App registration + webhook e2e — do at the end-of-phase
          AWS deploy step (needs a public backend URL for the webhook). Register
          the Cadence GitHub App (least-privilege: Repository permission
          Metadata:Read-only for the default commit_messages_only mode — zero code
          access; add Contents:Read-only ONLY for full_diff). Subscribe events:
          Push, Pull request, Installation, Installation repositories. Webhook URL
          = <backend-base>/api/v1/github/webhook; set GITHUB_WEBHOOK_SECRET (the
          only credential the default path needs) and, for full_diff only,
          GITHUB_APP_ID + GITHUB_APP_PRIVATE_KEY (base64 PEM). Then on a Docker
          host: apply V2, link an installation (POST /api/v1/github/installations),
          push to a selected repo, confirm source='github' events land and the
          /org/summary commits facet counts them. Mirror E2EIngestQueryIT.

RESOLVED  P2-E -> P2-A : /org/summary returns org_by_day[] (per-day per-category
          buckets) + org_totals_by_category[] + by_member[] (privacy-bounded).
          Shape = com.cadence.query.Summaries.OrgSummary. (P2-A.5)
NEEDS  P2-E -> P2-A : an admin-guarded endpoint to SET orgs.privacy_level (e.g.
          PATCH /api/v1/org/settings {privacyLevel}). Today the level is only
          READABLE (AuthResponse.org.privacyLevel); P2-E.4 privacy-level control
          needs to change it. INTERIM: privacy control is read-only in the UI.
RESOLVED  P2-E -> P2-A (+depends P2-D) : commit activity in /org/summary. Added
          as an ADDITIVE `commits` facet on Summaries.OrgSummary (P2-D, spine
          GitHub follow-up): { total, by_day:[{date,count}],
          by_member:[{member_id,display_name,count}] } counting source='github'
          commit events (meta.commit_sha present; PR/code_review excluded).
          by_member omitted under aggregate_only; org-level total/by_day at every
          level. Wire shape documented in 00-SYSTEM-KNOWLEDGE.md §6. P2-E.5 +
          P3-A: read commits from /org/summary (one rollup, no second endpoint);
          drop the "GitHub not connected" interim once an installation is linked.
NOTE   P2-E -> spine : env correction — phase-doc P2-E var NEXT_PUBLIC_API_BASE
          is build-time-inlined by Next and can't be set per deploy. The admin app
          talks to the backend SERVER-SIDE via a BFF proxy (no CORS change needed),
          so it uses the RUNTIME var CADENCE_API_BASE (default http://localhost:8080).
          Same correction P1-D made for the agent base. Please update the P2-E
          "Variables to set" block in docs/PHASE-2-cloud-org.md.
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
CONFIRMED P2-F -> P2-A : payload {"event_id","ts_start"} + row org_id is SUFFICIENT
          (it is exactly the events PK + the RLS key). No extension requested. (P2-F.1)
NEEDS  P2-F -> P2-A : extend ingest to ALSO enqueue a categorize job when an event
          arrives with category == 'other' (not only null). The device rule
          classifier defaults unmatched events to 'other', so a null-only trigger
          never surfaces the low-confidence events P2-F exists to handle. One-line
          change to IngestService.enqueueCategorize's guard (category null OR
          'other'). NON-BLOCKING for P2-F: the worker re-checks category on claim
          and only calls the LLM for null/other, so it is correct under either
          trigger; this NEEDS only changes throughput. Rationale: P2-F.1 doc.
NEEDS  P2-F -> P2-A : add a SECURITY DEFINER claim function to the migrations
          (P2-F can't write migrations). Claiming categorize jobs is CROSS-ORG, so
          it can't run under RLS with a single org bound (default-deny when
          app.current_org unset). Exact SQL (also reclaims stale 'running' locks +
          GRANT EXECUTE to cadence_app) is in backend/docs/P2-F-worker.md
          "DEPENDENCY". Per-job reads/writes afterwards run as cadence_app with org
          bound (RLS enforced). BLOCKS the worker's LIVE path only; build/tests are
          green without it (worker logs a claim warning and idles).
NEEDS  P2-F -> P2-A (deploy) : wire CADENCE_CATEGORIZE_ENABLED=true + ANTHROPIC_API_KEY
          (and optional CADENCE_CATEGORIZE_*) into the backend service in
          deploy/docker-compose.yml. Redis 7 is already present (P2-A.9); the
          worker reads spring.data.redis.url from REDIS_URL.
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

NEEDS  P3-E -> P3-A : add a migration for the two budget-monitor tables (P3-E
          can't write backend/migrations/). Exact DDL is in
          backend/insights/budget/docs/P3-E.1-anomaly-and-dedupe.md §3:
          (1) budget_alert_config (one row/org: enabled, spike_multiplier,
          min_absolute_usd, baseline_days, min_history_days, tiers[], channel,
          slack_webhook_url, alert_email, quiet_hours_start/_end, timezone,
          mute_until) and (2) budget_alerts (dedupe ledger, UNIQUE(org_id,
          subject_type, COALESCE(subject_id,…), day, severity)). Both org-scoped
          with RLS org_isolation, same as every org-scoped table. NON-BLOCKING:
          the budget loop catches a missing table, logs once, and skips — backend
          builds/boots green without it (mirrors the P2-F claim-fn pattern).

NOTE   P3-E -> ALL : budget-alert thresholds are PROVISIONAL until we have ≥2
          weeks of live token data to calibrate against. The detector ships now;
          the absolute floor (min_absolute_usd, default $10 ≈ a normal heavy
          active-dev day) gets retuned at/after deploy. ALL thresholds are config
          (CADENCE_BUDGET_* env defaults + per-org budget_alert_config overrides),
          never hardcoded. 3× ratio and [3,5,10] severity tiers stay as-is for now.
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

**Exit-criteria status (gate to Phase 3) — audited 2026-06-28 (docs audit):**
- [~] New org can self-register, invite members, **set a privacy level** —
  **PARTIAL.** Self-register (`POST /auth/register-org`) + invite
  (`POST /org/invites` → `/auth/invite/accept`) work. **Set privacy: NOT MET** —
  no endpoint mutates `orgs.privacy_level`; it's fixed at the server default and
  only readable. NEEDS P2-E→P2-A (setter, e.g. `PATCH /org/settings`) is open.
- [~] Daemon syncs events; admin sees team rollups honoring privacy —
  **CODE-COMPLETE, runtime-unverified.** P2-B sync + P2-A read path are built and
  privacy-aware; full live e2e needs a Docker host (P2-A.10 still `[~]`).
- [!] Token spend + GitHub commit activity visible — **SPLIT.** Token spend
  per member/model/day is built (`events_daily_tokens`, `/me/tokens`,
  `/org/tokens`). **GitHub commits: NOT MET** — webhook code exists but the
  `V2 github_installations` migration is unwritten (webhook can't resolve an org,
  so no github events store) and no commit data is surfaced in `/org/summary`
  (admin UI shows "GitHub not connected").
- [!] 3 pilot companies onboarded, zero manual DB work — **NOT MET.** Two
  owed migrations remain manual-DB gaps the spine still owes: `V2
  github_installations` (P2-D) and `claim_categorize_jobs()` SECURITY DEFINER
  (P2-F, worker idles without it). No live pilots onboarded (runtime deferred —
  no Docker host on this dev box).

**Verdict: Phase 2 is code-complete on nearly all stream tasks but NOT fully
gated.** Concrete remaining spine (P2-A) work before the Phase-3 gate: (1) the
privacy-level setter endpoint, (2) `V2 github_installations` + surfacing commits
in `/org/summary`, (3) `claim_categorize_jobs()` migration, (4) run the authored
e2e (P2-A.10) + onboard pilots on a Docker host. All four were already filed as
NEEDS/HANDOFF; this audit only confirms them against the as-built code.

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
- [x] P2-D.3 GitHub App webhook → events (code+unit-tested; V2 migration written; live e2e gated on Docker host)
- [x] P2-D.4 map github login → member
- [x] P2-D.5 respect toggle (full_diff stats-enrichment API call stubbed; default mode complete)
- [x] P2-D.6 V2 github_installations migration + /org/summary commit facet (spine GitHub follow-up; build+unit green; APPLY+e2e Docker handoff)

### P2-E — org admin dashboard
- [x] P2-E.1 explore admin needs (trust-first)
- [x] P2-E.2 onboarding flow UX
- [x] P2-E.3 auth pages
- [x] P2-E.4 roster + invites + privacy control
- [x] P2-E.5 team summary (heatmap/tokens/commits)
- [x] P2-E.6 member drilldown (privacy-bounded)
- [x] P2-E.7 install instructions page

### P2-F — categorisation worker
- [x] P2-F.1 explore escalation rules
- [x] P2-F.2 prompt design (fixed enum out)
- [x] P2-F.3 worker claims jobs + LLM call + write-back (build+unit green; live e2e deferred — no Docker/Redis/API key here)
- [x] P2-F.4 pattern cache
- [x] P2-F.5 cost guardrails + metrics

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
2026-06-27  P2-C.1  done   tool-log-location survey VERIFIED against real on-disk logs: Claude Code ~/.claude/projects/<cwd-slug>/<sessionId>.jsonl (assistant lines: message.model + message.usage.{input,output,cache_creation,cache_read}_tokens; top-level timestamp+cwd; NO cost field); Codex ~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl (session_meta.cwd + event_msg/token_count info.last_token_usage; cached_input is SUBSET of input; NO cost field); Cursor server-side only -> deferred to a future Admin-API connector. Cost MUST be computed from tokens x per-model pricing. Auto-detect by probing paths (zero config). Doc agent/token/docs/P2-C.1-tool-log-locations.md; commit b0bbc8e
2026-06-27  P2-C.2  done   counts-only confirmed ENFORCEABLE: both logs contain full conversation text in the same files, so privacy = extract an ALLOW-LIST of fields (ts/model/tokens/cost/project) per line, never a deny-list; message.content/base_instructions/tool args dropped at decode; token events carry null title/url so safe at every privacy level; sentinel test guards against content capture. Doc agent/token/docs/P2-C.2-counts-only-privacy.md; commit b0bbc8e
2026-06-27  P2-C     note   P2-C.5 SCOPE: P2-A ALREADY built the token aggregation primitives in ITS owned code — CAGG events_daily_tokens (per org/member/model/day cost+tokens, tagged "P2-C.5") in V1__init.sql + per-model TokenSummary in /me/summary & /org/summary (MeQueryService/OrgQueryService query raw events where source='token'). So P2-C.5's non-overlapping value = a dedicated per-member/model/DAY token endpoint under com.cadence.token backed by events_daily_tokens (currently it has NO consumer), feeding the P2-E admin token panel. Will NOT duplicate or edit P2-A's query package.
2026-06-27  P2-C     note   INTEGRATION PLAN (pre-impl, pending user OK): collector ships as a self-contained package agent/token + standalone runnable agent/token/cmd/cadence-token that POSTs to the daemon loopback /events (no P1-A changes needed, independently testable). Eventual in-daemon wiring (one line in agent/cmd/cadence-agent/main.go, P1-A-owned) filed as a NEEDS line below.
2026-06-28  P2-C     note   USER DECISIONS (Wave-1 kickoff Q): (1) P2-C.5 backend = NEW per-day token endpoint under com.cadence.token reading events_daily_tokens (no edits to P2-A query pkg); (2) collector = standalone runnable + NEEDS wire (no main.go edit now).
2026-06-28  P2-C.3  done   agent/token Go pkg: per-tool parsers (Claude Code + Codex) -> Event Contract source:"token" events w/ meta.model/tokens_in/tokens_out/cost_usd (+raw cache sub-counts, priced flag). Narrow allow-list decode (no message.content/base_instructions field) enforces counts-only (P2-C.2); sentinel test guards. Config-driven per-model pricing (pricing.go; Anthropic 4-tier, OpenAI cached-subset; DefaultTable rates from claude-api skill; CADENCE_TOKEN_PRICING_PATH overlay). Cursor=server-side limit; Cursor tool deferred (server-side only). go build/vet/test green, cross-compiles mac/linux; commit b0bbc8e
2026-06-28  P2-C.4  done   incremental tail (watcher.go): per-file byte-offset cursor persisted token-cursors.json (no reparse/double-count across restarts), reads only complete lines, rotation-safe; project attributed from cwd basename; 30s poll; sink-failure retries chunk (cursor advances only on accept). Codex parser stateful per-file (carries cwd/model across chunks). LIVE e2e vs real daemon: 2 Claude Code turns -> /timeline shows correct model/tokens/cost (opus 0.080805, sonnet 0.006000), null title/url, project=cadence, NO content leak; see agent/token/docs/P2-C-verification.md; commit b0bbc8e
2026-06-28  P2-C.5  done   com.cadence.token: GET /me/tokens?range + GET /org/tokens?range&team (admin, privacy-aware) reading events_daily_tokens CAGG (P2-A defined it, no prior consumer); explicit org_id filter (CAGG not RLS-covered, per schema note); aggregate_only -> org daily totals, no by_member. NO edits to P2-A query/ingest pkgs. Shapes TokenDtos.*; gradle build GREEN + wire/range unit tests (TokenWireAndRangeTest, 3). LIVE Timescale query HANDOFF (no Docker here; same limit as P2-A.10); commit b0bbc8e
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
2026-06-27  P2-E.1  done   admin-needs exploration (trust-first): grounded the UI in the as-built P2-A contract (auth surface, /org/members, /org/summary{orgTotalsByCategory,orgByDay heatmap,byMember rollups+tokens}); see+do list, privacy-as-trust-banner rules; drilldown is the byMember slice (privacy-bounded by construction, no per-event detail); web/admin/docs/01-requirements-exploration.md
2026-06-27  P2-E.2  done   onboarding-flow UX: register->set-privacy->invite(targeted/open link)->member-accept->install+device-code->data-appears; <30min/zero-DB exit criterion; ARCH decision = BFF proxy + httpOnly-cookie session (no CORS change vs spine SecurityConfig; tokens off JS; invisible refresh); self-contained Next app under /web/admin (no P2 web spine exists). 3 gaps filed as NEEDS (set-privacy endpoint, commit-activity field, env var). STOP: present findings to user before P2-E.3 implementation.
2026-06-28  P2-E.3  done   self-contained Next 14 app scaffolded under /web/admin (mirrors P1-D toolchain); BFF + httpOnly-cookie session (lib/api: config/session/backend/problem/auth-bff) w/ invisible refresh-on-401 + cookie rotation; TS contract mirrors (snake_case) of AuthDtos/Summaries; auth routes /api/auth/{login,register,accept,logout,session,invite/[token]}; pages login/register/accept-invite + authenticated (admin) shell (server-read session, nav, privacy banner, logout); middleware route gate. npm run lint + build GREEN (7 routes). USER-CONFIRMED arch=BFF, interim-gaps. commit 1397e9f
2026-06-28  P2-E.4  done   roster page: BFF proxy routes /api/org/{members,invites} (authedFetch via lib/api/proxy); RosterList (paginated roster + Load more, role/status badges, links to drilldown); InvitePanel (targeted email OR open shareable link w/ maxUses/ttlHours+role -> CreateInviteResponse url+copy+expiry); PrivacyControl READ-ONLY (3 levels w/ trade-offs, current highlighted; disabled per NEEDS set-endpoint); shared ui states/badge/copy-button. lint+build GREEN. commit d626226
2026-06-28  P2-E.5  done   team overview: BFF /api/org/summary (range/team passthrough); OverviewBody (range picker 7d default, fetch on change); Heatmap (categories x days, opacity by cell-ms vs busiest, aggregate-only never per-person); CategoryTotals (ranked where-time-went bars); TokenPanel (org tokens aggregated from by_member -> total + per-model; "hidden at aggregate_only" honored); CommitPanel honest "GitHub not connected" stub (NEEDS commit field + P2-D); lib/summary derivations. lint+build GREEN. commit 020428b
2026-06-28  P2-E.6  done   member drilldown /members/[id]: consumes the by_member slice of /org/summary (no new endpoint) -> privacy-bounded BY CONSTRUCTION (category mix + token spend only; never titles/urls/per-event). Header identity from /org/members (limit 1000); reuses CategoryTotals(title) + TokenPanel([rollup]); aggregate_only -> "per-member detail hidden" notice; no-rollup -> "no activity"; back-to-roster + range picker. lint+build GREEN. commit 9d4d242
2026-06-28  P2-E.7  done   install page: BFF /api/me/device-codes (deviceLabel as query, not body, per backend); DeviceCodeCard mints one-time enrollment code (copy + expiry) resolving the P1 member_id gap; 3 numbered steps (install agent / enroll device / editor+browser exts) w/ CodeBlock copy. lint+build GREEN (19 routes). Variables block filled. P2-E STREAM COMPLETE (all build tasks [x]); UI-level verification only — full live e2e needs a running backend+DB (Docker), same dev-box limit as P2-A.10. commit 31e6f10
2026-06-28  P2-E     note   STREAM COMPLETE at UI/build level: /web/admin Next 14 app, lint+build GREEN, all P2-E.1-.7 [x]. NOT live-verified end-to-end (no running Spring backend + Timescale on this Windows box; same limit as P2-A.10 / P1-A mac-linux). HANDOFF: run `cd web/admin && npm ci && npm run build` then point CADENCE_API_BASE at a live backend and walk register->invite->accept->install->overview. 3 interim NEEDS still open (set-privacy endpoint, commit-activity field, env-var doc update) — UI degrades honestly until P2-A/P2-D resolve them.
2026-06-27  P2-F     note   START P2-F (worker -> com.cadence.worker pkg). Rebased on origin/master (up to date, e5af75a). Read P2-A as-built: IngestService enqueues categorize jobs ONLY for category==null; device rule classifier (P1-A.7) defaults unmatched->'other', so null-only never surfaces low-confidence events. job_queue payload {event_id,ts_start}+row org_id confirmed sufficient.
2026-06-27  P2-F.1  done   escalation rules: escalate when category null OR 'other' (other = rule classifier's explicit give-up); never re-categorise the 7 specific cats. Worker re-checks on claim so trigger is throughput-only, not correctness. Cost layers: device rules + pattern cache + per-org daily cap + claim batching. Failure=best-effort (default other, never block). Filed NEEDS P2-F->P2-A (ingest enqueue 'other' too). doc backend/docs/exploration/P2-F.1-escalation-rules.md; commit 89fae2f
2026-06-27  P2-F.2  done   prompt/model: official Anthropic Java SDK, claude-haiku-4-5 (CADENCE_CATEGORIZE_MODEL), no thinking/effort (Haiku lacks effort). Force fixed 8-enum via strict tool use/structured output (+ defensive other fallback). System prompt = stable cacheable role+category defs; user msg = structured signals (source/app/title/url/project/is_idle/duration) mirroring device ruleset. Cache key source|app|norm-title|url-host. Privacy: reads raw (store-raw/redact-on-read), notes app/title/url leave box for LLM. doc backend/docs/exploration/P2-F.2-prompt-design.md; commit 89fae2f
2026-06-28  P2-F.3  done   worker (com.cadence.worker): CategorizeWorker @Scheduled claims a batch via claim_categorize_jobs() (FOR UPDATE SKIP LOCKED, cross-org) -> virtual-thread fan-out; JobProcessor per job: load event (RLS-bound) -> re-check null/other (skip specific) -> cache -> cap -> AnthropicCategorizer (structured output -> 8-enum) -> writeAndComplete. LLM call BETWEEN short txns (no held conn). Best-effort: backoff retry then failed; cap=defer. NEEDS P2-F->P2-A SECURITY DEFINER claim fn (cross-org RLS) + deploy env. build+33 unit green; live e2e deferred. doc backend/docs/P2-F-worker.md; commit 3457faf
2026-06-28  P2-F.4  done   pattern cache: RedisPatternCache (StringRedisTemplate, per-org keyed, TTL cache-ttl-days). Key=source|app|norm-title(drop ' — project' suffix)|url-host so app/title repeats + same-file re-opens never re-hit the LLM. PatternCacheKeyTest covers normalisation. commit 3457faf
2026-06-28  P2-F.5  done   guardrails+metrics: RedisDailyTokenCap per-org/UTC-day budget (CADENCE_CATEGORIZE_DAILY_TOKEN_CAP; 0=unlimited; exhausted=defer/soft-degrade, never fail). Micrometer counters cadence.categorize.{jobs[result],cache[outcome],llm.calls,llm.tokens} via actuator. commit 3457faf
2026-06-28  P2-F     note   deps added (build.gradle.kts): spring-boot-starter-data-redis + com.anthropic:anthropic-java:2.34.0. config (application.yml): spring.data.redis.url + cadence.categorize.*. Whole worker stack @ConditionalOnProperty(cadence.categorize.enabled=true), default false -> dev box (no API key/Redis/Docker) boots backend untouched & build stays green. Phase-2 P2-F Variables block filled.
2026-06-28  DOCS    done   audit as-built Phase-2 cloud vs frozen §6/§7/§8 (code = ground truth, no backend code changed). §6: documented as-built idempotency key (event_id,ts_start), ingest/list response envelopes, the 15 P2 routes beyond the frozen table, and the missing privacy-level setter. §7: named the 3 CAGGs + their grain (per member/org, no per-team/commit rollup), events/team_members PK exceptions, RLS-is-a-backstop-while-app-runs-as-owner reality, job_queue org_id, and the two owed migrations (V2 github_installations, claim_categorize_jobs). §8: store-raw/redact-on-read. Filled real values into PHASE-2 Variables blocks (P2-B sync-db, P2-C codex/pricing, P2-E CADENCE_API_BASE) + ENV-VARIABLES (JDBC scheme, SMTP, categorize tuning, web BFF var) + deploy/.env.example (github+categorize). Recorded Phase-2 exit-criteria audit above. Reported PHASE-3 READINESS (P3-A facts from CAGGs; commits+fragmentation are gaps; P3-C needs a read-only org-scoped role — none exists; token events confirmed counts/cost/model only). commit 144cbed
2026-06-28  P2-D     note   RESUME (Phase-2 cleanup before P3): finishing the spine's owed GitHub work. Read as-built com.cadence.github (webhook->event mapping, github_login->member resolver, mode toggle all built+unit-tested) + V1 schema (no github_installations table -> root cause commits never land e2e). Plan reviewed w/ user: (1) write owed V2 migration; (2) surface commits in /org/summary as an ADDITIVE facet (user-approved contract extension, not silent edit); (3) live App registration deferred to deploy (fixture-verify now).
2026-06-28  P2-D.6  done   wrote backend/migrations/V2__github_installations.sql (github_installations + RLS org_isolation, idx_github_inst_org, partial uq_members_org_github, idx_events_org_source_ts; transactional, no .conf) — unblocks the installation->org lookup so source='github' events land. Surfaced commit activity in /org/summary: ADDITIVE Summaries.OrgSummary.commits facet {total,by_day,by_member} counting source='github' commit events (meta.commit_sha; PR/code_review excluded), by_member hidden under aggregate_only — only Summaries.java + OrgQueryService.java touch com.cadence.query (spine GitHub authority); webhook/migration stay in com.cadence.github. Documented §6 (additive contract) + Coordination (P2-E/P3-A read commits from the one rollup) + HANDOFF (live App registration at deploy). `./gradlew test` GREEN (mapper push-fixture parse tests + new QueryLogicTest.orgSummaryCarriesAdditiveCommitFacet snake_case wire assertion). APPLY + live webhook e2e = Docker handoff (same dev-box limit as P2-A.10). commit 9a6d41a
2026-06-28  P2-D     audit  DoD audit of the V2 + commit-facet finish (9a6d41a), docs/verify only, no feature code changed. 6/6 DoD PASS: (1) V2 is the only migration added (github_installations+RLS+uq_members_org_github+idx_events_org_source_ts; live apply=Docker handoff); (2) push->event fixture-proven (GithubEventMapperTest.PUSH -> meta.commit_sha/repo; source='github' fixed at JdbcGithubEventStore); (3) additive OrgSummary.commits facet, prior 7 fields untouched, wire locked by QueryLogicTest.orgSummaryCarriesAdditiveCommitFacet, doc'd §6+Coordination; (4) login->member resolver+partial-uq, skip-unmapped unit-tested (JDBC lookup integration-deferred); (5) default commit_messages_only at enum/migration/properties, full_diff=count-only no paths, zero API call by default; (6) HANDOFF line + GITHUB_* marked deploy-time in ENV-VARIABLES. Zero drift (routes/OrgSummary shape/env all match code). Scope clean: only com.cadence.query field+read-query+test, V2 migration, docs; no /backend/github/ files touched. PHASE-3: commits GAP CLOSED at code-path/fixture level (ingestion+surfacing path exists, lights up live at deploy w/ zero code change); runtime proof still Docker/deploy-deferred.
```

---

## Phase 3 — AI Intelligence + Revenue

### P3-A — insights foundation  (SPINE)
- [x] P3-A.1 explore aggregated-fact shape
- [x] P3-A.2 explore delivery + shareable card
- [x] P3-A.3 insights/digests migration + aggregation layer  ← ticks P3-A.CONTRACT
- [x] P3-A.4 weekly insights endpoint
- [x] P3-A.5 digest job (compute → narrate → store/email)
- [x] P3-A.6 prompt engineering (grounded narrative)
- [x] P3-A.7 shareable card render

### P3-B — pattern engine
- [x] P3-B.1 explore useful patterns
- [x] P3-B.2 time-series rollups + simple models
- [x] P3-B.3 expose to digest + admin
- [x] P3-B.4 confidence thresholds

### P3-C — NL query
- [x] P3-C.1 explore safe text-to-SQL constraints
- [x] P3-C.2 schema-aware prompt → SQL (read-only, scoped)
- [x] P3-C.3 nl query endpoint
- [x] P3-C.4 query UI + charts

### P3-D — billing
- [ ] P3-D.1 explore pricing → Stripe mapping
- [ ] P3-D.2 products/prices + checkout + portal
- [ ] P3-D.3 webhook lifecycle handling
- [ ] P3-D.4 feature gating by plan
- [ ] P3-D.5 token-overage metering

### P3-E — budget alerts
- [x] P3-E.1 explore anomaly definition
- [x] P3-E.2 agent loop (compare → narrate)
- [x] P3-E.3 Slack + email delivery
- [x] P3-E.4 per-org config

**Build Log — Phase 3**
```
(append newest at bottom)
2026-06-29  P3-A     note   START P3-A (spine). Read 00/01/02 + PHASE-3 P3-A + readiness (DOCS line, commit 144cbed) + P2-D-finish (commits GAP closed at code path, 9a6d41a). Grounded design in as-built: facts from raw events + 3 CAGGs (events_daily_by_category/_hourly/_tokens), commit facet from /org/summary path, com.cadence.mail.Mailer already does SMTP-or-LogMailer fallback, Anthropic SDK already wired (anthropic-java 2.34.0, structured output), cadence_app role created in deploy/initdb/00-app-role.sql (pattern for cadence_readonly). User-approved design choices: digest grain = per-member + org rollup; fragmentation focus set = deep_work+code_review+ai_assisted+research; card = server-side SVG only.
2026-06-29  P3-A.1  done   aggregated-fact shape frozen: MemberWeekFacts + OrgWeekFacts (grain column), headline scalars (deep_work_h/meeting_h/token_cost_usd/commits/fragmentation_index) + by_category_h/tokens/peak_block + deltas_vs_4wk_avg; no new CAGG; org grain privacy-bounded (top_contributors omitted under aggregate_only). doc backend/insights/docs/P3-A.1-aggregated-fact-shape.md; commit 46cc026
2026-06-29  P3-A.2  done   delivery design: weekly @Scheduled cron (Sun 23:00), whole stack @ConditionalOnProperty(cadence.digest.enabled=true) default false; pipeline compute(SQL)->upsert insights->narrate(Sonnet structured {narrative,spotted[3]})->SVG card->persist digests->deliver via reused Mailer (SMTP else LogMailer console fallback) + GET /insights/weekly; SVG-only card; env reconcile EMAIL_* -> SMTP_*. doc backend/insights/docs/P3-A.2-delivery-and-card.md; commit 46cc026
2026-06-29  P3-A.3  done   wrote backend/migrations/V3__insights_digests.sql (insights + digests tables; grain column member|org with member_id-presence CHECK; denormalized headline scalars + facts jsonb; partial unique indexes per grain for upsert; RLS org_isolation, not FORCEd; transactional, no CAGGs, no .conf) + deploy/initdb/01-readonly-role.sql (cadence_readonly: SELECT-only non-owner RLS-enforced org-scoped; ALTER DEFAULT PRIVILEGES covers future Flyway tables). Documented additive in 00-SYSTEM-KNOWLEDGE.md §6 (aggregated-fact contract + /insights/weekly now contract-frozen) + §7 (V3 tables, cadence_readonly role, migrations list refreshed V1/V2/V3). Coordination: RESOLVED P3-A->P3-C (role) + NOTE P3-A->P3-B/C/E (contract frozen). Ticks P3-A.CONTRACT — unblocks P3-B/C/E. `./gradlew build` GREEN (no Java changed; migration packaged via processResources; live apply = Docker handoff, same dev-box limit as P2-A.10). commit f8adcb9
2026-06-29  P3-A.CONTRACT  done  aggregated-fact shape frozen on the stream branch; insights/digests (V3) + cadence_readonly role + §6/§7 docs merged; P3-B/C/E may launch. commit f8adcb9
2026-06-29  P3-A.4  done   GET /api/v1/insights/weekly + the pre-aggregation query layer (com.cadence.insights): InsightsAggregationService builds MemberWeekFacts/OrgWeekFacts from SQL over raw events (by-category hours, tokens, commits via /org/summary path, fragmentation via window fn, peak_block, deltas vs trailing 4wk, history_weeks/low_confidence); IsoWeek date math (Mon..Mon UTC, ?week=YYYY-Www default=most-recent-completed); WeeklyInsightsService computes facts live + attaches persisted narrative/spotted/card from digests when present (facts-only until P3-A.5), admin-only org section honoring privacy_level (no per-member under aggregate_only); InsightsController. Pure read path — NOT gated on the digest stack, no LLM. application.yml cadence.insights.fragmentation-saturation. §6 documents the endpoint shape. `./gradlew build` GREEN (+7 InsightsLogicTest: ISO-week math, fragmentation curve, snake_case wire shape incl. deltas_vs_4wk_avg); live DB exercise = Docker handoff (same limit as P2-A.10). commit c7c6692
2026-06-29  P3-A.5  done   weekly digest job (com.cadence.insights.digest, gated @ConditionalOnProperty cadence.digest.enabled=true, default false): DigestService @Scheduled(CADENCE_DIGEST_CRON, Sun 23:00) iterates orgs→active members; pipeline = compute facts (SQL, tenancy-bound short tx) → narrate (LLM, no tx) → render card → upsert insights+digests (status rendered) → deliver via reused com.cadence.mail.Mailer (SMTP else LogMailer console) → mark sent/failed. LLM + email BETWEEN short txns (no held conn, mirrors P2-F). min-days gate per member; org digest privacy-bounded (skips per-member under aggregate_only). DigestWriter idempotent upserts (partial-unique conflict targets per grain). Admin dogfood trigger POST /api/v1/insights/run (gated). IsoWeek made public for cross-package use. commit b0bbc8e
2026-06-29  P3-A.6  done   prompt engineering: DigestNarrator system prompt = facts-in JSON → grounded prose + exactly 3 spotted insights (peak hours / token efficiency / meeting load) via Anthropic structured output (StructuredMessageCreateParams, model CADENCE_DIGEST_MODEL=claude-sonnet-4-6). Hard rule enforced in prompt: use ONLY provided numbers, no invention, no raw-event access. Client built best-effort from env; missing key OR any call failure → deterministic template() fallback (pipeline always ships a digest, testable with no API key). commit b0bbc8e
2026-06-29  P3-A.7  done   shareable card: DigestCard renders a 1200×630 branded SVG (deep-work hrs, commits, AI token cost, focus score = 100−fragmentation_index, peak block) server-side, dependency-free; XML-escaped; stored on digests.card_svg, served by GET /insights/weekly. PNG rasterization deferred (would add an image lib). commit b0bbc8e
2026-06-29  P3-A    note   BUILD GREEN `./gradlew build` (+5 DigestRenderTest: card well-formed/hero-numbers/XML-escape, template grounded+3-spotted, low-confidence note). Env: application.yml cadence.digest.* + cadence.insights.fragmentation-saturation; ENV-VARIABLES + PHASE-3 P3-A Variables reconciled EMAIL_*→SMTP_* (SMTP-only delivery, console fallback). Live run (scheduler + LLM + SMTP + DB upserts) = Docker/key handoff, same dev-box limit as P2-A.10. P3-A STREAM COMPLETE (.1–.7 [x]); P3-A.CONTRACT ticked earlier. commit b0bbc8e
2026-06-29  P3-B     note   START P3-B (pattern engine). Read 00/01/02 + PHASE-3 P3-B + P3-A.1 frozen fact shape. BASE: P3-A.CONTRACT is [x] (f8adcb9) but lives on worktree-stream+p3-a-insights, NOT merged to master (kickoff's "origin/main" doesn't exist — main is master). Rebased P3-B onto the P3-A spine branch (clean ff: master+2 contract commits, linear) so it builds on the frozen V3 insights/CAGGs. Flagged to operator for confirmation (P3-B.1 §8 Q3).
2026-06-29  P3-B.1  done   pattern exploration: 3 HIGH-confidence findings only — (1) peak productivity window from events_hourly_by_category (7x24 focus grid; focus set = P3-A §3.1), (2) meeting->output correlation from events_daily_by_category (Pearson r + high/low split on daily meeting_h vs deep_work_h), (3) context-switch cost reusing P3-A §3.2 fragmentation (the ONE raw-events read — per-day switches, grain facts/CAGGs lack). Confidence = hard CADENCE_PATTERN_MIN_DAYS=14 gate (empty for low-history) + per-finding evidence bar; surface only HIGH, cap <=3. Exposure = additive PatternService bean + facts.patterns field + (proposed) GET /insights/patterns; NEEDS P3-B->P3-A (narrate facts.patterns) + P3-B->P2-E (render). Flow-state predictor deferred (not high-confidence at 2-4wk). Pure-fn analysis vs JDBC split for no-Docker unit tests + deferred Testcontainers IT. 3 open decisions for operator (route, output proxy, base). doc backend/insights/pattern/docs/P3-B.1-pattern-engine.md; commit f566a37
2026-06-29  P3-B     note   BASE clean: ff master -> b9c89b1 (P3-A contract: V3 insights/digests + cadence_readonly + §6/§7 docs) in the main worktree, then rebased P3-B onto master (linear). Per operator: master is the canonical base for all wave streams (no `main`/origin). P3-A's session keeps its later feature commits (640ee63 /insights/weekly endpoint, etc.) on its branch to merge separately.
2026-06-29  P3-B.2  done   pattern engine impl in com.cadence.insights.pattern: PatternAnalysis (PURE fns — 3 models: peakWindow over 7x24 focus grid w/ concentration ratio; meetingOutput Pearson r + median split on daily meeting_h vs deep_work_h; contextSwitch on daily project-switch rate vs deep_work_h) + PatternService (JDBC: events_hourly/daily_by_category CAGGs + ONE raw-events read = per-day fragmentation reusing P3-A §3.2; every query org_id-filtered, tenancy.bind). PatternRange (4w default). All <=3 findings, ranked by strength. `./gradlew build` GREEN. commit b00f6d8
2026-06-29  P3-B.4  done   confidence model: hard CADENCE_PATTERN_MIN_DAYS=14 gate in analyze()+service short-circuit -> low-history caller gets low_confidence=true + EMPTY findings; per-finding evidence bars (peak-concentration 1.5x, min |r| 0.4, min effect 0.15) tunable via cadence.pattern.* (PatternProperties record + PatternConfig). Proven by PatternAnalysisTest (9 tests, seeded >14d fixture): each model's math, weak-signal drop, and the headline gate (5 active days w/ strong data -> empty). commit b00f6d8
2026-06-29  P3-B.3  done   expose findings ADDITIVELY (same discipline as P2-D commits facet): PatternController GET /api/v1/insights/patterns?range[&scope=org] (member self / admin org) in com.cadence.insights.pattern; documented in 00-SYSTEM-KNOWLEDGE.md §6 (additive route + additive facts.patterns[] field on MemberWeekFacts/OrgWeekFacts + Finding wire shape + gating/privacy-safety). Coordination: NEEDS P3-B->P3-A (call PatternService, graft facts.patterns — NO dependency on insights table / P3-A.5; findings computed from same CAGGs+§3.2 fragmentation) + NEEDS P3-B->P2-E (render "What we noticed" card; honest-empty on low_confidence). `./gradlew build` GREEN. Live JDBC path (CAGG/raw reads) = Docker-deferred integrationTest, same dev-box limit as P2-A.10. commit 155ae1b
2026-06-29  P3-B     test   authored PatternEngineIT (src/integrationTest, mirrors E2EIngestQueryIT): register -> seed 21-day deep_work stream w/ a Tue-10:00 spike -> refresh_continuous_aggregate (the CAGG gotcha: views are WITH NO DATA, engine reads them) -> GET /insights/patterns?range=8w asserts peak_window at iso_dow=2/hour=10, and ?range=7d asserts the gate (low_confidence + empty). Exercises the real SQL the pure-fn test can't (CAGG cols, isodow/hour extract, §3.2 fragmentation read). `./gradlew compileIntegrationTestJava` GREEN; run deferred to a Docker host (same limit as P2-A.10/P2-D). commit aadef1f
2026-06-29  P3-C     note   START P3-C (NL query). Built on the P3-A contract (V3 insights/digests + cadence_readonly role). SECURITY = the point: text-to-SQL executes ONLY via cadence_readonly (SELECT-only, non-owner, RLS-enforced, org-scoped) behind a fail-closed allowlist; LLM generates SQL + caption, never sees raw rows. Owned pkg com.cadence.insights.nlquery (disjoint from P3-A's com.cadence.insights.*). The 5 P3-C dev commits were rebuilt as a single feature commit on top of master after master advanced past the session's 19a05fe snapshot (P3-A + P3-B landed); merge-base was b9c89b1 so the P3-A contract was already shared. Operator-approved JSqlParser + admin-only decisions recorded in Coordination.
2026-06-29  P3-C.1  done   safe text-to-SQL security design: threat model (LLM SQL = hostile), 6 defense layers (SELECT-only role + non-owner RLS + statement_timeout/row-cap = DB-hard; single-SELECT + table/column allowlist + token denylist = fail-closed app policy), privacy-level-aware allowlist (excludes password_hash/email/title/url/token-hash tables; aggregate_only drops member_id + per-member tables), separate cadence_readonly datasource (never owner-connection fallback), LLM sees schema metadata in / capped result out, admin-only. doc backend/insights/nlquery/docs/P3-C.1-safe-text-to-sql.md; commit 86fc8e2
2026-06-29  P3-C.2  done   safe text-to-SQL core (com.cadence.insights.nlquery): SqlAllowlist (privacy-aware tables/columns) + SqlValidator (JSqlParser 4.9 fail-closed: denylist pre-filter -> parse -> single flat PlainSelect, reject DML/DDL/CTE/UNION/subquery/SELECT*/INTO/system-catalog -> every table+column on allowlist or output alias) + NlSqlPlanner (schema-metadata->SQL via structured model output + caption from capped rows; owns its own client, no P2-F bean collision) + NlQueryProperties. Build dep jsqlparser:4.9. SqlValidatorTest (38 cases) + ReadonlyRoleDefinitionTest GREEN; `./gradlew build` GREEN. commit 86fc8e2
2026-06-29  P3-C.3  done   POST /api/v1/query/nl (NlQueryController/Service/Executor/Config/Dtos): admin-only -> privacy lookup -> allowlist -> generate -> validate -> execute as cadence_readonly (PRIVATE Hikari DS owned by executor, NOT a Spring DataSource bean to keep primary autoconfig; read-only txn + SET LOCAL statement_timeout + set_config app.current_org RLS bind + SELECT * FROM(...) LIMIT max+1 cap) -> caption. Whole stack @ConditionalOnProperty(cadence.nlquery.enabled=false default). application.yml cadence.nlquery.*. Authored NlQueryReadonlyRoleIT (Docker-gated: cross-org SELECT=0 rows, write denied, cap truncates). doc backend/insights/nlquery/docs/P3-C-backend-verification.md; `./gradlew build` + compileIntegrationTestJava GREEN. commit 86fc8e2
2026-06-29  P3-C.4  done   query UI: self-contained Next 14 app /web/insights (mirrors web/admin BFF httpOnly-cookie session). /ask: question box + example-prompt chips + ResultView (model caption, hand-rolled SVG bar chart for 2-col label->number results, table, capped banner, view-SQL); /login (admin-only). BFF: browser -> same-origin /api/query/nl -> proxyJson -> backend POST /api/v1/query/nl w/ Bearer server-side; tokens never reach JS. All enforcement server-side; UI is a thin client. `npm install && npm run lint && npm run build` GREEN (6 routes + middleware). doc web/insights/docs/P3-C.4-query-ui.md. commit 86fc8e2
2026-06-29  P3-C     note   STREAM COMPLETE: backend (com.cadence.insights.nlquery) + UI (/web/insights), P3-C.1-.4 [x]; `./gradlew build` + web build GREEN; SqlValidatorTest(38)+ReadonlyRoleDefinitionTest green. Live e2e = the cadence_readonly fresh-volume deploy HANDOFF (authored NlQueryReadonlyRoleIT runs there); no owner-connection fallback ever. PHASE-3 P3-C Variables block filled; nlquery env owed to ENV-VARIABLES.md at phase close.
2026-06-29  P3-E.1  done   anomaly + dedupe design frozen (user-approved): read events_daily_tokens (no new CAGG); daily burn per member+org; baseline = mean over ACTIVE days in trailing 14d; spike = ratio>=3x AND today>=$10 (PROVISIONAL absolute floor, config not hardcoded, retune post-deploy w/ >=2wk data); severity tiers [3,5,10]; min-history 7d. Dedupe: budget_alerts ledger UNIQUE(org,subject,day,severity) via ON CONFLICT (escalate-only, max 3/day); quiet-hours = defer-via-reevaluation (no extra queue); mute_until override. Delivery: email default (Mailer), Slack gated purely on per-org webhook presence (env=local default). Migrations spine-only -> NEEDS P3-E->P3-A filed w/ exact DDL; code degrades gracefully. doc backend/insights/budget/docs/P3-E.1-anomaly-and-dedupe.md; commit 6c3fd09
2026-06-29  P3-E.4  done   per-org config: OrgBudgetConfig + BudgetConfigStore reads budget_alert_config (env defaults when no row OR table missing -> graceful degrade, warns once). BudgetProperties binds cadence.budget.* (enabled gate off by default, like categorize). application.yml block added. Thresholds all config (env + per-org), none hardcoded. (built together w/ .2/.3) commit 9e68954
2026-06-29  P3-E.2  done   agent loop: BudgetMonitor @Scheduled(cron=CADENCE_BUDGET_CHECK_CRON, hourly), gated @ConditionalOnProperty cadence.budget.enabled=true. Cross-org scan of events_daily_tokens (explicit org_id filter = tenant guard, like P2-F); BudgetWindowAssembler folds cells->per-member+org daily burns; BudgetAnomalyDetector (active-day mean baseline, ratio>=mult AND today>=floor, min-history gate, severity tiers); BudgetAlertNarrator (Haiku via official SDK like AnthropicCategorizer, never throws -> deterministic template fallback; self-built client, no bean collision w/ worker). commit 9e68954
2026-06-29  P3-E.3  done   delivery: BudgetAlertDispatcher email-DEFAULT (reuse com.cadence.mail.Mailer, SMTP->console fallback) / Slack gated PURELY on webhook presence (SlackNotifier via JDK HttpClient, no new dep) -> flips on per-org w/ zero code change; Slack failure falls back to email. AlertRecipientStore = alert_email or org owners/admins. Dedupe: BudgetAlertLedger INSERT..ON CONFLICT DO NOTHING on (org,subject,day,severity) -> deliver only if newly claimed (escalate-only, never spams). Quiet hours = defer-via-reevaluation (QuietHours, tz-aware, midnight-wrap). 20 DB-free unit tests (detector/quiet-hours/assembler/narrator/channel) green; `./gradlew build` GREEN; live DB+Slack/SMTP = deploy handoff (no Docker on dev box, same as P2-A.10). commit 9e68954
```
