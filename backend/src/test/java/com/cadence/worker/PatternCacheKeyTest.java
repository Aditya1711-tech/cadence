package com.cadence.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PatternCacheKeyTest {

    private static EventSignals sig(String source, String app, String title, String url) {
        return new EventSignals(source, app, title, url, "proj", false, 1000);
    }

    @Test
    void lowercasesAndJoinsSignals() {
        String k = PatternCache.key(sig("VSCode", "Visual Studio Code", "main.go", null));
        assertEquals("vscode|visual studio code|main.go|", k);
    }

    @Test
    void dropsDaemonProjectSuffixSoSameFileCollides() {
        String a = PatternCache.key(sig("vscode", "Code", "auth.ts — cadence-api", null));
        String b = PatternCache.key(sig("vscode", "Code", "auth.ts — other-repo", null));
        assertEquals(a, b);
        assertTrue(a.contains("|auth.ts|"));
    }

    @Test
    void reducesUrlToHost() {
        String a = PatternCache.key(sig("chrome", "Chrome", "PR 12", "https://github.com/org/repo/pull/12"));
        String b = PatternCache.key(sig("chrome", "Chrome", "PR 99", "https://github.com/org/repo/pull/99"));
        // titles differ but host collapses; note title is not suffix-stripped here
        assertTrue(a.endsWith("|github.com"));
        assertTrue(b.endsWith("|github.com"));
    }

    @Test
    void nullsBecomeEmptySegments() {
        assertEquals("|||", PatternCache.key(sig(null, null, null, null)));
    }
}
