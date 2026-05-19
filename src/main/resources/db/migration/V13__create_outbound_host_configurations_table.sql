CREATE TABLE IF NOT EXISTS outbound_host_configurations
(
    id                          BIGSERIAL PRIMARY KEY,
    created_by                  VARCHAR(25),
    updated_by                  VARCHAR(25),
    created_date                TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_date                TIMESTAMP NOT NULL DEFAULT NOW(),
    version                     INT,
    base_url                    VARCHAR(500) NOT NULL UNIQUE,
    connect_time_out_ms         INT NOT NULL DEFAULT 5000,
    is_disable_ssl_verification BOOLEAN NOT NULL DEFAULT FALSE,
    is_use_proxy                BOOLEAN NOT NULL DEFAULT FALSE,
    proxy_host                  VARCHAR(255),
    proxy_port                  INT,
    proxy_username              BYTEA,
    proxy_password              BYTEA
);

CREATE INDEX IF NOT EXISTS idx_host_configurations_url on outbound_host_configurations(base_url);