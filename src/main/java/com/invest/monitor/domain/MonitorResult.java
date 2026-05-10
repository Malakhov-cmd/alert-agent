package com.invest.monitor.domain;

import java.time.Instant;

/**
 * Результат проверки одного триггера агентом.
 *
 * @param trigger    Проверенный триггер
 * @param fired      true — триггер сработал, нужно слать сигнал
 * @param summary    Краткое резюме агента (≤ 500 символов)
 * @param details    Полный анализ агента (для логов)
 * @param checkedAt  Момент завершения проверки
 */
public record MonitorResult(
        Trigger trigger,
        boolean fired,
        String  summary,
        String  details,
        Instant checkedAt
) {
    /** Фабричный метод для незасработавшего триггера. */
    public static MonitorResult ok(Trigger trigger, String details) {
        return new MonitorResult(trigger, false, "Норма", details, Instant.now());
    }

    /** Фабричный метод для сработавшего триггера. */
    public static MonitorResult fired(Trigger trigger, String summary, String details) {
        return new MonitorResult(trigger, true, summary, details, Instant.now());
    }

    /** Форматирует Telegram-сообщение (вызывается только если fired == true). HTML parse_mode. */
    public String toTelegramMessage() {
        String shortDetails = details.length() > 400 ? details.substring(0, 400) + "…" : details;
        return """
                %s <b>%s</b> — %s
                %s
                <i>%s</i>
                """.formatted(
                trigger.level().emoji(),
                trigger.level().label(),
                escapeHtml(trigger.shortLabel()),
                escapeHtml(summary),
                escapeHtml(shortDetails)
        );
    }

    /** Экранирует спецсимволы HTML для Telegram (parse_mode=HTML). */
    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    // compact canonical constructor — обеспечивает непустые строки
    public MonitorResult {
        if (summary == null || summary.isBlank()) summary = fired ? "Триггер сработал" : "Норма";
        if (details == null) details = "";
        if (checkedAt == null) checkedAt = Instant.now();
    }
}
