create table mf_watering_automation (
    user_id uuid primary key references mf_users(id) on delete cascade,
    enabled boolean not null default false,
    interval_minutes int not null default 60,
    updated_at timestamptz not null default now()
);

