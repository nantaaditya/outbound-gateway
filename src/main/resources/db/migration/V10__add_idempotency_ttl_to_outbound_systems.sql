ALTER TABLE outbound_systems
    ADD COLUMN idempotency_ttl_hours INT NOT NULL DEFAULT 24;
