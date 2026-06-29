-- 01-readonly-role.sql — runs once on first DB init (timescale image entrypoint,
-- as POSTGRES_USER=cadence), after 00-app-role.sql. Creates the SELECT-only,
-- non-owner, RLS-ENFORCED, org-scoped role that P3-C natural-language query
-- connects as (CADENCE_NLQUERY_DB_ROLE=cadence_readonly).
--
-- WHY A SEPARATE ROLE: text-to-SQL must never mutate data and must never read
-- another org's rows. Two guards, defence-in-depth:
--   1. SELECT-only privileges  → no INSERT/UPDATE/DELETE is even grantable here.
--   2. Non-owner + no BYPASSRLS → the org_isolation RLS policies (V1/V2/V3)
--      apply. P3-C sets app.current_org per request (same door as cadence_app),
--      so a query can only ever touch its own org — even if the SQL allowlist
--      were wrong, RLS is the hard backstop.
--
-- ROLE NOTE (matches 00-app-role.sql): the backend today connects as the owner
-- (DATABASE_USER=cadence) for the app's own org-scoped reads, so RLS is a
-- backstop there. P3-C is the first consumer that connects as a NON-owner role,
-- so for the NL-query path RLS is genuinely enforced. ALTER DEFAULT PRIVILEGES
-- is run as cadence (the role Flyway also runs as), so future Flyway-created
-- tables — including insights/digests (V3) — are covered automatically.
--
-- FRESH-INIT NOTE: docker entrypoint init scripts run only on an empty data
-- volume. On an already-initialised dev DB this file does not re-run; drop &
-- recreate the volume (dev model) to pick up the role. Same limitation as
-- cadence_app in 00-app-role.sql.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cadence_readonly') THEN
    CREATE ROLE cadence_readonly LOGIN PASSWORD 'cadence_readonly';
  END IF;
END $$;

GRANT USAGE ON SCHEMA public TO cadence_readonly;

-- Existing tables/views (incl. the V1 continuous aggregates) created before this
-- script — and, via ALTER DEFAULT PRIVILEGES, every table Flyway creates later.
GRANT SELECT ON ALL TABLES IN SCHEMA public TO cadence_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT ON TABLES TO cadence_readonly;

-- Deliberately NOT granted: INSERT/UPDATE/DELETE, sequence USAGE, BYPASSRLS,
-- CREATE. cadence_readonly can only SELECT, only within its bound org.
</content>
