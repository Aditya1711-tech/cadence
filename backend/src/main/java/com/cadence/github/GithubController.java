package com.cadence.github;

import com.cadence.common.ApiException;
import com.cadence.security.AuthPrincipal;
import com.cadence.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * P2-D GitHub integration HTTP surface, under {@code /api/v1/github}.
 *
 * <ul>
 *   <li>{@code POST /webhook} — public (HMAC-authenticated; see
 *       {@link GithubSecurityConfig}). Push/PR/installation events → events.</li>
 *   <li>{@code POST /installations}, {@code PUT /installations/{id}/mode},
 *       {@code GET /installations} — authenticated admin (P2-E uses these to link
 *       an installation and flip the privacy mode).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/github")
public class GithubController {

    private final GithubSignatureVerifier verifier;
    private final GithubWebhookService webhook;
    private final GithubInstallationService installations;
    private final ObjectMapper mapper;

    public GithubController(GithubSignatureVerifier verifier,
                            GithubWebhookService webhook,
                            GithubInstallationService installations,
                            ObjectMapper mapper) {
        this.verifier = verifier;
        this.webhook = webhook;
        this.installations = installations;
        this.mapper = mapper;
    }

    @PostMapping("/webhook")
    public Map<String, Object> webhook(
            @RequestBody(required = false) byte[] body,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Event", required = false) String eventType) {
        verifier.verify(body, signature);
        JsonNode payload = parse(body);
        WebhookResult r = webhook.handle(eventType, payload);
        return Map.of(
                "event", eventType == null ? "" : eventType,
                "status", r.status(),
                "stored", r.stored(),
                "skipped", r.skipped());
    }

    @PostMapping("/installations")
    @ResponseStatus(HttpStatus.CREATED)
    public GithubDtos.InstallationResponse link(@RequestBody @Valid GithubDtos.LinkRequest req) {
        AuthPrincipal p = requireAdmin();
        return GithubDtos.InstallationResponse.of(installations.link(p, req));
    }

    @PutMapping("/installations/{installationId}/mode")
    public GithubDtos.InstallationResponse setMode(@PathVariable long installationId,
                                                   @RequestBody @Valid GithubDtos.ModeRequest req) {
        AuthPrincipal p = requireAdmin();
        return GithubDtos.InstallationResponse.of(installations.setMode(p, installationId, req.mode()));
    }

    @GetMapping("/installations")
    public List<GithubDtos.InstallationResponse> list() {
        AuthPrincipal p = requireAdmin();
        return installations.list(p).stream().map(GithubDtos.InstallationResponse::of).toList();
    }

    private static AuthPrincipal requireAdmin() {
        AuthPrincipal p = CurrentUser.require();
        if (!p.isAdmin()) {
            throw ApiException.forbidden("Org admin role required.");
        }
        return p;
    }

    private JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw ApiException.badRequest("Empty webhook body.");
        }
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw ApiException.badRequest("Webhook body is not valid JSON.");
        }
    }
}
