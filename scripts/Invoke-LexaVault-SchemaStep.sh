#!/usr/bin/env bash
# LexaVault's schema step (task 0037): the app's tables (Liquibase) and the platform Flyway runs (identity,
# licensing), each with its own history table. The app never changes the database at startup.
#
# Usage: scripts/Invoke-LexaVault-SchemaStep.sh [apply|status|customer-installed] [--runtime-user NAME]
# Database settings in the environment only (never printed):
#   LEXAVAULT_SCHEMA_DB_URL, LEXAVAULT_SCHEMA_DB_USER, LEXAVAULT_SCHEMA_DB_PASSWORD
# Optional: LEXAVAULT_JAR (default backend/securedoc-api/target/securedoc-api-0.1.0-SNAPSHOT.jar).
# See docs/install/SCHEMA_STEP.md.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="${LEXAVAULT_JAR:-$repo_root/backend/securedoc-api/target/securedoc-api-0.1.0-SNAPSHOT.jar}"
if [[ ! -f "$jar" ]]; then
  echo "Backend jar not found: $jar (build it first: cd backend && mvn clean install)." >&2
  exit 2
fi
if [[ $# -eq 0 ]]; then set -- apply; fi
exec java -cp "$jar" -Dloader.main=com.securedoc.install.SchemaStep \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
