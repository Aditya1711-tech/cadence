package com.cadence;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3-B — end-to-end pattern engine against real Postgres+TimescaleDB: register →
 * seed a 21-day focus stream with a clear Tuesday-10:00 peak → refresh the
 * continuous aggregates → GET /insights/patterns and assert the peak window
 * surfaces, and that the confidence gate hides findings on a too-short window.
 *
 * Proves the JDBC/SQL plumbing the pure-function {@code PatternAnalysisTest}
 * cannot (CAGG column names, isodow/hour extraction, the §3.2 fragmentation read).
 *
 * <p><b>Requires Docker</b> (Testcontainers); excluded from the default
 * {@code build}, run with {@code ./gradlew integrationTest}. Same dev-box limit
 * as {@code E2EIngestQueryIT} (this Windows box has no Docker).
 *
 * <p><b>CAGG note:</b> {@code events_hourly/daily_by_category} are created
 * {@code WITH NO DATA}; the engine reads them, so the test must
 * {@code refresh_continuous_aggregate(...)} after seeding (the prod refresh
 * policies lag by design). This is the gotcha for anyone running it.
 */
@Testcontainers
@SpringBootTest(classes = CadenceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PatternEngineIT {

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
        r.add("cadence.org.default-privacy", () -> "categories_only");
        r.add("cadence.pattern.min-days", () -> "14");
    }

    private static final DateTimeFormatter RFC3339 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @Test
    void peakWindowSurfacesAndGateHidesShortHistory() {
        // 1. register an org + admin member; events ingest under this member.
        var reg = post("/api/v1/auth/register-org", """
                {"org_name":"Acme","email":"admin@acme.dev","password":"supersecret1"}""", null);
        assertEquals(HttpStatus.CREATED, reg.getStatusCode());
        String access = reg.getBody().get("access_token").asText();

        // 2. seed 21 distinct days: a 1h deep_work baseline at 09:00 every day, plus
        //    a 4h deep_work spike at 10:00 on Tuesdays → a clear (Tue,10:00) peak.
        OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(java.time.temporal.ChronoUnit.DAYS).minusDays(21);
        List<String> events = new ArrayList<>();
        for (int d = 0; d < 21; d++) {
            OffsetDateTime day = base.plusDays(d);
            events.add(deepWork(day.withHour(9), 3_600_000L, "cadence"));            // baseline 1h
            if (day.getDayOfWeek() == DayOfWeek.TUESDAY) {
                events.add(deepWork(day.withHour(10), 14_400_000L, "cadence-spike")); // spike 4h
            }
        }
        var ing = post("/api/v1/ingest/events", "[" + String.join(",", events) + "]", access);
        assertEquals(HttpStatus.OK, ing.getStatusCode());

        // 3. populate the continuous aggregates the engine reads (see CAGG note).
        refreshCaggs();

        // 4. wide window → enough history → peak window surfaces.
        var wide = get("/api/v1/insights/patterns?range=8w", access);
        assertEquals(HttpStatus.OK, wide.getStatusCode());
        JsonNode body = wide.getBody();
        assertFalse(body.get("low_confidence").asBoolean(), "21 active days clears the floor");
        JsonNode findings = body.get("findings");
        assertTrue(findings.size() >= 1 && findings.size() <= 3, "1–3 findings");
        boolean peak = false;
        for (JsonNode f : findings) {
            if ("peak_window".equals(f.get("kind").asText())) {
                peak = true;
                assertEquals("high", f.get("confidence").asText());
                assertEquals(2, f.get("evidence").get("iso_dow").asInt(), "Tuesday");
                assertEquals(10, f.get("evidence").get("hour").asInt(), "10:00");
            }
        }
        assertTrue(peak, "the seeded Tuesday-10:00 spike should surface as a peak window");

        // 5. narrow window → < 14 active days → confidence gate hides everything.
        var narrow = get("/api/v1/insights/patterns?range=7d", access);
        assertTrue(narrow.getBody().get("low_confidence").asBoolean(), "7 days < floor");
        assertEquals(0, narrow.getBody().get("findings").size(), "no noisy claims for low history");
    }

    // ── helpers ──

    private static String deepWork(OffsetDateTime start, long durationMs, String project) {
        OffsetDateTime end = start.plusNanos(durationMs * 1_000_000);
        return """
                {"event_id":"%s","schema_ver":1,"source":"vscode","ts_start":"%s","ts_end":"%s",
                 "duration_ms":%d,"app":"VS Code","title":"a.ts","url":null,"project":"%s",
                 "category":"deep_work","is_idle":false,"meta":{}}"""
                .formatted(UUID.randomUUID(), RFC3339.format(start), RFC3339.format(end),
                        durationMs, project);
    }

    private void refreshCaggs() {
        // refresh_continuous_aggregate cannot run in a tx block; JdbcTemplate is autocommit.
        jdbc.execute("CALL refresh_continuous_aggregate('events_daily_by_category', NULL, NULL)");
        jdbc.execute("CALL refresh_continuous_aggregate('events_hourly_by_category', NULL, NULL)");
    }

    private ResponseEntity<JsonNode> post(String path, String json, String bearer) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) h.setBearerAuth(bearer);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, h), JsonNode.class);
    }

    private ResponseEntity<JsonNode> get(String path, String bearer) {
        HttpHeaders h = new HttpHeaders();
        if (bearer != null) h.setBearerAuth(bearer);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
    }
}
