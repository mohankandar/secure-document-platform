#!/usr/bin/env bash
set -Eeuo pipefail

skip_tests=false
refresh_node_modules=false

usage() {
  cat <<'USAGE'
Usage: bash Build-LexaVault-All.sh [--skip-tests] [--refresh-node-modules]

  --skip-tests             Build backend modules without executing tests.
  --refresh-node-modules   Run npm ci even when node_modules already exists.
  -h, --help               Show this help.
USAGE
}

while (($#)); do
  case "$1" in
    --skip-tests) skip_tests=true ;;
    --refresh-node-modules) refresh_node_modules=true ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'Unknown argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
backend_dir="$project_root/backend"
frontend_dir="$project_root/frontend/securedoc-ui"

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    printf "Required command '%s' was not found in PATH.\n" "$1" >&2
    exit 1
  }
}

run_step() {
  local name="$1"
  shift
  printf '\n============================================================\n%s\n============================================================\n' "$name"
  local started=$SECONDS
  if "$@"; then
    printf '\n%s PASSED (%s sec)\n' "$name" "$((SECONDS - started))"
  else
    local status=$?
    printf '\n%s FAILED (exit code %s)\n' "$name" "$status" >&2
    exit "$status"
  fi
}

printf '\n=== LexaVault Full Build (Ubuntu/WSL) ===\nProject root: %s\n' "$project_root"
[[ -f "$backend_dir/pom.xml" ]] || { printf 'Backend pom.xml not found at: %s\n' "$backend_dir" >&2; exit 1; }
[[ -f "$frontend_dir/package.json" ]] || { printf 'Frontend package.json not found at: %s\n' "$frontend_dir" >&2; exit 1; }

for cmd in java mvn node npm npx; do
  require_command "$cmd"
done

printf '\nJava:\n'
java -version
printf '\nMaven:\n'
mvn -version
printf '\nNode: %s\nnpm:  %s\n' "$(node --version)" "$(npm --version)"
printf '\nEnterprise identity: external Maven dependency com.documentplatform.identity:*:0.2.0\n'
printf 'This build does not build enterprise-identity-parent; Maven resolves it from the configured/local artifact repository.\n'

overall_started=$SECONDS

cd -- "$backend_dir"
if "$skip_tests"; then
  run_step 'BACKEND - Maven clean install (tests skipped)' mvn clean install -DskipTests
else
  run_step 'BACKEND - Maven clean install (all modules)' mvn clean install
fi

cd -- "$frontend_dir"
if "$refresh_node_modules" || [[ ! -d node_modules ]]; then
  run_step 'FRONTEND - npm ci' npm ci
else
  printf '\nFRONTEND - node_modules already exists; skipping npm ci.\n'
  printf 'Use --refresh-node-modules to force a clean npm ci.\n'
fi

run_step 'FRONTEND - TypeScript check' npx tsc --noEmit
run_step 'FRONTEND - UI framework contract' npm run test:ui-framework
run_step 'FRONTEND - Vite production build' npm run build

printf '\n============================================================\nLEXAVAULT FULL BUILD PASSED\n============================================================\n'
printf 'Backend:  Maven clean install - PASS\nFrontend: TypeScript + Vite build - PASS\n'
printf 'Total:    %s seconds\n\n' "$((SECONDS - overall_started))"
