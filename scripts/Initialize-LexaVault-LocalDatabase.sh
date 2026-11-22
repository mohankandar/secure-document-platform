#!/usr/bin/env bash
# Brings a local development database up to date with the real install steps (task 0037), in order: the schema step,
# then setup with the demo input files while the demo step has not made the demo entity yet (task 0037 fix 1: also a
# database from before 0037, or one whose demo step stopped half-way), then the LexaVault demo step. The start scripts
# call this before starting the backend. Running it again is safe. Optional: LEXAVAULT_STATE_DIR (default .run) for setup's
# config fragment and run log.
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

state_dir="${LEXAVAULT_STATE_DIR:-$repo_root/.run}"
mkdir -p -- "$state_dir"
demo="$repo_root/install/demo"
keys="$demo/development-trusted-keys.json"

set +e
LEXAVAULT_DEMO_DB_URL="$db_url" LEXAVAULT_DEMO_DB_USER="$db_user" LEXAVAULT_DEMO_DB_PASSWORD="$db_password" \
  run_main com.securedoc.install.DemoStep installed
installed=$?
set -e
if [[ $installed -eq 4 ]]; then
  echo "Local database: the demo step has not run yet, so setup runs with the demo input files (install/demo)..."
  PLATFORM_SETUP_DB_URL="$db_url" PLATFORM_SETUP_DB_USER="$db_user" PLATFORM_SETUP_DB_PASSWORD="$db_password" \
    run_main com.documentplatform.setup.SetupCommand apply --licence "$demo/licence-demo.json" \
      --signin "$demo/signin-demo.json" --product LEXAVAULT --keys "$keys" \
      --product-roles "$repo_root/install/product-roles.json" \
      --config-out "$state_dir/platform-tenant.properties" --run-log "$state_dir/platform-setup-runs.log" \
      --no-customer-keys
elif [[ $installed -ne 0 ]]; then
  echo "The demo step check failed (exit $installed)." >&2
  exit 1
fi

echo "Local database: LexaVault demo step..."
LEXAVAULT_DEMO_DB_URL="$db_url" LEXAVAULT_DEMO_DB_USER="$db_user" LEXAVAULT_DEMO_DB_PASSWORD="$db_password" \
  run_main com.securedoc.install.DemoStep apply --keys "$keys"
