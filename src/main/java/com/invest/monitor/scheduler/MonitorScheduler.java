package com.invest.monitor.scheduler;

import com.invest.monitor.agent.MonitorAgent;
import com.invest.monitor.config.MonitorConfig;
import com.invest.monitor.domain.MonitorResult;
import com.invest.monitor.domain.Trigger;
import com.invest.monitor.domain.TriggerFrequency;
import com.invest.monitor.parser.TriggerParser;
import com.invest.monitor.telegram.TelegramNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Расписание проверок:
 * <ul>
 *   <li>DAILY      — ежедневно в 09:00 МСК</li>
 *   <li>WEEKLY     — по понедельникам в 09:00 МСК</li>
 *   <li>MONTHLY    — 1-го числа каждого месяца в 09:00 МСК</li>
 *   <li>QUARTERLY  — 1-го числа января, апреля, июля, октября в 09:00 МСК</li>
 * </ul>
 *
 * <p>При старте приложения выполняется немедленный прогон,
 * если {@code monitor.run-mode} не равен {@code "scheduled"}.
 */
@Component
public class MonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitorScheduler.class);

    // Cron-формат Spring: секунды минуты часы день месяц день_недели
    // Часовой пояс Europe/Moscow (UTC+3)
    private static final String ZONE = "Europe/Moscow";

    private final TriggerParser    parser;
    private final MonitorAgent     agent;
    private final TelegramNotifier notifier;
    private final String           runMode;

    public MonitorScheduler(TriggerParser parser,
                            MonitorAgent agent,
                            TelegramNotifier notifier,
                            MonitorConfig config) {
        this.parser   = parser;
        this.agent    = agent;
        this.notifier = notifier;
        this.runMode  = config.runMode().toLowerCase().trim();
    }

    // ── Расписание ───────────────────────────────────────────────────

    /** Ежедневно в 09:00 МСК. */
    @Scheduled(cron = "0 0 9 * * *", zone = ZONE)
    public void runDaily() {
        run(TriggerFrequency.DAILY, "daily");
    }

    /** По понедельникам в 09:00 МСК. */
    @Scheduled(cron = "0 0 9 * * MON", zone = ZONE)
    public void runWeekly() {
        run(TriggerFrequency.WEEKLY, "weekly");
    }

    /** 1-го числа каждого месяца в 09:00 МСК. */
    @Scheduled(cron = "0 0 9 1 * *", zone = ZONE)
    public void runMonthly() {
        run(TriggerFrequency.MONTHLY, "monthly");
    }

    /** 1-го числа января, апреля, июля и октября в 09:00 МСК. */
    @Scheduled(cron = "0 0 9 1 1,4,7,10 *", zone = ZONE)
    public void runQuarterly() {
        run(TriggerFrequency.QUARTERLY, "quarterly");
    }

    // ── Немедленный запуск при старте ────────────────────────────────

    /**
     * Если RUN_MODE != "scheduled" — запускает нужную частоту сразу после старта.
     * Удобно для разового запуска в CI / ручного теста.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if ("scheduled".equals(runMode)) {
            log.info("Режим scheduled — ждём расписания.");
            return;
        }

        TriggerFrequency freq = switch (runMode) {
            case "daily"     -> TriggerFrequency.DAILY;
            case "weekly"    -> TriggerFrequency.WEEKLY;
            case "monthly"   -> TriggerFrequency.MONTHLY;
            case "quarterly" -> TriggerFrequency.QUARTERLY;
            default -> {
                log.warn("Неизвестный run-mode: '{}', запускаем DAILY.", runMode);
                yield TriggerFrequency.DAILY;
            }
        };

        log.info("Немедленный запуск при старте, режим: {}", runMode);
        run(freq, runMode);
    }

    // ── Основная логика прогона ──────────────────────────────────────

    /**
     * Читает активные триггеры нужной частоты, проверяет каждый агентом,
     * отправляет сработавшие в Telegram.
     */
    void run(TriggerFrequency frequency, String label) {
        log.info("=== Запуск проверки [{}] ===", label);

        List<Trigger> triggers = parser.parseActive().stream()
                .filter(t -> t.frequency() == frequency)
                .toList();

        if (triggers.isEmpty()) {
            log.info("[{}] Нет активных триггеров для этой частоты.", label);
            return;
        }

        log.info("[{}] Триггеров к проверке: {}", label, triggers.size());

        List<MonitorResult> results = agent.checkAll(triggers);

        long firedCount = results.stream().filter(MonitorResult::fired).count();
        log.info("[{}] Проверено: {}, сработало: {}", label, results.size(), firedCount);

        notifier.notifyFired(results);

        log.info("=== Завершена проверка [{}] ===", label);
    }
}
