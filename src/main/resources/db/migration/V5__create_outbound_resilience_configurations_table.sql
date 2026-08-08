CREATE TABLE IF NOT EXISTS outbound_resilience_configurations (
    id                         BIGSERIAL PRIMARY KEY,
    created_by                 VARCHAR(25),
    updated_by                 VARCHAR(25),
    created_date               TIMESTAMP,
    updated_date               TIMESTAMP,
    version                    INT,
    outbound_system_id         BIGINT,

    -- timeouts
    read_time_out_ms           INT,

    -- circuit breaker
    cb_failure_rate_threshold  FLOAT        DEFAULT 50,
    cb_slow_call_rate_threshold FLOAT       DEFAULT 100,
    cb_slow_call_duration_ms   INT          DEFAULT 60000,
    cb_permitted_in_half_open  INT          DEFAULT 10,
    cb_sliding_window_size     INT          DEFAULT 100,
    cb_wait_duration_open_ms   INT          DEFAULT 60000,

    -- bulkhead
    bh_max_concurrent_calls    INT          DEFAULT 25,
    bh_max_wait_duration_ms    INT          DEFAULT 0,

    -- rate limiter
    rl_limit_for_period        INT          DEFAULT 100,
    rl_limit_refresh_period_ms INT          DEFAULT 1000,
    rl_timeout_duration_ms     INT          DEFAULT 0,

    -- retry
    retry_max_attempts         INT          DEFAULT 3,
    retry_wait_duration_ms     INT          DEFAULT 500,
    retry_on_status_codes      VARCHAR(255) DEFAULT '500,502,503',

    -- enable flags (opt-in per mechanism)
    circuit_breaker_enabled    BOOLEAN      NOT NULL DEFAULT FALSE,
    bulkhead_enabled           BOOLEAN      NOT NULL DEFAULT FALSE,
    rate_limiter_enabled       BOOLEAN      NOT NULL DEFAULT FALSE,
    retry_enabled              BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_outboundresilience_systemid
    ON outbound_resilience_configurations (outbound_system_id);
