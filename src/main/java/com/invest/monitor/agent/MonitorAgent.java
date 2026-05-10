package com.invest.monitor.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invest.monitor.config.MonitorConfig;
import com.invest.monitor.domain.MonitorResult;
import com.invest.monitor.domain.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Агент мониторинга: для каждого триггера запускает tool-use loop
 * через Claude, используя web_search для получения актуальных данных,
 * и возвращает {@link MonitorResult}.
 *
 * <p>Spring AI автоматически обрабатывает цикл tool-use:
 * Claude → вызов инструмента → результат → Claude → финальный ответ.
 */
@Service
public class MonitorAgent {

    private static final Logger log = LoggerFactory.getLogger(MonitorAgent.class);

    // Claude возвращает JSON внутри ответа — ищем по этому паттерну
    private static final Pattern JSON_BLOCK = Pattern.compile(
            "```json\\s*(\\{.*?})\\s*```|^(\\{.*})$",
            Pattern.DOTALL | Pattern.MULTILINE
    );

    private final ChatClient  chatClient;
    private final AgentTools  agentTools;
    private final ObjectMapper mapper;
    private final String       systemPrompt;
    private final long         delaySec;

    public MonitorAgent(ChatClient.Builder builder,
                        AgentTools agentTools,
                        ObjectMapper mapper,
                        MonitorConfig config) {
        this.chatClient   = builder.build();
        this.agentTools   = agentTools;
        this.mapper       = mapper;
        this.systemPrompt = config.prompt().system();
        this.delaySec     = config.delayBetweenChecksSec();
    }

    // ── Публичный API ────────────────────────────────────────────────

    /**
     * Проверяет один триггер. Запускает tool-use loop, возвращает результат.
     * Никогда не бросает исключение — ошибки оборачиваются в MonitorResult.
     */
    public MonitorResult check(Trigger trigger) {
        log.info("Проверяем триггер: {}", trigger.shortLabel());
        try {
            String raw = callClaude(trigger);
            return parseResponse(trigger, raw);
        } catch (Exception e) {
            log.error("Ошибка при проверке триггера {}: {}", trigger.shortLabel(), e.getMessage(), e);
            return MonitorResult.ok(trigger, "Ошибка агента: " + e.getMessage());
        }
    }

    /**
     * Проверяет список триггеров последовательно с паузой между запросами.
     * Пауза защищает от rate limit Anthropic API (30k токенов/мин на старте).
     */
    public List<MonitorResult> checkAll(List<Trigger> triggers) {
        List<MonitorResult> results = new ArrayList<>();
        for (int i = 0; i < triggers.size(); i++) {
            results.add(check(triggers.get(i)));
            if (i < triggers.size() - 1) {
                sleep();
            }
        }
        return results;
    }

    private void sleep() {
        try {
            log.info("Пауза {} сек. перед следующим триггером...", delaySec);
            Thread.sleep(delaySec * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Взаимодействие с Claude ──────────────────────────────────────

    private String callClaude(Trigger trigger) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(buildUserMessage(trigger))
                .tools(agentTools)   // регистрируем @Tool-методы
                .call()
                .content();
    }

    private String buildUserMessage(Trigger trigger) {
        return """
                Проверь триггер выхода из позиции:

                ISIN:      %s
                Бумага:    %s
                Условие:   %s
                Уровень:   %s (%s)
                Дата:      %s

                Используй web_search для получения актуальных рыночных данных.
                Верни результат строго в JSON-формате, описанном в системном промпте.
                """.formatted(
                trigger.isin(),
                trigger.name(),
                trigger.condition(),
                trigger.level().label(),
                trigger.level().emoji(),
                LocalDate.now()
        );
    }

    // ── Парсинг ответа ───────────────────────────────────────────────

    private MonitorResult parseResponse(Trigger trigger, String raw) {
        log.debug("Ответ Claude для {}: {}", trigger.shortLabel(), raw);

        String json = extractJson(raw);
        if (json == null) {
            // Fallback: Claude не вернул JSON — считаем норма, детали = полный текст
            log.warn("Claude не вернул JSON для {}. Ответ: {}", trigger.shortLabel(), raw);
            return MonitorResult.ok(trigger, raw);
        }

        try {
            AgentResponse resp = mapper.readValue(json, AgentResponse.class);
            if (resp.fired()) {
                return MonitorResult.fired(trigger, resp.summary(), resp.details());
            } else {
                return MonitorResult.ok(trigger, resp.details());
            }
        } catch (Exception e) {
            log.warn("Не удалось распарсить JSON ответа Claude: {}", e.getMessage());
            return MonitorResult.ok(trigger, raw);
        }
    }

    private String extractJson(String text) {
        Matcher m = JSON_BLOCK.matcher(text.trim());
        if (m.find()) {
            // группа 1 — из ```json блока, группа 2 — голый JSON
            return m.group(1) != null ? m.group(1) : m.group(2);
        }
        return null;
    }

    // ── DTO ответа агента ────────────────────────────────────────────

    /**
     * Структура JSON, которую должен вернуть Claude.
     *
     * @param fired   true — триггер сработал, нужно слать сигнал
     * @param summary краткое резюме (1–2 предложения) для Telegram
     * @param details полный анализ с источниками
     */
    record AgentResponse(
            boolean fired,
            String  summary,
            String  details,
            @JsonProperty("confidence") String confidence  // low / medium / high — для логов
    ) {}

}
