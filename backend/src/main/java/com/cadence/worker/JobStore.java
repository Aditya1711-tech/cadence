package com.cadence.worker;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * DB operations the worker needs, behind an interface so {@link JobProcessor} can
 * be unit-tested without Postgres. The live implementation is
 * {@link CategorizeJobStore}.
 */
interface JobStore {

    /** An event's classification signals plus its currently-stored category. */
    record EventRow(EventSignals signals, String category) {
    }

    List<ClaimedJob> claimBatch(String lockedBy, int limit);

    Optional<EventRow> loadSignals(UUID eventId, OffsetDateTime tsStart, UUID orgId);

    void writeAndComplete(UUID jobId, UUID eventId, OffsetDateTime tsStart, UUID orgId, Category category);

    void complete(UUID jobId, UUID orgId);

    void defer(UUID jobId, UUID orgId, long backoffSeconds);

    void fail(UUID jobId, UUID orgId);
}
