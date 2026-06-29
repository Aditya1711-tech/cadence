package com.cadence.insights.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Resolves who gets an org's budget email: the configured {@code alert_email} if
 * set, else the org's active owners/admins (they own the bill). Degrades to an
 * empty list on any read error — the dispatcher then logs and skips email rather
 * than failing the run.
 */
@Component
class AlertRecipientStore {

    private static final Logger log = LoggerFactory.getLogger(AlertRecipientStore.class);

    private final JdbcTemplate jdbc;

    AlertRecipientStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<String> recipients(UUID orgId, OrgBudgetConfig cfg) {
        if (cfg.alertEmail() != null && !cfg.alertEmail().isBlank()) {
            return List.of(cfg.alertEmail());
        }
        try {
            return jdbc.query("""
                    SELECT email FROM members
                    WHERE org_id = ? AND status = 'active' AND role IN ('owner','admin')
                    ORDER BY role, email
                    """, (rs, i) -> rs.getString("email"), orgId);
        } catch (DataAccessException e) {
            log.warn("could not resolve alert recipients for org {}: {}", orgId, e.toString());
            return List.of();
        }
    }
}
