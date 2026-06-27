# LOCAL-SETUP.md — run everything on your machine

This runs the whole system locally with no AWS. Use it for development and for
dogfooding through Phase 1.

---

## Prerequisites

```
- Go 1.22+
- Java 21 (Temurin) + Gradle 8 (or the wrapper ./gradlew)
- Node 20+ and npm
- Docker + Docker Compose
- A Chrome browser + VSCode (for the extensions)
```

---

## 1. Phase 1 only (local, no cloud)

You can run and dogfood the foundation without any backend.

```bash
# Agent (daemon)
cd agent
go build -o cadence-agent ./cmd/cadence-agent
# Variables from PHASE-1 "Variables to set" — all optional; shown with their
# defaults. Unset CADENCE_DB_PATH resolves to <os.UserConfigDir>/cadence/cadence.db.
export CADENCE_AGENT_PORT=47821
export CADENCE_DB_PATH="$HOME/.config/cadence/cadence.db"
export CADENCE_KEYCHAIN_SERVICE="com.cadence.agent"
./cadence-agent       # or install as a login service via agent/dist/install.sh

# Personal dashboard (lives in web/dashboard/; reads the daemon server-side)
cd ../web/dashboard
npm ci
echo "CADENCE_AGENT_BASE=http://127.0.0.1:47821" > .env.local
npm run dev           # open the dashboard route

# VSCode extension: open ext-vscode/ in VSCode, F5 to launch the Extension Host
# Chrome extension: chrome://extensions -> Developer mode -> Load unpacked -> ext-chrome/dist
```

At this point: code in VSCode, browse in Chrome, and watch events appear in the
local dashboard.

---

## 2. Phase 2+ (local cloud via docker-compose)

The backend, Postgres+TimescaleDB, and Redis run in containers. The daemon syncs
to the local backend instead of a remote one.

```bash
cd deploy
cp .env.example .env          # fill values (see ENV-VARIABLES.md)
docker compose up -d          # postgres+timescaledb, redis, backend

# the compose file runs Flyway migrations on backend startup
docker compose logs -f backend
```

`deploy/.env` (local) — minimum to boot:
```
DATABASE_URL=postgres://cadence:cadence@postgres:5432/cadence
DATABASE_USER=cadence
DATABASE_PASSWORD=cadence
JWT_SIGNING_SECRET=dev-only-change-me-32bytes-minimum
REDIS_URL=redis://redis:6379
SERVER_PORT=8080
DEFAULT_ORG_PRIVACY=categories_only
ANTHROPIC_API_KEY=sk-...        # needed once P2-F/P3 run
```

Point the daemon at local cloud:
```bash
export CADENCE_CLOUD_BASE=http://localhost:8080
export CADENCE_SYNC_INTERVAL_SEC=60     # faster for dev
```

Web apps against local cloud:
```bash
cd web
echo "NEXT_PUBLIC_API_BASE=http://localhost:8080" >> .env.local
npm run dev
```

---

## 3. Smoke test (local)

```bash
# register an org + admin
curl -s localhost:8080/api/v1/auth/register-org \
  -H 'content-type: application/json' \
  -d '{"org":"Acme","email":"you@acme.com","password":"..."}'

# login -> token
TOKEN=$(curl -s localhost:8080/api/v1/auth/login \
  -H 'content-type: application/json' \
  -d '{"email":"you@acme.com","password":"..."}' | jq -r .token)

# push a test event batch
curl -s localhost:8080/api/v1/ingest/events \
  -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '[{"event_id":"...","schema_ver":1,"source":"os","ts_start":"...","ts_end":"...","duration_ms":60000,"app":"VSCode","title":"x","category":null,"is_idle":false,"meta":{}}]'

# read summary
curl -s "localhost:8080/api/v1/me/summary?range=today" -H "authorization: Bearer $TOKEN"
```

---

## 4. Per-phase variable references
Each stream fills its **"Variables to set"** block in the phase doc when it
finishes. The consolidated list lives in `ENV-VARIABLES.md`. Keep `deploy/.env`
and `web/.env.local` in sync with it.
