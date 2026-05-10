# Investment Monitor Agent

Агент мониторинга триггеров выхода из облигационных позиций.  
Читает Obsidian vault → анализирует через Claude AI → присылает сигналы в Telegram.

---

## Содержание

1. [Что делает агент](#что-делает-агент)
2. [Что нужно для запуска](#что-нужно-для-запуска)
3. [Откуда брать секреты](#откуда-брать-секреты)
4. [Как устроен vault](#как-устроен-vault)
5. [Полный цикл работы](#полный-цикл-работы)
6. [Сценарии запуска](#сценарии-запуска)
7. [Тонкая настройка](#тонкая-настройка)
8. [Как выглядит сообщение в Telegram](#как-выглядит-сообщение-в-telegram)
9. [Структура проекта](#структура-проекта)

---

## Что делает агент

1. Читает таблицу триггеров из файла Obsidian vault
2. Для каждого активного триггера запускает Claude (`claude-sonnet-4-6`) в режиме tool-use
3. Claude делает до 2 поисковых запросов через Tavily (YTM, новости, рейтинги)
4. Если условие триггера выполнено — отправляет сигнал 🚨 или ⚠️ в Telegram
5. Между проверками держит паузу (по умолчанию 60 сек) чтобы не превысить rate limit API
6. Запускается по расписанию: ежедневно / еженедельно / ежемесячно / ежеквартально

---

## Что нужно для запуска

### Программное обеспечение

| Что | Версия | Зачем |
|-----|--------|-------|
| JDK | 21+ | сборка и запуск |
| Maven | 3.9+ | сборка |

### Переменные окружения

| Переменная | Обязательна | Описание |
|------------|-------------|----------|
| `ANTHROPIC_API_KEY` | ✅ | Ключ Claude API (не путать с подпиской Claude Pro) |
| `TELEGRAM_BOT_TOKEN` | ✅ | Токен Telegram-бота |
| `TELEGRAM_CHAT_ID` | ✅ | ID чата/канала для сигналов |
| `TAVILY_API_KEY` | ✅ | Ключ поискового API |
| `VAULT_PATH` | ✅ | Путь к папке vault, внутри которой есть `Invest/` |
| `RUN_MODE` | — | Режим запуска (см. [Сценарии](#сценарии-запуска)), по умолчанию `daily` |

### Минимальный `.env` для локального запуска

```bash
ANTHROPIC_API_KEY=sk-ant-...
TELEGRAM_BOT_TOKEN=7123456789:AAF...
TELEGRAM_CHAT_ID=-1001234567890
TAVILY_API_KEY=tvly-...
VAULT_PATH=/Users/you/Obsidian/work_brain/Invest
RUN_MODE=monthly
```

---

## Откуда брать секреты

### Anthropic API Key (`ANTHROPIC_API_KEY`)

> **Важно:** подписка Claude Pro (claude.ai) и API — это разные продукты с отдельной оплатой.  
> Pro даёт доступ к веб-интерфейсу и Claude Code, но не к программному API.

1. Зайди на [console.anthropic.com](https://console.anthropic.com)
2. **API Keys** → **Create Key** → скопируй `sk-ant-...`
3. Пополни баланс — минимум $5. Один прогон (10–15 триггеров) стоит ~$0.05–0.20

**Лимиты нового аккаунта:** 30 000 input-токенов в минуту. Именно поэтому агент делает паузу между запросами и ограничивает число поисков до 2 на триггер.

Используемая модель: `claude-sonnet-4-6` — задаётся в `application.yml`.

### Telegram Bot Token (`TELEGRAM_BOT_TOKEN`)

1. Напиши [@BotFather](https://t.me/BotFather) → `/newbot`
2. Задай имя и username
3. Получи токен вида `7123456789:AAFxxx...`

### Telegram Chat ID (`TELEGRAM_CHAT_ID`)

**Для личного чата:**
1. Напиши своему боту любое сообщение
2. Открой в браузере: `https://api.telegram.org/bot<ТОКЕН>/getUpdates`
3. Найди `"chat":{"id":123456789}` — это твой ID

**Для канала:**
- Добавь бота в канал как администратора
- ID начинается с `-100...`
- Проще всего узнать через [@getidsbot](https://t.me/getidsbot)

### Tavily API Key (`TAVILY_API_KEY`)

1. Зарегистрируйся на [tavily.com](https://tavily.com)
2. Скопируй API Key (`tvly-...`) в дашборде
3. Бесплатный план: 1 000 запросов/месяц — для 10–15 триггеров в день хватит

---

## Как устроен vault

Агент ожидает что внутри `VAULT_PATH` есть папка `Invest/` с файлом триггеров:

```
{VAULT_PATH}/
└── Invest/
    └── Мониторинг триггеров.md   ← главный файл
```

**Пример:** если vault лежит в `/Users/you/Obsidian/work_brain/Invest`, то:
```bash
VAULT_PATH=/Users/you/Obsidian/work_brain/Invest
# агент прочитает: /Users/you/Obsidian/work_brain/Invest/Invest/Мониторинг триггеров.md
```

### Формат файла `Мониторинг триггеров.md`

Файл должен содержать Markdown-таблицу. Порядок колонок произвольный — парсер определяет их по заголовку.

```markdown
| ISIN         | Название       | Условие                          | Уровень | Частота       | Активен |
|--------------|----------------|----------------------------------|---------|---------------|---------|
| RU000A10ES32 | ОДК 001P-01    | Снижение рейтинга ниже A+        | 🚨      | Ежемесячно    | да      |
| RU000A10ES32 | ОДК 001P-01    | Просрочка купона — любая задержка | 🚨     | Ежедневно     | да      |
| RU000A108000 | ВТБ Капитал БО | Новости о реструктуризации       | ⚠️      | Ежеквартально | нет     |
```

### Допустимые значения

**Уровень:**
| В таблице | Значение |
|-----------|----------|
| `🚨` / `Критично` / `critical` | 🚨 КРИТИЧНО — немедленное действие |
| `⚠️` / `Внимание` / `warning`  | ⚠️ ВНИМАНИЕ — повышенный мониторинг |

**Частота:**
| В таблице | Расписание |
|-----------|------------|
| `Ежедневно` / `daily` | Каждый день 09:00 МСК |
| `Еженедельно` / `weekly` | Понедельник 09:00 МСК |
| `Ежемесячно` / `monthly` | 1-е число 09:00 МСК |
| `Ежеквартально` / `quarterly` | 1 янв / апр / июл / окт 09:00 МСК |

**Активен:** `да` / `yes` / `true` / `✓` / `+` — всё остальное отключает триггер без удаления строки.

### Синхронизация через Obsidian Git

Агент читает файлы напрямую с диска. Если vault синхронизируется через Obsidian Git плагин — просто укажи `VAULT_PATH` на локальную копию, остальное делает плагин.

---

## Полный цикл работы

```
Obsidian vault (MD-файл)
        │
        ▼
  TriggerParser          Читает таблицу, парсит строки в Trigger-records
        │                Фильтрует: active=true + нужная частота
        ▼
  MonitorAgent           Для каждого триггера (с паузой 60 сек между ними):
        │
        ├─► Claude API   Системный промпт из application.yml:
        │   (tool-use)   роль, процесс, формат JSON-ответа
        │       │
        │       ▼
        │   AgentTools   Claude вызывает webSearch(query) — до 2 раз
        │   webSearch ──► Tavily API (финансовые источники)
        │       │         cbr.ru, moex.com, rusbonds.ru,
        │       │         smartlab.ru, interfax.ru...
        │       ▼
        │   Claude       Анализирует результаты, возвращает JSON:
        │                { fired, summary, details, confidence }
        │
        ▼
  MonitorResult          fired=true → сигнал, fired=false → норма
        │
        ▼
  TelegramNotifier       Только сработавшие (fired=true) → sendMessage
        │
        ▼
  Telegram               🚨 или ⚠️ сообщение в чат
```

Claude делает **не более 2 поисковых запросов** на триггер — ограничение задано в системном промпте в `application.yml` и позволяет укладываться в лимит 30 000 токенов/мин.

---

## Сценарии запуска

### Локально (разовый прогон)

```bash
# Сборка
mvn clean package -DskipTests

# Запуск — сразу проверит monthly-триггеры
ANTHROPIC_API_KEY=sk-ant-... \
TELEGRAM_BOT_TOKEN=7123456789:AAF... \
TELEGRAM_CHAT_ID=123456789 \
TAVILY_API_KEY=tvly-... \
VAULT_PATH=/Users/you/Obsidian/work_brain/Invest \
RUN_MODE=monthly \
java --enable-preview -jar target/alert-agent-0.1.0-SNAPSHOT.jar
```

### По расписанию (production)

```bash
RUN_MODE=scheduled java --enable-preview -jar alert-agent.jar
```

При `RUN_MODE=scheduled` прогон при старте **не выполняется** — агент ждёт расписания.

### Docker

```dockerfile
FROM eclipse-temurin:21-jre
COPY target/alert-agent-0.1.0-SNAPSHOT.jar /app/agent.jar
ENTRYPOINT ["java", "--enable-preview", "-jar", "/app/agent.jar"]
```

```bash
docker build -t alert-agent .
docker run \
  -e ANTHROPIC_API_KEY=... \
  -e TELEGRAM_BOT_TOKEN=... \
  -e TELEGRAM_CHAT_ID=... \
  -e TAVILY_API_KEY=... \
  -e VAULT_PATH=/vault \
  -e RUN_MODE=scheduled \
  -v /path/to/vault:/vault \
  alert-agent
```

### Все режимы `RUN_MODE`

| Значение | Что происходит при старте | Расписание активно |
|----------|---------------------------|--------------------|
| `daily` | Сразу запускает DAILY-триггеры | ✅ |
| `weekly` | Сразу запускает WEEKLY-триггеры | ✅ |
| `monthly` | Сразу запускает MONTHLY-триггеры | ✅ |
| `quarterly` | Сразу запускает QUARTERLY-триггеры | ✅ |
| `scheduled` | Ничего не делает, ждёт расписания | ✅ |

---

## Тонкая настройка

Все параметры можно переопределить без пересборки — через `application.yml` или аргументы командной строки.

### Ключевые параметры `application.yml`

```yaml
monitor:
  delay-between-checks-sec: 60   # пауза между триггерами (сек)
  search:
    max-results: 2               # результатов Tavily на один поиск
  prompt:
    system: |                    # системный промпт агента — редактируется без пересборки
      Ты — инвестиционный аналитик...

spring:
  ai:
    anthropic:
      chat:
        options:
          model: claude-sonnet-4-6
          max-tokens: 1024       # максимум токенов в ответе Claude
```

### Переопределение через аргументы запуска

```bash
# Ускоренный тест — пауза 10 сек вместо 60
java --enable-preview -jar alert-agent.jar \
  --monitor.delay-between-checks-sec=10

# Другая модель
java --enable-preview -jar alert-agent.jar \
  --spring.ai.anthropic.chat.options.model=claude-haiku-4-5
```

### Про rate limit

Новый Anthropic API аккаунт имеет лимит **30 000 input-токенов в минуту**. Один вызов агента потребляет ~6 000–8 000 токенов (с учётом tool-use цикла). Поэтому:

- `delay-between-checks-sec: 60` — одна проверка в минуту
- `max-results: 2` — короткие ответы Tavily
- `max-tokens: 1024` — короткий ответ Claude
- Промпт ограничивает Claude до 2 поисков

При повышении тира аккаунта (`Tier 2` — после пополнения на $40+) лимит вырастает до 80 000 токенов/мин и паузу можно снизить до 10–15 сек.

---

## Как выглядит сообщение в Telegram

### 🚨 Критический сигнал

```
🚨 КРИТИЧНО — [RU000A10ES32] ОДК 001P-01
Рейтинг НРА снижен до A (прогноз негативный) 07.05.2026
По данным ra-national.ru рейтинг эмитента пересмотрен
с A+ до A, прогноз изменён на негативный. Причина —
рост долговой нагрузки по итогам 2025 года.
```

### ⚠️ Предупреждение

```
⚠️ ВНИМАНИЕ — [RU000A105A95] Газпром БО-26
АКРА понизило рейтинг с AA до AA- (прогноз негативный)
Агентство АКРА 07.05.2026 пересмотрело рейтинг в сторону
понижения. Причина — рост долговой нагрузки и снижение
экспортной выручки. Источник: acra-ratings.ru
```

### Когда сигнала нет

Если триггер не сработал — сообщение в Telegram **не отправляется**. Тишина = норма.

---

## Структура проекта

```
src/main/java/com/invest/monitor/
├── AlertAgentApplication.java     — точка входа Spring Boot
├── agent/
│   ├── MonitorAgent.java          — tool-use loop, пауза между запросами
│   └── AgentTools.java            — @Tool webSearch → Tavily API
├── config/
│   └── MonitorConfig.java         — @ConfigurationProperties (monitor.*)
├── domain/
│   ├── Trigger.java               — record: одна строка таблицы
│   ├── TriggerLevel.java          — sealed interface: Critical | Warning
│   ├── TriggerFrequency.java      — enum: DAILY | WEEKLY | MONTHLY | QUARTERLY
│   └── MonitorResult.java         — record: результат проверки триггера
├── parser/
│   └── TriggerParser.java         — читает MD-таблицу из vault
├── scheduler/
│   └── MonitorScheduler.java      — @Scheduled расписание + startup run
└── telegram/
    └── TelegramNotifier.java      — RestClient → Telegram Bot API

src/main/resources/
└── application.yml                — вся конфигурация включая системный промпт

src/test/
├── java/com/invest/monitor/
│   ├── domain/
│   │   ├── TriggerLevelTest.java
│   │   └── MonitorResultTest.java
│   ├── parser/
│   │   └── TriggerParserTest.java
│   └── scheduler/
│       └── MonitorSchedulerTest.java
└── resources/
    └── vault/Invest/
        └── Мониторинг триггеров.md  — тестовый fixture
```
