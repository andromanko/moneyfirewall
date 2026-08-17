-- Free-form user tags/comments on a transaction (e.g. "#корпоратив"), independent of category and
-- separate from the bank-statement "description" column already populated on import.
alter table mf_transactions
    add column tags text;
