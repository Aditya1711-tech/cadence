package com.cadence.github;

/**
 * The hard GitHub privacy toggle (§8, P2-D.2). Code/patch is NEVER stored in
 * either mode; {@link #FULL_DIFF} only adds numeric diff <em>stats</em>.
 */
public enum GithubMode {
    /** Default. Commit message subject + sha + repo + branch. No API call. */
    COMMIT_MESSAGES_ONLY("commit_messages_only"),
    /** Opt-in. Adds numeric diff stats (additions/deletions/changed_files). */
    FULL_DIFF("full_diff");

    private final String wire;

    GithubMode(String wire) { this.wire = wire; }

    /** The on-wire / DB value (matches the github_installations.mode CHECK). */
    public String wire() { return wire; }

    /** Parse a wire value; privacy-safe default to {@code commit_messages_only}. */
    public static GithubMode fromWire(String s) {
        if (s != null && FULL_DIFF.wire.equals(s.trim())) {
            return FULL_DIFF;
        }
        return COMMIT_MESSAGES_ONLY;
    }

    /** Strict parse for admin input: reject anything not a known wire value. */
    public static GithubMode parseStrict(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (COMMIT_MESSAGES_ONLY.wire.equals(v)) return COMMIT_MESSAGES_ONLY;
        if (FULL_DIFF.wire.equals(v)) return FULL_DIFF;
        return null;
    }
}
