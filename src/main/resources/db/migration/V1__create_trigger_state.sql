CREATE TABLE trigger_state (
    isin            VARCHAR(12)  NOT NULL,
    frequency       VARCHAR(20)  NOT NULL,
    last_checked_at DATE         NOT NULL,
    last_fired_at   DATE,
    PRIMARY KEY (isin, frequency)
);
