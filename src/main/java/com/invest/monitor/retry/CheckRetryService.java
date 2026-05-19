package com.invest.monitor.retry;

import com.invest.monitor.domain.Trigger;
import com.invest.monitor.domain.TriggerFrequency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CheckRetryService {

    private static final Logger log          = LoggerFactory.getLogger(CheckRetryService.class);
    private static final int    MAX_RETRIES  = 3;
    private static final int    RETRY_HOURS  = 1;

    private final CheckRetryRepository repo;

    public CheckRetryService(CheckRetryRepository repo) {
        this.repo = repo;
    }

    /** Ставит в очередь retry для упавших триггеров (attempt=1, через 1 час). */
    @Transactional
    public void enqueue(List<Trigger> failedTriggers, TriggerFrequency frequency, String label) {
        Set<String> isins = failedTriggers.stream().map(Trigger::isin).collect(Collectors.toSet());
        LocalDateTime scheduledAt = LocalDateTime.now().plusHours(RETRY_HOURS);

        for (String isin : isins) {
            // Не дублируем — если запись уже есть, пропускаем
            boolean exists = repo.findByScheduledAtLessThanEqual(LocalDateTime.now().plusDays(1))
                    .stream().anyMatch(e -> e.getIsin().equals(isin)
                                        && e.getFrequency().equals(frequency.name()));
            if (!exists) {
                repo.save(new CheckRetryEntry(isin, frequency.name(), label, 1, scheduledAt));
                log.info("Добавлен retry для ISIN {} [{}], попытка 1/{}, время: {}",
                        isin, label, MAX_RETRIES, scheduledAt);
            }
        }
    }

    /** Возвращает записи, время которых наступило. */
    public List<CheckRetryEntry> pollDue() {
        return repo.findByScheduledAtLessThanEqual(LocalDateTime.now());
    }

    /** Успех — удаляем из очереди. */
    @Transactional
    public void markSuccess(CheckRetryEntry entry) {
        repo.delete(entry);
        log.info("Retry успешен, удалён из очереди: ISIN {} [{}]", entry.getIsin(), entry.getLabel());
    }

    /**
     * Неудача — либо переносим на следующий час, либо возвращаем true если исчерпаны попытки.
     * @return true если все попытки исчерпаны и нужно слать уведомление
     */
    @Transactional
    public boolean markFailed(CheckRetryEntry entry) {
        if (entry.getAttempt() >= MAX_RETRIES) {
            repo.delete(entry);
            log.warn("Исчерпаны все {} попытки для ISIN {} [{}] — удалён из очереди.",
                    MAX_RETRIES, entry.getIsin(), entry.getLabel());
            return true;
        }
        entry.reschedule(LocalDateTime.now().plusHours(RETRY_HOURS));
        repo.save(entry);
        log.info("Retry перенесён: ISIN {} [{}], попытка {}/{}, следующий запуск: {}",
                entry.getIsin(), entry.getLabel(), entry.getAttempt(), MAX_RETRIES, entry.getScheduledAt());
        return false;
    }
}
