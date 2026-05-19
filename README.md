# Investment Monitor Agent

Агент мониторинга триггеров выхода из облигационных позиций.  
Читает Obsidian vault → анализирует через **Claude AI** или **Gemini** → присылает сигналы в Telegram.

---

## Содержание

1. [Что делает агент](#что-делает-агент)
2. [Что нужно для запуска](#что-нужно-для-запуска)
3. [Откуда брать секреты](#откуда-брать-секреты)
4. [Как устроен vault](#как-устроен-vault)
5. [Полный цикл работы](#полный-цикл-работы)
6. [Когда триггер берётся в работу](#когда-триггер-берётся-в-работу)
7. [Устойчивость к ошибкам API](#устойчивость-к-ошибкам-api)
8. [Запуск в Docker — пошаговая инструкция](#запуск-в-docker--пошаговая-инструкция)
9. [Режим разработки (dev)](#режим-разработки-dev)
10. [Другие сценарии запуска](#другие-сценарии-запуска)
11. [Тонкая настройка](#тонкая-настройка)
12. [Как выглядит сообщение в Telegram](#как-выглядит-сообщение-в-telegram)
13. [Структура проекта](#структура-проекта)

---

## Что делает агент

1. Читает таблицу триггеров из файла Obsidian vault
2. Проверяет по базе данных, не был ли ISIN уже проверен в текущем периоде
3. Группирует оставшиеся триггеры по ISIN — **один вызов LLM на одну бумагу**
4. LLM собирает свежие данные и проверяет каждое условие:
   - **Claude (Anthropic)** — tool-use: вызывает `webSearch` через Tavily до 3 раз на ISIN
   - **Gemini (Google)** — нативный Grounding через Google Search, Tavily не нужен
5. Ответ содержит для каждого триггера: `fired`, `summary`, `details`, `confidence`, `action`
6. Если триггер сработал — отправляет сигнал 🚨 или ⚠️ в Telegram (с указанием действия)
7. Сохраняет результат и полную историю проверок в PostgreSQL
8. Между бумагами держит паузу (по умолчанию 60 сек) для соблюдения rate limit
9. При ошибке API (503/429) — моментальный retry (30→60→120 сек), затем отложенные повторы через БД
10. Запускается по расписанию: ежедневно / еженедельно / ежемесячно / ежеквартально

---

## Что нужно для запуска

### Docker (рекомендуется)

Нужен только **Docker Desktop**. PostgreSQL поднимается автоматически рядом с приложением.  
Пошаговая инструкция — в разделе [Запуск в Docker](#запуск-в-docker--пошаговая-инструкция).

### Локально (без Docker)

| Что | Версия | Зачем |
|-----|--------|-------|
| JDK | 21+ | сборка и запуск |
| Maven | 3.9+ | сборка |
| PostgreSQL | 14+ | хранение состояния триггеров |

### Переменные окружения

| Переменная | Обязательна | Описание |
|------------|-------------|----------|
| `AGENT_PROVIDER` | — | Провайдер LLM: `anthropic` (по умолчанию) или `gemini` |
| `ANTHROPIC_API_KEY` | ✅ при `anthropic` | Ключ Claude API |
| `GOOGLE_API_KEY` | ✅ при `gemini` | Google AI Studio API Key (основной) |
| `GOOGLE_API_KEYS` | — | Дополнительные ключи через запятую — включает режим «один вызов на триггер» |
| `TELEGRAM_BOT_TOKEN` | ✅ | Токен Telegram-бота |
| `TELEGRAM_CHAT_ID` | ✅ | ID чата/канала для сигналов |
| `TAVILY_API_KEY` | ✅ при `anthropic` | Ключ поискового API (при gemini не нужен) |
| `HOST_VAULT_PATH` | ✅ (Docker) | Путь к vault на хосте — монтируется как `/vault` |
| `POSTGRES_PASSWORD` | ✅ (Docker) | Пароль PostgreSQL |
| `VAULT_PATH` | — | Путь к vault внутри контейнера, по умолчанию `/vault` |
| `SPRING_DATASOURCE_URL` | — | URL БД, по умолчанию `jdbc:postgresql://localhost:5432/alertagent` |
| `SPRING_DATASOURCE_USERNAME` | — | Пользователь БД, по умолчанию `alertagent` |
| `SPRING_DATASOURCE_PASSWORD` | — | Пароль БД |
| `RUN_MODE` | — | Режим запуска (см. [таблицу](#все-режимы-run_mode)), по умолчанию `daily` |
| `GEMINI_MODEL` | — | Модель Gemini, по умолчанию `gemini-2.5-flash` |
| `MONITOR_HISTORY_SIZE` | — | Глубина истории проверок передаваемой агенту, по умолчанию `3` |
| `MONITOR_TIMEZONE` | — | Часовой пояс расписания (IANA), по умолчанию `Europe/Moscow` |
| `SPRING_PROFILES_ACTIVE` | — | `dev` — включает stub-режим и SQL-логи |

---

## Откуда брать секреты

### Anthropic API Key (`ANTHROPIC_API_KEY`)

> Нужен только при `AGENT_PROVIDER=anthropic` (значение по умолчанию).  
> **Важно:** подписка Claude Pro (claude.ai) и API — это разные продукты с отдельной оплатой.

1. Зайди на [console.anthropic.com](https://console.anthropic.com)
2. **API Keys** → **Create Key** → скопируй `sk-ant-...`
3. Пополни баланс — минимум $5. Один прогон (10 уникальных ISIN) стоит ~$0.10–0.30

**Лимиты нового аккаунта:** 30 000 input-токенов в минуту. Агент делает паузу между бумагами и ограничивает поиск до 3 запросов на ISIN.

Используемая модель: `claude-sonnet-4-6` — задаётся в `application.yml`.

### Google AI Studio API Key (`GOOGLE_API_KEY`)

> Нужен только при `AGENT_PROVIDER=gemini`.  
> Gemini с Grounding заменяет и Claude, и Tavily — при переходе оба предыдущих ключа необязательны.

1. Зайди на [aistudio.google.com](https://aistudio.google.com)
2. **Get API Key** → **Create API key** → скопируй `AIza...`
3. Бесплатный тариф Gemini 2.5 Flash: 500 запросов/день, 10 запросов/минуту

**Grounding с Google Search:** модель сама делает поисковые запросы через Google, без внешних API. Источники — весь публичный интернет, включая acra-ratings.ru, moex.com, interfax.ru и т. д.

Используемая модель: `gemini-2.5-flash` по умолчанию — переключается через переменную `GEMINI_MODEL` без пересборки.

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

> Нужен только при `AGENT_PROVIDER=anthropic`.

1. Зарегистрируйся на [tavily.com](https://tavily.com)
2. Скопируй API Key (`tvly-...`) в дашборде
3. Бесплатный план: 1 000 запросов/месяц — для 10 ISIN ежедневно хватит с запасом

---

## Как устроен vault

Агент ожидает что внутри `VAULT_PATH` есть папка `Invest/` с файлом триггеров:

```
{VAULT_PATH}/
└── Invest/
    └── Мониторинг триггеров.md   ← главный файл
```

### Формат файла `Мониторинг триггеров.md`

Файл должен содержать Markdown-таблицу. Порядок колонок произвольный — парсер определяет их по заголовку. Колонка **Позиция** опциональна.

```markdown
| ISIN         | Название       | Условие                          | Уровень | Частота       | Активен | Позиция |
|--------------|----------------|----------------------------------|---------|---------------|---------|---------|
| RU000A10ES32 | ОДК 001P-01    | Снижение рейтинга ниже A+        | 🚨      | Ежемесячно    | да      | 18%/25% |
| RU000A10ES32 | ОДК 001P-01    | Просрочка купона — любая задержка | 🚨     | Ежедневно     | да      | 18%/25% |
| RU000A108000 | ВТБ Капитал БО | Новости о реструктуризации       | ⚠️      | Ежеквартально | нет     |         |
```

> Все триггеры одного ISIN должны иметь одинаковое значение колонки Позиция — агент берёт её из первого триггера группы.

### Колонка Позиция

Формат: `текущая%/лимит%` — текущая доля в портфеле и максимально допустимая.

| Значение | currentSharePct | limitPct | Что получит агент |
|----------|-----------------|----------|-------------------|
| `18%/25%` | 18.0 | 25.0 | `"position": {"current_share_pct": 18.0, "limit_pct": 25.0}` |
| `0%/20%` | 0.0 | 20.0 | позиция ещё не открыта, лимит задан |
| `3%/` | 3.0 | null | доля без ограничения |
| *(пусто)* | null | null | поле position не передаётся |

Агент использует позицию при формировании `action`:
- `current >= limit` → «сократить до лимита N%»
- `current >= limit × 0.8` → «не докупать, доля близка к лимиту»
- `fired=true` + 🚨 → «выход, высвобождается ~N% портфеля»

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
        │                Опционально читает колонку Позиция (currentSharePct / limitPct)
        ▼
  TriggerStateService    Проверяет по PostgreSQL: ISIN уже обработан в этом периоде?
        │                Да → пропустить весь ISIN. Нет → передать агенту
        ▼
  MonitorAgent           Группирует триггеры по ISIN, затем:
        │
        ├─► ANTHROPIC    Один запрос на ISIN — все триггеры бумаги в одном промпте
        │   Claude       Системный промпт + список условий + previous_results из БД
        │   (tool-use)       │
        │                    ▼
        │               AgentTools.webSearch → Tavily API (до 3 раз на ISIN)
        │               acra-ratings.ru, moex.com, cbr.ru, interfax.ru...
        │               Пауза 60 сек между ISIN
        │
        ├─► GEMINI       Режим зависит от числа API-ключей в конфиге:
        │   Gemini 2.5   Grounding: Google Search встроен в модель (Tavily не нужен)
        │   Flash        │
        │                ├─ 1 ключ  → один запрос на ISIN (все триггеры вместе)
        │                │            3 поиска делятся между всеми условиями бумаги
        │                │            Пауза 60 сек между ISIN
        │                │
        │                └─ N ключей → один запрос на триггер (round-robin по ключам)
        │                              3 поиска посвящены одному условию → макс. качество
        │                              Пауза 2 сек между вызовами (нет паузы между ISIN)
        │
        ▼   (оба провайдера возвращают одинаковый формат)
        │   JSON-массив: [{ condition, fired, summary, details, confidence, action }, ...]
        │
        ▼
  TriggerStateService    INSERT в trigger_check_history (каждый триггер + action)
        │                UPDATE trigger_state (last_checked_at, last_fired_at, last_summary...)
        │                Не пишется при ошибке агента → ISIN остаётся в очереди retry
        ▼
  MonitorResult          fired=true → сигнал, error=true → retry queue, false → норма
        │
        ▼
  TelegramNotifier       Только сработавшие (fired=true) → sendMessage
        │
        ▼
  Telegram               🚨 или ⚠️ сообщение с summary, details и 🎯 action
```

### Режимы Gemini подробнее

#### Режим «один ключ» — батчевый

Все триггеры одной бумаги объединяются в один промпт. Модель получает массив условий и делает **не более 3 поисковых запросов на всю бумагу**, стараясь покрыть максимум условий за один запрос.

```
ISIN RU000A10DA74 (5 триггеров)
  [рейтинг, купон, цена, иски, новости] → 1 вызов → 3 поиска → 5 ответов
  sleep 60 сек
ISIN RU000A10CMQ5 (3 триггера)
  [рейтинг, купон, новости] → 1 вызов → 3 поиска → 3 ответа
```

Поисковый бюджет делится: при 5 условиях на каждое приходится в среднем 0.6 поиска.

#### Режим «несколько ключей» — per-trigger

Каждый триггер проверяется отдельным вызовом. Ключи чередуются через `AtomicInteger` — сквозной счётчик идёт через все ISIN и все триггеры.

```
ISIN RU000A10DA74 (5 триггеров)
  триггер 1 «рейтинг»  → ключ[0] → 3 поиска → 1 ответ
  sleep 2 сек
  триггер 2 «купон»    → ключ[1] → 3 поиска → 1 ответ
  sleep 2 сек
  триггер 3 «цена»     → ключ[2] → 3 поиска → 1 ответ
  sleep 2 сек
  триггер 4 «иски»     → ключ[3] → 3 поиска → 1 ответ
  sleep 2 сек
  триггер 5 «новости»  → ключ[4] → 3 поиска → 1 ответ

ISIN RU000A10CMQ5 (3 триггера)
  триггер 1 → ключ[0]   ← счётчик продолжается
  ...
```

Каждый ключ получает 1 вызов раз в `N × 2` секунд. При 5 ключах — раз в 10 сек, что комфортно в пределах лимита 10 запросов/мин бесплатного тарифа.

| | 1 ключ | 5 ключей |
|---|---|---|
| Вызовов LLM на 10 ISIN × 5 триг. | 10 | 50 |
| Поисков на триггер | ~0.6 | 3 |
| Время прогона | ~10 мин | ~2 мин |
| Нагрузка на ключ | 10 вызовов | 10 вызовов |

---

## Когда триггер берётся в работу

Перед каждым запуском агент проверяет таблицу `trigger_state` в PostgreSQL. ISIN **пропускается целиком** (все его триггеры, 0 вызовов LLM), если он уже был проверен в текущем периоде.

### Определение «текущего периода»

| Частота | ISIN пропускается, если `last_checked_at`... |
|---------|---------------------------------------------|
| `DAILY` | равна сегодняшней дате |
| `WEEKLY` | не раньше понедельника текущей недели |
| `MONTHLY` | совпадает по году и месяцу с сегодняшней датой |
| `QUARTERLY` | совпадает по году и кварталу с сегодняшней датой |

**Квартал** определяется как `(месяц - 1) / 3 + 1`: январь–март = Q1, апрель–июнь = Q2 и т. д.

### Алгоритм шаг за шагом

```
Для каждого активного ISIN нужной частоты:

  1. Ищем запись (isin, frequency) в таблице trigger_state.

  2. Запись не найдена
       → ISIN никогда не проверялся
       → берём в работу (INSERT после проверки)

  3. Запись найдена, last_checked_at НЕ входит в текущий период
       → предыдущая проверка была в прошлом цикле
       → берём в работу (UPDATE после проверки)

  4. Запись найдена, last_checked_at входит в текущий период
       → ISIN уже проверен в этом цикле
       → пропускаем (0 токенов, 0 вызовов LLM)

После успешной проверки одного ISIN:
  → для каждого триггера: INSERT в trigger_check_history (включая action)
  → UPDATE trigger_state: last_checked_at = сегодня
                           last_fired_at = сегодня (если хотя бы один fired)
                           last_summary / last_details / last_confidence
```

### Почему это важно

Без персистентного состояния каждый перезапуск приложения заново прогоняет все триггеры через LLM. При `RUN_MODE=scheduled` это не проблема (крон запускается раз в день/неделю), но при сбое контейнера в тот же день — дублирующиеся Telegram-сообщения и лишние расходы.

PostgreSQL устраняет эту проблему: даже после 10 перезапусков в один день каждый ISIN проверяется ровно один раз за период.

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
    confidence     VARCHAR(10),
    action         VARCHAR(200)
);

-- Очередь отложенных повторных проверок (при ошибках API)
CREATE TABLE check_retry_queue (
    id           BIGSERIAL   PRIMARY KEY,
    isin         VARCHAR(12) NOT NULL,
    frequency    VARCHAR(20) NOT NULL,
    label        VARCHAR(20) NOT NULL,
    attempt      INT         NOT NULL DEFAULT 1,
    scheduled_at TIMESTAMP   NOT NULL,
    created_at   TIMESTAMP   NOT NULL DEFAULT now()
);
```

Миграции применяются автоматически при старте через Flyway (V1–V4).

### Просмотр истории

```sql
-- Все срабатывания за последние 30 дней
SELECT checked_at, name, condition_text, summary, confidence, action
FROM trigger_check_history
WHERE fired = true
  AND checked_at >= now() - interval '30 days'
ORDER BY checked_at DESC;

-- История конкретной бумаги
SELECT checked_at, condition_text, fired, summary, confidence, action
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

## Устойчивость к ошибкам API

Провайдеры LLM периодически возвращают ошибки перегрузки (503) или превышения лимитов (429). Агент обрабатывает их в два уровня.

### Уровень 1 — моментальный retry (в момент вызова)

Применяется только для **Gemini** при ошибках 503/429. До 3 попыток с нарастающей паузой:

| Попытка | Пауза перед повтором |
|---------|----------------------|
| 1 → 2   | 30 секунд |
| 2 → 3   | 60 секунд |
| 3 → 4   | 120 секунд |

Если все 4 вызова провалились — ISIN переходит на второй уровень.

### Уровень 2 — отложенный retry через БД

Если ISIN не удалось проверить после всех моментальных попыток:

1. Запись попадает в таблицу `check_retry_queue` (PostgreSQL)
2. Поллер `processRetryQueue()` проверяет очередь **каждые 5 минут**
3. При наступлении времени — перепроверяет только упавшие ISIN, не трогая остальные
4. До **3 отложенных попыток** с интервалом **1 час** между ними

```
Основной прогон (09:00)
  └── ISIN упал → check_retry_queue (attempt=1, scheduled_at=10:00)

10:00 → поллер → retry #1
  ├── OK  → удаляем из очереди
  └── err → reschedule (attempt=2, scheduled_at=11:00)

11:00 → поллер → retry #2
  └── err → reschedule (attempt=3, scheduled_at=12:00)

12:00 → поллер → retry #3
  └── err → DELETE + Telegram: ⚠️ Не удалось проверить бумаги
```

### Telegram-уведомление при исчерпании попыток

Если после 3 отложенных попыток ISIN всё ещё недоступен — приходит сообщение:

```
⚠️ Не удалось проверить бумаги

Агент не ответил после всех попыток:
• RU000A10DA74 (4 триг.)
• RU000A10CMQ5 (3 триг.)

Требуется ручная проверка.
```

### Важные свойства

- **Очередь хранится в БД** — не теряется при рестарте контейнера
- **Состояние не пишется при ошибке** — `trigger_state` не обновляется, `alreadyCheckedThisPeriod` не заблокирует повтор
- **Нет дублирования** — если ISIN уже в очереди, повторный `enqueue` игнорируется

---

## Запуск в Docker — пошаговая инструкция

### Что нужно установить

1. **Docker Desktop** — скачай на [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/)  
   Включает Docker Engine и docker-compose. После установки убедись, что он запущен:
   ```bash
   docker --version        # Docker version 27.x.x
   docker compose version  # Docker Compose version v2.x.x
   ```

2. Больше ничего — JDK и PostgreSQL устанавливать не нужно, они живут внутри контейнеров.

### Шаг 1 — Определись с провайдером и получи ключи

Подробно — в разделе [Откуда брать секреты](#откуда-брать-секреты).

**Вариант A — Claude + Tavily (по умолчанию):**

| Ключ | Где взять | Примерный вид |
|------|-----------|---------------|
| `ANTHROPIC_API_KEY` | console.anthropic.com → API Keys | `sk-ant-api03-...` |
| `TAVILY_API_KEY` | tavily.com → Dashboard | `tvly-...` |
| `TELEGRAM_BOT_TOKEN` | @BotFather → /newbot | `7123456789:AAF...` |
| `TELEGRAM_CHAT_ID` | getUpdates или @getidsbot | `123456789` или `-1001234567890` |

**Вариант B — Gemini с Grounding (без Tavily):**

| Ключ | Где взять | Примерный вид |
|------|-----------|---------------|
| `GOOGLE_API_KEY` | aistudio.google.com → Get API Key | `AIza...` |
| `TELEGRAM_BOT_TOKEN` | @BotFather → /newbot | `7123456789:AAF...` |
| `TELEGRAM_CHAT_ID` | getUpdates или @getidsbot | `123456789` или `-1001234567890` |

### Шаг 2 — Создай `.env`

В корне проекта лежит `.env.example`. Скопируй его и заполни:

```bash
cp .env.example .env
```

**Для варианта A (Claude):**
```bash
AGENT_PROVIDER=anthropic
ANTHROPIC_API_KEY=sk-ant-api03-...
TAVILY_API_KEY=tvly-...
TELEGRAM_BOT_TOKEN=7123456789:AAF...
TELEGRAM_CHAT_ID=-1001234567890
POSTGRES_PASSWORD=my-secret-pass
HOST_VAULT_PATH=/Users/you/Documents/Obsidian/my-vault
RUN_MODE=scheduled
MONITOR_TIMEZONE=Europe/Moscow
```

**Для варианта B (Gemini):**
```bash
AGENT_PROVIDER=gemini
GOOGLE_API_KEY=AIza...
TELEGRAM_BOT_TOKEN=7123456789:AAF...
TELEGRAM_CHAT_ID=-1001234567890
POSTGRES_PASSWORD=my-secret-pass
HOST_VAULT_PATH=/Users/you/Documents/Obsidian/my-vault
RUN_MODE=scheduled
MONITOR_TIMEZONE=Europe/Moscow
```

> **Проверь путь к vault.** Если твой файл триггеров лежит по пути  
> `/Users/you/Documents/Obsidian/my-vault/Invest/Мониторинг триггеров.md`,  
> то `HOST_VAULT_PATH=/Users/you/Documents/Obsidian/my-vault`.

### Шаг 3 — Запусти

```bash
docker compose up --build
```

При первом запуске Docker:
1. Скачает образы PostgreSQL 16 и Eclipse Temurin 21 (~500 МБ суммарно, один раз)
2. Соберёт fat-jar приложения через Maven внутри контейнера (~2–3 мин первый раз)
3. Поднимет PostgreSQL, дождётся его готовности (healthcheck)
4. Запустит приложение — Flyway создаст таблицы автоматически

Признак успешного запуска в логах:
```
app  | Started AlertAgentApplication in 8.3 seconds
app  | === Запуск проверки [daily] ===
app  | Уникальных ISIN: 4, всего триггеров: 7
```

### Шаг 4 — Полезные команды

```bash
# Запуск в фоне (detached mode)
docker compose up --build -d

# Посмотреть логи приложения в реальном времени
docker compose logs -f app

# Посмотреть логи PostgreSQL
docker compose logs -f postgres

# Остановить всё (данные в БД сохраняются)
docker compose down

# Остановить и удалить данные БД (осторожно!)
docker compose down -v

# Пересобрать только образ приложения, не трогая БД
docker compose up --build app
```

### Шаг 5 — Подключись к БД (опционально)

Пока контейнеры запущены, можно подключиться к PostgreSQL напрямую:

```bash
docker compose exec postgres psql -U alertagent -d alertagent
```

Полезные запросы — в разделе [Просмотр истории](#просмотр-истории).

---

## Режим разработки (dev)

Dev-режим позволяет проверить работу агента без реальных вызовов LLM и поисковых API — никаких расходов. Полезно при разработке, отладке пайплайна или проверке персистентности.

### Что включает

| Компонент | Поведение в dev-режиме |
|-----------|----------------------|
| **Claude / Gemini** | Не вызывается. `checkIsin()` сразу возвращает `MonitorResult.ok` с пометкой `[STUB]` |
| **Tavily** | Не вызывается. `webSearch()` возвращает фиктивный текст с пометкой `[STUB]` |
| **PostgreSQL** | Работает в штатном режиме, история пишется как обычно |
| **Telegram** | Работает в штатном режиме — уведомления реальные |
| **SQL-логи** | Все SQL-запросы Hibernate видны в консоли с параметрами |

> В dev-режиме ни `ANTHROPIC_API_KEY`, ни `GOOGLE_API_KEY`, ни `TAVILY_API_KEY` не используются — можно передать любые строки или не указывать вовсе.

### Как активировать

**В Docker** — добавь в `.env`:
```bash
SPRING_PROFILES_ACTIVE=dev
```

И пробросьте переменную в `docker-compose.yml`, добавив в секцию `environment` сервиса `app`:
```yaml
SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-}
```

**Локально** — через аргумент JVM:
```bash
java --enable-preview -jar target/alert-agent-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=dev
```

**Через переменную окружения:**
```bash
SPRING_PROFILES_ACTIVE=dev java --enable-preview -jar alert-agent.jar
```

### Что увидишь в логах

```
WARN  [STUB] Агент не вызывается — возвращаем фиктивный OK для ISIN RU000A106347
DEBUG Hibernate: select ts1_0.isin,ts1_0.frequency,... from trigger_state ts1_0 where ts1_0.isin=? and ts1_0.frequency=?
TRACE binding parameter (1:VARCHAR) <- [RU000A106347]
TRACE binding parameter (2:VARCHAR) <- [DAILY]
```

Первая строка — LLM-агент заменён заглушкой, токены не тратятся. Далее — реальные SQL-запросы к PostgreSQL с подставленными параметрами.

### Ограничения

- Все результаты всегда `fired: false` — заглушка никогда не сработает триггер. Telegram не получит уведомлений.
- История в БД всё равно пишется — `trigger_state` и `trigger_check_history` обновляются как в prod.

---

## Другие сценарии запуска

### Локально (разовый прогон с Claude)

```bash
# PostgreSQL должен быть запущен отдельно
mvn clean package -DskipTests

AGENT_PROVIDER=anthropic \
ANTHROPIC_API_KEY=sk-ant-... \
TELEGRAM_BOT_TOKEN=7123456789:AAF... \
TELEGRAM_CHAT_ID=123456789 \
TAVILY_API_KEY=tvly-... \
VAULT_PATH=/Users/you/Obsidian/my-vault \
RUN_MODE=monthly \
java --enable-preview -jar target/alert-agent-0.1.0-SNAPSHOT.jar
```

### Локально (разовый прогон с Gemini)

```bash
AGENT_PROVIDER=gemini \
GOOGLE_API_KEY=AIza... \
TELEGRAM_BOT_TOKEN=7123456789:AAF... \
TELEGRAM_CHAT_ID=123456789 \
VAULT_PATH=/Users/you/Obsidian/my-vault \
RUN_MODE=monthly \
java --enable-preview -jar target/alert-agent-0.1.0-SNAPSHOT.jar
```

### По расписанию без немедленного прогона

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

### Выбор провайдера LLM

```yaml
monitor:
  agent-provider: anthropic   # anthropic | gemini
```

Или через переменную окружения:
```bash
AGENT_PROVIDER=gemini
```

### Ключевые параметры `application.yml`

```yaml
monitor:
  agent-provider: anthropic      # anthropic | gemini
  timezone: Europe/Moscow        # часовой пояс расписания (IANA)
  delay-between-checks-sec: 60   # пауза между ISIN (сек)
  search:
    stub: false                  # true — заглушка вместо реального LLM/Tavily
    max-results: 3               # результатов Tavily на один запрос (только для anthropic)
    history-size: 3              # сколько последних проверок передавать агенту как previous_results
    include-domains:             # приоритетные источники для Tavily
      - acra-ratings.ru
      - moex.com
      - cbr.ru
    tavily-url: https://api.tavily.com
  google:
    model: gemini-2.5-flash  # только при agent-provider=gemini; переопределяется через GEMINI_MODEL
    base-url: https://generativelanguage.googleapis.com
  telegram:
    api-base-url: https://api.telegram.org/bot
  prompt:
    system: |                    # системный промпт — редактируется без пересборки
      Ты — инвестиционный аналитик...

spring:
  ai:
    anthropic:
      chat:
        options:
          model: claude-sonnet-4-6
          max-tokens: 4096
```

### Переопределение через аргументы запуска

```bash
# Ускоренный тест — пауза 0 сек
java --enable-preview -jar alert-agent.jar \
  --monitor.delay-between-checks-sec=0

# Другой часовой пояс
java --enable-preview -jar alert-agent.jar \
  --monitor.timezone=Asia/Almaty

# Переключить провайдер без пересборки
java --enable-preview -jar alert-agent.jar \
  --monitor.agent-provider=gemini \
  --monitor.google.api-key=AIza...
```

### Про rate limit и ошибки API

**Claude (Anthropic):** новый аккаунт — 30 000 input-токенов/мин. Один вызов (один ISIN, 3–5 триггеров) ~8 000–12 000 токенов. Поэтому пауза 60 сек по умолчанию. При Tier 2 ($40+) лимит 80 000 токенов/мин — паузу можно снизить до 10–15 сек.

**Gemini (Google):** бесплатный тариф — 10 запросов/мин, 500 запросов/день. Для 10 ISIN ежедневно хватает. Платный тариф снимает большинство ограничений.

Ошибки 503 (`Service Unavailable`) — не превышение лимита, а временная перегрузка серверов Google. Gemini 2.5 Flash особенно подвержен им в часы пиковой нагрузки. Агент обрабатывает их автоматически — см. раздел [Устойчивость к ошибкам API](#устойчивость-к-ошибкам-api).

---

## Как выглядит сообщение в Telegram

### 🚨 Критический сигнал

```
🚨 КРИТИЧНО — [RU000A10ES32] ОДК 001P-01
Рейтинг НРА снижен до A (прогноз негативный) 07.05.2026
По данным ra-national.ru рейтинг эмитента пересмотрен
с A+ до A, прогноз изменён на негативный. Причина —
рост долговой нагрузки по итогам 2025 года.
🎯 выход, высвобождается ~18% портфеля
```

### ⚠️ Предупреждение

```
⚠️ ВНИМАНИЕ — [RU000A105A95] Газпром БО-26
АКРА понизило рейтинг с AA до AA- (прогноз негативный)
Агентство АКРА 07.05.2026 пересмотрело рейтинг в сторону
понижения. Причина — рост долговой нагрузки и снижение
экспортной выручки. Источник: acra-ratings.ru
🎯 не докупать, доля близка к лимиту
```

### Когда сигнала нет

Если триггеры не сработали — сообщение в Telegram **не отправляется**. Тишина = норма.

---

## Структура проекта

```
src/main/java/com/invest/monitor/
├── AlertAgentApplication.java           — точка входа Spring Boot
├── agent/
│   ├── MonitorAgent.java                — интерфейс: checkAll(triggers)
│   ├── AbstractMonitorAgent.java        — общая логика: группировка, промпт, парсинг
│   │                                      при ошибке возвращает MonitorResult.error (state не пишется)
│   ├── AnthropicMonitorAgent.java       — Claude + Tavily tool-use (@ConditionalOnProperty)
│   ├── GeminiMonitorAgent.java          — Gemini + Google Search Grounding (@ConditionalOnProperty)
│   │                                      моментальный retry 503/429: 30→60→120 сек
│   └── AgentTools.java                  — @Tool webSearch → Tavily API (stub-режим)
├── config/
│   └── MonitorConfig.java               — @ConfigurationProperties (monitor.*)
├── domain/
│   ├── Trigger.java                     — record: строка таблицы + позиция в портфеле
│   ├── TriggerLevel.java                — sealed interface: Critical | Warning
│   ├── TriggerFrequency.java            — enum: DAILY | WEEKLY | MONTHLY | QUARTERLY
│   └── MonitorResult.java               — record: fired, error, summary, details, confidence, action
├── parser/
│   └── TriggerParser.java               — читает MD-таблицу, парсит колонку Позиция
├── retry/
│   ├── CheckRetryEntry.java             — JPA-сущность: одна запись очереди (isin, attempt, scheduled_at)
│   ├── CheckRetryRepository.java        — Spring Data репозиторий
│   └── CheckRetryService.java           — enqueue / pollDue / markSuccess / markFailed
├── scheduler/
│   └── MonitorScheduler.java            — @Scheduled расписание + startup run
│                                          + поллер очереди retry каждые 5 мин
├── state/
│   ├── TriggerState.java                — JPA-сущность: (isin, frequency) → состояние
│   ├── TriggerStateId.java              — составной ключ для JPA
│   ├── TriggerStateRepository.java      — Spring Data репозиторий
│   ├── TriggerStateService.java         — дедупликация по периодам + запись истории
│   ├── TriggerCheckHistory.java         — JPA-сущность: одна запись на проверку (+ action)
│   └── TriggerCheckHistoryRepository.java
└── telegram/
    └── TelegramNotifier.java            — RestClient → Telegram Bot API
                                           notifyFired + notifyCheckFailed (исчерпаны retry)

src/main/resources/
├── application.yml                      — основная конфигурация + системный промпт
├── application-dev.yml                  — dev-профиль: stub LLM/Tavily + SQL-логи
└── db/migration/
    ├── V1__create_trigger_state.sql     — создание trigger_state
    ├── V2__add_history.sql              — добавление trigger_check_history
    ├── V3__add_action_to_history.sql    — колонка action в trigger_check_history
    └── V4__add_retry_queue.sql          — таблица check_retry_queue

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
        └── Мониторинг триггеров.md      — тестовый fixture

Dockerfile                               — двухэтапная сборка: Maven → JRE alpine
docker-compose.yml                       — app + PostgreSQL 16
.env.example                             — шаблон переменных окружения
```
