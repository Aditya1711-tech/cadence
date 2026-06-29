package com.cadence.insights.nlquery;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.cadence.common.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.StringJoiner;

/**
 * The LLM boundary for the NL-query path (P3-C.2, §6). It does two things, and
 * <b>never sees raw event rows</b>:
 * <ol>
 *   <li>{@link #generateSql} — given the question and the privacy-bounded schema
 *       <i>metadata</i> (table/column names only), returns a candidate SQL string
 *       via structured output. The output is still treated as hostile and must
 *       pass {@link SqlValidator} before execution.</li>
 *   <li>{@link #caption} — given column names + the already-capped result rows,
 *       writes one or two plain sentences. It sees no more than the capped result
 *       and never the excluded {@code title}/{@code url} fields.</li>
 * </ol>
 *
 * <p>Holds its own {@link AnthropicClient} (constructed from the environment)
 * rather than a shared bean, so it never collides with P2-F's worker client. The
 * whole stack is gated on {@code cadence.nlquery.enabled=true}.
 */
@Component
@ConditionalOnProperty(prefix = "cadence.nlquery", name = "enabled", havingValue = "true")
class NlSqlPlanner {

    /** Structured-output target for SQL generation: a single SQL string. */
    record SqlPlan(String sql) {}

    private static final String SYSTEM = """
            You translate a question about a software team's activity into ONE
            PostgreSQL read-only SELECT over a fixed schema. Hard rules:
            - Output ONE single SELECT statement. Never INSERT/UPDATE/DELETE/DDL.
            - Use ONLY the tables and columns listed in the user message. Never
              SELECT *. Never use subqueries, CTEs (WITH), or UNION.
            - Always AGGREGATE (GROUP BY / sum / count / avg) — return summary rows,
              not raw events. Keep the result small.
            - Do NOT add an org filter; org isolation is enforced automatically by
              the database. Never reference org_id, system catalogs, or any table
              not listed.
            - For relative periods use now()/current_date/date_trunc/interval in SQL
              (e.g. ts_start >= date_trunc('week', now()) - interval '1 week').
            - duration_ms is milliseconds; divide by 3600000.0 for hours. Category
              values: deep_work, meetings, comms, research, code_review,
              ai_assisted, idle, other. source values: os, vscode, chrome, token,
              github.
            Return only the SQL via the structured output field.""";

    private final AnthropicClient client;
    private final NlQueryProperties props;

    NlSqlPlanner(NlQueryProperties props) {
        this.props = props;
        this.client = AnthropicOkHttpClient.fromEnv();
    }

    /** Generate a candidate SQL string. Throws 502 if the model returns nothing. */
    String generateSql(String question, SqlAllowlist allow) {
        StructuredMessageCreateParams<SqlPlan> params = MessageCreateParams.builder()
                .model(props.model())
                .maxTokens(props.maxOutputTokens())
                .system(SYSTEM)
                .addUserMessage("Schema (only these tables/columns may be used):\n"
                        + schemaDescription(allow)
                        + "\n\nQuestion: " + question
                        + "\n\nReturn one PostgreSQL SELECT.")
                .outputConfig(SqlPlan.class)
                .build();

        var message = client.messages().create(params);
        String sql = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(typed -> typed.text().sql())
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse(null);
        if (sql == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Bad Gateway",
                    "The model did not return a query for that question.");
        }
        return sql;
    }

    /** Write a short caption grounded in the capped result. Best-effort (never throws). */
    String caption(String question, List<String> columns, List<List<Object>> rows) {
        try {
            StringBuilder table = new StringBuilder(String.join(" | ", columns)).append('\n');
            int shown = Math.min(rows.size(), 50);   // the model needs only a sample to caption
            for (int i = 0; i < shown; i++) {
                StringJoiner sj = new StringJoiner(" | ");
                for (Object cell : rows.get(i)) {
                    sj.add(cell == null ? "" : String.valueOf(cell));
                }
                table.append(sj).append('\n');
            }
            var message = client.messages().create(MessageCreateParams.builder()
                    .model(props.model())
                    .maxTokens(Math.min(props.maxOutputTokens(), 256))
                    .system("You write a one or two sentence plain-English caption for a "
                            + "query result. State the headline numbers. No preamble, no markdown.")
                    .addUserMessage("Question: " + question + "\n\nResult:\n" + table)
                    .build());
            return message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(t -> t.text())
                    .reduce("", String::concat)
                    .strip();
        } catch (Exception e) {
            return "";   // a missing caption never fails the query.
        }
    }

    /** The privacy-bounded schema metadata handed to the model (names only). */
    static String schemaDescription(SqlAllowlist allow) {
        StringBuilder sb = new StringBuilder();
        for (String table : allow.tableNames()) {
            sb.append(table).append('(')
              .append(String.join(", ", allow.columnsOf(table)))
              .append(")\n");
        }
        return sb.toString();
    }
}
