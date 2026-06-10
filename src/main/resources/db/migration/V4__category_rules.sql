create table mf_category_rules (
    id uuid primary key default gen_random_uuid(),
    budget_id uuid not null references mf_budgets(id) on delete cascade,
    category_id uuid not null references mf_categories(id) on delete cascade,
    pattern text not null,
    priority int not null default 100,
    is_regex boolean not null default false,
    created_at timestamptz not null default now()
);

create index mf_category_rules_budget_priority_idx on mf_category_rules(budget_id, priority asc);

