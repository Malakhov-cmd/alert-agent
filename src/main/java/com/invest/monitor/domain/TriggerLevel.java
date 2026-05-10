package com.invest.monitor.domain;

/**
 * Уровень важности триггера. Sealed — только два варианта сигнала.
 */
public sealed interface TriggerLevel permits TriggerLevel.Critical, TriggerLevel.Warning {

    /** Эмодзи для Telegram-сообщения. */
    String emoji();

    /** Человекочитаемая метка. */
    String label();

    // ── 🚨 Критический — немедленная продажа / действие ──────────────

    record Critical() implements TriggerLevel {
        public static final Critical INSTANCE = new Critical();

        @Override public String emoji() { return "🚨"; }
        @Override public String label() { return "КРИТИЧНО"; }
    }

    // ── ⚠️ Предупреждение — мониторить, готовиться ────────────────────

    record Warning() implements TriggerLevel {
        public static final Warning INSTANCE = new Warning();

        @Override public String emoji() { return "⚠️"; }
        @Override public String label() { return "ВНИМАНИЕ"; }
    }

    // ── Фабричный метод для парсинга из MD-таблицы ───────────────────

    static TriggerLevel of(String raw) {
        return switch (raw.trim().toLowerCase()) {
            case "критично", "critical", "🚨" -> Critical.INSTANCE;
            case "внимание", "warning", "⚠️"  -> Warning.INSTANCE;
            default -> throw new IllegalArgumentException("Неизвестный уровень триггера: " + raw);
        };
    }
}
