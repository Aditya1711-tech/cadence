package com.cadence.insights.budget;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Turns an {@link Anomaly} into a SHORT, specific, actionable alert (P3-E.2).
 *
 * <p>The model (Haiku, {@code CADENCE_BUDGET_MODEL}) sees ONLY aggregated facts —
 * subject name, today's $, the baseline $, the ratio, the top model, the
 * day-of-week — never raw events (§8; token events have no content anyway). It
 * NEVER throws: on a missing key or any API/parse error it falls back to a
 * deterministic templated line, so an alert always fires. The LLM is polish, not
 * a hard dependency — mirroring {@code AnthropicCategorizer}'s resilience.
 */
@Component
class BudgetAlertNarrator {

    private static final Logger log = LoggerFactory.getLogger(BudgetAlertNarrator.class);

    private static final String SYSTEM = """
            You write token-budget spend alerts for an engineering team's admin.
            You are given ONLY pre-aggregated numbers (no raw activity).
            Write 1–3 short sentences: state the spike plainly with the dollar
            figure and the multiple-vs-usual, name the top model, and suggest a
            concrete next step (e.g. check that session / agent). Be specific and
            calm, not alarmist. No preamble, no markdown, no emojis.""";

    private final BudgetProperties props;
    private final AnthropicClient client;   // null when no key / construction failed

    BudgetAlertNarrator(BudgetProperties props) {
        this.props = props;
        AnthropicClient c;
        try {
            c = AnthropicOkHttpClient.fromEnv();
        } catch (Exception e) {
            c = null;
            log.info("budget narrator: no Anthropic client ({}); using templated alerts", e.toString());
        }
        this.client = c;
    }

    /** Visible-for-test ctor allowing a stub/null client. */
    BudgetAlertNarrator(BudgetProperties props, AnthropicClient client) {
        this.props = props;
        this.client = client;
    }

    String subjectLine(Anomaly a) {
        return "Token-budget alert: %s burned $%s (%.1f× usual)"
                .formatted(a.displayName(), a.todayUsd().toPlainString(), a.ratio());
    }

    String narrate(Anomaly a) {
        if (client != null) {
            try {
                var params = MessageCreateParams.builder()
                        .model(props.model())
                        .maxTokens(props.maxOutputTokens())
                        .system(SYSTEM)
                        .addUserMessage(facts(a))
                        .build();
                var message = client.messages().create(params);
                String text = message.content().stream()
                        .flatMap(block -> block.text().stream())
                        .map(t -> t.text())
                        .findFirst()
                        .orElse(null);
                if (text != null && !text.isBlank()) {
                    return text.strip();
                }
            } catch (Exception e) {
                log.warn("budget narration failed; using template: {}", e.toString());
            }
        }
        return template(a);
    }

    /** Deterministic fallback — always grounded in the same facts. */
    static String template(Anomaly a) {
        String model = a.topModel() == null ? "an AI model" : a.topModel();
        return "%s burned $%s in tokens on %s — %.1f× the usual $%s/day. Top model: %s. Worth a look at that session."
                .formatted(a.displayName(), a.todayUsd().toPlainString(), dow(a),
                        a.ratio(), a.baselineUsd().toPlainString(), model);
    }

    private static String facts(Anomaly a) {
        return """
                subject: %s (%s)
                day: %s (%s)
                today_usd: %s
                usual_active_day_usd: %s
                ratio: %.1f
                top_model: %s
                """.formatted(
                a.displayName(), a.subjectType().wire(), a.day(), dow(a),
                a.todayUsd().toPlainString(), a.baselineUsd().toPlainString(),
                a.ratio(), a.topModel() == null ? "unknown" : a.topModel());
    }

    private static String dow(Anomaly a) {
        return a.day().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }
}
