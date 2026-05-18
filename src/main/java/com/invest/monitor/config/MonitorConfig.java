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
        String timezone,

        /** Провайдер агента: anthropic | gemini. */
        @DefaultValue("anthropic")
        String agentProvider,

        Google google

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
            String chatId,

            /** Базовый URL Telegram Bot API. */
            @DefaultValue("https://api.telegram.org/bot")
            String apiBaseUrl
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
            List<String> includeDomains,

            /** Базовый URL Tavily Search API. */
            @DefaultValue("https://api.tavily.com")
            String tavilyUrl,

            /** Заглушка поиска: возвращает фиктивный ответ без HTTP-запроса к Tavily. */
            @DefaultValue("false")
            boolean stub,

            /** Сколько последних проверок передавать агенту как previous_results. */
            @DefaultValue("3")
            int historySize
    ) {}

    public record Prompt(

            /** Системный промпт агента — роль, процесс, формат ответа. */
            String system
    ) {}

    public record Google(

            /** Google AI Studio API key. */
            @DefaultValue("")
            String apiKey,

            /** Модель Gemini. */
            @DefaultValue("gemini-2.5-flash")
            String model,

            /** Базовый URL Google Generative Language API. */
            @DefaultValue("https://generativelanguage.googleapis.com")
            String baseUrl,

            Http http
    ) {
        public record Http(

                /** Таймаут установки TCP-соединения, мс. */
                @DefaultValue("10000")
                int connectTimeoutMs,

                /** Таймаут чтения ответа (socket), с. Gemini может думать долго. */
                @DefaultValue("120")
                int responseTimeoutSec,

                /** TTL соединения в пуле, с. Должен быть меньше keep-alive сервера. */
                @DefaultValue("55")
                int connectionTtlSec,

                /** Максимальное время простоя соединения до вытеснения из пула, с. */
                @DefaultValue("30")
                int maxIdleTimeSec,

                /** Максимум соединений в пуле (Gemini — sequential, достаточно 2). */
                @DefaultValue("2")
                int maxConnections
        ) {}
    }
}
