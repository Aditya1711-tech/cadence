package com.cadence.query;

import com.cadence.common.ApiException;
import com.cadence.event.EventDto;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
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

    private static EventDto sample() {
        return new EventDto(UUID.randomUUID(), 1, "vscode", UUID.randomUUID(),
                OffsetDateTime.now(), OffsetDateTime.now(), 1000L,
                "VS Code", "auth.ts", "http://x", "cadence-api", "deep_work", false, null);
    }
}
