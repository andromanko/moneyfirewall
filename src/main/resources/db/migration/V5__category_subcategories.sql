alter table mf_categories
    add column parent_category_id uuid null;

alter table mf_categories
    add constraint mf_categories_parent_fk
        foreign key (parent_category_id) references mf_categories(id) on delete set null;

alter table mf_categories
    drop constraint if exists mf_categories_budget_id_kind_name_key;

alter table mf_categories
    add constraint mf_categories_budget_kind_parent_name_uq unique (budget_id, kind, parent_category_id, name);

create index mf_categories_budget_kind_parent_idx
    on mf_categories(budget_id, kind, parent_category_id);

