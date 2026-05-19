-- Drop the UNIQUE constraint that prevents key reuse after TTL expiry.
-- The application-level TTL check (created_date > cutoff) is the primary guard;
-- the DB constraint was too strict (it blocked re-use of the same key across TTL windows).
ALTER TABLE outbound_histories DROP CONSTRAINT IF EXISTS uq_idempotency;

-- Rebuild idempotency index to include created_date so the TTL range query is index-covered.
DROP INDEX IF EXISTS idx_histories_idempotency;
CREATE INDEX idx_histories_idempotency
    ON outbound_histories (outbound_system_id, idempotency_key, created_date);
