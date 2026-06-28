package com.cadence.worker;

/**
 * The low-sensitivity classification signals read from an event — the same
 * evidence the device rule classifier uses (app/title/url/source/is_idle), plus
 * project/duration for context. This is the only data sent to the LLM; it never
 * includes prompt/response content (§8). Any field a collector couldn't fill is
 * null, mirroring the Event Contract.
 */
public record EventSignals(
        String source,
        String app,
        String title,
        String url,
        String project,
        boolean isIdle,
        long durationMs
) {
}
