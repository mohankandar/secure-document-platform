# LexaVault Scanner Intake E2E — R3 Implementation Candidate

**Date:** 2026-09-10  
**Status:** IMPLEMENTED CANDIDATE / LOCAL BUILD + E2E VALIDATION REQUIRED  
**Base:** Scanner-E2E-R2 software-completion candidate.

## Why R3 exists

Senior review found one trust-boundary defect in R2 before the full local E2E pass: Scanner Bridge was described and secured as a loopback-only service, but `server.address`, `SECUREDOC_SCAN_BRIDGE_BIND_ADDRESS`, or the development launcher could still be configured to bind to a wildcard/LAN interface. The R2 browser request nonce is intentionally only a browser-to-loopback anti-CSRF control, so allowing non-loopback exposure would invalidate that boundary.

R3 corrects that defect without changing Scanner Intake product behavior.

## R3 changes over R2

- Added runtime `LoopbackBindAddressGuard` validation. Scanner Bridge startup fails closed unless every address resolved from `server.address` is loopback.
- Wildcard binds (`0.0.0.0`, `::`), LAN/non-loopback addresses, blank values, and unresolvable bind names are rejected.
- Added matching `Start-LexaVault-ScannerBridge.ps1` preflight validation so the normal local launcher rejects invalid binds before process creation.
- Added focused loopback-guard regression tests.
- Removed document SHA-256 values from Scanner Bridge logs. SHA-256 remains in durable scanner state, manifest/checksum/idempotency flows, and LexaVault scanner provenance because those are required by the accepted contract.

## Preserved boundaries

R3 does not change:

- one scan job = one consolidated PDF;
- SIMULATOR / PHYSICAL acquisition semantics;
- tenant-configured Business Identifier Definitions;
- durable queue/restart/retry behavior;
- scanner machine identity and existing LexaVault manifest-first intake;
- common Business Record, OCR/AI/redaction, Manual Review, approval, export, Dashboard, and Reports lifecycle;
- deferred physical scanner certification, Windows Service/installer and filesystem ACL hardening, production hosted-origin/PNA validation, trusted per-human operator delegation, or final throughput/capacity certification.

## Verification available in the packaging environment

- Source-level review of the new runtime guard and launcher preflight: PASS.
- Focused source scan confirms Scanner Bridge log statements no longer emit `sha256={...}` values: PASS.
- Full Maven build/tests: NOT RUN in this environment because Maven is not installed and outbound dependency download is unavailable.
- Frontend npm build: NOT RUN; dependency installation cannot complete in this offline environment.

## Next gate

Run `build-all.cmd` on the Product Owner workstation, then execute the complete `docs/testing/SCANNER_INTAKE_E2E_LOCAL_TEST.md` procedure, including the new non-loopback bind refusal check. If the local E2E pass succeeds, package R3 for focused Kiro verification. Do not mark Scanner Intake VERIFIED/FROZEN until that independent checkpoint is accepted.
