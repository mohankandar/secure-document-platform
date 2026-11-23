#!/usr/bin/env bash
# Task 0042, strict since task 0045: the product-name guard (independence rule, CLAUDE.md). Fails when another
# product's name appears in tracked code, SQL, JSON, XML, YAML, properties, scripts, styles or install guides.
# The names are in scripts/ci/product-names.txt (one extended regular expression); the frontend's "no other product
# on a screen" test reads the same file.
#
# Not checked, and why:
#   - docs/            history: task files, reports, designs (they record what was decided, names included);
#   - CLAUDE.md        the working instructions, which state the rule itself and name the known exception;
#   - the known exception, to be removed in switch-over part 3 (CLAUDE.md):
#       backend/securedoc-infrastructure/src/main/java/.../delivery/SdvDeliveryAdapter.java
#       its tests: backend/securedoc-api/src/test/java/.../delivery/SdvDeliveryAdapter*Test.java
#     (other files may name the class SdvDeliveryAdapter itself, nothing else);
#   - database history that can't change: changelog 017 (Liquibase keeps its file name and checksum, so the master
#     changelog's line naming that file is allowed too), and migration 046 with its test, which rename the old values;
#   - the owner's Docker containers sdv-postgres and sdv-vault: safety lists name them so scripts never touch them;
#   - this script and the names file (they have to name what they look for).
# Usage: scripts/ci/check-product-names.sh   (from the repository root; exit 0 clean, 1 names found)
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

pattern="$(head -n 1 scripts/ci/product-names.txt | tr -d '\r')"
allowed='SdvDeliveryAdapter|sdv-postgres|sdv-vault|017-sdv-delivery\.xml'
mapfile -t files < <(git ls-files \
  | grep -E '\.(java|kt|ts|tsx|js|mjs|cjs|sql|json|xml|ya?ml|properties|ps1|sh|cmd|csv|html|css|md)$' \
  | grep -vE '^docs/' \
  | grep -vE '^CLAUDE\.md$' \
  | grep -vE '^scripts/ci/check-product-names\.sh$' \
  | grep -vE '/delivery/SdvDeliveryAdapter\.java$' \
  | grep -vE '/delivery/SdvDeliveryAdapter[A-Za-z]*Test\.java$' \
  | grep -vE '/db/changelog/017-sdv-delivery\.xml$' \
  | grep -vE '/db/changelog/046-document-store-names\.xml$' \
  | grep -vE '/DocumentStoreNamesMigrationPostgresTest\.java$')

# Fail closed: no files means git could not list the repository (wrong shell, wrong folder), not a clean result.
if ((${#files[@]} == 0)); then
  echo "Product-name guard: no files to check; git could not list this repository. Run it with Git Bash from the repository."
  exit 2
fi

out="$(mktemp)"
trap 'rm -f "$out"' EXIT
# Every line with a name, after the allowed words are taken out of it.
if ((${#files[@]})); then
  grep -nE "$pattern" -- "${files[@]}" | sed -E "s/$allowed//g" | grep -E "^[^:]+:[0-9]+:.*($pattern)" > "$out" || true
fi
found=$(cut -d: -f1 "$out" | sort -u | grep -c . || true)
if ((found > 0)); then
  echo "Product-name guard: another product's name in $found file(s) (independence rule, CLAUDE.md):"
  cut -d: -f1 "$out" | sort | uniq -c | sort -rn
  echo
  echo "First matches:"
  head -n 40 "$out"
  exit 1
fi
echo "Product-name guard: clean (${#files[@]} files checked)."
