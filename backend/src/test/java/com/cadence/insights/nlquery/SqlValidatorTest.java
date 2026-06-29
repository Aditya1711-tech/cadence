package com.cadence.insights.nlquery;

import com.cadence.common.ApiException;
import com.cadence.query.PrivacyLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The primary security proof for P3-C: the validator must reject every unsafe
 * shape and accept legitimate aggregate SELECTs. Pure logic — no DB/LLM/Spring.
 */
class SqlValidatorTest {

    private final SqlValidator v = new SqlValidator();
    private final SqlAllowlist full = SqlAllowlist.forPrivacy(PrivacyLevel.FULL);
    private final SqlAllowlist aggregate = SqlAllowlist.forPrivacy(PrivacyLevel.AGGREGATE_ONLY);

    private void rejected(String sql, SqlAllowlist allow) {
        assertThrows(ApiException.class, () -> v.validate(sql, allow), () -> "should reject: " + sql);
    }

    private void accepted(String sql, SqlAllowlist allow) {
        assertDoesNotThrow(() -> v.validate(sql, allow), () -> "should accept: " + sql);
    }

    // ── legitimate analytics queries are accepted ───────────────────────────
    @Test
    void acceptsCodeVsMeet() {
        accepted("SELECT category, sum(duration_ms)/3600000.0 AS hours "
                + "FROM events WHERE category IN ('deep_work','meetings') GROUP BY category", full);
    }

    @Test
    void acceptsTokenSpendByModel() {
        accepted("SELECT model, sum(cost_usd) AS spend FROM events_daily_tokens "
                + "GROUP BY model ORDER BY spend DESC", full);
    }

    @Test
    void acceptsJoinToMembers() {
        accepted("SELECT m.display_name, sum(e.duration_ms) AS ms "
                + "FROM events e JOIN members m ON m.id = e.member_id GROUP BY m.display_name", full);
    }

    @Test
    void acceptsMetaJsonForTokensWithoutLeakingText() {
        accepted("SELECT sum((meta->>'cost_usd')::numeric) AS spend FROM events WHERE source = 'token'", full);
    }

    @Test
    void acceptsOrderByOutputAlias() {
        accepted("SELECT category, count(*) AS n FROM events GROUP BY category ORDER BY n DESC", full);
    }

    // ── writes / DDL / multi-statement are rejected ─────────────────────────
    @Test void rejectsInsert()   { rejected("INSERT INTO events (source) VALUES ('x')", full); }
    @Test void rejectsUpdate()   { rejected("UPDATE members SET role = 'owner'", full); }
    @Test void rejectsDelete()   { rejected("DELETE FROM events", full); }
    @Test void rejectsDrop()     { rejected("DROP TABLE events", full); }
    @Test void rejectsGrant()    { rejected("GRANT SELECT ON events TO public", full); }
    @Test void rejectsMultiStatement() {
        rejected("SELECT category FROM events; DROP TABLE events", full);
    }
    @Test void rejectsSelectInto() {
        rejected("SELECT category INTO evil FROM events", full);
    }
    @Test void rejectsComment() {
        rejected("SELECT category FROM events -- DROP TABLE events", full);
    }
    @Test void rejectsBlockComment() {
        rejected("SELECT category /* hidden */ FROM events", full);
    }

    // ── sensitive columns / off-allowlist tables are rejected ───────────────
    @Test void rejectsPasswordHash() {
        rejected("SELECT password_hash FROM members", full);
    }
    @Test void rejectsEmail() {
        rejected("SELECT email FROM members", full);
    }
    @Test void rejectsTitle() {
        rejected("SELECT title FROM events", full);
    }
    @Test void rejectsUrl() {
        rejected("SELECT url FROM events", full);
    }
    @Test void rejectsRefreshTokens() {
        rejected("SELECT token_hash FROM refresh_tokens", full);
    }
    @Test void rejectsOneTimeTokens() {
        rejected("SELECT token_hash FROM one_time_tokens", full);
    }
    @Test void rejectsSystemCatalog() {
        rejected("SELECT rolname FROM pg_catalog.pg_roles", full);
    }
    @Test void rejectsInformationSchema() {
        rejected("SELECT table_name FROM information_schema.tables", full);
    }
    @Test void rejectsSelectStar() {
        rejected("SELECT * FROM events", full);
    }
    @Test void rejectsSubquery() {
        rejected("SELECT category FROM (SELECT category FROM events) x", full);
    }
    @Test void rejectsCte() {
        rejected("WITH e AS (SELECT category FROM events) SELECT category FROM e", full);
    }
    @Test void rejectsDangerousFunction() {
        rejected("SELECT pg_sleep(10)", full);
    }
    @Test void rejectsForUpdate() {
        rejected("SELECT category FROM events FOR UPDATE", full);
    }
    @Test void rejectsGibberish() {
        rejected("not sql at all", full);
    }

    // ── privacy-level binding: aggregate_only drops member detail ───────────
    @Test
    void aggregateOnlyRejectsMemberId() {
        rejected("SELECT member_id, sum(duration_ms) FROM events GROUP BY member_id", aggregate);
    }

    @Test
    void aggregateOnlyRejectsMembersTable() {
        rejected("SELECT display_name FROM members", aggregate);
    }

    @Test
    void aggregateOnlyStillAllowsOrgAggregate() {
        accepted("SELECT category, sum(duration_ms) FROM events GROUP BY category", aggregate);
    }

    @Test
    void fullAllowsMemberId() {
        accepted("SELECT member_id, sum(duration_ms) FROM events GROUP BY member_id", full);
    }

    @Test
    void detailNamesTheViolation() {
        ApiException ex = assertThrows(ApiException.class,
                () -> v.validate("SELECT token_hash FROM refresh_tokens", full));
        assertTrue(ex.getMessage().toLowerCase().contains("reject"));
    }
}
