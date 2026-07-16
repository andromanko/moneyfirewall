alter table mf_transactions
    add column balance_after numeric(18, 2) null,
    add column balance_currency text null;
