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
6. [Когда триггер берётся в работу](#когда-триггер-берётся-в-работу)
7. [Сценарии запуска](#сценарии-запуска)
8. [Тонкая настройка](#тонкая-настройка)
9. [Как выглядит сообщение в Telegram](#как-выглядит-сообщение-в-telegram)
10. [Структура проекта](#структура-проекта)

---

## Что делает агент

1. Читает таблицу триггеров из файла Obsidian vault
2. Проверяет по базе данных, не был ли триггер уже проверен в текущем периоде
3. Для каждого нового триггера запускает Claude (`claude-sonnet-4-6`) в режиме tool-use
4. Claude делает до 2 поисковых запросов через Tavily (YTM, новости, рейтинги)
5. Если условие триггера выполнено — отправляет сигнал 🚨 или ⚠️ в Telegram
6. Сохраняет дату проверки (и срабатывания) в PostgreSQL
7. Между проверками держит паузу (по умолчанию 60 сек), чтобы не превысить rate limit API
8. Запускается по расписанию: ежедневно / еженедельно / ежемесячно / ежеквартально

---

## Что нужно для запуска

### Docker (рекомендуется)

Всё, что нужно — Docker и docker-compose. PostgreSQL поднимается автоматически в соседнем контейнере.

```bash
cp .env.example .env   # заполни .env своими ключами и путём к vault
docker compose up --build
```

### Локально (без Docker)

| Что | Версия | Зачем |
|-----|--------|-------|
| JDK | 21+ | сборка и запуск |
| Maven | 3.9+ | сборка |
| PostgreSQL | 14+ | хранение состояния триггеров |

### Переменные окружения

| Переменная | Обязательна | Описание |
|------------|-------------|----------|
| `ANTHROPIC_API_KEY` | ✅ | Ключ Claude API (не путать с подпиской Claude Pro) |
| `TELEGRAM_BOT_TOKEN` | ✅ | Токен Telegram-бота |
| `TELEGRAM_CHAT_ID` | ✅ | ID чата/канала для сигналов |
| `TAVILY_API_KEY` | ✅ | Ключ поискового API |
| `VAULT_PATH` | ✅ | Путь к папке vault внутри контейнера (обычно `/vault`) |
| `HOST_VAULT_PATH` | ✅ (Docker) | Путь к vault на хосте — монтируется в контейнер |
| `POSTGRES_PASSWORD` | ✅ (Docker) | Пароль PostgreSQL |
| `SPRING_DATASOURCE_URL` | — | URL БД, по умолчанию `jdbc:postgresql://localhost:5432/alertagent` |
| `SPRING_DATASOURCE_USERNAME` | — | Пользователь БД, по умолчанию `alertagent` |
| `SPRING_DATASOURCE_PASSWORD` | — | Пароль БД |
| `RUN_MODE` | — | Режим запуска (см. [Сценарии](#сценарии-запуска)), по умолчанию `daily` |
| `MONITOR_TIMEZONE` | — | Часовой пояс расписания (IANA), по умолчанию `Europe/Moscow` |

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
| `Ежедневно` / `daily` | Каждый день 09:00 по таймзоне |
| `Еженедельно` / `weekly` | Понедельник 09:00 |
| `Ежемесячно` / `monthly` | 1-е число 09:00 |
| `Ежеквартально` / `quarterly` | 1 янв / апр / июл / окт 09:00 |

**Активен:** `да` / `yes` / `true` / `✓` / `+` — всё остальное отключает триггер без удаления строки.

### Синхронизация через Obsidian Git

Агент читает файлы напрямую с диска. Если vault синхронизируется через Obsidian Git плагин — просто укажи `HOST_VAULT_PATH` на локальную копию, остальное делает плагин.

---

## Полный цикл работы

```
Obsidian vault (MD-файл)
        │
        ▼
  TriggerParser          Читает таблицу, парсит строки в Trigger-records
        │                Фильтрует: active=true + нужная частота
        ▼
  TriggerStateService    Проверяет по PostgreSQL: уже обработан в этом периоде?
        │                Да → пропустить. Нет → передать агенту
        ▼
  MonitorAgent           Для каждого нового триггера (с паузой 60 сек между ними):
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
  TriggerStateService    Сохраняет last_checked_at (и last_fired_at если сработал)
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

## Когда триггер берётся в работу

Перед каждым запуском агент проверяет таблицу `trigger_state` в PostgreSQL. Триггер **пропускается** (не отправляется Claude), если он уже был проверен в текущем периоде.

### Определение «текущего периода»

Для каждой частоты период определяется по-своему:

| Частота | Триггер пропускается, если `last_checked_at`... |
|---------|------------------------------------------------|
| `DAILY` | равна сегодняшней дате |
| `WEEKLY` | не раньше понедельника текущей недели |
| `MONTHLY` | совпадает по году и месяцу с сегодняшней датой |
| `QUARTERLY` | совпадает по году и кварталу с сегодняшней датой |

**Квартал** определяется как `(месяц - 1) / 3 + 1`: январь–март = Q1, апрель–июнь = Q2 и т. д.

### Алгоритм шаг за шагом

```
Для каждого активного триггера нужной частоты:

  1. Ищем запись (isin, frequency) в таблице trigger_state.

  2. Запись не найдена
       → триггер никогда не проверялся
       → берём в работу

  3. Запись найдена, last_checked_at НЕ входит в текущий период
       → предыдущая проверка была в прошлом цикле
       → берём в работу

  4. Запись найдена, last_checked_at входит в текущий период
       → триггер уже проверен в этом цикле
       → пропускаем (0 токенов)

После успешной проверки агентом (один вызов Claude = один ISIN):
  → для каждого триггера INSERT в trigger_check_history
  → UPDATE trigger_state: last_checked_at = сегодня,
                           last_fired_at = сегодня (если fired),
                           last_summary / last_details / last_confidence
```

### Почему это важно

Без персистентного состояния каждый перезапуск приложения заново прогоняет все триггеры через Claude. При `RUN_MODE=scheduled` это не проблема (крон запускается раз в день/неделю), но при сбое контейнера и его перезапуске в тот же день все ежедневные триггеры будут проверены повторно — лишние расходы токенов и дублирующиеся Telegram-сообщения.

PostgreSQL устраняет эту проблему: даже после 10 перезапусков в один день каждый триггер проверяется ровно один раз за период.

### Схема таблиц

```sql
-- Последнее состояние по каждому ISIN + частота
CREATE TABLE trigger_state (
    isin            VARCHAR(12) NOT NULL,
    frequency       VARCHAR(20) NOT NULL,
    last_checked_at DATE        NOT NULL,
    last_fired_at   DATE,
    last_summary    TEXT,
    last_details    TEXT,
    last_confidence VARCHAR(10),
    PRIMARY KEY (isin, frequency)
);

-- Полная история каждой проверки
CREATE TABLE trigger_check_history (
    id             BIGSERIAL    PRIMARY KEY,
    isin           VARCHAR(12)  NOT NULL,
    name           VARCHAR(100) NOT NULL,
    condition_text TEXT         NOT NULL,
    checked_at     TIMESTAMP    NOT NULL,
    run_mode       VARCHAR(20)  NOT NULL,
    fired          BOOLEAN      NOT NULL,
    summary        TEXT,
    details        TEXT,
    confidence     VARCHAR(10)
);
```

Миграции применяются автоматически при старте через Flyway (V1, V2).

### Просмотр истории

```sql
-- Все срабатывания за последние 30 дней
SELECT checked_at, name, condition_text, summary, confidence
FROM trigger_check_history
WHERE fired = true
  AND checked_at >= now() - interval '30 days'
ORDER BY checked_at DESC;

-- История конкретной бумаги
SELECT checked_at, condition_text, fired, summary, confidence
FROM trigger_check_history
WHERE isin = 'RU000A10ES32'
ORDER BY checked_at DESC;

-- Последнее состояние всех триггеров
SELECT isin, frequency, last_checked_at, last_fired_at,
       last_summary, last_confidence
FROM trigger_state
ORDER BY last_fired_at DESC NULLS LAST;
```

---

## Сценарии запуска

### Docker Compose (рекомендуется)

```bash
# 1. Скопируй шаблон и заполни своими данными
cp .env.example .env

# 2. Запуск — PostgreSQL поднимается автоматически
docker compose up --build

# 3. Только пересобрать образ приложения (без остановки БД)
docker compose up --build app
```

PostgreSQL хранит данные в именованном volume `pg_data` — они сохраняются между перезапусками.

### Локально (разовый прогон)

```bash
# PostgreSQL должен быть запущен отдельно
mvn clean package -DskipTests

ANTHROPIC_API_KEY=sk-ant-... \
TELEGRAM_BOT_TOKEN=7123456789:AAF... \
TELEGRAM_CHAT_ID=123456789 \
TAVILY_API_KEY=tvly-... \
VAULT_PATH=/Users/you/Obsidian/Invest \
RUN_MODE=monthly \
java --enable-preview -jar target/alert-agent-0.1.0-SNAPSHOT.jar
```

### По расписанию (production)

```bash
RUN_MODE=scheduled java --enable-preview -jar alert-agent.jar
```

При `RUN_MODE=scheduled` прогон при старте **не выполняется** — агент ждёт расписания.

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

Все параметры можно переопределить без пересборки — через `application.yml`, переменные окружения или аргументы командной строки.

### Ключевые параметры `application.yml`

```yaml
monitor:
  timezone: Europe/Moscow        # часовой пояс расписания (IANA)
  delay-between-checks-sec: 60   # пауза между триггерами (сек)
  search:
    max-results: 2               # результатов Tavily на один поиск
    include-domains:             # источники для поиска
      - cbr.ru
      - moex.com
      - rusbonds.ru
    tavily-url: https://api.tavily.com          # URL Tavily API
  telegram:
    api-base-url: https://api.telegram.org/bot  # URL Telegram Bot API
  prompt:
    system: |                    # системный промпт — редактируется без пересборки
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

# Другой часовой пояс
java --enable-preview -jar alert-agent.jar \
  --monitor.timezone=Asia/Almaty

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
├── state/
│   ├── TriggerState.java          — JPA-сущность: (isin, frequency) → даты проверки
│   ├── TriggerStateId.java        — составной ключ для JPA
│   ├── TriggerStateRepository.java — Spring Data репозиторий
│   └── TriggerStateService.java   — логика дедупликации по периодам
└── telegram/
    └── TelegramNotifier.java      — RestClient → Telegram Bot API

src/main/resources/
├── application.yml                — вся конфигурация включая системный промпт
└── db/migration/
    └── V1__create_trigger_state.sql — Flyway: создание таблицы trigger_state

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

Dockerfile                         — двухэтапная сборка: Maven → JRE alpine
docker-compose.yml                 — app + PostgreSQL 16
.env.example                       — шаблон переменных окружения
```
