# P1-C.7 — Verification

Two layers: automated checks that run in CI/dev, and a manual end-to-end pass
that needs the live Go daemon + a real Chrome (a Go toolchain and a browser the
collector can drive aren't available in the doc-authoring env, so the live pass
is run on a founder machine — it also feeds the Phase-1 "14-day dogfood" gate).

## Automated (run anywhere)

```
cd ext-chrome
npm ci
npm run build      # tsc strict typecheck + esbuild bundle (the stream check cmd)
npm test           # node:test suites, 33 cases
```

The suites pin the behavior P1-C.7 asks for, minus live transport:

- **`categorize.test.ts`** — domains map to the right categories
  (github→code_review, meet/zoom→meetings, slack/gmail→comms, AI tools→
  ai_assisted, stackoverflow/docs→research), case-insensitive, unknown→null.
- **`emit.test.ts`** — `spanToEvent` is **Validate-clean** against a JS port of
  the daemon's `event.Validate()` (schema_ver, non-empty member_id, ts order,
  `duration_ms === ts_end-ts_start`, valid category, every key present), and the
  **privacy/redaction** behavior holds: `domain_only` emits origin-only `url`
  and `null` `title`; `full` keeps both.
- **`focusLogic.test.ts`** — the focus state machine: span-on-transition,
  duration accounting, sub-second noise filter, checkpoint restart.
- **`sites.test.ts`** — popup aggregation (per-domain totals, chrome-only,
  sorting, duration formatting).

## Manual end-to-end (founder machine, live daemon)

1. Start the daemon: `cd agent && go run ./cmd/cadence-agent`
   (binds `127.0.0.1:47821` by default; override with `CADENCE_AGENT_PORT`).
2. Build + load the extension: `cd ext-chrome && npm ci && npm run build`, then
   `chrome://extensions` → enable Developer mode → **Load unpacked** →
   `ext-chrome/dist`. If the agent port isn't 47821, set it in the popup.
3. Browse a few dev sites (github.com, a Meet call, stackoverflow.com) and a
   distraction (youtube.com). Switch tabs/windows; leave one idle > 60s.
4. Wait for a heartbeat (~1 min) so spans flush, then read the store:
   `curl -s 'http://127.0.0.1:47821/timeline' | jq '.[] | select(.source=="chrome")'`
5. **Assert (domain_only default):** chrome events present; `url` is origin-only
   (e.g. `https://github.com`, no path/query); `title` is `null`; `category`
   matches the domain; `is_idle` true on the idle span; `duration_ms` plausible.
6. **Privacy toggle:** set popup to **Full URL**, browse more, re-query — new
   events now carry full `url` + real `title`. Switch back to domain_only.
7. **Pause:** click Pause; confirm no new chrome events accrue; Resume restores.
8. **Popup:** open it — "today's top sites" lists domains by time with category
   tags; stop the daemon and reopen to see the friendly offline state.

## Out of scope here (owned elsewhere)

- Redaction-list **hashing** of titles/urls is the daemon's job (P1-A.8); the
  collector only minimizes at source. The daemon also re-applies org privacy
  policy server-side in Phase 2 (never trust the client).
- A daemon-shared `member_id` (OPEN NEEDS to P1-A); chrome uses an interim id.
