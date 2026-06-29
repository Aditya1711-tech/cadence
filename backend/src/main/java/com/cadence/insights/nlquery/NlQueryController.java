package com.cadence.insights.nlquery;

import com.cadence.security.AuthPrincipal;
import com.cadence.security.CurrentUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P3-C.3 — POST /api/v1/query/nl (§6). Admin-only natural-language query. Present
 * only when {@code cadence.nlquery.enabled=true}; otherwise the route is absent
 * and Spring Security's default applies (no owner-connection fallback exists).
 */
@RestController
@RequestMapping("/api/v1/query")
@ConditionalOnProperty(prefix = "cadence.nlquery", name = "enabled", havingValue = "true")
class NlQueryController {

    private final NlQueryService service;

    NlQueryController(NlQueryService service) {
        this.service = service;
    }

    @PostMapping("/nl")
    public NlQueryDtos.NlQueryResponse query(@RequestBody NlQueryDtos.NlQueryRequest body) {
        AuthPrincipal p = CurrentUser.require();
        String question = body == null ? null : body.question();
        return service.answer(p, question);
    }
}
