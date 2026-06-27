-- V1__init.sql — Cadence Phase-2 cloud schema (SCHEMA CONTRACT)
--
-- Owned by the Phase-2 spine (P2-A). Other streams REQUEST changes via NEEDS
-- lines; only P2-A edits this folder (00-SYSTEM-KNOWLEDGE.md §9, PHASE-2 notes).
--
-- Design source of truth:
--   backend/docs/exploration/P2-A.1-multitenant-model.md
--   backend/docs/exploration/P2-A.2-auth-invite-flow.md
-- Grounding: 00-SYSTEM-KNOWLEDGE.md §5 (Event Contract), §6 (REST), §7 (DB
-- conventions), §8 (privacy).
--
-- TRANSACTION NOTE: TimescaleDB CREATE MATERIALIZED VIEW ... WITH
-- (timescaledb.continuous) and add_continuous_aggregate_policy() cannot run
-- inside a transaction block. This migration is therefore configured
-- non-transactional via the companion V1__init.sql.conf (executeInTransaction
-- =false). An init migration is the right place for this: on failure in dev,
-- drop & recreate the database.
--
-- ROLE NOTE: RLS policies below reference current_setting('app.current_org'),
-- never a role name, so they are role-agnostic. The runtime model (deploy):
--   * cadence_owner — owns the schema, runs Flyway (implicitly bypasses RLS as
--     owner; we do NOT FORCE RLS, so init/seed DML is unobstructed).
--   * cadence_app   — the Spring app's RLS-enforced runtime role for
--     authenticated, org-scoped requests; app.current_org is set per request.
--   * Public auth endpoints (register-org/login/invite/refresh/reset) operate
--     across org boundaries by secret token and use a separate, carefully
--     scoped auth datasource (see P2-A.6); they are the only cross-org door.
-- Role creation lives in deploy (docker-compose init), not in this migration.

-- ───────────────────────────────────────────────────────────────────────────
-- Extensions
-- ───────────────────────────────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE EXTENSION IF NOT EXISTS citext;
-- gen_random_uuid() is in core since PG13; no pgcrypto needed on PG16.

-- ───────────────────────────────────────────────────────────────────────────
-- orgs — the paying tenant
-- ───────────────────────────────────────────────────────────────────────────
CREATE TABLE orgs (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name          text  NOT NULL,
    slug          citext NOT NULL UNIQUE,
    privacy_level text  NOT NULL DEFAULT 'categories_only'
                        CHECK (privacy_level IN ('full','categories_only','aggregate_only')),
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- ───────────────────────────────────────────────────────────────────────────
-- members — a person in an org. members.id IS the Event Contract member_id.
-- ───────────────────────────────────────────────────────────────────────────
CREATE TABLE members (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        uuid NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    email         citext NOT NULL,
    password_hash text,                              -- null until set (invited)
    display_name  text,
    role          text NOT NULL DEFAULT 'member'
                       CHECK (role IN ('owner','admin','member')),
    status        text NOT NULL DEFAULT 'invited'
                       CHECK (status IN ('invited','active','disabled')),
    github_login  text,                              -- pre-provisioned for P2-D.4
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (org_id, email)
);
CREATE INDEX idx_members_org ON members(org_id);
-- At most one owner per org.
CREATE UNIQUE INDEX uq_members_one_owner ON members(org_id) WHERE role = 'owner';

-- ───────────────────────────────────────────────────────────────────────────
-- teams + team_members (join table — multi-team membership)
-- ───────────────────────────────────────────────────────────────────────────
CREATE TABLE teams (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     uuid NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    name       text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (org_id, name)
);
CREATE INDEX idx_teams_org ON teams(org_id);

CREATE TABLE team_members (
    org_id    uuid NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,  -- denorm for RLS
    team_id   uuid NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    member_id uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    PRIMARY KEY (team_id, member_id)
);
CREATE INDEX idx_team_members_member ON team_members(member_id);
CREATE INDEX idx_team_members_org ON team_members(org_id);

-- ───────────────────────────────────────────────────────────────────────────
-- seats — paid licenses. Active-seat count drives billing (P3-D).
-- ───────────────────────────────────────────────────────────────────────────
CREATE TABLE seats (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     uuid NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    member_id  uuid REFERENCES members(id) ON DELETE SET NULL,  -- null = unassigned
    status     text NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','revoked')),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_seats_org ON seats(org_id);
-- One active seat per member.
CREATE UNIQUE INDEX uq_seats_active_member ON seats(member_id) WHERE status = 'active';

-- ───────────────────────────────────────────────────────────────────────────
-- invites — onboarding origin (targeted email OR shareable multi-use link).
-- Only the token HASH is stored; plaintext lives in the URL, shown once.
-- ───────────────────────────────────────────────────────────────────────────
CREATE TABLE invites (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id               uuid NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    email                citext,                     -- null = open/shareable link
    token_hash           text NOT NULL UNIQUE,       -- SHA-256 of the invite token
    role                 text NOT NULL DEFAULT 'member'
                              CHECK (role IN ('admin','member')),
    team_id              uuid REFERENCES teams(id) ON DELETE SET NULL,
    max_uses             int,                        -- null = unlimited
    uses                 int NOT NULL DEFAULT 0,
    expires_at           timestamptz,
    created_by_member_id uuid REFERENCES members(id) ON DELETE SET NULL,
    created_at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_invites_org ON invites(org_id);

-- ───────────────────────────────────────────────────────────────────────────
-- refresh_tokens — opaque, rotating, revocable (token-family reuse detection).
-- Only the token HASH is stored.
-- ───────────────────────────────────────────────────────────────────────────
CREATE TABLE refresh_tokens (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id    uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    org_id       uuid NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    token_hash   text NOT NULL UNIQUE,               -- SHA-256 of the opaque token
    family_id    uuid NOT NULL,                       -- rotation lineage
    device_label text,
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz,
    replaced_by  uuid REFERENCES refresh_tokens(id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_member ON refresh_tokens(member_id);
CREATE INDEX idx_refresh_family ON refresh_tokens(family_id);

-- ───────────────────────────────────────────────────────────────────────────
-- one_time_tokens — short-lived single-use codes (password reset, device enroll)
-- ───────────────────────────────────────────────────────────────────────────
CREATE TABLE one_time_tokens (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     uuid REFERENCES orgs(id) ON DELETE CASCADE,
    member_id  uuid REFERENCES members(id) ON DELETE CASCADE,
    kind       text NOT NULL CHECK (kind IN ('password_reset','device_enroll')),
    token_hash text NOT NULL UNIQUE,                 -- SHA-256
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    meta       jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_ott_member_kind ON one_time_tokens(member_id, kind);

-- ───────────────────────────────────────────────────────────────────────────
-- events — the TimescaleDB hypertable. Mirrors the Event Contract (§5).
-- org_id + member_id are STAMPED FROM THE JWT at ingest, never trusted from the
-- client body. Privacy is enforced on READ (store-raw decision, P2-A.1 §4).
-- ───────────────────────────────────────────────────────────────────────────
CREATE TABLE events (
    event_id     uuid NOT NULL,                       -- collector-generated
    org_id       uuid NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    member_id    uuid NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    schema_ver   int  NOT NULL,
    source       text NOT NULL CHECK (source IN ('os','vscode','chrome','token','github')),
    ts_start     timestamptz NOT NULL,                -- partition column
    ts_end       timestamptz NOT NULL,
    duration_ms  bigint NOT NULL,
    app          text,
    title        text,
    url          text,
    project      text,
    category     text CHECK (category IS NULL OR category IN
                    ('deep_work','meetings','comms','research',
                     'code_review','ai_assisted','idle','other')),
    is_idle      boolean NOT NULL,
    meta         jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at   timestamptz NOT NULL DEFAULT now(),
    -- TimescaleDB requires every unique index to include the partition column.
    -- (event_id, ts_start) is the idempotency key: a re-sent event carries the
    -- same event_id AND the same ts_start, so ON CONFLICT DO NOTHING dedupes.
    UNIQUE (event_id, ts_start)
);

-- Hypertable partitioned on ts_start (create_hypertable runs fine in a txn; the
-- non-transactional bits are the continuous aggregates further below).
SELECT create_hypertable('events', by_range('ts_start'));

-- Read paths: per-member timeline, per-org rollups, category filters.
CREATE INDEX idx_events_org_member_ts ON events (org_id, member_id, ts_start DESC);
CREATE INDEX idx_events_org_ts        ON events (org_id, ts_start DESC);
CREATE INDEX idx_events_org_cat_ts    ON events (org_id, category, ts_start DESC);

-- ───────────────────────────────────────────────────────────────────────────
-- job_queue — async work (§7) + org_id (documented tenancy extension).
-- Workers claim via SELECT ... FOR UPDATE SKIP LOCKED.
-- ───────────────────────────────────────────────────────────────────────────
CREATE TABLE job_queue (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id     uuid NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,  -- §7 + tenancy rule
    kind       text NOT NULL,                          -- 'categorize' | 'digest' | ...
    payload    jsonb NOT NULL,
    status     text NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','running','done','failed')),
    attempts   int  NOT NULL DEFAULT 0,
    run_after  timestamptz NOT NULL DEFAULT now(),
    locked_by  text,
    locked_at  timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_job_queue_status_run ON job_queue (status, run_after);
CREATE INDEX idx_job_queue_org ON job_queue (org_id);

-- ───────────────────────────────────────────────────────────────────────────
-- Row-Level Security — org isolation on every org-scoped table.
-- Policy is role-agnostic: it reads app.current_org, set per request by the app.
-- current_setting(..., true) returns NULL when unset → default-deny for the app
-- role (NULL = org_id matches nothing). Not FORCEd, so the owner (Flyway) is
-- unobstructed during init/seed.
-- ───────────────────────────────────────────────────────────────────────────
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'orgs','members','teams','team_members','seats','invites',
    'refresh_tokens','one_time_tokens','events','job_queue'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY;', t);
  END LOOP;
END $$;

-- orgs is keyed by id (it has no org_id column).
CREATE POLICY org_isolation ON orgs
  USING (id = current_setting('app.current_org', true)::uuid)
  WITH CHECK (id = current_setting('app.current_org', true)::uuid);

-- All other org-scoped tables key on org_id.
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'members','teams','team_members','seats','invites',
    'refresh_tokens','one_time_tokens','events','job_queue'
  ] LOOP
    EXECUTE format($f$
      CREATE POLICY org_isolation ON %I
        USING (org_id = current_setting('app.current_org', true)::uuid)
        WITH CHECK (org_id = current_setting('app.current_org', true)::uuid);
    $f$, t);
  END LOOP;
END $$;

-- ───────────────────────────────────────────────────────────────────────────
-- Continuous aggregates (TimescaleDB) — power /me/summary, /org/summary, and
-- the aggregate_only read path. NON-TRANSACTIONAL (see header note).
-- NOTE: app reads of these views ALWAYS filter by org_id (org-scoped summary
-- queries); RLS is enforced on the base `events` for the materialization.
-- ───────────────────────────────────────────────────────────────────────────

-- Daily time-by-category per member.
CREATE MATERIALIZED VIEW events_daily_by_category
WITH (timescaledb.continuous) AS
SELECT time_bucket('1 day', ts_start) AS bucket,
       org_id,
       member_id,
       category,
       sum(duration_ms) AS total_ms,
       count(*)         AS event_count
FROM events
GROUP BY bucket, org_id, member_id, category
WITH NO DATA;

-- Hourly time-by-category per member (timeline UI resolution).
CREATE MATERIALIZED VIEW events_hourly_by_category
WITH (timescaledb.continuous) AS
SELECT time_bucket('1 hour', ts_start) AS bucket,
       org_id,
       member_id,
       category,
       sum(duration_ms) AS total_ms,
       count(*)         AS event_count
FROM events
GROUP BY bucket, org_id, member_id, category
WITH NO DATA;

-- Daily AI token spend per member per model (P2-C.5 + admin token panel).
CREATE MATERIALIZED VIEW events_daily_tokens
WITH (timescaledb.continuous) AS
SELECT time_bucket('1 day', ts_start) AS bucket,
       org_id,
       member_id,
       (meta->>'model')                       AS model,
       sum((meta->>'cost_usd')::numeric)      AS cost_usd,
       sum((meta->>'tokens_in')::numeric)     AS tokens_in,
       sum((meta->>'tokens_out')::numeric)    AS tokens_out
FROM events
WHERE source = 'token'
GROUP BY bucket, org_id, member_id, (meta->>'model')
WITH NO DATA;

-- Refresh policies: keep the recent window fresh, leave older buckets settled.
SELECT add_continuous_aggregate_policy('events_daily_by_category',
  start_offset => INTERVAL '7 days', end_offset => INTERVAL '1 hour',
  schedule_interval => INTERVAL '1 hour');
SELECT add_continuous_aggregate_policy('events_hourly_by_category',
  start_offset => INTERVAL '2 days', end_offset => INTERVAL '1 hour',
  schedule_interval => INTERVAL '30 minutes');
SELECT add_continuous_aggregate_policy('events_daily_tokens',
  start_offset => INTERVAL '7 days', end_offset => INTERVAL '1 hour',
  schedule_interval => INTERVAL '1 hour');
