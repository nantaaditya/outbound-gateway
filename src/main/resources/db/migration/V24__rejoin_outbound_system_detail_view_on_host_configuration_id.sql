-- host_configuration_id is now NOT NULL (V23). Rewrite the view to source base_url from the
-- host configuration (the master record) and join on the FK (INNER JOIN, guaranteed match)
-- instead of a base_url string match (LEFT JOIN, could silently miss). This must run BEFORE
-- V25 drops outbound_systems.base_url — the view still referenced that column and Postgres
-- refuses to drop a column a view depends on.
CREATE OR REPLACE VIEW v_outbound_system_detail AS
SELECT
    -- outbound_systems
    s.id,
    s.code,
    s.name,
    s.description,
    s.http_method,
    hc.base_url,
    s.api_path,
    s.is_active,
    s.idempotency_ttl_hours,
    -- outbound_resilience_configurations (prefix r_ on shared column names)
    r.id                           AS r_id,
    r.read_time_out_ms,
    r.cb_failure_rate_threshold,
    r.cb_slow_call_rate_threshold,
    r.cb_slow_call_duration_ms,
    r.cb_permitted_in_half_open,
    r.cb_sliding_window_size,
    r.cb_wait_duration_open_ms,
    r.bh_max_concurrent_calls,
    r.bh_max_wait_duration_ms,
    r.rl_limit_for_period,
    r.rl_limit_refresh_period_ms,
    r.rl_timeout_duration_ms,
    r.retry_max_attempts,
    r.retry_wait_duration_ms,
    r.retry_on_status_codes,
    r.circuit_breaker_enabled,
    r.bulkhead_enabled,
    r.rate_limiter_enabled,
    r.retry_enabled,
    -- outbound_header_configurations (raw JSON; row mapper scrubs sensitive keys)
    h.headers                      AS h_headers,
    -- outbound_auth_configurations (prefix a_ on shared column names; no secret bytes)
    a.id                           AS a_id,
    a.auth_type,
    a.is_auto_refresh,
    a.token_method,
    a.token_url,
    a.token_body,
    a.token_prefix,
    a.token_inject_location,
    a.token_key_name,
    a.token_response_path,
    a.token_response_expiry_path,
    -- outbound_response_mapper_configuration (prefix rm_ on shared column names)
    rm.id                          AS rm_id,
    rm.converter_type,
    rm.mapping_script,
    rm.mapping_status,
    rm.is_active                   AS rm_is_active,
    -- outbound_host_configurations (prefix hc_ on shared column names; no proxy bytes)
    hc.id                          AS hc_id,
    hc.base_url                    AS hc_base_url,
    hc.connect_time_out_ms,
    hc.max_connections,
    hc.pending_acquire_max_count,
    hc.pending_acquire_timeout_ms,
    hc.is_disable_ssl_verification,
    hc.is_use_proxy,
    hc.proxy_host,
    hc.proxy_port
FROM outbound_systems s
JOIN outbound_host_configurations hc            ON hc.id = s.host_configuration_id
LEFT JOIN outbound_resilience_configurations r  ON r.outbound_system_id  = s.id
LEFT JOIN outbound_header_configurations h      ON h.outbound_system_id  = s.id
LEFT JOIN outbound_auth_configurations a        ON a.outbound_system_id  = s.id
LEFT JOIN outbound_response_mapper_configuration rm ON rm.outbound_system_id = s.id;
