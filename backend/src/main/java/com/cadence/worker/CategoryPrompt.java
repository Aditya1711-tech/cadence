package com.cadence.worker;

/**
 * Builds the categorisation prompt (P2-F.2). The system prompt is a stable,
 * cacheable string (role + the 8 fixed category definitions + tie-break rules);
 * the user message is the per-event structured signal block. We never accept
 * free-text categories — the output is constrained to the frozen enum by the
 * structured-output schema, and validated again server-side.
 */
final class CategoryPrompt {

    private CategoryPrompt() {
    }

    static final String SYSTEM = """
            You label one developer activity event with exactly one category.
            Choose the single best fit from this fixed set (use these exact tokens):

              deep_work    - focused coding/building in an editor, IDE, or terminal
              meetings     - video calls / meeting apps (Zoom, Meet, Teams, etc.)
              comms        - chat and email (Slack, Discord, Mail, Outlook, etc.)
              research     - reading docs, articles, search, general web browsing
              code_review  - reviewing PRs/MRs/commits on a code host
              ai_assisted  - using an AI coding/chat tool (token usage, claude.ai, etc.)
              idle         - no activity during the window
              other        - none of the above clearly fits

            Rules:
            - Pick exactly one. Choose `other` only when no specific category genuinely fits.
            - If the event is marked idle, the category is `idle`.
            - Token-source events are `ai_assisted`.
            - Judge by what the developer was doing, not the app name alone.
            """;

    static String buildUser(EventSignals s) {
        return """
                source:    %s
                app:       %s
                title:     %s
                url:       %s
                project:   %s
                is_idle:   %s
                duration:  %ds
                """.formatted(
                orNone(s.source()),
                orNone(s.app()),
                orNone(s.title()),
                orNone(s.url()),
                orNone(s.project()),
                s.isIdle(),
                Math.max(0, s.durationMs() / 1000));
    }

    private static String orNone(String v) {
        return (v == null || v.isBlank()) ? "(none)" : v;
    }
}
