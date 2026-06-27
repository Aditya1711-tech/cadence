# P1-B — Requirements Exploration (VSCode extension)

Status: findings for `P1-B.1` and `P1-B.2`. No implementation here — this is the
"report before coding" deliverable required by the phase doc. Implementation
(`P1-B.3+`) is gated on `P1-A.CONTRACT` + `P1-A.5` (local POST route) and is NOT
started.

All API names below are stable VSCode Extension API (`vscode` namespace) unless
marked *proposed*.

---

## P1-B.1 — Which VSCode events best reflect real coding time?

### The core problem
VSCode gives us **no global keyboard/mouse hook**. We can only observe events
*inside* the editor (document edits, selection moves, scrolling) plus the
window's OS-focus state. So "real coding time" has to be reconstructed from the
union of in-editor interaction signals, gated by whether the window is actually
focused. The risk to avoid (the best-UX angle) is **inflating `deep_work` when a
file is left open while the user walked away**.

### Signal inventory (ranked by value for "real coding time")

| Rank | API event / property | What it tells us | Use |
|---|---|---|---|
| Gate | `window.onDidChangeWindowState` → `WindowState.focused` | Is the VSCode window the focused OS window right now | **Hard gate.** Never accumulate time while `focused === false`. Also read `window.state.focused` for initial state. |
| 1 | `workspace.onDidChangeTextDocument` | Active typing / edits | Strongest *authoring* signal → genuine `deep_work`. Frequency = typing-burst intensity. |
| 2 | `window.onDidChangeActiveTextEditor` | User switched file/tab | Marks file boundaries; tells us *which* file owns the time. Also an interaction heartbeat. |
| 3 | `window.onDidChangeTextEditorSelection` | Cursor / selection moved (keyboard nav **or** mouse click) | Reading + navigating heartbeat — keeps the clock alive when not typing. Note `event.kind` (Keyboard/Mouse/Command). |
| 4 | `window.onDidChangeTextEditorVisibleRanges` | Scrolling | Reading heartbeat (scanning code without editing). |
| 5 | `workspace.onDidSaveTextDocument` | File saved | Milestone, not duration. Good as a "this was real work" confirmation. |
| 6 | `debug.onDidStartDebugSession` / `onDidTerminateDebugSession` / `onDidChangeActiveDebugSession` | Debugging in progress | **Engaged even with little input** (stepping/inspecting). Treat an active debug session as non-idle. |
| —  | `window.onDidChangeActiveTerminal`, terminal open/close | Terminal focus | Coarse only. Per-keystroke terminal data (`onDidWriteTerminalData`) is *proposed* API — do not rely on it in stable. Terminal time is better left to the OS collector. |

### Recommended model: "focused interaction session"

Accumulate time into a **session** that is open only when BOTH hold:
1. `WindowState.focused === true`, AND
2. at least one interaction event (edit / selection / scroll / active-editor
   change / active debug session) occurred within the **idle threshold** window.

- Session **opens** on first interaction while focused.
- Session **pauses/closes** on either: window focus lost, or no interaction for
  `idleThresholdMs` (proposal: 60–120s; see coordination note — must match the
  OS collector's threshold so the two sources agree).
- While a **debug session is active**, keep the clock running even with no
  edits/selection (stepping counts as engaged work).
- Flush an Event Contract record per (file, language, project) focused segment on
  debounce or on file/focus change (see `P1-B.4`), with `is_idle:false`.

This directly kills the "file left open while away" inflation: no focus → no
time; focused-but-no-interaction past the threshold → no time.

### Editing vs reviewing (deep_work vs code_review)
- Edit-heavy bursts (`onDidChangeTextDocument` frequency high) → authoring.
- Selection/scroll-heavy with no edits, esp. in diff/SCM views → review-shaped.
- **But categorization is P1-A's job** (rule classifier sets `category`; the
  contract allows the collector to leave it `null` pre-classify). P1-B's job is
  to ship *rich, honest signal*: accurate `app`, `title`, `project`, `meta.lang`,
  and correct active-vs-idle segmentation. We can optionally add a `meta` hint
  (e.g. edit/read ratio) since `meta` is additive-only and unknown keys are
  ignored — but we should NOT set `category` ourselves.

---

## P1-B.2 — Capturing `project` + `lang`, and respecting redaction

### Language (`meta.lang`)
- **Canonical source: `TextEditor.document.languageId`** (e.g. `"typescript"`,
  `"python"`, `"go"`). Honors language overrides and extension-less files —
  strictly better than parsing the file extension.
- Fallback only if needed: `path.extname(document.fileName)`.
- For non-file editors (Output, Settings UI), `languageId` is not meaningful →
  send `meta.lang: null` (contract: a collector that cannot fill a field sends
  `null`, never omits the key).

### Project (`project`)
- **Primary: workspace folder name.** For a document, call
  `workspace.getWorkspaceFolder(document.uri)` → `.name`. This resolves the
  correct folder in **multi-root** workspaces (per-file, not the first folder).
- Files outside any workspace folder (loose files) → `project: null`.
- **Optional enrichment for better cross-source matching:** the built-in Git
  extension (`extensions.getExtension('vscode.git')?.exports.getAPI(1)`) exposes
  repositories, their root, and remote URL. Deriving the repo name (or
  `org/repo` from the remote) would make VSCode `project` line up with the
  GitHub source's `meta.repo` later (P2-D). Recommend: workspace folder name as
  the reliable baseline now; git-repo enrichment as a small follow-up, not a
  blocker.

### Redaction of file paths (the privacy-sensitive part)
Authoritative redaction is **daemon-side**: P1-A.8 maintains the user's regex
redaction list and hashes matching `title`/`url` values *before they hit the
local store* (system knowledge §8). The extension must NOT keep its own copy of
the user's regexes — single source of truth.

What the extension owns is **data minimization (defense in depth)**:
- **Never send absolute filesystem paths** (they leak home dir / usernames).
- `title` = `"<basename> — <project>"` (e.g. `"auth.ts — cadence-api"`), matching
  the contract's example exactly. Basename + workspace-folder name only.
- Honor the `cadence.redactPaths` setting (default `true`, per the Variables
  block):
  - `true`  → `title` = basename + project; no path beyond that.
  - `false` → may include workspace-**relative** path (still never absolute).
- The daemon's regex list then hashes any title the user has marked sensitive.

Net: extension minimizes by default, daemon does the regex hashing. The user's
redaction rules are respected without P1-B ever seeing them.

---

## Open coordination items (to file as NEEDS lines when P1-B.3+ starts)

These are the things P1-B will need from P1-A. They are **not** blocking the
exploration tasks, but they block implementation and are recorded here so the
NEEDS lines can be filed the moment `P1-A.CONTRACT` is ticked:

1. **Local route + port** (`P1-A.5`): exact `POST http://127.0.0.1:<port>/events`
   contract and `CADENCE_AGENT_PORT`. Currently blank in the phase doc.
2. ~~**Idle threshold alignment** (`P1-A.2`)~~ — **RESOLVED** (no NEEDS line
   required): P1-A.2 froze the OS idle threshold at **300s** (poll 5s, backdate
   idle start to last input). P1-B.3 adopts the same 300s default
   (`cadence.idleThresholdSec`) and backdates idle-close to the last interaction,
   so the two sources agree.
3. **Double-count reconciliation**: the OS active-window collector and the VSCode
   ext both report time spent in VSCode. The daemon should prefer the editor
   source (richer: file/lang/project) when both cover the same interval. Need to
   confirm how P1-A dedupes.
4. **Daemon-side redaction on collector events**: confirm P1-A.8's local
   redaction/hashing is applied to events arriving via the local POST route
   (not only to OS-collected events).
5. **`member_id` provenance**: the contract sets `member_id` at install. Does the
   extension fetch it from the daemon (a local `GET /identity`-style route), or
   does the daemon stamp/validate it on ingest? Need the mechanism from P1-A.

Non-issues (no dependency): `event_id` is collector-generated (uuid v4 in the
extension); `schema_ver` is the frozen constant `1` mirrored from the contract.
