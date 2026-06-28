package com.cadence.github;

import com.cadence.tenancy.Tenancy;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns verified GitHub webhooks into Event-Contract events (P2-D.3/.4/.5).
 *
 * <p>Flow (all in one transaction so {@code set_config} tenancy GUCs apply):
 * <ol>
 *   <li>Resolve {@code installation_id} → org mapping (cross-org, pre-bind).</li>
 *   <li>Bind the org's RLS context.</li>
 *   <li>Map the payload to drafts (honoring the org's privacy {@link GithubMode}).</li>
 *   <li>full_diff only: enrich with numeric diff stats (never code).</li>
 *   <li>Resolve each draft's author github login → member; insert idempotently;
 *       skip drafts with no mappable member.</li>
 * </ol>
 *
 * <p>Dependencies are interfaces so this is fully unit-testable without a DB.
 */
@Service
public class GithubWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GithubWebhookService.class);

    /** current_member GUC value for the webhook context (RLS gates on org only). */
    private static final UUID WEBHOOK_PRINCIPAL = new UUID(0L, 0L);
    private static final String WEBHOOK_ROLE = "github_webhook";

    private final GithubInstallationRepository installations;
    private final GithubMemberResolver members;
    private final GithubEventMapper mapper;
    private final GithubEventStore store;
    private final GithubStatsEnricher statsEnricher;
    private final Tenancy tenancy;

    public GithubWebhookService(GithubInstallationRepository installations,
                                GithubMemberResolver members,
                                GithubEventMapper mapper,
                                GithubEventStore store,
                                GithubStatsEnricher statsEnricher,
                                Tenancy tenancy) {
        this.installations = installations;
        this.members = members;
        this.mapper = mapper;
        this.store = store;
        this.statsEnricher = statsEnricher;
        this.tenancy = tenancy;
    }

    @Transactional
    public WebhookResult handle(String eventType, JsonNode payload) {
        if (eventType == null) return WebhookResult.ignored();

        switch (eventType) {
            case "push", "pull_request" -> {
                return handleActivity(eventType, payload);
            }
            case "installation", "installation_repositories" -> {
                return handleInstallationLifecycle(payload);
            }
            default -> {
                // ping and everything else: acknowledged, nothing to store.
                return WebhookResult.ignored();
            }
        }
    }

    private WebhookResult handleActivity(String eventType, JsonNode payload) {
        long installationId = payload.path("installation").path("id").asLong(0L);
        Optional<GithubInstallation> found = installationId > 0
                ? installations.findByInstallationId(installationId)
                : Optional.empty();
        if (found.isEmpty()) {
            log.warn("github {} webhook for unlinked installation_id={}", eventType, installationId);
            return WebhookResult.unlinked();
        }
        GithubInstallation inst = found.get();
        if (inst.suspended()) {
            return WebhookResult.suspended();
        }

        tenancy.bind(inst.orgId(), WEBHOOK_PRINCIPAL, WEBHOOK_ROLE);

        List<GithubEventDraft> drafts = "push".equals(eventType)
                ? mapper.mapPush(payload, inst.mode())
                : mapper.mapPullRequest(payload, inst.mode());

        if (inst.mode() == GithubMode.FULL_DIFF && !drafts.isEmpty()) {
            statsEnricher.enrich(inst, drafts);
        }

        int stored = 0, skipped = 0;
        for (GithubEventDraft d : drafts) {
            Optional<UUID> memberId = members.resolveMemberId(inst.orgId(), d.authorGithubLogin());
            if (memberId.isEmpty()) {
                skipped++;
                continue;       // author not on this org — cannot attribute
            }
            stored += store.insert(inst.orgId(), memberId.get(), d);
        }
        if (skipped > 0) {
            log.info("github {} org={} stored={} skipped(unmapped author)={}",
                    eventType, inst.orgId(), stored, skipped);
        }
        return new WebhookResult("processed", stored, skipped);
    }

    private WebhookResult handleInstallationLifecycle(JsonNode payload) {
        long installationId = payload.path("installation").path("id").asLong(0L);
        String action = payload.path("action").asText("");
        if (installationId <= 0) return WebhookResult.ignored();

        switch (action) {
            case "suspend", "deleted" -> installations.setSuspended(installationId, true);
            case "unsuspend" -> installations.setSuspended(installationId, false);
            default -> { /* created/added/removed: org linking is the admin endpoint's job */ }
        }
        return WebhookResult.ignored();
    }
}
