package com.cadence.insights.budget;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Posts an alert to a Slack incoming webhook ({@code {"text": …}}). Uses the JDK
 * HttpClient (no new dependency). Never throws — returns false on any failure so
 * the dispatcher can fall back to email. The webhook URL is per-org config
 * (env {@code SLACK_WEBHOOK_URL} is only a local-test default).
 */
@Component
class SlackNotifier {

    private static final Logger log = LoggerFactory.getLogger(SlackNotifier.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    boolean send(String webhookUrl, String text) {
        try {
            String body = mapper.writeValueAsString(Map.of("text", text));
            HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                return true;
            }
            log.warn("Slack webhook returned {}: {}", resp.statusCode(), resp.body());
            return false;
        } catch (Exception e) {
            log.warn("Slack webhook post failed: {}", e.toString());
            return false;
        }
    }
}
