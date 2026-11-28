#!/usr/bin/env bash
# Task 0114: LexaVault's package, from a finished build: the jars, the screens, the install files and the install guides.
# What a customer installs from, and what the install proof installs (docs/install/INSTALL.md). Nothing else from the
# repository and no secret: the install proof checks that.
#
#   bash scripts/package/make-package.sh <output folder>
#
# Needs: the backend built (mvn install), and the screens built (frontend/securedoc-ui: npm run build).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out="${1:?usage: make-package.sh <output folder>}"
version="$(git -C "$root" rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"
target() { echo "$root/backend/$1/target/$1-0.1.0-SNAPSHOT.jar"; }

for jar in securedoc-api securedoc-setup securedoc-scan-bridge; do
  [[ -f "$(target "$jar")" ]] || { echo "Not built: $(target "$jar")" >&2; exit 2; }
done
[[ -f "$root/frontend/securedoc-ui/dist/index.html" ]] || { echo "The screens are not built (npm run build)." >&2; exit 2; }

rm -rf -- "$out"
mkdir -p -- "$out"/{ui,install,docs}
cp "$(target securedoc-api)" "$out/lexavault.jar"
cp "$(target securedoc-setup)" "$out/lexavault-setup.jar"
cp "$(target securedoc-scan-bridge)" "$out/lexavault-scan-bridge.jar"
cp -R "$root/frontend/securedoc-ui/dist/." "$out/ui/"
cp -R "$root/install/." "$out/install/"
cp "$root"/docs/install/*.md "$out/docs/"
printf 'LexaVault %s\nbuilt %s\n' "$version" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$out/VERSION"
echo "Package: $out ($version)"
