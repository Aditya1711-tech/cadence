package com.cadence.event;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One activity record on the wire — the Event Contract (§5). Field names are
 * snake_case on the wire (global Jackson naming strategy). Used as the ingest
 * input shape and the /me/timeline output shape.
 *
 * On ingest: org_id and member_id are STAMPED from the JWT, never trusted from
 * the body — any client-supplied member_id is ignored. A field a collector
 * cannot fill is sent as null, never omitted (§5).
 */
public record EventDto(
        @NotNull UUID eventId,
        @NotNull Integer schemaVer,
        @NotNull String source,
        UUID memberId,                 // accepted but overridden by JWT on ingest
        @NotNull OffsetDateTime tsStart,
        @NotNull OffsetDateTime tsEnd,
        @NotNull @PositiveOrZero Long durationMs,
        String app,
        String title,
        String url,
        String project,
        String category,
        @NotNull Boolean isIdle,
        JsonNode meta
) {}
