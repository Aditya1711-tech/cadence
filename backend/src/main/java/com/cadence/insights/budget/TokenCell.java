package com.cadence.insights.budget;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One (member, day, model) token-cost cell read from {@code events_daily_tokens}.
 * The assembler folds these into per-member and per-org daily burns.
 */
record TokenCell(
        UUID memberId,
        String memberName,
        LocalDate day,
        String model,
        double costUsd
) {
}
