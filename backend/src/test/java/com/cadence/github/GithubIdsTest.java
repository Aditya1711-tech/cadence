package com.cadence.github;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GithubIdsTest {

    @Test
    void deterministicForSameName() {
        UUID a = GithubIds.deterministic("push:acme/api:abc123");
        UUID b = GithubIds.deterministic("push:acme/api:abc123");
        assertEquals(a, b, "redelivery of the same commit must dedupe");
    }

    @Test
    void differsForDifferentName() {
        assertNotEquals(
                GithubIds.deterministic("push:acme/api:abc123"),
                GithubIds.deterministic("push:acme/api:def456"));
    }

    @Test
    void isVersion5Variant() {
        UUID u = GithubIds.deterministic("anything");
        assertEquals(5, u.version(), "name-based SHA-1");
        assertEquals(2, u.variant(), "IETF variant");
    }
}
