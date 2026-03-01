CREATE TABLE IF NOT EXISTS event_logs (
    id varchar(25) primary key,
    client_id varchar(25),
    request_id varchar(64),
    method varchar(10),
    path text,
    response_code varchar(5),
    response_description text,
    payload bytea,
    additional_data bytea,
    created_date timestamp
);