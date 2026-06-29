package com.cadence.insights.budget;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Folds raw {@code events_daily_tokens} cells into {@link SubjectBurn}s — one per
 * member plus one for the whole org — for the day under evaluation. Pure and
 * DB-free so the burn maths is unit-tested directly.
 *
 * <p>For each subject: today's spend is the sum over {@code day}; the baseline is
 * the list of prior-day sums that were &gt; 0 (active days only — §1.2); the top
 * model is the model with the greatest spend on {@code today}.
 */
final class BudgetWindowAssembler {

    private BudgetWindowAssembler() {
    }

    static List<SubjectBurn> assemble(List<TokenCell> cells, LocalDate today, String orgName) {
        // member_id -> (day -> total), member_id -> (model -> today-cost), names
        var memberDaily = new LinkedHashMap<UUID, Map<LocalDate, Double>>();
        var memberTodayModels = new LinkedHashMap<UUID, Map<String, Double>>();
        var names = new LinkedHashMap<UUID, String>();
        // org-wide
        var orgDaily = new LinkedHashMap<LocalDate, Double>();
        var orgTodayModels = new LinkedHashMap<String, Double>();

        for (TokenCell c : cells) {
            names.putIfAbsent(c.memberId(), c.memberName());
            memberDaily.computeIfAbsent(c.memberId(), k -> new LinkedHashMap<>())
                    .merge(c.day(), c.costUsd(), Double::sum);
            orgDaily.merge(c.day(), c.costUsd(), Double::sum);
            if (c.day().equals(today)) {
                memberTodayModels.computeIfAbsent(c.memberId(), k -> new LinkedHashMap<>())
                        .merge(c.model(), c.costUsd(), Double::sum);
                orgTodayModels.merge(c.model(), c.costUsd(), Double::sum);
            }
        }

        List<SubjectBurn> out = new ArrayList<>();
        // Org grain first (so a team-wide spike is always considered).
        out.add(new SubjectBurn(SubjectType.ORG, null, orgName,
                orgDaily.getOrDefault(today, 0.0), baseline(orgDaily, today), topModel(orgTodayModels)));
        for (Map.Entry<UUID, Map<LocalDate, Double>> e : memberDaily.entrySet()) {
            UUID mid = e.getKey();
            out.add(new SubjectBurn(SubjectType.MEMBER, mid, names.get(mid),
                    e.getValue().getOrDefault(today, 0.0),
                    baseline(e.getValue(), today),
                    topModel(memberTodayModels.get(mid))));
        }
        return out;
    }

    /** Prior-day sums that were active (&gt; 0). Today is excluded from the baseline. */
    private static List<Double> baseline(Map<LocalDate, Double> daily, LocalDate today) {
        List<Double> active = new ArrayList<>();
        for (Map.Entry<LocalDate, Double> e : daily.entrySet()) {
            if (e.getKey().isBefore(today) && e.getValue() > 0) {
                active.add(e.getValue());
            }
        }
        return active;
    }

    private static String topModel(Map<String, Double> models) {
        if (models == null || models.isEmpty()) {
            return null;
        }
        String top = null;
        double max = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, Double> e : models.entrySet()) {
            if (e.getValue() > max) {
                max = e.getValue();
                top = e.getKey();
            }
        }
        return top;
    }
}
