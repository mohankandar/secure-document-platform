#!/usr/bin/env bash
set -Eeuo pipefail

skip_tests=false
refresh_node_modules=false
production=false

usage() {
  cat <<'USAGE'
Usage: bash Build-LexaVault-All.sh [--skip-tests] [--refresh-node-modules] [--production]

  --skip-tests             Build backend modules without executing tests.
  --refresh-node-modules   Run npm ci even when node_modules already exists.
  --production             Jars that trust production licence keys only (task 0054). Without it, the local development
                           build (mvn -Pdev-build), which accepts the demo licence; never for a customer.
  -h, --help               Show this help.
USAGE
}

while (($#)); do
  case "$1" in
    --skip-tests) skip_tests=true ;;
    --refresh-node-modules) refresh_node_modules=true ;;
    --production) production=true ;;
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
printf '\nplatform-core: released com.documentplatform.platform:platform-bom 1.0.0 from GitHub Packages (server github-platform-core in ~/.m2/settings.xml; docs/install/BUILD.md).\n'

profile_args=()
if "$production"; then
  printf 'Licence keys: PRODUCTION build (no development key)\n'
else
  profile_args+=(-Pdev-build)
  printf 'Licence keys: development build (-Pdev-build; demo licence accepted; never for a customer)\n'
fi

overall_started=$SECONDS

cd -- "$backend_dir"
if "$skip_tests"; then
  run_step 'BACKEND - Maven clean install (tests skipped)' mvn clean install -DskipTests ${profile_args[@]+"${profile_args[@]}"}
else
  run_step 'BACKEND - Maven clean install (all modules)' mvn clean install ${profile_args[@]+"${profile_args[@]}"}
fi

# Task 0082: the error code guide for installations without internet, written by the infrastructure module's tests.
guide_pdf="$backend_dir/securedoc-infrastructure/target/LexaVault-error-codes.pdf"
if [[ -f "$guide_pdf" ]]; then
  printf '  error code guide: %s\n' "$guide_pdf"
elif "$skip_tests"; then
  printf '  error code guide (PDF): not made, because the tests were skipped.\n'
else
  printf 'The error code guide was not made: %s\n' "$guide_pdf" >&2
  exit 1
fi

# Task 0041: the app's classpath carries platform-core's identity modules, licensing (task 0057) and nothing else of
# platform-core. Task 0054 decision 1: and what platform-identity 1.0.0 itself needs (outbound rules, error shape).
# Setup, with platform-crypto and platform-integration, runs from its own jar (securedoc-setup).
printf '\nBACKEND - app jar classpath check (task 0041)\n'
app_jar="$backend_dir/securedoc-api/target/securedoc-api-0.1.0-SNAPSHOT.jar"
platform_libs="$(jar tf "$app_jar" | sed -n 's#^BOOT-INF/lib/\(platform-[^/]*\.jar\)$#\1#p')"
not_allowed="$(printf '%s\n' "$platform_libs" | grep -Ev '^platform-(crypto-spring|crypto|erase-log-spring|erase-log|identity-core|identity-jdbc|identity-spring|licensing-spring|licensing|monitoring-spring|monitoring|notifications-spring|notifications|outbound-spring|outbound|errors)-[0-9][^-]*\.jar$' | grep -v '^$' || true)"
if [[ -n "$not_allowed" ]]; then
  printf 'The app jar carries platform-core modules it may not: %s\n' "$not_allowed" >&2
  exit 1
fi
printf '  app jar platform-core modules: %s\n' "$(printf '%s ' $platform_libs)"
[[ -f "$backend_dir/securedoc-setup/target/securedoc-setup-0.1.0-SNAPSHOT.jar" ]] || { printf 'The setup jar was not built (backend/securedoc-setup).\n' >&2; exit 1; }

# Task 0054: both jars say what this build was, and a production jar holds no development key.
for module in securedoc-api securedoc-setup; do
  jar_file="$backend_dir/$module/target/$module-0.1.0-SNAPSHOT.jar"
  check_dir="$(mktemp -d)"
  (cd -- "$check_dir" && jar xf "$jar_file" META-INF/platform/licence-build.properties META-INF/platform/licence-keys.json)
  build_text="$(cat "$check_dir/META-INF/platform/licence-build.properties" 2>/dev/null || true)"
  keys_text="$(cat "$check_dir/META-INF/platform/licence-keys.json" 2>/dev/null || true)"
  rm -rf -- "$check_dir"
  [[ -n "$build_text" ]] || { printf '%s jar: licence-build.properties is missing.\n' "$module" >&2; exit 1; }
  if printf '%s%s' "$build_text" "$keys_text" | grep -Eq 'development-keys|"development"|dev-lexavault'; then development=true; else development=false; fi
  if "$production" && "$development"; then
    printf '%s jar: a production build must not hold a development licence key.\n' "$module" >&2
    exit 1
  fi
  if ! "$production" && { ! "$development" || [[ -z "$keys_text" ]]; }; then
    printf '%s jar: the development build does not hold the development licence key.\n' "$module" >&2
    exit 1
  fi
  if "$production" && [[ -z "$keys_text" ]]; then
    printf '  %s jar: no licence-keys.json yet: this build refuses every licence (docs/install/BUILD.md).\n' "$module"
  fi
done
printf '  licence keys checked in both jars\n'

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
