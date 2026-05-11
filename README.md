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
7. [Запуск в Docker — пошаговая инструкция](#запуск-в-docker--пошаговая-инструкция)
8. [Режим разработки (dev)](#режим-разработки-dev)
9. [Другие сценарии запуска](#другие-сценарии-запуска)
10. [Тонкая настройка](#тонкая-настройка)
11. [Как выглядит сообщение в Telegram](#как-выглядит-сообщение-в-telegram)
12. [Структура проекта](#структура-проекта)

---

## Что делает агент

1. Читает таблицу триггеров из файла Obsidian vault
2. Проверяет по базе данных, не был ли ISIN уже проверен в текущем периоде
3. Группирует оставшиеся триггеры по ISIN — **один вызов Claude на одну бумагу**
4. Claude делает до 3 поисковых запросов через Tavily, покрывая все триггеры по эмитенту
5. Если условие триггера выполнено — отправляет сигнал 🚨 или ⚠️ в Telegram
6. Сохраняет результат и полную историю проверок в PostgreSQL
7. Между бумагами держит паузу (по умолчанию 60 сек), чтобы не превысить rate limit API
8. Запускается по расписанию: ежедневно / еженедельно / ежемесячно / ежеквартально

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
| `ANTHROPIC_API_KEY` | ✅ | Ключ Claude API |
| `TELEGRAM_BOT_TOKEN` | ✅ | Токен Telegram-бота |
| `TELEGRAM_CHAT_ID` | ✅ | ID чата/канала для сигналов |
| `TAVILY_API_KEY` | ✅ | Ключ поискового API (в dev-режиме не нужен) |
| `HOST_VAULT_PATH` | ✅ (Docker) | Путь к vault на хосте — монтируется как `/vault` |
| `POSTGRES_PASSWORD` | ✅ (Docker) | Пароль PostgreSQL |
| `VAULT_PATH` | — | Путь к vault внутри контейнера, по умолчанию `/vault` |
| `SPRING_DATASOURCE_URL` | — | URL БД, по умолчанию `jdbc:postgresql://localhost:5432/alertagent` |
| `SPRING_DATASOURCE_USERNAME` | — | Пользователь БД, по умолчанию `alertagent` |
| `SPRING_DATASOURCE_PASSWORD` | — | Пароль БД |
| `RUN_MODE` | — | Режим запуска (см. [таблицу](#все-режимы-run_mode)), по умолчанию `daily` |
| `MONITOR_TIMEZONE` | — | Часовой пояс расписания (IANA), по умолчанию `Europe/Moscow` |
| `SPRING_PROFILES_ACTIVE` | — | `dev` — включает stub Tavily и SQL-логи |

---

## Откуда брать секреты

### Anthropic API Key (`ANTHROPIC_API_KEY`)

> **Важно:** подписка Claude Pro (claude.ai) и API — это разные продукты с отдельной оплатой.  
> Pro даёт доступ к веб-интерфейсу и Claude Code, но не к программному API.

1. Зайди на [console.anthropic.com](https://console.anthropic.com)
2. **API Keys** → **Create Key** → скопируй `sk-ant-...`
3. Пополни баланс — минимум $5. Один прогон (10 уникальных ISIN) стоит ~$0.10–0.30

**Лимиты нового аккаунта:** 30 000 input-токенов в минуту. Агент делает паузу между бумагами и ограничивает поиск до 3 запросов на ISIN, чтобы укладываться в лимит.

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
  TriggerStateService    Проверяет по PostgreSQL: ISIN уже обработан в этом периоде?
        │                Да → пропустить весь ISIN. Нет → передать агенту
        ▼
  MonitorAgent           Группирует по ISIN, для каждого ISIN (с паузой между ними):
        │
        ├─► Claude API   Один запрос = один ISIN со всеми его триггерами
        │   (tool-use)   Системный промпт + список условий + предыдущий результат из БД
        │       │
        │       ▼
        │   AgentTools   Claude вызывает webSearch(query) — до 3 раз на ISIN
        │   webSearch ──► Tavily API (рейтинговые агентства, биржа, регулятор)
        │     [или stub]  acra-ratings.ru, moex.com, cbr.ru, interfax.ru...
        │       ▼
        │   Claude       Возвращает JSON-массив: по одному объекту на каждый триггер
        │                [{ condition, fired, summary, details, confidence }, ...]
        │
        ▼
  TriggerStateService    INSERT в trigger_check_history (каждый триггер)
        │                UPDATE trigger_state (last_checked_at, last_fired_at, last_summary...)
        ▼
  MonitorResult          fired=true → сигнал, fired=false → норма
        │
        ▼
  TelegramNotifier       Только сработавшие (fired=true) → sendMessage
        │
        ▼
  Telegram               🚨 или ⚠️ сообщение в чат
```

---

## Когда триггер берётся в работу

Перед каждым запуском агент проверяет таблицу `trigger_state` в PostgreSQL. ISIN **пропускается целиком** (все его триггеры, 0 вызовов Claude), если он уже был проверен в текущем периоде.

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
       → пропускаем (0 токенов, 0 вызовов Claude)

После успешной проверки одного ISIN (один вызов Claude):
  → для каждого триггера: INSERT в trigger_check_history
  → UPDATE trigger_state: last_checked_at = сегодня
                           last_fired_at = сегодня (если хотя бы один fired)
                           last_summary / last_details / last_confidence
```

### Почему это важно

Без персистентного состояния каждый перезапуск приложения заново прогоняет все триггеры через Claude. При `RUN_MODE=scheduled` это не проблема (крон запускается раз в день/неделю), но при сбое контейнера в тот же день — дублирующиеся Telegram-сообщения и лишние расходы токенов.

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

## Запуск в Docker — пошаговая инструкция

### Что нужно установить

1. **Docker Desktop** — скачай на [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/)  
   Включает Docker Engine и docker-compose. После установки убедись, что он запущен:
   ```bash
   docker --version        # Docker version 27.x.x
   docker compose version  # Docker Compose version v2.x.x
   ```

2. Больше ничего — JDK и PostgreSQL устанавливать не нужно, они живут внутри контейнеров.

### Шаг 1 — Получи ключи

Перед запуском нужны четыре ключа. Подробно — в разделе [Откуда брать секреты](#откуда-брать-секреты).

| Ключ | Где взять | Примерный вид |
|------|-----------|---------------|
| `ANTHROPIC_API_KEY` | console.anthropic.com → API Keys | `sk-ant-api03-...` |
| `TELEGRAM_BOT_TOKEN` | @BotFather в Telegram → /newbot | `7123456789:AAF...` |
| `TELEGRAM_CHAT_ID` | getUpdates или @getidsbot | `123456789` или `-1001234567890` |
| `TAVILY_API_KEY` | tavily.com → Dashboard | `tvly-...` |

### Шаг 2 — Создай `.env`

В корне проекта лежит `.env.example`. Скопируй его и заполни:

```bash
cp .env.example .env
```

Открой `.env` в редакторе и заполни каждую строку:

```bash
# Ключи API
ANTHROPIC_API_KEY=sk-ant-api03-...   # твой ключ с console.anthropic.com
TELEGRAM_BOT_TOKEN=7123456789:AAF... # токен от @BotFather
TELEGRAM_CHAT_ID=-1001234567890      # ID твоего канала или личного чата
TAVILY_API_KEY=tvly-...              # ключ с tavily.com

# PostgreSQL — придумай любой пароль
POSTGRES_PASSWORD=my-secret-pass

# Путь к папке Obsidian vault на твоём компьютере
# Это папка, внутри которой лежит папка Invest/ с файлом триггеров
HOST_VAULT_PATH=/Users/you/Documents/Obsidian/my-vault

# Режим запуска при старте контейнера
# scheduled — только по расписанию (продакшн)
# daily / weekly / monthly / quarterly — немедленный прогон + расписание
RUN_MODE=scheduled

# Часовой пояс для расписания (IANA timezone name)
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

Dev-режим позволяет проверить работу агента без реальных запросов в Tavily — полезно при разработке, отладке промпта или когда нужно просто убедиться, что пайплайн работает.

### Что включает

| Компонент | Поведение в dev-режиме |
|-----------|----------------------|
| **Tavily** | Не вызывается. `webSearch()` возвращает фиктивный текст с пометкой `[STUB]` |
| **Claude** | Работает в штатном режиме, формирует JSON-ответ на основе stub-данных |
| **PostgreSQL** | Работает в штатном режиме, история пишется как обычно |
| **Telegram** | Работает в штатном режиме — уведомления реальные |
| **SQL-логи** | Все SQL-запросы Hibernate видны в консоли с параметрами |

> Ключ `TAVILY_API_KEY` в dev-режиме не используется — можно передать любую строку.

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
WARN  [STUB] Tavily заглушка активна — возвращаем фиктивный ответ для: «ОФЗ 26238 YTM 2026»
DEBUG Hibernate: select ts1_0.isin,ts1_0.frequency,... from trigger_state ts1_0 where ts1_0.isin=? and ts1_0.frequency=?
TRACE binding parameter (1:VARCHAR) <- [RU000A106347]
TRACE binding parameter (2:VARCHAR) <- [DAILY]
```

Первая строка — Tavily заменён заглушкой. Далее — реальные SQL-запросы к PostgreSQL с подставленными параметрами. Это позволяет точно видеть, что пишется в БД и когда.

### Ограничения

- Claude всё равно вызывается и тратит токены — он пытается анализировать stub-текст. Если нужно протестировать только персистентность, можно добавить `monitor.delay-between-checks-sec=0` для ускорения.
- Ответы Claude при stub-данных будут `fired: false, confidence: low` (данных недостаточно) — это ожидаемое поведение.

---

## Другие сценарии запуска

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

### Ключевые параметры `application.yml`

```yaml
monitor:
  timezone: Europe/Moscow        # часовой пояс расписания (IANA)
  delay-between-checks-sec: 60   # пауза между ISIN (сек)
  search:
    max-results: 3               # результатов Tavily на один запрос
    stub: false                  # true — заглушка вместо реального Tavily
    include-domains:             # приоритетные источники для поиска
      - acra-ratings.ru
      - moex.com
      - cbr.ru
    tavily-url: https://api.tavily.com
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
          max-tokens: 2048       # максимум токенов в ответе Claude
```

### Переопределение через аргументы запуска

```bash
# Ускоренный тест — пауза 0 сек
java --enable-preview -jar alert-agent.jar \
  --monitor.delay-between-checks-sec=0

# Другой часовой пояс
java --enable-preview -jar alert-agent.jar \
  --monitor.timezone=Asia/Almaty

# Dev-режим + другой RUN_MODE
java --enable-preview -jar alert-agent.jar \
  --spring.profiles.active=dev \
  --monitor.run-mode=monthly
```

### Про rate limit

Новый Anthropic API аккаунт имеет лимит **30 000 input-токенов в минуту**. Один вызов агента (один ISIN с 3–5 триггерами) потребляет ~8 000–12 000 токенов. Поэтому:

- `delay-between-checks-sec: 60` — одна бумага в минуту
- `max-results: 3` — ограниченные ответы Tavily
- `max-tokens: 2048` — вмещает JSON-массив по всем триггерам ISIN
- Промпт ограничивает Claude до 3 поисков на бумагу

При `Tier 2` (после пополнения на $40+) лимит вырастает до 80 000 токенов/мин — паузу можно снизить до 10–15 сек.

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

Если триггеры не сработали — сообщение в Telegram **не отправляется**. Тишина = норма.

---

## Структура проекта

```
src/main/java/com/invest/monitor/
├── AlertAgentApplication.java       — точка входа Spring Boot
├── agent/
│   ├── MonitorAgent.java            — группировка по ISIN, tool-use loop
│   └── AgentTools.java              — @Tool webSearch → Tavily API (или stub)
├── config/
│   └── MonitorConfig.java           — @ConfigurationProperties (monitor.*)
├── domain/
│   ├── Trigger.java                 — record: одна строка таблицы
│   ├── TriggerLevel.java            — sealed interface: Critical | Warning
│   ├── TriggerFrequency.java        — enum: DAILY | WEEKLY | MONTHLY | QUARTERLY
│   └── MonitorResult.java           — record: результат проверки триггера
├── parser/
│   └── TriggerParser.java           — читает MD-таблицу из vault
├── scheduler/
│   └── MonitorScheduler.java        — @Scheduled расписание + startup run
├── state/
│   ├── TriggerState.java            — JPA-сущность: (isin, frequency) → состояние
│   ├── TriggerStateId.java          — составной ключ для JPA
│   ├── TriggerStateRepository.java  — Spring Data репозиторий
│   ├── TriggerStateService.java     — дедупликация по периодам + запись истории
│   ├── TriggerCheckHistory.java     — JPA-сущность: одна запись на проверку
│   └── TriggerCheckHistoryRepository.java
└── telegram/
    └── TelegramNotifier.java        — RestClient → Telegram Bot API

src/main/resources/
├── application.yml                  — основная конфигурация + системный промпт
├── application-dev.yml              — dev-профиль: stub Tavily + SQL-логи
└── db/migration/
    ├── V1__create_trigger_state.sql — создание trigger_state
    └── V2__add_history.sql          — добавление trigger_check_history

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

Dockerfile                           — двухэтапная сборка: Maven → JRE alpine
docker-compose.yml                   — app + PostgreSQL 16
.env.example                         — шаблон переменных окружения
```
