package com.invest.monitor.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MonitorResultTest {

    private static final Trigger CRITICAL_TRIGGER = new Trigger(
            "RU000A106347", "ОФЗ 26238",
            "YTM > 15%",
            TriggerLevel.Critical.INSTANCE,
            TriggerFrequency.DAILY,
            true
    );

    private static final Trigger WARNING_TRIGGER = new Trigger(
            "RU000A105A95", "Газпром БО-26",
            "Рейтинг снижен ниже BB+",
            TriggerLevel.Warning.INSTANCE,
            TriggerFrequency.MONTHLY,
            true
    );

    // ── Фабричные методы ─────────────────────────────────────────────

    @Test
    void ok_firedIsFalse() {
        MonitorResult result = MonitorResult.ok(CRITICAL_TRIGGER, "всё хорошо");
        assertThat(result.fired()).isFalse();
        assertThat(result.summary()).isEqualTo("Норма");
        assertThat(result.details()).isEqualTo("всё хорошо");
        assertThat(result.checkedAt()).isNotNull();
    }

    @Test
    void fired_firedIsTrue() {
        MonitorResult result = MonitorResult.fired(CRITICAL_TRIGGER, "YTM = 16.2%", "подробности");
        assertThat(result.fired()).isTrue();
        assertThat(result.summary()).isEqualTo("YTM = 16.2%");
        assertThat(result.details()).isEqualTo("подробности");
    }

    // ── Compact constructor — защита от null ─────────────────────────

    @Test
    void compactConstructor_nullSummaryBecomesDefault() {
        MonitorResult result = new MonitorResult(CRITICAL_TRIGGER, true, null, "d", null, null);
        assertThat(result.summary()).isEqualTo("Триггер сработал");
        assertThat(result.checkedAt()).isNotNull();
    }

    @Test
    void compactConstructor_nullDetailsBecomesEmpty() {
        MonitorResult result = new MonitorResult(CRITICAL_TRIGGER, false, "ok", null, null, null);
        assertThat(result.details()).isEqualTo("");
    }

    // ── toTelegramMessage ────────────────────────────────────────────

    @Test
    void toTelegramMessage_containsEmojiAndLabel() {
        MonitorResult result = MonitorResult.fired(CRITICAL_TRIGGER, "YTM превысил 15%", "детали");
        String msg = result.toTelegramMessage();

        assertThat(msg).contains("🚨");
        assertThat(msg).contains("КРИТИЧНО");
    }

    @Test
    void toTelegramMessage_containsIsinInEscapedForm() {
        MonitorResult result = MonitorResult.fired(CRITICAL_TRIGGER, "сигнал", "детали");
        String msg = result.toTelegramMessage();

        assertThat(msg).contains("RU000A106347");
        assertThat(msg).contains("ОФЗ 26238");
    }

    @Test
    void toTelegramMessage_warningUsesCorrectEmoji() {
        MonitorResult result = MonitorResult.fired(WARNING_TRIGGER, "рейтинг снижен", "детали");
        String msg = result.toTelegramMessage();

        assertThat(msg).contains("⚠️");
        assertThat(msg).contains("ВНИМАНИЕ");
    }

    @Test
    void toTelegramMessage_htmlSpecialCharsAreEscaped() {
        Trigger triggerWithHtml = new Trigger(
                "RU000A000000", "Test <Bond> & 'Fund'",
                "price < 90%",
                TriggerLevel.Critical.INSTANCE,
                TriggerFrequency.DAILY,
                true
        );
        MonitorResult result = MonitorResult.fired(triggerWithHtml, "price < 90%", "<b>детали</b>");
        String msg = result.toTelegramMessage();

        assertThat(msg).doesNotContain("<Bond>");
        assertThat(msg).contains("&lt;Bond&gt;");
        assertThat(msg).contains("&amp;");
        // детали тоже экранированы
        assertThat(msg).doesNotContain("<b>детали</b>");
        assertThat(msg).contains("&lt;b&gt;детали&lt;/b&gt;");
    }

    @Test
    void toTelegramMessage_longDetailsAreTruncated() {
        String longDetails = "x".repeat(500);
        MonitorResult result = MonitorResult.fired(CRITICAL_TRIGGER, "сигнал", longDetails);
        String msg = result.toTelegramMessage();

        // детали обрезаются до 400 символов + "…"
        assertThat(msg).contains("…");
        // полные 500 символов не должны присутствовать
        assertThat(msg).doesNotContain(longDetails);
    }
}
