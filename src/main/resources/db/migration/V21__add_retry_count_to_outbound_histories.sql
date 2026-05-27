ALTER TABLE outbound_histories
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
