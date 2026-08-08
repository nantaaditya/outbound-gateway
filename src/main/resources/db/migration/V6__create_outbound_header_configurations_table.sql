CREATE TABLE IF NOT EXISTS outbound_header_configurations (
    id                  BIGSERIAL PRIMARY KEY,
    created_by          VARCHAR(25),
    updated_by          VARCHAR(25),
    created_date        TIMESTAMP,
    updated_date        TIMESTAMP,
    version             INT,
    outbound_system_id  BIGINT,
    headers             JSONB -- {"key": ["value1", "value2"]}
);

CREATE INDEX IF NOT EXISTS idx_outboundheader_systemid
    ON outbound_header_configurations (outbound_system_id);
