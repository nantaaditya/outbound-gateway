ALTER TABLE outbound_resilience_configurations
    -- Circuit Breaker
    ADD COLUMN cb_failure_rate_threshold   FLOAT   DEFAULT 50,
    ADD COLUMN cb_slow_call_rate_threshold FLOAT   DEFAULT 100,
    ADD COLUMN cb_slow_call_duration_ms    INT     DEFAULT 60000,
    ADD COLUMN cb_permitted_in_half_open   INT     DEFAULT 10,
    ADD COLUMN cb_sliding_window_size      INT     DEFAULT 100,
    ADD COLUMN cb_wait_duration_open_ms    INT     DEFAULT 60000,
    -- Bulkhead
    ADD COLUMN bh_max_concurrent_calls     INT     DEFAULT 25,
    ADD COLUMN bh_max_wait_duration_ms     INT     DEFAULT 0,
    -- Rate Limiter
    ADD COLUMN rl_limit_for_period         INT     DEFAULT 100,
    ADD COLUMN rl_limit_refresh_period_ms  INT     DEFAULT 1000,
    ADD COLUMN rl_timeout_duration_ms      INT     DEFAULT 0,
    -- Retry
    ADD COLUMN retry_max_attempts          INT     DEFAULT 3,
    ADD COLUMN retry_wait_duration_ms      INT     DEFAULT 500,
    ADD COLUMN retry_on_status_codes       VARCHAR(255) DEFAULT '500,502,503';
