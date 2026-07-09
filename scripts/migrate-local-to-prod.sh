#!/usr/bin/env bash
# Copies users, categories/subcategories and accounts from the local DB to prod.
#
# Matching rules (natural keys, not raw UUIDs — local and prod generate
# independent gen_random_uuid() values so ids never line up):
#   - users:      matched by telegram_user_id (unique in mf_users)
#   - budgets:    matched by name; a budget MUST already exist in prod under
#                 the same name. Names that don't match exactly one prod
#                 budget are reported and skipped.
#   - categories: matched by (budget, kind, parent name, name). Parents are
#                 inserted before children.
#   - accounts:   matched by (budget, account name), owner resolved via the
#                 owner's telegram_user_id.
#
# Nothing is deleted or overwritten; rows that already exist in prod (by the
# natural key above) are left untouched. All prod writes happen in a single
# transaction, so a failure rolls back cleanly.
#
# Source (local) connection defaults to this repo's docker-compose Postgres.
# Target (prod) connection has no defaults — you must provide it.
#
# Usage:
#   TARGET_PGHOST=... TARGET_PGPORT=... TARGET_PGDATABASE=... \
#   TARGET_PGUSER=... TARGET_PGPASSWORD=... \
#   ./scripts/migrate-local-to-prod.sh
#
# Optional overrides for the source: SOURCE_PGHOST, SOURCE_PGPORT,
# SOURCE_PGDATABASE, SOURCE_PGUSER, SOURCE_PGPASSWORD.

set -euo pipefail

SOURCE_PGHOST="${SOURCE_PGHOST:-localhost}"
SOURCE_PGPORT="${SOURCE_PGPORT:-5433}"
SOURCE_PGDATABASE="${SOURCE_PGDATABASE:-fin-bot-db}"
SOURCE_PGUSER="${SOURCE_PGUSER:-moneyfirewall}"
SOURCE_PGPASSWORD="${SOURCE_PGPASSWORD:-abezyany_TUT_niprichEM!!!-)))}"

: "${TARGET_PGHOST:?Set TARGET_PGHOST (prod DB host)}"
: "${TARGET_PGPORT:?Set TARGET_PGPORT (prod DB port)}"
: "${TARGET_PGDATABASE:?Set TARGET_PGDATABASE (prod DB name)}"
: "${TARGET_PGUSER:?Set TARGET_PGUSER (prod DB user)}"
: "${TARGET_PGPASSWORD:?Set TARGET_PGPASSWORD (prod DB password)}"

psql_src() {
    PGPASSWORD="$SOURCE_PGPASSWORD" psql -X -q -v ON_ERROR_STOP=1 \
        -h "$SOURCE_PGHOST" -p "$SOURCE_PGPORT" -d "$SOURCE_PGDATABASE" -U "$SOURCE_PGUSER" "$@"
}

psql_tgt() {
    PGPASSWORD="$TARGET_PGPASSWORD" psql -X -q -v ON_ERROR_STOP=1 \
        -h "$TARGET_PGHOST" -p "$TARGET_PGPORT" -d "$TARGET_PGDATABASE" -U "$TARGET_PGUSER" "$@"
}

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

echo "==> Exporting from local DB ($SOURCE_PGHOST:$SOURCE_PGPORT/$SOURCE_PGDATABASE)"

psql_src -c "\copy (select telegram_user_id, telegram_chat_id, display_name, is_active from mf_users) to '$WORKDIR/users.csv' with csv"

psql_src -c "\copy (
    select b.name as budget_name, c.kind, p.name as parent_name, c.name
    from mf_categories c
    join mf_budgets b on b.id = c.budget_id
    left join mf_categories p on p.id = c.parent_category_id
) to '$WORKDIR/categories.csv' with csv"

psql_src -c "\copy (
    select b.name as budget_name, u.telegram_user_id as owner_telegram_user_id, a.name, a.type, a.currency
    from mf_accounts a
    join mf_budgets b on b.id = a.budget_id
    left join mf_users u on u.id = a.owner_user_id
) to '$WORKDIR/accounts.csv' with csv"

echo "==> Loaded: $(wc -l < "$WORKDIR/users.csv") users, $(wc -l < "$WORKDIR/categories.csv") categories, $(wc -l < "$WORKDIR/accounts.csv") accounts"

echo "==> Importing into prod ($TARGET_PGHOST:$TARGET_PGPORT/$TARGET_PGDATABASE), single transaction"

psql_tgt <<SQL
begin;

create temp table tmp_users (
    telegram_user_id bigint,
    telegram_chat_id bigint,
    display_name text,
    is_active boolean
);
\copy tmp_users from '$WORKDIR/users.csv' with csv

create temp table tmp_categories (
    budget_name text,
    kind text,
    parent_name text,
    name text
);
\copy tmp_categories from '$WORKDIR/categories.csv' with csv

create temp table tmp_accounts (
    budget_name text,
    owner_telegram_user_id bigint,
    name text,
    type text,
    currency text
);
\copy tmp_accounts from '$WORKDIR/accounts.csv' with csv

-- Budgets that exist under a unique name in prod; ambiguous/missing names are reported and skipped.
create temp table tmp_budgets_ok as
select b.id, b.name
from mf_budgets b
where (select count(*) from mf_budgets b2 where b2.name = b.name) = 1;

\echo '--- Budget names present locally but not matched 1:1 in prod (their categories/accounts will be skipped): ---'
select distinct t.budget_name
from (
    select budget_name from tmp_categories
    union
    select budget_name from tmp_accounts
) t
left join tmp_budgets_ok b on b.name = t.budget_name
where b.id is null;

-- 1) Users: upsert by telegram_user_id.
insert into mf_users (telegram_user_id, telegram_chat_id, display_name, is_active)
select telegram_user_id, telegram_chat_id, display_name, is_active
from tmp_users
on conflict (telegram_user_id) do update
    set telegram_chat_id = excluded.telegram_chat_id,
        display_name = excluded.display_name,
        is_active = excluded.is_active;

-- 2) Categories, parents first (parent_name is null).
insert into mf_categories (budget_id, name, kind, parent_category_id)
select b.id, t.name, t.kind, null
from tmp_categories t
join tmp_budgets_ok b on b.name = t.budget_name
where t.parent_name is null
  and not exists (
      select 1 from mf_categories c
      where c.budget_id = b.id and c.kind = t.kind
        and c.parent_category_id is null and c.name = t.name
  );

-- 3) Categories, children (resolve parent by name within the same budget/kind).
insert into mf_categories (budget_id, name, kind, parent_category_id)
select b.id, t.name, t.kind, p.id
from tmp_categories t
join tmp_budgets_ok b on b.name = t.budget_name
join mf_categories p on p.budget_id = b.id and p.kind = t.kind
    and p.parent_category_id is null and p.name = t.parent_name
where t.parent_name is not null
  and not exists (
      select 1 from mf_categories c
      where c.budget_id = b.id and c.kind = t.kind
        and c.parent_category_id = p.id and c.name = t.name
  );

-- 4) Accounts, owner resolved by telegram_user_id (nullable).
insert into mf_accounts (budget_id, owner_user_id, name, type, currency)
select b.id, u.id, t.name, t.type, t.currency
from tmp_accounts t
join tmp_budgets_ok b on b.name = t.budget_name
left join mf_users u on u.telegram_user_id = t.owner_telegram_user_id
where not exists (
      select 1 from mf_accounts a
      where a.budget_id = b.id and a.name = t.name
  );

commit;
SQL

echo "==> Done."
