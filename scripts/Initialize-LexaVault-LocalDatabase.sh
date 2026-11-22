#!/usr/bin/env bash
# Brings a local development database up to date with the real install steps (task 0037), in order: the schema step,
# then (on a fresh database only) setup with the demo input files and the LexaVault demo step. The start scripts call
# this before starting the backend. Running it again is safe.
#
# Database: LEXAVAULT_LOCAL_DB_URL (default jdbc:postgresql://localhost:5432/securedoc), LEXAVAULT_LOCAL_DB_USER
# (default securedoc), LEXAVAULT_LOCAL_DB_PASSWORD (required unless the defaults are used: then the development
# compose file's value). Passed to each step in its environment only; never printed.
# Optional: LEXAVAULT_JAR.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="${LEXAVAULT_JAR:-$repo_root/backend/securedoc-api/target/securedoc-api-0.1.0-SNAPSHOT.jar}"
[[ -f "$jar" ]] || { echo "Backend jar not found: $jar (build it first)." >&2; exit 2; }
db_url="${LEXAVAULT_LOCAL_DB_URL:-jdbc:postgresql://localhost:5432/securedoc}"
db_user="${LEXAVAULT_LOCAL_DB_USER:-securedoc}"
db_password="${LEXAVAULT_LOCAL_DB_PASSWORD:-}"
if [[ -z "$db_password" ]]; then
  if [[ "$db_url" != "jdbc:postgresql://localhost:5432/securedoc" || "$db_user" != "securedoc" ]]; then
    echo "Set LEXAVAULT_LOCAL_DB_PASSWORD for $db_url (only the default local database has a known development password)." >&2
    exit 2
  fi
  # The development compose file's value (docker/compose/postgres.yml), the same one application-local.yml uses.
  db_password="securedoc_dev_password"
fi

run_main() {
  local main="$1"; shift
  java -cp "$jar" -Dloader.main="$main" org.springframework.boot.loader.launch.PropertiesLauncher "$@"
}

echo "Local database: schema step..."
LEXAVAULT_SCHEMA_DB_URL="$db_url" LEXAVAULT_SCHEMA_DB_USER="$db_user" LEXAVAULT_SCHEMA_DB_PASSWORD="$db_password" \
  run_main com.securedoc.install.SchemaStep apply
