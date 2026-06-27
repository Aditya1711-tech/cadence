# P1-B.7 — Verification (VSCode extension)

Goal (phase doc): confirm events land in the local store with correct
`project` / `lang` and classify as `deep_work` / `code_review` per the ruleset.

## Automated (hermetic, `npm ci && npm run compile && npm test`)
22 `node:test` unit tests, no VS Code host or network required:
- `session.test.ts` (9) — focused-session segmentation: focus gate, idle
  backdating, context switch, debug-keeps-counting, pause, zero-duration drop.
- `emitter.test.ts` (10) — Event Contract mapping (every key present, nullables
  null, `duration_ms == ts_end-ts_start`, title = basename + project), batched
  POST, retain-on-failure, bounded queue, snapshot/restore.
- `identity.test.ts` (3) — provisional member-id generate/persist/reuse.

## Manual end-to-end (against the real daemon, incl. the P1-A.7 classifier)
Built `agent` at master (classifier wired into `POST /events` via
`api.Options.Classifier`, applied when `category == nil`). Ran the daemon on a
temp DB/port, generated an event with the **extension's own compiled
`mapSegment`** (so this exercises P1-B code, not a hand-written payload), POSTed
it, and read it back from `GET /timeline`:

| Check | Result |
|---|---|
| Emitter sends `category` | `null` (classifier fills it) |
| `POST /events` | `{accepted:1, rejected:0}` |
| Idempotency on `event_id` (POST same event ×3) | one stored row |
| `source` | `vscode` |
| `project` | `cadence-api` (workspace folder) |
| `meta.lang` | `typescript` (`languageId`) |
| `is_idle` | `false` (only focused segments emitted) |
| **`category` after classify** | **`deep_work`** |

`deep_work` is correct: a `vscode`-source, non-idle event with `url=null` matches
the ruleset's `editor-source` rule. `code_review` in the default ruleset is
URL-based (PR/commit pages) and therefore applies to the Chrome source, not
VSCode — VSCode coding time is `deep_work` by design.

## Status
Fully verified. (Earlier the classification half was blocked on P1-A.7; with the
classifier merged to master it is now confirmed.)
