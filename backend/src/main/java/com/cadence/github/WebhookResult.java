package com.cadence.github;

/**
 * Outcome of processing one webhook delivery.
 *
 * @param status   one of: {@code ignored} (event type not handled / ping),
 *                 {@code unlinked} (installation not mapped to an org),
 *                 {@code suspended} (installation suspended), {@code processed}.
 * @param stored   events newly inserted (excludes idempotent duplicates).
 * @param skipped  drafts dropped because the author could not be mapped to a member.
 */
public record WebhookResult(String status, int stored, int skipped) {
    public static WebhookResult ignored() { return new WebhookResult("ignored", 0, 0); }
    public static WebhookResult unlinked() { return new WebhookResult("unlinked", 0, 0); }
    public static WebhookResult suspended() { return new WebhookResult("suspended", 0, 0); }
}
