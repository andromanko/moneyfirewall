-- Savings ("Накопления") subcategories carry the currency/crypto they are held in, so their
-- totals can be shown both in the report currency and in their own unit. Null everywhere else.
alter table mf_categories
    add column currency text;

-- Special top-level category, seeded per budget like CASH (V6), so import rules can target its
-- subcategories immediately. Excluded from expense totals in reports.
insert into mf_categories (budget_id, name, kind, created_at, parent_category_id)
select b.id, 'Накопления', 'EXPENSE', now(), null
from mf_budgets b
where not exists (
    select 1
    from mf_categories c
    where c.budget_id = b.id
      and c.kind = 'EXPENSE'
      and c.parent_category_id is null
      and lower(c.name) = lower('Накопления')
);
