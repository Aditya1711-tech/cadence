# Phase 1 — Foundation (local)

**Goal:** activity data flows locally end to end, categories are mostly right,
and both founders can install Cadence and dogfood it for two weeks.

**Wave structure:**
```
Wave 0 (spine, 1 session):  P1-A   agent core + local store + event contract in code + rule classifier
Wave 1 (parallel, 3 sessions): P1-B vscode ext | P1-C chrome ext | P1-D personal dashboard
```
Launch P1-A alone. When `P1-A.CONTRACT` is ticked in `PROGRESS.md`, launch
P1-B, P1-C, P1-D simultaneously.

**Exit criteria (the gate to Phase 2):**
- Daemon runs on macOS + Linux, survives logout/login, uses < 2% CPU idle.
- VSCode and Chrome events appear in the local store with correct categories.
- Personal dashboard shows today's timeline and a category breakdown from local
  data only (no cloud yet).
- Both founders have run it for 14 continuous days without a crash.

---

## Stream P1-A — Agent core, local store, contract, classifier  (SPINE)

**Owns:** `/agent/`
**Check command:** `cd agent && go build ./... && go test ./...`

### Requirements exploration (do first, report before coding)
- `P1-A.1` Survey active-window detection per OS: macOS (Accessibility API),
  Linux (X11/Wayland differences), Windows (deferred but note the seam). List
  the libraries/syscalls and their permission prompts. **Best-UX angle:** what
  is the least-intrusive permission flow on macOS so users aren't scared off?
- `P1-A.2` Define idle detection approach (input cadence, idle threshold, how to
  treat meetings where there's little input but the user is engaged).

### Design / contract-in-code
- `P1-A.3` Implement the **Event Contract** (§5 of system knowledge) as Go
  structs + JSON marshalling. Add a `schema_ver` constant. This is the artifact
  every Wave-1 stream depends on. **Tick `P1-A.CONTRACT` when merged to main.**
- `P1-A.4` Local store: encrypted SQLite schema mirroring the Event Contract;
  an append API and a query API for the dashboard. Encryption key in OS keychain.
- `P1-A.5` Local HTTP/IPC surface on `127.0.0.1` so collectors (P1-B, P1-C) can
  POST events and the dashboard (P1-D) can read them. Document the local port +
  routes here when done (see "Variables to set").

### Implementation
- `P1-A.6` Active-window + idle collector (the OS source), writing events.
- `P1-A.7` Rule-based classifier: map app/title/url patterns → category. Ship a
  sensible default ruleset (editors→deep_work, Slack→comms, meet/zoom→meetings,
  browsers→research unless matched). User-editable rules file.
- `P1-A.8` Local redaction list (regex) — hash matching titles/urls before store.
- `P1-A.9` Background lifecycle: install as a user-level service (launchd on mac,
  systemd user unit on Linux). Start on login, restart on crash.
- `P1-A.10` Resource budget: verify < 2% idle CPU, bounded memory, batched writes.

### Verification
- `P1-A.11` 24-hour soak test on both founders' machines; no leaks, no crash.

### Variables to set (fill when stream completes)
```
CADENCE_AGENT_PORT=47821       # default loopback port (override via env)
CADENCE_DB_PATH=               # default: <os.UserConfigDir>/cadence/cadence.db (override via env)
CADENCE_KEYCHAIN_SERVICE=com.cadence.agent   # keychain service; accounts "store-key", "member-id"
CADENCE_MEMBER_ID=            # optional; else a uuid is generated and persisted in the keychain
CADENCE_RULES_PATH=            # optional JSON classifier ruleset; scaffolded with
                              # the default on first run if missing. Unset = built-in default.
CADENCE_REDACT_PATH=          # optional JSON {"patterns":[regex,...]}; matching titles/urls
                              # are hashed before store. Scaffolded empty on first run. Unset = off.
# Local collector POST route: POST http://127.0.0.1:47821/events
#   body: a single Event Contract object OR a JSON array (max 1000); idempotent
#   on event_id; 200 -> {"accepted":n,"rejected":m,"errors":[...]}; invalid
#   events are skipped (reported), malformed body/oversized batch -> problem+json.
# Local dashboard read route:  GET  http://127.0.0.1:47821/timeline?from&to
#   from/to are RFC3339 UTC (default: last 24h); 200 -> JSON array of events
#   (decrypted), [] when empty. Canonical event shape: agent golden_event.json.
# Health:                      GET  http://127.0.0.1:47821/healthz
#   200 -> {"status":"ok","events":<count>,"schema_ver":1}
# Loopback-only: non-127.0.0.1 peers get 403 (Phase-1 stand-in for auth).
```

---

## Stream P1-B — VSCode extension

**Owns:** `/ext-vscode/`
**Check command:** `cd ext-vscode && npm ci && npm run compile && npm test`
**Depends on:** `P1-A.CONTRACT` (event shape), `P1-A.5` (local POST route).

### Requirements exploration
- `P1-B.1` Which VSCode events best reflect real coding time? (active editor
  change, file save, debug start/stop, typing bursts). **Best-UX angle:** detect
  genuine focus vs file left open while away — avoid inflating deep_work.
- `P1-B.2` How to capture `project` + `lang` reliably (workspace folder, file
  extension) and how to respect the redaction list for file paths.

### Implementation
- `P1-B.3` Track active file + language + workspace; accumulate focused time.
- `P1-B.4` Emit Event Contract events to the daemon's local route on a debounce
  (e.g. flush every 30s or on focus change). Fill `source:"vscode"`,
  `meta.lang`, `project`.
- `P1-B.5` Graceful degradation when the daemon isn't running (queue locally,
  retry; never block the editor).
- `P1-B.6` Settings: enable/disable, redaction toggle, "pause tracking" command.

### Verification
- `P1-B.7` Confirm events land in the local store with correct project/lang and
  classify as `deep_work` / `code_review` per the ruleset.

### Variables to set
```
# VSCode settings contributed (see ext-vscode/package.json):
cadence.enabled=true             # master on/off (reload to apply)
cadence.agentPort=47821          # must match CADENCE_AGENT_PORT (daemon default 47821)
cadence.redactPaths=true         # titles carry basename + project only, never abs path
cadence.idleThresholdSec=300     # focus-session idle cutoff; matches OS collector (P1-A.2)
# Commands: cadence.pauseTracking / resumeTracking / toggleTracking (+ status-bar toggle)
# Local member_id: provisional per-install uuid in globalState (see OPEN NEEDS to P1-A)
```

---

## Stream P1-C — Chrome extension (MV3)

**Owns:** `/ext-chrome/`
**Check command:** `cd ext-chrome && npm ci && npm run build`
**Depends on:** `P1-A.CONTRACT`, `P1-A.5`.

### Requirements exploration
- `P1-C.1` MV3 service-worker lifecycle: how to track active-tab focus time
  without a persistent background page. **Best-UX angle:** categorize work tabs
  (GitHub, Linear, docs) vs distraction without storing full browsing history.
- `P1-C.2` Privacy default: store domain + category, not full URL, unless the
  org policy is `full`. Honor the redaction list.

### Implementation
- `P1-C.3` Track active tab + focus duration via `chrome.tabs` + `chrome.idle`.
- `P1-C.4` Emit Event Contract events (`source:"chrome"`, `url` per policy).
- `P1-C.5` Map common dev domains to categories (github→code_review,
  meet/zoom→meetings, stackoverflow/docs→research).
- `P1-C.6` Popup: today's top sites by time, pause button, privacy toggle.

### Verification
- `P1-C.7` Events land locally; domains categorized; redaction verified.

### Variables to set
```
# Build: cd ext-chrome && npm ci && npm run build  -> load ext-chrome/dist/ unpacked.
# Configured via the popup; stored in chrome.storage.local:
cadence.agentPort=47821          # matches CADENCE_AGENT_PORT (agent default); override in popup
cadence.urlPrivacy=domain_only   # domain_only (default: origin-only url + null title) | full
# Manifest permissions: tabs, idle, alarms, storage + host_permissions http://127.0.0.1/*
# member_id: interim self-generated stable uuid in storage, pending a daemon-shared
#   id (see the OPEN NEEDS line in PROGRESS.md — also affects P1-B).
# Manual on-machine E2E checklist: ext-chrome/docs/02-verification.md
```

---

## Stream P1-D — Personal dashboard (local-only for now)

**Owns:** `/web/dashboard/`
**Check command:** `cd web && npm ci && npm run lint && npm run build`
**Depends on:** `P1-A.5` (local read route).

### Requirements exploration
- `P1-D.1` What does a developer actually want to see day-one? Sketch: a daily
  timeline ribbon, category donut, top projects, focus vs fragmentation score.
  **Best-UX angle:** make it glanceable in 5 seconds; no setup friction.
- `P1-D.2` Decide the local read contract with P1-A (timeline + summary shapes).

### Implementation
- `P1-D.3` Next.js dashboard reading the daemon's local route.
- `P1-D.4` Daily timeline ribbon (hour blocks colored by category).
- `P1-D.5` Category breakdown + top projects + total tracked.
- `P1-D.6` Simple "focus score" (share of deep_work uninterrupted ≥ 25 min).
- `P1-D.7` Empty/loading/daemon-offline states that are friendly, not scary.

### Verification
- `P1-D.8` Dashboard renders real local data for a full day on both machines.

### Variables to set
```
NEXT_PUBLIC_CADENCE_AGENT_BASE=http://127.0.0.1:<CADENCE_AGENT_PORT>
```

---

## Phase 1 coordination notes
- Only **P1-A** writes the Event Contract code and the local routes. P1-B/C/D
  consume them. If a consumer needs a route change, file a NEEDS line to P1-A.
- No cloud, no auth, no AWS in Phase 1. Everything is `127.0.0.1`.
