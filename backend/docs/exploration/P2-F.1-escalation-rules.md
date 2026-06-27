# P2-F.1 — When to escalate from the rule classifier to the LLM

**Stream:** P2-F (categorisation worker) · **Owns:** `com.cadence.worker`
**Grounding:** 00-SYSTEM-KNOWLEDGE §5 (Event Contract categories), §7 (`job_queue`),
§8 (privacy), PHASE-2 P2-F, Coordination NOTE `P2-A -> P2-F` (payload shape).

> **Best-UX angle (phase doc):** keep cost low — escalate only low-confidence /
> ambiguous events, batch, cache by app+title pattern, use Haiku.

---

## 1. Where categories come from today (as-built)

The category enum is frozen (§5): `deep_work, meetings, comms, research,
code_review, ai_assisted, idle, other`.

Two classifiers exist *before* the cloud worker ever sees an event:

1. **Device rule classifier (P1-A.7, `agent/internal/classify`).** An ordered
   regex ruleset (app/title/url/source/is_idle → category, first-match-wins) with
   a **default category of `other`**. It is wired into the daemon's local
   `POST /events`, so every locally-collected event is stamped with *some*
   category — at minimum `other` — before it is stored or synced.
2. **Cloud ingest (P2-A.4, `IngestService`).** On `POST /api/v1/ingest/events`
   it enqueues a `categorize` job **only when `e.category() == null`**
   (`IngestService.java:64`).

### The gap this exposes

A daemon that ran the rule classifier never sends `null` — it sends `other` for
anything it couldn't match. So with ingest's current `null`-only trigger, the
worker would **never** see the very events P2-F.1 exists to handle: the ones the
rule classifier punted on. Events arrive `null` only when a producer skipped
local classification (a future thin API client; a source wired straight to
ingest). Both real cases of "the rules don't know" — explicit `other` and missing
`null` — should escalate.

---

## 2. Decision: the escalation trigger

**Escalate an event to the LLM when its category is `null` OR `other`.**

- `null` — no classifier ran; nothing is known.
- `other` — the rule classifier ran and explicitly gave up (its default). This is
  precisely the "low-confidence / ambiguous" case the task targets.
- Any of the 7 *specific* categories (`deep_work`, `meetings`, … not `other`) is
  treated as confident — **never** re-categorised. The rules are cheap and
  high-precision on the cases they match; spending Haiku tokens to second-guess
  them burns money for no signal.

This needs a one-line change in P2-A's ingest (also enqueue on `other`), filed as
a NEEDS line. **The worker does not depend on that change to be correct:** on
claim it re-reads the event and only calls the LLM if the category is still
`null`/`other` (see §4). So shipping order is independent — P2-F works on the
`null` stream today and automatically picks up `other` once ingest is extended.

---

## 3. Cost-control layers (so "escalate on null|other" stays cheap)

Escalation volume is bounded by four independent layers, cheapest first:

1. **Rule classifier on-device** already resolves the large majority of events to
   a specific category — those never enqueue.
2. **Pattern cache (P2-F.4).** Key a cache on the stable signal of an event
   (`source` + `app` + normalised title + url-host). The first
   `Visual Studio Code | main.go` resolves via LLM; every later identical combo
   reads the cached category and never hits the API. App/title combos repeat
   heavily within a developer's day, so the cache hit-rate should be high.
3. **Per-org daily token cap (P2-F.5).** A hard ceiling
   (`CADENCE_CATEGORIZE_DAILY_TOKEN_CAP`); once hit, the worker stops calling the
   API for that org that day and leaves the event `other` (jobs are left
   `pending`/`run_after` deferred, not failed) so the cap is a soft-degrade, not
   data loss.
4. **Claim batching.** The worker claims jobs in small batches
   (`FOR UPDATE SKIP LOCKED`) and processes them on a virtual-thread pool, so one
   poll amortises across many events without holding row locks.

---

## 4. Worker-side idempotency / re-check (independent of ingest trigger)

When the worker claims a `categorize` job it:

1. Binds org context from `job_queue.org_id` (RLS) — `Tenancy.bind`.
2. `SELECT` the event by `(event_id, ts_start)` from the payload (that pair is the
   events PK / idempotency key).
3. **Re-checks the live category.** If it is now a specific category (the event
   was re-ingested with a category, or another worker already handled it) → mark
   the job `done`, no API call. Only `null`/`other` proceed to the LLM.
4. On success, write the category back and mark the job `done`.

This makes the trigger decision (ingest enqueues `null` vs `null|other`) a pure
throughput knob, never a correctness dependency, and makes re-delivered jobs safe.

---

## 5. Failure / retry semantics

- **Transient API error** (timeout, 429, 5xx) → leave job `pending`, bump
  `attempts`, push `run_after` out with backoff. Stop retrying after a small
  cap (e.g. 5) → mark `failed`; the event simply stays `other`.
- **Refusal / unparseable / invalid-enum output** → default the category to
  `other` and mark the job `done` (categorisation is best-effort; never block).
- **Cap reached** → defer (see §3.3), do not fail.

A missing category is never fatal: the read path and dashboards already treat
`other`/unclassified as a valid bucket.

---

## 6. Open coordination items

- **NEEDS P2-F → P2-A:** extend ingest to also enqueue a `categorize` job when an
  event arrives with `category == 'other'` (not only `null`). One-line change in
  `IngestService.enqueueCategorize`'s guard. Filed in PROGRESS Coordination.
- **CONFIRMED payload (NOTE P2-A → P2-F):** `{"event_id","ts_start"}` + row
  `org_id` is **sufficient** — it is exactly the events PK plus the RLS key. No
  extension requested.
