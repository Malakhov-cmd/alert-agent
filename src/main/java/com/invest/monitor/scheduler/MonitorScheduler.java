package com.invest.monitor.scheduler;

import com.invest.monitor.agent.MonitorAgent;
import com.invest.monitor.config.MonitorConfig;
import com.invest.monitor.domain.MonitorResult;
import com.invest.monitor.domain.Trigger;
import com.invest.monitor.domain.TriggerFrequency;
import com.invest.monitor.parser.TriggerParser;
import com.invest.monitor.retry.CheckRetryEntry;
import com.invest.monitor.retry.CheckRetryService;
import com.invest.monitor.state.TriggerStateService;
import com.invest.monitor.telegram.TelegramNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Расписание проверок:
 * <ul>
 *   <li>DAILY      — ежедневно в 09:00 по таймзоне из конфига</li>
 *   <li>WEEKLY     — по понедельникам в 09:00</li>
 *   <li>MONTHLY    — 1-го числа каждого месяца в 09:00</li>
 *   <li>QUARTERLY  — 1-го числа января, апреля, июля, октября в 09:00</li>
 * </ul>
 *
 * <p>Перед вызовом агента фильтрует триггеры: если ISIN уже проверен
 * в текущем периоде (запись в trigger_state), его триггеры пропускаются.
 * Сохранение результатов и истории выполняется внутри {@link MonitorAgent}.
 *
 * <p>При ошибке агента ISIN попадает в {@code check_retry_queue} (БД).
 * Поллер {@link #processRetryQueue()} проверяет очередь каждые 5 минут.
 */
@Component
public class MonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitorScheduler.class);

    private final TriggerParser       parser;
    private final MonitorAgent        agent;
    private final TelegramNotifier    notifier;
    private final TriggerStateService stateService;
    private final CheckRetryService   retryService;
    private final String              runMode;

    public MonitorScheduler(TriggerParser parser,
                            MonitorAgent agent,
                            TelegramNotifier notifier,
                            TriggerStateService stateService,
                            CheckRetryService retryService,
                            MonitorConfig config) {
        this.parser        = parser;
        this.agent         = agent;
        this.notifier      = notifier;
        this.stateService  = stateService;
        this.retryService  = retryService;
        this.runMode       = config.runMode().toLowerCase().trim();
    }

    // ── Расписание ───────────────────────────────────────────────────

    @Scheduled(cron = "0 0 9 * * *", zone = "${monitor.timezone:Europe/Moscow}")
    public void runDaily() { run(TriggerFrequency.DAILY, "daily"); }

    @Scheduled(cron = "0 0 9 * * MON", zone = "${monitor.timezone:Europe/Moscow}")
    public void runWeekly() { run(TriggerFrequency.WEEKLY, "weekly"); }

    @Scheduled(cron = "0 0 9 1 * *", zone = "${monitor.timezone:Europe/Moscow}")
    public void runMonthly() { run(TriggerFrequency.MONTHLY, "monthly"); }

    @Scheduled(cron = "0 0 9 1 1,4,7,10 *", zone = "${monitor.timezone:Europe/Moscow}")
    public void runQuarterly() { run(TriggerFrequency.QUARTERLY, "quarterly"); }

    // ── Поллер очереди retry ─────────────────────────────────────────

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void processRetryQueue() {
        List<CheckRetryEntry> due = retryService.pollDue();
        if (due.isEmpty()) return;

        log.info("Retry-поллер: найдено {} записей к обработке", due.size());

        // Группируем по frequency+label чтобы сделать один вызов агента на группу
        due.stream()
           .collect(Collectors.groupingBy(e -> e.getFrequency() + ":" + e.getLabel()))
           .forEach((key, entries) -> processRetryGroup(entries));
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
        notifier.notifyFired(results);

        long firedCount = results.stream().filter(MonitorResult::fired).count();
        log.info("[{}] Проверено: {}, сработало: {}", label, results.size(), firedCount);

        List<Trigger> failed = results.stream()
                .filter(MonitorResult::error)
                .map(MonitorResult::trigger)
                .toList();

        if (!failed.isEmpty()) {
            log.warn("[{}] Не удалось проверить {} триггеров — добавляем в очередь retry.",
                    label, failed.size());
            retryService.enqueue(failed, frequency, label);
        }

        log.info("=== Завершена проверка [{}] ===", label);
    }

    // ── Обработка группы retry-записей ──────────────────────────────

    private void processRetryGroup(List<CheckRetryEntry> entries) {
        CheckRetryEntry first    = entries.get(0);
        TriggerFrequency frequency = TriggerFrequency.valueOf(first.getFrequency());
        String           label    = first.getLabel();
        Set<String>      isins    = entries.stream().map(CheckRetryEntry::getIsin)
                                           .collect(Collectors.toSet());

        log.info("=== Retry [{}] для ISIN: {} (попытка {}) ===",
                label, isins, first.getAttempt());

        List<Trigger> triggers = parser.parseActive().stream()
                .filter(t -> t.frequency() == frequency)
                .filter(t -> isins.contains(t.isin()))
                .toList();

        if (triggers.isEmpty()) {
            log.warn("Retry [{}]: триггеры для ISIN {} не найдены в vault — удаляем из очереди.", label, isins);
            entries.forEach(retryService::markSuccess);
            return;
        }

        List<MonitorResult> results = agent.checkAll(triggers);
        notifier.notifyFired(results);

        Set<String> failedIsins = results.stream()
                .filter(MonitorResult::error)
                .map(r -> r.trigger().isin())
                .collect(Collectors.toSet());

        for (CheckRetryEntry entry : entries) {
            if (failedIsins.contains(entry.getIsin())) {
                boolean exhausted = retryService.markFailed(entry);
                if (exhausted) {
                    List<Trigger> exhaustedTriggers = triggers.stream()
                            .filter(t -> t.isin().equals(entry.getIsin()))
                            .toList();
                    notifier.notifyCheckFailed(exhaustedTriggers);
                }
            } else {
                retryService.markSuccess(entry);
            }
        }
    }
}
