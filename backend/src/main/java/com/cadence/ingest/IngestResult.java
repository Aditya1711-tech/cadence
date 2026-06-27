package com.cadence.ingest;

/**
 * Ingest response shape (§6 ingest is idempotent on event_id). Frozen contract:
 * {@code { received, stored, duplicates }}.
 */
public record IngestResult(int received, int stored, int duplicates) {}
