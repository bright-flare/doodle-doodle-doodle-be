create table if not exists game_sessions (
    id varchar(64) primary key,
    payload text not null,
    updated_at bigint not null
);

create table if not exists game_rooms (
    code varchar(16) primary key,
    payload text not null,
    updated_at bigint not null
);
