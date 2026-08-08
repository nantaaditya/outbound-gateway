CREATE TABLE IF NOT EXISTS dead_letter_process (
    id            BIGSERIAL PRIMARY KEY,
    created_by    VARCHAR(25),
    updated_by    VARCHAR(25),
    created_date  TIMESTAMP,
    updated_date  TIMESTAMP,
    version       INT,
    process_type  VARCHAR(50),
    process_name  VARCHAR(100),
    last_error    TEXT,
    payload       BYTEA,
    processed     BOOLEAN
);

CREATE INDEX IF NOT EXISTS idx_deadletterprocess_type_name_processed
    ON dead_letter_process (process_type, process_name, processed);

CREATE INDEX IF NOT EXISTS idx_deadletterprocess_createddate
    ON dead_letter_process (created_date) WHERE processed IS TRUE;
