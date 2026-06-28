package com.cadence.worker;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-tests the worker's per-job decision logic (P2-F.1/.3/.4/.5) against fakes —
 * no Postgres, Redis, or API. Verifies: re-check skips confident events, cache
 * hits skip the LLM, the cap soft-degrades to a deferral, and errors retry then
 * fail past max attempts.
 */
class JobProcessorTest {

    private final UUID org = UUID.randomUUID();
    private final FakeStore store = new FakeStore();
    private final FakeCategorizer categorizer = new FakeCategorizer();
    private final FakeCache cache = new FakeCache();
    private final FakeCap cap = new FakeCap();
    private final CategorizeProperties props =
            new CategorizeProperties(true, "claude-haiku-4-5", 0, 2000, 20, 5, 256, 30);
    private final JobProcessor processor =
            new JobProcessor(store, categorizer, cache, cap, props, new SimpleMeterRegistry());

    private ClaimedJob job(int attempts) {
        return new ClaimedJob(UUID.randomUUID(), org, UUID.randomUUID(),
                OffsetDateTime.parse("2026-06-01T09:00:00Z"), attempts);
    }

    private static JobStore.EventRow row(String category) {
        return new JobStore.EventRow(
                new EventSignals("vscode", "Code", "x.go", null, "p", false, 1000), category);
    }

    @Test
    void specificCategorySkipsLlm() {
        store.row = Optional.of(row("deep_work"));
        ClaimedJob j = job(1);
        processor.process(j);
        assertEquals(0, categorizer.calls);
        assertTrue(store.completed.contains(j.jobId()));
        assertTrue(store.written.isEmpty());
    }

    @Test
    void cacheHitSkipsLlmAndWritesCachedCategory() {
        store.row = Optional.of(row(null));
        cache.preload(org, PatternCache.key(row(null).signals()), Category.research);
        ClaimedJob j = job(1);
        processor.process(j);
        assertEquals(0, categorizer.calls);
        assertEquals(Category.research, store.written.get(j.jobId()));
    }

    @Test
    void cacheMissCallsLlmRecordsTokensAndPopulatesCache() {
        store.row = Optional.of(row(null));
        categorizer.result = new CategorizationResult(Category.code_review, 42);
        ClaimedJob j = job(1);
        processor.process(j);
        assertEquals(1, categorizer.calls);
        assertEquals(Category.code_review, store.written.get(j.jobId()));
        assertEquals(42, cap.recorded);
        assertEquals(Category.code_review,
                cache.get(org, PatternCache.key(row(null).signals())).orElseThrow());
    }

    @Test
    void capExhaustionDefersWithoutCallingLlm() {
        store.row = Optional.of(row("other"));
        cap.allow = false;
        ClaimedJob j = job(1);
        processor.process(j);
        assertEquals(0, categorizer.calls);
        assertTrue(store.deferred.containsKey(j.jobId()));
        assertTrue(store.written.isEmpty());
    }

    @Test
    void missingEventCompletesJob() {
        store.row = Optional.empty();
        ClaimedJob j = job(1);
        processor.process(j);
        assertTrue(store.completed.contains(j.jobId()));
        assertEquals(0, categorizer.calls);
    }

    @Test
    void errorBelowMaxAttemptsDefers() {
        store.throwOnLoad = true;
        ClaimedJob j = job(2);                 // < maxAttempts(5)
        processor.process(j);
        assertTrue(store.deferred.containsKey(j.jobId()));
        assertFalse(store.failed.contains(j.jobId()));
    }

    @Test
    void errorAtMaxAttemptsFails() {
        store.throwOnLoad = true;
        ClaimedJob j = job(5);                 // == maxAttempts(5)
        processor.process(j);
        assertTrue(store.failed.contains(j.jobId()));
        assertFalse(store.deferred.containsKey(j.jobId()));
    }

    @Test
    void backoffIsExponentialAndCappedAtOneHour() {
        assertEquals(60, JobProcessor.backoffSeconds(1));
        assertEquals(120, JobProcessor.backoffSeconds(2));
        assertEquals(3600, JobProcessor.backoffSeconds(10));   // capped
    }

    // ── fakes ────────────────────────────────────────────────────────────────

    private static final class FakeStore implements JobStore {
        Optional<EventRow> row = Optional.empty();
        boolean throwOnLoad = false;
        final java.util.Set<UUID> completed = new java.util.HashSet<>();
        final java.util.Set<UUID> failed = new java.util.HashSet<>();
        final Map<UUID, Long> deferred = new HashMap<>();
        final Map<UUID, Category> written = new HashMap<>();

        public List<ClaimedJob> claimBatch(String lockedBy, int limit) {
            return List.of();
        }

        public Optional<EventRow> loadSignals(UUID eventId, OffsetDateTime tsStart, UUID orgId) {
            if (throwOnLoad) {
                throw new RuntimeException("boom");
            }
            return row;
        }

        public void writeAndComplete(UUID jobId, UUID eventId, OffsetDateTime ts, UUID orgId, Category c) {
            written.put(jobId, c);
            completed.add(jobId);
        }

        public void complete(UUID jobId, UUID orgId) {
            completed.add(jobId);
        }

        public void defer(UUID jobId, UUID orgId, long backoff) {
            deferred.put(jobId, backoff);
        }

        public void fail(UUID jobId, UUID orgId) {
            failed.add(jobId);
        }
    }

    private static final class FakeCategorizer implements Categorizer {
        int calls = 0;
        CategorizationResult result = new CategorizationResult(Category.other, 0);

        public CategorizationResult categorize(EventSignals signals) {
            calls++;
            return result;
        }
    }

    private static final class FakeCache implements PatternCache {
        final Map<String, Category> map = new HashMap<>();

        void preload(UUID org, String key, Category c) {
            map.put(org + key, c);
        }

        public Optional<Category> get(UUID orgId, String key) {
            return Optional.ofNullable(map.get(orgId + key));
        }

        public void put(UUID orgId, String key, Category category) {
            map.put(orgId + key, category);
        }
    }

    private static final class FakeCap implements DailyTokenCap {
        boolean allow = true;
        long recorded = 0;

        public boolean allows(UUID orgId) {
            return allow;
        }

        public void record(UUID orgId, long tokens) {
            recorded += tokens;
        }
    }
}
