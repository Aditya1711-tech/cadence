package com.cadence.insights.pattern;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3-B.2/.4 — proves the pattern math on a SEEDED fixture with enough history
 * (this box likely lacks {@code CADENCE_PATTERN_MIN_DAYS} of real data, so the
 * logic is asserted here, not against the live warehouse). Covers each finding's
 * model, the per-finding evidence bars, and — the headline — the hard
 * confidence gate that gives low-history callers zero findings.
 *
 * Pure functions only: no Spring, no DB. The JDBC path ({@link PatternService})
 * is exercised by a Testcontainers integration test, deferred to a Docker host.
 */
class PatternAnalysisTest {

    private static final PatternProperties CFG = new PatternProperties(14, 1.5, 0.4, 0.15);
    private static final OffsetDateTime FROM = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime TO = FROM.plusDays(28);
    private static final long HOUR_MS = 3_600_000L;

    // ── Finding 1: peak productivity window ─────────────────────────────────

    @Test
    void peakWindowDetectsConcentratedHour() {
        // Nine flat 1h cells + one 5h spike on Tuesday (isoDow=2) 10:00.
        List<PatternAnalysis.FocusCell> grid = new ArrayList<>();
        for (int h = 0; h < 9; h++) {
            grid.add(new PatternAnalysis.FocusCell(1, h, HOUR_MS));      // Monday, flat
        }
        grid.add(new PatternAnalysis.FocusCell(2, 10, 5 * HOUR_MS));     // Tuesday 10:00 spike

        Optional<Findings.Finding> f = PatternAnalysis.peakWindow(grid, CFG);
        assertTrue(f.isPresent(), "a clear spike should surface");
        assertEquals(Findings.KIND_PEAK_WINDOW, f.get().kind());
        assertEquals(Findings.CONFIDENCE_HIGH, f.get().confidence());
        assertEquals(2, f.get().evidence().get("iso_dow"));
        assertEquals(10, f.get().evidence().get("hour"));
        assertTrue(f.get().title().contains("Tue"));
    }

    @Test
    void peakWindowFlatDistributionYieldsNothing() {
        // Ten identical cells: concentration ratio == 1.0, below the 1.5 bar.
        List<PatternAnalysis.FocusCell> grid = new ArrayList<>();
        for (int h = 0; h < 10; h++) {
            grid.add(new PatternAnalysis.FocusCell(1, h, HOUR_MS));
        }
        assertTrue(PatternAnalysis.peakWindow(grid, CFG).isEmpty(), "a flat week has no peak");
    }

    @Test
    void peakWindowTooFewActiveCellsYieldsNothing() {
        List<PatternAnalysis.FocusCell> grid = List.of(
                new PatternAnalysis.FocusCell(1, 9, 5 * HOUR_MS),
                new PatternAnalysis.FocusCell(1, 10, HOUR_MS));
        assertTrue(PatternAnalysis.peakWindow(grid, CFG).isEmpty(), "needs a real distribution");
    }

    // ── Finding 2: meeting → output correlation ─────────────────────────────

    @Test
    void meetingOutputSurfacesNegativeCorrelation() {
        // 20 days: light-meeting days have high deep work, heavy-meeting days low.
        List<PatternAnalysis.DayMetrics> days = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            boolean heavy = i >= 10;
            days.add(new PatternAnalysis.DayMetrics(
                    heavy ? 3.0 : 0.5,   // meetingH
                    heavy ? 3.0 : 6.0,   // deepWorkH
                    0, 0.0));
        }
        Optional<Findings.Finding> f = PatternAnalysis.meetingOutput(days, CFG);
        assertTrue(f.isPresent());
        assertEquals(Findings.KIND_MEETING_OUTPUT, f.get().kind());
        assertTrue((double) f.get().evidence().get("output_delta_pct") > 0,
                "heavy-meeting days should show an output drop");
        assertTrue(f.get().detail().toLowerCase().contains("deep work"));
    }

    @Test
    void meetingOutputWeakSignalIsDropped() {
        // Output independent of meetings → |r| below the 0.4 bar.
        List<PatternAnalysis.DayMetrics> days = new ArrayList<>();
        double[] meet = {0.5, 3.0, 0.5, 3.0, 0.5, 3.0, 0.5, 3.0, 0.5, 3.0,
                         0.5, 3.0, 0.5, 3.0, 0.5, 3.0, 0.5, 3.0, 0.5, 3.0};
        double[] out = {5.0, 5.1, 4.9, 5.0, 5.2, 4.8, 5.0, 5.1, 4.9, 5.0,
                        5.0, 5.1, 4.9, 5.0, 5.2, 4.8, 5.0, 5.1, 4.9, 5.0};
        for (int i = 0; i < meet.length; i++) {
            days.add(new PatternAnalysis.DayMetrics(meet[i], out[i], 0, 0.0));
        }
        assertTrue(PatternAnalysis.meetingOutput(days, CFG).isEmpty(),
                "no meaningful effect ⇒ no claim");
    }

    // ── Finding 3: context-switch cost ──────────────────────────────────────

    @Test
    void contextSwitchCostSurfaces() {
        // Low-switch days (1 switch / 5 focus-h) have high deep work; high-switch low.
        List<PatternAnalysis.DayMetrics> days = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            boolean churny = i >= 10;
            days.add(new PatternAnalysis.DayMetrics(
                    0.0,                      // meetingH (irrelevant here)
                    churny ? 3.0 : 6.0,       // deepWorkH
                    churny ? 10 : 1,          // switches
                    5.0));                    // focusH
        }
        Optional<Findings.Finding> f = PatternAnalysis.contextSwitch(days, CFG);
        assertTrue(f.isPresent());
        assertEquals(Findings.KIND_CONTEXT_SWITCH, f.get().kind());
        assertTrue((double) f.get().evidence().get("focus_cost_h") > 0);
    }

    // ── Confidence gate (P3-B.4) — the headline guarantee ───────────────────

    @Test
    void hardGateBelowMinDaysReturnsEmptyEvenWithStrongData() {
        // Strong, surfaceable data — but only 5 active days, below the floor of 14.
        Findings.PatternFindings out = PatternAnalysis.analyze(
                "member", FROM, TO, 5, strongGrid(), strongDays(), CFG);
        assertTrue(out.lowConfidence(), "below the floor ⇒ low confidence");
        assertTrue(out.findings().isEmpty(), "low-history members get NO findings");
        assertEquals(5, out.historyDays());
    }

    @Test
    void enoughHistorySurfacesRankedFindingsCappedAtThree() {
        Findings.PatternFindings out = PatternAnalysis.analyze(
                "member", FROM, TO, 20, strongGrid(), strongDays(), CFG);
        assertFalse(out.lowConfidence());
        assertFalse(out.findings().isEmpty());
        assertTrue(out.findings().size() <= 3, "kickoff: surface 1–3 findings only");
        // All surfaced findings are high-confidence and ranked by strength desc.
        double prev = Double.MAX_VALUE;
        for (Findings.Finding f : out.findings()) {
            assertEquals(Findings.CONFIDENCE_HIGH, f.confidence());
            assertTrue(f.strength() <= prev, "findings must be ranked by strength");
            prev = f.strength();
        }
    }

    @Test
    void pearsonIsNullWithoutVariance() {
        assertNull(PatternAnalysis.pearson(new double[]{2, 2, 2}, new double[]{1, 2, 3}));
        assertEquals(-1.0, PatternAnalysis.pearson(new double[]{1, 2, 3}, new double[]{3, 2, 1}), 1e-9);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static List<PatternAnalysis.FocusCell> strongGrid() {
        List<PatternAnalysis.FocusCell> grid = new ArrayList<>();
        for (int h = 8; h < 17; h++) {
            grid.add(new PatternAnalysis.FocusCell(1, h, HOUR_MS));
        }
        grid.add(new PatternAnalysis.FocusCell(2, 10, 5 * HOUR_MS));   // spike
        return grid;
    }

    /** Days that trigger BOTH meeting→output and context-switch findings. */
    private static List<PatternAnalysis.DayMetrics> strongDays() {
        List<PatternAnalysis.DayMetrics> days = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            boolean heavy = i >= 10;
            days.add(new PatternAnalysis.DayMetrics(
                    heavy ? 3.0 : 0.5,    // meetingH
                    heavy ? 3.0 : 6.0,    // deepWorkH
                    heavy ? 10 : 1,       // switches
                    5.0));                // focusH
        }
        return days;
    }
}
