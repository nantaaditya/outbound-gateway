CREATE TABLE IF NOT EXISTS outbound_resilience_configurations
(
    id                  BIGSERIAL PRIMARY KEY,
    created_by          VARCHAR(25),
    updated_by          VARCHAR(25),
    created_date        TIMESTAMP,
    updated_date        TIMESTAMP,
    version             INT,
    outbound_system_id  INT,
    connect_time_out_ms INT,
    read_time_out_ms    INT,
    write_time_out_ms   INT,
    core_pool_size      INT,
    queue_size          INT,
    max_pool_size       INT
);

CREATE INDEX IF NOT EXISTS idx_outboundresilience_systemid on outbound_resilience_configurations(outbound_system_id);