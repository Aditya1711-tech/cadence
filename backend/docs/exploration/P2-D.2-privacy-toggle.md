# P2-D.2 — GitHub privacy toggle: `commit_messages_only` vs `full_diff`

Exploration deliverable for stream **P2-D**. Designs the hard privacy toggle that
governs how much GitHub data Cadence ever stores. Pairs with
`P2-D.1-github-integration-model.md`.

Grounding: `00-SYSTEM-KNOWLEDGE.md` §8 — *"GitHub integration has a hard toggle:
`commit_messages_only` (default) vs `full_diff` (opt-in). **Never default to
reading code.**"* Phase-2 doc P2-D.2 / P2-D.5 — *"store messages or
messages+diff-stats only."* Env default: `GITHUB_DEFAULT_MODE=commit_messages_only`.

---

## 1. The core privacy promise

Cadence is trust-first and **"no screenshots, ever"** (§1). The GitHub equivalent
is **we never store source code.** That is non-negotiable and is true in *both*
modes. The toggle is therefore **not** "metadata vs code" — it is:

| Mode | What Cadence stores | What it NEVER stores | GitHub permission needed |
|---|---|---|---|
| **`commit_messages_only`** (default) | commit message (subject), sha, repo, branch, author→member, PR title/number/action, timestamps | file names, diff stats, **patch/code** | `metadata: read` + push/PR webhook events only |
| **`full_diff`** (opt-in) | the above **+ numeric diff *stats*** (additions, deletions, changed-file *count*) | the actual patch / code / file contents — **still never stored** | additionally `contents: read` |

Read the name `full_diff` carefully: per P2-D.5 even the "full" mode stores only
**diff *stats*** (the numbers), never the diff text. There is no third mode that
stores code, by design. "Never read code" is upheld absolutely; the toggle only
controls whether we additionally compute the lines-changed *magnitude*.

> Rationale for storing stats, not patches, even in the opt-in mode: lines-added/
> removed is a useful effort signal for the dashboards, but the patch body is
> literal source code. Storing counts gives the signal without ever persisting
> code — consistent with the product's identity. If a future customer truly wants
> patch-level analysis it is a *new, separately-gated* feature, not this toggle.

---

## 2. Why the permission scope is the real enforcement

The strongest privacy guarantee is **not asking GitHub for the data in the first
place**:

- In `commit_messages_only`, the Cadence GitHub App requests **only
  `metadata: read`** + subscribes to `push`/`pull_request` webhook events. The
  push webhook payload already contains the commit message, sha, branch, and
  author — so Cadence makes **zero API calls** and has **no technical ability to
  read file contents**. Privacy is enforced by capability, not just by code.
- `full_diff` requires the org admin to grant the App **`contents: read`**. Only
  then can Cadence mint an installation token and call the commits/compare API to
  read the `stats` block (`{additions, deletions, total}`) and per-file change
  counts. Even with that permission, the receiver **extracts only the numeric
  stats** and discards the `patch` field — code is never written to the DB.

So the toggle has teeth at three layers: (1) the granted GitHub permission,
(2) whether we call the API at all, (3) what the parser persists.

---

## 3. Where the toggle lives & how it is resolved

- **Per-org**, stored as `github_installations.mode` (see P2-D.1 §4 NEEDS).
  Default seeded from `GITHUB_DEFAULT_MODE` (`commit_messages_only`).
- An org admin flips it in the admin UI (P2-E). Flipping to `full_diff` should
  prompt the admin to also grant `contents: read` to the App on GitHub (the UI
  surfaces the permission-upgrade link); until the permission is present, the
  enrichment API call will 403 and the receiver **degrades to messages-only**
  (logs a warning, stores the commit without stats) rather than dropping the
  event. Privacy-safe failure mode.
- Resolution happens in the webhook path: after `installation_id → org_id`, read
  `mode`; the `GithubWebhookService` branches on it.

---

## 4. Field-level mapping under each mode (what actually gets written)

`push` → one event per commit. `meta` (jsonb, additive per §5):

```jsonc
// commit_messages_only
"meta": {
  "commit_sha": "abc1234...",
  "repo":       "acme/cadence-api",
  "branch":     "main"
}
// title = commit message subject line (first line)

// full_diff  (adds numeric stats only; NEVER a patch/code)
"meta": {
  "commit_sha":    "abc1234...",
  "repo":          "acme/cadence-api",
  "branch":        "main",
  "additions":     42,
  "deletions":     7,
  "changed_files": 3
}
```

- `meta.commit_sha` and `meta.repo` satisfy the Event Contract §5 example for the
  github source. `branch` / stats are additive `meta` (allowed forever, §5).
- The commit **message body** beyond the subject line is *not* stored — only the
  subject (consistent with how editor/browser titles are single-line). This also
  avoids accidentally persisting long messages that paste in secrets/diffs.
- **No file paths are stored in either mode.** The push payload's
  `added/removed/modified` arrays are file *paths* (arguably sensitive directory
  structure); we use only their **lengths** to derive `changed_files` in
  `full_diff` and otherwise ignore them. `commit_messages_only` stores neither
  paths nor counts.

`pull_request` → one event. `title` = PR title; `meta = {repo, pr_number,
action}`. PR titles/numbers are metadata, stored in both modes.

---

## 5. Interaction with the org-wide privacy level (§8 levels)

Two privacy controls coexist and are **independent**:

1. **Org `privacy_level`** (`full` / `categories_only` / `aggregate_only`) —
   applied on **read** (P2-A.7, store-raw-redact-on-read decision). Governs what
   the admin *sees* across all sources.
2. **GitHub `mode`** (`commit_messages_only` / `full_diff`) — applied on
   **ingest/storage**. Governs what GitHub data is ever *stored*.

They compose cleanly. A commit message is stored in the `events.title` column
(same as any title). On read, the org's `privacy_level` then applies: under
`categories_only` the read layer strips `title`/`url`/`app` (P2-A.7
`redactForAdmin`), so even a stored commit message is not shown to the admin —
only the category/duration/project (here: repo + "commit activity" count). Under
`full` the admin sees commit subjects. This means:

- `commit_messages_only` + `categories_only` ⇒ admin sees commit *counts per
  repo*, no messages. (Strongest, and a very plausible default posture.)
- `commit_messages_only` + `full` ⇒ admin sees commit subjects, no diff stats.
- `full_diff` + `full` ⇒ admin sees subjects + lines-changed magnitude.

No new read-layer work is required from P2-D: because we store into the standard
`events` columns + `meta`, P2-A.7's existing redaction already governs the read
side. P2-D's responsibility is purely the **ingest/storage** discipline above.
(One note for P2-E: the diff-stat `meta` keys are only meaningful when
`privacy_level = full`; the admin token/commit panel should treat them as
optional.)

---

## 6. Decisions (this exploration) — pending user confirmation

1. **Two modes only**; `commit_messages_only` is the default. **Code/patch is
   never stored in either mode** — `full_diff` adds numeric *stats* only.
2. **Enforcement is primarily by GitHub permission scope**: default mode requests
   only `metadata:read` and makes no API calls; `full_diff` requires
   `contents:read` and still discards the patch.
3. **Mode is per-org** (`github_installations.mode`), default-seeded from
   `GITHUB_DEFAULT_MODE`; flipped in the admin UI (P2-E).
4. **Privacy-safe degradation**: if `full_diff` is set but the App lacks
   `contents:read` (or the API errors), store the commit messages-only and warn —
   never drop the event, never block.
5. **No file paths stored** in either mode (only a derived `changed_files` count
   in `full_diff`).
6. GitHub `mode` (storage) and org `privacy_level` (read) are **independent and
   compose** via the existing P2-A.7 read-time redaction — no new read code in
   P2-D.
