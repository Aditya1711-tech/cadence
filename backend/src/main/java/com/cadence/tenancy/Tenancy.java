package com.cadence.tenancy;

import com.cadence.security.AuthPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Binds the per-request org context onto the current transaction's connection so
 * Postgres RLS policies ({@code org_id = current_setting('app.current_org')}) take
 * effect (P2-A.1 §1). Call {@link #bind} as the first statement inside an
 * {@code @Transactional} service method — the GUCs are transaction-local (the
 * {@code true} flag), so they vanish when the transaction ends and never leak
 * across pooled connections.
 */
@Component
public class Tenancy {

    private final JdbcTemplate jdbc;

    public Tenancy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void bind(AuthPrincipal principal) {
        bind(principal.orgId(), principal.memberId(), principal.role());
    }

    public void bind(UUID orgId, UUID memberId, String role) {
        // set_config(key, value, is_local=true) → scoped to the current txn.
        jdbc.queryForObject("SELECT set_config('app.current_org', ?, true)",
                String.class, orgId.toString());
        jdbc.queryForObject("SELECT set_config('app.current_member', ?, true)",
                String.class, memberId.toString());
        jdbc.queryForObject("SELECT set_config('app.current_role', ?, true)",
                String.class, role);
    }
}
