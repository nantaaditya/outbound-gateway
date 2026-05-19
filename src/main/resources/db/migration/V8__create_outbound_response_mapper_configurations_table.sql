CREATE TABLE IF NOT EXISTS outbound_response_mapper_configuration
(
    id                          BIGSERIAL PRIMARY KEY,
    created_by                  VARCHAR(25),
    updated_by                  VARCHAR(25),
    created_date                TIMESTAMP,
    updated_date                TIMESTAMP,
    version                     INT,
    outbound_system_id          INT,
    converter_type              VARCHAR(50), -- NONE, JOLT, GROOVY
    mapping_script              TEXT,
    mapping_status              JSONB, -- {"404":400}
    is_active                   BOOLEAN
);
