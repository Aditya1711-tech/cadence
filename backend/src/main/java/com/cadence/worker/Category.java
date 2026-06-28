package com.cadence.worker;

import java.util.List;
import java.util.Optional;

/**
 * The frozen v1 category enum (00-SYSTEM-KNOWLEDGE §5). Constant names are
 * deliberately the on-wire / DB values (snake_case) so they round-trip with no
 * mapping: Jackson serialises the enum by name, the structured-output schema
 * derives its {@code enum} list from these, and the DB write uses
 * {@link #name()} directly (it matches the {@code events.category} CHECK list).
 */
public enum Category {
    deep_work, meetings, comms, research, code_review, ai_assisted, idle, other;

    /** A "specific" category is anything the rule classifier resolved confidently — i.e. not {@code other}. */
    public boolean isSpecific() {
        return this != other;
    }

    /** Parse a wire/DB value; empty if null or not one of the 8 (callers fall back to {@link #other}). */
    public static Optional<Category> fromWire(String s) {
        if (s == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(s));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** True when an event with this stored category should escalate to the LLM (null or {@code other}). */
    public static boolean needsLlm(String storedCategory) {
        return fromWire(storedCategory).map(c -> !c.isSpecific()).orElse(true);
    }

    public static List<String> wireValues() {
        return List.of(deep_work.name(), meetings.name(), comms.name(), research.name(),
                code_review.name(), ai_assisted.name(), idle.name(), other.name());
    }
}
