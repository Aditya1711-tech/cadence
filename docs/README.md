# Cadence — Build Plan

> **Codename:** "Cadence" is a placeholder product name. Run a global
> find-and-replace across these docs once you pick the real name.

This folder is the **single source of truth** for building Cadence with multiple
Claude Code sessions running in parallel. It is written so that any session can
be opened cold, read only what it needs, and start producing correct work
without re-deriving decisions that were already made.

---

## Read order (humans)

1. `00-SYSTEM-KNOWLEDGE.md` — what we are building and every shared contract.
   **Everyone reads this first, always.**
2. `01-GIT-CONVENTIONS.md` — commit and branch rules. **Strict. Non-negotiable.**
3. `02-PROGRESS-PROTOCOL.md` — how progress is tracked so resuming is cheap.
4. `PHASE-1-foundation.md`, `PHASE-2-cloud-org.md`, `PHASE-3-ai-intelligence.md`
   — the actual work, split into parallel streams.
5. `LOCAL-SETUP.md` / `AWS-DEPLOY.md` — how to run and ship.
6. `ENV-VARIABLES.md` — consolidated variable reference.
7. `PROGRESS.md` — the living checklist. Updated constantly, never deleted.

---

## How to launch a Claude Code session against this plan

Each **stream** (e.g. `P1-A`, `P2-C`) is designed to be one session. Open a
session in the repo and paste a kickoff prompt of this shape:

```
Read, in this exact order and nothing else yet:
  1. docs/00-SYSTEM-KNOWLEDGE.md
  2. docs/01-GIT-CONVENTIONS.md
  3. docs/02-PROGRESS-PROTOCOL.md
  4. docs/PHASE-1-foundation.md  (only the section for stream P1-A)
  5. docs/PROGRESS.md            (only the P1-A block)

You are working ONLY on stream P1-A. Do not touch files owned by other
streams (see the ownership table in 00-SYSTEM-KNOWLEDGE.md).

Follow the progress protocol exactly: update PROGRESS.md after every task,
before moving to the next. Follow the git conventions exactly: no co-author
trailers, no tool attribution, author is me only.

Start with the Requirements Exploration tasks for P1-A. Stop and show me your
findings before writing implementation code.
```

---

## The parallelization model (why this works)

Parallel sessions collide when they share **interfaces** or **files**. We remove
both sources of collision up front:

1. **Contract-first.** Every interface a stream depends on — event JSON shapes,
   REST paths, DB tables, env var names — is frozen in `00-SYSTEM-KNOWLEDGE.md`
   *before* any parallel work starts. Streams build against the contract, not
   against each other.

2. **Directory ownership.** Each stream owns a disjoint set of directories
   (ownership table in `00-SYSTEM-KNOWLEDGE.md`). Two streams never edit the
   same file, so git merges stay clean.

3. **Waves.** Inside each phase, one **spine** stream must finish first because
   it writes the shared contracts into code. After the spine, the remaining
   streams run fully in parallel.

```
Phase = [ Wave 0: spine (1 session) ] -> [ Wave 1: N streams in parallel ]
```

Launch the spine session alone. When its "contract frozen" checkpoint is ticked
in `PROGRESS.md`, launch every Wave-1 session at once.

---

## Golden rules (every session, every phase)

- Read the **Build Log** in `PROGRESS.md` to learn state. **Never** re-inspect
  the whole codebase to figure out what is done.
- Update `PROGRESS.md` the moment a task changes state. An untracked completed
  task is treated as not done.
- Stay inside your stream's owned directories.
- Commit per the git conventions. Author is the human only.
- When a stream finishes, fill in its **"Variables to set"** block so the next
  person knows exactly what to configure.
