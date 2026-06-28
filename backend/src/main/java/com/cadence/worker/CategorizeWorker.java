package com.cadence.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Polls the job queue on a fixed delay, claims a batch of categorize jobs
 * ({@code FOR UPDATE SKIP LOCKED} via the claim function), and fans them out to a
 * virtual-thread-per-task pool (P2-F.3). {@code fixedDelay} prevents overlapping
 * polls within one instance; SKIP LOCKED prevents double-claiming across
 * instances. Each {@link JobProcessor#process} call handles its own errors, so
 * one bad job never breaks the batch.
 */
@Component
@ConditionalOnProperty(prefix = "cadence.categorize", name = "enabled", havingValue = "true")
class CategorizeWorker {

    private static final Logger log = LoggerFactory.getLogger(CategorizeWorker.class);

    private final JobStore store;
    private final JobProcessor processor;
    private final CategorizeProperties props;
    private final String workerId = hostId();

    CategorizeWorker(JobStore store, JobProcessor processor, CategorizeProperties props) {
        this.store = store;
        this.processor = processor;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${cadence.categorize.poll-interval-ms}")
    void poll() {
        List<ClaimedJob> jobs;
        try {
            jobs = store.claimBatch(workerId, props.batchSize());
        } catch (Exception e) {
            // Most likely the claim function isn't installed yet (NEEDS P2-F->P2-A).
            log.warn("claim failed (is claim_categorize_jobs installed?): {}", e.toString());
            return;
        }
        if (jobs.isEmpty()) {
            return;
        }
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ClaimedJob job : jobs) {
                exec.submit(() -> processor.process(job));
            }
        } // close() blocks until every task finishes
        log.debug("processed {} categorize job(s)", jobs.size());
    }

    private static String hostId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "worker";
        }
        return host + "/" + UUID.randomUUID();
    }
}
