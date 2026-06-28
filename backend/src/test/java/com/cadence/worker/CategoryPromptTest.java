package com.cadence.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CategoryPromptTest {

    @Test
    void systemListsAllEightCategories() {
        for (Category c : Category.values()) {
            assertTrue(CategoryPrompt.SYSTEM.contains(c.name()),
                    "system prompt should mention " + c.name());
        }
    }

    @Test
    void userMessageCarriesSignalsAndConvertsDurationToSeconds() {
        String user = CategoryPrompt.buildUser(
                new EventSignals("vscode", "Visual Studio Code", "auth.ts — cadence-api",
                        null, "cadence-api", false, 283000));
        assertTrue(user.contains("source:    vscode"));
        assertTrue(user.contains("app:       Visual Studio Code"));
        assertTrue(user.contains("auth.ts — cadence-api"));
        assertTrue(user.contains("url:       (none)"));   // null → (none)
        assertTrue(user.contains("duration:  283s"));     // 283000ms → 283s
    }
}
