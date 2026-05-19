CREATE TABLE IF NOT EXISTS outbound_histories
(
    id                   BIGSERIAL PRIMARY KEY,
    outbound_system_id   BIGINT       NOT NULL,
    idempotency_key      VARCHAR(255),
    caller_service       VARCHAR(100),
    request_id           VARCHAR(255),
    request_method       VARCHAR(10),
    request_url          VARCHAR(1000),
    request_headers      TEXT,
    request_payload      TEXT,
    response_status      INT,
    response_headers     TEXT,
    response_payload     TEXT,
    duration_ms          INT,
    converted_status     INT,
    converted_response   TEXT,
    created_date         TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_idempotency UNIQUE (outbound_system_id, idempotency_key)
);

CREATE INDEX idx_histories_system_date     ON outbound_histories (outbound_system_id, created_date);
CREATE INDEX idx_histories_idempotency     ON outbound_histories (outbound_system_id, idempotency_key);
CREATE INDEX idx_histories_response_status ON outbound_histories (outbound_system_id, response_status);
