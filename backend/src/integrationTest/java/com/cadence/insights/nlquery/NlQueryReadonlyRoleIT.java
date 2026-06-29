package com.cadence.insights.nlquery;

import com.cadence.CadenceApplication;
import com.cadence.query.PrivacyLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3-C live verification (§9 item 3). The {@code cadence_readonly} role is
 * Docker-deferred (materializes on a fresh DB volume at deploy), so this IT
 * recreates it from the SAME deploy script against a Testcontainers Postgres and
 * proves the security model end to end:
 * <ol>
 *   <li>a cross-org SELECT returns <b>zero</b> of the other org's rows (RLS);</li>
 *   <li>an INSERT/UPDATE as {@code cadence_readonly} is <b>denied</b> (SELECT-only);</li>
 *   <li>the row cap <b>truncates</b> oversized results.</li>
 * </ol>
 *
 * Requires Docker. Lives in the integrationTest source set (excluded from the
 * default {@code build}); run with {@code ./gradlew integrationTest}. At deploy
 * this runs against the real fresh-volume role.
 */
@Testcontainers
@SpringBootTest(classes = CadenceApplication.class)
class NlQueryReadonlyRoleIT {

    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.17.2-pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("cadence");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
        r.add("cadence.jwt.signing-secret", () -> "0123456789abcdef0123456789abcdef");
        // nlquery stays DISABLED in Spring (no API key needed); we drive the
        // executor/validator directly so this IT needs no Anthropic credentials.
    }

    @Autowired JdbcTemplate owner;   // connects as the schema owner (bypasses RLS)

    private final SqlValidator validator = new SqlValidator();
    private UUID orgA;
    private UUID orgB;

    @BeforeEach
    void setUp() throws Exception {
        // Create the readonly role + grants from the actual deploy script (Flyway
        // has already applied V1/V2/V3 by now, so GRANT SELECT ON ALL TABLES covers
        // every table). Idempotent across @BeforeEach via the script's IF NOT EXISTS.
        String roleSql = Files.readString(findRoleScript());
        for (String stmt : roleSql.split(";\\s*\\n")) {
            if (!stmt.isBlank()) {
                owner.execute(stmt);
            }
        }
        orgA = seedOrg("Acme", "deep_work", "meetings", "comms", "research");   // 4 categories
        orgB = seedOrg("Globex", "meetings");                                   // other org
    }

    @Test
    void rlsBlocksCrossOrgReads() {
        SqlAllowlist allow = SqlAllowlist.forPrivacy(PrivacyLevel.FULL);
        String sql = validator.validate(
                "SELECT category, count(*) AS n FROM events GROUP BY category", allow);

        NlQueryExecutor exec = executor(100);
        try {
            NlQueryExecutor.ExecResult a = exec.execute(sql, orgA);
            // Only Acme's categories — Globex's lone 'meetings' row is invisible…
            assertTrue(a.rows().size() >= 4, "org A sees its own categories");
            // …and binding org B sees only B's single category, not A's four.
            NlQueryExecutor.ExecResult b = exec.execute(sql, orgB);
            assertEquals(1, b.rows().size(), "org B sees only its own row (RLS)");
        } finally {
            exec.close();
        }
    }

    @Test
    void rowCapTruncates() {
        SqlAllowlist allow = SqlAllowlist.forPrivacy(PrivacyLevel.FULL);
        String sql = validator.validate(
                "SELECT category, count(*) AS n FROM events GROUP BY category", allow);
        NlQueryExecutor exec = executor(2);   // cap below org A's 4 categories
        try {
            NlQueryExecutor.ExecResult a = exec.execute(sql, orgA);
            assertEquals(2, a.rows().size(), "result capped to maxRows");
            assertTrue(a.truncated(), "truncated flag set when more rows existed");
        } finally {
            exec.close();
        }
    }

    @Test
    void selectOnlyRoleCannotWrite() throws SQLException {
        try (Connection c = DriverManager.getConnection(
                DB.getJdbcUrl(), "cadence_readonly", "cadence_readonly");
             Statement s = c.createStatement()) {
            // Any write must be denied at the DB layer regardless of app validation.
            assertThrows(SQLException.class, () ->
                    s.executeUpdate("INSERT INTO orgs (name, slug) VALUES ('x','x')"));
            assertThrows(SQLException.class, () ->
                    s.executeUpdate("UPDATE members SET role = 'owner'"));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private NlQueryExecutor executor(int maxRows) {
        return new NlQueryExecutor(new NlQueryProperties(
                true, "claude-sonnet-4-6", maxRows, 5000, 1024,
                DB.getJdbcUrl(), "cadence_readonly", "cadence_readonly"));
    }

    private UUID seedOrg(String name, String... categories) {
        UUID org = owner.queryForObject(
                "INSERT INTO orgs (name, slug) VALUES (?, ?) RETURNING id",
                UUID.class, name, name.toLowerCase() + "-" + UUID.randomUUID());
        UUID member = owner.queryForObject(
                "INSERT INTO members (org_id, email, display_name, role, status) "
                + "VALUES (?, ?, ?, 'admin', 'active') RETURNING id",
                UUID.class, org, "a@" + name.toLowerCase() + ".dev", name + " Admin");
        OffsetDateTime base = OffsetDateTime.parse("2026-06-01T09:00:00Z");
        for (String cat : categories) {
            owner.update("""
                    INSERT INTO events (event_id, org_id, member_id, schema_ver, source,
                        ts_start, ts_end, duration_ms, app, title, url, project, category,
                        is_idle, meta)
                    VALUES (?, ?, ?, 1, 'vscode', ?, ?, 600000, 'VS Code', 'x', NULL,
                        'cadence', ?, false, '{}'::jsonb)
                    """, UUID.randomUUID(), org, member,
                    java.sql.Timestamp.from(base.toInstant()),
                    java.sql.Timestamp.from(base.plusMinutes(10).toInstant()), cat);
        }
        return org;
    }

    private static Path findRoleScript() {
        for (Path base : new Path[]{Path.of(""), Path.of(".."), Path.of("../..")}) {
            Path p = base.resolve("deploy/initdb/01-readonly-role.sql");
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IllegalStateException("deploy/initdb/01-readonly-role.sql not found");
    }
}
