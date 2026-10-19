CREATE TABLE IF NOT EXISTS outbound_parameters (
    id                     BIGSERIAL PRIMARY KEY,
    created_by             VARCHAR(25),
    updated_by             VARCHAR(25),
    created_date           TIMESTAMP,
    updated_date           TIMESTAMP,
    version                INT,
    host_configuration_id  BIGINT       NOT NULL,
    param_key              VARCHAR(100) NOT NULL,
    param_value            BYTEA        NOT NULL, -- encrypted (AES-256-GCM)
    inject_location        VARCHAR(20)  NOT NULL, -- HEADER, QUERY_PARAM, BODY
    target_name            VARCHAR(255) NOT NULL, -- header name, query param name, or dot path in JSON body
    is_active              BOOLEAN      NOT NULL DEFAULT TRUE,

    CONSTRAINT fk_outbound_parameters_host_configuration
        FOREIGN KEY (host_configuration_id)
        REFERENCES outbound_host_configurations (id),
    CONSTRAINT uq_outbound_parameters_host_key
        UNIQUE (host_configuration_id, param_key)
);

CREATE INDEX IF NOT EXISTS idx_outboundparameter_hostconfig
    ON outbound_parameters (host_configuration_id, is_active);
