#!/usr/bin/env bash
# LexaVault's setup command (task 0037): platform-core's setup for product LEXAVAULT, with the files LexaVault ships
# (trusted keys, product-roles.json) passed for you. See docs/install/SETUP.md.
#
# Usage: scripts/Setup-LexaVault.sh plan|apply --licence licence.json --signin signin.json [setup options...]
#   Extra options are passed to setup as they are (--config-out, --run-log, --remove-unlisted-grants,
#   --replace-connection).
# Database settings in the environment only (never printed):
#   PLATFORM_SETUP_DB_URL, PLATFORM_SETUP_DB_USER, PLATFORM_SETUP_DB_PASSWORD
# Optional: LEXAVAULT_TRUSTED_KEYS (default install/trusted-keys.json, shipped with task 0038; the demo uses
#   install/demo/development-trusted-keys.json), LEXAVAULT_PRODUCT_ROLES (default install/product-roles.json),
#   LEXAVAULT_SETUP_JAR (default backend/securedoc-setup/target/securedoc-setup-0.1.0-SNAPSHOT.jar: setup runs from its own
#   jar, task 0041, because it brings platform-crypto and platform-integration, which the app's classpath may not carry).
# Customer keys: --no-customer-keys is passed; LexaVault encrypts nothing with platform-core yet, and product-roles.json
#   says "customerKeys": "optional" (task 0041). Not wired yet: notifications, integration client bindings.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="${LEXAVAULT_SETUP_JAR:-$repo_root/backend/securedoc-setup/target/securedoc-setup-0.1.0-SNAPSHOT.jar}"
keys="${LEXAVAULT_TRUSTED_KEYS:-$repo_root/install/trusted-keys.json}"
roles="${LEXAVAULT_PRODUCT_ROLES:-$repo_root/install/product-roles.json}"
[[ -f "$jar" ]] || { echo "Setup jar not found: $jar (build it first)." >&2; exit 2; }
if [[ ! -f "$keys" ]]; then
  echo "Trusted-keys file not found: $keys. The production keys file comes with task 0038; for the demo set" >&2
  echo "LEXAVAULT_TRUSTED_KEYS=install/demo/development-trusted-keys.json." >&2
  exit 2
fi
[[ $# -ge 1 ]] || { echo "Usage: $0 plan|apply --licence FILE --signin FILE [options]" >&2; exit 2; }
command="$1"; shift
exec java -cp "$jar" -Dloader.main=com.documentplatform.setup.SetupCommand \
  org.springframework.boot.loader.launch.PropertiesLauncher "$command" "$@" \
  --product LEXAVAULT --keys "$keys" --product-roles "$roles" --no-customer-keys
