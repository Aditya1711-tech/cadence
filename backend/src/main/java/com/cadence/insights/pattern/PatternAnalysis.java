package com.cadence.insights.pattern;

import java.time.OffsetDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * P3-B.2/.4 — the pattern models, as PURE FUNCTIONS over already-rolled-up
 * series. No JDBC, no Spring: this is the unit-tested core (the data access lives
 * in {@link PatternService}). "Simple models" per the phase doc — concentration
 * ratios, Pearson correlation and median splits, no ML infra.
 *
 * <p>Confidence is two-gated (P3-B.4): the hard history floor ({@code minDays})
 * short-circuits {@link #analyze} to an empty list, and each finding then has its
 * own evidence bar. Only high-confidence findings survive; the top ≤ 3 by
 * strength are returned.
 */
final class PatternAnalysis {
    private PatternAnalysis() {}

    /** Focused cognitive work (mirrors P3-A §3.1 exactly — kept in lockstep). */
    static final List<String> FOCUS_CATEGORIES =
            List.of("deep_work", "code_review", "ai_assisted", "research");

    private static final double MS_PER_HOUR = 3_600_000.0;
    private static final int MAX_FINDINGS = 3;          // kickoff: 1–3 findings only
    private static final int MIN_ACTIVE_CELLS = 3;      // peak needs a real distribution
    private static final int MIN_SPLIT_DAYS = 3;        // each side of a median split

    /** One hour-of-week focus cell, summed over the window (isoDow 1=Mon..7=Sun). */
    record FocusCell(int isoDow, int hour, long focusMs) {}

    /** Per-day metrics; the unit of the correlation findings. */
    record DayMetrics(double meetingH, double deepWorkH, int switches, double focusH) {
        double switchesPerFocusH() {
            return focusH > 0 ? switches / focusH : 0.0;
        }
    }

    /**
     * Run all models for one grain over one window. Applies the hard history gate
     * first, then collects findings that clear their own bars, ranks by strength
     * and caps at {@value #MAX_FINDINGS}.
     */
    static Findings.PatternFindings analyze(
            String grain, OffsetDateTime from, OffsetDateTime to,
            int historyDays, List<FocusCell> grid, List<DayMetrics> days,
            PatternProperties cfg) {

        if (historyDays < cfg.minDays()) {
            // Low-data member/org: no claims at all (P3-B.4).
            return new Findings.PatternFindings(grain, from, to, historyDays, true, List.of());
        }

        List<Findings.Finding> found = new ArrayList<>();
        peakWindow(grid, cfg).ifPresent(found::add);
        meetingOutput(days, cfg).ifPresent(found::add);
        contextSwitch(days, cfg).ifPresent(found::add);

        found.sort(Comparator.comparingDouble(Findings.Finding::strength).reversed());
        if (found.size() > MAX_FINDINGS) {
            found = found.subList(0, MAX_FINDINGS);
        }
        return new Findings.PatternFindings(grain, from, to, historyDays, false, List.copyOf(found));
    }

    // ── Finding 1: peak productivity window ─────────────────────────────────
    // The hour-of-week where focus concentrates, vs the average active hour.
    static Optional<Findings.Finding> peakWindow(List<FocusCell> grid, PatternProperties cfg) {
        List<FocusCell> active = grid.stream().filter(c -> c.focusMs() > 0).toList();
        if (active.size() < MIN_ACTIVE_CELLS) {
            return Optional.empty();
        }
        long total = active.stream().mapToLong(FocusCell::focusMs).sum();
        if (total <= 0) {
            return Optional.empty();
        }
        FocusCell peak = active.stream().max(Comparator.comparingLong(FocusCell::focusMs)).orElseThrow();
        double meanActive = (double) total / active.size();
        double ratio = peak.focusMs() / meanActive;              // 1.0 = perfectly flat
        if (ratio < cfg.peakConcentration()) {
            return Optional.empty();
        }
        double sharePct = round1(100.0 * peak.focusMs() / total);
        double peakHours = round1(peak.focusMs() / MS_PER_HOUR);
        String dow = dayName(peak.isoDow());
        double strength = clamp01((ratio - 1.0) / 4.0);          // ratio 5 ⇒ 1.0

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("iso_dow", peak.isoDow());
        evidence.put("hour", peak.hour());
        evidence.put("peak_focus_h", peakHours);
        evidence.put("share_of_focus_pct", sharePct);
        evidence.put("concentration_ratio", round2(ratio));
        evidence.put("active_cells", active.size());

        return Optional.of(new Findings.Finding(
                Findings.KIND_PEAK_WINDOW,
                "Peak focus: " + dow + " around " + twoDigit(peak.hour()) + ":00",
                "Your most focused hour is " + dow + " ~" + twoDigit(peak.hour()) + ":00 (UTC), holding "
                        + trim(sharePct) + "% of your focused time — about "
                        + trim(ratio) + "× a typical active hour.",
                Findings.CONFIDENCE_HIGH, strength, evidence));
    }

    // ── Finding 2: meeting → output correlation ─────────────────────────────
    // Daily meeting hours vs daily deep-work hours: Pearson r + median split.
    static Optional<Findings.Finding> meetingOutput(List<DayMetrics> days, PatternProperties cfg) {
        double[] meet = days.stream().mapToDouble(DayMetrics::meetingH).toArray();
        double[] out = days.stream().mapToDouble(DayMetrics::deepWorkH).toArray();
        if (!hasVariance(meet) || !hasVariance(out)) {
            return Optional.empty();                              // nothing to correlate
        }
        Double r = pearson(meet, out);
        if (r == null || Math.abs(r) < cfg.minCorrelation()) {
            return Optional.empty();
        }
        Split split = medianSplit(days, DayMetrics::meetingH, DayMetrics::deepWorkH);
        if (split == null) {
            return Optional.empty();
        }
        // Fractional change in output on heavy- vs light-meeting days.
        double effect = (split.lowMean() - split.highMean()) / Math.max(split.lowMean(), 1e-9);
        if (Math.abs(effect) < cfg.minEffect()) {
            return Optional.empty();
        }
        double pctDelta = round1(Math.abs(effect) * 100.0);
        double lowH = round1(split.lowMean());
        double highH = round1(split.highMean());

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("pearson_r", round2(r));
        evidence.put("days", days.size());
        evidence.put("meeting_median_h", round1(split.threshold()));
        evidence.put("deep_work_light_meeting_h", lowH);
        evidence.put("deep_work_heavy_meeting_h", highH);
        evidence.put("output_delta_pct", round1(effect * 100.0));

        String title;
        String detail;
        if (effect > 0) {                                        // heavy meetings ⇒ less output
            title = "Meetings cut into your deep work";
            detail = "On heavy-meeting days your deep work drops about " + trim(pctDelta)
                    + "% (" + trim(lowH) + "h → " + trim(highH) + "h), r=" + trim(round2(r)) + ".";
        } else {                                                 // heavy meetings alongside more output
            title = "Meetings and deep work rise together";
            detail = "Heavier-meeting days also carry more deep work (" + trim(lowH) + "h → "
                    + trim(highH) + "h), r=" + trim(round2(r)) + ".";
        }
        return Optional.of(new Findings.Finding(
                Findings.KIND_MEETING_OUTPUT, title, detail,
                Findings.CONFIDENCE_HIGH, Math.abs(r), evidence));
    }

    // ── Finding 3: context-switch cost ──────────────────────────────────────
    // Daily project-switch rate (P3-A §3.2 fragmentation) vs deep-work output.
    static Optional<Findings.Finding> contextSwitch(List<DayMetrics> days, PatternProperties cfg) {
        List<DayMetrics> focusDays = days.stream().filter(d -> d.focusH() > 0).toList();
        double[] sw = focusDays.stream().mapToDouble(DayMetrics::switchesPerFocusH).toArray();
        double[] out = focusDays.stream().mapToDouble(DayMetrics::deepWorkH).toArray();
        if (!hasVariance(sw) || !hasVariance(out)) {
            return Optional.empty();
        }
        Double r = pearson(sw, out);
        if (r == null || Math.abs(r) < cfg.minCorrelation()) {
            return Optional.empty();
        }
        Split split = medianSplit(focusDays, DayMetrics::switchesPerFocusH, DayMetrics::deepWorkH);
        if (split == null) {
            return Optional.empty();
        }
        double effect = (split.lowMean() - split.highMean()) / Math.max(split.lowMean(), 1e-9);
        if (Math.abs(effect) < cfg.minEffect()) {
            return Optional.empty();
        }
        double lowH = round1(split.lowMean());
        double highH = round1(split.highMean());
        double costH = round1(split.lowMean() - split.highMean());

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("pearson_r", round2(r));
        evidence.put("focus_days", focusDays.size());
        evidence.put("switch_median_per_focus_h", round2(split.threshold()));
        evidence.put("deep_work_low_switch_h", lowH);
        evidence.put("deep_work_high_switch_h", highH);
        evidence.put("focus_cost_h", costH);

        String detail = effect > 0
                ? "High context-switching days cost about " + trim(Math.abs(costH)) + "h of deep work ("
                        + trim(lowH) + "h → " + trim(highH) + "h), r=" + trim(round2(r)) + "."
                : "More context-switching tracks with more deep work here (" + trim(lowH) + "h → "
                        + trim(highH) + "h), r=" + trim(round2(r)) + ".";
        return Optional.of(new Findings.Finding(
                Findings.KIND_CONTEXT_SWITCH,
                effect > 0 ? "Context-switching is costing you focus" : "Context-switching, no measurable cost",
                detail, Findings.CONFIDENCE_HIGH, Math.abs(r), evidence));
    }

    // ── stats helpers ───────────────────────────────────────────────────────

    private interface ToD { double apply(DayMetrics d); }

    private record Split(double threshold, double lowMean, double highMean) {}

    /**
     * Split days at the median of {@code key}; mean {@code value} for the low
     * (≤ median) vs high (&gt; median) groups. Null if either side is too thin.
     */
    private static Split medianSplit(List<DayMetrics> days, ToD key, ToD value) {
        double[] keys = days.stream().mapToDouble(key::apply).sorted().toArray();
        double median = median(keys);
        List<Double> low = new ArrayList<>();
        List<Double> high = new ArrayList<>();
        for (DayMetrics d : days) {
            if (key.apply(d) > median) {
                high.add(value.apply(d));
            } else {
                low.add(value.apply(d));
            }
        }
        if (low.size() < MIN_SPLIT_DAYS || high.size() < MIN_SPLIT_DAYS) {
            return null;
        }
        return new Split(median, mean(low), mean(high));
    }

    /** Pearson correlation; null if either series has zero variance. */
    static Double pearson(double[] x, double[] y) {
        int n = x.length;
        if (n < 2 || n != y.length) {
            return null;
        }
        double mx = mean(x), my = mean(y);
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx, dy = y[i] - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        if (sxx <= 0 || syy <= 0) {
            return null;
        }
        return sxy / Math.sqrt(sxx * syy);
    }

    private static boolean hasVariance(double[] a) {
        if (a.length < 2) {
            return false;
        }
        double first = a[0];
        for (double v : a) {
            if (v != first) {
                return true;
            }
        }
        return false;
    }

    private static double median(double[] sorted) {
        int n = sorted.length;
        if (n == 0) {
            return 0;
        }
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    private static double mean(double[] a) {
        double s = 0;
        for (double v : a) {
            s += v;
        }
        return a.length == 0 ? 0 : s / a.length;
    }

    private static double mean(List<Double> a) {
        double s = 0;
        for (double v : a) {
            s += v;
        }
        return a.isEmpty() ? 0 : s / a.size();
    }

    private static String dayName(int isoDow) {
        // isoDow 1=Mon..7=Sun → java.time.DayOfWeek
        return java.time.DayOfWeek.of(isoDow).getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    private static String twoDigit(int hour) {
        return (hour < 10 ? "0" : "") + hour;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Drop a trailing ".0" so prose reads "30%" not "30.0%". */
    private static String trim(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }
}
