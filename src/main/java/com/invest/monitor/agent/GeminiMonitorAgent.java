package com.invest.monitor.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invest.monitor.config.MonitorConfig;
import com.invest.monitor.domain.Trigger;
import com.invest.monitor.domain.TriggerFrequency;
import com.invest.monitor.state.TriggerStateService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Агент на базе Google Gemini с встроенным Grounding (Google Search).
 * Tavily не используется — поиск выполняется Gemini нативно.
 * Активен при {@code monitor.agent-provider=gemini}.
 */
@Service
@ConditionalOnProperty(name = "monitor.agent-provider", havingValue = "gemini")
public class GeminiMonitorAgent extends AbstractMonitorAgent {

    private final RestClient restClient;
    private final String     model;
    private final String     apiKey;

    public GeminiMonitorAgent(TriggerStateService stateService,
                               ObjectMapper mapper,
                               MonitorConfig config,
                               @Qualifier("geminiRestClient") RestClient restClient) {
        super(stateService, mapper, config);
        MonitorConfig.Google google = config.google();
        this.model      = google.model();
        this.apiKey     = google.apiKey();
        this.restClient = restClient;
    }

    @Override
    protected String callProvider(String isin, List<Trigger> triggers, TriggerFrequency frequency) {
        String userMessage = buildUserMessage(isin, triggers, frequency);
        log.debug("Вызов Gemini для ISIN {}", isin);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("system_instruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        body.put("tools", List.of(Map.of("google_search", Map.of())));
        body.put("contents", List.of(
                Map.of("role", "user", "parts", List.of(Map.of("text", userMessage)))));
        body.put("generationConfig", Map.of("maxOutputTokens", 8192, "temperature", 0.1));

        try {
            GeminiResponse response = restClient.post()
                    .uri("/v1beta/models/{model}:generateContent?key={key}", model, apiKey)
                    .body(body)
                    .retrieve()
                    .body(GeminiResponse.class);
            return extractText(response);
        } catch (RestClientException e) {
            throw new RuntimeException("Ошибка Gemini API: " + e.getMessage(), e);
        }
    }

    private String extractText(GeminiResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            return "";
        }
        GeminiResponse.Content content = response.candidates().get(0).content();
        if (content == null || content.parts() == null) return "";
        return content.parts().stream()
                .map(GeminiResponse.Part::text)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
    }

    // ── Gemini API DTOs ──────────────────────────────────────────────

    record GeminiResponse(List<Candidate> candidates) {
        record Candidate(Content content) {}
        record Content(List<Part> parts) {}
        record Part(String text) {}
    }
}
