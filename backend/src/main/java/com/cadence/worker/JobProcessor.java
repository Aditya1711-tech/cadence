package com.cadence.worker;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Processes one claimed categorize job (P2-F.3). Orchestration only — the actual
 * DB transactions live in {@link JobStore}, so the (slow) LLM call happens between
 * short transactions and never holds a pooled connection.
 *
 * Flow per job: load event → re-check it still needs the LLM (null/other; the
 * trigger is throughput, not correctness, per P2-F.1) → pattern cache → daily cap
 * → LLM → write back + cache. Never throws: any error becomes a retry (with
 * backoff) or, past max attempts, a failed job; the event simply stays {@code
 * other}. Cap exhaustion defers (soft-degrade), never fails.
 */
@Service
@ConditionalOnProperty(prefix = "cadence.categorize", name = "enabled", havingValue = "true")
class JobProcessor {

    private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

    private final JobStore store;
    private final Categorizer categorizer;
    private final PatternCache cache;
    private final DailyTokenCap cap;
    private final CategorizeProperties props;
    private final MeterRegistry metrics;

    JobProcessor(JobStore store, Categorizer categorizer, PatternCache cache,
                 DailyTokenCap cap, CategorizeProperties props, MeterRegistry metrics) {
        this.store = store;
        this.categorizer = categorizer;
        this.cache = cache;
        this.cap = cap;
        this.props = props;
        this.metrics = metrics;
    }

    void process(ClaimedJob job) {
        try {
            Optional<JobStore.EventRow> rowOpt = store.loadSignals(job.eventId(), job.tsStart(), job.orgId());
            if (rowOpt.isEmpty()) {
                // Event never landed / was removed — nothing to do.
                store.complete(job.jobId(), job.orgId());
                count("jobs", "result", "skipped");
                return;
            }
            JobStore.EventRow row = rowOpt.get();

            // Re-check on claim: only null/other escalate. A specific category means
            // the rules already classified it (or another worker won) — skip the LLM.
            if (!Category.needsLlm(row.category())) {
                store.complete(job.jobId(), job.orgId());
                count("jobs", "result", "skipped");
                return;
            }

            String key = PatternCache.key(row.signals());
            Optional<Category> cached = cache.get(job.orgId(), key);
            Category category;
            if (cached.isPresent()) {
                count("cache", "outcome", "hit");
                category = cached.get();
            } else {
                count("cache", "outcome", "miss");
                if (!cap.allows(job.orgId())) {
                    // Soft-degrade: leave the event 'other', retry the job tomorrow.
                    store.defer(job.jobId(), job.orgId(), capDeferSeconds());
                    count("jobs", "result", "capped");
                    return;
                }
                CategorizationResult res = categorizer.categorize(row.signals());
                cap.record(job.orgId(), res.tokens());
                cache.put(job.orgId(), key, res.category());
                metrics.counter("cadence.categorize.llm.calls").increment();
                metrics.counter("cadence.categorize.llm.tokens").increment(res.tokens());
                category = res.category();
            }

            store.writeAndComplete(job.jobId(), job.eventId(), job.tsStart(), job.orgId(), category);
            count("jobs", "result", "done");
        } catch (Exception e) {
            log.warn("categorize job {} failed (attempt {}): {}", job.jobId(), job.attempts(), e.toString());
            failOrRetry(job);
        }
    }

    private void failOrRetry(ClaimedJob job) {
        try {
            if (job.attempts() >= props.maxAttempts()) {
                store.fail(job.jobId(), job.orgId());
                count("jobs", "result", "failed");
            } else {
                store.defer(job.jobId(), job.orgId(), backoffSeconds(job.attempts()));
                count("jobs", "result", "retried");
            }
        } catch (Exception e) {
            // The job stays 'running'; the claim function reclaims stale locks.
            log.error("could not record terminal state for job {}: {}", job.jobId(), e.toString());
        }
    }

    /** Exponential backoff capped at 1h: 60·2^(attempts-1). */
    static long backoffSeconds(int attempts) {
        int n = Math.max(1, attempts);
        long s = 60L << Math.min(n - 1, 6);   // 60,120,…,3840 → capped below
        return Math.min(s, 3600L);
    }

    /** Cap-exhaustion deferral: retry well into the next day. */
    private static long capDeferSeconds() {
        return 3600L;
    }

    private void count(String name, String tagKey, String tagVal) {
        metrics.counter("cadence.categorize." + name, tagKey, tagVal).increment();
    }
}
