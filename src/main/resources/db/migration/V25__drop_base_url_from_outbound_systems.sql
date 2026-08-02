-- outbound_host_configurations is now the single source of truth for base_url
-- (one host configuration, many outbound_systems via host_configuration_id — see V23).
ALTER TABLE outbound_systems DROP COLUMN IF EXISTS base_url;
