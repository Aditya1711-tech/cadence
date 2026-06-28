package com.cadence.github;

import jakarta.validation.constraints.NotNull;

/** Request/response shapes for the admin GitHub-installation endpoints (P2-E calls these). */
public final class GithubDtos {
    private GithubDtos() {}

    /** Link a freshly-installed GitHub App installation to the caller's org. */
    public record LinkRequest(
            @NotNull Long installationId,
            String accountLogin,
            String mode) {            // null → org/system default
    }

    /** Change the privacy mode for an existing installation (P2-D.5 toggle). */
    public record ModeRequest(@NotNull String mode) {}

    /** What the admin sees back. */
    public record InstallationResponse(
            String id,
            long installationId,
            String accountLogin,
            String mode,
            boolean suspended) {

        static InstallationResponse of(GithubInstallation i) {
            return new InstallationResponse(
                    i.id() == null ? null : i.id().toString(),
                    i.installationId(),
                    i.accountLogin(),
                    i.mode().wire(),
                    i.suspended());
        }
    }
}
