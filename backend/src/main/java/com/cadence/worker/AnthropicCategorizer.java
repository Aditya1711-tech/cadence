package com.cadence.worker;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * LLM categoriser using the official Anthropic Java SDK with structured output
 * (P2-F.2). The output is constrained to {@link Category} by the schema derived
 * from {@link CategoryChoice}, so an invalid category is impossible at the API
 * layer; we still default to {@code other} on any refusal/parse/transport error
 * (never throws, never null — P2-F.1 §5).
 *
 * Only created when {@code cadence.categorize.enabled=true} (so a dev box without
 * ANTHROPIC_API_KEY / Redis does not instantiate the worker stack).
 */
@Component
@ConditionalOnProperty(prefix = "cadence.categorize", name = "enabled", havingValue = "true")
class AnthropicCategorizer implements Categorizer {

    private static final Logger log = LoggerFactory.getLogger(AnthropicCategorizer.class);

    /** Structured-output target: a single field constrained to the frozen 8-enum. */
    record CategoryChoice(Category category) {
    }

    private final AnthropicClient client;
    private final CategorizeProperties props;

    AnthropicCategorizer(AnthropicClient client, CategorizeProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public CategorizationResult categorize(EventSignals signals) {
        try {
            StructuredMessageCreateParams<CategoryChoice> params = MessageCreateParams.builder()
                    .model(props.model())
                    .maxTokens(props.maxOutputTokens())
                    .system(CategoryPrompt.SYSTEM)
                    .addUserMessage(CategoryPrompt.buildUser(signals))
                    .outputConfig(CategoryChoice.class)
                    .build();

            var message = client.messages().create(params);

            Category category = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(typed -> typed.text().category())
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(Category.other);

            long tokens = message.usage().inputTokens() + message.usage().outputTokens();
            return new CategorizationResult(category, tokens);
        } catch (Exception e) {
            log.warn("categorisation call failed; defaulting to other: {}", e.toString());
            return new CategorizationResult(Category.other, 0);
        }
    }
}
