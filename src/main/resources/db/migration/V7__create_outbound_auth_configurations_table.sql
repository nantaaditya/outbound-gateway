CREATE TABLE IF NOT EXISTS outbound_auth_configurations (
    id                          BIGSERIAL PRIMARY KEY,
    created_by                  VARCHAR(25),
    updated_by                  VARCHAR(25),
    created_date                TIMESTAMP,
    updated_date                TIMESTAMP,
    version                     INT,
    outbound_system_id          BIGINT,
    auth_type                   VARCHAR(20),  -- NONE, API_KEY, BASIC, BEARER_STATIC, OAUTH, HMAC, RSA
    api_key                     BYTEA,        -- encrypted (AES-256-GCM)
    client_id                   BYTEA,        -- encrypted (AES-256-GCM)
    client_secret               BYTEA,        -- encrypted (AES-256-GCM)
    is_auto_refresh             BOOLEAN,
    token_method                VARCHAR(10),
    token_url                   VARCHAR(500),
    token_body                  TEXT,
    token_prefix                VARCHAR(50),  -- Bearer, Basic
    token_inject_location       VARCHAR(50),  -- HEADER, QUERY_PARAM, COOKIE
    token_key_name              VARCHAR(255), -- Authorization, X-API-KEY, etc.
    token_response_path         VARCHAR(100), -- $.access_token
    token_response_expiry_path  VARCHAR(100), -- $.expires_in
    current_token               TEXT,
    token_expiry_time           TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_outboundauth_systemid
    ON outbound_auth_configurations (outbound_system_id);
