CREATE TABLE IF NOT EXISTS outbound_header_configurations
(
    id                  BIGSERIAL PRIMARY KEY,
    created_by          VARCHAR(25),
    updated_by          VARCHAR(25),
    created_date        TIMESTAMP,
    updated_date        TIMESTAMP,
    version             INT,
    outbound_system_id  INT,
    headers             JSONB -- {"key": ["value", "value"]}
);
