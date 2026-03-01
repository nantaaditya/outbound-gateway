CREATE TABLE IF NOT EXISTS event_logs (                                     id varchar(25) primary key,
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

create table if not exists dead_letter_process (
    id bigserial primary key,
    created_by varchar(25),
    updated_by varchar(25),
    created_date timestamp,
    updated_date timestamp,
    version int,
    process_type varchar(50),
    process_name varchar(100),
    last_error text,
    payload bytea,
    processed boolean
);

create index if not exists idx_deadletterprocess_type_name_processed on dead_letter_process(process_type, process_name, processed);