package com.cadence.worker;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One claimed {@code categorize} job: the job row id + its org, the target event
 * key {@code (event_id, ts_start)} parsed from the payload, and the post-claim
 * attempt count (drives retry/fail decisions). See PROGRESS Coordination NOTE
 * for the payload shape.
 */
record ClaimedJob(UUID jobId, UUID orgId, UUID eventId, OffsetDateTime tsStart, int attempts) {
}
