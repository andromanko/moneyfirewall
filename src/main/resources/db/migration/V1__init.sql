create extension if not exists pgcrypto;

create table mf_users (
    id uuid primary key default gen_random_uuid(),
    telegram_user_id bigint not null unique,
    telegram_chat_id bigint not null,
    display_name text not null,
    is_active boolean not null default true,
    created_at timestamptz not null default now()
);

create table mf_budgets (
    id uuid primary key default gen_random_uuid(),
    name text not null,
    created_at timestamptz not null default now()
);

create table mf_budget_members (
    id uuid primary key default gen_random_uuid(),
    budget_id uuid not null references mf_budgets(id) on delete cascade,
    user_id uuid not null references mf_users(id) on delete cascade,
    role text not null,
    created_at timestamptz not null default now(),
    unique (budget_id, user_id)
);

create table mf_user_settings (
    user_id uuid primary key references mf_users(id) on delete cascade,
    active_budget_id uuid null references mf_budgets(id) on delete set null,
    state_key text null,
    state_payload jsonb null,
    updated_at timestamptz not null default now()
);

create table mf_accounts (
    id uuid primary key default gen_random_uuid(),
    budget_id uuid not null references mf_budgets(id) on delete cascade,
    owner_user_id uuid null references mf_users(id) on delete set null,
    name text not null,
    type text not null,
    currency text not null,
    created_at timestamptz not null default now()
);

create index mf_accounts_budget_idx on mf_accounts(budget_id);

create table mf_categories (
    id uuid primary key default gen_random_uuid(),
    budget_id uuid not null references mf_budgets(id) on delete cascade,
    name text not null,
    kind text not null,
    created_at timestamptz not null default now(),
    unique (budget_id, kind, name)
);

create table mf_transfer_groups (
    id uuid primary key default gen_random_uuid(),
    budget_id uuid not null references mf_budgets(id) on delete cascade,
    created_at timestamptz not null default now()
);

create table mf_transactions (
    id uuid primary key default gen_random_uuid(),
    budget_id uuid not null references mf_budgets(id) on delete cascade,
    user_id uuid null references mf_users(id) on delete set null,
    direction text not null,
    occurred_at timestamptz not null,
    amount numeric(18,2) not null,
    currency text not null,
    account_id uuid not null references mf_accounts(id) on delete restrict,
    category_id uuid null references mf_categories(id) on delete set null,
    counterparty_raw text null,
    counterparty_normalized text null,
    description text null,
    source text not null,
    external_hash text null,
    transfer_group_id uuid null references mf_transfer_groups(id) on delete set null,
    import_session_id uuid null,
    created_at timestamptz not null default now()
);

create index mf_tx_budget_time_idx on mf_transactions(budget_id, occurred_at desc);
create unique index mf_tx_budget_external_hash_uq
    on mf_transactions(budget_id, external_hash)
    where external_hash is not null;

create table mf_import_sessions (
    id uuid primary key default gen_random_uuid(),
    budget_id uuid not null references mf_budgets(id) on delete cascade,
    uploaded_by_user_id uuid not null references mf_users(id) on delete cascade,
    bank_code text not null,
    file_type text not null,
    telegram_file_id text not null,
    sha256 text not null,
    status text not null,
    created_at timestamptz not null default now()
);

create unique index mf_import_budget_sha_uq on mf_import_sessions(budget_id, sha256);

alter table mf_transactions
    add constraint mf_tx_import_session_fk
        foreign key (import_session_id) references mf_import_sessions(id) on delete set null;

create table mf_merchant_aliases (
    id uuid primary key default gen_random_uuid(),
    budget_id uuid not null references mf_budgets(id) on delete cascade,
    pattern text not null,
    normalized_name text not null,
    priority int not null default 100,
    is_regex boolean not null default false,
    created_at timestamptz not null default now()
);

create index mf_alias_budget_priority_idx on mf_merchant_aliases(budget_id, priority asc);

create table mf_asset_events (
    id uuid primary key default gen_random_uuid(),
    budget_id uuid not null references mf_budgets(id) on delete cascade,
    user_id uuid null references mf_users(id) on delete set null,
    occurred_at timestamptz not null,
    event_type text not null,
    asset_symbol text not null,
    quantity numeric(36,18) not null,
    price_amount numeric(18,2) null,
    price_currency text null,
    fee_amount numeric(18,2) null,
    fee_currency text null,
    description text null,
    created_at timestamptz not null default now()
);

create index mf_asset_budget_time_idx on mf_asset_events(budget_id, occurred_at desc);

