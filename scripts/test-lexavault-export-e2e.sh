#!/usr/bin/env bash
# Task 0035 (test layer L5): the export end to end against the folder destination. The twin of
# scripts/Test-LexaVault-ExportE2E.ps1 for Linux and macOS (bash, docker, java 21, node 22).
#
# Starts a THROWAWAY PostgreSQL container (lexavault-0035-pg, 127.0.0.1:55435) with a generated password (never
# printed), runs the @e2e-export Playwright suite (it starts the backend jar, a mock Ollama and a Vite dev server
# itself), and removes only that container. Never touches securedoc-postgres or any other container named in CLAUDE.md.
#
# Usage: scripts/test-lexavault-export-e2e.sh [--jar PATH] [--grep TEXT]
# Exits 0 only when every test passed. Note: the suite uses the msedge channel (playwright.export-e2e.config.ts);
# on a machine without Microsoft Edge, install it or run `npx playwright install msedge` first.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="$repo_root/backend/securedoc-api/target/securedoc-api-0.1.0-SNAPSHOT.jar"
grep_arg=""
while [ $# -gt 0 ]; do
  case "$1" in
    --jar) jar="$2"; shift 2 ;;
    --grep) grep_arg="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -f "$jar" ] || { echo "Backend jar not found: $jar (build it first: cd backend && mvn clean install -Pdev-build)" >&2; exit 1; }

container="lexavault-0035-pg"
case "$container" in securedoc-postgres|sdv-postgres|sdv-vault|lexa-keycloak|lexa-keycloak-db) echo "protected name" >&2; exit 1 ;; esac
if [ -n "$(docker ps -a --filter "name=^/${container}$" --format '{{.Names}}')" ]; then
  echo "Container $container already exists; remove it first (docker rm -f $container)." >&2; exit 1
fi

run_dir="${TMPDIR:-/tmp}/lexavault-0035/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$run_dir"
echo "Run folder: $run_dir"
started=0
cleanup() {
  if [ -f "$run_dir/backend.pid" ]; then kill -9 "$(cat "$run_dir/backend.pid")" 2>/dev/null || true; fi
  if [ "$started" = 1 ]; then docker rm -f "$container" >/dev/null && echo "Removed throwaway container $container"; fi
}
trap cleanup EXIT

db_password="$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
docker run -d --name "$container" -e POSTGRES_DB=securedoc -e POSTGRES_USER=securedoc \
  -e "POSTGRES_PASSWORD=$db_password" -p "127.0.0.1:55435:5432" postgres:16-alpine >/dev/null
started=1
for _ in $(seq 1 60); do docker exec "$container" pg_isready -U securedoc -d securedoc >/dev/null 2>&1 && break; sleep 1; done
docker exec "$container" pg_isready -U securedoc -d securedoc >/dev/null || { echo "throwaway Postgres did not become ready" >&2; exit 1; }
sleep 2

export LEXAVAULT_E2E_RUN_DIR="$run_dir" LEXAVAULT_E2E_JAR="$jar" LEXAVAULT_E2E_PROFILE_DIR="$repo_root/test-tools/export-e2e"
export LEXAVAULT_E2E_DB_CONTAINER="$container" LEXAVAULT_E2E_DB_PORT=55435 LEXAVAULT_E2E_DB_PASSWORD="$db_password"
export LEXAVAULT_E2E_APP_PORT=18035 LEXAVAULT_E2E_OLLAMA_PORT=11535
export LEXAVAULT_E2E_PROOF_DIR="${LEXAVAULT_E2E_PROOF_DIR:-$repo_root/docs/work/0036-proof}" LEXAVAULT_E2E_RESULTS="$run_dir/results.json"

cd "$repo_root/frontend/securedoc-ui"
args=(node_modules/@playwright/test/cli.js test --config playwright.export-e2e.config.ts)
[ -n "$grep_arg" ] && args+=(--grep "$grep_arg")
if node "${args[@]}"; then echo "PASS"; else echo "FAIL"; exit 1; fi
