package com.cadence.worker;

/** Result of one LLM categorisation: the chosen category and tokens consumed. */
record CategorizationResult(Category category, long tokens) {
}

/**
 * Categorises an event from its signals. Implementations never throw and never
 * return null — on refusal, parse failure, or any error they return
 * {@link Category#other} (categorisation is best-effort, P2-F.1 §5).
 */
interface Categorizer {
    CategorizationResult categorize(EventSignals signals);
}
