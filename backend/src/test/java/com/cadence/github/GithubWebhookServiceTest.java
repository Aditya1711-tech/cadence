package com.cadence.github;

import com.cadence.tenancy.Tenancy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GithubWebhookServiceTest {

    private final ObjectMapper om = new ObjectMapper();
    private final GithubInstallationRepository installations = mock(GithubInstallationRepository.class);
    private final GithubMemberResolver members = mock(GithubMemberResolver.class);
    private final GithubEventStore store = mock(GithubEventStore.class);
    private final GithubStatsEnricher enricher = mock(GithubStatsEnricher.class);
    private final Tenancy tenancy = mock(Tenancy.class);
    private final GithubWebhookService service = new GithubWebhookService(
            installations, members, new GithubEventMapper(), store, enricher, tenancy);

    private JsonNode json(String s) {
        try { return om.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static final UUID ORG = UUID.randomUUID();
    private static final long INST = 42L;

    private static final String PUSH = """
        {
          "ref": "refs/heads/main",
          "repository": { "full_name": "acme/api", "name": "api" },
          "installation": { "id": 42 },
          "commits": [
            { "id": "sha1", "message": "feat: a", "timestamp": "2026-06-27T09:14:02Z",
              "author": { "username": "octodev" }, "added": [], "removed": [], "modified": [] }
          ]
        }
        """;

    private GithubInstallation inst(GithubMode mode, boolean suspended) {
        return new GithubInstallation(UUID.randomUUID(), ORG, INST, "acme", mode, suspended);
    }

    @Test
    void pingIsIgnored() {
        assertEquals("ignored", service.handle("ping", json("{}")).status());
        verifyNoInteractions(store);
    }

    @Test
    void storesPushAndBindsOrg() {
        when(installations.findByInstallationId(INST))
                .thenReturn(Optional.of(inst(GithubMode.COMMIT_MESSAGES_ONLY, false)));
        UUID member = UUID.randomUUID();
        when(members.resolveMemberId(ORG, "octodev")).thenReturn(Optional.of(member));
        when(store.insert(eq(ORG), eq(member), any())).thenReturn(1);

        WebhookResult r = service.handle("push", json(PUSH));

        assertEquals("processed", r.status());
        assertEquals(1, r.stored());
        assertEquals(0, r.skipped());
        verify(tenancy).bind(eq(ORG), any(UUID.class), anyString());
        verify(enricher, never()).enrich(any(), anyList());   // not full_diff
    }

    @Test
    void skipsCommitWhenAuthorUnmapped() {
        when(installations.findByInstallationId(INST))
                .thenReturn(Optional.of(inst(GithubMode.COMMIT_MESSAGES_ONLY, false)));
        when(members.resolveMemberId(eq(ORG), any())).thenReturn(Optional.empty());

        WebhookResult r = service.handle("push", json(PUSH));

        assertEquals(0, r.stored());
        assertEquals(1, r.skipped());
        verify(store, never()).insert(any(), any(), any());
    }

    @Test
    void unlinkedInstallationStoresNothing() {
        when(installations.findByInstallationId(INST)).thenReturn(Optional.empty());
        WebhookResult r = service.handle("push", json(PUSH));
        assertEquals("unlinked", r.status());
        verify(tenancy, never()).bind(any(), any(), anyString());
        verifyNoInteractions(store);
    }

    @Test
    void suspendedInstallationStoresNothing() {
        when(installations.findByInstallationId(INST))
                .thenReturn(Optional.of(inst(GithubMode.COMMIT_MESSAGES_ONLY, true)));
        WebhookResult r = service.handle("push", json(PUSH));
        assertEquals("suspended", r.status());
        verifyNoInteractions(store);
    }

    @Test
    void fullDiffInvokesEnricher() {
        when(installations.findByInstallationId(INST))
                .thenReturn(Optional.of(inst(GithubMode.FULL_DIFF, false)));
        when(members.resolveMemberId(eq(ORG), any())).thenReturn(Optional.of(UUID.randomUUID()));
        when(store.insert(any(), any(), any())).thenReturn(1);

        service.handle("push", json(PUSH));

        verify(enricher).enrich(any(GithubInstallation.class), anyList());
    }

    @Test
    void installationDeletedSuspendsMapping() {
        service.handle("installation",
                json("{ \"action\": \"deleted\", \"installation\": { \"id\": 42 } }"));
        verify(installations).setSuspended(INST, true);
    }
}
