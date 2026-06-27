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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-A.10 — end-to-end against a real Postgres+TimescaleDB (Flyway applies V1):
 * register org → login → ingest → read summary, with privacy-level behaviour.
 *
 * Requires Docker (Testcontainers). Lives in the integrationTest source set and
 * is excluded from the default {@code build}; run with {@code ./gradlew integrationTest}.
 */
@Testcontainers
@SpringBootTest(classes = CadenceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class E2EIngestQueryIT {

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
    }

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @Test
    void registerIngestRead() {
        // 1. register-org
        var reg = post("/api/v1/auth/register-org", """
                {"org_name":"Acme","email":"admin@acme.dev","password":"supersecret1"}""", null);
        assertEquals(HttpStatus.CREATED, reg.getStatusCode());
        JsonNode body = reg.getBody();
        String access = body.get("access_token").asText();
        UUID orgId = UUID.fromString(body.get("org").get("id").asText());
        assertEquals("categories_only", body.get("org").get("privacy_level").asText());

        // 2. ingest a batch (vscode deep_work + a token event)
        String batch = """
            [
             {"event_id":"%s","schema_ver":1,"source":"vscode","ts_start":"2025-06-01T09:00:00Z",
              "ts_end":"2025-06-01T09:30:00Z","duration_ms":1800000,"app":"VS Code","title":"a.ts",
              "url":null,"project":"cadence","category":"deep_work","is_idle":false,"meta":{}},
             {"event_id":"%s","schema_ver":1,"source":"token","ts_start":"2025-06-01T10:00:00Z",
              "ts_end":"2025-06-01T10:05:00Z","duration_ms":300000,"app":null,"title":null,"url":null,
              "project":"cadence","category":"ai_assisted","is_idle":false,
              "meta":{"model":"claude-sonnet-4","cost_usd":0.05,"tokens_in":1000,"tokens_out":200}}
            ]""".formatted(UUID.randomUUID(), UUID.randomUUID());
        var ing = post("/api/v1/ingest/events", batch, access);
        assertEquals(HttpStatus.OK, ing.getStatusCode());
        assertEquals(2, ing.getBody().get("stored").asInt());

        // idempotency: re-POST → all duplicates
        var ing2 = post("/api/v1/ingest/events", batch, access);
        assertEquals(2, ing2.getBody().get("duplicates").asInt());
        assertEquals(0, ing2.getBody().get("stored").asInt());

        // 3. /me/summary shows both categories + token spend
        var sum = get("/api/v1/me/summary?range=90d", access);
        assertEquals(HttpStatus.OK, sum.getStatusCode());
        JsonNode byCat = sum.getBody().get("by_category");
        assertTrue(byCat.size() >= 2);
        assertTrue(sum.getBody().get("tokens").get("total_cost_usd").asDouble() > 0.0);

        // 4. /org/summary as admin — categories_only keeps per-member rollups
        var org = get("/api/v1/org/summary?range=90d", access);
        assertEquals("categories_only", org.getBody().get("privacy_level").asText());
        assertFalse(org.getBody().get("by_member").isNull());

        // 5. flip org to aggregate_only → per-member breakdown disappears
        jdbc.update("UPDATE orgs SET privacy_level='aggregate_only' WHERE id=?", orgId);
        var agg = get("/api/v1/org/summary?range=90d", access);
        assertEquals("aggregate_only", agg.getBody().get("privacy_level").asText());
        assertTrue(agg.getBody().get("by_member") == null || agg.getBody().get("by_member").isNull());
        assertTrue(agg.getBody().get("org_totals_by_category").size() >= 1);
    }

    // ── helpers ──
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
