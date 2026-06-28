package com.cadence.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GithubEventMapperTest {

    private final ObjectMapper om = new ObjectMapper();
    private final GithubEventMapper mapper = new GithubEventMapper();

    private JsonNode json(String s) {
        try { return om.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static final String PUSH = """
        {
          "ref": "refs/heads/main",
          "repository": { "full_name": "acme/cadence-api", "name": "cadence-api" },
          "installation": { "id": 42 },
          "commits": [
            { "id": "abc123", "message": "fix: idempotent ingest\\n\\nlong body here",
              "timestamp": "2026-06-27T09:14:02Z",
              "author": { "username": "octodev", "name": "Octo Dev" },
              "added": ["a.java","b.java"], "removed": [], "modified": ["c.java"] },
            { "id": "def456", "message": "docs: update readme",
              "timestamp": "2026-06-27T09:20:00Z",
              "author": { "username": null },
              "added": [], "removed": [], "modified": ["README.md"] }
          ]
        }
        """;

    @Test
    void mapsOneEventPerCommit_commitMessagesOnly() {
        List<GithubEventDraft> drafts = mapper.mapPush(json(PUSH), GithubMode.COMMIT_MESSAGES_ONLY);
        assertEquals(2, drafts.size());

        GithubEventDraft first = drafts.get(0);
        assertEquals("GitHub", first.app());
        assertEquals("cadence-api", first.project());
        assertEquals("fix: idempotent ingest", first.title(), "subject only, no body");
        assertNull(first.category(), "commits are not categorized");
        assertEquals("octodev", first.authorGithubLogin());
        assertEquals("abc123", first.meta().get("commit_sha"));
        assertEquals("acme/cadence-api", first.meta().get("repo"));
        assertEquals("main", first.meta().get("branch"));
        assertFalse(first.meta().containsKey("changed_files"), "no stats in default mode");
    }

    @Test
    void fullDiffAddsChangedFilesCountButNoPaths() {
        List<GithubEventDraft> drafts = mapper.mapPush(json(PUSH), GithubMode.FULL_DIFF);
        GithubEventDraft first = drafts.get(0);
        assertEquals(3, first.meta().get("changed_files"), "2 added + 0 removed + 1 modified");
        // never store the paths themselves
        assertFalse(first.meta().toString().contains("a.java"));
    }

    @Test
    void deterministicEventIdAcrossModes() {
        var a = mapper.mapPush(json(PUSH), GithubMode.COMMIT_MESSAGES_ONLY).get(0).eventId();
        var b = mapper.mapPush(json(PUSH), GithubMode.FULL_DIFF).get(0).eventId();
        assertEquals(a, b, "event_id derives from repo+sha, independent of mode");
    }

    @Test
    void commitsAreZeroDurationPointInTime() {
        GithubEventDraft d = mapper.mapPush(json(PUSH), GithubMode.COMMIT_MESSAGES_ONLY).get(0);
        assertEquals("2026-06-27T09:14:02Z", d.tsStart().toInstant().toString());
        // tsEnd/duration are fixed at the store layer (ts_end=ts_start, duration_ms=0).
    }

    private static final String PR = """
        {
          "action": "opened",
          "number": 7,
          "pull_request": {
            "title": "Add GitHub integration",
            "updated_at": "2026-06-27T10:00:00Z",
            "created_at": "2026-06-27T09:55:00Z",
            "user": { "login": "prauthor" }
          },
          "repository": { "full_name": "acme/cadence-api", "name": "cadence-api" },
          "installation": { "id": 42 },
          "sender": { "login": "reviewer1" }
        }
        """;

    @Test
    void mapsPullRequestAsCodeReview() {
        List<GithubEventDraft> drafts = mapper.mapPullRequest(json(PR), GithubMode.COMMIT_MESSAGES_ONLY);
        assertEquals(1, drafts.size());
        GithubEventDraft d = drafts.get(0);
        assertEquals("code_review", d.category());
        assertEquals("Add GitHub integration", d.title());
        assertEquals("reviewer1", d.authorGithubLogin(), "attributed to the actor (sender)");
        assertEquals(7, d.meta().get("pr_number"));
        assertEquals("opened", d.meta().get("action"));
        assertEquals("acme/cadence-api", d.meta().get("repo"));
    }

    @Test
    void ignoresNoisyPullRequestActions() {
        String synchronize = PR.replace("\"opened\"", "\"synchronize\"");
        assertTrue(mapper.mapPullRequest(json(synchronize), GithubMode.COMMIT_MESSAGES_ONLY).isEmpty());
    }

    @Test
    void emptyPayloadsYieldNoDrafts() {
        assertTrue(mapper.mapPush(null, GithubMode.COMMIT_MESSAGES_ONLY).isEmpty());
        assertTrue(mapper.mapPush(json("{}"), GithubMode.COMMIT_MESSAGES_ONLY).isEmpty());
        assertTrue(mapper.mapPullRequest(json("{}"), GithubMode.COMMIT_MESSAGES_ONLY).isEmpty());
    }
}
