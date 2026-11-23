#!/usr/bin/env bash
# Task 0042: the product-name guard (independence rule, CLAUDE.md). Fails when another product's name (Vero, SDV,
# secure-document-vault) appears in tracked code, SQL, JSON, XML, YAML, properties, scripts or install guides.
#
# Not checked, and why:
#   - docs/            history: task files, reports, designs (they record what was decided, names included);
#   - CLAUDE.md        the working instructions, which state the rule itself and name the known exception;
#   - the known exception, to be removed in switch-over part 3 (CLAUDE.md):
#       backend/securedoc-infrastructure/src/main/java/.../delivery/SdvDeliveryAdapter.java
#       its tests: backend/securedoc-api/src/test/java/.../delivery/SdvDeliveryAdapter*Test.java
#   - this script (it has to name what it looks for).
# Usage: scripts/ci/check-product-names.sh   (from the repository root; exit 0 clean, 1 names found)
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

pattern='Vero|SDV|secure-document-vault'
mapfile -t files < <(git ls-files \
  | grep -E '\.(java|kt|ts|tsx|js|mjs|cjs|sql|json|xml|ya?ml|properties|ps1|sh|cmd|csv|html|css|md)$' \
  | grep -vE '^docs/' \
  | grep -vE '^CLAUDE\.md$' \
  | grep -vE '^scripts/ci/check-product-names\.sh$' \
  | grep -vE '/delivery/SdvDeliveryAdapter\.java$' \
  | grep -vE '/delivery/SdvDeliveryAdapter[A-Za-z]*Test\.java$')

out="$(mktemp)"
trap 'rm -f "$out"' EXIT
if ((${#files[@]})); then grep -nE "$pattern" -- "${files[@]}" > "$out" || true; fi
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
