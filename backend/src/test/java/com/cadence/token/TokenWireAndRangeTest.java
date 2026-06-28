package com.cadence.token;

import com.cadence.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the P2-C.5 token-endpoint wire shape (the snake_case keys P2-E decodes)
 * and the ?range parsing. DB-free — mirrors the prod Jackson config.
 */
class TokenWireAndRangeTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void meTokensSerializesSnakeCase() throws Exception {
        var byDay = List.of(new TokenDtos.DayModel(
                LocalDate.parse("2026-06-27"), "claude-opus-4-8", new BigDecimal("0.0808"), 30004, 830));
        var byModel = List.of(new TokenDtos.ModelTotal(
                "claude-opus-4-8", new BigDecimal("0.0808"), 30004, 830));
        var me = new TokenDtos.MeTokens(
                OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC),
                byModel, byDay, new TokenDtos.Totals(new BigDecimal("0.0808"), 30004, 830));

        String json = mapper.writeValueAsString(me);
        assertTrue(json.contains("\"by_model\""), json);
        assertTrue(json.contains("\"by_day\""), json);
        assertTrue(json.contains("\"cost_usd\""), json);
        assertTrue(json.contains("\"tokens_in\""), json);
        assertTrue(json.contains("\"tokens_out\""), json);
    }

    @Test
    void orgTokensSerializesSnakeCaseWithMemberBreakdown() throws Exception {
        var byModel = List.of(new TokenDtos.ModelTotal("gpt-5-codex", new BigDecimal("0.0038"), 10200, 58));
        var member = new TokenDtos.MemberTokens(
                UUID.randomUUID(), "Dev One", byModel, new TokenDtos.Totals(new BigDecimal("0.0038"), 10200, 58));
        var org = new TokenDtos.OrgTokens(
                OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC),
                null, "categories_only", byModel, List.of(),
                new TokenDtos.Totals(new BigDecimal("0.0038"), 10200, 58), List.of(member));

        String json = mapper.writeValueAsString(org);
        assertTrue(json.contains("\"org_by_model\""), json);
        assertTrue(json.contains("\"org_by_day\""), json);
        assertTrue(json.contains("\"org_totals\""), json);
        assertTrue(json.contains("\"by_member\""), json);
        assertTrue(json.contains("\"member_id\""), json);
        assertTrue(json.contains("\"display_name\""), json);
        assertTrue(json.contains("\"privacy\""), json);
    }

    @Test
    void rangeParsesKnownTokensAndRejectsUnknown() {
        assertNotNull(TokenRange.parse(null).from());      // defaults to 7d
        assertNotNull(TokenRange.parse("today").from());
        assertNotNull(TokenRange.parse("24h").from());
        assertNotNull(TokenRange.parse("30d").to());
        assertThrows(ApiException.class, () -> TokenRange.parse("nonsense"));
    }
}
