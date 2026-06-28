package com.cadence.github;

import java.util.UUID;

/**
 * Persists a mapped GitHub event under an already-bound org context. An
 * interface so {@link GithubWebhookService} is unit-testable without a database.
 */
public interface GithubEventStore {
    /**
     * Insert one github event idempotently. Returns rows affected (0 if the event
     * was a duplicate redelivery — {@code ON CONFLICT (event_id, ts_start) DO
     * NOTHING}). The caller must have bound {@code orgId} as the tenancy context.
     */
    int insert(UUID orgId, UUID memberId, GithubEventDraft draft);
}
