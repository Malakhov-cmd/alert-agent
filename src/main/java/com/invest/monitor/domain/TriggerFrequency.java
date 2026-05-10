package com.invest.monitor.domain;

/** Частота проверки триггера, соответствует расписаниям MonitorScheduler. */
public enum TriggerFrequency {

    DAILY,      // ежедневно
    WEEKLY,     // еженедельно
    MONTHLY,    // ежемесячно
    QUARTERLY;  // ежеквартально

    public static TriggerFrequency of(String raw) {
        return switch (raw.trim().toLowerCase()) {
            case "daily",     "ежедневно",    "д"  -> DAILY;
            case "weekly",    "еженедельно",  "н"  -> WEEKLY;
            case "monthly",   "ежемесячно",   "м"  -> MONTHLY;
            case "quarterly", "ежеквартально","кв" -> QUARTERLY;
            default -> throw new IllegalArgumentException("Неизвестная частота: " + raw);
        };
    }
}
