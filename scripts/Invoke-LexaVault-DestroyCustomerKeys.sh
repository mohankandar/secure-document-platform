#!/usr/bin/env bash
# Customer exit: destroys the customer's keys (task 0060, docs/install/OFFBOARDING.md). It CAN NOT be undone: every stored
# document of the customer, backups included, becomes unreadable. Platform-core's "crypto destroy-customer-keys".
#
# Usage: scripts/Invoke-LexaVault-DestroyCustomerKeys.sh --tenant-id TEN-... --operator "Your Name"
#          [--backup-retention-days 35] [--confirm TEN-...] [--result-out FILE]
# Key service login (policy platform-crypto-offboard only) in the environment only: PLATFORM_SETUP_VAULT_*.
# Optional: LEXAVAULT_SETUP_JAR (default backend/securedoc-setup/target/securedoc-setup-0.1.0-SNAPSHOT.jar).
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="${LEXAVAULT_SETUP_JAR:-$repo_root/backend/securedoc-setup/target/securedoc-setup-0.1.0-SNAPSHOT.jar}"
[[ -f "$jar" ]] || { echo "Setup jar not found: $jar (build it first)." >&2; exit 2; }
exec java -cp "$jar" -Dloader.main=com.documentplatform.crypto.command.CryptoCommand \
  org.springframework.boot.loader.launch.PropertiesLauncher destroy-customer-keys "$@"
