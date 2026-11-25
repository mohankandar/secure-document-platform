#!/usr/bin/env bash
# Activates and names the customer's first entity at a new installation (task 0075, docs/install/SETUP.md). Run it after the
# schema step and setup, with the app's normal settings in the environment (database, storage, licence, key service).
# Running it again with the same values changes nothing. Exit codes: 0 done, 1 failed, 2 refused.
#
# Usage: scripts/Invoke-LexaVault-FirstEntity.sh SLOT_ID "Entity name" [app options...]
# Optional: LEXAVAULT_JAR (default backend/securedoc-api/target/securedoc-api-0.1.0-SNAPSHOT.jar).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="${LEXAVAULT_JAR:-$repo_root/backend/securedoc-api/target/securedoc-api-0.1.0-SNAPSHOT.jar}"
[[ -f "$jar" ]] || { echo "Backend jar not found: $jar (build it first)." >&2; exit 2; }
[[ $# -ge 2 ]] || { echo "Usage: $0 SLOT_ID \"Entity name\" [app options...]" >&2; exit 2; }
slot="$1"; name="$2"; shift 2
# A port of its own: this process serves nobody and takes no work.
exec java -jar "$jar" --activate-first-entity "--entity-slot=$slot" "--entity-name=$name" \
  --server.port=0 --server.address=127.0.0.1 --spring.main.banner-mode=off "$@"
