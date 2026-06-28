# P2-C verification (.3 parsers, .4 incremental tail, .5 backend aggregation)

## Agent collector — LIVE e2e (verified 2026-06-28, Windows dev box)

Built `cadence-agent` + `cadence-token`, ran the real daemon over a synthetic
but real-shaped Claude Code transcript (two `assistant` turns: an Opus turn with
cache tokens, a Sonnet turn), then read `/timeline`:

| model | tokens_in | tokens_out | cost_usd | project | title/url | priced |
|---|---|---|---|---|---|---|
| claude-opus-4-8 | 30004 | 830 | 0.080805 | cadence | null | true |
| claude-sonnet-4-6 | 1000 | 200 | 0.006000 | cadence | null | true |

- **Cost is exact.** Opus `(6126·5 + 830·25 + 3041·6.25 + 20837·0.5)/1e6 = 0.080805`;
  Sonnet `(1000·3 + 200·15)/1e6 = 0.006000`. The two cache pools are billed
  separately (Anthropic semantics).
- **tokens_in headline** = input + cache_read + cache_write for Anthropic
  (30004 for the Opus turn), = input for OpenAI/Codex.
- **Privacy (P2-C.2) holds live:** title/url are null; the `DO-NOT-LEAK` sentinel
  placed in `message.content` does NOT appear anywhere in the timeline JSON.
- **Project attribution (P2-C.4):** `cadence`, derived from the line's `cwd`.
- **Incremental tail (P2-C.4):** unit-tested — re-scan with no new bytes emits
  nothing; appending a line emits only the new turn; a fresh watcher resumes at
  the persisted cursor and emits nothing (no reparse/double-count). Cursor file
  `token-cursors.json` (path→offset).
- **Flow:** the watcher POSTs to the daemon loopback `/events` like every other
  collector — no daemon-code change needed (see OPEN needs-line for the eventual
  in-daemon wire).

`go build/vet/test ./token/...` green; cross-compiles for darwin/arm64 +
linux/amd64. The Codex parser path is unit-tested against the real `token_count`
shape (cached-input subset, null-info skip, cumulative-vs-delta) — no Codex run
on this box, but the format is verified from on-disk logs (docs/P2-C.1).

## Backend P2-C.5 — token aggregation endpoints

`com.cadence.token`: `GET /api/v1/me/tokens?range`, `GET /api/v1/org/tokens?range&team`
(admin; privacy-aware), backed by the `events_daily_tokens` continuous aggregate
that P2-A's schema defines but no endpoint consumed. Does NOT touch P2-A's query
package; per-model TokenSummary in `/me/summary` & `/org/summary` is unchanged.

- `cd backend && ./gradlew build` GREEN (JDK21 toolchain); wire-shape + range
  unit tests pass (`TokenWireAndRangeTest`, 3 tests).
- **DB-backed query NOT run here** — needs Postgres+TimescaleDB (no Docker on this
  Windows box; same limit as P2-A.10). Verified at code/SQL/contract level. Run on
  a Docker host alongside `./gradlew integrationTest`. Queries filter `org_id`
  explicitly (the CAGG is a separate hypertable RLS doesn't cover — per the schema
  note); `aggregate_only` returns org daily token totals but no per-member rows.
