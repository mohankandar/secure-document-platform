# LexaVault Scanner Intake E2E — R2 Implementation Candidate

**Date:** 2026-09-10  
**Status:** IMPLEMENTED CANDIDATE / LOCAL BUILD + E2E VALIDATION REQUIRED  
**Base:** Scanner-E2E-R1; Product Owner reported the R1 `build-all.cmd` gate PASS.

## Purpose

R2 completes the planned software-hardening pass before the single full local Scanner Intake E2E validation. It does not change the product boundary: one operator-entered business context + one physical scan job + one consolidated PDF, followed by explicit Submit into the existing LexaVault processing lifecycle.

## R2 changes over R1

- Hardened browser -> loopback Scanner Bridge requests with exact configured Origin checks and a per-process ephemeral `X-LexaVault-Bridge-Token` requirement on mutating `/api/v1/**` requests.
- Added automatic browser-token refresh across Scanner Bridge restart using a new bridge process instance ID.
- Added Scanner Bridge application version/process provenance to safe diagnostics.
- Added local durable-storage admission checks and configurable maximum scan-PDF size.
- Simulator test-PDF acquisition now streams to a `.part` file, enforces the size ceiling while writing, fsyncs, and atomically publishes only a complete file.
- Added privacy-safe delivery diagnostics for QUEUED/SUBMITTING/ATTENTION_REQUIRED work.
- Added explicit requeue of retained ATTENTION_REQUIRED work using the same scanner submission identity so the delivery worker can reconcile before resend.
- Preserved the business-user workflow: configured identifiers -> Start Scan -> one consolidated PDF -> Submit -> Start Next Package.
- Preserved the existing common LexaVault Business Record, validation, processing, Manual Review, approval/policy, export, Dashboard and reporting path.

## Security boundary clarification

The R2 bridge request token is an anti-CSRF/local-browser request control. It is not a human identity credential and does not replace Scanner Bridge -> LexaVault machine/service authentication.

The following remain explicit production gates and do not block simulator software acceptance:

- physical TWAIN/WIA/vendor adapter and scanner certification;
- Windows Service/installer, service account and filesystem ACL hardening;
- production hosted-origin/private-network browser/PNA validation;
- trusted per-human operator delegation/attribution across browser -> bridge -> machine intake;
- final production throughput/concurrency/capacity hardening.

Future Document Intelligence auto-population remains future scope and is not implemented in Scanner Bridge.

## Validation completed in this packaging environment

- R1 full build: reported PASS by Product Owner on workstation.
- R2 `application.yml`: YAML parse PASS.
- R2 Scanner Bridge POM: XML parse PASS.
- Changed Scanner frontend TypeScript/TSX syntax transpilation: PASS.
- Scanner Bridge/UI source scan: no `firstName`, `lastName`, `metadataFields`, or `businessMetadata` parallel business schema references.
- Manual Upload source scan: no duplicate `Scan Documents` action.
- Focused Scanner source scan: no TODO/FIXME/HACK/XXX markers.

Maven is not installed in this packaging environment, so R2's authoritative full build/test gate must be run on the Product Owner workstation using `build-all.cmd`. R2 is not VERIFIED/FROZEN until local E2E validation and focused independent verification are accepted.

## Next gate

Run one complete local validation using `docs/testing/SCANNER_INTAKE_E2E_LOCAL_TEST.md`. If a defect is found, create Scanner-E2E-R3 rather than overwriting this R2 checkpoint.
