package com.invest.monitor.agent;

import com.invest.monitor.domain.MonitorResult;
import com.invest.monitor.domain.Trigger;

import java.util.List;

/** Стратегия агента мониторинга: один вызов LLM на каждый уникальный ISIN. */
public interface MonitorAgent {
    List<MonitorResult> checkAll(List<Trigger> triggers);
}
