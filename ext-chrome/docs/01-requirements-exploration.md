# P1-C — Requirements Exploration (Chrome MV3 extension)

> Status: findings for review. Tasks P1-C.1 + P1-C.2. No implementation yet
> (gated on `P1-A.CONTRACT`). This doc is the durable record of the conclusions
> a resumed session should rely on instead of re-deriving them.

---

## P1-C.1 — MV3 service-worker lifecycle & active-tab focus time

### The core constraint
MV3 has **no persistent background page**. The background is an *ephemeral
service worker (SW)*: Chrome spins it up to handle an event and terminates it
after ~30s idle (a received event resets the timer, but you must assume it can
die at any moment). Consequences that shape the whole design:

- **No running timers.** A `setInterval` accumulating focus seconds is wrong —
  the SW dies and the counter is lost. We must be **event-driven**: record
  timestamps on transitions and compute durations.
- **No in-memory state across restarts.** Module-global variables vanish when
  the SW is killed. Current-focus state must live in `chrome.storage`.

### Tracking model: a focus state machine, timestamps not timers
Maintain exactly one "focus session" at a time, persisted to storage:

```
session = { tabId, domain, startTs } | null   // stored in chrome.storage.session
```

Open a session when a tab becomes the focused-active tab and the user is active.
Close it (compute `duration = now - startTs`, emit/accumulate an event) on any
transition. The events that drive transitions:

| Event | Why it matters |
|---|---|
| `chrome.tabs.onActivated` | user switches active tab within a window |
| `chrome.tabs.onUpdated` (url/status) | navigation within the same tab (new domain) |
| `chrome.windows.onFocusChanged` | switching windows; `WINDOW_ID_NONE` = Chrome lost focus to another app → pause |
| `chrome.idle.onStateChanged` | `active` / `idle` / `locked` → pause/resume |
| `chrome.tabs.onRemoved` | active tab closed → close session |

"Focused-active tab" = the `active` tab **in the currently focused window**.
Only one exists across all windows; if no window is focused, nothing is tracked.

### State persistence
- `chrome.storage.session` — current `session` object (in-memory, MV3, never
  hits disk; perfect for the live focus pointer; auto-clears on browser restart).
- `chrome.storage.local` — accumulated per-domain duration buckets pending flush
  to the daemon, so nothing is lost if the SW dies between flushes.

### The "long single tab" problem (the subtle one)
If the user stays on one tab for 45 min, only one `onActivated` fired (at the
start) and the SW was almost certainly killed in between. Solution: a
**`chrome.alarms` heartbeat** (~1 min; alarms survive SW death and wake it):

1. **Checkpoint** the open session: write elapsed time into the local bucket and
   reset `startTs = now`. This bounds worst-case data loss to one alarm interval
   and means a long session is recorded as a series of capped chunks.
2. **Flush** accumulated buckets to the daemon's local route.

`chrome.alarms` minimum period is ~1 min for released extensions — fine for a
heartbeat. We do **not** use keep-alive hacks (long-lived ports/offscreen
tricks) to force the SW to stay alive; that fights MV3 and drains battery.

### Idle handling (aligns with the Event Contract `is_idle` + P1-A.2)
- `chrome.idle.setDetectionInterval(n)` (min 15s) + `chrome.idle.onStateChanged`.
- On `idle`/`locked`: close the current session (stop accumulating). On `active`:
  reopen for the focused-active tab.
- **Meetings caveat** (mirrors P1-A.2): a Meet/Zoom *web* tab with little input
  would look idle. For the browser collector we lean on the **domain→category**
  map: time on a `meetings` domain while the tab is focused is engaged time even
  with low input. Final idle-vs-meeting reconciliation is the daemon's call
  (P1-A owns idle policy); the ext just reports focus + domain + an `is_idle`
  hint from `chrome.idle`.

### Best-UX angle — work vs distraction WITHOUT storing browsing history
We never need a URL history list to separate work from distraction — we need
only **domain + category + duration**, computed at the moment of emission:

- Derive `domain = new URL(tab.url).hostname` and map domain→category via the
  ruleset (P1-C.5). Emit `{domain, category, duration}` and drop the rest.
- **No content scripts, no page-content access.** Focus tracking needs only tab
  metadata, which keeps the permission footprint (and the scare factor) minimal.

### Permissions — least-scary install (the macOS-equivalent UX concern)
| Permission | Needed for | Install warning |
|---|---|---|
| `tabs` | read `url`/`title` of tabs from the SW | "Read your browsing history" |
| `idle` | active/idle/locked | none |
| `alarms` | heartbeat flush/checkpoint | none |
| `storage` | persist session + buckets | none |
| `host_permissions: http://127.0.0.1/*` | POST to local daemon w/o CORS pain | localhost only (mild) |

Key decision: request **`tabs`** rather than `host_permissions: <all_urls>`.
`tabs` already exposes `url`/`title`/`favIconUrl` for all tabs, and avoids the
much scarier *"read and change all your data on the websites you visit"* prompt
that `<all_urls>` + content scripts would trigger. The one unavoidable warning
is "Read your browsing history" (intrinsic to any browser time-tracker); we
defuse it in onboarding copy: *data stays local, only domain+category leave the
device by default, no full URLs, no page contents, ever.*

### Pages we must ignore
`chrome://`, `chrome-extension://`, `about:`, `file://`, the New Tab page, and
**incognito windows** (extension is off in incognito by default — keep it that
way; never track private browsing). These emit no events / map to null.

---

## P1-C.2 — Privacy default: domain + category, not full URL

### What the frozen contracts say
- **Event Contract (§5):** `url` is present for the chrome source; `title` may be
  redacted; a collector that can't/shouldn't fill a field sends `null`.
- **Privacy model (§8):** org policy levels `full` / `categories_only` /
  `aggregate_only`, applied **server-side on ingest and again on read**; plus a
  user-controlled **local redaction list (regex)** whose matches are **hashed
  before they leave the device**; *"never trust the client to have redacted."*

### Two layers, kept distinct
1. **Collector-side data minimization (this stream's default).** Controlled by
   the popup toggle `cadence.urlPrivacy = domain_only | full`:
   - `domain_only` (**default**): `url` = origin only (`https://github.com`, no
     path/query/fragment), `title` = `null`. Domain is all the classifier needs,
     and titles are the biggest leak vector (e.g. `"Re: Acme acquisition – Gmail"`),
     so dropping them by default is the privacy-first choice.
   - `full`: `url` = full URL, `title` = tab title.
2. **Authoritative enforcement (NOT this stream).** Org-policy levels and
   redaction-list hashing are the hard boundary, owned by the **daemon (P1-A.8)**
   and **backend (P2-A)**. Per *"never trust the client,"* the daemon must
   enforce even if a buggy/forked extension over-sends. The ext toggle is
   defense-in-depth at the source, **not** the security boundary.

> Net: the extension *minimizes*; the daemon *enforces*. Both true at once.

### Redaction list — who applies it
The redaction list is owned by P1-A (P1-A.8) and the contract says hashing
happens at the device boundary (the daemon), before sync. Since the ext POSTs to
the **local** daemon over `127.0.0.1`, the daemon is the natural redaction point.
Recommendation: the ext does **not** reimplement redaction in v1; `domain_only`
already strips the high-risk parts. (Optional later enhancement: in `full` mode,
fetch the cached redaction regexes from the daemon and skip emitting matches —
pure minimization. Deferred; flagged as a question to P1-A below.)

### Field-by-field plan for `source:"chrome"` events
| Field | domain_only (default) | full |
|---|---|---|
| `source` | `"chrome"` | `"chrome"` |
| `url` | origin only (`https://host`) | full URL |
| `title` | `null` | tab title |
| `project` | `null` (no path to parse) | best-effort (e.g. `github.com/org/repo`→repo) |
| `category` | from domain→category map (P1-C.5) | same |
| `is_idle` | from `chrome.idle` hint | same |
| `member_id` | **fetched from daemon** (see open Q) | same |
| `event_id` | `crypto.randomUUID()` | same |
| `ts_*`,`duration_ms` | from focus-session timestamps, RFC3339 UTC | same |

---

## Open questions / coordination items for P1-A (file as NEEDS when contract lands)
These are **not** blockers for exploration, but must be resolved before P1-C.3+:

1. **Local route + CORS.** Exact `POST` route, port discovery, and whether the
   daemon sets permissive CORS for `127.0.0.1` *or* expects us to declare
   `host_permissions: http://127.0.0.1/*`. A JSON POST triggers a CORS preflight
   from a SW — daemon must handle `OPTIONS`/allow-origin, or we need the host
   permission. (Ref P1-A.5.)
2. **Shared `member_id`.** The contract sets `member_id` "at install" as a local
   identity. All collectors must share it — the ext should **fetch** `member_id`
   from the daemon's local API, not mint its own. Need an endpoint/handshake.
3. **Redaction ownership.** Confirm the daemon is the sole redaction/policy
   enforcement point and collectors only minimize (our assumption above).
4. **Idle reconciliation.** Confirm the daemon owns final idle/meeting policy and
   simply consumes our `is_idle` hint + domain.

## What's intentionally deferred (no silent scope creep)
- Public-suffix-accurate eTLD+1 (we use `hostname`; sufficient for v1 dev domains).
- `project` extraction from GitHub/Linear paths (only possible in `full` mode).
- Local redaction in the extension (daemon owns it).
- SPA in-tab navigations that don't fire `onUpdated` (accept minor under-count v1).
