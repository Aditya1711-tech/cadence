package com.cadence.insights.nlquery;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code cadence_readonly} role contract the NL-query path relies on
 * (P3-C.1 §9 item 2). The role is Docker-deferred (materializes on a fresh DB
 * volume at deploy), so we cannot connect as it here — but we CAN assert its
 * definition grants exactly SELECT and nothing that would let the text-to-SQL
 * path write or escape org isolation. If P3-A's role file ever drifts toward
 * granting writes/escalation, this fails.
 */
class ReadonlyRoleDefinitionTest {

    /** Code lines only (drop SQL `--` comments), lower-cased. */
    private List<String> roleSqlLines() throws IOException {
        Path file = null;
        for (Path base : new Path[]{Path.of(""), Path.of(".."), Path.of("../..")}) {
            Path p = base.resolve("deploy/initdb/01-readonly-role.sql");
            if (Files.exists(p)) {
                file = p;
                break;
            }
        }
        if (file == null) {
            throw new IllegalStateException("deploy/initdb/01-readonly-role.sql not found");
        }
        return Files.readAllLines(file).stream()
                .map(l -> l.toLowerCase(Locale.ROOT))
                .map(l -> {
                    int c = l.indexOf("--");
                    return c >= 0 ? l.substring(0, c) : l;
                })
                .toList();
    }

    @Test
    void grantsSelectOnly() throws IOException {
        String code = String.join("\n", roleSqlLines());
        assertTrue(code.contains("create role cadence_readonly"), "creates the role");
        assertTrue(code.contains("grant select on all tables in schema public to cadence_readonly"),
                "grants SELECT on existing tables");
        assertTrue(code.contains("alter default privileges") && code.contains("grant select on tables to cadence_readonly"),
                "grants SELECT on future (Flyway) tables");
    }

    @Test
    void neverGrantsWritesOrEscapes() throws IOException {
        List<String> lines = roleSqlLines();
        // Inspect only the actual GRANT-to-the-role statements (ignoring comments,
        // which legitimately mention the words INSERT/UPDATE/DELETE as "not granted").
        for (String line : lines) {
            if (line.contains("grant") && line.contains("cadence_readonly")) {
                assertFalse(line.contains("insert"), "GRANT line must not include INSERT: " + line);
                assertFalse(line.contains("update"), "GRANT line must not include UPDATE: " + line);
                assertFalse(line.contains("delete"), "GRANT line must not include DELETE: " + line);
            }
        }
        // No RLS escape / privilege escalation anywhere in the role definition.
        String code = String.join("\n", lines);
        assertFalse(code.contains("bypassrls"), "must not grant BYPASSRLS");
        assertFalse(code.contains("superuser"), "must not be SUPERUSER");
        assertFalse(code.contains("createdb"), "must not be CREATEDB");
        assertFalse(code.contains("createrole"), "must not be CREATEROLE");
    }
}
