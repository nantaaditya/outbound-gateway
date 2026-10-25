-- Non-secret, per-type settings of an authentication type, e.g. {"timestampHeader": "X-Timestamp"} for HMAC_TIMESTAMP.
-- Secrets stay in the encrypted BYTEA columns; NULL for the types that need no extra settings.
ALTER TABLE outbound_auth_configurations
    ADD COLUMN IF NOT EXISTS auth_settings JSONB;
