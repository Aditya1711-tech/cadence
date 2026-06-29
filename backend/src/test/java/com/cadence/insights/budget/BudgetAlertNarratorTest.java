package com.cadence.insights.budget;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Narrator template fallback (used when no Anthropic client) + subject line. */
class BudgetAlertNarratorTest {

    private static BudgetProperties props() {
        return new BudgetProperties(true, "claude-haiku-4-5", 3.0, new BigDecimal("10.00"),
                14, 7, new int[] {3, 5, 10}, 200L, "");
    }

    private static Anomaly anomaly() {
        return new Anomaly(UUID.randomUUID(), SubjectType.MEMBER, UUID.randomUUID(), "Dev One",
                LocalDate.parse("2026-06-29"), new BigDecimal("20.00"), new BigDecimal("5.00"),
                4.0, 3, "claude-opus-4-8");
    }

    @Test
    void narrateFallsBackToTemplateWhenNoClient() {
        var narrator = new BudgetAlertNarrator(props(), null);   // null client → template
        String body = narrator.narrate(anomaly());
        assertEquals(BudgetAlertNarrator.template(anomaly()), body);
    }

    @Test
    void templateIsGroundedAndSpecific() {
        String body = BudgetAlertNarrator.template(anomaly());
        assertTrue(body.contains("Dev One"), body);
        assertTrue(body.contains("$20.00"), body);
        assertTrue(body.contains("$5.00"), body);
        assertTrue(body.contains("4.0×"), body);            // "4.0×"
        assertTrue(body.contains("claude-opus-4-8"), body);
    }

    @Test
    void subjectLineSummarisesTheSpike() {
        String s = new BudgetAlertNarrator(props(), null).subjectLine(anomaly());
        assertTrue(s.contains("Dev One"), s);
        assertTrue(s.contains("$20.00"), s);
        assertTrue(s.contains("4.0×"), s);
    }
}
