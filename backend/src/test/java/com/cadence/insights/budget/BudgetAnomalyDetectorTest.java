package com.cadence.insights.budget;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** P3-E.1 anomaly rule — DB-free, the core safety logic. */
class BudgetAnomalyDetectorTest {

    private final BudgetAnomalyDetector detector = new BudgetAnomalyDetector();
    private final UUID org = UUID.randomUUID();
    private final LocalDate today = LocalDate.parse("2026-06-29");

    /** spike=3×, floor=$10, baseline=14, minHistory=7, tiers [3,5,10]. */
    private OrgBudgetConfig cfg() {
        return new OrgBudgetConfig(org, true, 3.0, new BigDecimal("10.00"),
                14, 7, new int[] {3, 5, 10}, "email", null, null, null, null, "UTC", null);
    }

    private SubjectBurn subject(double today, List<Double> baseline) {
        return new SubjectBurn(SubjectType.MEMBER, UUID.randomUUID(), "Dev One", today, baseline, "claude-opus-4-8");
    }

    private static List<Double> days(double value, int count) {
        List<Double> l = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            l.add(value);
        }
        return l;
    }

    @Test
    void fires_whenSpendIsAMultipleOfUsualAndAboveFloor() {
        // usual $5/day × 8 days; today $20 = 4× and ≥ $10 floor.
        Optional<Anomaly> a = detector.evaluate(org, today, subject(20.0, days(5.0, 8)), cfg());
        assertTrue(a.isPresent());
        assertEquals(4.0, a.get().ratio(), 0.001);
        assertEquals(3, a.get().severity());               // highest tier ≤ 4
        assertEquals(new BigDecimal("20.00"), a.get().todayUsd());
        assertEquals(new BigDecimal("5.00"), a.get().baselineUsd());
    }

    @Test
    void silent_whenBelowMinHistory() {
        // only 6 active days < minHistory 7 → no alert even on a huge spike.
        assertTrue(detector.evaluate(org, today, subject(100.0, days(5.0, 6)), cfg()).isEmpty());
    }

    @Test
    void silent_whenBelowAbsoluteFloor() {
        // 6× ratio but today is $3 < $10 floor → rounding-noise guard.
        assertTrue(detector.evaluate(org, today, subject(3.0, days(0.5, 8)), cfg()).isEmpty());
    }

    @Test
    void silent_whenRatioBelowMultiplier() {
        // today $20 vs usual $10 = 2× < 3× → not a spike.
        assertTrue(detector.evaluate(org, today, subject(20.0, days(10.0, 8)), cfg()).isEmpty());
    }

    @Test
    void severityEscalatesWithRatio() {
        assertEquals(3, BudgetAnomalyDetector.severityFor(3.0, new int[] {3, 5, 10}));
        assertEquals(3, BudgetAnomalyDetector.severityFor(4.9, new int[] {3, 5, 10}));
        assertEquals(5, BudgetAnomalyDetector.severityFor(5.0, new int[] {3, 5, 10}));
        assertEquals(10, BudgetAnomalyDetector.severityFor(12.0, new int[] {3, 5, 10}));
        assertEquals(0, BudgetAnomalyDetector.severityFor(2.0, new int[] {3, 5, 10}));
    }

    @Test
    void perOrgFloorOverrideRaisesTheBar() {
        // Same $20 today over $5 usual would fire at default $10 floor, but a
        // per-org $25 floor (heavy-usage team) suppresses it.
        OrgBudgetConfig strict = new OrgBudgetConfig(org, true, 3.0, new BigDecimal("25.00"),
                14, 7, new int[] {3, 5, 10}, "email", null, null, null, null, "UTC", null);
        assertTrue(detector.evaluate(org, today, subject(20.0, days(5.0, 8)), strict).isEmpty());
    }
}
