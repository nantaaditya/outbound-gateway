CREATE TABLE IF NOT EXISTS outbound_systems (
    id                     BIGSERIAL PRIMARY KEY,
    created_by             VARCHAR(25),
    updated_by             VARCHAR(25),
    created_date           TIMESTAMP,
    updated_date           TIMESTAMP,
    version                INT,
    code                   VARCHAR(100) NOT NULL UNIQUE,
    name                   VARCHAR(255) NOT NULL,
    description            TEXT,
    http_method            VARCHAR(10)  NOT NULL,
    api_path               VARCHAR(500) NOT NULL DEFAULT '',
    is_active              BOOLEAN      NOT NULL DEFAULT TRUE,
    idempotency_ttl_hours  INT          NOT NULL DEFAULT 24,
    host_configuration_id  BIGINT       NOT NULL,

    CONSTRAINT fk_outbound_systems_host_configuration
        FOREIGN KEY (host_configuration_id)
        REFERENCES outbound_host_configurations (id)
);

CREATE INDEX IF NOT EXISTS idx_outboundsystem_code
    ON outbound_systems (code, is_active);

CREATE INDEX IF NOT EXISTS idx_outboundsystem_hostconfig
    ON outbound_systems (host_configuration_id);
