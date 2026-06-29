package com.cadence.insights.nlquery;

import java.util.List;

/** Wire shapes for POST /api/v1/query/nl (P3-C.3, §6). snake_case via global Jackson. */
public final class NlQueryDtos {
    private NlQueryDtos() {}

    /** Request body. */
    public record NlQueryRequest(String question) {}

    /**
     * Response: the validated SQL that ran, the capped tabular result, and a short
     * model-written caption. {@code rows} are already capped at
     * CADENCE_NLQUERY_MAX_ROWS; {@code truncated} flags when more existed.
     */
    public record NlQueryResponse(
            String question,
            String sql,
            List<String> columns,
            List<List<Object>> rows,
            int rowCount,
            boolean truncated,
            String caption
    ) {}
}
