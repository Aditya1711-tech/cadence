package com.cadence.insights.budget;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Folds events_daily_tokens cells into per-member + per-org daily burns. */
class BudgetWindowAssemblerTest {

    private final LocalDate today = LocalDate.parse("2026-06-29");
    private final LocalDate d1 = LocalDate.parse("2026-06-27");
    private final LocalDate d2 = LocalDate.parse("2026-06-28");
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void buildsMemberAndOrgBurnsWithActiveBaselineAndTopModel() {
        List<TokenCell> cells = List.of(
                // Alice: two prior active days + a today spike across two models
                new TokenCell(alice, "Alice", d1, "claude-opus-4-8", 4.0),
                new TokenCell(alice, "Alice", d2, "claude-opus-4-8", 6.0),
                new TokenCell(alice, "Alice", today, "claude-opus-4-8", 12.0),
                new TokenCell(alice, "Alice", today, "claude-haiku-4-5", 3.0),
                // Bob: one prior day only
                new TokenCell(bob, "Bob", d2, "gpt-5-codex", 2.0),
                new TokenCell(bob, "Bob", today, "gpt-5-codex", 5.0));

        List<SubjectBurn> subjects = BudgetWindowAssembler.assemble(cells, today, "Acme");

        // Org subject is first.
        SubjectBurn orgS = subjects.get(0);
        assertEquals(SubjectType.ORG, orgS.subjectType());
        assertEquals("Acme", orgS.displayName());
        assertEquals(20.0, orgS.todayUsd(), 1e-9);           // 12+3+5
        // org baseline active days: d1=4.0, d2=6.0+2.0=8.0
        assertEquals(List.of(4.0, 8.0), orgS.baselineActiveDayBurns());
        assertEquals("claude-opus-4-8", orgS.topModelToday());  // 12 > 5 > 3

        SubjectBurn aliceS = byId(subjects, alice);
        assertEquals(15.0, aliceS.todayUsd(), 1e-9);          // 12+3
        assertEquals(List.of(4.0, 6.0), aliceS.baselineActiveDayBurns());
        assertEquals("claude-opus-4-8", aliceS.topModelToday());

        SubjectBurn bobS = byId(subjects, bob);
        assertEquals(5.0, bobS.todayUsd(), 1e-9);
        assertEquals(List.of(2.0), bobS.baselineActiveDayBurns());
    }

    @Test
    void todayExcludedFromBaseline_andZeroDaysAreNotActive() {
        List<TokenCell> cells = List.of(
                new TokenCell(alice, "Alice", today, "claude-opus-4-8", 9.0));
        SubjectBurn aliceS = byId(BudgetWindowAssembler.assemble(cells, today, "Acme"), alice);
        assertEquals(9.0, aliceS.todayUsd(), 1e-9);
        assertTrue(aliceS.baselineActiveDayBurns().isEmpty());   // no prior days
    }

    private static SubjectBurn byId(List<SubjectBurn> subjects, UUID id) {
        return subjects.stream().filter(s -> id.equals(s.subjectId())).findFirst().orElseThrow();
    }
}
