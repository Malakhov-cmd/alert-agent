package com.invest.monitor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Конфигурация агента из application.yml (префикс monitor:).
 */
@ConfigurationProperties(prefix = "monitor")
public record MonitorConfig(

        /** Путь к корню Obsidian vault (или git-клона). */
        @DefaultValue("./vault")
        String vaultPath,

        /** Режим запуска: daily / weekly / monthly / quarterly / scheduled. */
        @DefaultValue("daily")
        String runMode,

        /** Пауза между проверками триггеров в секундах (защита от rate limit). */
        @DefaultValue("60")
        long delayBetweenChecksSec,

        Anthropic anthropic,
        Telegram  telegram,
        Search    search,
        Prompt    prompt,

        /** Часовой пояс для cron-расписания (IANA, например Europe/Moscow). */
        @DefaultValue("Europe/Moscow")
        String timezone

) {
    public record Anthropic(

            /** Ключ Anthropic API. */
            String apiKey,

            /** ID модели Claude. */
            @DefaultValue("claude-sonnet-4-6")
            String model
    ) {}

    public record Telegram(

            /** Bot token вида 123456:ABC-... */
            String botToken,

            /** ID чата / канала для сигналов. */
            String chatId
    ) {}

    public record Search(

            /** Tavily API key — tavily.com */
            String tavilyApiKey,

            /** Максимум результатов на один запрос. */
            @DefaultValue("5")
            int maxResults,

            /** Домены, по которым ограничивается поиск Tavily. */
            @DefaultValue({"cbr.ru", "moex.com", "rusbonds.ru", "finam.ru",
                           "smartlab.ru", "ria.ru", "interfax.ru", "bloomberg.com"})
            List<String> includeDomains
    ) {}

    public record Prompt(

            /** Системный промпт агента — роль, процесс, формат ответа. */
            String system
    ) {}
}
