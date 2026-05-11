package com.invest.monitor.scheduler;

import com.invest.monitor.agent.MonitorAgent;
import com.invest.monitor.config.MonitorConfig;
import com.invest.monitor.domain.MonitorResult;
import com.invest.monitor.domain.Trigger;
import com.invest.monitor.domain.TriggerFrequency;
import com.invest.monitor.parser.TriggerParser;
import com.invest.monitor.state.TriggerStateService;
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
 *   <li>DAILY      — ежедневно в 09:00 по таймзоне из конфига</li>
 *   <li>WEEKLY     — по понедельникам в 09:00</li>
 *   <li>MONTHLY    — 1-го числа каждого месяца в 09:00</li>
 *   <li>QUARTERLY  — 1-го числа января, апреля, июля, октября в 09:00</li>
 * </ul>
 *
 * <p>Триггеры, уже проверенные в текущем периоде, пропускаются — это
 * защищает от повторных расходов токенов при перезапуске приложения.
 */
@Component
public class MonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitorScheduler.class);

    private final TriggerParser       parser;
    private final MonitorAgent        agent;
    private final TelegramNotifier    notifier;
    private final TriggerStateService stateService;
    private final String              runMode;

    public MonitorScheduler(TriggerParser parser,
                            MonitorAgent agent,
                            TelegramNotifier notifier,
                            TriggerStateService stateService,
                            MonitorConfig config) {
        this.parser       = parser;
        this.agent        = agent;
        this.notifier     = notifier;
        this.stateService = stateService;
        this.runMode      = config.runMode().toLowerCase().trim();
    }

    // ── Расписание ───────────────────────────────────────────────────

    @Scheduled(cron = "0 0 9 * * *", zone = "${monitor.timezone:Europe/Moscow}")
    public void runDaily() {
        run(TriggerFrequency.DAILY, "daily");
    }

    @Scheduled(cron = "0 0 9 * * MON", zone = "${monitor.timezone:Europe/Moscow}")
    public void runWeekly() {
        run(TriggerFrequency.WEEKLY, "weekly");
    }

    @Scheduled(cron = "0 0 9 1 * *", zone = "${monitor.timezone:Europe/Moscow}")
    public void runMonthly() {
        run(TriggerFrequency.MONTHLY, "monthly");
    }

    @Scheduled(cron = "0 0 9 1 1,4,7,10 *", zone = "${monitor.timezone:Europe/Moscow}")
    public void runQuarterly() {
        run(TriggerFrequency.QUARTERLY, "quarterly");
    }

    // ── Немедленный запуск при старте ────────────────────────────────

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

    void run(TriggerFrequency frequency, String label) {
        log.info("=== Запуск проверки [{}] ===", label);

        List<Trigger> triggers = parser.parseActive().stream()
                .filter(t -> t.frequency() == frequency)
                .filter(t -> !stateService.alreadyCheckedThisPeriod(t, frequency))
                .toList();

        if (triggers.isEmpty()) {
            log.info("[{}] Нет активных триггеров для этой частоты.", label);
            return;
        }

        log.info("[{}] Триггеров к проверке: {}", label, triggers.size());

        List<MonitorResult> results = agent.checkAll(triggers);
        results.forEach(r -> stateService.recordCheck(r.trigger(), frequency, r.fired()));

        long firedCount = results.stream().filter(MonitorResult::fired).count();
        log.info("[{}] Проверено: {}, сработало: {}", label, results.size(), firedCount);

        notifier.notifyFired(results);

        log.info("=== Завершена проверка [{}] ===", label);
    }
}
