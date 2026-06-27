# 02 — Progress Protocol

The point of this protocol: **a resumed session learns the exact state by
reading the Build Log, not by re-inspecting the codebase.** If you ever find
yourself opening source files to figure out "what's already done," the protocol
has been violated upstream — fix the log, then continue.

---

## 1. Task lifecycle

Every task in a phase doc has a stable ID, e.g. `P1-A.3`. Its checkbox in
`PROGRESS.md` moves through exactly these states:

```
[ ]  todo        not started
[~]  doing       in progress, started this session
[x]  done        complete, committed, verified
[!]  blocked     cannot proceed; reason recorded in the Build Log
```

---

## 2. The two strict rules

**Rule 1 — Update on the go, never in a batch.**
The instant a task changes state, update its checkbox in `PROGRESS.md` and
append one line to the Build Log. Do this *before* starting the next task. A
completed-but-untracked task is treated as **not done** by the next session and
may be redone — wasting work.

**Rule 2 — State lives in the log, not in your head or the code.**
The Build Log is the authoritative record. When you resume, read:
1. The stream's checklist block in `PROGRESS.md`
2. The stream's Build Log entries (newest 10 are enough)

That is sufficient to continue. Do **not** scan the repo to reconstruct state.

---

## 3. Build Log entry format

One line per state change, newest at the bottom of the stream's log block:

```
2025-06-01  P1-A.3  done   active-window collector + idle detection; commit a1b2c3d
2025-06-01  P1-A.4  doing  starting sqlite store schema
2025-06-02  P1-A.4  done   sqlite store w/ encryption; commit d4e5f6a
2025-06-02  P1-A.5  block  needs event schema v1 frozen in code — requesting from spine
```

Fields: `date  task-id  state  short note; commit <sha>` (commit only on `done`).

---

## 4. "Needs" lines (cross-stream requests)

If your stream is blocked on something another stream owns, do not reach into
their files. Record a needs-line in the **Coordination** block of `PROGRESS.md`:

```
NEEDS  P2-B -> P2-A : ingest endpoint /api/v1/ingest/events must accept schema_ver 1
NEEDS  P2-E -> P2-A : /api/v1/org/summary must return per-category daily buckets
```

The owning stream resolves it, ticks it, and the requester unblocks. This keeps
parallel work honest without merge conflicts.

---

## 5. Session start ritual (every session, every time)

```
1. Read 00-SYSTEM-KNOWLEDGE.md section relevant to your stream.
2. Read 01-GIT-CONVENTIONS.md (confirm git author config).
3. Read your stream block in PROGRESS.md + last ~10 Build Log lines.
4. Resolve any NEEDS lines addressed to your stream first.
5. Pick the next [ ] task in order; mark it [~]; begin.
```

## 6. Session end ritual

```
1. Ensure the current task is committed (or left clearly [~] with a note).
2. Confirm PROGRESS.md matches reality (every [x] is truly committed).
3. Append a final Build Log line summarizing where you stopped.
4. Run the authorship grep self-check; fix if needed.
```

---

## 7. Definition of Done (applies to every task)

A task is `[x]` only when **all** of these hold:
- Code builds and the stream's check command passes.
- The behavior is verified (test or manual check noted in the log).
- It is committed per git conventions (author = human, clean message).
- `PROGRESS.md` checkbox + Build Log line updated in that same commit.
- If it completes a stream, the stream's **"Variables to set"** block is filled.

---

## 8. Phase completion gate

A phase is complete only when:
- Every stream's tasks are `[x]`.
- The phase's **exit criteria** (in the phase doc) are demonstrably met.
- `LOCAL-SETUP.md` and `AWS-DEPLOY.md` are updated for anything new the phase
  introduced (new env vars, new run steps).
- The consolidated `ENV-VARIABLES.md` includes every variable the phase added.
