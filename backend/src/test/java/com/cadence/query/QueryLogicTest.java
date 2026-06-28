package com.cadence.query;

import com.cadence.common.ApiException;
import com.cadence.event.EventDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class QueryLogicTest {

    @Test
    void rangeParserKnownTokens() {
        assertNotNull(RangeParser.parse("7d").from());
        assertNotNull(RangeParser.parse("today").from());
        assertNotNull(RangeParser.parse(null).from());          // defaults to 7d
        assertTrue(RangeParser.parse("30d").to().isAfter(RangeParser.parse("30d").from()));
    }

    @Test
    void rangeParserRejectsUnknown() {
        assertThrows(ApiException.class, () -> RangeParser.parse("forever"));
    }

    @Test
    void privacyFullPassesThrough() {
        EventDto e = sample();
        assertSame(e, PrivacyLevel.FULL.redactForAdmin(e));
    }

    @Test
    void categoriesOnlyStripsAppTitleUrl() {
        EventDto redacted = PrivacyLevel.CATEGORIES_ONLY.redactForAdmin(sample());
        assertNull(redacted.app());
        assertNull(redacted.title());
        assertNull(redacted.url());
        assertEquals("cadence-api", redacted.project());   // project + category retained
        assertEquals("deep_work", redacted.category());
    }

    @Test
    void privacyLevelOfDefaultsToCategoriesOnly() {
        assertEquals(PrivacyLevel.CATEGORIES_ONLY, PrivacyLevel.of("nonsense"));
        assertEquals(PrivacyLevel.FULL, PrivacyLevel.of("full"));
        assertEquals(PrivacyLevel.AGGREGATE_ONLY, PrivacyLevel.of("aggregate_only"));
    }

    /**
     * P2-D contract extension: /org/summary carries an additive `commits` facet
     * (source='github' commit counts) in the documented snake_case wire shape, so
     * P2-E + P3-A read commit activity from the one rollup. Mirrors the app's
     * global Jackson config (SNAKE_CASE, RFC3339 dates).
     */
    @Test
    void orgSummaryCarriesAdditiveCommitFacet() throws Exception {
        UUID mid = UUID.randomUUID();
        Summaries.CommitActivity commits = new Summaries.CommitActivity(
                3,
                List.of(new Summaries.DayCount(LocalDate.parse("2026-06-27"), 3)),
                List.of(new Summaries.MemberCommits(mid, "Octo Dev", 3)));
        Summaries.OrgSummary summary = new Summaries.OrgSummary(
                null, null, null, "categories_only",
                List.of(), List.of(), List.of(), commits);

        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        String json = om.writeValueAsString(summary);

        // Additive field present, with the documented nested wire names.
        assertTrue(json.contains("\"commits\""), json);
        assertTrue(json.contains("\"by_day\""), json);
        assertTrue(json.contains("\"by_member\""), json);
        assertTrue(json.contains("\"member_id\""), json);
        assertTrue(json.contains("\"2026-06-27\""), "RFC3339 date for the day bucket");
        assertTrue(json.contains("\"total\":3"), json);
    }

    private static EventDto sample() {
        return new EventDto(UUID.randomUUID(), 1, "vscode", UUID.randomUUID(),
                OffsetDateTime.now(), OffsetDateTime.now(), 1000L,
                "VS Code", "auth.ts", "http://x", "cadence-api", "deep_work", false, null);
    }
}
