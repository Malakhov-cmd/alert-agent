package com.invest.monitor.scheduler;

import com.invest.monitor.agent.MonitorAgent;
import com.invest.monitor.config.MonitorConfig;
import com.invest.monitor.domain.MonitorResult;
import com.invest.monitor.domain.Trigger;
import com.invest.monitor.domain.TriggerFrequency;
import com.invest.monitor.domain.TriggerLevel;
import com.invest.monitor.parser.TriggerParser;
import com.invest.monitor.state.TriggerStateService;
import com.invest.monitor.telegram.TelegramNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitorSchedulerTest {

    @Mock TriggerParser       parser;
    @Mock MonitorAgent        agent;
    @Mock TelegramNotifier    notifier;
    @Mock TriggerStateService stateService;

    private final Trigger daily1    = trigger("RU0001", TriggerFrequency.DAILY);
    private final Trigger daily2    = trigger("RU0002", TriggerFrequency.DAILY);
    private final Trigger weekly    = trigger("RU0003", TriggerFrequency.WEEKLY);
    private final Trigger monthly   = trigger("RU0004", TriggerFrequency.MONTHLY);
    private final Trigger quarterly = trigger("RU0005", TriggerFrequency.QUARTERLY);

    @BeforeEach
    void setUp() {
        lenient().when(parser.parseActive()).thenReturn(
                List.of(daily1, daily2, weekly, monthly, quarterly)
        );
        // По умолчанию: триггеры ещё не проверялись в текущем периоде
        lenient().when(stateService.alreadyCheckedThisPeriod(any(), any())).thenReturn(false);
    }

    // ── Фильтрация по частоте ────────────────────────────────────────

    @Test
    void run_daily_passesOnlyDailyTriggers() {
        MonitorScheduler scheduler = makeScheduler("scheduled");

        scheduler.run(TriggerFrequency.DAILY, "daily");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Trigger>> captor = ArgumentCaptor.forClass(List.class);
        verify(agent).checkAll(captor.capture());

        assertThat(captor.getValue())
                .containsExactlyInAnyOrder(daily1, daily2)
                .noneMatch(t -> t.frequency() != TriggerFrequency.DAILY);
    }

    @Test
    void run_weekly_passesOnlyWeeklyTriggers() {
        MonitorScheduler scheduler = makeScheduler("scheduled");

        scheduler.run(TriggerFrequency.WEEKLY, "weekly");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Trigger>> captor = ArgumentCaptor.forClass(List.class);
        verify(agent).checkAll(captor.capture());

        assertThat(captor.getValue()).containsExactly(weekly);
    }

    // ── Нет триггеров для частоты ────────────────────────────────────

    @Test
    void run_noMatchingTriggers_agentNotCalled() {
        when(parser.parseActive()).thenReturn(List.of(monthly));

        MonitorScheduler scheduler = makeScheduler("scheduled");
        scheduler.run(TriggerFrequency.DAILY, "daily");

        verify(agent, never()).checkAll(anyList());
        verify(notifier, never()).notifyFired(anyList());
    }

    // ── Уже проверен в этом периоде — пропускаем ────────────────────

    @Test
    void run_alreadyChecked_skipsAndDoesNotCallAgent() {
        when(stateService.alreadyCheckedThisPeriod(any(), any())).thenReturn(true);

        MonitorScheduler scheduler = makeScheduler("scheduled");
        scheduler.run(TriggerFrequency.DAILY, "daily");

        verify(agent, never()).checkAll(anyList());
    }

    // ── Нотификация только сработавших ───────────────────────────────

    @Test
    void run_notifiesOnlyFiredResults() {
        MonitorResult fired = MonitorResult.fired(daily1, "YTM=16%", "детали");
        MonitorResult ok    = MonitorResult.ok(daily2, "норма");

        when(agent.checkAll(anyList())).thenReturn(List.of(fired, ok));

        MonitorScheduler scheduler = makeScheduler("scheduled");
        scheduler.run(TriggerFrequency.DAILY, "daily");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MonitorResult>> captor = ArgumentCaptor.forClass(List.class);
        verify(notifier).notifyFired(captor.capture());

        assertThat(captor.getValue()).containsExactlyInAnyOrder(fired, ok);
    }

    // ── Startup: run-mode маппинг ────────────────────────────────────

    @Test
    void onStartup_runModeDaily_callsRunWithDaily() {
        when(agent.checkAll(anyList())).thenReturn(List.of());

        MonitorScheduler scheduler = makeScheduler("daily");
        scheduler.onStartup();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Trigger>> captor = ArgumentCaptor.forClass(List.class);
        verify(agent).checkAll(captor.capture());

        assertThat(captor.getValue()).allMatch(t -> t.frequency() == TriggerFrequency.DAILY);
    }

    @Test
    void onStartup_runModeScheduled_doesNothing() {
        MonitorScheduler scheduler = makeScheduler("scheduled");
        scheduler.onStartup();

        verify(agent, never()).checkAll(anyList());
    }

    // ── Хелперы ──────────────────────────────────────────────────────

    private MonitorScheduler makeScheduler(String runMode) {
        MonitorConfig config = new MonitorConfig(
                "./vault", runMode, 0L,
                new MonitorConfig.Anthropic("key", "model"),
                new MonitorConfig.Telegram("token", "chat", ""),
                new MonitorConfig.Search("tavily-key", 5, List.of(), "", false),
                new MonitorConfig.Prompt("test system prompt"),
                "Europe/Moscow", "anthropic",
                new MonitorConfig.Google("", "gemini-2.5-flash-preview-05-20", "")
        );
        return new MonitorScheduler(parser, agent, notifier, stateService, config);
    }

    private Trigger trigger(String isin, TriggerFrequency frequency) {
        return new Trigger(isin, "Бумага " + isin, "условие",
                TriggerLevel.Critical.INSTANCE, frequency, true, null, null);
    }
}
