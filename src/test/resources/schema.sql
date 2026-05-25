CREATE TABLE IF NOT EXISTS event_logs (                                     id varchar(25) primary key,
    client_id varchar(25),
    request_id varchar(64),
    method varchar(10),
    path text,
    response_code varchar(5),
    response_description text,
    payload bytea,
    additional_data bytea,
    created_date timestamp
);

create table if not exists dead_letter_process (
    id bigserial primary key,
    created_by varchar(25),
    updated_by varchar(25),
    created_date timestamp,
    updated_date timestamp,
    version int,
    process_type varchar(50),
    process_name varchar(100),
    last_error text,
    payload bytea,
    processed boolean
);

create index if not exists idx_deadletterprocess_type_name_processed on dead_letter_process(process_type, process_name, processed);

create table if not exists outbound_host_configurations (
    id                          bigserial primary key,
    created_by                  varchar(25),
    updated_by                  varchar(25),
    created_date                timestamp not null default now(),
    updated_date                timestamp not null default now(),
    version                     int,
    base_url                    varchar(500) not null unique,
    connect_time_out_ms         int          not null default 5000,
    max_connections             int          not null default 500,
    pending_acquire_max_count   int          not null default 1000,
    pending_acquire_timeout_ms  int          not null default 30000,
    is_disable_ssl_verification boolean      not null default false,
    is_use_proxy                boolean      not null default false,
    proxy_host                  varchar(255),
    proxy_port                  int,
    proxy_username              bytea,
    proxy_password              bytea
);

create table if not exists outbound_systems (
    id                          bigserial primary key,
    created_by                  varchar(25),
    updated_by                  varchar(25),
    created_date                timestamp,
    updated_date                timestamp,
    version                     int,
    code                        varchar(100) not null unique,
    name                        varchar(255) not null,
    description                 text,
    http_method                 varchar(10)  not null,
    base_url                    varchar(500) not null,
    is_active                   boolean      not null default true,
    idempotency_ttl_hours       int          not null default 24,
    api_path                    varchar(500) not null default ''
);

create table if not exists outbound_resilience_configurations (
    id                          bigserial primary key,
    created_by                  varchar(25),
    updated_by                  varchar(25),
    created_date                timestamp,
    updated_date                timestamp,
    version                     int,
    outbound_system_id          bigint,
    read_time_out_ms            int,
    cb_failure_rate_threshold   float   default 50,
    cb_slow_call_rate_threshold float   default 100,
    cb_slow_call_duration_ms    int     default 60000,
    cb_permitted_in_half_open   int     default 10,
    cb_sliding_window_size      int     default 100,
    cb_wait_duration_open_ms    int     default 60000,
    bh_max_concurrent_calls     int     default 25,
    bh_max_wait_duration_ms     int     default 0,
    rl_limit_for_period         int     default 100,
    rl_limit_refresh_period_ms  int     default 1000,
    rl_timeout_duration_ms      int     default 0,
    retry_max_attempts          int     default 3,
    retry_wait_duration_ms      int     default 500,
    retry_on_status_codes       varchar(255) default '500,502,503',
    circuit_breaker_enabled     boolean not null default false,
    bulkhead_enabled            boolean not null default false,
    rate_limiter_enabled        boolean not null default false,
    retry_enabled               boolean not null default false
);

create table if not exists outbound_header_configurations (
    id                  bigserial primary key,
    created_by          varchar(25),
    updated_by          varchar(25),
    created_date        timestamp,
    updated_date        timestamp,
    version             int,
    outbound_system_id  bigint,
    headers             bytea
);

create table if not exists outbound_auth_configurations (
    id                          bigserial primary key,
    created_by                  varchar(25),
    updated_by                  varchar(25),
    created_date                timestamp,
    updated_date                timestamp,
    version                     int,
    outbound_system_id          bigint,
    auth_type                   varchar(20),
    api_key                     bytea,
    client_id                   bytea,
    client_secret               bytea,
    is_auto_refresh             boolean,
    token_method                varchar(10),
    token_url                   varchar(500),
    token_body                  text,
    token_prefix                varchar(50),
    token_inject_location       varchar(50),
    token_key_name              varchar(255),
    token_response_path         varchar(100),
    token_response_expiry_path  varchar(100),
    current_token               text,
    token_expiry_time           timestamp
);

create table if not exists outbound_response_mapper_configuration (
    id                  bigserial primary key,
    created_by          varchar(25),
    updated_by          varchar(25),
    created_date        timestamp,
    updated_date        timestamp,
    version             int,
    outbound_system_id  bigint,
    converter_type      varchar(50),
    mapping_script      text,
    mapping_status      bytea,
    is_active           boolean
);

create table if not exists outbound_histories (
    id                   bigserial primary key,
    outbound_system_id   bigint       not null,
    idempotency_key      varchar(255),
    caller_service       varchar(100),
    request_id           varchar(255),
    request_method       varchar(10),
    request_url          varchar(1000),
    request_headers      text,
    request_payload      text,
    response_status      int,
    response_headers     text,
    response_payload     text,
    duration_ms          int,
    converted_status     int,
    converted_response   text,
    created_date         timestamp    not null default now()
);

create index if not exists idx_histories_system_date     on outbound_histories (outbound_system_id, created_date);
create index if not exists idx_histories_idempotency     on outbound_histories (outbound_system_id, idempotency_key, created_date);
create index if not exists idx_histories_response_status on outbound_histories (outbound_system_id, response_status);

create view if not exists v_outbound_system_detail as
select
    s.id,
    s.code,
    s.name,
    s.description,
    s.http_method,
    s.base_url,
    s.api_path,
    s.is_active,
    s.idempotency_ttl_hours,
    r.id                           as r_id,
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
    h.headers                      as h_headers,
    a.id                           as a_id,
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
    rm.id                          as rm_id,
    rm.converter_type,
    rm.mapping_script,
    rm.mapping_status,
    rm.is_active                   as rm_is_active,
    hc.id                          as hc_id,
    hc.base_url                    as hc_base_url,
    hc.connect_time_out_ms,
    hc.max_connections,
    hc.pending_acquire_max_count,
    hc.pending_acquire_timeout_ms,
    hc.is_disable_ssl_verification,
    hc.is_use_proxy,
    hc.proxy_host,
    hc.proxy_port
from outbound_systems s
left join outbound_resilience_configurations r  on r.outbound_system_id  = s.id
left join outbound_header_configurations h      on h.outbound_system_id  = s.id
left join outbound_auth_configurations a        on a.outbound_system_id  = s.id
left join outbound_response_mapper_configuration rm on rm.outbound_system_id = s.id
left join outbound_host_configurations hc       on hc.base_url           = s.base_url;