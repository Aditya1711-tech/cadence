package com.cadence.event;

import com.cadence.ingest.IngestResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the wire shape (Event Contract §5) — the snake_case keys other streams
 * decode. Mirrors the prod Jackson config (application.yml).
 */
class EventContractJsonTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void eventDeserializesFromContractJson() throws Exception {
        String json = """
            {
              "event_id":"11111111-1111-4111-8111-111111111111",
              "schema_ver":1,
              "source":"vscode",
              "member_id":"22222222-2222-4222-8222-222222222222",
              "ts_start":"2025-06-01T09:14:02Z",
              "ts_end":"2025-06-01T09:18:45Z",
              "duration_ms":283000,
              "app":"Visual Studio Code",
              "title":"auth.ts — cadence-api",
              "url":null,
              "project":"cadence-api",
              "category":"deep_work",
              "is_idle":false,
              "meta":{"lang":"typescript"}
            }""";
        EventDto e = mapper.readValue(json, EventDto.class);
        assertEquals(1, e.schemaVer());
        assertEquals("vscode", e.source());
        assertEquals(283000L, e.durationMs());
        assertFalse(e.isIdle());
        assertEquals("typescript", e.meta().get("lang").asText());
    }

    @Test
    void eventSerializesWithSnakeCaseKeys() throws Exception {
        String json = """
            {"event_id":"11111111-1111-4111-8111-111111111111","schema_ver":1,
             "source":"token","ts_start":"2025-06-01T09:14:02Z","ts_end":"2025-06-01T09:18:45Z",
             "duration_ms":1000,"is_idle":false,"meta":{}}""";
        EventDto e = mapper.readValue(json, EventDto.class);
        JsonNode out = mapper.valueToTree(e);
        assertTrue(out.has("event_id"));
        assertTrue(out.has("schema_ver"));
        assertTrue(out.has("duration_ms"));
        assertTrue(out.has("is_idle"));
        assertFalse(out.has("eventId"), "must be snake_case on the wire");
    }

    @Test
    void ingestResultShape() throws Exception {
        JsonNode out = mapper.valueToTree(new IngestResult(10, 8, 2));
        assertEquals(10, out.get("received").asInt());
        assertEquals(8, out.get("stored").asInt());
        assertEquals(2, out.get("duplicates").asInt());
    }
}
