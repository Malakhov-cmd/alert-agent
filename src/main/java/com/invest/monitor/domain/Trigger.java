package com.invest.monitor.domain;

/**
 * Одна строка из таблицы «Мониторинг триггеров.md».
 *
 * @param isin      ISIN бумаги (RU000A…)
 * @param name      Название выпуска
 * @param condition Условие срабатывания в свободном тексте
 * @param level     Уровень сигнала (Critical / Warning)
 * @param frequency Частота проверки
 * @param active    Триггер активен (не зачёркнут, не в архиве)
 */
public record Trigger(
        String           isin,
        String           name,
        String           condition,
        TriggerLevel     level,
        TriggerFrequency frequency,
        boolean          active
) {
    /** Компактная метка для логов и промптов. */
    public String shortLabel() {
        return "[%s] %s".formatted(isin, name);
    }
}
