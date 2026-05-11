package com.invest.monitor.state;

import com.invest.monitor.domain.Trigger;
import com.invest.monitor.domain.TriggerFrequency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;

@Service
public class TriggerStateService {

    private static final Logger log = LoggerFactory.getLogger(TriggerStateService.class);

    private final TriggerStateRepository repo;

    public TriggerStateService(TriggerStateRepository repo) {
        this.repo = repo;
    }

    /**
     * Возвращает true, если триггер уже был проверен в текущем периоде
     * (сегодня / эта неделя / этот месяц / этот квартал).
     */
    public boolean alreadyCheckedThisPeriod(Trigger trigger, TriggerFrequency frequency) {
        return repo.findById(new TriggerStateId(trigger.isin(), frequency.name()))
                .map(s -> isCurrentPeriod(s.getLastCheckedAt(), frequency))
                .orElse(false);
    }

    /** Сохраняет дату последней проверки и при необходимости дату последнего срабатывания. */
    public void recordCheck(Trigger trigger, TriggerFrequency frequency, boolean fired) {
        LocalDate today = LocalDate.now();
        TriggerStateId id = new TriggerStateId(trigger.isin(), frequency.name());
        TriggerState state = repo.findById(id)
                .orElseGet(() -> new TriggerState(trigger.isin(), frequency.name(), today, null));
        state.setLastCheckedAt(today);
        if (fired) state.setLastFiredAt(today);
        repo.save(state);
        log.debug("Состояние сохранено: isin={}, freq={}, fired={}", trigger.isin(), frequency, fired);
    }

    private boolean isCurrentPeriod(LocalDate lastChecked, TriggerFrequency frequency) {
        LocalDate today = LocalDate.now();
        return switch (frequency) {
            case DAILY -> lastChecked.equals(today);
            case WEEKLY -> !lastChecked.isBefore(today.with(DayOfWeek.MONDAY));
            case MONTHLY -> lastChecked.getYear() == today.getYear()
                    && lastChecked.getMonthValue() == today.getMonthValue();
            case QUARTERLY -> lastChecked.getYear() == today.getYear()
                    && quarter(lastChecked) == quarter(today);
        };
    }

    private static int quarter(LocalDate date) {
        return (date.getMonthValue() - 1) / 3 + 1;
    }
}
