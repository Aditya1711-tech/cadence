# P2-F.2 — Categorisation prompt + model design

**Stream:** P2-F · **Owns:** `com.cadence.worker`
**Grounding:** 00-SYSTEM-KNOWLEDGE §5 (categories, privacy of token events), §8
(privacy model), PHASE-2 P2-F, [[P2-F.1-escalation-rules]].
**API reference:** the `claude-api` skill (Java SDK section) — consulted for model
ID, structured-output support, and Haiku constraints.

> **Best-UX angle (phase doc):** structured input (app/title/url/project/role) →
> one of the fixed category enums; **never free-text categories.**

---

## 1. Model + SDK

- **SDK:** the official Anthropic **Java** SDK (`com.anthropic:anthropic-java`) —
  the backend is a single Spring Boot (Java 21) jar, so this is the correct
  surface (the skill mandates the official SDK for the project language). Raw HTTP
  is unnecessary.
- **Model:** `claude-haiku-4-5` (the `claude-api` catalog confirms this exact ID),
  configurable via `CADENCE_CATEGORIZE_MODEL`. Haiku is the cheapest tier and more
  than capable of a constrained 8-way label.
- **No thinking, no effort.** Categorisation is a fast, low-stakes single label.
  Haiku 4.5 **does not support the `effort` parameter** (it errors), and thinking
  adds latency/cost for no benefit here — so we omit both.
- **`max_tokens` small** (e.g. 256) — the output is one short structured object.

## 2. Forcing the fixed enum (no free-text categories)

Haiku 4.5 supports **strict tool use / structured outputs** (per the API
reference). We constrain the output to the frozen enum rather than parsing free
text:

- Define a single tool / output schema whose `category` field is an `enum` of
  exactly the 8 v1 categories: `deep_work, meetings, comms, research,
  code_review, ai_assisted, idle, other`, with `strict: true` /
  `additionalProperties:false`.
- The model **must** return one of those 8 — invalid categories are impossible at
  the API layer. The worker still defensively validates and falls back to `other`
  if anything is off (refusal, max_tokens, null).
- Optional second field `confidence` (`low|medium|high`) — useful for metrics and
  a future "leave as other if low" tuning lever; not load-bearing in v1.

## 3. Prompt shape (structured input)

**System prompt** (stable, cacheable prefix — frozen string, no per-event data):
defines the role, lists the 8 categories with a one-line definition each, and the
rules:
- pick exactly one; pick `other` only when genuinely none fit;
- `is_idle = true` ⇒ `idle`;
- token-source events are `ai_assisted`; code-host PR/commit URLs are
  `code_review`; chat/mail apps are `comms`; meeting apps/URLs are `meetings`;
- judge by what the developer was *doing*, not the raw app name alone.

**User message** (the per-event volatile part): a compact structured block of the
classification signals only —

```
source:    vscode
app:       Visual Studio Code
title:     auth.ts — cadence-api
url:       (none)
project:   cadence-api
is_idle:   false
duration:  283s
```

Mirrors the device ruleset's signals (app/title/url/source/is_idle) so cloud and
device agree on the same evidence. `project`/`duration` add context the regex
rules can't use. **No prompt/response content, no PII beyond what the event
already carries** (see §5).

## 4. Pattern-cache key (ties into P2-F.4)

The cache key is the **stable, low-cardinality signal** of an event, normalised so
repeats collide:
`source | app | normalised-title | url-host`
— e.g. title stripped to a pattern (drop trailing `— project`, collapse
file-specific bits where cheap), url reduced to host. Same key ⇒ reuse the prior
LLM category, never re-call. Cache lives in Postgres (a small worker-owned table,
requested from P2-A via NEEDS — P2-F does not write migrations) or Redis (already
in the stack by Phase 2); decided at P2-F.4.

## 5. Privacy considerations

- The worker reads **raw** `app/title/url` to categorise — consistent with the
  store-raw / redact-on-read decision (P2-A.1 §4); redaction is a *read*-path
  concern (P2-A.7), not an ingest/worker concern. The device already hashed any
  user-redaction-list matches before sync (§8), so titles/urls here are
  already device-filtered.
- Categorisation sends those signals to the Anthropic API. This is the core
  function of the worker and matches the architecture (§4 "worker → LLM
  categorisation"). It is worth noting explicitly in ops docs that enabling the
  worker means app/title/url leave the box for the LLM call.
- **Token-source events never include prompt/response content** (§8) — only
  counts/cost/model — and are trivially `ai_assisted` by rule anyway, so they
  rarely reach the LLM.

## 6. Output contract (worker-internal)

LLM call → `{ category: <enum>, confidence?: <low|medium|high> }` → validate →
write `events.category` back for `(event_id, ts_start)` under org context →
populate the pattern cache → mark job `done`. Invalid/absent ⇒ `other`.
