CREATE TABLE IF NOT EXISTS event_logs (
    id                   VARCHAR(25) PRIMARY KEY,
    client_id            VARCHAR(25),
    request_id           VARCHAR(64),
    method               VARCHAR(10),
    path                 TEXT,
    response_code        VARCHAR(5),
    response_description TEXT,
    payload              BYTEA,
    additional_data      BYTEA,
    created_date         TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_eventlogs_request_id   ON event_logs (request_id);
CREATE INDEX IF NOT EXISTS idx_eventlogs_created_date ON event_logs (created_date);
