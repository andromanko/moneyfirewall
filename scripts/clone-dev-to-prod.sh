#!/usr/bin/env bash
# DESTRUCTIVE: wipes all app tables in prod and replaces them with a full
# copy of dev's data. Prod's current data is permanently lost except for
# the automatic backup this script takes first.
#
# What it does, in order:
#   1. Prompts for DEV and PROD connection details (host/port/db/user/password).
#      Look these up in DBeaver's connection settings if you don't remember them.
#   2. Shows current row counts on both sides and asks for a typed confirmation.
#   3. Dumps PROD's current data to a timestamped .sql backup file (data-only,
#      excludes flyway_schema_history) — this is the only way back if something
#      is wrong.
#   4. TRUNCATEs every mf_* table in PROD (CASCADE, single statement).
#   5. Dumps DEV's data and restores it into PROD, all inside one transaction
#      (truncate + restore succeed or fail together).
#   6. Prints final row counts for both sides so you can compare.
#
# flyway_schema_history is never touched — only mf_* application tables.
#
# Usage:
#   ./scripts/clone-dev-to-prod.sh
# You will be prompted for everything; nothing is read from DBeaver directly.

set -euo pipefail

APP_TABLES=(
    mf_transactions
    mf_asset_events
    mf_import_sessions
    mf_merchant_aliases
    mf_category_rules
    mf_accounts
    mf_categories
    mf_budget_members
    mf_user_settings
    mf_transfer_groups
    mf_budgets
    mf_users
)

# Sets <PREFIX>_HOST/_PORT/_DB/_USER/_PASSWORD globals directly (no stdout
# capture) so a prompt's text can never end up parsed as a connection value.
prompt_conn() {
    local label="$1" prefix="$2" host port db user password
    echo "--- $label connection ---"
    read -r -p "  host [localhost]: " host; host="${host:-localhost}"
    read -r -p "  port [5432]: " port; port="${port:-5432}"
    read -r -p "  database: " db
    read -r -p "  user: " user
    read -r -s -p "  password: " password; echo
    printf -v "${prefix}_HOST" '%s' "$host"
    printf -v "${prefix}_PORT" '%s' "$port"
    printf -v "${prefix}_DB" '%s' "$db"
    printf -v "${prefix}_USER" '%s' "$user"
    printf -v "${prefix}_PASSWORD" '%s' "$password"
}

prompt_conn "DEV (source)" DEV
prompt_conn "PROD (target — will be wiped)" PROD

psql_dev() { PGPASSWORD="$DEV_PASSWORD" psql -X -q -v ON_ERROR_STOP=1 -h "$DEV_HOST" -p "$DEV_PORT" -d "$DEV_DB" -U "$DEV_USER" "$@"; }
psql_prod() { PGPASSWORD="$PROD_PASSWORD" psql -X -q -v ON_ERROR_STOP=1 -h "$PROD_HOST" -p "$PROD_PORT" -d "$PROD_DB" -U "$PROD_USER" "$@"; }
pgdump_dev() { PGPASSWORD="$DEV_PASSWORD" pg_dump -h "$DEV_HOST" -p "$DEV_PORT" -d "$DEV_DB" -U "$DEV_USER" "$@"; }
pgdump_prod() { PGPASSWORD="$PROD_PASSWORD" pg_dump -h "$PROD_HOST" -p "$PROD_PORT" -d "$PROD_DB" -U "$PROD_USER" "$@"; }

echo "==> Verifying connections"
psql_dev -c "select 1" >/dev/null
psql_prod -c "select 1" >/dev/null
echo "    both OK"

row_counts() {
    local fn="$1"
    for t in "${APP_TABLES[@]}"; do
        printf '%-22s ' "$t"
        "$fn" -t -A -c "select count(*) from $t"
    done
}

echo
echo "==> Current row counts — DEV ($DEV_HOST/$DEV_DB):"
row_counts psql_dev
echo
echo "==> Current row counts — PROD ($PROD_HOST/$PROD_DB), about to be wiped:"
row_counts psql_prod
echo

read -r -p "Type EXACTLY 'WIPE PROD' to truncate PROD and replace it with DEV's data: " confirm
if [[ "$confirm" != "WIPE PROD" ]]; then
    echo "Aborted, nothing was touched." >&2
    exit 1
fi

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

BACKUP_FILE="$(pwd)/prod-backup-$(date -u +%Y%m%dT%H%M%SZ).sql"
echo "==> Backing up current PROD data to $BACKUP_FILE"
pgdump_prod --data-only --exclude-table=flyway_schema_history > "$BACKUP_FILE"
echo "    $(wc -l < "$BACKUP_FILE") lines written. Keep this file until you're sure the clone is good."
echo "    To restore it if needed: psql -h $PROD_HOST -p $PROD_PORT -d $PROD_DB -U $PROD_USER -f '$BACKUP_FILE'"

echo "==> Dumping DEV data"
pgdump_dev --data-only --exclude-table=flyway_schema_history > "$WORKDIR/dev-data.sql"

TRUNCATE_LIST="$(IFS=,; echo "${APP_TABLES[*]}")"

echo "==> Truncating PROD tables and restoring DEV data (one transaction)"
{
    echo "begin;"
    echo "truncate table $TRUNCATE_LIST cascade;"
    cat "$WORKDIR/dev-data.sql"
    echo "commit;"
} > "$WORKDIR/apply.sql"

psql_prod -f "$WORKDIR/apply.sql"

echo
echo "==> Final row counts — PROD ($PROD_HOST/$PROD_DB):"
row_counts psql_prod

echo
echo "==> Done. Backup of the old PROD data is at: $BACKUP_FILE"
