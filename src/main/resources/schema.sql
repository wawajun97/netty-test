create table if not exists robot_status_history (
    id bigint auto_increment primary key,
    robot_type tinyint not null,
    robot_id varchar(128) not null,
    occurred_at timestamp not null,
    status_code tinyint not null,
    battery_percent tinyint not null,
    created_at timestamp default current_timestamp not null
);

create table if not exists robot_position_history (
    id bigint auto_increment primary key,
    robot_type tinyint not null,
    robot_id varchar(128) not null,
    occurred_at timestamp not null,
    pos_x double not null,
    pos_y double not null,
    heading_deg real not null,
    created_at timestamp default current_timestamp not null
);

create table if not exists robot_ingest_failure (
    id bigint auto_increment primary key,
    robot_type tinyint not null,
    op_code tinyint not null,
    robot_id varchar(128),
    error_message varchar(512) not null,
    created_at timestamp default current_timestamp not null
);
