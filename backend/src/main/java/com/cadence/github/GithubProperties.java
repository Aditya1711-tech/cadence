package com.cadence.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code cadence.github.*} (P2-D). Mirrors the reserved env vars
 * {@code GITHUB_APP_ID}, {@code GITHUB_APP_PRIVATE_KEY} (PEM, base64),
 * {@code GITHUB_WEBHOOK_SECRET}, {@code GITHUB_DEFAULT_MODE}.
 *
 * <p>Only {@code webhookSecret} + {@code defaultMode} are needed for the default
 * {@code commit_messages_only} path (the push webhook carries everything; no API
 * call). {@code appId} + {@code privateKey} are used only by the opt-in
 * {@code full_diff} stats enrichment (P2-D.5) and may be empty otherwise.
 */
@ConfigurationProperties(prefix = "cadence.github")
public class GithubProperties {
    /** GitHub App id (numeric, as text). Used only to mint App JWTs for full_diff. */
    private String appId = "";
    /** GitHub App RSA private key, PEM, base64-encoded. full_diff only. */
    private String privateKey = "";
    /** HMAC secret shared with GitHub to verify X-Hub-Signature-256. */
    private String webhookSecret = "";
    /** Privacy mode for newly-linked installations (P2-D.2). */
    private String defaultMode = "commit_messages_only";

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public String getDefaultMode() { return defaultMode; }
    public void setDefaultMode(String defaultMode) { this.defaultMode = defaultMode; }
}
