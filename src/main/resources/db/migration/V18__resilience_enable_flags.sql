-- Each resilience mechanism is independently opt-in.
-- DEFAULT false: after this migration all existing rows have all mechanisms disabled.
-- Admins must explicitly enable each mechanism. This is intentional — applying
-- circuit-breaker or retry with default thresholds silently would be worse than running
-- unprotected, because wrong thresholds can cause more harm than no protection.
ALTER TABLE outbound_resilience_configurations
    ADD COLUMN is_circuit_breaker_enabled  BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN is_bulkhead_enabled         BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN is_rate_limiter_enabled     BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN is_retry_enabled            BOOLEAN NOT NULL DEFAULT false;
