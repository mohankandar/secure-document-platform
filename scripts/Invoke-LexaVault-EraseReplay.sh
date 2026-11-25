#!/usr/bin/env bash
# The erase replay after a restore from a backup (task 0060, docs/install/ERASE_LOG.md): does every erasure made after the
# backup again, checks that nothing in the erase log can be read, prints a report and exits 0 (done) or 1 (not complete;
# fix the cause and run it again). Run it with the app's normal settings in the environment (database, storage, key
# service, PLATFORM_ERASELOG_LOCATION) while every LexaVault server is stopped.
#
# Usage: scripts/Invoke-LexaVault-EraseReplay.sh [--seal] [app options...]
#   --seal   seal an existing erase log once instead (it was written before the key service was set up)
# Optional: LEXAVAULT_JAR (default backend/securedoc-api/target/securedoc-api-0.1.0-SNAPSHOT.jar).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="${LEXAVAULT_JAR:-$repo_root/backend/securedoc-api/target/securedoc-api-0.1.0-SNAPSHOT.jar}"
[[ -f "$jar" ]] || { echo "Backend jar not found: $jar (build it first)." >&2; exit 2; }
option=--erase-replay
if [[ "${1:-}" == "--seal" ]]; then option=--erase-log-seal; shift; fi
# No scheduled work, and a port of its own: this process serves nobody.
exec java -jar "$jar" "$option" --server.port=0 --spring.main.banner-mode=off "$@"
