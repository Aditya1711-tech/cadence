package com.cadence.ingest;

import com.cadence.common.ApiException;
import com.cadence.event.EventDto;
import com.cadence.security.AuthPrincipal;
import com.cadence.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * P2-A.4 — {@code POST /api/v1/ingest/events}.
 * Batch upload from the daemon: array, &le; 1000 events, idempotent on
 * {@code event_id} (§6). org_id + member_id are stamped from the JWT.
 * **Ticks P2-A.CONTRACT** together with the query shapes.
 */
@RestController
@RequestMapping("/api/v1/ingest")
public class IngestController {

    static final int MAX_BATCH = 1000;

    private final IngestService service;

    public IngestController(IngestService service) {
        this.service = service;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.OK)
    public IngestResult ingest(@RequestBody @Valid List<@Valid EventDto> events) {
        if (events == null || events.isEmpty()) {
            throw ApiException.badRequest("Event batch must be a non-empty array.");
        }
        if (events.size() > MAX_BATCH) {
            throw ApiException.payloadTooLarge(
                "Batch of " + events.size() + " exceeds the " + MAX_BATCH + "-event limit.");
        }
        AuthPrincipal principal = CurrentUser.require();
        return service.ingest(principal, events);
    }
}
