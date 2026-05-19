ALTER TABLE outbound_host_configurations
    ADD COLUMN max_connections            INT NOT NULL DEFAULT 500,
    ADD COLUMN pending_acquire_max_count  INT NOT NULL DEFAULT 1000,
    ADD COLUMN pending_acquire_timeout_ms INT NOT NULL DEFAULT 30000;

ALTER TABLE outbound_resilience_configurations
    DROP COLUMN IF EXISTS write_time_out_ms,
    DROP COLUMN IF EXISTS core_pool_size,
    DROP COLUMN IF EXISTS queue_size,
    DROP COLUMN IF EXISTS max_pool_size;
