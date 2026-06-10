insert into mf_categories (budget_id, name, kind, created_at, parent_category_id)
select b.id, 'CASH', 'EXPENSE', now(), null
from mf_budgets b
where not exists (
    select 1
    from mf_categories c
    where c.budget_id = b.id
      and c.kind = 'EXPENSE'
      and c.parent_category_id is null
      and lower(c.name) = 'cash'
);
