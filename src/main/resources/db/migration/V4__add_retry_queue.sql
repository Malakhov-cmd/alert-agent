CREATE TABLE check_retry_queue (
    id           BIGSERIAL    PRIMARY KEY,
    isin         VARCHAR(12)  NOT NULL,
    frequency    VARCHAR(20)  NOT NULL,
    label        VARCHAR(20)  NOT NULL,
    attempt      INT          NOT NULL DEFAULT 1,
    scheduled_at TIMESTAMP    NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_retry_scheduled ON check_retry_queue(scheduled_at);
