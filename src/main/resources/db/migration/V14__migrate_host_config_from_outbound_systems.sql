-- Non-idempotent migration: ON CONFLICT DO NOTHING means re-runs are safe but won't update
-- existing rows if host config already exists. Ensure flyway.cleanDisabled=true in production.
-- Populate outbound_host_configurations from existing outbound_systems data.
-- For each unique base_url, use the first system's SSL/proxy config and its resilience connect timeout.
INSERT INTO outbound_host_configurations
    (base_url, connect_time_out_ms, is_disable_ssl_verification, is_use_proxy,
     proxy_host, proxy_port, proxy_username, proxy_password)
SELECT DISTINCT ON (os.base_url)
    os.base_url,
    COALESCE(orc.connect_time_out_ms, 5000),
    os.is_disable_ssl_verification,
    os.is_use_proxy,
    os.proxy_host,
    os.proxy_port,
    os.proxy_username,
    os.proxy_password
FROM outbound_systems os
LEFT JOIN outbound_resilience_configurations orc ON orc.outbound_system_id = os.id
ORDER BY os.base_url, os.id
ON CONFLICT (base_url) DO NOTHING;

-- Drop host-level columns that now live in outbound_host_configurations
ALTER TABLE outbound_systems
    DROP COLUMN IF EXISTS is_disable_ssl_verification,
    DROP COLUMN IF EXISTS is_use_proxy,
    DROP COLUMN IF EXISTS proxy_host,
    DROP COLUMN IF EXISTS proxy_port,
    DROP COLUMN IF EXISTS proxy_username,
    DROP COLUMN IF EXISTS proxy_password;

-- connect_time_out_ms is now host-level; drop from per-system resilience config
ALTER TABLE outbound_resilience_configurations
    DROP COLUMN IF EXISTS connect_time_out_ms;
