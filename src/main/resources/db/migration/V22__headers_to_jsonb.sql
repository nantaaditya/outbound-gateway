ALTER TABLE outbound_histories
  ALTER COLUMN request_headers  TYPE JSONB USING request_headers::JSONB,
  ALTER COLUMN response_headers TYPE JSONB USING response_headers::JSONB;
