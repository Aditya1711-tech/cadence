# P2-B.1 — Sync strategy (findings)

Goal: define how the daemon ships locally-stored events to the cloud ingest
endpoint **invisibly** — never blocking the user, recovering cleanly from days
offline, never sending a duplicate, and tolerant of clock skew. Maps to phase
tasks P2-B.3 (outbound loop) and P2-B.6 (backoff/offline durability).

> Verified against the as-built local store (`agent/internal/store/store.go`),
> the local API (`agent/internal/api/server.go`), and the P2-A ingest +
> auth contracts (`backend/.../ingest`, `backend/.../auth`,
> coordination block in `docs/PROGRESS.md`).

---

## Summary recommendation

- **Periodic pull-filter-push loop**, default every `CADENCE_SYNC_INTERVAL_SEC`
  (300s), plus an immediate first run shortly after start. Sync is a background
  goroutine started from `cmd/cadence-agent`; it never sits on the request path
  of any collector.
- **The wire is already settled.** A stored `event.Event` marshals to the exact
  snake-case `EventDto` the backend ingest accepts (every key present, nulls not
  omitted). No transform is needed — P2-B serializes events as-is and POSTs an
  array of ≤1000.
- **Idempotency is free and double-guarded.** Ingest is idempotent on
  `event_id` server-side (`ON CONFLICT(event_id, ts_start) DO NOTHING`), so a
  retried batch never double-counts. The client *also* tracks what it has sent
  so it does not waste bandwidth re-POSTing the whole store every cycle.
- **No client-side org-privacy filtering** (see §4): the daemon has no signal of
  the org privacy level, and the server enforces privacy on read regardless.
  Local user redaction is already applied at store time, so synced rows are
  already masked. P2-B.3's "privacy filter" reduces to "the data on disk is
  already redaction-safe; ship it; the server enforces the rest."
- **One open design decision** blocks a clean implementation: the store exposes
  no "give me un-synced events" surface and no append-order cursor. See §3 — it
  needs a decision before P2-B.3.

---

## 1. The loop

```
every interval (and once at startup):
  if not enrolled -> skip (nothing to send; log once at debug)
  batch = nextUnsynced(limit=1000)          // see §3 for how
  while batch not empty:
    POST /api/v1/ingest/events  Authorization: Bearer <access>
      200 -> mark batch synced; batch = nextUnsynced(1000)
      401 -> refresh token once; retry; if still 401 -> stop, surface re-enroll
      413 -> halve batch (defensive; we already cap at 1000)
      429/5xx/network -> stop this cycle; exponential backoff to next cycle
    on any stop: leave un-acked events un-synced (durable; retried next cycle)
```

- **Never blocks the user.** It is a timer-driven goroutine reading the same
  SQLite store the collectors write; SQLite WAL allows a reader alongside the
  single writer.
- **Batching.** Hard cap 1000 per POST (§6 + server returns 413 over). We pull
  in 1000-row pages until the store is drained, then sleep to the next tick.
- **First-run latency.** Kick an immediate sync ~10s after start so a freshly
  enrolled device shows data quickly, then fall to the interval cadence.

## 2. Offline durability & recovery from days offline

- Events live in the encrypted SQLite store regardless of connectivity; the
  sync loop is the *only* thing that needs the network. Offline = the loop's
  POST fails, the cycle stops, nothing is marked synced, and everything is
  retried next cycle. **No event is lost by being offline.**
- Coming back after days offline: the backlog is simply "every event not yet
  marked synced." We drain it 1000 at a time across as many cycles as needed.
  Because ingest is idempotent, even a half-acked batch (2xx lost on the wire)
  is safe to resend.
- **Backoff (P2-B.6):** on transient failure (5xx/429/network), back off the
  *next* cycle exponentially — base = interval, factor 2, cap ~30 min, full
  jitter — so a recovering backend is not stampeded by every device at once.
  Reset to base on the first success.

## 3. THE OPEN DECISION — how to track "un-synced"

The local store (`store.go`, owned by P1-A) exposes exactly three methods:

```go
Append(*event.Event) error
Query(from, to time.Time) ([]event.Event, error)   // by ts_start window only
Count() (int, error)
```

There is **no** "unsynced" notion, **no** `synced_at` column, and `created_at`
(append order) is **not queryable**. `Query` filters only by `ts_start`. So
P2-B cannot, today, ask the store "what haven't I sent?" Two viable designs,
and P2-B owns only `/agent/sync/` ("client glue only") — it must not edit
`store.go`:

**Option A — self-contained sidecar (no cross-stream dependency).**
P2-B owns a tiny second SQLite DB in `/agent/sync/` (e.g. `sync.db`) with one
table `synced(event_id TEXT PRIMARY KEY, synced_at_ms INTEGER)` plus a
persisted low-water-mark `scan_from_ms`. Each cycle:
`Query(scan_from_ms, now)` → drop rows already in `synced` → POST the rest →
record their ids in `synced` on 2xx → advance `scan_from_ms` to the oldest
*still-unsynced* event's `ts_start` (or `now − lookback` if none).
- ✅ Zero cross-stream dependency; fully parallel; respects ownership strictly.
- ✅ Durable + idempotent; survives days offline (watermark never advances past
  unsent events).
- ⚠️ Relies on `ts_start` windowing. An event *inserted late* with a
  `ts_start` older than an already-advanced watermark would be missed. This
  does **not** happen for the realtime collectors (OS/vscode/chrome stamp
  `ts ≈ now`); it *could* matter for future backfill sources (token/github).
  Mitigation: keep a generous fixed lookback floor (re-scan last 48h every
  cycle) on top of the watermark; document the limitation.

**Option B — ask P1-A for a store cursor (cleanest data model).**
File a NEEDS to P1-A to add `synced_at INTEGER` (nullable) to the `events`
table + `Unsynced(limit)` / `MarkSynced(ids)` methods. §7.1 of
00-SYSTEM-KNOWLEDGE explicitly anticipates this: *"A future store-schema change
(e.g. P2-B's sync cursor) is the agent's first real migration step."*
- ✅ Exact, single source of truth; no windowing edge cases; trivial query.
- ⚠️ Crosses ownership; reopens a completed Phase-1 stream; P2-B blocks until
  serviced (defeats the point of parallel waves).

**Recommendation: Option A** for P2-B v1 — it keeps the stream unblocked and
parallel, is correct for every realtime collector, and the only gap (late
inserts with old timestamps below the watermark) has no source in Phase 2.
Record a NOTE in coordination that the eventual clean form is the store
`synced_at` column (Option B) when P1-A next touches the store. **Flagged for
the operator to confirm before P2-B.3.**

## 4. Privacy & redaction on sync

- **Local user redaction is already applied at store time** — the local API
  runs `redactor.Apply` before `store.Append` (`server.go:133`). So titles/URLs
  matching the user's regex list are already hashed on disk and therefore in
  anything we sync. P2-B does **not** re-redact.
- **Org privacy level (full / categories_only / aggregate_only) is a
  server/org concept the daemon does not know.** The device-enroll response
  carries `member_id` + tokens but *not* the org privacy level, and §8 +
  P2-A.7 enforce privacy **server-side on read** (the org chose "store raw,
  redact on read"). So there is nothing for the client to filter beyond the
  user's local list. Sending raw (locally-redacted) events is correct and
  matches the frozen model. *(If we later want defense-in-depth client
  filtering, it needs the enroll response to expose the privacy level — a
  future NEEDS to P2-A, not required now.)*

## 5. Clock skew

- All event timestamps are UTC epoch-ms produced on-device; the server stores
  them as-is and never trusts a client `member_id`/`org_id`. Skew does not
  affect idempotency (keyed on `event_id`) or ordering of stored data.
- For the *sync watermark* we use the device's own monotonic-ish `ts_start`
  values consistently (compare device clock to device clock), so skew between
  device and server is irrelevant to what we choose to send.
- Auth is the one place server time matters: the access JWT has a 60-min server
  expiry. We treat a 401 as "refresh and retry" rather than pre-computing
  expiry from a possibly-skewed local clock, so a wrong device clock can never
  wedge sync.

## 6. Config surface (per phase doc)

```
CADENCE_CLOUD_BASE=http://localhost:8080      # backend base URL (prod: https://api.<domain>)
CADENCE_SYNC_INTERVAL_SEC=300                 # loop cadence
# tokens + member_id persisted in the OS keychain after enrollment (not env)
```
</content>
</invoke>
