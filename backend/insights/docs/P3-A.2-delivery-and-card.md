# P3-A.2 — Digest delivery, cadence, and the shareable card

**Status:** exploration (decisions feed P3-A.3–.7).
**Grounding:** `00-SYSTEM-KNOWLEDGE.md` §4 (digest job), §8 (privacy). Reuses the
Phase-2 `com.cadence.mail.Mailer` and the Anthropic SDK already on the classpath.

---

## 1. Cadence & trigger

- **Weekly**, `@Scheduled(cron = ${CADENCE_DIGEST_CRON:0 0 23 * * SUN})` — Sunday
  23:00, narrating the just-completed ISO week.
- The whole digest stack is `@ConditionalOnProperty(cadence.digest.enabled=true)`,
  **default false** — a dev box with no `ANTHROPIC_API_KEY` builds and boots the
  rest of the backend untouched (mirrors the P2-F worker gate). Build stays green.
- A manual/admin trigger (`POST` internal or a CLI run) drives the same pipeline
  for local dogfooding without waiting for Sunday.

## 2. Pipeline (per run)

```
for each org:
  for each active member with >= CADENCE_DIGEST_MIN_DAYS of data:
     1. compute MemberWeekFacts   (SQL only — P3-A.1 §3..§5)
     2. upsert insights row        (idempotent on org/member/iso_week/grain)
     3. narrate  -> Anthropic structured output {narrative, spotted[3]}
     4. render   -> shareable SVG card (server-side, dependency-free)
     5. persist  -> digests row (narrative + spotted + card_svg + status)
     6. deliver  -> Mailer (SMTP or LogMailer) + in-app via GET /insights/weekly
  compute OrgWeekFacts (privacy-bounded) -> same 2..6 for the admin digest
```

**Numbers come from step 1 (SQL) only.** Step 3 receives the facts JSON and writes
prose — it cannot introduce or alter a number (system prompt forbids it; the facts
are the sole input).

## 3. Narration (P3-A.6 detail)

- Model `CADENCE_DIGEST_MODEL=claude-sonnet-4-6`, official Anthropic Java SDK,
  `StructuredMessageCreateParams` (same pattern as `AnthropicCategorizer`).
- Structured output: `{ "narrative": string, "spotted": [{title, detail} × 3] }`.
  The three spotted insights map to the phase-doc trio: **peak hours**, **token
  efficiency**, **meeting load**.
- System prompt: "You are given pre-aggregated weekly facts as JSON. Write a warm,
  specific, plain-English summary. Use ONLY the numbers provided; never invent or
  recompute. If a delta is null, say it's the member's first weeks." Facts JSON is
  the user message.
- Failure = best-effort: on any API/parse error, fall back to a deterministic
  **template narrator** (facts → sentence templates) so a digest still ships. The
  pipeline never throws on a narration failure.

## 4. Delivery — SMTP only, console fallback (kickoff hard requirement)

Reuse `com.cadence.mail.Mailer` **unchanged**:
- `SmtpMailer` when `SMTP_HOST` is set (`SMTP_HOST/PORT/USERNAME/PASSWORD` +
  `SMTP_FROM`) — **no AWS, no provider API**.
- `LogMailer` otherwise — logs the fully rendered digest to console, so the
  pipeline is **testable with zero mail server** (local dogfooding).
- In-app delivery is the same persisted `digests` row surfaced by
  `GET /api/v1/insights/weekly` (P3-A.4).

**Env reconciliation (doc-vs-code, as prior streams did):** the phase-doc
`EMAIL_FROM` / `EMAIL_PROVIDER_API_KEY` variables are superseded by the as-built
SMTP model (`SMTP_*` + `cadence.mail.from`). Recorded so `ENV-VARIABLES.md` /
phase doc reflect the SMTP reality, not a provider API.

## 5. Shareable card — server-side SVG (the "wrapped" hook)

- **SVG string render only** (v1 decision) — dependency-free, crisp, scales; no
  headless browser, no image library added to the backend.
- Content: the week's hero numbers — deep-work hours, top category, commits,
  token cost, and a focus score (`100 − fragmentation_index`) — branded card.
- Stored as `digests.card_svg`; served by the endpoint. PNG rasterization for
  social embeds that don't preview SVG is a documented later add (would introduce
  an image lib) — out of scope for v1.

## 6. Variables (additive)

```
CADENCE_DIGEST_ENABLED=false           # gate: LLM + scheduler; off by default
CADENCE_DIGEST_MODEL=claude-sonnet-4-6
CADENCE_DIGEST_CRON=0 0 23 * * SUN     # Sunday 23:00
CADENCE_DIGEST_MIN_DAYS=14             # confidence floor (aligns with P3-B)
CADENCE_FRAGMENTATION_SATURATION=4.0   # see P3-A.1 §3.3
# delivery reuses existing SMTP_HOST/PORT/USERNAME/PASSWORD + SMTP_FROM
ANTHROPIC_API_KEY=                     # shared with P2-F (read from env by the SDK)
```
</content>
