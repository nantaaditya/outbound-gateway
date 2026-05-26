CREATE TABLE IF NOT EXISTS outbound_systems
(
    id              BIGSERIAL PRIMARY KEY,
    created_by      VARCHAR(25),
    updated_by      VARCHAR(25),
    created_date    TIMESTAMP,
    updated_date    TIMESTAMP,
    version         INT,
    code            VARCHAR(100) NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    http_method     VARCHAR(10)  NOT NULL,
    base_url        VARCHAR(500) NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    is_disable_ssl_verification BOOLEAN NOT NULL DEFAULT FALSE,
    is_use_proxy    BOOLEAN NOT NULL DEFAULT FALSE,
    proxy_host      VARCHAR(255),
    proxy_port      INT,
    proxy_username  bytea, -- encrypted
    proxy_password  bytea -- encrypted
);

CREATE INDEX IF NOT EXISTS idx_outboundsystem_code on outbound_systems(code, is_active);
