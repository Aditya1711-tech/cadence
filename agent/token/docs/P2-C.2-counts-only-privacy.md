# P2-C.2 — Counts/cost/model only, never prompt or response text

**Stream:** P2-C · **Status:** exploration / privacy contract for the token watcher.

00-SYSTEM-KNOWLEDGE §8 is explicit: *"Token events never include prompt/response
content — only counts and cost."* This doc confirms that is enforceable given the
real log formats (see [P2-C.1](./P2-C.1-tool-log-locations.md)) and pins the rules
the parsers must follow.

## The risk is real and local

Both tools' session logs **do contain full conversation text** in the same files
we read for usage:
- Claude Code: every `type:"assistant"` / `type:"user"` line carries
  `message.content` (the actual prompt, model reply, tool inputs/outputs).
- Codex: the rollout file holds `base_instructions`, user messages, and model
  output alongside the `token_count` events.

So privacy is **not** about which files we open — it's about which **fields** we
extract from each line. The watcher reads sensitive files; it must emit only the
non-sensitive numbers.

## Allow-list (the ONLY fields the parser may extract)

Per source, the parser pulls exactly these and ignores everything else:

| Event Contract field | Claude Code source | Codex source |
|---|---|---|
| `ts_start` / `ts_end` | line `timestamp` | event `timestamp` |
| `meta.model` | `message.model` | model record / configured default |
| `meta.tokens_in` | `usage.input_tokens` (+cache sub-counts) | `last_token_usage.input_tokens` |
| `meta.tokens_out` | `usage.output_tokens` | `last_token_usage.output_tokens` |
| `meta.cost_usd` | computed from the above | computed from the above |
| `project` | line `cwd` → repo name | `session_meta.cwd` → repo name |
| raw sub-counts in `meta` | cache_read/creation, service_tier | cached_input, reasoning_output |

**Deny — never read, never store, never transmit:**
`message.content`, `base_instructions`, tool-call args/results, file paths inside
messages, system prompts, titles of work, any free text. `title` and `url` on the
emitted event are **always `null`** for `source:"token"`.

## Enforcement (defense in depth)

1. **Field-level decode.** Parsers decode logs into a *narrow* typed struct that
   has fields only for the allow-list above. `message.content` is decoded as
   `json.RawMessage` and dropped (never into a Go string we could leak), or
   omitted from the struct entirely so the JSON decoder skips it. There is no code
   path that turns conversation text into an event field.
2. **`project` is a name, not a path leak.** We reduce `cwd` to a repo/basename;
   we do not ship the full filesystem path as a title. (cwd → basename or git-root
   name; matches the daemon's existing redaction posture.)
3. **The daemon's existing redaction/privacy layers still apply.** Token events
   flow through the same loopback `/events` route as other collectors, so the
   daemon classifier/redactor and the backend's server-side privacy policy
   (full / categories_only / aggregate_only, P2-A.7) govern them too. Token events
   carry no title/url to redact, so they are safe at every privacy level —
   `aggregate_only` still gets daily token totals (that's the whole point of the
   `events_daily_tokens` rollup).
4. **No network egress from the watcher except to loopback.** The watcher talks
   only to `127.0.0.1:<port>/events`. It never calls the AI vendors' APIs (it
   reads local logs), so there is no second channel that could carry content.
5. **Test guard.** A unit test feeds a fixture log line whose `content` /
   `base_instructions` contains a sentinel string and asserts the produced event
   (full JSON) does **not** contain that sentinel anywhere — a regression trap
   against accidental content capture.

## Conclusion

Counts-only is fully achievable: the usage numbers we need live in dedicated
`usage` / `token_count` fields, fully separable from conversation text. The
contract is enforced by **extracting an allow-list, not by filtering a deny-list**,
and is backed by a sentinel test. `source:"token"` events are the
lowest-sensitivity events in the system — pure numbers, model id, and a project
name.
