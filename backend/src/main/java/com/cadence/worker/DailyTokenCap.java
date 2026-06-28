package com.cadence.worker;

import java.util.UUID;

/**
 * Per-org daily categorisation token budget (P2-F.5). When an org reaches its cap
 * the worker stops calling the LLM for that org until the next day; affected jobs
 * are deferred (not failed) so the cap is a soft-degrade, never data loss.
 */
interface DailyTokenCap {

    /** True if this org may still spend tokens today (always true when the cap is disabled). */
    boolean allows(UUID orgId);

    /** Record tokens spent by this org today. */
    void record(UUID orgId, long tokens);
}
