package com.cadence.query;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared aggregation helpers for the /me and /org summary endpoints. */
final class QuerySupport {
    private QuerySupport() {}

    /**
     * Runs a {@code (day, category, total_ms, n)} query and folds it into
     * per-day category buckets, preserving day order.
     */
    static List<Summaries.DayBucket> byDay(JdbcTemplate jdbc, String sql, Object... args) {
        Map<LocalDate, List<Summaries.CategoryBucket>> days = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            LocalDate day = rs.getObject("day", LocalDate.class);
            days.computeIfAbsent(day, d -> new ArrayList<>()).add(new Summaries.CategoryBucket(
                    rs.getString("category"), rs.getLong("total_ms"), rs.getLong("n")));
        }, args);
        List<Summaries.DayBucket> out = new ArrayList<>(days.size());
        days.forEach((d, cats) -> out.add(new Summaries.DayBucket(d, cats)));
        return out;
    }

    /** Runs a {@code (model, cost_usd, tokens_in, tokens_out)} query into a TokenSummary. */
    static Summaries.TokenSummary tokenSummary(JdbcTemplate jdbc, String sql, Object... args) {
        List<Summaries.ModelBucket> byModel = jdbc.query(sql, (rs, i) -> new Summaries.ModelBucket(
                rs.getString("model"),
                rs.getBigDecimal("cost_usd") == null ? BigDecimal.ZERO : rs.getBigDecimal("cost_usd"),
                rs.getLong("tokens_in"),
                rs.getLong("tokens_out")), args);
        BigDecimal total = byModel.stream()
                .map(Summaries.ModelBucket::costUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Summaries.TokenSummary(total, byModel);
    }
}
