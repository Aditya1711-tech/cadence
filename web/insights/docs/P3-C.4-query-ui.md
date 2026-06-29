# P3-C.4 — NL query UI (`/web/insights`)

Self-contained Next 14 app (mirrors the `web/admin` toolchain + BFF pattern; the
shared web shell was deferred to a web spine that doesn't exist, so admin /
dashboard / insights are each self-contained).

## What it is
- **`/ask`** — a question box, example-prompt chips, and the result view:
  a prominent model caption, an optional hand-rolled SVG bar chart (shown when
  the result is a 2-column label→number breakdown), a result table, a "capped"
  banner when truncated, and a collapsible "View the SQL that ran".
- **`/login`** — admin sign-in (NL query is admin-only; §P3-C.1).

## Security posture (UI side)
- **BFF + httpOnly cookie session**, identical to admin: the browser only calls
  same-origin Next routes (`app/api/*`); the access/refresh tokens live in an
  httpOnly cookie and never reach JS. No backend CORS change.
- The browser calls `POST /api/query/nl` → BFF `proxyJson` → backend
  `POST /api/v1/query/nl` with the Bearer token attached server-side (invisible
  refresh-on-401 + cookie rotation). The UI never sees a token and never talks to
  the backend directly.
- All the real enforcement (admin gate, allowlist, validation, cadence_readonly
  execution, row cap) is server-side — the UI is a thin client over it. The
  caption copy tells the user results are "read-only, scoped to your org,
  aggregates only".

## Env (runtime, server-side — not NEXT_PUBLIC)
```
CADENCE_API_BASE=http://localhost:8080     # backend base (BFF target)
CADENCE_INSIGHTS_COOKIE=cadence_insights_session   # optional override
CADENCE_COOKIE_SECURE=false                 # "true" only behind HTTPS
```

## Verification
- `npm install && npm run lint && npm run build` GREEN (6 routes + middleware).
- Live walk-through (sign in → ask → chart/table/caption) needs a running backend
  with `CADENCE_NLQUERY_ENABLED=true` + the cadence_readonly role — deferred to
  deploy (same Docker/fresh-volume handoff as the backend path).

Check command: `cd web/insights && npm ci && npm run lint && npm run build`.
