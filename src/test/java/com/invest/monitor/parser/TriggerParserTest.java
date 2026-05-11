package com.invest.monitor.parser;

import com.invest.monitor.config.MonitorConfig;
import com.invest.monitor.domain.Trigger;
import com.invest.monitor.domain.TriggerFrequency;
import com.invest.monitor.domain.TriggerLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TriggerParserTest {

    private TriggerParser parser;

    @BeforeEach
    void setUp() throws URISyntaxException {
        Path vaultPath = Path.of(
                Objects.requireNonNull(getClass().getClassLoader().getResource("vault")).toURI()
        );
        MonitorConfig config = new MonitorConfig(
                vaultPath.toString(), "daily", 0L,
                new MonitorConfig.Anthropic("test-key", "claude-sonnet-4-6"),
                new MonitorConfig.Telegram("test-token", "test-chat", ""),
                new MonitorConfig.Search("test-tavily-key", 5, List.of(), "", false, 3),
                new MonitorConfig.Prompt("test system prompt"),
                "Europe/Moscow", "anthropic",
                new MonitorConfig.Google("", "gemini-2.5-flash", "")
        );
        parser = new TriggerParser(config);
    }

    // ── Полный парсинг ───────────────────────────────────────────────

    @Test
    void parse_returnsAllRows() {
        List<Trigger> all = parser.parse();
        assertThat(all).hasSize(5);
    }

    @Test
    void parseActive_filtersInactiveRows() {
        List<Trigger> active = parser.parseActive();
        assertThat(active).hasSize(4);
        assertThat(active).allMatch(Trigger::active);
    }

    // ── Корректность полей ───────────────────────────────────────────

    @Test
    void parse_firstRow_fieldsAreCorrect() {
        Trigger t = parser.parse().getFirst();

        assertThat(t.isin()).isEqualTo("RU000A106347");
        assertThat(t.name()).isEqualTo("ОФЗ 26238");
        assertThat(t.condition()).isEqualTo("YTM > 15%");
        assertThat(t.level()).isInstanceOf(TriggerLevel.Critical.class);
        assertThat(t.frequency()).isEqualTo(TriggerFrequency.DAILY);
        assertThat(t.active()).isTrue();
    }

    @Test
    void parse_secondRow_warningLevelAndMonthly() {
        Trigger t = parser.parse().get(1);

        assertThat(t.isin()).isEqualTo("RU000A105A95");
        assertThat(t.level()).isInstanceOf(TriggerLevel.Warning.class);
        assertThat(t.frequency()).isEqualTo(TriggerFrequency.MONTHLY);
    }

    @Test
    void parse_fourthRow_inactiveFlag() {
        Trigger t = parser.parse().get(3);

        assertThat(t.isin()).isEqualTo("RU000A108000");
        assertThat(t.active()).isFalse();
        assertThat(t.frequency()).isEqualTo(TriggerFrequency.QUARTERLY);
    }

    // ── Фильтрация по частоте ────────────────────────────────────────

    @Test
    void parseActive_containsBothDailyTriggers() {
        List<Trigger> daily = parser.parseActive().stream()
                .filter(t -> t.frequency() == TriggerFrequency.DAILY)
                .toList();

        assertThat(daily).hasSize(2);
        assertThat(daily).extracting(Trigger::isin)
                .containsExactlyInAnyOrder("RU000A106347", "RU000A109111");
    }

    // ── shortLabel ───────────────────────────────────────────────────

    @Test
    void shortLabel_hasCorrectFormat() {
        Trigger t = parser.parse().getFirst();
        assertThat(t.shortLabel()).isEqualTo("[RU000A106347] ОФЗ 26238");
    }

    // ── Файл не найден ───────────────────────────────────────────────

    @Test
    void parse_throwsWhenFileNotFound() {
        MonitorConfig badConfig = new MonitorConfig(
                "/несуществующий/путь", "daily", 0L,
                new MonitorConfig.Anthropic("k", "m"),
                new MonitorConfig.Telegram("t", "c", ""),
                new MonitorConfig.Search("s", 5, List.of(), "", false, 3),
                new MonitorConfig.Prompt("test prompt"),
                "Europe/Moscow", "anthropic",
                new MonitorConfig.Google("", "gemini-2.5-flash", "")
        );
        TriggerParser badParser = new TriggerParser(badConfig);

        assertThatThrownBy(badParser::parse)
                .isInstanceOf(java.io.UncheckedIOException.class)
                .hasMessageContaining("Не удалось прочитать файл триггеров");
    }
}
