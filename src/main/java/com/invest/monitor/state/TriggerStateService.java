package com.invest.monitor.state;

import com.invest.monitor.domain.MonitorResult;
import com.invest.monitor.domain.Trigger;
import com.invest.monitor.domain.TriggerFrequency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class TriggerStateService {

    private static final Logger log = LoggerFactory.getLogger(TriggerStateService.class);

    private final TriggerStateRepository        stateRepo;
    private final TriggerCheckHistoryRepository historyRepo;

    public TriggerStateService(TriggerStateRepository stateRepo,
                                TriggerCheckHistoryRepository historyRepo) {
        this.stateRepo   = stateRepo;
        this.historyRepo = historyRepo;
    }

    /**
     * Возвращает true, если ISIN уже был проверен в текущем периоде —
     * тогда весь ISIN-блок пропускается без вызова Claude.
     */
    public boolean alreadyCheckedThisPeriod(Trigger trigger, TriggerFrequency frequency) {
        return stateRepo.findById(new TriggerStateId(trigger.isin(), frequency.name()))
                .map(s -> isCurrentPeriod(s.getLastCheckedAt(), frequency))
                .orElse(false);
    }

    /** Возвращает последнее сохранённое состояние для данного ISIN и частоты. */
    public Optional<TriggerState> getState(String isin, TriggerFrequency frequency) {
        return stateRepo.findById(new TriggerStateId(isin, frequency.name()));
    }

    /**
     * Сохраняет результат проверки одного триггера:
     * <ol>
     *   <li>INSERT в trigger_check_history</li>
     *   <li>UPDATE trigger_state: last_checked_at, last_fired_at (если сработал),
     *       last_summary/details/confidence — fired-результат перезаписывает OK</li>
     * </ol>
     */
    @Transactional
    public void recordCheck(MonitorResult result, TriggerFrequency frequency) {
        LocalDate today = LocalDate.now();
        Trigger   t     = result.trigger();

        historyRepo.save(new TriggerCheckHistory(
                t.isin(), t.name(), t.condition(),
                LocalDateTime.now(), frequency.name(),
                result.fired(), result.summary(), result.details(), result.confidence()
        ));

        TriggerStateId id    = new TriggerStateId(t.isin(), frequency.name());
        TriggerState   state = stateRepo.findById(id)
                .orElseGet(() -> new TriggerState(
                        t.isin(), frequency.name(), today, null, null, null, null));

        state.setLastCheckedAt(today);
        if (result.fired()) state.setLastFiredAt(today);

        // fired-результат всегда перезаписывает предыдущий OK;
        // OK записывается только если поле ещё не заполнено
        if (result.fired() || state.getLastSummary() == null) {
            state.setLastSummary(result.summary());
            state.setLastDetails(result.details());
            state.setLastConfidence(result.confidence());
        }

        stateRepo.save(state);
        log.debug("Сохранено: isin={}, freq={}, fired={}", t.isin(), frequency, result.fired());
    }

    // ── Определение текущего периода ─────────────────────────────────

    private boolean isCurrentPeriod(LocalDate lastChecked, TriggerFrequency frequency) {
        LocalDate today = LocalDate.now();
        return switch (frequency) {
            case DAILY    -> lastChecked.equals(today);
            case WEEKLY   -> !lastChecked.isBefore(today.with(DayOfWeek.MONDAY));
            case MONTHLY  -> lastChecked.getYear() == today.getYear()
                          && lastChecked.getMonthValue() == today.getMonthValue();
            case QUARTERLY -> lastChecked.getYear() == today.getYear()
                          && quarter(lastChecked) == quarter(today);
        };
    }

    private static int quarter(LocalDate date) {
        return (date.getMonthValue() - 1) / 3 + 1;
    }
}
