#!/usr/bin/env bash
# One-off copy of an existing install's login connection and group grants from LexaVault's old tables into the
# platform idn_ tables (task 0037). The old tables are not changed. Run after the schema step and setup.
#
# Usage: scripts/Invoke-LexaVault-IdentityMigration.sh plan|apply
# Database settings in the environment only (never printed):
#   LEXAVAULT_MIGRATE_DB_URL, LEXAVAULT_MIGRATE_DB_USER, LEXAVAULT_MIGRATE_DB_PASSWORD
# Optional: LEXAVAULT_JAR. See docs/install/IDENTITY_MIGRATION.md.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="${LEXAVAULT_JAR:-$repo_root/backend/securedoc-api/target/securedoc-api-0.1.0-SNAPSHOT.jar}"
[[ -f "$jar" ]] || { echo "Backend jar not found: $jar (build it first)." >&2; exit 2; }
exec java -cp "$jar" -Dloader.main=com.securedoc.install.IdentityMigration \
  org.springframework.boot.loader.launch.PropertiesLauncher "${1:-plan}"
