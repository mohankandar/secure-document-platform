#!/usr/bin/env bash
# Task 0087: checks a destination's published profile and OpenAPI file with platform-core's contract kit
# (ProfileValidator, ProfileContract.checkAgainstSpec) and that LexaVault can use the profile. The files are the
# destination owner's, from a release tag; they are not kept in this repository. For example, from a tag:
#   git -C <its repository> show <tag>:<path of the profile> > /tmp/contract/profile.json
#   git -C <its repository> show <tag>:<path of the specification> > /tmp/contract-spec/openapi.json
# The checksums are printed for the report. Usage: Test-LexaVault-DestinationContract.sh <profile.json> <openapi.json>
set -euo pipefail
(($# == 2)) || { echo "usage: $0 <profile.json> <openapi.json>" >&2; exit 2; }
profile="$(realpath "$1")"
openapi="$(realpath "$2")"
sha256sum "$profile" "$openapi"
command -v cygpath >/dev/null && { profile="$(cygpath -w "$profile")"; openapi="$(cygpath -w "$openapi")"; }
export LEXAVAULT_DESTINATION_PROFILE="$profile" LEXAVAULT_DESTINATION_OPENAPI="$openapi"
cd "$(dirname "$0")/../backend"
mvn -B -Pdev-build test -pl securedoc-infrastructure -am -Dtest=DestinationContractTest -Dsurefire.failIfNoSpecifiedTests=false
