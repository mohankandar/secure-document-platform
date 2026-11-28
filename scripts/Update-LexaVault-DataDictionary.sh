#!/usr/bin/env bash
# Task 0118: regenerate docs/data/DATA-DICTIONARY.md and docs/data/RELATIONSHIPS.md from the live schema.
# Starts a throwaway PostgreSQL 16 container (its own name, a free local port, a random password never printed), runs
# the schema step (every migration and the platform comments), reads the schema back, writes the two files and removes
# the container. It never touches any other database or container.
#
# Usage: scripts/Update-LexaVault-DataDictionary.sh [--check]
#   --check  write nothing; exit 1 when a table or column has no comment or the committed files are out of date
# Needs Docker and the backend jar (cd backend && mvn -Pdev-build clean install), or LEXAVAULT_JAR.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="${LEXAVAULT_JAR:-$repo_root/backend/securedoc-api/target/securedoc-api-0.1.0-SNAPSHOT.jar}"
mode=write
if [[ "${1:-}" == "--check" ]]; then mode=check; elif [[ $# -gt 0 ]]; then echo "Usage: $0 [--check]" >&2; exit 2; fi
if [[ ! -f "$jar" ]]; then echo "Backend jar not found: $jar (build it first: cd backend && mvn -Pdev-build clean install)." >&2; exit 2; fi

name="lv-data-dictionary-$$-$RANDOM"
password="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$name" -e POSTGRES_USER=lvdict -e POSTGRES_PASSWORD="$password" -e POSTGRES_DB=lvdict \
  -p 127.0.0.1::5432 postgres:16-alpine >/dev/null
for _ in $(seq 1 60); do
  # pg_isready answers during the image's first, temporary start too: wait for a real query over TCP.
  if docker exec "$name" psql -h 127.0.0.1 -U lvdict -d lvdict -Atc 'select 1' >/dev/null 2>&1; then break; fi
  sleep 1
done
port="$(docker port "$name" 5432/tcp | head -n 1 | sed 's/.*://')"

export LEXAVAULT_SCHEMA_DB_URL="jdbc:postgresql://127.0.0.1:${port}/lvdict"
export LEXAVAULT_SCHEMA_DB_USER=lvdict
export LEXAVAULT_SCHEMA_DB_PASSWORD="$password"
launch() {
  java -cp "$jar" -Dloader.main="$1" org.springframework.boot.loader.launch.PropertiesLauncher "${@:2}"
}
launch com.securedoc.install.SchemaStep apply >/dev/null
launch com.securedoc.install.DataDictionary "$mode" "$repo_root/docs/data"
