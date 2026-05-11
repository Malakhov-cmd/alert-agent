package com.invest.monitor.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.invest.monitor.config.MonitorConfig;
import com.invest.monitor.domain.MonitorResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Отправляет сигналы в Telegram через Bot API.
 * Сообщения шлём только для сработавших триггеров (🚨 и ⚠️).
 */
@Component
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);

    private final String chatId;
    private final RestClient restClient;

    public TelegramNotifier(MonitorConfig config, RestClient.Builder builder) {
        MonitorConfig.Telegram tg = config.telegram();
        this.chatId = tg.chatId();
        this.restClient = builder
                .baseUrl(tg.apiBaseUrl() + tg.botToken())
                .build();
    }

    // ── Публичный API ────────────────────────────────────────────────

    /**
     * Отправляет сигналы для всех сработавших результатов из списка.
     * Результаты без fired == true молча игнорируются.
     */
    public void notifyFired(List<MonitorResult> results) {
        results.stream()
               .filter(MonitorResult::fired)
               .forEach(this::send);
    }

    /** Отправляет одно сообщение по готовому тексту (для тестов и ручного вызова). */
    public void sendRaw(String text) {
        doSend(text);
    }

    // ── Внутренняя логика ────────────────────────────────────────────

    private void send(MonitorResult result) {
        String text = result.toTelegramMessage();
        log.info("Отправляем сигнал в Telegram: {}", result.trigger().shortLabel());
        doSend(text);
    }

    private void doSend(String text) {
        // Telegram ограничивает сообщение 4096 символами
        String safe = text.length() > 4096 ? text.substring(0, 4093) + "…" : text;

        // Payload sendMessage: chat_id, text, parse_mode=HTML
        Map<String, Object> body = Map.of(
                "chat_id",    chatId,
                "text",       safe,
                "parse_mode", "HTML"
        );

        try {
            TelegramResponse response = restClient
                    .post()
                    .uri("/sendMessage")
                    .body(body)
                    .retrieve()
                    .body(TelegramResponse.class);

            if (response != null && !response.ok()) {
                log.error("Telegram API вернул ok=false: {}", response.description());
            } else {
                int msgId = (response != null && response.result() != null)
                        ? response.result().messageId() : -1;
                log.debug("Сообщение доставлено, message_id={}", msgId);
            }
        } catch (RestClientException e) {
            // Логируем и не прерываем цикл проверок — сигнал важнее сбоя доставки
            log.error("Ошибка отправки в Telegram: {}", e.getMessage(), e);
        }
    }

    // ── DTO ответа Telegram API ──────────────────────────────────────

    // Telegram возвращает: {"ok": true, "result": {"message_id": 123, ...}}
    record TelegramResponse(boolean ok, Result result, String description) {
        record Result(@JsonProperty("message_id") int messageId) {}
    }
}
