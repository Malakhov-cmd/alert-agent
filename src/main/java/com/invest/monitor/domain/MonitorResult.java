package com.invest.monitor.domain;

import java.time.Instant;

/**
 * Результат проверки одного триггера агентом.
 *
 * @param trigger     Проверенный триггер
 * @param fired       true — триггер сработал, нужно слать сигнал
 * @param summary     Краткое резюме агента (≤ 500 символов)
 * @param details     Полный анализ агента (для логов и истории)
 * @param confidence  Уверенность агента: low | medium | high
 * @param checkedAt   Момент завершения проверки
 */
public record MonitorResult(
        Trigger trigger,
        boolean fired,
        String  summary,
        String  details,
        String  confidence,
        Instant checkedAt
) {
    public static MonitorResult ok(Trigger trigger, String details) {
        return ok(trigger, details, null);
    }

    public static MonitorResult ok(Trigger trigger, String details, String confidence) {
        return new MonitorResult(trigger, false, "Норма", details, confidence, Instant.now());
    }

    public static MonitorResult fired(Trigger trigger, String summary, String details) {
        return fired(trigger, summary, details, null);
    }

    public static MonitorResult fired(Trigger trigger, String summary, String details, String confidence) {
        return new MonitorResult(trigger, true, summary, details, confidence, Instant.now());
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

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public MonitorResult {
        if (summary == null || summary.isBlank()) summary = fired ? "Триггер сработал" : "Норма";
        if (details == null) details = "";
        if (checkedAt == null) checkedAt = Instant.now();
    }
}
