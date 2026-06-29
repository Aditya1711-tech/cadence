-- V3__insights_digests.sql — Phase-3 insights foundation (P3-A spine).
--
-- Owned by the Phase-3 spine (P3-A). Other streams REQUEST changes via NEEDS
-- lines; only the phase spine edits this folder (00-SYSTEM-KNOWLEDGE.md §9).
--
-- Freezes the AGGREGATED-FACT contract the LLM narrates from. The model only
-- ever sees the pre-aggregated facts stored here — never raw event rows, never
-- prompt/response content (kickoff hard rule; §8). Numbers come from SQL; the
-- model writes prose into `digests`.
--
-- Design source of truth:
--   backend/insights/docs/P3-A.1-aggregated-fact-shape.md  (fact shape + fragmentation)
--   backend/insights/docs/P3-A.2-delivery-and-card.md      (digest pipeline)
-- Grounding: 00-SYSTEM-KNOWLEDGE.md §6 (REST: GET /insights/weekly), §7 (DB
-- conventions, tenancy, RLS), §8 (privacy: org grain is privacy-bounded).
--
-- TRANSACTION NOTE: like V2 and unlike V1, this migration creates no TimescaleDB
-- continuous aggregates, so it runs in Flyway's default (transactional) mode —
-- no companion .conf file is needed. The fact builder reads the existing V1
-- CAGGs + raw events; NO new CAGG is added (a CAGG addition would be a
-- coordination event — deliberately avoided to keep the contract surface small).

-- ───────────────────────────────────────────────────────────────────────────
-- insights — the frozen aggregated-fact store. One row per (org, member, ISO
-- week, grain). Two grains share one table via `grain`:
--   * grain='member' → member_id NOT NULL  (MemberWeekFacts)
--   * grain='org'    → member_id NULL       (OrgWeekFacts, admin digest)
-- Denormalized headline scalars (for cheap indexing / P3-B queries) sit
-- alongside the full frozen object in `facts` jsonb (the wire/contract shape).
-- ───────────────────────────────────────────────────────────────────────────
CREATE TABLE insights (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id               uuid NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    member_id            uuid REFERENCES members(id) ON DELETE CASCADE, -- NULL for grain='org'
    grain                text NOT NULL CHECK (grain IN ('member','org')),
    iso_week             text NOT NULL,                 -- e.g. '2026-W26'
    period_start         timestamptz NOT NULL,
    period_end           timestamptz NOT NULL,
    -- headline scalars (frozen must-haves; mirror facts->>... for indexing)
    deep_work_h          numeric NOT NULL DEFAULT 0,
    meeting_h            numeric NOT NULL DEFAULT 0,
    token_cost_usd       numeric NOT NULL DEFAULT 0,
    commits              integer NOT NULL DEFAULT 0,
    fragmentation_index  integer,                        -- 0..100; NULL when no focus time
    facts                jsonb   NOT NULL,               -- full MemberWeekFacts/OrgWeekFacts
    created_at           timestamptz NOT NULL DEFAULT now(),
    -- grain is bound to member_id presence so a malformed row can't exist.
    CONSTRAINT insights_grain_member CHECK (
        (grain = 'member' AND member_id IS NOT NULL) OR
        (grain = 'org'    AND member_id IS NULL)
    )
);

-- One member digest and one org digest per ISO week. Partial uniques give
-- unambiguous upsert targets per grain (NULL member_id needs no special-casing).
CREATE UNIQUE INDEX uq_insights_member ON insights(org_id, member_id, iso_week)
    WHERE grain = 'member';
CREATE UNIQUE INDEX uq_insights_org    ON insights(org_id, iso_week)
    WHERE grain = 'org';
-- P3-B scans the aggregation layer by org + grain + week.
CREATE INDEX idx_insights_org_grain_week ON insights(org_id, grain, iso_week);

-- ───────────────────────────────────────────────────────────────────────────
-- digests — the narrated + delivered output. One row per insights row. Holds
-- the model's prose (grounded in `insights.facts`), the 3 spotted insights, the
-- server-side shareable SVG card, and the delivery state. The narrative is the
-- ONLY model-authored content; every number it cites lives in the linked
-- insights row.
-- ───────────────────────────────────────────────────────────────────────────
CREATE TABLE digests (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       uuid NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    member_id    uuid REFERENCES members(id) ON DELETE CASCADE,   -- NULL for grain='org'
    insight_id   uuid NOT NULL REFERENCES insights(id) ON DELETE CASCADE,
    grain        text NOT NULL CHECK (grain IN ('member','org')),
    iso_week     text NOT NULL,
    narrative    text,                                  -- model prose (grounded)
    spotted      jsonb NOT NULL DEFAULT '[]'::jsonb,     -- [{title,detail} × 3]
    card_svg     text,                                  -- server-side shareable card
    channel      text NOT NULL DEFAULT 'email'
                      CHECK (channel IN ('email','in_app','console')),
    status       text NOT NULL DEFAULT 'pending'
                      CHECK (status IN ('pending','rendered','sent','failed')),
    sent_at      timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT digests_grain_member CHECK (
        (grain = 'member' AND member_id IS NOT NULL) OR
        (grain = 'org'    AND member_id IS NULL)
    )
);

CREATE UNIQUE INDEX uq_digests_member ON digests(org_id, member_id, iso_week)
    WHERE grain = 'member';
CREATE UNIQUE INDEX uq_digests_org    ON digests(org_id, iso_week)
    WHERE grain = 'org';
CREATE INDEX idx_digests_insight ON digests(insight_id);

-- ───────────────────────────────────────────────────────────────────────────
-- Row-Level Security — org isolation, identical pattern to V1/V2. Role-agnostic
-- (reads app.current_org, set per request by the app). Not FORCEd, so the owner
-- (Flyway / the app's current owner connection) is unobstructed; the non-owner
-- runtime roles (cadence_app read-write, cadence_readonly SELECT-only) are
-- subject to it. cadence_readonly + grants are created in deploy/initdb.
-- ───────────────────────────────────────────────────────────────────────────
ALTER TABLE insights ENABLE ROW LEVEL SECURITY;
ALTER TABLE digests  ENABLE ROW LEVEL SECURITY;

CREATE POLICY org_isolation ON insights
  USING (org_id = current_setting('app.current_org', true)::uuid)
  WITH CHECK (org_id = current_setting('app.current_org', true)::uuid);

CREATE POLICY org_isolation ON digests
  USING (org_id = current_setting('app.current_org', true)::uuid)
  WITH CHECK (org_id = current_setting('app.current_org', true)::uuid);
</content>
