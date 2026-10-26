-- SHA-256 (hex) of the client's request (query parameters, body, injected keys) for rows stored under an
-- idempotency key. A later request with the same key but a different fingerprint is rejected instead of being
-- answered with this row. NULL for rows without a key and for rows written before this column existed (those
-- are replayed as before).
ALTER TABLE outbound_histories
    ADD COLUMN IF NOT EXISTS request_fingerprint VARCHAR(64);
