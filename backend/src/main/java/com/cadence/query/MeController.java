package com.cadence.query;

import com.cadence.security.AuthPrincipal;
import com.cadence.security.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/** P2-A.5 — personal read API: GET /me/timeline, GET /me/summary (§6). */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final MeQueryService service;

    public MeController(MeQueryService service) {
        this.service = service;
    }

    @GetMapping("/timeline")
    public Summaries.TimelineResponse timeline(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "100") int limit) {
        AuthPrincipal p = CurrentUser.require();
        return service.timeline(p, from, to, cursor, limit);
    }

    @GetMapping("/summary")
    public Summaries.Summary summary(@RequestParam(required = false) String range) {
        AuthPrincipal p = CurrentUser.require();
        RangeParser.Window w = RangeParser.parse(range);
        return service.summary(p, w.from(), w.to());
    }
}
