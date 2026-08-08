CREATE TABLE IF NOT EXISTS outbound_response_mapper_configuration (
    id                  BIGSERIAL PRIMARY KEY,
    created_by          VARCHAR(25),
    updated_by          VARCHAR(25),
    created_date        TIMESTAMP,
    updated_date        TIMESTAMP,
    version             INT,
    outbound_system_id  BIGINT,
    converter_type      VARCHAR(50), -- NONE, JSLT, GROOVY
    mapping_script      TEXT,
    mapping_status      JSONB,       -- {"404": 400, "503": 500}
    is_active           BOOLEAN
);

CREATE INDEX IF NOT EXISTS idx_outboundresponse_systemid
    ON outbound_response_mapper_configuration (outbound_system_id);

CREATE INDEX IF NOT EXISTS idx_outboundresponse_systemid_active
    ON outbound_response_mapper_configuration (outbound_system_id, is_active);
