# 01 — Git Conventions (STRICT)

These rules are **non-negotiable** and apply to every session, every commit.
If any instruction elsewhere conflicts with this file, this file wins.

---

## 1. Authorship — the hard rule

Every commit must appear authored **only by the human repository owner**. There
must be **no trace** of any AI tool, assistant, or co-author anywhere in the
commit.

**FORBIDDEN in every commit message — never include any of these:**
- `Co-Authored-By:` trailers of any kind
- `Co-Authored-By: Claude <...>`
- `🤖 Generated with [Claude Code]` or any tool-attribution line
- Any mention of Claude, Anthropic, AI, assistant, LLM, or "generated"
- Any name, email, or handle other than the repository owner's

The commit author and committer must be the human. Set this once per machine
and verify it before the first commit:

```bash
git config user.name  "Your Name"
git config user.email "you@example.com"

# verify
git config user.name
git config user.email
```

**Self-check before every commit.** Run this; it must print nothing:

```bash
git log -1 --format='%an <%ae>%n%b' | grep -iE 'co-authored|claude|anthropic|generated with|assistant' && echo "VIOLATION" || echo "clean"
```

If it prints `VIOLATION`, amend the commit and remove the offending content
before pushing:

```bash
git commit --amend       # delete the offending lines in the editor
```

Do not add tool-attribution even if a default template suggests it. Strip it.

---

## 2. Commit message format

Messages describe **what changed**, in the imperative mood. Conventional-commit
prefix, then a concise subject, then an optional body that lists concrete
changes. Nothing about who or what produced the change.

```
<type>(<scope>): <subject line, imperative, <= 72 chars>

<optional body: bullet list of concrete changes, wrapped at 72 cols>
```

**Types:** `feat`, `fix`, `refactor`, `perf`, `test`, `docs`, `chore`, `build`.
**Scope:** the stream's area, e.g. `agent`, `ingest`, `admin`, `dashboard`.

**Good examples:**

```
feat(agent): add active-window collector with idle detection

- poll active window every 5s via platform API
- mark events idle after 60s of no input
- write events to encrypted sqlite store
- precompute duration_ms on event close
```

```
fix(ingest): make event upload idempotent on event_id

- upsert on conflict (event_id) do nothing
- reject batches larger than 1000 with 413
```

**Bad examples (never do this):**

```
feat: stuff                          # too vague
Update files                         # says nothing
feat(agent): add collector

Co-Authored-By: Claude <...>         # FORBIDDEN
🤖 Generated with Claude Code        # FORBIDDEN
```

---

## 3. Branching

- One branch per stream: `stream/p1-a-agent`, `stream/p2-e-admin`, etc.
- The spine merges to `main` first. Parallel streams branch off `main` after the
  spine's contract checkpoint is ticked, then merge back via PR (or fast-forward
  if solo).
- Rebase your stream branch on `main` before merging to keep history linear.
- Never force-push `main`.

---

## 4. Commit cadence

- Commit at the end of every completed task (see the progress protocol). One
  task → one commit is the ideal granularity.
- A commit must build. Do not commit code that fails the stream's local check
  command listed in its phase doc.
- Update `PROGRESS.md` in the **same commit** as the work it describes, so state
  and code never drift apart.

---

## 5. Pre-commit checklist (paste into each session's working notes)

```
[ ] code builds / lints clean (stream's check command)
[ ] PROGRESS.md updated for this task in this same commit
[ ] commit message: conventional prefix + concrete what-changed body
[ ] NO co-author trailer, NO tool attribution, NO non-owner name
[ ] ran the authorship grep self-check -> "clean"
[ ] only touched files inside this stream's owned directories
```
