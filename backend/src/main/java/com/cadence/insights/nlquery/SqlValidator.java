package com.cadence.insights.nlquery;

import com.cadence.common.ApiException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The security gate for the NL-query path (P3-C.1 §3/§4). The SQL handed in is
 * <b>LLM-generated and treated as hostile</b>; this validator proves —
 * structurally, fail-closed — that it is a single read-only SELECT touching only
 * {@link SqlAllowlist}ed tables and columns before it is ever executed. Anything
 * it cannot parse and prove safe is rejected (never executed).
 *
 * <p>This is app-layer policy on top of the DB-hard guarantees (SELECT-only
 * {@code cadence_readonly} role + non-owner RLS + statement_timeout/row-cap). It
 * is the layer that blocks reading sensitive columns within the caller's own org
 * (which RLS does not stop). Pure logic — no Spring/DB/LLM dependency, so it is
 * unit-tested unconditionally.
 */
public final class SqlValidator {

    /** Coarse denylist pre-filter (P3-C.1 §3.1) — belt in front of the parser. */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|create|grant|revoke|truncate|copy|call|do|"
            + "merge|vacuum|analyze|reindex|cluster|comment|security|label|set|reset|begin|"
            + "commit|rollback|savepoint|listen|notify|lock|into|returning|nextval|setval|"
            + "currval|set_config|pg_sleep|pg_read_file|pg_ls_dir|pg_terminate_backend|"
            + "lo_import|lo_export|dblink)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Sensitive column names that must never be selectable (belt; the positive
     * column allowlist is the real gate). */
    private static final Pattern SENSITIVE_COL = Pattern.compile(
            "\\b(password_hash|token_hash|password|secret|title|url|email)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * @return the trimmed, validated SQL (safe to execute under the readonly role).
     * @throws ApiException 422 if the SQL violates any rule.
     */
    public String validate(String rawSql, SqlAllowlist allowlist) {
        if (rawSql == null || rawSql.isBlank()) {
            throw reject("empty SQL");
        }
        String sql = rawSql.strip();
        // Strip a single trailing semicolon, then forbid any remaining one.
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).strip();
        }
        if (sql.contains(";")) {
            throw reject("multiple statements are not allowed");
        }
        if (sql.contains("--") || sql.contains("/*")) {
            throw reject("SQL comments are not allowed");
        }
        if (FORBIDDEN.matcher(sql).find()) {
            throw reject("contains a forbidden keyword or function");
        }
        if (SENSITIVE_COL.matcher(sql).find()) {
            throw reject("references a sensitive column");
        }

        Statement stmt;
        try {
            stmt = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            // Fail closed: if we cannot fully parse it, we do not run it.
            throw reject("could not be parsed as a safe query");
        }
        if (!(stmt instanceof Select select)) {
            throw reject("only SELECT statements are allowed");
        }
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            throw reject("WITH/CTE queries are not allowed");
        }
        if (!(select instanceof PlainSelect plain)) {
            throw reject("only a single flat SELECT is allowed (no UNION)");
        }
        validatePlainSelect(plain, allowlist);
        return sql;
    }

    private void validatePlainSelect(PlainSelect ps, SqlAllowlist allow) {
        // SELECT INTO is a write disguised as a select. (FOR UPDATE is caught by
        // the FORBIDDEN "update" keyword pre-filter.)
        if (ps.getIntoTables() != null && !ps.getIntoTables().isEmpty()) {
            throw reject("SELECT INTO is not allowed");
        }

        // FROM + JOINs must be bare allowlisted base tables (no subqueries/derived).
        Set<String> outputAliases = new LinkedHashSet<>();
        requireAllowedTable(ps.getFromItem(), allow);
        if (ps.getJoins() != null) {
            for (Join j : ps.getJoins()) {
                requireAllowedTable(j.getRightItem(), allow);
            }
        }

        // SELECT items: reject *, collect output aliases, collect columns.
        ColumnCollector cols = new ColumnCollector();
        for (SelectItem<?> item : ps.getSelectItems()) {
            Expression expr = item.getExpression();
            if (expr instanceof AllColumns || expr instanceof AllTableColumns) {
                throw reject("SELECT * is not allowed (it would expose excluded columns)");
            }
            if (item.getAlias() != null) {
                outputAliases.add(item.getAlias().getName().toLowerCase(Locale.ROOT));
            }
            accept(expr, cols);
        }

        // Collect columns from every other clause.
        accept(ps.getWhere(), cols);
        accept(ps.getHaving(), cols);
        if (ps.getJoins() != null) {
            for (Join j : ps.getJoins()) {
                if (j.getOnExpressions() != null) {
                    j.getOnExpressions().forEach(e -> accept(e, cols));
                }
            }
        }
        if (ps.getGroupBy() != null && ps.getGroupBy().getGroupByExpressionList() != null) {
            for (Object e : ps.getGroupBy().getGroupByExpressionList()) {
                accept((Expression) e, cols);
            }
        }
        if (ps.getOrderByElements() != null) {
            for (OrderByElement o : ps.getOrderByElements()) {
                accept(o.getExpression(), cols);
            }
        }

        // Every referenced column must be allowlisted OR a query-defined output
        // alias (e.g. `ORDER BY hours` where `hours` is `... AS hours`).
        for (String c : cols.names) {
            if (!allow.allowsColumn(c) && !outputAliases.contains(c)) {
                throw reject("column `" + c + "` is not queryable");
            }
        }
    }

    private void requireAllowedTable(FromItem from, SqlAllowlist allow) {
        if (!(from instanceof Table table)) {
            throw reject("only base tables may be queried (no subqueries)");
        }
        if (table.getSchemaName() != null) {
            throw reject("schema-qualified tables are not allowed: " + table.getFullyQualifiedName());
        }
        if (!allow.allowsTable(table.getName())) {
            throw reject("table `" + table.getName() + "` is not queryable");
        }
    }

    private static void accept(net.sf.jsqlparser.expression.Expression e, ColumnCollector c) {
        if (e != null) {
            e.accept(c);
        }
    }

    /** Collects every bare/qualified column name referenced anywhere it visits. */
    private static final class ColumnCollector extends ExpressionVisitorAdapter {
        final Set<String> names = new LinkedHashSet<>();

        @Override
        public void visit(Column column) {
            names.add(column.getColumnName().toLowerCase(Locale.ROOT));
            super.visit(column);
        }
    }

    private static ApiException reject(String why) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable Entity",
                "Generated query rejected: " + why + ".");
    }
}
