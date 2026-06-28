-- V2__github_installations.sql — GitHub App installation → org mapping (P2-D).
--
-- Owned by the Phase-2 spine. Finishes the spine's GitHub integration work: the
-- com.cadence.github code (P2-D.3/.4/.5) already targets exactly these columns
-- via JdbcGithubInstallationRepository; this migration creates the table they
-- bind to. Design source of truth:
--   backend/docs/exploration/P2-D.1-github-integration-model.md §4.
-- Grounding: 00-SYSTEM-KNOWLEDGE.md §7 (DB conventions, tenancy, RLS), §8
-- (GitHub privacy toggle: commit_messages_only default vs full_diff).
--
-- TRANSACTION NOTE: unlike V1, this migration has no TimescaleDB continuous
-- aggregates, so it runs in Flyway's default (transactional) mode — no companion
-- .conf file is needed.

-- ───────────────────────────────────────────────────────────────────────────
-- github_installations — installation_id → org_id (+ per-install privacy mode).
--
-- A GitHub webhook arrives keyed only by GitHub's installation.id; we must
-- resolve which Cadence org it belongs to (and that org's privacy mode) before
-- inserting source='github' events under the right org_id.
--
-- CROSS-ORG LOOKUP (defense-in-depth): the webhook resolver reads this table
-- BEFORE it knows the org, so the read cannot run under an org RLS context —
-- exactly like the public auth flows (invite/login token resolution). RLS is
-- ENABLED below (not FORCEd) so it isolates org-scoped admin reads/writes, while
-- the owner connection the app uses today (deploy/initdb/00-app-role.sql) lets
-- the pre-bind webhook lookup through. The documented hardening (a narrowly
-- scoped privileged datasource for cross-org doors) applies equally here; P2-D
-- records this rather than inventing its own DB role.
-- ───────────────────────────────────────────────────────────────────────────
CREATE TABLE github_installations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          uuid NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    installation_id bigint NOT NULL UNIQUE,           -- GitHub's installation id
    account_login   text,                             -- the GitHub org/account name
    mode            text NOT NULL DEFAULT 'commit_messages_only'
                         CHECK (mode IN ('commit_messages_only','full_diff')),
    suspended_at    timestamptz,                      -- App suspended/uninstalled
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_github_inst_org ON github_installations(org_id);

ALTER TABLE github_installations ENABLE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON github_installations
  USING (org_id = current_setting('app.current_org', true)::uuid)
  WITH CHECK (org_id = current_setting('app.current_org', true)::uuid);

-- ───────────────────────────────────────────────────────────────────────────
-- members ergonomics (P2-D.4): one github login maps to at most one member per
-- org, so the github_login → member resolver (JdbcGithubMemberResolver) is
-- unambiguous. Partial: only constrains rows that actually carry a login.
-- ───────────────────────────────────────────────────────────────────────────
CREATE UNIQUE INDEX uq_members_org_github
  ON members(org_id, github_login) WHERE github_login IS NOT NULL;

-- ───────────────────────────────────────────────────────────────────────────
-- Commit-activity read path (P2-D surfacing in /org/summary): the org summary's
-- commit facet counts source='github' commit events (meta.commit_sha present)
-- per day and per member. This index supports the source-filtered scan over the
-- events hypertable alongside the existing (org_id, ts_start) / (org_id,
-- category, ts_start) indexes.
-- ───────────────────────────────────────────────────────────────────────────
CREATE INDEX idx_events_org_source_ts ON events (org_id, source, ts_start DESC);
