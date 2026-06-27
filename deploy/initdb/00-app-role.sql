-- Runs once on first DB init (timescale image entrypoint). Creates the
-- non-owner runtime role used to ENFORCE Row-Level Security in production
-- (P2-A.1 §1). RLS does not apply to the table owner, so the app must connect
-- as a non-owner role for org isolation to take effect.
--
-- Phase-2 milestone note: the backend currently connects as the owner
-- (DATABASE_USER=cadence) for simplicity, so RLS is a backstop. Production
-- hardening = point the app's org-scoped datasource at cadence_app. The role is
-- created here so that switch needs no schema change.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cadence_app') THEN
    CREATE ROLE cadence_app LOGIN PASSWORD 'cadence_app';
  END IF;
END $$;

GRANT USAGE ON SCHEMA public TO cadence_app;
-- Flyway (run by the owner) creates tables after this; grant on future objects.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cadence_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO cadence_app;
