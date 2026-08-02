ALTER TABLE outbound_systems ADD COLUMN host_configuration_id BIGINT;

-- Every distinct base_url already used by a system must have a host_configurations row
-- before the FK below can be populated. This mirrors the "production default" pool settings
-- the application previously synthesized in memory (GatewayServiceImpl.defaultHostConfig /
-- OutboundHostConfiguration.productionDefault) for systems with no explicitly registered host
-- configuration — that silent in-memory fallback goes away once the FK is mandatory.
INSERT INTO outbound_host_configurations
    (base_url, connect_time_out_ms, max_connections, pending_acquire_max_count,
     pending_acquire_timeout_ms, is_disable_ssl_verification, is_use_proxy)
SELECT DISTINCT s.base_url, 5000, 500, 1000, 30000, false, false
FROM outbound_systems s
WHERE NOT EXISTS (
    SELECT 1 FROM outbound_host_configurations hc WHERE hc.base_url = s.base_url
);

UPDATE outbound_systems s
SET host_configuration_id = hc.id
FROM outbound_host_configurations hc
WHERE hc.base_url = s.base_url;

ALTER TABLE outbound_systems
    ALTER COLUMN host_configuration_id SET NOT NULL,
    ADD CONSTRAINT fk_outbound_systems_host_configuration
        FOREIGN KEY (host_configuration_id) REFERENCES outbound_host_configurations (id);

CREATE INDEX IF NOT EXISTS idx_outboundsystem_hostconfig ON outbound_systems (host_configuration_id);
