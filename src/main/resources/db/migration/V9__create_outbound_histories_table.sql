CREATE TABLE IF NOT EXISTS outbound_histories (
    id                   BIGSERIAL PRIMARY KEY,
    outbound_system_id   BIGINT       NOT NULL,
    idempotency_key      VARCHAR(255),
    caller_service       VARCHAR(100),
    request_id           VARCHAR(255),
    request_method       VARCHAR(10),
    request_url          VARCHAR(1000),
    request_headers      JSONB,
    request_payload      TEXT,
    response_status      INT,
    response_headers     JSONB,
    response_payload     TEXT,
    duration_ms          INT,
    converted_status     INT,
    converted_response   TEXT,
    retry_count          INT          NOT NULL DEFAULT 0,
    created_date         TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- TTL-gated idempotency lookup: findFirstByOutboundSystemIdAndIdempotencyKeyAndCreatedDateAfter...
-- No UNIQUE constraint — application-level TTL guard allows key reuse across TTL windows.
CREATE INDEX idx_histories_idempotency
    ON outbound_histories (outbound_system_id, idempotency_key, created_date);

-- Filtered paged query by system + date range
CREATE INDEX idx_histories_system_date
    ON outbound_histories (outbound_system_id, created_date);

-- Filter by system + response status
CREATE INDEX idx_histories_response_status
    ON outbound_histories (outbound_system_id, response_status);
