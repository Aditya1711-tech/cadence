package com.cadence.insights;

import com.cadence.common.ApiException;
import com.cadence.insights.InsightFacts.Deltas;
import com.cadence.insights.InsightFacts.Fragmentation;
import com.cadence.insights.InsightFacts.MemberWeekFacts;
import com.cadence.insights.InsightFacts.PeakBlock;
import com.cadence.insights.InsightFacts.Period;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for P3-A.4 (no DB): ISO-week math, fragmentation curve, wire shape. */
class InsightsLogicTest {

    // ── IsoWeek ───────────────────────────────────────────────────────────────

    @Test
    void isoWeekParsesAndRoundTripsLabel() {
        IsoWeek w = IsoWeek.parse("2026-W26");
        assertEquals(2026, w.weekYear());
        assertEquals(26, w.week());
        assertEquals("2026-W26", w.label());
        assertEquals("2026-W05", IsoWeek.parse("2026-w5").label());   // case + zero-pad tolerant
    }

    @Test
    void isoWeekRejectsGarbage() {
        assertThrows(ApiException.class, () -> IsoWeek.parse("nope"));
        assertThrows(ApiException.class, () -> IsoWeek.parse("2026-W99"));
        assertThrows(ApiException.class, () -> IsoWeek.parse(""));
    }

    @Test
    void weekWindowIsMondayToNextMondayUtc() {
        IsoWeek w = IsoWeek.parse("2026-W26");
        OffsetDateTime start = w.start();
        assertEquals(DayOfWeek.MONDAY, start.getDayOfWeek());
        assertEquals(ZoneOffset.UTC, start.getOffset());
        assertEquals(0, start.getHour());
        assertEquals(start.plusWeeks(1), w.end());                    // exclusive end, 7 days
    }

    @Test
    void mostRecentCompletedAbutsCurrentWeek() {
        OffsetDateTime now = OffsetDateTime.parse("2026-06-29T12:00:00Z");
        // the completed week's exclusive end == the current week's start (no gap, no overlap)
        assertEquals(IsoWeek.current(now).start(), IsoWeek.mostRecentCompleted(now).end());
    }

    @Test
    void historyWeeksIsInclusiveAndZeroWhenNoData() {
        IsoWeek w = IsoWeek.parse("2026-W26");
        assertEquals(0, w.historyWeeksSince(null));
        assertEquals(4, w.historyWeeksSince(w.start().minusWeeks(3)));  // weeks -3,-2,-1,target
        assertEquals(1, w.historyWeeksSince(w.start()));
    }

    // ── Fragmentation curve (P3-A.1 §3.3) ──────────────────────────────────────

    @Test
    void fragmentationIndexSaturates() {
        assertEquals(0.0, InsightsAggregationService.indexFrom(0.0, 4.0));
        assertEquals(50.0, InsightsAggregationService.indexFrom(2.0, 4.0));
        assertEquals(100.0, InsightsAggregationService.indexFrom(4.0, 4.0));
        assertEquals(100.0, InsightsAggregationService.indexFrom(9.9, 4.0));   // clamped
    }

    // ── Wire shape ──────────────────────────────────────────────────────────────

    @Test
    void memberFactsSerializeToDocumentedSnakeCase() throws Exception {
        Map<String, Double> byCat = new LinkedHashMap<>();
        byCat.put("deep_work", 18.4);
        byCat.put("meetings", 6.2);
        OffsetDateTime from = OffsetDateTime.parse("2026-06-22T00:00:00Z");
        MemberWeekFacts facts = new MemberWeekFacts(
                UUID.randomUUID(), UUID.randomUUID(), "Octo Dev", "member",
                new Period(from, from.plusWeeks(1), "2026-W26"),
                18.4, 6.2, new BigDecimal("4.73"), 27, 38,
                byCat, 412000, 98000,
                new Fragmentation(71, 2.7, 22.0),
                new PeakBlock("Tue", 10, "deep_work", 5_400_000L),
                new Deltas(3.1, -1.4, new BigDecimal("0.90"), 6, -5),
                7, false);

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        String json = om.writeValueAsString(facts);

        // frozen headline scalars + supporting fields, exact wire names
        for (String key : new String[]{
                "\"grain\":\"member\"", "\"deep_work_h\":18.4", "\"meeting_h\":6.2",
                "\"token_cost_usd\":4.73", "\"commits\":27", "\"fragmentation_index\":38",
                "\"by_category_h\"", "\"tokens_in\":412000", "\"switches_per_focus_h\":2.7",
                "\"mean_session_min\":22.0", "\"peak_block\"", "\"iso_week\":\"2026-W26\"",
                "\"history_weeks\":7", "\"low_confidence\":false"}) {
            assertTrue(json.contains(key), "missing " + key + " in " + json);
        }
        // the one field whose snake_case is overridden to match the doc exactly
        assertTrue(json.contains("\"deltas_vs_4wk_avg\""), json);
        assertFalse(json.contains("deltas_vs4wk_avg"), "must use the documented name, not raw snake_case");
    }
}
