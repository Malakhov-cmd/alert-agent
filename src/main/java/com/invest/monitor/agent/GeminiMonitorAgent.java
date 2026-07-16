package com.invest.monitor.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invest.monitor.config.MonitorConfig;
import com.invest.monitor.domain.MonitorResult;
import com.invest.monitor.domain.Trigger;
import com.invest.monitor.domain.TriggerFrequency;
import com.invest.monitor.state.TriggerStateService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Агент на базе Google Gemini с встроенным Grounding (Google Search).
 * Tavily не используется — поиск выполняется Gemini нативно.
 * Активен при {@code monitor.agent-provider=gemini}.
 *
 * <p>При наличии нескольких API-ключей (GOOGLE_API_KEY_2..5) переходит в режим
 * «один вызов на триггер»: каждый триггер получает свой ключ и полный бюджет поиска.
 * При одном ключе — классический режим «один вызов на ISIN».
 */
@Service
@ConditionalOnProperty(name = "monitor.agent-provider", havingValue = "gemini")
public class GeminiMonitorAgent extends AbstractMonitorAgent {

    private final RestClient    restClient;
    private final String        model;
    private final String        perTriggerPrompt;
    private final long          callDelayMs;
    private final List<String>  keyPool;
    private final AtomicInteger keyIndex = new AtomicInteger(0);

    public GeminiMonitorAgent(TriggerStateService stateService,
                               ObjectMapper mapper,
                               MonitorConfig config,
                               @Qualifier("geminiRestClient") RestClient restClient) {
        super(stateService, mapper, config);
        MonitorConfig.Google google = config.google();
        this.model            = google.model();
        this.restClient       = restClient;
        this.callDelayMs      = config.delayBetweenCallsSec() * 1000L;
        this.keyPool          = buildKeyPool(google);
        String configured     = config.prompt().systemPerTrigger();
        this.perTriggerPrompt = (configured != null && !configured.isBlank()) ? configured : systemPrompt;
        log.info("Gemini key pool: {} ключей, режим: {}, per-trigger промпт: {}",
                keyPool.size(),
                keyPool.size() > 1 ? "один вызов на триггер" : "один вызов на ISIN",
                (configured != null && !configured.isBlank()) ? "отдельный" : "основной");
    }

    // ── Режим «один вызов на триггер» при нескольких ключах ─────────

    @Override
    public List<MonitorResult> checkAll(List<Trigger> triggers) {
        if (keyPool.size() == 1) {
            return super.checkAll(triggers);
        }
        return checkAllPerTrigger(triggers);
    }

    private List<MonitorResult> checkAllPerTrigger(List<Trigger> triggers) {
        if (triggers.isEmpty()) return List.of();

        TriggerFrequency frequency = triggers.get(0).frequency();

        Map<String, List<Trigger>> byIsin = triggers.stream()
                .collect(Collectors.groupingBy(Trigger::isin, LinkedHashMap::new, Collectors.toList()));

        log.info("Уникальных ISIN: {}, всего триггеров: {} ({} ключей — один вызов на триггер)",
                byIsin.size(), triggers.size(), keyPool.size());

        List<MonitorResult> allResults = new ArrayList<>();

        for (Map.Entry<String, List<Trigger>> entry : byIsin.entrySet()) {
            String         isin         = entry.getKey();
            List<Trigger>  isinTriggers = entry.getValue();
            log.info("Проверяем ISIN {} ({} триггеров)", isin, isinTriggers.size());

            Iterator<Trigger> it = isinTriggers.iterator();
            while (it.hasNext()) {
                Trigger trigger = it.next();
                List<MonitorResult> results = checkSingleTrigger(isin, trigger, frequency);
                results.stream()
                       .filter(r -> !r.error())
                       .forEach(r -> stateService.recordCheck(r, frequency));
                allResults.addAll(results);

                if (it.hasNext()) sleepBetweenCalls();
            }
        }

        return allResults;
    }

    private List<MonitorResult> checkSingleTrigger(String isin, Trigger trigger,
                                                    TriggerFrequency frequency) {
        if (stub) {
            log.warn("[STUB] Агент не вызывается — возвращаем фиктивный OK для ISIN {}", isin);
            return List.of(MonitorResult.ok(trigger, "[STUB] Проверка пропущена в dev-режиме", "stub"));
        }
        try {
            String raw = callGemini(isin, List.of(trigger), frequency, perTriggerPrompt);
            return parseArrayResponse(List.of(trigger), wrapSingleObject(raw));
        } catch (Exception e) {
            log.error("Ошибка при проверке триггера ISIN {}: {}", isin, e.getMessage(), e);
            return List.of(MonitorResult.error(trigger, e.getMessage()));
        }
    }

    /** Если ответ — одиночный JSON-объект, оборачивает в массив для parseArrayResponse. */
    static String wrapSingleObject(String raw) {
        String trimmed = raw.trim()
                .replaceAll("^```json\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();
        return trimmed.startsWith("{") ? "[" + trimmed + "]" : raw;
    }

    private void sleepBetweenCalls() {
        try {
            Thread.sleep(callDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Вызов Gemini API ─────────────────────────────────────────────

    /** Батчевый режим — использует основной системный промпт. */
    @Override
    protected String callProvider(String isin, List<Trigger> triggers, TriggerFrequency frequency) {
        return callGemini(isin, triggers, frequency, systemPrompt);
    }

    private String callGemini(String isin, List<Trigger> triggers, TriggerFrequency frequency, String prompt) {
        String apiKey      = nextKey();
        String userMessage = buildUserMessage(isin, triggers, frequency);
        log.debug("Вызов Gemini для ISIN {} (ключ …{})", isin, apiKey.substring(Math.max(0, apiKey.length() - 6)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("system_instruction", Map.of("parts", List.of(Map.of("text", prompt))));
        body.put("tools", List.of(Map.of("google_search", Map.of())));
        body.put("contents", List.of(
                Map.of("role", "user", "parts", List.of(Map.of("text", userMessage)))));
        body.put("generationConfig", Map.of("maxOutputTokens", 8192, "temperature", 0.1));

        int[] retryDelaysSec = {30, 60, 120};
        RestClientException lastException = null;

        for (int attempt = 0; attempt <= retryDelaysSec.length; attempt++) {
            try {
                GeminiResponse response = restClient.post()
                        .uri("/v1beta/models/{model}:generateContent?key={key}", model, apiKey)
                        .body(body)
                        .retrieve()
                        .body(GeminiResponse.class);
                return extractText(response);
            } catch (HttpServerErrorException e) {
                lastException = e;
                if ((e.getStatusCode().value() == 503 || e.getStatusCode().value() == 429)
                        && attempt < retryDelaysSec.length) {
                    int delaySec = retryDelaysSec[attempt];
                    if (e.getStatusCode().value() == 429 && keyPool.size() > 1) {
                        apiKey = nextKey();
                        log.warn("Gemini 429 для ISIN {} (попытка {}/{}), ротация ключа → …{}, повтор через {} сек.",
                                isin, attempt + 1, retryDelaysSec.length,
                                apiKey.substring(Math.max(0, apiKey.length() - 6)), delaySec);
                    } else {
                        log.warn("Gemini {} для ISIN {} (попытка {}/{}), повтор через {} сек.",
                                e.getStatusCode().value(), isin, attempt + 1, retryDelaysSec.length, delaySec);
                    }
                    try {
                        Thread.sleep(delaySec * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Прервано ожидание retry Gemini", ie);
                    }
                } else {
                    throw new RuntimeException("Ошибка Gemini API: " + e.getMessage(), e);
                }
            } catch (RestClientException e) {
                throw new RuntimeException("Ошибка Gemini API: " + e.getMessage(), e);
            }
        }

        throw new RuntimeException("Ошибка Gemini API после " + retryDelaysSec.length
                + " попыток: " + lastException.getMessage(), lastException);
    }

    // ── Утилиты ──────────────────────────────────────────────────────

    private String nextKey() {
        return keyPool.get(Math.abs(keyIndex.getAndIncrement() % keyPool.size()));
    }

    private static List<String> buildKeyPool(MonitorConfig.Google google) {
        List<String> pool = Stream.concat(
                Stream.of(google.apiKey()),
                google.apiKeys() != null ? google.apiKeys().stream() : Stream.empty()
        )
        .filter(k -> k != null && !k.isBlank())
        .distinct()
        .toList();

        if (pool.isEmpty()) {
            throw new IllegalStateException("Не настроен ни один Gemini API key (GOOGLE_API_KEY)");
        }
        return pool;
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
