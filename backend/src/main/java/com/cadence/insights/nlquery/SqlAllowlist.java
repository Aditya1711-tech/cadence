package com.cadence.insights.nlquery;

import com.cadence.query.PrivacyLevel;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The privacy-level-aware table/column allowlist (P3-C.1 §4) — the surface the
 * LLM may query and the {@link SqlValidator} accepts. <b>Everything not listed
 * here is rejected.</b>
 *
 * <p>Security note: {@code cadence_readonly} is granted {@code SELECT ON ALL
 * TABLES}, so this app-layer allowlist is the only thing stopping the generated
 * SQL from reading sensitive columns (e.g. {@code members.password_hash},
 * {@code events.title}/{@code url}, the token-hash tables) <i>within the caller's
 * own org</i> — RLS scopes by org, not by column. The allowlist is therefore a
 * genuine security boundary, enforced structurally (not by regex) on the parsed
 * statement.
 *
 * <p>Privacy binding (§8): under {@code aggregate_only}, the {@code member_id}
 * column and the per-member tables ({@code members}, {@code team_members}) are
 * dropped so only org-level aggregates are answerable — "no per-person detail
 * under aggregate_only" becomes true by construction (the column isn't
 * selectable). All identifiers are compared case-insensitively (lower-cased).
 */
public final class SqlAllowlist {

    /** Tables → the columns exposed on them. Lower-case throughout. */
    private final Map<String, Set<String>> tables;

    private SqlAllowlist(Map<String, Set<String>> tables) {
        this.tables = tables;
    }

    /** The effective allowlist for an org's privacy level. */
    public static SqlAllowlist forPrivacy(PrivacyLevel level) {
        boolean perMember = level != PrivacyLevel.AGGREGATE_ONLY;
        Map<String, Set<String>> t = new LinkedHashMap<>();

        // events — raw hypertable. NOTE: title and url (free-text §8 fields) and
        // org_id are deliberately absent. meta is allowed: it carries only token
        // counts/cost/model and github commit_sha/repo (never prompt/response
        // content — §8), no free text.
        t.put("events", cols(perMember,
                "source", "ts_start", "ts_end", "duration_ms",
                "app", "project", "category", "is_idle", "meta"));

        // Continuous aggregates (fast pre-rollups).
        t.put("events_daily_by_category", cols(perMember,
                "bucket", "category", "total_ms", "event_count"));
        t.put("events_hourly_by_category", cols(perMember,
                "bucket", "category", "total_ms", "event_count"));
        t.put("events_daily_tokens", cols(perMember,
                "bucket", "model", "cost_usd", "tokens_in", "tokens_out"));

        // P3-A weekly aggregated facts (read-only). insights has both grains; the
        // per-member rows are only exposed when per-member detail is allowed.
        if (perMember) {
            t.put("insights", set("member_id", "grain", "iso_week",
                    "period_start", "period_end",
                    "deep_work_h", "meeting_h", "token_cost_usd", "commits",
                    "fragmentation_index"));
        } else {
            t.put("insights", set("grain", "iso_week", "period_start", "period_end",
                    "deep_work_h", "meeting_h", "token_cost_usd", "commits",
                    "fragmentation_index"));
        }

        // Per-member identity + team tables — omitted entirely under aggregate_only.
        if (perMember) {
            t.put("members", set("id", "display_name", "role", "status", "github_login"));
            t.put("teams", set("id", "name"));
            t.put("team_members", set("team_id", "member_id"));
        } else {
            // teams (org-level) stays available for labels; team_members (the
            // member join) is dropped with the rest of the per-member surface.
            t.put("teams", set("id", "name"));
        }
        return new SqlAllowlist(t);
    }

    /** True if {@code table} (any case) is queryable. */
    public boolean allowsTable(String table) {
        return table != null && tables.containsKey(table.toLowerCase(Locale.ROOT));
    }

    /**
     * True if {@code column} (any case) is a column on SOME allowlisted table that
     * appears in the query. Sensitive columns (password_hash, title, url, email,
     * token_hash, …) are on no allowlist, so they are rejected regardless of which
     * table alias they are written against — the security property does not depend
     * on alias resolution.
     */
    public boolean allowsColumn(String column) {
        if (column == null) {
            return false;
        }
        String c = column.toLowerCase(Locale.ROOT);
        for (Set<String> cs : tables.values()) {
            if (cs.contains(c)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> tableNames() {
        return tables.keySet();
    }

    public Set<String> columnsOf(String table) {
        return tables.getOrDefault(table.toLowerCase(Locale.ROOT), Set.of());
    }

    private static Set<String> cols(boolean perMember, String... base) {
        Set<String> s = new java.util.LinkedHashSet<>();
        if (perMember) {
            s.add("member_id");
        }
        for (String b : base) {
            s.add(b);
        }
        return s;
    }

    private static Set<String> set(String... cs) {
        return new java.util.LinkedHashSet<>(java.util.Arrays.asList(cs));
    }
}
