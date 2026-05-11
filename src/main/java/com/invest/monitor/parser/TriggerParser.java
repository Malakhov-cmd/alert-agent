package com.invest.monitor.parser;

import com.invest.monitor.config.MonitorConfig;
import com.invest.monitor.domain.Trigger;
import com.invest.monitor.domain.TriggerFrequency;
import com.invest.monitor.domain.TriggerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Читает «Invest/Мониторинг триггеров.md» из Obsidian vault
 * и превращает MD-таблицу в список {@link Trigger}.
 *
 * <p>Формат ожидаемой таблицы (порядок колонок гибкий):
 * <pre>
 * | ISIN         | Название   | Условие           | Уровень  | Частота    | Активен |
 * |--------------|------------|-------------------|----------|------------|---------|
 * | RU000A106347 | ОФЗ 26238  | YTM > 15%         | Критично | Ежедневно  | да      |
 * </pre>
 */
@Component
public class TriggerParser {

    private static final Logger log = LoggerFactory.getLogger(TriggerParser.class);

    /** Путь к файлу триггеров относительно корня vault. */
    static final String TRIGGERS_FILE = "Invest/Мониторинг триггеров.md";

    private final Path triggersPath;

    public TriggerParser(MonitorConfig config) {
        this.triggersPath = Path.of(config.vaultPath()).resolve(TRIGGERS_FILE);
    }

    // ── Публичный API ────────────────────────────────────────────────

    /** Парсит файл и возвращает все активные триггеры. */
    public List<Trigger> parseActive() {
        return parse().stream().filter(Trigger::active).toList();
    }

    /** Парсит файл и возвращает все триггеры (включая неактивные). */
    public List<Trigger> parse() {
        List<String> lines = readLines();
        return parseTable(lines);
    }

    // ── Внутренняя логика ────────────────────────────────────────────

    private List<String> readLines() {
        try {
            log.debug("Читаем файл триггеров: {}", triggersPath);
            return Files.readAllLines(triggersPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать файл триггеров: " + triggersPath, e);
        }
    }

    private List<Trigger> parseTable(List<String> lines) {
        List<Trigger> result = new ArrayList<>();

        // Ищем строку-заголовок таблицы (содержит '|' и ключевые слова колонок)
        int headerIdx = findHeaderLine(lines);
        if (headerIdx < 0) {
            log.warn("Таблица триггеров не найдена в файле: {}", triggersPath);
            return result;
        }

        Map<String, Integer> colIndex = parseHeader(lines.get(headerIdx));
        log.debug("Колонки таблицы: {}", colIndex);

        // Строка-разделитель (|---|---) идёт сразу после заголовка — пропускаем
        int dataStart = headerIdx + 2;

        for (int i = dataStart; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!line.startsWith("|") || isSeparator(line)) break; // конец таблицы

            try {
                Trigger t = parseRow(line, colIndex);
                result.add(t);
            } catch (Exception e) {
                log.warn("Пропускаем строку {}: {} — {}", i + 1, line, e.getMessage());
            }
        }

        log.info("Загружено триггеров: {} (всего), {} активных",
                result.size(), result.stream().filter(Trigger::active).count());
        return result;
    }

    private int findHeaderLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String lower = lines.get(i).toLowerCase();
            // Заголовок содержит хотя бы isin или название и символ |
            if (lower.contains("|") && (lower.contains("isin") || lower.contains("название"))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Из строки заголовка вида {@code | ISIN | Название | ... |}
     * строит карту: нормализованное_имя_колонки → индекс_ячейки (0-based).
     */
    private Map<String, Integer> parseHeader(String headerLine) {
        Map<String, Integer> map = new HashMap<>();
        String[] cells = splitRow(headerLine);
        for (int i = 0; i < cells.length; i++) {
            map.put(normalize(cells[i]), i);
        }
        return map;
    }

    private Trigger parseRow(String line, Map<String, Integer> colIndex) {
        String[] cells = splitRow(line);

        String isin      = cell(cells, colIndex, "isin");
        String name      = cell(cells, colIndex, "название");
        String condition = cell(cells, colIndex, "условие", "условие триггера", "триггер");
        String rawLevel  = cell(cells, colIndex, "уровень", "level");
        String rawFreq   = cell(cells, colIndex, "частота", "frequency");
        String rawActive = cell(cells, colIndex, "активен", "active", "статус");

        return new Trigger(
                isin,
                name,
                condition,
                TriggerLevel.of(rawLevel),
                TriggerFrequency.of(rawFreq),
                isActive(rawActive),
                parsePosition(cells, colIndex)
        );
    }

    // ── Вспомогательные методы ───────────────────────────────────────

    /** Делит строку таблицы по '|', обрезает пробелы, убирает пустые края. */
    private String[] splitRow(String line) {
        // "| a | b | c |"  →  ["a", "b", "c"]
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|"))   trimmed = trimmed.substring(0, trimmed.length() - 1);
        String[] parts = trimmed.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    /**
     * Возвращает значение ячейки по первому совпавшему имени колонки.
     * Имена — в порядке приоритета.
     */
    private String cell(String[] cells, Map<String, Integer> colIndex, String... names) {
        for (String name : names) {
            Integer idx = colIndex.get(name);
            if (idx != null && idx < cells.length) {
                return cells[idx];
            }
        }
        throw new IllegalArgumentException(
                "Колонка не найдена: " + String.join(" / ", names) + " в " + colIndex.keySet());
    }

    private String normalize(String s) {
        return s.trim().toLowerCase()
                // убираем Obsidian-форматирование **bold** и *italic*
                .replaceAll("[*_`]", "");
    }

    private boolean isSeparator(String line) {
        // строка вида |---|---| или |:---|:---:|
        return line.replaceAll("[|:\\-\\s]", "").isEmpty();
    }

    /**
     * Парсит опциональную колонку «Позиция» формата «3%/5%» (текущая доля / лимит).
     * Возвращает null если колонки нет или значение пустое/некорректное.
     */
    private Trigger.Position parsePosition(String[] cells, Map<String, Integer> colIndex) {
        Integer idx = colIndex.get("позиция");
        if (idx == null) idx = colIndex.get("position");
        if (idx == null || idx >= cells.length) return null;

        String raw = cells[idx].replaceAll("[%\\s]", "");
        if (raw.isEmpty() || raw.equals("-")) return null;

        String[] parts = raw.split("/");
        if (parts.length != 2) return null;

        try {
            return new Trigger.Position(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
        } catch (NumberFormatException e) {
            log.warn("Не удалось разобрать позицию: «{}»", cells[idx]);
            return null;
        }
    }

    private boolean isActive(String raw) {
        return switch (raw.trim().toLowerCase()) {
            case "да", "yes", "true", "✓", "+" -> true;
            default -> false;
        };
    }
}
