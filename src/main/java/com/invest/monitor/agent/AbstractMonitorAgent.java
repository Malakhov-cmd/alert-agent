package com.invest.monitor.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invest.monitor.config.MonitorConfig;
import com.invest.monitor.domain.MonitorResult;
import com.invest.monitor.domain.Trigger;
import com.invest.monitor.domain.TriggerFrequency;
import com.invest.monitor.state.TriggerState;
import com.invest.monitor.state.TriggerStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Общая логика обоих агентов: группировка по ISIN, формирование user message,
 * парсинг JSON-массива ответа, сохранение истории.
 * Конкретный провайдер (Claude / Gemini) реализует {@link #callProvider}.
 */
abstract class AbstractMonitorAgent implements MonitorAgent {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private static final Pattern JSON_ARRAY = Pattern.compile(
            "```json\\s*(\\[.*?])\\s*```|(\\[.*])$",
            Pattern.DOTALL | Pattern.MULTILINE
    );

    protected final TriggerStateService stateService;
    protected final ObjectMapper        mapper;
    protected final String              systemPrompt;
    protected final long                delaySec;
    protected final boolean             stub;

    protected AbstractMonitorAgent(TriggerStateService stateService,
                                    ObjectMapper mapper,
                                    MonitorConfig config) {
        this.stateService = stateService;
        this.mapper       = mapper;
        this.systemPrompt = config.prompt().system();
        this.delaySec     = config.delayBetweenChecksSec();
        this.stub         = config.search().stub();
    }

    // ── Публичный API ────────────────────────────────────────────────

    @Override
    public List<MonitorResult> checkAll(List<Trigger> triggers) {
        if (triggers.isEmpty()) return List.of();

        TriggerFrequency frequency = triggers.get(0).frequency();

        Map<String, List<Trigger>> byIsin = triggers.stream()
                .collect(Collectors.groupingBy(Trigger::isin, LinkedHashMap::new, Collectors.toList()));

        log.info("Уникальных ISIN: {}, всего триггеров: {}", byIsin.size(), triggers.size());

        List<MonitorResult> allResults = new ArrayList<>();
        Iterator<Map.Entry<String, List<Trigger>>> it = byIsin.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<String, List<Trigger>> entry = it.next();
            List<MonitorResult> results = checkIsin(entry.getKey(), entry.getValue(), frequency);
            results.forEach(r -> stateService.recordCheck(r, frequency));
            allResults.addAll(results);

            if (it.hasNext()) sleep();
        }

        return allResults;
    }

    // ── Проверка одного ISIN ─────────────────────────────────────────

    private List<MonitorResult> checkIsin(String isin, List<Trigger> triggers,
                                           TriggerFrequency frequency) {
        log.info("Проверяем ISIN {} ({} триггеров)", isin, triggers.size());

        if (stub) {
            log.warn("[STUB] Агент не вызывается — возвращаем фиктивный OK для ISIN {}", isin);
            return triggers.stream()
                    .map(t -> MonitorResult.ok(t, "[STUB] Проверка пропущена в dev-режиме", "stub"))
                    .toList();
        }

        try {
            String raw = callProvider(isin, triggers, frequency);
            return parseArrayResponse(triggers, raw);
        } catch (Exception e) {
            log.error("Ошибка при проверке ISIN {}: {}", isin, e.getMessage(), e);
            return triggers.stream()
                    .map(t -> MonitorResult.ok(t, "Ошибка агента: " + e.getMessage()))
                    .toList();
        }
    }

    /** Реализация вызова конкретного провайдера — Claude или Gemini. */
    protected abstract String callProvider(String isin, List<Trigger> triggers,
                                           TriggerFrequency frequency);

    // ── Формирование запроса ─────────────────────────────────────────

    protected String buildUserMessage(String isin, List<Trigger> triggers,
                                      TriggerFrequency frequency) {
        Optional<TriggerState> prevState = stateService.getState(isin, frequency);

        List<Map<String, Object>> triggerList = triggers.stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("condition", t.condition());
                    m.put("level", t.level().emoji() + " " + t.level().label());
                    m.put("previous_result", formatPreviousResult(prevState));
                    return m;
                })
                .toList();

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("isin", isin);
        msg.put("name", triggers.get(0).name());

        Double currentShare = triggers.stream().map(Trigger::currentSharePct)
                .filter(Objects::nonNull).findFirst().orElse(null);
        Double limitPct = triggers.stream().map(Trigger::limitPct)
                .filter(Objects::nonNull).findFirst().orElse(null);
        if (currentShare != null || limitPct != null) {
            Map<String, Object> pos = new LinkedHashMap<>();
            if (currentShare != null) pos.put("current_share_pct", currentShare);
            if (limitPct     != null) pos.put("limit_pct", limitPct);
            msg.put("position", pos);
        }

        msg.put("triggers", triggerList);

        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(msg);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сериализовать user message", e);
        }
    }

    private String formatPreviousResult(Optional<TriggerState> state) {
        return state.map(s -> {
            String status  = s.getLastFiredAt() != null
                    && !s.getLastFiredAt().isBefore(LocalDate.now().minusDays(1))
                    ? "FIRED" : "OK";
            String conf    = s.getLastConfidence() != null ? s.getLastConfidence() : "unknown";
            String summary = s.getLastSummary()    != null ? ". " + s.getLastSummary() : "";
            return status + ", " + conf + ", " + s.getLastCheckedAt() + summary;
        }).orElse(null);
    }

    // ── Парсинг ответа ───────────────────────────────────────────────

    protected List<MonitorResult> parseArrayResponse(List<Trigger> triggers, String raw) {
        log.debug("Ответ агента: {}", raw);

        String json = extractJsonArray(raw);
        if (json == null) {
            log.warn("Агент не вернул JSON-массив. Ответ: {}", raw);
            return triggers.stream().map(t -> MonitorResult.ok(t, raw)).toList();
        }

        try {
            List<AgentResponse> responses = mapper.readValue(json, new TypeReference<>() {});

            if (responses.size() != triggers.size()) {
                log.warn("Несоответствие: триггеров={}, ответов={}", triggers.size(), responses.size());
            }

            List<MonitorResult> results = new ArrayList<>();
            for (int i = 0; i < triggers.size(); i++) {
                Trigger t = triggers.get(i);
                if (i < responses.size()) {
                    AgentResponse r = responses.get(i);
                    results.add(r.fired()
                            ? MonitorResult.fired(t, r.summary(), r.details(), r.confidence(), r.action())
                            : MonitorResult.ok(t, r.details(), r.confidence(), r.action()));
                } else {
                    results.add(MonitorResult.ok(t, "Нет ответа от агента"));
                }
            }
            return results;

        } catch (Exception e) {
            log.warn("Не удалось распарсить JSON-массив: {}", e.getMessage());
            return triggers.stream().map(t -> MonitorResult.ok(t, raw)).toList();
        }
    }

    private String extractJsonArray(String text) {
        Matcher m = JSON_ARRAY.matcher(text.trim());
        if (m.find()) {
            return m.group(1) != null ? m.group(1) : m.group(2);
        }
        return null;
    }

    // ── Утилиты ──────────────────────────────────────────────────────

    private void sleep() {
        try {
            log.info("Пауза {} сек. перед следующим ISIN...", delaySec);
            Thread.sleep(delaySec * 1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── DTO ответа агента ────────────────────────────────────────────

    record AgentResponse(
            String  condition,
            boolean fired,
            String  summary,
            String  details,
            String  confidence,
            String  action
    ) {}
}
