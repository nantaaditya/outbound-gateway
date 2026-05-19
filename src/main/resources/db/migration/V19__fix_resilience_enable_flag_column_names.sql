-- V18 used is_* prefix but the entity maps to circuit_breaker_enabled (no is_ prefix).
-- Rename to match the R2DBC entity field → column convention.
ALTER TABLE outbound_resilience_configurations
    RENAME COLUMN is_circuit_breaker_enabled TO circuit_breaker_enabled;

ALTER TABLE outbound_resilience_configurations
    RENAME COLUMN is_bulkhead_enabled TO bulkhead_enabled;

ALTER TABLE outbound_resilience_configurations
    RENAME COLUMN is_rate_limiter_enabled TO rate_limiter_enabled;

ALTER TABLE outbound_resilience_configurations
    RENAME COLUMN is_retry_enabled TO retry_enabled;
