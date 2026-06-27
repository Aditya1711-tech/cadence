# P2-C.1 — Where AI coding tools write usage locally

**Stream:** P2-C (AI token watcher) · **Status:** exploration, findings below are
verified against real on-disk logs on this machine (2026-06-27) unless marked
*(by reference)*.

**Goal (best-UX angle):** zero config. Detect installed tools automatically by
probing well-known per-user paths; no API keys, no manual setup. We only ever
read **token counts, model id, and timestamps** — never prompt/response text
(see [P2-C.2](./P2-C.2-counts-only-privacy.md)).

The watcher turns each model turn into one Event Contract event with
`source:"token"` and `meta.model / tokens_in / tokens_out / cost_usd`
(00-SYSTEM-KNOWLEDGE §5), posted to the daemon's loopback `/events` route like
every other collector.

---

## Summary table

| Tool | Local log? | Path (per-user) | Format | Token usage | Cost in log? | Project from |
|---|---|---|---|---|---|---|
| **Claude Code** | ✅ yes | `~/.claude/projects/<cwd-slug>/<sessionId>.jsonl` | JSONL, 1 msg/line | `message.usage.*` on `type:"assistant"` lines | ❌ no — compute | `cwd` field (exact) |
| **Codex CLI** | ✅ yes | `~/.codex/sessions/YYYY/MM/DD/rollout-<ts>-<uuid>.jsonl` | JSONL | `event_msg.token_count.info.last_token_usage` | ❌ no — compute | `session_meta.payload.cwd` |
| **Cursor** | ⚠️ no usable local counts | `~/.cursor/` (not present here) | usage is server-side | not in local files | n/a | n/a |

`~` is the user home on every OS. On Windows the same dirs live under
`C:\Users\<user>\` and `$CLAUDE_CONFIG_DIR` / `$CODEX_HOME` override them.

---

## 1. Claude Code  *(verified)*

**Location.** `~/.claude/projects/<slug>/<sessionId>.jsonl`. One subdirectory
per working directory; `<slug>` is the absolute cwd with path separators and `:`
replaced by `-`. Example seen here: `c:\learn\cadence` →
`C--learn-cadence`. Each `.jsonl` is one session; one JSON object per line.

**Override:** `$CLAUDE_CONFIG_DIR` relocates the whole `~/.claude` tree (we will
honor it, plus our own `CADENCE_CLAUDE_CODE_LOG_DIR` override from the phase doc).

**Usage record.** Assistant turns are `type:"assistant"` lines. Verified shape:

```jsonc
{
  "type": "assistant",
  "timestamp": "2026-06-27T09:57:53.731Z",   // RFC3339 UTC, ms — use for ts_start/ts_end
  "cwd": "c:\\learn\\cadence",                // exact project attribution
  "sessionId": "8aa9c364-…",                  // session grouping
  "gitBranch": "master",
  "requestId": "…", "uuid": "…", "parentUuid": "…",
  "message": {
    "model": "claude-opus-4-8",               // -> meta.model
    "role": "assistant",
    "content": [ … ],                         // PROMPT/RESPONSE TEXT — never read (see P2-C.2)
    "usage": {
      "input_tokens": 6126,
      "output_tokens": 830,
      "cache_creation_input_tokens": 3041,
      "cache_read_input_tokens": 20837,
      "cache_creation": { "ephemeral_1h_input_tokens": 3041, "ephemeral_5m_input_tokens": 0 },
      "service_tier": "standard"
    }
  }
}
```

**Key facts confirmed by inspection:**
- **No cost field** anywhere in the transcript (`grep costUSD|cost_usd|total_cost`
  → 0 hits across a 92-turn session). **Cost must be computed** from token counts
  × per-model pricing (decided in P2-C.3).
- Anthropic cache semantics: `cache_read_input_tokens` and
  `cache_creation_input_tokens` are **separate from** `input_tokens` and billed at
  different rates (cache-read cheap, 5m vs 1h cache-write differ). A correct
  `cost_usd` needs four rate tiers per model: input, output, cache-write, cache-read.
- Contract mapping: `tokens_in = input_tokens (+ cache_creation + cache_read)`,
  `tokens_out = output_tokens`. We keep the raw sub-counts in `meta` (additive)
  so the cost is auditable and re-priceable; `tokens_in/out` stay the headline
  numbers the dashboards already query (P2-A `events_daily_tokens`).
- Many lines are non-assistant (`user`, `system`, tool results, summaries) and
  some assistant lines have no `usage`. Parser must skip anything without
  `message.usage`.

## 2. Codex CLI  *(verified)*

**Location.** `~/.codex/sessions/YYYY/MM/DD/rollout-<ISO-ts>-<uuid>.jsonl`
(override: `$CODEX_HOME`). One file per session, JSONL.

**Session meta** is the first line:

```jsonc
{ "type": "session_meta", "timestamp": "…Z",
  "payload": { "id": "019c24b9-…", "cwd": "c:\\vyttah\\…",   // project attribution
               "model_provider": "openai", "originator": "codex_vscode",
               "source": "vscode", "cli_version": "0.94.0-alpha.10" } }
```

**Usage record.** `type:"event_msg"`, `payload.type:"token_count"`:

```jsonc
{ "type": "event_msg", "timestamp": "…Z",
  "payload": { "type": "token_count",
    "info": {
      "last_token_usage":  { "input_tokens": 10200, "cached_input_tokens": 8448,
                             "output_tokens": 58, "reasoning_output_tokens": 0,
                             "total_tokens": 10258 },   // THIS TURN's delta — use this
      "total_token_usage": { … cumulative … },          // running total — don't double-count
      "model_context_window": 258400 } } }
```

**Key facts confirmed:**
- **No cost field** → compute from tokens × pricing.
- OpenAI cache semantics differ from Anthropic: `cached_input_tokens` is a
  **subset of** `input_tokens` (not additive). Cost = `(input − cached)·in_rate +
  cached·cached_rate + output·out_rate`. `reasoning_output_tokens` is part of
  `output_tokens`. The per-tool cost adapter must encode these differences.
- Use **`last_token_usage`** per `token_count` event for incremental tailing;
  `total_token_usage` is cumulative and would double-count.
- The **model id is not in `session_meta`** (only `model_provider:"openai"`).
  It must be read from a `turn_context` / model-selection record in the same
  file, falling back to a configured default (`CADENCE_CODEX_DEFAULT_MODEL`).
  Flagged as a P2-C.3 parser detail; needs one more pass over a session that
  switched models.
- Early `token_count` events can have `info:null` (e.g. the first one, which
  only carries `rate_limits`). Parser must skip null-info events.

## 3. Cursor  *(by reference — not installed here)*

`~/.cursor/` is absent on this box. Cursor does not write per-turn token counts
to a stable, documented local file; usage/billing is tracked **server-side** and
surfaced in Cursor's dashboard + the Admin API (team plans). Cursor's local
state (SQLite under the VS Code-style `globalStorage`) holds chat history, not a
clean usage ledger, and its schema is undocumented and unstable.

**Decision:** Cursor is **deferred** from the zero-config local watcher. Capturing
it cleanly belongs to a future server-side connector against the Cursor Admin
API (an org-level integration, like P2-D GitHub), not a local log tail. The
watcher will list `cursor` in `CADENCE_TOKEN_SOURCES` as *recognized but
unavailable-locally* so the choice is explicit, not a silent gap.

---

## Auto-detection plan (zero config)

On start the watcher probes, in order, honoring env overrides:
1. **Claude Code:** `$CADENCE_CLAUDE_CODE_LOG_DIR` → `$CLAUDE_CONFIG_DIR/projects`
   → `~/.claude/projects`. Present ⇒ enable.
2. **Codex:** `$CODEX_HOME/sessions` → `~/.codex/sessions`. Present ⇒ enable.
3. **Cursor:** recognized, not locally tailed (see above).

`CADENCE_TOKEN_SOURCES` (default `claude_code,codex,cursor`) filters which
detected sources are active; an explicit list overrides auto-detection. A tool
whose dir is missing is skipped silently (it's just not installed) — never an error.

## Incremental tail (feeds P2-C.4)

- Track a per-file cursor = `(path, byte-offset, sessionId)` persisted in the
  daemon store dir (`~/.config/cadence/token-cursors.json`), so a restart never
  reparses or double-counts. New bytes only.
- Watch directories for new/rotated session files (Codex rotates per session and
  by date; Claude Code adds a file per session). Poll-based (no OS fs-notify dep)
  on a modest interval — token logs are low-frequency.
- `project` = the session's `cwd` mapped to a best-effort repo name (basename, or
  git-root name when resolvable); falls back to the cwd-slug for Claude Code.

## Open items for implementation
- Pin per-model pricing tables (Anthropic 4-tier; OpenAI cached-subset) with a
  cited source and make them config-overridable (`CADENCE_TOKEN_PRICING_PATH`).
  Anthropic/Claude rates: source via the `claude-api` skill, not from memory.
- One more read of a Codex session to lock the model-id record path.
- Confirm Claude Code `timestamp` is per-line (it is) so each event gets a real
  `ts_start`/`ts_end`; token turns are near-instant, so `ts_end ≈ ts_start`
  (duration_ms small but non-negative).
