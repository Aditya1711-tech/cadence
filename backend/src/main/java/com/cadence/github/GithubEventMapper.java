package com.cadence.github;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure mapping from GitHub webhook payloads to {@link GithubEventDraft}s
 * (P2-D.3). No DB, no IO — deterministic and unit-testable.
 *
 * <p>Privacy (P2-D.2/.5): in either {@link GithubMode} we store the commit
 * message subject + sha + repo + branch only. {@code FULL_DIFF} additionally
 * derives {@code changed_files} (a count, from the push payload's file-path
 * array lengths — the paths themselves are never stored); numeric
 * additions/deletions are filled later by the (opt-in) stats enricher. We never
 * store file paths or any patch/code.
 */
@Component
public class GithubEventMapper {

    private static final String APP = "GitHub";

    /** PR actions worth recording as activity; the rest (synchronize, edited, …) are ignored. */
    private static final Set<String> PR_ACTIONS = Set.of(
            "opened", "closed", "reopened", "ready_for_review");

    /** Map a {@code push} payload to one draft per commit. */
    public List<GithubEventDraft> mapPush(JsonNode payload, GithubMode mode) {
        List<GithubEventDraft> out = new ArrayList<>();
        if (payload == null) return out;

        String repoFull = text(payload.path("repository").path("full_name"));
        String repoShort = text(payload.path("repository").path("name"));
        String branch = branchFromRef(text(payload.path("ref")));

        JsonNode commits = payload.path("commits");
        if (!commits.isArray()) return out;

        for (JsonNode c : commits) {
            String sha = text(c.path("id"));
            if (sha == null) continue;
            OffsetDateTime ts = parseTs(text(c.path("timestamp")));
            if (ts == null) continue;

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("commit_sha", sha);
            if (repoFull != null) meta.put("repo", repoFull);
            if (branch != null) meta.put("branch", branch);
            if (mode == GithubMode.FULL_DIFF) {
                // Count only — never the file paths themselves.
                meta.put("changed_files",
                        size(c.path("added")) + size(c.path("removed")) + size(c.path("modified")));
            }

            out.add(new GithubEventDraft(
                    GithubIds.deterministic("push:" + repoFull + ":" + sha),
                    ts,
                    APP,
                    subject(text(c.path("message"))),
                    repoShort,
                    null,                       // commits left uncategorized (not LLM-routed)
                    meta,
                    text(c.path("author").path("username"))));
        }
        return out;
    }

    /** Map a {@code pull_request} payload to zero or one draft (recorded actions only). */
    public List<GithubEventDraft> mapPullRequest(JsonNode payload, GithubMode mode) {
        List<GithubEventDraft> out = new ArrayList<>();
        if (payload == null) return out;

        String action = text(payload.path("action"));
        if (action == null || !PR_ACTIONS.contains(action)) return out;

        JsonNode pr = payload.path("pull_request");
        Integer number = payload.path("number").isInt() ? payload.path("number").asInt()
                : (pr.path("number").isInt() ? pr.path("number").asInt() : null);
        if (number == null) return out;

        String repoFull = text(payload.path("repository").path("full_name"));
        String repoShort = text(payload.path("repository").path("name"));
        OffsetDateTime ts = parseTs(text(pr.path("updated_at")));
        if (ts == null) ts = parseTs(text(pr.path("created_at")));
        if (ts == null) return out;

        Map<String, Object> meta = new LinkedHashMap<>();
        if (repoFull != null) meta.put("repo", repoFull);
        meta.put("pr_number", number);
        meta.put("action", action);

        // Actor performing the action; falls back to the PR author.
        String actor = text(payload.path("sender").path("login"));
        if (actor == null) actor = text(pr.path("user").path("login"));

        out.add(new GithubEventDraft(
                GithubIds.deterministic("pr:" + repoFull + ":" + number + ":" + action + ":" + ts),
                ts,
                APP,
                subject(text(pr.path("title"))),
                repoShort,
                "code_review",
                meta,
                actor));
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String branchFromRef(String ref) {
        if (ref == null) return null;
        String prefix = "refs/heads/";
        return ref.startsWith(prefix) ? ref.substring(prefix.length()) : ref;
    }

    /** First non-empty line of a commit/PR message (we never store the body). */
    private static String subject(String message) {
        if (message == null) return null;
        int nl = message.indexOf('\n');
        String first = (nl >= 0 ? message.substring(0, nl) : message).strip();
        return first.isEmpty() ? null : first;
    }

    private static OffsetDateTime parseTs(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static int size(JsonNode arr) {
        return arr != null && arr.isArray() ? arr.size() : 0;
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String v = n.asText(null);
        return (v == null || v.isEmpty()) ? null : v;
    }
}
