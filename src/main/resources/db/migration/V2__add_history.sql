-- 1. Новые колонки в trigger_state для быстрого доступа к последнему результату
ALTER TABLE trigger_state
    ADD COLUMN last_summary    TEXT,
    ADD COLUMN last_details    TEXT,
    ADD COLUMN last_confidence VARCHAR(10);

-- 2. Полная история всех проверок — для просмотра и аудита
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

CREATE INDEX idx_history_isin       ON trigger_check_history(isin);
CREATE INDEX idx_history_checked_at ON trigger_check_history(checked_at DESC);
