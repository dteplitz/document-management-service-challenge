#!/usr/bin/env bash
# =============================================================================
# Create the application database user (document_user).
# Runs before 01-schema-init.sql. Reads APP_DB_PASSWORD from the container
# environment. Kept as a .sh wrapper because .sql files cannot interpolate
# env vars directly — psql variable substitution requires -v at invocation.
# =============================================================================
set -euo pipefail

if [[ -z "${APP_DB_PASSWORD:-}" ]]; then
    echo "ERROR: APP_DB_PASSWORD is not set in the container environment" >&2
    exit 1
fi

psql -v ON_ERROR_STOP=1 \
     --username "${POSTGRES_USER}" \
     --dbname "challenge" \
     -v app_db_password="${APP_DB_PASSWORD}" <<-EOSQL
    CREATE ROLE document_user LOGIN PASSWORD :'app_db_password';
EOSQL

echo "document_user role created."
