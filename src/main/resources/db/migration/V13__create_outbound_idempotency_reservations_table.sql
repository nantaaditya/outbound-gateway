-- Cross-instance "this idempotency key is being executed right now" marker.
--
-- outbound_histories has no UNIQUE (system, key) on purpose: the same key may be reused once the system's
-- idempotency TTL has passed. A history row only exists AFTER the upstream answered, so it cannot stop two
-- instances from calling the upstream at the same time. This table can: the instance that inserts the row
-- owns the key while it executes; everyone else waits for the history row instead of calling the upstream.
--
-- Rows are deleted by their owner when the execution ends. A row left behind by a crashed instance is
-- ignored once expires_at has passed and is taken over by the next request for that key. All timestamps
-- come from the database clock (NOW()), so instance clock skew does not matter.
CREATE TABLE IF NOT EXISTS outbound_idempotency_reservations (
    outbound_system_id BIGINT       NOT NULL,
    idempotency_key    VARCHAR(255) NOT NULL,
    owner              VARCHAR(64)  NOT NULL,
    expires_at         TIMESTAMP    NOT NULL,
    created_date       TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (outbound_system_id, idempotency_key)
);
