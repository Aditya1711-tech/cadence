package com.cadence.insights.digest;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.cadence.insights.WeeklyInsightsResponse.Spotted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Turns PRE-AGGREGATED facts into a grounded plain-English narrative + 3 spotted
 * insights (P3-A.6). The model receives ONLY the facts JSON — never raw events,
 * never prompt/response content — and is instructed to use only the numbers
 * given (the kickoff hard rule).
 *
 * <p>Resilience: the Anthropic client is built best-effort from the environment;
 * if there is no ANTHROPIC_API_KEY, or any call fails, narration falls back to a
 * deterministic {@link #template} so a digest always ships (and the pipeline is
 * testable with no API key). The client is private to this component (not a
 * shared bean) so it never collides with the P2-F worker's client.
 */
@Component
@ConditionalOnProperty(prefix = "cadence.digest", name = "enabled", havingValue = "true")
class DigestNarrator {

    private static final Logger log = LoggerFactory.getLogger(DigestNarrator.class);

    static final String SYSTEM = """
            You are Cadence's weekly digest writer for software developers and teams.
            You are given a JSON object of PRE-AGGREGATED weekly facts (hours by
            category, AI token cost, commits, a fragmentation index 0..100 where
            lower is more focused, a peak focus block, and deltas vs the trailing
            4-week average).

            Write a warm, concise, specific summary (4–6 sentences). Hard rules:
            - Use ONLY the numbers in the JSON. Never invent, estimate, or recompute
              a number that is not present. Round naturally; do not fabricate precision.
            - Use the deltas to note what changed vs the 4-week average. If the
              deltas are null, this is an early week — say so gently, no comparisons.
            - Be encouraging and human, never judgmental or surveillance-y.
            - You have NO access to titles, URLs, or raw activity — never imply you do.

            Then give exactly 3 spotted insights as {title, detail}: one on peak focus
            time, one on AI token efficiency, one on meeting load.
            """;

    /** Structured-output target: the SDK constrains the model to this shape. */
    record Narration(String narrative, List<Spotted> spotted) {}

    private final AnthropicClient client;   // nullable — null ⇒ template-only
    private final DigestProperties props;

    DigestNarrator(DigestProperties props) {
        this.props = props;
        AnthropicClient c = null;
        try {
            c = AnthropicOkHttpClient.fromEnv();   // reads ANTHROPIC_API_KEY
        } catch (Exception e) {
            log.warn("no Anthropic client ({}); weekly digests will use the template narrator",
                    e.toString());
        }
        this.client = c;
    }

    /** Narrate the facts for {@code scopeLabel} (e.g. a name or "the team"). Never throws. */
    Narration narrate(String scopeLabel, String factsJson, DigestHeadline headline) {
        if (client != null) {
            try {
                StructuredMessageCreateParams<Narration> params = MessageCreateParams.builder()
                        .model(props.model())
                        .maxTokens(props.maxOutputTokens())
                        .system(SYSTEM)
                        .addUserMessage("Facts for " + scopeLabel + ":\n" + factsJson)
                        .outputConfig(Narration.class)
                        .build();

                Narration out = client.messages().create(params).content().stream()
                        .flatMap(block -> block.text().stream())
                        .map(typed -> typed.text())
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);

                if (out != null && out.narrative() != null && !out.narrative().isBlank()) {
                    return new Narration(out.narrative(),
                            out.spotted() == null ? List.of() : out.spotted());
                }
                log.warn("empty narration for {}; using template", scopeLabel);
            } catch (Exception e) {
                log.warn("narration call failed for {} ({}); using template", scopeLabel, e.toString());
            }
        }
        return template(headline);
    }

    /** Deterministic, dependency-free fallback — also the canonical "no-LLM" digest. */
    static Narration template(DigestHeadline h) {
        String early = h.lowConfidence()
                ? " (Early days — week-over-week trends appear once you have ~4 weeks of history.)"
                : "";
        String narrative = ("%s logged %.1fh of deep work and %.1fh in meetings during %s, "
                + "spent $%s on AI tokens, and shipped %d commit%s. Focus score: %d/100.%s")
                .formatted(h.title(), h.deepWorkH(), h.meetingH(), h.isoWeek(),
                        h.tokenCostUsd().toPlainString(), h.commits(), h.commits() == 1 ? "" : "s",
                        h.focusScore(), early);
        List<Spotted> spotted = List.of(
                new Spotted("Peak focus", "Most productive block: " + h.peakLabel() + "."),
                new Spotted("AI efficiency",
                        "$%s spent on AI tokens this week.".formatted(h.tokenCostUsd().toPlainString())),
                new Spotted("Meeting load",
                        "%.1fh in meetings vs %.1fh of deep work.".formatted(h.meetingH(), h.deepWorkH())));
        return new Narration(narrative, spotted);
    }
}
