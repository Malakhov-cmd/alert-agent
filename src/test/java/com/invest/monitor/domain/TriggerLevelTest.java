package com.invest.monitor.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TriggerLevelTest {

    // ── Critical ─────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"критично", "КРИТИЧНО", "Критично", "critical", "🚨"})
    void of_returnsCritical(String input) {
        assertThat(TriggerLevel.of(input)).isInstanceOf(TriggerLevel.Critical.class);
    }

    @Test
    void critical_hasCorrectEmojiAndLabel() {
        TriggerLevel level = TriggerLevel.Critical.INSTANCE;
        assertThat(level.emoji()).isEqualTo("🚨");
        assertThat(level.label()).isEqualTo("КРИТИЧНО");
    }

    @Test
    void critical_instanceIsSingleton() {
        assertThat(TriggerLevel.of("критично"))
                .isSameAs(TriggerLevel.of("critical"))
                .isSameAs(TriggerLevel.Critical.INSTANCE);
    }

    // ── Warning ──────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"внимание", "ВНИМАНИЕ", "Внимание", "warning", "⚠️"})
    void of_returnsWarning(String input) {
        assertThat(TriggerLevel.of(input)).isInstanceOf(TriggerLevel.Warning.class);
    }

    @Test
    void warning_hasCorrectEmojiAndLabel() {
        TriggerLevel level = TriggerLevel.Warning.INSTANCE;
        assertThat(level.emoji()).isEqualTo("⚠️");
        assertThat(level.label()).isEqualTo("ВНИМАНИЕ");
    }

    // ── Неизвестное значение ─────────────────────────────────────────

    @Test
    void of_throwsOnUnknownValue() {
        assertThatThrownBy(() -> TriggerLevel.of("супер-критично"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Неизвестный уровень триггера");
    }

    // ── sealed exhaustiveness (проверяем через switch) ───────────────

    @Test
    void sealedSwitch_coversAllPermits() {
        TriggerLevel level = TriggerLevel.Critical.INSTANCE;
        // Компилятор Java 26 проверяет полноту switch по sealed interface
        String result = switch (level) {
            case TriggerLevel.Critical c -> "critical";
            case TriggerLevel.Warning  w -> "warning";
        };
        assertThat(result).isEqualTo("critical");
    }
}
