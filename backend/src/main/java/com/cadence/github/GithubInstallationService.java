package com.cadence.github;

import com.cadence.common.ApiException;
import com.cadence.security.AuthPrincipal;
import com.cadence.tenancy.Tenancy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin-side installation management (P2-D.4 linking, P2-D.5 mode toggle). All
 * operations run under the caller's bound org context, so RLS confines them to
 * that org. Only org admins/owners may call (enforced in the controller).
 */
@Service
public class GithubInstallationService {

    private final GithubInstallationRepository installations;
    private final GithubProperties props;
    private final Tenancy tenancy;

    public GithubInstallationService(GithubInstallationRepository installations,
                                     GithubProperties props,
                                     Tenancy tenancy) {
        this.installations = installations;
        this.props = props;
        this.tenancy = tenancy;
    }

    @Transactional
    public GithubInstallation link(AuthPrincipal principal, GithubDtos.LinkRequest req) {
        tenancy.bind(principal);
        GithubMode mode = req.mode() == null
                ? GithubMode.fromWire(props.getDefaultMode())
                : requireMode(req.mode());
        if (installations.findByInstallationId(req.installationId()).isPresent()) {
            throw ApiException.conflict("That GitHub installation is already linked.");
        }
        return installations.link(principal.orgId(), req.installationId(), req.accountLogin(), mode);
    }

    @Transactional
    public GithubInstallation setMode(AuthPrincipal principal, long installationId, String modeWire) {
        tenancy.bind(principal);
        GithubMode mode = requireMode(modeWire);
        GithubInstallation existing = installations.findByInstallationId(installationId)
                .filter(i -> i.orgId().equals(principal.orgId()))
                .orElseThrow(() -> ApiException.notFound("No such installation for this org."));
        installations.setMode(principal.orgId(), installationId, mode);
        return new GithubInstallation(existing.id(), existing.orgId(), existing.installationId(),
                existing.accountLogin(), mode, existing.suspended());
    }

    @Transactional(readOnly = true)
    public List<GithubInstallation> list(AuthPrincipal principal) {
        tenancy.bind(principal);
        return installations.listForOrg(principal.orgId());
    }

    private static GithubMode requireMode(String wire) {
        GithubMode m = GithubMode.parseStrict(wire);
        if (m == null) {
            throw ApiException.badRequest(
                    "mode must be 'commit_messages_only' or 'full_diff'.");
        }
        return m;
    }
}
