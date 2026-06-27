# P1-D.8 — End-to-end verification (real local data)

Verified the dashboard renders **real** local data sourced from the actual
Go daemon (P1-A.5) and its encrypted SQLite store — not the mock.

## Path exercised

```
browser → dashboard /api/timeline proxy (server-side)
        → cadence-agent GET /timeline (127.0.0.1:47821)
        → encrypted SQLite store (key in OS keychain)
```

## Steps (reproducible)

1. Build + run the daemon with a throwaway store:
   ```
   go build -o cadence-agent ./agent/cmd/cadence-agent
   CADENCE_DB_PATH=$TEMP/cadence-verify.db CADENCE_AGENT_PORT=47821 ./cadence-agent
   ```
   `GET /healthz` → `{"events":0,"schema_ver":1,"status":"ok"}`.
2. Seed a day of events (the dashboard fixture, captured via the mock proxy)
   and POST to the daemon:
   ```
   curl -X POST :47821/events --data @events.json
   → {"accepted":14,"rejected":0,"errors":[]}
   ```
   `GET /healthz` → `events: 14`. Re-POST → still 14 (idempotent on event_id).
3. Run the dashboard against the live daemon (mock OFF):
   ```
   CADENCE_AGENT_BASE=http://127.0.0.1:47821 npm run start
   ```

## Results

- Page renders: hero **Deep work today**, **Timeline** ribbon, **Focused**
  score band, **Categories** donut, **Top projects** (cadence-api,
  cadence-dashboard).
- Ribbon draws all 14 events; proxy round-trip returns 14 events as a bare
  array with `content-type: application/json`.
- Offline path (daemon stopped) → friendly "Cadence isn't running" panel with
  the start command + Retry; proxy returns `503 application/problem+json`.

## Caveat

The phase-doc exit criterion also asks for a full day on **both founders'
machines** (14-day dogfood). That is environment/dogfooding work outside this
session; verified here on one machine with real daemon data end to end.

## Runtime variables (P1-D "Variables to set")

```
CADENCE_AGENT_BASE=http://127.0.0.1:47821   # daemon base URL (server-side, runtime)
CADENCE_USE_MOCK=0                           # 1 = render fixtures instead of the daemon
```
See `.env.example`. NOTE: these replace the phase-doc's build-inlined
`NEXT_PUBLIC_CADENCE_AGENT_BASE` (see the P1-D coordination note in PROGRESS.md).
