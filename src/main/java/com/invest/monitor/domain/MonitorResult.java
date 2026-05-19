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
 * @param action      Конкретное действие от агента (выход / не докупать / наблюдать)
 * @param checkedAt   Момент завершения проверки
 */
public record MonitorResult(
        Trigger trigger,
        boolean fired,
        boolean error,
        String  summary,
        String  details,
        String  confidence,
        String  action,
        Instant checkedAt
) {
    public static MonitorResult ok(Trigger trigger, String details) {
        return ok(trigger, details, null);
    }

    public static MonitorResult ok(Trigger trigger, String details, String confidence) {
        return ok(trigger, details, confidence, null);
    }

    public static MonitorResult ok(Trigger trigger, String details, String confidence, String action) {
        return new MonitorResult(trigger, false, false, "Норма", details, confidence, action, Instant.now());
    }

    public static MonitorResult fired(Trigger trigger, String summary, String details) {
        return fired(trigger, summary, details, null);
    }

    public static MonitorResult fired(Trigger trigger, String summary, String details, String confidence) {
        return fired(trigger, summary, details, confidence, null);
    }

    public static MonitorResult fired(Trigger trigger, String summary, String details,
                                      String confidence, String action) {
        return new MonitorResult(trigger, true, false, summary, details, confidence, action, Instant.now());
    }

    public static MonitorResult error(Trigger trigger, String message) {
        return new MonitorResult(trigger, false, true, "Ошибка проверки", message, null, null, Instant.now());
    }

    /** Форматирует Telegram-сообщение (вызывается только если fired == true). HTML parse_mode. */
    public String toTelegramMessage() {
        String shortDetails = details.length() > 400 ? details.substring(0, 400) + "…" : details;
        String actionLine   = (action != null && !action.isBlank())
                ? "\n🎯 " + escapeHtml(action)
                : "";
        return """
                %s <b>%s</b> — %s
                %s
                <i>%s</i>%s
                """.formatted(
                trigger.level().emoji(),
                trigger.level().label(),
                escapeHtml(trigger.shortLabel()),
                escapeHtml(summary),
                escapeHtml(shortDetails),
                actionLine
        );
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public MonitorResult {
        if (summary == null || summary.isBlank()) summary = fired ? "Триггер сработал" : (error ? "Ошибка проверки" : "Норма");
        if (details == null) details = "";
        if (checkedAt == null) checkedAt = Instant.now();
    }
}
