alter table mf_category_rules
    add column account_name text null,
    add column min_amount numeric(18, 2) null,
    add column exact_amount numeric(18, 2) null,
    add column once_per_month boolean not null default false;
